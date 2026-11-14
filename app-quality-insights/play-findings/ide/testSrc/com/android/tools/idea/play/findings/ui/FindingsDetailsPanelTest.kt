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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.android.tools.idea.findings.model.DeviceInfo
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingContext
import com.android.tools.idea.findings.model.FindingOccurrence
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.StackTrace
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.replaceService
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class FindingsDetailsPanelTest {

  private val projectRule = AndroidProjectRule.inMemory()
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
    AppFinding(
      name = "finding-drm",
      category = FindingCategory.TECHNICAL_QUALITY,
      severity = FindingSeverity.WARNING,
      displayName = "Bad version of your app protection service",
      description = "Sample DRM compatibility description.",
      affectedScopes = listOf(AffectedScope.Artifact("101")),
    )

  @Test
  fun testUpdateFindingDisplaysDetails() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    panel.updateFinding(sampleFinding)

    composeTestRule.onNodeWithTag("finding_summary_section", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_occurrences_section", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertTextEquals(sampleFinding.displayName)
    composeTestRule.onNodeWithContentDescription(sampleFinding.severity.name, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("ask_ai_button", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Ask AI", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_description", useUnmergedTree = true).assertTextEquals(sampleFinding.description)
  }

  @Test
  fun testAskAiButtonInvokesCallback() {
    var clickedFinding: AppFinding? = null
    val panel =
      FindingsDetailsPanel(
        parentDisposable = disposableRule.disposable,
        project = projectRule.project,
        onAskAiClick = { clickedFinding = it },
      )
    composeTestRule.setContent { Box(Modifier.size(600.dp, 200.dp)) { panel.FindingsDetailsContent() } }

    panel.updateFinding(sampleFinding)
    composeTestRule.waitForIdle()

    val titleBounds = composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).getUnclippedBoundsInRoot()
    val descriptionBounds = composeTestRule.onNodeWithTag("finding_description", useUnmergedTree = true).getUnclippedBoundsInRoot()
    val askAiBounds = composeTestRule.onNodeWithTag("ask_ai_button").getUnclippedBoundsInRoot()
    assertThat(askAiBounds.top).isAtLeast(titleBounds.bottom)
    assertThat(askAiBounds.top).isEqualTo(descriptionBounds.top)
    assertThat(askAiBounds.left).isAtLeast(descriptionBounds.right)

    composeTestRule.onNodeWithTag("ask_ai_button").performClick()

    assertThat(clickedFinding).isEqualTo(sampleFinding)
  }

  @Test
  fun testUpdateFindingWithNullClearsDetails() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    panel.updateFinding(sampleFinding)
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()

    panel.updateFinding(null)

    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("empty_details_text", useUnmergedTree = true).assertTextEquals("Select a finding to view details.")
  }

  @Test
  fun testSwitchingFindingResetsScrollPosition() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { Box(Modifier.size(450.dp, 150.dp)) { panel.FindingsDetailsContent() } }

    val longFinding =
      AppFinding(
        name = "finding-long",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Edge-to-edge display not supported",
        description = (1..30).joinToString("\n") { "Line $it of long finding description" },
      )

    panel.updateFinding(longFinding)
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()

    composeTestRule.onNodeWithTag("finding_summary_section", useUnmergedTree = true).performTouchInput { swipeUp() }
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
      AppFinding(
        name = "finding-drm",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.WARNING,
        displayName = "Bad version of your app protection service",
      )

    panel.updateFinding(findingWithoutScreenshot)

    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testFindingWithScreenshotLoadsAndDisplaysImage() {
    replaceAssetLoader { mockImage }
    val fileEditorManager: FileEditorManagerEx = mock()
    whenever(fileEditorManager.openFiles).thenReturn(emptyArray())
    whenever(fileEditorManager.openFile(any(), eq(true))).thenReturn(emptyArray<FileEditor>())
    projectRule.project.registerOrReplaceServiceInstance(
      FileEditorManager::class.java,
      fileEditorManager,
      disposableRule.disposable,
    )
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithScreenshot =
      AppFinding(
        name = "applications/com.example.app/findings/10",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(
                        url = "https://example.com/screenshot.png",
                        label = "Step 1: Initial state",
                      )
                    )
                )
              )
          ),
      )

    panel.updateFinding(findingWithScreenshot)

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertTextEquals("Step 1: Initial state")
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }

    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      val fileCaptor = argumentCaptor<VirtualFile>()
      verify(fileEditorManager).openFile(fileCaptor.capture(), eq(true))
      assertThat(fileCaptor.firstValue.name).isEqualTo("finding_10_screenshot_1.png")
    }

    // With multiple occurrences, the opened editor filename includes the 1-based occurrence index.
    val multiOccurrenceScreenshotFinding =
      findingWithScreenshot.copy(
        name = "applications/com.example.app/findings/20",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(visualEvidences = listOf(VisualEvidence("https://example.com/occ1.png"))),
                FindingOccurrence(visualEvidences = listOf(VisualEvidence("https://example.com/occ2.png"))),
              )
          ),
      )
    panel.updateFinding(multiOccurrenceScreenshotFinding)
    composeTestRule.onNodeWithTag("next_occurrence_button").performClick()
    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      val fileCaptor = argumentCaptor<VirtualFile>()
      verify(fileEditorManager, times(2)).openFile(fileCaptor.capture(), eq(true))
      assertThat(fileCaptor.secondValue.name).isEqualTo("finding_20_occurrence_2_screenshot_1.png")
    }
  }

  @Test
  fun testFindingWithScreenshotFailureShowsErrorMessage() {
    replaceAssetLoader { throw RuntimeException("Network error") }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val findingWithScreenshot =
      AppFinding(
        name = "finding-large-screen",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
        context =
          FindingContext(
            occurrences = listOf(FindingOccurrence(visualEvidences = listOf(VisualEvidence("https://example.com/broken-screenshot.png"))))
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
      AppFinding(
        name = "finding-large-screen",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
        context =
          FindingContext(
            occurrences = listOf(FindingOccurrence(visualEvidences = listOf(VisualEvidence("https://example.com/screenshot.png"))))
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
  fun testFindingWithStackTrace_showsStackTraceAndFitsRemainingViewport() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { Box(Modifier.size(500.dp, 600.dp)) { panel.FindingsDetailsContent() } }
    val sampleStackTrace =
      """
      java.lang.NullPointerException: Attempt to invoke virtual method on a null object
      	at com.example.app.ui.detail.DetailFragment.onViewCreated(DetailFragment.kt:74)
      """
        .trimIndent()

    val crashFinding =
      AppFinding(
        name = "finding-large-screen-crash",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context = FindingContext(occurrences = listOf(FindingOccurrence(stackTrace = StackTrace(sampleStackTrace)))),
      )

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim()).isEqualTo(sampleStackTrace)

    // In a viewport tall enough for summary + occurrence chrome + MIN_STACK_TRACE_HEIGHT, there is no phantom outer scroll overflow.
    val titleTopBeforeSwipe = composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).getUnclippedBoundsInRoot().top
    composeTestRule.onNodeWithTag("finding_summary_section", useUnmergedTree = true).performTouchInput { swipeUp() }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).assertIsDisplayed()
    assertThat(composeTestRule.onNodeWithTag("finding_title", useUnmergedTree = true).getUnclippedBoundsInRoot().top)
      .isEqualTo(titleTopBeforeSwipe)
  }

  @Test
  fun testFindingWithoutStackTrace_hidesStackTraceSection() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val crashFinding =
      AppFinding(
        name = "finding-log-only",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
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
      AppFinding(
        name = "finding-crash",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context =
          FindingContext(occurrences = listOf(FindingOccurrence(stackTrace = StackTrace("java.lang.IllegalStateException: failed")))),
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
      AppFinding(
        name = "finding-crash-wrap",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context = FindingContext(occurrences = listOf(FindingOccurrence(stackTrace = StackTrace("java.lang.NullPointerException")))),
      )

    panel.updateFinding(crashFinding)
    composeTestRule.waitForIdle()

    assertThat(panel.stackTraceConsoleView.editor!!.settings.isUseSoftWraps).isFalse()
    composeTestRule.onNodeWithTag("finding_stack_trace_soft_wrap", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    assertThat(panel.stackTraceConsoleView.editor!!.settings.isUseSoftWraps).isTrue()
  }

  @Test
  fun testFindingWithBothStackTraceAndScreenshot_switchesAndPreservesSubTabsAcrossOccurrences() {
    replaceAssetLoader { mockImage }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val finding =
      AppFinding(
        name = "finding-crash-and-screenshot",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences = listOf(VisualEvidence("https://example.com/screenshot-1.png", label = "Occurrence 1 screenshot")),
                  stackTrace = StackTrace("java.lang.NullPointerException: crash 1"),
                ),
                FindingOccurrence(
                  visualEvidences = listOf(VisualEvidence("https://example.com/screenshot-2.png", label = "Occurrence 2 screenshot")),
                  stackTrace = StackTrace("java.lang.NullPointerException: crash 2"),
                ),
                FindingOccurrence(stackTrace = StackTrace("java.lang.NullPointerException: crash 3 only")),
              )
          ),
      )

    panel.updateFinding(finding)

    composeTestRule.onNodeWithTag("finding_sub_tab_strip", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Stack trace", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Screenshots", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()

    composeTestRule.onNodeWithText("Screenshots", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertTextEquals("Occurrence 1 screenshot")
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    }

    // Switching to occurrence 2 keeps the Screenshots sub-tab selected when available.
    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertTextEquals("Occurrence 2 screenshot")
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    }

    // Switching to occurrence 3 (which only has a stack trace) falls back to Stack trace.
    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim()).isEqualTo("java.lang.NullPointerException: crash 3 only")

    // When the first occurrence only has screenshots, navigating to a second occurrence that has both keeps Screenshots selected.
    val screenshotFirstFinding =
      finding.copy(
        name = "finding-screenshot-first-then-both",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences = listOf(VisualEvidence("https://example.com/screenshot-1.png", label = "Occurrence 1 screenshot"))
                ),
                FindingOccurrence(
                  visualEvidences = listOf(VisualEvidence("https://example.com/screenshot-2.png", label = "Occurrence 2 screenshot")),
                  stackTrace = StackTrace("java.lang.NullPointerException: crash 2"),
                ),
              )
          ),
      )
    panel.updateFinding(screenshotFirstFinding)
    composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertTextEquals("Occurrence 1 screenshot")
    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertTextEquals("Occurrence 2 screenshot")
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testMultipleOccurrences_displaysDeviceAndNavigatesBetweenOccurrences() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val multiOccurrenceFinding =
      AppFinding(
        name = "finding-multi-occurrence-crash",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "Google",
                      deviceModel = "Pixel Fold",
                      androidVersion = "14",
                      androidSdkVersion = 34,
                    ),
                  stackTrace = StackTrace("java.lang.IllegalStateException: Crash on Pixel Fold"),
                ),
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "Samsung",
                      deviceModel = "Galaxy Tab S9",
                      androidVersion = "15",
                      androidSdkVersion = 35,
                    ),
                  stackTrace = StackTrace("java.lang.NullPointerException: Crash on Galaxy Tab S9"),
                ),
              )
          ),
      )

    panel.updateFinding(multiOccurrenceFinding)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrences_header", useUnmergedTree = true).assertTextEquals("Observed occurrence")
    composeTestRule.onNodeWithTag("occurrence_counter", useUnmergedTree = true).assertTextEquals("1 of 2")
    composeTestRule.onNodeWithTag("previous_occurrence_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("next_occurrence_button").assertIsEnabled()
    composeTestRule.onNodeWithContentDescription("Affected Device", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Google Pixel Fold")
    composeTestRule.onNodeWithContentDescription("Affected API Level", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Android 14 (API 34)")
    composeTestRule.onNodeWithTag("finding_sub_tab_strip", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Stack trace", useUnmergedTree = true).assertIsDisplayed()
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim()).isEqualTo("java.lang.IllegalStateException: Crash on Pixel Fold")

    composeTestRule.onNodeWithTag("next_occurrence_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_counter", useUnmergedTree = true).assertTextEquals("2 of 2")
    composeTestRule.onNodeWithTag("previous_occurrence_button").assertIsEnabled()
    composeTestRule.onNodeWithTag("next_occurrence_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Samsung Galaxy Tab S9")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Android 15 (API 35)")
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim())
      .isEqualTo("java.lang.NullPointerException: Crash on Galaxy Tab S9")

    // Switching to another finding resets the selected occurrence index back to 1 of N.
    val secondFinding = multiOccurrenceFinding.copy(name = "finding-multi-occurrence-crash-2")
    panel.updateFinding(secondFinding)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("occurrence_counter", useUnmergedTree = true).assertTextEquals("1 of 2")
    composeTestRule.onNodeWithTag("previous_occurrence_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("next_occurrence_button").assertIsEnabled()

    panel.updateFinding(multiOccurrenceFinding)
    composeTestRule.onNodeWithTag("next_occurrence_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("previous_occurrence_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_counter", useUnmergedTree = true).assertTextEquals("1 of 2")
    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Google Pixel Fold")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Android 14 (API 34)")
    assertThat(panel.stackTraceConsoleView.editor!!.document.text.trim()).isEqualTo("java.lang.IllegalStateException: Crash on Pixel Fold")
  }

  @Test
  fun testOccurrenceMetadata_showsUnknownForMissingDeviceOrApiLevel() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val singleOccurrenceWithoutDevice =
      AppFinding(
        name = "finding-single-no-device",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context =
          FindingContext(
            occurrences = listOf(FindingOccurrence(deviceInfo = null, stackTrace = StackTrace("java.lang.RuntimeException: single")))
          ),
      )

    panel.updateFinding(singleOccurrenceWithoutDevice)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrences_header", useUnmergedTree = true).assertTextEquals("Observed occurrence")
    composeTestRule.onNodeWithTag("occurrence_counter", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Unknown")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Unknown")

    val finding =
      AppFinding(
        name = "finding-partial-device",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Large screen crash on configuration change",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "Google",
                      deviceModel = "Pixel Tablet",
                      androidVersion = "",
                      androidSdkVersion = null,
                    ),
                  stackTrace = StackTrace("java.lang.RuntimeException: device only"),
                ),
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "",
                      deviceModel = "",
                      androidVersion = "14",
                      androidSdkVersion = null,
                    ),
                  stackTrace = StackTrace("java.lang.RuntimeException: os only"),
                ),
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "google",
                      deviceModel = "Google Pixel Fold",
                      androidVersion = "",
                      androidSdkVersion = 34,
                    ),
                  stackTrace = StackTrace("java.lang.RuntimeException: api only and deduplicated brand"),
                ),
                FindingOccurrence(
                  deviceInfo = null,
                  stackTrace = StackTrace("java.lang.RuntimeException: no device info"),
                ),
              )
          ),
      )

    panel.updateFinding(finding)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Google Pixel Tablet")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Unknown")

    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Unknown")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Android 14")

    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Google Pixel Fold")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("API 34")

    composeTestRule.onNodeWithTag("next_occurrence_button", useUnmergedTree = true).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Unknown")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Unknown")
  }

  @Test
  fun testDeviceOnlyOccurrence_displaysMetadataWithoutSubTabs() {
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val deviceOnlyFinding =
      AppFinding(
        name = "finding-device-only",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screen continuity issue",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  deviceInfo =
                    DeviceInfo(
                      deviceBrandName = "Google",
                      deviceModel = "Pixel Fold",
                      androidVersion = "14",
                      androidSdkVersion = 34,
                    )
                )
              )
          ),
      )

    panel.updateFinding(deviceOnlyFinding)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("finding_occurrences_section", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("occurrences_header", useUnmergedTree = true).assertTextEquals("Observed occurrence")
    composeTestRule.onNodeWithTag("occurrence_device_label", useUnmergedTree = true).assertTextEquals("Google Pixel Fold")
    composeTestRule.onNodeWithTag("occurrence_api_level_label", useUnmergedTree = true).assertTextEquals("Android 14 (API 34)")
    composeTestRule.onNodeWithTag("finding_sub_tab_strip", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("finding_stack_trace", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun testMultipleScreenshots_displaysStepLabelsAndHighlightsIssueStep() {
    replaceAssetLoader { mockImage }
    val panel = FindingsDetailsPanel(parentDisposable = disposableRule.disposable, project = projectRule.project)
    composeTestRule.setContent { panel.FindingsDetailsContent() }

    val layoutMultiCaptureFinding =
      AppFinding(
        name = "finding-layout-multi-capture",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Content is cut off on screen",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = "https://example.com/1.png"),
                      VisualEvidence(url = "https://example.com/2.png"),
                    )
                )
              )
          ),
      )

    panel.updateFinding(layoutMultiCaptureFinding)
    retryUntilPassing(5.seconds) {
      composeTestRule.onAllNodesWithTag("image_preview", useUnmergedTree = true).assertCountEquals(2)
      composeTestRule.onNodeWithTag("visual_evidence_label", useUnmergedTree = true).assertDoesNotExist()
      composeTestRule.onNodeWithTag("highlighted_visual_evidence", useUnmergedTree = true).assertDoesNotExist()
    }

    val continuityStepFinding =
      layoutMultiCaptureFinding.copy(
        name = "finding-continuity-steps",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = "https://example.com/1.png", label = "Step 1: Initial state"),
                      VisualEvidence(url = "https://example.com/2.png", label = "Step 2: Unfold (Issue detected)", highlighted = true),
                    )
                )
              )
          ),
      )

    panel.updateFinding(continuityStepFinding)
    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithText("Step 1: Initial state", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithText("Step 2: Unfold (Issue detected)", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("screenshots_horizontal_scrollbar", useUnmergedTree = true).assertExists()
    }
    // Only the step that shows the issue is highlighted.
    composeTestRule.onAllNodesWithTag("highlighted_visual_evidence", useUnmergedTree = true).assertCountEquals(1)
  }
}
