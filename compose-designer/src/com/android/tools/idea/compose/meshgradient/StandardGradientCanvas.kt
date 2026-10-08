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

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.android.tools.idea.compose.meshgradient.components.GRADIENT_HANDLE_SIZE
import com.android.tools.idea.compose.meshgradient.components.GradientHandle
import com.android.tools.idea.compose.meshgradient.components.handleDrag
import com.android.tools.idea.compose.preview.message
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme

private val PREVIEW_SHAPE = RoundedCornerShape(8.dp)

/**
 * Previews the standard Brush gradient of [state] and lets the user drag its points.
 *
 * The gradient is drawn into an area of [GradientEditorState.previewSize] pixels, scaled to fit, so that the preview shows what a component
 * of that size would show at runtime.
 */
@Composable
internal fun StandardGradientCanvas(state: GradientEditorState, modifier: Modifier = Modifier) {
  val previewSize = state.previewSize
  Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().padding(16.dp)) {
    BoxWithConstraints(modifier = Modifier.aspectRatio(previewSize.width / previewSize.height)) {
      val canvasScale = constraints.maxWidth / previewSize.width
      val density = LocalDensity.current
      // Radius of a handle, in canvas pixels. Handles are centered on points clamped to the preview, so they can stick out of it by this
      // much: the drag detector extends past the preview by the same amount so the whole handle can be grabbed.
      val handleRadius = with(density) { (GRADIENT_HANDLE_SIZE / 2).roundToPx() }
      val points = brushPoints(state)
      val dragOrigin = remember { PointDragOrigin() }

      // Position of the handle of a point, in brush pixels.
      fun handlePosition(point: BrushPoint, value: Offset = point.value()): Offset =
        clampToPreview(point.resolve(value, previewSize), previewSize)

      Box(
        Modifier.fillMaxSize()
          .outset(handleRadius)
          .handleDrag(
            findHandle = { position ->
              val previewPosition = position - Offset(handleRadius.toFloat(), handleRadius.toFloat())
              // The closest handle wins; on a tie, the one drawn last (on top).
              points
                .asReversed()
                .map { it to (handlePosition(it) * canvasScale - previewPosition).getDistance() }
                .filter { (_, distance) -> distance <= handleRadius }
                .minByOrNull { (_, distance) -> distance }
                ?.first
            },
            onDragStart = { point ->
              dragOrigin.point = point.value()
              dragOrigin.handlePosition = handlePosition(point, dragOrigin.point)
            },
            onDrag = { point, totalDelta ->
              point.onValueChange(draggedPoint(dragOrigin.point, dragOrigin.handlePosition, totalDelta, canvasScale))
            },
          )
          .padding(with(density) { handleRadius.toDp() })
      ) {
        Box(
          Modifier.fillMaxSize().clip(PREVIEW_SHAPE).border(1.dp, JewelTheme.globalColors.borders.normal, PREVIEW_SHAPE).drawWithCache {
            val brushSize = state.previewSize
            val brush = state.toBrushGradient()?.let(::createPreviewBrush)
            onDrawBehind {
              if (brush != null) {
                scale(size.width / brushSize.width, pivot = Offset.Zero) { drawRect(brush, size = brushSize) }
              }
            }
          }
        )
        for (point in points) {
          GradientHandle(
            glyph = point.glyph,
            contentDescription = point.contentDescription,
            color = point.color,
            modifier =
              Modifier.offset {
                val center = handlePosition(point) * canvasScale
                IntOffset(center.x.roundToInt() - handleRadius, center.y.roundToInt() - handleRadius)
              },
          )
        }
      }
    }
  }
}

/** Grows the element by [outset] pixels on each side, without changing the size and position it is laid out with. */
private fun Modifier.outset(outset: Int): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints.offset(2 * outset, 2 * outset))
  layout(placeable.width - 2 * outset, placeable.height - 2 * outset) { placeable.place(-outset, -outset) }
}

/**
 * A draggable point of a Brush gradient.
 *
 * @property value returns the current value of the point, in brush pixels as written in code. It is only read during layout, when looking
 *   for the dragged handle and when a drag starts, so moving the point does not recompose the canvas.
 * @property resolve resolves automatic values of the point against the preview size to place the handle.
 */
private class BrushPoint(
  val glyph: String,
  val contentDescription: String,
  val color: Color,
  val value: () -> Offset,
  val resolve: (Offset, Size) -> Offset,
  val onValueChange: (Offset) -> Unit,
)

/** Returns the draggable points of the Brush gradient of [state], in drawing order. */
private fun brushPoints(state: GradientEditorState): List<BrushPoint> {
  val stops = state.stops
  val firstColor = stops.firstOrNull()?.color ?: Color.White
  val lastColor = stops.lastOrNull()?.color ?: Color.White
  return when (state.currentType) {
    GradientType.LINEAR ->
      listOf(
        BrushPoint(
          glyph = message("gradient.editor.canvas.start.glyph"),
          contentDescription = message("gradient.editor.canvas.start"),
          color = firstColor,
          value = { state.start },
          resolve = ::resolveLinearPoint,
          onValueChange = { state.start = it },
        ),
        BrushPoint(
          glyph = message("gradient.editor.canvas.end.glyph"),
          contentDescription = message("gradient.editor.canvas.end"),
          color = lastColor,
          value = { state.end },
          resolve = ::resolveLinearPoint,
          onValueChange = { state.end = it },
        ),
      )
    GradientType.RADIAL,
    GradientType.SWEEP ->
      listOf(
        BrushPoint(
          glyph = message("gradient.editor.canvas.center.glyph"),
          contentDescription = message("gradient.editor.canvas.center"),
          color = firstColor,
          value = { state.center },
          resolve = ::resolveCenter,
          onValueChange = { state.center = it },
        )
      )
    GradientType.MESH -> emptyList()
  }
}

/** Holds the state captured when a drag of a [BrushPoint] starts. */
private class PointDragOrigin {
  var point: Offset = Offset.Zero
  var handlePosition: Offset = Offset.Zero
}
