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
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.AnActionButton
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import java.awt.event.KeyEvent
import java.awt.geom.Line2D
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [LocationDialog]. */
@RunsInEdt
class LocationDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val tempFolder = TemporaryFolder()

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      tempFolder,
      EdtRule(),
      HeadlessDialogRule(),
    )

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_POINTS_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY)
    val testAvdFolder = FakeEmulator.createPhoneAvd(emulatorRule.avdRoot)
    fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    assertThat(emulators).hasSize(1)
    emulatorController = emulators.first()
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  }

  @After
  fun tearDown() {
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_POINTS_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY)
  }

  @Test
  fun testInitialStateAndSetLocation() {
    runBlocking {
      emulatorController.setGps(GpsState.newBuilder().setLatitude(37.7749).setLongitude(-122.4194).setAltitude(15.0).build())
    }

    val dialog = LocationDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val latField = ui.getComponent<JBTextField> { it.name == "latitudeField" }
    val lonField = ui.getComponent<JBTextField> { it.name == "longitudeField" }
    val eleField = ui.getComponent<JBTextField> { it.name == "elevationField" }
    val setLocationButton = ui.getComponent<JButton> { it.name == "setLocationButton" }

    waitForCondition(5.seconds) { latField.text == "37.7749" }
    assertThat(lonField.text).isEqualTo("-122.4194")
    assertThat(eleField.text).isEqualTo("15.0")
    assertThat(setLocationButton.isEnabled).isTrue()

    // Verify offline map renders cleanly.
    assertThat(ui.render().width).isGreaterThan(0)

    // Test invalid latitude disables Set Location button.
    latField.text = "95.0"
    assertThat(setLocationButton.isEnabled).isFalse()

    // Test valid coordinates enable Set Location button and send GpsState to emulator.
    latField.text = "40.7128"
    lonField.text = "-74.0060"
    eleField.text = "10.5"
    assertThat(setLocationButton.isEnabled).isTrue()

    ui.clickOn(setLocationButton)
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 40.7128) < 1e-4 &&
        abs(fakeEmulator.gpsLocation.longitude - (-74.0060)) < 1e-4 &&
        abs(fakeEmulator.gpsLocation.altitude - 10.5) < 1e-4
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testMapClickOfflineSearchAndSavedPointsCrud() {
    val dialogWrapper = LocationDialog(emulatorController).show()
    val rootPane = dialogWrapper.rootPane
    val ui = FakeUi(rootPane)

    val mapPanel = ui.getComponent<MapPanel> { it.name == "singlePointMap" }
    val searchField = ui.getComponent<SearchTextField> { it.name == "locationSearchField" }
    val latField = ui.getComponent<JBTextField> { it.name == "latitudeField" }
    val lonField = ui.getComponent<JBTextField> { it.name == "longitudeField" }
    val pointsTable = ui.getComponent<TableView<*>> { it.name == "savedPointsTable" }
    val actionsPanel = ui.findAllComponents<CommonActionsPanel>()[0]

    waitForCondition(5.seconds) { latField.text == "37.4220" }

    // Click on map to select a point.
    ui.layoutAndDispatchEvents()
    ui.clickOn(mapPanel)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Verify zooming out is limited so that when centered at the equator, the visible vertical range is -60° to +60° (120°).
    mapPanel.panTo(GeoPoint(0.0, 0.0))
    mapPanel.setZoomLevel(GeoMath.MIN_ZOOM)
    assertThat(mapPanel.zoomOutButton.isEnabled).isFalse()
    val topLat = mapPanel.pixelToGeoPoint(0.0, 0.0).latitude
    val bottomLat = mapPanel.pixelToGeoPoint(0.0, mapPanel.height.toDouble()).latitude
    assertThat(topLat).isWithin(1e-2).of(60.0)
    assertThat(bottomLat).isWithin(1e-2).of(-60.0)
    mapPanel.setZoomLevel(GeoMath.DEFAULT_ZOOM)
    assertThat(mapPanel.zoomOutButton.isEnabled).isTrue()

    // Search for a bundled city via SearchTextField by pressing Enter, and verify the dialog remains open.
    searchField.text = "San Francisco"
    ui.keyboard.setFocus(searchField.textEditor)
    ui.keyboard.pressAndRelease(KeyEvent.VK_ENTER)
    assertThat(dialogWrapper.isShowing).isTrue()
    assertThat(latField.text).isEqualTo("37.7749")
    assertThat(lonField.text).isEqualTo("-122.4194")

    // Search using DMS coordinates.
    searchField.text = "37°49'11.64\"N 122°28'41.88\"W"
    searchField.textEditor.postActionEvent()
    assertThat(latField.text).isEqualTo("37.8199")
    assertThat(lonField.text).isEqualTo("-122.4783")

    // Save the point via Add action on ToolbarDecorator.
    val addAction = actionsPanel.getAnAction(CommonActionsPanel.Buttons.ADD)!!
    val editAction = actionsPanel.getAnAction(CommonActionsPanel.Buttons.EDIT)!!
    val removeAction = actionsPanel.getAnAction(CommonActionsPanel.Buttons.REMOVE)!!

    createModalDialogAndInteractWithIt({ performAction(addAction) }) { editDlg ->
      val editUi = FakeUi(editDlg.rootPane)
      val nameField = editUi.getComponent<JBTextField> { it.name == "pointNameField" }
      val descArea = editUi.getComponent<JBTextArea> { it.name == "pointDescriptionArea" }
      nameField.text = "Golden Gate"
      descArea.text = "San Francisco landmark"
      editUi.clickOn(editDlg.rootPane.defaultButton)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertThat(pointsTable.rowCount).isEqualTo(1)
    assertThat(pointsTable.getValueAt(0, 0)).isEqualTo("Golden Gate")
    assertThat(pointsTable.getValueAt(0, 1)).isEqualTo("37.8199, -122.4783")
    assertThat(pointsTable.getValueAt(0, 2)).isEqualTo("San Francisco landmark")

    // Search for a saved point by name after panning away.
    searchField.text = "Tokyo"
    searchField.textEditor.postActionEvent()
    assertThat(latField.text).isEqualTo("35.6762")
    searchField.text = "Golden Gate"
    searchField.textEditor.postActionEvent()
    assertThat(latField.text).isEqualTo("37.8199")
    assertThat(pointsTable.selectedRow).isEqualTo(0)

    // Manually editing a coordinate field clears the saved points table selection.
    latField.text = "35.6762"
    assertThat(pointsTable.selectedRow).isEqualTo(-1)
    assertThat(isPresentationEnabled(editAction)).isFalse()

    // Re-selecting the saved point restores its coordinates and allows editing.
    pointsTable.selectionModel.setSelectionInterval(0, 0)
    assertThat(isPresentationEnabled(editAction)).isTrue()
    assertThat(latField.text).isEqualTo("37.8199")
    createModalDialogAndInteractWithIt({ performAction(editAction) }) { editDlg ->
      val editUi = FakeUi(editDlg.rootPane)
      val nameField = editUi.getComponent<JBTextField> { it.name == "pointNameField" }
      nameField.text = "Golden Gate Updated"
      editUi.clickOn(editDlg.rootPane.defaultButton)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(pointsTable.getValueAt(0, 0)).isEqualTo("Golden Gate Updated")
    assertThat(latField.text).isEqualTo("37.8199")
    assertThat(lonField.text).isEqualTo("-122.4783")

    // Remove the saved point.
    performAction(removeAction)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(pointsTable.rowCount).isEqualTo(0)

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialogWrapper.isShowing).isFalse()
  }

  @Test
  fun testRoutesCreationSavingAndPlayback() {
    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show()
    val rootPane = dialogWrapper.rootPane
    val ui = FakeUi(rootPane)

    val tabbedPane = ui.getComponent<JBTabbedPane> { it.name == "locationTabbedPane" }
    tabbedPane.selectedIndex = 1
    ui.layoutAndDispatchEvents()

    val originField = ui.getComponent<JBTextField> { it.name == "originField" }
    val destinationField = ui.getComponent<JBTextField> { it.name == "destinationField" }
    val swapWaypointsButton = ui.getComponent<JButton> { it.name == "swapWaypointsButton" }
    val findRouteButton = ui.getComponent<JButton> { it.name == "findRouteButton" }
    val saveRouteButton = ui.getComponent<JButton> { it.name == "saveRouteButton" }
    val playRouteButton = ui.getComponent<JButton> { it.name == "playRouteButton" }
    val travelModeComboBox = ui.getComponent<ComboBox<TravelMode>> { it.name == "travelModeComboBox" }
    val speedComboBox = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val repeatCheckBox = ui.getComponent<JBCheckBox> { it.name == "repeatPlaybackCheckBox" }
    val routesTable = ui.getComponent<TableView<*>> { it.name == "savedRoutesTable" }

    assertThat(playRouteButton.isEnabled).isFalse()
    assertThat(saveRouteButton.isEnabled).isFalse()

    originField.text = "37.4220, -122.0840"
    destinationField.text = "37.4230, -122.0850"
    ui.keyboard.setFocus(originField)
    ui.keyboard.pressAndRelease(KeyEvent.VK_ENTER)
    assertThat(dialogWrapper.isShowing).isTrue()
    assertThat(playRouteButton.isEnabled).isTrue()

    ui.keyboard.setFocus(destinationField)
    ui.keyboard.pressAndRelease(KeyEvent.VK_ENTER)
    assertThat(dialogWrapper.isShowing).isTrue()

    // Verify westward headings are normalized to [0.0, 360.0).
    assertThat(GeoMath.headingDegrees(0.0, 0.0, 0.0, -1.0)).isWithin(1e-6).of(270.0)
    assertThat(GeoMath.headingDegrees(37.4220, -122.0840, 37.4230, -122.0850))
      .isIn(com.google.common.collect.Range.closedOpen(270.0, 360.0))

    // Verify antimeridian (±180°) route interpolation and fitBounds wrap across the shortest 2° seam instead of 358° backwards.
    val antimeridianRoute =
      OfflineLocationResolver.computeDirectRoute(
        origin = GeoPoint(0.0, 179.0),
        destination = GeoPoint(0.0, -179.0),
        mode = TravelMode.DRIVING,
      )
    assertThat(antimeridianRoute.points.size).isGreaterThan(2)
    for ((_, longitude) in antimeridianRoute.points) {
      assertThat(abs(longitude)).isAtLeast(179.0 - 1e-6)
    }
    locationDialog.routesMap.fitBounds(antimeridianRoute.points.map { GeoPoint(it.latitude, it.longitude, it.elevation) })
    assertThat(abs(locationDialog.routesMap.center.longitude)).isWithin(1e-6).of(180.0)
    assertThat(locationDialog.routesMap.zoom).isGreaterThan(5.0)
    // Restore original route bounds on routesMap.
    ui.clickOn(findRouteButton)

    assertThat(playRouteButton.isEnabled).isTrue()
    assertThat(saveRouteButton.isEnabled).isTrue()
    val pointCount = locationDialog.routesMap.routePoints.size
    assertThat(pointCount).isAtLeast(2)

    // Save the route.
    createModalDialogAndInteractWithIt({ ui.clickOn(saveRouteButton) }) { saveDlg ->
      val saveUi = FakeUi(saveDlg.rootPane)
      val nameField = saveUi.getComponent<JBTextField> { it.name == "routeNameField" }
      val descArea = saveUi.getComponent<JBTextArea> { it.name == "routeDescriptionArea" }
      nameField.text = "Morning Commute"
      descArea.text = "Campus to North Bayshore"
      saveUi.clickOn(saveDlg.rootPane.defaultButton)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertThat(routesTable.rowCount).isEqualTo(1)
    assertThat(routesTable.getValueAt(0, 0)).isEqualTo("Morning Commute")
    assertThat(routesTable.getValueAt(0, 1)).isEqualTo("Driving")
    assertThat(routesTable.getValueAt(0, 2)).isEqualTo(pointCount.toString())

    // Clear input fields, re-select the saved route, and verify waypoints and text fields are populated.
    originField.text = ""
    destinationField.text = ""
    routesTable.selectionModel.clearSelection()
    routesTable.selectionModel.setSelectionInterval(0, 0)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(originField.text).isEqualTo("37.4220, -122.0840")
    assertThat(destinationField.text).isEqualTo("37.4230, -122.0850")

    // Verify swapWaypointsButton and travelModeComboBox recalculate the route after selecting a saved route.
    ui.clickOn(swapWaypointsButton)
    assertThat(originField.text).isEqualTo("37.4230, -122.0850")
    assertThat(destinationField.text).isEqualTo("37.4220, -122.0840")
    ui.clickOn(swapWaypointsButton)
    assertThat(originField.text).isEqualTo("37.4220, -122.0840")
    assertThat(destinationField.text).isEqualTo("37.4230, -122.0850")
    travelModeComboBox.selectedItem = TravelMode.WALKING
    travelModeComboBox.selectedItem = TravelMode.DRIVING

    // Play the route at 5X speed with a short update interval.
    speedComboBox.selectedItem = PlaybackSpeed.SPEED_5X
    repeatCheckBox.isSelected = false
    locationDialog.startRoutePlayback(updateInterval = 20.milliseconds)
    assertThat(playRouteButton.text).isEqualTo("Stop Route")
    assertThat(tabbedPane.isEnabledAt(0)).isFalse()
    assertThat(routesTable.isEnabled).isFalse()
    assertThat(travelModeComboBox.isEnabled).isFalse()
    assertThat(originField.isEnabled).isFalse()
    assertThat(destinationField.isEnabled).isFalse()

    // Restart playback while already running and verify the canceled job's finally block does not reset the new playback state.
    locationDialog.startRoutePlayback(updateInterval = 20.milliseconds)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(playRouteButton.text).isEqualTo("Stop Route")
    assertThat(tabbedPane.isEnabledAt(0)).isFalse()
    assertThat(routesTable.isEnabled).isFalse()
    assertThat(travelModeComboBox.isEnabled).isFalse()
    assertThat(originField.isEnabled).isFalse()
    assertThat(destinationField.isEnabled).isFalse()

    // Wait for playback to complete and reach destination.
    waitForCondition(5.seconds) {
      playRouteButton.text == "Play Route" &&
        abs(fakeEmulator.gpsLocation.latitude - 37.4230) < 1e-4 &&
        abs(fakeEmulator.gpsLocation.longitude - (-122.0850)) < 1e-4
    }
    assertThat(tabbedPane.isEnabledAt(0)).isTrue()
    assertThat(routesTable.isEnabled).isTrue()
    assertThat(travelModeComboBox.isEnabled).isTrue()
    assertThat(originField.isEnabled).isTrue()
    assertThat(destinationField.isEnabled).isTrue()

    // Start playback again with repeat enabled, then close the dialog while playing and verify speed resets to 0.0.
    repeatCheckBox.isSelected = true
    locationDialog.startRoutePlayback(updateInterval = 20.milliseconds)
    waitForCondition(5.seconds) { fakeEmulator.gpsLocation.speed > 0.0 }
    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialogWrapper.isShowing).isFalse()
    waitForCondition(5.seconds) { fakeEmulator.gpsLocation.speed == 0.0 }
  }

  @Test
  fun testImportGpxAndKmlFiles() {
    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show()
    val rootPane = dialogWrapper.rootPane
    val ui = FakeUi(rootPane)

    val tabbedPane = ui.getComponent<JBTabbedPane> { it.name == "locationTabbedPane" }
    val pointsTable = ui.getComponent<TableView<*>> { it.name == "savedPointsTable" }
    val routesTable = ui.getComponent<TableView<*>> { it.name == "savedRoutesTable" }

    // 1. Import a single-waypoint GPX file -> adds to Saved Points.
    val singlePtGpx = tempFolder.newFile("single_point.gpx").toPath()
    Files.writeString(
      singlePtGpx,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="37.5000" lon="-122.2000">
          <ele>12.5</ele>
          <name>Redwood City</name>
          <desc>Peninsula stop</desc>
        </wpt>
      </gpx>
      """
        .trimIndent(),
    )
    locationDialog.importGpxKmlFile(singlePtGpx)
    waitForCondition(5.seconds) { pointsTable.rowCount == 1 }
    assertThat(tabbedPane.selectedIndex).isEqualTo(0)
    assertThat(pointsTable.getValueAt(0, 0)).isEqualTo("Redwood City")
    assertThat(pointsTable.getValueAt(0, 1)).isEqualTo("37.5000, -122.2000")
    assertThat(pointsTable.getValueAt(0, 2)).isEqualTo("Peninsula stop")

    // 2. Import a multipoint GPX track -> switches to Routes tab and adds to Saved Routes.
    val routeGpx = tempFolder.newFile("track_route.gpx").toPath()
    Files.writeString(
      routeGpx,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <trk>
          <name>Bike Trail</name>
          <desc>Morning ride</desc>
          <trkseg>
            <trkpt lat="37.4000" lon="-122.0500">
              <ele>5.0</ele>
              <time>2026-10-20T10:00:00Z</time>
            </trkpt>
            <trkpt lat="37.4050" lon="-122.0550">
              <ele>6.0</ele>
              <time>2026-10-20T10:00:15Z</time>
            </trkpt>
            <trkpt lat="37.4100" lon="-122.0600">
              <ele>7.0</ele>
              <time>2026-10-20T10:00:45Z</time>
            </trkpt>
          </trkseg>
        </trk>
      </gpx>
      """
        .trimIndent(),
    )
    locationDialog.importGpxKmlFile(routeGpx)
    waitForCondition(5.seconds) { routesTable.rowCount == 1 }
    assertThat(tabbedPane.selectedIndex).isEqualTo(1)
    assertThat(routesTable.getValueAt(0, 0)).isEqualTo("Bike Trail")
    assertThat(routesTable.getValueAt(0, 2)).isEqualTo("3")
    assertThat(routesTable.getValueAt(0, 3)).isEqualTo("45s")
    assertThat((routesTable.getRow(0) as SavedRouteItem).description).isEqualTo("Morning ride")

    // 3. Import a KML route with a non-geometry Placemark and a MultiGeometry Placemark -> adds to Saved Routes.
    val routeKml = tempFolder.newFile("placemark_route.kml").toPath()
    Files.writeString(
      routeKml,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <kml xmlns="http://www.opengis.net/kml/2.2">
        <Document>
          <Placemark>
            <name>Metadata Only</name>
            <description>No geometry child</description>
          </Placemark>
          <Placemark>
            <name>Bay Bridge Run</name>
            <description>KML LineString test</description>
            <MultiGeometry>
              <LineString>
                <coordinates>
                  -122.3900,37.7900,10
                  -122.3600,37.8100,25
                </coordinates>
              </LineString>
            </MultiGeometry>
          </Placemark>
        </Document>
      </kml>
      """
        .trimIndent(),
    )
    locationDialog.importGpxKmlFile(routeKml)
    waitForCondition(5.seconds) { routesTable.rowCount == 2 }
    assertThat(routesTable.getValueAt(1, 0)).isEqualTo("Bay Bridge Run")
    assertThat(routesTable.getValueAt(1, 2)).isEqualTo("2")

    // 3b. Import a KML gx:Track with <when> timestamps -> preserves recorded segment durations.
    val gxTrackKml = tempFolder.newFile("gx_track.kml").toPath()
    Files.writeString(
      gxTrackKml,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
        <Document>
          <Placemark>
            <name>Timed KML Track</name>
            <gx:Track>
              <when>2026-10-20T10:00:00Z</when>
              <when>2026-10-20T10:00:20Z</when>
              <when>2026-10-20T10:01:05Z</when>
              <gx:coord>-122.0500 37.4000 5.0</gx:coord>
              <gx:coord>-122.0550 37.4050 6.0</gx:coord>
              <gx:coord>-122.0600 37.4100 7.0</gx:coord>
            </gx:Track>
          </Placemark>
        </Document>
      </kml>
      """
        .trimIndent(),
    )
    locationDialog.importGpxKmlFile(gxTrackKml)
    waitForCondition(5.seconds) { routesTable.rowCount == 3 }
    assertThat(routesTable.getValueAt(2, 0)).isEqualTo("Timed KML Track")
    assertThat(routesTable.getValueAt(2, 2)).isEqualTo("3")
    assertThat(routesTable.getValueAt(2, 3)).isEqualTo("1m 5s")

    // 4. Import a GPX track with mixed timed/untimed points -> preserves track order and computes reasonable duration.
    val mixedGpx = tempFolder.newFile("mixed_times.gpx").toPath()
    Files.writeString(
      mixedGpx,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <trk>
          <name>Mixed Timestamps</name>
          <trkseg>
            <trkpt lat="37.4000" lon="-122.0500" />
            <trkpt lat="37.4050" lon="-122.0550">
              <time>2026-10-20T10:00:15Z</time>
            </trkpt>
            <trkpt lat="37.4100" lon="-122.0600" />
          </trkseg>
        </trk>
      </gpx>
      """
        .trimIndent(),
    )
    locationDialog.importGpxKmlFile(mixedGpx)
    waitForCondition(5.seconds) { routesTable.rowCount == 4 }
    val mixedRoute = routesTable.getRow(3) as SavedRouteItem
    assertThat(mixedRoute.name).isEqualTo("Mixed Timestamps")
    assertThat(mixedRoute.points.map { it.latitude }).containsExactly(37.4000, 37.4050, 37.4100).inOrder()
    assertThat(mixedRoute.durationSeconds).isLessThan(3600)

    // 5. Out-of-range or non-finite coordinates in GPX and KML are rejected.
    val invalidGpx = tempFolder.newFile("invalid_lat.gpx").toPath()
    Files.writeString(
      invalidGpx,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="95.0" lon="-122.2000" />
      </gpx>
      """
        .trimIndent(),
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(invalidGpx) }

    val nanGpx = tempFolder.newFile("nan_lat.gpx").toPath()
    Files.writeString(
      nanGpx,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="NaN" lon="-122.2000" />
      </gpx>
      """
        .trimIndent(),
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(nanGpx) }

    val invertedKml = tempFolder.newFile("inverted_coords.kml").toPath()
    Files.writeString(
      invertedKml,
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <kml xmlns="http://www.opengis.net/kml/2.2">
        <Placemark>
          <Point>
            <coordinates>37.4220,-122.0840,0</coordinates>
          </Point>
        </Placemark>
      </kml>
      """
        .trimIndent(),
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(invertedKml) }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialogWrapper.isShowing).isFalse()
  }

  @Test
  fun testWorldMapPolygonsDoNotSelfIntersect() {
    assertThat(OfflineWorldMap.loadedMapData.landPolygons.size).isGreaterThan(1400)
    assertThat(OfflineWorldMap.loadedMapData.lakePolygons.size).isGreaterThan(400)
    assertThat(OfflineWorldMap.loadedMapData.lakeIslandPolygons.size).isGreaterThan(50)

    // Verify Antarctica is present (spans -180°..+180° longitude near the southern Web Mercator boundary).
    val antarctica = OfflineWorldMap.loadedMapData.landPolygons.find { it.maxNormY > 0.99f && it.maxNormX - it.minNormX > 0.99f }
    assertThat(antarctica).isNotNull()

    // Verify all UN country capitals, state/provincial capitals, and >=100k population cities are present.
    assertThat(OfflineWorldMap.loadedMapData.cities.size).isGreaterThan(5000)
    assertThat(OfflineWorldMap.loadedMapData.cities.count { it.isCapital }).isAtLeast(193)

    for ((groupName, group) in
      listOf(
        "land" to OfflineWorldMap.loadedMapData.landPolygons,
        "lake" to OfflineWorldMap.loadedMapData.lakePolygons,
        "lakeIsland" to OfflineWorldMap.loadedMapData.lakeIslandPolygons,
      )) {
      for ((polyIndex, polygon) in group.withIndex()) {
        val coords = polygon.normCoords
        assertThat(coords.size % 2).isEqualTo(0)
        val n = coords.size / 2
        assertThat(n).isAtLeast(3)

        for (i in 0 until n) {
          val iNext = (i + 1) % n
          val x1 = coords[i * 2].toDouble()
          val y1 = coords[i * 2 + 1].toDouble()
          val x2 = coords[iNext * 2].toDouble()
          val y2 = coords[iNext * 2 + 1].toDouble()
          val minX1 = minOf(x1, x2)
          val maxX1 = maxOf(x1, x2)
          val minY1 = minOf(y1, y2)
          val maxY1 = maxOf(y1, y2)

          for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue // Adjacent at vertex 0
            val jNext = (j + 1) % n
            val x3 = coords[j * 2].toDouble()
            val y3 = coords[j * 2 + 1].toDouble()
            val x4 = coords[jNext * 2].toDouble()
            val y4 = coords[jNext * 2 + 1].toDouble()
            if (maxX1 < minOf(x3, x4) || maxOf(x3, x4) < minX1 || maxY1 < minOf(y3, y4) || maxOf(y3, y4) < minY1) {
              continue
            }

            val intersects = Line2D.linesIntersect(x1, y1, x2, y2, x3, y3, x4, y4)
            assertThat(intersects).named("$groupName polygon $polyIndex edge $i intersects edge $j").isFalse()
          }
        }
      }
    }
  }

  private fun performAction(action: AnAction) {
    if (action is AnActionButton) {
      val contextComponent =
        object : JLayeredPane() {
          override fun isShowing(): Boolean = true
        }
      action.withContextComponent(contextComponent) {
        executeAction(action)
      }
    } else {
      executeAction(action)
    }
  }

  private fun isPresentationEnabled(action: AnAction): Boolean {
    return if (action is AnActionButton) {
      val contextComponent =
        object : JLayeredPane() {
          override fun isShowing(): Boolean = true
        }
      action.withContextComponent(contextComponent) {
        updateAndGetActionPresentation(action).isEnabled
      }
    } else {
      updateAndGetActionPresentation(action).isEnabled
    }
  }

  private fun <T> AnActionButton.withContextComponent(component: JComponent, function: () -> T): T {
    val savedContextComponent = contextComponent
    try {
      contextComponent = component
      return function()
    } finally {
      contextComponent = savedContextComponent
    }
  }
}
