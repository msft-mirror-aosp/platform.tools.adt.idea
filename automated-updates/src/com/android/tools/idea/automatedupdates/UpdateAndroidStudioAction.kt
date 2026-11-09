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

package com.android.tools.idea.automatedupdates

import com.android.tools.idea.flags.StudioFlags
import com.intellij.ide.setToolTipText
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.PillButton
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.annotations.VisibleForTesting

class UpdateAndroidStudioAction internal constructor(private val showUpdateDialog: (Project?) -> Unit) :
  DumbAwareAction(), CustomComponentAction {

  constructor() : this({ project -> UpdateDialog(project).show() })

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = StudioFlags.AUTOMATED_UPDATES_FLOW.get()
  }

  override fun actionPerformed(e: AnActionEvent) {
    showUpdateDialog(e.project)
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return PillButtonWrapper { component ->
      val dataContext = ActionToolbar.getDataContextFor(component)
      val event =
        AnActionEvent.createEvent(
          this@UpdateAndroidStudioAction,
          dataContext,
          presentation,
          place,
          ActionUiKind.TOOLBAR,
          null,
        )
      ActionUtil.performAction(this@UpdateAndroidStudioAction, event)
    }
      .apply { updateFromPresentation(presentation) }
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    if (component is PillButtonWrapper) {
      component.updateFromPresentation(presentation)
    }
  }
}

@Suppress("UnstableApiUsage")
internal class PillButtonWrapper(private val onClick: (JComponent) -> Unit) : JPanel(GridBagLayout()) {
  @VisibleForTesting internal val button = PillButton()

  init {
    isOpaque = false
    button.addActionListener { performClick() }
    button.setColorState(PillButton.BLUE)

    add(button, GridBagConstraints().apply { insets = JBUI.insets(0, 10) })
  }

  private fun performClick() {
    if (button.isEnabled) {
      onClick(button)
    }
  }

  internal fun updateFromPresentation(presentation: Presentation) {
    isVisible = presentation.isVisible
    isEnabled = presentation.isEnabled
    button.isVisible = presentation.isVisible
    button.isEnabled = presentation.isEnabled
    button.text = presentation.getText(false)
    button.setToolTipText(presentation.description?.let(HtmlChunk::text))
  }
}
