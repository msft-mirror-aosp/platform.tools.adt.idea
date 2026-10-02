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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import com.android.tools.idea.compose.preview.message
import java.util.Locale

enum class GradientType {
  MESH,
  LINEAR,
  RADIAL,
  SWEEP;

  /** The localized name of the gradient type, e.g. "Linear Gradient". */
  internal val displayName: String
    get() =
      when (this) {
        MESH -> message("gradient.editor.kind.mesh")
        LINEAR -> message("gradient.editor.kind.linear")
        RADIAL -> message("gradient.editor.kind.radial")
        SWEEP -> message("gradient.editor.kind.sweep")
      }
}

class GradientEditorState {
  var currentType by mutableStateOf(GradientType.MESH)

  // Linear/Radial/Sweep specific state. Offsets and radius are in pixels, as written in code; see BrushGeometry.kt for the meaning of
  // infinite and unspecified values. The defaults are the Compose defaults, which adapt to any size.

  /** Color stops of the Brush gradient. Either all stops have a fraction (`colorStops`) or none has (`colors`). */
  internal var stops: List<ColorStop> by mutableStateOf(ColorStops.defaultStops())
    private set

  var start by mutableStateOf(Offset.Zero)
  var end by mutableStateOf(Offset.Infinite)
  var center by mutableStateOf(Offset.Unspecified)
  var radius by mutableFloatStateOf(Float.POSITIVE_INFINITY)
  var tileMode by mutableStateOf(TileMode.Clamp)

  /** Reference size, in pixels, of the area the Brush gradient is previewed in. Only used to preview and drag; never written to code. */
  internal var previewSize by mutableStateOf(DEFAULT_PREVIEW_SIZE)

  /** Colors of [stops], in order. */
  val colors: List<Color>
    get() = stops.map { it.color }

  /** `fraction to color` pairs of [stops], or an empty list when the stops have no explicit fractions. */
  val colorStops: List<Pair<Float, Color>>
    get() =
      if (ColorStops.hasExplicitFractions(stops)) stops.mapNotNull { stop -> stop.fraction?.let { it to stop.color } } else emptyList()

  /** Replaces the stops with the parsed [colors] or [colorStops] and adds their colors to [availableColors]. */
  internal fun loadColorStops(colors: List<Color>, colorStops: List<Pair<Float, Color>>?) {
    stops = ColorStops.fromParsed(colors, colorStops)
    addAvailableColors(stops.map { it.color })
  }

  /** Adds a stop with the given [color] without moving existing stops, and returns its [ColorStop.id]. */
  internal fun addColorStop(color: Color): Long {
    val (index, fraction) = ColorStops.insertionPoint(stops)
    val stop = ColorStop(color, fraction)
    stops = stops.toMutableList().apply { add(index, stop) }
    return stop.id
  }

  internal fun removeColorStop(id: Long) {
    stops = ColorStops.remove(stops, id)
  }

  internal fun updateStopColor(id: Long, color: Color) {
    stops = ColorStops.updateColor(stops, id, color)
  }

  internal fun updateStopFraction(id: Long, fraction: Float) {
    stops = ColorStops.updateFraction(stops, id, fraction)
  }

  internal fun setExplicitFractions(explicit: Boolean) {
    stops = ColorStops.withExplicitFractions(stops, explicit)
  }

  /** Sets [previewSize] to the smallest size containing the current Brush gradient geometry. */
  internal fun fitPreviewSizeToBrush() {
    previewSize = inferPreviewSize(currentType, start, end, center, radius)
  }

  /** Returns the current Brush gradient, or `null` when editing a mesh gradient. */
  internal fun toBrushGradient(): Gradient? {
    val explicitStops = colorStops.takeIf { it.isNotEmpty() }
    return when (currentType) {
      GradientType.LINEAR -> Gradient.LinearGradient(colors, explicitStops, start, end, tileMode)
      GradientType.RADIAL -> Gradient.RadialGradient(colors, explicitStops, center, radius, tileMode)
      GradientType.SWEEP -> Gradient.SweepGradient(colors, explicitStops, center)
      GradientType.MESH -> null
    }
  }

  var rows by mutableIntStateOf(3)
    private set

  var cols by mutableIntStateOf(4)
    private set

  var showPoints by mutableStateOf(true)
  var constrainEdgePoints by mutableStateOf(true)
  var hasBicubicColor by mutableStateOf(false)
  var hasDynamicOrUnresolvedValues by mutableStateOf(false)

  val meshPoints = mutableStateListOf<List<MeshGradientPoint>>()

  val generatedCode: String
    get() = generateCode()

  private val defaultColors =
    listOf(
      Color(0xFFF44336), // Red
      Color(0xFFE91E63), // Pink
      Color(0xFF9C27B0), // Purple
      Color(0xFF673AB7), // Deep Purple
      Color(0xFF3F51B5), // Indigo
      Color(0xFF2196F3), // Blue
      Color(0xFF03A9F4), // Light Blue
      Color(0xFF00BCD4), // Cyan
      Color(0xFF009688), // Teal
      Color(0xFF4CAF50), // Green
    )

  val availableColors = mutableStateListOf<Color>()

  init {
    availableColors.addAll(defaultColors)
    generateMeshPoints()
  }

  fun addAvailableColors(colors: Iterable<Color>) {
    colors.forEach { color ->
      if (color !in availableColors) {
        availableColors.add(color)
      }
    }
  }

  fun updateRows(value: Int) {
    rows = value.coerceIn(2, 10)
    generateMeshPoints()
  }

  fun updateCols(value: Int) {
    cols = value.coerceIn(2, 10)
    generateMeshPoints()
  }

  /**
   * Loads the mesh [newPoints] in the editor. The number of rows and columns is the size of the grid, which must be rectangular and have at
   * least two vertices on each axis.
   */
  fun loadMesh(newPoints: List<List<MeshGradientPoint>>, newHasBicubicColor: Boolean = false) {
    require(newPoints.size >= 2 && newPoints[0].size >= 2 && newPoints.all { it.size == newPoints[0].size }) {
      "The mesh must be a rectangular grid with at least 2 x 2 vertices"
    }
    rows = newPoints.size
    cols = newPoints[0].size
    hasBicubicColor = newHasBicubicColor
    meshPoints.clear()
    meshPoints.addAll(newPoints)

    val loadedColors = newPoints.flatten().map { it.color }.distinct()
    loadedColors.forEach { color ->
      if (color !in availableColors) {
        availableColors.add(color)
      }
    }
  }

  fun updateMeshPoint(row: Int, col: Int, offset: Offset) {
    if (row !in meshPoints.indices || col !in meshPoints[row].indices) return

    val colorPointsInRow = meshPoints[row].toMutableList()

    var newX = offset.x
    var newY = offset.y

    if (constrainEdgePoints) {
      newX =
        when (col) {
          0 -> 0f
          colorPointsInRow.size - 1 -> 1f
          else -> newX
        }
      newY =
        when (row) {
          0 -> 0f
          meshPoints.size - 1 -> 1f
          else -> newY
        }
    }

    val newPoint = colorPointsInRow[col].copy(position = Offset(x = newX, y = newY), positionExpression = null)
    colorPointsInRow[col] = newPoint

    meshPoints[row] = colorPointsInRow.toList()
  }

  fun updateVertexColor(row: Int, col: Int, color: Color) {
    if (row !in meshPoints.indices || col !in meshPoints[row].indices) return
    val colorPointsInRow = meshPoints[row].toMutableList()
    val newPoint = colorPointsInRow[col].copy(color = color, colorExpression = null)
    colorPointsInRow[col] = newPoint
    meshPoints[row] = colorPointsInRow.toList()
  }

  fun distributeMeshPointsEvenly() {
    val newPoints = meshPoints.mapIndexed { rowIdx, currentPoints ->
      val newRowPoints = mutableListOf<MeshGradientPoint>()
      val yPosition = if (rows > 1) rowIdx.toFloat() / (rows - 1) else 0f
      repeat(cols) { colIdx ->
        val xPosition = if (cols > 1) colIdx.toFloat() / (cols - 1) else 0f
        newRowPoints.add(currentPoints[colIdx].copy(position = Offset(xPosition, yPosition), positionExpression = null))
      }
      newRowPoints.toList()
    }
    meshPoints.clear()
    meshPoints.addAll(newPoints)
  }

  fun updateAllPoints(transform: (MeshGradientPoint) -> MeshGradientPoint) {
    val updated = meshPoints.map { row -> row.map(transform) }
    meshPoints.clear()
    meshPoints.addAll(updated)
  }

  fun updatePaletteAndMeshColor(oldColor: Color, newColor: Color) {
    val index = availableColors.indexOf(oldColor)
    if (index != -1) {
      availableColors[index] = newColor
    }
    updateAllPoints { point ->
      if (point.color == oldColor) {
        point.copy(color = newColor, colorExpression = null)
      } else {
        point
      }
    }
  }

  private fun generateMeshPoints() {
    val newMeshPoints = mutableListOf<List<MeshGradientPoint>>()
    repeat(rows) { rowIdx ->
      val newPoints = mutableListOf<MeshGradientPoint>()
      val yPosition = if (rows > 1) rowIdx.toFloat() / (rows - 1) else 0f
      repeat(cols) { colIdx ->
        val xPosition = if (cols > 1) colIdx.toFloat() / (cols - 1) else 0f
        val color = availableColors[(rowIdx * cols + colIdx) % availableColors.size]
        newPoints.add(MeshGradientPoint(position = Offset(xPosition, yPosition), color = color))
      }
      newMeshPoints.add(newPoints.toList())
    }
    meshPoints.clear()
    meshPoints.addAll(newMeshPoints)
  }

  private fun generateCode(): String {
    return when (currentType) {
      GradientType.MESH -> generateMeshCode()
      GradientType.LINEAR,
      GradientType.RADIAL,
      GradientType.SWEEP -> generateBrushCode()
    }
  }

  private fun generateMeshCode(): String {
    val sb = StringBuilder()
    sb.append("val gradientPainter = remember {\n")
    sb.append(
      String.format(
        Locale.US,
        "    MeshGradientPainter(rows = %d, columns = %d, hasBicubicColor = %b) {\n",
        rows - 1,
        cols - 1,
        hasBicubicColor,
      )
    )

    meshPoints.forEachIndexed { rowIdx, row ->
      row.forEachIndexed { colIdx, point ->
        sb.append("        ")
        sb.append(formatSetVertexCall(rowIdx, colIdx, point))
        sb.append("\n")
      }
    }

    sb.append("    }\n")
    sb.append("}\n\n")

    return sb.toString()
  }

  private fun generateBrushCode(): String {
    val expression = toBrushGradient()?.let(BrushSourceGenerator::generate) ?: return ""
    return "val gradientBrush = remember {\n${expression.prependIndent("    ")}\n}"
  }
}
