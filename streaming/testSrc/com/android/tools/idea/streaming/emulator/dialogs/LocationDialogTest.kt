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

import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.impl.FileChooserUtil
import com.intellij.openapi.fileChooser.universal.UniversalFileChooser
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.geom.Line2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.UIManager
import kotlin.io.path.writeText
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
      PortableUiFontRule(),
      HeadlessDialogRule(),
    )

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SELECTED_ROUTE_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SORT_ORDER_STORAGE_KEY)
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
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SELECTED_ROUTE_STORAGE_KEY)
    PropertiesComponent.getInstance().unsetValue(LocationDialog.SORT_ORDER_STORAGE_KEY)
  }

  @Test
  fun testInitialStateLayoutAndZoomLimits() {
    PropertiesComponent.getInstance()
      .setValue(
        LocationDialog.SAVED_ROUTES_STORAGE_KEY,
        """[{"name":"Paris Walk","description":"Louvre","durationSeconds":20,"points":[{"latitude":48.8566,"longitude":2.3522,"elevation":35.0,"delaySeconds":0.0},{"latitude":48.8606,"longitude":2.3376,"elevation":35.0,"delaySeconds":20.0}]}]""",
      )

    fakeEmulator.pauseGrpc()
    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show(projectRule.project)
    val ui = FakeUi(dialogWrapper.rootPane)
    ui.layoutAndDispatchEvents()

    val routesTable = ui.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    val routesMap = ui.getComponent<MapPanel> { it.name == "routesMap" }
    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    val timeLabel = ui.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    val repeatCheckBox = ui.getComponent<JBCheckBox> { it.name == "repeatPlaybackCheckBox" }
    val speedComboBox = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val speedItems = (0 until speedComboBox.itemCount).map { speedComboBox.getItemAt(it).toString() }
    assertThat(speedItems).containsExactly("1X", "2X", "5X", "10X", "20X", "50X", "100X").inOrder()

    // Map is positioned to the right of the saved routes table, matching SensorReplayDialog's master-detail layout.
    assertThat(ui.getPosition(routesTable).x).isLessThan(ui.getPosition(routesMap).x)
    assertThat(routesMap.cursor).isNotEqualTo(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
    assertThat(routesMap.cursor).isNotEqualTo(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR))
    assertThat(routesMap.zoomOutButton.cursor).isEqualTo(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR))
    assertThat(routesTable.rowCount).isEqualTo(1)
    assertThat(routesTable.selectedRow).isEqualTo(0)
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:20")

    // Verify icon-only square Play button presentation, tooltip, and accessible name.
    assertThat(playPauseButton.text).isEmpty()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.toolTipText).isEqualTo("Play")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")
    assertThat(playPauseButton.preferredSize.width).isEqualTo(playPauseButton.preferredSize.height)
    assertThat(slider).isInstanceOf(ProgressSlider::class.java)

    // Playback and seeking stay disabled until initial GPS arrives, while saved route selection & map remain usable.
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(slider.isEnabled).isFalse()
    assertThat(repeatCheckBox.isEnabled).isTrue()
    assertThat(speedComboBox.isEnabled).isTrue()
    assertThat(routesMap.routePoints).hasSize(2)
    val routeCenterBeforeGps = routesMap.center

    fakeEmulator.resumeGrpc()
    waitForCondition(5.seconds) { playPauseButton.isEnabled }
    assertThat(slider.isEnabled).isTrue()

    // Initial getGps completion must not pan routesMap away from the already-selected route,
    // and must send the first point of the initially selected route to the emulator.
    assertThat(routesMap.center).isEqualTo(routeCenterBeforeGps)
    assertThat(routesMap.deviceLocation).isEqualTo(GeoPoint(48.8566, 2.3522, 35.0))
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 48.8566) < 1e-4 && abs(fakeEmulator.gpsLocation.longitude - 2.3522) < 1e-4
    }

    // Verify offline map renders cleanly and zooming out is limited to -60°..+60° when centered at the equator.
    assertThat(ui.render().width).isGreaterThan(0)
    routesMap.panTo(GeoPoint(0.0, 0.0))
    routesMap.setZoomLevel(GeoMath.MIN_ZOOM)
    assertThat(routesMap.zoomOutButton.isEnabled).isFalse()
    val topLat = routesMap.pixelToGeoPoint(0.0, 0.0).latitude
    val bottomLat = routesMap.pixelToGeoPoint(0.0, routesMap.height.toDouble()).latitude
    assertThat(topLat).isWithin(1e-2).of(60.0)
    assertThat(bottomLat).isWithin(1e-2).of(-60.0)
    routesMap.setZoomLevel(GeoMath.DEFAULT_ZOOM)
    assertThat(routesMap.zoomOutButton.isEnabled).isTrue()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialogWrapper.isShowing).isFalse()
  }

  @Test
  fun testSavedRoutesColumnMinimumAndPreferredWidth() {
    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show(projectRule.project)
    val rootPane = dialogWrapper.rootPane
    val ui = FakeUi(rootPane)

    val savedRoutesTablePanel = ui.getComponent<JPanel> { it.name == "savedRoutesTablePanel" }
    assertThat(savedRoutesTablePanel.preferredSize.width).isEqualTo(JBUIScale.scale(280))
    assertThat(savedRoutesTablePanel.minimumSize.width).isEqualTo(JBUIScale.scale(200))

    rootPane.size = rootPane.preferredSize
    ui.layoutAndDispatchEvents()
    assertThat(savedRoutesTablePanel.width).isEqualTo(JBUIScale.scale(280))

    rootPane.size = rootPane.minimumSize
    ui.layoutAndDispatchEvents()
    assertThat(savedRoutesTablePanel.width).isEqualTo(JBUIScale.scale(200))

    dialogWrapper.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testImportGpxAndKmlFilesAndRemoval() {
    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show(projectRule.project)
    val ui = FakeUi(dialogWrapper.rootPane)

    val routesTable = ui.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    val routesMap = ui.getComponent<MapPanel> { it.name == "routesMap" }
    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    val timeLabel = ui.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    val repeatCheckBox = ui.getComponent<JBCheckBox> { it.name == "repeatPlaybackCheckBox" }
    val speedLabel = ui.getComponent<JLabel> { it.name == "playbackSpeedLabel" }
    val speedComboBox = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }

    waitForCondition(5.seconds) { routesMap.deviceLocation != null }
    assertThat(routesTable.rowCount).isEqualTo(0)
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(playPauseButton.disabledIcon).isEqualTo(IconLoader.getDisabledIcon(AllIcons.Actions.Execute))
    assertThat(slider.isEnabled).isFalse()
    assertThat(timeLabel.isEnabled).isFalse()
    assertThat(repeatCheckBox.isEnabled).isFalse()
    assertThat(speedLabel.isEnabled).isFalse()
    assertThat(speedComboBox.isEnabled).isFalse()
    val emptyFragments = routesTable.emptyText.wrappedFragmentsIterable.toList()
    assertThat(emptyFragments.joinToString(" ") { it.toString() }).isEqualTo("Click the plus button below to import a GPX or KML file")

    // 1. Import via ToolbarDecorator's Add action and verify file chooser descriptor filters GPX/KML files.
    val routeGpx = tempFolder.newFile("track_route.gpx").toPath()
    routeGpx.writeText(
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
        .trimIndent()
    )
    val notesTxt = tempFolder.newFile("notes.txt").toPath().apply { writeText("ignored") }
    val localFs = LocalFileSystem.getInstance()
    val routeGpxVf = checkNotNull(localFs.refreshAndFindFileByNioFile(routeGpx))
    val notesTxtVf = checkNotNull(localFs.refreshAndFindFileByNioFile(notesTxt))

    val defaultChooserCreator = locationDialog.fileChooserCreator
    var capturedDescriptor: FileChooserDescriptor? = null
    locationDialog.fileChooserCreator = { descriptor, parentComponent, project ->
      capturedDescriptor = descriptor
      if (!GraphicsEnvironment.isHeadless()) {
        val defaultChooser = defaultChooserCreator(descriptor, parentComponent, project)
        try {
          @Suppress("UnstableApiUsage") assertThat(defaultChooser).isInstanceOf(UniversalFileChooser.Dialog::class.java)
        } finally {
          Disposer.dispose((defaultChooser as DialogWrapper).disposable)
        }
      }
      FileChooserDialog { _, _ -> FileChooserUtil.getChosenFiles(descriptor, listOf(notesTxtVf, routeGpxVf)).toTypedArray() }
    }
    performToolbarAction(ui, "Add")
    waitForCondition(5.seconds) { routesTable.rowCount == 1 }

    val descriptor = checkNotNull(capturedDescriptor)
    assertThat(FileChooserUtil.getChosenFiles(descriptor, listOf(routeGpxVf, notesTxtVf))).containsExactly(routeGpxVf)

    assertThat(routesTable.items[0].name).isEqualTo("Bike Trail")
    assertThat(routesTable.getValueAt(0, 1)).isEqualTo("3")
    assertThat(routesTable.getValueAt(0, 2)).isEqualTo("45s")
    assertThat(routesTable.items[0].description).isEqualTo("Morning ride")
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:45")
    assertThat(playPauseButton.isEnabled).isTrue()
    assertThat(slider.isEnabled).isTrue()
    assertThat(timeLabel.isEnabled).isTrue()
    assertThat(repeatCheckBox.isEnabled).isTrue()
    assertThat(speedLabel.isEnabled).isTrue()
    assertThat(speedComboBox.isEnabled).isTrue()
    assertThat(routesMap.routePoints).hasSize(3)
    assertThat(routesMap.routeOrigin).isEqualTo(GeoPoint(37.4000, -122.0500, 5.0))
    assertThat(routesMap.routeDestination).isEqualTo(GeoPoint(37.4100, -122.0600, 7.0))
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 37.4000) < 1e-4 && abs(fakeEmulator.gpsLocation.longitude - (-122.0500)) < 1e-4
    }

    // 2. Import a single-waypoint GPX file -> adds 1-point entry; all playback-related controls are disabled.
    val singlePtGpx = tempFolder.newFile("single_point.gpx").toPath()
    singlePtGpx.writeText(
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
        .trimIndent()
    )
    locationDialog.importGpxKmlFile(singlePtGpx)
    waitForCondition(5.seconds) { routesTable.rowCount == 2 }
    assertThat(routesTable.items[1].name).isEqualTo("Redwood City")
    assertThat(routesTable.getValueAt(1, 1)).isEqualTo("1")
    assertThat(routesTable.getValueAt(1, 2)).isEqualTo("0s")
    assertThat(slider.isEnabled).isFalse()
    assertThat(timeLabel.isEnabled).isFalse()
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(playPauseButton.disabledIcon).isEqualTo(IconLoader.getDisabledIcon(AllIcons.Actions.Execute))
    assertThat(repeatCheckBox.isEnabled).isFalse()
    assertThat(speedLabel.isEnabled).isFalse()
    assertThat(speedComboBox.isEnabled).isFalse()
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 37.5000) < 1e-4 && abs(fakeEmulator.gpsLocation.longitude - (-122.2000)) < 1e-4
    }

    // 3. Import a KML route with a non-geometry Placemark and a MultiGeometry Placemark.
    val routeKml = tempFolder.newFile("placemark_route.kml").toPath()
    routeKml.writeText(
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
        .trimIndent()
    )
    locationDialog.importGpxKmlFile(routeKml)
    waitForCondition(5.seconds) { routesTable.rowCount == 3 }
    assertThat(routesTable.items[2].name).isEqualTo("Bay Bridge Run")
    assertThat(routesTable.getValueAt(0, 1)).isEqualTo("2")

    // 4. Import a KML gx:Track with <when> timestamps -> preserves recorded segment durations.
    val gxTrackKml = tempFolder.newFile("gx_track.kml").toPath()
    gxTrackKml.writeText(
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
        .trimIndent()
    )
    locationDialog.importGpxKmlFile(gxTrackKml)
    waitForCondition(5.seconds) { routesTable.rowCount == 4 }
    assertThat(routesTable.items[3].name).isEqualTo("Timed KML Track")
    assertThat(routesTable.getValueAt(3, 1)).isEqualTo("3")
    assertThat(routesTable.getValueAt(3, 2)).isEqualTo("1m 5s")
    assertThat(timeLabel.text).isEqualTo("00:00 / 01:05")

    // 5. Import a GPX track with mixed timed/untimed points -> preserves track order and computes reasonable duration.
    val mixedGpx = tempFolder.newFile("mixed_times.gpx").toPath()
    mixedGpx.writeText(
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
        .trimIndent()
    )
    locationDialog.importGpxKmlFile(mixedGpx)
    waitForCondition(5.seconds) { routesTable.rowCount == 5 }
    val mixedRoute = routesTable.getRow(2) as SavedRouteItem
    assertThat(mixedRoute.name).isEqualTo("Mixed Timestamps")
    assertThat(mixedRoute.points.map { it.latitude }).containsExactly(37.4000, 37.4050, 37.4100).inOrder()
    assertThat(mixedRoute.durationSeconds).isLessThan(3600)

    // 6. Re-importing an already-imported file shows a duplicate error without adding a second entry.
    locationDialog.importGpxKmlFile(routeGpx)
    waitForCondition(5.seconds) { hasValidationError(ui, "Route or location 'Bike Trail' has already been imported") }
    assertThat(routesTable.rowCount).isEqualTo(5)

    // 7. Empty or invalid GPX/KML files show validation errors without modifying saved routes.
    val emptyGpx = tempFolder.newFile("empty.gpx").toPath().apply { writeText("""<?xml version="1.0"?><gpx version="1.1"/>""") }
    locationDialog.importGpxKmlFile(emptyGpx)
    waitForCondition(5.seconds) { hasValidationError(ui, "No valid locations found in the file") }
    assertThat(routesTable.rowCount).isEqualTo(5)

    val invalidGpx = tempFolder.newFile("invalid_lat.gpx").toPath()
    invalidGpx.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="95.0" lon="-122.2000" />
      </gpx>
      """
        .trimIndent()
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(invalidGpx) }
    locationDialog.importGpxKmlFile(invalidGpx)
    waitForCondition(5.seconds) { hasValidationError(ui, "Failed to parse GPX/KML file:") }

    val invalidTimeGpx = tempFolder.newFile("invalid_time.gpx").toPath()
    invalidTimeGpx.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="37.5000" lon="-122.2000">
          <time>2026-13-45T25:61:61</time>
        </wpt>
      </gpx>
      """
        .trimIndent()
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(invalidTimeGpx) }
    locationDialog.importGpxKmlFile(invalidTimeGpx)
    waitForCondition(5.seconds) { hasValidationError(ui, "Failed to parse GPX/KML file:") }
    assertThat(routesTable.rowCount).isEqualTo(5)

    // Non-local VirtualFile (toNioPath throws UnsupportedOperationException) imports via stream without crashing.
    val nonLocalVf =
      LightVirtualFile(
        "virtual_stop.gpx",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1">
          <wpt lat="37.4500" lon="-122.1500">
            <name>Virtual Stop</name>
          </wpt>
        </gpx>
        """
          .trimIndent(),
      )
    assertThrows(UnsupportedOperationException::class.java) { nonLocalVf.toNioPath() }
    locationDialog.fileChooserCreator = { _, _, _ -> FileChooserDialog { _, _ -> arrayOf(nonLocalVf) } }
    performToolbarAction(ui, "Add")
    waitForCondition(5.seconds) { routesTable.rowCount == 6 }

    val nanGpx = tempFolder.newFile("nan_lat.gpx").toPath()
    nanGpx.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <wpt lat="NaN" lon="-122.2000" />
      </gpx>
      """
        .trimIndent()
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(nanGpx) }

    val invertedKml = tempFolder.newFile("inverted_coords.kml").toPath()
    invertedKml.writeText(
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
        .trimIndent()
    )
    assertThrows(IllegalArgumentException::class.java) { GpxKmlParser.parseFile(invertedKml) }

    // 7. Verify antimeridian (±180°) fitBounds wraps across the shortest 2° seam instead of 358° backwards.
    routesMap.fitBounds(listOf(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0)))
    assertThat(abs(routesMap.center.longitude)).isWithin(1e-6).of(180.0)
    assertThat(routesMap.zoom).isGreaterThan(5.0)

    // 8. Select "Bay Bridge Run" (view row 0), close and reopen dialog -> persisted routes and selected route are restored, then remove all
    // routes.
    routesTable.selectionModel.setSelectionInterval(0, 0)
    dialogWrapper.close(DialogWrapper.CLOSE_EXIT_CODE)
    val reopenedDialog = LocationDialog(emulatorController)
    val reopenedWrapper = reopenedDialog.show(projectRule.project)
    val reopenedUi = FakeUi(reopenedWrapper.rootPane)
    val reopenedTable = reopenedUi.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    val reopenedMap = reopenedUi.getComponent<MapPanel> { it.name == "routesMap" }
    val reopenedSlider = reopenedUi.getComponent<JSlider> { it.name == "playbackSlider" }
    val reopenedTimeLabel = reopenedUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    val reopenedPlayPause = reopenedUi.getComponent<JButton> { it.name == "playPauseButton" }
    waitForCondition(5.seconds) { reopenedPlayPause.isEnabled }
    assertThat(reopenedTable.rowCount).isEqualTo(6)
    assertThat(reopenedTable.selectedRow).isEqualTo(0)
    assertThat(reopenedTable.selectedObject?.name).isEqualTo("Bay Bridge Run")

    reopenedTable.selectionModel.setSelectionInterval(0, reopenedTable.rowCount - 1)
    performToolbarAction(reopenedUi, "Remove")
    waitForCondition(5.seconds) { reopenedTable.rowCount == 0 }
    assertThat(reopenedMap.routePoints).isEmpty()
    assertThat(reopenedMap.routeOrigin).isNull()
    assertThat(reopenedMap.routeDestination).isNull()
    assertThat(reopenedTimeLabel.text).isEqualTo("00:00 / 00:00")
    assertThat(reopenedPlayPause.isEnabled).isFalse()
    assertThat(reopenedSlider.isEnabled).isFalse()

    reopenedUi.clickOn(reopenedUi.getComponent<JButton> { it.text == "Close" })
    assertThat(reopenedWrapper.isShowing).isFalse()
  }

  @Test
  fun testColumnSortingSelectedRoutePersistenceAndNoFocusBorder() {
    val routeA =
      SavedRouteItem(
        name = "alpha route",
        description = "Short 10-point walk",
        durationSeconds = 30,
        points = List(10) { RoutePoint(37.4 + it * 0.001, -122.0, 0.0, if (it == 0) 0.0 else 3.0) },
      )
    val pointB =
      SavedRouteItem(
        name = "Beta Point",
        description = "Single waypoint",
        durationSeconds = 0,
        points = listOf(RoutePoint(37.5, -122.1, 0.0, 0.0)),
      )
    val routeC =
      SavedRouteItem(
        name = "gamma route",
        description = "Longer 2-point drive",
        durationSeconds = 120,
        points = listOf(RoutePoint(37.6, -122.2, 0.0, 0.0), RoutePoint(37.7, -122.3, 0.0, 120.0)),
      )
    PropertiesComponent.getInstance()
      .setValue(LocationDialog.SAVED_ROUTES_STORAGE_KEY, com.google.gson.Gson().toJson(listOf(routeC, routeA, pointB)))

    val locationDialog = LocationDialog(emulatorController)
    val dialogWrapper = locationDialog.show(projectRule.project)
    val ui = FakeUi(dialogWrapper.rootPane)
    ui.layoutAndDispatchEvents()

    val routesTable = ui.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    val routesMap = ui.getComponent<MapPanel> { it.name == "routesMap" }
    assertThat(routesTable.rowCount).isEqualTo(3)
    // Sorted by Name ascending by default, with the first visible item selected.
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("alpha route", "Beta Point", "gamma route")
      .inOrder()
    assertThat(routesTable.selectedObject?.name).isEqualTo("alpha route")

    // Verify no focus border is added to any cell (including Name) when focused and selected.
    val noFocusBorder = UIManager.getBorder("Table.cellNoFocusBorder")
    for (col in 0 until routesTable.columnCount) {
      val renderer = routesTable.getCellRenderer(0, col)
      val value = routesTable.getValueAt(0, col)
      val focusedComp = renderer.getTableCellRendererComponent(routesTable, value, true, true, 0, col) as JComponent
      val unfocusedComp = renderer.getTableCellRendererComponent(routesTable, value, true, false, 0, col) as JComponent
      assertThat(focusedComp.border).isEqualTo(unfocusedComp.border)
      if (noFocusBorder != null) {
        assertThat(focusedComp.border).isEqualTo(noFocusBorder)
      }
    }
    // Verify Name column cell renderer preserves route description tooltip.
    val nameRendererComp = routesTable.prepareRenderer(routesTable.getCellRenderer(0, 0), 0, 0) as JComponent
    assertThat(nameRendererComp.toolTipText).contains("Short 10-point walk")

    // Click Name column header -> toggles from default ascending to descending, then back to ascending.
    clickTableHeader(ui, routesTable, 0)
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("gamma route", "Beta Point", "alpha route")
      .inOrder()
    assertThat(routesTable.selectedObject?.name).isEqualTo("alpha route")
    clickTableHeader(ui, routesTable, 0)
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("alpha route", "Beta Point", "gamma route")
      .inOrder()

    // Click Points column header -> numeric sort (1, 2, 10, not lexicographic "1", "10", "2").
    clickTableHeader(ui, routesTable, 1)
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("Beta Point", "gamma route", "alpha route")
      .inOrder()
    clickTableHeader(ui, routesTable, 1)
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("alpha route", "gamma route", "Beta Point")
      .inOrder()

    // Click Duration column header -> numeric duration sort (0s, 30s, 2m 0s).
    clickTableHeader(ui, routesTable, 2)
    assertThat((0 until routesTable.rowCount).map { routesTable.getRow(it).name })
      .containsExactly("Beta Point", "alpha route", "gamma route")
      .inOrder()

    // Select "Beta Point" (view row 0), verify it sends GPS to emulator, close dialog, and verify both sort order and selection are
    // preserved on reopen.
    routesTable.selectionModel.setSelectionInterval(0, 0)
    assertThat(routesTable.selectedObject?.name).isEqualTo("Beta Point")
    assertThat(routesMap.routeOrigin).isEqualTo(GeoPoint(37.5, -122.1, 0.0))
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 37.5) < 1e-4 && abs(fakeEmulator.gpsLocation.longitude - (-122.1)) < 1e-4
    }
    dialogWrapper.close(DialogWrapper.CLOSE_EXIT_CODE)

    val reopenedDialog = LocationDialog(emulatorController)
    val reopenedWrapper = reopenedDialog.show(projectRule.project)
    val reopenedUi = FakeUi(reopenedWrapper.rootPane)
    reopenedUi.layoutAndDispatchEvents()
    val reopenedTable = reopenedUi.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    val reopenedMap = reopenedUi.getComponent<MapPanel> { it.name == "routesMap" }
    assertThat((0 until reopenedTable.rowCount).map { reopenedTable.getRow(it).name })
      .containsExactly("Beta Point", "alpha route", "gamma route")
      .inOrder()
    assertThat(reopenedTable.selectedObject?.name).isEqualTo("Beta Point")
    assertThat(reopenedMap.routeOrigin).isEqualTo(GeoPoint(37.5, -122.1, 0.0))

    // Reverse Duration sort order to descending, reopen again, and verify descending sort order is preserved.
    clickTableHeader(reopenedUi, reopenedTable, 2)
    assertThat((0 until reopenedTable.rowCount).map { reopenedTable.getRow(it).name })
      .containsExactly("gamma route", "alpha route", "Beta Point")
      .inOrder()
    reopenedWrapper.close(DialogWrapper.CLOSE_EXIT_CODE)

    val thirdDialog = LocationDialog(emulatorController)
    val thirdWrapper = thirdDialog.show(projectRule.project)
    val thirdUi = FakeUi(thirdWrapper.rootPane)
    thirdUi.layoutAndDispatchEvents()
    val thirdTable = thirdUi.getComponent<TableView<SavedRouteItem>> { it.name == "savedRoutesTable" }
    assertThat((0 until thirdTable.rowCount).map { thirdTable.getRow(it).name })
      .containsExactly("gamma route", "alpha route", "Beta Point")
      .inOrder()
    assertThat(thirdTable.selectedObject?.name).isEqualTo("Beta Point")

    thirdWrapper.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testRoutePlaybackSeekPauseRepeatAndClose() {
    val locationDialog = LocationDialog(emulatorController)
    locationDialog.playbackUpdateInterval = 20.milliseconds
    val dialogWrapper = locationDialog.show(projectRule.project)
    val ui = FakeUi(dialogWrapper.rootPane)

    val routesTable = ui.getComponent<TableView<*>> { it.name == "savedRoutesTable" }
    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    val timeLabel = ui.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    val speedComboBox = ui.getComponent<ComboBox<PlaybackSpeed>> { it.name == "playbackSpeedComboBox" }
    val repeatCheckBox = ui.getComponent<JBCheckBox> { it.name == "repeatPlaybackCheckBox" }

    // Verify westward headings are normalized to [0.0, 360.0).
    assertThat(GeoMath.headingDegrees(0.0, 0.0, 0.0, -1.0)).isWithin(1e-6).of(270.0)
    assertThat(GeoMath.headingDegrees(37.4220, -122.0840, 37.4230, -122.0850)).isIn(Range.closedOpen(270.0, 360.0))

    val routeGpx = tempFolder.newFile("commute.gpx").toPath()
    routeGpx.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1">
        <trk>
          <name>Morning Commute</name>
          <trkseg>
            <trkpt lat="37.4220" lon="-122.0840">
              <ele>5.0</ele>
              <time>2026-10-20T10:00:00Z</time>
            </trkpt>
            <trkpt lat="37.4230" lon="-122.0850">
              <ele>15.0</ele>
              <time>2026-10-20T10:00:04Z</time>
            </trkpt>
          </trkseg>
        </trk>
      </gpx>
      """
        .trimIndent()
    )
    locationDialog.importGpxKmlFile(routeGpx)
    waitForCondition(5.seconds) { routesTable.rowCount == 1 && playPauseButton.isEnabled }
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:04")

    // Seek to 50% (2s) -> interpolates halfway between origin and destination with speed 0.
    slider.value = 500
    waitForCondition(5.seconds) {
      abs(fakeEmulator.gpsLocation.latitude - 37.4225) < 1e-4 && abs(fakeEmulator.gpsLocation.longitude - (-122.0845)) < 1e-4
    }
    assertThat(fakeEmulator.gpsLocation.speed).isEqualTo(0.0)
    assertThat(timeLabel.text).isEqualTo("00:02 / 00:04")

    // Start playback from 50% at 5X speed.
    speedComboBox.selectedItem = PlaybackSpeed.SPEED_5X
    repeatCheckBox.isSelected = false
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(playPauseButton.toolTipText).isEqualTo("Pause")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Pause")
    assertThat(routesTable.isEnabled).isFalse()

    // Pause mid-playback or restart playback while already running and verify canceled job does not reset new state.
    locationDialog.startRoutePlayback(updateInterval = 20.milliseconds)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(routesTable.isEnabled).isFalse()

    // Pause playback manually and verify speed resets to 0.0 and controls become interactive.
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.toolTipText).isEqualTo("Play")
    assertThat(routesTable.isEnabled).isTrue()
    waitForCondition(5.seconds) { fakeEmulator.gpsLocation.speed == 0.0 }

    // Resume playback to completion -> reaches destination, resets speed to 0.0 and slider to 0.
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) {
      playPauseButton.icon == AllIcons.Actions.Execute &&
        abs(fakeEmulator.gpsLocation.latitude - 37.4230) < 1e-4 &&
        abs(fakeEmulator.gpsLocation.longitude - (-122.0850)) < 1e-4 &&
        fakeEmulator.gpsLocation.speed == 0.0
    }
    assertThat(slider.value).isEqualTo(0)
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:04")
    assertThat(routesTable.isEnabled).isTrue()

    // Seek to the very end while playing (with repeat off) -> finishes at destination instead of restarting from 0.
    speedComboBox.selectedItem = PlaybackSpeed.SPEED_1X
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) { playPauseButton.icon == AllIcons.Actions.Pause && fakeEmulator.gpsLocation.speed > 0.0 }
    slider.valueIsAdjusting = true
    slider.value = SensorSessionPlayer.SLIDER_MAX
    slider.valueIsAdjusting = false
    waitForCondition(5.seconds) {
      playPauseButton.icon == AllIcons.Actions.Execute &&
        abs(fakeEmulator.gpsLocation.latitude - 37.4230) < 1e-4 &&
        abs(fakeEmulator.gpsLocation.longitude - (-122.0850)) < 1e-4 &&
        fakeEmulator.gpsLocation.speed == 0.0
    }
    assertThat(slider.value).isEqualTo(0)
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:04")

    // Start playback again with repeat enabled, then close dialog while playing -> resets speed to 0.0.
    repeatCheckBox.isSelected = true
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) { fakeEmulator.gpsLocation.speed > 0.0 }
    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialogWrapper.isShowing).isFalse()
    waitForCondition(5.seconds) { fakeEmulator.gpsLocation.speed == 0.0 }
  }

  @Test
  fun testRouteEvaluationWithZeroDelaysAndGsonDeserialization() {
    val route =
      SavedRouteItem(
        name = "Zero-delay segments",
        durationSeconds = 30,
        points =
          listOf(
            RoutePoint(0.0, 0.0, 0.0, 0.0),
            RoutePoint(1.0, 1.0, 10.0, 0.0),
            RoutePoint(2.0, 2.0, 20.0, 10.0),
            RoutePoint(3.0, 3.0, 30.0, 0.0),
            RoutePoint(5.0, 5.0, 50.0, 20.0),
          ),
      )
    val gson = com.google.gson.Gson()
    val json = gson.toJson(route)
    assertThat(json).doesNotContain("cachedCumulativeTimesSeconds")
    val roundTripped = gson.fromJson(json, SavedRouteItem::class.java)
    assertThat(roundTripped.duration).isEqualTo(30.seconds)
    assertThat(roundTripped.cumulativeTimesSeconds === roundTripped.cumulativeTimesSeconds).isTrue()

    // At 0s, skips leading zero-delay segment (0->1) and starts on segment (1->2).
    assertThat(LocationDialog.evaluateRouteAt(roundTripped, 0.seconds, 1)?.point).isEqualTo(GeoPoint(1.0, 1.0, 10.0))
    // At 10s, hits end of segment (1->2) before middle zero-delay segment (2->3).
    assertThat(LocationDialog.evaluateRouteAt(roundTripped, 10.seconds, 1)?.point).isEqualTo(GeoPoint(2.0, 2.0, 20.0))
    // At 20s, interpolates halfway through segment (3->4).
    assertThat(LocationDialog.evaluateRouteAt(roundTripped, 20.seconds, 1)?.point).isEqualTo(GeoPoint(4.0, 4.0, 40.0))
    // At end, clamps to destination.
    assertThat(LocationDialog.evaluateRouteAt(roundTripped, 30.seconds, 1)?.point).isEqualTo(GeoPoint(5.0, 5.0, 50.0))
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

  private fun performToolbarAction(ui: FakeUi, actionName: String) {
    val toolbar = ui.getComponent<ActionToolbar>()
    waitForCondition(5.seconds) {
      toolbar.updateActionsAsync()
      toolbar.actions.any { it.templatePresentation.text == actionName }
    }
    val button = ui.getComponent<JComponent> { it.accessibleContext?.accessibleName == actionName }
    ui.clickOn(button)
  }

  private fun clickTableHeader(ui: FakeUi, table: TableView<*>, column: Int) {
    val header = table.tableHeader
    val rect = header.getHeaderRect(column)
    ui.mouse.click(ui.getPosition(header).x + rect.centerX.toInt(), ui.getPosition(header).y + rect.centerY.toInt())
  }

  private fun hasValidationError(ui: FakeUi, substring: String): Boolean {
    return ui.findAllComponents<JComponent>().any { component ->
      component.isShowing && component.accessibleContext?.accessibleName?.contains(substring) == true ||
        (component as? JBLabel)?.text?.contains(substring) == true
    }
  }
}
