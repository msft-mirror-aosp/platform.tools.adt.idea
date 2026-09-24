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
package com.android.tools.idea.profilers.leakcanary

import com.android.tools.idea.gemini.GeminiPluginApiV2
import com.android.tools.idea.gemini.LlmModelSlot
import com.android.tools.idea.testing.ui.createFakeToolWindow
import com.android.tools.leakcanarylib.data.Leak
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

class LeakCanaryAiHandlerTest {

  @get:Rule val projectRule = ProjectRule()
  @get:Rule val edtRule = EdtRule()

  private val project: Project
    get() = projectRule.project


  @RunsInEdt
  @Test
  fun `test analyzeLeakWithStudioBot sends query to Gemini`() {
    runBlocking {
      val mockGeminiApiV2 = mock(GeminiPluginApiV2::class.java)
      ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(mockGeminiApiV2), projectRule.project)
      whenever(mockGeminiApiV2.isAvailable()).thenReturn(true)

      val rawTrace = "Test Trace"
      val leak = mock(Leak::class.java)

      LeakCanaryAiHandler.getInstance(project).analyzeLeakWithStudioBot(rawTrace, leak)
      val queryCaptor = argumentCaptor<String>()

      PlatformTestUtil.waitWithEventsDispatching(
        "Query was not submitted in time",
        { Mockito.mockingDetails(mockGeminiApiV2).invocations.any { it.method.name == "submitQueryInToolWindow" } },
        10,
      )

      verify(mockGeminiApiV2).submitQueryInToolWindow(eq(project), queryCaptor.capture(), any(), any(), any())

      val submittedQuery = queryCaptor.firstValue
      assertTrue(submittedQuery.contains("Fix this memory leak and summarize the outcome:"))
      assertTrue(submittedQuery.contains(rawTrace))
    }
  }

  @Test
  fun `test fetchLeakInsight calls GeminiPluginApiV2 generate`() {
    runBlocking {
      val mockGeminiApiV2 = mock(GeminiPluginApiV2::class.java)
      ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(mockGeminiApiV2), projectRule.project)

      whenever(mockGeminiApiV2.isAvailable()).thenReturn(true)
      whenever(mockGeminiApiV2.generate(eq(project), any(), eq(LlmModelSlot.THINKING)))
        .thenReturn("Insight chunk 1 chunk 2")

      val result = LeakCanaryAiHandler.fetchLeakInsight(project, "raw trace").toList()
      assertEquals(listOf("Insight chunk 1 chunk 2"), result)
    }
  }

  @Test
  fun `test fetchLeakInsight throws when Gemini returns empty response`() {
    runBlocking {
      val mockGeminiApiV2 = mock(GeminiPluginApiV2::class.java)
      ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(mockGeminiApiV2), projectRule.project)

      whenever(mockGeminiApiV2.isAvailable()).thenReturn(true)
      whenever(mockGeminiApiV2.generate(eq(project), any(), eq(LlmModelSlot.THINKING)))
        .thenReturn(null)

      try {
        LeakCanaryAiHandler.fetchLeakInsight(project, "raw trace").toList()
        org.junit.Assert.fail("Expected IllegalStateException")
      } catch (e: IllegalStateException) {
        assertEquals("Failed to generate insight: No response received.", e.message)
      }
    }
  }

  @RunsInEdt
  @Test
  fun `test analyzeLeakWithStudioBot triggers onboarding when Gemini V2 is unavailable`() {
    runBlocking {
      val mockGeminiApiV2 = mock(GeminiPluginApiV2::class.java)
      ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(mockGeminiApiV2), projectRule.project)
      whenever(mockGeminiApiV2.isAvailable()).thenReturn(false)
      val toolWindow = createFakeToolWindow(project, project, "Gemini")

      assertFalse(toolWindow.isActive)
      LeakCanaryAiHandler.getInstance(project).analyzeLeakWithStudioBot("Test Trace", null)

      PlatformTestUtil.waitWithEventsDispatching(
        "Gemini tool window was not activated in time",
        { toolWindow.isActive },
        10,
      )
      assertTrue(toolWindow.isActive)
      verify(mockGeminiApiV2, never()).submitQueryInToolWindow(any(), any(), any(), any(), any())
    }
  }
}
