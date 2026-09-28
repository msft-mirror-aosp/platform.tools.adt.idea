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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.preview.message
import org.jetbrains.jewel.ui.component.Text

@Composable
fun GradientCanvas(
  meshPoints: List<List<MeshGradientPoint>>,
  showPoints: Boolean,
  onPointDrag: (row: Int, col: Int, offset: Offset) -> Unit,
  modifier: Modifier = Modifier,
  hasBicubicColor: Boolean = false,
  constrainEdgePoints: Boolean = true,
  onTogglePoints: () -> Unit = {},
  onPointClick: ((row: Int, col: Int) -> Unit)? = null,
) {
  if (meshPoints.isEmpty() || meshPoints[0].isEmpty()) return

  val currentMeshPoints by rememberUpdatedState(meshPoints)
  val currentOnTogglePoints by rememberUpdatedState(onTogglePoints)
  val currentOnPointDrag by rememberUpdatedState(onPointDrag)
  val currentOnPointClick by rememberUpdatedState(onPointClick)

  Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(
      modifier = Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnTogglePoints() }) }.padding(16.dp).fillMaxSize()
    ) {
      val maxWidth = constraints.maxWidth
      val maxHeight = constraints.maxHeight

      fun handlePointDrag(row: Int, col: Int, offsetX: Float, offsetY: Float) {
        if (maxWidth <= 0 || maxHeight <= 0) return
        val currentPoint = currentMeshPoints.getOrNull(row)?.getOrNull(col) ?: return
        val currentOffset = currentPoint.position

        val x = (currentOffset.x + (offsetX / maxWidth)).coerceIn(0f, 1f)
        val y = (currentOffset.y + (offsetY / maxHeight)).coerceIn(0f, 1f)

        currentOnPointDrag(row, col, Offset(x = x, y = y))
      }

      MeshGradient(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).fillMaxSize(),
        rows = meshPoints.size - 1,
        columns = meshPoints[0].size - 1,
        hasBicubicColor = hasBicubicColor,
        points = meshPoints,
        showPoints = showPoints,
      ) {
        Spacer(Modifier.fillMaxSize())
      }

      Layout(
        content = {
          if (showPoints) {
            val maxRow = meshPoints.size - 1
            meshPoints.forEachIndexed { rowIdx, row ->
              val maxCol = row.size - 1
              row.forEachIndexed { colIdx, col ->
                val isCorner = (rowIdx == 0 || rowIdx == maxRow) && (colIdx == 0 || colIdx == maxCol)
                val isMovable = !(constrainEdgePoints && isCorner)
                PointCursor(
                  xIndex = colIdx,
                  yIndex = rowIdx,
                  color = col.color,
                  enabled = isMovable,
                  modifier =
                    Modifier.pointerInput(rowIdx, colIdx) {
                        detectTapGestures(onTap = { currentOnPointClick?.invoke(rowIdx, colIdx) })
                      }
                      .pointerInput(rowIdx, colIdx, maxWidth, maxHeight) {
                        detectDragGestures { change, dragAmount ->
                          change.consume()
                          handlePointDrag(row = rowIdx, col = colIdx, offsetX = dragAmount.x, offsetY = dragAmount.y)
                        }
                      },
                )
              }
            }
          }
        },
        measurePolicy = { measurables, constraints ->
          val placeables = measurables.map { measurable -> measurable.measure(constraints) }

          layout(constraints.maxWidth, constraints.maxHeight) {
            if (placeables.isNotEmpty()) {
              val cursorWidth = placeables[0].width
              val cursorHeight = placeables[0].height
              val cols = meshPoints[0].size

              placeables.forEachIndexed { i, placeable ->
                val row = i / cols
                val col = i % cols
                val point = meshPoints.getOrNull(row)?.getOrNull(col) ?: return@forEachIndexed

                val xOffset = point.position.x
                val yOffset = point.position.y

                val x = ((xOffset * constraints.maxWidth) - cursorWidth / 2).toInt()
                val y = ((yOffset * constraints.maxHeight) - cursorHeight / 2).toInt()
                placeable.place(x, y)
              }
            }
          }
        },
      )
    }
  }
}

@Composable
fun StandardGradientCanvas(state: GradientEditorState, modifier: Modifier = Modifier) {
  val currentState by rememberUpdatedState(state)

  Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.padding(16.dp).fillMaxSize()) {
      val maxWidth = constraints.maxWidth
      val maxHeight = constraints.maxHeight

      Box(
        Modifier.clip(RoundedCornerShape(8.dp)).fillMaxSize().background(createPreviewBrush(state, maxWidth.toFloat(), maxHeight.toFloat()))
      )

      val activeColors = state.colors
      val primaryColor = activeColors.firstOrNull() ?: Color.White
      val secondaryColor = activeColors.lastOrNull() ?: Color.White

      Layout(
        content = {
          when (state.currentType) {
            GradientType.LINEAR -> {
              LabeledPointCursor(
                label = message("gradient.editor.canvas.start"),
                color = primaryColor,
                modifier =
                  Modifier.pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, dragAmount ->
                      change.consume()
                      if (maxWidth <= 0 || maxHeight <= 0) return@detectDragGestures
                      val curX = currentState.start.safeX(0f).coerceIn(0f, 1f)
                      val curY = currentState.start.safeY(0f).coerceIn(0f, 1f)
                      val newX = (curX + (dragAmount.x / maxWidth)).coerceIn(0f, 1f)
                      val y = (curY + (dragAmount.y / maxHeight)).coerceIn(0f, 1f)
                      currentState.start = Offset(newX, y)
                    }
                  },
              )
              LabeledPointCursor(
                label = message("gradient.editor.canvas.end"),
                color = secondaryColor,
                modifier =
                  Modifier.pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, dragAmount ->
                      change.consume()
                      if (maxWidth <= 0 || maxHeight <= 0) return@detectDragGestures
                      val curX = currentState.end.safeX(1f).coerceIn(0f, 1f)
                      val curY = currentState.end.safeY(1f).coerceIn(0f, 1f)
                      val newX = (curX + (dragAmount.x / maxWidth)).coerceIn(0f, 1f)
                      val y = (curY + (dragAmount.y / maxHeight)).coerceIn(0f, 1f)
                      currentState.end = Offset(newX, y)
                    }
                  },
              )
            }
            GradientType.RADIAL,
            GradientType.SWEEP -> {
              LabeledPointCursor(
                label = message("gradient.editor.canvas.center"),
                color = primaryColor,
                modifier =
                  Modifier.pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, dragAmount ->
                      change.consume()
                      if (maxWidth <= 0 || maxHeight <= 0) return@detectDragGestures
                      val curX = currentState.center.safeX(0.5f).coerceIn(0f, 1f)
                      val curY = currentState.center.safeY(0.5f).coerceIn(0f, 1f)
                      val newX = (curX + (dragAmount.x / maxWidth)).coerceIn(0f, 1f)
                      val y = (curY + (dragAmount.y / maxHeight)).coerceIn(0f, 1f)
                      currentState.center = Offset(newX, y)
                    }
                  },
              )
            }
            else -> {}
          }
        },
        measurePolicy = { measurables, constraints ->
          val placeables = measurables.map { measurable -> measurable.measure(constraints) }
          layout(constraints.maxWidth, constraints.maxHeight) {
            if (placeables.isNotEmpty()) {
              val cursorWidth = placeables[0].width
              val cursorHeight = placeables[0].height

              when (state.currentType) {
                GradientType.LINEAR -> {
                  if (placeables.size == 2) {
                    val startNormX = state.start.safeX(0f).coerceIn(0f, 1f)
                    val startNormY = state.start.safeY(0f).coerceIn(0f, 1f)
                    val cursorStartPixelX = ((startNormX * constraints.maxWidth) - cursorWidth / 2).toInt()
                    val cursorStartPixelY = ((startNormY * constraints.maxHeight) - cursorHeight / 2).toInt()
                    placeables[0].place(cursorStartPixelX, cursorStartPixelY)

                    val endNormX = state.end.safeX(1f).coerceIn(0f, 1f)
                    val endNormY = state.end.safeY(1f).coerceIn(0f, 1f)
                    val cursorEndPixelX = ((endNormX * constraints.maxWidth) - cursorWidth / 2).toInt()
                    val cursorEndPixelY = ((endNormY * constraints.maxHeight) - cursorHeight / 2).toInt()
                    placeables[1].place(cursorEndPixelX, cursorEndPixelY)
                  }
                }
                GradientType.RADIAL,
                GradientType.SWEEP -> {
                  if (placeables.size == 1) {
                    val centerNormX = state.center.safeX(0.5f).coerceIn(0f, 1f)
                    val centerNormY = state.center.safeY(0.5f).coerceIn(0f, 1f)
                    val cursorCenterPixelX = ((centerNormX * constraints.maxWidth) - cursorWidth / 2).toInt()
                    val cursorCenterPixelY = ((centerNormY * constraints.maxHeight) - cursorHeight / 2).toInt()
                    placeables[0].place(cursorCenterPixelX, cursorCenterPixelY)
                  }
                }
                else -> {}
              }
            }
          }
        },
      )
    }
  }
}

@Composable
fun LabeledPointCursor(label: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier.size(24.dp).drawWithContent {
        drawCircle(color = color)
        val borderColor = if (enabled) Color.White else Color.LightGray
        drawCircle(color = borderColor, style = Stroke(width = 4.dp.toPx()))
        drawContent()
      },
  ) {
    val textColor =
      if (!enabled) {
        Color.LightGray
      } else if (color.luminance() > 0.5f) {
        Color.Black
      } else {
        Color.White
      }
    Text(label.take(1), color = textColor)
  }
}
