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

import com.intellij.ide.HelpTooltip
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JButton
import org.jetbrains.annotations.Nls

/** Square icon-only button used in extended controls dialogs. */
internal open class IconButton(icon: Icon, @Nls tooltip: String, action: () -> Unit) : JButton() {
  private val helpTooltip = HelpTooltip().setTitle(HtmlChunk.text(tooltip))
  var tooltipText: @Nls String? = tooltip
    private set

  init {
    this.icon = icon
    isOpaque = false
    // Use HelpTooltip directly instead of setToolTipText as a workaround for IJPL-261599.
    helpTooltip.installOn(this)
    putClientProperty("JButton.buttonType", "square")
    putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(3))
    addActionListener { action() }
  }

  final override fun setToolTipText(text: String?) {
    tooltipText = text
    helpTooltip.setTitle(text?.let(HtmlChunk::text))
    helpTooltip.installOn(this)
  }

  fun setToolTipText(html: HtmlChunk?) {
    tooltipText = html?.toString()?.let(StringUtil::unescapeXmlEntities)
    helpTooltip.setTitle(html)
    helpTooltip.installOn(this)
  }

  final override fun setIcon(defaultIcon: Icon?) {
    super.setIcon(defaultIcon)
    disabledIcon = defaultIcon?.let(IconLoader::getDisabledIcon)
  }

  final override fun updateUI() {
    super.updateUI()
    disabledIcon = icon?.let(IconLoader::getDisabledIcon)
  }

  final override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    return Dimension(size.height, size.height)
  }

  final override fun getMinimumSize(): Dimension = preferredSize
}
