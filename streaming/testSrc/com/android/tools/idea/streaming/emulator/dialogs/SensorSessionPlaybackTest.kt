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

import com.android.emulation.control.incubating.VehiclePropStatus
import com.android.emulator.control.SensorSession
import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SensorRecord.CarPropertyValue
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto.PointProto
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto.PositionProvider
import com.android.emulator.control.SensorSession.SensorRecord.SensorEvent
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.emulator.control.SensorValue.SensorType
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.protobuf.CodedOutputStream
import com.android.tools.idea.protobuf.WireFormat
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSlider
import kotlin.io.path.writeBytes
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for sensor session playback, seeking, scrubbing, record coalescing, and disk-backed session replay. */
@RunsInEdt
class SensorSessionPlaybackTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      emulatorRule,
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      EdtRule(),
      PortableUiFontRule(),
      HeadlessDialogRule(),
    )

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().setList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY, null)
  }

  @After
  fun tearDown() {
    PropertiesComponent.getInstance().setList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY, null)
  }

  @Test
  fun testSeekPlayPauseAndResume() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-playback")
    val sessionFile = tempDir.resolve("drive_replay.pb")

    // Intentionally add records out-of-order to verify timestamp sorting on load.
    val session =
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder()
            .putSubscribedSensors(
              "android.sensor.accelerometer_Accel",
              SessionMetadata.Sensor.newBuilder().setName("Accel").setType(1).setWakeUpSensor(false).build(),
            )
            .putSubscribedSensors(
              "android.sensor.gyroscope_uncalibrated_GyroUncalib",
              SessionMetadata.Sensor.newBuilder().setName("GyroUncalib").setType(16).setWakeUpSensor(false).build(),
            )
            .putSubscribedSensors(
              "android.sensor.pressure_WakeUpBarometer",
              SessionMetadata.Sensor.newBuilder().setName("WakeUpBarometer").setType(6).setWakeUpSensor(true).build(),
            )
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(200_000_000L)
            .putCarPropertyValues(
              VehicleProperty.PERF_VEHICLE_SPEED,
              CarPropertyValue.newBuilder().addFloatValues(25.5f).build(),
            )
            .putCarPropertyValues(
              VehicleProperty.NIGHT_MODE,
              CarPropertyValue.newBuilder().setBoolValue(true).setStatus(VehiclePropStatus.ERROR_VALUE).build(),
            )
            .addLocations(
              PositionProto.newBuilder()
                .setProvider(PositionProvider.WIFI)
                .setPoint(PointProto.newBuilder().setLatE7(100_000_000).setLngE7(200_000_000))
            )
            .addLocations(
              PositionProto.newBuilder()
                .setProvider(PositionProvider.GPS)
                // 37.4219999, -122.0840575 encoded as signed e7 in fixed32
                .setPoint(PointProto.newBuilder().setLatE7(374_219_999).setLngE7(-1_220_840_575))
                .setAltitudeM(15.2)
                .setSpeedMps(10.0f)
                .setBearingDegFullAccuracy(180.5f)
            )
            .putSensorEvents(
              "android.sensor.accelerometer_Accel",
              SensorEvent.newBuilder().addValues(0.1f).addValues(9.7f).addValues(-0.3f).build(),
            )
            .putSensorEvents(
              "android.sensor.gyroscope_uncalibrated_GyroUncalib",
              SensorEvent.newBuilder()
                .addValues(0.01f)
                .addValues(0.02f)
                .addValues(0.03f)
                .addValues(0.001f)
                .addValues(0.002f)
                .addValues(0.003f)
                .build(),
            )
            .putSensorEvents(
              "android.sensor.pressure_WakeUpBarometer",
              SensorEvent.newBuilder().addValues(1013.25f).build(),
            )
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(50_000_000L)
            .putCarPropertyValues(
              VehicleProperty.GEAR_SELECTION,
              CarPropertyValue.newBuilder().addInt32Values(VehicleGear.GEAR_DRIVE).build(),
            )
            .putCarPropertyValues(
              VehicleProperty.INFO_VIN,
              CarPropertyValue.newBuilder()
                .setStringValue("TESTVIN12345")
                .setBytesValue(ByteString.copyFrom(byteArrayOf(0x01, 0x02)))
                .addInt64Values(9876543210L)
                .setAreaId(0)
                .setStatus(VehiclePropStatus.UNAVAILABLE_VALUE)
                .build(),
            )
        )
        .build()
    sessionFile.writeBytes(session.toByteArray())

    importSensorFile(ui, dialogController, sessionFile)
    val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }

    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    assertThat(slider).isInstanceOf(ProgressSlider::class.java)
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }

    // Verify icon-only square Play button presentation, tooltip, and accessible name.
    assertThat(playPauseButton.text).isEmpty()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.toolTipText).isEqualTo("Play")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")
    assertThat(playPauseButton.preferredSize.width).isEqualTo(playPauseButton.preferredSize.height)

    // Test slider seek to 50% (100ms): applies first record (50ms) but not second record (200ms).
    slider.value = 500
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0) == VehicleGear.GEAR_DRIVE &&
        fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.stringValue == "TESTVIN12345"
    }
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.status).isEqualTo(VehiclePropStatus.AVAILABLE)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.status).isEqualTo(VehiclePropStatus.UNAVAILABLE)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.getInt64Values(0)).isEqualTo(9876543210L)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.bytesValue?.toByteArray()).isEqualTo(byteArrayOf(0x01, 0x02))
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0)).isEqualTo(0f)

    // Select 10X playback speed and click Play from 50% -> completes remaining records and resets to 0.
    speedCombo.selectSpeed("10X")
    ui.clickOn(playPauseButton)

    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState &&
        fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 25.5f &&
        fakeEmulator.getVehicleProperty(VehicleProperty.NIGHT_MODE)?.getInt32Values(0) == 1
    }
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.status).isEqualTo(VehiclePropStatus.AVAILABLE)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.NIGHT_MODE)?.status).isEqualTo(VehiclePropStatus.ERROR)

    // Verify GPS state (latitude, negative longitude, altitude, knots speed, bearing, satellites).
    assertThat(abs(fakeEmulator.gpsLocation.latitude - 37.4219999)).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.longitude - (-122.0840575))).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.altitude - 15.2)).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.speed - 19.4384)).isLessThan(1e-3)
    assertThat(abs(fakeEmulator.gpsLocation.bearing - 180.5)).isLessThan(1e-3)
    assertThat(fakeEmulator.gpsLocation.satellites).isEqualTo(4)

    // Verify both non-wake-up and wake-up sensors were overridden (and uncalibrated gyro trimmed to 3 values).
    assertThat(fakeEmulator.getSensorOverride(SensorType.ACCELERATION)).containsExactly(0.1f, 9.7f, -0.3f).inOrder()
    assertThat(fakeEmulator.getSensorOverride(SensorType.GYROSCOPE_UNCALIBRATED)).containsExactly(0.01f, 0.02f, 0.03f).inOrder()
    assertThat(fakeEmulator.getSensorOverride(SensorType.PRESSURE)).containsExactly(1013.25f)

    // Slider & button reset after playback finishes.
    assertThat(slider.value).isEqualTo(0)
    assertThat(playPauseButton.text).isEmpty()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.toolTipText).isEqualTo("Play")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")

    // Test Play -> Pause -> Resume during a 2s session: Pause preserves position without resetting, and Play resumes from pause point.
    val pauseResumeSessionFile = tempDir.resolve("pause_resume_session.pb")
    pauseResumeSessionFile.writeBytes(
      SensorSession.newBuilder()
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(50_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(12f).build())
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(2_000_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(88f).build())
        )
        .build()
        .toByteArray()
    )
    importSensorFile(ui, dialogController, pauseResumeSessionFile)
    waitForCondition(5.seconds) { savedTable.rowCount == 2 }

    speedCombo.selectSpeed("1X")
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.isPlayingState).isTrue()
    assertThat(playPauseButton.text).isEmpty()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(playPauseButton.toolTipText).isEqualTo("Pause")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Pause")
    assertThat(slider.isEnabled).isTrue()
    assertThat(speedCombo.isEnabled).isTrue()

    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 12f && slider.value > 25
    }

    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.isPlayingState).isFalse()
    assertThat(playPauseButton.text).isEmpty()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.toolTipText).isEqualTo("Play")
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")
    assertThat(slider.isEnabled).isTrue()
    assertThat(speedCombo.isEnabled).isTrue()
    assertThat(slider.value).isIn(26..999)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0)).isEqualTo(12f)

    // Resume playback at 100X and verify remaining record is played and slider resets to 0 on completion.
    speedCombo.selectSpeed("100X")
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 88f
    }
    assertThat(slider.value).isEqualTo(0)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)

    // Verify Play -> Pause -> Play does not re-dispatch already-played records on resume.
    val dispatchedTimestamps = CopyOnWriteArrayList<Long>()
    dialogController.recordSenderOverride = { record, _ -> dispatchedTimestamps.add(record.timestampNs) }
    speedCombo.selectSpeed("1X")
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) { dispatchedTimestamps.contains(50_000_000L) && slider.value > 25 }
    ui.clickOn(playPauseButton)
    assertThat(dispatchedTimestamps).containsExactly(50_000_000L)

    speedCombo.selectSpeed("100X")
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) { !playPauseButton.isPlayingState }
    assertThat(dispatchedTimestamps).containsExactly(50_000_000L, 2_000_000_000L).inOrder()

    // Test changing playback speed live while playing (0.1X -> 100X and 10X -> 0.1X).
    dialogController.recordSenderOverride = null
    val liveSpeedSessionFile = tempDir.resolve("live_speed_session.pb")
    liveSpeedSessionFile.writeBytes(
      SensorSession.newBuilder()
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(0L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(10f).build())
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(2_000_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(55f).build())
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(10_000_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(99f).build())
        )
        .build()
        .toByteArray()
    )
    importSensorFile(ui, dialogController, liveSpeedSessionFile)
    waitForCondition(5.seconds) { savedTable.rowCount == 3 }

    // Start at 0.1X (10s session would take 100s wall time), then switch to 100X while playing so it finishes within ~100ms.
    speedCombo.selectSpeed("0.1X")
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.isPlayingState).isTrue()
    assertThat(speedCombo.isEnabled).isTrue()
    waitForCondition(5.seconds) { fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 10f }

    speedCombo.selectSpeed("100X")
    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 99f
    }
    assertThat(slider.value).isEqualTo(0)

    // Start at 10X (2s record arrives in ~200ms), then slow down to 0.1X while playing and verify position does not jump backward.
    speedCombo.selectSpeed("10X")
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 55f && slider.value >= 200
    }
    val sliderBeforeSlowdown = slider.value
    speedCombo.selectSpeed("0.1X")
    assertThat(playPauseButton.isPlayingState).isTrue()
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.isPlayingState).isFalse()
    assertThat(slider.value).isIn(sliderBeforeSlowdown..999)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0)).isEqualTo(55f)

    // Test seeking on the slider while playback is in progress (at 0.1X, seek past 2s to 99% so remaining 0.1s finishes in ~1s).
    slider.value = 0
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 10f
    }
    speedCombo.selectSpeed("0.1X")
    ui.clickOn(playPauseButton)
    assertThat(playPauseButton.isPlayingState).isTrue()
    assertThat(slider.isEnabled).isTrue()

    // Simulate dragging thumb while playing (valueIsAdjusting = true -> false) to 995 (9.95s out of 10s).
    slider.valueIsAdjusting = true
    slider.value = 995
    assertThat(playPauseButton.isPlayingState).isTrue()
    slider.valueIsAdjusting = false

    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 99f
    }
    assertThat(slider.value).isEqualTo(0)

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testHighSpeedPlaybackCoalescesDenseRecords() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-100x")
    val denseFile = tempDir.resolve("dense_20s_100hz.pb")
    val sessionBuilder =
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder()
            .putSubscribedSensors(
              "android.sensor.accelerometer_Accel",
              SessionMetadata.Sensor.newBuilder().setName("Accel").setType(1).setWakeUpSensor(false).build(),
            )
        )

    // 2,000 records over 20 seconds (100 Hz = every 10ms). Without coalescing, 3ms/RPC would take >6 seconds instead of ~200ms at 100X.
    for (i in 1..2000) {
      val recordBuilder =
        SensorRecord.newBuilder()
          .setTimestampNs(i * 10_000_000L)
          .putCarPropertyValues(
            VehicleProperty.PERF_VEHICLE_SPEED,
            CarPropertyValue.newBuilder().addFloatValues(i * 0.05f).build(),
          )
          .putCarPropertyValues(
            VehicleProperty.GEAR_SELECTION,
            CarPropertyValue.newBuilder().addInt32Values(VehicleGear.GEAR_DRIVE).build(),
          )
          .putSensorEvents(
            "android.sensor.accelerometer_Accel",
            SensorEvent.newBuilder().addValues(0.2f).addValues(9.8f).addValues(i * 0.001f).build(),
          )
      if (i == 1999) {
        recordBuilder.addLocations(
          PositionProto.newBuilder()
            .setProvider(PositionProvider.GPS)
            .setPoint(PointProto.newBuilder().setLatE7(374_219_999).setLngE7(-1_220_840_575))
            .setAltitudeM(30.0)
        )
      } else if (i == 2000) {
        // Non-GPS location on the final record must not erase the coalesced GPS fix from record 1999.
        recordBuilder.addLocations(
          PositionProto.newBuilder()
            .setProvider(PositionProvider.WIFI)
            .setPoint(PointProto.newBuilder().setLatE7(100_000_000).setLngE7(200_000_000))
        )
      }
      sessionBuilder.addSensorRecords(recordBuilder)
    }
    denseFile.writeBytes(sessionBuilder.build().toByteArray())

    importSensorFile(ui, dialogController, denseFile)
    val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }

    val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    speedCombo.selectSpeed("100X")

    val dispatchedBatches = CopyOnWriteArrayList<SensorRecord>()
    dialogController.recordSenderOverride = { record, _ ->
      delay(3.milliseconds)
      dispatchedBatches.add(record)
    }

    val startMark = TimeSource.Monotonic.markNow()
    ui.clickOn(playPauseButton)
    waitForCondition(2.seconds) { !playPauseButton.isPlayingState }

    assertThat(startMark.elapsedNow()).isLessThan(1500.milliseconds)
    assertThat(dispatchedBatches.size).isLessThan(200)
    val finalBatch = dispatchedBatches.last()
    assertThat(finalBatch.timestampNs).isEqualTo(20_000_000_000L)
    assertThat(finalBatch.carPropertyValuesMap[VehicleProperty.PERF_VEHICLE_SPEED]?.getFloatValues(0)).isEqualTo(100f)
    assertThat(finalBatch.locationsList.any { it.provider == PositionProvider.GPS }).isTrue()

    // Also verify end-to-end 100X playback against FakeEmulator over real gRPC calls.
    dialogController.recordSenderOverride = null
    ui.clickOn(playPauseButton)
    waitForCondition(2.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 100f
    }
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0)).isEqualTo(VehicleGear.GEAR_DRIVE)
    assertThat(abs(fakeEmulator.gpsLocation.latitude - 37.4219999)).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.altitude - 30.0)).isLessThan(1e-6)
    assertThat(fakeEmulator.getSensorOverride(SensorType.ACCELERATION)).containsExactly(0.2f, 9.8f, 2.0f).inOrder()

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testMultiGigabyteSessionWithCameraPayloadsAndPackedFields() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-huge")
    val hugeFile = tempDir.resolve("camera_heavy_2_4gb.pb")
    try {
      Files.newByteChannel(hugeFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val metadata =
          SessionMetadata.newBuilder()
            .setVersion(SessionMetadata.Version.newBuilder().setMajor(3).setMinor(0).setPatch(1))
            .putCarPropertyConfigs(VehicleProperty.PERF_VEHICLE_SPEED, SessionMetadata.CarPropertyConfig.getDefaultInstance())
            .build()
        writeTopLevelMessage(channel, fieldNumber = 1, messageBytes = metadata.toByteArray())

        // Write 5 records with 400 MB unknown camera payloads (tag 6) + 1 record with 400 MB image payload (tag 4) = 2.4 GB (> 2 GB).
        val sparsePayloadSize = 400_000_000
        for (i in 0 until 5) {
          val record =
            SensorRecord.newBuilder()
              .setTimestampNs((i + 1) * 100_000_000L)
              .putCarPropertyValues(
                VehicleProperty.PERF_VEHICLE_SPEED,
                CarPropertyValue.newBuilder().addFloatValues(10f + i).build(),
              )
              .build()
          appendRecordWithSparseField(channel, record, fieldNumber = 6, payloadSize = sparsePayloadSize)
        }

        // Include packed sint32, sint64, and float fields in the final record to exercise packed wire decoding.
        val packedFinalRecordBytes = buildRecordWithPackedCarProperty(timestampNs = 600_000_000L, speed = 42.5f)
        appendRawRecordWithSparseField(channel, packedFinalRecordBytes, fieldNumber = 4, payloadSize = sparsePayloadSize)
      }

      assertThat(Files.size(hugeFile)).isGreaterThan(Int.MAX_VALUE.toLong())

      importSensorFile(ui, dialogController, hugeFile)
      val savedTable = ui.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }
      waitForCondition(5.seconds) { savedTable.rowCount == 1 }

      assertThat(ui.getComponent<JBLabel> { it.name == "appVersionValueLabel" }.text).isEqualTo("3.0.1")
      assertThat(ui.getComponent<JBLabel> { it.name == "totalEventsValueLabel" }.text).isEqualTo("6")
      assertThat(savedTable.items.single().tempFileForTesting).isNull()

      val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
      slider.value = 1000
      waitForCondition(5.seconds) {
        fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 42.5f &&
          fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.getInt32Values(0) == -42 &&
          fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.getInt64Values(0) == -9876543210L
      }
    } finally {
      Files.deleteIfExists(hugeFile)
      dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    }
  }

  @Test
  fun testDiskBackedExternalSortCheckpointsAndTempFileCleanup() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    // Force external k-way merge sort with small in-memory chunk size, small checkpoint interval, and multi-pass fan-in of 2.
    dialogController.sessionLoader = { path, checkCancelled ->
      loadSensorSession(
        file = path,
        maxInMemoryBytes = 256,
        checkpointIntervalBytes = 96,
        maxMergeFanIn = 2,
        checkCancelled = checkCancelled,
      )
    }
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-disk")
    val sessionFile = tempDir.resolve("spilled_out_of_order.pb")

    // Add out-of-order records including negative timestamps and duplicate timestamps (to verify stable tie-breaking across merge passes).
    val sessionBuilder =
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder().setVersion(SessionMetadata.Version.newBuilder().setMajor(1).setMinor(2).setPatch(3))
        )
    val timestamps = listOf(500_000_000L, -200_000_000L, 300_000_000L, 100_000_000L, 300_000_000L, 400_000_000L, 200_000_000L, 0L)
    for ((idx, ts) in timestamps.withIndex()) {
      val recordBuilder =
        SensorRecord.newBuilder()
          .setTimestampNs(ts)
          .putCarPropertyValues(
            VehicleProperty.PERF_VEHICLE_SPEED,
            CarPropertyValue.newBuilder().addFloatValues((idx + 1) * 10f).build(),
          )
          .putCarPropertyValues(
            VehicleProperty.INFO_VIN,
            CarPropertyValue.newBuilder().setStringValue("REC_$idx").build(),
          )
      if (ts == 0L) {
        recordBuilder.addLocations(
          PositionProto.newBuilder()
            .setProvider(PositionProvider.GPS_INJECTED)
            .setPoint(PointProto.newBuilder().setLatE7(374_219_999).setLngE7(-1_220_840_575))
        )
      } else if (ts == 100_000_000L) {
        // Non-GPS location before a checkpoint must not overwrite the earlier GPS fix in SeekCheckpoint.aggregatedBefore.
        recordBuilder.addLocations(
          PositionProto.newBuilder()
            .setProvider(PositionProvider.WIFI)
            .setPoint(PointProto.newBuilder().setLatE7(100_000_000).setLngE7(200_000_000))
        )
      }
      sessionBuilder.addSensorRecords(recordBuilder)
    }
    sessionFile.writeBytes(sessionBuilder.build().toByteArray())

    importSensorFile(ui, dialogController, sessionFile)
    val savedTable = ui.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }

    val importedItem = savedTable.items.single()
    val backingTempFile = importedItem.tempFileForTesting
    assertThat(backingTempFile).isNotNull()
    assertThat(Files.exists(backingTempFile!!)).isTrue()
    assertThat(importedItem.totalEvents).isEqualTo(8L)
    assertThat(importedItem.duration).isEqualTo(500.milliseconds)

    // Duplicate timestamp 300ms appeared at idx=2 (30f, REC_2) and idx=4 (50f, REC_4); stable sort must keep idx=4 after idx=2.
    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    slider.value = 600 // 60% of 500ms = 300ms
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.stringValue == "REC_4" &&
        fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 50f &&
        abs(fakeEmulator.gpsLocation.latitude - 37.4219999) < 1e-6
    }

    // Play remaining records from 300ms to 500ms (idx=5 at 400ms, idx=0 at 500ms -> REC_0, 10f).
    val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    speedCombo.selectSpeed("100X")
    ui.clickOn(playPauseButton)
    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.stringValue == "REC_0"
    }

    // Removing the session deletes its disk-backed temporary file.
    val toolbar = ui.getComponent<ActionToolbar>()
    savedTable.selectionModel.setSelectionInterval(0, 0)
    waitForCondition(5.seconds) {
      toolbar.updateActionsAsync()
      toolbar.actions.any { it.templatePresentation.text == "Remove" }
    }
    val removeButton = ui.getComponent<JComponent> { it.accessibleContext?.accessibleName == "Remove" }
    ui.clickOn(removeButton)
    waitForCondition(5.seconds) { savedTable.rowCount == 0 && !Files.exists(backingTempFile) }

    // Import a second disk-backed file and verify EmulatorController disposal also cleans up the temporary file.
    val sessionFile2 = tempDir.resolve("spilled_dispose_cleanup.pb")
    sessionFile2.writeBytes(sessionBuilder.build().toByteArray())
    importSensorFile(ui, dialogController, sessionFile2)
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }
    val disposeTempFile = savedTable.items.single().tempFileForTesting!!
    assertThat(Files.exists(disposeTempFile)).isTrue()

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    Disposer.dispose(emulatorController)
    assertThat(Files.exists(disposeTempFile)).isFalse()
  }

  private fun writeTopLevelMessage(channel: SeekableByteChannel, fieldNumber: Int, messageBytes: ByteArray) {
    val header = ByteArrayOutputStream()
    val coded = CodedOutputStream.newInstance(header)
    coded.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    coded.writeUInt32NoTag(messageBytes.size)
    coded.flush()
    writeFully(channel, header.toByteArray())
    writeFully(channel, messageBytes)
  }

  private fun appendRecordWithSparseField(channel: SeekableByteChannel, record: SensorRecord, fieldNumber: Int, payloadSize: Int) {
    appendRawRecordWithSparseField(channel, record.toByteArray(), fieldNumber, payloadSize)
  }

  private fun appendRawRecordWithSparseField(channel: SeekableByteChannel, recordBytes: ByteArray, fieldNumber: Int, payloadSize: Int) {
    val fieldHeader = ByteArrayOutputStream()
    val fieldCoded = CodedOutputStream.newInstance(fieldHeader)
    fieldCoded.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    fieldCoded.writeUInt32NoTag(payloadSize)
    fieldCoded.flush()
    val fieldHeaderBytes = fieldHeader.toByteArray()

    val outerHeader = ByteArrayOutputStream()
    val outerCoded = CodedOutputStream.newInstance(outerHeader)
    outerCoded.writeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    outerCoded.writeUInt32NoTag(recordBytes.size + fieldHeaderBytes.size + payloadSize)
    outerCoded.flush()

    writeFully(channel, outerHeader.toByteArray())
    writeFully(channel, recordBytes)
    writeFully(channel, fieldHeaderBytes)
    channel.position(channel.position() + payloadSize - 1)
    writeFully(channel, byteArrayOf(0))
  }

  private fun buildRecordWithPackedCarProperty(timestampNs: Long, speed: Float): ByteArray {
    // Manually encode packed sint32_values (tag 4), sint64_values (tag 5), and float_values (tag 6) inside CarPropertyValue.
    val packedInt32 =
      ByteArrayOutputStream()
        .also {
          val out = CodedOutputStream.newInstance(it)
          out.writeSInt32NoTag(-42)
          out.writeSInt32NoTag(7)
          out.flush()
        }
        .toByteArray()
    val packedInt64 =
      ByteArrayOutputStream()
        .also {
          val out = CodedOutputStream.newInstance(it)
          out.writeSInt64NoTag(-9876543210L)
          out.flush()
        }
        .toByteArray()
    val packedFloat =
      ByteArrayOutputStream()
        .also {
          val out = CodedOutputStream.newInstance(it)
          out.writeFloatNoTag(speed)
          out.flush()
        }
        .toByteArray()

    val vinCarProp =
      ByteArrayOutputStream()
        .also {
          val out = CodedOutputStream.newInstance(it)
          out.writeByteArray(4, packedInt32)
          out.writeByteArray(5, packedInt64)
          out.flush()
        }
        .toByteArray()
    val speedCarProp =
      ByteArrayOutputStream()
        .also {
          val out = CodedOutputStream.newInstance(it)
          out.writeByteArray(6, packedFloat)
          out.flush()
        }
        .toByteArray()

    val vinMapEntry = encodeCarPropertyMapEntry(VehicleProperty.INFO_VIN, vinCarProp)
    val speedMapEntry = encodeCarPropertyMapEntry(VehicleProperty.PERF_VEHICLE_SPEED, speedCarProp)

    return ByteArrayOutputStream()
      .also {
        val out = CodedOutputStream.newInstance(it)
        out.writeInt64(1, timestampNs)
        out.writeByteArray(3, vinMapEntry)
        out.writeByteArray(3, speedMapEntry)
        out.flush()
      }
      .toByteArray()
  }

  private fun encodeCarPropertyMapEntry(propId: Int, valueBytes: ByteArray): ByteArray {
    return ByteArrayOutputStream()
      .also {
        val out = CodedOutputStream.newInstance(it)
        out.writeInt32(1, propId)
        out.writeByteArray(2, valueBytes)
        out.flush()
      }
      .toByteArray()
  }

  private fun writeFully(channel: SeekableByteChannel, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
      channel.write(buffer)
    }
  }
}
