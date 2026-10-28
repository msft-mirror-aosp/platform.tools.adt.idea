/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.rendering.classloading

import com.android.annotations.concurrency.GuardedBy
import com.android.annotations.concurrency.Slow
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.URLUtil
import com.intellij.util.lang.UrlClassLoader
import java.io.File
import java.lang.ref.SoftReference
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

/**
 * Cached external library state for a specific set of `libraryPath -> "$libraryPath@$modificationTime"` identities.
 *
 * Reusing this entry across class loaders in both Android Studio (`ModuleClassLoaderImpl`) and standalone preview rendering
 * (`DefaultLoader`) avoids re-creating the [UrlClassLoader] over external dependency JARs and caches negative lookups ([missingClasses]) so
 * repeated probes for classes absent from external libraries do not re-scan all library JARs.
 */
class ExternalLibraryClassLoaderEntry(val classLoader: UrlClassLoader) {
  @Volatile private var missingClassesRef = SoftReference<MutableSet<String>>(null)

  fun getMissingClasses(): MutableSet<String> {
    missingClassesRef.get()?.let {
      return it
    }
    return ConcurrentHashMap.newKeySet<String>().also { missingClassesRef = SoftReference(it) }
  }

  /**
   * Creates a [DelegatingClassLoader.Loader] backed by [classLoader] that:
   * 1. Short-circuits repeated negative lookups via [getMissingClasses], and
   * 2. Resolves the originating library identity from [libraryIdentities] whenever a class is loaded on disk, invoking
   *    [onLibraryIdentityResolved] so the caller can store the ASM-transformed bytecode in [ClassBinaryCache] under that library's
   *    identity.
   */
  fun createCachingJarLoader(
    libraryIdentities: Map<String, String>,
    onLibraryIdentityResolved: (fqcn: String, libraryIdentity: String) -> Unit,
  ): DelegatingClassLoader.Loader {
    // Loads raw .class bytes from external library JARs (or directories) and records which library
    // provided each class so its transformed bytecode can later be stored in `ClassBinaryCache`
    // under that library's identity (`"$libraryPath@$modificationTime"`).
    val underlyingJarLoader =
      ClassLoaderLoader(classLoader) { fqcn, path, _ ->
        ExternalLibraryClassLoaderPool.resolveLibraryPath(path, fqcn)?.let { libraryPath ->
          // Associate `fqcn` with its originating library's `"$libraryPath@$modificationTime"`
          // identity so the outer `ListeningLoader` can insert the ASM-transformed bytes into
          // `ClassBinaryCache` once transformation completes.
          libraryIdentities[libraryPath]?.let { libraryIdentity ->
            onLibraryIdentityResolved(fqcn, libraryIdentity)
          }
        }
      }

    return object : DelegatingClassLoader.Loader {
      override fun loadClass(fqcn: String): ByteArray? {
        val missingClasses = getMissingClasses()
        if (fqcn in missingClasses) return null
        val bytes = underlyingJarLoader.loadClass(fqcn)
        if (bytes == null) {
          missingClasses.add(fqcn)
        }
        return bytes
      }
    }
  }
}

/**
 * Maximum number of distinct library classpath configurations cached in the pool.
 *
 * Standalone renders and IDE preview sessions typically use 1–3 distinct configurations (e.g. main module, test module, dynamic feature);
 * capping at 8 prevents memory accumulation across repeated dependency updates while allowing common multi-module configurations to stay
 * warm.
 */
private const val MAX_CACHED_CONFIGURATIONS = 8

/**
 * Shared pool of external-library [UrlClassLoader] instances and [UrlClassLoader.CachePool] used by both Android Studio
 * (`ModuleClassLoaderUtil`) and standalone preview rendering (`DefaultLoader`).
 */
object ExternalLibraryClassLoaderPool {
  private val externalLibraryCacheLock = Any()

  @GuardedBy("externalLibraryCacheLock") private var ourLoaderCachePool = UrlClassLoader.createCachePool()

  /** Tracks the latest `libraryPath -> "$libraryPath@$modificationTime"` identity observed for each library path. */
  @GuardedBy("externalLibraryCacheLock") private val lastKnownLibraryIdentities = HashMap<String, String>()

  /**
   * Path segments that identify project or sibling-module build outputs (Gradle subproject JARs under `build/` and IntelliJ/JPS module
   * outputs under `out/`) as opposed to immutable external dependency archives in Gradle/Maven caches.
   */
  private val BUILD_OUTPUT_PATH_SEGMENTS =
    listOf(
      "/build/intermediates/",
      "/build/libs/",
      "/build/outputs/",
      "/build/classes/",
      "/build/generated/",
      "/build/tmp/",
      "/out/production/",
      "/out/test/",
    )

  /**
   * Normalized filesystem paths of non-build-output library JARs that have been modified in-place on disk at least once (e.g. a local JAR
   * in `libs/` overwritten in place).
   *
   * Project and sibling-module build outputs are already excluded from [ourLoaderCachePool] upfront via [isMutableBuildOutputPath]. If any
   * other JAR path that was previously admitted to [ourLoaderCachePool] mutates in-place, it is recorded here so that only its first
   * mutation resets [ourLoaderCachePool] and subsequent updates only evict the affected [ExternalLibraryClassLoaderEntry].
   */
  @GuardedBy("externalLibraryCacheLock") private val mutatedLibraryPaths = HashSet<String>()

  @GuardedBy("externalLibraryCacheLock")
  private var externalLibraryCacheRef = SoftReference<ConcurrentHashMap<List<String>, ExternalLibraryClassLoaderEntry>>(null)

  /**
   * Returns a cached [ExternalLibraryClassLoaderEntry] for the given [externalLibraries] and their [libraryIdentities] (`libraryPath ->
   * "$libraryPath@$modificationTime"`), or creates and caches a new one if no matching entry exists.
   *
   * Because [externalLibraries] are resolved in order, the cache key is the ordered list of library identities corresponding to
   * [externalLibraries]. This ensures that two modules with identical dependencies listed in different classpath orders do not collide or
   * share a classloader with the wrong classpath precedence.
   *
   * The cache map is held via a [SoftReference] ([externalLibraryCacheRef]) so it can be reclaimed under memory pressure, and is bounded to
   * at most [MAX_CACHED_CONFIGURATIONS] entries to avoid accumulating stale classloaders across repeated dependency updates.
   */
  @Slow
  fun getOrCreateEntry(
    externalLibraries: List<Path>,
    libraryIdentities: Map<String, String>,
  ): ExternalLibraryClassLoaderEntry {
    val orderedIdentities = externalLibraries.map { path ->
      val normalized = FileUtil.toSystemIndependentName(path.toString())
      libraryIdentities[normalized] ?: normalized
    }
    return synchronized(externalLibraryCacheLock) {
      // Any superseded library identity removes all referencing entries from
      // `externalLibraryCacheRef`. Consequently, a cache hit here guarantees that every identity in
      // `orderedIdentities` is still current without performing O(N) identity-map updates on warm
      // preview refreshes.
      externalLibraryCacheRef.get()?.get(orderedIdentities)?.let {
        return@synchronized it
      }
      updateKnownLibraryIdentitiesLocked(externalLibraries, libraryIdentities)
      val map =
        externalLibraryCacheRef.get()
          ?: ConcurrentHashMap<List<String>, ExternalLibraryClassLoaderEntry>().also {
            externalLibraryCacheRef = SoftReference(it)
          }
      map.getOrPut(orderedIdentities) {
        if (map.size >= MAX_CACHED_CONFIGURATIONS) {
          map.clear()
        }
        ExternalLibraryClassLoaderEntry(createUrlClassLoaderLocked(externalLibraries, !SystemInfo.isWindows))
      }
    }
  }

  /**
   * Creates a [UrlClassLoader] over [paths] backed by the shared [ourLoaderCachePool] so ZIP central-directory and package-hash metadata
   * are reused across class loaders.
   */
  @Slow
  @JvmOverloads
  fun createUrlClassLoader(
    paths: List<Path>,
    allowLock: Boolean = !SystemInfo.isWindows,
  ): UrlClassLoader {
    val identities = computeLibraryIdentities(paths)
    return synchronized(externalLibraryCacheLock) {
      updateKnownLibraryIdentitiesLocked(paths, identities)
      createUrlClassLoaderLocked(paths, allowLock)
    }
  }

  @GuardedBy("externalLibraryCacheLock")
  private fun createUrlClassLoaderLocked(
    paths: List<Path>,
    allowLock: Boolean,
  ): UrlClassLoader {
    val mutatedPathsSnapshot = if (mutatedLibraryPaths.isEmpty()) emptySet() else HashSet(mutatedLibraryPaths)
    return UrlClassLoader.build()
      .files(paths)
      .useCache(ourLoaderCachePool) { path ->
        val normalized = FileUtil.toSystemIndependentName(path.toString())
        !isMutableBuildOutputPath(normalized) && normalized !in mutatedPathsSnapshot
      }
      .allowLock(allowLock)
      .get()
  }

  /**
   * Returns `true` if [normalizedPath] represents a mutable project or sibling-module build output (such as a Gradle subproject JAR under
   * `build/intermediates/` or `build/libs/`, an IntelliJ/JPS output under `out/production/`, or a non-archive class directory) rather than
   * an immutable external dependency archive.
   *
   * Excluding these paths from [ourLoaderCachePool] upfront via `useCache(ourLoaderCachePool, condition)` avoids ever populating—or needing
   * to reset—the shared [ourLoaderCachePool] when sibling modules are rebuilt in place. Note that `UrlClassLoader` still builds its own
   * per-instance `ClasspathCache` package index for every entry regardless of the `cachingCondition` predicate, and
   * [ExternalLibraryClassLoaderPool] reuses the resulting [ExternalLibraryClassLoaderEntry] across warm renders until a file timestamp
   * changes.
   */
  internal fun isMutableBuildOutputPath(normalizedPath: String): Boolean {
    if (!normalizedPath.endsWith(".jar", ignoreCase = true) && !normalizedPath.endsWith(".zip", ignoreCase = true)) {
      return true
    }
    return BUILD_OUTPUT_PATH_SEGMENTS.any { segment -> normalizedPath.contains(segment) }
  }

  /**
   * Tracks the latest `libraryPath -> "$libraryPath@$modificationTime"` identity for each path in [libraries] and evicts any cached
   * [ExternalLibraryClassLoaderEntry] referencing a superseded identity whenever a previously seen library path changes its identity on
   * disk.
   *
   * Why this is necessary: IntelliJ's `CachePoolImpl` (`UrlClassLoader.createCachePool()`) keys `loaderIndexCache` (package-hash indices)
   * and `manifestData` strictly by [Path] without modification timestamps and does not expose per-path removal. When a dependency JAR or
   * directory (such as a sibling Gradle module's `classes.jar`) is rebuilt in place at the same [Path]—for example after switching Git
   * branches or adding a new package to a library module—reusing a cached `IndexRegistrar` from [ourLoaderCachePool] causes the newly
   * constructed [UrlClassLoader] to inherit the stale package-hash index and fail with [ClassNotFoundException] for classes in newly added
   * packages.
   *
   * Project and sibling-module build outputs ([isMutableBuildOutputPath]) are excluded from [ourLoaderCachePool] upfront in
   * [createUrlClassLoaderLocked], so rebuilding them in place never populates or resets [ourLoaderCachePool]. If any other JAR path that
   * was previously eligible for [ourLoaderCachePool] mutates in-place on disk, it is recorded in [mutatedLibraryPaths] and
   * [ourLoaderCachePool] is reset once so subsequent loads exclude that path as well.
   */
  @GuardedBy("externalLibraryCacheLock")
  private fun updateKnownLibraryIdentitiesLocked(
    libraries: List<Path>,
    libraryIdentities: Map<String, String>,
  ) {
    val staleIdentities = HashSet<String>()
    var shouldResetCachePool = false
    for (path in libraries) {
      val normalized = FileUtil.toSystemIndependentName(path.toString())
      val identity = libraryIdentities[normalized] ?: normalized
      val previous = lastKnownLibraryIdentities.put(normalized, identity)
      if (previous != null && previous != identity) {
        staleIdentities.add(previous)
        if (!isMutableBuildOutputPath(normalized) && mutatedLibraryPaths.add(normalized)) {
          // This non-build-output archive was eligible for caching in `ourLoaderCachePool` prior to
          // its first in-place mutation; reset the shared pool once and exclude this path from
          // future shared-pool caching via `mutatedLibraryPaths`.
          shouldResetCachePool = true
        }
      }
    }
    if (shouldResetCachePool) {
      ourLoaderCachePool = UrlClassLoader.createCachePool()
    }
    if (staleIdentities.isNotEmpty()) {
      externalLibraryCacheRef.get()?.keys?.removeIf { key -> key.any { it in staleIdentities } }
    }
  }

  /**
   * Computes the `libraryPath -> "$libraryPath@$modificationTime"` identity map for [libraries] using [modificationTimeProvider] (which
   * defaults to reading [Files.getLastModifiedTime]).
   */
  @Slow
  @JvmOverloads
  fun computeLibraryIdentities(
    libraries: Iterable<Path>,
    modificationTimeProvider: (Path) -> Long = ::defaultModificationTime,
  ): Map<String, String> = libraries.associate { path ->
    val libraryPath = FileUtil.toSystemIndependentName(path.toString())
    val modificationTime = modificationTimeProvider(path)
    libraryPath to "$libraryPath@$modificationTime"
  }

  private fun defaultModificationTime(path: Path): Long =
  // A library that cannot be read is given a fixed identity: it would fail to load classes anyway.
  runCatching {
    Files.getLastModifiedTime(path).toMillis()
  }
    .getOrDefault(-1L)

  /**
   * Resolves the normalized system-independent library root path (either a `.jar` file path or a classpath directory path) from the
   * resource URL [path] reported by [ClassLoaderLoader].
   */
  fun resolveLibraryPath(path: String, onDiskFqcn: String? = null): String? {
    // `path` is typically the resource URL path inside a JAR (e.g.,
    // "file:/path/to/lib.jar!/com/example/Foo.class"). `splitJarUrl` extracts the part before
    // "!/" (the JAR path itself).
    val jarRoot = URLUtil.splitJarUrl(path)?.first
    if (jarRoot != null) {
      return normalizeUrlPath(jarRoot)
    }

    // If `splitJarUrl` returned null and `onDiskFqcn` is provided, the class was loaded from a
    // directory on the classpath rather than a JAR (for example in unit tests); strip the
    // relative `.class` path suffix to recover the library directory root.
    if (onDiskFqcn != null) {
      val rawFilePath = normalizeUrlPath(path)
      val classRelativePath = "/" + onDiskFqcn.replace('.', '/') + ".class"
      if (rawFilePath.endsWith(classRelativePath)) {
        return rawFilePath.removeSuffix(classRelativePath)
      }
    }
    return null
  }

  /**
   * Normalizes a URL or filesystem path string into a system-independent filesystem path that matches the keys of `libraryIdentities`
   * (created via `Path.toString()`).
   *
   * Uses [File] rather than [Path.of] so that `URL.getPath()` strings on Windows (which begin with a leading slash before the drive letter,
   * e.g. `"/C:/path/to/classes"`) are normalized by `WinNTFileSystem` instead of throwing `InvalidPathException`.
   */
  private fun normalizeUrlPath(rawPath: String): String =
    FileUtil.toSystemIndependentName(
      runCatching {
        if (rawPath.startsWith("file:", ignoreCase = true)) {
          File(URI.create(rawPath)).path
        } else {
          File(URLUtil.unescapePercentSequences(rawPath)).path
        }
      }
        .getOrElse {
          val extracted = URLUtil.extractPath(rawPath)
          File(URLUtil.unescapePercentSequences(extracted)).path
        }
    )

  @TestOnly internal fun getCachePoolForTest(): UrlClassLoader.CachePool = synchronized(externalLibraryCacheLock) { ourLoaderCachePool }

  @TestOnly
  fun clearForTest() {
    synchronized(externalLibraryCacheLock) {
      externalLibraryCacheRef.get()?.clear()
      externalLibraryCacheRef.clear()
      lastKnownLibraryIdentities.clear()
      mutatedLibraryPaths.clear()
      ourLoaderCachePool = UrlClassLoader.createCachePool()
    }
  }
}
