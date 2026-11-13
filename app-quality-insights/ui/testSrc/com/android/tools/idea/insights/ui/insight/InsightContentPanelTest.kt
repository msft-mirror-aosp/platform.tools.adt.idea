/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.insights.ui.insight

import com.android.testutils.delayUntilCondition
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.insights.AppInsightsCrashController
import com.android.tools.idea.insights.AppInsightsCrashState
import com.android.tools.idea.insights.CONNECTION1
import com.android.tools.idea.insights.DEFAULT_AI_INSIGHT
import com.android.tools.idea.insights.FAKE_INSIGHTS_PROVIDER
import com.android.tools.idea.insights.ISSUE1
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.TEST_FILTERS
import com.android.tools.idea.insights.Timed
import com.android.tools.idea.insights.ai.AiInsight
import com.android.tools.idea.insights.ai.AiInsightToolkit
import com.android.tools.idea.insights.ai.AiModelInfo
import com.android.tools.idea.insights.analytics.AppInsightsTracker
import com.android.tools.idea.insights.model.connection.ConnectionMode
import com.android.tools.idea.insights.ui.AI_INSIGHT_TOOLKIT_KEY
import com.android.tools.idea.insights.ui.APP_INSIGHTS_TRACKER_KEY
import com.android.tools.idea.insights.ui.SELECTED_APP_ID_KEY
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.google.gct.login2.LoginFeatureRule
import com.google.wireless.android.sdk.stats.AppQualityInsightsUsageEvent.GenerateInsightsAction.Action
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import java.awt.Dimension
import java.net.SocketTimeoutException
import java.time.Instant
import javax.swing.JButton
import javax.swing.JTextPane
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class InsightContentPanelTest {
  private val projectRule = ProjectRule()
  private val loginFeatureRule = LoginFeatureRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(EdtRule()).around(projectRule).around(loginFeatureRule)

  private lateinit var contentStateFlow: MutableStateFlow<InsightContentState>
  private lateinit var insightContentPanel: InsightContentPanel

  private val errorText: String
    get() = insightContentPanel.emptyStateText.text

  private val secondaryText: String
    get() = insightContentPanel.emptyStateText.secondaryComponent.toString()

  private val mockController = mock<AppInsightsCrashController>()

  private val mockAiInsightToolkit = mock<AiInsightToolkit>()

  private val mockTracker = mock<AppInsightsTracker>()

  private val scope = CoroutineScope(EmptyCoroutineContext)

  @Before
  fun setup() = runBlocking {
    doReturn(
        flowOf(
          AppInsightsCrashState(
            Selection(CONNECTION1, listOf(CONNECTION1)),
            TEST_FILTERS,
            LoadingState.Ready(Timed(Selection(ISSUE1, listOf(ISSUE1)), Instant.now())),
          )
        )
      )
      .whenever(mockController)
      .state
    doReturn(projectRule.project).whenever(mockController).project
    doReturn(mockAiInsightToolkit).whenever(mockController).aiInsightToolkit
    doReturn(FAKE_INSIGHTS_PROVIDER).whenever(mockController).provider
    contentStateFlow = MutableStateFlow(InsightContentState(LoadingState.Ready(AiInsight("insight", ISSUE1.sampleEvent))))
    insightContentPanel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
  }

  @After
  fun teardown() {
    scope.cancel()
  }

  @Test
  fun `test loading state`() = runBlocking {
    updateInsight(LoadingState.Loading)

    val fakeUi = FakeUi(insightContentPanel)

    val loadingPanel = fakeUi.findComponent<JBLoadingPanel>() ?: fail("Loading panel not found")
    assertThat(loadingPanel.getLoadingText()).isEqualTo("Fetching issue data...")

    updateInsight(LoadingState.Loading("Generating insight..."))
    waitForCondition(2.seconds) { loadingPanel.getLoadingText() == "Generating insight..." }
  }

  @Test
  fun `test value null, then action not fired text`() = runBlocking {
    updateInsight(LoadingState.Ready(null))

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo("Transient state (\"fetch insight\" action is not fired yet), should recover shortly.")
  }

  @Test
  fun `test insight empty, then no insight`() = runBlocking {
    updateInsight(LoadingState.Ready(DEFAULT_AI_INSIGHT))

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo("No insights")
    assertThat(secondaryText).isEqualTo("There are no insights available for this issue")
  }

  @Test
  fun `test failure shows failure message if available`() = runBlocking {
    updateInsight(LoadingState.UnknownFailure("Some failure", SocketTimeoutException()))

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo("Failed to generate insight")
    assertThat(secondaryText).isEqualTo("Some failure")
  }

  @Test
  fun `test failure shows generic failure message when message unavailable`() = runBlocking {
    updateInsight(LoadingState.UnknownFailure(null))

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo("Failed to generate insight")
    assertThat(secondaryText).isEqualTo("An unknown failure occurred")
  }

  @Test
  fun `test failure shows retry link that regenerates insight`() = runBlocking {
    updateInsight(LoadingState.UnknownFailure("Some failure"))

    val fakeUi = createSizedFakeUi()
    delayUntilStatusTextVisible()

    assertThat(statusTextLines).containsExactly("Failed to generate insight", "Some failure", "Retry").inOrder()

    fakeUi.clickRetryLink()
    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
  }

  @Test
  fun `test user needs onboarding shows enable insight button`() = runBlocking {
    val mutex = Mutex(locked = true)
    doAnswer { mutex.unlock() }.whenever(mockAiInsightToolkit).showOnboarding()
    updateInsight(LoadingState.Unauthorized(""))

    val fakeUi = FakeUi(insightContentPanel)

    val statusTexts = fakeUi.findAllComponents<Any> { it.javaClass.name.contains("StatusText\$Fragment") }.map { it.toString() }

    assertThat(statusTexts.size).isEqualTo(2)
    assertThat(statusTexts[0]).isEqualTo("Insights require Gemini")
    assertThat(statusTexts[1]).isEqualTo("You can set up Gemini and enable insights via the button below.")

    val button = fakeUi.findComponent<JButton>() ?: fail("Button not found")
    assertThat(button.text).isEqualTo("Enable Insights")
    assertThat(button.isVisible).isTrue()

    button.doClick()
    withTimeout(2.seconds) { mutex.lock() }
  }

  @Test
  fun `test offline mode`(): Unit = runBlocking {
    updateInsight(LoadingState.NetworkFailure(null), ConnectionMode.OFFLINE)

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(statusTextLines).containsExactly("Insights data is not available.")
  }

  @Test
  fun `test network failure while online shows retry link`() = runBlocking {
    updateInsight(LoadingState.NetworkFailure("Connection reset"))

    val fakeUi = createSizedFakeUi()
    delayUntilStatusTextVisible()

    assertThat(statusTextLines).containsExactly("Insights data is not available.", "Retry").inOrder()

    fakeUi.clickRetryLink()
    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
  }

  @Test
  fun `test quota exhausted`() = runBlocking {
    updateInsight(
      LoadingState.NetworkFailure(
        "Quota exceeded for quota metric 'Duet Task API requests' and limit 'Duet Task API requests per day per user' of service 'cloudaicompanion.googleapis.com' for consumer 'project_number:123456789'."
      )
    )

    val fakeUi = createSizedFakeUi()
    delayUntilStatusTextVisible()

    assertThat(statusTextLines)
      .containsExactly("Quota exhausted", "You have consumed your available daily quota for insights.", "Retry")
      .inOrder()

    fakeUi.clickRetryLink()
    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
  }

  @Test
  fun `test gemini is not enabled due to plugin not enabled`() = runBlocking {
    updateInsight(LoadingState.Unauthorized("Gemini is disabled"))

    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo(GEMINI_NOT_AVAILABLE)
    assertThat(secondaryText).isEqualTo("To see insights, please enable the Gemini plugin in Settings > Plugins")
  }

  @Test
  fun `test unsupported operation`() = runBlocking {
    updateInsight(LoadingState.UnsupportedOperation("Some message"))

    FakeUi(insightContentPanel)
    delayUntilStatusTextVisible()

    assertThat(errorText).isEqualTo("No insight available")
    assertThat(secondaryText).isEqualTo("Some message")
  }

  @Test
  fun `test illegal state shows auto-generation panel`() = runBlocking {
    updateInsight(LoadingState.InsightAutogenerateDisabled)

    val fakeUi = FakeUi(insightContentPanel)

    val autoGeneratePanel = fakeUi.findComponent<AutoGenerateInsightPanel>() ?: fail("AutoGenerateInsightPanel not found")
    assertThat(autoGeneratePanel.isVisible).isTrue()
  }

  @Test
  fun `test clicking 'Generate insight' in auto-generation panel refreshes insight`() = runBlocking {
    updateInsight(LoadingState.InsightAutogenerateDisabled)

    val fakeUi = FakeUi(insightContentPanel)
    val generateLink =
      fakeUi.findComponent<HyperlinkLabel> { it.text == "Generate insight for this issue" } ?: fail("Generate insight link not found")

    generateLink.doClick()
    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
    verify(mockTracker).logGenerateInsightAction("app1", Action.GENERATE_ONCE)
  }

  @Test
  fun `test clicking 'Enable auto-generation' in auto-generation panel sets auto-generation and refreshes insight`() = runBlocking {
    updateInsight(LoadingState.InsightAutogenerateDisabled)

    val fakeUi = FakeUi(insightContentPanel)
    val enableLink =
      fakeUi.findComponent<HyperlinkLabel> { it.text == "Enable auto-generation" } ?: fail("Enable auto-generation link not found")

    enableLink.doClick()
    verify(mockAiInsightToolkit).setAutoGenerate(true)
    verify(mockController).refreshInsight(false)
    verify(mockTracker).logGenerateInsightAction("app1", Action.ENABLE_AUTO_GENERATE)
  }

  @Test
  fun `test tooltip in auto-generation panel`() = runBlocking {
    updateInsight(LoadingState.InsightAutogenerateDisabled)

    val fakeUi = FakeUi(insightContentPanel)
    fakeUi.findComponent<AutoGenerateInsightPanel>() ?: fail("AutoGenerateInsightPanel not found")

    val iconLabel = fakeUi.findComponent<JBLabel> { it.toolTipText != null } ?: fail("Icon label not found")
    assertThat(iconLabel.toolTipText).contains("Insight auto-generation")
    assertThat(iconLabel.toolTipText).contains("Note: This may increase usage or charges.")
  }

  @Test
  fun `test uiDataSnapshot provides necessary keys`() = runBlocking {
    val sink = mock<DataSink>()
    insightContentPanel.uiDataSnapshot(sink)

    verify(sink)[AI_INSIGHT_TOOLKIT_KEY] = mockAiInsightToolkit
    verify(sink)[APP_INSIGHTS_TRACKER_KEY] = mockTracker
    verify(sink)[SELECTED_APP_ID_KEY] = "app1"
  }

  @Test
  fun `test model name visible when insight generated`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    val insight = AiInsight("insight text", ISSUE1.sampleEvent, modelInfo = AiModelInfo("Gemini Flash", null))
    updateInsight(LoadingState.Ready(insight))

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.text).isEqualTo("From Gemini Flash")
    assertThat(titledSeparator.isVisible).isTrue()
  }

  @Test
  fun `test model and provider name visible when insight generated`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    val insight = AiInsight("insight text", ISSUE1.sampleEvent, modelInfo = AiModelInfo("Gemini Flash", "Google"))
    updateInsight(LoadingState.Ready(insight))

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.text).isEqualTo("From Google > Gemini Flash")
    assertThat(titledSeparator.isVisible).isTrue()
  }

  @Test
  fun `test provider name only visible when insight generated`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    val insight = AiInsight("insight text", ISSUE1.sampleEvent, modelInfo = AiModelInfo(null, "Google"))
    updateInsight(LoadingState.Ready(insight))

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.text).isEqualTo("From Google")
    assertThat(titledSeparator.isVisible).isTrue()
  }

  @Test
  fun `test model header hidden when model and provider name are null`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    val insight = AiInsight("insight text", ISSUE1.sampleEvent, modelInfo = AiModelInfo(null, null))
    updateInsight(LoadingState.Ready(insight))

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.isVisible).isFalse()
  }

  @Test
  fun `test model name visible when auto-generation disabled`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.InsightAutogenerateDisabled)

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.text).isEqualTo("AI Insights")
    assertThat(titledSeparator.isVisible).isTrue()
  }

  @Test
  fun `test model name visible when no model available`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(panel)
    val titledSeparator = fakeUi.findComponent<TitledSeparator>() ?: fail("TitledSeparator not found")
    assertThat(titledSeparator.text).isEqualTo("AI Insights")
    assertThat(titledSeparator.isVisible).isTrue()
  }

  @Test
  fun `test no model available shows auto-generation panel with correct card`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(panel)

    val autoGeneratePanel = fakeUi.findComponent<AutoGenerateInsightPanel>() ?: fail("AutoGenerateInsightPanel not found")
    assertThat(autoGeneratePanel.isVisible).isTrue()

    val textPane = fakeUi.findComponent<JTextPane> { it.text.contains("No model assigned") }
    assertThat(textPane).isNotNull()
    assertThat(textPane?.isVisible).isTrue()

    val regenerateLink = fakeUi.findComponent<HyperlinkLabel> { it.text == "Regenerate" } ?: fail("Regenerate link not found")
    regenerateLink.doClick()
    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
  }

  @Test
  fun `test no model available shows correct message when model manager is enabled`() = runBlocking {
    StudioFlags.MODEL_MANAGER_ENABLED.overrideForTest(true, projectRule.disposable)
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(panel)
    val textPane = fakeUi.findComponent<JTextPane> { it.text.contains("No model assigned") }
    assertThat(textPane).isNotNull()
    assertThat(textPane?.text).contains("Settings")
  }

  @Test
  fun `test no model available shows correct message when model manager is disabled`() = runBlocking {
    StudioFlags.MODEL_MANAGER_ENABLED.overrideForTest(false, projectRule.disposable)
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(panel)
    val textPane = fakeUi.findComponent<JTextPane> { it.text.contains("No model assigned") }
    assertThat(textPane).isNotNull()
    assertThat(textPane?.text).contains("Agent tool window")
  }

  @Test
  fun `test clicking 'Assign model' in auto-generation panel invokes handleModelAssignment`(): Unit = runBlocking {
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(insightContentPanel)
    val assignModelLink = fakeUi.findComponent<HyperlinkLabel> { it.text == "Assign model" } ?: fail("Assign model link not found")

    assignModelLink.doClick()
    verify(mockAiInsightToolkit).handleModelAssignment()
  }

  @Test
  fun `test clicking regenerate generates insight irrespective of auto generate setting`() = runBlocking {
    val panel = InsightContentPanel(mockController, scope, contentStateFlow, mockTracker, projectRule.disposable)
    updateInsight(LoadingState.NoModelAvailable)

    val fakeUi = FakeUi(panel)
    val regenerateLink = fakeUi.findComponent<HyperlinkLabel> { it.text == "Regenerate" } ?: fail("Regenerate link not found")
    regenerateLink.doClick()

    verify(mockController).refreshInsight(forceGenerateNewInsight = true)
    verify(mockTracker).logGenerateInsightAction("app1", Action.GENERATE_ONCE)
  }

  private suspend fun delayUntilStatusTextVisible() = delayUntilCondition(200) { insightContentPanel.emptyStateText.isStatusVisible }

  private fun updateInsight(insight: LoadingState<AiInsight?>, mode: ConnectionMode = ConnectionMode.ONLINE) {
    contentStateFlow.value = InsightContentState(insight, mode)
  }

  private val statusTextLines: List<String>
    get() = insightContentPanel.emptyStateText.toString().lines()

  /** Gives the panel a real size so that clicks on the status text can be located. */
  private fun createSizedFakeUi(): FakeUi {
    insightContentPanel.size = Dimension(400, 400)
    return FakeUi(insightContentPanel).apply { layout() }
  }

  /** The status text is centered and "Retry" is its last line, so click just above the bottom of the text. */
  private fun FakeUi.clickRetryLink() {
    val bottomOfText = insightContentPanel.emptyStateText.pointBelow
    mouse.click(insightContentPanel.width / 2, bottomOfText.y - 2)
  }
}
