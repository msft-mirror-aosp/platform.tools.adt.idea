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

import com.android.emulation.control.incubating.CarEvent
import com.android.emulation.control.incubating.EmulatorMessage
import com.android.emulation.control.incubating.MsgType
import com.android.emulation.control.incubating.Status
import com.android.emulation.control.incubating.VehiclePropStatus
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.testutils.waitForCondition
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.FuelType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateReport
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateReq
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateShutdownParam
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaDoor
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaSeat
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleHvacFanDirection
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyGroup
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyType
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [VhalPropertyDictionary] and emulator VHAL car events. */
@RunsInEdt
class CarDataDialogTest {

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
    )

  private fun createAndStartAutomotiveEmulator(customizeAvd: (Path) -> Unit = {}): Pair<FakeEmulator, EmulatorController> {
    val testAvdFolder = FakeEmulator.createAutomotiveAvd(emulatorRule.avdRoot)
    customizeAvd(testAvdFolder)
    val fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  @Test
  fun testVhalPropertyDictionaryFormattingAndMetadataLoading() {
    val dict = VhalPropertyDictionary.DEFAULT
    assertThat(dict.getPropertyLabel(VehicleProperty.INFO_MAKE)).isEqualTo("Manufacturer")
    assertThat(dict.getPropertyLabel(VehiclePropertyGroup.VENDOR or 0x1234)).isEqualTo("Vendor(id: 0x20001234)")
    assertThat(dict.getValueType(VehicleProperty.INFO_MAKE)).isEqualTo(VehiclePropertyType.STRING)
    assertThat(dict.getValueTypeLabel(VehicleProperty.INFO_MAKE)).isEqualTo("String")
    assertThat(dict.getValueTypeLabel(VehicleProperty.INFO_FUEL_TYPE)).isEqualTo("Int32[]")
    assertThat(dict.getAreaString(VehicleProperty.INFO_MAKE, 0)).isEqualTo("Global")
    assertThat(dict.getAreaString(VehicleProperty.HVAC_TEMPERATURE_SET, VehicleAreaSeat.ROW_1_LEFT or VehicleAreaSeat.ROW_1_RIGHT))
      .isEqualTo("Seat: Row 1 L, Row 1 R")
    assertThat(dict.getAreaString(VehicleProperty.DOOR_LOCK, VehicleAreaDoor.ROW_1_LEFT)).isEqualTo("Door: Row 1 L")

    assertThat(
        dict.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.GEAR_SELECTION)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(VehicleGear.GEAR_DRIVE)
            .build()
        )
      )
      .isEqualTo("D")
    assertThat(
        dict.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.HVAC_FAN_DIRECTION)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(VehicleHvacFanDirection.FACE or VehicleHvacFanDirection.FLOOR)
            .build()
        )
      )
      .isEqualTo("Face, Floor")
    assertThat(
        dict.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.HVAC_SEAT_TEMPERATURE)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(-2)
            .build()
        )
      )
      .isEqualTo("-2 (Cooling)")
    assertThat(
        dict.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.AP_POWER_STATE_REQ)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(VehicleApPowerStateReq.SHUTDOWN_PREPARE)
            .addInt32Values(VehicleApPowerStateShutdownParam.CAN_SLEEP)
            .build()
        )
      )
      .isEqualTo("Shutdown requested (Enter deep sleep after Garage Mode)")
    assertThat(
        dict.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.AP_POWER_STATE_REPORT)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(VehicleApPowerStateReport.SHUTDOWN_POSTPONE)
            .addInt32Values(5000)
            .build()
        )
      )
      .isEqualTo("Shutdown postponed (Time: 5000 ms)")
    assertThat(dict.isEditable(VehicleProperty.INFO_MAKE)).isTrue()
    assertThat(dict.isEditable(VehicleProperty.INFO_FUEL_TYPE)).isTrue()
    assertThat(dict.isEditable(VehicleProperty.AP_POWER_STATE_REQ)).isFalse()
    assertThat(dict.isEditable(VehicleProperty.WHEEL_TICK)).isFalse()

    val dir = tempFolder.newFolder().toPath()
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
    Files.writeString(dir.resolve("android.hardware.automotive.vehicle-types-meta.json"), metaJson)
    val loaded = VhalPropertyDictionary.loadFromDirectories(listOf(dir))
    assertThat(loaded.getDetailedDescription(VehicleProperty.INFO_MAKE)).isEqualTo("Manufacturer name of the vehicle.")
    assertThat(loaded.getPropertyLabel(289472782)).isEqualTo("Vehicle Size Class")
    assertThat(loaded.getLookupTable(289472782)).containsExactly(100, "EU_A_SEGMENT", 400, "JPN_KEI")
    assertThat(loaded.isEditable(289472782)).isTrue()
    assertThat(
        loaded.getValueString(
          VehiclePropValue.newBuilder()
            .setProp(VehicleProperty.INFO_FUEL_TYPE)
            .setStatus(VehiclePropStatus.AVAILABLE)
            .addInt32Values(FuelType.FUEL_TYPE_UNLEADED)
            .addInt32Values(FuelType.FUEL_TYPE_ELECTRIC)
            .build()
        )
      )
      .isEqualTo("Unleaded; Electric")
  }

  @Test
  fun testReceiveAndSendCarEvents() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val receivedMessages = CopyOnWriteArrayList<EmulatorMessage>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      scope.launch {
        emulatorController
          .receiveCarEvents(
            onSubscribed = {
              val getAllMsg = EmulatorMessage.newBuilder().setMsgType(MsgType.GET_PROPERTY_ALL_CMD).setStatus(Status.RESULT_OK).build()
              emulatorController.sendCarEvent(CarEvent.newBuilder().setData(getAllMsg.toByteString()).build())
            }
          )
          .collect { event ->
            receivedMessages.add(EmulatorMessage.parseFrom(event.data))
          }
      }

      waitForCondition(5.seconds) {
        receivedMessages.any { it.msgType == MsgType.GET_PROPERTY_ALL_RESP && it.valueCount > 0 }
      }

      val newGear =
        VehiclePropValue.newBuilder()
          .setProp(VehicleProperty.GEAR_SELECTION)
          .setStatus(VehiclePropStatus.AVAILABLE)
          .addInt32Values(VehicleGear.GEAR_DRIVE)
          .build()
      runBlocking {
        val setMsg = EmulatorMessage.newBuilder().setMsgType(MsgType.SET_PROPERTY_CMD).setStatus(Status.RESULT_OK).addValue(newGear).build()
        emulatorController.sendCarEvent(CarEvent.newBuilder().setData(setMsg.toByteString()).build())
      }

      waitForCondition(5.seconds) {
        fakeEmulator.getVehicleProperty(VehicleProperty.GEAR_SELECTION)?.getInt32Values(0) == VehicleGear.GEAR_DRIVE &&
          receivedMessages.any { it.msgType == MsgType.SET_PROPERTY_RESP && it.status == Status.RESULT_OK }
      }
    } finally {
      scope.cancel()
    }
  }
}
