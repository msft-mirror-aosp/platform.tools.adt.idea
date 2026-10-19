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
package com.android.tools.idea.compose.preview.actions.glasses

import com.android.tools.idea.compose.preview.util.previewElement
import com.android.tools.idea.preview.BackgroundManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.preview.PreviewDisplaySettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/** * An [ToggleAction] to set the [GlassesBackground] in a [NlDesignSurface]. */
class SetGlassesBackgroundAction(val glassesBackground: GlassesBackground) : ToggleAction(glassesBackground.displayName, null, null) {

  override fun isSelected(e: AnActionEvent): Boolean {
    val previewElement = e.dataContext.previewElement() ?: return false
    val backgroundManager = BackgroundManager.getInstance(e.project ?: return false)
    val currentBackground = backgroundManager.getBackground(previewElement)

    // "No Background" is selected either when no background has been explicitly set in
    // BackgroundManager (the default state) or when it explicitly matches GlassesBackground.NONE.
    return if (glassesBackground == GlassesBackground.NONE) {
      currentBackground == null || currentBackground.image == glassesBackground.imageTransform
    } else {
      currentBackground?.image == glassesBackground.imageTransform
    }
  }

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val previewElement = e.dataContext.previewElement() ?: return
    val backgroundManager = BackgroundManager.getInstance(e.project ?: return)
    // When selecting "No Background" (or unselecting the current background), clear the entry
    // in BackgroundManager to null so standard window background rendering applies.
    val background =
      if (state && glassesBackground != GlassesBackground.NONE) {
        PreviewDisplaySettings.Background.Image(glassesBackground.imageTransform)
      } else {
        null
      }

    backgroundManager.setBackground(previewElement, background)
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
