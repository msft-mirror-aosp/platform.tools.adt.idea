/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.adtui.TabularLayout
import com.android.tools.adtui.util.disableHtml
import com.android.tools.idea.insights.model.issue.IssueStats
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import kotlin.math.roundToInt

class DistributionPanel : JPanel(TabularLayout("Fit,Fit,*,Fit")) {
  init {
    isOpaque = false
  }

  fun updateDistribution(stats: IssueStats<Double>, category: String) {
    val emptyBorder = JBUI.Borders.empty(4)
    if (stats.groups.isEmpty()) {
      add(JLabel("No data available").apply { border = emptyBorder }, TabularLayout.Constraint(stats.groups.size, 0, 4))
    } else {
      stats.groups.forEachIndexed { index, group ->
        // Group names and breakdown names are backend values derived from end user controlled data such as Build.BRAND, so they must not
        // be interpreted as HTML. The text is assigned after disableHtml because JLabel(text) installs its UI, and thus builds the
        // BasicHTML view, from within the constructor. See b/509617662.
        val nameLabel =
          JLabel().disableHtml().apply {
            text = group.groupName
            border = emptyBorder
          }
        add(nameLabel, TabularLayout.Constraint(index, 0))
        val percent = group.percentage.roundToInt()
        val percentLabel = JLabel("$percent%").apply { border = emptyBorder }
        add(percentLabel, TabularLayout.Constraint(index, 1))
        add(
          JProgressBar(0, 100).apply {
            value = percent
            isOpaque = false
            border = emptyBorder
          },
          TabularLayout.Constraint(index, 2),
        )
        val infoLabel = JLabel(StudioIcons.Common.INFO)
        add(infoLabel, TabularLayout.Constraint(index, 3))
        val tooltip = HelpTooltip()
        tooltip.setPlainTextTitle("${group.groupName} (${group.percentage.roundToInt()})")
        if (group.breakdown.isNotEmpty()) {
          tooltip.setDescription(
            HtmlBuilder()
              .appendWithSeparators(
                HtmlChunk.br(),
                group.breakdown.map { (name, value) -> HtmlChunk.text("${value.roundToInt()}% ($name)") },
              )
              .toFragment()
          )
        }
        tooltip.installOn(infoLabel)
      }
      add(
        JLabel("Most affected $category: ${stats.topValue}").apply { border = emptyBorder },
        TabularLayout.Constraint(stats.groups.size, 0, 4),
      )
    }
    revalidate()
  }
}
