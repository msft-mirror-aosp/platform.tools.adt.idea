/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.emulator.control.ThemingStyle
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorViewRule
import com.android.tools.idea.streaming.testutil.newEmulatorView
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfoImpl
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [EmulatorShowExtendedControlsAction]. */
@RunsInEdt
class EmulatorShowExtendedControlsActionTest {
  private val emulatorViewRule = EmulatorViewRule()
  @get:Rule
  val ruleChain = RuleChain(ApplicationRule(), ClipboardSynchronizationDisablementRule(), emulatorViewRule, EdtRule(), HeadlessDialogRule())

  @Test
  fun testShowExtendedControls() {
    val mockUIThemeLookAndFeelInfo = mock<UIThemeLookAndFeelInfoImpl>()
    whenever(mockUIThemeLookAndFeelInfo.name).thenReturn("High contrast")
    val mockLafManager = mock<LafManager>()
    whenever(mockLafManager.currentUIThemeLookAndFeel).thenReturn(mockUIThemeLookAndFeelInfo)
    ApplicationManager.getApplication().replaceService(LafManager::class.java, mockLafManager, emulatorViewRule.disposable)

    val view = emulatorViewRule.newEmulatorView()
    emulatorViewRule.executeAction("android.emulator.show.extended.controls", view)

    val emulator = emulatorViewRule.getFakeEmulator(view)
    var call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.UiController/setUiTheme")
    assertThat(call.request).isEqualTo(ThemingStyle.newBuilder().setStyle(ThemingStyle.Style.CONTRAST).build())
    call = emulator.getNextGrpcCall(1.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.UiController/showExtendedControls")
  }

  @Test
  fun testFlagEnabledHidesAndDisablesAction() {
    val view = emulatorViewRule.newEmulatorView()
    val actionId = "android.emulator.show.extended.controls"

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(false, emulatorViewRule.disposable)
    val presentationDisabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationDisabled.isVisible).isTrue()
    assertThat(presentationDisabled.isEnabled).isTrue()

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, emulatorViewRule.disposable)
    val presentationEnabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationEnabled.isVisible).isFalse()
    assertThat(presentationEnabled.isEnabled).isFalse()
  }
}
