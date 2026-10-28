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

import com.android.tools.idea.streaming.StreamingBundle.message
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Component
import java.awt.Dimension
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer

/** Studio-styled `TableView` matching `DisplayTable` in [DisplaysDialog]. */
internal class SavedLocationTable<T>(tableModel: ListTableModel<T>, tableName: String, emptyTextMessage: String) :
  TableView<T>(tableModel) {

  private var preferredColumnWidths: IntArray? = null

  init {
    name = tableName
    emptyText.text = emptyTextMessage
    createDefaultColumnsFromModel()
    setAutoResizeMode(AUTO_RESIZE_ALL_COLUMNS)
    selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

    addPropertyChangeListener { event ->
      if (event.propertyName == "model") {
        preferredColumnWidths = null
      }
    }
  }

  private fun getPreferredColumnWidth(column: Int): Int {
    var widths = preferredColumnWidths
    if (widths == null) {
      widths = calculatePreferredColumnWidths()
      preferredColumnWidths = widths
    }
    return widths[column]
  }

  private fun calculatePreferredColumnWidths(): IntArray {
    val widths = IntArray(listTableModel.columnCount)
    val tableWidth = width - insets.left - insets.right
    if (tableWidth <= 0) {
      return widths
    }
    var totalWidth = 0
    for (column in listTableModel.columnInfos.indices.reversed()) {
      val preferredColumnWidth =
        if (column == 0) {
          (tableWidth - totalWidth).coerceAtLeast(0)
        } else {
          calculatePreferredColumnWidth(column)
        }
      widths[column] = preferredColumnWidth
      totalWidth += preferredColumnWidth
    }
    return widths
  }

  override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
    val component = super.prepareRenderer(renderer, row, column) as JComponent
    val tableColumn = columnModel.getColumn(column)
    tableColumn.preferredWidth = getPreferredColumnWidth(column)
    return component
  }

  override fun tableChanged(event: TableModelEvent) {
    preferredColumnWidths = null
    super.tableChanged(event)
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
      val c: Component = super.prepareRenderer(cellRenderer, row, column)
      dataWidth = dataWidth.coerceAtLeast(c.preferredSize.width + intercellSpacing.width)
    }
    return dataWidth
  }

  companion object {
    private const val HEADER_GAP = 17
    private const val COLUMN_PADDING = 16
  }
}

/** Table model for saved single points. */
internal class SavedPointsTableModel : ListTableModel<SavedPointItem>() {
  private val nameColumn =
    object : LocationColumnInfo<SavedPointItem>(message("location.dialog.column.name")) {
      override fun valueOf(item: SavedPointItem): String = item.name
    }

  private val coordinatesColumn =
    object : LocationColumnInfo<SavedPointItem>(message("location.dialog.column.coordinates")) {
      override fun valueOf(item: SavedPointItem): String = item.coordinatesText
    }

  private val descriptionColumn =
    object : LocationColumnInfo<SavedPointItem>(message("location.dialog.column.description")) {
      override fun valueOf(item: SavedPointItem): String = item.description
    }

  init {
    columnInfos = arrayOf(nameColumn, coordinatesColumn, descriptionColumn)
    isSortable = false
  }
}

/** Table model for saved routes. */
internal class SavedRoutesTableModel : ListTableModel<SavedRouteItem>() {
  private val nameColumn =
    object : LocationColumnInfo<SavedRouteItem>(message("location.dialog.column.name")) {
      override fun valueOf(item: SavedRouteItem): String = item.name
    }

  private val modeColumn =
    object : LocationColumnInfo<SavedRouteItem>(message("location.dialog.column.mode")) {
      override fun valueOf(item: SavedRouteItem): String = item.mode.displayName
    }

  private val pointsColumn =
    object : LocationColumnInfo<SavedRouteItem>(message("location.dialog.column.points")) {
      override fun valueOf(item: SavedRouteItem): String = item.points.size.toString()
    }

  private val durationColumn =
    object : LocationColumnInfo<SavedRouteItem>(message("location.dialog.column.duration")) {
      override fun valueOf(item: SavedRouteItem): String = GeoMath.formatDuration(item.durationSeconds)
    }

  init {
    columnInfos = arrayOf(nameColumn, modeColumn, pointsColumn, durationColumn)
    isSortable = false
  }
}

private abstract class LocationColumnInfo<T>(name: String) : ColumnInfo<T, String>(name) {
  private val renderer = LocationTextColumnRenderer()

  override fun getRenderer(item: T): TableCellRenderer = renderer
}

private class LocationTextColumnRenderer : DefaultTableCellRenderer() {
  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any?,
    isSelected: Boolean,
    hasFocus: Boolean,
    row: Int,
    column: Int,
  ): Component = super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
}

internal class BorderLayoutPanelWithPreferredSize(
  private val preferredWidth: Int = TABLE_PREFERRED_WIDTH,
  private val preferredHeight: Int = TABLE_PREFERRED_HEIGHT,
) : BorderLayoutPanel() {
  override fun getPreferredSize(): Dimension {
    return if (isPreferredSizeSet) super.getPreferredSize() else JBUI.size(preferredWidth, preferredHeight)
  }

  companion object {
    private const val TABLE_PREFERRED_WIDTH = 360
    private const val TABLE_PREFERRED_HEIGHT = 320
  }
}

/** Represents a saved single point row in [LocationDialog]. */
internal data class SavedPointItem(
  val name: String,
  val latitude: Double,
  val longitude: Double,
  val elevation: Double = 0.0,
  val description: String = "",
) {
  val coordinatesText: String
    get() = String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude)
}

/** Represents a saved route row in [LocationDialog]. */
internal data class SavedRouteItem(
  val name: String,
  val description: String = "",
  val mode: TravelMode = TravelMode.DRIVING,
  val durationSeconds: Int = 0,
  val points: List<RoutePoint> = emptyList(),
)
