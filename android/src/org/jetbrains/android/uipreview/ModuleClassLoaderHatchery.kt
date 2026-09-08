/*
 * Copyright (C) 2021 The Android Open Source Project
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
package org.jetbrains.android.uipreview

import com.android.tools.idea.rendering.StudioModuleRenderContext
import com.android.tools.rendering.classloading.ClassTransform
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting

private val LOG = Logger.getInstance(ModuleClassLoaderHatchery::class.java)

/**
 * How many different classloader types the hatchery stores. The current default is 2 with the idea of having:
 * 1. ModuleClassLoader for the static preview
 * 2. ModuleClassLoader for the interactive preview
 */
private const val CAPACITY = 2
/** How many copies of the same classloader the hatchery maintains */
private const val COPIES = 1
private const val DEFAULT_MAX_REQUESTS_SIZE = 20

private fun getDefaultExecutor(): Executor {
  return if (ApplicationManager.getApplication()?.isUnitTestMode == true) {
    Executor { command -> command.run() }
  } else {
    AppExecutorUtil.getAppExecutorService()
  }
}

/** Contains all the information that was used to create a [StudioModuleClassLoader]. */
data class StudioModuleClassLoaderCreationContext(
  val parent: ClassLoader?,
  val moduleRenderContext: StudioModuleRenderContext,
  val classesToPreload: Set<String>,
  val projectTransform: ClassTransform,
  val nonProjectTransformation: ClassTransform,
) {

  /** Creates a new [StudioModuleClassLoader] from this [StudioModuleClassLoaderCreationContext]. */
  fun createClassLoader(): StudioModuleClassLoader =
    StudioModuleClassLoader(
      parent,
      moduleRenderContext,
      projectTransform,
      nonProjectTransformation,
      StudioModuleClassLoaderManager.createDiagnostics(),
    )

  companion object {
    /** Obtains a the [StudioModuleClassLoaderCreationContext] used to create the [classLoader]. */
    fun fromClassLoader(classLoader: StudioModuleClassLoader): StudioModuleClassLoaderCreationContext? =
      classLoader.moduleContext?.let { moduleRenderContext ->
        StudioModuleClassLoaderCreationContext(
          parent = classLoader.parentAtConstruction,
          moduleRenderContext = moduleRenderContext,
          classesToPreload = classLoader.nonProjectLoadedClasses,
          projectTransform = classLoader.projectClassesTransform,
          nonProjectTransformation = classLoader.nonProjectClassesTransform,
        )
      }

    @TestOnly
    fun fromClassLoaderOrThrow(classLoader: StudioModuleClassLoader): StudioModuleClassLoaderCreationContext =
      fromClassLoader(classLoader)!!
  }
}

/**
 * A storage of [StudioModuleClassLoader]s of the same type responsible for their preloading and replenishment. [StudioModuleClassLoader]s
 * managed by this storage do not get stale after updating only the user code since module dependencies should change for these
 * [StudioModuleClassLoader]s to become invalid.
 */
private class Clutch(
  private val cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader?,
  private val donor: StudioModuleClassLoaderCreationContext,
  copies: Int = COPIES,
  private val executor: Executor = getDefaultExecutor(),
) {
  private val eggs = ConcurrentLinkedQueue<StudioPreloader>()
  private val isDestroyed = AtomicBoolean(false)
  private val inFlightReplenishments = AtomicInteger(0)

  init {
    replenishAsync(copies)
  }

  private fun replenishAsync(count: Int = 1) {
    if (isDestroyed.get() || count <= 0) return
    inFlightReplenishments.addAndGet(count)
    try {
      executor.execute {
        var remaining = count
        try {
          if (isDestroyed.get()) return@execute
          repeat(count) {
            if (isDestroyed.get()) return@execute
            try {
              cloner(donor)?.let { newClassLoader ->
                if (isDestroyed.get()) {
                  newClassLoader.dispose()
                  return@let
                }
                // Until the preloader is constructed nothing else owns newClassLoader, so a failure here would leak it.
                val preloader =
                  try {
                    StudioPreloader(newClassLoader, donor.classesToPreload, executor)
                  } catch (t: Throwable) {
                    newClassLoader.dispose()
                    throw t
                  }
                eggs.add(preloader)
                if (isDestroyed.get()) {
                  // If destroy() ran while we were creating/adding, ensure it is disposed.
                  if (eggs.remove(preloader)) {
                    preloader.dispose()
                  }
                }
              }
            } catch (t: Throwable) {
              LOG.warn("Failed to create a pre-warmed class loader", t)
            } finally {
              inFlightReplenishments.decrementAndGet()
              remaining--
            }
          }
        } finally {
          if (remaining > 0) {
            inFlightReplenishments.addAndGet(-remaining)
          }
        }
      }
    } catch (e: Throwable) {
      inFlightReplenishments.addAndGet(-count)
      LOG.warn("Failed to schedule class loader replenishment", e)
    }
  }

  /**
   * Returns true if this clutch can no longer produce [StudioModuleClassLoader]s: it holds no eggs at all and has no replenishment in
   * flight, meaning the last attempt to clone from the donor produced nothing. A clutch whose eggs were merely garbage collected is *not*
   * dead: it still has queued eggs and [retrieve] will drop them and replenish from the same donor.
   *
   * Must only be called while holding the [ModuleClassLoaderHatchery] monitor. [retrieve] transiently satisfies this predicate between
   * draining the last egg and scheduling its replenishment.
   */
  fun isDead(): Boolean = isDestroyed.get() || (eggs.isEmpty() && inFlightReplenishments.get() <= 0)

  /** Checks if the clutch maintains the [StudioModuleClassLoader]s of this type. */
  fun isCompatible(parent: ClassLoader?, projectTransformations: ClassTransform, nonProjectTransformations: ClassTransform): Boolean {
    if (isDestroyed.get()) return false
    var hasLiveEggs = false
    for (egg in eggs) {
      if (!egg.isAlive()) continue
      hasLiveEggs = true
      if (egg.isForCompatible(parent, projectTransformations, nonProjectTransformations)) {
        return true
      }
    }
    if (hasLiveEggs) return false
    if (isDead()) return false
    // Fallback if eggs are still being preloaded in the background or were GC'd. In the latter case the clutch stays compatible on purpose,
    // so that retrieve() drops the dead eggs and replenishes from the same donor instead of forcing a new clutch to be incubated.
    return (donor.parent == parent) &&
      (donor.projectTransform.id == projectTransformations.id) &&
      (donor.nonProjectTransformation.id == nonProjectTransformations.id)
  }

  /**
   * If possible, returns a [StudioModuleClassLoader] from the clutch and transfers full ownership to the caller, otherwise returns null.
   */
  fun retrieve(): StudioModuleClassLoader? {
    if (isDestroyed.get()) return null
    var discardedCount = 0
    var compatibleClassLoader: StudioModuleClassLoader? = null

    while (true) {
      val preloader = eggs.poll() ?: break
      val classLoader = preloader.getClassLoader()
      if (classLoader == null) {
        discardedCount++
        continue
      }
      if (!classLoader.isUserCodeUpToDate) {
        classLoader.dispose()
        discardedCount++
        continue
      }
      compatibleClassLoader = classLoader
      break
    }

    val needsReplenish = discardedCount + (if (compatibleClassLoader != null) 1 else 0)
    if (needsReplenish > 0) {
      replenishAsync(needsReplenish)
    }

    return compatibleClassLoader
  }

  fun disposeFirstEggForTesting() {
    eggs.peek()?.dispose()
  }

  fun isFirstEggActiveForTesting(): Boolean = eggs.peek()?.isActive ?: false

  /** Should be called when the clutch is no longer needed to free all the resources. */
  fun destroy() {
    isDestroyed.set(true)
    generateSequence { eggs.poll() }.forEach { it.dispose() }
  }

  fun getStats(): Stats {
    return Stats("Clutch ${this.hashCode()}", eggs.map { ReadyState(it.getLoadedCount(), donor.classesToPreload.size) })
  }
}

/** Data representing the identification of the [StudioModuleClassLoader] type. */
private data class Request(
  val parent: ClassLoader?,
  val projectTransformations: ClassTransform,
  val nonProjectTransformations: ClassTransform,
) {
  override fun equals(other: Any?): Boolean {
    if (other !is Request) {
      return false
    }
    return (parent == other.parent) &&
      (projectTransformations.id == other.projectTransformations.id) &&
      (nonProjectTransformations.id == other.nonProjectTransformations.id)
  }

  override fun hashCode(): Int {
    var result = parent?.hashCode() ?: 0
    result = 31 * result + projectTransformations.id.hashCode()
    result = 31 * result + nonProjectTransformations.id.hashCode()
    return result
  }
}

/** A data structure responsible for replenishing and providing on demand [StudioModuleClassLoader]s ready to use */
class ModuleClassLoaderHatchery(
  private val capacity: Int = CAPACITY,
  private val copies: Int = COPIES,
  private val maxRequestsSize: Int = DEFAULT_MAX_REQUESTS_SIZE,
  private val executor: Executor = getDefaultExecutor(),
  parentDisposable: Disposable,
) {
  // Requests for ModuleClassLoaders type that hatchery does not know how to create
  private val requests = LinkedHashSet<Request>()
  // Clutches of different ModuleClassLoader types
  private val storage = LinkedList<Clutch>()

  // If this class is disposed, it will stop accepting requests
  private val isDisposed = AtomicBoolean(false)

  init {
    Disposer.register(parentDisposable) {
      isDisposed.set(true)
      destroy()
    }
  }

  /** Request a ModuleClassLoader compatible with the input from this hatchery if such exists. */
  @Synchronized
  fun requestClassLoader(
    parent: ClassLoader?,
    projectTransformations: ClassTransform,
    nonProjectTransformations: ClassTransform,
  ): StudioModuleClassLoader? {
    if (isDisposed.get()) return null

    storage
      .find { it.isCompatible(parent, projectTransformations, nonProjectTransformations) }
      ?.let { clutch ->
        return clutch.retrieve()
      }
    // If there is no compatible clutch we remember the request and will create one when we have an appropriate donor
    if (requests.size >= maxRequestsSize) {
      requests.remove(requests.first())
    }
    requests.add(Request(parent, projectTransformations, nonProjectTransformations))
    return null
  }

  /**
   * Create a clutch from the [donor] [StudioModuleClassLoader] if a clutch of this type does not exist and such type was requested. The
   * [donor] should only be used for cloning. Returns true if donor was used for cloning and false otherwise.
   */
  @Synchronized
  fun incubateIfNeeded(
    donor: StudioModuleClassLoaderCreationContext,
    cloner: (StudioModuleClassLoaderCreationContext) -> StudioModuleClassLoader?,
  ): Boolean {
    if (isDisposed.get()) return false

    // Drop clutches that can no longer produce class loaders. Without this, the capacity eviction below removes the head of the list, which
    // may well be a healthy clutch for another preview type while the dead one stays behind.
    storage.removeIf { clutch ->
      clutch.isDead().also { if (it) clutch.destroy() }
    }

    val hasCompatibleDonor = storage.find { it.isCompatible(donor.parent, donor.projectTransform, donor.nonProjectTransformation) } != null
    if (hasCompatibleDonor) return false
    val request = Request(donor.parent, donor.projectTransform, donor.nonProjectTransformation)
    if (requests.contains(request)) {
      requests.remove(request)
      if (storage.size == capacity) {
        storage.poll().destroy()
      }
      storage.add(Clutch(cloner, donor, copies, executor))
      return true
    }
    return false
  }

  @Synchronized
  fun getStats(): List<Stats> {
    return storage.map { it.getStats() }
  }

  @VisibleForTesting
  fun disposeFirstEggForTesting() {
    storage.firstOrNull()?.disposeFirstEggForTesting()
  }

  @VisibleForTesting
  fun isFirstEggActiveForTesting(): Boolean {
    return storage.firstOrNull()?.isFirstEggActiveForTesting() ?: false
  }

  @VisibleForTesting
  fun getRequestsSizeForTesting(): Int {
    return requests.size
  }

  @Synchronized
  fun destroy() {
    requests.clear()
    storage.forEach { it.destroy() }
    storage.clear()
  }
}

/** Represents the current preloading [progress] (number of classes) out of full [toDo] number. */
data class ReadyState(val progress: Int, val toDo: Int)

/** Represents [ReadyState] stats for all eggs in a Clutch identified by [label] */
data class Stats(val label: String, val states: List<ReadyState>)
