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
package com.android.tools.idea.play.findings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@RunsInEdt
class FindingStackTracePanelTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule).around(composeTestRule)

  @Test
  fun findingStackTracePanel_printsAndClearsOnDispose() {
    val consoleView = createFindingStackTraceConsoleView(projectRule.project, disposableRule.disposable)
    val trace =
      """
      java.lang.NullPointerException: Attempt to invoke virtual method on a null object
      	at com.example.app.ui.detail.DetailFragment.onViewCreated(DetailFragment.kt:74)
      """
        .trimIndent()
    var showPanel by mutableStateOf(true)

    composeTestRule.setContent {
      if (showPanel) {
        FindingStackTracePanel(
          stackTrace = trace,
          consoleView = consoleView,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    assertThat(consoleView.editor!!.document.text.trim()).isEqualTo(trace)

    showPanel = false
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    assertThat(consoleView.editor!!.document.text).isEmpty()
  }

  @Test
  fun softWrapButton_togglesEditorSoftWraps() {
    val consoleView = createFindingStackTraceConsoleView(projectRule.project, disposableRule.disposable)

    composeTestRule.setContent {
      FindingStackTraceSoftWrapButton(consoleView = consoleView)
      FindingStackTracePanel(
        stackTrace = "java.lang.RuntimeException: boom",
        consoleView = consoleView,
      )
    }
    composeTestRule.waitForIdle()

    assertThat(consoleView.editor!!.settings.isUseSoftWraps).isFalse()

    composeTestRule.onNodeWithTag("finding_stack_trace_soft_wrap", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    assertThat(consoleView.editor!!.settings.isUseSoftWraps).isTrue()

    composeTestRule.onNodeWithTag("finding_stack_trace_soft_wrap", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    assertThat(consoleView.editor!!.settings.isUseSoftWraps).isFalse()
  }
}
