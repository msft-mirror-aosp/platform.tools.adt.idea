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
import com.android.tools.idea.insights.MultiSelection
import com.android.tools.idea.insights.model.common.WithCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Client-side filter over [AppFinding]s. */
internal sealed interface FindingsFilter {
  /** Restores the filter selection back to its default state. */
  fun resetSelection()

  /** Returns `true` if [finding] satisfies this filter's active selection. */
  fun matches(finding: AppFinding): Boolean

  /** Refreshes this filter's available options and counts from [allFindings] and [matchingFindings]. */
  fun updateCounts(allFindings: List<AppFinding>, matchingFindings: List<AppFinding>) = Unit

  /**
   * Multi-select [FindingsFilter] whose [selectionFlow] emits a [MultiSelection] of dynamically discovered options with per-option counts
   * ([WithCount]).
   *
   * @param T The type of the [AppFinding] property being filtered on (e.g. category).
   * @param onFilterUpdated Callback invoked when the user updates the filter selection.
   * @param selector Returns the [AppFinding] property to filter and group counts by.
   */
  class MultiSelect<T>(
    private val onFilterUpdated: () -> Unit = {},
    private val selector: AppFinding.() -> T,
  ) : FindingsFilter {
    private val _selectionFlow = MutableStateFlow(MultiSelection.emptySelection<WithCount<T>>())

    /** Reactive selection state consumed by the toolbar dropdown action. */
    val selectionFlow: StateFlow<MultiSelection<WithCount<T>>> = _selectionFlow.asStateFlow()

    override fun resetSelection() {
      _selectionFlow.value = MultiSelection.emptySelection()
    }

    override fun matches(finding: AppFinding): Boolean = _selectionFlow.value.isSelected(finding.selector())

    /** Updates the selected filter values to [values] and notifies [onFilterUpdated]. */
    fun select(values: Set<T>) {
      _selectionFlow.update { it.selectMatching { item -> item.value in values } }
      onFilterUpdated()
    }

    override fun updateCounts(allFindings: List<AppFinding>, matchingFindings: List<AppFinding>) {
      val counts = matchingFindings.groupingBy(selector).eachCount()
      val items = allFindings.map(selector).distinct().map { WithCount(counts[it]?.toLong() ?: 0L, it) }
      _selectionFlow.value =
        MultiSelection(
          selected = items.filterTo(mutableSetOf()) { _selectionFlow.value.isSelected(it.value) },
          items = items,
        )
    }

    private fun MultiSelection<WithCount<T>>.isSelected(value: T): Boolean = allSelected() || selected.any { it.value == value }
  }

  /**
   * Toggle-based [FindingsFilter] over a fixed list of [items] (e.g. toolbar toggle buttons for severity), where all [items] are selected
   * by default.
   *
   * @param T The type of the [AppFinding] property being filtered on (e.g. severity).
   * @param items Fixed list of filter options in display order.
   * @param onFilterUpdated Callback invoked when the user updates the filter selection.
   * @param selector Returns the [AppFinding] property to filter by.
   */
  class Toggle<T>(
    val items: List<T>,
    private val onFilterUpdated: () -> Unit = {},
    private val selector: AppFinding.() -> T,
  ) : FindingsFilter {
    private val _selectionFlow = MutableStateFlow(items.toSet())

    /** Reactive set of currently selected options consumed by the toolbar toggle actions. */
    val selectionFlow: StateFlow<Set<T>> = _selectionFlow.asStateFlow()

    override fun resetSelection() {
      _selectionFlow.value = items.toSet()
    }

    override fun matches(finding: AppFinding): Boolean = finding.selector() in _selectionFlow.value

    /** Toggles whether [value] is selected and notifies [onFilterUpdated]. */
    fun toggle(value: T) {
      _selectionFlow.update { if (value in it) it - value else it + value }
      onFilterUpdated()
    }
  }
}

/**
 * Coordinates a group of [FindingsFilter]s, resetting all filters together and computing cross-filter option counts when filtering a list
 * of [AppFinding]s.
 */
internal class FindingsFilterGroup(private vararg val filters: FindingsFilter) {
  /** Restores every filter in this group back to its default selection. */
  fun resetAll() {
    filters.forEach { it.resetSelection() }
  }

  /**
   * Refreshes each filter's option counts based on findings matching all *other* filters in this group, and returns the findings matching
   * every filter.
   */
  fun apply(findings: List<AppFinding>): List<AppFinding> {
    filters.forEach { current ->
      val otherFilters = filters.filterNot { it == current }
      val matchingOthers = findings.filter { finding -> otherFilters.all { it.matches(finding) } }
      current.updateCounts(allFindings = findings, matchingFindings = matchingOthers)
    }
    return findings.filter { finding -> filters.all { it.matches(finding) } }
  }
}
