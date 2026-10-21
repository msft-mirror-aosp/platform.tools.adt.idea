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

import com.android.tools.adtui.common.primaryContentBackground
import com.android.tools.adtui.util.ActionToolbarUtil
import com.android.tools.adtui.workbench.ToolWindowDefinition
import com.android.tools.adtui.workbench.WorkBench
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.model.common.GroupAware
import com.android.tools.idea.insights.ui.APP_INSIGHTS_ID
import com.android.tools.idea.insights.ui.AppInsightsToolbar
import com.android.tools.idea.insights.ui.Timestamp
import com.android.tools.idea.insights.ui.TimestampState
import com.android.tools.idea.insights.ui.actions.AppInsightsDisplayRefreshTimestampAction
import com.android.tools.idea.insights.ui.actions.AppInsightsToggleAction
import com.android.tools.idea.insights.ui.actions.TreeDropDownAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBLoadingPanel
import java.awt.BorderLayout
import java.time.Clock
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting

private val LOG = Logger.getInstance(FindingsTab::class.java)

@VisibleForTesting internal const val NO_FINDINGS_TEXT = "No findings found."

@VisibleForTesting internal const val NO_APPS_TEXT = "Select an app connected to Google Play Console."

@VisibleForTesting internal const val NO_MATCHING_FINDINGS_TEXT = "No findings match the current filters."

/**
 * Returns the empty-state message for the findings table when [FindingsState.currentFindings] is [LoadingState.Ready].
 *
 * @param packageName The selected Play Console package name, or `null` if no Play-connected app is selected.
 * @param hasFindings Whether the app has any findings before category and severity filters are applied.
 */
@VisibleForTesting
internal fun emptyTableText(packageName: String?, hasFindings: Boolean) =
  when {
    packageName == null -> NO_APPS_TEXT
    !hasFindings -> NO_FINDINGS_TEXT
    else -> NO_MATCHING_FINDINGS_TEXT
  }

/** Context placeholder for the Findings tool window workbench. */
class FindingsToolWindowContext

/**
 * The main tab panel for Play Console Findings.
 *
 * Layout consists of a top filter toolbar above a two-pane [WorkBench] and [ThreeComponentsSplitter]:
 * - **Top toolbar**: Category/severity filter dropdowns, refresh action, and last-refreshed timestamp.
 * - **Left pane**: [FindingsTableView] listing findings matching the active filters.
 * - **Right pane**: [FindingsDetailsPanel] displaying metadata and description for the selected finding.
 */
class FindingsTab(
  private val controller: FindingsProjectLevelController,
  private val project: Project,
  clock: Clock = Clock.systemDefaultZone(),
  mainContext: CoroutineContext = Dispatchers.EDT,
) : JPanel(BorderLayout()), Disposable {
  private val scope = createCoroutineScope(mainContext)

  @VisibleForTesting internal val detailsPanel = FindingsDetailsPanel()
  private val table = FindingsTableView(onSelectionChanged = ::updateSelection)
  private val workBench = WorkBench<FindingsToolWindowContext>(project, "AQI_FINDINGS", null, this)

  @VisibleForTesting internal val categoryFilter = FindingsFilter.MultiSelect(onFilterUpdated = ::onFilterUpdated) { category }
  @VisibleForTesting
  internal val severityFilter = FindingsFilter.Toggle(FindingSeverity.entries.reversed(), onFilterUpdated = ::onFilterUpdated) { severity }
  private val filterGroup = FindingsFilterGroup(categoryFilter, severityFilter)

  private val timestamp: Flow<Timestamp> =
    controller.state
      .map { it.packageName to it.currentFindings }
      .distinctUntilChanged()
      .map { (packageName, findings) ->
        if (packageName == null) {
          Timestamp.UNINITIALIZED
        } else {
          when (findings) {
            is LoadingState.Loading -> Timestamp(null, TimestampState.UNAVAILABLE)
            is LoadingState.Done -> Timestamp(clock.instant(), TimestampState.ONLINE)
          }
        }
      }

  @VisibleForTesting internal val timestampAction = AppInsightsDisplayRefreshTimestampAction(timestamp, clock, scope)

  init {
    background = primaryContentBackground

    val loadingPanel = createLoadingTablePanel()
    val splitter = createSplitter(loadingPanel)

    workBench.init(splitter, FindingsToolWindowContext(), createToolWindowDefinitions(), false)
    add(createToolbar().component, BorderLayout.NORTH)
    add(workBench, BorderLayout.CENTER)

    updateSelection(null)
    observeControllerState(loadingPanel)
  }

  /**
   * Builds the top action toolbar containing the severity toggles, Category filter dropdown, Refresh action, and last-refreshed timestamp.
   */
  private fun createToolbar(): ActionToolbar {
    val toolbarGroup =
      DefaultActionGroup().apply {
        addAll(severityFilter.toToggleActions(labelProvider = { it.displayName }, iconProvider = FindingSeverity::icon))
        add(categoryFilter.toDropDownAction("Categories") { it.displayName })
        addSeparator()
        add(
          object : AnAction("Refresh", null, AllIcons.General.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
              controller.refresh()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
          }
        )
        add(timestampAction)
      }

    return AppInsightsToolbar("AQI_FINDINGS_TOOLBAR", toolbarGroup, true).apply {
      targetComponent = this@FindingsTab
      component.border = SideBorder(JBColor.border(), SideBorder.BOTTOM)
      ActionToolbarUtil.makeToolbarNavigable(this)
    }
  }

  private fun <T> FindingsFilter.Toggle<T>.toToggleActions(
    labelProvider: (T) -> String,
    iconProvider: (T) -> Icon,
  ): List<AppInsightsToggleAction> = items.map { value ->
    AppInsightsToggleAction(
      text = labelProvider(value),
      description = null,
      icon = iconProvider(value),
      flow = selectionFlow.map { value in it }.distinctUntilChanged(),
      scope = scope,
    ) {
      toggle(value)
    }
  }

  private fun <T> FindingsFilter.MultiSelect<T>.toDropDownAction(
    name: String,
    labelProvider: (T) -> String,
  ): TreeDropDownAction<T, GroupAware.Empty> =
    TreeDropDownAction(
      name = name,
      flow = selectionFlow,
      scope = scope,
      enabledFlow = MutableStateFlow(true),
      primaryColumnName = name,
      groupNameSupplier = labelProvider,
      nameSupplier = labelProvider,
      onSelected = ::select,
    )

  private fun createLoadingTablePanel(): JBLoadingPanel =
    JBLoadingPanel(BorderLayout(), this).apply {
      isOpaque = false
      add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
    }

  /** Creates the horizontal splitter pairing the table pane with [detailsPanel], sizing each to at least 1/4 of the tool window width. */
  private fun createSplitter(leftPanel: JComponent): ThreeComponentsSplitter =
    ThreeComponentsSplitter(false, true).apply {
      setHonorComponentsMinimumSize(true)
      firstComponent = leftPanel
      lastComponent = detailsPanel.component
      isFocusCycleRoot = false
      ToolWindowManager.getInstance(project).getToolWindow(APP_INSIGHTS_ID)?.let { toolWindow ->
        val minSize = toolWindow.component.width / 4
        firstSize = minSize
        lastSize = minSize
      }
    }

  private fun createToolWindowDefinitions(): List<ToolWindowDefinition<FindingsToolWindowContext>> = emptyList()

  /** Collects [FindingsProjectLevelController.state] to drive the loading overlay, table rows, filters, and details panel. */
  private fun observeControllerState(loadingPanel: JBLoadingPanel) {
    scope.launch {
      controller.state
        .map { state -> state.selectedFinding.takeIf { state.currentFindings is LoadingState.Ready } }
        .distinctUntilChanged()
        .collect { finding -> detailsPanel.updateFinding(finding) }
    }
    scope.launch {
      controller.state
        .map { it.packageName }
        .distinctUntilChanged()
        .collectLatest { packageName ->
          LOG.info("FindingsTab: Package name changed to '$packageName'. Resetting filters and clearing UI selection.")
          filterGroup.resetAll()

          controller.state
            .map { it.currentFindings }
            .distinctUntilChanged()
            .collect { loadingState ->
              when (loadingState) {
                is LoadingState.Loading -> handleLoadingState(packageName, loadingPanel)
                is LoadingState.Ready -> handleReadyState(loadingPanel)
                is LoadingState.Unauthorized -> {
                  LOG.warn("Failed to load findings: Unauthorized: ${loadingState.message}")
                  handleFailureState(
                    "Not logged in. Please sign in to your Play Developer Console account.",
                    loadingPanel,
                  )
                }
                is LoadingState.Failure -> {
                  LOG.warn("Failed to load findings: ${loadingState.message}", loadingState.cause)
                  handleFailureState(
                    "Failed to load findings: ${loadingState.message ?: "Unknown error"}",
                    loadingPanel,
                  )
                }
              }
            }
        }
    }
  }

  private fun handleLoadingState(packageName: String?, loadingPanel: JBLoadingPanel) {
    loadingPanel.apply {
      setLoadingText(if (packageName == null) "Syncing connected apps..." else "Loading findings...")
      startLoading()
    }
    table.clearItems()
  }

  /** Applies active filters to loaded findings, refreshes filter counts, and restores or defaults the selected row. */
  private fun handleReadyState(loadingPanel: JBLoadingPanel) {
    loadingPanel.stopLoading()
    onFilterUpdated(preferredSelection = controller.state.value.selectedFinding)
  }

  /** Stops loading, clears the table, and displays [emptyMessage] in the table's empty state. */
  private fun handleFailureState(emptyMessage: String, loadingPanel: JBLoadingPanel) {
    loadingPanel.stopLoading()
    table.clearItems()
    table.emptyText.text = emptyMessage
    updateSelection(null)
  }

  /** Applies active filters, updates table rows and empty-state text, and preserves or defaults row selection. */
  private fun onFilterUpdated(preferredSelection: AppFinding? = null) {
    val state = controller.state.value
    val allFindings = (state.currentFindings as? LoadingState.Ready)?.value ?: return
    val currentSelection = preferredSelection ?: table.selectedObject
    val filtered = filterGroup.apply(allFindings)
    table.updateItems(filtered)

    // Update empty text here so hiding all rows via filters displays NO_MATCHING_FINDINGS_TEXT instead of NO_FINDINGS_TEXT.
    table.emptyText.text = emptyTableText(state.packageName, hasFindings = allFindings.isNotEmpty())

    val targetSelection =
      if (currentSelection == null || currentSelection !in filtered) {
        filtered.firstOrNull()
      } else {
        currentSelection
      }
    updateSelection(targetSelection, scrollIntoView = preferredSelection != null)
  }

  /** Synchronizes the selected finding across [table] and [controller]. */
  private fun updateSelection(selected: AppFinding?, scrollIntoView: Boolean = false) {
    table.setSelectedFinding(selected, scrollIntoView = scrollIntoView)
    if (controller.state.value.selectedFinding != selected) {
      controller.selectFinding(selected)
    }
  }

  override fun dispose() = Unit
}
