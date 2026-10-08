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
package com.android.tools.idea.compose.meshgradient

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/*
 * Geometry of standard `Brush` gradients.
 *
 * The editor keeps offsets and radii in the same units as the source code: pixels of the component the brush is drawn into. Compose
 * reserves some values to mean "relative to the drawing size": a `Float.POSITIVE_INFINITY` coordinate stands for the full width or
 * height, an `Offset.Unspecified` center for the center of the drawing area, and an infinite radius for half of its smallest dimension.
 * These values are kept untouched in the state so they round-trip to code unchanged. The preview draws the brush into an area of a
 * user-editable reference size ([GradientEditorState.previewSize]) and lets Compose resolve them.
 */

/** Reference preview size used when it cannot be inferred from the gradient. */
internal val DEFAULT_PREVIEW_SIZE = Size(400f, 400f)

/** Smallest width or height of the preview, in pixels. */
internal const val MIN_PREVIEW_DIMENSION = 1f

/** Largest width or height of the preview, in pixels. */
internal const val MAX_PREVIEW_DIMENSION = 100_000f

/** Smallest radius drawn by the preview: Skia cannot create a radial gradient without a positive radius. */
internal const val MIN_RADIUS = 0.01f

/** Movement, in canvas pixels, below which a dragged axis keeps its original value. */
private const val DRAG_DEAD_ZONE = 2f

/** Maximum number of decimals of a dragged value. */
private const val MAX_DRAG_DECIMALS = 4

/** An axis of an [Offset]. */
internal enum class Axis {
  X,
  Y,
}

/** A point of a standard `Brush` gradient, which determines the value a coordinate takes when it is cleared. */
internal enum class PointKind {
  /** `start` of a linear gradient: a cleared coordinate becomes `0`. */
  START,

  /** `end` of a linear gradient: a cleared coordinate becomes `Float.POSITIVE_INFINITY` (the full size). */
  END,

  /** `center` of a radial or sweep gradient: clearing any coordinate makes the whole point `Offset.Unspecified` (the center). */
  CENTER,
}

/**
 * Resolves one coordinate for placing a handle: an infinite coordinate means the full [extent], like in Compose. Other non-finite values
 * resolve to 0, while Compose would pass them on to the shader.
 */
private fun resolveCoordinate(value: Float, extent: Float): Float =
  when {
    value == Float.POSITIVE_INFINITY -> extent
    value.isFinite() -> value
    else -> 0f
  }

/**
 * Resolves the `start` or `end` of a linear gradient against [size], to place its handle. A `Float.POSITIVE_INFINITY` coordinate resolves
 * to the full size as in Compose. Unlike Compose, which passes them on to the shader, `NaN` and `Float.NEGATIVE_INFINITY` coordinates
 * (including those of `Offset.Unspecified`) resolve to 0.
 */
internal fun resolveLinearPoint(point: Offset, size: Size): Offset =
  if (point.isUnspecified) Offset.Zero else Offset(resolveCoordinate(point.x, size.width), resolveCoordinate(point.y, size.height))

/**
 * Resolves the `center` of a radial or sweep gradient against [size], to place its handle. `Offset.Unspecified` resolves to the center of
 * [size] and a `Float.POSITIVE_INFINITY` coordinate to the full size, as in Compose. Unlike Compose, `NaN` and `Float.NEGATIVE_INFINITY`
 * coordinates of an otherwise specified offset resolve to 0.
 */
internal fun resolveCenter(center: Offset, size: Size): Offset =
  if (center.isUnspecified) size.center else Offset(resolveCoordinate(center.x, size.width), resolveCoordinate(center.y, size.height))

/** Keeps [point] within the bounds of [size]; used to place handles of points that lie outside of the preview. */
internal fun clampToPreview(point: Offset, size: Size): Offset = Offset(point.x.coerceIn(0f, size.width), point.y.coerceIn(0f, size.height))

/**
 * Number of decimals that a dragged value needs at the given [scale] (canvas pixels per brush pixel): enough to tell apart the positions of
 * adjacent canvas pixels, and no more.
 */
internal fun dragDecimals(scale: Float): Int = ceil(-log10(1.0 / scale)).toInt().coerceIn(0, MAX_DRAG_DECIMALS)

private fun roundToDecimals(value: Float, decimals: Int): Float {
  val factor = 10.0.pow(decimals)
  val rounded = (round(value * factor) / factor).toFloat()
  // Rounding a small negative value gives -0f, which is written as "-0f" and is not equal to 0f in an Offset.
  return if (rounded == 0f) 0f else rounded
}

/**
 * Returns the new value of a dragged point.
 *
 * An axis that moved less than [DRAG_DEAD_ZONE] canvas pixels keeps its [original] value, so dragging never rewrites a coordinate the user
 * did not move (e.g. an infinite one). An `Offset.Unspecified` point has no meaningful single coordinate, so both axes take their dragged
 * value once either moves. Dragged values are rounded to the precision of a canvas pixel (see [dragDecimals]).
 *
 * @param original the value of the point when the drag started, as written in code.
 * @param handleStart the position, in brush pixels, where the handle was drawn when the drag started.
 * @param canvasDelta the total drag movement since the pointer went down, in canvas pixels.
 * @param scale canvas pixels per brush pixel.
 */
internal fun draggedPoint(original: Offset, handleStart: Offset, canvasDelta: Offset, scale: Float): Offset {
  if (scale <= 0f || !scale.isFinite()) return original
  val movedX = abs(canvasDelta.x) >= DRAG_DEAD_ZONE
  val movedY = abs(canvasDelta.y) >= DRAG_DEAD_ZONE
  if (!movedX && !movedY) return original
  val decimals = dragDecimals(scale)
  val x = roundToDecimals(handleStart.x + canvasDelta.x / scale, decimals)
  val y = roundToDecimals(handleStart.y + canvasDelta.y / scale, decimals)
  if (original.isUnspecified) return Offset(x, y)
  return Offset(if (movedX) x else original.x, if (movedY) y else original.y)
}

/**
 * Returns this point with the coordinate on [axis] set to [value], in pixels.
 *
 * A `null` [value] clears the coordinate as described by [kind]. When this point is `Offset.Unspecified` and a single coordinate is set,
 * the other coordinate takes its [resolved] value since an offset cannot be partially unspecified.
 */
internal fun Offset.withCoordinate(axis: Axis, value: Float?, resolved: Offset, kind: PointKind): Offset {
  val newValue =
    value
      ?: when (kind) {
        PointKind.START -> 0f
        PointKind.END -> Float.POSITIVE_INFINITY
        PointKind.CENTER -> return Offset.Unspecified
      }
  val base = if (isUnspecified) resolved else this
  return when (axis) {
    Axis.X -> Offset(newValue, base.y)
    Axis.Y -> Offset(base.x, newValue)
  }
}

/**
 * Infers a reference preview size for a gradient: the smallest size that contains its finite geometry, rounded up to whole pixels.
 *
 * Linear gradients must contain their `start` and `end` points. Radial and sweep gradients must be able to show their center in the middle
 * of the preview, and radial gradients additionally extend the preview to contain the right and bottom edges of their circle. When the
 * radius is larger than a center coordinate, the circle also extends past the left or top edge, which no preview size can contain.
 * Automatic (infinite or unspecified) values do not constrain the size. If only one dimension is constrained, the preview is square; if
 * none is, [DEFAULT_PREVIEW_SIZE] is used.
 */
internal fun inferPreviewSize(type: GradientType, start: Offset, end: Offset, center: Offset, radius: Float): Size {
  val widths = mutableListOf<Float>()
  val heights = mutableListOf<Float>()
  fun addExtent(x: Float, y: Float) {
    if (x.isFinite()) widths += x
    if (y.isFinite()) heights += y
  }
  when (type) {
    GradientType.LINEAR -> {
      if (!start.isUnspecified) addExtent(start.x, start.y)
      if (!end.isUnspecified) addExtent(end.x, end.y)
    }
    GradientType.RADIAL,
    GradientType.SWEEP -> {
      val finiteRadius = radius.takeIf { type == GradientType.RADIAL && it.isFinite() && it > 0f } ?: 0f
      if (center.isUnspecified) {
        addExtent(2 * finiteRadius, 2 * finiteRadius)
      } else {
        addExtent(maxOf(2 * center.x, center.x + finiteRadius), maxOf(2 * center.y, center.y + finiteRadius))
      }
    }
    GradientType.MESH -> Unit
  }
  val width = widths.filter { it > 0f }.maxOrNull()?.let(::toPreviewDimension)
  val height = heights.filter { it > 0f }.maxOrNull()?.let(::toPreviewDimension)
  return when {
    width != null && height != null -> Size(width, height)
    width != null -> Size(width, width)
    height != null -> Size(height, height)
    else -> DEFAULT_PREVIEW_SIZE
  }
}

private fun toPreviewDimension(extent: Float): Float = ceil(extent).coerceIn(MIN_PREVIEW_DIMENSION, MAX_PREVIEW_DIMENSION)

/** Replaces a coordinate that would break the shader (`NaN` or `Float.NEGATIVE_INFINITY`) by 0. */
private fun sanitizeCoordinate(value: Float): Float = if (value.isNaN() || value == Float.NEGATIVE_INFINITY) 0f else value

private fun sanitizePoint(point: Offset): Offset = Offset(sanitizeCoordinate(point.x), sanitizeCoordinate(point.y))

/** Keeps an `Offset.Unspecified` center, which Compose resolves to the center of the drawing area, and sanitizes other centers. */
private fun sanitizeCenter(center: Offset): Offset = if (center.isUnspecified) center else sanitizePoint(center)

/** Replaces a `NaN` radius by the automatic `Float.POSITIVE_INFINITY` and a non-positive one by [MIN_RADIUS]. */
private fun sanitizeRadius(radius: Float): Float =
  when {
    radius.isNaN() -> Float.POSITIVE_INFINITY
    radius <= 0f -> MIN_RADIUS
    else -> radius
  }

/**
 * Creates the brush that previews [gradient]. It is meant to be drawn into an area of [GradientEditorState.previewSize] pixels, so that
 * Compose resolves automatic values (infinite coordinates and radius, unspecified center) against that size as it would at runtime. Only
 * values that would make Skia fail are replaced: `NaN` and `Float.NEGATIVE_INFINITY` coordinates become 0, a `NaN` radius becomes
 * automatic, a non-positive radius becomes [MIN_RADIUS], and fewer than two colors are padded.
 */
internal fun createPreviewBrush(gradient: Gradient): Brush {
  val (colors, colorStops) =
    when (gradient) {
      is Gradient.LinearGradient -> gradient.colors to gradient.colorStops
      is Gradient.RadialGradient -> gradient.colors to gradient.colorStops
      is Gradient.SweepGradient -> gradient.colors to gradient.colorStops
    }
  val stops = colorStops?.takeIf { it.size >= ColorStops.MIN_STOPS }?.toTypedArray()
  val safeColors =
    when (colors.size) {
      0 -> listOf(Color.Transparent, Color.Transparent)
      1 -> listOf(colors[0], colors[0])
      else -> colors
    }
  return when (gradient) {
    is Gradient.LinearGradient -> {
      val start = sanitizePoint(gradient.start)
      val end = sanitizePoint(gradient.end)
      if (stops != null) Brush.linearGradient(*stops, start = start, end = end, tileMode = gradient.tileMode)
      else Brush.linearGradient(safeColors, start = start, end = end, tileMode = gradient.tileMode)
    }
    is Gradient.RadialGradient -> {
      val center = sanitizeCenter(gradient.center)
      val radius = sanitizeRadius(gradient.radius)
      if (stops != null) Brush.radialGradient(*stops, center = center, radius = radius, tileMode = gradient.tileMode)
      else Brush.radialGradient(safeColors, center = center, radius = radius, tileMode = gradient.tileMode)
    }
    is Gradient.SweepGradient -> {
      val center = sanitizeCenter(gradient.center)
      if (stops != null) Brush.sweepGradient(*stops, center = center) else Brush.sweepGradient(safeColors, center = center)
    }
  }
}
