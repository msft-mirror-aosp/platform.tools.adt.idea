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

private const val FUN_MESH_PAINTER = "MeshGradientPainter"
private const val FUN_SET_VERTEX = "setVertex"
private const val FUN_OFFSET = "Offset"
private const val FUN_COLOR = "Color"

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
    if (index < args.size) {
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
