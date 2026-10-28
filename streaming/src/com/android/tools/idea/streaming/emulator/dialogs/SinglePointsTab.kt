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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.ActivityTracker
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ValidationInfoBuilder
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.time.LocalDateTime
import java.util.Locale
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Encapsulates the "Single points" tab of [LocationDialog], including offline search, map selection, coordinate fields, and saved points.
 */
internal class SinglePointsTab(private val dialog: LocationDialog) {

  private var selectedPoint: GeoPoint = GeoPoint.DEFAULT

  internal val savedPoints: List<SavedPointItem>
    field = mutableListOf<SavedPointItem>()

  private val pointsTableModel = SavedPointsTableModel()
  private val pointsTable = SavedLocationTable(pointsTableModel, "savedPointsTable", message("location.dialog.empty.points"))

  internal val singlePointMap = MapPanel { clickedPoint ->
    onSinglePointMapClicked(clickedPoint)
  }
    .apply {
      name = "singlePointMap"
      deviceLocation = dialog.currentDeviceLocation
      selectedPoint = this@SinglePointsTab.selectedPoint
      panTo(dialog.currentDeviceLocation)
    }

  private lateinit var searchField: SearchTextField
  private lateinit var latitudeField: JBTextField
  private lateinit var longitudeField: JBTextField
  private lateinit var elevationField: JBTextField
  private lateinit var savePointButton: JButton
  private lateinit var setLocationButton: JButton
  private lateinit var importPointsGpxKmlButton: JButton

  init {
    loadSavedPointsFromStorage()

    pointsTable.apply {
      selectionModel.addListSelectionListener { event ->
        if (!event.valueIsAdjusting && !dialog.isUpdatingUiState && !dialog.isPlayingRoute) {
          onSavedPointSelectionChanged()
        }
      }
      object : DoubleClickListener() {
          override fun onDoubleClick(event: MouseEvent): Boolean {
            if (dialog.isPlayingRoute) return false
            val point = event.point
            val row = rowAtPoint(point)
            if (row < 0 || columnAtPoint(point) < 0) return false
            selectionModel.setSelectionInterval(row, row)
            editSelectedPoint()
            return true
          }
        }
        .installOn(this)
    }
  }

  internal fun createPanel(): DialogPanel {
    val coordinateChangeListener =
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          if (!dialog.isUpdatingUiState) {
            onCoordinateFieldsEdited()
          }
        }
      }

    return panel {
      row {
        // Left column: Search field, interactive offline map, and coordinate inputs
        panel {
            row {
              searchField =
                SearchTextField(false).apply {
                  name = "locationSearchField"
                  textEditor.emptyText.text = message("location.dialog.search.placeholder")
                  textEditor.addActionListener { performSinglePointSearch(text) }
                  textEditor.addKeyListener(
                    object : KeyAdapter() {
                      override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                          e.consume()
                          performSinglePointSearch(text)
                        }
                      }
                    }
                  )
                }
              cell(searchField).align(AlignX.FILL).resizableColumn()
            }

            row {
              cell(singlePointMap).align(Align.FILL)
            }
              .resizableRow()

            row {
                val latLabel = message("location.dialog.label.latitude")
                textField()
                  .label(latLabel, LabelPosition.TOP)
                  .accessibleName(latLabel.removeSuffix(":"))
                  .align(AlignX.FILL)
                  .resizableColumn()
                  .validationOnInput { validateLatitudeField(it) }
                  .applyToComponent {
                    name = "latitudeField"
                    columns = 9
                    latitudeField = this
                    document.addDocumentListener(coordinateChangeListener)
                  }

                val lonLabel = message("location.dialog.label.longitude")
                textField()
                  .label(lonLabel, LabelPosition.TOP)
                  .accessibleName(lonLabel.removeSuffix(":"))
                  .align(AlignX.FILL)
                  .resizableColumn()
                  .validationOnInput { validateLongitudeField(it) }
                  .applyToComponent {
                    name = "longitudeField"
                    columns = 9
                    longitudeField = this
                    document.addDocumentListener(coordinateChangeListener)
                  }

                val eleLabel = message("location.dialog.label.elevation")
                textField()
                  .label(eleLabel, LabelPosition.TOP)
                  .accessibleName(eleLabel.removeSuffix(":"))
                  .align(AlignX.FILL)
                  .resizableColumn()
                  .validationOnInput { validateElevationField(it) }
                  .applyToComponent {
                    name = "elevationField"
                    columns = 6
                    elevationField = this
                    document.addDocumentListener(coordinateChangeListener)
                  }
              }
              .layout(RowLayout.PARENT_GRID)
          }
          .align(Align.FILL)
          .resizableColumn()

        // Right column: Saved points table and action buttons
        panel {
            row {
              label(message("location.dialog.saved.points.label"))
            }

            row {
              cell(createSavedPointsTablePanel()).align(Align.FILL)
            }
              .resizableRow()

            row {
              importPointsGpxKmlButton =
                JButton(message("location.dialog.button.import.gpx.kml")).apply {
                  name = "importPointsGpxKmlButton"
                  addActionListener { dialog.chooseAndImportGpxKmlFile() }
                }
              cell(importPointsGpxKmlButton)

              savePointButton =
                JButton(message("location.dialog.button.save.point")).apply {
                  name = "savePointButton"
                  addActionListener { saveCurrentPointViaDialog() }
                }
              cell(savePointButton)

              @Suppress("UnstableApiUsage") placeholder().resizableColumn()

              setLocationButton =
                JButton(message("location.dialog.button.set.location")).apply {
                  name = "setLocationButton"
                  addActionListener { sendSelectedPointToDevice() }
                }
              cell(setLocationButton).align(AlignX.RIGHT)
            }
          }
          .align(Align.FILL)
          .resizableColumn()
      }
        .resizableRow()
    }
  }

  private fun createSavedPointsTablePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize().apply {
      add(
        ToolbarDecorator.createDecorator(pointsTable)
          .setAddAction { saveCurrentPointViaDialog() }
          .setEditAction { editSelectedPoint() }
          .setRemoveAction { removeSelectedPoints() }
          .setMoveDownAction(null)
          .setMoveUpAction(null)
          .setAddActionUpdater { !dialog.isPlayingRoute && isCoordinateInputValid() }
          .setEditActionUpdater { !dialog.isPlayingRoute && pointsTable.selectedRowCount == 1 }
          .setRemoveActionUpdater { !dialog.isPlayingRoute && pointsTable.selectedObjects.isNotEmpty() }
          .setToolbarPosition(ActionToolbarPosition.BOTTOM)
          .createPanel()
      )
    }
  }

  internal fun updateDeviceLocation(point: GeoPoint, panAndSelect: Boolean) {
    singlePointMap.deviceLocation = point
    if (panAndSelect) {
      selectAndPanToPoint(point)
    }
  }

  internal fun selectAndPanToPoint(point: GeoPoint) {
    selectedPoint = point
    singlePointMap.selectedPoint = point
    singlePointMap.panTo(point)
    updateCoordinateFieldsFromSelectedPoint()
  }

  internal fun updateCoordinateFieldsFromSelectedPoint() {
    if (!::latitudeField.isInitialized) return
    dialog.runUpdateUi {
      latitudeField.text = String.format(Locale.ROOT, "%.4f", selectedPoint.latitude)
      longitudeField.text = String.format(Locale.ROOT, "%.4f", selectedPoint.longitude)
      elevationField.text = String.format(Locale.ROOT, "%.1f", selectedPoint.elevation)
    }
  }

  internal fun updateButtonsState(isPlaying: Boolean) {
    val validPoint = isCoordinateInputValid()
    if (::setLocationButton.isInitialized) {
      setLocationButton.isEnabled = !isPlaying && validPoint
    }
    if (::savePointButton.isInitialized) {
      savePointButton.isEnabled = !isPlaying && validPoint
    }
    if (::importPointsGpxKmlButton.isInitialized) {
      importPointsGpxKmlButton.isEnabled = !isPlaying
    }
    pointsTable.isEnabled = !isPlaying
  }

  private fun ValidationInfoBuilder.validateLatitudeField(field: JBTextField): ValidationInfo? {
    val v = field.text.trim().toDoubleOrNull() ?: return error(message("location.dialog.error.latitude"))
    return if (v !in GeoPoint.MIN_LATITUDE..GeoPoint.MAX_LATITUDE) error(message("location.dialog.error.latitude")) else null
  }

  private fun ValidationInfoBuilder.validateLongitudeField(field: JBTextField): ValidationInfo? {
    val v = field.text.trim().toDoubleOrNull() ?: return error(message("location.dialog.error.longitude"))
    return if (v !in GeoPoint.MIN_LONGITUDE..GeoPoint.MAX_LONGITUDE) error(message("location.dialog.error.longitude")) else null
  }

  private fun ValidationInfoBuilder.validateElevationField(field: JBTextField): ValidationInfo? {
    val v = field.text.trim().toDoubleOrNull()
    return if (v == null || !v.isFinite()) error(message("location.dialog.error.elevation")) else null
  }

  private fun isCoordinateInputValid(): Boolean {
    if (!::latitudeField.isInitialized || !::longitudeField.isInitialized || !::elevationField.isInitialized) {
      return selectedPoint.isValid
    }
    val lat = latitudeField.text.trim().toDoubleOrNull() ?: return false
    val lon = longitudeField.text.trim().toDoubleOrNull() ?: return false
    val ele = elevationField.text.trim().toDoubleOrNull() ?: return false
    return GeoPoint(lat, lon, ele).isValid
  }

  private fun onCoordinateFieldsEdited() {
    dialog.clearErrorMessage()
    dialog.runUpdateUi {
      pointsTable.selectionModel.clearSelection()
    }
    val lat = latitudeField.text.trim().toDoubleOrNull()
    val lon = longitudeField.text.trim().toDoubleOrNull()
    val ele = elevationField.text.trim().toDoubleOrNull()
    if (lat != null && lon != null && ele != null) {
      val point = GeoPoint(lat, lon, ele)
      if (point.isValid) {
        selectedPoint = point
        singlePointMap.selectedPoint = point
        singlePointMap.panTo(point)
      }
    }
    dialog.updateButtonsState()
  }

  private fun onSinglePointMapClicked(point: GeoPoint) {
    dialog.clearErrorMessage()
    val pointWithElevation = point.copy(elevation = selectedPoint.elevation)
    selectedPoint = pointWithElevation
    singlePointMap.selectedPoint = pointWithElevation
    dialog.runUpdateUi {
      pointsTable.selectionModel.clearSelection()
    }
    updateCoordinateFieldsFromSelectedPoint()
    dialog.updateButtonsState()
  }

  private fun performSinglePointSearch(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    dialog.clearErrorMessage()
    val result = OfflineLocationResolver.resolveLocation(trimmed, savedPoints)
    if (result == null) {
      dialog.showErrorMessage(message("location.dialog.error.search.not.found"))
      return
    }
    val elevation = result.matchedSavedPoint?.elevation ?: selectedPoint.elevation
    selectedPoint = result.point.copy(elevation = elevation)
    singlePointMap.selectedPoint = selectedPoint
    singlePointMap.panTo(selectedPoint)
    dialog.runUpdateUi {
      val matchedIdx = result.matchedSavedPoint?.let { savedPoints.indexOf(it) } ?: -1
      if (matchedIdx >= 0) {
        val viewRow = pointsTable.convertRowIndexToView(matchedIdx)
        pointsTable.selectionModel.setSelectionInterval(viewRow, viewRow)
        TableUtil.scrollSelectionToVisible(pointsTable)
      } else {
        pointsTable.selectionModel.clearSelection()
      }
    }
    updateCoordinateFieldsFromSelectedPoint()
    dialog.updateButtonsState()
  }

  private fun onSavedPointSelectionChanged() {
    val selected = pointsTable.selectedObject ?: return
    dialog.clearErrorMessage()
    val point = GeoPoint(selected.latitude, selected.longitude, selected.elevation)
    selectedPoint = point
    singlePointMap.selectedPoint = point
    singlePointMap.panTo(point)
    updateCoordinateFieldsFromSelectedPoint()
    dialog.updateButtonsState()
  }

  /** Sends the currently selected single point to the emulator via `setGps`. */
  private fun sendSelectedPointToDevice() {
    dialog.clearErrorMessage()
    if (!isCoordinateInputValid()) return
    dialog.sendGpsLocation(selectedPoint, speed = 0.0, heading = 0.0)
    dialog.updateButtonsState()
  }

  private fun saveCurrentPointViaDialog() {
    if (!isCoordinateInputValid()) return
    val defaultName = "point_${LocalDateTime.now().format(MapPanel.DEFAULT_NAME_FORMATTER)}"
    val point = selectedPoint
    val editDialog =
      EditPointDialog(
        title = message("location.dialog.add.point.title"),
        pointName = defaultName,
        latitude = point.latitude,
        longitude = point.longitude,
        elevation = point.elevation,
        description = "",
      )
    if (editDialog.createWrapper(project = dialog.project, parent = pointsTable).showAndGet()) {
      addSavedPoint(
        SavedPointItem(
          name = editDialog.pointName.trim(),
          latitude = point.latitude,
          longitude = point.longitude,
          elevation = point.elevation,
          description = editDialog.description.trim(),
        )
      )
    }
  }

  internal fun addSavedPoint(item: SavedPointItem) {
    savedPoints.add(item)
    savePointsToStorage()
    updatePointsTableFromState(selectItem = item)
    dialog.updateButtonsState()
  }

  private fun editSelectedPoint() {
    TableUtil.stopEditing(pointsTable)
    val index = pointsTable.selectedRow.takeIf { it >= 0 }?.let { pointsTable.convertRowIndexToModel(it) } ?: return
    val item = savedPoints.getOrNull(index) ?: return
    val editDialog =
      EditPointDialog(
        title = message("location.dialog.edit.point.title"),
        pointName = item.name,
        latitude = item.latitude,
        longitude = item.longitude,
        elevation = item.elevation,
        description = item.description,
      )
    if (editDialog.createWrapper(project = dialog.project, parent = pointsTable).showAndGet()) {
      val updated = item.copy(name = editDialog.pointName.trim(), description = editDialog.description.trim())
      savedPoints[index] = updated
      savePointsToStorage()
      updatePointsTableFromState(selectItem = updated)
      dialog.updateButtonsState()
    }
  }

  private fun removeSelectedPoints() {
    TableUtil.stopEditing(pointsTable)
    val selectionModel = pointsTable.selectionModel
    val minSelectionIndex = selectionModel.minSelectionIndex
    val toRemove = pointsTable.selectedObjects
    if (toRemove.isEmpty()) return
    savedPoints.removeAll(toRemove.toSet())
    savePointsToStorage()
    val items = savedPoints.toList()
    pointsTableModel.items = items
    singlePointMap.savedPoints = items
    selectionModel.clearSelection()
    if (pointsTable.rowCount > 0) {
      val index =
        when {
          minSelectionIndex < 0 -> 0
          minSelectionIndex < pointsTable.rowCount -> minSelectionIndex
          else -> pointsTable.rowCount - 1
        }
      selectionModel.setSelectionInterval(index, index)
    }
    ActivityTracker.getInstance().inc()
    dialog.updateButtonsState()
  }

  private fun updatePointsTableFromState(selectItem: SavedPointItem? = null) {
    val selected = selectItem ?: pointsTable.selectedObject
    val items = savedPoints.toList()
    singlePointMap.savedPoints = items
    dialog.runUpdateUi {
      pointsTableModel.items = items
      val indexToSelect = items.indexOf(selected).takeIf { it >= 0 } ?: (items.size - 1)
      if (items.isNotEmpty() && indexToSelect >= 0) {
        val viewRow = pointsTable.convertRowIndexToView(indexToSelect)
        pointsTable.selectionModel.setSelectionInterval(viewRow, viewRow)
        TableUtil.scrollSelectionToVisible(pointsTable)
      }
    }
    if (selected != null) {
      onSavedPointSelectionChanged()
    }
    ActivityTracker.getInstance().inc()
  }

  private fun loadSavedPointsFromStorage() {
    val json = PropertiesComponent.getInstance().getValue(LocationDialog.SAVED_POINTS_STORAGE_KEY) ?: return
    try {
      val type = object : TypeToken<List<SavedPointItem>>() {}.type
      val loaded: List<SavedPointItem>? = gson.fromJson(json, type)
      if (loaded != null) {
        savedPoints.clear()
        savedPoints.addAll(loaded)
        val items = savedPoints.toList()
        pointsTableModel.items = items
        singlePointMap.savedPoints = items
      }
    } catch (_: Exception) {
      // Ignore corrupted preference state
    }
  }

  private fun savePointsToStorage() {
    PropertiesComponent.getInstance().setValue(LocationDialog.SAVED_POINTS_STORAGE_KEY, gson.toJson(savedPoints))
  }

  companion object {
    private val gson = Gson()
  }
}
