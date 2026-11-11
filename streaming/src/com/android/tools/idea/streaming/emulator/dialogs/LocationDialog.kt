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
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.core.formatErrorMessage
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.SLIDER_MAX
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.formatTimeProgress
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.positionToSliderValue
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.sliderValueToPosition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.setToolTipText
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.universal.UniversalFileChooser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ItemEvent
import java.io.IOException
import java.nio.file.Path
import java.text.Collator
import java.time.DateTimeException
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.event.RowSorterEvent
import javax.xml.parsers.ParserConfigurationException
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.xml.sax.SAXException

/** Playback speed multipliers supported by the emulator location dialog. */
internal enum class PlaybackSpeed(val multiplier: Int, val displayName: String) {
  SPEED_1X(1, "1X"),
  SPEED_2X(2, "2X"),
  SPEED_5X(5, "5X"),
  SPEED_10X(10, "10X"),
  SPEED_20X(20, "20X"),
  SPEED_50X(50, "50X"),
  SPEED_100X(100, "100X");

  override fun toString(): String = displayName
}

/** Represents a saved route row in [LocationDialog]. */
internal data class SavedRouteItem(
  val name: String,
  val description: String = "",
  val durationSeconds: Int = 0,
  val points: List<RoutePoint> = emptyList(),
) {
  @Transient private var cachedCumulativeTimesSeconds: DoubleArray? = null

  val cumulativeTimesSeconds: DoubleArray
    get() =
      cachedCumulativeTimesSeconds
        ?: DoubleArray(points.size).also { times ->
          for (i in 1 until points.size) {
            times[i] = times[i - 1] + points[i].delaySeconds.coerceAtLeast(0.0)
          }
          cachedCumulativeTimesSeconds = times
        }

  val duration: Duration
    get() = (cumulativeTimesSeconds.lastOrNull() ?: 0.0).seconds
}

/** Table model for saved routes. */
internal class SavedRoutesTableModel :
  ListTableModel<SavedRouteItem>(
    arrayOf(
      object : TextColumnInfo<SavedRouteItem>(message("location.dialog.column.name")) {
        override fun valueOf(item: SavedRouteItem): String = item.name

        override fun getCellTooltipText(item: SavedRouteItem): String = item.description

        override fun getComparator(): Comparator<SavedRouteItem> = compareBy(Collator.getInstance(), SavedRouteItem::name)
      },
      object : TextColumnInfo<SavedRouteItem>(message("location.dialog.column.points")) {
        override fun valueOf(item: SavedRouteItem): String = item.points.size.toString()

        override fun getComparator(): Comparator<SavedRouteItem> =
          compareBy<SavedRouteItem> { it.points.size }.thenBy(Collator.getInstance(), SavedRouteItem::name)
      },
      object : TextColumnInfo<SavedRouteItem>(message("location.dialog.column.duration")) {
        override fun valueOf(item: SavedRouteItem): String = GeoMath.formatDuration(item.durationSeconds)

        override fun getComparator(): Comparator<SavedRouteItem> =
          compareBy(SavedRouteItem::durationSeconds).thenBy(Collator.getInstance(), SavedRouteItem::name)
      },
    ),
    mutableListOf(),
    0,
    SortOrder.ASCENDING,
  )

/**
 * Extended controls dialog for importing and playing back GPX and KML GPS routes on an emulator without requiring any external Maps API.
 */
internal class LocationDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("location.dialog.title", emulator.emulatorId.avdName)) {

  private val dialogJob = SupervisorJob(coroutineScope.coroutineContext[Job])
  private val dialogScope = CoroutineScope(coroutineScope.coroutineContext + dialogJob)

  private var currentDeviceLocation: GeoPoint = GeoPoint.DEFAULT
  private var gpsInitialized: Boolean = false

  private val savedRoutes = mutableListOf<SavedRouteItem>()
  private var selectedRoute: SavedRouteItem? = null
  private var playbackSpeed: PlaybackSpeed = PlaybackSpeed.SPEED_1X
  private var repeatPlayback: Boolean = false
  private var currentPosition: Duration = Duration.ZERO
  private var playbackJob: Job? = null
  private var resumePlaybackAfterAdjusting: Boolean = false

  private val isPlaying: Boolean
    get() = playbackJob != null || resumePlaybackAfterAdjusting

  @VisibleForTesting internal var playbackUpdateInterval: Duration = PLAYBACK_UPDATE_INTERVAL

  @VisibleForTesting
  var fileChooserCreator: (FileChooserDescriptor, Component?, Project?) -> FileChooserDialog = { descriptor, parentComponent, project ->
    UniversalFileChooser.create(project, parentComponent, descriptor)
  }

  private val routesTableModel = SavedRoutesTableModel()
  private val routesTable =
    ExtendedControlsTable(routesTableModel, "savedRoutesTable", message("location.dialog.empty.routes")).apply {
      accessibleContext.accessibleName = message("location.dialog.saved.routes.label")
    }

  @VisibleForTesting
  internal val routesMap =
    MapPanel().apply {
      name = "routesMap"
      panTo(currentDeviceLocation)
    }

  private lateinit var playbackSlider: ProgressSlider
  private lateinit var playbackTimeLabel: JBLabel
  private lateinit var playPauseButton: JButton
  private lateinit var repeatPlaybackCheckBox: JBCheckBox
  private lateinit var playbackSpeedLabel: JLabel
  private lateinit var playbackSpeedComboBox: ComboBox<PlaybackSpeed>

  private val gpsStateChannel =
    createStateChannel(
      getState = EmulatorController::getGps,
      onStateLoaded = ::updateFromGpsState,
      setState = EmulatorController::setGps,
    )

  init {
    onDispose {
      stopRoutePlayback()
      dialogJob.cancel()
    }

    loadSavedRoutesFromStorage()

    routesTable.selectionModel.addListSelectionListener { event ->
      if (!event.valueIsAdjusting && !isUpdatingUi && !isPlaying) {
        onSelectedRouteChanged()
      }
    }

    routesTable.rowSorter?.addRowSorterListener { event ->
      if (event.type == RowSorterEvent.Type.SORT_ORDER_CHANGED && !isUpdatingUi) {
        saveSortOrderToStorage()
      }
    }

    if (OfflineWorldMap.loadedMapDataOrNull == null) {
      dialogScope.launch(Dispatchers.IO) {
        OfflineWorldMap.loadedMapData
        withContext(uiContext) {
          routesMap.repaint()
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
    gpsInitialized = true
    val selectedFirstPoint = selectedRoute?.points?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude, it.elevation) }
    if (selectedFirstPoint != null) {
      sendGpsLocation(selectedFirstPoint)
    } else {
      currentDeviceLocation = point
      routesMap.deviceLocation = point
      routesMap.panTo(point)
    }
    updateControlsEnabledState()
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    val dialogPanel = panel {
      row {
        panel {
            row {
              label(message("location.dialog.saved.routes.label"))
            }

            row {
              cell(createSavedRoutesTablePanel()).align(Align.FILL)
            }
              .resizableRow()
          }
          .align(Align.FILL)
          .resizableColumn()

        panel {
            row {
              cell(routesMap).align(Align.FILL)
            }
              .resizableRow()

            row {
              playbackSlider =
                ProgressSlider(0, SLIDER_MAX, 0).apply {
                  name = "playbackSlider"
                  accessibleContext.accessibleName = message("location.dialog.accessible.playback.progress")
                  addChangeListener { onPlaybackSliderChanged() }
                }
              cell(playbackSlider).align(AlignX.FILL).resizableColumn()

              playbackTimeLabel =
                JBLabel(formatTimeProgress(Duration.ZERO, Duration.ZERO)).apply {
                  name = "playbackTimeLabel"
                }
              cell(playbackTimeLabel)
            }

            row {
              val playLabel = message("location.dialog.button.play")
              playPauseButton =
                IconButton(AllIcons.Actions.Execute, playLabel) { toggleRoutePlayback() }
                  .apply {
                    name = "playPauseButton"
                  }
              cell(playPauseButton).accessibleName(playLabel)

              repeatPlaybackCheckBox =
                JBCheckBox(message("location.dialog.checkbox.repeat.playback"), repeatPlayback).apply {
                  name = "repeatPlaybackCheckBox"
                  addActionListener { repeatPlayback = isSelected }
                }
              cell(repeatPlaybackCheckBox)

              placeholder().resizableColumn()

              val speedLabel = message("location.dialog.label.playback.speed")
              label(speedLabel).gap(RightGap.SMALL).applyToComponent {
                name = "playbackSpeedLabel"
                playbackSpeedLabel = this
              }
              comboBox(
                  DefaultComboBoxModel(PlaybackSpeed.entries.toTypedArray()),
                  renderer = textListCellRenderer { it?.displayName ?: "" },
                )
                .accessibleName(speedLabel.removeSuffix(":"))
                .applyToComponent {
                  name = "playbackSpeedComboBox"
                  playbackSpeedComboBox = this
                  playbackSpeedLabel.labelFor = this
                  selectedItem = playbackSpeed
                  addItemListener { event ->
                    if (!isUpdatingUi && event.stateChange == ItemEvent.SELECTED) {
                      (event.item as? PlaybackSpeed)?.let { playbackSpeed = it }
                    }
                  }
                }
            }
          }
          .align(Align.FILL)
          .resizableColumn()
      }
        .resizableRow()
    }

    var initialIndex = -1
    updateUi {
      routesTable.rowSorter?.sortKeys = listOf(getPersistedSortKey())
      routesTableModel.items = savedRoutes.toList()
      initialIndex = getInitialSelectedRouteModelIndex()
      if (initialIndex >= 0) {
        routesTable.selectModelRow(initialIndex)
      }
    }
    applySelectedRoute(savedRoutes.getOrNull(initialIndex))
    updateControlsEnabledState()

    return dialogPanel
  }

  private fun createSavedRoutesTablePanel(): JPanel {
    return BorderLayoutPanelWithPreferredSize(280, 280, minimumWidth = 200).apply {
      name = "savedRoutesTablePanel"
      add(
        ToolbarDecorator.createDecorator(routesTable)
          .setAddAction { chooseAndImportGpxKmlFile() }
          .setRemoveAction { removeSelectedRoutes() }
          .setMoveDownAction(null)
          .setMoveUpAction(null)
          .setAddActionUpdater { !isPlaying }
          .setRemoveActionUpdater { !isPlaying && routesTable.selectedObjects.isNotEmpty() }
          .setToolbarPosition(ActionToolbarPosition.BOTTOM)
          .createPanel()
      )
    }
  }

  private fun sendGpsLocation(point: GeoPoint, speed: Double = 0.0, heading: Double = 0.0) {
    stateModified = true
    gpsInitialized = true
    currentDeviceLocation = point
    routesMap.deviceLocation = point
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

  private fun chooseAndImportGpxKmlFile() {
    if (isPlaying) return
    val descriptor = createGpxKmlFileChooserDescriptor()
    val parentComponent =
      (parent ?: dialogWrapper?.window ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow)?.takeIf { it.isShowing }
    val virtualFile = fileChooserCreator(descriptor, parentComponent, project).choose(project).firstOrNull() ?: return
    val nioPath = virtualFile.fileSystem.getNioPath(virtualFile)
    if (nioPath != null) {
      importGpxKmlFile(nioPath)
    } else {
      importGpxKml(virtualFile.name) {
        virtualFile.inputStream.buffered().use { GpxKmlParser.parseStream(virtualFile.name, it) }
      }
    }
  }

  /** Imports a GPX or KML [file] as a saved route. */
  @VisibleForTesting
  internal fun importGpxKmlFile(file: Path) {
    importGpxKml(file.fileName.toString()) { GpxKmlParser.parseFile(file) }
  }

  private fun importGpxKml(fileName: String, parse: () -> List<GpsFix>) {
    if (isPlaying) return
    clearError()
    dialogScope.launch {
      val fixes =
        try {
          withContext(Dispatchers.IO) {
            parse()
          }
        } catch (e: IOException) {
          showImportError(e)
          return@launch
        } catch (e: IllegalArgumentException) {
          showImportError(e)
          return@launch
        } catch (e: DateTimeException) {
          showImportError(e)
          return@launch
        } catch (e: UnsupportedOperationException) {
          showImportError(e)
          return@launch
        } catch (e: SAXException) {
          showImportError(e)
          return@launch
        } catch (e: ParserConfigurationException) {
          showImportError(e)
          return@launch
        }

      withContext(uiContext) {
        if (fixes.isEmpty()) {
          showError(message("location.dialog.error.import.empty"))
          return@withContext
        }
        val routePoints = GpxKmlParser.fixesToRoutePoints(fixes)
        val totalDuration = routePoints.sumOf { it.delaySeconds }.roundToLong().toInt()
        val defaultName = fixes.firstOrNull { it.name.isNotBlank() }?.name ?: fileName.substringBeforeLast('.').ifBlank { fileName }
        val description = fixes.firstOrNull { it.description.isNotBlank() }?.description ?: fileName
        val item =
          SavedRouteItem(
            name = defaultName,
            description = description,
            durationSeconds = totalDuration,
            points = routePoints,
          )
        if (item in savedRoutes) {
          showError(message("location.dialog.error.duplicate.route", item.name))
          return@withContext
        }
        addSavedRoute(item)
      }
    }
  }

  private suspend fun showImportError(error: Exception) {
    withContext(uiContext) {
      showError(message("location.dialog.error.import.failed", formatErrorMessage(error)))
    }
  }

  private fun addSavedRoute(item: SavedRouteItem) {
    val previousSelected = selectedRoute
    savedRoutes.add(item)
    saveRoutesToStorage()
    if (isPlaying) {
      updateUi {
        routesTableModel.items = savedRoutes.toList()
        val selectedIndex =
          if (previousSelected != null) {
            savedRoutes.indexOfFirst { it === previousSelected }.takeIf { it >= 0 } ?: savedRoutes.indexOf(previousSelected)
          } else {
            -1
          }
        if (selectedIndex >= 0) {
          routesTable.selectModelRow(selectedIndex)
        }
      }
    } else {
      updateUi {
        routesTableModel.items = savedRoutes.toList()
        routesTable.selectModelRow(savedRoutes.lastIndex)
      }
      onSelectedRouteChanged()
    }
  }

  private fun removeSelectedRoutes() {
    if (isPlaying) return
    TableUtil.stopEditing(routesTable)
    val minSelectionIndex = routesTable.selectionModel.minSelectionIndex
    val modelIndices =
      routesTable.selectedRows.map { routesTable.convertRowIndexToModel(it) }.filter { it in savedRoutes.indices }.sortedDescending()
    if (modelIndices.isEmpty()) return
    clearError()
    for (index in modelIndices) {
      savedRoutes.removeAt(index)
    }
    saveRoutesToStorage()
    updateUi {
      routesTableModel.items = savedRoutes.toList()
      routesTable.selectRowAfterRemoval(minSelectionIndex)
    }
    onSelectedRouteChanged()
  }

  private fun onSelectedRouteChanged() {
    val selected = if (routesTable.selectedRowCount == 1) routesTable.selectedObject else routesTable.selectedObjects.firstOrNull()
    saveSelectedRouteToStorage(selected)
    if (selected === selectedRoute) {
      return
    }
    clearError()
    applySelectedRoute(selected)
    updateControlsEnabledState()
  }

  private fun applySelectedRoute(route: SavedRouteItem?) {
    selectedRoute = route
    currentPosition = Duration.ZERO
    if (route == null || route.points.isEmpty()) {
      routesMap.routeOrigin = null
      routesMap.routeDestination = null
      routesMap.routePoints = emptyList()
      updatePlaybackProgressUi(Duration.ZERO, Duration.ZERO)
      return
    }
    val geoPoints = route.points.map { GeoPoint(it.latitude, it.longitude, it.elevation) }
    val firstPoint = geoPoints.firstOrNull()
    routesMap.routeOrigin = firstPoint
    routesMap.routeDestination = if (geoPoints.size >= 2) geoPoints.last() else null
    routesMap.routePoints = if (geoPoints.size >= 2) geoPoints else emptyList()
    routesMap.fitBounds(geoPoints)
    if (gpsInitialized && firstPoint != null) {
      sendGpsLocation(firstPoint)
    }
    updatePlaybackProgressUi(Duration.ZERO, route.duration)
  }

  private fun onPlaybackSliderChanged() {
    if (isUpdatingUi) return
    val route = selectedRoute ?: return
    val totalDuration = route.duration
    if (!totalDuration.isPositive()) return
    currentPosition = sliderValueToPosition(playbackSlider.value, totalDuration)
    playbackTimeLabel.text = formatTimeProgress(currentPosition, totalDuration)

    if (playbackSlider.valueIsAdjusting) {
      val job = playbackJob
      if (job != null) {
        playbackJob = null
        resumePlaybackAfterAdjusting = true
        job.cancel()
        updateControlsEnabledState()
      }
      evaluateRouteAt(route, currentPosition, playbackSpeed.multiplier)?.let { sample ->
        routesMap.deviceLocation = sample.point
      }
    } else if (isPlaying) {
      val job = playbackJob
      playbackJob = null
      resumePlaybackAfterAdjusting = false
      job?.cancel()
      startRoutePlayback(fromSeek = true)
    } else if (gpsInitialized) {
      evaluateRouteAt(route, currentPosition, playbackSpeed.multiplier)?.let { sample ->
        sendGpsLocation(sample.point, speed = 0.0, heading = sample.headingDegrees)
      }
    }
  }

  private fun toggleRoutePlayback() {
    if (isPlaying) {
      pauseRoutePlayback()
    } else {
      startRoutePlayback()
    }
  }

  /** Starts or resumes playing the selected route along its interpolated path. */
  @VisibleForTesting
  internal fun startRoutePlayback(updateInterval: Duration = playbackUpdateInterval, fromSeek: Boolean = false) {
    if (!gpsInitialized) return
    val route = selectedRoute ?: return
    val points = route.points
    if (points.size < 2) return

    val previousJob = playbackJob
    playbackJob = null
    resumePlaybackAfterAdjusting = false
    previousJob?.cancel()
    clearError()
    stateModified = true

    val totalDuration = route.duration
    var pos =
      when {
        fromSeek -> currentPosition.coerceIn(Duration.ZERO, totalDuration)
        currentPosition > Duration.ZERO && currentPosition < totalDuration -> currentPosition
        else -> Duration.ZERO
      }

    lateinit var job: Job
    job =
      dialogScope.launch(uiContext, start = CoroutineStart.LAZY) {
        var reachedDestination = false
        try {
          if (!totalDuration.isPositive()) {
            val pt = points[0]
            val geoPoint = GeoPoint(pt.latitude, pt.longitude, pt.elevation)
            do {
              sendGpsLocation(geoPoint, speed = 0.0, heading = 0.0)
              if (!repeatPlayback) {
                reachedDestination = true
                break
              }
              delay(updateInterval)
            } while (isActive && repeatPlayback)
          } else {
            var lastTick = TimeSource.Monotonic.markNow()
            while (isActive) {
              val sample = evaluateRouteAt(route, pos, playbackSpeed.multiplier) ?: break
              sendGpsLocation(sample.point, sample.speedKnots, sample.headingDegrees)
              updatePlaybackProgressUi(pos, totalDuration)

              if (pos >= totalDuration) {
                if (repeatPlayback) {
                  pos = Duration.ZERO
                  lastTick = TimeSource.Monotonic.markNow()
                  delay(updateInterval)
                  continue
                } else {
                  reachedDestination = true
                  break
                }
              }

              val remainingRouteTime = totalDuration - pos
              val remainingWallTime = remainingRouteTime / playbackSpeed.multiplier.toDouble()
              val stepWall = minOf(updateInterval, remainingWallTime.coerceAtLeast(1.milliseconds))
              delay(stepWall)

              val elapsedWall = lastTick.elapsedNow().coerceAtLeast(Duration.ZERO)
              lastTick = TimeSource.Monotonic.markNow()
              pos = (pos + elapsedWall * playbackSpeed.multiplier.toDouble()).coerceAtMost(totalDuration)
            }
          }
        } finally {
          withContext(NonCancellable + uiContext) {
            if (playbackJob === job) {
              playbackJob = null
              if (reachedDestination) {
                val last = points.last()
                sendGpsLocation(GeoPoint(last.latitude, last.longitude, last.elevation), speed = 0.0, heading = 0.0)
                updatePlaybackProgressUi(Duration.ZERO, totalDuration)
              } else {
                currentPosition = pos
                sendGpsLocation(currentDeviceLocation, speed = 0.0, heading = 0.0)
              }
              updateControlsEnabledState()
            }
          }
        }
      }

    playbackJob = job
    updateControlsEnabledState()
    job.start()
  }

  private fun pauseRoutePlayback() {
    val job = playbackJob
    playbackJob = null
    resumePlaybackAfterAdjusting = false
    job?.cancel()
    sendGpsLocation(currentDeviceLocation, speed = 0.0, heading = 0.0)
    updateControlsEnabledState()
  }

  private fun stopRoutePlayback() {
    if (!isPlaying) return
    val job = playbackJob
    playbackJob = null
    resumePlaybackAfterAdjusting = false
    job?.cancel()
    sendGpsLocation(currentDeviceLocation, speed = 0.0, heading = 0.0)
    updateControlsEnabledState()
  }

  private fun updatePlaybackProgressUi(position: Duration, duration: Duration) {
    currentPosition = position
    if (!::playbackSlider.isInitialized || !::playbackTimeLabel.isInitialized) return
    updateUi {
      if (!playbackSlider.valueIsAdjusting) {
        playbackSlider.value = positionToSliderValue(position, duration)
      }
      playbackTimeLabel.text = formatTimeProgress(position, duration)
    }
  }

  private fun updateControlsEnabledState() {
    val playing = isPlaying
    val route = selectedRoute
    val isPlayableRoute = route != null && route.points.size >= 2
    val duration = route?.duration ?: Duration.ZERO
    if (::playPauseButton.isInitialized) {
      val buttonLabel = if (playing) message("location.dialog.button.pause") else message("location.dialog.button.play")
      playPauseButton.icon = if (playing) AllIcons.Actions.Pause else AllIcons.Actions.Execute
      playPauseButton.setToolTipText(HtmlChunk.text(buttonLabel))
      playPauseButton.accessibleContext.accessibleName = buttonLabel
      playPauseButton.isEnabled = gpsInitialized && isPlayableRoute
    }
    if (::playbackSlider.isInitialized) {
      playbackSlider.isEnabled = gpsInitialized && isPlayableRoute && duration.isPositive()
    }
    if (::playbackTimeLabel.isInitialized) {
      playbackTimeLabel.isEnabled = isPlayableRoute
    }
    if (::repeatPlaybackCheckBox.isInitialized) {
      repeatPlaybackCheckBox.isEnabled = isPlayableRoute
    }
    if (::playbackSpeedLabel.isInitialized) {
      playbackSpeedLabel.isEnabled = isPlayableRoute
    }
    if (::playbackSpeedComboBox.isInitialized) {
      playbackSpeedComboBox.isEnabled = isPlayableRoute
    }
    routesTable.isEnabled = !playing
    ActivityTracker.getInstance().inc()
  }

  private fun loadSavedRoutesFromStorage() {
    val json = PropertiesComponent.getInstance().getValue(SAVED_ROUTES_STORAGE_KEY) ?: return
    try {
      val type = object : TypeToken<List<SavedRouteItem>>() {}.type
      val loaded: List<SavedRouteItem>? = gson.fromJson(json, type)
      if (loaded != null) {
        savedRoutes.clear()
        savedRoutes.addAll(loaded)
      }
    } catch (_: Exception) {
      // Ignore corrupted preference state.
    }
  }

  private fun saveRoutesToStorage() {
    val properties = PropertiesComponent.getInstance()
    if (savedRoutes.isEmpty()) {
      properties.unsetValue(SAVED_ROUTES_STORAGE_KEY)
      properties.unsetValue(SELECTED_ROUTE_STORAGE_KEY)
    } else {
      properties.setValue(SAVED_ROUTES_STORAGE_KEY, gson.toJson(savedRoutes))
    }
  }

  private fun getInitialSelectedRouteModelIndex(): Int {
    if (savedRoutes.isEmpty()) return -1
    val defaultIndex = if (routesTable.rowCount > 0) routesTable.convertRowIndexToModel(0) else 0
    val raw = PropertiesComponent.getInstance().getValue(SELECTED_ROUTE_STORAGE_KEY) ?: return defaultIndex
    val index = raw.toIntOrNull() ?: return defaultIndex
    return if (index < 0) -1 else if (index >= savedRoutes.size) defaultIndex else index
  }

  private fun getPersistedSortKey(): RowSorter.SortKey {
    val defaultKey = RowSorter.SortKey(0, SortOrder.ASCENDING)
    val raw = PropertiesComponent.getInstance().getValue(SORT_ORDER_STORAGE_KEY) ?: return defaultKey
    val parts = raw.split(':', limit = 2)
    if (parts.size != 2) return defaultKey
    val column = parts[0].toIntOrNull() ?: return defaultKey
    if (column !in 0 until routesTableModel.columnCount) return defaultKey
    val order = SortOrder.entries.firstOrNull { it.name == parts[1] } ?: return defaultKey
    if (order == SortOrder.UNSORTED) return defaultKey
    return RowSorter.SortKey(column, order)
  }

  private fun saveSortOrderToStorage() {
    val sortKey = routesTable.rowSorter?.sortKeys?.firstOrNull() ?: return
    if (sortKey.sortOrder == SortOrder.UNSORTED) return
    PropertiesComponent.getInstance().setValue(SORT_ORDER_STORAGE_KEY, "${sortKey.column}:${sortKey.sortOrder.name}")
  }

  private fun saveSelectedRouteToStorage(selected: SavedRouteItem?) {
    val properties = PropertiesComponent.getInstance()
    if (savedRoutes.isEmpty()) {
      properties.unsetValue(SELECTED_ROUTE_STORAGE_KEY)
      return
    }
    val selectedIndex =
      if (selected == null) {
        -1
      } else {
        savedRoutes.indexOfFirst { it === selected }.takeIf { it >= 0 } ?: savedRoutes.indexOf(selected)
      }
    properties.setValue(SELECTED_ROUTE_STORAGE_KEY, selectedIndex.toString())
  }

  companion object {
    private const val DEFAULT_SATELLITES = 4
    private val PLAYBACK_UPDATE_INTERVAL = 200.milliseconds
    private val gson = Gson()

    internal const val SAVED_ROUTES_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.location.saved.routes"
    internal const val SELECTED_ROUTE_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.location.selected.route"
    internal const val SORT_ORDER_STORAGE_KEY = "com.android.tools.idea.streaming.emulator.location.sort.order"

    private fun createGpxKmlFileChooserDescriptor(): FileChooserDescriptor =
      FileChooserDescriptorFactory.singleFile()
        .withExtensionFilter("GPX/KML files", "gpx", "kml")
        .withTitle(message("location.dialog.import.chooser.title"))
        .withDescription(message("location.dialog.import.chooser.description"))

    @VisibleForTesting
    internal data class RouteSample(
      val point: GeoPoint,
      val speedKnots: Double,
      val headingDegrees: Double,
    )

    @VisibleForTesting
    internal fun evaluateRouteAt(route: SavedRouteItem, position: Duration, speedMultiplier: Int): RouteSample? {
      val points = route.points
      if (points.isEmpty()) return null
      if (points.size == 1) {
        val pt = points[0]
        return RouteSample(GeoPoint(pt.latitude, pt.longitude, pt.elevation), speedKnots = 0.0, headingDegrees = 0.0)
      }

      val times = route.cumulativeTimesSeconds
      val totalDurationSec = times.last()
      val targetSec = (position.inWholeNanoseconds / 1e9).coerceIn(0.0, totalDurationSec)
      var lo = 1
      var hi = points.lastIndex
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val matches = if (targetSec > 0.0) times[mid] >= targetSec else times[mid] > 0.0
        if (matches) {
          hi = mid
        } else {
          lo = mid + 1
        }
      }
      val prev = points[lo - 1]
      val next = points[lo]
      val elapsedSec = times[lo - 1]
      val segDurSec = next.delaySeconds.coerceAtLeast(0.0)
      val alpha = if (segDurSec > 0.0) ((targetSec - elapsedSec) / segDurSec).coerceIn(0.0, 1.0) else 1.0
      val lat = alpha * next.latitude + (1.0 - alpha) * prev.latitude
      val lon = GeoMath.interpolateLongitude(prev.longitude, next.longitude, alpha)
      val ele = alpha * next.elevation + (1.0 - alpha) * prev.elevation
      val distanceNm = GeoMath.distanceNm(prev.latitude, prev.longitude, next.latitude, next.longitude)
      val deltaTimeHours = (segDurSec / speedMultiplier) / 3600.0
      val speedKnots = if (deltaTimeHours <= 0.0) 0.0 else min(distanceNm / deltaTimeHours, GeoMath.MAX_SPEED_KNOTS)
      val heading = GeoMath.headingDegrees(prev.latitude, prev.longitude, next.latitude, next.longitude)
      return RouteSample(GeoPoint(lat, lon, ele), speedKnots, heading)
    }
  }
}
