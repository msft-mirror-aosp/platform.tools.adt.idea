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
package com.android.tools.idea.compose.meshgradient.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
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

@RunWith(JUnit4::class)
class ColorComponentsTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun editableColorSwatchSupportsDeleteViaKeyAndCustomAccessibilityAction() {
    var deletedCount = 0
    composeTestRule.setContent {
      Row {
        EditableColorSwatch(
          color = Color.Red,
          contentDescription = "Color stop 1: #FFFF0000",
          onClick = {},
          onDelete = { deletedCount++ },
        )
        EditableColorSwatch(
          color = Color.Blue,
          contentDescription = "Color stop 2: #FF0000FF",
          onClick = {},
          onDelete = null,
        )
      }
    }

    val deletable = composeTestRule.onNodeWithContentDescription("Color stop 1: #FFFF0000")
    val nonDeletable = composeTestRule.onNodeWithContentDescription("Color stop 2: #FF0000FF")

    val actions = deletable.fetchSemanticsNode().config[SemanticsActions.CustomActions]
    assertEquals(1, actions.size)
    assertEquals(message("gradient.editor.brush.delete.color"), actions[0].label)
    assertTrue(actions[0].action())
    assertEquals(1, deletedCount)

    deletable.performSemanticsAction(SemanticsActions.RequestFocus)
    deletable.performKeyInput { pressKey(Key.Delete) }
    composeTestRule.waitForIdle()
    assertEquals(2, deletedCount)

    assertFalse(SemanticsActions.CustomActions in nonDeletable.fetchSemanticsNode().config)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun paletteSwatchGridAutoFocusesSelectedColorAndSupportsArrowKeyNavigation() {
    val palette = listOf(Color.Red, Color.Green, Color.Blue)
    val picked = mutableListOf<Color>()
    composeTestRule.setContent {
      Row {
        PaletteSwatchGrid(
          palette = palette,
          selectedColor = Color.Green,
          onColorSelected = { picked.add(it) },
        )
        AddColorButton(contentDescription = "Add Color", onClick = {})
      }
    }
    composeTestRule.waitForIdle()

    val redNode = composeTestRule.onNodeWithContentDescription(colorSwatchDescription(Color.Red))
    val greenNode = composeTestRule.onNodeWithContentDescription(colorSwatchDescription(Color.Green))
    val blueNode = composeTestRule.onNodeWithContentDescription(colorSwatchDescription(Color.Blue))
    val addNode = composeTestRule.onNodeWithContentDescription("Add Color")

    redNode.assertIsNotSelected()
    greenNode.assertIsSelected()
    greenNode.assertIsFocused()

    greenNode.performKeyInput { pressKey(Key.DirectionRight) }
    composeTestRule.waitForIdle()
    blueNode.assertIsFocused()

    blueNode.performKeyInput { pressKey(Key.DirectionRight) }
    composeTestRule.waitForIdle()
    addNode.assertIsFocused()

    addNode.performKeyInput { pressKey(Key.DirectionLeft) }
    composeTestRule.waitForIdle()
    blueNode.assertIsFocused()
  }
}
