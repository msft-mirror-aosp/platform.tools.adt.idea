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

import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.emulator.EMULATOR_TOOL_WINDOW_PANEL_KEY
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent

/** Shows the TV remote panel for Android TV emulators if it was closed. */
internal class EmulatorTvRemoteAction : AbstractEmulatorAction(configFilter = { it.deviceType == DeviceType.TV }) {

  override fun update(event: AnActionEvent) {
    if (!StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.get()) {
      event.presentation.isEnabledAndVisible = false
      return
    }
    super.update(event)
    if (event.presentation.isVisible) {
      val toolWindowPanel = event.getData(EMULATOR_TOOL_WINDOW_PANEL_KEY)
      if (toolWindowPanel == null || toolWindowPanel.isBottomPanelVisible || toolWindowPanel.isLayoutInspectorActive) {
        event.presentation.isEnabledAndVisible = false
      }
    }
  }

  override fun actionPerformed(event: AnActionEvent) {
    event.getData(EMULATOR_TOOL_WINDOW_PANEL_KEY)?.showBottomPanel()
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
