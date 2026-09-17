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
package com.android.tools.idea.insights.ui

import com.android.tools.idea.insights.model.common.DataPoint
import com.android.tools.idea.insights.model.common.StatsGroup
import com.android.tools.idea.insights.model.issue.IssueStats
import com.android.tools.idea.testing.ui.flatten
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.HelpTooltip
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import javax.swing.JLabel
import org.junit.Rule
import org.junit.Test

/** Device names come from the backend and are derived from end user controlled data, so they must never be rendered as markup. */
@RunsInEdt
class DistributionPanelTest {

  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val edtRule = EdtRule()

  private val payload = "<html><img src=\"http://injected/x\">"

  @Test
  fun `group name is not rendered as html`() {
    val panel = DistributionPanel()

    panel.updateDistribution(IssueStats(topValue = "brand", groups = listOf(StatsGroup(payload, 100.0, emptyList()))), "device")

    val nameLabel = panel.flatten().filterIsInstance<JLabel>().single { it.text == payload }
    // A non-null "html" client property means Swing built a BasicHTML view, i.e. it parsed the payload as markup.
    assertThat(nameLabel.getClientProperty("html")).isNull()
  }

  @Test
  fun `breakdown name is escaped in the tooltip`() {
    val panel = DistributionPanel()

    panel.updateDistribution(
      IssueStats(topValue = "brand", groups = listOf(StatsGroup("brand", 100.0, listOf(DataPoint(payload, 100.0))))),
      "device",
    )

    val infoLabel = panel.flatten().filterIsInstance<JLabel>().single { HelpTooltip.getTooltipFor(it) != null }
    val description = HelpTooltip.getTooltipFor(infoLabel)!!.description

    assertThat(description).doesNotContain("<img")
    assertThat(description).contains("&lt;img")
  }
}
