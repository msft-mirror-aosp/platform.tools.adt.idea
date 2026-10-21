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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.FindingType
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@RunsInEdt
class FindingsDetailsPanelTest {

  private val applicationRule = ApplicationRule()
  private val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(applicationRule).around(EdtRule()).around(composeTestRule)

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
    val panel = FindingsDetailsPanel()
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
    val panel = FindingsDetailsPanel()
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
    val panel = FindingsDetailsPanel()
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
}
