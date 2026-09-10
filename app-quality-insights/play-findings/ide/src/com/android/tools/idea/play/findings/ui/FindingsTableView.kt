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

import com.android.tools.adtui.common.ColoredIconGenerator
import com.android.tools.adtui.common.primaryContentBackground
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingCategory
import com.intellij.ui.NewUI
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.event.ListSelectionListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer

/**
 * Table displaying [AppFinding] items with "Finding" (severity icon + display name) and "Category" columns.
 *
 * Encapsulates column sizing, cell rendering, speed search, and listener-suppressed programmatic updates.
 */
internal class FindingsTableView(private val onSelectionChanged: (AppFinding?) -> Unit) :
  TableView<AppFinding>(createFindingsTableModel()) {

  private var isUpdatingSelection = false

  private val changeListener = ListSelectionListener {
    if (!isUpdatingSelection && !it.valueIsAdjusting) {
      onSelectionChanged(selectedObject)
    }
  }

  init {
    setShowGrid(false)
    isFocusable = true
    background = primaryContentBackground
    selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    tableHeader.reorderingAllowed = false

    TableSpeedSearch.installOn(this) { if (it is AppFinding) it.displayName else it?.toString() }
    selectionModel.addListSelectionListener(changeListener)
  }

  override fun createDefaultTableHeader(): JTableHeader {
    return object : JBTableHeader() {
      override fun updateUI() {
        super.updateUI()
        background = UIManager.getColor("Panel.background")
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
      }
    }
  }

  /**
   * Replaces the displayed [items] and resizes columns. Suppresses [changeListener] because updating [ListTableModel.items] clears the
   * table selection to `null` before the caller restores it.
   */
  fun updateItems(items: List<AppFinding>) {
    if (listTableModel.items != items) {
      suppressSelectionListener {
        listTableModel.items = items
        updateColumnSizes()
      }
    }
  }

  /**
   * Clears all rows. Suppresses [changeListener] so emptying the table while loading (e.g. on refresh) does not clear the controller's
   * preserved selection.
   */
  fun clearItems() {
    suppressSelectionListener { listTableModel.items = emptyList() }
  }

  /**
   * Selects [finding] and optionally scrolls it into view. Suppresses [changeListener] to avoid firing a transient `null` selection (from
   * `clearSelection()`) and re-entering [onSelectionChanged].
   */
  fun setSelectedFinding(finding: AppFinding?, scrollIntoView: Boolean = false) {
    suppressSelectionListener { selection = listOfNotNull(finding) }
    if (scrollIntoView && selectedRow != -1) {
      scrollRectToVisible(getCellRect(selectedRow, 0, true))
    }
  }

  /** Suppresses [changeListener] during programmatic table updates so [onSelectionChanged] only fires on user selection changes. */
  private inline fun suppressSelectionListener(block: () -> Unit) {
    val wasUpdating = isUpdatingSelection
    isUpdatingSelection = true
    try {
      block()
    } finally {
      isUpdatingSelection = wasUpdating
    }
  }
}

private val MAX_CATEGORY_DISPLAY_NAME = FindingCategory.entries.maxBy { it.displayName.length }.displayName

// TODO(b/517599039): Make table columns sortable.
private fun createFindingsTableModel(): ListTableModel<AppFinding> =
  ListTableModel(
    object : ColumnInfo<AppFinding, AppFinding>("Finding") {
      override fun valueOf(item: AppFinding) = item

      override fun getRenderer(item: AppFinding) = FindingCellRenderer
    },
    object : ColumnInfo<AppFinding, String>("Category") {
      override fun valueOf(item: AppFinding) = item.category.displayName

      override fun getMaxStringValue() = MAX_CATEGORY_DISPLAY_NAME

      override fun getAdditionalWidth() = JBUI.scale(15)

      override fun getRenderer(item: AppFinding) = CategoryColumnRenderer
    },
  )

private object FindingCellRenderer : TableCellRenderer {
  private val cellComponent =
    SimpleColoredComponent().apply {
      isOpaque = true
      border = JBUI.Borders.empty(2, 4)
    }

  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any?,
    isSelected: Boolean,
    hasFocus: Boolean,
    row: Int,
    column: Int,
  ): Component {
    val finding = value as AppFinding
    cellComponent.clear()

    val rawIcon = finding.severity.icon
    cellComponent.icon =
      if (isSelected && !NewUI.isEnabled()) {
        ColoredIconGenerator.generateColoredIcon(rawIcon, table.selectionForeground)
      } else {
        rawIcon
      }
    cellComponent.append(finding.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)

    cellComponent.toolTipText = finding.displayName
    cellComponent.background = if (isSelected) table.selectionBackground else table.background
    cellComponent.foreground = if (isSelected) table.selectionForeground else table.foreground

    return cellComponent
  }
}

private object CategoryColumnRenderer : DefaultTableCellRenderer() {
  override fun getTableCellRendererComponent(
    table: JTable?,
    value: Any?,
    isSelected: Boolean,
    hasFocus: Boolean,
    row: Int,
    column: Int,
  ): Component {
    return super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
  }
}
