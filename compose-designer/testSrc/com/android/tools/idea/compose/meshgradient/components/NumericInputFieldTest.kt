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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NumericInputFieldTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  private fun fieldText(): String =
    composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

  @Test
  fun valueNormalizedBackToThePreviousOneReplacesTheTypedText() {
    var value by mutableIntStateOf(3)
    val updates = mutableListOf<Int?>()
    val format = intFormat()
    composeTestRule.setContent {
      NumericInputField(
        value = value,
        format = format,
        onUpdate = {
          updates.add(it)
          // Values above 3 are rejected, so the stored value does not change.
          value = minOf(it ?: value, 3)
        },
      )
    }

    val field = composeTestRule.onNode(hasSetTextAction())
    field.performSemanticsAction(SemanticsActions.RequestFocus)
    field.performTextReplacement("5")
    field.performImeAction()
    composeTestRule.waitForIdle()

    assertEquals(listOf<Int?>(5), updates)
    assertEquals(3, value)
    assertEquals("3", fieldText())
  }
}
