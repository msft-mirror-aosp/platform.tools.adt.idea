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
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.compose.preview.message
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

private val logger = Logger.getInstance(GradientEditorDialog::class.java)

class GradientEditorDialog(private val project: Project, private val file: KtFile, painterCall: KtCallExpression) :
  DialogWrapper(project, true) {

  private val psiManager = GradientPsiManager(project)
  internal val state = GradientEditorState()
  private val painterCallPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(painterCall)

  init {
    title = message("gradient.editor.title")

    runReadActionBlocking {
      val contextColors = psiManager.collectAvailableColors(painterCall)
      state.addAvailableColors(contextColors)

      // Populate editor state by parsing the call in order of specificity:
      // 1. Mesh gradient painter
      // 2. Linear / Horizontal / Vertical brush gradients
      // 3. Radial brush gradient
      // 4. Sweep brush gradient
      // 5. Dynamic fallback for recognized Brush gradient calls whose arguments cannot be statically parsed
      val populated =
        tryPopulateMesh(painterCall) ||
          tryPopulateLinear(painterCall) ||
          tryPopulateRadial(painterCall) ||
          tryPopulateSweep(painterCall) ||
          tryPopulateDynamicBrushFallback(painterCall)

      if (!populated) {
        logger.warn("Unrecognized gradient call: ${painterCall.text}")
      }
    }

    init()
  }

  private fun tryPopulateMesh(call: KtCallExpression): Boolean {
    val parsedMesh = psiManager.parseMesh(call) ?: return false
    state.currentType = GradientType.MESH
    val grid =
      List(parsedMesh.rows) { r ->
        List(parsedMesh.cols) { c ->
          val vertex = parsedMesh.vertices.firstOrNull { it.row == r && it.col == c }
          val offset = vertex?.offset ?: Offset(c.toFloat() / (parsedMesh.cols - 1), r.toFloat() / (parsedMesh.rows - 1))
          val color = vertex?.color ?: Color.White
          MeshGradientPoint(
            position = offset,
            color = color,
            leftBezierOffset = vertex?.leftBezierOffset ?: Offset.Unspecified,
            topBezierOffset = vertex?.topBezierOffset ?: Offset.Unspecified,
            rightBezierOffset = vertex?.rightBezierOffset ?: Offset.Unspecified,
            bottomBezierOffset = vertex?.bottomBezierOffset ?: Offset.Unspecified,
            positionExpression = vertex?.positionExpression,
            colorExpression = vertex?.colorExpression,
          )
        }
      }
    state.hasDynamicOrUnresolvedValues = parsedMesh.hasDynamicOrUnresolvedValues
    state.loadMesh(parsedMesh.rows, parsedMesh.cols, grid, parsedMesh.hasBicubicColor)
    return true
  }

  private fun tryPopulateLinear(call: KtCallExpression): Boolean {
    val parsedLinear =
      psiManager.parseLinearGradient(call)
        ?: psiManager.parseHorizontalGradient(call)
        ?: psiManager.parseVerticalGradient(call)
        ?: return false

    state.currentType = GradientType.LINEAR
    populateBrushColorsAndStops(parsedLinear.colors, parsedLinear.colorStops)
    state.start = parsedLinear.start
    state.end = parsedLinear.end
    state.tileMode = parsedLinear.tileMode
    state.hasDynamicOrUnresolvedValues = parsedLinear.hasDynamicOrUnresolvedValues
    return true
  }

  private fun tryPopulateRadial(call: KtCallExpression): Boolean {
    val parsedRadial = psiManager.parseRadialGradient(call) ?: return false
    state.currentType = GradientType.RADIAL
    populateBrushColorsAndStops(parsedRadial.colors, parsedRadial.colorStops)
    state.center = parsedRadial.center
    state.radius = parsedRadial.radius
    state.tileMode = parsedRadial.tileMode
    state.hasDynamicOrUnresolvedValues = parsedRadial.hasDynamicOrUnresolvedValues
    return true
  }

  private fun tryPopulateSweep(call: KtCallExpression): Boolean {
    val parsedSweep = psiManager.parseSweepGradient(call) ?: return false
    state.currentType = GradientType.SWEEP
    populateBrushColorsAndStops(parsedSweep.colors, parsedSweep.colorStops)
    state.center = parsedSweep.center
    state.hasDynamicOrUnresolvedValues = parsedSweep.hasDynamicOrUnresolvedValues
    return true
  }

  /**
   * Fallback for recognized Brush gradient calls whose arguments could not be statically parsed (e.g. dynamic variables or function
   * arguments). Initializes reasonable defaults and flags that dynamic values are present.
   */
  private fun tryPopulateDynamicBrushFallback(call: KtCallExpression): Boolean {
    return when {
      call.isValidBrushGradientCall(FUN_LINEAR_GRADIENT) ||
        call.isValidBrushGradientCall(FUN_HORIZONTAL_GRADIENT) ||
        call.isValidBrushGradientCall(FUN_VERTICAL_GRADIENT) -> {
        state.currentType = GradientType.LINEAR
        state.hasDynamicOrUnresolvedValues = true
        true
      }
      call.isValidBrushGradientCall(FUN_RADIAL_GRADIENT) -> {
        state.currentType = GradientType.RADIAL
        state.hasDynamicOrUnresolvedValues = true
        true
      }
      call.isValidBrushGradientCall(FUN_SWEEP_GRADIENT) -> {
        state.currentType = GradientType.SWEEP
        state.hasDynamicOrUnresolvedValues = true
        true
      }
      else -> false
    }
  }

  private fun populateBrushColorsAndStops(colors: List<Color>, colorStops: List<Pair<Float, Color>>?) {
    val rawColors = colorStops?.map { it.second }?.takeIf { it.isNotEmpty() } ?: colors
    val initialColors =
      when {
        rawColors.size >= 2 -> rawColors
        rawColors.size == 1 -> listOf(rawColors[0], Color.White)
        else -> listOf(Color.Red, Color.Blue)
      }
    state.colors.clear()
    state.colors.addAll(initialColors)
    state.addAvailableColors(initialColors)
    state.colorStops.clear()
    colorStops?.let { stops ->
      val safeStops =
        when {
          stops.size >= 2 -> stops
          stops.size == 1 -> listOf(stops[0], Pair(1f, Color.White))
          else -> emptyList()
        }
      state.colorStops.addAll(safeStops)
    }
  }

  override fun createCenterPanel(): JComponent {
    val mainPanel = JPanel(BorderLayout())

    if (state.currentType != GradientType.MESH) {
      val brushTypes = arrayOf(GradientType.LINEAR, GradientType.RADIAL, GradientType.SWEEP)
      val typeSelector = JComboBox(brushTypes)
      typeSelector.selectedItem = state.currentType
      typeSelector.addActionListener { state.currentType = typeSelector.selectedItem as GradientType }
      mainPanel.add(typeSelector, BorderLayout.NORTH)
    }

    val panel = StudioComposePanel {
      when (state.currentType) {
        GradientType.MESH -> MeshGradientEditorScreen(project, state, isEditingExisting = true)
        GradientType.LINEAR,
        GradientType.RADIAL,
        GradientType.SWEEP -> StandardGradientEditorScreen(project, state, isEditingExisting = true)
      }
    }
    panel.preferredSize = Dimension(460, 530)
    mainPanel.add(panel, BorderLayout.CENTER)
    return mainPanel
  }

  public override fun doOKAction() {
    val call = painterCallPointer.element?.takeIf { it.isValid } ?: return super.doOKAction()

    WriteCommandAction.runWriteCommandAction(
      project,
      message("gradient.editor.update.command.name"),
      null,
      {
        val success =
          when (state.currentType) {
            GradientType.MESH -> {
              val successArgs = psiManager.updateConstructorArguments(call, state.rows, state.cols, state.hasBicubicColor)
              val successBody = psiManager.regenerateLambdaBody(call, state.meshPoints)
              successArgs && successBody
            }
            GradientType.LINEAR,
            GradientType.RADIAL,
            GradientType.SWEEP -> {
              val gradient =
                when (state.currentType) {
                  GradientType.LINEAR ->
                    Gradient.LinearGradient(
                      state.colors,
                      if (state.colorStops.isEmpty()) null else state.colorStops,
                      state.start,
                      state.end,
                      state.tileMode,
                    )
                  GradientType.RADIAL ->
                    Gradient.RadialGradient(
                      state.colors,
                      if (state.colorStops.isEmpty()) null else state.colorStops,
                      state.center,
                      state.radius,
                      state.tileMode,
                    )
                  GradientType.SWEEP ->
                    Gradient.SweepGradient(state.colors, if (state.colorStops.isEmpty()) null else state.colorStops, state.center)
                  GradientType.MESH -> throw IllegalStateException()
                }
              psiManager.updateGradient(call, gradient)
            }
          }
        if (!success) {
          logger.warn("Failed to update gradient for type ${state.currentType}")
          return@runWriteCommandAction
        }
        CodeStyleManager.getInstance(project).reformat(if (call.isValid) call else file)
      },
      file,
    )

    super.doOKAction()
  }
}
