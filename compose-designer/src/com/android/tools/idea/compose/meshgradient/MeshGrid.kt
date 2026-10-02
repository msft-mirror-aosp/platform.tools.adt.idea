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
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color

/** Smallest number of vertex rows or columns the mesh editor supports. */
internal const val MIN_MESH_DIMENSION = 2

/** Largest number of vertex rows or columns the mesh editor supports. */
internal const val MAX_MESH_DIMENSION = 10

/** Position of a vertex in the mesh grid: its [row] and [col] index. */
internal data class VertexIndex(val row: Int, val col: Int)

/** Returns this vertex moved to [newPosition]. Its position expression is kept only if the position doesn't change. */
internal fun MeshGradientPoint.withPosition(newPosition: Offset): MeshGradientPoint =
  if (newPosition == position) this else copy(position = newPosition, positionExpression = null)

/** Returns this vertex with [newColor]. Its color expression is kept only if the color doesn't change. */
internal fun MeshGradientPoint.withColor(newColor: Color): MeshGradientPoint =
  if (newColor == color) this else copy(color = newColor, colorExpression = null)

/** Returns true if [points] is a rectangular grid of at least [MIN_MESH_DIMENSION] x [MIN_MESH_DIMENSION] vertices. */
internal fun isValidMeshGrid(points: List<List<MeshGradientPoint>>): Boolean {
  if (points.size < MIN_MESH_DIMENSION) return false
  val cols = points[0].size
  return cols >= MIN_MESH_DIMENSION && points.all { it.size == cols }
}

/**
 * Returns [points] resized to [newRows] x [newCols] vertices, keeping as many of the existing vertices as possible.
 *
 * The first and last rows and columns are always kept, so a mesh whose border vertices sit on the canvas edges keeps covering the canvas.
 * Interior vertices keep their row and column index while they still fit:
 * - When growing, the new rows (columns) are inserted, evenly spaced, between the last two existing ones.
 * - When shrinking, the interior rows (columns) closest to the end are dropped.
 *
 * Kept vertices are copied unchanged, including their expressions and control points. Inserted vertices are linearly interpolated between
 * their two neighbors and take the color of the nearest one, so they only use colors that are already part of the mesh. They have no
 * expressions and no control points.
 *
 * The result depends on the order of the resizes: growing and then shrinking back restores the original mesh, but shrinking drops vertices
 * for good, and growing again inserts interpolated vertices in their place.
 *
 * [points] must satisfy [isValidMeshGrid].
 */
internal fun resizeMeshGrid(points: List<List<MeshGradientPoint>>, newRows: Int, newCols: Int): List<List<MeshGradientPoint>> {
  require(isValidMeshGrid(points)) { "Cannot resize an invalid mesh grid" }
  require(newRows >= MIN_MESH_DIMENSION && newCols >= MIN_MESH_DIMENSION) { "Invalid mesh size $newRows x $newCols" }
  val resizedColumns = points.map { row -> resizeKeepingEnds(row, newCols, ::interpolateVertex) }
  return resizeKeepingEnds(resizedColumns, newRows) { above, below, fraction ->
    above.zip(below) { a, b -> interpolateVertex(a, b, fraction) }
  }
}

/** Returns the evenly distributed position of the vertex at ([row], [col]) in a grid of [rowCount] x [colCount] vertices. */
internal fun evenMeshPosition(row: Int, col: Int, rowCount: Int, colCount: Int): Offset =
  Offset(x = evenFraction(col, colCount), y = evenFraction(row, rowCount))

private fun evenFraction(index: Int, count: Int): Float = if (count > 1) index.toFloat() / (count - 1) else 0f

private fun <T> resizeKeepingEnds(items: List<T>, newSize: Int, interpolate: (T, T, Float) -> T): List<T> {
  val size = items.size
  return when {
    newSize == size -> items
    newSize < size -> items.subList(0, newSize - 1) + items.last()
    else -> {
      val beforeLast = items[size - 2]
      val last = items[size - 1]
      val insertedCount = newSize - size
      val inserted = List(insertedCount) { i -> interpolate(beforeLast, last, (i + 1f) / (insertedCount + 1)) }
      items.subList(0, size - 1) + inserted + last
    }
  }
}

private fun interpolateVertex(start: MeshGradientPoint, stop: MeshGradientPoint, fraction: Float): MeshGradientPoint =
  MeshGradientPoint(
    position = lerp(start.position, stop.position, fraction),
    color = if (fraction <= 0.5f) start.color else stop.color,
  )
