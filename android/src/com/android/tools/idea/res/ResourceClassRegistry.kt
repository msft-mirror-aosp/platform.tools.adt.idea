/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.res

import com.android.annotations.concurrency.GuardedBy
import com.android.ide.common.resources.ResourceRepository
import com.android.tools.res.ResourceRepositoryManager
import com.android.tools.res.ids.ResourceClassGenerator
import com.android.tools.res.ids.ResourceIdManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TimeoutCachedValue
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.annotations.TestOnly

private val PACKAGE_TIMEOUT = 5.minutes

/** Returns whether this is an R class or one of its inner classes. */
private fun String.isRClassName(): Boolean {
  val lastDot = lastIndexOf('.')
  if (lastDot < 0) return false
  return endsWith(".R") || startsWith("R$", lastDot + 1)
}

/**
 * A project-wide registry for class lookup of resource classes (R classes).
 *
 * The registry is used concurrently: libraries are registered when class loaders are created and R classes are looked up from render
 * threads, while [clearCache] can be called from any thread (e.g. when the project roots change). All the state is guarded by [lock].
 */
@Service(Service.Level.PROJECT)
class ResourceClassRegistry @TestOnly constructor(private val packageTimeout: Duration) {
  constructor() : this(PACKAGE_TIMEOUT)

  private val lock = Any()
  @GuardedBy("lock") private val repoMap = WeakHashMap<ResourceRepository, ResourceRepositoryInfo>()
  @GuardedBy("lock") private var packages: TimeoutCachedValue<Set<String>> = createPackageCache()

  /**
   * Adds definition of a new R class to the registry. The R class will contain resources from the given repo and will be generated when
   * [findClassDefinition] is called with a class name that matches the [packageName] and the `repo` resource repository matches
   * [ResourceRepositoryManager.appResources] of the manager passed to [findClassDefinition].
   *
   * Note that the [ResourceClassRegistry] is a project-level component, so the same R class may be generated in different ways depending on
   * the [AppResourceRepository] of the module in question.
   */
  fun addLibrary(repo: ResourceRepository, idManager: ResourceIdManager, packageName: String?) {
    addLibraries(repo, idManager, listOf(packageName))
  }

  /** Same as [addLibrary] for all the [packageNames], registered atomically: [clearCache] can not drop only some of them. */
  fun addLibraries(repo: ResourceRepository, idManager: ResourceIdManager, packageNames: Collection<String?>) {
    synchronized(lock) { addLibrariesLocked(repo, idManager, packageNames) }
  }

  /**
   * Same as [addLibraries], and then returns the class definition of [className] for [repo] like [findClassDefinition]. The class is looked
   * up in the same atomic step as the registration, so a concurrent [clearCache] can not drop the registration first.
   */
  fun addLibrariesAndFindClassDefinition(
    repo: ResourceRepository,
    idManager: ResourceIdManager,
    packageNames: Collection<String?>,
    className: String,
  ): ByteArray? {
    if (!className.isRClassName()) return null
    val pkg = className.substringBeforeLast(".", "")
    val generator =
      synchronized(lock) {
        addLibrariesLocked(repo, idManager, packageNames)
        repoMap[repo]?.takeIf { pkg in it.packages }?.resourceClassGenerator
      } ?: return null
    // The generator does not depend on the registry, so this works even if the registry is cleared in the meantime.
    return synchronized(generator) { generator.generate(className) }
  }

  @GuardedBy("lock")
  private fun addLibrariesLocked(repo: ResourceRepository, idManager: ResourceIdManager, packageNames: Collection<String?>) {
    val names = packageNames.mapNotNull { it?.takeIf(String::isNotEmpty) }
    if (names.isEmpty()) return
    var info = repoMap[repo]
    if (info == null) {
      // Explicit cleanup for Disposable instead of waiting for GC. Registered before adding the repository, so that an already disposed
      // repository is never added.
      if (repo is Disposable && !Disposer.tryRegister(repo) { removeRepository(repo) }) return
      info = ResourceRepositoryInfo(repo, idManager)
      repoMap[repo] = info
    }
    if (info.packages.addAll(names)) {
      packages = createPackageCache() // Invalidate cache.
    }
  }

  /** Looks up a class definition for the given name, if possible */
  fun findClassDefinition(className: String, repositoryManager: ResourceRepositoryManager): ByteArray? {
    if (!className.isRClassName()) return null
    val pkg = className.substringBeforeLast(".", "")
    if (synchronized(lock) { pkg !in packages.get() }) return null
    // Read outside the lock, since this can create the repository.
    val appResources = repositoryManager.appResources
    val generator = synchronized(lock) { repoMap[appResources]?.takeIf { pkg in it.packages }?.resourceClassGenerator } ?: return null
    // ResourceClassGenerator caches the values it generates and is not thread safe.
    return synchronized(generator) { generator.generate(className) }
  }

  /**
   * Ideally, this method would not exist. But there are potential bugs in the caching mechanism. So, the method should be called when
   * rendering fails due to hard-to-explain causes like NoSuchFieldError.
   *
   * @see ResourceIdManager.resetDynamicIds
   */
  fun clearCache() {
    synchronized(lock) {
      repoMap.clear()
      packages = createPackageCache()
    }
  }

  @GuardedBy("lock")
  private fun createPackageCache() =
    TimeoutCachedValue(packageTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) {
      // Only computed from packages.get(), which is always called with the lock held.
      buildSet { repoMap.values.forEach { this.addAll(it.packages) } }
    }

  private fun removeRepository(repo: ResourceRepository) {
    synchronized(lock) {
      repoMap.remove(repo)
      packages = createPackageCache()
    }
  }

  private class ResourceRepositoryInfo(repo: ResourceRepository, idManager: ResourceIdManager) {
    val resourceClassGenerator: ResourceClassGenerator = ResourceClassGenerator.create(idManager, repo)
    @GuardedBy("ResourceClassRegistry.lock") val packages = mutableSetOf<String>()
  }

  companion object {
    /** Lazily instantiates a registry with the target project. */
    @JvmStatic fun get(project: Project): ResourceClassRegistry = project.service()

    /**
     * Get an instance of [ResourceClassRegistry] if it already exists. Use this method if you want to, for example, simply clean the cache,
     * and you do not want to initialize a new instance if it doesn't exist.
     */
    @JvmStatic fun getInstanceIfCreated(project: Project): ResourceClassRegistry? = project.serviceIfCreated()
  }
}
