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

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JButton
import org.jetbrains.annotations.Nls

/** Square icon-only button used for playback controls in extended controls dialogs. */
internal class IconButton(icon: Icon, @Nls tooltip: String, action: () -> Unit) : JButton() {
  init {
    this.icon = icon
    isOpaque = false
    setToolTipText(HtmlChunk.text(tooltip))
    putClientProperty("JButton.buttonType", "square")
    putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(3))
    addActionListener { action() }
  }

  override fun setIcon(defaultIcon: Icon?) {
    super.setIcon(defaultIcon)
    disabledIcon = defaultIcon?.let(IconLoader::getDisabledIcon)
  }

  override fun updateUI() {
    super.updateUI()
    disabledIcon = icon?.let(IconLoader::getDisabledIcon)
  }

  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    return Dimension(size.height, size.height)
  }

  override fun getMinimumSize(): Dimension = preferredSize
}
