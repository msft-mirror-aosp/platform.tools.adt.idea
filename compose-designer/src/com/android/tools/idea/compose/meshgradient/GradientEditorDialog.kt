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

import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.compose.preview.message
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.kotlin.psi.KtCallExpression

private val LOG = logger<GradientEditorDialog>()

private const val DIMENSION_SERVICE_KEY = "#com.android.tools.idea.compose.meshgradient.GradientEditorDialog"

/**
 * Dialog to edit the gradient described by [input] and write the changes back to the source.
 *
 * Use [GradientEditorDialog.analyzeAndShow] to analyze a call and open the dialog.
 */
internal class GradientEditorDialog(private val project: Project, private val input: GradientEditorInput.Editable) :
  DialogWrapper(project, true) {

  internal val state = GradientEditorState()

  /** The values loaded in [state], used to detect and write only the changes made by the user. */
  private val initialMesh: MeshValues?
  private val initialBrush: BrushValues?

  init {
    title = message("gradient.editor.title")
    when (input) {
      is GradientEditorInput.Mesh -> loadMesh(input)
      is GradientEditorInput.Brush -> loadBrush(input)
    }
    initialMesh = if (input is GradientEditorInput.Mesh) state.meshValues() else null
    initialBrush = if (input is GradientEditorInput.Brush) state.brushValues() else null
    init()
  }

  private fun loadMesh(input: GradientEditorInput.Mesh) {
    val mesh = input.mesh
    state.currentType = GradientType.MESH
    state.addAvailableColors(input.availableColors)
    state.hasDynamicOrUnresolvedValues = mesh.hasDynamicOrUnresolvedValues
    state.loadMesh(mesh.toGrid(), mesh.hasBicubicColor)
  }

  private fun loadBrush(input: GradientEditorInput.Brush) {
    val gradient = input.brush.gradient
    state.addAvailableColors(input.availableColors)
    when (gradient) {
      is Gradient.LinearGradient -> {
        state.currentType = GradientType.LINEAR
        state.loadColorStops(gradient.colors, gradient.colorStops)
        state.start = gradient.start
        state.end = gradient.end
        state.tileMode = gradient.tileMode
      }
      is Gradient.RadialGradient -> {
        state.currentType = GradientType.RADIAL
        state.loadColorStops(gradient.colors, gradient.colorStops)
        state.center = gradient.center
        state.radius = gradient.radius
        state.tileMode = gradient.tileMode
      }
      is Gradient.SweepGradient -> {
        state.currentType = GradientType.SWEEP
        state.loadColorStops(gradient.colors, gradient.colorStops)
        state.center = gradient.center
      }
    }
    state.hasDynamicOrUnresolvedValues = gradient.hasDynamicOrUnresolvedValues
    state.fitPreviewSizeToBrush()
  }

  override fun createCenterPanel(): JComponent {
    val mainPanel = JPanel(BorderLayout())

    if (input is GradientEditorInput.Brush) {
      val typeSelector = ComboBox(arrayOf(GradientType.LINEAR, GradientType.RADIAL, GradientType.SWEEP))
      typeSelector.renderer = SimpleListCellRenderer.create("") { it.displayName }
      typeSelector.selectedItem = state.currentType
      typeSelector.addActionListener { state.currentType = typeSelector.item }
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
    panel.preferredSize = JBUI.size(460, 530)
    mainPanel.add(panel, BorderLayout.CENTER)
    return mainPanel
  }

  override fun getDimensionServiceKey(): String = DIMENSION_SERVICE_KEY

  override fun doOKAction() {
    val result = applyChanges()
    if (result is GradientWriteResult.Failed) {
      setErrorText(result.message)
      return
    }
    super.doOKAction()
  }

  /** Triggers the OK action, as if the user pressed the OK button. */
  @VisibleForTesting internal fun performOkAction() = doOKAction()

  /**
   * Writes the changes made in the editor to the source, in a single undoable command. Nothing is written when the values are the ones
   * initially loaded, or when the change cannot be applied safely.
   */
  @VisibleForTesting
  internal fun applyChanges(): GradientWriteResult {
    val writer = GradientSourceWriter(project)
    return when (input) {
      is GradientEditorInput.Mesh -> {
        val initial = checkNotNull(initialMesh)
        val target = state.meshValues()
        if (target == initial) GradientWriteResult.Unchanged
        else runWriteCommand(input.mesh.callPointer.containingFile) { writer.updateMesh(input.mesh, initial, target) }
      }
      is GradientEditorInput.Brush -> {
        val initial = checkNotNull(initialBrush)
        val target = state.brushValues()
        if (target == initial) GradientWriteResult.Unchanged
        else runWriteCommand(input.brush.callPointer.containingFile) { writer.updateBrush(input.brush, initial, target) }
      }
    }
  }

  /**
   * Runs [write] in an undoable command modifying [file]. Returns a failure without running [write] if [file] no longer exists or cannot be
   * made writable.
   */
  private fun runWriteCommand(file: PsiFile?, write: () -> GradientWriteResult): GradientWriteResult {
    if (file == null) return GradientWriteResult.Failed(message("gradient.editor.error.stale"))
    return WriteCommandAction.writeCommandAction(project, file)
      .withName(message("gradient.editor.update.command.name"))
      .compute(
        ThrowableComputable<GradientWriteResult, RuntimeException> {
          val documentManager = PsiDocumentManager.getInstance(project)
          documentManager.getDocument(file)?.let { documentManager.commitDocument(it) }
          write()
        }
      ) ?: GradientWriteResult.Failed(message("gradient.editor.error.readonly"))
  }

  override fun dispose() {
    input.releasePointers(project)
    super.dispose()
  }

  private fun GradientEditorState.meshValues() = MeshValues(meshPoints.toList(), hasBicubicColor)

  private fun GradientEditorState.brushValues() =
    BrushValues(currentType, colors.toList(), colorStops.toList(), start, end, center, radius, tileMode)

  companion object {
    /**
     * Analyzes the gradient [call] in a cancellable background read action, under a modal progress, and opens the editor. If the call
     * cannot be edited, an error message explains why.
     */
    @RequiresEdt
    fun analyzeAndShow(project: Project, call: KtCallExpression) {
      val calleeName = call.calleeExpression?.text
      val callPointer = SmartPointerManager.createPointer(call)
      val input =
        try {
          PsiDocumentManager.getInstance(project).commitAllDocuments()
          runWithModalProgressBlocking(project, message("gradient.editor.progress.title")) {
            smartReadAction(project) { callPointer.element?.let { GradientPsiManager(project).analyze(it) } }
          }
        } catch (e: CancellationException) {
          // The user cancelled the analysis.
          return
        } finally {
          SmartPointerManager.getInstance(project).removePointer(callPointer)
        }
      when (input) {
        null -> return
        is GradientEditorInput.Unsupported -> {
          LOG.debug { "The gradient editor cannot edit the call to $calleeName" }
          Messages.showErrorDialog(project, input.reason, message("gradient.editor.title"))
        }
        is GradientEditorInput.Editable -> GradientEditorDialog(project, input).show()
      }
    }
  }
}
