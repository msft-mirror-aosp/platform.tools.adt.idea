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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.InfoMarkdown
import com.android.tools.adtui.compose.LocalIsInUiTest
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.adtui.workbench.ToolContent
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.ai.AiInsight
import com.android.tools.idea.insights.analytics.AppInsightsTracker
import com.android.tools.idea.insights.analytics.AppInsightsTrackerImpl
import com.android.tools.idea.insights.ui.AI_INSIGHT_TOOLKIT_KEY
import com.android.tools.idea.insights.ui.APP_INSIGHTS_TRACKER_KEY
import com.android.tools.idea.insights.ui.InsightDeprecatedPanel
import com.android.tools.idea.insights.ui.SELECTED_APP_ID_KEY
import com.android.tools.idea.insights.ui.insight.InsightSettingGroup
import com.android.tools.idea.insights.ui.insight.InsightToolbarPanel
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CopyProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

class FindingsInsightPanel(
  private val project: Project,
  private val controller: FindingsProjectLevelController,
  parentDisposable: Disposable,
  private val tracker: AppInsightsTracker = AppInsightsTrackerImpl(project, AppInsightsTracker.ProductType.PLAY_FINDINGS),
  mainContext: CoroutineContext = Dispatchers.EDT,
) : JPanel(BorderLayout()), ToolContent<FindingsToolWindowContext>, UiDataProvider, CopyProvider, Disposable {

  private val scope = CoroutineScope(SupervisorJob() + mainContext)
  private var currentState by mutableStateOf(controller.state.value)

  private val activeFinding: AppFinding?
    get() = currentState.selectedFinding.takeIf { currentState.currentFindings is LoadingState.Ready }

  private val readyInsight: AiInsight?
    get() = (currentState.currentInsight as? LoadingState.Ready)?.value?.takeIf { activeFinding != null && it.rawInsight.isNotEmpty() }

  private val composeContentPanel: JComponent = StudioComposePanel { FindingsInsightContent() }
  @VisibleForTesting
  internal val loadingPanel =
    JBLoadingPanel(BorderLayout(), this).apply {
      border = JBUI.Borders.empty()
      add(composeContentPanel, BorderLayout.CENTER)
    }

  private val insightToolbarPanel =
    InsightToolbarPanel(
      controller = controller,
      // The toolbar is only shown alongside a ready insight, so states without a selection (null insight) can be skipped.
      currentInsightFlow = controller.state.mapNotNull { it.currentInsight },
      parentDisposable = this,
    )

  private val noModelSettingsToolbar: ActionToolbar =
    ActionManager.getInstance().createActionToolbar("FindingsNoModelSettings", DefaultActionGroup(InsightSettingGroup()), true).apply {
      layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
      targetComponent = this@FindingsInsightPanel
      component.isOpaque = false
    }

  init {
    Disposer.register(parentDisposable, this)

    val deprecationData = controller.aiInsightToolkit.insightDeprecationData
    if (deprecationData.isUnsupported()) {
      add(InsightDeprecatedPanel(scope, project, deprecationData, flowOf(true), tracker), BorderLayout.CENTER)
    } else {
      add(loadingPanel, BorderLayout.CENTER)
      applyState(currentState)
      scope.launch {
        controller.state.collect(::applyState)
      }
    }
  }

  private fun applyState(state: FindingsState) {
    currentState = state
    val selectedFinding = activeFinding
    val insightState = state.currentInsight

    if (selectedFinding != null && insightState is LoadingState.Loading) {
      loadingPanel.setLoadingText(insightState.message.ifEmpty { "Fetching insights..." })
      loadingPanel.startLoading()
    } else {
      loadingPanel.stopLoading()
    }
    revalidate()
    repaint()
  }

  @VisibleForTesting
  @Composable
  internal fun FindingsInsightContent() {
    val finding = activeFinding
    if (finding == null) {
      EmptyOrErrorBody(title = "Select a finding", message = "Select a finding to view insights.")
      return
    }

    // TODO(b/517599039): Align these states with the Crashlytics/Vitals InsightContentPanel: onboarding for Unauthorized, the
    //  model-manager-aware no-model message, and the quota-exhausted, network-failure, and unsupported-operation messages.
    when (val insightState = currentState.currentInsight) {
      null,
      is LoadingState.Loading -> Unit
      is LoadingState.Ready -> {
        val insight = insightState.value
        if (insight.rawInsight.isEmpty()) {
          EmptyOrErrorBody(title = "No insights", message = "There are no insights available for this finding")
        } else {
          InsightContentColumn(modelHeader = insight.modelInfo.readableName) {
            InsightTextBody(finding = finding, markdown = insight.rawInsight)
          }
        }
      }
      is LoadingState.InsightAutogenerateDisabled -> {
        InsightContentColumn(modelHeader = "From AI Model") {
          AutoGenerateDisabledBody()
        }
      }
      is LoadingState.NoModelAvailable -> {
        InsightContentColumn(modelHeader = "From AI Model") {
          NoModelAvailableBody()
        }
      }
      is LoadingState.Unauthorized -> {
        EmptyOrErrorBody(
          title = "Gemini is not available",
          message = "To see insights, please enable the Gemini plugin in Settings > Plugins",
        )
      }
      is LoadingState.Failure -> {
        EmptyOrErrorBody(
          title = "Failed to generate insight",
          message = insightState.message ?: "An unknown error occurred.",
        )
      }
    }
  }

  @Composable
  private fun EmptyOrErrorBody(title: String, message: String) {
    Box(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = title,
          fontWeight = FontWeight.Bold,
          color = JewelTheme.globalColors.text.info,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("empty_insight_title"),
        )
        Text(
          text = message,
          color = JewelTheme.globalColors.text.info,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("empty_insight_message"),
        )
      }
    }
  }

  @Composable
  private fun InsightContentColumn(
    modelHeader: String,
    content: @Composable ColumnScope.() -> Unit,
  ) {
    val scrollState = rememberScrollState()
    Row(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (modelHeader.isNotEmpty()) {
          GroupHeader(
            text = modelHeader,
            modifier = Modifier.fillMaxWidth().testTag("insight_model_header"),
          )
        }
        content()
      }
      VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.fillMaxHeight(),
      )
    }
  }

  @Composable
  private fun InsightTextBody(finding: AppFinding, markdown: String) {
    InfoMarkdown(
      markdown = markdown,
      modifier = Modifier.fillMaxWidth().testTag("insight_markdown"),
      selectable = true,
      textStyle = JewelTheme.defaultTextStyle,
      textColor = JewelTheme.globalColors.text.normal,
      onUrlClick = { url -> BrowserUtil.browse(url, project) },
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
      ) {
        Link(
          text = "See more",
          onClick = {
            val query = "Generate a detailed insight for the Play Console finding \"${finding.displayName}\"."
            scope.launch { controller.aiInsightToolkit.submitQueryToAgent(query, finding) }
          },
          modifier = Modifier.testTag("see_more_link"),
        )
        Link(
          text = "Fix with AI",
          onClick = {
            val query = "Suggest a fix for the Play Console finding \"${finding.displayName}\"."
            scope.launch { controller.aiInsightToolkit.submitQueryToAgent(query, finding) }
          },
          modifier = Modifier.testTag("fix_with_ai_link"),
        )
      }
      if (!LocalIsInUiTest.current) {
        SwingPanel(
          background = JewelTheme.globalColors.panelBackground,
          modifier = Modifier.size(width = 150.dp, height = 28.dp),
          factory = { insightToolbarPanel },
        )
      }
    }
  }

  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  private fun AutoGenerateDisabledBody() {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Link(
        text = "Generate insight for this finding",
        onClick = { controller.refreshInsight(forceGenerateNewInsight = true) },
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Link(
          text = "Enable auto-generation",
          onClick = {
            controller.aiInsightToolkit.setAutoGenerate(true)
            controller.refreshInsight(false)
          },
        )
        Tooltip(
          tooltip = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Insight auto-generation", fontWeight = FontWeight.Bold)
              Text("Automatically generate an AI insight for every finding you select using the model selected in the Agent tool window.")
              Text("Note: This may increase usage or charges. You can disable this anytime via the gear icon on any generated insight.")
            }
          }
        ) {
          Icon(
            key = AllIconsKeys.General.Information,
            contentDescription = "Insight auto-generation",
          )
        }
      }
    }
  }

  @Composable
  private fun NoModelAvailableBody() {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "No AI model selected. Please add or select a model from the Agent tool window and click \u2018Regenerate\u2019.",
        color = JewelTheme.globalColors.text.info,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Link(
          text = "Regenerate",
          onClick = { controller.refreshInsight(forceGenerateNewInsight = true) },
        )
        if (!LocalIsInUiTest.current) {
          SwingPanel(
            background = JewelTheme.globalColors.panelBackground,
            modifier = Modifier.size(width = 36.dp, height = 28.dp),
            factory = { noModelSettingsToolbar.component },
          )
        }
      }
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun performCopy(dataContext: DataContext) {
    val text = readyInsight?.rawInsight
    if (!text.isNullOrBlank()) {
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
  }

  override fun isCopyEnabled(dataContext: DataContext): Boolean = readyInsight?.rawInsight?.isNotBlank() == true

  override fun isCopyVisible(dataContext: DataContext): Boolean = true

  override fun uiDataSnapshot(sink: DataSink) {
    sink[PlatformDataKeys.COPY_PROVIDER] = this
    sink[AI_INSIGHT_TOOLKIT_KEY] = controller.aiInsightToolkit
    sink[APP_INSIGHTS_TRACKER_KEY] = tracker
    sink[SELECTED_APP_ID_KEY] = controller.state.value.packageName
  }

  @TestOnly
  internal fun getToolbarActions(): List<AnAction> =
    ((insightToolbarPanel.getComponent(0) as ActionToolbar).actionGroup as DefaultActionGroup).getChildren(null).toList()

  override fun getComponent() = this

  override fun setToolContext(toolContext: FindingsToolWindowContext?) {}

  override fun dispose() {
    scope.cancel()
  }
}
