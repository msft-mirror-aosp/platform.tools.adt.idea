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
import com.android.tools.idea.flags.StudioFlags
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Opens the gradient editor playground, which edits a new gradient that is not backed by source code. */
internal class GradientEditorAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val dialog = GradientEditorPlaygroundDialog(project)
    dialog.show()
  }

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.get()
  }
}

internal class GradientEditorPlaygroundDialog(private val project: Project) : DialogWrapper(project, true) {
  @VisibleForTesting internal val state = GradientEditorState()

  @VisibleForTesting
  internal val typeSelector =
    ComboBox(GradientType.entries.toTypedArray()).apply {
      renderer = SimpleListCellRenderer.create<GradientType>("") { it.displayName }
      item = state.currentType
      addActionListener { item?.let { state.currentType = it } }
    }

  init {
    title = message("gradient.editor.title")
    init()
  }

  override fun createCenterPanel(): JComponent {
    val mainPanel = JPanel(BorderLayout())
    mainPanel.add(typeSelector, BorderLayout.NORTH)

    val panel = StudioComposePanel {
      when (state.currentType) {
        GradientType.MESH -> MeshGradientEditorScreen(project, state, isEditingExisting = false)
        GradientType.LINEAR,
        GradientType.RADIAL,
        GradientType.SWEEP -> StandardGradientEditorScreen(project, state, isEditingExisting = false)
      }
    }
    panel.preferredSize = JBUI.size(520, 650)
    mainPanel.add(panel, BorderLayout.CENTER)
    return mainPanel
  }
}
