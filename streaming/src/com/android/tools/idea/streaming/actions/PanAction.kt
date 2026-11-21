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
package com.android.tools.idea.streaming.actions

import com.android.tools.adtui.PANNABLE_KEY
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import icons.StudioIcons.LayoutEditor.Toolbar.PAN_TOOL
import icons.StudioIcons.LayoutEditor.Toolbar.PAN_TOOL_SELECTED

internal class PanAction : ToggleAction(), DumbAware {

  override fun update(event: AnActionEvent) {
    super.update(event)
    val presentation = event.presentation
    val pannable = event.getData(PANNABLE_KEY)
    presentation.isEnabledAndVisible = pannable?.isPannable == true
    presentation.icon = if (isSelected(event)) PAN_TOOL_SELECTED else PAN_TOOL
  }

  override fun isSelected(event: AnActionEvent): Boolean = event.getData(PANNABLE_KEY)?.isPanning == true

  override fun setSelected(event: AnActionEvent, state: Boolean) {
    event.getData(PANNABLE_KEY)?.isPanning = state
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  companion object {
    const val ACTION_ID = "android.streaming.pan"
  }
}
