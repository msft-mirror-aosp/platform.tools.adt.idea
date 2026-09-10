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

import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.insights.AppInsightsConfigurationManager
import com.android.tools.idea.insights.AppInsightsModel
import com.android.tools.idea.insights.InsightsProvider
import com.android.tools.idea.insights.ui.AppInsightsTabPanel
import com.android.tools.idea.insights.ui.AppInsightsTabProvider
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.google.gct.login2.fstLoginFeature
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.StatusText
import icons.StudioIllustrations
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val LOG = Logger.getInstance(FindingsTabProvider::class.java)

class FindingsTabProvider(private val mainContext: kotlin.coroutines.CoroutineContext = Dispatchers.EDT) : AppInsightsTabProvider {
  override val insightsProvider: InsightsProvider = PlayFindingsInsightsProvider

  override val icon: Icon = StudioIllustrations.Common.PLAY_CONSOLE_ICON

  override fun populateTab(project: Project, tabPanel: AppInsightsTabPanel, activeTabFlow: Flow<Boolean>) {
    val configService = FindingsConfigurationService.getInstance(project)
    val configManager = configService.manager
    val scope = tabPanel.createCoroutineScope()
    tabPanel.setComponent(placeholderContent())

    scope.launch(mainContext) {
      configManager.configuration.collect { appInsightsModel ->
        LOG.info("FindingsTabProvider: State changed: config=${appInsightsModel::class.java.simpleName}")
        when (appInsightsModel) {
          AppInsightsModel.Unauthenticated -> {
            tabPanel.setComponent(loggedOutErrorStateComponent())
          }
          is AppInsightsModel.Authenticated -> {
            val controller = appInsightsModel.controller as FindingsProjectLevelController
            tabPanel.setComponent(FindingsTab(controller, project, mainContext = mainContext))
          }
          // InitializationFailed is not currently emitted by FindingsConfigurationManager; share the placeholder with Uninitialized.
          AppInsightsModel.InitializationFailed,
          AppInsightsModel.Uninitialized -> {
            tabPanel.setComponent(placeholderContent())
          }
        }
      }
    }
  }

  override fun getConfigurationManager(project: Project): AppInsightsConfigurationManager {
    return FindingsConfigurationService.getInstance(project).manager
  }

  override fun isApplicable(): Boolean {
    return StudioFlags.PLAY_FINDINGS_ENABLED.get() && StudioFlags.APP_INSIGHTS_GLOBAL_SELECTOR.get() && StudioFlags.ENABLE_FSTS.get()
  }

  private fun placeholderContent(): JPanel =
    object : JPanel() {
      private val text =
        object : StatusText() {
            override fun isStatusVisible() = true
          }
          .also {
            it.appendLine("Waiting for initial sync...", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            it.attachTo(this)
          }

      override fun paint(g: Graphics?) {
        super.paint(g)
        text.paint(this, g)
      }
    }

  @Suppress("DialogTitleCapitalization")
  private fun loggedOutErrorStateComponent(): JPanel {
    val loggedOutText =
      object : StatusText() {
          override fun isStatusVisible() = true
        }
        .apply {
          appendLine(StudioIllustrations.Common.PLAY_CONSOLE, "", SimpleTextAttributes.REGULAR_ATTRIBUTES, null)
          appendLine("See app quality findings from Play Console", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, null)
          appendLine("Log in", SimpleTextAttributes.LINK_ATTRIBUTES) { fstLoginFeature.logInBlocking() }
          appendText(" to Android Studio to connect to your Play Console account.")
          appendLine("")
          appendLine(AllIcons.General.ContextHelp, "More Info", SimpleTextAttributes.LINK_ATTRIBUTES) {
            BrowserUtil.browse("https://d.android.com/r/studio-ui/debug/aqi-play-findings")
          }
        }

    return object : JPanel() {
      init {
        loggedOutText.attachTo(this)
      }

      override fun paint(g: Graphics?) {
        super.paint(g)
        loggedOutText.paint(this, g)
      }
    }
  }
}
