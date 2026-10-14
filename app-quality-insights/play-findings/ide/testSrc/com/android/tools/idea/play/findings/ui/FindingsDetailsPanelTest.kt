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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.android.testutils.retryUntilPassing
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.FindingType
import com.android.tools.idea.findings.model.StackTrace
import com.android.tools.idea.findings.model.VisualEvidence
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class FindingsDetailsPanelTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule).around(composeTestRule)

  private val mockImage = createTestPngBytes()

  private fun replaceAssetLoader(fetcher: (String) -> ByteArray) {
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url -> LoadedAsset(fetcher(url)) },
      disposableRule.disposable,
    )
  }

  private val sampleFinding =
    AppFinding.Supported(
      name = "finding-drm",
      type = FindingType.DRM_APP_COMPAT,
      severity = FindingSeverity.WARNING,
      findingData =
        FindingData.DrmAppCompat(
          drmPackageName = "com.example.drm",
          drmDisplayName = "Example DRM",
          drmVersion = "1.0",
          recommendedDrmVersion = "2.0",
          learnMoreUrl = "https://example.com/drm",
        ),
      affectedScopes = listOf(AffectedScope.Artifact("101")),
    )

  @Test
  fun testUpdateFindingDisplaysDetails() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    panel.updateFinding(sampleFinding)

    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertTextEquals(sampleFinding.displayName)
    composeTestRule.onNodeWithContentDescription(sampleFinding.severity.name, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_category_badge", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText(sampleFinding.category.displayName, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_description", useUnmergedTree = true).assertTextEquals(sampleFinding.details)
  }

  @Test
  fun testUpdateFindingWithNullClearsDetails() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    panel.updateFinding(sampleFinding)
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()

    panel.updateFinding(null)

    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("finding_category_badge", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("empty_details_text", useUnmergedTree = true).assertTextEquals("Select a finding to view details.")
  }

  @Test
  fun testSwitchingFindingResetsScrollPosition() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { Box(Modifier.size(250.dp, 150.dp)) { panel.FindingsDetailsContent() } }

    val longFinding =
      AppFinding.Supported(
        name = "finding-long",
        type = FindingType.EDGE_TO_EDGE_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.EdgeToEdge(
            manualReviewMessage = (1..30).joinToString("\n") { "Line $it of long finding description" },
            versionCode = "101",
          ),
      )

    panel.updateFinding(longFinding)
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()

    composeTestRule.onNodeWithTag("finding_description", useUnmergedTree = true).performTouchInput { swipeUp() }
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsNotDisplayed()

    panel.updateFinding(sampleFinding)
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun testInitialState() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testFindingWithoutScreenshotHidesScreenshotSection() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithoutScreenshot =
      AppFinding.Supported(
        name = "finding-drm",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.example.drm",
            drmDisplayName = "Example DRM",
            drmVersion = "1.0",
            recommendedDrmVersion = "2.0",
            learnMoreUrl = null,
          ),
      )

    panel.updateFinding(findingWithoutScreenshot)

    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testFindingWithScreenshotLoadsAndDisplaysImage() {
    replaceAssetLoader { mockImage }
    val fileEditorManager: FileEditorManager = mock()
    whenever(fileEditorManager.openFiles).thenReturn(emptyArray())
    whenever(fileEditorManager.openFile(any(), eq(true))).thenReturn(emptyArray<FileEditor>())
    projectRule.project.replaceService(
      FileEditorManager::class.java,
      fileEditorManager,
      disposableRule.disposable,
    )
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithScreenshot =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/10",
        type = FindingType.LARGE_SCREENS_LAYOUT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.LargeScreens(
            layoutIssueType = "LETTERBOXING",
            visualEvidences = listOf(VisualEvidence("https://example.com/screenshot.png")),
          ),
      )

    panel.updateFinding(findingWithScreenshot)

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }

    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      val fileCaptor = argumentCaptor<VirtualFile>()
      verify(fileEditorManager).openFile(fileCaptor.capture(), eq(true))
      assertThat(fileCaptor.firstValue.name).isEqualTo("finding_10_screenshot_1.png")
    }
  }

  @Test
  fun testFindingWithScreenshotFailureShowsErrorMessage() {
    replaceAssetLoader { throw RuntimeException("Network error") }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithScreenshot =
      AppFinding.Supported(
        name = "finding-large-screen",
        type = FindingType.LARGE_SCREENS_LAYOUT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.LargeScreens(
            layoutIssueType = "LETTERBOXING",
            visualEvidences = listOf(VisualEvidence("https://example.com/broken-screenshot.png")),
          ),
      )

    panel.updateFinding(findingWithScreenshot)

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertDoesNotExist()
      composeTestRule.onNodeWithTag("image_status", useUnmergedTree = true).assertTextEquals("Unable to load preview.")
    }
  }

  @Test
  fun testUpdateFindingWithNullClearsScreenshotSection() {
    replaceAssetLoader { mockImage }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithScreenshot =
      AppFinding.Supported(
        name = "finding-large-screen",
        type = FindingType.LARGE_SCREENS_LAYOUT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.LargeScreens(
            layoutIssueType = "LETTERBOXING",
            visualEvidences = listOf(VisualEvidence("https://example.com/screenshot.png")),
          ),
      )

    panel.updateFinding(findingWithScreenshot)

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }

    panel.updateFinding(null)

    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testFindingWithStackTrace_showsStackTrace() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }
    val sampleStackTrace =
      """
      java.lang.NullPointerException: Attempt to invoke virtual method on a null object
      	at com.example.app.ui.detail.DetailFragment.onViewCreated(DetailFragment.kt:74)
      """
        .trimIndent()

    val crashFinding =
      AppFinding.Supported(
        name = "finding-large-screen-crash",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData = FindingData.LargeScreens(stackTrace = StackTrace(sampleStackTrace)),
      )

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim()).isEqualTo(sampleStackTrace)
  }

  @Test
  fun testFindingWithoutStackTrace_hidesStackTraceSection() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val crashFinding =
      AppFinding.Supported(
        name = "finding-log-only",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData = FindingData.LargeScreens(),
      )

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()

    // With no stack trace there is nothing to show, so the whole section stays hidden.
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testClearingFinding_clearsStackTrace() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val crashFinding =
      AppFinding.Supported(
        name = "finding-crash",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData = FindingData.LargeScreens(stackTrace = StackTrace("java.lang.IllegalStateException: failed")),
      )

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()

    panel.updateFinding(null)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    assertThat(panel.stackTraceConsoleView.editor!!.document.text).isEmpty()
  }

  @Test
  fun testToggleSoftWrapAction() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val crashFinding =
      AppFinding.Supported(
        name = "finding-crash-wrap",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData = FindingData.LargeScreens(stackTrace = StackTrace("java.lang.NullPointerException")),
      )

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()

    assertThat(panel.stackTraceConsoleView.editor!!.settings.isUseSoftWraps).isFalse()
    composeTestRule.onNodeWithTag("finding_stack_trace_soft_wrap", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    assertThat(panel.stackTraceConsoleView.editor!!.settings.isUseSoftWraps).isTrue()
  }

  @Test
  fun testFindingWithBothStackTraceAndScreenshot_switchesSubTabs() {
    replaceAssetLoader { mockImage }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val finding =
      AppFinding.Supported(
        name = "finding-crash-and-screenshot",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.LargeScreens(
            visualEvidences = listOf(VisualEvidence("https://example.com/screenshot.png")),
            stackTrace = StackTrace("java.lang.NullPointerException: crash"),
          ),
      )

    panel.updateFinding(finding)

    composeTestRule.onNodeWithTag("finding_sub_tab_strip", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Stack trace", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Visual evidence", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()

    composeTestRule.onNodeWithText("Visual evidence", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    }
  }
}
