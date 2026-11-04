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
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionAccumulator.SensorRecordStore
import java.nio.file.Path
import kotlin.time.Duration
import org.jetbrains.annotations.VisibleForTesting

/** A 1-second time bucket summary entry displayed in the session timeline table. */
internal data class TimelineEntry(val timeRange: String, val eventCount: Int)

/** Loaded sensor replay session metadata, timeline summary, and record storage. */
internal class SensorSessionItem(
  val path: Path,
  val metadata: SessionMetadata,
  private val recordStore: SensorRecordStore,
  val timeline: List<TimelineEntry>,
) : AutoCloseable {
  val fileName: String
    get() = path.fileName?.toString() ?: path.toString()

  val appVersion: String
    get() =
      if (metadata.hasVersion()) {
        "${metadata.version.major}.${metadata.version.minor}.${metadata.version.patch}"
      } else {
        message("sensor.replay.dialog.value.not.available")
      }

  val totalEvents: Long
    get() = recordStore.totalEvents

  val hasNoEvents: Boolean
    get() = recordStore.isEmpty

  val duration: Duration
    get() = recordStore.duration

  @get:VisibleForTesting
  val tempFileForTesting: Path?
    get() = recordStore.tempFileForTesting

  fun aggregateRecordsUpTo(targetOffset: Duration): SensorRecord? = recordStore.aggregateRecordsUpTo(targetOffset)

  suspend fun forEachPlaybackRecord(startOffset: Duration, action: suspend (SensorRecord) -> Unit) {
    recordStore.forEachPlaybackRecord(startOffset, action)
  }

  fun getAvailableSensors(dictionary: VhalPropertyDictionary): List<String> {
    val carProperties = metadata.carPropertyConfigsMap.keys.sorted().map { dictionary.getPropertyLabel(it) }
    val androidSensors = metadata.subscribedSensorsMap.keys.sorted()
    return carProperties + androidSensors
  }

  override fun close() {
    recordStore.close()
  }
}
