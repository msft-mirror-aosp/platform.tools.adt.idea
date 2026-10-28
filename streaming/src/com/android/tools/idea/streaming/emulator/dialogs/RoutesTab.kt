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
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.setToolTipText
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.time.LocalDateTime
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback speed multipliers supported by the emulator location UI. */
internal enum class PlaybackSpeed(val multiplier: Int, val displayName: String) {
  SPEED_1X(1, "1X"),
  SPEED_2X(2, "2X"),
  SPEED_3X(3, "3X"),
  SPEED_4X(4, "4X"),
  SPEED_5X(5, "5X");

  override fun toString(): String = displayName
}

/** Encapsulates the "Routes" tab of [LocationDialog], including offline route calculation, saved routes, and route playback. */
internal class RoutesTab(private val dialog: LocationDialog) {

  private val savedRoutes = mutableListOf<SavedRouteItem>()
  private val routesTableModel = SavedRoutesTableModel()
  private val routesTable = SavedLocationTable(routesTableModel, "savedRoutesTable", message("location.dialog.empty.routes"))

  internal val routesMap = MapPanel { clickedPoint ->
    onRoutesMapClicked(clickedPoint)
  }
    .apply {
      name = "routesMap"
      deviceLocation = dialog.currentDeviceLocation
      panTo(dialog.currentDeviceLocation)
    }

  private var activeRoute: ComputedRoute? = null
  private var routeOriginPoint: GeoPoint? = null
  private var routeDestinationPoint: GeoPoint? = null
  private var editingDestinationOnMapClick: Boolean = false
  private var travelMode: TravelMode = TravelMode.DRIVING
  private var playbackSpeed: PlaybackSpeed = PlaybackSpeed.SPEED_1X
  private var repeatPlayback: Boolean = false
  private var playbackJob: Job? = null

  internal val isPlaying: Boolean
    get() = playbackJob != null

  private lateinit var travelModeComboBox: ComboBox<TravelMode>
  private lateinit var originField: JBTextField
  private lateinit var destinationField: JBTextField
  private lateinit var swapWaypointsButton: JButton
  private lateinit var findRouteButton: JButton
  private lateinit var saveRouteButton: JButton
  private lateinit var repeatPlaybackCheckBox: JBCheckBox
  private lateinit var playbackSpeedComboBox: ComboBox<PlaybackSpeed>
  private lateinit var importRoutesGpxKmlButton: JButton
  private lateinit var playRouteButton: JButton

  init {
    loadSavedRoutesFromStorage()

    routesTable.apply {
      selectionModel.addListSelectionListener { event ->
        if (!event.valueIsAdjusting && !dialog.isUpdatingUiState && !isPlaying) {
          onSavedRouteSelectionChanged()
        }
      }
      object : DoubleClickListener() {
          override fun onDoubleClick(event: MouseEvent): Boolean {
            if (isPlaying) return false
            val point = event.point
            val row = rowAtPoint(point)
            if (row < 0 || columnAtPoint(point) < 0) return false
            selectionModel.setSelectionInterval(row, row)
            editSelectedRoute()
            return true
          }
        }
        .installOn(this)
    }
  }

  internal fun createPanel(): DialogPanel {
    return panel {
      row {
        // Left column: Route creation controls + interactive offline map
        panel {
            row {
              val modeLabel = message("location.dialog.label.travel.mode")
              comboBox(DefaultComboBoxModel(TravelMode.entries.toTypedArray()), renderer = textListCellRenderer { it?.displayName ?: "" })
                .label(modeLabel, LabelPosition.LEFT)
                .accessibleName(modeLabel.removeSuffix(":"))
                .applyToComponent {
                  name = "travelModeComboBox"
                  travelModeComboBox = this
                  selectedItem = travelMode
                  addItemListener { event ->
                    if (!dialog.isUpdatingUiState && event.stateChange == ItemEvent.SELECTED) {
                      val newMode = event.item as? TravelMode ?: return@addItemListener
                      if (travelMode != newMode) {
                        travelMode = newMode
                        recalculateRouteIfWaypointsSet()
                      }
                    }
                  }
                }

              @Suppress("UnstableApiUsage") placeholder().resizableColumn()

              swapWaypointsButton =
                JButton(AllIcons.Actions.SwapPanels).apply {
                  name = "swapWaypointsButton"
                  setToolTipText(HtmlChunk.text(message("location.dialog.button.swap.waypoints")))
                  addActionListener { swapRouteWaypoints() }
                }
              cell(swapWaypointsButton)

              findRouteButton =
                JButton(message("location.dialog.button.find.route")).apply {
                  name = "findRouteButton"
                  addActionListener { searchAndCalculateRoute() }
                }
              cell(findRouteButton)

              saveRouteButton =
                JButton(message("location.dialog.button.save.route")).apply {
                  name = "saveRouteButton"
                  addActionListener { saveCurrentRouteViaDialog() }
                }
              cell(saveRouteButton)
            }

            row(message("location.dialog.label.origin")) {
              originField =
                JBTextField().apply {
                  name = "originField"
                  emptyText.text = message("location.dialog.search.placeholder")
                  addFocusListener(
                    object : FocusAdapter() {
                      override fun focusGained(e: FocusEvent) {
                        editingDestinationOnMapClick = false
                      }
                    }
                  )
                  addKeyListener(
                    object : KeyAdapter() {
                      override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                          e.consume()
                          searchAndCalculateRoute()
                        }
                      }
                    }
                  )
                }
              cell(originField).align(AlignX.FILL)
            }

            row(message("location.dialog.label.destination")) {
              destinationField =
                JBTextField().apply {
                  name = "destinationField"
                  emptyText.text = message("location.dialog.search.placeholder")
                  addFocusListener(
                    object : FocusAdapter() {
                      override fun focusGained(e: FocusEvent) {
                        editingDestinationOnMapClick = true
                      }
                    }
                  )
                  addKeyListener(
                    object : KeyAdapter() {
                      override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                          e.consume()
                          searchAndCalculateRoute()
                        }
                      }
                    }
                  )
                }
              cell(destinationField).align(AlignX.FILL)
            }

            row {
              cell(routesMap).align(Align.FILL)
            }
              .resizableRow()
          }
          .align(Align.FILL)
          .resizableColumn()

        // Right column: Saved routes table, playback options, and Play/Stop button
        panel {
            row {
              label(message("location.dialog.saved.routes.label"))
            }

            row {
              cell(createSavedRoutesTablePanel()).align(Align.FILL)
            }
              .resizableRow()

            row {
              repeatPlaybackCheckBox =
                JBCheckBox(message("location.dialog.checkbox.repeat.playback"), repeatPlayback).apply {
                  name = "repeatPlaybackCheckBox"
                  addActionListener { repeatPlayback = isSelected }
                }
              cell(repeatPlaybackCheckBox)

              @Suppress("UnstableApiUsage") placeholder().resizableColumn()

              val speedLabel = message("location.dialog.label.playback.speed")
              comboBox(
                  DefaultComboBoxModel(PlaybackSpeed.entries.toTypedArray()),
                  renderer = textListCellRenderer { it?.displayName ?: "" },
                )
                .label(speedLabel, LabelPosition.LEFT)
                .accessibleName(speedLabel.removeSuffix(":"))
                .applyToComponent {
                  name = "playbackSpeedComboBox"
                  playbackSpeedComboBox = this
                  selectedItem = playbackSpeed
                  addItemListener { event ->
                    if (!dialog.isUpdatingUiState && event.stateChange == ItemEvent.SELECTED) {
                      (event.item as? PlaybackSpeed)?.let { playbackSpeed = it }
                    }
                  }
                }
            }

            row {
              importRoutesGpxKmlButton =
                JButton(message("location.dialog.button.import.gpx.kml")).apply {
                  name = "importRoutesGpxKmlButton"
                  addActionListener { dialog.chooseAndImportGpxKmlFile() }
                }
              cell(importRoutesGpxKmlButton)

              @Suppress("UnstableApiUsage") placeholder().resizableColumn()

              playRouteButton =
                JButton(message("location.dialog.button.play.route")).apply {
                  name = "playRouteButton"
                  addActionListener { toggleRoutePlayback() }
                }
              cell(playRouteButton).align(AlignX.RIGHT)
            }
          }
          .align(Align.FILL)
          .resizableColumn()
      }
        .resizableRow()
    }
  }

  private fun createSavedRoutesTablePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize().apply {
      add(
        ToolbarDecorator.createDecorator(routesTable)
          .setAddAction { saveCurrentRouteViaDialog() }
          .setEditAction { editSelectedRoute() }
          .setRemoveAction { removeSelectedRoutes() }
          .setMoveDownAction(null)
          .setMoveUpAction(null)
          .setAddActionUpdater { !isPlaying && activeRoute?.points?.isNotEmpty() == true }
          .setEditActionUpdater { !isPlaying && routesTable.selectedRowCount == 1 }
          .setRemoveActionUpdater { !isPlaying && routesTable.selectedObjects.isNotEmpty() }
          .setToolbarPosition(ActionToolbarPosition.BOTTOM)
          .createPanel()
      )
    }
  }

  internal fun updateDeviceLocation(point: GeoPoint, pan: Boolean) {
    routesMap.deviceLocation = point
    if (pan) {
      routesMap.panTo(point)
    }
  }

  internal fun updateButtonsState(isPlaying: Boolean) {
    if (::importRoutesGpxKmlButton.isInitialized) {
      importRoutesGpxKmlButton.isEnabled = !isPlaying
    }
    if (::travelModeComboBox.isInitialized) {
      travelModeComboBox.isEnabled = !isPlaying
    }
    if (::originField.isInitialized) {
      originField.isEnabled = !isPlaying
    }
    if (::destinationField.isInitialized) {
      destinationField.isEnabled = !isPlaying
    }
    if (::findRouteButton.isInitialized) {
      findRouteButton.isEnabled = !isPlaying
    }
    if (::swapWaypointsButton.isInitialized) {
      swapWaypointsButton.isEnabled = !isPlaying
    }
    val hasActiveRoutePoints = activeRoute?.points?.isNotEmpty() == true
    if (::saveRouteButton.isInitialized) {
      saveRouteButton.isEnabled = !isPlaying && hasActiveRoutePoints
    }
    routesTable.isEnabled = !isPlaying
    if (::playRouteButton.isInitialized) {
      playRouteButton.text = if (isPlaying) message("location.dialog.button.stop.route") else message("location.dialog.button.play.route")
      playRouteButton.isEnabled = isPlaying || hasActiveRoutePoints
    }
  }

  private fun onRoutesMapClicked(point: GeoPoint) {
    if (isPlaying) return
    dialog.clearErrorMessage()
    if (!editingDestinationOnMapClick || routeOriginPoint == null) {
      routeOriginPoint = point
      originField.text = point.formatCoordinates()
      routesMap.routeOrigin = point
      editingDestinationOnMapClick = true
    } else {
      routeDestinationPoint = point
      destinationField.text = point.formatCoordinates()
      routesMap.routeDestination = point
      editingDestinationOnMapClick = false
    }
    recalculateRouteIfWaypointsSet()
  }

  private fun swapRouteWaypoints() {
    if (isPlaying) return
    val oldOriginText = originField.text
    originField.text = destinationField.text
    destinationField.text = oldOriginText

    val oldOriginPoint = routeOriginPoint
    routeOriginPoint = routeDestinationPoint
    routeDestinationPoint = oldOriginPoint
    routesMap.routeOrigin = routeOriginPoint
    routesMap.routeDestination = routeDestinationPoint
    recalculateRouteIfWaypointsSet()
  }

  /** Resolves the origin and destination queries offline and calculates a direct route between them. */
  private fun searchAndCalculateRoute() {
    if (isPlaying) return
    dialog.clearErrorMessage()
    val originQuery = originField.text.trim()
    val destQuery = destinationField.text.trim()
    if (originQuery.isEmpty() || destQuery.isEmpty()) {
      dialog.showErrorMessage(message("location.dialog.error.route.not.found"))
      return
    }
    val originResult = OfflineLocationResolver.resolveLocation(originQuery, dialog.savedPoints)
    val destResult = OfflineLocationResolver.resolveLocation(destQuery, dialog.savedPoints)
    if (originResult == null || destResult == null) {
      dialog.showErrorMessage(message("location.dialog.error.route.not.found"))
      return
    }
    routeOriginPoint = originResult.point
    routeDestinationPoint = destResult.point
    val computed =
      OfflineLocationResolver.computeDirectRoute(
        origin = originResult.point,
        destination = destResult.point,
        mode = travelMode,
        originLabel = originResult.label,
        destinationLabel = destResult.label,
      )
    dialog.runUpdateUi {
      routesTable.selectionModel.clearSelection()
    }
    setActiveRouteOnMap(computed)
  }

  private fun recalculateRouteIfWaypointsSet() {
    val origin = routeOriginPoint ?: return
    val dest = routeDestinationPoint ?: return
    val computed = OfflineLocationResolver.computeDirectRoute(origin, dest, travelMode)
    dialog.runUpdateUi {
      routesTable.selectionModel.clearSelection()
    }
    setActiveRouteOnMap(computed)
  }

  private fun setActiveRouteOnMap(route: ComputedRoute) {
    activeRoute = route
    val geoPoints = route.points.map { GeoPoint(it.latitude, it.longitude, it.elevation) }
    routesMap.routeOrigin = geoPoints.firstOrNull()
    routesMap.routeDestination = geoPoints.lastOrNull()
    routesMap.routePoints = geoPoints
    routesMap.fitBounds(geoPoints)
    dialog.updateButtonsState()
  }

  private fun onSavedRouteSelectionChanged() {
    if (isPlaying) return
    val selected = routesTable.selectedObject ?: return
    dialog.clearErrorMessage()
    travelMode = selected.mode
    val firstPoint = selected.points.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude, it.elevation) }
    val lastPoint = selected.points.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude, it.elevation) }
    routeOriginPoint = firstPoint
    routeDestinationPoint = lastPoint
    dialog.runUpdateUi {
      if (::travelModeComboBox.isInitialized) {
        travelModeComboBox.selectedItem = selected.mode
      }
      if (::originField.isInitialized) {
        originField.text = firstPoint?.formatCoordinates().orEmpty()
      }
      if (::destinationField.isInitialized) {
        destinationField.text = lastPoint?.formatCoordinates().orEmpty()
      }
    }
    val computed =
      ComputedRoute(
        points = selected.points,
        durationSeconds = selected.durationSeconds,
        originLabel = selected.description,
        destinationLabel = "",
        mode = selected.mode,
      )
    setActiveRouteOnMap(computed)
  }

  private fun saveCurrentRouteViaDialog() {
    val route = activeRoute?.takeIf { it.points.isNotEmpty() } ?: return
    val defaultName = "route_${LocalDateTime.now().format(MapPanel.DEFAULT_NAME_FORMATTER)}"
    val defaultDesc =
      if (route.originLabel.isNotBlank() && route.destinationLabel.isNotBlank()) {
        "${route.originLabel} - ${route.destinationLabel}"
      } else {
        route.originLabel
      }
    val editDialog =
      EditRouteDialog(
        title = message("location.dialog.save.route.title"),
        routeName = defaultName,
        description = defaultDesc,
      )
    if (editDialog.createWrapper(project = dialog.project, parent = routesTable).showAndGet()) {
      addSavedRoute(
        SavedRouteItem(
          name = editDialog.routeName.trim(),
          description = editDialog.description.trim(),
          mode = route.mode,
          durationSeconds = route.durationSeconds,
          points = route.points,
        )
      )
    }
  }

  internal fun addSavedRoute(item: SavedRouteItem) {
    savedRoutes.add(item)
    saveRoutesToStorage()
    updateRoutesTableFromState(selectItem = item)
    dialog.updateButtonsState()
  }

  private fun editSelectedRoute() {
    if (isPlaying) return
    TableUtil.stopEditing(routesTable)
    val index = routesTable.selectedRow.takeIf { it >= 0 }?.let { routesTable.convertRowIndexToModel(it) } ?: return
    val item = savedRoutes.getOrNull(index) ?: return
    val editDialog =
      EditRouteDialog(
        title = message("location.dialog.edit.route.title"),
        routeName = item.name,
        description = item.description,
      )
    if (editDialog.createWrapper(project = dialog.project, parent = routesTable).showAndGet()) {
      val updated = item.copy(name = editDialog.routeName.trim(), description = editDialog.description.trim())
      savedRoutes[index] = updated
      saveRoutesToStorage()
      updateRoutesTableFromState(selectItem = updated)
      dialog.updateButtonsState()
    }
  }

  private fun removeSelectedRoutes() {
    TableUtil.stopEditing(routesTable)
    val selectionModel = routesTable.selectionModel
    val minSelectionIndex = selectionModel.minSelectionIndex
    val toRemove = routesTable.selectedObjects
    if (toRemove.isEmpty()) return
    savedRoutes.removeAll(toRemove.toSet())
    saveRoutesToStorage()
    routesTableModel.items = savedRoutes.toList()
    selectionModel.clearSelection()
    if (routesTable.rowCount > 0) {
      val index =
        when {
          minSelectionIndex < 0 -> 0
          minSelectionIndex < routesTable.rowCount -> minSelectionIndex
          else -> routesTable.rowCount - 1
        }
      selectionModel.setSelectionInterval(index, index)
    } else {
      activeRoute = null
      routeOriginPoint = null
      routeDestinationPoint = null
      routesMap.routeOrigin = null
      routesMap.routeDestination = null
      routesMap.routePoints = emptyList()
      dialog.runUpdateUi {
        if (::originField.isInitialized) {
          originField.text = ""
        }
        if (::destinationField.isInitialized) {
          destinationField.text = ""
        }
      }
    }
    ActivityTracker.getInstance().inc()
    dialog.updateButtonsState()
  }

  private fun updateRoutesTableFromState(selectItem: SavedRouteItem? = null) {
    val selected = selectItem ?: routesTable.selectedObject
    val items = savedRoutes.toList()
    dialog.runUpdateUi {
      routesTableModel.items = items
      val indexToSelect = items.indexOf(selected).takeIf { it >= 0 } ?: (items.size - 1)
      if (items.isNotEmpty() && indexToSelect >= 0) {
        val viewRow = routesTable.convertRowIndexToView(indexToSelect)
        routesTable.selectionModel.setSelectionInterval(viewRow, viewRow)
        TableUtil.scrollSelectionToVisible(routesTable)
      }
    }
    if (selected != null) {
      onSavedRouteSelectionChanged()
    }
    ActivityTracker.getInstance().inc()
  }

  private fun toggleRoutePlayback() {
    if (isPlaying) {
      stopRoutePlayback()
    } else {
      startRoutePlayback()
    }
  }

  /**
   * Starts playing the active route using the interpolation and bearing/velocity algorithm from `LocationPage::timeout_v2` in
   * `location-page.cpp`.
   */
  internal fun startRoutePlayback(updateInterval: Duration = PLAYBACK_UPDATE_INTERVAL) {
    val route = activeRoute ?: return
    val points = route.points
    if (points.isEmpty()) return
    stopRoutePlayback()
    dialog.markStateModified()

    playbackJob =
      dialog.dialogCoroutineScope.launch(dialog.dialogUiContext) {
        var reachedDestination = false
        try {
          do {
            reachedDestination = false
            if (points.size == 1) {
              val pt = points[0]
              dialog.sendGpsLocation(GeoPoint(pt.latitude, pt.longitude, pt.elevation), speed = 0.0, heading = 0.0)
              delay(updateInterval)
              continue
            }

            var nextIdx = 1
            var segmentDuration = Duration.ZERO
            var timeIntoSegment = Duration.ZERO
            var velocityOnRoute = 0.0
            var headingOnRoute = 0.0
            var prev = points[0]
            var next = points[1]

            while (isActive) {
              val latNow: Double
              val lngNow: Double
              val altNow: Double

              if (timeIntoSegment >= segmentDuration) {
                if (nextIdx >= points.size) {
                  if (repeatPlayback) {
                    val last = points.last()
                    dialog.sendGpsLocation(GeoPoint(last.latitude, last.longitude, last.elevation), velocityOnRoute, headingOnRoute)
                    delay(updateInterval)
                  } else {
                    reachedDestination = true
                  }
                  break
                }
                prev = points[nextIdx - 1]
                next = points[nextIdx]
                nextIdx++
                val deltaTimeSec = next.delaySeconds / playbackSpeed.multiplier
                segmentDuration = deltaTimeSec.seconds.coerceAtLeast(1.milliseconds)

                val distanceNm = GeoMath.distanceNm(prev.latitude, prev.longitude, next.latitude, next.longitude)
                val deltaTimeHours = deltaTimeSec / 3600.0
                velocityOnRoute = if (deltaTimeHours <= 0.0) 0.0 else min(distanceNm / deltaTimeHours, GeoMath.MAX_SPEED_KNOTS)
                headingOnRoute = GeoMath.headingDegrees(prev.latitude, prev.longitude, next.latitude, next.longitude)

                latNow = prev.latitude
                lngNow = prev.longitude
                altNow = prev.elevation
                timeIntoSegment = Duration.ZERO
              } else {
                val alpha = timeIntoSegment / segmentDuration
                latNow = alpha * next.latitude + (1.0 - alpha) * prev.latitude
                lngNow = GeoMath.interpolateLongitude(prev.longitude, next.longitude, alpha)
                altNow = alpha * next.elevation + (1.0 - alpha) * prev.elevation
              }

              dialog.sendGpsLocation(GeoPoint(latNow, lngNow, altNow), velocityOnRoute, headingOnRoute)

              val step = minOf(updateInterval, (segmentDuration - timeIntoSegment).coerceAtLeast(1.milliseconds))
              delay(step)
              timeIntoSegment += step
            }
          } while (isActive && repeatPlayback)
        } finally {
          if (playbackJob === coroutineContext[Job]) {
            playbackJob = null
            val stopPoint =
              if (reachedDestination) {
                val last = points.last()
                GeoPoint(last.latitude, last.longitude, last.elevation)
              } else {
                dialog.currentDeviceLocation
              }
            // Reset velocity to 0 when playback stops, matching LocationPage::locationPlaybackStop.
            dialog.sendGpsLocation(stopPoint, speed = 0.0, heading = 0.0)
            dialog.updateButtonsState()
          }
        }
      }

    dialog.updateButtonsState()
  }

  internal fun stopRoutePlayback() {
    val job = playbackJob ?: return
    playbackJob = null
    job.cancel()
    dialog.sendGpsLocation(dialog.currentDeviceLocation, speed = 0.0, heading = 0.0)
    dialog.updateButtonsState()
  }

  private fun loadSavedRoutesFromStorage() {
    val json = PropertiesComponent.getInstance().getValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY) ?: return
    try {
      val type = object : TypeToken<List<SavedRouteItem>>() {}.type
      val loaded: List<SavedRouteItem>? = gson.fromJson(json, type)
      if (loaded != null) {
        savedRoutes.clear()
        savedRoutes.addAll(loaded)
        routesTableModel.items = savedRoutes.toList()
      }
    } catch (_: Exception) {
      // Ignore corrupted preference state
    }
  }

  private fun saveRoutesToStorage() {
    PropertiesComponent.getInstance().setValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY, gson.toJson(savedRoutes))
  }

  companion object {
    internal val PLAYBACK_UPDATE_INTERVAL = 1.seconds
    private val gson = Gson()
  }
}
