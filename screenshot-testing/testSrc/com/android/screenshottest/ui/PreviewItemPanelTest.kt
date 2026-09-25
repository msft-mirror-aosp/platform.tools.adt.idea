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
package com.android.screenshottest.ui

import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.view.ScreenshotViewType
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.util.concurrent.MoreExecutors
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBImageIcon
import java.awt.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class PreviewItemPanelTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule var temporaryFolder = TemporaryFolder()

  @Test
  fun verifyInitialization() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    assertEquals(details, panel.previewData)
  }

  @Test
  fun verifyDetailsPanelHidden() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
      )
    val panel = PreviewItemPanel(details, projectRule.project, showDetails = false)

    // Expect only 1 component (the image panel)
    assertEquals(1, panel.componentCount)
  }

  @Test
  fun verifyDetailsLabels() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "MyPreview",
        testResult = AndroidTestCaseResult.FAILED,
        diffPercent = "0.01", // 99% match
      )
    val panel = PreviewItemPanel(details, projectRule.project, showDetails = true)

    val labels = findAllLabels(panel)

    val matchTextLabel = labels.find { it.text == "Match: " }
    val percentageLabel = labels.find { it.text == "99.00%" }
    val nameLabel = labels.find { it.text == "MyPreview" }

    assertNotNull("Match prefix label 'Match: ' should exist", matchTextLabel)
    assertNotNull("Match percentage label '99.00%' should exist", percentageLabel)
    assertNotNull("Name label 'MyPreview' should exist", nameLabel)
  }

  @Test
  fun verifyDetailsLabels_whenNoReferenceImage_displaysNewTag() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "MyPreview",
        testResult = AndroidTestCaseResult.FAILED,
        destImagePath = null,
        diffPercent = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project, showDetails = true)

    val labels = findAllLabels(panel)

    val matchTextLabel = labels.find { it.text == "Match: " }
    val newTagLabel = labels.find { it.text == "New" }
    val nameLabel = labels.find { it.text == "MyPreview" }

    assertNull("Match prefix label 'Match: ' should not exist for new screenshot", matchTextLabel)
    assertNotNull("New tag label 'New' should exist", newTagLabel)
    assertEquals(JBColor.GREEN.darker(), newTagLabel?.foreground)
    assertNotNull("Name label 'MyPreview' should exist", nameLabel)
  }

  @Test
  fun verifyDetailsLabels_whenNonExistentDestImagePath_displaysNewTag() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "MyPreview",
        testResult = AndroidTestCaseResult.FAILED,
        destImagePath = "non_existent_ref.png",
        diffPercent = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project, showDetails = true)

    val labels = findAllLabels(panel)

    val matchTextLabel = labels.find { it.text == "Match: " }
    val newTagLabel = labels.find { it.text == "New" }

    assertNull("Match prefix label 'Match: ' should not exist for new screenshot", matchTextLabel)
    assertNotNull("New tag label 'New' should exist", newTagLabel)
    assertEquals(JBColor.GREEN.darker(), newTagLabel?.foreground)
  }

  @Test
  fun testUpdateData_toNewPreview_updatesToNewTag() = runInEdtAndWait {
    val refFile = temporaryFolder.newFile("ref.png")
    val details1 =
      PreviewDetails(
        testId = "id1",
        className = "Class1",
        methodName = "method1",
        previewName = "preview1",
        testResult = AndroidTestCaseResult.FAILED,
        destImagePath = refFile.absolutePath,
        diffPercent = "0.05",
      )
    val panel = PreviewItemPanel(details1, projectRule.project)

    val details2 =
      PreviewDetails(
        testId = "id2",
        className = "Class2",
        methodName = "method2",
        previewName = "preview2",
        testResult = AndroidTestCaseResult.FAILED,
        destImagePath = null,
        diffPercent = null,
      )

    panel.updateData(details2, ScreenshotViewType.NEW)

    val labels = findAllLabels(panel)
    assertNull(labels.find { it.text == "Match: " })
    val newTagLabel = labels.find { it.text == "New" }
    assertNotNull(newTagLabel)
    assertEquals(JBColor.GREEN.darker(), newTagLabel?.foreground)
  }

  @Test
  fun verifyPlaceholderForMissingNewImage() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.FAILED,
        srcImagePath = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    panel.showImageForView(ScreenshotViewType.NEW)

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val label = findLabel(panel)
    assertEquals("No New Image", label?.text)
  }

  @Test
  fun verifyPlaceholderForPassedDiff() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        diffImagePath = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    panel.showImageForView(ScreenshotViewType.DIFF)

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val label = findLabel(panel)
    assertEquals("No Difference", label?.text)
  }

  @Test
  fun verifyPlaceholderForFailedDiff() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.FAILED,
        diffImagePath = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    panel.showImageForView(ScreenshotViewType.DIFF)

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val label = findLabel(panel)
    assertEquals("No Diff Image", label?.text)
  }

  @Test
  fun verifyPlaceholderForReferenceImage() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        destImagePath = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    panel.showImageForView(ScreenshotViewType.REFERENCE)

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val label = findLabel(panel)
    assertEquals("No Reference Image", label?.text)
  }

  @Test
  fun verifyImageLoadIsCached() = runInEdtAndWait {
    var imageCreationCount = 0

    val srcPath = temporaryFolder.newFile("image.png").absolutePath
    val diffPath = temporaryFolder.newFile("diff.png").absolutePath
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.FAILED,
        srcImagePath = srcPath,
        diffImagePath = diffPath,
        diffImageExists = true,
      )

    val panel =
      PreviewItemPanel(
        previewData = details,
        project = projectRule.project,
        thumbnailLoader =
          directThumbnailLoader { _ ->
            imageCreationCount++
            mock()
          },
      )

    panel.showImageForView(ScreenshotViewType.NEW)

    assertEquals("First load should trigger image creation", 1, imageCreationCount)

    // We request the SAME path. The 'if (current == new)' check should prevent execution.
    panel.showImageForView(ScreenshotViewType.NEW)

    assertEquals("Subsequent load for same path should be skipped (Cached)", 1, imageCreationCount)

    // Switch to DIFF view (different path)
    panel.showImageForView(ScreenshotViewType.DIFF)

    assertEquals("Changing the path should trigger new load", 2, imageCreationCount)
  }

  @Test
  fun testUpdateData() = runInEdtAndWait {
    val details1 =
      PreviewDetails(
        testId = "id1",
        className = "Class1",
        methodName = "method1",
        previewName = "preview1",
        testResult = AndroidTestCaseResult.PASSED,
      )
    val panel = PreviewItemPanel(details1, projectRule.project)

    val details2 =
      PreviewDetails(
        testId = "id2",
        className = "Class2",
        methodName = "method2",
        previewName = "preview2",
        testResult = AndroidTestCaseResult.FAILED,
        diffPercent = "0.05",
      )

    panel.updateData(details2, ScreenshotViewType.NEW)

    assertEquals(details2, panel.previewData)
    val labels = findAllLabels(panel)
    assertNotNull(labels.find { it.text == "preview2" })
    assertNotNull(labels.find { it.text == "95.00%" })
  }

  @Test
  fun testStaleLoadPrevention() = runInEdtAndWait {
    var imageCreationCount = 0

    val path1 = temporaryFolder.newFile("image1.png").absolutePath
    val path2 = temporaryFolder.newFile("image2.png").absolutePath

    val details =
      PreviewDetails(
        testId = "id",
        className = "Class",
        methodName = "method",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        srcImagePath = path1,
      )

    // We need to control the order of execution for the async loads.
    // However, since we use DirectExecutor, they happen immediately.
    // To simulate stale loads, we can manually manipulate the fields if needed,
    // but the current implementation of loadImage already handles it by checking currentImagePath.

    val panel =
      PreviewItemPanel(
        previewData = details,
        project = projectRule.project,
        thumbnailLoader =
          directThumbnailLoader { _ ->
            imageCreationCount++
            mock()
          },
      )

    panel.loadImage(path1, "id")
    assertEquals(1, imageCreationCount)

    panel.loadImage(path2, "id")
    assertEquals(2, imageCreationCount)
  }

  @Test
  fun testOnImageLoadedCallback() = runInEdtAndWait {
    var callbackCount = 0
    val path = temporaryFolder.newFile("image.png").absolutePath
    val details =
      PreviewDetails(
        testId = "id",
        className = "Class",
        methodName = "m",
        previewName = "p",
        srcImagePath = path,
      )

    val panel =
      PreviewItemPanel(
        previewData = details,
        project = projectRule.project,
        thumbnailLoader = directThumbnailLoader { mock() },
      )

    panel.updateData(details, ScreenshotViewType.NEW) { callbackCount++ }

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals("Callback should be invoked after image load", 1, callbackCount)
  }

  @Test
  fun verifyImageReloadsAfterPlaceholder() = runInEdtAndWait {
    var imageCreationCount = 0
    val srcPath = temporaryFolder.newFile("image.png").absolutePath
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        srcImagePath = srcPath,
        diffImagePath = null,
      )

    val panel =
      PreviewItemPanel(
        previewData = details,
        project = projectRule.project,
        showDetails = false, // Disable details to avoid finding the "Match: " label
        thumbnailLoader =
          directThumbnailLoader { _ ->
            imageCreationCount++
            mock()
          },
      )

    // 1. Initial load
    panel.showImageForView(ScreenshotViewType.NEW)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals("Should load image the first time", 1, imageCreationCount)
    assertTrue("Flag should be true after successful load", panel.isLoadedSuccessfully)
    assertNull(
      "The placeholder label should be removed when an image is displayed",
      findLabel(panel),
    )

    // 2. Switch to Diff (which shows a placeholder for PASSED tests)
    panel.showImageForView(ScreenshotViewType.DIFF)
    // CRUCIAL: Dispatch events so the invokeLater in showPlaceholder runs
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val label = findLabel(panel)
    assertEquals("No Difference", label?.text)

    // 3. Switch back to "New" image (same path as step 1)
    panel.showImageForView(ScreenshotViewType.NEW)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // The image is shown again synchronously from the cache, without decoding it a second time.
    assertEquals("Should reuse the cached thumbnail after a placeholder was shown", 1, imageCreationCount)
    assertTrue("Flag should still be true", panel.isLoadedSuccessfully)
    assertNull("The placeholder label should be replaced by the image", findLabel(panel))
  }

  @Test
  fun verifyPlaceholderIsSetSynchronously() = runInEdtAndWait {
    val details =
      PreviewDetails(
        testId = "test.id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        diffImagePath = null,
      )
    val panel = PreviewItemPanel(details, projectRule.project)

    // Trigger the view change.
    panel.showImageForView(ScreenshotViewType.DIFF)

    // NOT dispatching the events here! The UI must update synchronously
    // to work correctly within a JBList CellRenderer.

    val label = findLabel(panel)
    assertNotNull("Placeholder label should be applied synchronously", label)
    assertEquals("No Difference", label?.text)
  }

  @Test
  fun verifyPanelReuseUpdatesPlaceholderSynchronously() = runInEdtAndWait {
    // 1. Initial state: The panel is rendering a successful new image
    val srcPath = temporaryFolder.newFile("image.png").absolutePath
    val detailsWithImage =
      PreviewDetails(
        testId = "test.id1",
        className = "TestClass",
        methodName = "testMethod1",
        previewName = "preview1",
        testResult = AndroidTestCaseResult.FAILED,
        srcImagePath = srcPath,
      )
    val panel =
      PreviewItemPanel(
        previewData = detailsWithImage,
        project = projectRule.project,
        showDetails = false,
        thumbnailLoader = directThumbnailLoader { mock() },
      )

    panel.showImageForView(ScreenshotViewType.NEW)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertNull("There should be no placeholder label when an image is shown", findLabel(panel))

    // 2. Re-use panel ("rubber stamp" simulation) for a second test without a diff image
    val detailsWithoutImage =
      PreviewDetails(
        testId = "test.id2",
        className = "TestClass",
        methodName = "testMethod2",
        previewName = "preview2",
        testResult = AndroidTestCaseResult.PASSED,
        diffImagePath = null,
      )

    // Switch the tab to Diff
    panel.updateData(detailsWithoutImage, ScreenshotViewType.DIFF)

    // Verify placeholder is present IMMEDIATELY without dispatching events
    val label = findLabel(panel)
    assertNotNull("Placeholder should be updated immediately upon panel reuse", label)
    assertEquals("No Difference", label?.text)
  }

  @Test
  fun verifyOnImageLoadedInvokedWhenPanelReused() = runInEdtAndWait {
    var callbackCount = 0
    val path1 = temporaryFolder.newFile("image1.png").absolutePath
    val path2 = temporaryFolder.newFile("image2.png").absolutePath

    val details1 =
      PreviewDetails(
        testId = "id1",
        className = "TestClass",
        methodName = "testMethod1",
        previewName = "preview1",
        testResult = AndroidTestCaseResult.PASSED,
        srcImagePath = path1,
      )

    val panel =
      PreviewItemPanel(
        previewData = details1,
        project = projectRule.project,
        thumbnailLoader = directThumbnailLoader { mock() },
      )

    // First request initiates loading
    panel.loadImage(path1, "id1") { callbackCount++ }

    // Simulate panel reuse before EDT invokeLater runs for path1:
    // Calling loadImage with path2 updates currentImagePath and currentTestId
    panel.loadImage(path2, "id2") { callbackCount++ }

    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals("Callbacks should be invoked for both requests to allow parent repaint", 2, callbackCount)
  }

  @Test
  fun verifyCorruptedImageFailsSafelyAndShowsError() = runInEdtAndWait {
    val corruptFile = temporaryFolder.newFile("corrupt.png")
    corruptFile.writeText("not a valid image content")

    val details =
      PreviewDetails(
        testId = "id",
        className = "TestClass",
        methodName = "testMethod",
        previewName = "preview",
        testResult = AndroidTestCaseResult.PASSED,
        srcImagePath = corruptFile.absolutePath,
      )
    val panel =
      PreviewItemPanel(
        previewData = details,
        project = projectRule.project,
        showDetails = false,
        thumbnailLoader = ThumbnailLoader(executor = MoreExecutors.newDirectExecutorService()),
      )

    var callbackInvoked = false
    panel.loadImage(corruptFile.absolutePath, "id") { callbackInvoked = true }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertTrue("Callback should be invoked on failure to allow repaint", callbackInvoked)
    assertFalse("Corrupted image should fail to load", panel.isLoadedSuccessfully)
    val label = findLabel(panel)
    assertEquals("Couldn't load image", label?.text)
  }

  @Test
  fun verifyCachedImageIsAppliedSynchronouslyWithoutCallback() = runInEdtAndWait {
    val path = temporaryFolder.newFile("image.png").absolutePath
    val details = PreviewDetails(testId = "id1", className = "C", methodName = "m", previewName = "p", srcImagePath = path)
    var decodeCount = 0
    val loader =
      directThumbnailLoader {
        decodeCount++
        mock()
      }
    val firstPanel = PreviewItemPanel(details, projectRule.project, showDetails = false, thumbnailLoader = loader)
    firstPanel.loadImage(path, "id1")
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // A list renders every row with the same panel, so a cache hit must not ask the list to repaint
    // again, otherwise the list would repaint forever.
    var callbackCount = 0
    val secondPanel = PreviewItemPanel(details, projectRule.project, showDetails = false, thumbnailLoader = loader)
    secondPanel.loadImage(path, "id2") { callbackCount++ }

    assertTrue("Cached image should be shown synchronously", secondPanel.isLoadedSuccessfully)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(0, callbackCount)
    assertEquals(1, decodeCount)
  }

  @Test
  fun verifyFailedImageIsNotDecodedAgain() = runInEdtAndWait {
    val badPath = temporaryFolder.newFile("bad.png").absolutePath
    val goodPath = temporaryFolder.newFile("good.png").absolutePath
    val decodedPaths = mutableListOf<String>()
    val loader =
      directThumbnailLoader { path ->
        decodedPaths.add(path)
        if (path == goodPath) mock() else null
      }
    val badDetails =
      PreviewDetails(testId = "bad", className = "C", methodName = "m", previewName = "bad", srcImagePath = badPath)
    val goodDetails =
      PreviewDetails(testId = "good", className = "C", methodName = "m", previewName = "good", srcImagePath = goodPath)
    val panel = PreviewItemPanel(badDetails, projectRule.project, showDetails = false, thumbnailLoader = loader)

    var callbackCount = 0
    panel.updateData(badDetails, ScreenshotViewType.NEW) { callbackCount++ }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals("Couldn't load image", findLabel(panel)?.text)

    // Simulate the list painting other rows and then this row again, several times.
    repeat(3) {
      panel.updateData(goodDetails, ScreenshotViewType.NEW) { callbackCount++ }
      panel.updateData(badDetails, ScreenshotViewType.NEW) { callbackCount++ }
      assertEquals("Failure should be shown synchronously", "Couldn't load image", findLabel(panel)?.text)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    assertEquals(listOf(badPath, goodPath), decodedPaths)
    assertEquals("Only the two decodes should ask for a repaint", 2, callbackCount)
  }

  @Test
  fun verifyMatchLabelUsesDestImageExistsFlag() = runInEdtAndWait {
    // The reference image is on disk, but the panel relies on the flag rather than checking the disk.
    val refPath = temporaryFolder.newFile("ref.png").absolutePath
    val details =
      PreviewDetails(
        testId = "id",
        className = "C",
        methodName = "m",
        previewName = "p",
        testResult = AndroidTestCaseResult.FAILED,
        destImagePath = refPath,
      )
    val panel = PreviewItemPanel(details, projectRule.project)
    assertNotNull(findAllLabels(panel).find { it.text == "New" })
    assertNull(findAllLabels(panel).find { it.text == "Match: " })

    panel.updateData(details.copy(destImageExists = true), ScreenshotViewType.NEW)

    assertNull(findAllLabels(panel).find { it.text == "New" })
    assertNotNull(findAllLabels(panel).find { it.text == "Match: " })
  }

  @Test
  fun verifyReferenceViewUsesDestImageExistsFlag() = runInEdtAndWait {
    val refPath = temporaryFolder.newFile("ref.png").absolutePath
    val decodedPaths = mutableListOf<String>()
    val details =
      PreviewDetails(testId = "id", className = "C", methodName = "m", previewName = "p", destImagePath = refPath)
    val panel =
      PreviewItemPanel(
        details,
        projectRule.project,
        showDetails = false,
        thumbnailLoader =
          directThumbnailLoader {
            decodedPaths.add(it)
            mock()
          },
      )

    panel.showImageForView(ScreenshotViewType.REFERENCE)
    assertEquals("No Reference Image", findLabel(panel)?.text)
    assertTrue(decodedPaths.isEmpty())

    panel.updateData(details.copy(destImageExists = true), ScreenshotViewType.REFERENCE)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(listOf(refPath), decodedPaths)
    assertTrue(panel.isLoadedSuccessfully)
  }

  @Test
  fun verifyDiffViewUsesDiffImageExistsFlag() = runInEdtAndWait {
    val diffPath = temporaryFolder.newFile("diff.png").absolutePath
    val decodedPaths = mutableListOf<String>()
    val details =
      PreviewDetails(
        testId = "id",
        className = "C",
        methodName = "m",
        previewName = "p",
        testResult = AndroidTestCaseResult.FAILED,
        diffImagePath = diffPath,
      )
    val panel =
      PreviewItemPanel(
        details,
        projectRule.project,
        showDetails = false,
        thumbnailLoader =
          directThumbnailLoader {
            decodedPaths.add(it)
            mock()
          },
      )

    panel.showImageForView(ScreenshotViewType.DIFF)
    assertEquals("No Diff Image", findLabel(panel)?.text)
    assertTrue(decodedPaths.isEmpty())

    panel.updateData(details.copy(diffImageExists = true), ScreenshotViewType.DIFF)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(listOf(diffPath), decodedPaths)
    assertTrue(panel.isLoadedSuccessfully)
  }

  private fun directThumbnailLoader(decode: (String) -> JBImageIcon?) =
    ThumbnailLoader(executor = MoreExecutors.newDirectExecutorService(), decode = decode)

  private fun findLabel(container: Container): JBLabel? {
    for (component in container.components) {
      if (component is JBLabel) {
        return component
      }
      if (component is Container) {
        val label = findLabel(component)
        if (label != null) return label
      }
    }
    return null
  }

  private fun findAllLabels(container: Container): List<JBLabel> {
    val result = mutableListOf<JBLabel>()
    for (component in container.components) {
      if (component is JBLabel) {
        result.add(component)
      } else if (component is Container) {
        result.addAll(findAllLabels(component))
      }
    }
    return result
  }
}
