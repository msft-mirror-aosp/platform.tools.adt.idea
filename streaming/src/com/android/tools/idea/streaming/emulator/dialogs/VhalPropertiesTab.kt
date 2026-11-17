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

import com.android.emulation.control.incubating.VehiclePropConfig
import com.android.emulation.control.incubating.VehiclePropStatus
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyAccess
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyType
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CheckBoxListListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.text.Collator
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.VisibleForTesting

/**
 * "VHAL properties" tab of [CarDataDialog], displaying a searchable master-detail table of Vehicle HAL properties with live updates and
 * in-place value editing.
 */
internal class VhalPropertiesTab(private val owner: CarDataDialog) {

  data class VhalPropertyKey(val propId: Int, val areaId: Int)

  @VisibleForTesting data class EnumChoice(val value: Int, val label: String)

  @VisibleForTesting
  data class VhalPropertyEntry(
    val propValue: VehiclePropValue,
    val label: String,
    val areaText: String,
  ) {
    val propId: Int
      get() = propValue.prop

    val areaId: Int
      get() = propValue.areaId

    val key: VhalPropertyKey
      get() = VhalPropertyKey(propId, areaId)
  }

  private val entriesByKey = LinkedHashMap<VhalPropertyKey, VhalPropertyEntry>()
  private val propConfigs = HashMap<Int, VehiclePropConfig>()
  private var isUpdatingSelection = false
  private var isUpdatingDetailPanel = false
  private var textFieldDirty = false
  private var displayedEntry: VhalPropertyEntry? = null
  private var activeValueCard: String = CARD_TEXT
  private var int32VecKey: VhalPropertyKey? = null

  private val tableModel = VhalPropertiesTableModel()
  private val propertiesTable =
    ExtendedControlsTable(tableModel, "vhalPropertiesTable", message("car.data.dialog.empty.properties"), SORT_ORDER_STORAGE_KEY).apply {
      selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
      selectionModel.addListSelectionListener { event ->
        if (!event.valueIsAdjusting && !isUpdatingSelection) {
          if (selectedObject?.key != displayedEntry?.key) {
            commitTextFieldValue()
          }
          updateDetailPanel(selectedObject)
        }
      }
      object : DoubleClickListener() {
          override fun onDoubleClick(event: MouseEvent): Boolean {
            val row = rowAtPoint(event.point)
            if (row < 0 || columnAtPoint(event.point) < 0) return false
            selectionModel.setSelectionInterval(row, row)
            return focusValueEditor()
          }
        }
        .installOn(this)
      addKeyListener(
        object : KeyAdapter() {
          override fun keyPressed(e: KeyEvent) {
            if (e.keyCode == KeyEvent.VK_ENTER && focusValueEditor()) {
              e.consume()
            }
          }
        }
      )
    }

  private lateinit var searchField: SearchTextField
  private lateinit var refreshButton: JButton
  private lateinit var propertyNameField: JBTextField
  private lateinit var propertyIdField: JBTextField
  private lateinit var areaField: JBTextField
  private lateinit var typeField: JBTextField
  private lateinit var valueEditorContainer: JPanel
  private lateinit var valueCardLayout: VisibleCardLayout
  private lateinit var valueField: JBTextField
  private lateinit var booleanComboBox: ComboBox<Boolean>
  private lateinit var enumComboBox: ComboBox<EnumChoice>
  private lateinit var int32VecCheckBoxList: CheckBoxList<EnumChoice>
  private lateinit var int32VecScrollPane: JScrollPane
  private lateinit var descriptionArea: JBTextArea

  fun createPanel(): DialogPanel {
    val leftPanel = panel {
      row {
        searchField =
          SearchTextField(false).apply {
            name = "vhalSearchField"
            textEditor.emptyText.text = message("car.data.dialog.search.placeholder")
            addDocumentListener(
              object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                  rebuildTablePreservingSelection()
                }
              }
            )
          }
        cell(searchField).align(AlignX.FILL).resizableColumn()

        button(message("car.data.dialog.button.refresh")) {
            textFieldDirty = false
            valueField.putClientProperty("JComponent.outline", null)
            owner.clearErrorMessage()
            owner.refreshVhalProperties()
          }
          .applyToComponent {
            name = "vhalRefreshButton"
            refreshButton = this
          }
      }

      row {
        val scrollPane =
          ScrollPaneFactory.createScrollPane(propertiesTable).apply {
            preferredSize = Dimension(JBUIScale.scale(TABLE_WIDTH), JBUIScale.scale(TABLE_HEIGHT))
          }
        cell(scrollPane).align(Align.FILL)
      }
        .resizableRow()
    }
      .apply { border = JBUI.Borders.emptyRight(SPLITTER_GAP) }

    val valueLabel = message("car.data.dialog.label.value")
    val valueAccessibleName = valueLabel.removeSuffix(":")

    valueField =
      JBTextField().apply {
        name = "vhalValueField"
        isEditable = false
        accessibleContext.accessibleName = valueAccessibleName
        document.addDocumentListener(
          object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
              if (!isUpdatingDetailPanel && isEditable) {
                textFieldDirty = true
                validateTextFieldInput()
              }
            }
          }
        )
        addActionListener { commitTextFieldValue() }
        addFocusListener(
          object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
              commitTextFieldValue()
            }
          }
        )
        addKeyListener(
          object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
              if (e.keyCode == KeyEvent.VK_ESCAPE && isEditable) {
                val entry = displayedEntry ?: return
                val currentText = owner.dictionary.getValueString(entry.propValue)
                if (text != currentText || getClientProperty("JComponent.outline") != null || textFieldDirty) {
                  isUpdatingDetailPanel = true
                  try {
                    textFieldDirty = false
                    text = currentText
                    putClientProperty("JComponent.outline", null)
                    owner.clearErrorMessage()
                  } finally {
                    isUpdatingDetailPanel = false
                  }
                  e.consume()
                }
              }
            }
          }
        )
      }

    booleanComboBox =
      ComboBox(DefaultComboBoxModel(arrayOf(true, false))).apply {
        name = "vhalBooleanComboBox"
        accessibleContext.accessibleName = valueAccessibleName
        renderer = textListCellRenderer {
          if (it == true) message("car.data.dialog.value.true") else message("car.data.dialog.value.false")
        }
        addItemListener { event ->
          if (!isUpdatingDetailPanel && event.stateChange == ItemEvent.SELECTED) {
            onBooleanSelectionChanged()
          }
        }
      }

    enumComboBox =
      ComboBox<EnumChoice>().apply {
        name = "vhalEnumComboBox"
        accessibleContext.accessibleName = valueAccessibleName
        renderer = textListCellRenderer { it?.label.orEmpty() }
        addItemListener { event ->
          if (!isUpdatingDetailPanel && event.stateChange == ItemEvent.SELECTED) {
            onEnumSelectionChanged()
          }
        }
      }

    int32VecCheckBoxList =
      object : CheckBoxList<EnumChoice>(CheckBoxListListener { _, _ -> onInt32VecSelectionChanged() }) {
          override fun setItemSelected(item: EnumChoice?, selected: Boolean) {
            super.setItemSelected(item, selected)
            if (!isUpdatingDetailPanel) {
              onInt32VecSelectionChanged()
            }
          }
        }
        .apply {
          name = "vhalInt32VecCheckBoxList"
          accessibleContext.accessibleName = valueAccessibleName
        }

    int32VecScrollPane =
      ScrollPaneFactory.createScrollPane(int32VecCheckBoxList).apply {
        preferredSize = Dimension(JBUIScale.scale(CHECKBOX_LIST_WIDTH), JBUIScale.scale(CHECKBOX_LIST_HEIGHT))
      }

    valueCardLayout = VisibleCardLayout()
    valueEditorContainer =
      JPanel(valueCardLayout).apply {
        isOpaque = false
        add(valueField, CARD_TEXT)
        add(booleanComboBox, CARD_BOOLEAN)
        add(enumComboBox, CARD_ENUM)
        add(int32VecScrollPane, CARD_INT32_VEC)
        valueCardLayout.show(this, CARD_TEXT)
      }

    val rightPanel = panel {
      row {
          val nameLabel = message("car.data.dialog.label.property.name")
          textField().label(nameLabel, LabelPosition.TOP).accessibleName(nameLabel.removeSuffix(":")).align(AlignX.FILL).applyToComponent {
            name = "vhalPropertyNameField"
            isEditable = false
            propertyNameField = this
          }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          val idLabel = message("car.data.dialog.label.property.id")
          textField()
            .label(idLabel, LabelPosition.TOP)
            .accessibleName(idLabel.removeSuffix(":"))
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              name = "vhalPropertyIdField"
              isEditable = false
              columns = 10
              propertyIdField = this
            }

          val areaLabel = message("car.data.dialog.label.area")
          textField()
            .label(areaLabel, LabelPosition.TOP)
            .accessibleName(areaLabel.removeSuffix(":"))
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              name = "vhalAreaField"
              isEditable = false
              columns = 10
              areaField = this
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          val typeLabel = message("car.data.dialog.label.type")
          textField().label(typeLabel, LabelPosition.TOP).accessibleName(typeLabel.removeSuffix(":")).align(AlignX.FILL).applyToComponent {
            name = "vhalTypeField"
            isEditable = false
            typeField = this
          }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          cell(valueEditorContainer).label(valueLabel, LabelPosition.TOP).align(AlignX.FILL).onApply { commitTextFieldValue() }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          val descLabel = message("car.data.dialog.label.description")
          textArea()
            .label(descLabel, LabelPosition.TOP)
            .accessibleName(descLabel.removeSuffix(":"))
            .rows(6)
            .align(Align.FILL)
            .applyToComponent {
              name = "vhalDescriptionArea"
              isEditable = false
              lineWrap = true
              wrapStyleWord = true
              descriptionArea = this
            }
        }
        .layout(RowLayout.PARENT_GRID)
        .resizableRow()
    }
      .apply {
        border = JBUI.Borders.emptyLeft(SPLITTER_GAP)
        preferredSize = Dimension(JBUIScale.scale(DETAIL_WIDTH), JBUIScale.scale(TABLE_HEIGHT))
      }

    val splitter =
      JBSplitter(false, SPLITTER_PROPORTION).apply {
        name = "vhalPropertiesSplitter"
        firstComponent = leftPanel
        secondComponent = rightPanel
      }

    return panel {
      row { cell(splitter).align(Align.FILL) }.resizableRow()
    }
      .apply {
        border = JBUI.Borders.empty(TAB_INSET)
        rebuildTablePreservingSelection()
      }
  }

  /**
   * Replaces the table's VHAL properties with [values] (preserving any existing keys matching [retainExistingKey]) and refreshes the UI.
   */
  fun setProperties(values: Collection<VehiclePropValue>, retainExistingKey: (VhalPropertyKey) -> Boolean = { false }) {
    entriesByKey.keys.removeIf { !retainExistingKey(it) }
    for (propValue in values) {
      if (isSupportedPropertyValue(propValue)) {
        val entry = createEntry(propValue)
        entriesByKey[entry.key] = entry
      }
    }
    rebuildTablePreservingSelection()
  }

  /** Applies a single updated or streamed [VehiclePropValue] to the table and detail view. */
  fun applyPropertyValue(propValue: VehiclePropValue) {
    applyPropertyValues(listOf(propValue))
  }

  /** Applies a batch of updated or streamed [VehiclePropValue]s and removals, rebuilding the table at most once. */
  fun applyPropertyValues(
    propValues: Collection<VehiclePropValue>,
    keysToRemove: Collection<VhalPropertyKey> = emptyList(),
  ) {
    var rebuildNeeded = false
    for (key in keysToRemove) {
      if (entriesByKey.remove(key) != null) {
        rebuildNeeded = true
      }
    }
    for (propValue in propValues) {
      if (updateEntry(propValue, allowInPlaceTableUpdate = !rebuildNeeded)) {
        rebuildNeeded = true
      }
    }
    if (rebuildNeeded) {
      rebuildTablePreservingSelection()
    }
  }

  private fun updateEntry(propValue: VehiclePropValue, allowInPlaceTableUpdate: Boolean): Boolean {
    val key = VhalPropertyKey(propValue.prop, propValue.areaId)
    if (!entriesByKey.containsKey(key) && !isSupportedPropertyValue(propValue)) {
      return false
    }
    val updatedEntry = createEntry(propValue)
    val existed = entriesByKey.put(key, updatedEntry) != null
    val matchesFilter = matchesCurrentFilter(updatedEntry)
    if (existed) {
      if (!matchesFilter) {
        return false
      }
      if (allowInPlaceTableUpdate) {
        val modelIndex = tableModel.items.indexOfFirst { it.key == key }
        if (modelIndex >= 0) {
          tableModel.setItem(modelIndex, updatedEntry)
          if (propertiesTable.selectedObject?.key == key) {
            updateDetailPanel(updatedEntry)
          }
          return false
        }
      }
      return true
    }
    return matchesFilter
  }

  /** Removes a property entry with [key] if present and refreshes the table. */
  fun removeProperty(key: VhalPropertyKey) {
    if (entriesByKey.remove(key) != null) {
      rebuildTablePreservingSelection()
    }
  }

  /** Stores property configurations received from the Vehicle HAL and updates the detail panel. */
  fun setPropertyConfigs(configs: Collection<VehiclePropConfig>) {
    for (config in configs) {
      propConfigs[config.prop] = config
    }
    updateDetailPanel(propertiesTable.selectedObject)
  }

  /** Re-formats labels, areas, and descriptions after [VhalPropertyDictionary] metadata finishes loading. */
  fun onDictionaryUpdated() {
    int32VecKey = null
    val updated = entriesByKey.values.map { createEntry(it.propValue) }
    entriesByKey.clear()
    for (entry in updated) {
      entriesByKey[entry.key] = entry
    }
    rebuildTablePreservingSelection()
  }

  private fun createEntry(propValue: VehiclePropValue): VhalPropertyEntry {
    val dict = owner.dictionary
    return VhalPropertyEntry(
      propValue = propValue,
      label = dict.getPropertyLabel(propValue.prop),
      areaText = dict.getAreaString(propValue.prop, propValue.areaId),
    )
  }

  private fun rebuildTablePreservingSelection() {
    val previousKey = propertiesTable.selectedObject?.key
    val filtered = entriesByKey.values.filter(::matchesCurrentFilter).toMutableList()

    isUpdatingSelection = true
    try {
      tableModel.items = filtered
      val modelIndexToSelect =
        when {
          propertiesTable.rowCount == 0 -> -1
          previousKey != null ->
            filtered.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: propertiesTable.convertRowIndexToModel(0)
          else -> propertiesTable.convertRowIndexToModel(0)
        }
      propertiesTable.selectModelRow(modelIndexToSelect)
    } finally {
      isUpdatingSelection = false
    }
    if (propertiesTable.selectedObject?.key != displayedEntry?.key) {
      commitTextFieldValue()
    }
    updateDetailPanel(propertiesTable.selectedObject)
  }

  private fun matchesCurrentFilter(entry: VhalPropertyEntry): Boolean {
    val query = if (::searchField.isInitialized) searchField.text.trim() else ""
    if (query.isEmpty()) return true
    return entry.label.contains(query, ignoreCase = true) ||
      entry.areaText.contains(query, ignoreCase = true) ||
      entry.propId.toString().contains(query, ignoreCase = true) ||
      "0x${Integer.toUnsignedString(entry.propId, 16)}".contains(query, ignoreCase = true)
  }

  private fun updateDetailPanel(entry: VhalPropertyEntry?) {
    if (!::propertyNameField.isInitialized) return
    val keyChanged = entry?.key != displayedEntry?.key
    displayedEntry = entry
    isUpdatingDetailPanel = true
    try {
      if (keyChanged || entry == null) {
        textFieldDirty = false
        if (valueField.getClientProperty("JComponent.outline") != null) {
          valueField.putClientProperty("JComponent.outline", null)
          owner.clearErrorMessage()
        }
      }
      if (entry == null) {
        propertyNameField.text = ""
        propertyIdField.text = ""
        areaField.text = ""
        typeField.text = ""
        valueField.text = ""
        valueField.isEditable = false
        descriptionArea.text = ""
        showValueCard(CARD_TEXT)
        return
      }
      val dict = owner.dictionary
      val propId = entry.propId
      val propValue = entry.propValue
      val editable = isEditable(entry)
      val valueType = dict.getValueType(propId)
      val lookupTable = dict.getLookupTable(propId)
      val usesTextCard =
        !editable ||
          (valueType != VehiclePropertyType.BOOLEAN &&
            !(valueType == VehiclePropertyType.INT32 && !lookupTable.isNullOrEmpty()) &&
            valueType != VehiclePropertyType.INT32_VEC)
      val preserveUserTextEdit = !keyChanged && textFieldDirty && editable && usesTextCard

      propertyNameField.text = entry.label
      propertyNameField.caretPosition = 0
      propertyIdField.text = propId.toString()
      areaField.text = entry.areaText
      typeField.text = dict.getValueTypeLabel(propId)
      if (!preserveUserTextEdit) {
        textFieldDirty = false
        valueField.text = dict.getValueString(propValue)
        valueField.caretPosition = 0
        valueField.putClientProperty("JComponent.outline", null)
      }
      descriptionArea.text = dict.getDetailedDescription(propId)
      descriptionArea.caretPosition = 0

      when {
        editable && valueType == VehiclePropertyType.BOOLEAN -> {
          val current = propValue.int32ValuesCount > 0 && propValue.getInt32Values(0) != 0
          if (booleanComboBox.selectedItem != current) {
            booleanComboBox.selectedItem = current
          }
          valueField.isEditable = false
          showValueCard(CARD_BOOLEAN)
        }
        editable && valueType == VehiclePropertyType.INT32 && !lookupTable.isNullOrEmpty() -> {
          val current = if (propValue.int32ValuesCount > 0) propValue.getInt32Values(0) else 0
          val choices = buildList {
            for ((k, v) in lookupTable) {
              add(EnumChoice(k, v))
            }
            if (propValue.int32ValuesCount > 0 && !lookupTable.containsKey(current)) {
              add(EnumChoice(current, dict.getValueString(propValue)))
            }
          }
          val selectedChoice = choices.firstOrNull { it.value == current } ?: choices.first()
          val currentChoices = (0 until enumComboBox.itemCount).map { enumComboBox.getItemAt(it) }
          if (currentChoices != choices) {
            enumComboBox.model = DefaultComboBoxModel(choices.toTypedArray())
          }
          if (enumComboBox.selectedItem != selectedChoice) {
            enumComboBox.selectedItem = selectedChoice
          }
          valueField.isEditable = false
          showValueCard(CARD_ENUM)
        }
        editable && valueType == VehiclePropertyType.INT32_VEC -> {
          val currentSet = propValue.int32ValuesList.toSet()
          val table = lookupTable.orEmpty()
          val existingValues = (0 until int32VecCheckBoxList.itemsCount).mapNotNull { int32VecCheckBoxList.getItemAt(it)?.value }.toSet()
          if (int32VecKey != entry.key || !existingValues.containsAll(currentSet) || !existingValues.containsAll(table.keys)) {
            int32VecKey = entry.key
            int32VecCheckBoxList.clear()
            for ((k, v) in table) {
              int32VecCheckBoxList.addItem(EnumChoice(k, v), v, k in currentSet)
            }
            for (v in currentSet) {
              if (!table.containsKey(v)) {
                val label = v.toString()
                int32VecCheckBoxList.addItem(EnumChoice(v, label), label, true)
              }
            }
          } else {
            for (i in 0 until int32VecCheckBoxList.itemsCount) {
              val item = int32VecCheckBoxList.getItemAt(i) ?: continue
              int32VecCheckBoxList.setItemSelected(item, item.value in currentSet)
            }
            int32VecCheckBoxList.repaint()
          }
          valueField.isEditable = false
          showValueCard(CARD_INT32_VEC)
        }
        else -> {
          valueField.isEditable = editable
          showValueCard(CARD_TEXT)
        }
      }
    } finally {
      isUpdatingDetailPanel = false
    }
  }

  private fun showValueCard(cardName: String) {
    val cardChanged = activeValueCard != cardName
    activeValueCard = cardName
    valueCardLayout.show(valueEditorContainer, cardName)
    if (cardChanged) {
      valueEditorContainer.revalidate()
      valueEditorContainer.repaint()
    }
  }

  private fun validateTextFieldInput() {
    val entry = displayedEntry ?: return
    val errorMsg =
      when (owner.dictionary.getValueType(entry.propId)) {
        VehiclePropertyType.INT32 ->
          if (valueField.text.trim().toIntOrNull() == null) message("car.data.dialog.error.invalid.integer") else null
        VehiclePropertyType.INT64 ->
          if (valueField.text.trim().toLongOrNull() == null) message("car.data.dialog.error.invalid.integer") else null
        VehiclePropertyType.FLOAT ->
          if (valueField.text.trim().toFloatOrNull()?.takeIf(Float::isFinite) == null) message("car.data.dialog.error.invalid.number")
          else null
        else -> null
      }
    valueField.putClientProperty("JComponent.outline", if (errorMsg != null) "error" else null)
    if (errorMsg != null) {
      owner.showErrorMessage(errorMsg)
    } else {
      owner.clearErrorMessage()
    }
  }

  private fun commitTextFieldValue() {
    if (isUpdatingDetailPanel || !::valueField.isInitialized || !valueField.isEditable || activeValueCard != CARD_TEXT) {
      return
    }
    val entry = displayedEntry ?: return
    if (!isEditable(entry)) {
      return
    }
    val propId = entry.propId
    val propValue = entry.propValue
    val builder = VehiclePropValue.newBuilder().setProp(propId).setAreaId(entry.areaId).setStatus(VehiclePropStatus.AVAILABLE)

    when (owner.dictionary.getValueType(propId)) {
      VehiclePropertyType.INT32 -> {
        val parsed = valueField.text.trim().toIntOrNull()
        if (parsed == null) {
          valueField.putClientProperty("JComponent.outline", "error")
          owner.showErrorMessage(message("car.data.dialog.error.invalid.integer"))
          return
        }
        val current = if (propValue.int32ValuesCount > 0) propValue.getInt32Values(0) else null
        textFieldDirty = false
        valueField.putClientProperty("JComponent.outline", null)
        owner.clearErrorMessage()
        if (parsed == current && propValue.status == VehiclePropStatus.AVAILABLE) {
          resetTextFieldToEntry(propValue)
          return
        }
        builder.addInt32Values(parsed)
      }
      VehiclePropertyType.INT64 -> {
        val parsed = valueField.text.trim().toLongOrNull()
        if (parsed == null) {
          valueField.putClientProperty("JComponent.outline", "error")
          owner.showErrorMessage(message("car.data.dialog.error.invalid.integer"))
          return
        }
        val current = if (propValue.int64ValuesCount > 0) propValue.getInt64Values(0) else null
        textFieldDirty = false
        valueField.putClientProperty("JComponent.outline", null)
        owner.clearErrorMessage()
        if (parsed == current && propValue.status == VehiclePropStatus.AVAILABLE) {
          resetTextFieldToEntry(propValue)
          return
        }
        builder.addInt64Values(parsed)
      }
      VehiclePropertyType.FLOAT -> {
        val parsed = valueField.text.trim().toFloatOrNull()?.takeIf(Float::isFinite)
        if (parsed == null) {
          valueField.putClientProperty("JComponent.outline", "error")
          owner.showErrorMessage(message("car.data.dialog.error.invalid.number"))
          return
        }
        val current = if (propValue.floatValuesCount > 0) propValue.getFloatValues(0) else null
        textFieldDirty = false
        valueField.putClientProperty("JComponent.outline", null)
        owner.clearErrorMessage()
        if (parsed == current && propValue.status == VehiclePropStatus.AVAILABLE) {
          resetTextFieldToEntry(propValue)
          return
        }
        builder.addFloatValues(parsed)
      }
      VehiclePropertyType.STRING -> {
        val newText = valueField.text
        textFieldDirty = false
        valueField.putClientProperty("JComponent.outline", null)
        owner.clearErrorMessage()
        if (newText == propValue.stringValue && propValue.status == VehiclePropStatus.AVAILABLE) {
          return
        }
        builder.stringValue = newText
      }
      else -> return
    }
    owner.sendPropertyValue(builder.build())
  }

  private fun resetTextFieldToEntry(propValue: VehiclePropValue) {
    isUpdatingDetailPanel = true
    try {
      valueField.text = owner.dictionary.getValueString(propValue)
      valueField.caretPosition = 0
    } finally {
      isUpdatingDetailPanel = false
    }
  }

  private fun onBooleanSelectionChanged() {
    val entry = displayedEntry ?: return
    if (!isEditable(entry)) return
    val selected = (booleanComboBox.selectedItem as? Boolean) ?: return
    val newInt = if (selected) 1 else 0
    val currentInt = if (entry.propValue.int32ValuesCount > 0) entry.propValue.getInt32Values(0) else null
    if (newInt == currentInt && entry.propValue.status == VehiclePropStatus.AVAILABLE) {
      return
    }
    val updated =
      VehiclePropValue.newBuilder()
        .setProp(entry.propId)
        .setAreaId(entry.areaId)
        .setStatus(VehiclePropStatus.AVAILABLE)
        .addInt32Values(newInt)
        .build()
    owner.sendPropertyValue(updated)
  }

  private fun onEnumSelectionChanged() {
    val entry = displayedEntry ?: return
    if (!isEditable(entry)) return
    val choice = (enumComboBox.selectedItem as? EnumChoice) ?: return
    val currentInt = if (entry.propValue.int32ValuesCount > 0) entry.propValue.getInt32Values(0) else null
    if (choice.value == currentInt && entry.propValue.status == VehiclePropStatus.AVAILABLE) {
      return
    }
    val updated =
      VehiclePropValue.newBuilder()
        .setProp(entry.propId)
        .setAreaId(entry.areaId)
        .setStatus(VehiclePropStatus.AVAILABLE)
        .addInt32Values(choice.value)
        .build()
    owner.sendPropertyValue(updated)
  }

  private fun onInt32VecSelectionChanged() {
    if (isUpdatingDetailPanel) return
    val entry = displayedEntry ?: return
    if (!isEditable(entry)) return
    val builder = VehiclePropValue.newBuilder().setProp(entry.propId).setAreaId(entry.areaId).setStatus(VehiclePropStatus.AVAILABLE)
    for (i in 0 until int32VecCheckBoxList.itemsCount) {
      val item = int32VecCheckBoxList.getItemAt(i)
      if (item != null && int32VecCheckBoxList.isItemSelected(item)) {
        builder.addInt32Values(item.value)
      }
    }
    val updated = builder.build()
    if (updated.int32ValuesList == entry.propValue.int32ValuesList && entry.propValue.status == VehiclePropStatus.AVAILABLE) {
      return
    }
    owner.sendPropertyValue(updated)
  }

  private fun isEditable(entry: VhalPropertyEntry): Boolean {
    val config = propConfigs[entry.propId]
    if (config != null && config.hasAccess() && config.access == VehiclePropertyAccess.WRITE) {
      return false
    }
    return owner.dictionary.isEditable(entry.propId)
  }

  private fun focusValueEditor(): Boolean {
    val selected = propertiesTable.selectedObject ?: return false
    if (!isEditable(selected)) return false
    val target =
      when (activeValueCard) {
        CARD_BOOLEAN -> booleanComboBox
        CARD_ENUM -> enumComboBox
        CARD_INT32_VEC -> int32VecCheckBoxList
        else -> valueField.apply { selectAll() }
      }
    target.requestFocusInWindow()
    return true
  }

  /** [CardLayout] whose preferred and minimum sizes match only the currently visible card component. */
  private class VisibleCardLayout : CardLayout() {
    override fun preferredLayoutSize(parent: Container): Dimension {
      synchronized(parent.treeLock) {
        val visible = parent.components.firstOrNull { it.isVisible } ?: return super.preferredLayoutSize(parent)
        val insets = parent.insets
        val pref = visible.preferredSize
        return Dimension(pref.width + insets.left + insets.right, pref.height + insets.top + insets.bottom)
      }
    }

    override fun minimumLayoutSize(parent: Container): Dimension {
      synchronized(parent.treeLock) {
        val visible = parent.components.firstOrNull { it.isVisible } ?: return super.minimumLayoutSize(parent)
        val insets = parent.insets
        val min = visible.minimumSize
        return Dimension(min.width + insets.left + insets.right, min.height + insets.top + insets.bottom)
      }
    }
  }

  /** Table model for VHAL property rows. */
  private class VhalPropertiesTableModel : ListTableModel<VhalPropertyEntry>(PROPERTY_COLUMN, AREA_COLUMN, ID_COLUMN) {

    companion object {
      private val collator = Collator.getInstance()

      private val PROPERTY_COLUMN =
        object : TextColumnInfo<VhalPropertyEntry>(message("car.data.dialog.column.property")) {
          private val comparator =
            compareBy(collator, VhalPropertyEntry::label).thenBy(VhalPropertyEntry::areaId).thenBy(VhalPropertyEntry::propId)

          override fun valueOf(item: VhalPropertyEntry): String = item.label

          override fun getComparator(): Comparator<VhalPropertyEntry> = comparator
        }

      private val AREA_COLUMN =
        object : TextColumnInfo<VhalPropertyEntry>(message("car.data.dialog.column.area")) {
          private val comparator =
            compareBy(collator, VhalPropertyEntry::areaText)
              .thenBy(collator, VhalPropertyEntry::label)
              .thenBy(VhalPropertyEntry::areaId)
              .thenBy(VhalPropertyEntry::propId)

          override fun valueOf(item: VhalPropertyEntry): String = item.areaText

          override fun getComparator(): Comparator<VhalPropertyEntry> = comparator
        }

      private val ID_COLUMN =
        object : TextColumnInfo<VhalPropertyEntry>(message("car.data.dialog.column.id")) {
          private val comparator = compareBy(VhalPropertyEntry::propId).thenBy(VhalPropertyEntry::areaId)

          override fun valueOf(item: VhalPropertyEntry): String = item.propId.toString()

          override fun getComparator(): Comparator<VhalPropertyEntry> = comparator
        }
    }
  }

  companion object {
    @NonNls @VisibleForTesting const val SORT_ORDER_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.vhal.properties.sort.order"
    private const val TAB_INSET = 8
    private const val SPLITTER_GAP = 6
    private const val SPLITTER_PROPORTION = 0.56f
    private const val TABLE_WIDTH = 360
    private const val TABLE_HEIGHT = 360
    private const val DETAIL_WIDTH = 300
    private const val CHECKBOX_LIST_WIDTH = 280
    private const val CHECKBOX_LIST_HEIGHT = 110

    private const val CARD_TEXT = "text"
    private const val CARD_BOOLEAN = "boolean"
    private const val CARD_ENUM = "enum"
    private const val CARD_INT32_VEC = "int32Vec"

    fun isSupportedPropertyValue(propValue: VehiclePropValue): Boolean {
      if (propValue.prop == 0) return false
      if (propValue.status == VehiclePropStatus.AVAILABLE) return true
      return propValue.timestamp != 0L ||
        propValue.int32ValuesCount > 0 ||
        propValue.floatValuesCount > 0 ||
        propValue.int64ValuesCount > 0 ||
        !propValue.bytesValue.isEmpty ||
        propValue.stringValue.isNotEmpty()
    }
  }
}
