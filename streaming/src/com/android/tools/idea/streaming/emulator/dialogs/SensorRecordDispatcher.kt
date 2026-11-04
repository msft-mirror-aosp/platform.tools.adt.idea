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
import com.android.emulator.control.GpsState
import com.android.emulator.control.ParameterValue
import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto.PositionProvider
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.emulator.control.SensorValue
import com.android.emulator.control.SensorValue.SensorType
import com.android.tools.idea.streaming.emulator.EmulatorController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Converts a [SensorRecord] into emulator gRPC requests (VHAL car events, GPS state, and physical sensor values) and sends them. */
internal suspend fun sendSensorRecordToEmulator(emulator: EmulatorController, record: SensorRecord, metadata: SessionMetadata) {
  val carEvents =
    if (record.carPropertyValuesCount > 0) {
      record.carPropertyValuesMap.entries
        .sortedBy { it.key }
        .map { (propId, carValue) ->
          val emulatorMsg =
            EmulatorMessage.newBuilder()
              .setMsgType(MsgType.SET_PROPERTY_CMD)
              .setStatus(Status.RESULT_OK)
              .addValue(convertCarPropertyValue(propId, carValue))
              .build()
          CarEvent.newBuilder().setData(emulatorMsg.toByteString()).build()
        }
    } else {
      emptyList()
    }

  val gpsState =
    if (record.locationsCount > 0) {
      record.locationsList
        .firstOrNull { it.hasGpsPoint() }
        ?.let { gpsPosition ->
          val point = gpsPosition.point
          GpsState.newBuilder()
            .setPassiveUpdate(true)
            .setLatitude(point.latE7 / E7)
            .setLongitude(point.lngE7 / E7)
            .setAltitude(gpsPosition.altitudeM)
            .setSpeed(gpsPosition.speedMps.toDouble() * MPS_TO_KNOTS)
            .setBearing(gpsPosition.bearingDegFullAccuracy.toDouble())
            .setSatellites(DEFAULT_SATELLITES)
            .build()
        }
    } else {
      null
    }

  val sensorValues =
    if (record.sensorEventsCount > 0) {
      val byType = LinkedHashMap<SensorType, SensorValue>()
      for ((sensorKey, sensorEvent) in record.sensorEventsMap.entries.sortedBy { it.key }) {
        val sensorMeta = metadata.subscribedSensorsMap[sensorKey] ?: continue
        val (sensorType, valueCount) = mapAndroidSensorType(sensorMeta.type) ?: continue
        if (sensorEvent.valuesCount < valueCount) continue
        byType[sensorType] =
          SensorValue.newBuilder()
            .setTarget(sensorType)
            .setValue(ParameterValue.newBuilder().addAllData(sensorEvent.valuesList.take(valueCount)))
            .build()
      }
      byType.values.toList()
    } else {
      emptyList()
    }

  val totalCalls = carEvents.size + (if (gpsState != null) 1 else 0) + sensorValues.size
  if (totalCalls == 0) return
  if (totalCalls == 1) {
    when {
      carEvents.isNotEmpty() -> emulator.sendCarEvent(carEvents[0])
      gpsState != null -> emulator.setGps(gpsState)
      else -> emulator.setSensor(sensorValues[0])
    }
    return
  }

  coroutineScope {
    for (carEvent in carEvents) {
      launch { emulator.sendCarEvent(carEvent) }
    }
    if (gpsState != null) {
      launch { emulator.setGps(gpsState) }
    }
    for (sensorValue in sensorValues) {
      launch { emulator.setSensor(sensorValue) }
    }
  }
}

private data class EmulatorSensorSpec(val sensorType: SensorType, val valueCount: Int)

private const val E7 = 1e7
private const val MPS_TO_KNOTS = 1.94384
private const val DEFAULT_SATELLITES = 4

// Android framework hardware.Sensor TYPE_* constants.
private const val ANDROID_SENSOR_TYPE_ACCELEROMETER = 1
private const val ANDROID_SENSOR_TYPE_MAGNETIC_FIELD = 2
private const val ANDROID_SENSOR_TYPE_ORIENTATION = 3
private const val ANDROID_SENSOR_TYPE_GYROSCOPE = 4
private const val ANDROID_SENSOR_TYPE_LIGHT = 5
private const val ANDROID_SENSOR_TYPE_PRESSURE = 6
private const val ANDROID_SENSOR_TYPE_PROXIMITY = 8
private const val ANDROID_SENSOR_TYPE_RELATIVE_HUMIDITY = 12
private const val ANDROID_SENSOR_TYPE_AMBIENT_TEMPERATURE = 13
private const val ANDROID_SENSOR_TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14
private const val ANDROID_SENSOR_TYPE_GYROSCOPE_UNCALIBRATED = 16
private const val ANDROID_SENSOR_TYPE_HEART_RATE = 21
private const val ANDROID_SENSOR_TYPE_ACCELEROMETER_UNCALIBRATED = 35
private const val ANDROID_SENSOR_TYPE_HEADING = 42

private fun mapAndroidSensorType(androidType: Int): EmulatorSensorSpec? =
  when (androidType) {
    ANDROID_SENSOR_TYPE_ACCELEROMETER -> EmulatorSensorSpec(SensorType.ACCELERATION, 3)
    ANDROID_SENSOR_TYPE_MAGNETIC_FIELD -> EmulatorSensorSpec(SensorType.MAGNETIC_FIELD, 3)
    ANDROID_SENSOR_TYPE_ORIENTATION -> EmulatorSensorSpec(SensorType.ORIENTATION, 3)
    ANDROID_SENSOR_TYPE_GYROSCOPE -> EmulatorSensorSpec(SensorType.GYROSCOPE, 3)
    ANDROID_SENSOR_TYPE_LIGHT -> EmulatorSensorSpec(SensorType.LIGHT, 1)
    ANDROID_SENSOR_TYPE_PRESSURE -> EmulatorSensorSpec(SensorType.PRESSURE, 1)
    ANDROID_SENSOR_TYPE_PROXIMITY -> EmulatorSensorSpec(SensorType.PROXIMITY, 1)
    ANDROID_SENSOR_TYPE_RELATIVE_HUMIDITY -> EmulatorSensorSpec(SensorType.HUMIDITY, 1)
    ANDROID_SENSOR_TYPE_AMBIENT_TEMPERATURE -> EmulatorSensorSpec(SensorType.TEMPERATURE, 1)
    ANDROID_SENSOR_TYPE_MAGNETIC_FIELD_UNCALIBRATED -> EmulatorSensorSpec(SensorType.MAGNETIC_FIELD_UNCALIBRATED, 3)
    ANDROID_SENSOR_TYPE_GYROSCOPE_UNCALIBRATED -> EmulatorSensorSpec(SensorType.GYROSCOPE_UNCALIBRATED, 3)
    ANDROID_SENSOR_TYPE_HEART_RATE -> EmulatorSensorSpec(SensorType.HEART_RATE, 1)
    ANDROID_SENSOR_TYPE_ACCELEROMETER_UNCALIBRATED -> EmulatorSensorSpec(SensorType.ACCELERATION_UNCALIBRATED, 3)
    ANDROID_SENSOR_TYPE_HEADING -> EmulatorSensorSpec(SensorType.HEADING, 1)
    else -> null
  }

private fun convertCarPropertyValue(propId: Int, valProto: SensorRecord.CarPropertyValue): VehiclePropValue {
  val status =
    if (valProto.hasStatus()) {
      VehiclePropStatus.forNumber(valProto.status) ?: VehiclePropStatus.AVAILABLE
    } else {
      VehiclePropStatus.AVAILABLE
    }
  val builder = VehiclePropValue.newBuilder().setProp(propId).setStatus(status)
  if (valProto.hasAreaId()) {
    builder.areaId = valProto.areaId
  }
  if (valProto.hasBoolValue()) {
    builder.addInt32Values(if (valProto.boolValue) 1 else 0)
  }
  builder.addAllInt32Values(valProto.int32ValuesList)
  builder.addAllInt64Values(valProto.int64ValuesList)
  builder.addAllFloatValues(valProto.floatValuesList)
  if (valProto.hasStringValue()) {
    builder.stringValue = valProto.stringValue
  }
  if (valProto.hasBytesValue()) {
    builder.bytesValue = valProto.bytesValue
  }
  return builder.build()
}

internal fun PositionProto.hasGpsPoint(): Boolean =
  (provider == PositionProvider.GPS || provider == PositionProvider.GPS_INJECTED) && hasPoint()
