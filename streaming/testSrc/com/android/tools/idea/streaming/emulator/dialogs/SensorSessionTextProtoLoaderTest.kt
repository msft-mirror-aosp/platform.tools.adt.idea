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
import com.android.tools.idea.protobuf.TextFormat
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSlider
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for TextProto sensor session loading, dialect handling, large payload skipping, disk spillover, and parse error reporting. */
@RunsInEdt
class SensorSessionTextProtoLoaderTest {

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
  fun testTextProtoImportSeekAndPlayback() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-textproto")
    val textProtoFile = tempDir.resolve("drive_session.textproto")
    val session =
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder()
            .setUnixTimestampMs(1_710_000_000_000L)
            .setName("Morning commute")
            .setDescription("Recorded with camera & car sensors")
            .setVersion(SessionMetadata.Version.newBuilder().setMajor(4).setMinor(2).setPatch(1))
            .addSensors(SessionMetadata.SensorType.SENSOR_TYPE_CAR_PROPERTY)
            .addSensors(SessionMetadata.SensorType.SENSOR_TYPE_SENSOR_EVENT)
            .addSensors(SessionMetadata.SensorType.SENSOR_TYPE_PLATFORM_LOCATION)
            .putCarPropertyConfigs(
              VehicleProperty.PERF_VEHICLE_SPEED,
              SessionMetadata.CarPropertyConfig.newBuilder()
                .setAccess(1)
                .setAreaType(2)
                .setChangeMode(1)
                .addConfigArray(10)
                .setConfigString("speed_cfg")
                .setMinSampleRate(1f)
                .setMaxSampleRate(100f)
                .setType("FLOAT")
                .addSupportedAreas(
                  SessionMetadata.CarPropertyConfig.SupportedArea.newBuilder()
                    .setAreaId(0)
                    .setAreaConfig(
                      SessionMetadata.CarPropertyConfig.SupportedArea.AreaConfig.newBuilder()
                        .setMinInt32Value(-10)
                        .setMaxInt32Value(10)
                        .setMinInt64Value(-100L)
                        .setMaxInt64Value(100L)
                        .setMinFloatValue(0f)
                        .setMaxFloatValue(300f)
                    )
                )
                .build(),
            )
            .putCarPropertyConfigs(VehicleProperty.GEAR_SELECTION, SessionMetadata.CarPropertyConfig.getDefaultInstance())
            .putSubscribedSensors(
              "android.sensor.accelerometer_Accel",
              SessionMetadata.Sensor.newBuilder()
                .setName("Accel")
                .setVendor("AOSP")
                .setVersion(1)
                .setType(1)
                .setMaxRange(39.2f)
                .setResolution(0.01f)
                .setPowerMa(0.5f)
                .setWakeUpSensor(false)
                .build(),
            )
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(1_200_000_000L)
            .putCarPropertyValues(
              VehicleProperty.PERF_VEHICLE_SPEED,
              CarPropertyValue.newBuilder().addFloatValues(31.25f).build(),
            )
            .addLocations(
              PositionProto.newBuilder()
                .setProvider(PositionProvider.GPS)
                .setPoint(PointProto.newBuilder().setLatE7(374_219_999).setLngE7(-1_220_840_575))
                .setAccuracyMm(1500)
                .setAltitudeM(22.5)
                .setVerticalAccuracyMeters(1.2)
                .setSpeedMps(12.0f)
                .setBearingDegFullAccuracy(90.25f)
                .setLevelId("L1")
                .setClusterId("C1")
                .setLevelNumberE3(1000)
            )
            .setImage(ByteString.copyFrom(ByteArray(4096) { (it and 0xFF).toByte() }))
            .putSensorEvents(
              "android.sensor.accelerometer_Accel",
              SensorEvent.newBuilder().addValues(0.4f).addValues(9.8f).addValues(-0.2f).setAccuracy(3).build(),
            )
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(400_000_000L)
            .putCarPropertyValues(
              VehicleProperty.GEAR_SELECTION,
              CarPropertyValue.newBuilder().addInt32Values(VehicleGear.GEAR_DRIVE).build(),
            )
            .putCarPropertyValues(
              VehicleProperty.INFO_VIN,
              CarPropertyValue.newBuilder()
                .setStringValue("VIN-\"TEXT\"-°C")
                .setBytesValue(ByteString.copyFrom(byteArrayOf(0x00, 0x7F, 0xFF.toByte())))
                .addInt64Values(-9876543210L)
                .setAreaId(0)
                .setStatus(0)
                .build(),
            )
        )
        .build()

    textProtoFile.writeText(TextFormat.printer().printToString(session))

    importSensorFile(ui, dialogController, textProtoFile)
    val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }

    val fileNameLabel = ui.getComponent<JBLabel> { it.name == "fileNameValueLabel" }
    val appVersionLabel = ui.getComponent<JBLabel> { it.name == "appVersionValueLabel" }
    val totalEventsLabel = ui.getComponent<JBLabel> { it.name == "totalEventsValueLabel" }
    val timelineTable = ui.getComponent<TableView<TimelineEntry>> { it.name == "timelineTable" }

    assertThat(fileNameLabel.text).isEqualTo("drive_session.textproto")
    assertThat(appVersionLabel.text).isEqualTo("4.2.1")
    assertThat(totalEventsLabel.text).isEqualTo("2")
    assertThat(timelineTable.items)
      .containsExactly(
        TimelineEntry("00:00 - 00:01", 1),
        TimelineEntry("00:01 - 00:02", 1),
      )
      .inOrder()

    // Seek to 50% (600ms out of 1.2s): applies 400ms record (out-of-order second in file) without applying 1200ms record.
    val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
    slider.value = 500
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0) == VehicleGear.GEAR_DRIVE &&
        fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.stringValue == "VIN-\"TEXT\"-°C"
    }
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.getInt64Values(0)).isEqualTo(-9876543210L)
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.bytesValue?.toByteArray())
      .isEqualTo(byteArrayOf(0x00, 0x7F, 0xFF.toByte()))
    assertThat(fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0)).isEqualTo(0f)

    // Play remaining events at 100X and verify GPS, car property, and accelerometer values.
    val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    speedCombo.selectSpeed("100X")
    ui.clickOn(playPauseButton)

    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState && fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 31.25f
    }
    assertThat(abs(fakeEmulator.gpsLocation.latitude - 37.4219999)).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.longitude - (-122.0840575))).isLessThan(1e-6)
    assertThat(abs(fakeEmulator.gpsLocation.altitude - 22.5)).isLessThan(1e-6)
    assertThat(fakeEmulator.getSensorOverride(SensorType.ACCELERATION)).containsExactly(0.4f, 9.8f, -0.2f).inOrder()

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testTextProtoDialectsStreamingSkipAndDiskSpill() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    // Force external k-way merge sort spillover to DiskBackedRecordStore on textproto input.
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
    val tempDir = Files.createTempDirectory("sensor-replay-textproto-dialect")

    // Build a .txtpb using UTF-8 BOM, comments, angle brackets, bracketed repeated lists, hex/octal numbers, signed negative fixed32,
    // adjacent single/double-quoted strings, unknown extension & numeric fields, and a multi-megabyte skipped image literal.
    val largeCameraEscapeChunk = "A".repeat(4096)
    val txtpbFile = tempDir.resolve("dialect_session.txtpb")
    Files.newBufferedWriter(txtpbFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { writer ->
      writer.write("\uFEFF# Recorded textproto session with diverse syntax\n")
      writer.write(
        """
        session_metadata <
          version: { major: 0x2, minor: 010; patch: 3 }
          sensors: [SENSOR_TYPE_CAR_PROPERTY, SENSOR_TYPE_PLATFORM_LOCATION]
          car_property_configs: [
            { key: ${VehicleProperty.PERF_VEHICLE_SPEED}, value < access: 1 > },
            { key: ${VehicleProperty.INFO_VIN} }
          ]
          [custom.ext.metadata_note]: "ignored"
        >
        sensor_records: [
          {
            timestamp_ns: 300000000;
            locations <
              provider: GPS
              point { lat_e7: 374219999, lng_e7: 3074126721 }
              speed_mps: .5f
              altitude_m: 1e1
            >
            car_property_values {
              key: ${VehicleProperty.PERF_VEHICLE_SPEED}
              value { float_values: [42.5] }
            }
            6 {
              1: "unknown_protoc_camera_payload"
            }
            image: "
        """
          .trimIndent()
      )
      repeat(512) { writer.write(largeCameraEscapeChunk) }
      writer.write("\\x89PNG\\u0041\\U0001F680\"\n          },\n")
      for (idx in 1..20) {
        val ts = (21 - idx) * 10_000_000L
        writer.write(
          """
          {
            timestamp_ns: $ts,
            car_property_values <
              key: ${VehicleProperty.INFO_VIN}
              value <
                string_value: 'VIN_' "$idx" "\x21"
                int32_values: [-0x10, 077]
                bool_value: True
              >
            >
          }${if (idx < 20) "," else ""}
          """
            .trimIndent()
        )
        writer.write("\n")
      }
      writer.write("]\n")
    }

    importSensorFile(ui, dialogController, txtpbFile)
    val savedTable = ui.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }
    waitForCondition(10.seconds) { savedTable.rowCount == 1 }

    val loadedItem = savedTable.items.single()
    val backingTempFile = loadedItem.tempFileForTesting
    assertThat(backingTempFile).isNotNull()
    assertThat(Files.exists(backingTempFile!!)).isTrue()
    assertThat(loadedItem.appVersion).isEqualTo("2.8.3")
    assertThat(loadedItem.totalEvents).isEqualTo(21L)

    // Verify playback across disk-backed textproto records.
    val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
    speedCombo.selectSpeed("100X")
    ui.clickOn(playPauseButton)

    waitForCondition(5.seconds) {
      !playPauseButton.isPlayingState &&
        fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 42.5f &&
        fakeEmulator.getVehicleProperty(VehicleProperty.INFO_VIN)?.stringValue == "VIN_1!"
    }
    assertThat(abs(fakeEmulator.gpsLocation.longitude - (-122.0840575))).isLessThan(1e-6)

    // Also verify extensionless textproto files and .pbtxt files are detected and imported cleanly.
    val extensionlessFile = tempDir.resolve("extensionless_textproto_session")
    extensionlessFile.writeText(
      """
      # Extensionless textproto
      session_metadata { version { major: 1 minor: 0 patch: 0 } }
      sensor_records { timestamp_ns: 50000000 }
      """
        .trimIndent()
    )
    importSensorFile(ui, dialogController, extensionlessFile)
    waitForCondition(5.seconds) { savedTable.rowCount == 2 }

    // Removing the disk-backed textproto session deletes its temporary backing file.
    val toolbar = ui.getComponent<ActionToolbar>()
    savedTable.selectionModel.setSelectionInterval(0, 0)
    waitForCondition(5.seconds) {
      toolbar.updateActionsAsync()
      toolbar.actions.any { it.templatePresentation.text == "Remove" }
    }
    val removeButton = ui.getComponent<JComponent> { it.accessibleContext?.accessibleName == "Remove" }
    ui.clickOn(removeButton)
    waitForCondition(5.seconds) { savedTable.rowCount == 1 && !Files.exists(backingTempFile) }

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testInvalidTextProtoFilesShowParseError() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)
    val tempDir = Files.createTempDirectory("sensor-replay-invalid-textproto")

    // 1. 0-byte .textproto file -> Empty file error.
    val emptyTextProto = tempDir.resolve("empty.textproto")
    emptyTextProto.writeBytes(ByteArray(0))
    importSensorFile(ui, dialogController, emptyTextProto)
    waitForCondition(5.seconds) { hasValidationError(ui, "Sensor file 'empty.textproto' is empty") }

    // 2. HTML page saved with .textproto extension -> Invalid file error.
    val htmlTextProto = tempDir.resolve("gothenburg_hamburg_dummy_detection.textproto")
    htmlTextProto.writeText("<!DOCTYPE html><html lang=\"en\"><head><title>Sign in</title></head><body></body></html>")
    importSensorFile(ui, dialogController, htmlTextProto)
    waitForCondition(5.seconds) {
      hasValidationError(ui, "Unable to parse sensor file 'gothenburg_hamburg_dummy_detection.textproto'")
    }

    // 3. Wrong-schema .textproto (valid textproto syntax for a different proto message without session_metadata or sensor_records).
    val wrongSchemaFile = tempDir.resolve("wrong_schema.textproto")
    wrongSchemaFile.writeText("packets { id: 1 payload: \"abc\" }\n")
    importSensorFile(ui, dialogController, wrongSchemaFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'wrong_schema.textproto'") }

    // 4. Comment-only .textproto with neither metadata nor records.
    val commentOnlyFile = tempDir.resolve("comments_only.textproto")
    commentOnlyFile.writeText("# Just a comment\n   \n")
    importSensorFile(ui, dialogController, commentOnlyFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'comments_only.textproto'") }

    // 5. Truncated .textproto missing closing brace.
    val truncatedTextProto = tempDir.resolve("truncated.textproto")
    truncatedTextProto.writeText("sensor_records { timestamp_ns: 100000")
    importSensorFile(ui, dialogController, truncatedTextProto)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'truncated.textproto'") }

    // 6. Invalid scalar type and invalid escape sequence in skipped string.
    val badEscapeFile = tempDir.resolve("bad_escape.pbtxt")
    badEscapeFile.writeText("sensor_records { timestamp_ns: 100 image: \"\\xZZ\" }")
    importSensorFile(ui, dialogController, badEscapeFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'bad_escape.pbtxt'") }

    // 7. Deeply nested unknown messages (>64 levels) fail cleanly without StackOverflowError.
    val deepTextProto = tempDir.resolve("deep_nested.textproto")
    deepTextProto.writeText("u {".repeat(100) + "a: 1" + "}".repeat(100))
    importSensorFile(ui, dialogController, deepTextProto)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'deep_nested.textproto'") }

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }
}
