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
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.cleanNegativeZero
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.EvConnectorType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.FuelType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.GsrComplianceRequirementType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.PortLocationType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateReport
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateReq
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleApPowerStateShutdownParam
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleArea
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaDoor
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaMirror
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaSeat
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaWheel
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleAreaWindow
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleHvacFanDirection
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleIgnitionState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleLightState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleLightSwitch
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleOilLevel
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyGroup
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehiclePropertyType
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleSeatOccupancyState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleTurnSignal
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleUnit
import com.android.utils.throwIfCancellation
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Metadata and human-readable formatting for Android Automotive Vehicle HAL (VHAL) properties, areas, and enum values, optionally enriched
 * from `*types-meta.json` files in the AVD's system image or configuration folder.
 */
internal class VhalPropertyDictionary
private constructor(
  private val properties: Map<Int, PropertyMetadata>,
  private val lookupTables: Map<String, Map<Int, String>>,
) {

  private data class PropertyMetadata(
    val label: String,
    val lookupTableNames: List<String> = emptyList(),
    val int32Formatter: ((Int) -> String)? = null,
    val int32VecFormatter: ((List<Int>) -> String)? = null,
    val detailedDescription: String = "",
  )

  fun getPropertyLabel(propId: Int): String {
    properties[propId]
      ?.label
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return it
      }
    if ((propId and VehiclePropertyGroup.MASK) == VehiclePropertyGroup.VENDOR) {
      return String.format(Locale.US, "Vendor(id: 0x%x)", propId)
    }
    return propId.toString()
  }

  fun getDetailedDescription(propId: Int): String = properties[propId]?.detailedDescription.orEmpty()

  fun getLookupTable(propId: Int): Map<Int, String>? {
    val meta = properties[propId] ?: return null
    val matchingTables = meta.lookupTableNames.mapNotNull { lookupTables[it]?.takeIf(Map<Int, String>::isNotEmpty) }
    return when (matchingTables.size) {
      0 -> null
      1 -> matchingTables[0]
      else -> buildMap { matchingTables.forEach(::putAll) }
    }
  }

  fun getValueType(propId: Int): Int = propId and VehiclePropertyType.MASK

  fun getValueTypeLabel(propId: Int): String {
    return when (getValueType(propId)) {
      VehiclePropertyType.STRING -> "String"
      VehiclePropertyType.BOOLEAN -> "Boolean"
      VehiclePropertyType.INT32 -> "Int32"
      VehiclePropertyType.INT32_VEC -> "Int32[]"
      VehiclePropertyType.INT64 -> "Int64"
      VehiclePropertyType.INT64_VEC -> "Int64[]"
      VehiclePropertyType.FLOAT -> "Float"
      VehiclePropertyType.FLOAT_VEC -> "Float[]"
      VehiclePropertyType.BYTES -> "Bytes"
      VehiclePropertyType.MIXED -> "Mixed"
      else -> String.format(Locale.US, "0x%x", getValueType(propId))
    }
  }

  fun getAreaString(propId: Int, areaId: Int): String {
    val maskedArea = propId and VehicleArea.MASK
    val (areaPrefix, tableName) =
      when (maskedArea) {
        VehicleArea.GLOBAL -> return "Global"
        VehicleArea.WINDOW -> "Window" to "window"
        VehicleArea.MIRROR -> "Mirror" to "mirror"
        VehicleArea.SEAT -> "Seat" to "seat"
        VehicleArea.DOOR -> "Door" to "door"
        VehicleArea.WHEEL -> "Wheel" to "wheel"
        else -> return if (areaId == 0) "Global" else String.format(Locale.US, "0x%x", areaId)
      }
    if (areaId == 0) {
      return areaPrefix
    }
    val table = lookupTables[tableName]
    val details = table?.let { multiDetailToString(it, areaId) }.orEmpty()
    return if (details.isNotEmpty()) "$areaPrefix: $details" else "$areaPrefix: ${String.format(Locale.US, "0x%x", areaId)}"
  }

  fun getValueString(propValue: VehiclePropValue): String {
    when (propValue.status) {
      VehiclePropStatus.AVAILABLE -> {}
      VehiclePropStatus.ERROR -> return message("car.data.dialog.value.error")
      else -> return message("car.data.dialog.value.unavailable")
    }

    val meta = properties[propValue.prop]
    return when (getValueType(propValue.prop)) {
      VehiclePropertyType.STRING -> propValue.stringValue
      VehiclePropertyType.BOOLEAN -> {
        if (propValue.int32ValuesCount > 0) {
          if (propValue.getInt32Values(0) != 0) message("car.data.dialog.value.true") else message("car.data.dialog.value.false")
        } else {
          "N/A"
        }
      }
      VehiclePropertyType.INT32 -> {
        if (propValue.int32ValuesCount > 0) {
          int32ToString(meta, propValue.getInt32Values(0))
        } else {
          "N/A"
        }
      }
      VehiclePropertyType.INT32_VEC -> {
        val values = propValue.int32ValuesList
        meta?.int32VecFormatter?.invoke(values) ?: values.joinToString("; ") { int32ToString(meta, it) }
      }
      VehiclePropertyType.INT64 -> {
        if (propValue.int64ValuesCount > 0) propValue.getInt64Values(0).toString() else "N/A"
      }
      VehiclePropertyType.INT64_VEC -> propValue.int64ValuesList.joinToString("; ")
      VehiclePropertyType.FLOAT -> {
        if (propValue.floatValuesCount > 0) formatFloat(propValue.getFloatValues(0)) else "N/A"
      }
      VehiclePropertyType.FLOAT_VEC -> propValue.floatValuesList.joinToString("; ") { formatFloat(it) }
      VehiclePropertyType.BYTES -> "Display N/A (bytes)"
      VehiclePropertyType.MIXED -> "Display N/A (mixed)"
      else -> "Unknown value type"
    }
  }

  fun isEditable(propId: Int): Boolean {
    return when (getValueType(propId)) {
      VehiclePropertyType.BOOLEAN,
      VehiclePropertyType.INT32,
      VehiclePropertyType.INT64,
      VehiclePropertyType.FLOAT,
      VehiclePropertyType.STRING -> true
      VehiclePropertyType.INT32_VEC -> properties[propId]?.int32VecFormatter == null && getLookupTable(propId) != null
      else -> false
    }
  }

  private fun int32ToString(meta: PropertyMetadata?, value: Int): String {
    meta?.int32Formatter?.let {
      return it(value)
    }
    if (meta != null) {
      for (tableName in meta.lookupTableNames) {
        lookupTables[tableName]?.get(value)?.let {
          return it
        }
      }
    }
    return value.toString()
  }

  companion object {
    private val LOG = Logger.getInstance(VhalPropertyDictionary::class.java)

    private val MIRROR_MAP =
      linkedMapOf(
        VehicleAreaMirror.DRIVER_LEFT to "Driver left",
        VehicleAreaMirror.DRIVER_RIGHT to "Driver right",
        VehicleAreaMirror.DRIVER_CENTER to "Driver center",
      )

    private val SEAT_MAP =
      linkedMapOf(
        VehicleAreaSeat.ROW_1_LEFT to "Row 1 L",
        VehicleAreaSeat.ROW_1_CENTER to "Row 1 C",
        VehicleAreaSeat.ROW_1_RIGHT to "Row 1 R",
        VehicleAreaSeat.ROW_2_LEFT to "Row 2 L",
        VehicleAreaSeat.ROW_2_CENTER to "Row 2 C",
        VehicleAreaSeat.ROW_2_RIGHT to "Row 2 R",
        VehicleAreaSeat.ROW_3_LEFT to "Row 3 L",
        VehicleAreaSeat.ROW_3_CENTER to "Row 3 C",
        VehicleAreaSeat.ROW_3_RIGHT to "Row 3 R",
      )

    private val WINDOW_MAP =
      linkedMapOf(
        VehicleAreaWindow.FRONT_WINDSHIELD to "Front windshield",
        VehicleAreaWindow.REAR_WINDSHIELD to "Rear windshield",
        VehicleAreaWindow.ROW_1_LEFT to "Row 1 L",
        VehicleAreaWindow.ROW_1_RIGHT to "Row 1 R",
        VehicleAreaWindow.ROW_2_LEFT to "Row 2 L",
        VehicleAreaWindow.ROW_2_RIGHT to "Row 2 R",
        VehicleAreaWindow.ROW_3_LEFT to "Row 3 L",
        VehicleAreaWindow.ROW_3_RIGHT to "Row 3 R",
        VehicleAreaWindow.ROOF_TOP_1 to "Roof top 1",
        VehicleAreaWindow.ROOF_TOP_2 to "Roof top 2",
      )

    private val DOOR_MAP =
      linkedMapOf(
        VehicleAreaDoor.ROW_1_LEFT to "Row 1 L",
        VehicleAreaDoor.ROW_1_RIGHT to "Row 1 R",
        VehicleAreaDoor.ROW_2_LEFT to "Row 2 L",
        VehicleAreaDoor.ROW_2_RIGHT to "Row 2 R",
        VehicleAreaDoor.ROW_3_LEFT to "Row 3 L",
        VehicleAreaDoor.ROW_3_RIGHT to "Row 3 R",
        VehicleAreaDoor.HOOD to "Hood",
        VehicleAreaDoor.REAR to "Rear",
      )

    private val WHEEL_MAP =
      linkedMapOf(
        VehicleAreaWheel.UNKNOWN to "Unknown",
        VehicleAreaWheel.LEFT_FRONT to "Front left",
        VehicleAreaWheel.RIGHT_FRONT to "Front right",
        VehicleAreaWheel.LEFT_REAR to "Rear left",
        VehicleAreaWheel.RIGHT_REAR to "Rear right",
      )

    private val IGNITION_STATE_MAP =
      linkedMapOf(
        VehicleIgnitionState.UNDEFINED to "Undefined",
        VehicleIgnitionState.LOCK to "Locked",
        VehicleIgnitionState.OFF to "Off",
        VehicleIgnitionState.ACC to "Accessories available",
        VehicleIgnitionState.ON to "On",
        VehicleIgnitionState.START to "Engine starting",
      )

    private val GEAR_MAP =
      linkedMapOf(
        VehicleGear.GEAR_NEUTRAL to "N",
        VehicleGear.GEAR_REVERSE to "R",
        VehicleGear.GEAR_PARK to "P",
        VehicleGear.GEAR_DRIVE to "D",
        VehicleGear.GEAR_1 to "D1",
        VehicleGear.GEAR_2 to "D2",
        VehicleGear.GEAR_3 to "D3",
        VehicleGear.GEAR_4 to "D4",
        VehicleGear.GEAR_5 to "D5",
        VehicleGear.GEAR_6 to "D6",
        VehicleGear.GEAR_7 to "D7",
        VehicleGear.GEAR_8 to "D8",
        VehicleGear.GEAR_9 to "D9",
      )

    private val LIGHT_STATE_MAP =
      linkedMapOf(
        VehicleLightState.OFF to "Off",
        VehicleLightState.ON to "On",
        VehicleLightState.DAYTIME_RUNNING to "Daytime running",
      )

    private val LIGHT_SWITCH_MAP =
      linkedMapOf(
        VehicleLightSwitch.OFF to "Off",
        VehicleLightSwitch.ON to "On",
        VehicleLightSwitch.DAYTIME_RUNNING to "Daytime running lights",
        VehicleLightSwitch.AUTOMATIC to "Automatic",
      )

    private val TEMP_UNITS_MAP =
      linkedMapOf(
        VehicleUnit.CELSIUS to "Celsius",
        VehicleUnit.FAHRENHEIT to "Fahrenheit",
      )

    private val FAN_DIRECTION_MAP =
      linkedMapOf(
        VehicleHvacFanDirection.FACE to "Face",
        VehicleHvacFanDirection.FLOOR to "Floor",
        VehicleHvacFanDirection.DEFROST to "Defrost",
      )

    private val OIL_LEVEL_MAP =
      linkedMapOf(
        VehicleOilLevel.CRITICALLY_LOW to "Critically low",
        VehicleOilLevel.LOW to "Low",
        VehicleOilLevel.NORMAL to "Normal",
        VehicleOilLevel.HIGH to "High",
        VehicleOilLevel.ERROR to "Error",
      )

    private val FUEL_TYPE_MAP =
      linkedMapOf(
        FuelType.FUEL_TYPE_UNKNOWN to "Unknown",
        FuelType.FUEL_TYPE_UNLEADED to "Unleaded",
        FuelType.FUEL_TYPE_LEADED to "Leaded",
        FuelType.FUEL_TYPE_DIESEL_1 to "Diesel #1",
        FuelType.FUEL_TYPE_DIESEL_2 to "Diesel #2",
        FuelType.FUEL_TYPE_BIODIESEL to "Biodiesel",
        FuelType.FUEL_TYPE_E85 to "85% ethanol/gasoline blend",
        FuelType.FUEL_TYPE_LPG to "Liquified petroleum gas",
        FuelType.FUEL_TYPE_CNG to "Compressed natural gas",
        FuelType.FUEL_TYPE_LNG to "Liquified natural gas",
        FuelType.FUEL_TYPE_ELECTRIC to "Electric",
        FuelType.FUEL_TYPE_HYDROGEN to "Hydrogen",
        FuelType.FUEL_TYPE_OTHER to "Other",
      )

    private val EV_CONNECTOR_TYPE_MAP =
      linkedMapOf(
        EvConnectorType.UNKNOWN to "Unknown",
        EvConnectorType.IEC_TYPE_1_AC to "IEC type 1 AC",
        EvConnectorType.IEC_TYPE_2_AC to "IEC type 2 AC",
        EvConnectorType.IEC_TYPE_3_AC to "IEC type 3 AC",
        EvConnectorType.IEC_TYPE_4_DC to "IEC type 4 DC",
        EvConnectorType.IEC_TYPE_1_CCS_DC to "IEC type 1 CCS DC",
        EvConnectorType.IEC_TYPE_2_CCS_DC to "IEC type 2 CCS DC",
        EvConnectorType.TESLA_ROADSTER to "Tesla Roadster",
        EvConnectorType.TESLA_HPWC to "Tesla HPWC",
        EvConnectorType.TESLA_CHARGER to "Tesla Charger",
        EvConnectorType.GBT_AC to "GBT AC",
        EvConnectorType.GBT_DC to "GBT DC",
        EvConnectorType.OTHER to "Other",
      )

    private val PORT_LOCATION_MAP =
      linkedMapOf(
        PortLocationType.UNKNOWN to "Unknown",
        PortLocationType.FRONT_LEFT to "Front left",
        PortLocationType.FRONT_RIGHT to "Front right",
        PortLocationType.REAR_RIGHT to "Rear right",
        PortLocationType.REAR_LEFT to "Rear left",
        PortLocationType.FRONT to "Front",
        PortLocationType.REAR to "Rear",
      )

    private val GSR_COMPLIANCE_REQ_MAP =
      linkedMapOf(
        GsrComplianceRequirementType.GSR_COMPLIANCE_NOT_REQUIRED to "GSR compliance not required",
        GsrComplianceRequirementType.GSR_COMPLIANCE_REQUIRED_V1 to "GSR compliance required through system image",
      )

    private val TURN_SIGNAL_MAP =
      linkedMapOf(
        VehicleTurnSignal.NONE to "None",
        VehicleTurnSignal.RIGHT to "Right",
        VehicleTurnSignal.LEFT to "Left",
      )

    private val SEAT_OCCUPANCY_MAP =
      linkedMapOf(
        VehicleSeatOccupancyState.UNKNOWN to "Unknown",
        VehicleSeatOccupancyState.VACANT to "Vacant",
        VehicleSeatOccupancyState.OCCUPIED to "Occupied",
      )

    private val AP_POWER_STATE_REQ_MAP =
      linkedMapOf(
        VehicleApPowerStateReq.ON to "Fully on",
        VehicleApPowerStateReq.SHUTDOWN_PREPARE to "Shutdown requested",
        VehicleApPowerStateReq.CANCEL_SHUTDOWN to "Shutdown canceled",
        VehicleApPowerStateReq.FINISHED to "VHAL is ready for shutdown",
      )

    private val AP_POWER_STATE_SHUTDOWN_MAP =
      linkedMapOf(
        VehicleApPowerStateShutdownParam.SHUTDOWN_IMMEDIATELY to "Must shutdown immediately",
        VehicleApPowerStateShutdownParam.CAN_SLEEP to "Enter deep sleep after Garage Mode",
        VehicleApPowerStateShutdownParam.SHUTDOWN_ONLY to "Shutdown after Garage Mode",
        VehicleApPowerStateShutdownParam.SLEEP_IMMEDIATELY to "Enter deep sleep immediately",
        VehicleApPowerStateShutdownParam.HIBERNATE_IMMEDIATELY to "Enter hibernation immediately",
        VehicleApPowerStateShutdownParam.CAN_HIBERNATE to "Enter hibernation after Garage Mode",
      )

    private val AP_POWER_STATE_REPORT_MAP =
      linkedMapOf(
        VehicleApPowerStateReport.WAIT_FOR_VHAL to "Waiting for VHAL",
        VehicleApPowerStateReport.DEEP_SLEEP_ENTRY to "Entering deep sleep",
        VehicleApPowerStateReport.DEEP_SLEEP_EXIT to "Exiting deep sleep",
        VehicleApPowerStateReport.SHUTDOWN_POSTPONE to "Shutdown postponed",
        VehicleApPowerStateReport.SHUTDOWN_START to "Starting shut down",
        VehicleApPowerStateReport.ON to "Device on",
        VehicleApPowerStateReport.SHUTDOWN_PREPARE to "Shutdown request received",
        VehicleApPowerStateReport.SHUTDOWN_CANCELLED to "Shutdown cancel received",
        VehicleApPowerStateReport.HIBERNATION_ENTRY to "Entering hibernation",
        VehicleApPowerStateReport.HIBERNATION_EXIT to "Exiting hibernation",
      )

    private val BUILT_IN_LOOKUP_TABLES: Map<String, Map<Int, String>> =
      linkedMapOf(
        "fuelType" to FUEL_TYPE_MAP,
        "FuelType" to FUEL_TYPE_MAP,
        "evConnector" to EV_CONNECTOR_TYPE_MAP,
        "EvConnectorType" to EV_CONNECTOR_TYPE_MAP,
        "portLocation" to PORT_LOCATION_MAP,
        "PortLocationType" to PORT_LOCATION_MAP,
        "seat" to SEAT_MAP,
        "VehicleAreaSeat" to SEAT_MAP,
        "mirror" to MIRROR_MAP,
        "VehicleAreaMirror" to MIRROR_MAP,
        "door" to DOOR_MAP,
        "VehicleAreaDoor" to DOOR_MAP,
        "window" to WINDOW_MAP,
        "VehicleAreaWindow" to WINDOW_MAP,
        "oilLevel" to OIL_LEVEL_MAP,
        "VehicleOilLevel" to OIL_LEVEL_MAP,
        "gear" to GEAR_MAP,
        "VehicleGear" to GEAR_MAP,
        "wheel" to WHEEL_MAP,
        "VehicleAreaWheel" to WHEEL_MAP,
        "ignitionState" to IGNITION_STATE_MAP,
        "VehicleIgnitionState" to IGNITION_STATE_MAP,
        "fanDirection" to FAN_DIRECTION_MAP,
        "VehicleHvacFanDirection" to FAN_DIRECTION_MAP,
        "tempUnits" to TEMP_UNITS_MAP,
        "lightState" to LIGHT_STATE_MAP,
        "VehicleLightState" to LIGHT_STATE_MAP,
        "lightSwitch" to LIGHT_SWITCH_MAP,
        "VehicleLightSwitch" to LIGHT_SWITCH_MAP,
        "gsrComplianceReq" to GSR_COMPLIANCE_REQ_MAP,
        "GsrComplianceRequirementType" to GSR_COMPLIANCE_REQ_MAP,
        "turnSignal" to TURN_SIGNAL_MAP,
        "VehicleTurnSignal" to TURN_SIGNAL_MAP,
        "seatOccupancy" to SEAT_OCCUPANCY_MAP,
        "VehicleSeatOccupancyState" to SEAT_OCCUPANCY_MAP,
      )

    private val BUILT_IN_PROPERTIES: Map<Int, PropertyMetadata> =
      linkedMapOf(
        VehicleProperty.INVALID to PropertyMetadata("INVALID"),
        VehicleProperty.INFO_VIN to PropertyMetadata("VIN"),
        VehicleProperty.INFO_MAKE to PropertyMetadata("Manufacturer"),
        VehicleProperty.INFO_MODEL to PropertyMetadata("Model"),
        VehicleProperty.INFO_MODEL_YEAR to PropertyMetadata("Model year"),
        VehicleProperty.INFO_FUEL_CAPACITY to PropertyMetadata("Fuel capacity (mL)"),
        VehicleProperty.INFO_FUEL_TYPE to PropertyMetadata("Usable fuels", listOf("fuelType")),
        VehicleProperty.INFO_EV_BATTERY_CAPACITY to PropertyMetadata("Battery capacity (Wh)"),
        VehicleProperty.INFO_EV_CONNECTOR_TYPE to PropertyMetadata("Usable connectors (EV)", listOf("evConnector")),
        VehicleProperty.INFO_FUEL_DOOR_LOCATION to PropertyMetadata("Fuel door location", listOf("portLocation")),
        VehicleProperty.INFO_EV_PORT_LOCATION to PropertyMetadata("EV port location", listOf("portLocation")),
        VehicleProperty.INFO_DRIVER_SEAT to PropertyMetadata("Driver seat location", listOf("seat")),
        VehicleProperty.INFO_EXTERIOR_DIMENSIONS to PropertyMetadata("Exterior dimensions (mm)"),
        VehicleProperty.INFO_MULTI_EV_PORT_LOCATIONS to PropertyMetadata("List of fuel door locations", listOf("portLocation")),
        VehicleProperty.PERF_ODOMETER to PropertyMetadata("Odometer value (km)"),
        VehicleProperty.PERF_VEHICLE_SPEED to PropertyMetadata("Speed (m/s)"),
        VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY to PropertyMetadata("Speed for displays (m/s)"),
        VehicleProperty.PERF_STEERING_ANGLE to PropertyMetadata("Front bicycle model steering angle (\u00B0)"),
        VehicleProperty.PERF_REAR_STEERING_ANGLE to PropertyMetadata("Rear bicycle model steering angle (\u00B0)"),
        VehicleProperty.ENGINE_COOLANT_TEMP to PropertyMetadata("Engine coolant temperature (\u00B0C)"),
        VehicleProperty.ENGINE_OIL_LEVEL to PropertyMetadata("Engine oil level", listOf("oilLevel")),
        VehicleProperty.ENGINE_OIL_TEMP to PropertyMetadata("Engine oil temperature (\u00B0C)"),
        VehicleProperty.ENGINE_RPM to PropertyMetadata("Engine RPM"),
        VehicleProperty.WHEEL_TICK to PropertyMetadata("Wheel ticks"),
        VehicleProperty.FUEL_LEVEL to PropertyMetadata("Fuel level (mL)"),
        VehicleProperty.FUEL_DOOR_OPEN to PropertyMetadata("Fuel door open"),
        VehicleProperty.EV_BATTERY_LEVEL to PropertyMetadata("EV battery level (Wh)"),
        VehicleProperty.EV_CHARGE_PORT_OPEN to PropertyMetadata("EV charge port open"),
        VehicleProperty.EV_CHARGE_PORT_CONNECTED to PropertyMetadata("EV charge port connected"),
        VehicleProperty.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE to PropertyMetadata("EV instantaneous charge rate (mW)"),
        VehicleProperty.RANGE_REMAINING to PropertyMetadata("Range remaining (m)"),
        VehicleProperty.TIRE_PRESSURE to PropertyMetadata("Tire pressure (kPa)"),
        VehicleProperty.CRITICALLY_LOW_TIRE_PRESSURE to PropertyMetadata("Critically low tire pressure"),
        VehicleProperty.GEAR_SELECTION to PropertyMetadata("Selected gear", listOf("gear")),
        VehicleProperty.CURRENT_GEAR to PropertyMetadata("Current gear", listOf("gear")),
        VehicleProperty.PARKING_BRAKE_ON to PropertyMetadata("Parking brake on"),
        VehicleProperty.PARKING_BRAKE_AUTO_APPLY to PropertyMetadata("Parking brake auto apply on"),
        VehicleProperty.FUEL_LEVEL_LOW to PropertyMetadata("Fuel level low"),
        VehicleProperty.NIGHT_MODE to PropertyMetadata("Night mode on"),
        VehicleProperty.TURN_SIGNAL_STATE to PropertyMetadata("Turn signal state", listOf("turnSignal")),
        VehicleProperty.IGNITION_STATE to PropertyMetadata("Ignition state", listOf("ignitionState")),
        VehicleProperty.ABS_ACTIVE to PropertyMetadata("ABS active"),
        VehicleProperty.TRACTION_CONTROL_ACTIVE to PropertyMetadata("Traction control active"),
        VehicleProperty.HVAC_FAN_SPEED to PropertyMetadata("Fan speed"),
        VehicleProperty.HVAC_FAN_DIRECTION to
          PropertyMetadata("Fan direction", listOf("fanDirection"), int32Formatter = ::fanDirectionToString),
        VehicleProperty.HVAC_TEMPERATURE_CURRENT to PropertyMetadata("Current temperature (\u00B0C)"),
        VehicleProperty.HVAC_TEMPERATURE_SET to PropertyMetadata("Target temperature set (\u00B0C)"),
        VehicleProperty.HVAC_DEFROSTER to PropertyMetadata("Defroster on"),
        VehicleProperty.HVAC_AC_ON to PropertyMetadata("AC on"),
        VehicleProperty.HVAC_MAX_AC_ON to PropertyMetadata("Max AC on"),
        VehicleProperty.HVAC_MAX_DEFROST_ON to PropertyMetadata("Max defrost on"),
        VehicleProperty.HVAC_RECIRC_ON to PropertyMetadata("Recirculation on"),
        VehicleProperty.HVAC_DUAL_ON to PropertyMetadata("Temperature coupling enabled"),
        VehicleProperty.HVAC_AUTO_ON to PropertyMetadata("Automatic mode on"),
        VehicleProperty.HVAC_SEAT_TEMPERATURE to PropertyMetadata("Seat heating/cooling", int32Formatter = ::heatingCoolingToString),
        VehicleProperty.HVAC_SIDE_MIRROR_HEAT to PropertyMetadata("Side mirror heat"),
        VehicleProperty.HVAC_STEERING_WHEEL_HEAT to
          PropertyMetadata("Steering wheel heating/cooling", int32Formatter = ::heatingCoolingToString),
        VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS to PropertyMetadata("User display temperature units", listOf("tempUnits")),
        VehicleProperty.HVAC_ACTUAL_FAN_SPEED_RPM to PropertyMetadata("Actual fan speed"),
        VehicleProperty.HVAC_POWER_ON to PropertyMetadata("Global power state"),
        VehicleProperty.HVAC_FAN_DIRECTION_AVAILABLE to
          PropertyMetadata("Available fan positions", listOf("fanDirection"), int32Formatter = ::fanDirectionToString),
        VehicleProperty.HVAC_AUTO_RECIRC_ON to PropertyMetadata("Automatic recirculation on"),
        VehicleProperty.HVAC_SEAT_VENTILATION to PropertyMetadata("Seat ventilation", int32Formatter = ::heatingCoolingToString),
        VehicleProperty.HVAC_ELECTRIC_DEFROSTER_ON to PropertyMetadata("Electric defroster"),
        VehicleProperty.HVAC_TEMPERATURE_VALUE_SUGGESTION to PropertyMetadata("Suggested temperature set (\u00B0C)"),
        VehicleProperty.DISTANCE_DISPLAY_UNITS to PropertyMetadata("Distance units for display"),
        VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS to PropertyMetadata("Fuel volume units for display"),
        VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS to PropertyMetadata("Tire pressure units for display"),
        VehicleProperty.EV_BATTERY_DISPLAY_UNITS to PropertyMetadata("EV battery units for display"),
        VehicleProperty.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME to PropertyMetadata("Fuel consumption units for display"),
        VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS to PropertyMetadata("Speed units for display"),
        VehicleProperty.EXTERNAL_CAR_TIME to PropertyMetadata("Current time in car (Epoch)"),
        VehicleProperty.ANDROID_EPOCH_TIME to PropertyMetadata("Current time in Android (Epoch)"),
        VehicleProperty.STORAGE_ENCRYPTION_BINDING_SEED to PropertyMetadata("External encryption binding seed"),
        VehicleProperty.ENV_OUTSIDE_TEMPERATURE to PropertyMetadata("Outside temperature (\u00B0C)"),
        VehicleProperty.AP_POWER_STATE_REQ to
          PropertyMetadata("App processor power state control", int32VecFormatter = ::apPowerStateReqToString),
        VehicleProperty.AP_POWER_STATE_REPORT to
          PropertyMetadata("App processor power state report", int32VecFormatter = ::apPowerStateReportToString),
        VehicleProperty.AP_POWER_BOOTUP_REASON to PropertyMetadata("Bootup reason for power on"),
        VehicleProperty.DISPLAY_BRIGHTNESS to PropertyMetadata("Display brightness"),
        VehicleProperty.HW_KEY_INPUT to PropertyMetadata("H/W input key"),
        VehicleProperty.HW_ROTARY_INPUT to PropertyMetadata("H/W rotary events"),
        VehicleProperty.HW_CUSTOM_INPUT to PropertyMetadata("Custom OEM partner input"),
        VehicleProperty.DOOR_POS to PropertyMetadata("Door position"),
        VehicleProperty.DOOR_MOVE to PropertyMetadata("Door move"),
        VehicleProperty.DOOR_LOCK to PropertyMetadata("Door locked"),
        VehicleProperty.MIRROR_Z_POS to PropertyMetadata("Mirror Z position"),
        VehicleProperty.MIRROR_Z_MOVE to PropertyMetadata("Mirror Z move"),
        VehicleProperty.MIRROR_Y_POS to PropertyMetadata("Mirror Y position"),
        VehicleProperty.MIRROR_Y_MOVE to PropertyMetadata("Mirror Y move"),
        VehicleProperty.MIRROR_LOCK to PropertyMetadata("Mirror locked"),
        VehicleProperty.MIRROR_FOLD to PropertyMetadata("Mirror folded"),
        VehicleProperty.SEAT_MEMORY_SELECT to PropertyMetadata("Seat selection memory preset"),
        VehicleProperty.SEAT_MEMORY_SET to PropertyMetadata("Seat memory set"),
        VehicleProperty.SEAT_BELT_BUCKLED to PropertyMetadata("Seat belt buckled"),
        VehicleProperty.SEAT_BELT_HEIGHT_POS to PropertyMetadata("Seat belt height"),
        VehicleProperty.SEAT_BELT_HEIGHT_MOVE to PropertyMetadata("Seat belt height move"),
        VehicleProperty.SEAT_FORE_AFT_POS to PropertyMetadata("Seat forward/backward position"),
        VehicleProperty.SEAT_FORE_AFT_MOVE to PropertyMetadata("Seat forward/backward move"),
        VehicleProperty.SEAT_BACKREST_ANGLE_1_POS to PropertyMetadata("Backrest angle 1"),
        VehicleProperty.SEAT_BACKREST_ANGLE_1_MOVE to PropertyMetadata("Backrest angle 1 move"),
        VehicleProperty.SEAT_BACKREST_ANGLE_2_POS to PropertyMetadata("Backrest angle 2"),
        VehicleProperty.SEAT_BACKREST_ANGLE_2_MOVE to PropertyMetadata("Backrest angle 2 move"),
        VehicleProperty.SEAT_HEIGHT_POS to PropertyMetadata("Seat height"),
        VehicleProperty.SEAT_HEIGHT_MOVE to PropertyMetadata("Seat height move"),
        VehicleProperty.SEAT_DEPTH_POS to PropertyMetadata("Seat depth"),
        VehicleProperty.SEAT_DEPTH_MOVE to PropertyMetadata("Seat depth move"),
        VehicleProperty.SEAT_TILT_POS to PropertyMetadata("Seat tilt"),
        VehicleProperty.SEAT_TILT_MOVE to PropertyMetadata("Seat tilt move"),
        VehicleProperty.SEAT_LUMBAR_FORE_AFT_POS to PropertyMetadata("Lumbar forward/backward position"),
        VehicleProperty.SEAT_LUMBAR_FORE_AFT_MOVE to PropertyMetadata("Lumbar forward/backward move"),
        VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_POS to PropertyMetadata("Lumbar side support"),
        VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_MOVE to PropertyMetadata("Lumbar side support move"),
        VehicleProperty.SEAT_HEADREST_HEIGHT_POS to PropertyMetadata("Headrest height"),
        VehicleProperty.SEAT_HEADREST_HEIGHT_MOVE to PropertyMetadata("Headrest height move"),
        VehicleProperty.SEAT_HEADREST_ANGLE_POS to PropertyMetadata("Headrest angle"),
        VehicleProperty.SEAT_HEADREST_ANGLE_MOVE to PropertyMetadata("Headrest angle move"),
        VehicleProperty.SEAT_HEADREST_FORE_AFT_POS to PropertyMetadata("Headrest forward/backward position"),
        VehicleProperty.SEAT_HEADREST_FORE_AFT_MOVE to PropertyMetadata("Headrest forward/backward move"),
        VehicleProperty.SEAT_OCCUPANCY to PropertyMetadata("Seat occupancy", listOf("seatOccupancy")),
        VehicleProperty.WINDOW_POS to PropertyMetadata("Window position"),
        VehicleProperty.WINDOW_MOVE to PropertyMetadata("Window move"),
        VehicleProperty.WINDOW_LOCK to PropertyMetadata("Window locked"),
        VehicleProperty.VEHICLE_MAP_SERVICE to PropertyMetadata("Vehicle maps service msg"),
        VehicleProperty.OBD2_LIVE_FRAME to PropertyMetadata("OBD2 live sensor data"),
        VehicleProperty.OBD2_FREEZE_FRAME to PropertyMetadata("OBD2 freeze frame sensor data"),
        VehicleProperty.OBD2_FREEZE_FRAME_INFO to PropertyMetadata("OBD2 freeze frame info"),
        VehicleProperty.OBD2_FREEZE_FRAME_CLEAR to PropertyMetadata("OBD2 freeze frame clear"),
        VehicleProperty.HEADLIGHTS_STATE to PropertyMetadata("Headlights state", listOf("lightState")),
        VehicleProperty.HIGH_BEAM_LIGHTS_STATE to PropertyMetadata("High beam lights state", listOf("lightState")),
        VehicleProperty.FOG_LIGHTS_STATE to PropertyMetadata("Fog lights state", listOf("lightState")),
        VehicleProperty.HAZARD_LIGHTS_STATE to PropertyMetadata("Hazard lights state", listOf("lightState")),
        VehicleProperty.HEADLIGHTS_SWITCH to PropertyMetadata("Headlights switch", listOf("lightSwitch")),
        VehicleProperty.HIGH_BEAM_LIGHTS_SWITCH to PropertyMetadata("High beam lights switch", listOf("lightSwitch")),
        VehicleProperty.FOG_LIGHTS_SWITCH to PropertyMetadata("Fog lights switch", listOf("lightSwitch")),
        VehicleProperty.HAZARD_LIGHTS_SWITCH to PropertyMetadata("Hazard lights switch", listOf("lightSwitch")),
        VehicleProperty.CABIN_LIGHTS_STATE to PropertyMetadata("Cabin lights", listOf("lightState")),
        VehicleProperty.CABIN_LIGHTS_SWITCH to PropertyMetadata("Cabin lights switch", listOf("lightSwitch")),
        VehicleProperty.READING_LIGHTS_STATE to PropertyMetadata("Reading lights", listOf("lightState")),
        VehicleProperty.READING_LIGHTS_SWITCH to PropertyMetadata("Reading lights switch", listOf("lightSwitch")),
        VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION to PropertyMetadata("Support customize permissions for vendor properties"),
        VehicleProperty.DISABLED_OPTIONAL_FEATURES to PropertyMetadata("Allow disabling optional features from VHAL"),
        VehicleProperty.INITIAL_USER_INFO to PropertyMetadata("Android user during initialization"),
        VehicleProperty.SWITCH_USER to PropertyMetadata("Request to switch the foreground Android user"),
        VehicleProperty.CREATE_USER to PropertyMetadata("Android user was created"),
        VehicleProperty.REMOVE_USER to PropertyMetadata("Android user was removed"),
        VehicleProperty.USER_IDENTIFICATION_ASSOCIATION to
          PropertyMetadata("Associate the current user with vehicle-specific identification"),
        VehicleProperty.EVS_SERVICE_REQUEST to PropertyMetadata("Enable/request an EVS service"),
        VehicleProperty.POWER_POLICY_REQ to PropertyMetadata("Request to apply power policy"),
        VehicleProperty.POWER_POLICY_GROUP_REQ to PropertyMetadata("Request to set the power policy group"),
        VehicleProperty.CURRENT_POWER_POLICY to PropertyMetadata("Current power policy"),
        VehicleProperty.WATCHDOG_ALIVE to PropertyMetadata("Event telling the car watchdog alive"),
        VehicleProperty.WATCHDOG_TERMINATED_PROCESS to PropertyMetadata("Process terminated by car watchdog and the reason"),
        VehicleProperty.VHAL_HEARTBEAT to PropertyMetadata("Event VHAL signals to car watchdog as a heartbeat"),
        VehicleProperty.CLUSTER_SWITCH_UI to PropertyMetadata("Starts the ClusterUI"),
        VehicleProperty.CLUSTER_DISPLAY_STATE to PropertyMetadata("Changes the state of the cluster display"),
        VehicleProperty.CLUSTER_REPORT_STATE to PropertyMetadata("Reports the current display state and ClusterUI state"),
        VehicleProperty.CLUSTER_REQUEST_DISPLAY to PropertyMetadata("Requests to change the cluster display state to show some ClusterUI"),
        VehicleProperty.CLUSTER_NAVIGATION_STATE to PropertyMetadata("Current cluster navigation state"),
        VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE to PropertyMetadata("Electronic Toll Collection card type"),
        VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS to PropertyMetadata("Electronic Toll Collection card status"),
        VehicleProperty.FRONT_FOG_LIGHTS_STATE to PropertyMetadata("Front fog lights state", listOf("lightState")),
        VehicleProperty.FRONT_FOG_LIGHTS_SWITCH to PropertyMetadata("Front fog lights switch", listOf("lightSwitch")),
        VehicleProperty.REAR_FOG_LIGHTS_STATE to PropertyMetadata("Rear fog lights state", listOf("lightState")),
        VehicleProperty.REAR_FOG_LIGHTS_SWITCH to PropertyMetadata("Rear fog lights switch", listOf("lightSwitch")),
        VehicleProperty.EV_CHARGE_CURRENT_DRAW_LIMIT to PropertyMetadata("EV maximum current draw threshold for charging set by the user"),
        VehicleProperty.EV_CHARGE_PERCENT_LIMIT to PropertyMetadata("EV maximum charge percent threshold set by the user"),
        VehicleProperty.EV_CHARGE_STATE to PropertyMetadata("EV charging state"),
        VehicleProperty.EV_CHARGE_SWITCH to PropertyMetadata("EV start or stop charging the battery"),
        VehicleProperty.EV_CHARGE_TIME_REMAINING to PropertyMetadata("EV estimated charge time remaining (seconds)"),
        VehicleProperty.EV_REGENERATIVE_BRAKING_STATE to PropertyMetadata("EV regenerative braking or one-pedal drive state"),
        VehicleProperty.TRAILER_PRESENT to PropertyMetadata("Trailer present or not"),
        VehicleProperty.VEHICLE_CURB_WEIGHT to PropertyMetadata("Vehicle curb weight"),
        VehicleProperty.GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT to
          PropertyMetadata("EU General Safety Regulation (GSR) compliance requirement", listOf("gsrComplianceReq")),
      )

    val DEFAULT: VhalPropertyDictionary = VhalPropertyDictionary(BUILT_IN_PROPERTIES, BUILT_IN_LOOKUP_TABLES)

    // TODO: Replace reading of *types-meta.json files by accessing this information through gRPC once available (b/579958152).
    /** Loads any `*types-meta.json` files from [directories] and merges them with the built-in VHAL dictionary. */
    fun loadFromDirectories(directories: Iterable<Path?>): VhalPropertyDictionary {
      val jsonFiles = mutableListOf<Path>()
      for (dir in directories.filterNotNull().distinct()) {
        try {
          if (!Files.isDirectory(dir)) continue
          Files.list(dir).use { stream ->
            stream
              .filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) && it.name.endsWith("types-meta.json") }
              .sorted()
              .forEach(jsonFiles::add)
          }
        } catch (e: Exception) {
          e.throwIfCancellation()
          LOG.warn("Unable to scan directory for VHAL metadata: $dir", e)
        }
      }
      if (jsonFiles.isEmpty()) {
        return DEFAULT
      }

      val mergedProps = LinkedHashMap(BUILT_IN_PROPERTIES)
      val mergedTables = LinkedHashMap(BUILT_IN_LOOKUP_TABLES)
      for (file in jsonFiles) {
        loadFromJsonFile(file, mergedProps, mergedTables)
      }
      return VhalPropertyDictionary(mergedProps, mergedTables)
    }

    private fun loadFromJsonFile(
      file: Path,
      mergedProps: MutableMap<Int, PropertyMetadata>,
      mergedTables: MutableMap<String, Map<Int, String>>,
    ) {
      try {
        val root = JsonParser.parseString(file.readText())
        if (!root.isJsonArray) return
        for (element in root.asJsonArray) {
          if (!element.isJsonObject) continue
          val enumObj = element.asJsonObject
          val enumName = enumObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
          val valuesArray = enumObj.get("values")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
          if (enumName == "VehicleProperty") {
            for (propEl in valuesArray) {
              if (!propEl.isJsonObject) continue
              val propObj = propEl.asJsonObject
              val propId = propObj.get("value")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
              val jsonName = propObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
              val description = propObj.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
              val dataEnums = mutableListOf<String>()
              propObj
                .get("data_enums")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.forEach { item ->
                  if (item.isJsonPrimitive) dataEnums.add(item.asString)
                }
              if (dataEnums.isEmpty()) {
                propObj.get("data_enum")?.takeIf { it.isJsonPrimitive }?.asString?.let(dataEnums::add)
              }
              val valueType = propId and VehiclePropertyType.MASK
              val jsonLookupTables =
                if (valueType == VehiclePropertyType.INT32 || valueType == VehiclePropertyType.INT32_VEC) dataEnums else emptyList()
              val existing = mergedProps[propId]
              mergedProps[propId] =
                PropertyMetadata(
                  label = existing?.label?.takeIf { it.isNotEmpty() } ?: jsonName,
                  lookupTableNames = existing?.lookupTableNames?.ifEmpty { jsonLookupTables } ?: jsonLookupTables,
                  int32Formatter = existing?.int32Formatter,
                  int32VecFormatter = existing?.int32VecFormatter,
                  detailedDescription = description.ifEmpty { existing?.detailedDescription.orEmpty() },
                )
            }
          } else if (!mergedTables.containsKey(enumName)) {
            val enumMap = LinkedHashMap<Int, String>()
            for (valEl in valuesArray) {
              if (!valEl.isJsonObject) continue
              val valObj = valEl.asJsonObject
              val intVal = valObj.get("value")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
              val valName = valObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
              enumMap[intVal] = valName
            }
            if (enumMap.isNotEmpty()) {
              mergedTables[enumName] = enumMap
            }
          }
        }
      } catch (e: Exception) {
        e.throwIfCancellation()
        LOG.warn("Unable to read VHAL metadata file: $file", e)
      }
    }

    private fun multiDetailToString(lookupTable: Map<Int, String>, value: Int): String {
      if (value == 0) {
        return lookupTable[0].orEmpty()
      }
      return lookupTable.entries.filter { (bit, _) -> bit != 0 && (value and bit) == bit }.joinToString(", ") { it.value }
    }

    private fun fanDirectionToString(value: Int): String {
      return multiDetailToString(FAN_DIRECTION_MAP, value).ifEmpty { value.toString() }
    }

    private fun heatingCoolingToString(value: Int): String {
      val behavior =
        when {
          value < 0 -> "Cooling"
          value == 0 -> "Off"
          else -> "On"
        }
      return "$value ($behavior)"
    }

    private fun apPowerStateReqToString(values: List<Int>): String {
      if (values.isEmpty()) return "N/A"
      val stateText = AP_POWER_STATE_REQ_MAP[values[0]] ?: values[0].toString()
      if (values[0] == VehicleApPowerStateReq.SHUTDOWN_PREPARE && values.size >= 2) {
        val paramText = AP_POWER_STATE_SHUTDOWN_MAP[values[1]]
        if (paramText != null) {
          return "$stateText ($paramText)"
        }
      }
      return stateText
    }

    private fun apPowerStateReportToString(values: List<Int>): String {
      if (values.isEmpty()) return "N/A"
      val stateText = AP_POWER_STATE_REPORT_MAP[values[0]] ?: values[0].toString()
      if (values.size >= 2) {
        when (values[0]) {
          VehicleApPowerStateReport.SHUTDOWN_POSTPONE -> return "$stateText (Time: ${values[1]} ms)"
          VehicleApPowerStateReport.SHUTDOWN_START -> return "$stateText (Time to turn on AP: ${values[1]} s)"
        }
      }
      return stateText
    }

    fun formatFloat(value: Float): String {
      val clean = if (value == -0.0f) 0.0f else value
      return if (clean == clean.toLong().toFloat()) {
        String.format(Locale.US, "%.1f", clean).cleanNegativeZero()
      } else {
        clean.toString().cleanNegativeZero()
      }
    }
  }
}
