/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.scene

import com.android.annotations.concurrency.GuardedBy
import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.idea.compose.preview.InteractivePreviewNavigationController
import com.android.tools.idea.compose.preview.parseViewInfo
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.rendering.classloading.LocalNavigationEventTransform
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.preview.ComposePreviewElementInstance
import com.intellij.openapi.diagnostic.thisLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import org.jetbrains.annotations.TestOnly

private const val NAV_DISPLAY_NAME = "NavDisplay"

/**
 * Sets up the [InteractivePreviewNavigationController] on the [ComposePreviewElementInstance] responsible for handling interactive back
 * navigation events in the preview.
 */
object InteractivePreviewBackNavigationUpdater {

  // Encapsulates synchronization for dispatcherOwnersByClassLoader without locking on the public singleton monitor
  private val dispatcherOwnersByClassLoaderLock = Any()

  // Maps each preview ClassLoader to its active FakeNavigationEventDispatcherOwner using weak references to prevent leaking ClassLoaders
  @GuardedBy("dispatcherOwnersByClassLoaderLock") private val dispatcherOwnersByClassLoader = WeakHashMap<ClassLoader, WeakReference<Any>>()

  /**
   * Registers [dispatcher] for its preview [ClassLoader].
   *
   * This method is invoked by [LocalNavigationEventTransform] via bytecode injection to store the
   * `androidx.navigationevent.compose.FakeNavigationEventDispatcherOwner` it creates so [update] can resolve it for the matching preview
   * [ClassLoader].
   *
   * WARNING: Do not rename or delete this method. It is accessed by name using a Java [org.objectweb.asm.MethodVisitor] within
   * [LocalNavigationEventTransform.visitMethod].
   */
  @Suppress("unused") // Accessed via bytecode injection in [LocalNavigationEventTransform]
  fun setNavigationEventDispatcherOwner(dispatcher: Any) {
    // Ignore bootstrap objects with a null ClassLoader; real preview owners are always loaded by StudioModuleClassLoader
    val ownerClassLoader = dispatcher.javaClass.classLoader ?: return
    // Store the dispatcher owner under its preview ClassLoader so concurrent previews remain isolated
    synchronized(dispatcherOwnersByClassLoaderLock) {
      dispatcherOwnersByClassLoader[ownerClassLoader] = WeakReference(dispatcher)
    }
  }

  /** Clears the cached dispatcher owner for [classLoader] without removing owners belonging to other active preview classloaders. */
  fun clearForClassLoader(classLoader: ClassLoader?) {
    // Remove only the given preview ClassLoader's entry; no-op when viewObject has not rendered yet
    classLoader?.let {
      synchronized(dispatcherOwnersByClassLoaderLock) { dispatcherOwnersByClassLoader.remove(it) }
    }
  }

  /** Clears all cached dispatcher owner entries across all classloaders in tests. */
  @TestOnly
  fun resetForTest() {
    // Clear the entire map between unit tests to prevent cross-test state leakage
    synchronized(dispatcherOwnersByClassLoaderLock) { dispatcherOwnersByClassLoader.clear() }
  }

  /** Resolves the cached dispatcher owner registered for [adapterClassLoader], or null if none is present. */
  private fun resolveDispatcherOwner(adapterClassLoader: ClassLoader?): Any? =
    // Look up the dispatcher owner registered for the same preview ClassLoader as ComposeViewAdapter
    adapterClassLoader?.let { classLoader ->
      synchronized(dispatcherOwnersByClassLoaderLock) {
        dispatcherOwnersByClassLoader[classLoader]?.get()
      }
    }

  /**
   * Updates the [InteractivePreviewNavigationController] for the current [ComposePreviewElementInstance] using the provided
   * [LayoutlibSceneManager].
   *
   * This method retrieves the underlying object of [ComposeViewAdapter] and the hierarchy information from the [LayoutlibSceneManager] to
   * check for the presence of a navigation3 [NavDisplay] element, and then propagates this status along with the resolved dispatcher
   * objects to the [InteractivePreviewNavigationController].
   *
   * Call this method on every preview render to ensure the [InteractivePreviewNavigationController] has the most current navigation
   * information from the [ComposeViewAdapter].
   *
   * @param previewManager The [PreviewModeManager] to check the current preview mode.
   * @param layoutlibSceneManager The [LayoutlibSceneManager] providing the [ComposePreviewElementInstance] and the [ComposeViewAdapter]
   *   object.
   */
  fun update(
    previewManager: PreviewModeManager,
    layoutlibSceneManager: LayoutlibSceneManager,
    interactivePreviewNavigationController: InteractivePreviewNavigationController,
  ) {
    // Bail out early if the preview has not rendered a ComposeViewAdapter yet (nothing to resolve or clear)
    val composeViewAdapterObj = layoutlibSceneManager.viewObject ?: return
    val adapterClassLoader = composeViewAdapterObj.javaClass.classLoader
    if (previewManager.mode.value !is PreviewMode.Interactive) {
      // Remove only this preview's ClassLoader entry so other open interactive tabs keep their dispatchers
      clearForClassLoader(adapterClassLoader)
      return
    }
    val rootViews = layoutlibSceneManager.renderResult?.rootViews ?: emptyList()
    val viewInfo: ViewInfo? = rootViews.firstOrNull()
    val hasNavDisplayInViewTree: Boolean =
      viewInfo?.let { info ->
        val composeViewInfos = parseViewInfo(rootViewInfo = info, logger = thisLogger())
        // Check in the viewInfo if we have a navigation3 NavDisplay.
        composeViewInfos
          .flatMap { composeViewInfo -> composeViewInfo.allChildren() }
          .any { childInfo -> childInfo.name == NAV_DISPLAY_NAME }
      } ?: false

    val weakSceneManager = WeakReference(layoutlibSceneManager)
    interactivePreviewNavigationController.executeInRenderSessionAsync = { runnable ->
      // Execute in the render session where RenderSecurityManager/RenderSandbox is active
      weakSceneManager.get()?.executeInRenderSessionAsync(runnable, 0, TimeUnit.SECONDS)
    }

    // Resolve the dispatcher owner belonging to this preview's ClassLoader and update the navigation controller
    val resolvedDispatcherOwner = resolveDispatcherOwner(adapterClassLoader)
    interactivePreviewNavigationController.updateObjects(
      currentNavigationEventDispatcherOwnerObj = resolvedDispatcherOwner,
      currentComposeViewAdapterObj = composeViewAdapterObj,
      hasNavDisplay = hasNavDisplayInViewTree,
    )
  }
}
