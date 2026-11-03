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
package com.android.tools.idea.streaming.emulator.dialogs

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension

internal class BorderLayoutPanelWithPreferredSize(
  private val preferredWidth: Int,
  private val preferredHeight: Int,
  private val minimumWidth: Int = 0,
) : BorderLayoutPanel() {

  override fun getPreferredSize(): Dimension {
    return if (isPreferredSizeSet) super.getPreferredSize() else JBUI.size(preferredWidth, preferredHeight)
  }

  override fun getMinimumSize(): Dimension {
    val size = super.getMinimumSize()
    return if (isMinimumSizeSet || minimumWidth <= 0) {
      size
    } else {
      Dimension(size.width.coerceAtLeast(JBUIScale.scale(minimumWidth)), size.height)
    }
  }
}
