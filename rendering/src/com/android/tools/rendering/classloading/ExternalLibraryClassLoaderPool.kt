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
  private val ourLoaderCachePool = UrlClassLoader.createCachePool()
  private val externalLibraryCacheLock = Any()

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
  fun getOrCreateEntry(
    externalLibraries: List<Path>,
    libraryIdentities: Map<String, String>,
  ): ExternalLibraryClassLoaderEntry {
    val orderedIdentities = externalLibraries.map { path ->
      val normalized = FileUtil.toSystemIndependentName(path.toString())
      libraryIdentities[normalized] ?: normalized
    }
    return synchronized(externalLibraryCacheLock) {
      val map =
        externalLibraryCacheRef.get()
          ?: ConcurrentHashMap<List<String>, ExternalLibraryClassLoaderEntry>().also {
            externalLibraryCacheRef = SoftReference(it)
          }
      map.getOrPut(orderedIdentities) {
        if (map.size >= MAX_CACHED_CONFIGURATIONS) {
          map.clear()
        }
        ExternalLibraryClassLoaderEntry(createUrlClassLoader(externalLibraries))
      }
    }
  }

  /**
   * Creates a [UrlClassLoader] over [paths] backed by the shared [ourLoaderCachePool] so ZIP central-directory and package-hash metadata
   * are reused across class loaders.
   */
  @JvmOverloads
  fun createUrlClassLoader(
    paths: List<Path>,
    allowLock: Boolean = !SystemInfo.isWindows,
  ): UrlClassLoader {
    return UrlClassLoader.build().files(paths).useCache(ourLoaderCachePool) { true }.allowLock(allowLock).get()
  }

  /**
   * Computes the `libraryPath -> "$libraryPath@$modificationTime"` identity map for [libraries] using [modificationTimeProvider] (which
   * defaults to reading [Files.getLastModifiedTime]).
   */
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

  @TestOnly
  fun clearForTest() {
    synchronized(externalLibraryCacheLock) {
      externalLibraryCacheRef.get()?.clear()
      externalLibraryCacheRef.clear()
    }
  }
}
