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

import com.android.emulator.control.GpsState
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.VisibleForTesting

/**
 * Dialog for configuring emulator GPS location and playing back GPS routes, providing functionality similar to the Location page in
 * extended controls without requiring any external Maps API.
 */
internal class LocationDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("location.dialog.title", emulator.emulatorId.avdName)) {

  internal var currentDeviceLocation: GeoPoint = GeoPoint.DEFAULT
    private set

  private val singlePointsTab = SinglePointsTab(this)
  private val routesTab = RoutesTab(this)

  @get:VisibleForTesting
  internal val routesMap: MapPanel
    get() = routesTab.routesMap

  internal val savedPoints: List<SavedPointItem>
    get() = singlePointsTab.savedPoints

  internal val isPlayingRoute: Boolean
    get() = routesTab.isPlaying

  internal val isUpdatingUiState: Boolean
    get() = isUpdatingUi

  internal val dialogCoroutineScope: CoroutineScope
    get() = coroutineScope

  internal val dialogUiContext: CoroutineContext
    get() = uiContext

  private lateinit var tabbedPane: JBTabbedPane

  private val gpsStateChannel =
    createStateChannel(
      getState = EmulatorController::getGps,
      onStateLoaded = ::updateFromGpsState,
      setState = EmulatorController::setGps,
    )

  init {
    onDispose {
      routesTab.stopRoutePlayback()
    }
    if (OfflineWorldMap.loadedMapDataOrNull == null) {
      coroutineScope.launch(Dispatchers.IO) {
        OfflineWorldMap.loadedMapData
        withContext(uiContext) {
          singlePointsTab.singlePointMap.repaint()
          routesTab.routesMap.repaint()
        }
      }
    }
  }

  private fun updateFromGpsState(state: GpsState) {
    // FakeEmulator defaults to (0.0, 0.0, 0.0) when unset; treat non-zero or explicitly set coordinates as device location.
    val point =
      if (state.latitude == 0.0 && state.longitude == 0.0 && state.altitude == 0.0) {
        GeoPoint.DEFAULT
      } else {
        GeoPoint(state.latitude, state.longitude, state.altitude)
      }
    currentDeviceLocation = point
    singlePointsTab.updateDeviceLocation(point, panAndSelect = true)
    routesTab.updateDeviceLocation(point, pan = true)
    updateButtonsState()
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    tabbedPane =
      JBTabbedPane().apply {
        name = "locationTabbedPane"
        tabComponentInsets = JBUI.emptyInsets()
        addTab(message("location.dialog.tab.single.points"), singlePointsTab.createPanel())
        addTab(message("location.dialog.tab.routes"), routesTab.createPanel())
      }

    return panel {
      row {
        cell(tabbedPane).align(Align.FILL)
      }
        .resizableRow()
    }
      .apply {
        singlePointsTab.updateCoordinateFieldsFromSelectedPoint()
        updateButtonsState()
      }
  }

  internal fun sendGpsLocation(point: GeoPoint, speed: Double = 0.0, heading: Double = 0.0) {
    stateModified = true
    currentDeviceLocation = point
    singlePointsTab.updateDeviceLocation(point, panAndSelect = false)
    routesTab.updateDeviceLocation(point, pan = false)
    val gpsState =
      GpsState.newBuilder()
        .setPassiveUpdate(true)
        .setLatitude(point.latitude)
        .setLongitude(point.longitude)
        .setAltitude(point.elevation)
        .setSpeed(speed)
        .setBearing(heading)
        .setSatellites(DEFAULT_SATELLITES)
        .build()
    gpsStateChannel.trySend(gpsState)
  }

  internal fun markStateModified() {
    stateModified = true
  }

  internal fun runUpdateUi(block: () -> Unit) {
    updateUi(block)
  }

  internal fun showErrorMessage(@Nls message: String) {
    showError(message)
  }

  internal fun clearErrorMessage() {
    clearError()
  }

  internal fun updateButtonsState() {
    val isPlaying = routesTab.isPlaying
    singlePointsTab.updateButtonsState(isPlaying)
    routesTab.updateButtonsState(isPlaying)
    if (::tabbedPane.isInitialized && tabbedPane.tabCount >= 2) {
      tabbedPane.setEnabledAt(0, !isPlaying)
    }
    ActivityTracker.getInstance().inc()
  }

  @VisibleForTesting
  internal fun startRoutePlayback(updateInterval: Duration = RoutesTab.PLAYBACK_UPDATE_INTERVAL) {
    routesTab.startRoutePlayback(updateInterval)
  }

  internal fun chooseAndImportGpxKmlFile() {
    if (routesTab.isPlaying) return
    val descriptor =
      FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        .withExtensionFilter("GPX/KML files", "gpx", "kml")
        .withTitle(message("location.dialog.import.chooser.title"))
        .withDescription(message("location.dialog.import.chooser.description"))
    val virtualFile = FileChooser.chooseFile(descriptor, parent, project, null) ?: return
    importGpxKmlFile(virtualFile.toNioPath())
  }

  /**
   * Imports a GPX or KML [file], matching `LocationPage::finishGeoDataLoading_v2` in `location-page-route.cpp`:
   * - If the file has 1 point, switches to the Single points tab and saves the point.
   * - If the file has >= 2 points, switches to the Routes tab and saves the route.
   */
  internal fun importGpxKmlFile(file: Path) {
    clearError()
    coroutineScope.launch {
      val result =
        withContext(Dispatchers.IO) {
          runCatching { GpxKmlParser.parseFile(file) }
        }
      withContext(uiContext) {
        result.fold(
          onSuccess = { fixes ->
            when {
              fixes.isEmpty() -> {
                showError(message("location.dialog.error.import.empty"))
              }
              fixes.size == 1 -> {
                val fix = fixes[0]
                val name = fix.name.ifBlank { file.fileName.toString().substringBeforeLast('.') }
                val point = GeoPoint(fix.latitude, fix.longitude, fix.elevation)
                singlePointsTab.selectAndPanToPoint(point)
                if (::tabbedPane.isInitialized) {
                  tabbedPane.selectedIndex = 0
                }
                singlePointsTab.addSavedPoint(
                  SavedPointItem(
                    name = name,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    elevation = fix.elevation,
                    description = fix.description,
                  )
                )
              }
              else -> {
                val routePoints = GpxKmlParser.fixesToRoutePoints(fixes)
                val totalDuration = routePoints.sumOf { it.delaySeconds }.roundToLong().toInt().coerceAtLeast(1)
                val defaultName =
                  fixes.firstOrNull { it.name.isNotBlank() }?.name ?: "route_${LocalDateTime.now().format(MapPanel.DEFAULT_NAME_FORMATTER)}"
                val description = fixes.firstOrNull { it.description.isNotBlank() }?.description ?: file.fileName.toString()
                if (::tabbedPane.isInitialized) {
                  tabbedPane.selectedIndex = 1
                }
                routesTab.addSavedRoute(
                  SavedRouteItem(
                    name = defaultName,
                    description = description,
                    mode = TravelMode.DRIVING,
                    durationSeconds = totalDuration,
                    points = routePoints,
                  )
                )
              }
            }
          },
          onFailure = { e ->
            showError(message("location.dialog.error.import.failed", e.message ?: "Unknown error"))
          },
        )
      }
    }
  }

  companion object {
    private const val DEFAULT_SATELLITES = 4

    internal const val SAVED_POINTS_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.location.saved.points"
    internal const val SAVED_ROUTES_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.location.saved.routes"
  }
}
