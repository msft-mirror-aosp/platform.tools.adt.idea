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

import com.android.SdkConstants.PRIMARY_DISPLAY_ID
import com.android.emulator.control.DisplayConfiguration
import com.android.emulator.control.DisplayConfigurations
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.NotificationTracker
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import kotlinx.coroutines.launch

/** Dialog for configuring emulator displays, providing functionality similar to the Displays page in extended controls. */
internal class DisplaysDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("displays.dialog.title", emulator.emulatorId.avdName)) {

  private var maxSecondaryDisplays: Int = 0

  private val displaysMap = sortedMapOf<Int, DisplayItem>()

  private val displayTableModel = DisplayTableModel()
  private val displayTable = DisplayTable(displayTableModel)

  private val displayConfigurationsChannel =
    createStateChannel(
      getState = EmulatorController::getDisplayConfigurations,
      onStateLoaded = ::updateFromDisplayConfigurations,
      setState = EmulatorController::setDisplayConfigurations,
    )

  init {
    // Install a double-click listener to edit configurable displays on double click.
    displayTable.apply {
      object : DoubleClickListener() {
          override fun onDoubleClick(event: MouseEvent): Boolean {
            val point = event.point
            val row = rowAtPoint(point)
            if (row < 0 || columnAtPoint(point) < 0) {
              return false
            }
            val item = displayTableModel.getItem(convertRowIndexToModel(row))
            if (!item.isConfigurable) {
              return false
            }
            selectionModel.setSelectionInterval(row, row)
            editSelectedDisplay()
            return true
          }
        }
        .installOn(this)
    }
  }

  /** Returns the available [DisplayType] presets for the given [configurableDisplayIndex]. */
  private fun getPresetDisplayTypes(
    configurableDisplayIndex: Int,
    hasPresetCluster: Boolean = displaysMap.values.any { it.displayId != PRIMARY_DISPLAY_ID && !it.isConfigurable },
  ): List<DisplayType> {
    return when (emulator.emulatorConfig.deviceType) {
      DeviceType.AUTOMOTIVE -> {
        if (configurableDisplayIndex == 0 && !hasPresetCluster) AUTO_CLUSTER_DISPLAY_TYPES else AUTO_SECONDARY_DISPLAY_TYPES
      }
      else -> STANDARD_DISPLAY_TYPES
    }
  }

  private fun findMatchingType(displayId: Int, width: Int, height: Int, dpi: Int): DisplayType {
    return findMatchingType(getPresetDisplayTypes(displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET), width, height, dpi)
  }

  private fun findMatchingType(types: List<DisplayType>, width: Int, height: Int, dpi: Int): DisplayType {
    return types.firstOrNull { !it.isCustom && it.width == width && it.height == height && it.dpi == dpi } ?: types.last()
  }

  /** Adds a new secondary display in the lowest available display ID slot, or returns null if at maximum capacity. */
  private fun addSecondaryDisplay(
    width: Int? = null,
    height: Int? = null,
    dpi: Int? = null,
  ): DisplayItem? {
    val displayId = findNextDisplayId()
    if (displayId < 0) {
      return null
    }
    val types = getPresetDisplayTypes(displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET)
    val defaultType = types.first()
    val w = width ?: defaultType.width
    val h = height ?: defaultType.height
    val d = dpi ?: defaultType.dpi
    val item =
      DisplayItem(
        displayId = displayId,
        isConfigurable = true,
        width = w,
        height = h,
        dpi = d,
        description = findMatchingType(displayId, w, h, d).shortName,
      )
    displaysMap[displayId] = item
    stateModified = true
    updateTableFromState(selectItem = item)
    applyChanges()
    return item
  }

  /** Sets or updates the secondary display with the given [displayId]. */
  private fun setSecondaryDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
    require(displayId in 1..maxSecondaryDisplays) { "Invalid displayId: $displayId" }
    val description = findMatchingType(displayId, width, height, dpi).shortName
    val item = displaysMap[displayId]
    if (item != null && item.isConfigurable) {
      item.width = width
      item.height = height
      item.dpi = dpi
      item.description = description
    } else {
      displaysMap[displayId] =
        DisplayItem(
          displayId = displayId,
          isConfigurable = true,
          width = width,
          height = height,
          dpi = dpi,
          description = description,
        )
    }
    stateModified = true
    updateTableFromState(selectItem = displaysMap[displayId])
    applyChanges()
  }

  /** Sends the current secondary display configurations to the emulator. */
  private fun applyChanges() {
    val updated =
      DisplayConfigurations.newBuilder()
        .apply {
          for (item in displaysMap.values) {
            if (item.displayId != PRIMARY_DISPLAY_ID) {
              addDisplays(item.toDisplayConfiguration())
            }
          }
        }
        .build()
    displayConfigurationsChannel.trySend(updated)
  }

  private fun findNextDisplayId(): Int {
    return (1..maxSecondaryDisplays).firstOrNull { it !in displaysMap } ?: -1
  }

  private fun updateFromDisplayConfigurations(config: DisplayConfigurations) {
    maxSecondaryDisplays = config.userConfigurable
    updateFromDisplayList(config.displaysList)
  }

  internal fun updateFromDisplayList(displayConfigs: List<DisplayConfiguration>) {
    val presetClusterId =
      if (emulator.emulatorConfig.deviceType == DeviceType.AUTOMOTIVE) {
        displayConfigs
          .map { it.display }
          .filter { it != PRIMARY_DISPLAY_ID && it - CONFIGURABLE_DISPLAY_INDEX_OFFSET !in 0 until maxSecondaryDisplays }
          .minOrNull() ?: -1
      } else {
        -1
      }
    val newDisplays = sortedMapOf<Int, DisplayItem>()
    for (display in displayConfigs) {
      val displayId = display.display
      val isConfigurable = displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET in 0 until maxSecondaryDisplays
      val types =
        if (isConfigurable) {
          getPresetDisplayTypes(displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET, presetClusterId >= 0)
        } else {
          emptyList()
        }
      val dpi = if (display.dpi > 0) display.dpi else types.firstOrNull()?.dpi ?: 0
      val description =
        when {
          displayId == PRIMARY_DISPLAY_ID -> message("displays.dialog.display.primary")
          displayId == presetClusterId -> message("displays.dialog.display.cluster")
          !isConfigurable -> message("displays.dialog.display.preset")
          else -> findMatchingType(types, display.width, display.height, dpi).shortName
        }
      newDisplays[displayId] =
        DisplayItem(
          displayId = displayId,
          isConfigurable = isConfigurable,
          width = display.width,
          height = display.height,
          dpi = dpi,
          description = description,
        )
    }
    if (newDisplays == displaysMap) {
      return
    }
    displaysMap.clear()
    displaysMap.putAll(newDisplays)
    updateTableFromState()
  }

  private fun getSortedDisplayItems(): List<DisplayItem> {
    return displaysMap.values.sortedWith(compareBy<DisplayItem> { it.isConfigurable }.thenBy { it.displayId })
  }

  private fun updateTableFromState(selectItem: DisplayItem? = null) {
    val selectedDisplayId = selectItem?.displayId ?: displayTable.selectedObject?.displayId
    val items = getSortedDisplayItems()
    displayTableModel.items = items
    val indexToSelect = items.indexOfFirst { it.displayId == selectedDisplayId }.takeIf { it >= 0 } ?: 0
    if (items.isNotEmpty()) {
      val viewRow = displayTable.convertRowIndexToView(indexToSelect)
      displayTable.selectionModel.setSelectionInterval(viewRow, viewRow)
      TableUtil.scrollSelectionToVisible(displayTable)
    }
    ActivityTracker.getInstance().inc()
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      row {
        cell(createTablePanel()).align(Align.FILL)
      }
        .resizableRow()
    }
  }

  private fun createTablePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize(450, 250).apply {
      add(
        ToolbarDecorator.createDecorator(displayTable)
          .setAddAction { addDisplayViaDialog() }
          .setEditAction { editSelectedDisplay() }
          .setRemoveAction { removeSelectedDisplays() }
          .setMoveDownAction(null)
          .setMoveUpAction(null)
          .setAddActionUpdater { findNextDisplayId() > 0 }
          .setEditActionUpdater {
            displayTable.selectedRowCount == 1 && displayTable.selectedObject?.isConfigurable == true
          }
          .setRemoveActionUpdater {
            displayTable.selectedObjects.isNotEmpty() && displayTable.selectedObjects.all { it.isConfigurable }
          }
          .setToolbarPosition(ActionToolbarPosition.BOTTOM)
          .createPanel()
      )
    }
  }

  private fun addDisplayViaDialog() {
    val displayId = findNextDisplayId()
    if (displayId < 0) {
      return
    }
    val types = getPresetDisplayTypes(displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET)
    val defaultType = types.first()
    val dialog =
      EditDisplayDialog(
        title = message("displays.dialog.add.title"),
        displayTypes = types,
        width = defaultType.width,
        height = defaultType.height,
        dpi = defaultType.dpi,
      )
    if (dialog.createWrapper(parent = displayTable).showAndGet()) {
      addSecondaryDisplay(dialog.width, dialog.height, dialog.dpi)
    }
  }

  private fun editSelectedDisplay() {
    TableUtil.stopEditing(displayTable)
    val item = displayTable.selectedObject ?: return
    if (!item.isConfigurable) {
      return
    }
    val dialog =
      EditDisplayDialog(
        title = message("displays.dialog.configure.title"),
        displayTypes = getPresetDisplayTypes(item.displayId - CONFIGURABLE_DISPLAY_INDEX_OFFSET),
        width = item.width,
        height = item.height,
        dpi = item.dpi,
      )
    if (dialog.createWrapper(parent = displayTable).showAndGet()) {
      setSecondaryDisplay(item.displayId, dialog.width, dialog.height, dialog.dpi)
    }
  }

  private fun removeSelectedDisplays() {
    TableUtil.stopEditing(displayTable)
    val selectionModel = displayTable.selectionModel
    val minSelectionIndex = selectionModel.minSelectionIndex
    val toRemove = displayTable.selectedObjects.filter { it.isConfigurable }
    if (toRemove.isEmpty()) {
      return
    }
    for (item in toRemove) {
      displaysMap.remove(item.displayId)
    }
    stateModified = true
    displayTableModel.items = getSortedDisplayItems()
    selectionModel.clearSelection()
    if (displayTable.rowCount > 0) {
      val index =
        when {
          minSelectionIndex < 0 -> 0
          minSelectionIndex < displayTable.rowCount -> minSelectionIndex
          else -> displayTable.rowCount - 1
        }
      selectionModel.setSelectionInterval(index, index)
    }
    ActivityTracker.getInstance().inc()
    applyChanges()
  }

  /** Creates the dialog wrapper. */
  override fun createWrapper(project: Project?, parent: Component?): DialogWrapper {
    return super.createWrapper(project, parent).apply {
      val notificationTracker = NotificationTracker.forEmulator(emulator)
      disposable.createCoroutineScope(uiContext).launch {
        notificationTracker.displayConfigurations.collect { displayConfigs ->
          if (displayConfigs != null && !stateModified) {
            updateFromDisplayList(displayConfigs)
          }
        }
      }
    }
  }

  private class DisplayTableModel : ListTableModel<DisplayItem>() {

    private val typeColumn =
      object : DisplayColumnInfo(message("displays.dialog.column.type")) {
        override fun valueOf(item: DisplayItem): String = item.description
      }

    private val widthColumn =
      object : DisplayColumnInfo(message("displays.dialog.column.width")) {
        override fun valueOf(item: DisplayItem): String = item.width.toString()
      }

    private val heightColumn =
      object : DisplayColumnInfo(message("displays.dialog.column.height")) {
        override fun valueOf(item: DisplayItem): String = item.height.toString()
      }

    private val densityColumn =
      object : DisplayColumnInfo(message("displays.dialog.column.density")) {
        override fun valueOf(item: DisplayItem): String = item.densityText
      }

    init {
      columnInfos = arrayOf(typeColumn, widthColumn, heightColumn, densityColumn)
      isSortable = false
    }
  }

  private abstract class DisplayColumnInfo(name: String) : ColumnInfo<DisplayItem, String>(name) {
    override fun getRenderer(item: DisplayItem): TableCellRenderer = DisplayTextColumnRenderer()
  }

  private class DisplayTextColumnRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
      table: JTable,
      value: Any,
      isSelected: Boolean,
      hasFocus: Boolean,
      row: Int,
      column: Int,
    ): Component {
      return super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
    }
  }

  private class DisplayTable(tableModel: DisplayTableModel) : TableView<DisplayItem>(tableModel) {

    private var preferredColumnWidths: IntArray? = null

    override fun getModel(): DisplayTableModel = dataModel as DisplayTableModel

    init {
      name = "displaysTable"
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
      val widths = IntArray(model.columnCount)
      val tableWidth = width - insets.left - insets.right
      if (tableWidth <= 0) {
        return widths
      }
      var totalWidth = 0
      for (column in model.columnInfos.indices.reversed()) {
        val preferredColumnWidth =
          if (column == 0) {
            // The first column gets the remaining width.
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
  }

  private class BorderLayoutPanelWithPreferredSize(private val preferredWidth: Int, private val preferredHeight: Int) :
    BorderLayoutPanel() {

    override fun getPreferredSize(): Dimension {
      return if (isPreferredSizeSet) super.getPreferredSize() else JBUI.size(preferredWidth, preferredHeight)
    }
  }

  companion object {
    private const val HEADER_GAP = 17
    private const val COLUMN_PADDING = 16

    internal fun isValidResolution(width: Int, height: Int): Boolean {
      return width > 0 &&
        height > 0 &&
        ((width <= MAX_RESOLUTION_LONG && height <= MAX_RESOLUTION_SHORT) ||
          (width <= MAX_RESOLUTION_SHORT && height <= MAX_RESOLUTION_LONG))
    }

    /** Shows the [DisplaysDialog] for the given [emulator]. */
    fun showDisplaysDialog(emulator: EmulatorController, project: Project? = null, parent: Component? = null) {
      DisplaysDialog(emulator).show(project, parent)
    }
  }
}

/** Dialog for adding or editing a secondary display configuration. */
private class EditDisplayDialog(
  private val title: String,
  private val displayTypes: List<DisplayType>,
  var width: Int,
  var height: Int,
  var dpi: Int,
) {
  private var selectedType: DisplayType = findMatchingType(width, height, dpi)

  private var widthField: JBTextField? = null
  private var heightField: JBTextField? = null
  private var dpiField: JBTextField? = null
  private var displayTypeComboBox: ComboBox<DisplayType>? = null
  private var isUpdatingUi = false

  private fun findMatchingType(w: Int, h: Int, d: Int): DisplayType =
    displayTypes.firstOrNull { !it.isCustom && it.width == w && it.height == h && it.dpi == d } ?: displayTypes.last()

  private fun updatePresetFromFields() {
    if (isUpdatingUi) return
    val w = widthField?.text?.toIntOrNull()
    val h = heightField?.text?.toIntOrNull()
    val d = dpiField?.text?.toIntOrNull()
    if (w != null) width = w
    if (h != null) height = h
    if (d != null) dpi = d
    val matchingType =
      if (w != null && h != null && d != null) {
        findMatchingType(w, h, d)
      } else {
        displayTypes.last()
      }
    if (selectedType != matchingType) {
      selectedType = matchingType
      isUpdatingUi = true
      try {
        displayTypeComboBox?.selectedItem = matchingType
      } finally {
        isUpdatingUi = false
      }
    }
  }

  fun createPanel(): DialogPanel {
    val documentListener =
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          updatePresetFromFields()
        }
      }

    return panel {
      row {
          comboBox(DefaultComboBoxModel(displayTypes.toTypedArray()), renderer = textListCellRenderer { it?.displayName ?: "" })
            .label(message("displays.dialog.preset.label"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .focused()
            .applyToComponent {
              name = "displayTypeComboBox"
              selectedItem = selectedType
              displayTypeComboBox = this
              setMinimumAndPreferredWidth(JBUIScale.scale(COMBO_BOX_WIDTH))
              addItemListener { event ->
                if (!isUpdatingUi && event.stateChange == ItemEvent.SELECTED) {
                  val newType = event.item as? DisplayType ?: return@addItemListener
                  selectedType = newType
                  if (!newType.isCustom) {
                    this@EditDisplayDialog.width = newType.width
                    this@EditDisplayDialog.height = newType.height
                    dpi = newType.dpi
                    isUpdatingUi = true
                    try {
                      widthField?.text = newType.width.toString()
                      heightField?.text = newType.height.toString()
                      dpiField?.text = newType.dpi.toString()
                    } finally {
                      isUpdatingUi = false
                    }
                  }
                }
              }
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          intTextField()
            .label(message("displays.dialog.width.label"), LabelPosition.TOP)
            .bindIntText(::width)
            .align(AlignX.FILL)
            .resizableColumn()
            .validationOnInput { validateDimensionField(it, isHeight = false) }
            .validationOnApply { validateDimensionField(it, isHeight = false) }
            .applyToComponent {
              name = "widthField"
              widthField = this
              document.addDocumentListener(documentListener)
            }
          intTextField()
            .label(message("displays.dialog.height.label"), LabelPosition.TOP)
            .bindIntText(::height)
            .align(AlignX.FILL)
            .resizableColumn()
            .validationOnInput { validateDimensionField(it, isHeight = true) }
            .validationOnApply { validateDimensionField(it, isHeight = true) }
            .applyToComponent {
              name = "heightField"
              heightField = this
              document.addDocumentListener(documentListener)
            }
          intTextField()
            .label(message("displays.dialog.dpi.label"), LabelPosition.TOP)
            .bindIntText(::dpi)
            .align(AlignX.FILL)
            .resizableColumn()
            .validationOnInput { validateDpiField(it) }
            .validationOnApply { validateDpiField(it) }
            .applyToComponent {
              name = "dpiField"
              dpiField = this
              document.addDocumentListener(documentListener)
            }
        }
        .layout(RowLayout.PARENT_GRID)
    }
  }

  private fun ValidationInfoBuilder.validateDpiField(field: JBTextField): ValidationInfo? {
    val d = field.text.toIntOrNull() ?: return error(message("displays.dialog.error.dpi.range", MIN_DPI, MAX_DPI))
    return if (d !in MIN_DPI..MAX_DPI) {
      error(message("displays.dialog.error.dpi.range", MIN_DPI, MAX_DPI))
    } else {
      null
    }
  }

  private fun ValidationInfoBuilder.validateDimensionField(field: JBTextField, isHeight: Boolean): ValidationInfo? {
    val d = (dpiField?.text?.toIntOrNull() ?: dpi).coerceIn(MIN_DPI, MAX_DPI)
    val minPx = MIN_DIMENSION_DP * d / 160
    val value = field.text.toIntOrNull() ?: return error(message("displays.dialog.error.min.dimension", MIN_DIMENSION_DP))
    if (value < minPx) {
      return error(message("displays.dialog.error.min.dimension", MIN_DIMENSION_DP))
    }
    val otherField = if (isHeight) widthField else heightField
    val otherValue = otherField?.text?.toIntOrNull() ?: if (isHeight) width else height
    val w = if (isHeight) otherValue else value
    val h = if (isHeight) value else otherValue
    if (!DisplaysDialog.isValidResolution(w, h)) {
      return error(message("displays.dialog.error.max.resolution", MAX_RESOLUTION_LABEL, MAX_RESOLUTION_LONG, MAX_RESOLUTION_SHORT))
    }
    return null
  }

  fun createWrapper(project: Project? = null, parent: Component? = null): DialogWrapper {
    return dialog(
      title = title,
      resizable = false,
      panel = createPanel(),
      project = project,
      parent = parent,
    )
  }

  companion object {
    private const val COMBO_BOX_WIDTH = 220
  }
}

/** Preset or custom display configuration type for a secondary display. */
private data class DisplayType(
  val displayName: String,
  val shortName: String,
  val width: Int,
  val height: Int,
  val dpi: Int,
  val isCustom: Boolean = false,
) {
  override fun toString(): String = displayName
}

/** Standard secondary display types. */
private val STANDARD_DISPLAY_TYPES =
  listOf(
    DisplayType("480p (720\u00D7480)", "480p", width = 480, height = 720, dpi = 142),
    DisplayType("720p (1280\u00D7720)", "720p", width = 720, height = 1280, dpi = 213),
    DisplayType("1080p (1920\u00D71080)", "1080p", width = 1080, height = 1920, dpi = 320),
    DisplayType("4K (3840\u00D72160)", "4K", width = 2160, height = 3840, dpi = 320),
    DisplayType("4K upscaled (3840\u00D72160)", "4K upscaled", width = 2160, height = 3840, dpi = 640),
    DisplayType("Custom", "Custom", width = 720, height = 1080, dpi = 213, isCustom = true),
  )

/** Automotive cluster (display 1) display types. */
private val AUTO_CLUSTER_DISPLAY_TYPES =
  listOf(
    DisplayType("Cluster (400\u00D7600) portrait", "Cluster portrait", width = 400, height = 600, dpi = 120),
    DisplayType("Cluster (800\u00D7600) landscape", "Cluster landscape", width = 800, height = 600, dpi = 120),
    DisplayType("Custom", "Custom", width = 400, height = 600, dpi = 120, isCustom = true),
  )

/** Automotive secondary (display > 1) display types. */
private val AUTO_SECONDARY_DISPLAY_TYPES =
  listOf(
    DisplayType("768p (1024\u00D7768) landscape", "768p", width = 1024, height = 768, dpi = 120),
    DisplayType("1024p (1024\u00D7768) portrait", "1024p", width = 768, height = 1024, dpi = 120),
    DisplayType("1080p (1920\u00D71080) landscape", "1080p", width = 1920, height = 1080, dpi = 320),
    DisplayType("4K (3840\u00D72160) portrait", "4K portrait", width = 2160, height = 3840, dpi = 320),
    DisplayType("4K (3840\u00D72160) landscape", "4K landscape", width = 3840, height = 2160, dpi = 320),
    DisplayType("Custom", "Custom", width = 720, height = 1080, dpi = 213, isCustom = true),
  )

/** Represents a display row in [DisplaysDialog]. */
private data class DisplayItem(
  val displayId: Int,
  val isConfigurable: Boolean,
  var width: Int,
  var height: Int,
  var dpi: Int,
  var description: String,
) {
  val densityText: String
    get() = if (dpi > 0) "$dpi dpi" else "-"

  fun toDisplayConfiguration(): DisplayConfiguration =
    DisplayConfiguration.newBuilder().setDisplay(displayId).setWidth(width).setHeight(height).setDpi(dpi).build()
}

private const val CONFIGURABLE_DISPLAY_INDEX_OFFSET = 1 // Zero display id is reserved for the primary display.
private const val MIN_DPI = 120
private const val MAX_DPI = 640
private const val MIN_DIMENSION_DP = 320
private const val MAX_RESOLUTION_LABEL = "8K"
private const val MAX_RESOLUTION_LONG = 7680
private const val MAX_RESOLUTION_SHORT = 4320
