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

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingContext
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.gservices.DevServicesDeprecationData
import com.android.tools.idea.gservices.DevServicesDeprecationStatus
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.ai.AiInsight
import com.android.tools.idea.insights.ai.AiModelInfo
import com.android.tools.idea.insights.experiments.InsightFeedback
import com.android.tools.idea.insights.ui.InsightDeprecatedPanel
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class FindingsInsightPanelTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule).around(composeTestRule)

  private val controller: FindingsProjectLevelController = mock()
  private val aiInsightToolkit: FindingsInsightToolkit = mock()
  private val stateFlow = MutableStateFlow(FindingsState(packageName = "com.example.app"))

  private val testFinding =
    AppFinding(
      name = "findings/large_screen_1",
      category = FindingCategory.USER_EXPERIENCE,
      severity = FindingSeverity.WARNING,
      displayName = "App does not use entire screen",
      context = FindingContext(visualEvidences = listOf(VisualEvidence(url = "https://example.com/1.png"))),
    )

  private val testInsight =
    AiInsight(
      rawInsight = "Here is an AI insight for large screen layout.",
      modelInfo = AiModelInfo("Gemini 2.5 Pro", "Google"),
    )

  @Before
  fun setUp() {
    whenever(aiInsightToolkit.insightDeprecationData).thenReturn(DevServicesDeprecationData.EMPTY)
    whenever(controller.aiInsightToolkit).thenReturn(aiInsightToolkit)
    whenever(controller.provider).thenReturn(PlayFindingsInsightsProvider)
    whenever(controller.state).thenReturn(stateFlow)
  }

  private fun findingReadyState(
    insightState: LoadingState<AiInsight> = LoadingState.Ready(testInsight),
    currentFindings: LoadingState<List<AppFinding>> = LoadingState.Ready(listOf(testFinding)),
    selectedFinding: AppFinding? = testFinding,
  ): FindingsState =
    FindingsState(
      currentFindings = currentFindings,
      selectedFinding = selectedFinding,
      currentInsight = insightState,
      packageName = "com.example.app",
    )

  private fun mountInsightPanel(): FindingsInsightPanel {
    val panel =
      FindingsInsightPanel(
        project = projectRule.project,
        controller = controller,
        parentDisposable = disposableRule.disposable,
        mainContext = Dispatchers.Unconfined,
      )
    composeTestRule.setContent { panel.FindingsInsightContent() }
    composeTestRule.waitForIdle()
    return panel
  }

  @Test
  fun testDeprecatedServiceShowsDeprecatedPanel() {
    whenever(aiInsightToolkit.insightDeprecationData)
      .thenReturn(
        DevServicesDeprecationData("Gemini", "No longer supported", "https://example.com", true, DevServicesDeprecationStatus.UNSUPPORTED)
      )

    val panel =
      FindingsInsightPanel(
        project = projectRule.project,
        controller = controller,
        parentDisposable = disposableRule.disposable,
        mainContext = Dispatchers.Unconfined,
      )

    assertThat(panel.components.any { it is InsightDeprecatedPanel }).isTrue()
  }

  @Test
  fun testInitialStateShowsEmpty() {
    mountInsightPanel()

    composeTestRule.onNodeWithTag("empty_insight_title", useUnmergedTree = true).assertTextEquals("Select a finding")
    composeTestRule.onNodeWithTag("empty_insight_message", useUnmergedTree = true).assertTextEquals("Select a finding to view insights.")
    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("fix_with_ai_link", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testInsightLoadedShowsBottomPanelAndLinks() {
    mountInsightPanel()
    stateFlow.value = findingReadyState()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("insight_model_header", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("From Google > Gemini 2.5 Pro", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("insight_markdown", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("fix_with_ai_link", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun testClickSeeMoreNavigatesToStudioBot() {
    mountInsightPanel()
    stateFlow.value = findingReadyState()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("see_more_link").performSemanticsAction(SemanticsActions.OnClick)

    runBlocking {
      verify(aiInsightToolkit)
        .submitQueryToAgent(
          "Generate a detailed insight for the Play Console finding \"App does not use entire screen\".",
          testFinding,
        )
    }
  }

  @Test
  fun testClickFixWithAiNavigatesToStudioBot() {
    mountInsightPanel()
    stateFlow.value = findingReadyState()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("fix_with_ai_link").performSemanticsAction(SemanticsActions.OnClick)

    runBlocking {
      verify(aiInsightToolkit)
        .submitQueryToAgent(
          "Suggest a fix for the Play Console finding \"App does not use entire screen\".",
          testFinding,
        )
    }
  }

  @Test
  fun testUpvoteActionSubmitsFeedback() {
    val panel = mountInsightPanel()
    stateFlow.value = findingReadyState()

    val upvoteAction = panel.getToolbarActions().single { it.templateText == "Upvote this insight" }

    val event = TestActionEvent.createTestEvent()
    upvoteAction.actionPerformed(event)

    verify(controller).submitInsightFeedback(InsightFeedback.THUMBS_UP)
  }

  @Test
  fun testDownvoteActionSubmitsFeedback() {
    val panel = mountInsightPanel()
    stateFlow.value = findingReadyState()

    val downvoteAction = panel.getToolbarActions().single { it.templateText == "Downvote this insight" }

    val event = TestActionEvent.createTestEvent()
    downvoteAction.actionPerformed(event)

    verify(controller).submitInsightFeedback(InsightFeedback.THUMBS_DOWN)
  }

  @Test
  fun testRefreshActionRefreshesInsight() {
    val panel = mountInsightPanel()
    stateFlow.value = findingReadyState()

    val refreshAction = panel.getToolbarActions().single { it.templateText == "Regenerate insight" }

    val event = TestActionEvent.createTestEvent()
    refreshAction.actionPerformed(event)

    verify(controller).refreshInsight(forceGenerateNewInsight = true)
  }

  @Test
  fun testBottomPanelHiddenDuringLoading() {
    val panel = mountInsightPanel()
    stateFlow.value = findingReadyState(insightState = LoadingState.Loading("Loading insight..."))
    composeTestRule.waitForIdle()

    assertThat(panel.loadingPanel.isLoading).isTrue()
    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testFindingsRefreshClearsAndRestoresInsight() {
    mountInsightPanel()
    stateFlow.value = findingReadyState()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertIsDisplayed()

    // While findings are refreshing (LoadingState.Loading), insight panel resets to empty state even though selectedFinding is preserved
    stateFlow.value = findingReadyState(currentFindings = LoadingState.Loading)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("empty_insight_title", useUnmergedTree = true).assertTextEquals("Select a finding")

    // When findings finish loading (LoadingState.Ready), the insight is restored
    stateFlow.value = findingReadyState()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun testEmptyAndErrorStatesRenderInCompose() {
    mountInsightPanel()

    stateFlow.value = findingReadyState(insightState = LoadingState.Ready(AiInsight("")))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("empty_insight_title", useUnmergedTree = true).assertTextEquals("No insights")
    composeTestRule
      .onNodeWithTag("empty_insight_message", useUnmergedTree = true)
      .assertTextEquals("There are no insights available for this finding")

    stateFlow.value = findingReadyState(insightState = LoadingState.Unauthorized("unauthorized"))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("empty_insight_title", useUnmergedTree = true).assertTextEquals("Gemini is not available")
    composeTestRule
      .onNodeWithTag("empty_insight_message", useUnmergedTree = true)
      .assertTextEquals("To see insights, please enable the Gemini plugin in Settings > Plugins")

    stateFlow.value = findingReadyState(insightState = LoadingState.UnknownFailure("Something went wrong"))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("empty_insight_title", useUnmergedTree = true).assertTextEquals("Failed to generate insight")
    composeTestRule.onNodeWithTag("empty_insight_message", useUnmergedTree = true).assertTextEquals("Something went wrong")
    composeTestRule.onNodeWithTag("see_more_link", useUnmergedTree = true).assertDoesNotExist()

    stateFlow.value = findingReadyState(insightState = LoadingState.InsightAutogenerateDisabled)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Generate insight for this finding").assertIsDisplayed()
    composeTestRule.onNodeWithText("Enable auto-generation").assertIsDisplayed()

    stateFlow.value = findingReadyState(insightState = LoadingState.NoModelAvailable)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Regenerate").assertIsDisplayed()
  }
}
