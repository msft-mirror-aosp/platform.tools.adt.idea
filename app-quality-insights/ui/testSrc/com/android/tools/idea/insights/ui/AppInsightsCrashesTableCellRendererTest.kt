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

import com.android.tools.idea.insights.FakeInsightsProvider
import com.android.tools.idea.insights.model.issue.AppInsightsCrash
import com.android.tools.idea.insights.model.issue.FailureType
import com.android.tools.idea.insights.model.issue.IssueDetails
import com.android.tools.idea.insights.model.issue.IssueId
import com.android.tools.idea.insights.model.issue.SignalType
import com.android.tools.idea.testing.ui.flatten
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.table.JBTable
import icons.StudioIcons
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.table.TableColumn
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

@RunsInEdt
class AppInsightsCrashesTableCellRendererTest {

  @get:Rule val edtRule = EdtRule()

  @Test
  fun testIcons() {
    val table = JBTable()
    val detailsTemplate =
      IssueDetails(
        IssueId("1"),
        "Issue1",
        "com.google.crash.Crash",
        FailureType.FATAL,
        "Sample Event",
        "1.2.3",
        "1.2.3",
        12L,
        14L,
        5L,
        10L,
        emptySet(),
        "https://url.for-crash.com",
        0,
        emptyList(),
      )

    table.columnModel.addColumn(TableColumn(0).apply { width = 200 })
    fun getIcons(issue: AppInsightsCrash): Collection<Icon> {
      val renderer =
        AppInsightsCrashesTableCellRenderer.getTableCellRendererComponent(
          table,
          issue,
          selected = false,
          focused = false,
          viewRowIndex = 0,
          viewColumnIndex = 0,
        )
      return renderer.flatten().mapNotNull {
        when (it) {
          is JLabel -> it.icon
          is SimpleColoredComponent -> it.icon
          else -> null
        }
      }
    }
    assertThat(getIcons(AppInsightsCrash(detailsTemplate, mock(), FakeInsightsProvider())))
      .containsExactly(StudioIcons.AppQualityInsights.FATAL)

    assertThat(getIcons(AppInsightsCrash(detailsTemplate.copy(fatality = FailureType.NON_FATAL), mock(), FakeInsightsProvider())))
      .containsExactly(StudioIcons.AppQualityInsights.NON_FATAL)

    assertThat(getIcons(AppInsightsCrash(detailsTemplate.copy(notesCount = 4), mock(), FakeInsightsProvider())))
      .containsExactly(StudioIcons.AppQualityInsights.FATAL_WITH_NOTE)

    assertThat(
        getIcons(
          AppInsightsCrash(
            detailsTemplate.copy(signals = setOf(SignalType.SIGNAL_FRESH, SignalType.SIGNAL_REGRESSED)),
            mock(),
            FakeInsightsProvider(),
          )
        )
      )
      .containsExactly(
        StudioIcons.AppQualityInsights.FATAL,
        StudioIcons.AppQualityInsights.REGRESSED_SIGNAL,
        StudioIcons.AppQualityInsights.FRESH_SIGNAL,
      )
  }

  @Test
  fun testTooltipIsEscaped() {
    val table = JBTable()
    table.columnModel.addColumn(TableColumn(0).apply { width = 200 })
    // The title and subtitle are derived from the reported stack trace and must not be able to inject markup. See b/509617662.
    val details =
      IssueDetails(
        IssueId("1"),
        "<img src=x>",
        "com.google.crash.<b>Crash</b>",
        FailureType.FATAL,
        "Sample Event",
        "1.2.3",
        "1.2.3",
        12L,
        14L,
        5L,
        10L,
        emptySet(),
        "https://url.for-crash.com",
        0,
        emptyList(),
      )

    val renderer =
      AppInsightsCrashesTableCellRenderer.getTableCellRendererComponent(
        table,
        AppInsightsCrash(details, mock(), FakeInsightsProvider()),
        selected = false,
        focused = false,
        viewRowIndex = 0,
        viewColumnIndex = 0,
      )

    val toolTipText = (renderer as JComponent).toolTipText
    assertThat(toolTipText).doesNotContain("<img")
    assertThat(toolTipText).contains("&lt;img src=x&gt;")
    assertThat(toolTipText).contains("&lt;b&gt;Crash&lt;/b&gt;")
  }
}
