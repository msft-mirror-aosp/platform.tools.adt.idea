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

import com.google.android.apps.automotive.sensing.sensorreplay.SensorSession.SensorRecord
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

private const val DEFAULT_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024
private const val DEFAULT_CHECKPOINT_INTERVAL_BYTES = 4 * 1024 * 1024
private const val DEFAULT_MAX_MERGE_FAN_IN = 64
private const val FORMAT_PROBE_BYTES = 4 * 1024

internal const val IO_BUFFER_SIZE = 64 * 1024
internal const val MAX_GROUP_NESTING = 64

private val TEXTPROTO_EXTENSIONS = setOf("textproto", "txtpb", "pbtxt", "textpb")

/**
 * Streams and parses a binary or textproto `SensorSession` protobuf file of arbitrary size while bounding heap usage:
 * - Unused large payloads (such as camera frames in `SensorRecord.image` or unknown fields) are skipped directly without allocating byte
 *   arrays, strings, or `UnknownFieldSet` entries.
 * - `CodedInputStream`'s size counter is reset between top-level binary fields, and textproto tokens are streamed over a fixed buffer, so
 *   files larger than 64 MB or 2 GB stream cleanly.
 * - If stripped [SensorRecord]s exceed [maxInMemoryBytes], sorted chunks are spilled to temporary run files and merged via external k-way
 *   merge sort into a disk-backed [SensorSessionAccumulator.SensorRecordStore] with sparse seek checkpoints.
 */
internal fun loadSensorSession(
  file: Path,
  maxInMemoryBytes: Int = DEFAULT_MAX_IN_MEMORY_BYTES,
  checkpointIntervalBytes: Int = DEFAULT_CHECKPOINT_INTERVAL_BYTES,
  maxMergeFanIn: Int = DEFAULT_MAX_MERGE_FAN_IN,
  checkCancelled: () -> Unit = {},
): SensorSessionItem {
  require(maxInMemoryBytes > 0) { "maxInMemoryBytes must be positive: $maxInMemoryBytes" }
  require(checkpointIntervalBytes > 0) { "checkpointIntervalBytes must be positive: $checkpointIntervalBytes" }
  require(maxMergeFanIn >= 2) { "maxMergeFanIn must be at least 2: $maxMergeFanIn" }

  if (!file.isRegularFile()) {
    throw IOException("File does not exist: $file")
  }
  if (Files.size(file) == 0L) {
    throw EmptySensorSessionException()
  }

  return if (hasTextProtoExtension(file)) {
    try {
      SensorSessionTextProtoLoader.loadTextProtoSensorSession(
        file,
        maxInMemoryBytes,
        checkpointIntervalBytes,
        maxMergeFanIn,
        checkCancelled,
      )
    } catch (textError: IOException) {
      if (!looksLikeUtf8TextPrefix(file)) {
        try {
          SensorSessionBinaryProtoLoader.loadBinarySensorSession(
            file,
            maxInMemoryBytes,
            checkpointIntervalBytes,
            maxMergeFanIn,
            checkCancelled,
          )
        } catch (_: IOException) {
          throw textError
        }
      } else {
        throw textError
      }
    }
  } else {
    try {
      SensorSessionBinaryProtoLoader.loadBinarySensorSession(
        file,
        maxInMemoryBytes,
        checkpointIntervalBytes,
        maxMergeFanIn,
        checkCancelled,
      )
    } catch (binaryError: IOException) {
      if (looksLikeUtf8TextPrefix(file)) {
        try {
          SensorSessionTextProtoLoader.loadTextProtoSensorSession(
            file,
            maxInMemoryBytes,
            checkpointIntervalBytes,
            maxMergeFanIn,
            checkCancelled,
          )
        } catch (_: IOException) {
          throw binaryError
        }
      } else {
        throw binaryError
      }
    }
  }
}

/** Thrown when an imported sensor session file has zero bytes. */
internal class EmptySensorSessionException : IOException("Sensor session file is empty")

private fun hasTextProtoExtension(file: Path): Boolean = file.extension.lowercase(Locale.ROOT) in TEXTPROTO_EXTENSIONS

private fun looksLikeUtf8TextPrefix(file: Path): Boolean {
  return try {
    Files.newInputStream(file, StandardOpenOption.READ).use { stream ->
      val prefix = ByteArray(FORMAT_PROBE_BYTES)
      var totalRead = 0
      while (totalRead < prefix.size) {
        val count = stream.read(prefix, totalRead, prefix.size - totalRead)
        if (count < 0) break
        totalRead += count
      }
      if (totalRead == 0) return false
      for (i in 0 until totalRead) {
        val b = prefix[i].toInt() and 0xFF
        if (b in 0..8 || b in 14..31 || b == 0x7F) {
          return false
        }
      }
      val decoder =
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      val endOfInput = totalRead < prefix.size
      val result = decoder.decode(ByteBuffer.wrap(prefix, 0, totalRead), CharBuffer.allocate(totalRead), endOfInput)
      !result.isError
    }
  } catch (_: IOException) {
    false
  }
}
