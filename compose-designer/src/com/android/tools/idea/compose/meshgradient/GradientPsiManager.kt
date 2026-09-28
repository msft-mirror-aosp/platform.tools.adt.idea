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
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import java.util.Locale
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument

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
    val unwrapped = (expr as? KtPrefixExpression)?.takeIf { it.operationReference.text == "*" }?.baseExpression ?: expr
    val resolved = resolveExpression(unwrapped, onDynamic)
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
    val text = resolveExpression(expr, onDynamic).text
    return when {
      text.endsWith(TILE_MODE_CLAMP) -> TileMode.Clamp
      text.endsWith(TILE_MODE_REPEATED) -> TileMode.Repeated
      text.endsWith(TILE_MODE_MIRROR) -> TileMode.Mirror
      text.endsWith(TILE_MODE_DECAL) -> TileMode.Decal
      else -> null
    }
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

    val rows = resolveExpression(rowsExpr, onDynamic).text.toIntOrNull() ?: return null
    val cols = resolveExpression(colsExpr, onDynamic).text.toIntOrNull() ?: return null

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
      val row = rowExpr?.let { resolveExpression(it, onDynamic).text.toIntOrNull() }
      val col = colExpr?.let { resolveExpression(it, onDynamic).text.toIntOrNull() }
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

  private fun parseOffset(expr: KtExpression, onDynamic: (() -> Unit)? = null): Offset? {
    val resolvedExpr = resolveExpression(expr, onDynamic)
    if (resolvedExpr is KtParenthesizedExpression) {
      val inner = resolvedExpr.expression ?: return null
      return parseOffset(inner, onDynamic)
    }
    if (resolvedExpr is KtPrefixExpression) {
      val base = resolvedExpr.baseExpression ?: return null
      val offset = parseOffset(base, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
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
          val l = parseOffset(left, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
          val r = parseOffset(right, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
          l + r
        }
        "-" -> {
          val l = parseOffset(left, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
          val r = parseOffset(right, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
          l - r
        }
        "*" -> {
          val lOffset = parseOffset(left, onDynamic)?.takeIf { it != Offset.Unspecified }
          if (lOffset != null) {
            val rFloat = parseFloat(right, onDynamic) ?: return null
            lOffset * rFloat
          } else {
            val lFloat = parseFloat(left, onDynamic) ?: return null
            val rOffset = parseOffset(right, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
            rOffset * lFloat
          }
        }
        "/" -> {
          val lOffset = parseOffset(left, onDynamic)?.takeIf { it != Offset.Unspecified } ?: return null
          val rFloat = parseFloat(right, onDynamic) ?: return null
          if (rFloat == 0f) null else lOffset / rFloat
        }
        else -> null
      }
    }

    val call = getCallExpression(resolvedExpr)
    if (call != null) {
      val calleeText = getQualifiedCalleeText(resolvedExpr) ?: return null
      val supportedNames = getSupportedNames(expr, "androidx.compose.ui.geometry.Offset", FUN_OFFSET)
      if (calleeText !in supportedNames) return null

      val xExpr = findArgumentExpression(call, ARG_X, 0) ?: return null
      val yExpr = findArgumentExpression(call, ARG_Y, 1) ?: return null

      val x = parseFloat(xExpr, onDynamic) ?: return null
      val y = parseFloat(yExpr, onDynamic) ?: return null
      return Offset(x, y)
    }

    if (resolvedExpr is KtDotQualifiedExpression) {
      val text = resolvedExpr.text
      val supportedNames = getSupportedNames(expr, "androidx.compose.ui.geometry.Offset", FUN_OFFSET)
      val prefix = supportedNames.firstOrNull { text.startsWith("$it.") }
      if (prefix != null) {
        return when (text.removePrefix("$prefix.")) {
          "Zero" -> Offset.Zero
          "Unspecified" -> Offset.Unspecified
          "Infinite" -> Offset.Infinite
          else -> null
        }
      }
    }

    return null
  }

  private fun parseFloat(expr: KtExpression, onDynamic: (() -> Unit)? = null): Float? {
    val resolved = resolveExpression(expr, onDynamic)
    if (resolved is KtParenthesizedExpression) {
      val inner = resolved.expression ?: return null
      return parseFloat(inner, onDynamic)
    }
    if (resolved is KtPrefixExpression) {
      val base = resolved.baseExpression ?: return null
      return when (resolved.operationReference.text) {
        "-" -> parseFloat(base, onDynamic)?.let { -it }
        "+" -> parseFloat(base, onDynamic)
        else -> null
      }
    }
    if (resolved is KtBinaryExpression) {
      val left = resolved.left ?: return null
      val right = resolved.right ?: return null
      val l = parseFloat(left, onDynamic) ?: return null
      val r = parseFloat(right, onDynamic) ?: return null
      return when (resolved.operationReference.text) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "/" -> if (r == 0f) null else l / r
        else -> null
      }
    }
    if (resolved.text.endsWith("POSITIVE_INFINITY")) {
      return Float.POSITIVE_INFINITY
    }
    if (resolved.text.endsWith("NEGATIVE_INFINITY")) {
      return Float.NEGATIVE_INFINITY
    }
    return resolved.text.removeSuffix("f").removeSuffix("F").toFloatOrNull()
  }

  private fun parseColor(expr: KtExpression, onDynamic: (() -> Unit)? = null): Color? {
    val resolvedExpr = resolveExpression(expr, onDynamic)
    val call = getCallExpression(resolvedExpr)
    if (call != null) {
      val calleeText = getQualifiedCalleeText(resolvedExpr) ?: return null
      val supportedNames = getSupportedNames(expr, "androidx.compose.ui.graphics.Color", FUN_COLOR)
      if (calleeText !in supportedNames) return null

      val args = call.valueArguments
      if (args.isEmpty()) return null

      if (args.size == 1) {
        val argExpr = args[0].getArgumentExpression() ?: return null
        val resolvedArg = resolveExpression(argExpr, onDynamic)
        val text = resolvedArg.text.removeSuffix(".toInt()").removeSuffix(".toLong()")
        val longValue = parseLong(text) ?: return null
        return Color(longValue)
      } else if (args.size >= 3) {
        val rExpr = findArgumentExpression(call, ARG_RED, 0) ?: return null
        val gExpr = findArgumentExpression(call, ARG_GREEN, 1) ?: return null
        val bExpr = findArgumentExpression(call, ARG_BLUE, 2) ?: return null
        val aExpr = findArgumentExpression(call, ARG_ALPHA, 3)

        val r = parseColorComponent(rExpr, onDynamic) ?: return null
        val g = parseColorComponent(gExpr, onDynamic) ?: return null
        val b = parseColorComponent(bExpr, onDynamic) ?: return null
        val a = if (aExpr != null) parseColorComponent(aExpr, onDynamic) ?: 1f else 1f

        return Color(r, g, b, a)
      }
    } else if (resolvedExpr is KtDotQualifiedExpression) {
      val text = resolvedExpr.text
      val supportedNames = getSupportedNames(expr, "androidx.compose.ui.graphics.Color", FUN_COLOR)
      val prefix = supportedNames.firstOrNull { text.startsWith("$it.") }
      if (prefix != null) {
        val colorName = text.removePrefix("$prefix.").uppercase()
        return mapConstantColor(colorName)
      }
    }
    return null
  }

  private fun parseLong(text: String): Long? {
    val clean = text.replace("_", "").trimEnd('L', 'l', 'U', 'u')
    return if (clean.startsWith("0x") || clean.startsWith("0X")) {
      clean.substring(2).toLongOrNull(16)
    } else {
      clean.toLongOrNull()
    }
  }

  private fun parseColorComponent(expr: KtExpression, onDynamic: (() -> Unit)? = null): Float? {
    val resolved = resolveExpression(expr, onDynamic)
    val rawText = resolved.text
    val text = rawText.removeSuffix("f").removeSuffix("F")
    return if (
      text.contains(".") ||
        rawText.endsWith("f") ||
        rawText.endsWith("F") ||
        resolved is KtBinaryExpression ||
        resolved is KtPrefixExpression ||
        resolved is KtParenthesizedExpression
    ) {
      // Float: 0f .. 1f
      parseFloat(resolved, onDynamic)?.coerceIn(0f, 1f)
    } else {
      // Int: 0 .. 255
      val intVal = text.toIntOrNull() ?: return null
      (intVal.coerceIn(0, 255).toFloat() / 255f)
    }
  }

  private fun mapConstantColor(name: String): Color? {
    return when (name) {
      "BLACK" -> Color.Black
      "DARKGRAY" -> Color.DarkGray
      "GRAY" -> Color.Gray
      "LIGHTGRAY" -> Color.LightGray
      "WHITE" -> Color.White
      "RED" -> Color.Red
      "GREEN" -> Color.Green
      "BLUE" -> Color.Blue
      "YELLOW" -> Color.Yellow
      "CYAN" -> Color.Cyan
      "MAGENTA" -> Color.Magenta
      "TRANSPARENT" -> Color.Transparent
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

  private fun getSupportedNames(expr: KtExpression, fqn: String, defaultName: String): List<String> {
    val file = expr.containingFile as? KtFile ?: return listOf(defaultName, fqn)
    val importDirective = file.importDirectives.firstOrNull { it.importedFqName?.asString() == fqn }
    val alias = importDirective?.aliasName
    return if (alias != null) {
      listOf(alias, fqn)
    } else {
      listOf(defaultName, fqn)
    }
  }

  private fun isCollectionListFunction(expr: KtExpression, callee: String): Boolean {
    if (callee in COLLECTION_LIST_FUNCTIONS) return true
    val file = expr.containingFile as? KtFile ?: return false
    val importDirective = file.importDirectives.firstOrNull { it.aliasName == callee } ?: return false
    val fqn = importDirective.importedFqName?.asString() ?: return false
    return fqn in COLLECTION_LIST_MAP.keys
  }

  private fun resolveExpression(expr: KtExpression, onDynamic: (() -> Unit)? = null): KtExpression {
    var current = expr
    val visited = mutableSetOf<KtExpression>()
    while (true) {
      if (!visited.add(current)) {
        break
      }
      if (current is KtParenthesizedExpression) {
        val inner = current.expression
        if (inner != null && inner != current) {
          current = inner
          continue
        }
      }
      val unwrapped = unwrapRemember(current)
      if (unwrapped != current) {
        current = unwrapped
        continue
      }
      val unwrappedDynamic = unwrapDynamicState(current, onDynamic)
      if (unwrappedDynamic != current) {
        current = unwrappedDynamic
        continue
      }
      if (
        current is KtDotQualifiedExpression && (current.selectorExpression as? KtSimpleNameExpression)?.getReferencedName() == ARG_VALUE
      ) {
        onDynamic?.invoke()
        current = current.receiverExpression
        continue
      }

      if (current is KtSimpleNameExpression) {
        val resolved = resolveLocalVariable(current, onDynamic)
        if (resolved != null && resolved != current) {
          current = resolved
          continue
        }
      }

      if (current is KtArrayAccessExpression) {
        val resolved = resolveArrayAccess(current, onDynamic)
        if (resolved != null && resolved != current) {
          current = resolved
          continue
        }
      }

      break
    }
    return current
  }

  private fun unwrapDynamicState(expr: KtExpression, onDynamic: (() -> Unit)?): KtExpression {
    val call = getCallExpression(expr) ?: return expr
    val rawCallee = call.calleeExpression?.text ?: return expr
    val qualifiedCallee = getQualifiedCalleeText(expr)

    val canonicalName =
      DYNAMIC_STATE_MAP[qualifiedCallee]
        ?: DYNAMIC_STATE_MAP.values.firstOrNull { it == rawCallee }
        ?: run {
          val file = expr.containingFile as? KtFile ?: return@run null
          val importDirective = file.importDirectives.firstOrNull { it.aliasName == rawCallee } ?: return@run null
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
      onDynamic?.invoke()
      return extracted
    }
    return expr
  }

  private fun unwrapRemember(expr: KtExpression): KtExpression {
    val call = getCallExpression(expr)
    if (call != null) {
      val calleeText = getQualifiedCalleeText(expr)
      val supportedNames = getSupportedNames(expr, "androidx.compose.runtime.remember", "remember")
      if (calleeText in supportedNames) {
        val lambdaArg = call.valueArguments.lastOrNull() as? KtLambdaArgument
        val body = lambdaArg?.getLambdaExpression()?.bodyExpression
        if (body != null) {
          val lastStatement = PsiTreeUtil.getChildrenOfType(body, KtExpression::class.java)?.lastOrNull()
          if (lastStatement != null) {
            return unwrapRemember(lastStatement)
          }
        }
      }
    }
    return expr
  }

  private fun resolveLocalVariable(nameExpr: KtSimpleNameExpression, onDynamic: (() -> Unit)? = null): KtExpression? {
    val targetName = nameExpr.getReferencedName()
    var current: PsiElement? = nameExpr
    while (current != null) {
      if (current is KtBlockExpression) {
        val properties = PsiTreeUtil.getChildrenOfType(current, KtProperty::class.java)
        val property = properties?.lastOrNull { it.name == targetName && it.textRange.endOffset <= nameExpr.textOffset }
        if (property != null) {
          return property.initializer ?: property.delegateExpression?.also { onDynamic?.invoke() }
        }
      }
      if (current is KtClassBody || current is KtFile) {
        val declarations = (current as? KtClassBody)?.declarations ?: (current as? KtFile)?.declarations
        val property = declarations?.firstOrNull { it is KtProperty && it.name == targetName } as? KtProperty
        if (property != null) {
          return property.initializer ?: property.delegateExpression?.also { onDynamic?.invoke() }
        }
      }
      current = current.parent
    }
    val file = nameExpr.containingFile as? KtFile ?: return null
    return resolveImportedOrSamePackageProperty(project, file, targetName)?.initializer
  }

  private fun resolveArrayAccess(arrayAccess: KtArrayAccessExpression, onDynamic: (() -> Unit)? = null): KtExpression? {
    val arrayExpr = arrayAccess.arrayExpression ?: return null
    val indexExprs = arrayAccess.indexExpressions
    if (indexExprs.size != 1) return null
    val indexExpr = indexExprs[0]

    val resolvedIndexExpr = resolveExpression(indexExpr, onDynamic)
    val index = resolvedIndexExpr.text.toIntOrNull() ?: return null

    val resolvedArray = resolveExpression(arrayExpr, onDynamic)

    val call = getCallExpression(resolvedArray)
    if (call != null) {
      val callee = getQualifiedCalleeText(resolvedArray)
      if (callee != null && isCollectionListFunction(resolvedArray, callee)) {
        val args = call.valueArguments
        if (index in args.indices) {
          return args[index].getArgumentExpression()
        }
      }
    }
    return null
  }

  /**
   * Collects all resolvable [Color] declarations in scope at [callExpr], including local variables in enclosing blocks, top-level
   * properties in the current file, and top-level properties imported from the module.
   */
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
      properties.addAll(findImportedAndSamePackageProperties(project, file))
    }

    return properties.mapNotNull { prop -> prop.initializer?.let { parseColor(it) } }.distinct()
  }
}
