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
package com.android.tools.idea.streaming.emulator.actions

import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware

/** Action group containing extended controls pages when [StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO] is enabled. */
class EmulatorExtendedControlsActionGroup : DefaultActionGroup(), DumbAware {

  init {
    templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
  }

  override fun update(event: AnActionEvent) {
    if (!StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.get()) {
      event.presentation.isEnabledAndVisible = false
      return
    }
    val presentation = event.presentation
    presentation.isVisible = isEmulator(event)
    presentation.isEnabled = presentation.isVisible && isEmulatorConnected(event)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
