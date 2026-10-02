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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/** Padding between the bounds of a gradient canvas and the gradient it previews. */
internal val CANVAS_PADDING = 16.dp

/**
 * Interactive preview of a mesh gradient. While [showPoints] is set, each vertex in [meshPoints] gets a handle that can be clicked and,
 * unless it is a corner pinned by [constrainEdgePoints], dragged.
 *
 * @param onPointDrag called with the normalized position a vertex is dragged to.
 * @param onPointClick called when a vertex handle is clicked without being dragged.
 */
@Composable
internal fun GradientCanvas(
  meshPoints: List<List<MeshGradientPoint>>,
  showPoints: Boolean,
  onPointDrag: (row: Int, col: Int, offset: Offset) -> Unit,
  modifier: Modifier = Modifier,
  hasBicubicColor: Boolean = false,
  constrainEdgePoints: Boolean = true,
  onPointClick: ((row: Int, col: Int) -> Unit)? = null,
) {
  if (meshPoints.isEmpty() || meshPoints[0].isEmpty()) return

  val currentMeshPoints by rememberUpdatedState(meshPoints)
  val currentOnPointDrag by rememberUpdatedState(onPointDrag)
  val currentOnPointClick by rememberUpdatedState(onPointClick)

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .fillMaxSize()
        .then(
          if (showPoints) {
            Modifier.pointerInput(constrainEdgePoints) {
              detectVertexGestures(
                meshPoints = { currentMeshPoints },
                constrainEdgePoints = constrainEdgePoints,
                onDrag = { vertex, position -> currentOnPointDrag(vertex.row, vertex.col, position) },
                onClick = { vertex -> currentOnPointClick?.invoke(vertex.row, vertex.col) },
              )
            }
          } else {
            Modifier
          }
        ),
  ) {
    Box(modifier = Modifier.padding(CANVAS_PADDING).fillMaxSize()) {
      MeshGradient(
        rows = meshPoints.size - 1,
        columns = meshPoints[0].size - 1,
        points = meshPoints,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).fillMaxSize(),
        hasBicubicColor = hasBicubicColor,
      ) {
        Spacer(Modifier.fillMaxSize())
      }

      Layout(
        content = {
          if (showPoints) {
            meshPoints.forEachIndexed { rowIdx, row ->
              row.forEachIndexed { colIdx, point ->
                PointCursor(
                  row = rowIdx,
                  col = colIdx,
                  color = point.color,
                  movable = !isPinnedVertex(meshPoints, rowIdx, colIdx, constrainEdgePoints),
                  onSelect = onPointClick?.let { onClick -> { onClick(rowIdx, colIdx) } },
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

/** Returns true if the vertex at ([row], [col]) is a corner that can't be moved because of [constrainEdgePoints]. */
private fun isPinnedVertex(meshPoints: List<List<MeshGradientPoint>>, row: Int, col: Int, constrainEdgePoints: Boolean): Boolean =
  constrainEdgePoints && (row == 0 || row == meshPoints.lastIndex) && (col == 0 || col == meshPoints[row].lastIndex)

/**
 * Detects clicks and drags of the vertex handles drawn on a [GradientCanvas]. Gestures are tracked on the stationary canvas rather than on
 * the handles, so a single hit test decides which vertex a gesture applies to:
 * - A press released before the pointer moves past the touch slop clicks the nearest handle under the pointer.
 * - Moving past the touch slop drags the nearest movable handle under the pointer, so a pinned corner overlapping a movable vertex doesn't
 *   block dragging it. The vertex is moved by the total pointer movement since the press, including the touch slop, so it stays under the
 *   pointer even though its handle is laid out again while it moves. If only pinned handles are under the pointer, the gesture does nothing
 *   and its movement is not consumed.
 *
 * Only the primary mouse button starts a gesture.
 *
 * @param onDrag called with the dragged vertex and its new normalized position.
 * @param onClick called with the clicked vertex.
 */
private suspend fun PointerInputScope.detectVertexGestures(
  meshPoints: () -> List<List<MeshGradientPoint>>,
  constrainEdgePoints: Boolean,
  onDrag: (vertex: VertexIndex, position: Offset) -> Unit,
  onClick: (vertex: VertexIndex) -> Unit,
) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
    // Matches the rounding of the padding modifier that insets the gradient and the handles.
    val paddingPx = CANVAS_PADDING.roundToPx().toFloat()
    val innerWidth = size.width - 2 * paddingPx
    val innerHeight = size.height - 2 * paddingPx
    if (innerWidth <= 0f || innerHeight <= 0f) return@awaitEachGesture

    val points = meshPoints()
    val origin = Offset(paddingPx, paddingPx)
    val hitRadius = POINT_CURSOR_SIZE.toPx() / 2
    var clickedVertex: VertexIndex? = null
    var clickedDistance = Float.MAX_VALUE
    var draggedVertex: VertexIndex? = null
    var draggedDistance = Float.MAX_VALUE
    points.forEachIndexed { row, rowPoints ->
      rowPoints.forEachIndexed { col, point ->
        val center = origin + Offset(point.position.x * innerWidth, point.position.y * innerHeight)
        val distance = (center - down.position).getDistance()
        if (distance <= hitRadius) {
          if (distance <= clickedDistance) {
            clickedVertex = VertexIndex(row, col)
            clickedDistance = distance
          }
          if (distance <= draggedDistance && !isPinnedVertex(points, row, col, constrainEdgePoints)) {
            draggedVertex = VertexIndex(row, col)
            draggedDistance = distance
          }
        }
      }
    }
    val vertexToClick = clickedVertex ?: return@awaitEachGesture
    val vertexToDrag = draggedVertex

    // Movement past the touch slop is only consumed if there is a vertex to drag. Otherwise, the gesture is not a click either.
    var isPastTouchSlop = false
    val slopChange =
      awaitTouchSlopOrCancellation(down.id) { change, _ ->
        isPastTouchSlop = true
        if (vertexToDrag != null) change.consume()
      }
    if (slopChange == null || vertexToDrag == null) {
      val up = currentEvent.changes.firstOrNull { it.id == down.id }
      if (!isPastTouchSlop && up != null && up.changedToUp()) {
        up.consume()
        onClick(vertexToClick)
      }
      return@awaitEachGesture
    }

    val start = points[vertexToDrag.row][vertexToDrag.col].position

    fun moveTo(pointerPosition: Offset) {
      val total = pointerPosition - down.position
      val x = (start.x + total.x / innerWidth).coerceIn(0f, 1f)
      val y = (start.y + total.y / innerHeight).coerceIn(0f, 1f)
      onDrag(vertexToDrag, Offset(x = x, y = y))
    }

    moveTo(slopChange.position)
    drag(slopChange.id) { change ->
      change.consume()
      moveTo(change.position)
    }
  }
}
