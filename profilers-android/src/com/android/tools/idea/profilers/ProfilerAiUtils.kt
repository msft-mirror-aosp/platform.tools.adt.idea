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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Common AI and Gemini utilities for Android Studio Profilers.
 */
object ProfilerAiUtils {
  private const val GEMINI_TOOL_WINDOW_ID = "Gemini"
  private const val STUDIO_BOT_TOOL_WINDOW_ID = "StudioBot"

  private val logger = Logger.getInstance(ProfilerAiUtils::class.java)

  /** Checks if AI features (Gemini) are available and ready to use. */
  @JvmStatic
  fun isAiAvailable(): Boolean {
    return GeminiPluginApiV2.getInstance().isAvailable()
  }

  /**
   * Opens the AI assistant (Gemini / StudioBot) tool window to trigger the onboarding or sign-in flow.
   */
  @JvmStatic
  fun showAiOnboarding(project: Project) {
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      try {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow =
          toolWindowManager.getToolWindow(GEMINI_TOOL_WINDOW_ID)
            ?: toolWindowManager.getToolWindow(STUDIO_BOT_TOOL_WINDOW_ID)
        if (toolWindow != null) {
          toolWindow.activate(null)
        } else {
          logger.warn("Neither $GEMINI_TOOL_WINDOW_ID nor $STUDIO_BOT_TOOL_WINDOW_ID tool window was found.")
        }
      } catch (e: Exception) {
        logger.warn("Failed to open AI onboarding window", e)
      }
    }
  }
}
