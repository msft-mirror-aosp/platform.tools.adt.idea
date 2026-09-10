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

import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.FindingType
import com.android.tools.idea.insights.InsightsProvider
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.model.common.WithCount
import com.android.tools.idea.insights.model.connection.Connection
import com.android.tools.idea.insights.ui.AppInsightsToolbar
import com.android.tools.idea.insights.ui.actions.AppInsightsToggleAction
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import java.awt.BorderLayout
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@RunsInEdt
class FindingsTabTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule)

  private lateinit var controller: TestFindingsController
  private lateinit var tab: FindingsTab
  private val table: FindingsTableView
    get() = UIUtil.findComponentOfType(tab, FindingsTableView::class.java)!!

  private val findingEdgeToEdge =
    AppFinding.Supported(
      name = "finding-edge-to-edge",
      type = FindingType.EDGE_TO_EDGE_COMPAT,
      severity = FindingSeverity.WARNING,
      findingData =
        FindingData.EdgeToEdge(
          manualReviewMessage = "App does not draw edge to edge",
          versionCode = "101",
        ),
    )

  private val findingDrm =
    AppFinding.Supported(
      name = "finding-drm",
      type = FindingType.DRM_APP_COMPAT,
      severity = FindingSeverity.SEVERE,
      findingData =
        FindingData.DrmAppCompat(
          drmPackageName = "com.example.drm",
          drmDisplayName = "Example DRM",
          drmVersion = "1.0",
          recommendedDrmVersion = "2.0",
          learnMoreUrl = null,
        ),
    )

  @Before
  fun setUp() {
    controller = TestFindingsController()
    tab =
      FindingsTab(
        controller = controller,
        project = projectRule.project,
        clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
        mainContext = Dispatchers.Unconfined,
      )
    Disposer.register(disposableRule.disposable, tab)
  }

  @Test
  fun testReadyStatePopulatesTableAndSelectsFirstFinding() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )

    assertThat(table.listTableModel.items).containsExactly(findingEdgeToEdge, findingDrm).inOrder()
    assertThat(table.selectedObject).isEqualTo(findingEdgeToEdge)
    assertThat(controller.state.value.selectedFinding).isEqualTo(findingEdgeToEdge)
    assertThat(table.getColumnName(0)).isEqualTo("Finding")
    assertThat(table.getColumnName(1)).isEqualTo("Category")
    assertThat(table.getValueAt(0, 0)).isEqualTo(findingEdgeToEdge)
    assertThat(table.getValueAt(0, 1)).isEqualTo(FindingCategory.USER_EXPERIENCE.displayName)
    assertThat(table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).isNotNull()
    assertThat(table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1)).isNotNull()
  }

  @Test
  fun testUserRowSelectionUpdatesController() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )

    table.selection = listOf(findingDrm)

    assertThat(table.selectedObject).isEqualTo(findingDrm)
    assertThat(controller.state.value.selectedFinding).isEqualTo(findingDrm)
  }

  @Test
  fun testRefreshPreservesSelectedFinding() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )
    table.selection = listOf(findingDrm)

    // Simulate a refresh that preserves selectedFinding across Loading -> Ready
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Loading,
        selectedFinding = findingDrm,
      )
    assertThat(table.listTableModel.items).isEmpty()

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
        selectedFinding = findingDrm,
      )
    assertThat(table.selectedObject).isEqualTo(findingDrm)
    assertThat(controller.state.value.selectedFinding).isEqualTo(findingDrm)
  }

  @Test
  fun testCategoryAndSeverityFilteringUpdatesTableAndEmptyText() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )

    // Toggle WARNING off -> only findingDrm (SEVERE) remains, and categoryFilter counts update (USER_EXPERIENCE = 0, TECHNICAL_QUALITY = 1)
    tab.severityFilter.toggle(FindingSeverity.WARNING)
    assertThat(table.listTableModel.items).containsExactly(findingDrm)
    assertThat(table.selectedObject).isEqualTo(findingDrm)
    assertThat(controller.state.value.selectedFinding).isEqualTo(findingDrm)
    assertThat(tab.categoryFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(0L, FindingCategory.USER_EXPERIENCE),
        WithCount(1L, FindingCategory.TECHNICAL_QUALITY),
      )
      .inOrder()

    // Filtering category to TECHNICAL_QUALITY while findingDrm is already selected preserves findingDrm
    tab.categoryFilter.select(setOf(FindingCategory.TECHNICAL_QUALITY))
    assertThat(table.selectedObject).isEqualTo(findingDrm)

    // Filter to empty category set -> all rows hidden, empty text reports NO_MATCHING_FINDINGS_TEXT
    tab.categoryFilter.select(emptySet())
    assertThat(table.listTableModel.items).isEmpty()
    assertThat(table.emptyText.text).isEqualTo(NO_MATCHING_FINDINGS_TEXT)
    assertThat(controller.state.value.selectedFinding).isNull()
  }

  @Test
  fun testEmptyFindingsAndNoConnectedAppStates() {
    controller.mutableState.value =
      FindingsState(
        packageName = null,
        currentFindings = LoadingState.Ready(emptyList()),
      )
    assertThat(table.emptyText.text).isEqualTo(NO_APPS_TEXT)

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(emptyList()),
      )
    assertThat(table.emptyText.text).isEqualTo(NO_FINDINGS_TEXT)
  }

  @Test
  fun testUnauthorizedAndFailureStatesClearTableAndShowErrorEmptyText() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge)),
      )
    assertThat(table.listTableModel.items).hasSize(1)

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Unauthorized("Auth expired"),
      )
    assertThat(table.listTableModel.items).isEmpty()
    assertThat(table.emptyText.text).isEqualTo("Not logged in. Please sign in to your Play Developer Console account.")
    assertThat(controller.state.value.selectedFinding).isNull()

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.UnknownFailure("Backend unavailable"),
      )
    assertThat(table.listTableModel.items).isEmpty()
    assertThat(table.emptyText.text).isEqualTo("Failed to load findings: Backend unavailable")

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.UnknownFailure(null),
      )
    assertThat(table.emptyText.text).isEqualTo("Failed to load findings: Unknown error")

    // Changing filters while in a non-Ready state must not repopulate the table with previously loaded findings
    tab.severityFilter.toggle(FindingSeverity.WARNING)
    assertThat(table.listTableModel.items).isEmpty()
    assertThat(table.emptyText.text).isEqualTo("Failed to load findings: Unknown error")
  }

  @Test
  fun testPackageSwitchResetsFilters() {
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app1",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )

    tab.severityFilter.toggle(FindingSeverity.WARNING)
    assertThat(table.listTableModel.items).containsExactly(findingDrm)

    // Switching packageName transitions through Loading before Ready, and resets filter selections for app2
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app2",
        currentFindings = LoadingState.Loading,
      )
    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app2",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )
    assertThat(table.listTableModel.items).containsExactly(findingEdgeToEdge, findingDrm)
  }

  @Test
  fun testSeverityIcons() {
    assertThat(FindingSeverity.BLOCKING.icon).isEqualTo(StudioIcons.AppQualityInsights.FATAL)
    assertThat(FindingSeverity.SEVERE.icon).isEqualTo(StudioIcons.Common.ERROR)
    assertThat(FindingSeverity.WARNING.icon).isEqualTo(StudioIcons.Common.WARNING)
    assertThat(FindingSeverity.INFO.icon).isEqualTo(StudioIcons.Common.INFO)
  }

  @Test
  fun testTopToolbarAndRefreshTimestamp() {
    val layout = tab.layout as BorderLayout
    val topComponent = layout.getLayoutComponent(BorderLayout.NORTH)
    assertThat(topComponent).isInstanceOf(AppInsightsToolbar::class.java)

    assertThat(tab.timestampAction.displayText).isEqualTo("Last refreshed: never")

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Loading,
      )
    assertThat(tab.timestampAction.displayText).isEqualTo("Refreshing...")

    controller.mutableState.value =
      FindingsState(
        packageName = "com.example.app",
        currentFindings = LoadingState.Ready(listOf(findingEdgeToEdge, findingDrm)),
      )
    assertThat(tab.timestampAction.displayText).isEqualTo("Last refreshed: right now")

    val toolbar = topComponent as AppInsightsToolbar
    val severityToggles = toolbar.actionGroup.getChildren(null).filterIsInstance<AppInsightsToggleAction>()
    assertThat(severityToggles.map { it.templatePresentation.text })
      .containsExactly(
        FindingSeverity.BLOCKING.displayName,
        FindingSeverity.SEVERE.displayName,
        FindingSeverity.WARNING.displayName,
        FindingSeverity.INFO.displayName,
      )
      .inOrder()

    val warningToggle = severityToggles.first { it.templatePresentation.text == FindingSeverity.WARNING.displayName }
    assertThat(warningToggle.isSelected(TestActionEvent.createTestEvent())).isTrue()
    warningToggle.setSelected(TestActionEvent.createTestEvent(), false)
    assertThat(table.listTableModel.items).containsExactly(findingDrm)

    val refreshAction = toolbar.actionGroup.getChildren(null).first { it.templatePresentation.text == "Refresh" }
    refreshAction.actionPerformed(TestActionEvent.createTestEvent())
    assertThat(controller.refreshCount).isEqualTo(1)
  }
}

private class TestFindingsController : FindingsProjectLevelController {
  val mutableState = MutableStateFlow(FindingsState(packageName = null))
  override val state: StateFlow<FindingsState> = mutableState.asStateFlow()
  var refreshCount = 0

  override val provider: InsightsProvider = PlayFindingsInsightsProvider
  override val connections: StateFlow<Selection<Connection>> = MutableStateFlow(Selection.emptySelection())

  override fun refresh() {
    refreshCount++
  }

  override fun selectFinding(finding: AppFinding?) {
    mutableState.value = mutableState.value.copy(selectedFinding = finding)
  }
}
