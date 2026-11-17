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

import com.android.tools.adtui.swing.PortableUiFontRule
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.Dimension
import java.awt.Font
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource
import org.junit.Rule
import org.junit.Test

/** Tests for [ExtendedControlsTable]. */
@RunsInEdt
class ExtendedControlsTableTest {

  @get:Rule val ruleChain = RuleChain(ApplicationRule(), EdtRule(), PortableUiFontRule())

  @Test
  fun testWrapsAndRewrapsEnglishEmptyText() {
    val message = "Click the plus button below to import a sensor file"
    val table = createTable(message)

    table.size = Dimension(JBUI.scale(150), JBUI.scale(120))
    table.doLayout()

    val narrowLines = table.emptyTextLines()
    assertThat(narrowLines.size).isGreaterThan(1)
    assertThat(narrowLines.joinToString(" ")).isEqualTo(message)
    for (fragment in table.emptyText.wrappedFragmentsIterable) {
      assertThat(fragment.preferredSize.width).isAtMost(table.width)
    }

    table.size = Dimension(JBUI.scale(600), JBUI.scale(120))
    table.doLayout()

    assertThat(table.emptyTextLines()).containsExactly(message)
  }

  @Test
  fun testSplitsExplicitLineBreaks() {
    val message = "First line\nSecond line with several words\r\n\nFourth line"
    val table = createTable(message)

    // Explicit line breaks are split even before layout runs.
    assertThat(table.emptyTextLines()).containsExactly("First line", "Second line with several words", "", "Fourth line").inOrder()

    // Wide layout keeps explicit line breaks separated instead of collapsing onto one line.
    table.size = Dimension(JBUI.scale(600), JBUI.scale(120))
    table.doLayout()
    assertThat(table.emptyTextLines()).containsExactly("First line", "Second line with several words", "", "Fourth line").inOrder()

    // Narrow layout wraps long paragraphs while preserving explicit line breaks.
    table.size = Dimension(JBUI.scale(140), JBUI.scale(120))
    table.doLayout()
    val wrappedLines = table.emptyTextLines()
    assertThat(wrappedLines.first()).isEqualTo("First line")
    assertThat(wrappedLines).contains("")
    assertThat(wrappedLines.last()).isEqualTo("Fourth line")
    assertThat(wrappedLines.size).isGreaterThan(4)
    for (line in wrappedLines) {
      assertThat(line).doesNotContain("\n")
      assertThat(line).doesNotContain("\r")
    }
  }

  @Test
  fun testWrapsCjkEmptyTextWithoutSpaces() {
    val cjkMessage = "点击下方的加号按钮导入传感器文件。"
    val table = createTable(cjkMessage)
    // Use a logical font so CJK characters are guaranteed to have positive advance widths on all platforms.
    val logicalFont = Font(Font.DIALOG, Font.PLAIN, JBUI.scaleFontSize(12f))
    table.font = logicalFont
    table.emptyText.setFont(logicalFont)

    table.size = Dimension(JBUI.scale(120), JBUI.scale(120))
    table.doLayout()

    val lines = table.emptyTextLines()
    assertThat(lines.size).isGreaterThan(1)
    assertThat(lines.joinToString("")).isEqualTo(cjkMessage)
    for (line in lines) {
      assertThat(line.startsWith("。")).isFalse()
    }
    val availableWidth = table.width - JBUI.scale(16)
    val fontMetrics = table.getFontMetrics(logicalFont)
    for (line in lines) {
      assertThat(fontMetrics.stringWidth(line)).isAtMost(availableWidth)
    }
  }

  @Test
  fun testPreservesNonBreakingSpacesAndBreaksOnTabs() {
    val nbspMessage = "Import file of 100\u00A0MB maximum"
    val nbspTable = createTable(nbspMessage)
    nbspTable.size = Dimension(JBUI.scale(130), JBUI.scale(120))
    nbspTable.doLayout()

    val nbspLines = nbspTable.emptyTextLines()
    assertThat(nbspLines.size).isGreaterThan(1)
    assertThat(nbspLines.any { it.contains("100\u00A0MB") }).isTrue()
    assertThat(nbspLines.joinToString(" ")).isEqualTo(nbspMessage)

    val tabMessage = "Alpha\tBeta\tGamma\tDelta\tEpsilon"
    val tabTable = createTable(tabMessage)
    tabTable.size = Dimension(JBUI.scale(100), JBUI.scale(120))
    tabTable.doLayout()

    val tabLines = tabTable.emptyTextLines()
    assertThat(tabLines.size).isGreaterThan(1)
    for (line in tabLines) {
      assertThat(line.endsWith("\t")).isFalse()
      assertThat(line.startsWith("\t")).isFalse()
    }
  }

  @Test
  fun testOverlongUnbreakableTokenDoesNotHang() {
    val overlongWord = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val table = createTable("$overlongWord after")
    table.size = Dimension(JBUI.scale(100), JBUI.scale(120))
    table.doLayout()

    assertThat(table.emptyTextLines()).containsExactly(overlongWord, "after").inOrder()
  }

  @Test
  fun testUpdateUiRewrapsEmptyTextAtSameTableWidth() {
    val message = "Click the plus button below to import a sensor file"
    val table = createTable(message)
    table.size = Dimension(JBUI.scale(360), JBUI.scale(120))
    table.doLayout()

    assertThat(table.emptyTextLines()).containsExactly(message)

    val originalLabelFont = UIManager.get("Label.font")
    try {
      UIManager.put("Label.font", FontUIResource(table.font.deriveFont(28f)))
      table.updateUI()
      table.doLayout()

      val rewrappedLines = table.emptyTextLines()
      assertThat(rewrappedLines.size).isGreaterThan(1)
      assertThat(rewrappedLines.joinToString(" ")).isEqualTo(message)
    } finally {
      UIManager.put("Label.font", originalLabelFont)
    }
  }

  @Test
  fun testSortOrderPersistenceAndNonSortableColumnFallback() {
    val storageKey = "com.android.tools.idea.streaming.emulator.dialogs.ExtendedControlsTableTest.sort.order"
    val properties = PropertiesComponent.getInstance()
    properties.unsetValue(storageKey)
    try {
      fun createSortableModel(): ListTableModel<String> {
        val sortableColumn =
          object : ColumnInfo<String, String>("Sortable") {
            override fun valueOf(item: String): String = item

            override fun getComparator(): Comparator<String> = naturalOrder()
          }
        val nonSortableColumn =
          object : ColumnInfo<String, String>("NonSortable") {
            override fun valueOf(item: String): String = item
          }
        return ListTableModel(sortableColumn, nonSortableColumn)
      }

      val table1 = ExtendedControlsTable(createSortableModel(), "sortableTable", sortOrderStorageKey = storageKey)
      assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.ASCENDING))

      table1.rowSorter.toggleSortOrder(0)
      assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.DESCENDING))
      assertThat(properties.getValue(storageKey)).isEqualTo("0:DESCENDING")

      val table2 = ExtendedControlsTable(createSortableModel(), "sortableTable", sortOrderStorageKey = storageKey)
      assertThat(table2.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.DESCENDING))

      // Persisted index pointing to a non-sortable column falls back to the model's default sort key.
      properties.setValue(storageKey, "1:ASCENDING")
      val table3 = ExtendedControlsTable(createSortableModel(), "sortableTable", sortOrderStorageKey = storageKey)
      assertThat(table3.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.ASCENDING))

      // Replacing a 1-column non-sortable model with a 3-column sortable model via setModel / setModelAndUpdateColumns restores and tracks
      // the new sorter without failing against the previous model/sorter.
      fun createThreeColumnSortableModel(): ListTableModel<String> {
        fun sortableCol(name: String) =
          object : ColumnInfo<String, String>(name) {
            override fun valueOf(item: String): String = item

            override fun getComparator(): Comparator<String> = naturalOrder()
          }
        return ListTableModel(sortableCol("Col0"), sortableCol("Col1"), sortableCol("Col2"))
      }

      val singleNonSortableCol =
        object : ColumnInfo<String, String>("Only") {
          override fun valueOf(item: String): String = item
        }
      properties.setValue(storageKey, "2:DESCENDING")
      val table4 = ExtendedControlsTable(ListTableModel<String>(singleNonSortableCol), "sortableTable", sortOrderStorageKey = storageKey)
      assertThat(table4.rowSorter).isNull()

      table4.setModel(createThreeColumnSortableModel())
      assertThat(table4.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(2, SortOrder.DESCENDING))
      table4.rowSorter.toggleSortOrder(1)
      assertThat(properties.getValue(storageKey)).isEqualTo("1:ASCENDING")

      val table5 = ExtendedControlsTable(ListTableModel<String>(singleNonSortableCol), "sortableTable", sortOrderStorageKey = storageKey)
      properties.setValue(storageKey, "2:ASCENDING")
      table5.setModelAndUpdateColumns(createThreeColumnSortableModel())
      assertThat(table5.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(2, SortOrder.ASCENDING))
      table5.rowSorter.toggleSortOrder(2)
      assertThat(properties.getValue(storageKey)).isEqualTo("2:DESCENDING")
    } finally {
      properties.unsetValue(storageKey)
    }
  }

  private fun createTable(emptyTextMessage: String): ExtendedControlsTable<String> {
    val column =
      object : ColumnInfo<String, String>("Item") {
        override fun valueOf(item: String): String = item
      }
    return ExtendedControlsTable(ListTableModel(column), "testTable", emptyTextMessage)
  }

  private fun ExtendedControlsTable<*>.emptyTextLines(): List<String> = emptyText.wrappedFragmentsIterable.map { it.toString() }
}
