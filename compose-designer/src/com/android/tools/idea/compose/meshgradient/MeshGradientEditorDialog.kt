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
import com.android.tools.idea.compose.meshgradient.MeshGradientPsiManager.ParsedMesh
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.awt.Dimension
import javax.swing.JComponent
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

private val logger = Logger.getInstance(MeshGradientEditorDialog::class.java)

class MeshGradientEditorDialog(private val project: Project, private val file: KtFile, painterCall: KtCallExpression) :
  DialogWrapper(project, true) {

  private val psiManager = MeshGradientPsiManager(project)
  internal val state = MeshGeneratorState()
  private var parsedMesh: ParsedMesh? = null
  private val painterCallPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(painterCall)

  init {
    title = "Mesh Gradient Editor"

    var contextColors = emptyList<Color>()
    runReadActionBlocking {
      parsedMesh = psiManager.parseMesh(painterCall)
      contextColors = psiManager.collectAvailableColors(painterCall)
    }
    state.addAvailableColors(contextColors)

    parsedMesh?.let { parsed ->
      val grid =
        List(parsed.rows) { r ->
          List(parsed.cols) { c ->
            val vertex = parsed.vertices.firstOrNull { it.row == r && it.col == c }
            val offset = vertex?.offset ?: Offset(c.toFloat() / (parsed.cols - 1), r.toFloat() / (parsed.rows - 1))
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
      state.hasDynamicOrUnresolvedValues = parsed.hasDynamicOrUnresolvedValues
      state.loadMesh(parsed.rows, parsed.cols, grid, parsed.hasBicubicColor)
    }

    init()
  }

  override fun createCenterPanel(): JComponent {
    val panel = StudioComposePanel { MeshGradientEditorScreen(project, state, isEditingExisting = true) }
    panel.preferredSize = Dimension(460, 530)
    return panel
  }

  public override fun doOKAction() {
    val call = painterCallPointer.element?.takeIf { it.isValid } ?: return super.doOKAction()

    WriteCommandAction.runWriteCommandAction(
      project,
      "Update Mesh Gradient",
      null,
      {
        val successArgs = psiManager.updateConstructorArguments(call, state.rows, state.cols, state.hasBicubicColor)
        val successBody = psiManager.regenerateLambdaBody(call, state.meshPoints)
        if (!successArgs || !successBody) {
          logger.warn("Failed to update mesh structure! Args success: $successArgs, Body success: $successBody")
          return@runWriteCommandAction
        }
        CodeStyleManager.getInstance(project).reformat(call)
      },
      file,
    )

    super.doOKAction()
  }
}
