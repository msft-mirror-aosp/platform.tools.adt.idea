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

import com.android.screenshottest.ui.PreviewDetails
import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.screenshottest.util.UPDATE_ACTION_DESCRIPTION
import com.android.screenshottest.util.UPDATE_ACTION_TEXT
import com.android.screenshottest.util.UpdateReferenceImagesDialogManager
import com.android.tools.idea.metrics.MetricsTrackerRule
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.ScreenshotTestComposePreviewEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.CheckedTreeNode
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JPanel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UpdateReferenceImagesFromTestPanelActionTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val metricsTrackerRule = MetricsTrackerRule()

  private val tinyPngBytes =
    Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

  private val createdDialogs = mutableListOf<UpdateReferenceImagesDialog>()

  @Before
  fun setUp() {
    UpdateReferenceImagesDialogManager.getInstance(projectRule.project).resetForTests()
  }

  @After
  fun tearDown() {
    runInEdtAndWait {
      for (dialog in createdDialogs) {
        if (!dialog.isDisposed) {
          dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
      }
      createdDialogs.clear()
      UpdateReferenceImagesDialogManager.getInstance(projectRule.project).resetForTests()
    }
  }

  @Test
  fun testActionPerformed_populatesDialogTreeWithMultipleClassesAndMethods() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)

    var showCalled = false
    // Set on the background thread that processes the test results.
    val suiteFinished = AtomicBoolean(false)
    var capturedDialog: UpdateReferenceImagesDialog? = null
    manager.dialogFactory = { proj ->
      spy(UpdateReferenceImagesDialog(proj)).also { spyDialog ->
        createdDialogs += spyDialog
        doAnswer {
            showCalled = true
            null
          }
          .`when`(spyDialog)
          .show()
        doAnswer {
            suiteFinished.set(true)
            it.callRealMethod()
          }
          .`when`(spyDialog)
          .onTestSuiteFinished()
        capturedDialog = spyDialog
      }
    }

    val refPath = createProjectImage("screenshots/ref.png")
    val newFailedPath = createProjectImage("build/outputs/new_failed.png")
    val diffFailedPath = createProjectImage("build/outputs/diff_failed.png")
    val newPassedPath = createProjectImage("build/outputs/new_passed.png")
    val secondClassImagePath = createProjectImage("build/outputs/second_class.png")

    val failedCase =
      AndroidTestCase(
        id = "failed_id",
        methodName = "MethodA",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "MethodA",
            "PreviewScreenshot.previewName" to "PreviewFailed",
            "PreviewScreenshot.refImagePath" to refPath,
            "PreviewScreenshot.newImagePath" to newFailedPath,
            "PreviewScreenshot.diffImagePath" to diffFailedPath,
            "PreviewScreenshot.diffPercent" to "0.12",
          ),
      )

    val passedCase =
      AndroidTestCase(
        id = "passed_id",
        methodName = "MethodA",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "MethodA",
            "PreviewScreenshot.previewName" to "PreviewPassed",
            "PreviewScreenshot.newImagePath" to newPassedPath,
          ),
      )

    val secondMethodCase =
      AndroidTestCase(
        id = "method_b_id",
        methodName = "MethodB",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "MethodB",
            "PreviewScreenshot.previewName" to "PreviewMethodB",
            "PreviewScreenshot.newImagePath" to newFailedPath,
          ),
      )

    val secondClassCase =
      AndroidTestCase(
        id = "second_class_id",
        methodName = "MethodC",
        className = "com.example.SecondScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "MethodC",
            "PreviewScreenshot.previewName" to "PreviewSecondClass",
            "PreviewScreenshot.newImagePath" to secondClassImagePath,
          ),
      )

    // Missing previewName -> should be skipped
    val incompleteCase =
      AndroidTestCase(
        id = "incomplete_id",
        methodName = "IncompleteMethod",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts = mutableMapOf("PreviewScreenshot.methodName" to "IncompleteMethod"),
      )

    // Missing methodName -> should also be skipped
    val missingMethodCase =
      AndroidTestCase(
        id = "missing_method_id",
        methodName = "MissingMethod",
        className = "com.example.MyScreenshotTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts = mutableMapOf("PreviewScreenshot.previewName" to "OrphanPreview"),
      )

    val action = UpdateReferenceImagesFromTestPanelAction()
    action.testResults =
      createFakeTestResults(
        listOf(
          failedCase,
          passedCase,
          secondMethodCase,
          secondClassCase,
          incompleteCase,
          missingMethodCase,
        )
      )

    val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, dataContext))

    PlatformTestUtil.waitWithEventsDispatching("onTestSuiteFinished() was not called", { suiteFinished.get() }, 10)

    assertTrue("Dialog show() was not called", showCalled)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val dialog = capturedDialog!!
    verify(dialog).onTestSuiteFinished()
    // FromTestPanelAction checks only FAILED cases by default; since failed cases are checked and suite finished, OK is enabled.
    assertTrue("OK button should be enabled when at least one failed test is checked", dialog.isOKActionEnabled)

    val root = dialog.tree.model.root as CheckedTreeNode
    assertEquals(2, root.childCount)

    val firstClassNode = root.getChildAt(0) as CheckedTreeNode
    assertEquals("MyScreenshotTest", firstClassNode.userObject)
    assertEquals("IncompleteMethod and MissingMethod should not be added", 2, firstClassNode.childCount)

    val methodANode = firstClassNode.getChildAt(0) as CheckedTreeNode
    assertEquals("MethodA", methodANode.userObject)
    assertEquals(2, methodANode.childCount)

    val failedNode = methodANode.getChildAt(0) as CheckedTreeNode
    val failedDetails = failedNode.userObject as PreviewDetails
    assertTrue("FAILED test case should be checked by default", failedNode.isChecked)
    assertEquals("com.example.MyScreenshotTest.MethodA.PreviewFailed", failedDetails.testId)
    assertEquals(refPath, failedDetails.destImagePath)
    assertEquals(newFailedPath, failedDetails.srcImagePath)
    assertEquals(diffFailedPath, failedDetails.diffImagePath)
    assertEquals("0.12", failedDetails.diffPercent)
    assertTrue(failedDetails.destImageExists)
    assertTrue(failedDetails.diffImageExists)

    val passedNode = methodANode.getChildAt(1) as CheckedTreeNode
    val passedDetails = passedNode.userObject as PreviewDetails
    assertFalse("PASSED test case should not be checked by default", passedNode.isChecked)
    assertEquals("com.example.MyScreenshotTest.MethodA.PreviewPassed", passedDetails.testId)
    assertEquals(newPassedPath, passedDetails.srcImagePath)
    assertFalse(passedDetails.destImageExists)
    assertFalse(passedDetails.diffImageExists)

    val methodBNode = firstClassNode.getChildAt(1) as CheckedTreeNode
    assertEquals("MethodB", methodBNode.userObject)
    assertEquals(1, methodBNode.childCount)
    val methodBPreviewNode = methodBNode.getChildAt(0) as CheckedTreeNode
    assertTrue(methodBPreviewNode.isChecked)
    assertEquals("com.example.MyScreenshotTest.MethodB.PreviewMethodB", (methodBPreviewNode.userObject as PreviewDetails).testId)

    val secondClassNode = root.getChildAt(1) as CheckedTreeNode
    assertEquals("SecondScreenshotTest", secondClassNode.userObject)
    assertEquals(1, secondClassNode.childCount)
    val methodCNode = secondClassNode.getChildAt(0) as CheckedTreeNode
    assertEquals("MethodC", methodCNode.userObject)
    assertEquals(1, methodCNode.childCount)
    val methodCPreviewNode = methodCNode.getChildAt(0) as CheckedTreeNode
    assertEquals(secondClassImagePath, (methodCPreviewNode.userObject as PreviewDetails).srcImagePath)

    // Verify analytics events were logged
    val eventTypes =
      metricsTrackerRule.testTracker.usages
        .filter { it.studioEvent.kind == AndroidStudioEvent.EventKind.SCREENSHOT_TEST_COMPOSE_PREVIEW }
        .map { it.studioEvent.screenshotTestComposePreviewEvent.type }
    assertTrue(eventTypes.contains(ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_SOURCE_TEST_RESULTS))
    assertTrue(eventTypes.contains(ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_OPEN))
  }

  @Test
  fun testActionPerformed_rejectsPathTraversalAndExternalPaths() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)

    var showCalled = false
    // Set on the background thread that processes the test results.
    val suiteFinished = AtomicBoolean(false)
    var capturedDialog: UpdateReferenceImagesDialog? = null
    manager.dialogFactory = { proj ->
      spy(UpdateReferenceImagesDialog(proj)).also { spyDialog ->
        createdDialogs += spyDialog
        doAnswer {
            showCalled = true
            null
          }
          .`when`(spyDialog)
          .show()
        doAnswer {
            suiteFinished.set(true)
            it.callRealMethod()
          }
          .`when`(spyDialog)
          .onTestSuiteFinished()
        capturedDialog = spyDialog
      }
    }

    val validInsidePath = createProjectImage("build/outputs/inside.png")
    val projectRoot = File(project.basePath!!).canonicalFile
    val outside =
      FileUtil.createTempFile(projectRoot.parentFile, "outside_project", ".png", true).apply {
        writeBytes(tinyPngBytes)
      }
    try {
      assertTrue("External file must exist on disk so only traversal check rejects it", outside.exists())
      assertFalse(
        "External file must be outside project root",
        outside.canonicalFile.toPath().startsWith(projectRoot.toPath()),
      )
      val relativeEscape = projectRoot.toPath().relativize(outside.canonicalFile.toPath()).toString()
      assertTrue("Relative path must traverse upward with ..", relativeEscape.startsWith(".."))

      // 1. Relative traversal in refImagePath pointing to an existing file outside the project
      val relativeRefEscapeCase =
        AndroidTestCase(
          id = "rel_ref_escape",
          methodName = "MethodA",
          className = "com.example.TraversalTest",
          packageName = "com.example",
          result = AndroidTestCaseResult.FAILED,
          additionalTestArtifacts =
            mutableMapOf(
              "PreviewScreenshot.methodName" to "MethodA",
              "PreviewScreenshot.previewName" to "RelativeRefEscape",
              "PreviewScreenshot.refImagePath" to relativeEscape,
              "PreviewScreenshot.newImagePath" to validInsidePath,
            ),
        )

      // 2. Absolute path outside the project in refImagePath
      val absoluteRefEscapeCase =
        AndroidTestCase(
          id = "abs_ref_escape",
          methodName = "MethodA",
          className = "com.example.TraversalTest",
          packageName = "com.example",
          result = AndroidTestCaseResult.FAILED,
          additionalTestArtifacts =
            mutableMapOf(
              "PreviewScreenshot.methodName" to "MethodA",
              "PreviewScreenshot.previewName" to "AbsoluteRefEscape",
              "PreviewScreenshot.refImagePath" to outside.absolutePath,
              "PreviewScreenshot.newImagePath" to validInsidePath,
            ),
        )

      // 3. Relative and absolute paths outside the project in newImagePath (source of copy)
      val relativeNewEscapeCase =
        AndroidTestCase(
          id = "rel_new_escape",
          methodName = "MethodA",
          className = "com.example.TraversalTest",
          packageName = "com.example",
          result = AndroidTestCaseResult.FAILED,
          additionalTestArtifacts =
            mutableMapOf(
              "PreviewScreenshot.methodName" to "MethodA",
              "PreviewScreenshot.previewName" to "RelativeNewEscape",
              "PreviewScreenshot.newImagePath" to relativeEscape,
            ),
        )

      val absoluteNewEscapeCase =
        AndroidTestCase(
          id = "abs_new_escape",
          methodName = "MethodA",
          className = "com.example.TraversalTest",
          packageName = "com.example",
          result = AndroidTestCaseResult.FAILED,
          additionalTestArtifacts =
            mutableMapOf(
              "PreviewScreenshot.methodName" to "MethodA",
              "PreviewScreenshot.previewName" to "AbsoluteNewEscape",
              "PreviewScreenshot.newImagePath" to outside.absolutePath,
            ),
        )

      val action = UpdateReferenceImagesFromTestPanelAction()
      action.testResults =
        createFakeTestResults(
          listOf(
            relativeRefEscapeCase,
            absoluteRefEscapeCase,
            relativeNewEscapeCase,
            absoluteNewEscapeCase,
          )
        )

      val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
      action.actionPerformed(TestActionEvent.createTestEvent(action, dataContext))

      PlatformTestUtil.waitWithEventsDispatching("onTestSuiteFinished() was not called", { suiteFinished.get() }, 10)

      assertTrue("Dialog show() was not called", showCalled)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

      val dialog = capturedDialog!!
      val root = dialog.tree.model.root as CheckedTreeNode
      val classNode = root.firstChild as CheckedTreeNode
      val methodNode = classNode.firstChild as CheckedTreeNode
      assertEquals(4, methodNode.childCount)

      val relRefDetails = (methodNode.getChildAt(0) as CheckedTreeNode).userObject as PreviewDetails
      assertNull("Relative path escaping project root must resolve to null", relRefDetails.destImagePath)
      assertFalse(relRefDetails.destImageExists)
      assertEquals(validInsidePath, relRefDetails.srcImagePath)

      val absRefDetails = (methodNode.getChildAt(1) as CheckedTreeNode).userObject as PreviewDetails
      assertNull("Absolute path outside project root must resolve to null", absRefDetails.destImagePath)
      assertFalse(absRefDetails.destImageExists)
      assertEquals(validInsidePath, absRefDetails.srcImagePath)

      val relNewDetails = (methodNode.getChildAt(2) as CheckedTreeNode).userObject as PreviewDetails
      assertNull("Relative newImagePath escaping project root must resolve to null", relNewDetails.srcImagePath)

      val absNewDetails = (methodNode.getChildAt(3) as CheckedTreeNode).userObject as PreviewDetails
      assertNull("Absolute newImagePath outside project root must resolve to null", absNewDetails.srcImagePath)
    } finally {
      outside.delete()
    }
  }

  @Test
  fun testActionPerformed_emptyResultsClosesDialogAndLogsEmptyMetric() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)
    val mockLogger = mock(Logger::class.java)

    var showCalled = false
    // Set on the background thread that processes the test results.
    val suiteFinished = AtomicBoolean(false)
    var capturedDialog: UpdateReferenceImagesDialog? = null
    manager.dialogFactory = { proj ->
      spy(UpdateReferenceImagesDialog(proj, mockLogger)).also { spyDialog ->
        createdDialogs += spyDialog
        doAnswer {
            showCalled = true
            null
          }
          .`when`(spyDialog)
          .show()
        doAnswer {
            suiteFinished.set(true)
            it.callRealMethod()
          }
          .`when`(spyDialog)
          .onTestSuiteFinished()
        capturedDialog = spyDialog
      }
    }

    val shownMessages = mutableListOf<String>()
    TestDialogManager.setTestDialog { message ->
      shownMessages += message
      Messages.OK
    }
    try {
      val action = UpdateReferenceImagesFromTestPanelAction()
      action.testResults = createFakeTestResults(emptyList())
      val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
      action.actionPerformed(TestActionEvent.createTestEvent(action, dataContext))

      PlatformTestUtil.waitWithEventsDispatching("onTestSuiteFinished() was not called", { suiteFinished.get() }, 10)

      assertTrue("Dialog show() was not called", showCalled)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

      val dialog = capturedDialog!!
      assertEquals(DialogWrapper.CANCEL_EXIT_CODE, dialog.exitCode)
      verify(mockLogger).error("No tests were discovered in the test suite")
      assertEquals(listOf("Error while generating screenshots"), shownMessages)

      val eventTypes =
        metricsTrackerRule.testTracker.usages
          .filter {
            it.studioEvent.kind == AndroidStudioEvent.EventKind.SCREENSHOT_TEST_COMPOSE_PREVIEW
          }
          .map { it.studioEvent.screenshotTestComposePreviewEvent.type }
      assertTrue(eventTypes.contains(ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_TEST_RESULTS_EMPTY))
    } finally {
      TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }
  }

  @Test
  fun testActionPerformed_earlyReturnsWhenProjectOrResultsMissing() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)
    manager.dialogFactory = { error("Dialog should not be created") }

    val action = UpdateReferenceImagesFromTestPanelAction()

    // 1. Missing project in event
    action.testResults = createFakeTestResults(emptyList())
    val emptyContext = SimpleDataContext.builder().build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, emptyContext))

    // 2. Null testResults on action
    action.testResults = null
    val projectContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, projectContext))
  }

  @Test
  fun testActionPerformed_whenDialogAlreadyVisible_bringsToFront() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)

    val mockDialog = mock(UpdateReferenceImagesDialog::class.java)
    val disposable = Disposer.newDisposable(projectRule.testRootDisposable, "MockDialog")
    `when`(mockDialog.disposable).thenReturn(disposable)
    `when`(mockDialog.isVisible).thenReturn(false)

    var factoryCalls = 0
    manager.dialogFactory = {
      factoryCalls++
      mockDialog
    }

    // Open first dialog and mark it visible
    assertNotNull(manager.showOrGetDialog())
    `when`(mockDialog.isVisible).thenReturn(true)

    val action = UpdateReferenceImagesFromTestPanelAction()
    action.testResults = createFakeTestResults(emptyList())
    val projectContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, projectContext))

    assertEquals(1, factoryCalls)
    verify(mockDialog).toFront()
    Disposer.dispose(disposable)
  }

  @Test
  fun testActionPerformed_showsDialogBeforeTestResultsAreProcessed() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)

    val mockDialog = mock(UpdateReferenceImagesDialog::class.java)
    val disposable = Disposer.newDisposable(projectRule.testRootDisposable, "MockDialog")
    `when`(mockDialog.disposable).thenReturn(disposable)
    manager.dialogFactory = { mockDialog }

    val action = UpdateReferenceImagesFromTestPanelAction()
    action.testResults = createFakeTestResults(emptyList())
    val projectContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, projectContext))

    // The dialog must be visible as soon as it is registered with the manager. Otherwise, the
    // manager treats it as closed and a second click would open another dialog.
    verify(mockDialog).show()
    Disposer.dispose(disposable)
  }

  @Test
  fun testCustomComponentCreationUpdateAndClick() = runInEdtAndWait {
    val project = projectRule.project
    val manager = UpdateReferenceImagesDialogManager.getInstance(project)

    var showCalled = false
    manager.dialogFactory = { proj ->
      spy(UpdateReferenceImagesDialog(proj)).also { spyDialog ->
        createdDialogs += spyDialog
        doAnswer {
            showCalled = true
            null
          }
          .`when`(spyDialog)
          .show()
      }
    }

    val action = UpdateReferenceImagesFromTestPanelAction()
    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)

    val updateEvent = TestActionEvent.createTestEvent(action)
    updateEvent.presentation.isEnabledAndVisible = false
    action.update(updateEvent)
    assertTrue(updateEvent.presentation.isEnabled && updateEvent.presentation.isVisible)

    val presentation =
      Presentation().apply {
        text = UPDATE_ACTION_TEXT
        description = UPDATE_ACTION_DESCRIPTION
        isEnabled = true
        isVisible = true
      }

    val component = action.createCustomComponent(presentation, "TestSuiteHeader")
    assertTrue(component is JButton)
    val button = component as JButton
    assertEquals(UPDATE_ACTION_TEXT, button.text)
    assertEquals(UPDATE_ACTION_DESCRIPTION, button.toolTipText)
    assertTrue(button.isFocusable)
    assertEquals(UPDATE_ACTION_TEXT, button.accessibleContext.accessibleName)

    // Test updateCustomComponent updates button properties
    presentation.text = "Updated Text"
    presentation.description = "Updated Description"
    presentation.isEnabled = false
    presentation.isVisible = false
    action.updateCustomComponent(button, presentation)

    assertEquals("Updated Text", button.text)
    assertEquals("Updated Description", button.toolTipText)
    assertFalse(button.isEnabled)
    assertFalse(button.isVisible)
    assertEquals("Updated Text", button.accessibleContext.accessibleName)

    // Re-enable button and verify clicking triggers actionPerformed
    presentation.isEnabled = true
    presentation.isVisible = true
    action.updateCustomComponent(button, presentation)

    val newImagePath = createProjectImage("build/outputs/button_click.png")
    val testCase =
      AndroidTestCase(
        id = "click_test",
        methodName = "ClickMethod",
        className = "com.example.ClickTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.FAILED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "ClickMethod",
            "PreviewScreenshot.previewName" to "ClickPreview",
            "PreviewScreenshot.newImagePath" to newImagePath,
          ),
      )
    action.testResults = createFakeTestResults(listOf(testCase))

    val parentComponent =
      UiDataProvider.wrapComponent(button) { sink ->
        sink[CommonDataKeys.PROJECT] = project
      }
    assertNotNull(parentComponent)

    button.doClick()
    PlatformTestUtil.waitWithEventsDispatching(
      "Dialog show() was not called after button click",
      { showCalled },
      10,
    )
  }

  @Test
  fun testUpdateCustomComponent_ignoresNonButtonComponent() {
    val action = UpdateReferenceImagesFromTestPanelAction()
    val panel = JPanel().apply { isEnabled = true }
    val presentation =
      Presentation().apply {
        text = "Ignored Text"
        description = "Ignored Description"
        isEnabled = false
        isVisible = false
      }

    // updateCustomComponent only mutates JButton instances; other JComponents are left untouched.
    action.updateCustomComponent(panel, presentation)
    assertTrue(panel.isEnabled)
    assertTrue(panel.isVisible)
    assertNull(panel.toolTipText)
  }

  private fun createProjectImage(relativePath: String): String {
    val file = File(projectRule.project.basePath, relativePath).canonicalFile
    file.parentFile.mkdirs()
    file.writeBytes(tinyPngBytes)
    return file.absolutePath
  }
}
