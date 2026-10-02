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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.meshgradient.components.colorSwatchDescription
import com.android.tools.idea.compose.preview.message
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalTestApi::class)
@RunWith(JUnit4::class)
class ColorPaletteTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  private fun setPalette(state: GradientEditorState) {
    composeTestRule.setContent { ColorPalette(state) }
  }

  private fun swatchNode(color: Color): SemanticsNodeInteraction =
    composeTestRule.onNodeWithContentDescription(colorSwatchDescription(color))

  private fun SemanticsNodeInteraction.pressDeleteWhileFocused() {
    performSemanticsAction(SemanticsActions.RequestFocus)
    performKeyInput { pressKey(Key.Delete) }
    composeTestRule.waitForIdle()
  }

  private fun SemanticsNodeInteraction.hasDeleteAction(): Boolean =
    fetchSemanticsNode()
      .config
      .getOrElse(SemanticsActions.CustomActions) { emptyList() }
      .any {
        it.label == message("gradient.editor.palette.delete")
      }

  @Test
  fun deleteKeyRemovesTheFocusedColor() {
    val state = GradientEditorState()
    val color = state.availableColors[1]
    val initialSize = state.availableColors.size
    setPalette(state)

    swatchNode(color).pressDeleteWhileFocused()

    assertFalse(color in state.availableColors)
    assertEquals(initialSize - 1, state.availableColors.size)
  }

  @Test
  fun accessibilityDeleteActionRemovesTheColor() {
    val state = GradientEditorState()
    val color = state.availableColors[1]
    setPalette(state)

    val deleteAction =
      swatchNode(color).fetchSemanticsNode().config[SemanticsActions.CustomActions].single {
        it.label == message("gradient.editor.palette.delete")
      }
    composeTestRule.runOnUiThread { deleteAction.action() }
    composeTestRule.waitForIdle()

    assertFalse(color in state.availableColors)
  }

  @Test
  fun lastColorCannotBeDeleted() {
    val state = GradientEditorState()
    repeat(state.availableColors.size - 1) { state.removePaletteColor(0) }
    val color = state.availableColors.single()
    setPalette(state)

    assertFalse(swatchNode(color).hasDeleteAction())
    swatchNode(color).pressDeleteWhileFocused()

    assertEquals(listOf(color), state.availableColors)
  }

  @Test
  fun colorsCanBeDeletedWhileThePaletteHasSeveral() {
    val state = GradientEditorState()
    setPalette(state)

    assertTrue(state.availableColors.size > 1)
    assertTrue(state.availableColors.all { swatchNode(it).hasDeleteAction() })
  }
}
