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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import java.util.Locale

enum class GradientType {
  MESH,
  LINEAR,
  RADIAL,
  SWEEP,
}

class GradientEditorState {
  var currentType by mutableStateOf(GradientType.MESH)

  // Linear/Radial/Sweep specific state
  val colors = mutableStateListOf<Color>(Color.Red, Color.Blue)
  val colorStops = mutableStateListOf<Pair<Float, Color>>()
  var start by mutableStateOf(Offset.Zero)
  var end by mutableStateOf(Offset(1f, 1f))
  var center by mutableStateOf(Offset(0.5f, 0.5f))
  var radius by mutableFloatStateOf(0.5f)
  var tileMode by mutableStateOf(TileMode.Clamp)

  fun addColor(color: Color) {
    colors.add(color)
    if (colorStops.isNotEmpty()) {
      colorStops.add(Pair(1f, color))
      redistributeStops()
    }
  }

  fun removeColor(index: Int) {
    if (colors.size <= 2) return
    if (index in colors.indices) {
      colors.removeAt(index)
    }
    if (colorStops.isNotEmpty() && index in colorStops.indices) {
      colorStops.removeAt(index)
      redistributeStops()
    }
  }

  fun updateColor(index: Int, newColor: Color) {
    if (index in colors.indices) {
      colors[index] = newColor
    }
    if (colorStops.isNotEmpty() && index in colorStops.indices) {
      colorStops[index] = Pair(colorStops[index].first, newColor)
    }
  }

  private fun redistributeStops() {
    val size = colorStops.size
    if (size < 2) return
    for (i in 0..<size) {
      val fraction = i.toFloat() / (size - 1)
      colorStops[i] = Pair(fraction, colorStops[i].second)
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

  fun loadMesh(
    newRows: Int,
    newCols: Int,
    newPoints: List<List<MeshGradientPoint>>,
    newHasBicubicColor: Boolean = false,
  ) {
    rows = newRows.coerceIn(2, 10)
    cols = newCols.coerceIn(2, 10)
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
      GradientType.LINEAR -> generateLinearCode()
      GradientType.RADIAL -> generateRadialCode()
      GradientType.SWEEP -> generateSweepCode()
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

  private fun generateColorsOrStopsCode(): String {
    return if (colorStops.size >= 2) {
      "colorStops = arrayOf(${colorStops.joinToString { "${generateFloatSource(it.first)} to Color(${it.second.toComposeHexLiteral()})" }})"
    } else {
      val safeColors =
        when {
          colors.size >= 2 -> colors.toList()
          colors.size == 1 -> listOf(colors[0], colors[0])
          else -> listOf(Color.Red, Color.Blue)
        }
      "colors = listOf(${safeColors.joinToString { "Color(${it.toComposeHexLiteral()})" }})"
    }
  }

  private fun generateLinearCode(): String {
    val colorsStr = generateColorsOrStopsCode()
    return """
        val gradientBrush = remember {
            Brush.linearGradient(
                $colorsStr,
                start = ${generateOffsetSource(start)},
                end = ${generateOffsetSource(end)},
                tileMode = TileMode.$tileMode
            )
        }
    """
      .trimIndent()
  }

  private fun generateRadialCode(): String {
    val colorsStr = generateColorsOrStopsCode()
    return """
        val gradientBrush = remember {
            Brush.radialGradient(
                $colorsStr,
                center = ${generateOffsetSource(center)},
                radius = ${generateFloatSource(radius)},
                tileMode = TileMode.$tileMode
            )
        }
    """
      .trimIndent()
  }

  private fun generateSweepCode(): String {
    val colorsStr = generateColorsOrStopsCode()
    return """
        val gradientBrush = remember {
            Brush.sweepGradient(
                $colorsStr,
                center = ${generateOffsetSource(center)}
            )
        }
    """
      .trimIndent()
  }
}

private fun resolveCanvasOffset(offset: Offset, width: Float, height: Float, defaultNormX: Float, defaultNormY: Float): Offset {
  if (offset == Offset.Unspecified) {
    return Offset(defaultNormX * width, defaultNormY * height)
  }
  val x =
    when {
      !offset.x.isFinite() -> defaultNormX * width
      offset.x in 0f..1f && width > 1f -> offset.x * width
      else -> offset.x
    }
  val y =
    when {
      !offset.y.isFinite() -> defaultNormY * height
      offset.y in 0f..1f && height > 1f -> offset.y * height
      else -> offset.y
    }
  return Offset(x, y)
}

fun createPreviewBrush(state: GradientEditorState, width: Float = 1f, height: Float = 1f): Brush {
  val colorStops = state.colorStops.toList()
  val safeColors =
    when {
      state.colors.size >= 2 -> state.colors.toList()
      state.colors.size == 1 -> listOf(state.colors[0], state.colors[0])
      else -> listOf(Color.Red, Color.Blue)
    }
  return when (state.currentType) {
    GradientType.LINEAR -> {
      val resolvedStart = resolveCanvasOffset(state.start, width, height, 0f, 0f)
      val resolvedEnd = resolveCanvasOffset(state.end, width, height, 1f, 1f)
      if (colorStops.size >= 2) {
        Brush.linearGradient(colorStops = colorStops.toTypedArray(), start = resolvedStart, end = resolvedEnd, tileMode = state.tileMode)
      } else {
        Brush.linearGradient(colors = safeColors, start = resolvedStart, end = resolvedEnd, tileMode = state.tileMode)
      }
    }
    GradientType.RADIAL -> {
      val resolvedCenter = resolveCanvasOffset(state.center, width, height, 0.5f, 0.5f)
      val minDim = minOf(width, height).coerceAtLeast(1f)
      val resolvedRadius =
        when {
          !state.radius.isFinite() -> minDim * 0.5f
          state.radius in 0f..1f && minDim > 1f -> (state.radius * minDim).coerceAtLeast(0.001f)
          else -> state.radius.coerceAtLeast(0.001f)
        }
      if (colorStops.size >= 2) {
        Brush.radialGradient(
          colorStops = colorStops.toTypedArray(),
          center = resolvedCenter,
          radius = resolvedRadius,
          tileMode = state.tileMode,
        )
      } else {
        Brush.radialGradient(colors = safeColors, center = resolvedCenter, radius = resolvedRadius, tileMode = state.tileMode)
      }
    }
    GradientType.SWEEP -> {
      val resolvedCenter = resolveCanvasOffset(state.center, width, height, 0.5f, 0.5f)
      if (colorStops.size >= 2) {
        Brush.sweepGradient(colorStops = colorStops.toTypedArray(), center = resolvedCenter)
      } else {
        Brush.sweepGradient(colors = safeColors, center = resolvedCenter)
      }
    }
    else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
  }
}
