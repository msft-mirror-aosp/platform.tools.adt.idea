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
package com.android.tools.idea.preview.animation.actions

import com.android.tools.idea.preview.NoopAnimationTracker
import com.android.tools.idea.preview.PreviewBundle.message
import com.android.tools.idea.preview.animation.SupportedAnimationManager.FrozenState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.TestActionEvent.createTestEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FreezeActionTest {
  @get:Rule val projectRule = ApplicationRule()

  @Test
  fun testFreezeActionPresentation() {
    val frozenState = MutableStateFlow(FrozenState(false, 0))
    val action = FreezeAction({ 100 }, frozenState, NoopAnimationTracker)
    val event = createTestEvent(action)

    action.update(event)
    assertEquals(message("animation.inspector.action.freeze"), event.presentation.text)
    assertFalse(action.isSelected(event))

    action.setSelected(event, true)
    assertEquals(100, frozenState.value.frozenAt)
    assertTrue(frozenState.value.isFrozen)
    assertTrue(action.isSelected(event))
    action.update(event)
    assertEquals(message("animation.inspector.action.unfreeze"), event.presentation.text)

    action.setSelected(event, false)
    assertFalse(action.isSelected(event))
    action.update(event)
    assertEquals(message("animation.inspector.action.freeze"), event.presentation.text)
  }

  @Test
  fun testActionUpdateThread() {
    val frozenState = MutableStateFlow(FrozenState(false, 0))
    val action = FreezeAction({ 0 }, frozenState, NoopAnimationTracker)
    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
  }
}
