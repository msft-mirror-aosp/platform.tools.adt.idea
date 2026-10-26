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
package com.android.analytics

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.analytics.ConsentDialogNext
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class ConsentDialogNextTest {

  private val composeTestRule = createStudioComposeTestRule()
  private val applicationRule = ApplicationRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(applicationRule).around(composeTestRule)

  private lateinit var dialog: ConsentDialogNext

  @Before
  fun setUp() = runTest {
    dialog = withContext(Dispatchers.EDT) { ConsentDialogNext() }
  }

  @After
  fun tearDown() = runTest {
    withContext(Dispatchers.EDT) { Disposer.dispose(dialog.disposable) }
  }

  @Test
  fun testDefaultOptedInIsTrue() = runTest {
    composeTestRule.setContent { dialog.ConsentDialogPage() }

    // Verify checkbox is ON (true) by default
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOn()

    // Click Done button
    composeTestRule.onNodeWithText("OK").performClickOnEdt()

    // Verify isOptedIn is true
    assertThat(dialog.isOptedIn).isTrue()
  }

  @Test
  fun testModifyingCheckboxChangesOptedIn() = runTest {
    composeTestRule.setContent { dialog.ConsentDialogPage() }

    // Verify checkbox is initially ON
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOn()

    // Click checkbox to turn it OFF
    composeTestRule.onNodeWithTag("OptInCheckbox").performClickOnEdt()
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOff()

    // Click Done button
    composeTestRule.onNodeWithText("OK").performClickOnEdt()

    // Verify isOptedIn is false
    assertThat(dialog.isOptedIn).isFalse()
  }

  @Test
  fun testClickingCheckboxCommentStringTogglesCheckbox() = runTest {
    composeTestRule.setContent { dialog.ConsentDialogPage() }

    // Verify checkbox is initially ON (true)
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOn()

    // Click on the checkbox comment string to toggle it OFF
    composeTestRule.onNodeWithText("Yes, I agree to allow Google to collect usage data", substring = true).performClickOnEdt()
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOff()

    // Click on the checkbox comment string again to toggle it back ON
    composeTestRule.onNodeWithText("Yes, I agree to allow Google to collect usage data", substring = true).performClickOnEdt()
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOn()
  }

  @Test
  fun testClosedWithoutOkButtonIsOptedInFalse() = runTest {
    composeTestRule.setContent { dialog.ConsentDialogPage() }

    // Verify checkbox is initially ON (true)
    composeTestRule.onNodeWithTag("OptInCheckbox").assertIsOn()

    // Close dialog without clicking OK
    withContext(Dispatchers.EDT) {
      dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    // Verify isOptedIn is false even though checkbox is checked
    assertThat(dialog.isOptedIn).isFalse()
  }

  private suspend fun SemanticsNodeInteraction.performClickOnEdt() = withContext(Dispatchers.EDT) { performClick() }
}
