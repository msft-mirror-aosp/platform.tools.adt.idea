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

import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.TableUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import java.awt.FontMetrics
import java.text.BreakIterator
import javax.swing.ListSelectionModel
import javax.swing.event.TableModelEvent
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer

/**
 * Studio-styled [TableView] for extended controls dialogs that sizes non-initial columns to fit their header/data and gives the remaining
 * width to the first column.
 */
internal class ExtendedControlsTable<T>(
  tableModel: ListTableModel<T>,
  tableName: String,
  private val emptyTextMessage: @NlsContexts.StatusText String? = null,
) : TableView<T>(tableModel) {

  private var preferredColumnWidths: IntArray? = null
  private var lastTableWidth: Int = 0
  private var lastEmptyTextWidth: Int = 0

  init {
    name = tableName
    if (emptyTextMessage != null) {
      setEmptyTextLines(emptyTextMessage.lines())
    }
    createDefaultColumnsFromModel()
    getTableHeader().reorderingAllowed = false
    setAutoResizeMode(AUTO_RESIZE_ALL_COLUMNS)
    selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

    addPropertyChangeListener { event ->
      if (event.propertyName == "model") {
        preferredColumnWidths = null
      }
    }
  }

  fun selectModelRow(modelRow: Int) {
    val viewRow = if (rowCount > 0 && modelRow in 0 until listTableModel.rowCount) convertRowIndexToView(modelRow) else -1
    if (viewRow >= 0) {
      selectionModel.setSelectionInterval(viewRow, viewRow)
      TableUtil.scrollSelectionToVisible(this)
    } else {
      selectionModel.clearSelection()
    }
  }

  fun selectRowAfterRemoval(previousMinSelectionIndex: Int): Boolean {
    selectionModel.clearSelection()
    if (rowCount <= 0) {
      return false
    }
    val index = previousMinSelectionIndex.coerceIn(0, rowCount - 1)
    selectionModel.setSelectionInterval(index, index)
    TableUtil.scrollSelectionToVisible(this)
    return true
  }

  private fun getPreferredColumnWidth(column: Int): Int? {
    val tableWidth = width - insets.left - insets.right
    if (tableWidth <= 0) {
      return null
    }
    var widths = preferredColumnWidths
    if (widths == null || lastTableWidth != tableWidth) {
      widths = calculatePreferredColumnWidths(tableWidth)
      preferredColumnWidths = widths
      lastTableWidth = tableWidth
    }
    return widths[column]
  }

  private fun calculatePreferredColumnWidths(tableWidth: Int): IntArray {
    val widths = IntArray(columnCount)
    var totalWidth = 0
    for (column in (0 until columnCount).reversed()) {
      val preferredColumnWidth =
        if (column == 0) {
          // The first column gets the remaining width.
          (tableWidth - totalWidth).coerceAtLeast(getColumnHeaderWidth(0) + JBUIScale.scale(COLUMN_PADDING))
        } else {
          calculatePreferredColumnWidth(column)
        }
      widths[column] = preferredColumnWidth
      totalWidth += preferredColumnWidth
    }
    return widths
  }

  override fun updateUI() {
    super.updateUI()
    preferredColumnWidths = null
    lastEmptyTextWidth = 0
    if (emptyTextMessage != null) {
      for (fragment in emptyText.wrappedFragmentsIterable) {
        fragment.updateUI()
      }
    }
  }

  override fun doLayout() {
    for (column in 0 until columnCount) {
      getPreferredColumnWidth(column)?.let { columnModel.getColumn(column).preferredWidth = it }
    }
    updateWrappedEmptyText()
    super.doLayout()
  }

  private fun updateWrappedEmptyText() {
    val message = emptyTextMessage ?: return
    val availableWidth = width - insets.left - insets.right - JBUIScale.scale(EMPTY_TEXT_PADDING)
    if (availableWidth <= 0 || availableWidth == lastEmptyTextWidth) {
      return
    }
    lastEmptyTextWidth = availableWidth
    val fontMetrics = getFontMetrics(emptyText.component.font ?: font)
    val wrappedLines = buildList {
      for (paragraph in message.lines()) {
        wrapParagraph(paragraph, fontMetrics, availableWidth)
      }
    }
    setEmptyTextLines(wrappedLines)
  }

  private fun setEmptyTextLines(lines: List<String>) {
    if (lines.isEmpty()) {
      emptyText.clear()
      return
    }
    emptyText.text = lines[0]
    for (i in 1 until lines.size) {
      emptyText.appendLine(lines[i])
    }
  }

  override fun tableChanged(event: TableModelEvent) {
    preferredColumnWidths = null
    super.tableChanged(event)
    revalidate()
  }

  private fun calculatePreferredColumnWidth(column: Int): Int {
    return (getColumnHeaderWidth(column).coerceAtLeast(getColumnDataWidth(column))) + JBUIScale.scale(COLUMN_PADDING)
  }

  private fun getColumnHeaderWidth(column: Int): Int {
    val tableHeader: JTableHeader = getTableHeader()
    val headerFontMetrics = tableHeader.getFontMetrics(tableHeader.font)
    return headerFontMetrics.stringWidth(getColumnName(column)) + JBUIScale.scale(HEADER_GAP)
  }

  private fun getColumnDataWidth(column: Int): Int {
    var dataWidth = 0
    for (row in 0 until rowCount) {
      val cellRenderer: TableCellRenderer = getCellRenderer(row, column)
      val c: Component = prepareRenderer(cellRenderer, row, column)
      dataWidth = dataWidth.coerceAtLeast(c.preferredSize.width + intercellSpacing.width)
    }
    return dataWidth
  }

  companion object {
    private const val HEADER_GAP = 17
    private const val COLUMN_PADDING = 16
    private const val EMPTY_TEXT_PADDING = 16

    private fun MutableList<String>.wrapParagraph(paragraph: String, fontMetrics: FontMetrics, availableWidth: Int) {
      val trimmed = paragraph.trimTrailingBreakingWhitespace()
      if (trimmed.isEmpty()) {
        add("")
        return
      }
      if (fontMetrics.stringWidth(trimmed) <= availableWidth) {
        add(trimmed)
        return
      }
      val breakIterator = BreakIterator.getLineInstance()
      breakIterator.setText(trimmed)
      var lineStart = breakIterator.first()
      while (lineStart < trimmed.length) {
        var lineEnd = lineStart
        var hasNonWhitespace = false
        var nextBoundary = breakIterator.following(lineStart)
        while (nextBoundary != BreakIterator.DONE) {
          val candidate = trimmed.substring(lineStart, nextBoundary).trimTrailingBreakingWhitespace()
          val candidateWidth = fontMetrics.stringWidth(candidate)
          if (hasNonWhitespace && candidateWidth > availableWidth) {
            break
          }
          lineEnd = nextBoundary
          hasNonWhitespace = candidate.isNotEmpty()
          if (hasNonWhitespace && candidateWidth > availableWidth) {
            break
          }
          nextBoundary = breakIterator.next()
        }
        val wrappedLine = trimmed.substring(lineStart, lineEnd).trimTrailingBreakingWhitespace()
        if (wrappedLine.isNotEmpty()) {
          add(wrappedLine)
        }
        lineStart = lineEnd
        while (lineStart < trimmed.length && Character.isWhitespace(trimmed[lineStart])) {
          lineStart++
        }
      }
    }

    private fun String.trimTrailingBreakingWhitespace(): String {
      var end = length
      while (end > 0 && Character.isWhitespace(this[end - 1])) {
        end--
      }
      return substring(0, end)
    }
  }
}
