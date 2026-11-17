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
import com.android.emulation.control.incubating.VehiclePropStatus
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
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertiesTab.EnumChoice
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertiesTab.VhalPropertyEntry
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.FuelType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateReq
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateShutdownParam
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaDoor
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaSeat
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleIgnitionState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleOilLevel
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.CheckBoxList
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_KP_DOWN
import java.awt.event.KeyEvent.VK_KP_UP
import java.awt.event.KeyEvent.VK_UP
import java.nio.file.Files
import java.nio.file.Path
import java.text.Collator
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().unsetValue(VhalPropertiesTab.SORT_ORDER_STORAGE_KEY)
  }

  @After
  fun tearDown() {
    PropertiesComponent.getInstance().unsetValue(VhalPropertiesTab.SORT_ORDER_STORAGE_KEY)
  }

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
    fakeEmulator.pauseGrpc()
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

    assertThat(speedSlider.isEnabled).isFalse()
    assertThat(speedField.isEnabled).isFalse()
    assertThat(speedField.text).isEmpty()
    assertThat(speedUnitCombo.isEnabled).isFalse()
    assertThat(gearCombo.isEnabled).isFalse()
    assertThat(ignitionCombo.isEnabled).isFalse()
    assertThat(parkingBrakeCheckBox.isEnabled).isFalse()
    assertThat(nightModeCheckBox.isEnabled).isFalse()
    assertThat(fuelLevelLowCheckBox.isEnabled).isFalse()

    // Programmatic changes while controls are uninitialized must not send premature VHAL writes or block initial values.
    speedSlider.value = 50
    gearCombo.selectedItem = GearOption.DRIVE
    ignitionCombo.selectedItem = IgnitionOption.ON

    fakeEmulator.resumeGrpc()
    // Initial state loaded from FakeEmulator defaults (Park, Lock, Parking brake = true).
    waitForCondition(5.seconds) {
      speedSlider.isEnabled &&
        gearCombo.isEnabled &&
        ignitionCombo.isEnabled &&
        parkingBrakeCheckBox.isEnabled &&
        nightModeCheckBox.isEnabled &&
        fuelLevelLowCheckBox.isEnabled &&
        parkingBrakeCheckBox.isSelected
    }
    assertThat(speedField.isEnabled).isTrue()
    assertThat(speedUnitCombo.isEnabled).isTrue()
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

    // Up/Down keys increment/decrement within current unit bounds (-50..250 km/h) and sync slider and emulator.
    ui.keyboard.setFocus(speedField)
    ui.keyboard.pressAndRelease(VK_UP)
    ui.keyboard.pressAndRelease(VK_KP_UP)
    assertThat(speedField.text).isEqualTo("102")
    assertThat(speedSlider.value).isEqualTo(102)
    val expected102KmhMps = 102 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expected102KmhMps) < 0.01f
    }

    ui.keyboard.pressAndRelease(VK_DOWN)
    ui.keyboard.pressAndRelease(VK_KP_DOWN)
    assertThat(speedField.text).isEqualTo("100")
    assertThat(speedSlider.value).isEqualTo(100)
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expected100KmhMps) < 0.01f
    }

    // Out-of-range and extreme Int values clamp to unit bounds before stepping (no overflow/underflow).
    speedField.text = "999"
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(speedField.text).isEqualTo("249")
    assertThat(speedSlider.value).isEqualTo(249)
    val expected249KmhMps = 249 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expected249KmhMps) < 0.01f
    }

    speedField.text = "-100"
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(speedField.text).isEqualTo("-49")
    assertThat(speedSlider.value).isEqualTo(-49)
    val expectedMinus49KmhMps = -49 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: 1f
      abs(actualSpeed - expectedMinus49KmhMps) < 0.01f
    }

    speedField.text = Int.MAX_VALUE.toString()
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(speedField.text).isEqualTo("250")
    assertThat(speedSlider.value).isEqualTo(250)
    val expected250KmhMps = 250 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: -1f
      abs(actualSpeed - expected250KmhMps) < 0.01f
    }

    speedField.text = Int.MIN_VALUE.toString()
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(speedField.text).isEqualTo("-50")
    assertThat(speedSlider.value).isEqualTo(-50)
    val expectedMinus50KmhMps = -50 * 1000f / 3600f
    waitForCondition(5.seconds) {
      val actualSpeed = fakeEmulator.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) ?: 1f
      abs(actualSpeed - expectedMinus50KmhMps) < 0.01f
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

    // Verify live-streaming updates originating on the guest device update UI controls.
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
  fun testVhalPropertiesTableFilteringAndEditing() {
    val (fakeEmulator, emulatorController) =
      createAndStartAutomotiveEmulator { avdFolder ->
        // Write a *types-meta.json metadata file to verify description & custom vendor property loading.
        val metaJson =
          """
          [
            {
              "name": "VehicleProperty",
              "values": [
                {
                  "name": "INFO_MAKE",
                  "value": 286261505,
                  "description": "Manufacturer name of the vehicle."
                },
                {
                  "name": "AP_POWER_STATE_REQ",
                  "value": 289475072,
                  "data_enums": ["VehicleApPowerStateReq", "VehicleApPowerStateShutdownParam"]
                },
                {
                  "name": "Vehicle Size Class",
                  "value": 289472782,
                  "data_enum": "VehicleSizeClass"
                }
              ]
            },
            {
              "name": "VehicleSizeClass",
              "values": [
                { "name": "EU_A_SEGMENT", "value": 100 },
                { "name": "JPN_KEI", "value": 400 }
              ]
            }
          ]
          """
            .trimIndent()
        Files.writeString(avdFolder.resolve("android.hardware.automotive.vehicle-types-meta.json"), metaJson)
      }

    val dialog = CarDataDialog(emulatorController).show(projectRule.project)
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val tabbedPane = ui.getComponent<JBTabbedPane> { it.name == "carDataTabbedPane" }
    tabbedPane.selectedIndex = 1
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    @Suppress("UNCHECKED_CAST") val table = ui.getComponent<TableView<VhalPropertyEntry>> { it.name == "vhalPropertiesTable" }
    val searchField = ui.getComponent<SearchTextField> { it.name == "vhalSearchField" }
    val refreshButton = ui.getComponent<JButton> { it.name == "vhalRefreshButton" }
    val propNameField = ui.getComponent<JBTextField> { it.name == "vhalPropertyNameField" }
    val propIdField = ui.getComponent<JBTextField> { it.name == "vhalPropertyIdField" }
    val areaField = ui.getComponent<JBTextField> { it.name == "vhalAreaField" }
    val typeField = ui.getComponent<JBTextField> { it.name == "vhalTypeField" }
    val valueField = ui.getComponent<JBTextField> { it.name == "vhalValueField" }
    @Suppress("UNCHECKED_CAST") val boolCombo = ui.getComponent<ComboBox<Boolean>> { it.name == "vhalBooleanComboBox" }
    @Suppress("UNCHECKED_CAST") val enumCombo = ui.getComponent<ComboBox<EnumChoice>> { it.name == "vhalEnumComboBox" }
    @Suppress("UNCHECKED_CAST") val checkList = ui.getComponent<CheckBoxList<EnumChoice>> { it.name == "vhalInt32VecCheckBoxList" }
    val descriptionArea = ui.getComponent<JBTextArea> { it.name == "vhalDescriptionArea" }

    // Wait for properties and JSON metadata to load.
    waitForCondition(5.seconds) {
      table.items.any { it.propId == VehicleProperty.INFO_MAKE } && table.items.any { it.propId == VehicleProperty.WHEEL_TICK }
    }

    // Filter by "Manufacturer" (STRING property) and edit it in place.
    searchField.text = "Manufacturer"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(propNameField.text).isEqualTo("Manufacturer")
    assertThat(propIdField.text).isEqualTo(VehicleProperty.INFO_MAKE.toString())
    assertThat(areaField.text).isEqualTo("Global")
    assertThat(typeField.text).isEqualTo("String")
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isTrue()
    assertThat(valueField.text).isEqualTo("Android")
    waitForCondition(5.seconds) { descriptionArea.text == "Manufacturer name of the vehicle." }

    valueField.text = "Waymo"
    valueField.postActionEvent()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("Waymo")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.INFO_MAKE)?.stringValue == "Waymo"
    }

    // Edit BOOLEAN property (Door locked, Row 1 L) in place.
    searchField.text = "Door locked"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(areaField.text).isEqualTo("Door: Row 1 L")
    assertThat(boolCombo.isVisible).isTrue()
    assertThat(boolCombo.selectedItem).isEqualTo(true)

    boolCombo.selectedItem = false
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("False")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.DOOR_LOCK, VehicleAreaDoor.ROW_1_LEFT)?.getInt32Values(0) == 0
    }

    // Edit INT32 enum property (Engine oil level) in place.
    searchField.text = "Engine oil level"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(enumCombo.isVisible).isTrue()
    assertThat((enumCombo.selectedItem as? EnumChoice)?.label).isEqualTo("Normal")

    val lowChoice = (0 until enumCombo.itemCount).map { enumCombo.getItemAt(it) }.first { it.value == VehicleOilLevel.LOW }
    enumCombo.selectedItem = lowChoice
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("Low")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.ENGINE_OIL_LEVEL)?.getInt32Values(0) == VehicleOilLevel.LOW
    }

    // Edit raw INT32 property (Model year) in place, including invalid input validation.
    searchField.text = "Model year"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isTrue()
    assertThat(valueField.text).isEqualTo("2026")

    valueField.text = "invalid"
    assertThat(valueField.getClientProperty("JComponent.outline")).isEqualTo("error")
    valueField.text = "2027"
    assertThat(valueField.getClientProperty("JComponent.outline")).isNull()
    valueField.postActionEvent()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("2027")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.INFO_MODEL_YEAR)?.getInt32Values(0) == 2027
    }

    // Edit FLOAT multi-area property (Target temperature set, Seat: Row 1 R) in place.
    searchField.text = "Target temperature set"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(2)
    val row1RightIndex = (0 until table.rowCount).indexOfFirst { table.getRow(it).areaId == VehicleAreaSeat.ROW_1_RIGHT }
    table.selectionModel.setSelectionInterval(row1RightIndex, row1RightIndex)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(areaField.text).isEqualTo("Seat: Row 1 R")
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isTrue()
    assertThat(valueField.text).isEqualTo("22.0")

    valueField.text = "19.5"
    valueField.postActionEvent()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("19.5")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.HVAC_TEMPERATURE_SET, VehicleAreaSeat.ROW_1_RIGHT)?.getFloatValues(0) == 19.5f
    }

    // Edit INT64 property (VHAL_HEARTBEAT) in place.
    searchField.text = "290459443"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isTrue()
    assertThat(valueField.text).isEqualTo("1000")

    valueField.text = "9876543210"
    valueField.postActionEvent()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("9876543210")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.VHAL_HEARTBEAT)?.getInt64Values(0) == 9876543210L
    }

    // Edit INT32_VEC enum property (Usable fuels) in place.
    searchField.text = "Usable fuels"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(checkList.isVisible).isTrue()
    val choices = (0 until checkList.itemsCount).mapNotNull { checkList.getItemAt(it) }
    val unleadedChoice = choices.first { it.value == FuelType.FUEL_TYPE_UNLEADED }
    val electricChoice = choices.first { it.value == FuelType.FUEL_TYPE_ELECTRIC }
    val hydrogenChoice = choices.first { it.value == FuelType.FUEL_TYPE_HYDROGEN }
    assertThat(checkList.isItemSelected(unleadedChoice)).isTrue()
    assertThat(checkList.isItemSelected(electricChoice)).isTrue()
    checkList.setItemSelected(unleadedChoice, false)
    checkList.setItemSelected(hydrogenChoice, true)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.text).isEqualTo("Electric; Hydrogen")
    waitForCondition(5.seconds) {
      fakeEmulator.getVehicleProperty(VehicleProperty.INFO_FUEL_TYPE)?.int32ValuesList ==
        listOf(FuelType.FUEL_TYPE_ELECTRIC, FuelType.FUEL_TYPE_HYDROGEN)
    }

    // Non-editable INT64_VEC property (Wheel ticks) displays a read-only text field.
    searchField.text = "Wheel ticks"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(typeField.text).isEqualTo("Int64[]")
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isFalse()
    assertThat(valueField.text).isEqualTo("1; 100; 100; 100; 100")

    // Positional INT32_VEC tuple properties remain non-editable, while INT32_VEC enum properties from *types-meta.json are editable.
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder()
        .setProp(VehicleProperty.AP_POWER_STATE_REQ)
        .addInt32Values(VehicleApPowerStateReq.SHUTDOWN_PREPARE)
        .addInt32Values(VehicleApPowerStateShutdownParam.CAN_SLEEP)
        .build()
    )
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder().setProp(VehicleProperty.HW_KEY_INPUT).addInt32Values(0).addInt32Values(24).addInt32Values(0).build()
    )
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder().setProp(289472782).addInt32Values(100).addInt32Values(400).build()
    )
    searchField.text = "App processor power state control"
    waitForCondition(5.seconds) { table.rowCount == 1 }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isFalse()
    assertThat(valueField.text).isEqualTo("Shutdown requested (Enter deep sleep after Garage Mode)")

    searchField.text = "H/W input key"
    waitForCondition(5.seconds) { table.rowCount == 1 }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isFalse()
    assertThat(valueField.text).isEqualTo("0; 24; 0")

    searchField.text = "Vehicle Size Class"
    waitForCondition(5.seconds) { table.rowCount == 1 }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(checkList.isVisible).isTrue()
    assertThat(valueField.text).isEqualTo("EU_A_SEGMENT; JPN_KEI")

    // Refresh button reloads properties.
    searchField.text = ""
    ui.clickOn(refreshButton)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isGreaterThan(10)

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
      **All properties, 5 properties
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
      Property:0x11410105, Property name:INFO_FUEL_TYPE, access:0x3, changeMode:0x0, config:[], fs min:0.000000, fs max:0.000000
          areaId:0x0, f min:0.000000, f max:0.000000, i min:0, i max:0, i64 min:0, i64 max:0
      **All property values, 5 values
      HalPropValue{Property ID: INFO_MAKE(0x11100101), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 123, Status: AVAILABLE(0x0), Value: AOSP Automotive}
      HalPropValue{Property ID: PERF_VEHICLE_SPEED(0x11600207), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 124, Status: AVAILABLE(0x0), Value: 0.0 METER_PER_SEC}
      HalPropValue{Property ID: GEAR_SELECTION(0x11400400), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 125, SystemStatus: AVAILABLE(0x0), VendorStatus: 0, Value: GEAR_DRIVE(0x8)}
      HalPropValue{Property ID: PARKING_BRAKE_ON(0x11200402), Area ID: GLOBAL(0x0), ElapsedRealtimeNanos: 126, Status: AVAILABLE(0x0), Value: FALSE}
      Property:0x11410105, status: 0, timestamp: 127, zone: 0x0, floatValues: [], int32Values: [1, 10], int64Values: [], bytes: [], string:
      """
        .trimIndent()
    adb.configureShellCommand(deviceSelector, DUMP_VHAL_COMMAND, adbDump)

    val dialog = CarDataDialog(emulatorController).show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val speedSlider = ui.getComponent<JSlider> { it.name == "carSpeedSlider" }
    @Suppress("UNCHECKED_CAST") val gearCombo = ui.getComponent<ComboBox<GearOption>> { it.name == "gearComboBox" }
    @Suppress("UNCHECKED_CAST") val ignitionCombo = ui.getComponent<ComboBox<IgnitionOption>> { it.name == "ignitionComboBox" }
    val parkingBrakeCheckBox = ui.getComponent<JBCheckBox> { it.name == "parkingBrakeCheckBox" }
    val nightModeCheckBox = ui.getComponent<JBCheckBox> { it.name == "nightModeCheckBox" }
    val fuelLevelLowCheckBox = ui.getComponent<JBCheckBox> { it.name == "fuelLevelLowCheckBox" }
    waitForCondition(5.seconds) { gearCombo.isEnabled && gearCombo.selectedItem == GearOption.DRIVE && !parkingBrakeCheckBox.isSelected }
    assertThat(speedSlider.isEnabled).isTrue()
    assertThat(parkingBrakeCheckBox.isEnabled).isTrue()
    assertThat(ignitionCombo.isEnabled).isFalse()
    assertThat(nightModeCheckBox.isEnabled).isFalse()
    assertThat(fuelLevelLowCheckBox.isEnabled).isFalse()

    val tabbedPane = ui.getComponent<JBTabbedPane> { it.name == "carDataTabbedPane" }
    tabbedPane.selectedIndex = 1
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    @Suppress("UNCHECKED_CAST") val table = ui.getComponent<TableView<VhalPropertyEntry>> { it.name == "vhalPropertiesTable" }
    val searchField = ui.getComponent<SearchTextField> { it.name == "vhalSearchField" }
    val valueField = ui.getComponent<JBTextField> { it.name == "vhalValueField" }
    @Suppress("UNCHECKED_CAST") val checkList = ui.getComponent<CheckBoxList<EnumChoice>> { it.name == "vhalInt32VecCheckBoxList" }
    assertThat(table.items).hasSize(5)

    // Write-only property from ADB config has read-only Value field.
    searchField.text = "Manufacturer"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(valueField.isVisible).isTrue()
    assertThat(valueField.isEditable).isFalse()
    assertThat(valueField.text).isEqualTo("AOSP Automotive")

    // Legacy format INT32_VEC property parsed and editable in place.
    searchField.text = "Usable fuels"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.rowCount).isEqualTo(1)
    assertThat(checkList.isVisible).isTrue()
    assertThat(valueField.text).isEqualTo("Unleaded; Electric")

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testVhalPropertiesTableSortingAndPersistence() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    // Add properties with 2-digit and 10-digit IDs so numeric vs lexicographic ID sorting is distinguishable.
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder().setProp(99).setStatus(VehiclePropStatus.AVAILABLE).addInt32Values(1).build()
    )
    fakeEmulator.setVehiclePropertyOnDevice(
      VehiclePropValue.newBuilder().setProp(1_000_000_000).setStatus(VehiclePropStatus.AVAILABLE).addInt32Values(2).build()
    )

    val dialog1 = CarDataDialog(emulatorController).show(projectRule.project)
    val ui1 = FakeUi(dialog1.rootPane)
    ui1.getComponent<JBTabbedPane> { it.name == "carDataTabbedPane" }.selectedIndex = 1
    ui1.layoutAndDispatchEvents()

    @Suppress("UNCHECKED_CAST") val table1 = ui1.getComponent<TableView<VhalPropertyEntry>> { it.name == "vhalPropertiesTable" }
    val propNameField1 = ui1.getComponent<JBTextField> { it.name == "vhalPropertyNameField" }

    waitForCondition(5.seconds) {
      table1.items.any { it.propId == 99 } &&
        table1.items.any { it.propId == 1_000_000_000 } &&
        table1.items.any { it.propId == VehicleProperty.INFO_MAKE }
    }
    ui1.layoutAndDispatchEvents()

    // Default sort order is Property ascending, with multi-area tie-breaking by areaId and view row 0 selected.
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.ASCENDING))
    assertThat(table1.selectedRow).isEqualTo(0)
    assertThat(propNameField1.text).isEqualTo(table1.getRow(0).label)
    val defaultOrderKeys = (0 until table1.rowCount).map { table1.getRow(it).key }
    val hvacAreasInDefaultOrder =
      (0 until table1.rowCount).map { table1.getRow(it) }.filter { it.propId == VehicleProperty.HVAC_TEMPERATURE_SET }.map { it.areaId }
    assertThat(hvacAreasInDefaultOrder).containsExactly(VehicleAreaSeat.ROW_1_LEFT, VehicleAreaSeat.ROW_1_RIGHT).inOrder()

    // Select "Manufacturer" and verify selection is preserved across column header clicks.
    val makeViewRow = (0 until table1.rowCount).indexOfFirst { table1.getRow(it).propId == VehicleProperty.INFO_MAKE }
    table1.selectionModel.setSelectionInterval(makeViewRow, makeViewRow)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(propNameField1.text).isEqualTo("Manufacturer")

    // Click Property header -> descending, then click again -> ascending.
    clickTableHeader(ui1, table1, 0)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.DESCENDING))
    assertThat((0 until table1.rowCount).map { table1.getRow(it).key }).isEqualTo(defaultOrderKeys.asReversed())
    assertThat(table1.selectedObject?.propId).isEqualTo(VehicleProperty.INFO_MAKE)
    assertThat(propNameField1.text).isEqualTo("Manufacturer")

    clickTableHeader(ui1, table1, 0)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.ASCENDING))
    assertThat((0 until table1.rowCount).map { table1.getRow(it).key }).isEqualTo(defaultOrderKeys)

    // Click Area header -> ascending ("Door: Row 1 L" < "Global" < "Seat: Row 1 L" < "Seat: Row 1 R"), then click again -> descending.
    val sampleAreas = setOf("Door: Row 1 L", "Global", "Seat: Row 1 L", "Seat: Row 1 R")
    clickTableHeader(ui1, table1, 1)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(1, SortOrder.ASCENDING))
    val areaAscList = (0 until table1.rowCount).map { table1.getRow(it).areaText }
    for ((a, b) in areaAscList.zipWithNext()) {
      assertThat(Collator.getInstance().compare(a, b)).isAtMost(0)
    }
    assertThat(areaAscList.distinct().filter { it in sampleAreas })
      .containsExactly("Door: Row 1 L", "Global", "Seat: Row 1 L", "Seat: Row 1 R")
      .inOrder()
    assertThat(table1.selectedObject?.propId).isEqualTo(VehicleProperty.INFO_MAKE)

    clickTableHeader(ui1, table1, 1)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(1, SortOrder.DESCENDING))
    val areaDescList = (0 until table1.rowCount).map { table1.getRow(it).areaText }
    for ((a, b) in areaDescList.zipWithNext()) {
      assertThat(Collator.getInstance().compare(a, b)).isAtLeast(0)
    }
    assertThat(areaDescList.distinct().filter { it in sampleAreas })
      .containsExactly("Seat: Row 1 R", "Seat: Row 1 L", "Global", "Door: Row 1 L")
      .inOrder()

    // Click ID header -> numeric ascending (99 first, 1000000000 last), then click again -> numeric descending.
    clickTableHeader(ui1, table1, 2)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(2, SortOrder.ASCENDING))
    val idAscEntries = (0 until table1.rowCount).map { table1.getRow(it) }
    assertThat(idAscEntries.first().propId).isEqualTo(99)
    assertThat(idAscEntries.last().propId).isEqualTo(1_000_000_000)
    for ((a, b) in idAscEntries.zipWithNext()) {
      assertThat(a.propId < b.propId || (a.propId == b.propId && a.areaId <= b.areaId)).isTrue()
    }

    clickTableHeader(ui1, table1, 2)
    assertThat(table1.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(2, SortOrder.DESCENDING))
    val idDescEntries = (0 until table1.rowCount).map { table1.getRow(it) }
    assertThat(idDescEntries.first().propId).isEqualTo(1_000_000_000)
    assertThat(idDescEntries.last().propId).isEqualTo(99)
    for ((a, b) in idDescEntries.zipWithNext()) {
      assertThat(a.propId > b.propId || (a.propId == b.propId && a.areaId >= b.areaId)).isTrue()
    }

    dialog1.close(DialogWrapper.CLOSE_EXIT_CODE)

    // Verify sort order is stored in application-level PropertiesComponent and restored when opening a new dialog.
    val properties = PropertiesComponent.getInstance()
    assertThat(properties.getValue(VhalPropertiesTab.SORT_ORDER_STORAGE_KEY)).isEqualTo("2:DESCENDING")

    val dialog2 = CarDataDialog(emulatorController).show(projectRule.project)
    val ui2 = FakeUi(dialog2.rootPane)
    ui2.getComponent<JBTabbedPane> { it.name == "carDataTabbedPane" }.selectedIndex = 1
    ui2.layoutAndDispatchEvents()

    @Suppress("UNCHECKED_CAST") val table2 = ui2.getComponent<TableView<VhalPropertyEntry>> { it.name == "vhalPropertiesTable" }
    val propIdField2 = ui2.getComponent<JBTextField> { it.name == "vhalPropertyIdField" }
    waitForCondition(5.seconds) { table2.items.any { it.propId == 1_000_000_000 } }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertThat(table2.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(2, SortOrder.DESCENDING))
    val restoredEntries = (0 until table2.rowCount).map { table2.getRow(it) }
    assertThat(restoredEntries.first().propId).isEqualTo(1_000_000_000)
    assertThat(restoredEntries.last().propId).isEqualTo(99)
    for ((a, b) in restoredEntries.zipWithNext()) {
      assertThat(a.propId > b.propId || (a.propId == b.propId && a.areaId >= b.areaId)).isTrue()
    }
    // Initial selection is the first visible row under the restored sort order.
    assertThat(table2.selectedRow).isEqualTo(0)
    assertThat(propIdField2.text).isEqualTo("1000000000")

    dialog2.close(DialogWrapper.CLOSE_EXIT_CODE)

    // Corrupted persisted sort value falls back cleanly to Property ascending.
    properties.setValue(VhalPropertiesTab.SORT_ORDER_STORAGE_KEY, "99:INVALID")
    val dialog3 = CarDataDialog(emulatorController).show(projectRule.project)
    val ui3 = FakeUi(dialog3.rootPane)
    ui3.getComponent<JBTabbedPane> { it.name == "carDataTabbedPane" }.selectedIndex = 1
    ui3.layoutAndDispatchEvents()
    @Suppress("UNCHECKED_CAST") val table3 = ui3.getComponent<TableView<VhalPropertyEntry>> { it.name == "vhalPropertiesTable" }
    assertThat(table3.rowSorter.sortKeys).containsExactly(RowSorter.SortKey(0, SortOrder.ASCENDING))
    dialog3.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  private fun clickTableHeader(ui: FakeUi, table: TableView<*>, column: Int) {
    val header = table.tableHeader
    val rect = header.getHeaderRect(column)
    ui.mouse.click(ui.getPosition(header).x + rect.centerX.toInt(), ui.getPosition(header).y + rect.centerY.toInt())
  }
}
