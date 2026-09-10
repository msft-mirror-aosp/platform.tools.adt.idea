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

import com.android.testutils.delayUntilCondition
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.insights.AppInsightsConfigurationManager
import com.android.tools.idea.insights.AppInsightsModel
import com.android.tools.idea.insights.InsightsProvider
import com.android.tools.idea.insights.OfflineStatusManagerImpl
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.model.connection.Connection
import com.android.tools.idea.insights.ui.AppInsightsTabPanel
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FindingsTabProviderTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(disposableRule)

  private lateinit var model: MutableStateFlow<AppInsightsModel>
  private lateinit var controller: FakeFindingsProjectLevelController
  private lateinit var tabPanel: AppInsightsTabPanel
  private lateinit var tabProvider: FindingsTabProvider

  @Before
  fun setUp() {
    controller = FakeFindingsProjectLevelController()

    model = MutableStateFlow(AppInsightsModel.Uninitialized)
    val manager =
      object : AppInsightsConfigurationManager {
        override val project = projectRule.project
        override val configuration = model
        override val offlineStatusManager = OfflineStatusManagerImpl()

        override fun refreshConfiguration() = Unit
      }
    val configService = mock<FindingsConfigurationService>()
    whenever(configService.manager).thenReturn(manager)
    projectRule.project.replaceService(FindingsConfigurationService::class.java, configService, disposableRule.disposable)

    tabProvider = FindingsTabProvider()
    tabPanel = AppInsightsTabPanel()
    Disposer.register(disposableRule.disposable, tabPanel)
  }

  @Test
  fun `model change swaps the displayed component`() = runTest {
    tabProvider.populateTab(projectRule.project, tabPanel, MutableStateFlow(false))
    delayUntilCondition(200) { tabPanel.components.firstOrNull().toString().contains("placeholderContent") }

    model.value = AppInsightsModel.Authenticated(controller)
    delayUntilCondition(200) { tabPanel.components.firstOrNull() is FindingsTab }

    model.value = AppInsightsModel.Unauthenticated
    delayUntilCondition(200) { tabPanel.components.firstOrNull().toString().contains("loggedOutErrorStateComponent") }
  }
}

private class FakeFindingsProjectLevelController : FindingsProjectLevelController {
  val mutableState = MutableStateFlow(FindingsState(packageName = null))
  override val state: StateFlow<FindingsState> = mutableState.asStateFlow()

  override val provider: InsightsProvider = PlayFindingsInsightsProvider
  override val connections: StateFlow<Selection<Connection>> = MutableStateFlow(Selection.emptySelection())

  override fun refresh() = Unit

  override fun selectFinding(finding: AppFinding?) {
    mutableState.value = mutableState.value.copy(selectedFinding = finding)
  }
}
