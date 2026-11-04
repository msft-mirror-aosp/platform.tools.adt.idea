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

import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SensorRecord.CarPropertyValue
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto.PointProto
import com.android.emulator.control.SensorSession.SensorRecord.PositionProto.PositionProvider
import com.android.emulator.control.SensorSession.SensorRecord.SensorEvent
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.tools.idea.protobuf.CodedInputStream
import com.android.tools.idea.protobuf.InvalidProtocolBufferException
import com.android.tools.idea.protobuf.WireFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Streams a binary wire-format `SensorSession` file over a [SeekableChannelInputStream], selectively decoding only the fields used by
 * sensor replay while skipping large unused payloads (such as camera frames in `SensorRecord.image` or unknown fields) in `O(1)` without
 * allocating byte arrays or `UnknownFieldSet` entries.
 */
internal object SensorSessionBinaryProtoLoader {
  // Wire tags for SensorSession
  private const val SESSION_METADATA_TAG = 1 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val SENSOR_RECORDS_TAG = 2 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  // Wire tags for SessionMetadata
  private const val METADATA_VERSION_TAG = 2 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val METADATA_CAR_PROPERTY_CONFIGS_TAG = 6 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val METADATA_SUBSCRIBED_SENSORS_TAG = 7 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  // Wire tags for SessionMetadata.Version
  private const val VERSION_MAJOR_TAG = 1 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val VERSION_MINOR_TAG = 2 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val VERSION_PATCH_TAG = 3 shl 3 or WireFormat.WIRETYPE_VARINT

  // Wire tags for SessionMetadata.Sensor
  private const val SENSOR_NAME_TAG = 1 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val SENSOR_TYPE_TAG = 5 shl 3 or WireFormat.WIRETYPE_VARINT

  // Wire tags for protobuf map entries
  private const val MAP_INT32_KEY_TAG = 1 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val MAP_STRING_KEY_TAG = 1 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val MAP_MESSAGE_VALUE_TAG = 2 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  // Wire tags for SensorRecord
  private const val RECORD_TIMESTAMP_NS_TAG = 1 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val RECORD_LOCATIONS_TAG = 2 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val RECORD_CAR_PROPERTY_VALUES_TAG = 3 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val RECORD_SENSOR_EVENTS_TAG = 5 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  // Wire tags for SensorRecord.PositionProto and PointProto
  private const val POSITION_POINT_TAG = 1 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val POSITION_SPEED_MPS_TAG = 5 shl 3 or WireFormat.WIRETYPE_FIXED32
  private const val POSITION_ALTITUDE_M_TAG = 6 shl 3 or WireFormat.WIRETYPE_FIXED64
  private const val POSITION_PROVIDER_TAG = 8 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val POSITION_BEARING_DEG_TAG = 9 shl 3 or WireFormat.WIRETYPE_FIXED32
  private const val POINT_LAT_E7_TAG = 1 shl 3 or WireFormat.WIRETYPE_FIXED32
  private const val POINT_LNG_E7_TAG = 2 shl 3 or WireFormat.WIRETYPE_FIXED32

  // Wire tags for SensorRecord.CarPropertyValue
  private const val CAR_PROP_AREA_ID_TAG = 1 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val CAR_PROP_STATUS_TAG = 2 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val CAR_PROP_BOOL_VALUE_TAG = 3 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val CAR_PROP_INT32_VALUES_TAG = 4 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val CAR_PROP_INT32_VALUES_PACKED_TAG = 4 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val CAR_PROP_INT64_VALUES_TAG = 5 shl 3 or WireFormat.WIRETYPE_VARINT
  private const val CAR_PROP_INT64_VALUES_PACKED_TAG = 5 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val CAR_PROP_FLOAT_VALUES_TAG = 6 shl 3 or WireFormat.WIRETYPE_FIXED32
  private const val CAR_PROP_FLOAT_VALUES_PACKED_TAG = 6 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val CAR_PROP_STRING_VALUE_TAG = 7 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED
  private const val CAR_PROP_BYTES_VALUE_TAG = 8 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  // Wire tags for SensorRecord.SensorEvent
  private const val SENSOR_EVENT_VALUES_TAG = 1 shl 3 or WireFormat.WIRETYPE_FIXED32
  private const val SENSOR_EVENT_VALUES_PACKED_TAG = 1 shl 3 or WireFormat.WIRETYPE_LENGTH_DELIMITED

  fun loadBinarySensorSession(
    file: Path,
    maxInMemoryBytes: Int,
    checkpointIntervalBytes: Int,
    maxMergeFanIn: Int,
    checkCancelled: () -> Unit,
  ): SensorSessionItem {
    return SensorSessionAccumulator(maxInMemoryBytes, checkpointIntervalBytes, maxMergeFanIn, checkCancelled).use { accumulator ->
      Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
        val input = CodedInputStream.newInstance(SeekableChannelInputStream(channel), IO_BUFFER_SIZE)
        input.setSizeLimit(Int.MAX_VALUE)

        while (true) {
          checkCancelled()
          input.resetSizeCounter()
          val tag = input.readTag()
          if (tag == 0) break

          when (tag) {
            SESSION_METADATA_TAG -> {
              accumulator.markSessionMetadataPresent()
              parseSessionMetadata(input, accumulator.metadataBuilder)
            }
            SENSOR_RECORDS_TAG -> accumulator.addRecord(parseSensorRecord(input))
            else -> skipFieldOrThrow(input, tag)
          }
        }
        input.checkLastTagWas(0)
      }
      accumulator.buildSessionItem(file)
    }
  }

  private fun parseSessionMetadata(input: CodedInputStream, builder: SessionMetadata.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        METADATA_VERSION_TAG -> parseVersion(input, builder.versionBuilder)
        METADATA_CAR_PROPERTY_CONFIGS_TAG -> {
          var propId = 0
          forEachTagInMessage(input) { entryTag ->
            when (entryTag) {
              MAP_INT32_KEY_TAG -> propId = input.readInt32()
              else -> skipFieldOrThrow(input, entryTag)
            }
          }
          builder.putCarPropertyConfigs(propId, SessionMetadata.CarPropertyConfig.getDefaultInstance())
        }
        METADATA_SUBSCRIBED_SENSORS_TAG -> {
          var sensorKey = ""
          val sensorBuilder = SessionMetadata.Sensor.newBuilder()
          forEachTagInMessage(input) { entryTag ->
            when (entryTag) {
              MAP_STRING_KEY_TAG -> sensorKey = input.readString()
              MAP_MESSAGE_VALUE_TAG -> parseSubscribedSensor(input, sensorBuilder)
              else -> skipFieldOrThrow(input, entryTag)
            }
          }
          builder.putSubscribedSensors(sensorKey, sensorBuilder.build())
        }
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private fun parseVersion(input: CodedInputStream, builder: SessionMetadata.Version.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        VERSION_MAJOR_TAG -> builder.major = input.readUInt32()
        VERSION_MINOR_TAG -> builder.minor = input.readUInt32()
        VERSION_PATCH_TAG -> builder.patch = input.readUInt32()
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private fun parseSubscribedSensor(input: CodedInputStream, builder: SessionMetadata.Sensor.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        SENSOR_NAME_TAG -> builder.name = input.readString()
        SENSOR_TYPE_TAG -> builder.type = input.readInt32()
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private fun parseSensorRecord(input: CodedInputStream): SensorRecord {
    val builder = SensorRecord.newBuilder()
    forEachTagInMessage(input) { tag ->
      when (tag) {
        RECORD_TIMESTAMP_NS_TAG -> builder.timestampNs = input.readInt64()
        RECORD_LOCATIONS_TAG -> builder.addLocations(parsePositionProto(input))
        RECORD_CAR_PROPERTY_VALUES_TAG -> {
          var propId = 0
          val valueBuilder = CarPropertyValue.newBuilder()
          forEachTagInMessage(input) { entryTag ->
            when (entryTag) {
              MAP_INT32_KEY_TAG -> propId = input.readInt32()
              MAP_MESSAGE_VALUE_TAG -> parseCarPropertyValue(input, valueBuilder)
              else -> skipFieldOrThrow(input, entryTag)
            }
          }
          builder.putCarPropertyValues(propId, valueBuilder.build())
        }
        RECORD_SENSOR_EVENTS_TAG -> {
          var sensorKey = ""
          val eventBuilder = SensorEvent.newBuilder()
          forEachTagInMessage(input) { entryTag ->
            when (entryTag) {
              MAP_STRING_KEY_TAG -> sensorKey = input.readString()
              MAP_MESSAGE_VALUE_TAG -> parseSensorEvent(input, eventBuilder)
              else -> skipFieldOrThrow(input, entryTag)
            }
          }
          builder.putSensorEvents(sensorKey, eventBuilder.build())
        }
        // SensorRecord.image (tag 4) and unknown fields (e.g. camera frames in tag 6+) are intentionally skipped.
        else -> skipFieldOrThrow(input, tag)
      }
    }
    return builder.build()
  }

  private fun parsePositionProto(input: CodedInputStream): PositionProto {
    val builder = PositionProto.newBuilder()
    forEachTagInMessage(input) { tag ->
      when (tag) {
        POSITION_POINT_TAG -> parsePointProto(input, builder.pointBuilder)
        POSITION_SPEED_MPS_TAG -> builder.speedMps = input.readFloat()
        POSITION_ALTITUDE_M_TAG -> builder.altitudeM = input.readDouble()
        POSITION_PROVIDER_TAG -> {
          val provider = PositionProvider.forNumber(input.readEnum())
          if (provider != null) {
            builder.provider = provider
          }
        }
        POSITION_BEARING_DEG_TAG -> builder.bearingDegFullAccuracy = input.readFloat()
        else -> skipFieldOrThrow(input, tag)
      }
    }
    return builder.build()
  }

  private fun parsePointProto(input: CodedInputStream, builder: PointProto.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        POINT_LAT_E7_TAG -> builder.latE7 = input.readFixed32()
        POINT_LNG_E7_TAG -> builder.lngE7 = input.readFixed32()
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private fun parseCarPropertyValue(input: CodedInputStream, builder: CarPropertyValue.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        CAR_PROP_AREA_ID_TAG -> builder.areaId = input.readInt32()
        CAR_PROP_STATUS_TAG -> builder.status = input.readInt32()
        CAR_PROP_BOOL_VALUE_TAG -> builder.boolValue = input.readBool()
        CAR_PROP_INT32_VALUES_TAG -> builder.addInt32Values(input.readSInt32())
        CAR_PROP_INT32_VALUES_PACKED_TAG -> readPackedElements(input) { builder.addInt32Values(input.readSInt32()) }
        CAR_PROP_INT64_VALUES_TAG -> builder.addInt64Values(input.readSInt64())
        CAR_PROP_INT64_VALUES_PACKED_TAG -> readPackedElements(input) { builder.addInt64Values(input.readSInt64()) }
        CAR_PROP_FLOAT_VALUES_TAG -> builder.addFloatValues(input.readFloat())
        CAR_PROP_FLOAT_VALUES_PACKED_TAG -> readPackedElements(input) { builder.addFloatValues(input.readFloat()) }
        CAR_PROP_STRING_VALUE_TAG -> builder.stringValue = input.readString()
        CAR_PROP_BYTES_VALUE_TAG -> builder.bytesValue = input.readBytes()
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private fun parseSensorEvent(input: CodedInputStream, builder: SensorEvent.Builder) {
    forEachTagInMessage(input) { tag ->
      when (tag) {
        SENSOR_EVENT_VALUES_TAG -> builder.addValues(input.readFloat())
        SENSOR_EVENT_VALUES_PACKED_TAG -> readPackedElements(input) { builder.addValues(input.readFloat()) }
        else -> skipFieldOrThrow(input, tag)
      }
    }
  }

  private inline fun forEachTagInMessage(input: CodedInputStream, onTag: (Int) -> Unit) {
    val length = input.readRawVarint32()
    if (length < 0 || input.totalBytesRead > Int.MAX_VALUE - length) {
      throw InvalidProtocolBufferException("Invalid submessage length: $length")
    }
    val oldLimit = input.pushLimit(length)
    while (true) {
      val tag = input.readTag()
      if (tag == 0) break
      onTag(tag)
    }
    input.checkLastTagWas(0)
    if (input.bytesUntilLimit != 0) {
      throw InvalidProtocolBufferException("Truncated sensor session message")
    }
    input.popLimit(oldLimit)
  }

  private inline fun readPackedElements(input: CodedInputStream, readOne: () -> Unit) {
    val length = input.readRawVarint32()
    if (length < 0 || input.totalBytesRead > Int.MAX_VALUE - length) {
      throw InvalidProtocolBufferException("Invalid packed field length: $length")
    }
    val oldLimit = input.pushLimit(length)
    while (input.bytesUntilLimit > 0) {
      readOne()
    }
    if (input.bytesUntilLimit != 0) {
      throw InvalidProtocolBufferException("Truncated packed field")
    }
    input.popLimit(oldLimit)
  }

  private fun skipFieldOrThrow(input: CodedInputStream, tag: Int) {
    when (WireFormat.getTagWireType(tag)) {
      WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
        val length = input.readRawVarint32()
        if (length < 0 || input.totalBytesRead > Int.MAX_VALUE - length) {
          throw InvalidProtocolBufferException("Invalid length-delimited field size: $length")
        }
        input.skipRawBytes(length)
      }
      WireFormat.WIRETYPE_START_GROUP -> skipGroupIteratively(input, tag)
      WireFormat.WIRETYPE_END_GROUP -> throw InvalidProtocolBufferException("Unexpected end-group tag")
      else -> {
        if (!input.skipField(tag)) {
          throw InvalidProtocolBufferException("Unexpected end-group tag")
        }
      }
    }
  }

  private fun skipGroupIteratively(input: CodedInputStream, startGroupTag: Int) {
    val expectedEndTags = IntArray(MAX_GROUP_NESTING)
    expectedEndTags[0] = WireFormat.getTagFieldNumber(startGroupTag) shl 3 or WireFormat.WIRETYPE_END_GROUP
    var depth = 1
    while (depth > 0) {
      val tag = input.readTag()
      if (tag == 0) {
        throw InvalidProtocolBufferException("Truncated group in sensor session")
      }
      when (WireFormat.getTagWireType(tag)) {
        WireFormat.WIRETYPE_START_GROUP -> {
          if (depth >= MAX_GROUP_NESTING) {
            throw InvalidProtocolBufferException("Protocol message had too many levels of nesting")
          }
          expectedEndTags[depth++] = WireFormat.getTagFieldNumber(tag) shl 3 or WireFormat.WIRETYPE_END_GROUP
        }
        WireFormat.WIRETYPE_END_GROUP -> {
          val expected = expectedEndTags[--depth]
          if (tag != expected) {
            throw InvalidProtocolBufferException("Mismatched end-group tag")
          }
        }
        WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
          val length = input.readRawVarint32()
          if (length < 0 || input.totalBytesRead > Int.MAX_VALUE - length) {
            throw InvalidProtocolBufferException("Invalid length-delimited field size: $length")
          }
          input.skipRawBytes(length)
        }
        else -> {
          if (!input.skipField(tag)) {
            throw InvalidProtocolBufferException("Unexpected end-group tag")
          }
        }
      }
    }
  }
}
