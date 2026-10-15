/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.emulator.actions.EmulatorBatteryAction
import com.android.tools.idea.streaming.emulator.actions.EmulatorCellularAction
import com.android.tools.idea.streaming.emulator.actions.EmulatorDisplaysAction
import com.android.tools.idea.streaming.emulator.actions.EmulatorExtendedControlsActionGroup
import com.android.tools.idea.streaming.emulator.actions.EmulatorShowExtendedControlsAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionStubBase
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.keymap.KeymapExtension
import com.intellij.openapi.keymap.KeymapGroup
import com.intellij.openapi.keymap.KeymapGroupFactory
import com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition

/** The name of the Settings > Keymap section. */
private const val KEYMAP_SECTION_NAME = "Running Devices"
/** The action group defining the contents of the Settings > Keymap > Running Devices section. */
private const val ACTION_GROUP = "Streaming"

private const val SHOW_EXTENDED_CONTROLS_ACTION_ID = "android.emulator.show.extended.controls"
private const val EXTENDED_CONTROLS_GROUP_ID = "android.emulator.extended.controls"
private const val DISPLAYS_ACTION_ID = "android.emulator.displays"
private const val CELLULAR_ACTION_ID = "android.emulator.cellular"
private const val BATTERY_ACTION_ID = "android.emulator.battery"

/** Adds the Settings -> Keymap > Running Devices section. */
internal class StreamingKeymapExtension : KeymapExtension {

  override fun createGroup(filtered: Condition<in AnAction>, project: Project?): KeymapGroup? {
    val keymapGroup = KeymapGroupFactory.getInstance().createGroup(KEYMAP_SECTION_NAME)

    val actionManager = ActionManager.getInstance()
    for (action in ActionsTreeUtil.getActions(ACTION_GROUP)) {
      if (
        (action is StreamingXrInputModeAction.InteractionHand ||
          action is StreamingXrInputModeAction.InteractionEye ||
          action is StreamingXrInputModePopupGroup) && !StudioFlags.EMBEDDED_EMULATOR_XR_HAND_AND_EYE_INPUT.get()
      ) {
        continue
      }
      val id = (action as? ActionStubBase)?.id ?: actionManager.getId(action)
      val extendedControlsInStudio = StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.get()
      if (
        (action is EmulatorExtendedControlsActionGroup ||
          action is EmulatorDisplaysAction ||
          action is EmulatorCellularAction ||
          action is EmulatorBatteryAction ||
          id == EXTENDED_CONTROLS_GROUP_ID ||
          id == DISPLAYS_ACTION_ID ||
          id == CELLULAR_ACTION_ID ||
          id == BATTERY_ACTION_ID) && !extendedControlsInStudio
      ) {
        continue
      }
      if ((action is EmulatorShowExtendedControlsAction || id == SHOW_EXTENDED_CONTROLS_ACTION_ID) && extendedControlsInStudio) {
        continue
      }
      ActionsTreeUtil.addAction(keymapGroup, action, filtered)
    }

    return keymapGroup
  }
}
