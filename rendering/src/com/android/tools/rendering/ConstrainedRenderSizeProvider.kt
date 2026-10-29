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
package com.android.tools.rendering

import com.android.ide.common.rendering.api.RenderSizeProvider
import java.awt.Dimension
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ConstrainedRenderSizeProvider(
  private val maxImageSize: Long,
  private val qualityProvider: () -> Float,
  private val targetScaleProvider: () -> Float,
) : RenderSizeProvider {
  constructor(maxImageSize: Long, qualityProvider: () -> Float) : this(maxImageSize, qualityProvider, { 1f })

  override fun getTargetSize(width: Int, height: Int): Dimension {
    val wantedImageSize: Long = maxOf(1, width.toLong() * height.toLong())
    // First adjust dimensions according to quality and target scale multipliers. Each dimension is
    // scaled using `sqrt(quality) * targetScale`, so that quality scales the whole image area down
    // while targetScale (>= 1) upscales zoomed-in or low-density renders to display resolution.
    val quality = qualityProvider().toDouble().coerceIn(1e-6, 1.0)
    val targetScale: Double = targetScaleProvider().toDouble().coerceIn(1.0, maxTargetScale(width, height, quality))
    val wantedDimensionScale: Double = sqrt(quality) * targetScale

    // Also use maxImageSize as absolute upper bound
    val maxSizeDimensionScale: Double = sqrt(maxImageSize.toDouble() / wantedImageSize)

    // Use the tighter bound (wanted vs max size)
    val dimensionScale: Double = min(wantedDimensionScale, maxSizeDimensionScale)
    val scaledWidth: Double = width * dimensionScale
    val scaledHeight: Double = height * dimensionScale

    // Make sure both dimensions are positive integers
    val w = max(1, scaledWidth.toInt())
    val h = max(1, scaledHeight.toInt())
    // Layoutlib's RenderSessionImpl.prepareRenderBuffers only applies FLAG_KEY_RESULT_IMAGE_AUTO_SCALE
    // when both dimensions differ from the measured dimensions; avoid leaving one axis unscaled when
    // rounding changes only one axis (e.g. a 1px-thick divider). Images above maxImageSize keep their
    // capped dimensions. This drops scaling entirely for very thin views (shorter side below
    // ~1 / |scale - 1| px).
    if ((w == width) != (h == height) && wantedImageSize <= maxImageSize) {
      return Dimension(width, height)
    }
    return Dimension(w, h)
  }

  companion object {
    /** Upper bound (width x height) for images upscaled by a target scale > 1: 4M pixels, i.e. 16 MB at 4 bytes per pixel. */
    const val MAX_UPSCALED_IMAGE_SIZE: Long = 4L * 1024 * 1024

    /**
     * Returns the largest effective target scale (>= 1) for a [width] x [height] render at [quality]. Any larger target scale is capped so
     * that the upscaled image does not exceed [MAX_UPSCALED_IMAGE_SIZE].
     */
    @JvmStatic
    fun maxTargetScale(width: Int, height: Int, quality: Double): Double {
      val imageSize = maxOf(1, width.toLong() * height.toLong())
      return sqrt(MAX_UPSCALED_IMAGE_SIZE.toDouble() / imageSize / quality.coerceIn(1e-6, 1.0)).coerceAtLeast(1.0)
    }
  }
}
