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
package com.android.tools.idea.profilers

import com.android.tools.idea.gemini.GeminiPluginApiV2
import com.android.tools.idea.testing.ui.createFakeToolWindow
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@RunsInEdt
class ProfilerAiUtilsTest {

  @get:Rule val projectRule = ProjectRule()
  @get:Rule val edtRule = EdtRule()

  private val project: Project
    get() = projectRule.project

  @Test
  fun `test isAiAvailable reflects GeminiPluginApiV2 availability`() {
    val mockGeminiApiV2 = mock(GeminiPluginApiV2::class.java)
    ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(mockGeminiApiV2), projectRule.project)

    whenever(mockGeminiApiV2.isAvailable()).thenReturn(true)
    assertTrue(ProfilerAiUtils.isAiAvailable())

    whenever(mockGeminiApiV2.isAvailable()).thenReturn(false)
    assertFalse(ProfilerAiUtils.isAiAvailable())
  }

  @Test
  fun `test showAiOnboarding activates Gemini tool window`() {
    val toolWindow = createFakeToolWindow(project, project, "Gemini")

    assertFalse(toolWindow.isActive)
    ProfilerAiUtils.showAiOnboarding(project)

    PlatformTestUtil.waitWithEventsDispatching(
      "Gemini tool window was not activated in time",
      { toolWindow.isActive },
      10,
    )
    assertTrue(toolWindow.isActive)
  }

  @Test
  fun `test showAiOnboarding activates StudioBot tool window if Gemini not found`() {
    val toolWindow = createFakeToolWindow(project, project, "StudioBot")

    assertFalse(toolWindow.isActive)
    ProfilerAiUtils.showAiOnboarding(project)

    PlatformTestUtil.waitWithEventsDispatching(
      "StudioBot tool window was not activated in time",
      { toolWindow.isActive },
      10,
    )
    assertTrue(toolWindow.isActive)
  }
}
