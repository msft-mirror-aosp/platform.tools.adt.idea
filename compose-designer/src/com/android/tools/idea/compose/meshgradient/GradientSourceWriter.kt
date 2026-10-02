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
import com.android.tools.idea.compose.preview.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

private const val COMPOSE_FQN_PREFIX = "androidx.compose."
private const val FUN_LIST_OF = "listOf"

/**
 * Writes the values edited in the [GradientEditorDialog] back to the source.
 *
 * Edits are minimal: only the arguments whose value changed are replaced, everything else (including expressions the editor could not
 * evaluate, comments and formatting) is kept as written. All edits, including the elements to insert, are created before the PSI is
 * modified, so a change that cannot be applied safely leaves the source untouched and returns [GradientWriteResult.Failed].
 *
 * New expressions reference the Compose classes by the name they are imported with (including import aliases). When a class is not
 * imported, its fully qualified name is emitted and then shortened with [ShortenReferencesFacility], which adds the import. Code copied
 * from the original source is never shortened.
 */
internal class GradientSourceWriter(private val project: Project) {
  private val psiFactory = KtPsiFactory(project)

  /**
   * A planned PSI modification. The elements to insert are created, from their [Source], while planning, so that applying the edits only
   * inserts, replaces and deletes elements.
   */
  private sealed interface Edit {
    /** Replaces [target] with [replacement], created from [source]. */
    class Replace(val target: KtExpression, val replacement: KtExpression, val source: Source) : Edit

    /** Appends [argument], created from [source], to [argumentList]. */
    class AddArgument(val argumentList: KtValueArgumentList, val argument: KtValueArgument, val source: Source) : Edit

    /** Deletes the [statement], including the line break that precedes it and a comment that follows it on the same line. */
    class DeleteStatement(val statement: KtExpression) : Edit

    /** Adds [statement], created from [source], at the end of [body]. */
    class AppendStatement(val body: KtBlockExpression, val statement: KtExpression, val source: Source) : Edit
  }

  private fun replaceEdit(target: KtExpression, source: Source) = Edit.Replace(target, psiFactory.createExpression(source.text), source)

  private fun addArgumentEdit(argumentList: KtValueArgumentList, source: Source) =
    Edit.AddArgument(argumentList, psiFactory.createArgument(source.text), source)

  private fun appendStatementEdit(body: KtBlockExpression, source: Source) =
    Edit.AppendStatement(body, psiFactory.createExpression(source.text), source)

  /**
   * Updates the `MeshGradientPainter` call of [mesh] so that it represents [target]. [initial] is the state that was loaded from [mesh] in
   * the editor; vertices that are equal in [initial] and [target] are not touched.
   */
  @RequiresWriteLock
  fun updateMesh(mesh: GradientPsiManager.ParsedMesh, initial: MeshValues, target: MeshValues): GradientWriteResult {
    if (initial == target) return GradientWriteResult.Unchanged
    val call = mesh.callPointer.element ?: return stale()
    val edits = mutableListOf<Edit>()
    planMesh(call, mesh, initial, target, edits)?.let {
      return it
    }
    return apply(call.containingKtFile, edits)
  }

  /**
   * Updates the `Brush` gradient call of [brush] so that it represents [target]. [initial] is the state that was loaded from [brush] in the
   * editor; values that are equal in [initial] and [target] are not touched.
   */
  @RequiresWriteLock
  fun updateBrush(brush: ParsedBrush, initial: BrushValues, target: BrushValues): GradientWriteResult {
    if (initial == target) return GradientWriteResult.Unchanged
    val call = brush.callPointer.element ?: return stale()
    val edits = mutableListOf<Edit>()
    planBrush(call, brush, initial, target, edits)?.let {
      return it
    }
    return apply(call.containingKtFile, edits)
  }

  private fun planMesh(
    call: KtCallExpression,
    mesh: GradientPsiManager.ParsedMesh,
    initial: MeshValues,
    target: MeshValues,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    val body = call.meshLambdaBody() ?: return stale()
    val names = ComposeNames(call.containingKtFile)

    val targetRows = target.points.size
    val targetCols = target.points.firstOrNull()?.size ?: 0
    val supportedSizes = MIN_MESH_VERTICES..MAX_MESH_VERTICES
    if (targetRows !in supportedSizes || targetCols !in supportedSizes || target.points.any { it.size != targetCols }) {
      return GradientWriteResult.Failed(message("gradient.editor.error.mesh.size", MIN_MESH_VERTICES, MAX_MESH_VERTICES))
    }
    val initialRows = initial.points.size
    val initialCols = initial.points.firstOrNull()?.size ?: 0
    val sizeChanged = targetRows != initialRows || targetCols != initialCols
    val isStructureEditable = !mesh.hasUnparsedVertices && mesh.isBodyRepresentable
    if (sizeChanged && !isStructureEditable) return GradientWriteResult.Failed(message("gradient.editor.error.mesh.structure"))

    val vertexCalls = mutableMapOf<Pair<Int, Int>, MutableList<Pair<GradientPsiManager.ParsedVertex, KtCallExpression>>>()
    for (vertex in mesh.vertices) {
      val vertexCall = vertex.callPointer.element ?: return stale()
      vertexCalls.getOrPut(vertex.row to vertex.col) { mutableListOf() }.add(vertex to vertexCall)
    }

    val structuralEdits = mutableListOf<Edit>()
    for (r in 0 until targetRows) {
      for (c in 0 until targetCols) {
        val point = target.points[r][c]
        val initialPoint = initial.points.getOrNull(r)?.getOrNull(c)
        if (point == initialPoint) continue
        // As at runtime, the last call that sets a vertex wins.
        val owner = vertexCalls[r to c]?.last()
        if (owner == null) {
          if (mesh.hasUnparsedVertices) return GradientWriteResult.Failed(message("gradient.editor.error.mesh.vertex.unresolved", r, c))
          structuralEdits.add(appendStatementEdit(body, setVertexSource(r, c, point, names)))
          continue
        }
        val (vertex, vertexCall) = owner
        // Calls nested in other statements, such as conditionals, might not run or might be one of several calls setting the vertex.
        if (vertexCall.parent != body) return GradientWriteResult.Failed(message("gradient.editor.error.mesh.vertex.unresolved", r, c))
        planVertex(vertex, vertexCall, initialPoint ?: vertex.toMeshGradientPoint(), point, names, edits)?.let {
          return it
        }
      }
    }
    for (r in 0 until initialRows) {
      for (c in 0 until initialCols) {
        if (r < targetRows && c < targetCols) continue
        vertexCalls[r to c]?.forEach { (_, vertexCall) -> structuralEdits.add(Edit.DeleteStatement(vertexCall)) }
      }
    }

    if (structuralEdits.isNotEmpty() && !isStructureEditable) {
      return GradientWriteResult.Failed(message("gradient.editor.error.mesh.structure"))
    }
    // The size arguments are only replaced when they change, so that expressions such as `rows = N - 1` are kept.
    if (targetRows != initialRows) {
      val rowsExpr = call.findValueArgument(ARG_ROWS, 0)?.getArgumentExpression() ?: return stale()
      edits.add(replaceEdit(rowsExpr, Source.generated((targetRows - 1).toString())))
    }
    if (targetCols != initialCols) {
      val colsExpr = call.findValueArgument(ARG_COLUMNS, 1)?.getArgumentExpression() ?: return stale()
      edits.add(replaceEdit(colsExpr, Source.generated((targetCols - 1).toString())))
    }
    if (target.hasBicubicColor != initial.hasBicubicColor) {
      if (!mesh.isBicubicColorParsed) return unresolved(ARG_HAS_BICUBIC_COLOR)
      val bicubicExpr = call.findValueArgument(ARG_HAS_BICUBIC_COLOR, 2)?.getArgumentExpression()
      val value = Source.generated(target.hasBicubicColor.toString())
      if (bicubicExpr != null) {
        edits.add(replaceEdit(bicubicExpr, value))
      } else {
        val argumentList = call.valueArgumentList ?: return stale()
        edits.add(addArgumentEdit(argumentList, Source("$ARG_HAS_BICUBIC_COLOR = ") + value))
      }
    }
    edits.addAll(structuralEdits)
    return null
  }

  private fun planVertex(
    vertex: GradientPsiManager.ParsedVertex,
    call: KtCallExpression,
    initial: MeshGradientPoint,
    target: MeshGradientPoint,
    names: ComposeNames,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    if (target.position != initial.position || target.positionExpression != initial.positionExpression) {
      if (ARG_POSITION in vertex.unparsedArguments) return unresolved(ARG_POSITION)
      val positionExpr = call.findValueArgument(ARG_POSITION, INDEX_POSITION)?.getArgumentExpression() ?: return stale()
      edits.add(replaceEdit(positionExpr, positionSource(target, names)))
    }
    if (target.color != initial.color || target.colorExpression != initial.colorExpression) {
      if (ARG_COLOR in vertex.unparsedArguments) return unresolved(ARG_COLOR)
      val colorExpr = call.findValueArgument(ARG_COLOR, INDEX_COLOR)?.getArgumentExpression() ?: return stale()
      edits.add(replaceEdit(colorExpr, colorSource(target, names)))
    }
    val initialControlPoints = initial.controlPoints()
    val targetControlPoints = target.controlPoints()
    CONTROL_POINT_ARGS.forEachIndexed { i, name ->
      val controlPoint = targetControlPoints[i]
      if (controlPoint == initialControlPoints[i]) return@forEachIndexed
      if (name in vertex.unparsedArguments) return unresolved(name)
      val source = Source.generated(generateOffsetSource(controlPoint, names.offset))
      val controlPointExpr = call.findValueArgument(name, INDEX_FIRST_CONTROL_POINT + i)?.getArgumentExpression()
      when {
        controlPointExpr != null -> edits.add(replaceEdit(controlPointExpr, source))
        controlPoint != Offset.Unspecified ->
          edits.add(addArgumentEdit(call.valueArgumentList ?: return stale(), Source("$name = ") + source))
      }
    }
    return null
  }

  /** The source of a new `setVertex` call for the vertex at row [r] and column [c]. */
  private fun setVertexSource(r: Int, c: Int, point: MeshGradientPoint, names: ComposeNames): Source {
    val arguments = buildList {
      add(Source(r.toString()))
      add(Source(c.toString()))
      add(positionSource(point, names))
      add(colorSource(point, names))
      CONTROL_POINT_ARGS.zip(point.controlPoints()).forEach { (name, controlPoint) ->
        if (controlPoint != Offset.Unspecified) add(Source("$name = ") + Source.generated(generateOffsetSource(controlPoint, names.offset)))
      }
    }
    return Source("$FUN_SET_VERTEX(") + arguments.join(", ") + ")"
  }

  /** The source of the position of [point], which is its original expression if it has one. */
  private fun positionSource(point: MeshGradientPoint, names: ComposeNames): Source =
    point.positionExpression?.let { Source(it) } ?: Source.generated(generateOffsetSource(point.position, names.offset))

  /** The source of the color of [point], which is its original expression if it has one. */
  private fun colorSource(point: MeshGradientPoint, names: ComposeNames): Source =
    point.colorExpression?.let { Source(it) } ?: Source.generated(generateColorSource(point.color, names.color))

  private fun MeshGradientPoint.controlPoints() = listOf(leftBezierOffset, topBezierOffset, rightBezierOffset, bottomBezierOffset)

  private fun planBrush(
    call: KtCallExpression,
    brush: ParsedBrush,
    initial: BrushValues,
    target: BrushValues,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    val names = ComposeNames(call.containingKtFile)
    val targetFunction = targetBrushFunction(brush.function, initial, target)
    val colorsChanged = initial.colors != target.colors || initial.colorStops != target.colorStops
    val targetUsesStops = target.colorStops.isNotEmpty()
    val needsRebuild =
      targetFunction != brush.function ||
        (colorsChanged &&
          (targetUsesStops != brush.colors.isStops ||
            (brush.colors.varargStops.isNotEmpty() && target.colorStops.size != brush.colors.varargStops.size)))
    return if (needsRebuild) {
      planBrushRebuild(call, brush, initial, target, targetFunction, colorsChanged, names, edits)
    } else {
      planBrushInPlace(call, brush, initial, target, colorsChanged, names, edits)
    }
  }

  /** Returns the `Brush` factory function that should be called to represent [target]. */
  private fun targetBrushFunction(function: String, initial: BrushValues, target: BrushValues): String =
    when (target.type) {
      GradientType.LINEAR ->
        when {
          initial.type != GradientType.LINEAR -> FUN_LINEAR_GRADIENT
          function == FUN_HORIZONTAL_GRADIENT && target.start.y == initial.start.y && target.end.y == initial.end.y ->
            FUN_HORIZONTAL_GRADIENT
          function == FUN_VERTICAL_GRADIENT && target.start.x == initial.start.x && target.end.x == initial.end.x -> FUN_VERTICAL_GRADIENT
          else -> FUN_LINEAR_GRADIENT
        }
      GradientType.RADIAL -> FUN_RADIAL_GRADIENT
      GradientType.SWEEP -> FUN_SWEEP_GRADIENT
      GradientType.MESH -> error("A Brush gradient cannot be converted to a mesh")
    }

  /** Replaces the changed arguments of [call], keeping its callee and the other arguments as written. */
  private fun planBrushInPlace(
    call: KtCallExpression,
    brush: ParsedBrush,
    initial: BrushValues,
    target: BrushValues,
    colorsChanged: Boolean,
    names: ComposeNames,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    if (colorsChanged) {
      planColorsInPlace(brush.colors, initial, target, names, edits)?.let {
        return it
      }
    }
    val initialParams = brushParameters(brush.function, initial)
    for ((name, value) in brushParameters(brush.function, target)) {
      if (initialParams[name] == value) continue
      val argument = brush.arguments[name]
      if (argument == null) {
        edits.add(addArgumentEdit(call.valueArgumentList ?: return stale(), Source("$name = ") + valueSource(value, names)))
        continue
      }
      if (!argument.isParsed) return unresolved(name)
      val expr = argument.pointer.element?.getArgumentExpression() ?: return stale()
      edits.add(replaceEdit(expr, valueSource(value, names)))
    }
    return null
  }

  private fun planColorsInPlace(
    colors: ParsedColors,
    initial: BrushValues,
    target: BrushValues,
    names: ComposeNames,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    val colorsName = if (colors.isStops) ARG_COLOR_STOPS else ARG_COLORS
    if (colors.elementParsed.isEmpty()) return unresolved(colorsName)
    val initialElements: List<Any> = if (colors.isStops) initial.colorStops else initial.colors
    val targetElements: List<Any> = if (colors.isStops) target.colorStops else target.colors

    val sourceElements = if (colors.isDirect) colors.sourceElements() ?: return stale() else emptyList()
    if (colors.isDirect && initialElements.size == targetElements.size) {
      val changed = targetElements.indices.filter { targetElements[it] != initialElements[it] }
      // Elements past the source ones are placeholders padding a single-element list, which are written by regenerating the list.
      if (changed.all { it < sourceElements.size }) {
        for (i in changed) {
          if (colors.elementParsed.getOrNull(i) != true) return unresolved(colorsName)
          val element = sourceElements[i]
          if (colors.isStops) {
            @Suppress("UNCHECKED_CAST")
            planStop(element, initialElements[i] as Pair<Float, Color>, targetElements[i] as Pair<Float, Color>, names, edits)
          } else {
            edits.add(replaceEdit(element, Source.generated(generateColorSource(targetElements[i] as Color, names.color))))
          }
        }
        return null
      }
    }

    if (!colors.canRegenerate(sourceElements)) return unresolved(colorsName)
    val argumentExpr = colors.argument?.element?.getArgumentExpression() ?: return stale()
    val kept = mutableListOf<PsiElement>()
    if (colors.isDirect) {
      // Keep the list function, its type arguments and the layout of its elements.
      val listCall = colors.listCall() ?: return stale()
      val argumentList = listCall.valueArgumentList ?: return stale()
      val elements = elementsSource(targetElements, initialElements, sourceElements, names, kept)
      if (!areCommentsKept(argumentList, kept)) return commentsLost()
      val callee = Source(listCall.text.substring(0, argumentList.startOffsetInParent))
      edits.add(replaceEdit(listCall, callee + ArgumentLayout.of(argumentList).format(elements)))
    } else {
      if (!areCommentsKept(argumentExpr, kept)) return commentsLost()
      val elements = elementsSource(targetElements, initialElements, emptyList(), names, kept)
      val listFunction = if (colors.isStops) FUN_ARRAY_OF else FUN_LIST_OF
      edits.add(replaceEdit(argumentExpr, Source(listFunction) + ArgumentLayout.DEFAULT.format(elements)))
    }
    return null
  }

  /** Updates the color stop [element], replacing only the fraction or the color when it is written as `fraction to color`. */
  private fun planStop(
    element: KtExpression,
    initial: Pair<Float, Color>,
    target: Pair<Float, Color>,
    names: ComposeNames,
    edits: MutableList<Edit>,
  ) {
    val (fractionExpr, colorExpr) =
      when {
        element is KtBinaryExpression && element.operationReference.text == OP_TO -> element.left to element.right
        element is KtCallExpression && element.calleeExpression?.text == FUN_PAIR && element.valueArguments.size == 2 ->
          element.valueArguments[0].getArgumentExpression() to element.valueArguments[1].getArgumentExpression()
        else -> null to null
      }
    if (fractionExpr == null || colorExpr == null) {
      edits.add(replaceEdit(element, stopSource(target, names)))
      return
    }
    if (target.first != initial.first) edits.add(replaceEdit(fractionExpr, Source.generated(generateFloatSource(target.first))))
    if (target.second != initial.second) {
      edits.add(replaceEdit(colorExpr, Source.generated(generateColorSource(target.second, names.color))))
    }
  }

  /**
   * Regenerates the argument list of [call], for changes that cannot be applied argument by argument: switching to another factory
   * function, or changing the number of vararg color stops or the form of the colors. Arguments whose value did not change keep their
   * original expression; arguments that were never passed are only added when the user changed their value.
   */
  private fun planBrushRebuild(
    call: KtCallExpression,
    brush: ParsedBrush,
    initial: BrushValues,
    target: BrushValues,
    targetFunction: String,
    colorsChanged: Boolean,
    names: ComposeNames,
    edits: MutableList<Edit>,
  ): GradientWriteResult.Failed? {
    val arguments = mutableListOf<Source>()
    // Elements of the original call whose text is copied to the new call.
    val kept = mutableListOf<PsiElement>()
    val colors = brush.colors
    if (!colorsChanged) {
      val colorArgs = colors.argument?.let { listOf(it) } ?: colors.varargStops
      for (pointer in colorArgs) {
        val argument = pointer.element ?: return stale()
        arguments.add(Source(argument.text))
        kept.add(argument)
      }
    } else {
      val reusable = if (colors.isDirect) colors.sourceElements() ?: return stale() else emptyList()
      if (!colors.canRegenerate(reusable)) return unresolved(if (colors.isStops) ARG_COLOR_STOPS else ARG_COLORS)
      val initialElements: List<Any> = if (colors.isStops) initial.colorStops else initial.colors
      if (target.colorStops.isNotEmpty()) {
        // colorStops is the leading vararg parameter of every Brush factory, so the stops are passed positionally.
        val initialStops = initialElements.takeIf { colors.isStops }.orEmpty()
        arguments.addAll(elementsSource(target.colorStops, initialStops, reusable, names, kept))
      } else {
        val initialColors = initialElements.takeIf { !colors.isStops }.orEmpty()
        val elements = elementsSource(target.colors, initialColors, reusable, names, kept)
        val layout =
          colors.listCall()?.takeUnless { colors.isStops }?.valueArgumentList?.let { ArgumentLayout.of(it) } ?: ArgumentLayout.DEFAULT
        arguments.add(Source("$ARG_COLORS = $FUN_LIST_OF") + layout.format(elements))
      }
    }

    val isConversionToLinear =
      brush.function != FUN_LINEAR_GRADIENT && targetFunction == FUN_LINEAR_GRADIENT && initial.type == GradientType.LINEAR
    if (isConversionToLinear) {
      // horizontalGradient and verticalGradient are written as linearGradient(start, end) with the same values.
      brush.arguments.filterKeys { it != ARG_TILE_MODE }.forEach { (name, argument) -> if (!argument.isParsed) return unresolved(name) }
    }
    val originalParams = brushParameters(brush.function, initial)
    val initialParams = brushParameters(targetFunction, initial)
    for ((name, value) in brushParameters(targetFunction, target)) {
      val argument = brush.arguments[name]
      when {
        isConversionToLinear && (name == ARG_START || name == ARG_END) -> {
          val isStart = name == ARG_START
          val source =
            convertedOffsetSource(
              brush,
              if (isStart) target.start else target.end,
              if (isStart) initial.start else initial.end,
              isStart,
              names,
              kept,
            ) ?: return stale()
          arguments.add(Source("$name = ") + source)
        }
        argument != null && originalParams[name] == value -> {
          val expr = argument.pointer.element?.getArgumentExpression() ?: return stale()
          arguments.add(Source("$name = ${expr.text}"))
          kept.add(expr)
        }
        argument != null && !argument.isParsed -> return unresolved(name)
        argument != null || initialParams[name] != value -> arguments.add(Source("$name = ") + valueSource(value, names))
      }
    }

    val callee =
      when {
        targetFunction == brush.function -> {
          val calleeExpr = call.calleeExpression ?: return stale()
          kept.add(calleeExpr)
          Source(calleeExpr.text)
        }
        (call.parent as? KtQualifiedExpression)?.selectorExpression == call -> Source(targetFunction)
        else -> Source.generated(names.brush) + ".$targetFunction"
      }
    if (!areCommentsKept(call, kept)) return commentsLost()
    val layout = call.valueArgumentList?.let { ArgumentLayout.of(it) } ?: ArgumentLayout.DEFAULT
    edits.add(replaceEdit(call, callee + layout.format(arguments)))
    return null
  }

  /**
   * The source of the `start` (if [isStart]) or `end` [value] of a `linearGradient` call converted from the `horizontalGradient` or
   * `verticalGradient` call of [brush]. The coordinate that was passed to the original call keeps its expression when unchanged from
   * [initialValue], in which case the expression is added to [kept]. Returns null if the original argument cannot be found.
   */
  private fun convertedOffsetSource(
    brush: ParsedBrush,
    value: Offset,
    initialValue: Offset,
    isStart: Boolean,
    names: ComposeNames,
    kept: MutableList<PsiElement>,
  ): Source? {
    val isHorizontal = brush.function == FUN_HORIZONTAL_GRADIENT
    val axisName =
      when {
        isHorizontal -> if (isStart) ARG_START_X else ARG_END_X
        else -> if (isStart) ARG_START_Y else ARG_END_Y
      }
    val axisValue = if (isHorizontal) value.x else value.y
    val isAxisUnchanged = axisValue == if (isHorizontal) initialValue.x else initialValue.y
    val axisExpr = brush.arguments[axisName]?.let { it.pointer.element?.getArgumentExpression() ?: return null }
    val axisSource =
      if (axisExpr != null && isAxisUnchanged) {
        kept.add(axisExpr)
        Source(axisExpr.text)
      } else {
        Source.generated(generateFloatSource(axisValue))
      }
    val otherSource = Source.generated(generateFloatSource(if (isHorizontal) value.y else value.x))
    val (x, y) = if (isHorizontal) axisSource to otherSource else otherSource to axisSource
    return Source.generated(names.offset) + "(" + x + ", " + y + ")"
  }

  /** The values of the parameters of the `Brush` factory [function], other than the colors, for [values]. */
  private fun brushParameters(function: String, values: BrushValues): Map<String, Any> =
    when (function) {
      FUN_LINEAR_GRADIENT -> mapOf(ARG_START to values.start, ARG_END to values.end, ARG_TILE_MODE to values.tileMode)
      FUN_HORIZONTAL_GRADIENT -> mapOf(ARG_START_X to values.start.x, ARG_END_X to values.end.x, ARG_TILE_MODE to values.tileMode)
      FUN_VERTICAL_GRADIENT -> mapOf(ARG_START_Y to values.start.y, ARG_END_Y to values.end.y, ARG_TILE_MODE to values.tileMode)
      FUN_RADIAL_GRADIENT -> mapOf(ARG_CENTER to values.center, ARG_RADIUS to values.radius, ARG_TILE_MODE to values.tileMode)
      FUN_SWEEP_GRADIENT -> mapOf(ARG_CENTER to values.center)
      else -> emptyMap()
    }

  private fun valueSource(value: Any, names: ComposeNames): Source =
    Source.generated(
      when (value) {
        is Offset -> generateOffsetSource(value, names.offset)
        is Float -> generateFloatSource(value)
        is TileMode -> "${names.tileMode}.${tileModeName(value)}"
        else -> throw IllegalArgumentException("Unsupported value: $value")
      }
    )

  private fun tileModeName(tileMode: TileMode): String =
    when (tileMode) {
      TileMode.Clamp -> "Clamp"
      TileMode.Repeated -> "Repeated"
      TileMode.Mirror -> "Mirror"
      TileMode.Decal -> "Decal"
      else -> throw IllegalArgumentException("Unsupported tile mode: $tileMode")
    }

  private fun stopSource(stop: Pair<Float, Color>, names: ComposeNames): Source =
    Source.generated(generateFloatSource(stop.first)) + " $OP_TO " + Source.generated(generateColorSource(stop.second, names.color))

  /**
   * Generates the source of each of the [targetElements] (colors or color stops). Elements equal to one of [initialElements] reuse the text
   * of the corresponding [reusable] source element, if available, which is then added to [kept].
   */
  private fun elementsSource(
    targetElements: List<Any>,
    initialElements: List<Any>,
    reusable: List<KtExpression>,
    names: ComposeNames,
    kept: MutableList<PsiElement>,
  ): List<Source> {
    val used = BooleanArray(initialElements.size)
    return targetElements.map { element ->
      val index = initialElements.indices.firstOrNull { !used[it] && initialElements[it] == element && it < reusable.size }
      if (index != null) {
        used[index] = true
        kept.add(reusable[index])
        Source(reusable[index].text)
      } else {
        @Suppress("UNCHECKED_CAST")
        when (element) {
          is Color -> Source.generated(generateColorSource(element, names.color))
          else -> stopSource(element as Pair<Float, Color>, names)
        }
      }
    }
  }

  /** Returns the element expressions of a direct colors list or of the vararg color stops, or null if they cannot be found. */
  private fun ParsedColors.sourceElements(): List<KtExpression>? {
    if (varargStops.isNotEmpty()) {
      return varargStops.map { it.element?.getArgumentExpression() ?: return null }
    }
    return listCall()?.valueArguments?.map { it.getArgumentExpression() ?: return null }
  }

  /**
   * Whether the colors can be regenerated, i.e. all their elements could be evaluated. For colors written at the call site, a placeholder
   * that pads a single-element list (Compose requires two colors) is not one of the [sourceElements] and does not prevent it.
   */
  private fun ParsedColors.canRegenerate(sourceElements: List<KtExpression>): Boolean =
    if (isDirect) sourceElements.isNotEmpty() && sourceElements.indices.all { elementParsed.getOrNull(it) == true } else isFullyParsed

  /** Returns the `listOf`/`arrayOf` call written as the colors argument, if any. */
  private fun ParsedColors.listCall(): KtCallExpression? =
    when (val expr = argument?.element?.getArgumentExpression()) {
      is KtCallExpression -> expr
      is KtDotQualifiedExpression -> expr.selectorExpression as? KtCallExpression
      else -> null
    }

  /** Whether all the comments in [element] are inside one of the [kept] elements, whose text is copied to the regenerated code. */
  private fun areCommentsKept(element: PsiElement, kept: Collection<PsiElement>): Boolean =
    PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java).all { comment ->
      kept.any { PsiTreeUtil.isAncestor(it, comment, false) }
    }

  private fun apply(file: KtFile, edits: List<Edit>): GradientWriteResult {
    if (edits.isEmpty()) return GradientWriteResult.Unchanged
    val qualifiedReferences = mutableListOf<GeneratedRange>()
    val statements = mutableListOf<PsiElement>()

    fun track(element: PsiElement, source: Source) {
      for (range in source.generatedRanges) {
        if (range.substring(source.text).contains(COMPOSE_FQN_PREFIX)) qualifiedReferences.add(GeneratedRange(element, range))
      }
    }

    // The generated code is laid out like the code it replaces, so the formatter must not rewrap it. Only the whitespace around new
    // statements and imports is adjusted below.
    disablePostprocessFormattingInside {
      for (edit in edits) {
        when (edit) {
          is Edit.Replace -> track(edit.target.replace(edit.replacement), edit.source)
          is Edit.AddArgument -> track(addArgument(edit.argumentList, edit.argument), edit.source)
          is Edit.DeleteStatement -> deleteStatement(edit.statement)
          is Edit.AppendStatement -> {
            val anchor = edit.body.statements.lastOrNull()
            val newLine = edit.body.addAfter(psiFactory.createNewLine(), anchor)
            val statement = edit.body.addAfter(edit.statement, newLine)
            track(statement, edit.source)
            statements.add(statement)
          }
        }
      }
    }

    val codeStyleManager = CodeStyleManager.getInstance(project)
    shortenReferences(file, qualifiedReferences, codeStyleManager)
    for (statement in statements) {
      if (statement.isValid) codeStyleManager.reformatNewlyAddedElement(statement.parent.node, statement.node)
    }
    return GradientWriteResult.Written
  }

  /** Appends [newArgument] to [argumentList], separated from the previous argument like the last argument is, if any. */
  private fun addArgument(argumentList: KtValueArgumentList, newArgument: KtValueArgument): PsiElement {
    val lastArgument = argumentList.arguments.lastOrNull() ?: return argumentList.addAfter(newArgument, argumentList.leftParenthesis)
    val separator = psiFactory.createWhiteSpace((lastArgument.prevSibling as? PsiWhiteSpace)?.text ?: " ")
    val trailingComma = PsiTreeUtil.skipWhitespacesAndCommentsForward(lastArgument)?.takeIf { it.node.elementType == KtTokens.COMMA }
    if (trailingComma != null) {
      val argument = argumentList.addAfter(newArgument, trailingComma)
      argumentList.addBefore(separator, argument)
      argumentList.addAfter(psiFactory.createComma(), argument)
      return argument
    }
    val comma = argumentList.addAfter(psiFactory.createComma(), lastArgument)
    val argument = argumentList.addAfter(newArgument, comma)
    argumentList.addBefore(separator, argument)
    return argument
  }

  private fun deleteStatement(statement: KtExpression) {
    val next = statement.nextSibling
    val trailingComment = (if (next is PsiWhiteSpace && !next.textContains('\n')) next.nextSibling else next) as? PsiComment
    val last = trailingComment ?: statement
    val previousWhiteSpace = statement.prevSibling as? PsiWhiteSpace
    if (previousWhiteSpace != null) {
      statement.parent.deleteChildRange(previousWhiteSpace, last)
    } else {
      // The whitespace after the `{` of a lambda belongs to the function literal, not to its block, so the first statement of the block
      // takes the line break that follows it instead.
      statement.parent.deleteChildRange(statement, last.nextSibling as? PsiWhiteSpace ?: last)
    }
  }

  /**
   * Shortens the fully qualified references in the generated [ranges] of [file], adding the needed imports. Only the new imports are
   * formatted.
   */
  private fun shortenReferences(file: KtFile, ranges: List<GeneratedRange>, codeStyleManager: CodeStyleManager) {
    if (ranges.isEmpty()) return
    val importsBefore = file.importDirectives.toSet()
    disablePostprocessFormattingInside {
      // Shortening a reference only moves the code that follows it, and the imports are above all the ranges, so processing the ranges
      // from the last one keeps the offsets of the remaining ranges valid relative to their element.
      for (generated in ranges.sortedByDescending { it.fileRange().startOffset }) {
        if (generated.element.isValid) ShortenReferencesFacility.getInstance().shorten(file, generated.fileRange())
      }
    }
    val importList = file.importList
    if (importList != null && importsBefore.isEmpty() && importList.imports.isNotEmpty()) {
      codeStyleManager.reformatNewlyAddedElement(file.node, importList.node)
    }
    for (directive in file.importDirectives) {
      if (directive !in importsBefore) codeStyleManager.reformatNewlyAddedElement(directive.parent.node, directive.node)
    }
  }

  private fun <T> disablePostprocessFormattingInside(action: () -> T): T =
    PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside(Computable { action() })

  private fun stale() = GradientWriteResult.Failed(message("gradient.editor.error.stale"))

  private fun unresolved(name: String) = GradientWriteResult.Failed(message("gradient.editor.error.argument.unresolved", name))

  private fun commentsLost() = GradientWriteResult.Failed(message("gradient.editor.error.comments"))
}

/** A [range] of code generated by the editor, relative to the start of the inserted [element] that contains it. */
private class GeneratedRange(val element: PsiElement, val range: TextRange) {
  /** The range in the file. */
  fun fileRange(): TextRange = range.shiftRight(element.textRange.startOffset)
}

/**
 * Code to insert in the source. [generatedRanges] are the ranges of [text] generated by the editor; the rest is either punctuation or code
 * copied from the original source, which is inserted as written.
 */
private class Source(val text: String, val generatedRanges: List<TextRange> = emptyList()) {
  operator fun plus(other: Source) = Source(text + other.text, generatedRanges + other.generatedRanges.map { it.shiftRight(text.length) })

  operator fun plus(other: String) = Source(text + other, generatedRanges)

  companion object {
    /** Code fully generated by the editor. */
    fun generated(text: String) = Source(text, listOf(TextRange(0, text.length)))
  }
}

private fun List<Source>.join(separator: String): Source =
  foldIndexed(Source("")) { i, joined, source -> if (i == 0) source else joined + separator + source }

/** The whitespace layout of an argument list, used to generate a new argument list formatted like the original one. */
private class ArgumentLayout(
  private val open: String,
  private val separator: String,
  private val close: String,
  private val hasTrailingComma: Boolean,
) {
  /** Formats [arguments] as a parenthesized argument list. */
  fun format(arguments: List<Source>): Source =
    Source("($open") + arguments.join(",$separator") + (if (hasTrailingComma && arguments.isNotEmpty()) "," else "") + "$close)"

  companion object {
    val DEFAULT = ArgumentLayout(open = "", separator = " ", close = "", hasTrailingComma = false)

    fun of(argumentList: KtValueArgumentList): ArgumentLayout {
      val arguments = argumentList.arguments
      val open = (argumentList.leftParenthesis?.nextSibling as? PsiWhiteSpace)?.text.orEmpty()
      val close = (argumentList.rightParenthesis?.prevSibling as? PsiWhiteSpace)?.text.orEmpty()
      val separator =
        arguments.drop(1).firstNotNullOfOrNull { (it.prevSibling as? PsiWhiteSpace)?.text } ?: open.takeIf { it.contains('\n') } ?: " "
      val hasTrailingComma =
        arguments.lastOrNull()?.let { PsiTreeUtil.skipWhitespacesAndCommentsForward(it) }?.node?.elementType == KtTokens.COMMA
      return ArgumentLayout(open, separator, close, hasTrailingComma)
    }
  }
}

/**
 * Names used to reference the Compose classes in a file: the imported name (or its alias) when the class is imported, or its fully
 * qualified name otherwise.
 */
private class ComposeNames(file: KtFile) {
  val offset = file.referenceNameOf(FQN_OFFSET)
  val color = file.referenceNameOf(FQN_COLOR)
  val tileMode = file.referenceNameOf(FQN_TILE_MODE)
  val brush = file.referenceNameOf(FQN_BRUSH)
}

private fun KtFile.referenceNameOf(fqn: String): String {
  val shortName = fqn.substringAfterLast('.')
  val packageName = fqn.substringBeforeLast('.')
  val explicitImports = importDirectives.filter { !it.isAllUnder }
  explicitImports
    .firstOrNull { it.importedFqName?.asString() == fqn }
    ?.let {
      return it.aliasName ?: shortName
    }
  val isShadowed = explicitImports.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == shortName }
  val isImplicitlyVisible =
    packageFqName.asString() == packageName || importDirectives.any { it.isAllUnder && it.importedFqName?.asString() == packageName }
  return if (isImplicitlyVisible && !isShadowed) shortName else fqn
}
