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

import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import java.awt.Shape
import java.awt.geom.RoundRectangle2D

/**
 * Returns a capsule [Shape] for an expanded preview of [width] x [height] pixels at ([x], [y]) rendered with [renderingMode], or null if
 * the render is not elongated along its expand axis.
 *
 * For round devices, an expanded render ([RenderingMode.V_SCROLL] or [RenderingMode.H_SCROLL]) would stretch the device circle into an
 * ellipse over the whole render, squeezing the content into a lens. Instead, a capsule along the longer axis is used: half-circles of the
 * shorter side at both ends, joined by straight sides. This keeps the round-device framing of the start (e.g. `TimeText`) and end (e.g.
 * `EdgeButton`) of the screen.
 */
fun expandedCapsuleShape(
  renderingMode: RenderingMode?,
  width: Int,
  height: Int,
  x: Double = 0.0,
  y: Double = 0.0,
): Shape? {
  val isElongated =
    width > 0 &&
      height > 0 &&
      when (renderingMode) {
        RenderingMode.V_SCROLL -> height > width
        RenderingMode.H_SCROLL -> width > height
        else -> false
      }
  if (!isElongated) return null
  val arc = minOf(width, height).toDouble()
  return RoundRectangle2D.Double(x, y, width.toDouble(), height.toDouble(), arc, arc)
}

/**
 * Returns the shape to clip a preview of [width] x [height] pixels at ([x], [y]), rendered with [renderingMode], to.
 *
 * For round devices whose render is elongated along the expand axis, returns a capsule shape (see [expandedCapsuleShape]). Non-round
 * devices and renders that fit in the device keep their [deviceShape].
 *
 * @param deviceShape The shape of the device screen fitted to the render, null for non-round devices.
 * @return The shape to clip the render to, or null if it should not be clipped.
 */
fun expandedScreenShape(
  deviceShape: Shape?,
  renderingMode: RenderingMode?,
  width: Int,
  height: Int,
  x: Double = 0.0,
  y: Double = 0.0,
): Shape? {
  if (deviceShape == null) return null
  return expandedCapsuleShape(renderingMode, width, height, x, y) ?: deviceShape
}
