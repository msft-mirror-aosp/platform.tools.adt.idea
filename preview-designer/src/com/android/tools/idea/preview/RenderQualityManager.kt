/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.preview

import com.android.annotations.concurrency.GuardedBy
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.preview.essentials.PreviewEssentialsModeManager
import com.android.tools.idea.rendering.isCancellationException
import com.android.tools.idea.rendering.isErrorResult
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.rendering.ConstrainedRenderSizeProvider
import java.awt.Rectangle
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly

/**
 * Base interface for render quality management. Any RenderQualityManager should fulfill the three following responsibilities:
 * - Detect UI changes that might derive in a need of changing the render quality of some previews.
 * - Provide the target quality for a given preview ([getTargetQuality]).
 * - Discern whether a specific preview needs a quality change ([needsQualityChange]).
 */
interface RenderQualityManager {
  fun getTargetQuality(sceneManager: LayoutlibSceneManager): Float

  /**
   * On-screen physical pixels per dp that the render of [sceneManager] should match, see
   * [com.android.tools.idea.uibuilder.scene.LayoutlibSceneRenderConfiguration.screenScale]. 0f disables upscaling.
   */
  fun getTargetScreenScale(sceneManager: LayoutlibSceneManager): Float = 0f

  /** Linear scale (>= 1f) used on top of [getTargetQuality] to upscale the render of [sceneManager] to display resolution. */
  fun getTargetScale(sceneManager: LayoutlibSceneManager): Float {
    val screenScale = getTargetScreenScale(sceneManager)
    val densityScale = sceneManager.sceneScalingFactor
    return if (screenScale > 0f && densityScale > 0f) (screenScale / densityScale).coerceAtLeast(1f) else 1f
  }

  fun needsQualityChange(sceneManager: LayoutlibSceneManager): Boolean

  fun needsQualityChange(surface: NlDesignSurface) = surface.sceneManagers.any { needsQualityChange(it) }

  /**
   * Compares the target rendered image area of [sceneManager] with the one of its last render. Used for refreshing the previews whose
   * quality decreases before the ones whose quality increases.
   */
  fun compareTargetToLastRender(sceneManager: LayoutlibSceneManager): Int {
    val targetScale = getTargetScale(sceneManager)
    val lastScale = sceneManager.lastRenderScale
    return (getTargetQuality(sceneManager) * targetScale * targetScale).compareTo(sceneManager.lastRenderQuality * lastScale * lastScale)
  }
}

/**
 * Base interface needed for using a [DefaultRenderQualityManager].
 *
 * This policy intends to provide a way of easily tweaking the behaviour of a [DefaultRenderQualityManager] according to what each tool
 * needs.
 */
interface RenderQualityPolicy {
  val acceptedErrorMargin: Float

  val debounceTimeMillis: Long

  fun getTargetQuality(scale: Double, isVisible: Boolean): Float

  /** On-screen physical pixels per dp that a render at [targetQuality] should match at the surface [scale]. 0f disables upscaling. */
  fun getTargetScreenScale(scale: Double, targetQuality: Float): Float = 0f
}

fun getDefaultPreviewQuality() = if (PreviewEssentialsModeManager.isEssentialsModeEnabled) 0.75f else 0.95f

/** When zooming out, a preview is re-rendered only once its last render scale is more than this many times (linearly) the target scale. */
private const val MAX_ZOOM_OUT_SCALE_RATIO = 2f

/**
 * Default [RenderQualityManager] implementation, configurable by a [RenderQualityPolicy], that given a [NlDesignSurface], it detects its
 * scroll, zoom and resize changes, and delegates the corresponding render quality preview updates to [onQualityChangeMightBeNeeded].
 *
 * Note that, as its name suggests, when [onQualityChangeMightBeNeeded] is executed, it may happen that no quality change is actually
 * needed, it is each tool's responsibility to check this before refreshing its previews. This is because doing such check would probably be
 * expensive and the quality changes are likely to be low priority, so each tool may schedule them for later, for example by using a low
 * priority [PreviewRefreshRequest].
 */
class DefaultRenderQualityManager(
  private val mySurface: NlDesignSurface,
  private val myPolicy: RenderQualityPolicy,
  private val onQualityChangeMightBeNeeded: () -> Unit,
) : RenderQualityManager {
  private val scope = mySurface.createCoroutineScope()

  private val uiDataLock = ReentrantLock()
  @GuardedBy("uiDataLock") private val sceneViewRectangles: WeakHashMap<SceneView, Rectangle?> = WeakHashMap()
  @GuardedBy("uiDataLock") private var scrollRectangle: Rectangle? = null
  @GuardedBy("uiDataLock") private var isUiDataUpToDate = false

  init {
    scope.launch {
      merge(mySurface.panningChanged, mySurface.zoomChanged, mySurface.modelChanged).debounce(myPolicy.debounceTimeMillis).collect {
        // Mark the ui data as outdated,
        // but don't refresh it now as it may not be needed until later
        uiDataLock.withLock { isUiDataUpToDate = false }
        onQualityChangeMightBeNeeded()
      }
    }
  }

  private fun isSceneManagerVisible(sceneManager: LayoutlibSceneManager): Boolean {
    return uiDataLock.withLock {
      // A null scrollRectangle should be caused by a not-scrollable surface, and in such case
      // all previews are considered to be visible
      scrollRectangle == null ||
        sceneManager.sceneViews.any { sceneViewRectangles.getOrDefault(it, null)?.intersects(scrollRectangle!!) == true }
    }
  }

  override fun getTargetQuality(sceneManager: LayoutlibSceneManager): Float {
    uiDataLock.withLock {
      if (
        // Update rectangles if any UI related dimensions changed or a sceneView instance changed
        !isUiDataUpToDate || sceneManager.sceneViews.any { !sceneViewRectangles.containsKey(it) }
      ) {
        sceneViewRectangles.clear()
        sceneViewRectangles.putAll(mySurface.findSceneViewRectangles())
        scrollRectangle = mySurface.currentScrollRectangle
        isUiDataUpToDate = true
      }
    }
    return myPolicy.getTargetQuality(mySurface.zoomController.scale, isSceneManagerVisible(sceneManager))
  }

  override fun getTargetScreenScale(sceneManager: LayoutlibSceneManager): Float =
    myPolicy.getTargetScreenScale(mySurface.zoomController.scale, getTargetQuality(sceneManager))

  override fun needsQualityChange(sceneManager: LayoutlibSceneManager): Boolean = sceneManager.let {
    // Refreshes are skipped in any of the following scenarios:
    // - Last render failed and not due to a cancellation exception
    // - Neither the target quality nor the effective target scale (capped by
    //   ConstrainedRenderSizeProvider.MAX_UPSCALED_IMAGE_SIZE) differs substantially from the last successful render.
    if (it.renderResult.isCancellationException()) return@let true
    if (it.renderResult.isErrorResult()) return@let false
    val targetQuality = getTargetQuality(it)
    if (abs(it.lastRenderQuality - targetQuality) > myPolicy.acceptedErrorMargin) return@let true
    val maxScale =
      it.renderResult?.rootViewDimensions?.let { dim ->
        ConstrainedRenderSizeProvider.maxTargetScale(dim.width, dim.height, targetQuality.toDouble()).toFloat()
      } ?: Float.MAX_VALUE
    val lastScale = it.lastRenderScale.coerceIn(1f, maxScale)
    val targetScale = getTargetScale(it).coerceIn(1f, maxScale)
    // Zooming in needs a sharper render. Zooming out does not: the higher resolution bitmap is downscaled smoothly while painting, so
    // re-render only once it is more than twice as large as needed, to release its memory.
    if (targetScale > lastScale) (targetScale - lastScale) / targetScale > myPolicy.acceptedErrorMargin
    else lastScale > MAX_ZOOM_OUT_SCALE_RATIO * targetScale
  }

  @TestOnly internal fun sceneViewRectanglesContainsForTest(sceneView: SceneView) = sceneViewRectangles.containsKey(sceneView)
}
