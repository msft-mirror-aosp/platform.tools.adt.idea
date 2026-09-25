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

import com.android.screenshottest.ui.PreviewDetails
import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuite
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@RunsInEdt
class UpdateScreenshotTestResultsListenerTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val edtRule = EdtRule()

  /** Verifies that the listener correctly extracts all fields from the AndroidTestCase when all relevant artifacts are present. */
  @Test
  fun testOnTestCaseFinished_extractsDataCorrectly() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    `when`(dialog.project).thenReturn(projectRule.project)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }

    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val refPath = File(projectRule.project.basePath, "ref.png").canonicalPath
    val newPath = File(projectRule.project.basePath, "new.png").canonicalPath
    val diffPath = File(projectRule.project.basePath, "diff.png").canonicalPath

    val artifacts =
      mutableMapOf(
        "PreviewScreenshot.methodName" to "testMethod",
        "PreviewScreenshot.previewName" to "preview1",
        "PreviewScreenshot.refImagePath" to refPath,
        "PreviewScreenshot.newImagePath" to newPath,
        "PreviewScreenshot.diffImagePath" to diffPath,
        "PreviewScreenshot.diffPercent" to "0.05",
      )

    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "ignoredMethodName",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts = artifacts,
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)

    // Flush EDT to ensure invokeLater block runs
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Capture argument
    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertEquals("com.example.TestClass.testMethod.preview1", details.testId)
    assertEquals("com.example.TestClass", details.className)
    assertEquals("testMethod", details.methodName)
    assertEquals("preview1", details.previewName)
    assertEquals(refPath, details.destImagePath)
    assertEquals(newPath, details.srcImagePath)
    assertEquals(diffPath, details.diffImagePath)
    assertEquals("0.05", details.diffPercent)
  }

  /** Verifies that the listener correctly cleans the preview name from raw artifacts. */
  @Test
  fun testOnTestCaseFinished_cleansPreviewName() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val artifacts =
      mutableMapOf(
        "PreviewScreenshot.methodName" to "testMethod",
        "PreviewScreenshot.previewName" to "[{provider=com.example.MyProvider}]",
        "PreviewScreenshot.refImagePath" to "/path/to/ref.png",
      )

    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts = artifacts,
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    // Should be cleaned and have _0 removed
    assertEquals("MyProvider", details.previewName)
    assertEquals("com.example.TestClass.testMethod.MyProvider", details.testId)
  }

  /** Verifies that cleanPreviewName handles multiple parameters correctly. */
  @Test
  fun testOnTestCaseFinished_cleansMultipleParameters() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val artifacts =
      mutableMapOf(
        "PreviewScreenshot.methodName" to "testMethod",
        "PreviewScreenshot.previewName" to "[{provider=com.example.ProviderA, provider=com.example.ProviderB}]_0",
      )

    val testCase =
      AndroidTestCase(
        id = "test_multi_param",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts = artifacts,
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertEquals("ProviderA_ProviderB_0", details.previewName)
  }

  /**
   * Verifies that the listener handles cases where the artifacts map is empty, providing safe default values instead of crashing or
   * returning nulls where strings are expected.
   */
  @Test
  fun testOnTestCaseFinished_handlesMissingArtifacts() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }

    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "ignoredMethodName",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts = mutableMapOf(),
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    // Check default values
    assertEquals(" ", details.methodName)
    assertEquals(" ", details.previewName)
    assertEquals("com.example.TestClass. . ", details.testId)
    assertEquals(null, details.destImagePath)
  }

  /**
   * Verifies that the listener correctly extracts available data even when some artifacts are missing. This ensures robustness against
   * partial data.
   */
  @Test
  fun testOnTestCaseFinished_partialArtifacts() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    `when`(dialog.project).thenReturn(projectRule.project)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val refPath = File(projectRule.project.basePath, "ref.png").canonicalPath

    // Only method name and one image path are present
    val artifacts =
      mutableMapOf(
        "PreviewScreenshot.methodName" to "partialMethod",
        "PreviewScreenshot.refImagePath" to refPath,
      )

    val testCase =
      AndroidTestCase(
        id = "test_partial",
        methodName = "ignored",
        className = "com.example.PartialClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts = artifacts,
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertEquals("partialMethod", details.methodName)
    assertEquals(" ", details.previewName) // Default
    assertEquals(refPath, details.destImagePath)
    assertEquals(null, details.srcImagePath) // Missing
    assertEquals("com.example.PartialClass.partialMethod. ", details.testId)
  }

  /** Verifies that the test result status (PASSED, FAILED, etc.) is correctly propagated to the PreviewDetails. */
  @Test
  fun testOnTestCaseFinished_propagatesTestResult() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val testCase =
      AndroidTestCase(
        id = "test_result_check",
        methodName = "ignored",
        className = "com.example.ResultClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.SKIPPED,
        additionalTestArtifacts = mutableMapOf(),
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertEquals(AndroidTestCaseResult.SKIPPED, details.testResult)
  }

  /** Verifies that onTestSuiteFinished is correctly delegated to the dialog. */
  @Test
  fun testOnTestSuiteFinished() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    listener.onTestSuiteFinished(mockDevice, mockSuite)

    // Flush EDT to ensure invokeLater block from listener runs before verification.
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    verify(dialog).onTestSuiteFinished()
  }

  /** Verifies that the start of the suite is reported to the dialog as the start of rendering. */
  @Test
  fun testOnTestSuiteStarted_notifiesRenderingStarted() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }

    listener.onTestSuiteStarted(mock(AndroidDevice::class.java), mock(AndroidTestSuite::class.java))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    verify(dialog).onRenderingStarted()
  }

  /** Verifies that suite and test events reach the dialog in the order they were received. */
  @Test
  fun testEventsAreDeliveredInOrder() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val tasks = ArrayDeque<Runnable>()
    val listener = UpdateScreenshotTestResultsListener(dialog) { tasks.add(it) }
    val device = mock(AndroidDevice::class.java)
    val suite = mock(AndroidTestSuite::class.java)
    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts = mutableMapOf("PreviewScreenshot.methodName" to "m", "PreviewScreenshot.previewName" to "p"),
      )

    listener.onTestSuiteStarted(device, suite)
    listener.onTestCaseFinished(device, suite, testCase)
    listener.onTestSuiteFinished(device, suite)
    while (tasks.isNotEmpty()) tasks.removeFirst().run()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val inOrder = inOrder(dialog)
    inOrder.verify(dialog).onRenderingStarted()
    inOrder.verify(dialog).updateDialogWithTestResult(capturePreviewDetails(ArgumentCaptor.forClass(PreviewDetails::class.java)), ArgumentMatchers.eq(true))
    inOrder.verify(dialog).onTestSuiteFinished()
  }

  /** Verifies that the listener records which images exist so the dialog doesn't have to check the disk. */
  @Test
  fun testOnTestCaseFinished_resolvesImageFileExistence() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    `when`(dialog.project).thenReturn(projectRule.project)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }

    val refFile = File(projectRule.project.basePath, "existing_ref.png").canonicalFile.apply {
      parentFile.mkdirs()
      writeText("ref")
    }
    val diffPath = File(projectRule.project.basePath, "missing_diff.png").canonicalPath
    val testCase =
      AndroidTestCase(
        id = "test1",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "m",
            "PreviewScreenshot.previewName" to "p",
            "PreviewScreenshot.refImagePath" to refFile.path,
            "PreviewScreenshot.diffImagePath" to diffPath,
          ),
      )

    listener.onTestCaseFinished(mock(AndroidDevice::class.java), mock(AndroidTestSuite::class.java), testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))
    assertTrue(captor.value.destImageExists)
    assertFalse(captor.value.diffImageExists)
  }

  /** Verifies that size mismatch errors in the stack trace are detected and parsed correctly. */
  @Test
  fun testOnTestCaseFinished_extractsSizeMismatch() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val testCase =
      AndroidTestCase(
        id = "test_size_mismatch",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts = mutableMapOf(),
        errorStackTrace =
          "java.lang.AssertionError: Size Mismatch: Expected 100x100 but was 200x200\n\tat com.example.TestClass.test(TestClass.kt:42)",
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertTrue(details.isSizeMismatch)
    assertEquals("Size Mismatch: Expected 100x100 but was 200x200", details.sizeMismatchMessage)
  }

  /** Verifies that ordinary failure stack traces without size mismatch do not flag isSizeMismatch. */
  @Test
  fun testOnTestCaseFinished_noSizeMismatchForOtherErrors() {
    val dialog = mock(UpdateReferenceImagesDialog::class.java)
    val listener = UpdateScreenshotTestResultsListener(dialog) { it.run() }
    val mockDevice = mock(AndroidDevice::class.java)
    val mockSuite = mock(AndroidTestSuite::class.java)

    val testCase =
      AndroidTestCase(
        id = "test_other_failure",
        methodName = "ignored",
        className = "com.example.TestClass",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts = mutableMapOf(),
        errorStackTrace = "java.lang.AssertionError: Images differ by 5.2%\n\tat com.example.TestClass.test(TestClass.kt:42)",
      )

    listener.onTestCaseFinished(mockDevice, mockSuite, testCase)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val captor = ArgumentCaptor.forClass(PreviewDetails::class.java)
    verify(dialog).updateDialogWithTestResult(capturePreviewDetails(captor), ArgumentMatchers.eq(true))

    val details = captor.value
    assertFalse(details.isSizeMismatch)
    assertNull(details.sizeMismatchMessage)
  }

  /**
   * Helper function to capture non-nullable arguments with Mockito in Kotlin. Returns a dummy instance to satisfy Kotlin's null-safety
   * during the stubbing phase, while Mockito captures the actual value.
   */
  private fun capturePreviewDetails(captor: ArgumentCaptor<PreviewDetails>): PreviewDetails {
    captor.capture()
    return PreviewDetails(testId = "", className = "", methodName = "", previewName = "")
  }
}
