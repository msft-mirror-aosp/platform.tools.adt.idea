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
package com.android.screenshottest.listener

import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.screenshottest.util.PreviewScreenshotArtifacts
import com.android.screenshottest.util.createPreviewDetails
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultListener
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuite
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Executor

/**
 * A listener that receives screenshot test results and passes them to the UI dialog.
 *
 * Events are forwarded from a single background thread, so they keep their order and slow work such as resolving paths and checking files
 * stays off the EDT. The dialog moves each event to the EDT itself.
 *
 * @param dialog The dialog to update with the test results.
 */
class UpdateScreenshotTestResultsListener(
  private val dialog: UpdateReferenceImagesDialog,
  private val executor: Executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("ScreenshotTestResults", 1),
) : AndroidTestResultListener {

  override fun onTestCaseFinished(
    device: AndroidDevice,
    testSuite: AndroidTestSuite,
    testCase: AndroidTestCase,
  ) {
    // AndroidTestCase is mutable, so read its state now rather than on the executor thread.
    val className = testCase.className
    val artifacts = testCase.additionalTestArtifacts.toMap()
    val testResult = testCase.result
    val errorStackTrace = testCase.errorStackTrace

    executor.execute {
      val previewDetails =
        createPreviewDetails(
          project = dialog.project,
          className = className,
          methodName = artifacts[PreviewScreenshotArtifacts.METHOD_NAME] ?: " ",
          rawPreviewName = artifacts[PreviewScreenshotArtifacts.PREVIEW_NAME] ?: " ",
          artifacts = artifacts,
          testResult = testResult,
          errorStackTrace = errorStackTrace,
        )
      dialog.updateDialogWithTestResult(previewDetails, true)
    }
  }

  override fun onTestSuiteStarted(device: AndroidDevice, testSuite: AndroidTestSuite) {
    // The suite starts once the project is built, right before the previews are rendered.
    executor.execute { dialog.onRenderingStarted() }
  }

  override fun onTestSuiteFinished(device: AndroidDevice, testSuite: AndroidTestSuite) {
    executor.execute { dialog.onTestSuiteFinished() }
  }
}
