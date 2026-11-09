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
package com.android.tools.profilers.taskbased.tabs.task.leakcanary.banner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.profilers.leakcanary.MIN_STUDIO_LEAKCANARY_VERSION
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OutdatedLibraryBannerTest {
  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @Test
  fun `banner shows the message, the link and the close button`() {
    var openBuildFileClicks = 0
    var closeClicks = 0

    composeTestRule.setContent {
      OutdatedLibraryBanner(
        foundVersion = "0.0.1",
        showOpenBuildFile = true,
        onOpenBuildFileClick = { openBuildFileClicks++ },
        onBannerClose = { closeClicks++ },
      )
    }

    composeTestRule
      .onNodeWithText(TaskBasedUxStrings.getLeakCanaryOutdatedLibraryMessage("0.0.1", MIN_STUDIO_LEAKCANARY_VERSION))
      .assertIsDisplayed()
    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_OPEN_BUILD_FILE).performClick()
    assertEquals(1, openBuildFileClicks)
    composeTestRule.onNodeWithContentDescription("Close").performClick()
    assertEquals(1, closeClicks)
    // A needed update must not be hidden forever.
    composeTestRule.onNodeWithText(TaskBasedUxStrings.DONT_SHOW_AGAIN_TITLE).assertDoesNotExist()
  }

  @Test
  fun `banner has no link without a build file`() {
    composeTestRule.setContent {
      OutdatedLibraryBanner(foundVersion = "0.0.1", showOpenBuildFile = false, onOpenBuildFileClick = {}, onBannerClose = {})
    }

    composeTestRule
      .onNodeWithText(TaskBasedUxStrings.getLeakCanaryOutdatedLibraryMessage("0.0.1", MIN_STUDIO_LEAKCANARY_VERSION))
      .assertIsDisplayed()
    composeTestRule.onNodeWithText(TaskBasedUxStrings.LEAKCANARY_OPEN_BUILD_FILE).assertDoesNotExist()
  }
}
