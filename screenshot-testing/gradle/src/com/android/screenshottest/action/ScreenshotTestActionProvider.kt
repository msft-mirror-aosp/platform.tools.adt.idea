/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.screenshottest.action

import com.android.tools.idea.testartifacts.instrumented.testsuite.actions.AndroidTestSuiteDetailsActionProvider
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResults
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger

class ScreenshotTestActionProvider : AndroidTestSuiteDetailsActionProvider {
  private val LOG = Logger.getInstance(this.javaClass)

  override fun isApplicable(runConfiguration: RunConfiguration): Boolean {
    val isApplicable = runConfiguration.name.contains("Screenshot Tests", ignoreCase = true)
    LOG.debug("Checking applicability for run configuration: ${runConfiguration.name}, result: $isApplicable")
    return isApplicable
  }

  override fun getDetailsViewHeaderActions(testResults: AndroidTestResults?): List<AnAction> {
    LOG.debug("Getting details view header actions for test results: $testResults")
    // Create a new action for every test suite view. The registered action is a single shared
    // instance, so storing the results on it would make every run tab use the latest run's results.
    val registered = ActionManager.getInstance().getAction(FROM_TEST_PANEL_ACTION_ID)
    val action =
      UpdateReferenceImagesFromTestPanelAction().apply {
        this.testResults = testResults
        registered?.templatePresentation?.let {
          templatePresentation.text = it.text
          templatePresentation.description = it.description
        }
        templatePresentation.putClientProperty("SHOW_TEXT_IN_TOOLBAR", true)
      }
    return listOf(action)
  }

  private companion object {
    const val FROM_TEST_PANEL_ACTION_ID = "com.android.screenshottest.action.UpdateReferenceImagesFromTestPanelAction"
  }
}
