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

package com.android.tools.idea.profilers.perfetto.ai

import com.android.tools.idea.gemini.GeminiPluginApiV2
import com.android.tools.idea.gemini.LlmChatInToolWindowResult
import com.android.tools.idea.gemini.LlmFailureReason
import com.android.tools.idea.project.AndroidNotification
import com.android.tools.sherlock.common.perfetto.ai.BasePerfettoAiService
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * AI-backed implementation of [BasePerfettoAiService]. This service uses [GeminiPluginApiV2] to send chat queries to the AI agent in
 * Android Studio.
 */
class GeminiPerfettoAiService(private val project: Project, private val scope: CoroutineScope) : BasePerfettoAiService() {

  companion object {
    private val LOG = Logger.getInstance(GeminiPerfettoAiService::class.java)
  }

  override fun isAvailable(): Boolean = !project.isDisposed && GeminiPluginApiV2.getInstance().isAvailable()

  /**
   * Helper method to send a query to the AI agent in the tool window for agent V2.
   *
   * @param prompt The prompt text to send to the model.
   * @param traceFilePath The path of the trace file to attach as context.
   */
  override fun submitPrompt(prompt: String, traceFilePath: String) {
    scope.launch {
      try {
        val api = GeminiPluginApiV2.getInstance()
        val traceFileRef = listOfNotNull(traceFilePath.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() })
        when (val result = api.submitQueryInToolWindow(project, prompt, fileReferences = traceFileRef)) {
          is LlmChatInToolWindowResult.Success -> {
            LOG.info("Successfully submitted query to AI agent.")
          }
          is LlmChatInToolWindowResult.RequestNotSubmitted -> {
            LOG.warn("Failed to submit query to AI tool window: ${result.reason}")
            showErrorNotification(getUserFriendlyErrorMessage(result.reason))
          }
        }
      } catch (c: CancellationException) {
        throw c
      } catch (e: Exception) {
        LOG.warn("Exception while submitting query to AI tool window", e)
        showErrorNotification("Failed to submit query: ${e.localizedMessage ?: "Unknown error"}")
      }
    }
  }

  private fun getUserFriendlyErrorMessage(reason: LlmFailureReason? = null): String {
    return when (reason) {
      LlmFailureReason.NO_MODELS_AVAILABLE -> "Please ensure a model is configured and available"
      else -> "Failed to submit query"
    }
  }

  private fun showErrorNotification(message: String) {
    AndroidNotification.getInstance(project).showBalloon("Request failed", message, NotificationType.ERROR)
  }
}
