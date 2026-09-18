/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.insights.ui

import com.android.tools.idea.insights.model.issue.IssueVariant
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Represents a row in the variants dropdown. */
sealed interface Row

/** A row containing variant information. */
data class VariantRow(val name: String, val eventCount: Long, val userCount: Long, val issueVariant: IssueVariant?) : Row

/** Shown when the dropdown has no variant information. */
data class DisabledTextRow(val text: String) : Row

/** Represents the header of the variants list. */
object HeaderRow : Row

private val labelFont: Font
  get() = UIUtil.getLabelFont()

class VariantComboBoxListCellRenderer : ListCellRenderer<Row> {
  // Components for rendering a VariantRow inside the dropdown list
  private val textLabel = JBLabel()
  private val eventCountLabel = JBLabel()
  private val userCountLabel = JBLabel()
  private val variantPanel =
    JPanel(HorizontalLayout(5)).apply {
      isOpaque = false
      add(textLabel, HorizontalLayout.LEFT)
      add(eventCountLabel, HorizontalLayout.RIGHT)
      add(userCountLabel, HorizontalLayout.RIGHT)
    }

  // Components for rendering the combo box title when a VariantRow is selected (index == -1)
  private val titleLabel = JBLabel("Variant: ")
  private val titleNameLabel =
    JBLabel().apply {
      border = JBUI.Borders.emptyRight(2)
    }
  private val titlePanel =
    JPanel(HorizontalLayout(5)).apply {
      add(titleLabel, HorizontalLayout.LEFT)
      add(titleNameLabel, HorizontalLayout.RIGHT)
      border = JBUI.Borders.empty()
    }

  // Component for rendering DisabledTextRow
  private val disabledTextLabel =
    JBLabel().apply {
      isEnabled = false
      isOpaque = false
    }

  // Component for rendering HeaderRow
  private val variantsHeaderLabel = JBLabel("Variants").apply { isOpaque = false }
  private val impactHeaderLabel =
    JBLabel("Impact").apply {
      isOpaque = false
      horizontalAlignment = SwingConstants.RIGHT
    }
  private val headerPanel =
    JPanel(BorderLayout()).apply {
      add(variantsHeaderLabel, BorderLayout.WEST)
      add(impactHeaderLabel, BorderLayout.EAST)
    }

  init {
    updateFonts()
  }

  fun updateUI() {
    SwingUtilities.updateComponentTreeUI(variantPanel)
    SwingUtilities.updateComponentTreeUI(titlePanel)
    SwingUtilities.updateComponentTreeUI(disabledTextLabel)
    SwingUtilities.updateComponentTreeUI(headerPanel)
    updateFonts()
  }

  private fun updateFonts() {
    val baseFont = labelFont
    textLabel.font = baseFont
    eventCountLabel.font = baseFont
    userCountLabel.font = baseFont
    disabledTextLabel.font = baseFont
    titleLabel.font = baseFont.deriveFont(JBFont.ITALIC)
    titleNameLabel.font = baseFont.deriveFont(JBFont.BOLD or JBFont.ITALIC)
    variantsHeaderLabel.font = baseFont.deriveFont(JBFont.BOLD)
    impactHeaderLabel.font = baseFont.deriveFont(JBFont.BOLD)
  }

  override fun getListCellRendererComponent(
    list: JList<out Row>?,
    value: Row?,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component {
    if (index == -1 && value is VariantRow) {
      titleLabel.font = labelFont.deriveFont(JBFont.ITALIC)
      titleNameLabel.font = labelFont.deriveFont(JBFont.BOLD or JBFont.ITALIC)
      titleNameLabel.text = value.name
      return titlePanel
    }

    return when (value) {
      is VariantRow -> configureVariantRow(value)
      is DisabledTextRow -> {
        disabledTextLabel.text = value.text
        disabledTextLabel
      }
      is HeaderRow -> headerPanel
      null -> disabledTextLabel.apply { text = "" }
    }
  }

  private fun configureVariantRow(row: VariantRow): Component {
    textLabel.update(if (row.issueVariant == null) row.name else "Variant ${row.name}", null)
    eventCountLabel.update(row.eventCount.formatNumberToPrettyString(), StudioIcons.AppQualityInsights.ISSUE)
    userCountLabel.update(row.userCount.formatNumberToPrettyString(), StudioIcons.LayoutEditor.Palette.QUICK_CONTACT_BADGE)
    variantPanel.invalidate()
    return variantPanel
  }

  private fun JBLabel.update(value: String, icon: Icon?) {
    this.icon = icon
    toolTipText = value
    text = value
    font = labelFont
  }
}
