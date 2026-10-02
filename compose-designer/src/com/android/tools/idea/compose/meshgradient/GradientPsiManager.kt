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
import com.android.tools.idea.compose.preview.message
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
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
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression

internal const val FUN_SET_VERTEX = "setVertex"
internal const val FUN_OFFSET = "Offset"
internal const val FUN_COLOR = "Color"
internal const val FUN_ARRAY_OF = "arrayOf"
internal const val FUN_PAIR = "Pair"
internal const val OP_TO = "to"

internal const val ARG_ROWS = "rows"
internal const val ARG_COLUMNS = "columns"
internal const val ARG_HAS_BICUBIC_COLOR = "hasBicubicColor"
private const val ARG_ROW = "row"
private const val ARG_COLUMN = "column"
internal const val ARG_POSITION = "position"
internal const val ARG_COLOR = "color"
internal const val ARG_LEFT_CONTROL_POINT = "leftControlPoint"
internal const val ARG_TOP_CONTROL_POINT = "topControlPoint"
internal const val ARG_RIGHT_CONTROL_POINT = "rightControlPoint"
internal const val ARG_BOTTOM_CONTROL_POINT = "bottomControlPoint"
private const val ARG_X = "x"
private const val ARG_Y = "y"
private const val ARG_RED = "red"
private const val ARG_GREEN = "green"
private const val ARG_BLUE = "blue"
private const val ARG_ALPHA = "alpha"
private const val ARG_INITIAL_VALUE = "initialValue"
private const val ARG_TARGET_VALUE = "targetValue"
private const val ARG_VALUE = "value"
internal const val ARG_COLORS = "colors"
internal const val ARG_COLOR_STOPS = "colorStops"
internal const val ARG_START = "start"
internal const val ARG_END = "end"
internal const val ARG_START_X = "startX"
internal const val ARG_END_X = "endX"
internal const val ARG_START_Y = "startY"
internal const val ARG_END_Y = "endY"
internal const val ARG_CENTER = "center"
internal const val ARG_RADIUS = "radius"
internal const val ARG_TILE_MODE = "tileMode"

/** Positional index of the `setVertex` position argument. */
internal const val INDEX_POSITION = 2

/** Positional index of the `setVertex` color argument. */
internal const val INDEX_COLOR = 3

/** `setVertex` control point parameters, in declaration order, starting at positional index 4. */
internal val CONTROL_POINT_ARGS = listOf(ARG_LEFT_CONTROL_POINT, ARG_TOP_CONTROL_POINT, ARG_RIGHT_CONTROL_POINT, ARG_BOTTOM_CONTROL_POINT)

/** Positional index of the first `setVertex` control point argument. */
internal const val INDEX_FIRST_CONTROL_POINT = 4

/** Minimum number of vertices per mesh axis supported by the editor. */
internal const val MIN_MESH_VERTICES = 2

/** Maximum number of vertices per mesh axis supported by the editor. */
internal const val MAX_MESH_VERTICES = 10

private const val TILE_MODE_CLAMP = "Clamp"
private const val TILE_MODE_REPEATED = "Repeated"
private const val TILE_MODE_MIRROR = "Mirror"
private const val TILE_MODE_DECAL = "Decal"

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

internal const val FQN_OFFSET = "androidx.compose.ui.geometry.Offset"
internal const val FQN_COLOR = "androidx.compose.ui.graphics.Color"
internal const val FQN_TILE_MODE = "androidx.compose.ui.graphics.TileMode"
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

/**
 * Formats a `setVertex` call for the vertex at row [r] and column [c]. [offsetName] and [colorName] are the names used to reference the
 * Compose `Offset` and `Color` classes.
 */
internal fun formatSetVertexCall(
  r: Int,
  c: Int,
  point: MeshGradientPoint,
  offsetName: String = FUN_OFFSET,
  colorName: String = FUN_COLOR,
): String {
  val arguments = buildList {
    add(r.toString())
    add(c.toString())
    add(point.positionExpression ?: generateOffsetSource(point.position, offsetName))
    add(point.colorExpression ?: generateColorSource(point.color, colorName))
    val controlPoints = listOf(point.leftBezierOffset, point.topBezierOffset, point.rightBezierOffset, point.bottomBezierOffset)
    CONTROL_POINT_ARGS.zip(controlPoints).forEach { (name, controlPoint) ->
      if (controlPoint != Offset.Unspecified) {
        add("$name = ${generateOffsetSource(controlPoint, offsetName)}")
      }
    }
  }
  return "$FUN_SET_VERTEX(${arguments.joinToString(", ")})"
}

/** Generates a hexadecimal Compose color literal for [color], referencing the `Color` class as [colorName]. */
internal fun generateColorSource(color: Color, colorName: String = FUN_COLOR): String = "$colorName(${color.toComposeHexLiteral()})"

/**
 * Finds the argument for parameter [name], either passed by name or, when not named, at positional [index]. Pass a negative [index] to only
 * match named arguments.
 */
internal fun KtCallExpression.findValueArgument(name: String, index: Int): KtValueArgument? {
  val args = valueArguments
  args
    .firstOrNull { it.getArgumentName()?.asName?.asString() == name }
    ?.let {
      return it
    }
  val arg = args.getOrNull(index) ?: return null
  return arg.takeIf { it !is KtLambdaArgument && it.getArgumentName() == null }
}

/** Returns the body of the `MeshGradientPainter` block lambda, passed either as a trailing lambda or as a (possibly named) argument. */
internal fun KtCallExpression.meshLambdaBody(): KtBlockExpression? {
  val lambda = valueArguments.firstNotNullOfOrNull { arg ->
    (arg as? KtLambdaArgument)?.getLambdaExpression() ?: arg.getArgumentExpression() as? KtLambdaExpression
  }
  return lambda?.bodyExpression
}

/**
 * Returns the name of the `Brush` gradient factory function called by this expression, or null if it is not one. See [gradientCallKind] for
 * the matching rules.
 */
internal fun KtCallExpression.brushGradientFunction(): String? = gradientCallKind()?.takeIf { it != GradientCallKind.MESH }?.calleeName

/** Import directives of the files visited by an analysis, shared by its evaluations. */
private typealias ImportsByFile = MutableMap<KtFile, List<KtImportDirective>>

class GradientPsiManager(private val project: Project) {

  /**
   * A `setVertex` call whose row and column could be evaluated, and whose position and color could be evaluated at least to the value used
   * in the initial composition.
   *
   * @param unparsedArguments names of the passed arguments whose value cannot be updated by the editor: they could not be evaluated
   *   (control points are then reported as [Offset.Unspecified]), or they depend on runtime state such as animations.
   * @param callPointer pointer to the `setVertex` call.
   */
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
    val unparsedArguments: Set<String> = emptySet(),
    val callPointer: SmartPsiElementPointer<KtCallExpression>,
  ) {
    /** Converts this vertex to the editor model. */
    fun toMeshGradientPoint(): MeshGradientPoint =
      MeshGradientPoint(
        position = offset,
        color = color,
        leftBezierOffset = leftBezierOffset,
        topBezierOffset = topBezierOffset,
        rightBezierOffset = rightBezierOffset,
        bottomBezierOffset = bottomBezierOffset,
        positionExpression = positionExpression,
        colorExpression = colorExpression,
      )
  }

  /**
   * A parsed `MeshGradientPainter` call.
   *
   * @param rows the number of vertex rows (the `rows` argument plus one).
   * @param cols the number of vertex columns (the `columns` argument plus one).
   * @param vertices the parsed `setVertex` calls, in source order. When several calls set the same vertex, the last one wins.
   * @param isBicubicColorParsed false when `hasBicubicColor` is passed but could not be evaluated, or depends on runtime state.
   * @param hasUnparsedVertices whether the block contains `setVertex` calls that could not be evaluated, e.g. because they are in a loop.
   * @param isBodyRepresentable whether the block only contains parsed `setVertex` calls and local property declarations, so vertices can be
   *   added or removed without affecting code the editor does not understand.
   * @param callPointer pointer to the `MeshGradientPainter` call.
   */
  data class ParsedMesh(
    val rows: Int,
    val cols: Int,
    val vertices: List<ParsedVertex>,
    val hasBicubicColor: Boolean = false,
    val hasDynamicOrUnresolvedValues: Boolean = false,
    val isBicubicColorParsed: Boolean = true,
    val hasUnparsedVertices: Boolean = false,
    val isBodyRepresentable: Boolean = true,
    val callPointer: SmartPsiElementPointer<KtCallExpression>,
  ) {
    /** Returns the vertex that is effective at runtime for the given cell, i.e. the last one set, if any. */
    fun vertexAt(row: Int, col: Int): ParsedVertex? = vertices.lastOrNull { it.row == row && it.col == col }

    /**
     * Returns the [rows] x [cols] grid shown by the editor. Vertices that are not set by a parsed `setVertex` call are shown white and
     * evenly distributed.
     */
    fun toGrid(): List<List<MeshGradientPoint>> =
      List(rows) { r ->
        List(cols) { c ->
          vertexAt(r, c)?.toMeshGradientPoint()
            ?: MeshGradientPoint(position = Offset(c.toFloat() / (cols - 1), r.toFloat() / (rows - 1)), color = Color.White)
        }
      }

    /** Releases the [SmartPsiElementPointer]s held by this instance. */
    fun release(project: Project) {
      val pointerManager = SmartPointerManager.getInstance(project)
      pointerManager.removePointer(callPointer)
      vertices.forEach { pointerManager.removePointer(it.callPointer) }
    }
  }

  /**
   * Analyzes [callExpr] for the gradient editor. For mesh gradients, this also collects the colors declared in scope, which may query the
   * stub indexes, so this should not be called on the EDT.
   */
  @RequiresReadLock
  internal fun analyze(callExpr: KtCallExpression): GradientEditorInput {
    if (callExpr.isValidMeshGradientCall()) {
      val mesh =
        parseMesh(callExpr)
          ?: return GradientEditorInput.Unsupported(message("gradient.editor.error.mesh.unsupported", MAX_MESH_VERTICES - 1))
      return GradientEditorInput.Mesh(mesh, collectAvailableColors(callExpr))
    }
    val function = callExpr.brushGradientFunction() ?: return GradientEditorInput.Unsupported(message("gradient.editor.error.unsupported"))
    val brush = parseBrush(callExpr, function)
    if (brush.colors.elementParsed.none { it }) {
      // Showing placeholder colors that cannot be written back would be misleading.
      brush.release(project)
      return GradientEditorInput.Unsupported(message("gradient.editor.error.brush.colors"))
    }
    return GradientEditorInput.Brush(brush)
  }

  /**
   * Parses a `Brush.linearGradient` [KtCallExpression] into a [Gradient.LinearGradient], or returns null if the call does not represent a
   * valid linear gradient.
   */
  internal fun parseLinearGradient(callExpr: KtCallExpression): Gradient.LinearGradient? =
    parseBrushGradient(callExpr, FUN_LINEAR_GRADIENT) as? Gradient.LinearGradient

  /**
   * Parses a `Brush.horizontalGradient` [KtCallExpression] into an equivalent horizontal [Gradient.LinearGradient] (`y = 0f`), or returns
   * null if the call is not a valid horizontal gradient.
   */
  internal fun parseHorizontalGradient(callExpr: KtCallExpression): Gradient.LinearGradient? =
    parseBrushGradient(callExpr, FUN_HORIZONTAL_GRADIENT) as? Gradient.LinearGradient

  /**
   * Parses a `Brush.verticalGradient` [KtCallExpression] into an equivalent vertical [Gradient.LinearGradient] (`x = 0f`), or returns null
   * if the call is not a valid vertical gradient.
   */
  internal fun parseVerticalGradient(callExpr: KtCallExpression): Gradient.LinearGradient? =
    parseBrushGradient(callExpr, FUN_VERTICAL_GRADIENT) as? Gradient.LinearGradient

  /**
   * Parses a `Brush.radialGradient` [KtCallExpression] into a [Gradient.RadialGradient], or returns null if the call does not represent a
   * valid radial gradient.
   */
  internal fun parseRadialGradient(callExpr: KtCallExpression): Gradient.RadialGradient? =
    parseBrushGradient(callExpr, FUN_RADIAL_GRADIENT) as? Gradient.RadialGradient

  /**
   * Parses a `Brush.sweepGradient` [KtCallExpression] into a [Gradient.SweepGradient], or returns null if the call does not represent a
   * valid sweep gradient.
   */
  internal fun parseSweepGradient(callExpr: KtCallExpression): Gradient.SweepGradient? =
    parseBrushGradient(callExpr, FUN_SWEEP_GRADIENT) as? Gradient.SweepGradient

  /** Returns the values of [callExpr] if it calls the `Brush` factory [function] and at least one of its colors can be evaluated. */
  private fun parseBrushGradient(callExpr: KtCallExpression, function: String): Gradient? {
    if (callExpr.brushGradientFunction() != function) return null
    val parsed = parseBrushValues(callExpr, function)
    return parsed.gradient.takeIf { parsed.colors.elementParsed.any { it } }
  }

  /**
   * Parses a call to the `Brush` factory [function]. Unlike the `parse*Gradient` functions, this succeeds even when no color can be
   * evaluated, in which case the gradient holds no colors and is flagged as dynamic. The result holds smart pointers, which should be
   * released with [ParsedBrush.release] when no longer needed.
   */
  internal fun parseBrush(callExpr: KtCallExpression, function: String): ParsedBrush {
    val parsed = parseBrushValues(callExpr, function)
    val pointerManager = SmartPointerManager.getInstance(project)
    return ParsedBrush(
      callPointer = pointerManager.createSmartPsiElementPointer(callExpr),
      function = function,
      gradient = parsed.gradient,
      colors =
        ParsedColors(
          isStops = parsed.colors.isStops,
          argument = parsed.colors.argument?.let { pointerManager.createSmartPsiElementPointer(it) },
          varargStops = parsed.colors.varargStops.map { pointerManager.createSmartPsiElementPointer(it) },
          isDirect = parsed.colors.isDirect,
          elementParsed = parsed.colors.elementParsed,
        ),
      arguments =
        parsed.arguments.mapValues { (_, argument) ->
          BrushArgument(pointerManager.createSmartPsiElementPointer(argument.first), argument.second)
        },
    )
  }

  private class BrushParseResult(
    val gradient: Gradient,
    val colors: ColorsParseResult,
    val arguments: Map<String, Pair<KtValueArgument, Boolean>>,
  )

  /** Accumulates the state of parsing the arguments of a `Brush` gradient call. */
  private class BrushParseContext(private val callExpr: KtCallExpression, private val importsByFile: ImportsByFile) {
    var hasDynamicOrUnresolved = false
      private set

    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }

    /** Whether the colors are passed as stops, in which case the other arguments can only be passed by name. */
    var hasStops = false

    /**
     * The explicitly passed arguments, mapped to whether their value could be statically evaluated. Values that depend on runtime state
     * (e.g. animations) are not considered evaluated, as the editor cannot update them without removing that dependency.
     */
    val arguments = mutableMapOf<String, Pair<KtValueArgument, Boolean>>()

    /**
     * Parses the optional argument [name], at positional [index] when not named. Omitted arguments take the Compose [default] value.
     * Arguments that are passed but cannot be evaluated also take the [default] value, and flag the gradient as dynamic.
     */
    fun <T : Any> optional(name: String, index: Int, default: T, parse: (KtExpression, EvalContext) -> T?): T {
      val argument = callExpr.findValueArgument(name, if (hasStops) -1 else index) ?: return default
      val evaluation = ValueEvaluation(onDynamic, importsByFile)
      val value = argument.getArgumentExpression()?.let { parse(it, evaluation.ctx) }
      arguments[name] = argument to (value != null && !evaluation.isDynamic)
      return value ?: default.also { onDynamic() }
    }
  }

  private fun parseBrushValues(callExpr: KtCallExpression, function: String): BrushParseResult {
    val importsByFile: ImportsByFile = mutableMapOf()
    val context = BrushParseContext(callExpr, importsByFile)
    val colorsResult = parseColorsOrStops(callExpr, context.onDynamic, importsByFile)
    context.hasStops = colorsResult.isStops
    val colors = colorsResult.colors.orEmpty()
    val stops = colorsResult.stops
    val offsetParser: (KtExpression, EvalContext) -> Offset? = { expr, ctx -> parseOffset(expr, ctx) }
    val floatParser: (KtExpression, EvalContext) -> Float? = { expr, ctx -> parseFloat(expr, ctx) }
    val tileModeParser: (KtExpression, EvalContext) -> TileMode? = { expr, ctx -> parseTileMode(expr, ctx) }

    val gradient =
      when (function) {
        FUN_LINEAR_GRADIENT -> {
          val start = context.optional(ARG_START, 1, Offset.Zero, offsetParser)
          val end = context.optional(ARG_END, 2, Offset.Infinite, offsetParser)
          val tileMode = context.optional(ARG_TILE_MODE, 3, TileMode.Clamp, tileModeParser)
          Gradient.LinearGradient(colors, stops, start, end, tileMode, context.hasDynamicOrUnresolved)
        }
        FUN_HORIZONTAL_GRADIENT -> {
          val startX = context.optional(ARG_START_X, 1, 0f, floatParser)
          val endX = context.optional(ARG_END_X, 2, Float.POSITIVE_INFINITY, floatParser)
          val tileMode = context.optional(ARG_TILE_MODE, 3, TileMode.Clamp, tileModeParser)
          Gradient.LinearGradient(colors, stops, Offset(startX, 0f), Offset(endX, 0f), tileMode, context.hasDynamicOrUnresolved)
        }
        FUN_VERTICAL_GRADIENT -> {
          val startY = context.optional(ARG_START_Y, 1, 0f, floatParser)
          val endY = context.optional(ARG_END_Y, 2, Float.POSITIVE_INFINITY, floatParser)
          val tileMode = context.optional(ARG_TILE_MODE, 3, TileMode.Clamp, tileModeParser)
          Gradient.LinearGradient(colors, stops, Offset(0f, startY), Offset(0f, endY), tileMode, context.hasDynamicOrUnresolved)
        }
        FUN_RADIAL_GRADIENT -> {
          val center = context.optional(ARG_CENTER, 1, Offset.Unspecified, offsetParser)
          val radius = context.optional(ARG_RADIUS, 2, Float.POSITIVE_INFINITY, floatParser)
          val tileMode = context.optional(ARG_TILE_MODE, 3, TileMode.Clamp, tileModeParser)
          Gradient.RadialGradient(colors, stops, center, radius, tileMode, context.hasDynamicOrUnresolved)
        }
        FUN_SWEEP_GRADIENT -> {
          val center = context.optional(ARG_CENTER, 1, Offset.Unspecified, offsetParser)
          Gradient.SweepGradient(colors, stops, center, context.hasDynamicOrUnresolved)
        }
        else -> throw IllegalArgumentException("Unknown Brush gradient function: $function")
      }
    return BrushParseResult(gradient, colorsResult, context.arguments)
  }

  /** Values evaluated from a list of colors or color stops, and whether each element could be evaluated. */
  private class ParsedList<T>(val values: List<T>, val elementParsed: List<Boolean>, val isDirect: Boolean)

  private class ColorsParseResult(
    val colors: List<Color>?,
    val stops: List<Pair<Float, Color>>?,
    val argument: KtValueArgument?,
    val varargStops: List<KtValueArgument>,
    val isDirect: Boolean,
    val elementParsed: List<Boolean>,
  ) {
    val isStops: Boolean
      get() = stops != null
  }

  private fun parseColorsOrStops(callExpr: KtCallExpression, onDynamic: () -> Unit, importsByFile: ImportsByFile): ColorsParseResult {
    val colorsArg = callExpr.findValueArgument(ARG_COLORS, 0)
    val colors = colorsArg?.getArgumentExpression()?.let { parseColorsList(it, onDynamic, importsByFile) }
    if (colors != null) {
      return ColorsParseResult(colors.values, null, colorsArg, emptyList(), colors.isDirect, colors.elementParsed)
    }

    val colorStopsArg = callExpr.findValueArgument(ARG_COLOR_STOPS, 0)
    val colorStops = colorStopsArg?.getArgumentExpression()?.let { parseColorStops(it, onDynamic, importsByFile) }
    if (colorStops != null) {
      return ColorsParseResult(null, colorStops.values, colorStopsArg, emptyList(), colorStops.isDirect, colorStops.elementParsed)
    }

    val positionalArgs = callExpr.valueArguments.filter { it !is KtLambdaArgument && it.getArgumentName() == null }
    val positionalStops = parseStopArguments(positionalArgs, isDirect = true, onDynamic, importsByFile)
    if (positionalStops != null) {
      return ColorsParseResult(null, positionalStops.values, null, positionalArgs, isDirect = true, positionalStops.elementParsed)
    }

    onDynamic()
    return ColorsParseResult(null, null, colorsArg ?: colorStopsArg, emptyList(), isDirect = false, elementParsed = emptyList())
  }

  private fun parseColorsList(expr: KtExpression, onDynamic: () -> Unit, importsByFile: ImportsByFile): ParsedList<Color>? {
    val list = ValueEvaluation(onDynamic, importsByFile)
    val resolved = resolveInFrame(expr, list.ctx)
    val call = getCallExpression(resolved) ?: return null
    val callee = getQualifiedCalleeText(resolved) ?: return null
    if (!isCollectionListFunction(resolved, callee, list.ctx)) return null
    if (call.valueArguments.isEmpty()) return null
    val elements =
      call.valueArguments.map { arg ->
        val element = ValueEvaluation(onDynamic, importsByFile)
        val color = arg.getArgumentExpression()?.let { parseColor(it, element.ctx) }
        color to (color != null && !element.isDynamic && !list.isDynamic)
      }
    if (elements.all { it.first == null }) return null
    return toParsedList(elements.map { it.first }, resolved == expr, Color.White, onDynamic, elements.map { it.second })
  }

  private fun parseColorStops(expr: KtExpression, onDynamic: () -> Unit, importsByFile: ImportsByFile): ParsedList<Pair<Float, Color>>? {
    val list = ValueEvaluation(onDynamic, importsByFile)
    val resolved = resolveInFrame(expr, list.ctx)
    val call = getCallExpression(resolved) ?: return null
    val callee = call.calleeExpression?.text ?: return null
    if (callee != FUN_ARRAY_OF && !isCollectionListFunction(resolved, callee, list.ctx)) return null
    val stops = parseStopArguments(call.valueArguments, resolved == expr, onDynamic, importsByFile) ?: return null
    return if (list.isDynamic) ParsedList(stops.values, stops.elementParsed.map { false }, stops.isDirect) else stops
  }

  private fun parseStopArguments(
    args: List<KtValueArgument>,
    isDirect: Boolean,
    onDynamic: () -> Unit,
    importsByFile: ImportsByFile,
  ): ParsedList<Pair<Float, Color>>? {
    if (args.isEmpty()) return null
    val count = args.size
    val parsed = args.mapIndexed { index, arg ->
      val defaultFraction = if (count > 1) index.toFloat() / (count - 1) else 0f
      arg.getArgumentExpression()?.let { parseColorStop(it, ValueEvaluation(onDynamic, importsByFile), defaultFraction) }
    }
    if (parsed.all { it == null }) return null
    val values = parsed.mapIndexed { index, stop -> stop?.first ?: Pair(if (count > 1) index.toFloat() / (count - 1) else 0f, Color.White) }
    return toParsedList(values, isDirect, Pair(1f, Color.White), onDynamic, parsed.map { it?.second == true })
  }

  /**
   * Builds a [ParsedList] from [parsed] elements, replacing those that could not be evaluated (null) with placeholders. Lists with a single
   * element are padded with [padding], as Compose requires at least two colors.
   */
  private fun <T : Any> toParsedList(
    parsed: List<T?>,
    isDirect: Boolean,
    padding: T,
    onDynamic: () -> Unit,
    elementParsed: List<Boolean> = parsed.map { it != null },
  ): ParsedList<T> {
    if (elementParsed.any { !it }) onDynamic()
    val values = parsed.map { it ?: padding }
    if (values.size == 1) {
      onDynamic()
      return ParsedList(listOf(values[0], padding), elementParsed + false, isDirect)
    }
    return ParsedList(values, elementParsed, isDirect)
  }

  /**
   * Parses a color stop (`fraction to color` or `Pair(fraction, color)`) as part of [evaluation]. Returns null if neither side can be
   * evaluated. Otherwise, the returned flag tells whether both sides could be statically evaluated, without depending on runtime state; the
   * side that could not be evaluated takes [defaultFraction] or white.
   */
  private fun parseColorStop(expr: KtExpression, evaluation: ValueEvaluation, defaultFraction: Float): Pair<Pair<Float, Color>, Boolean>? {
    val ctx = evaluation.ctx
    val resolved = resolveInFrame(expr, ctx)
    val (leftExpr, rightExpr) =
      when {
        resolved is KtBinaryExpression && resolved.operationReference.text == OP_TO -> resolved.left to resolved.right
        resolved is KtCallExpression && resolved.calleeExpression?.text == FUN_PAIR && resolved.valueArguments.size == 2 ->
          resolved.valueArguments[0].getArgumentExpression() to resolved.valueArguments[1].getArgumentExpression()
        else -> return null
      }
    val fraction = leftExpr?.let { parseFloat(it, ctx)?.coerceIn(0f, 1f) }
    val color = rightExpr?.let { parseColor(it, ctx) }
    if (fraction == null && color == null) return null
    val complete = fraction != null && color != null && !evaluation.isDynamic
    if (!complete) ctx.markDynamic()
    return Pair(Pair(fraction ?: defaultFraction, color ?: Color.White), complete)
  }

  private fun parseTileMode(expr: KtExpression, ctx: EvalContext): TileMode? = ctx.withFrame {
    parseResolvedTileMode(resolveExpression(expr, ctx), ctx)
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
   * Parses the [KtCallExpression] of MeshGradientPainter to extract rows, cols, hasBicubicColor, and vertices. Returns null when the mesh
   * size cannot be statically evaluated or is not supported by the editor, or when there is no block lambda. The result holds smart
   * pointers, which should be released with [ParsedMesh.release] when no longer needed.
   */
  fun parseMesh(callExpr: KtCallExpression): ParsedMesh? {
    var hasDynamicOrUnresolved = false
    val onDynamic: () -> Unit = { hasDynamicOrUnresolved = true }
    val importsByFile: ImportsByFile = mutableMapOf()

    val rowsExpr = findArgumentExpression(callExpr, ARG_ROWS, 0) ?: return null
    val colsExpr = findArgumentExpression(callExpr, ARG_COLUMNS, 1) ?: return null

    val rows = parseStaticInt(rowsExpr, importsByFile) ?: return null
    val cols = parseStaticInt(colsExpr, importsByFile) ?: return null

    val actualRows = rows + 1
    val actualCols = cols + 1
    val supportedSizes = MIN_MESH_VERTICES..MAX_MESH_VERTICES
    if (actualRows !in supportedSizes || actualCols !in supportedSizes) return null

    val bicubicExpr = findArgumentExpression(callExpr, ARG_HAS_BICUBIC_COLOR, 2)
    val bicubic = ValueEvaluation(onDynamic, importsByFile)
    val parsedBicubicColor = bicubicExpr?.let { resolveInFrame(it, bicubic.ctx).text.toBooleanStrictOrNull() }
    if (bicubicExpr != null && parsedBicubicColor == null) onDynamic()

    val body = callExpr.meshLambdaBody() ?: return null
    val vertices = parseVertices(body, onDynamic, importsByFile)
    val coveredVertices =
      vertices.parsed.map { it.row to it.col }.filter { (r, c) -> r in 0 until actualRows && c in 0 until actualCols }.toSet()
    if (coveredVertices.size < actualRows * actualCols) {
      hasDynamicOrUnresolved = true
    }

    return ParsedMesh(
      rows = actualRows,
      cols = actualCols,
      vertices = vertices.parsed,
      hasBicubicColor = parsedBicubicColor ?: false,
      hasDynamicOrUnresolvedValues = hasDynamicOrUnresolved,
      isBicubicColorParsed = bicubicExpr == null || (parsedBicubicColor != null && !bicubic.isDynamic),
      hasUnparsedVertices = vertices.hasUnparsedCalls,
      isBodyRepresentable = vertices.isBodyRepresentable,
      callPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(callExpr),
    )
  }

  private fun findArgumentExpression(callExpr: KtCallExpression, name: String, index: Int): KtExpression? =
    callExpr.findValueArgument(name, index)?.getArgumentExpression()

  private class ParsedVertices(val parsed: List<ParsedVertex>, val hasUnparsedCalls: Boolean, val isBodyRepresentable: Boolean)

  private fun parseVertices(body: KtBlockExpression, onDynamic: () -> Unit, importsByFile: ImportsByFile): ParsedVertices {
    val vertices = mutableListOf<ParsedVertex>()
    val parsedCalls = mutableSetOf<KtCallExpression>()
    var hasUnparsedCalls = false
    val setVertexCalls =
      SyntaxTraverser.psiTraverser(body).filter(KtCallExpression::class.java).filter { it.calleeExpression?.text == FUN_SET_VERTEX }

    for (call in setVertexCalls) {
      ProgressManager.checkCanceled()
      val vertex = parseVertex(call, onDynamic, importsByFile)
      if (vertex == null) {
        onDynamic()
        hasUnparsedCalls = true
        continue
      }
      if (call.parent != body) {
        // Calls nested in other statements (e.g. conditionals) might not run.
        onDynamic()
      }
      parsedCalls.add(call)
      vertices.add(vertex)
    }

    val isBodyRepresentable =
      body.statements.all { statement ->
        when (statement) {
          is KtCallExpression -> statement in parsedCalls
          is KtProperty ->
            PsiTreeUtil.findChildrenOfType(statement, KtCallExpression::class.java).none { it.calleeExpression?.text == FUN_SET_VERTEX }
          else -> false
        }
      }
    return ParsedVertices(vertices, hasUnparsedCalls, isBodyRepresentable)
  }

  /**
   * Parses a `setVertex` [call]. Returns null when its row or column cannot be statically evaluated, or when its position or color cannot
   * be evaluated at all.
   */
  private fun parseVertex(call: KtCallExpression, onDynamic: () -> Unit, importsByFile: ImportsByFile): ParsedVertex? {
    if (call.valueArguments.size < 4) return null

    val row = findArgumentExpression(call, ARG_ROW, 0)?.let { parseStaticInt(it, importsByFile) } ?: return null
    val col = findArgumentExpression(call, ARG_COLUMN, 1)?.let { parseStaticInt(it, importsByFile) } ?: return null

    val unparsedArguments = mutableSetOf<String>()
    /** Evaluates the argument [name] with [parse], recording it as unparsed when it cannot be statically evaluated. */
    fun <T : Any> evaluate(name: String, expr: KtExpression, parse: (KtExpression, EvalContext) -> T?): T? {
      val evaluation = ValueEvaluation(onDynamic, importsByFile)
      val value = parse(expr, evaluation.ctx)
      if (value == null || evaluation.isDynamic) unparsedArguments.add(name)
      return value
    }

    val offsetExpr = findArgumentExpression(call, ARG_POSITION, INDEX_POSITION) ?: return null
    val offset = evaluate(ARG_POSITION, offsetExpr) { expr, ctx -> parseSpecifiedOffset(expr, ctx) } ?: return null

    val colorExpr = findArgumentExpression(call, ARG_COLOR, INDEX_COLOR) ?: return null
    val color = evaluate(ARG_COLOR, colorExpr) { expr, ctx -> parseColor(expr, ctx) } ?: return null

    val controlPoints = CONTROL_POINT_ARGS.mapIndexed { i, name ->
      val expr = findArgumentExpression(call, name, INDEX_FIRST_CONTROL_POINT + i) ?: return@mapIndexed Offset.Unspecified
      evaluate(name, expr) { e, ctx -> parseOffset(e, ctx) } ?: Offset.Unspecified.also { onDynamic() }
    }

    val posExprText = offsetExpr.text.takeUnless { isDirectOffsetLiteral(offsetExpr) }
    val colExprText = colorExpr.text.takeUnless { isDirectHexColorLiteral(colorExpr) }

    return ParsedVertex(
      row = row,
      col = col,
      offset = offset,
      color = color,
      leftBezierOffset = controlPoints[0],
      topBezierOffset = controlPoints[1],
      rightBezierOffset = controlPoints[2],
      bottomBezierOffset = controlPoints[3],
      positionExpression = posExprText,
      colorExpression = colExprText,
      unparsedArguments = unparsedArguments,
      callPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(call),
    )
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

  /** Evaluates [expr] as an `Int`, returning null if it can't be statically evaluated, e.g. because it depends on runtime state. */
  private fun parseStaticInt(expr: KtExpression, importsByFile: ImportsByFile): Int? {
    var isDynamic = false
    val value = parseInt(expr, EvalContext(onDynamic = { isDynamic = true }, importsByFile))
    return value.takeUnless { isDynamic }
  }

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

  /** Resolves [expr] in a new evaluation step of [ctx]. */
  private fun resolveInFrame(expr: KtExpression, ctx: EvalContext): KtExpression = ctx.withFrame { resolveExpression(expr, ctx) } ?: expr

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
   * The static evaluation of a single value. Notifications that it depends on dynamic or unresolvable values are recorded in [isDynamic]
   * and forwarded to [parentOnDynamic].
   */
  private class ValueEvaluation(private val parentOnDynamic: () -> Unit, importsByFile: ImportsByFile) {
    var isDynamic = false
      private set

    val ctx =
      EvalContext(
        onDynamic = {
          isDynamic = true
          parentOnDynamic()
        },
        importsByFile,
      )
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
