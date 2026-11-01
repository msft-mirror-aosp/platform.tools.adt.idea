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

import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbDeviceServices
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.adblib.testing.FakeAdbSessionRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.streaming.emulator.dialogs.CarDataDialog.Companion.DUMP_VHAL_COMMAND
import com.android.tools.idea.streaming.emulator.dialogs.CarSensorDataTab.GearOption
import com.android.tools.idea.streaming.emulator.dialogs.CarSensorDataTab.IgnitionOption
import com.android.tools.idea.streaming.emulator.dialogs.CarSensorDataTab.SpeedUnit
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleIgnitionState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [CarDataDialog]. */
@RunsInEdt
class CarDataDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val adbSessionRule = FakeAdbSessionRule(projectRule)
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      adbSessionRule,
      EdtRule(),
      PortableUiFontRule(),
      HeadlessDialogRule(),
    )

  private val adb: FakeAdbDeviceServices
    get() = AdbLibService.getSession(projectRule.project).deviceServices as FakeAdbDeviceServices

  private fun createAndStartAutomotiveEmulator(customizeAvd: (Path) -> Unit = {}): Pair<FakeEmulator, EmulatorController> {
    val testAvdFolder = FakeEmulator.createAutomotiveAvd(emulatorRule.avdRoot)
    customizeAvd(testAvdFolder)
    val fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    adb.configureShellCommand(DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber), DUMP_VHAL_COMMAND, "")
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  @Test
  fun testCarSensorDataControlsAndLiveUpdates() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val dialog = CarDataDialog(emulatorController).show(projectRule.project)
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val speedSlider = ui.getComponent<JSlider> { it.name == "carSpeedSlider" }
    val speedField = ui.getComponent<JBTextField> { it.name == "carSpeedField" }
    @Suppress("UNCHECKED_CAST") val speedUnitCombo = ui.getComponent<ComboBox<SpeedUnit>> { it.name == "carSpeedUnitComboBox" }
    @Suppress("UNCHECKED_CAST") val gearCombo = ui.getComponent<ComboBox<GearOption>> { it.name == "gearComboBox" }
    @Suppress("UNCHECKED_CAST") val ignitionCombo = ui.getComponent<ComboBox<IgnitionOption>> { it.name == "ignitionComboBox" }
    val parkingBrakeCheckBox = ui.getComponent<JBCheckBox> { it.name == "parkingBrakeCheckBox" }
    val nightModeCheckBox = ui.getComponent<JBCheckBox> { it.name == "nightModeCheckBox" }
    val fuelLevelLowCheckBox = ui.getComponent<JBCheckBox> { it.name == "fuelLevelLowCheckBox" }

    // Initial state loaded from FakeEmulator defaults (Park, Lock, Parking brake = true).
    waitForCondition(5.seconds) { parkingBrakeCheckBox.isSelected }
    val speedLabel = ui.getComponent<JLabel> { it.text == "Car speed:" }
    val speedLabelLeftInRoot = SwingUtilities.convertPoint(speedLabel.parent, speedLabel.x, 0, rootPane).x
    val speedSliderVisualLeftInRoot = SwingUtilities.convertPoint(speedSlider.parent, speedSlider.x + JBUIScale.scale(11), 0, rootPane).x
    assertThat(speedSliderVisualLeftInRoot).isEqualTo(speedLabelLeftInRoot)
    assertThat(speedSlider.paintTicks).isTrue()
    assertThat(speedSlider.paintLabels).isFalse()
    assertThat(speedSlider.minimum).isEqualTo(-30)
    assertThat(speedSlider.maximum).isEqualTo(160)
    assertThat(speedSlider.majorTickSpacing).isEqualTo(30)
    val longestUnitTextWidth = speedUnitCombo.getFontMetrics(speedUnitCombo.font).stringWidth(SpeedUnit.KMH.displayName)
    assertThat(speedUnitCombo.width).isEqualTo(speedUnitCombo.preferredSize.width)
    assertThat(speedUnitCombo.width).isIn((longestUnitTextWidth + JBUIScale.scale(16))..(longestUnitTextWidth + JBUIScale.scale(40)))
    val sliderToFieldVisualGap = speedField.x - (speedSlider.x + speedSlider.width - JBUIScale.scale(11))
    val fieldToUnitGap = speedUnitCombo.x - (speedField.x + speedField.width)
    assertThat(fieldToUnitGap).isIn(0..JBUIScale.scale(12))
    assertThat(sliderToFieldVisualGap).isGreaterThan(fieldToUnitGap)
    val speedUnitRightInRoot = SwingUtilities.convertPoint(speedUnitCombo.parent, speedUnitCombo.x + speedUnitCombo.width, 0, rootPane).x
    val gearRightInRoot = SwingUtilities.convertPoint(gearCombo.parent, gearCombo.x + gearCombo.width, 0, rootPane).x
    val ignitionLeftInRoot = SwingUtilities.convertPoint(ignitionCombo.parent, ignitionCombo.x, 0, rootPane).x
    assertThat(speedUnitRightInRoot).isLessThan(ignitionLeftInRoot)
    assertThat(abs(speedUnitRightInRoot - gearRightInRoot)).isAtMost(JBUIScale.scale(4))
    assertThat(nightModeCheckBox.x - (parkingBrakeCheckBox.x + parkingBrakeCheckBox.width)).isIn(0..JBUIScale.scale(24))
    assertThat(fuelLevelLowCheckBox.x - (nightModeCheckBox.x + nightModeCheckBox.width)).isIn(0..JBUIScale.scale(24))
    assertThat(fuelLevelLowCheckBox.parent.width).isAtLeast(gearCombo.width + ignitionCombo.width)
    assertThat(speedSlider.value).isEqualTo(0)
    assertThat(speedField.text).isEqualTo("0")
    assertThat(speedUnitCombo.selectedItem).isEqualTo(SpeedUnit.MPH)
    assertThat(gearCombo.selectedItem).isEqualTo(GearOption.PARK)
    assertThat(ignitionCombo.selectedItem).isEqualTo(IgnitionOption.LOCK)
    assertThat(nightModeCheckBox.isSelected).isFalse()
    assertThat(fuelLevelLowCheckBox.isSelected).isFalse()

    // Select Drive gear and ignition On.
    gearCombo.selectedItem = GearOption.DRIVE
    ignitionCombo.selectedItem = IgnitionOption.ON
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0) == VehicleGear.GEAR_DRIVE &&
        fakeEmulator.getVehicleProperty(VehicleProperty.IGNITION_STATE)?.getInt32Values(0) == VehicleIgnitionState.ON
    }

    // Adjust speed slider to 60 mph and verify conversion to m/s and unit toggle to km/h.
    speedSlider.value = 60
    assertThat(speedField.text).isEqualTo("60")
    val expectedMps = 60 * 1609.34f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      val actualDisplay = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expectedMps) < 0.01f && abs(actualDisplay - expectedMps) < 0.01f
    }

    speedUnitCombo.selectedItem = SpeedUnit.KMH
    assertThat(speedSlider.minimum).isEqualTo(-50)
    assertThat(speedSlider.maximum).isEqualTo(250)
    assertThat(speedSlider.majorTickSpacing).isEqualTo(50)
    assertThat(speedSlider.value).isEqualTo(97)
    assertThat(speedField.text).isEqualTo("97")

    // Out-of-range speed input restores previous display speed.
    speedField.text = "999"
    speedField.postActionEvent()
    assertThat(speedField.text).isEqualTo("97")

    // Valid speed field input updates slider and emulator.
    speedField.text = "100"
    speedField.postActionEvent()
    assertThat(speedSlider.value).isEqualTo(100)
    val expected100KmhMps = 100 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expected100KmhMps) < 0.01f
    }

    // Selecting Park resets speed to 0.
    gearCombo.selectedItem = GearOption.PARK
    assertThat(speedSlider.value).isEqualTo(0)
    assertThat(speedField.text).isEqualTo("0")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0) == VehicleGear.GEAR_PARK &&
        fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 0f
    }

    // Toggle checkboxes.
    ui.clickOn(nightModeCheckBox)
    ui.clickOn(fuelLevelLowCheckBox)
    ui.clickOn(parkingBrakeCheckBox)
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.NIGHT_MODE)?.getInt32Values(0) == 1 &&
        fakeEmulator.getVehicleProperty(VehicleProperty.FUEL_LEVEL_LOW)?.getInt32Values(0) == 1 &&
        fakeEmulator.getVehicleProperty(VehicleProperty.PARKING_BRAKE_ON)?.getInt32Values(0) == 0
    }

    // Verify live streaming updates originating on the guest device update UI controls.
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder().setProp(VehicleProperty.GEAR_SELECTION).addInt32Values(VehicleGear.GEAR_REVERSE).build()
    )
    fakeEmulator.setVehiclePropertyOnDevice(VehiclePropValue.newBuilder().setProp(VehicleProperty.NIGHT_MODE).addInt32Values(0).build())
    waitForCondition(5.seconds) {
      gearCombo.selectedItem == GearOption.REVERSE && !nightModeCheckBox.isSelected
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testAdbWorkaroundWhenReceiveCarEventsProducesNoEvents() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    fakeEmulator.carEventStreamEnabled = false
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    val adbDump =
      """
      **All properties, 4 properties
      Property:INFO_MAKE(0x11100101), group:SYSTEM(0x10000000), areaType:GLOBAL(0x1000000), valueType:STRING(0x100000),
          access:WRITE(0x2), changeMode:STATIC(0x0), configArray:[], minSampleRateHz:0.000000, maxSampleRateHz:0.000000
              areaId:GLOBAL(0x0), access:WRITE(0x2), f min:0.000000, f max:0.000000, i min:0, i max:0, i64 min:0, i64 max:0
      Property:PERF_VEHICLE_SPEED(0x11600207), group:SYSTEM(0x10000000), areaType:GLOBAL(0x1000000), valueType:FLOAT(0x600000),
          access:READ_WRITE(0x3), changeMode:CONTINUOUS(0x2), configArray:[], minSampleRateHz:1.000000, maxSampleRateHz:100.000000
              areaId:GLOBAL(0x0), access:READ_WRITE(0x3), f min:0.000000, f max:100.000000, i min:0, i max:0, i64 min:0, i64 max:0
      Property:GEAR_SELECTION(0x11400400), group:SYSTEM(0x10000000), areaType:GLOBAL(0x1000000), valueType:INT32(0x400000),
          access:READ_WRITE(0x3), changeMode:ON_CHANGE(0x1), configArray:[4, 2, 8], minSampleRateHz:0.000000, maxSampleRateHz:0.000000
              areaId:GLOBAL(0x0), access:READ_WRITE(0x3), f min:0.000000, f max:0.000000, i min:0, i max:0, i64 min:0, i64 max:0
      Property:PARKING_BRAKE_ON(0x11200402), group:SYSTEM(0x10000000), areaType:GLOBAL(0x1000000), valueType:BOOLEAN(0x200000),
          access:READ_WRITE(0x3), changeMode:ON_CHANGE(0x1), configArray:[], minSampleRateHz:0.000000, maxSampleRateHz:0.000000
              areaId:GLOBAL(0x0), access:READ_WRITE(0x3), f min:0.000000, f max:0.000000, i min:0, i max:0, i64 min:0, i64 max:0
      **All property values, 4 values
      HalPropValue{Property ID: INFO_MAKE(0x11100101), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 123, Status: AVAILABLE(0x0), Value: AOSP Automotive}
      HalPropValue{Property ID: PERF_VEHICLE_SPEED(0x11600207), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 124, Status: AVAILABLE(0x0), Value: 0.0 METER_PER_SEC}
      HalPropValue{Property ID: GEAR_SELECTION(0x11400400), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 125, SystemStatus: AVAILABLE(0x0), VendorStatus: 0, Value: GEAR_DRIVE(0x8)}
      HalPropValue{Property ID: PARKING_BRAKE_ON(0x11200402), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 126, Status: AVAILABLE(0x0), Value: FALSE}
      """
        .trimIndent()
    adb.configureShellCommand(deviceSelector, DUMP_VHAL_COMMAND, adbDump)

    val dialog = CarDataDialog(emulatorController).show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    @Suppress("UNCHECKED_CAST") val gearCombo = ui.getComponent<ComboBox<GearOption>> { it.name == "gearComboBox" }
    val parkingBrakeCheckBox = ui.getComponent<JBCheckBox> { it.name == "parkingBrakeCheckBox" }
    waitForCondition(5.seconds) { gearCombo.selectedItem == GearOption.DRIVE && !parkingBrakeCheckBox.isSelected }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }
}
