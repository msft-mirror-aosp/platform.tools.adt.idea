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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.isSpecified
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.util.Locale
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression

private const val FUN_MESH_PAINTER = "MeshGradientPainter"
private const val FUN_SET_VERTEX = "setVertex"
private const val FUN_OFFSET = "Offset"
private const val FUN_COLOR = "Color"
private const val FUN_ARRAY_OF = "arrayOf"
private const val FUN_PAIR = "Pair"
private const val OP_TO = "to"

private const val ARG_ROWS = "rows"
private const val ARG_COLUMNS = "columns"
private const val ARG_HAS_BICUBIC_COLOR = "hasBicubicColor"
private const val ARG_ROW = "row"
private const val ARG_COLUMN = "column"
private const val ARG_POSITION = "position"
private const val ARG_COLOR = "color"
private const val ARG_LEFT_CONTROL_POINT = "leftControlPoint"
private const val ARG_TOP_CONTROL_POINT = "topControlPoint"
private const val ARG_RIGHT_CONTROL_POINT = "rightControlPoint"
private const val ARG_BOTTOM_CONTROL_POINT = "bottomControlPoint"
private const val ARG_X = "x"
private const val ARG_Y = "y"
private const val ARG_RED = "red"
private const val ARG_GREEN = "green"
private const val ARG_BLUE = "blue"
private const val ARG_ALPHA = "alpha"
private const val ARG_INITIAL_VALUE = "initialValue"
private const val ARG_TARGET_VALUE = "targetValue"
private const val ARG_VALUE = "value"
private const val ARG_COLORS = "colors"
private const val ARG_COLOR_STOPS = "colorStops"
private const val ARG_START = "start"
private const val ARG_END = "end"
private const val ARG_START_X = "startX"
private const val ARG_END_X = "endX"
private const val ARG_START_Y = "startY"
private const val ARG_END_Y = "endY"
private const val ARG_CENTER = "center"
private const val ARG_RADIUS = "radius"
private const val ARG_TILE_MODE = "tileMode"

private const val TILE_MODE_CLAMP = "Clamp"
private const val TILE_MODE_REPEATED = "Repeated"
private const val TILE_MODE_MIRROR = "Mirror"
private const val TILE_MODE_DECAL = "Decal"

private const val FORMAT_OFFSET = "Offset(%.4ff, %.4ff)"

private val COLLECTION_LIST_MAP =
  mapOf(
    "kotlin.collections.listOf" to "listOf",
    "kotlin.collections.arrayListOf" to "arrayListOf",
    "kotlin.collections.mutableListOf" to "mutableListOf",
  )

private val COLLECTION_LIST_FUNCTIONS = COLLECTION_LIST_MAP.keys + COLLECTION_LIST_MAP.values

private val DYNAMIC_STATE_MAP =
  mapOf(
    "androidx.compose.animation.core.animateFloat" to "animateFloat",
    "androidx.compose.animation.animateColor" to "animateColor",
    "androidx.compose.animation.core.animateFloatAsState" to "animateFloatAsState",
    "androidx.compose.animation.animateColorAsState" to "animateColorAsState",
    "androidx.compose.runtime.mutableStateOf" to "mutableStateOf",
    "androidx.compose.runtime.mutableFloatStateOf" to "mutableFloatStateOf",
    "androidx.compose.runtime.mutableIntStateOf" to "mutableIntStateOf",
    "androidx.compose.runtime.mutableLongStateOf" to "mutableLongStateOf",
  )

private const val FQN_OFFSET = "androidx.compose.ui.geometry.Offset"
private const val FQN_COLOR = "androidx.compose.ui.graphics.Color"
private const val FQN_TILE_MODE = "androidx.compose.ui.graphics.TileMode"
private const val CLASS_TILE_MODE = "TileMode"
private const val FQN_COLOR_SPACES = "androidx.compose.ui.graphics.colorspace.ColorSpaces"
private const val CLASS_COLOR_SPACES = "ColorSpaces"
private const val COLOR_SPACE_SRGB = "Srgb"
private const val ARG_COLOR_SPACE = "colorSpace"
private const val FQN_REMEMBER = "androidx.compose.runtime.remember"
private const val FUN_REMEMBER = "remember"
private const val IMPLICIT_LAMBDA_PARAMETER = "it"

/** Maximum nesting of evaluation steps, beyond which an expression is considered unresolvable. */
private const val MAX_EVALUATION_DEPTH = 64

/**
 * Maximum number of evaluation steps of a single evaluation, beyond which an expression is considered unresolvable. This bounds the cost of
 * declarations referencing others several times, e.g. `val a1 = a0 + a0; val a2 = a1 + a1; ...`.
 */
private const val MAX_EVALUATION_STEPS = 10_000

/** Names of explicit property types that can't hold a [Color]. */
private val NON_COLOR_TYPE_NAMES =
  setOf("Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Short", "String", "Dp", "TextUnit", "TextStyle", "Offset", "Brush")

private val ARITHMETIC_OPERATORS = setOf("+", "-", "*", "/", "%")

private val FLOAT_LITERAL_REGEX = Regex("""(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?[fF]?""")

private val FLOAT_CONSTANTS = mapOf("POSITIVE_INFINITY" to Float.POSITIVE_INFINITY, "NEGATIVE_INFINITY" to Float.NEGATIVE_INFINITY)

private val FLOAT_CONSTANT_RECEIVERS = setOf("", "Float", "kotlin.Float", "Float$SUFFIX_COMPANION", "kotlin.Float$SUFFIX_COMPANION")

private val TILE_MODES =
  mapOf(
    TILE_MODE_CLAMP to TileMode.Clamp,
    TILE_MODE_REPEATED to TileMode.Repeated,
    TILE_MODE_MIRROR to TileMode.Mirror,
    TILE_MODE_DECAL to TileMode.Decal,
  )

/**
 * Represents a parsed Compose gradient configuration along with whether any of its parameters rely on dynamic or unresolvable expressions.
 */
sealed class Gradient {
  abstract val hasDynamicOrUnresolvedValues: Boolean

  /**
   * A 2D grid-based gradient defined via `MeshGradientPainter`, where colors and cubic Bézier control points are interpolated across a
   * [rows] x [cols] mesh of [vertices].
   */
  data class MeshGradient(
    val rows: Int,
    val cols: Int,
    val vertices: List<Vertex>,
    override val hasDynamicOrUnresolvedValues: Boolean = false,
  ) : Gradient()

  /**
   * A gradient that interpolates [colors] (or fractional [colorStops]) along a straight line between [start] and [end] coordinates,
   * repeating or clamping outside the bounds according to [tileMode]. Also represents `Brush.horizontalGradient` and
   * `Brush.verticalGradient`.
   */
  data class LinearGradient(
    val colors: List<Color>,
    val colorStops: List<Pair<Float, Color>>? = null,
    val start: Offset,
    val end: Offset,
    val tileMode: TileMode = TileMode.Clamp,
    override val hasDynamicOrUnresolvedValues: Boolean = false,
  ) : Gradient()

  /**
   * A circular gradient that radiates outward from a [center] point up to a given [radius], interpolating [colors] (or fractional
   * [colorStops]) and applying [tileMode] beyond the radius.
   */
  data class RadialGradient(
    val colors: List<Color>,
    val colorStops: List<Pair<Float, Color>>? = null,
    val center: Offset,
    val radius: Float,
    val tileMode: TileMode = TileMode.Clamp,
    override val hasDynamicOrUnresolvedValues: Boolean = false,
  ) : Gradient()

  /**
   * An angular (conic) gradient that sweeps [colors] (or fractional [colorStops]) full circle (360 degrees) clockwise around a [center]
   * point.
   */
  data class SweepGradient(
    val colors: List<Color>,
    val colorStops: List<Pair<Float, Color>>? = null,
    val center: Offset,
    override val hasDynamicOrUnresolvedValues: Boolean = false,
  ) : Gradient()
}

data class Vertex(val row: Int, val col: Int, val offset: Offset, val color: Color)

internal fun formatSetVertexCall(r: Int, c: Int, point: MeshGradientPoint): String {
  val positionStr = point.positionExpression ?: String.format(Locale.US, FORMAT_OFFSET, point.position.x, point.position.y)
  val colorStr = point.colorExpression ?: "Color(${point.color.toComposeHexLiteral()})"
  val base = String.format(Locale.US, "setVertex(%d, %d, %s, %s", r, c, positionStr, colorStr)
  val controlPoints = buildList {
    if (point.leftBezierOffset != Offset.Unspecified) {
      add(String.format(Locale.US, "leftControlPoint = Offset(%.4ff, %.4ff)", point.leftBezierOffset.x, point.leftBezierOffset.y))
    }
    if (point.topBezierOffset != Offset.Unspecified) {
      add(String.format(Locale.US, "topControlPoint = Offset(%.4ff, %.4ff)", point.topBezierOffset.x, point.topBezierOffset.y))
    }
    if (point.rightBezierOffset != Offset.Unspecified) {
      add(String.format(Locale.US, "rightControlPoint = Offset(%.4ff, %.4ff)", point.rightBezierOffset.x, point.rightBezierOffset.y))
    }
    if (point.bottomBezierOffset != Offset.Unspecified) {
      add(String.format(Locale.US, "bottomControlPoint = Offset(%.4ff, %.4ff)", point.bottomBezierOffset.x, point.bottomBezierOffset.y))
    }
  }
  return if (controlPoints.isEmpty()) {
    "$base)"
  } else {
    "$base, ${controlPoints.joinToString(", ")})"
  }
}

class GradientPsiManager(private val project: Project) {

  data class ParsedVertex(
    val row: Int,
    val col: Int,
    val offset: Offset,
    val color: Color,
    val leftBezierOffset: Offset = Offset.Unspecified,
    val topBezierOffset: Offset = Offset.Unspecified,
    val rightBezierOffset: Offset = Offset.Unspecified,
    val bottomBezierOffset: Offset = Offset.Unspecified,
    val positionExpression: String? = null,
    val colorExpression: String? = null,
  )

  data class ParsedMesh(
    val rows: Int,
    val cols: Int,
    val vertices: List<ParsedVertex>,
    val hasBicubicColor: Boolean = false,
    val hasDynamicOrUnresolvedValues: Boolean = false,
  )

  /** Finds the first [KtCallExpression] for "MeshGradientPainter" in the file. */
  @RequiresReadLock
  fun findMeshPainterCall(file: KtFile): KtCallExpression? {
    return SyntaxTraverser.psiTraverser(file).filter(KtCallExpression::class.java).firstOrNull { it.isValidMeshGradientCall() }
  }

  /**
   * Parses a `Brush.linearGradient` [KtCallExpression] into a [Gradient.LinearGradient], or returns null if the call does not represent a
   * valid linear gradient.
   */
  fun parseLinearGradient(callExpr: KtCallExpression): Gradient.LinearGradient? {
    if (!callExpr.isValidBrushGradientCall(FUN_LINEAR_GRADIENT) && callExpr.calleeExpression?.text != FUN_LINEAR_GRADIENT) return null
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val (colors, colorStops) = parseColorsOrStops(callExpr, onDynamic)
    if (colors == null && colorStops == null) return null

    val hasVarargStops = colorStops != null
    val startExpr = findArgumentExpression(callExpr, ARG_START, if (hasVarargStops) -1 else 1)
    val endExpr = findArgumentExpression(callExpr, ARG_END, if (hasVarargStops) -1 else 2)
    val tileModeExpr = findArgumentExpression(callExpr, ARG_TILE_MODE, if (hasVarargStops) -1 else 3)

    // If an optional argument is explicitly provided (expr != null) but cannot be statically
    // resolved, we mark the gradient as having dynamic/unresolved values (`onDynamic()`) and fall
    // back to its Compose default. If the argument is omitted altogether (expr == null), using its
    // default parameter value is standard static behavior and should not trigger `onDynamic()`.
    val start = startExpr?.let { parseOffset(it, onDynamic) ?: Offset.Zero.also { onDynamic() } } ?: Offset.Zero
    val end = endExpr?.let { parseOffset(it, onDynamic) ?: Offset.Infinite.also { onDynamic() } } ?: Offset.Infinite
    val tileMode = tileModeExpr?.let { parseTileMode(it, onDynamic) ?: TileMode.Clamp.also { onDynamic() } } ?: TileMode.Clamp

    return Gradient.LinearGradient(
      colors = colors ?: emptyList(),
      colorStops = colorStops,
      start = start,
      end = end,
      tileMode = tileMode,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
    )
  }

  /**
   * Parses a `Brush.horizontalGradient` [KtCallExpression] into an equivalent horizontal [Gradient.LinearGradient] (`y = 0f`), or returns
   * null if the call is not a valid horizontal gradient.
   */
  fun parseHorizontalGradient(callExpr: KtCallExpression): Gradient.LinearGradient? {
    if (!callExpr.isValidBrushGradientCall(FUN_HORIZONTAL_GRADIENT) && callExpr.calleeExpression?.text != FUN_HORIZONTAL_GRADIENT) {
      return null
    }
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val (colors, colorStops) = parseColorsOrStops(callExpr, onDynamic)
    if (colors == null && colorStops == null) return null

    val hasVarargStops = colorStops != null
    val startXExpr = findArgumentExpression(callExpr, ARG_START_X, if (hasVarargStops) -1 else 1)
    val endXExpr = findArgumentExpression(callExpr, ARG_END_X, if (hasVarargStops) -1 else 2)
    val tileModeExpr = findArgumentExpression(callExpr, ARG_TILE_MODE, if (hasVarargStops) -1 else 3)

    // Omitted optional arguments (expr == null) fall back to their Compose default values without
    // calling `onDynamic()`; only explicitly passed but unresolvable arguments trigger `onDynamic()`.
    val startX = startXExpr?.let { parseFloat(it, onDynamic) ?: 0f.also { onDynamic() } } ?: 0f
    val endX = endXExpr?.let { parseFloat(it, onDynamic) ?: Float.POSITIVE_INFINITY.also { onDynamic() } } ?: Float.POSITIVE_INFINITY
    val tileMode = tileModeExpr?.let { parseTileMode(it, onDynamic) ?: TileMode.Clamp.also { onDynamic() } } ?: TileMode.Clamp

    return Gradient.LinearGradient(
      colors = colors ?: emptyList(),
      colorStops = colorStops,
      start = Offset(startX, 0f),
      end = Offset(endX, 0f),
      tileMode = tileMode,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
    )
  }

  /**
   * Parses a `Brush.verticalGradient` [KtCallExpression] into an equivalent vertical [Gradient.LinearGradient] (`x = 0f`), or returns null
   * if the call is not a valid vertical gradient.
   */
  fun parseVerticalGradient(callExpr: KtCallExpression): Gradient.LinearGradient? {
    if (!callExpr.isValidBrushGradientCall(FUN_VERTICAL_GRADIENT) && callExpr.calleeExpression?.text != FUN_VERTICAL_GRADIENT) return null
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val (colors, colorStops) = parseColorsOrStops(callExpr, onDynamic)
    if (colors == null && colorStops == null) return null

    val hasVarargStops = colorStops != null
    val startYExpr = findArgumentExpression(callExpr, ARG_START_Y, if (hasVarargStops) -1 else 1)
    val endYExpr = findArgumentExpression(callExpr, ARG_END_Y, if (hasVarargStops) -1 else 2)
    val tileModeExpr = findArgumentExpression(callExpr, ARG_TILE_MODE, if (hasVarargStops) -1 else 3)

    // Omitted optional arguments (expr == null) fall back to their Compose default values without
    // calling `onDynamic()`; only explicitly passed but unresolvable arguments trigger `onDynamic()`.
    val startY = startYExpr?.let { parseFloat(it, onDynamic) ?: 0f.also { onDynamic() } } ?: 0f
    val endY = endYExpr?.let { parseFloat(it, onDynamic) ?: Float.POSITIVE_INFINITY.also { onDynamic() } } ?: Float.POSITIVE_INFINITY
    val tileMode = tileModeExpr?.let { parseTileMode(it, onDynamic) ?: TileMode.Clamp.also { onDynamic() } } ?: TileMode.Clamp

    return Gradient.LinearGradient(
      colors = colors ?: emptyList(),
      colorStops = colorStops,
      start = Offset(0f, startY),
      end = Offset(0f, endY),
      tileMode = tileMode,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
    )
  }

  /**
   * Parses a `Brush.radialGradient` [KtCallExpression] into a [Gradient.RadialGradient], or returns null if the call does not represent a
   * valid radial gradient.
   */
  fun parseRadialGradient(callExpr: KtCallExpression): Gradient.RadialGradient? {
    if (!callExpr.isValidBrushGradientCall(FUN_RADIAL_GRADIENT) && callExpr.calleeExpression?.text != FUN_RADIAL_GRADIENT) return null
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val (colors, colorStops) = parseColorsOrStops(callExpr, onDynamic)
    if (colors == null && colorStops == null) return null

    val hasVarargStops = colorStops != null
    val centerExpr = findArgumentExpression(callExpr, ARG_CENTER, if (hasVarargStops) -1 else 1)
    val radiusExpr = findArgumentExpression(callExpr, ARG_RADIUS, if (hasVarargStops) -1 else 2)
    val tileModeExpr = findArgumentExpression(callExpr, ARG_TILE_MODE, if (hasVarargStops) -1 else 3)

    // Omitted optional arguments (expr == null) fall back to their Compose default values without
    // calling `onDynamic()`; only explicitly passed but unresolvable arguments trigger `onDynamic()`.
    val center = centerExpr?.let { parseOffset(it, onDynamic) ?: Offset.Unspecified.also { onDynamic() } } ?: Offset.Unspecified
    val radius = radiusExpr?.let { parseFloat(it, onDynamic) ?: Float.POSITIVE_INFINITY.also { onDynamic() } } ?: Float.POSITIVE_INFINITY
    val tileMode = tileModeExpr?.let { parseTileMode(it, onDynamic) ?: TileMode.Clamp.also { onDynamic() } } ?: TileMode.Clamp

    return Gradient.RadialGradient(
      colors = colors ?: emptyList(),
      colorStops = colorStops,
      center = center,
      radius = radius,
      tileMode = tileMode,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
    )
  }

  /**
   * Parses a `Brush.sweepGradient` [KtCallExpression] into a [Gradient.SweepGradient], or returns null if the call does not represent a
   * valid sweep gradient.
   */
  fun parseSweepGradient(callExpr: KtCallExpression): Gradient.SweepGradient? {
    if (!callExpr.isValidBrushGradientCall(FUN_SWEEP_GRADIENT) && callExpr.calleeExpression?.text != FUN_SWEEP_GRADIENT) return null
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val (colors, colorStops) = parseColorsOrStops(callExpr, onDynamic)
    if (colors == null && colorStops == null) return null

    val hasVarargStops = colorStops != null
    val centerExpr = findArgumentExpression(callExpr, ARG_CENTER, if (hasVarargStops) -1 else 1)
    // Omitted optional `center` argument (centerExpr == null) defaults to Offset.Unspecified
    // without calling `onDynamic()`; only an explicitly passed but unresolvable argument triggers it.
    val center = centerExpr?.let { parseOffset(it, onDynamic) ?: Offset.Unspecified.also { onDynamic() } } ?: Offset.Unspecified

    return Gradient.SweepGradient(
      colors = colors ?: emptyList(),
      colorStops = colorStops,
      center = center,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
    )
  }

  private fun parseColorsOrStops(
    callExpr: KtCallExpression,
    onDynamic: () -> Unit,
  ): Pair<List<Color>?, List<Pair<Float, Color>>?> {
    val colorsExpr = findArgumentExpression(callExpr, ARG_COLORS, 0)
    val colors = colorsExpr?.let { parseColorsList(it, onDynamic) }
    if (colors != null) return colors to null

    val colorStopsExpr = findArgumentExpression(callExpr, ARG_COLOR_STOPS, 0)
    val colorStops = colorStopsExpr?.let { parseColorStops(it, onDynamic) } ?: parsePositionalColorStops(callExpr, onDynamic)
    return null to colorStops
  }

  private fun parseColorsList(expr: KtExpression, onDynamic: (() -> Unit)? = null): List<Color>? {
    val resolved = resolveExpression(expr, onDynamic)
    val call = getCallExpression(resolved) ?: return null
    val callee = getQualifiedCalleeText(resolved) ?: return null
    if (!isCollectionListFunction(resolved, callee)) return null
    if (call.valueArguments.isEmpty()) return null
    var anyResolved = false
    var anyFailed = false
    val result =
      call.valueArguments.map { arg ->
        val argExpr = arg.getArgumentExpression()
        val color = argExpr?.let { parseColor(it, onDynamic) }
        if (color != null) {
          anyResolved = true
          color
        } else {
          anyFailed = true
          Color.White
        }
      }
    if (!anyResolved) return null
    if (anyFailed) onDynamic?.invoke()
    return if (result.size == 1) {
      onDynamic?.invoke()
      listOf(result[0], Color.White)
    } else {
      result
    }
  }

  private fun parseColorStops(expr: KtExpression, onDynamic: (() -> Unit)? = null): List<Pair<Float, Color>>? {
    val resolved = resolveExpression(expr, onDynamic)
    val call = getCallExpression(resolved) ?: return null
    val callee = call.calleeExpression?.text ?: return null
    if (callee != FUN_ARRAY_OF && !isCollectionListFunction(resolved, callee)) return null
    return parseStopArguments(call.valueArguments, onDynamic)
  }

  private fun parsePositionalColorStops(callExpr: KtCallExpression, onDynamic: (() -> Unit)? = null): List<Pair<Float, Color>>? {
    val positionalArgs = callExpr.valueArguments.filter { it !is KtLambdaArgument && it.getArgumentName() == null }
    if (positionalArgs.isEmpty()) return null
    return parseStopArguments(positionalArgs, onDynamic)
  }

  private fun parseStopArguments(args: List<KtValueArgument>, onDynamic: (() -> Unit)? = null): List<Pair<Float, Color>>? {
    if (args.isEmpty()) return null
    var anyResolved = false
    var anyFailed = false
    val count = args.size
    val result = args.mapIndexed { index, arg ->
      val defaultFraction = if (count > 1) index.toFloat() / (count - 1) else 0f
      val argExpr = arg.getArgumentExpression()
      val stop = argExpr?.let { parseColorStop(it, onDynamic, defaultFraction) }
      if (stop != null) {
        anyResolved = true
        stop
      } else {
        anyFailed = true
        Pair(defaultFraction, Color.White)
      }
    }
    if (!anyResolved) return null
    if (anyFailed) onDynamic?.invoke()
    return if (result.size == 1) {
      onDynamic?.invoke()
      listOf(result[0], Pair(1f, Color.White))
    } else {
      result
    }
  }

  private fun parseColorStop(
    expr: KtExpression,
    onDynamic: (() -> Unit)? = null,
    defaultFraction: Float? = null,
  ): Pair<Float, Color>? {
    val resolved = resolveExpression(expr, onDynamic)
    if (resolved is KtBinaryExpression && resolved.operationReference.text == OP_TO) {
      val rawLeft = resolved.left?.let { parseFloat(it, onDynamic)?.coerceIn(0f, 1f) }
      val rawRight = resolved.right?.let { parseColor(it, onDynamic) }
      if (rawLeft == null && rawRight == null) return null
      if (rawLeft == null || rawRight == null) onDynamic?.invoke()
      return Pair(rawLeft ?: defaultFraction ?: 0f, rawRight ?: Color.White)
    }
    if (resolved is KtCallExpression && resolved.calleeExpression?.text == FUN_PAIR) {
      val args = resolved.valueArguments
      if (args.size == 2) {
        val rawLeft = args[0].getArgumentExpression()?.let { parseFloat(it, onDynamic)?.coerceIn(0f, 1f) }
        val rawRight = args[1].getArgumentExpression()?.let { parseColor(it, onDynamic) }
        if (rawLeft == null && rawRight == null) return null
        if (rawLeft == null || rawRight == null) onDynamic?.invoke()
        return Pair(rawLeft ?: defaultFraction ?: 0f, rawRight ?: Color.White)
      }
    }
    return null
  }

  private fun parseTileMode(expr: KtExpression, onDynamic: (() -> Unit)? = null): TileMode? {
    val ctx = EvalContext(onDynamic)
    return ctx.withFrame { parseResolvedTileMode(resolveExpression(expr, ctx), ctx) }
  }

  /** Accepts `TileMode.X`, optionally fully qualified or aliased, or an `X` explicitly imported from `TileMode`. */
  private fun parseResolvedTileMode(resolved: KtExpression, ctx: EvalContext): TileMode? {
    val entryName =
      when (resolved) {
        is KtDotQualifiedExpression -> {
          val receiver = resolved.receiverExpression.text.removeSuffix(SUFFIX_COMPANION)
          if (receiver !in getSupportedNames(resolved, FQN_TILE_MODE, CLASS_TILE_MODE, ctx)) return null
          (resolved.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        }
        is KtNameReferenceExpression -> {
          val file = resolved.containingFile as? KtFile ?: return null
          val name = resolved.getReferencedName()
          val importedFqName =
            ctx.imports(file).firstOrNull { !it.isAllUnder && it.importedName?.asString() == name }?.importedFqName ?: return null
          importedFqName.shortName().asString().takeIf {
            importedFqName.parent().asString().removeSuffix(SUFFIX_COMPANION) == FQN_TILE_MODE
          }
        }
        else -> null
      }
    return entryName?.let { TILE_MODES[it] }
  }

  /**
   * Replaces the given `Brush` gradient [callExpr] (or its enclosing qualified expression) in the PSI tree with updated source code for
   * [gradient]. Returns false for [Gradient.MeshGradient], which is updated via [updateConstructorArguments] and [regenerateLambdaBody].
   */
  fun updateGradient(callExpr: KtCallExpression, gradient: Gradient): Boolean {
    val psiFactory = KtPsiFactory(project)
    val newExprStr =
      when (gradient) {
        is Gradient.LinearGradient -> generateLinearGradientSource(gradient)
        is Gradient.RadialGradient -> generateRadialGradientSource(gradient)
        is Gradient.SweepGradient -> generateSweepGradientSource(gradient)
        is Gradient.MeshGradient -> return false
      }
    val newExpr = psiFactory.createExpression(newExprStr)
    val targetToReplace = (callExpr.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == callExpr } ?: callExpr
    targetToReplace.replace(newExpr)
    return true
  }

  private fun generateLinearGradientSource(gradient: Gradient.LinearGradient): String {
    val colorsStr = generateColorsOrStopsSource(gradient.colors, gradient.colorStops)
    return "Brush.$FUN_LINEAR_GRADIENT($colorsStr, $ARG_START = ${generateOffsetSource(gradient.start)}, $ARG_END = ${generateOffsetSource(gradient.end)}, $ARG_TILE_MODE = ${generateTileModeSource(gradient.tileMode)})"
  }

  private fun generateRadialGradientSource(gradient: Gradient.RadialGradient): String {
    val colorsStr = generateColorsOrStopsSource(gradient.colors, gradient.colorStops)
    return "Brush.$FUN_RADIAL_GRADIENT($colorsStr, $ARG_CENTER = ${generateOffsetSource(gradient.center)}, $ARG_RADIUS = ${generateFloatSource(gradient.radius)}, $ARG_TILE_MODE = ${generateTileModeSource(gradient.tileMode)})"
  }

  private fun generateSweepGradientSource(gradient: Gradient.SweepGradient): String {
    val colorsStr = generateColorsOrStopsSource(gradient.colors, gradient.colorStops)
    return "Brush.$FUN_SWEEP_GRADIENT($colorsStr, $ARG_CENTER = ${generateOffsetSource(gradient.center)})"
  }

  private fun generateColorsOrStopsSource(colors: List<Color>, colorStops: List<Pair<Float, Color>>?): String {
    return if (colorStops != null) {
      "$ARG_COLOR_STOPS = $FUN_ARRAY_OF(${colorStops.joinToString { "${generateFloatSource(it.first)} $OP_TO Color(${it.second.toComposeHexLiteral()})" }})"
    } else {
      "$ARG_COLORS = listOf(${colors.joinToString { "Color(${it.toComposeHexLiteral()})" }})"
    }
  }

  private fun generateTileModeSource(tileMode: TileMode): String {
    return "TileMode.$tileMode"
  }

  /** Parses the [KtCallExpression] of MeshGradientPainter to extract rows, cols, hasBicubicColor, and vertices. */
  fun parseMesh(callExpr: KtCallExpression): ParsedMesh? {
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    val rowsExpr = findArgumentExpression(callExpr, ARG_ROWS, 0) ?: return null
    val colsExpr = findArgumentExpression(callExpr, ARG_COLUMNS, 1) ?: return null

    val rows = parseInt(rowsExpr, onDynamic) ?: return null
    val cols = parseInt(colsExpr, onDynamic) ?: return null

    val bicubicExpr = findArgumentExpression(callExpr, ARG_HAS_BICUBIC_COLOR, 2)
    val hasBicubicColor = bicubicExpr?.let { resolveExpression(it, onDynamic).text.toBooleanStrictOrNull() } ?: false

    val actualRows = rows + 1
    val actualCols = cols + 1

    val body = getMeshLambdaBody(callExpr) ?: return null
    val vertices = parseVertices(body, onDynamic)
    val coveredVertices = vertices.map { it.row to it.col }.filter { (r, c) -> r in 0 until actualRows && c in 0 until actualCols }.toSet()
    if (coveredVertices.size < actualRows * actualCols) {
      hasDynamicOrUnresolved = true
    }

    return ParsedMesh(actualRows, actualCols, vertices, hasBicubicColor, hasDynamicOrUnresolved)
  }

  private fun getMeshLambdaBody(callExpr: KtCallExpression): KtBlockExpression? {
    val lambdaArg = callExpr.lambdaArguments.firstOrNull() ?: (callExpr.valueArguments.lastOrNull() as? KtLambdaArgument)
    return lambdaArg?.getLambdaExpression()?.bodyExpression
  }

  private fun findArgumentExpression(callExpr: KtCallExpression, name: String, index: Int): KtExpression? {
    val namedArg = callExpr.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }
    if (namedArg != null) return namedArg.getArgumentExpression()

    val args = callExpr.valueArguments
    if (index in args.indices) {
      val arg = args[index]
      if (arg is KtLambdaArgument) return null
      if (arg.getArgumentName() == null) {
        return arg.getArgumentExpression()
      }
    }
    return null
  }

  private fun parseVertices(body: KtBlockExpression, onDynamic: () -> Unit): List<ParsedVertex> {
    val vertices = mutableListOf<ParsedVertex>()
    val setVertexCalls =
      SyntaxTraverser.psiTraverser(body).filter(KtCallExpression::class.java).filter { it.calleeExpression?.text == FUN_SET_VERTEX }

    for (call in setVertexCalls) {
      val args = call.valueArguments
      if (args.size < 4) {
        onDynamic()
        continue
      }

      val rowExpr = findArgumentExpression(call, ARG_ROW, 0)
      val colExpr = findArgumentExpression(call, ARG_COLUMN, 1)
      val row = rowExpr?.let { parseInt(it, onDynamic) }
      val col = colExpr?.let { parseInt(it, onDynamic) }
      if (row == null || col == null) {
        onDynamic()
        continue
      }

      val offsetExpr = findArgumentExpression(call, ARG_POSITION, 2)
      val offset = offsetExpr?.let { parseOffset(it, onDynamic) }
      if (offsetExpr == null || offset == null || offset == Offset.Unspecified) {
        onDynamic()
        continue
      }

      val colorExpr = findArgumentExpression(call, ARG_COLOR, 3)
      val color = colorExpr?.let { parseColor(it, onDynamic) }
      if (colorExpr == null || color == null) {
        onDynamic()
        continue
      }

      val leftCp = findArgumentExpression(call, ARG_LEFT_CONTROL_POINT, 4)?.let { parseOffset(it, onDynamic) } ?: Offset.Unspecified
      val topCp = findArgumentExpression(call, ARG_TOP_CONTROL_POINT, 5)?.let { parseOffset(it, onDynamic) } ?: Offset.Unspecified
      val rightCp = findArgumentExpression(call, ARG_RIGHT_CONTROL_POINT, 6)?.let { parseOffset(it, onDynamic) } ?: Offset.Unspecified
      val bottomCp = findArgumentExpression(call, ARG_BOTTOM_CONTROL_POINT, 7)?.let { parseOffset(it, onDynamic) } ?: Offset.Unspecified

      val posExprText = offsetExpr.text.takeUnless { isDirectOffsetLiteral(offsetExpr) }
      val colExprText = colorExpr.text.takeUnless { isDirectHexColorLiteral(colorExpr) }

      vertices.add(ParsedVertex(row, col, offset, color, leftCp, topCp, rightCp, bottomCp, posExprText, colExprText))
    }
    return vertices
  }

  private fun isDirectOffsetLiteral(expr: KtExpression): Boolean {
    val call = expr as? KtCallExpression ?: return false
    if (call.calleeExpression?.text != FUN_OFFSET) return false
    val xExpr = findArgumentExpression(call, ARG_X, 0) ?: return false
    val yExpr = findArgumentExpression(call, ARG_Y, 1) ?: return false
    return isDirectFloatLiteral(xExpr) && isDirectFloatLiteral(yExpr)
  }

  private fun isDirectFloatLiteral(expr: KtExpression): Boolean {
    return expr.text.removeSuffix("f").removeSuffix("F").toFloatOrNull() != null
  }

  private fun isDirectHexColorLiteral(expr: KtExpression): Boolean {
    val call = expr as? KtCallExpression ?: return false
    if (call.calleeExpression?.text != FUN_COLOR) return false
    val args = call.valueArguments
    if (args.size != 1) return false
    val argExpr = args[0].getArgumentExpression() ?: return false
    val text = argExpr.text.removeSuffix(".toInt()").removeSuffix(".toLong()")
    return parseLong(text) != null
  }

  private fun parseOffset(expr: KtExpression, onDynamic: (() -> Unit)? = null): Offset? = parseOffset(expr, EvalContext(onDynamic))

  private fun parseOffset(expr: KtExpression, ctx: EvalContext): Offset? = ctx.withFrame {
    parseResolvedOffset(resolveExpression(expr, ctx), ctx)
  }

  private fun parseSpecifiedOffset(expr: KtExpression, ctx: EvalContext): Offset? =
    parseOffset(expr, ctx)?.takeIf { it != Offset.Unspecified }

  private fun parseResolvedOffset(resolvedExpr: KtExpression, ctx: EvalContext): Offset? {
    if (resolvedExpr is KtPrefixExpression) {
      val base = resolvedExpr.baseExpression ?: return null
      val offset = parseSpecifiedOffset(base, ctx) ?: return null
      return when (resolvedExpr.operationReference.text) {
        "-" -> Offset(-offset.x, -offset.y)
        "+" -> offset
        else -> null
      }
    }
    if (resolvedExpr is KtBinaryExpression) {
      val left = resolvedExpr.left ?: return null
      val right = resolvedExpr.right ?: return null
      return when (resolvedExpr.operationReference.text) {
        "+" -> {
          val l = parseSpecifiedOffset(left, ctx) ?: return null
          val r = parseSpecifiedOffset(right, ctx) ?: return null
          l + r
        }
        "-" -> {
          val l = parseSpecifiedOffset(left, ctx) ?: return null
          val r = parseSpecifiedOffset(right, ctx) ?: return null
          l - r
        }
        "*" -> {
          val lOffset = parseSpecifiedOffset(left, ctx)
          if (lOffset != null) {
            val rFloat = parseFloat(right, ctx) ?: return null
            lOffset * rFloat
          } else {
            val lFloat = parseFloat(left, ctx) ?: return null
            val rOffset = parseSpecifiedOffset(right, ctx) ?: return null
            rOffset * lFloat
          }
        }
        "/" -> {
          val lOffset = parseSpecifiedOffset(left, ctx) ?: return null
          val rFloat = parseFloat(right, ctx) ?: return null
          if (rFloat == 0f) null else lOffset / rFloat
        }
        else -> null
      }
    }

    val supportedNames = getSupportedNames(resolvedExpr, FQN_OFFSET, FUN_OFFSET, ctx)
    val call = getCallExpression(resolvedExpr)
    if (call != null) {
      val calleeText = getQualifiedCalleeText(resolvedExpr) ?: return null
      if (calleeText !in supportedNames) return null

      val xExpr = findArgumentExpression(call, ARG_X, 0) ?: return null
      val yExpr = findArgumentExpression(call, ARG_Y, 1) ?: return null

      val x = parseFloat(xExpr, ctx) ?: return null
      val y = parseFloat(yExpr, ctx) ?: return null
      return Offset(x, y)
    }

    if (resolvedExpr is KtDotQualifiedExpression && resolvedExpr.receiverExpression.text in supportedNames) {
      return when ((resolvedExpr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()) {
        "Zero" -> Offset.Zero
        "Unspecified" -> Offset.Unspecified
        "Infinite" -> Offset.Infinite
        else -> null
      }
    }

    return null
  }

  private fun parseFloat(expr: KtExpression, onDynamic: (() -> Unit)? = null): Float? = parseFloat(expr, EvalContext(onDynamic))

  private fun parseFloat(expr: KtExpression, ctx: EvalContext): Float? = ctx.withFrame {
    parseResolvedFloat(resolveExpression(expr, ctx), ctx)
  }

  private fun parseResolvedFloat(resolved: KtExpression, ctx: EvalContext): Float? {
    if (resolved is KtPrefixExpression) {
      val base = resolved.baseExpression ?: return null
      return when (resolved.operationReference.text) {
        "-" -> parseFloat(base, ctx)?.let { -it }
        "+" -> parseFloat(base, ctx)
        else -> null
      }
    }
    if (resolved is KtBinaryExpression) {
      // An operation between integers follows integer semantics (e.g. `1 / 2` is 0) even when used as a float.
      parseResolvedInt(resolved, ctx)?.let {
        return it.toFloat()
      }
      val operator = resolved.operationReference.text
      if (operator !in ARITHMETIC_OPERATORS) return null
      val l = resolved.left?.let { parseFloat(it, ctx) } ?: return null
      val r = resolved.right?.let { parseFloat(it, ctx) } ?: return null
      return when (operator) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "/" -> if (r == 0f) null else l / r
        "%" -> if (r == 0f) null else l % r
        else -> null
      }
    }
    return parseFloatLiteral(resolved.text)
  }

  /** Parses a decimal literal, such as `1`, `0.5f` or `1_000f`, or a `Float.POSITIVE_INFINITY`/`Float.NEGATIVE_INFINITY` constant. */
  private fun parseFloatLiteral(text: String): Float? {
    if (text.substringBeforeLast('.', "") in FLOAT_CONSTANT_RECEIVERS) {
      FLOAT_CONSTANTS[text.substringAfterLast('.')]?.let {
        return it
      }
    }
    val clean = text.replace("_", "")
    if (!FLOAT_LITERAL_REGEX.matches(clean)) return null
    return clean.removeSuffix("f").removeSuffix("F").toFloatOrNull()
  }

  private fun parseInt(expr: KtExpression, onDynamic: (() -> Unit)? = null): Int? = parseInt(expr, EvalContext(onDynamic))

  /**
   * Evaluates [expr] as an `Int`, supporting literals, references to local or top-level `val`s, unary `+`/`-`, parentheses and the binary
   * `+`, `-`, `*`, `/` and `%` operators with Kotlin integer semantics.
   */
  private fun parseInt(expr: KtExpression, ctx: EvalContext): Int? = ctx.withFrame { parseResolvedInt(resolveExpression(expr, ctx), ctx) }

  private fun parseResolvedInt(resolved: KtExpression, ctx: EvalContext): Int? {
    if (resolved is KtPrefixExpression) {
      val base = resolved.baseExpression ?: return null
      return when (resolved.operationReference.text) {
        "-" -> parseInt(base, ctx)?.let { -it }
        "+" -> parseInt(base, ctx)
        else -> null
      }
    }
    if (resolved is KtBinaryExpression) {
      val operator = resolved.operationReference.text
      if (operator !in ARITHMETIC_OPERATORS) return null
      val l = resolved.left?.let { parseInt(it, ctx) } ?: return null
      val r = resolved.right?.let { parseInt(it, ctx) } ?: return null
      return when (operator) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "/" -> if (r == 0) null else l / r
        "%" -> if (r == 0) null else l % r
        else -> null
      }
    }
    return parseIntLiteral(resolved.text)
  }

  /** Parses a decimal, hexadecimal (`0x`) or binary (`0b`) `Int` literal. `Long` (`L`) and unsigned (`u`) literals are rejected. */
  private fun parseIntLiteral(text: String): Int? {
    val clean = text.replace("_", "")
    return when {
      clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toIntOrNull(16)
      clean.startsWith("0b", ignoreCase = true) -> clean.substring(2).toIntOrNull(2)
      else -> clean.toIntOrNull()
    }
  }

  private fun parseColor(expr: KtExpression, onDynamic: (() -> Unit)? = null): Color? = parseColor(expr, EvalContext(onDynamic))

  private fun parseColor(expr: KtExpression, ctx: EvalContext): Color? = ctx.withFrame {
    parseResolvedColor(resolveExpression(expr, ctx), ctx)
  }

  private fun parseResolvedColor(resolvedExpr: KtExpression, ctx: EvalContext): Color? {
    val supportedNames = getSupportedNames(resolvedExpr, FQN_COLOR, FUN_COLOR, ctx)
    val call = getCallExpression(resolvedExpr)
    if (call != null) {
      val calleeText = getQualifiedCalleeText(resolvedExpr) ?: return null
      if (calleeText !in supportedNames) return null

      val args = call.valueArguments
      if (args.isEmpty()) return null

      if (args.size == 1) {
        val argExpr = args[0].getArgumentExpression() ?: return null
        val resolvedArg = resolveExpression(argExpr, ctx)
        val text = resolvedArg.text.removeSuffix(".toInt()").removeSuffix(".toLong()")
        val longValue = parseLong(text) ?: return null
        return Color(longValue)
      } else if (args.size >= 3) {
        val rExpr = findArgumentExpression(call, ARG_RED, 0) ?: return null
        val gExpr = findArgumentExpression(call, ARG_GREEN, 1) ?: return null
        val bExpr = findArgumentExpression(call, ARG_BLUE, 2) ?: return null
        val aExpr = findArgumentExpression(call, ARG_ALPHA, 3)

        val r = parseColorComponent(rExpr, ctx) ?: return null
        val g = parseColorComponent(gExpr, ctx) ?: return null
        val b = parseColorComponent(bExpr, ctx) ?: return null
        val a = aExpr?.let { parseColorComponent(it, ctx) ?: 1f.also { ctx.markDynamic() } } ?: 1f

        // The components are interpreted as sRGB; any other color space can't be represented faithfully.
        val colorSpaceExpr = findArgumentExpression(call, ARG_COLOR_SPACE, 4)
        if (colorSpaceExpr != null && !isSrgbColorSpace(colorSpaceExpr, ctx)) ctx.markDynamic()

        return Color(r, g, b, a)
      }
    } else if (resolvedExpr is KtDotQualifiedExpression && resolvedExpr.receiverExpression.text in supportedNames) {
      val colorName = (resolvedExpr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
      return mapConstantColor(colorName)
    }
    return null
  }

  private fun isSrgbColorSpace(expr: KtExpression, ctx: EvalContext): Boolean {
    val resolved = ctx.withFrame { resolveExpression(expr, ctx) } as? KtDotQualifiedExpression ?: return false
    return resolved.receiverExpression.text in getSupportedNames(resolved, FQN_COLOR_SPACES, CLASS_COLOR_SPACES, ctx) &&
      (resolved.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == COLOR_SPACE_SRGB
  }

  private fun parseLong(text: String): Long? {
    val clean = text.replace("_", "").trimEnd('L', 'l', 'U', 'u')
    return if (clean.startsWith("0x") || clean.startsWith("0X")) {
      clean.substring(2).toLongOrNull(16)
    } else {
      clean.toLongOrNull()
    }
  }

  /**
   * Parses a color channel normalized to `0..1`. Like `Color(red: Int, ...)`, `Int` channels keep their lowest 8 bits, while `Float`
   * channels are clamped to `0..1`.
   */
  private fun parseColorComponent(expr: KtExpression, ctx: EvalContext): Float? =
    parseInt(expr, ctx)?.let { (it and 0xFF) / 255f } ?: parseFloat(expr, ctx)?.coerceIn(0f, 1f)

  private fun mapConstantColor(name: String): Color? {
    return when (name) {
      "Black" -> Color.Black
      "DarkGray" -> Color.DarkGray
      "Gray" -> Color.Gray
      "LightGray" -> Color.LightGray
      "White" -> Color.White
      "Red" -> Color.Red
      "Green" -> Color.Green
      "Blue" -> Color.Blue
      "Yellow" -> Color.Yellow
      "Cyan" -> Color.Cyan
      "Magenta" -> Color.Magenta
      "Transparent" -> Color.Transparent
      "Unspecified" -> Color.Unspecified
      else -> null
    }
  }

  /**
   * Updates the color of a specific vertex in-place.
   *
   * Note: Preserved as internal for potential future granular/partial AST updates and standalone programmatic API usage. The main editor
   * dialog uses regenerateLambdaBody to rewrite the entire block cleanly.
   */
  internal fun updateVertexColor(painterCall: KtCallExpression, row: Int, col: Int, newColor: Color): Boolean {
    val body = getMeshLambdaBody(painterCall) ?: return false
    val setVertexCall = findSetVertexCall(body, row, col) ?: return false
    val colorExpr = findArgumentExpression(setVertexCall, ARG_COLOR, 3) ?: return false

    val psiFactory = KtPsiFactory(project)
    val newColorExpr = psiFactory.createExpression("Color(${newColor.toComposeHexLiteral()})")

    colorExpr.replace(newColorExpr)
    return true
  }

  /**
   * Updates the offset of a specific vertex in-place.
   *
   * Note: Preserved as internal for potential future granular/partial AST updates and standalone programmatic API usage. The main editor
   * dialog uses regenerateLambdaBody to rewrite the entire block cleanly.
   */
  internal fun updateVertexOffset(painterCall: KtCallExpression, row: Int, col: Int, newOffset: Offset): Boolean {
    val body = getMeshLambdaBody(painterCall) ?: return false
    val setVertexCall = findSetVertexCall(body, row, col) ?: return false
    val offsetExpr = findArgumentExpression(setVertexCall, ARG_POSITION, 2) ?: return false

    val psiFactory = KtPsiFactory(project)
    val newOffsetExpr = psiFactory.createExpression(String.format(Locale.US, FORMAT_OFFSET, newOffset.x, newOffset.y))

    offsetExpr.replace(newOffsetExpr)
    return true
  }

  /** Updates the rows, columns, and optional hasBicubicColor constructor arguments of the painter call. */
  fun updateConstructorArguments(
    painterCall: KtCallExpression,
    newRows: Int,
    newCols: Int,
    hasBicubicColor: Boolean? = null,
  ): Boolean {
    val rowsExpr = findArgumentExpression(painterCall, ARG_ROWS, 0) ?: return false
    val colsExpr = findArgumentExpression(painterCall, ARG_COLUMNS, 1) ?: return false

    val psiFactory = KtPsiFactory(project)
    val newRowsExpr = psiFactory.createExpression((newRows - 1).toString())
    val newColsExpr = psiFactory.createExpression((newCols - 1).toString())

    rowsExpr.replace(newRowsExpr)
    colsExpr.replace(newColsExpr)

    if (hasBicubicColor != null) {
      val bicubicExpr = findArgumentExpression(painterCall, ARG_HAS_BICUBIC_COLOR, 2)
      if (bicubicExpr != null) {
        bicubicExpr.replace(psiFactory.createExpression(hasBicubicColor.toString()))
      } else if (hasBicubicColor) {
        painterCall.valueArgumentList?.addArgument(psiFactory.createArgument("$ARG_HAS_BICUBIC_COLOR = true"))
      }
    }
    return true
  }

  /** Completely clears and regenerates all setVertex statements inside the lambda body block. */
  fun regenerateLambdaBody(painterCall: KtCallExpression, meshPoints: List<List<MeshGradientPoint>>): Boolean {
    val body = getMeshLambdaBody(painterCall) ?: return false

    // Delete all existing statements inside lambda body
    body.statements.forEach { it.delete() }

    val psiFactory = KtPsiFactory(project)

    meshPoints.forEachIndexed { r, row ->
      row.forEachIndexed { c, point ->
        val statementStr = formatSetVertexCall(r, c, point)
        val statementExpr = psiFactory.createExpression(statementStr)
        body.add(statementExpr)
        body.add(psiFactory.createNewLine())
      }
    }

    return true
  }

  private fun findSetVertexCall(body: KtBlockExpression, row: Int, col: Int): KtCallExpression? {
    return SyntaxTraverser.psiTraverser(body)
      .filter(KtCallExpression::class.java)
      .filter { it.calleeExpression?.text == FUN_SET_VERTEX }
      .firstOrNull { call ->
        val rExpr = findArgumentExpression(call, ARG_ROW, 0) ?: return@firstOrNull false
        val cExpr = findArgumentExpression(call, ARG_COLUMN, 1) ?: return@firstOrNull false
        val r = resolveExpression(rExpr).text.toIntOrNull()
        val c = resolveExpression(cExpr).text.toIntOrNull()
        r == row && c == col
      }
  }

  private fun getQualifiedCalleeText(expr: KtExpression): String? {
    if (expr is KtCallExpression) {
      return expr.calleeExpression?.text
    }
    if (expr is KtDotQualifiedExpression) {
      val selector = expr.selectorExpression
      if (selector is KtCallExpression) {
        val selectorCallee = selector.calleeExpression?.text ?: return null
        return expr.receiverExpression.text + "." + selectorCallee
      }
    }
    return null
  }

  private fun getCallExpression(expr: KtExpression): KtCallExpression? {
    if (expr is KtCallExpression) return expr
    if (expr is KtDotQualifiedExpression) {
      val selector = expr.selectorExpression
      if (selector is KtCallExpression) return selector
    }
    return null
  }

  private fun getSupportedNames(expr: KtExpression, fqn: String, defaultName: String, ctx: EvalContext): List<String> {
    val file = expr.containingFile as? KtFile ?: return listOf(defaultName, fqn)
    val alias = ctx.imports(file).firstOrNull { it.importedFqName?.asString() == fqn }?.aliasName
    return listOf(alias ?: defaultName, fqn)
  }

  private fun isCollectionListFunction(expr: KtExpression, callee: String, ctx: EvalContext? = null): Boolean {
    if (callee in COLLECTION_LIST_FUNCTIONS) return true
    val file = expr.containingFile as? KtFile ?: return false
    val imports = ctx?.imports(file) ?: file.importDirectives
    val importDirective = imports.firstOrNull { it.aliasName == callee } ?: return false
    val fqn = importDirective.importedFqName?.asString() ?: return false
    return fqn in COLLECTION_LIST_MAP.keys
  }

  private fun resolveExpression(expr: KtExpression, onDynamic: (() -> Unit)? = null): KtExpression {
    val ctx = EvalContext(onDynamic)
    return ctx.withFrame { resolveExpression(expr, ctx) } ?: expr
  }

  /**
   * Follows references, `remember`/state wrappers, parentheses and list indexing from [expr] until reaching an expression that can't be
   * simplified any further, which is returned. Must be called within an [EvalContext.withFrame] of [ctx].
   */
  private fun resolveExpression(expr: KtExpression, ctx: EvalContext): KtExpression {
    var current = expr
    val visited = mutableSetOf<KtExpression>()
    while (visited.add(current)) {
      ProgressManager.checkCanceled()
      current = resolveStep(current, ctx) ?: break
    }
    return current
  }

  /** Performs a single resolution step on [expr], or returns null if it can't be simplified. */
  private fun resolveStep(expr: KtExpression, ctx: EvalContext): KtExpression? {
    if (expr is KtParenthesizedExpression) return expr.expression
    val unwrapped = unwrapRemember(expr, ctx)
    if (unwrapped != expr) return unwrapped
    val unwrappedDynamic = unwrapDynamicState(expr, ctx)
    if (unwrappedDynamic != expr) return unwrappedDynamic
    if (expr is KtDotQualifiedExpression && (expr.selectorExpression as? KtSimpleNameExpression)?.getReferencedName() == ARG_VALUE) {
      ctx.markDynamic()
      return expr.receiverExpression
    }
    return when (expr) {
      is KtSimpleNameExpression -> resolveReference(expr, ctx)
      is KtArrayAccessExpression -> resolveArrayAccess(expr, ctx)
      else -> null
    }
  }

  private fun unwrapDynamicState(expr: KtExpression, ctx: EvalContext): KtExpression {
    val call = getCallExpression(expr) ?: return expr
    val rawCallee = call.calleeExpression?.text ?: return expr
    val qualifiedCallee = getQualifiedCalleeText(expr)

    val canonicalName =
      DYNAMIC_STATE_MAP[qualifiedCallee]
        ?: DYNAMIC_STATE_MAP.values.firstOrNull { it == rawCallee }
        ?: run {
          val file = expr.containingFile as? KtFile ?: return@run null
          val importDirective = ctx.imports(file).firstOrNull { it.aliasName == rawCallee } ?: return@run null
          DYNAMIC_STATE_MAP[importDirective.importedFqName?.asString()]
        }
        ?: return expr

    val extracted =
      when (canonicalName) {
        "animateFloat",
        "animateColor" -> findArgumentExpression(call, ARG_INITIAL_VALUE, 0) ?: findArgumentExpression(call, ARG_TARGET_VALUE, 1)
        "animateFloatAsState",
        "animateColorAsState" -> findArgumentExpression(call, ARG_TARGET_VALUE, 0)
        "mutableStateOf",
        "mutableFloatStateOf",
        "mutableIntStateOf",
        "mutableLongStateOf" -> findArgumentExpression(call, ARG_VALUE, 0)
        else -> null
      }
    if (extracted != null) {
      ctx.markDynamic()
      return extracted
    }
    return expr
  }

  private fun unwrapRemember(expr: KtExpression, ctx: EvalContext): KtExpression {
    val call = getCallExpression(expr) ?: return expr
    if (getQualifiedCalleeText(expr) !in getSupportedNames(expr, FQN_REMEMBER, FUN_REMEMBER, ctx)) return expr
    val lambdaArg = call.valueArguments.lastOrNull() as? KtLambdaArgument
    val lastStatement = lambdaArg?.getLambdaExpression()?.bodyExpression?.statements?.lastOrNull() ?: return expr
    return unwrapRemember(lastStatement, ctx)
  }

  /**
   * Resolves [nameExpr] to the initializer of the declaration it refers to by walking the enclosing scopes and then the imported and
   * same-package top-level properties. Returns null, flagging the evaluation as dynamic, when the name is bound to a value only known at
   * runtime (parameters, loop variables, destructured values) or when the referenced property is already being evaluated.
   */
  private fun resolveReference(nameExpr: KtSimpleNameExpression, ctx: EvalContext): KtExpression? {
    val name = nameExpr.getReferencedName()
    var child: PsiElement = nameExpr
    var scope: PsiElement? = nameExpr.parent
    while (scope != null) {
      when (val binding = findBinding(scope, child, nameExpr, name)) {
        is Binding.Value -> return evaluateProperty(binding.property, ctx)
        Binding.Opaque -> {
          ctx.markDynamic()
          return null
        }
        null -> Unit
      }
      if (scope is KtFile) break
      child = scope
      scope = scope.parent
    }
    val file = nameExpr.containingFile as? KtFile ?: return null
    val property = resolveImportedOrSamePackageProperty(project, file, name) ?: return null
    return evaluateProperty(property, ctx)
  }

  /**
   * Finds the declaration binding [name] in [scope], an ancestor of [nameExpr] reached through its direct [child], that is visible from
   * [nameExpr].
   */
  private fun findBinding(scope: PsiElement, child: PsiElement, nameExpr: KtSimpleNameExpression, name: String): Binding? =
    when (scope) {
      is KtBlockExpression -> findBlockBinding(scope, nameExpr, name)
      is KtFunctionLiteral ->
        if (scope.hasParameterSpecification()) {
          scope.valueParameters.findBinding(name)
        } else {
          // Without resolving the call, it's unknown whether the lambda has an implicit parameter, so `it` is conservatively assumed to
          // refer to one.
          Binding.Opaque.takeIf { name == IMPLICIT_LAMBDA_PARAMETER }
        }
      is KtDeclarationWithBody -> scope.valueParameters.findBinding(name)
      is KtForExpression ->
        if (PsiTreeUtil.isAncestor(scope.body, nameExpr, false)) listOfNotNull(scope.loopParameter).findBinding(name) else null
      is KtCatchClause ->
        if (PsiTreeUtil.isAncestor(scope.catchBody, nameExpr, false)) listOfNotNull(scope.catchParameter).findBinding(name) else null
      is KtWhenExpression ->
        scope.subjectVariable?.takeIf { it.name == name && !PsiTreeUtil.isAncestor(it, nameExpr, false) }?.let { Binding.Value(it) }
      is KtClassBody -> findClassBodyBinding(scope, child, nameExpr, name)
      // The class body is handled above; other children (supertype list, primary constructor) see all the constructor parameters.
      is KtClassOrObject -> if (child is KtClassBody) null else scope.primaryConstructorParameters.findBinding(name)
      is KtFile -> findMemberBinding(scope.declarations.filterIsInstance<KtProperty>(), nameExpr, name)
      else -> null
    }

  /**
   * Finds the binding of [name] in a class [body], reached through its member [child]. Primary constructor parameters are visible, and
   * shadow members, in property initializers and `init` blocks. Elsewhere, only those declared as properties (`val`/`var`) are visible.
   * Members of the companion object are visible too, with a lower priority than the members of the class itself. Inherited members are not
   * considered.
   */
  private fun findClassBodyBinding(body: KtClassBody, child: PsiElement, nameExpr: KtSimpleNameExpression, name: String): Binding? {
    val classOrObject = body.parent as? KtClassOrObject
    val constructorParameters = classOrObject?.primaryConstructorParameters.orEmpty()
    val isInitializer =
      child is KtAnonymousInitializer ||
        (child is KtProperty &&
          (PsiTreeUtil.isAncestor(child.initializer, nameExpr, false) || PsiTreeUtil.isAncestor(child.delegateExpression, nameExpr, false)))
    if (isInitializer) {
      constructorParameters.findBinding(name)?.let {
        return it
      }
    }
    return findMemberBinding(body.properties, nameExpr, name)
      ?: constructorParameters.filter { it.hasValOrVar() }.findBinding(name)
      ?: classOrObject?.companionObjects.orEmpty().firstNotNullOfOrNull { companion ->
        companion.body?.let { findMemberBinding(it.properties, nameExpr, name) }
      }
  }

  /** Finds the last local declaration of [name] in [block] that precedes [nameExpr]. */
  private fun findBlockBinding(block: KtBlockExpression, nameExpr: KtSimpleNameExpression, name: String): Binding? {
    for (statement in block.statements.asReversed()) {
      if (statement.textRange.endOffset > nameExpr.textOffset) continue
      if (statement is KtProperty && statement.name == name) return Binding.Value(statement)
      if (statement is KtDestructuringDeclaration && statement.entries.any { it.name == name }) return Binding.Opaque
    }
    return null
  }

  private fun findMemberBinding(properties: List<KtProperty>, nameExpr: KtSimpleNameExpression, name: String): Binding? {
    val property = properties.firstOrNull { it.name == name } ?: return null
    // A member referencing itself from its own initializer has no value to evaluate.
    return if (PsiTreeUtil.isAncestor(property, nameExpr, true)) Binding.Opaque else Binding.Value(property)
  }

  private fun List<KtParameter>.findBinding(name: String): Binding? =
    Binding.Opaque.takeIf { any { it.name == name || it.destructuringDeclaration?.entries?.any { entry -> entry.name == name } == true } }

  private fun evaluateProperty(property: KtProperty, ctx: EvalContext): KtExpression? {
    if (!ctx.enter(property)) {
      ctx.markDynamic()
      return null
    }
    // A `var` may be reassigned after its declaration, so its initializer is only a best guess.
    if (property.isVar) ctx.markDynamic()
    return property.initializer ?: property.delegateExpression?.also { ctx.markDynamic() }
  }

  private fun resolveArrayAccess(arrayAccess: KtArrayAccessExpression, ctx: EvalContext): KtExpression? {
    val arrayExpr = arrayAccess.arrayExpression ?: return null
    val indexExpr = arrayAccess.indexExpressions.singleOrNull() ?: return null
    val index = parseInt(indexExpr, ctx) ?: return null

    val resolvedArray = resolveExpression(arrayExpr, ctx)
    val call = getCallExpression(resolvedArray) ?: return null
    val callee = getQualifiedCalleeText(resolvedArray) ?: return null
    if (!isCollectionListFunction(resolvedArray, callee, ctx)) return null
    return call.valueArguments.getOrNull(index)?.getArgumentExpression()
  }

  /**
   * Collects all resolvable [Color] declarations in scope at [callExpr], including local variables in enclosing blocks, top-level
   * properties in the current file, and top-level properties imported from the module and its dependencies.
   */
  @RequiresReadLock
  fun collectAvailableColors(callExpr: KtCallExpression): List<Color> {
    val properties = mutableListOf<KtProperty>()
    var current: PsiElement? = callExpr
    while (current != null) {
      if (current is KtBlockExpression) {
        val blockProps = PsiTreeUtil.getChildrenOfType(current, KtProperty::class.java).orEmpty()
        properties.addAll(blockProps.filter { it.textRange.endOffset <= callExpr.textOffset })
      } else if (current is KtClassBody || current is KtFile) {
        val declarations = (current as? KtClassBody)?.declarations ?: (current as? KtFile)?.declarations
        properties.addAll(declarations.orEmpty().filterIsInstance<KtProperty>())
      }
      current = current.parent
    }

    val file = callExpr.containingFile as? KtFile
    if (file != null) {
      properties.addAll(findImportedAndSamePackageProperties(project, file).filter { mayHoldColor(it) })
    }

    // Each property is a separate evaluation with its own step budget; only the import directives are shared.
    val importsByFile = mutableMapOf<KtFile, List<KtImportDirective>>()
    return properties
      .mapNotNull { prop -> prop.initializer?.let { parseColor(it, EvalContext(onDynamic = null, importsByFile)) } }
      .filter { it.isSpecified }
      .distinct()
  }

  /**
   * Returns false if [property] is explicitly typed with a type that can't be a [Color]. Only stub-backed data is accessed, so this can be
   * used to discard candidates before loading their AST.
   */
  private fun mayHoldColor(property: KtProperty): Boolean {
    val typeElement = property.typeReference?.typeElement ?: return true
    val userType = ((typeElement as? KtNullableType)?.innerType ?: typeElement) as? KtUserType ?: return false
    // Other type names may be aliases of Color, so only well-known types are discarded.
    return userType.referencedName !in NON_COLOR_TYPE_NAMES
  }

  /** A declaration binding a name in some scope. */
  private sealed interface Binding {
    /** A property whose initializer can be statically evaluated. */
    data class Value(val property: KtProperty) : Binding

    /** A parameter, loop variable or destructured value whose value is only known at runtime. */
    data object Opaque : Binding
  }

  /**
   * State shared by all the steps of a single static evaluation.
   *
   * It tracks the properties whose initializers are being evaluated, so that self-referencing or mutually recursive declarations are
   * reported as unresolved instead of recursing forever, bounds the evaluation depth and number of steps, and caches the import directives
   * of each visited file in [importsByFile].
   */
  private class EvalContext(
    private val onDynamic: (() -> Unit)?,
    private val importsByFile: MutableMap<KtFile, List<KtImportDirective>> = mutableMapOf(),
  ) {
    private val activeProperties = mutableSetOf<KtProperty>()
    private val frames = ArrayDeque<MutableList<KtProperty>>()
    private var steps = 0

    /** Flags the evaluated expression as depending on dynamic or unresolvable values. */
    fun markDynamic() {
      onDynamic?.invoke()
    }

    fun imports(file: KtFile): List<KtImportDirective> = importsByFile.getOrPut(file) { file.importDirectives }

    /**
     * Runs [block] as a nested evaluation step. Properties [enter]ed during the step stop being considered under evaluation once it
     * completes. Returns null, flagging the evaluation as dynamic, if the maximum depth or number of steps is exceeded.
     */
    fun <T> withFrame(block: () -> T?): T? {
      if (frames.size >= MAX_EVALUATION_DEPTH || ++steps > MAX_EVALUATION_STEPS) {
        markDynamic()
        return null
      }
      ProgressManager.checkCanceled()
      val frame = mutableListOf<KtProperty>()
      frames.addLast(frame)
      try {
        return block()
      } finally {
        frames.removeLast()
        activeProperties.removeAll(frame)
      }
    }

    /** Marks [property] as being evaluated. Returns false if it already is, which means its value depends on itself. */
    fun enter(property: KtProperty): Boolean {
      check(frames.isNotEmpty()) { "Properties can only be entered within a frame" }
      if (!activeProperties.add(property)) return false
      frames.last().add(property)
      return true
    }
  }
}
