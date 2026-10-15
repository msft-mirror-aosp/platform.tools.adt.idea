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

import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.StagingIcons
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorViewRule
import com.android.tools.idea.streaming.testutil.newEmulatorView
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test

/** Tests for [EmulatorExtendedControlsActionGroup]. */
@RunsInEdt
class EmulatorExtendedControlsActionGroupTest {
  private val emulatorViewRule = EmulatorViewRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      ApplicationRule(),
      ClipboardSynchronizationDisablementRule(),
      emulatorViewRule,
      EdtRule(),
      HeadlessDialogRule(),
    )

  @Test
  fun testFlagEnabledAndDisabledVisibility() {
    val view = emulatorViewRule.newEmulatorView()
    val actionId = "android.emulator.extended.controls"
    val displaysActionId = "android.emulator.displays"
    val cellularActionId = "android.emulator.cellular"

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(false, emulatorViewRule.disposable)
    val presentationDisabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationDisabled.isVisible).isFalse()
    assertThat(presentationDisabled.isEnabled).isFalse()

    val displaysDisabled = updateAndGetActionPresentation(displaysActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(displaysDisabled.isVisible).isFalse()
    assertThat(displaysDisabled.isEnabled).isFalse()

    val cellularDisabled = updateAndGetActionPresentation(cellularActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cellularDisabled.isVisible).isFalse()
    assertThat(cellularDisabled.isEnabled).isFalse()

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, emulatorViewRule.disposable)
    val presentationEnabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationEnabled.isVisible).isTrue()
    assertThat(presentationEnabled.isEnabled).isTrue()

    val displaysEnabled = updateAndGetActionPresentation(displaysActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(displaysEnabled.isVisible).isTrue()
    assertThat(displaysEnabled.isEnabled).isTrue()

    val cellularEnabled = updateAndGetActionPresentation(cellularActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cellularEnabled.isVisible).isTrue()
    assertThat(cellularEnabled.isEnabled).isTrue()
  }

  @Test
  fun testChildrenAndOpenDialogs() {
    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, emulatorViewRule.disposable)
    val view = emulatorViewRule.newEmulatorView()

    val actionManager = ActionManager.getInstance()
    val group = actionManager.getAction("android.emulator.extended.controls") as DefaultActionGroup
    assertThat(group.isPopup).isTrue()

    val children = group.getChildren(actionManager)
    assertThat(children).hasLength(2)
    val displaysAction = children[0]
    assertThat(displaysAction.templatePresentation.text).isEqualTo("Displays")
    assertThat(displaysAction.templatePresentation.icon).isEqualTo(StagingIcons.DISPLAYS)

    val cellularAction = children[1]
    assertThat(cellularAction.templatePresentation.text).isEqualTo("Cellular")
    assertThat(cellularAction.templatePresentation.icon).isEqualTo(StagingIcons.CELLULAR)

    createModalDialogAndInteractWithIt({ executeAction(displaysAction, view, emulatorViewRule.project, ActionPlaces.POPUP) }) { dlg ->
      assertThat(dlg.title).isEqualTo("Displays (${view.emulator.emulatorId.avdName})")
      dlg.close(DialogWrapper.CLOSE_EXIT_CODE)
    }

    createModalDialogAndInteractWithIt({ executeAction(cellularAction, view, emulatorViewRule.project, ActionPlaces.POPUP) }) { dlg ->
      assertThat(dlg.title).isEqualTo("Cellular (${view.emulator.emulatorId.avdName})")
      dlg.close(DialogWrapper.CLOSE_EXIT_CODE)
    }
  }
}
