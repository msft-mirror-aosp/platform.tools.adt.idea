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
package com.android.screenshottest.action

import com.android.tools.idea.testartifacts.instrumented.testsuite.actions.AndroidTestSuiteDetailsActionProvider
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.actionSystem.ActionManager
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenshotTestActionProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val provider = ScreenshotTestActionProvider()

  @After
  fun tearDown() {
    val action =
      ActionManager.getInstance().getAction("com.android.screenshottest.action.UpdateReferenceImagesFromTestPanelAction")
        as? UpdateReferenceImagesFromTestPanelAction
    action?.testResults = null
  }

  @Test
  fun testIsApplicable_matchingAndNonMatchingConfigurations() {
    assertTrue(provider.isApplicable(createRunConfiguration("Screenshot Tests in MyScreenshotTest")))
    assertTrue(provider.isApplicable(createRunConfiguration("screenshot tests in com.example")))
    assertTrue(provider.isApplicable(createRunConfiguration("Run SCREENSHOT TESTS")))

    assertFalse(provider.isApplicable(createRunConfiguration("Unit Tests in MyUnitTest")))
    assertFalse(provider.isApplicable(createRunConfiguration("Screenshot Test")))
    assertFalse(provider.isApplicable(createRunConfiguration("")))
  }

  @Test
  fun testGetDetailsViewHeaderActions_configuresActionAndTestResults() {
    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "PreviewMethod",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
      )
    val testResults = createFakeTestResults(listOf(testCase))

    val actions = provider.getDetailsViewHeaderActions(testResults)
    assertEquals(1, actions.size)

    val action = actions.single()
    assertTrue(action is UpdateReferenceImagesFromTestPanelAction)
    val panelAction = action as UpdateReferenceImagesFromTestPanelAction
    assertSame(
      ActionManager.getInstance().getAction("com.android.screenshottest.action.UpdateReferenceImagesFromTestPanelAction"),
      panelAction,
    )
    assertSame(testResults, panelAction.testResults)
    assertEquals(true, panelAction.templatePresentation.getClientProperty("SHOW_TEXT_IN_TOOLBAR"))

    // Calling with null clears testResults on the action
    val actionsWithNull = provider.getDetailsViewHeaderActions(null)
    assertEquals(1, actionsWithNull.size)
    assertNull((actionsWithNull.single() as UpdateReferenceImagesFromTestPanelAction).testResults)
  }

  @Test
  fun testCompanionGetDetailsViewHeaderActions_integrationWithExtensionPoint() {
    val testResults = createFakeTestResults(emptyList())
    val screenshotConfig = createRunConfiguration("Screenshot Tests in MyScreenshotTest")

    val screenshotActions =
      AndroidTestSuiteDetailsActionProvider.getDetailsViewHeaderActions(
        screenshotConfig,
        testResults,
      )
    assertEquals(1, screenshotActions.size)
    val panelAction = screenshotActions.single() as UpdateReferenceImagesFromTestPanelAction
    assertSame(testResults, panelAction.testResults)

    val nullConfigActions = AndroidTestSuiteDetailsActionProvider.getDetailsViewHeaderActions(null, testResults)
    assertTrue(nullConfigActions.isEmpty())
  }

  private fun createRunConfiguration(name: String): RunConfiguration {
    val factory = GradleExternalTaskConfigurationType.getInstance().factory
    return factory.createTemplateConfiguration(projectRule.project).apply { this.name = name }
  }
}
