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
package com.android.tools.profilers.taskbased.tabs.task.leakcanary.insight

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.profilers.leakcanary.AiInsight
import com.android.tools.profilers.leakcanary.LoadingState
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LeakInsightPanelTest {

  @get:Rule val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @Test
  fun testUnauthorizedStateDisplaysOnboardingScreen() {
    var enableInsightsClicked = false

    composeTestRule.setContent {
      LeakInsightPanel(
        insightState = LoadingState.Unauthorized("AI Assistant is not available."),
        isLeakSelected = true,
        autoGenerateEnabled = false,
        onAutoGenerateChange = {},
        onClose = {},
        onFeedback = {},
        onGenerateFix = {},
        onCopy = {},
        onRefresh = {},
        onEnableInsights = { enableInsightsClicked = true },
      )
    }

    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_INSIGHTS_REQUIRE_GEMINI).assertIsDisplayed()
    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_ENABLE_INSIGHTS_DESCRIPTION).assertIsDisplayed()
    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_ENABLE_INSIGHTS_BUTTON).assertIsDisplayed()

    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_ENABLE_INSIGHTS_BUTTON).performClick()
    assertTrue(enableInsightsClicked)
  }

  @Test
  fun testReadyStateDisplaysInsightContent() {
    composeTestRule.setContent {
      LeakInsightPanel(
        insightState = LoadingState.Ready(AiInsight(rawInsight = "This is a memory leak explanation.")),
        isLeakSelected = true,
        autoGenerateEnabled = false,
        onAutoGenerateChange = {},
        onClose = {},
        onFeedback = {},
        onGenerateFix = {},
        onCopy = {},
        onRefresh = {},
        markdownDispatcher = Dispatchers.Unconfined,
      )
    }

    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_FROM_AI_ASSISTANT).assertIsDisplayed()
    composeTestRule.onNodeWithText("This is a memory leak explanation.").assertIsDisplayed()
    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_FIX_WITH_AI).assertIsDisplayed()
  }

  @Test
  fun testFailureStateDisplaysErrorMessageAndRetry() {
    var retryClicked = false

    composeTestRule.setContent {
      LeakInsightPanel(
        insightState = LoadingState.Failure("Network connection timeout."),
        isLeakSelected = true,
        autoGenerateEnabled = false,
        onAutoGenerateChange = {},
        onClose = {},
        onFeedback = {},
        onGenerateFix = {},
        onCopy = {},
        onRefresh = { retryClicked = true },
      )
    }

    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_FAILED_TO_GENERATE_INSIGHT_TITLE).assertIsDisplayed()
    composeTestRule.onNodeWithText("Network connection timeout.").assertIsDisplayed()
  }

  @Test
  fun testEmptyStateWhenNoLeakSelected() {
    composeTestRule.setContent {
      LeakInsightPanel(
        insightState = LoadingState.Ready(null),
        isLeakSelected = false,
        autoGenerateEnabled = false,
        onAutoGenerateChange = {},
        onClose = {},
        onFeedback = {},
        onGenerateFix = {},
        onCopy = {},
        onRefresh = {},
      )
    }

    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_SELECT_LEAK_FOR_INSIGHT).assertIsDisplayed()
  }

  @Test
  fun testAutoGenDisabledLinksStayOnSameLineWhenWide() {
    setAutoGenDisabledEmptyStateContent(panelWidth = 600.dp)

    val generateBounds = composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_GENERATE_INSIGHT_LINK).getUnclippedBoundsInRoot()
    val enableBounds = composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_ENABLE_AUTO_GEN_LINK).getUnclippedBoundsInRoot()

    assertEquals("Links should share the same line when there is enough width", generateBounds.top, enableBounds.top)
    assertTrue("Enable link should be to the right of the Generate link", enableBounds.left >= generateBounds.right)
  }

  @Test
  fun testAutoGenDisabledLinksWrapToNextLineWhenNarrow() {
    setAutoGenDisabledEmptyStateContent(panelWidth = 200.dp)

    val generateLink = composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_GENERATE_INSIGHT_LINK).assertIsDisplayed()
    val enableLink = composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_ENABLE_AUTO_GEN_LINK).assertIsDisplayed()
    val generateBounds = generateLink.getUnclippedBoundsInRoot()
    val enableBounds = enableLink.getUnclippedBoundsInRoot()

    // The second link moves to its own line below the first instead of being clipped.
    assertTrue("Enable link should wrap below the Generate link when the pane is narrow", enableBounds.top >= generateBounds.bottom)
    assertEquals("Wrapped link should be start-aligned with the first link", generateBounds.left, enableBounds.left)
  }

  private fun setAutoGenDisabledEmptyStateContent(panelWidth: Dp) {
    composeTestRule.setContent {
      LeakInsightPanel(
        insightState = LoadingState.Ready(null),
        isLeakSelected = true,
        autoGenerateEnabled = false,
        onAutoGenerateChange = {},
        onClose = {},
        onFeedback = {},
        onGenerateFix = {},
        onCopy = {},
        onRefresh = {},
        modifier = Modifier.width(panelWidth),
      )
    }
  }
}
