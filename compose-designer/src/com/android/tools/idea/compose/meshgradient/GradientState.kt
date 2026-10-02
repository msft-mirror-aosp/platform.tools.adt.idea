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

import androidx.compose.runtime.derivedStateOf
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
import org.jetbrains.annotations.VisibleForTesting

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

  private val _meshPoints = mutableStateListOf<List<MeshGradientPoint>>()

  /** The mesh vertices, indexed by row and then by column. */
  internal val meshPoints: List<List<MeshGradientPoint>>
    get() = _meshPoints

  /** Source code for the current gradient. Only recomputed when the state it depends on changes. */
  val generatedCode: String by derivedStateOf { generateCode() }

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

  private val _availableColors = mutableStateListOf<Color>()

  /** The palette of colors offered for mesh vertices. Never contains duplicates once an edit session ends. */
  internal val availableColors: List<Color>
    get() = _availableColors

  /**
   * Incremented whenever the palette or vertex indices held by a [PaletteColorEdit] may stop being valid: when palette entries are removed
   * or merged, and when the mesh is resized or loaded. Appending palette entries keeps existing indices valid and doesn't change it.
   */
  private var editVersion = 0

  init {
    _availableColors.addAll(defaultColors)
    generateMeshPoints()
  }

  internal fun addAvailableColors(colors: Iterable<Color>) {
    colors.forEach { color ->
      if (color !in _availableColors) {
        _availableColors.add(color)
      }
    }
  }

  /** Sets the number of vertex rows, keeping existing vertices where possible. See [resizeMeshGrid]. */
  internal fun updateRows(value: Int) {
    resizeMesh(value.coerceIn(MIN_MESH_DIMENSION, MAX_MESH_DIMENSION), cols)
  }

  /** Sets the number of vertex columns, keeping existing vertices where possible. See [resizeMeshGrid]. */
  internal fun updateCols(value: Int) {
    resizeMesh(rows, value.coerceIn(MIN_MESH_DIMENSION, MAX_MESH_DIMENSION))
  }

  private fun resizeMesh(newRows: Int, newCols: Int) {
    rows = newRows
    cols = newCols
    if (_meshPoints.size == newRows && _meshPoints.all { it.size == newCols }) return
    editVersion++
    if (isValidMeshGrid(_meshPoints)) {
      replaceMesh(resizeMeshGrid(_meshPoints.toList(), newRows, newCols))
    } else {
      generateMeshPoints()
    }
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
    editVersion++
    _meshPoints.clear()
    _meshPoints.addAll(newPoints)

    val loadedColors = newPoints.flatten().map { it.color }.distinct()
    loadedColors.forEach { color ->
      if (color !in availableColors) {
        _availableColors.add(color)
      }
    }
  }

  /**
   * Moves the vertex at ([row], [col]) to [offset], snapping border vertices to their edge when [constrainEdgePoints] is set. The vertex's
   * position expression is only dropped if its position actually changes.
   */
  internal fun updateMeshPoint(row: Int, col: Int, offset: Offset) {
    val rowPoints = _meshPoints.getOrNull(row) ?: return
    val point = rowPoints.getOrNull(col) ?: return
    val target =
      if (constrainEdgePoints) {
        Offset(
          x =
            when (col) {
              0 -> 0f
              rowPoints.lastIndex -> 1f
              else -> offset.x
            },
          y =
            when (row) {
              0 -> 0f
              _meshPoints.lastIndex -> 1f
              else -> offset.y
            },
        )
      } else {
        offset
      }
    updatePoint(row, col, point.withPosition(target))
  }

  /** Sets the color of the vertex at ([row], [col]), dropping its color expression unless the color is unchanged. */
  internal fun updateVertexColor(row: Int, col: Int, color: Color) {
    val point = _meshPoints.getOrNull(row)?.getOrNull(col) ?: return
    updatePoint(row, col, point.withColor(color))
  }

  /** Spreads the vertices evenly over the canvas, keeping the position expressions of vertices that are already in place. */
  internal fun distributeMeshPointsEvenly() {
    val rowCount = _meshPoints.size
    replaceMesh(
      _meshPoints.mapIndexed { row, rowPoints ->
        rowPoints.mapIndexed { col, point -> point.withPosition(evenMeshPosition(row, col, rowCount, rowPoints.size)) }
      }
    )
  }

  /**
   * Removes the palette color at [index]. Vertices using the removed color switch to the first remaining palette color and lose their color
   * expression, so the canvas and the generated code stay in sync. The last remaining color can't be removed.
   */
  internal fun removePaletteColor(index: Int) {
    val removedColor = _availableColors.getOrNull(index) ?: return
    dedupePalette()
    if (_availableColors.size <= 1) return
    _availableColors.remove(removedColor)
    editVersion++
    val fallbackColor = _availableColors.first()
    updatePoints { point -> if (point.color == removedColor) point.copy(color = fallbackColor, colorExpression = null) else point }
  }

  /** Replaces the palette entry at [index] with [newColor], recoloring the vertices that use it. */
  @VisibleForTesting
  internal fun replacePaletteColor(index: Int, newColor: Color) {
    val edit = beginPaletteColorEdit(index) ?: return
    edit.update(newColor)
    edit.finish()
  }

  /**
   * Starts editing the palette entry at [index], e.g. while a color picker is open. The vertices using that color are captured now, so
   * intermediate colors never pull in unrelated vertices that happen to share them. The caller must call [PaletteColorEdit.finish] when the
   * edit ends.
   *
   * @return the edit session, or null if [index] is not a palette index.
   */
  internal fun beginPaletteColorEdit(index: Int): PaletteColorEdit? {
    val color = _availableColors.getOrNull(index) ?: return null
    dedupePalette()
    val vertices = buildSet {
      _meshPoints.forEachIndexed { row, rowPoints ->
        rowPoints.forEachIndexed { col, point -> if (point.color == color) add(VertexIndex(row, col)) }
      }
    }
    return PaletteColorEdit(_availableColors.indexOf(color), vertices, color)
  }

  /**
   * Starts adding a new palette entry, which is appended on the first [PaletteColorEdit.update]. The caller must call
   * [PaletteColorEdit.finish] when the edit ends.
   */
  internal fun beginNewPaletteColor(): PaletteColorEdit {
    dedupePalette()
    return PaletteColorEdit(entryIndex = null, vertices = emptySet(), currentColor = null)
  }

  /**
   * A live edit of a single palette entry and of the vertices that used its color when the edit started.
   *
   * Intermediate colors may temporarily duplicate other palette entries; [finish] merges the duplicates. Updates are ignored once the edit
   * is finished, or after the palette entries are removed or merged or the mesh is resized, since the captured indices may no longer refer
   * to the same entry and vertices.
   */
  internal inner class PaletteColorEdit(
    private var entryIndex: Int?,
    private val vertices: Set<VertexIndex>,
    private var currentColor: Color?,
  ) {
    private val startVersion = editVersion
    private var isFinished = false

    /** Sets the edited palette entry, and the captured vertices that still use the edited color, to [newColor]. */
    fun update(newColor: Color) {
      if (newColor == currentColor || isFinished || startVersion != editVersion) return
      val index = entryIndex
      if (index == null) {
        _availableColors.add(newColor)
        entryIndex = _availableColors.lastIndex
      } else {
        if (index !in _availableColors.indices) return
        _availableColors[index] = newColor
      }
      val previousColor = currentColor
      currentColor = newColor
      vertices.forEach { (row, col) ->
        val point = _meshPoints.getOrNull(row)?.getOrNull(col) ?: return@forEach
        if (point.color == previousColor) {
          updatePoint(row, col, point.withColor(newColor))
        }
      }
    }

    /** Ends the edit, merging any palette entries it made identical. Later calls to [update] are ignored. */
    fun finish() {
      if (isFinished) return
      isFinished = true
      dedupePalette()
    }
  }

  private fun dedupePalette() {
    val distinct = _availableColors.distinct()
    if (distinct.size == _availableColors.size) return
    _availableColors.clear()
    _availableColors.addAll(distinct)
    editVersion++
  }

  private fun updatePoint(row: Int, col: Int, newPoint: MeshGradientPoint) {
    val rowPoints = _meshPoints[row]
    if (rowPoints[col] == newPoint) return
    _meshPoints[row] = rowPoints.toMutableList().apply { set(col, newPoint) }
  }

  private fun updatePoints(transform: (MeshGradientPoint) -> MeshGradientPoint) {
    replaceMesh(_meshPoints.map { row -> row.map(transform) })
  }

  private fun replaceMesh(newPoints: List<List<MeshGradientPoint>>) {
    if (newPoints == _meshPoints) return
    _meshPoints.clear()
    _meshPoints.addAll(newPoints)
  }

  private fun generateMeshPoints() {
    val palette = _availableColors.ifEmpty { defaultColors }
    replaceMesh(
      List(rows) { row ->
        List(cols) { col ->
          MeshGradientPoint(position = evenMeshPosition(row, col, rows, cols), color = palette[(row * cols + col) % palette.size])
        }
      }
    )
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
