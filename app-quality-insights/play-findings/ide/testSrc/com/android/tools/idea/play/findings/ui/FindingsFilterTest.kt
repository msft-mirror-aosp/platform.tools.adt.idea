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
package com.android.tools.idea.play.findings.ui

import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.FindingType
import com.android.tools.idea.insights.model.common.WithCount
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FindingsFilterTest {

  private val finding1 =
    AppFinding.Supported(
      name = "finding-1",
      type = FindingType.EDGE_TO_EDGE_COMPAT,
      severity = FindingSeverity.WARNING,
      findingData = FindingData.Empty,
    )
  private val finding2 =
    AppFinding.Supported(
      name = "finding-2",
      type = FindingType.EDGE_TO_EDGE_COMPAT,
      severity = FindingSeverity.SEVERE,
      findingData = FindingData.Empty,
    )
  private val finding3 =
    AppFinding.Supported(
      name = "finding-3",
      type = FindingType.DRM_APP_COMPAT,
      severity = FindingSeverity.SEVERE,
      findingData = FindingData.Empty,
    )

  @Test
  fun `apply computes counts and selects all initially`() {
    val filter = FindingsFilter.MultiSelect { category }
    val filterGroup = FindingsFilterGroup(filter)

    val filtered = filterGroup.apply(listOf(finding1, finding2, finding3))

    val expectedItems =
      listOf(
        WithCount(2L, FindingCategory.USER_EXPERIENCE),
        WithCount(1L, FindingCategory.TECHNICAL_QUALITY),
      )
    assertThat(filtered).containsExactly(finding1, finding2, finding3).inOrder()
    assertThat(filter.selectionFlow.value.items).containsExactlyElementsIn(expectedItems).inOrder()
    assertThat(filter.selectionFlow.value.selected).containsExactlyElementsIn(expectedItems)
  }

  @Test
  fun `select updates selection, filters findings, and invokes callback`() {
    var callbackCount = 0
    val filter = FindingsFilter.MultiSelect(onFilterUpdated = { callbackCount++ }) { category }
    val filterGroup = FindingsFilterGroup(filter)
    val findings = listOf(finding1, finding2, finding3)

    filterGroup.apply(findings)
    filter.select(setOf(FindingCategory.TECHNICAL_QUALITY))

    assertThat(callbackCount).isEqualTo(1)
    assertThat(filterGroup.apply(findings)).containsExactly(finding3)
  }

  @Test
  fun `apply preserves partial selection when counts change`() {
    val filter = FindingsFilter.MultiSelect { category }
    val filterGroup = FindingsFilterGroup(filter)

    filterGroup.apply(listOf(finding1, finding2, finding3))
    filter.select(setOf(FindingCategory.TECHNICAL_QUALITY))

    // Refresh with updated counts -> only TECHNICAL_QUALITY remains selected with its updated count
    val extraFinding =
      AppFinding.Supported(
        name = "finding-4",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.INFO,
        findingData = FindingData.Empty,
      )
    filterGroup.apply(listOf(finding1, finding3, extraFinding))

    assertThat(filter.selectionFlow.value.selected).containsExactly(WithCount(2L, FindingCategory.TECHNICAL_QUALITY))
  }

  @Test
  fun `resetAll and re-selecting all options restore unfiltered state`() {
    val filter = FindingsFilter.MultiSelect { severity }
    val filterGroup = FindingsFilterGroup(filter)

    filterGroup.apply(listOf(finding1, finding2))
    filter.select(setOf(FindingSeverity.SEVERE))
    assertThat(filter.selectionFlow.value.allSelected()).isFalse()

    // Re-selecting all current options restores allSelected() so new options on refresh are included
    filter.select(setOf(FindingSeverity.WARNING, FindingSeverity.SEVERE))
    assertThat(filter.selectionFlow.value.allSelected()).isTrue()
    val infoFinding =
      AppFinding.Supported(
        name = "finding-info",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.INFO,
        findingData = FindingData.Empty,
      )
    filterGroup.apply(listOf(finding1, finding2, infoFinding))
    assertThat(filter.selectionFlow.value.allSelected()).isTrue()

    filter.select(setOf(FindingSeverity.SEVERE))
    assertThat(filter.selectionFlow.value.allSelected()).isFalse()
    filterGroup.resetAll()
    assertThat(filter.selectionFlow.value.allSelected()).isTrue()
  }

  @Test
  fun `apply applies multiple filters and updates cross-filter counts`() {
    val categoryFilter = FindingsFilter.MultiSelect { category }
    val severityFilter = FindingsFilter.MultiSelect { severity }
    val filterGroup = FindingsFilterGroup(categoryFilter, severityFilter)

    val findings = listOf(finding1, finding2, finding3)
    assertThat(filterGroup.apply(findings)).containsExactly(finding1, finding2, finding3).inOrder()

    // Select USER_EXPERIENCE (finding1, finding2)
    categoryFilter.select(setOf(FindingCategory.USER_EXPERIENCE))
    assertThat(filterGroup.apply(findings)).containsExactly(finding1, finding2).inOrder()
    // Severity counts reflect USER_EXPERIENCE: WARNING has 1 (finding1), SEVERE has 1 (finding2)
    assertThat(severityFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(1L, FindingSeverity.WARNING),
        WithCount(1L, FindingSeverity.SEVERE),
      )
      .inOrder()

    // Narrow severity to WARNING -> only finding1 matches
    severityFilter.select(setOf(FindingSeverity.WARNING))
    assertThat(filterGroup.apply(findings)).containsExactly(finding1)
    // Category counts reflect WARNING: USER_EXPERIENCE has 1 (finding1), TECHNICAL_QUALITY has 0
    assertThat(categoryFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(1L, FindingCategory.USER_EXPERIENCE),
        WithCount(0L, FindingCategory.TECHNICAL_QUALITY),
      )
      .inOrder()

    // Deselecting all categories -> no findings match, severity counts become 0 while category counts still reflect WARNING
    categoryFilter.select(emptySet())
    assertThat(filterGroup.apply(findings)).isEmpty()
    assertThat(severityFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(0L, FindingSeverity.WARNING),
        WithCount(0L, FindingSeverity.SEVERE),
      )
      .inOrder()
    assertThat(categoryFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(1L, FindingCategory.USER_EXPERIENCE),
        WithCount(0L, FindingCategory.TECHNICAL_QUALITY),
      )
      .inOrder()

    // Resetting selections restores full counts
    filterGroup.resetAll()
    assertThat(filterGroup.apply(findings)).containsExactly(finding1, finding2, finding3).inOrder()
    assertThat(categoryFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(2L, FindingCategory.USER_EXPERIENCE),
        WithCount(1L, FindingCategory.TECHNICAL_QUALITY),
      )
      .inOrder()
  }

  @Test
  fun `toggle filter selects all items initially, toggles individual items, and updates cross-filter counts`() {
    var callbackCount = 0
    val categoryFilter = FindingsFilter.MultiSelect { category }
    val severityFilter = FindingsFilter.Toggle(FindingSeverity.entries, onFilterUpdated = { callbackCount++ }) { severity }
    val filterGroup = FindingsFilterGroup(categoryFilter, severityFilter)
    val findings = listOf(finding1, finding2, finding3)

    assertThat(severityFilter.selectionFlow.value).containsExactlyElementsIn(FindingSeverity.entries)
    assertThat(filterGroup.apply(findings)).containsExactly(finding1, finding2, finding3).inOrder()

    severityFilter.toggle(FindingSeverity.WARNING)
    assertThat(callbackCount).isEqualTo(1)
    assertThat(severityFilter.selectionFlow.value).doesNotContain(FindingSeverity.WARNING)
    assertThat(filterGroup.apply(findings)).containsExactly(finding2, finding3).inOrder()
    assertThat(categoryFilter.selectionFlow.value.items)
      .containsExactly(
        WithCount(1L, FindingCategory.USER_EXPERIENCE),
        WithCount(1L, FindingCategory.TECHNICAL_QUALITY),
      )
      .inOrder()

    severityFilter.toggle(FindingSeverity.WARNING)
    assertThat(callbackCount).isEqualTo(2)
    assertThat(filterGroup.apply(findings)).containsExactly(finding1, finding2, finding3).inOrder()

    severityFilter.toggle(FindingSeverity.SEVERE)
    assertThat(callbackCount).isEqualTo(3)
    filterGroup.resetAll()
    assertThat(severityFilter.selectionFlow.value).containsExactlyElementsIn(FindingSeverity.entries)
  }
}
