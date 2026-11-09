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
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.PillButton
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import org.junit.Rule
import org.junit.Test

class UpdateAndroidStudioActionTest {
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun registeredAction_hasExpectedTextAndDescription() {
    val action = ActionManager.getInstance().getAction("AutomatedUpdates.UpdateAndroidStudio")

    assertThat(action).isInstanceOf(UpdateAndroidStudioAction::class.java)
    assertThat(action.templatePresentation.text).isEqualTo("Update Android Studio")
    assertThat(action.templatePresentation.description).isEqualTo("Open the Android Studio update dialog")
  }

  @Test
  fun update_invisibleWhenFlagDisabled() {
    StudioFlags.AUTOMATED_UPDATES_FLOW.overrideForTest(false, projectRule.disposable)
    val action = UpdateAndroidStudioAction()
    val event = TestActionEvent.createTestEvent()
    action.update(event)

    assertThat(event.presentation.isEnabledAndVisible).isFalse()
  }

  @Test
  fun update_visibleWhenFlagEnabled() {
    StudioFlags.AUTOMATED_UPDATES_FLOW.overrideForTest(true, projectRule.disposable)
    val action = UpdateAndroidStudioAction()
    val event = TestActionEvent.createTestEvent()
    action.update(event)

    assertThat(event.presentation.isEnabledAndVisible).isTrue()
  }

  @Suppress("UnstableApiUsage")
  @Test
  fun customComponent_createsPillButtonAndOpensDialogOnClick() {
    StudioFlags.AUTOMATED_UPDATES_FLOW.overrideForTest(true, projectRule.disposable)
    var dialogOpenedCount = 0
    val action = UpdateAndroidStudioAction { _: Project? -> dialogOpenedCount++ }
    val event = TestActionEvent.createTestEvent()
    action.update(event)

    runInEdtAndWait {
      val component = action.createCustomComponent(event.presentation, ActionPlaces.MAIN_TOOLBAR)
      assertThat(component).isInstanceOf(PillButtonWrapper::class.java)
      val wrapper = component as PillButtonWrapper
      action.updateCustomComponent(wrapper, event.presentation)

      val pillButton = wrapper.button
      assertThat(pillButton).isInstanceOf(PillButton::class.java)

      val now = System.currentTimeMillis()
      pillButton.dispatchEvent(
        MouseEvent(pillButton, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1)
      )
      pillButton.dispatchEvent(MouseEvent(pillButton, MouseEvent.MOUSE_RELEASED, now + 10, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
    }

    assertThat(dialogOpenedCount).isEqualTo(1)
  }
}
