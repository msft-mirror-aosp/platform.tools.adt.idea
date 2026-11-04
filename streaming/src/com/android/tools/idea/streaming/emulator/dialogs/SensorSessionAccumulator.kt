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
import com.android.emulator.control.SensorSession.SensorRecord.SensorEvent
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.tools.idea.protobuf.CodedInputStream
import com.android.tools.idea.protobuf.CodedOutputStream
import com.android.tools.idea.protobuf.InvalidProtocolBufferException
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionAccumulator.SensorRecordStore.Companion.mergeRecordIntoAggregate
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.PriorityQueue
import java.util.TreeMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

/**
 * Accumulates [SessionMetadata], timeline event buckets, and [SensorRecord]s while streaming a sensor session file, spilling sorted chunks
 * to temporary run files and merging them via external k-way merge sort into a [DiskBackedRecordStore] when [maxInMemoryBytes] is exceeded.
 */
internal class SensorSessionAccumulator(
  private val maxInMemoryBytes: Int,
  private val checkpointIntervalBytes: Int,
  private val maxMergeFanIn: Int,
  private val checkCancelled: () -> Unit,
) : Closeable {
  val metadataBuilder: SessionMetadata.Builder = SessionMetadata.newBuilder()
  private var hasSessionMetadata: Boolean = false

  private val timelineBuilder = TimelineBuilder()
  private val currentChunk = ArrayList<SensorRecord>()
  private var currentChunkBytes = 0L
  private var totalEvents = 0L
  private val spilledRuns = ArrayList<Path>()

  fun markSessionMetadataPresent() {
    hasSessionMetadata = true
  }

  fun addRecord(record: SensorRecord) {
    totalEvents++
    timelineBuilder.add(record.timestampNs.nanoseconds)
    currentChunk.add(record)
    currentChunkBytes += RECORD_HEAP_OVERHEAD_BYTES + record.serializedSize
    if (currentChunkBytes >= maxInMemoryBytes) {
      spilledRuns.add(spillChunkToTempRun(currentChunk, checkCancelled))
      currentChunk.clear()
      currentChunkBytes = 0L
    }
  }

  fun buildSessionItem(file: Path): SensorSessionItem {
    if (!hasSessionMetadata && totalEvents == 0L) {
      throw IOException("Invalid sensor session file: $file")
    }

    val recordStore: SensorRecordStore =
      if (spilledRuns.isEmpty()) {
        currentChunk.sortBy { it.timestampNs }
        val sorted = ArrayList(currentChunk)
        currentChunk.clear()
        InMemoryRecordStore(sorted)
      } else {
        if (currentChunk.isNotEmpty()) {
          spilledRuns.add(spillChunkToTempRun(currentChunk, checkCancelled))
          currentChunk.clear()
        }
        mergeRunsToDiskStore(
          initialRuns = spilledRuns,
          totalEvents = totalEvents,
          checkpointIntervalBytes = checkpointIntervalBytes.toLong(),
          maxMergeFanIn = maxMergeFanIn,
          checkCancelled = checkCancelled,
        )
      }

    return SensorSessionItem(
      path = file,
      metadata = metadataBuilder.build(),
      recordStore = recordStore,
      timeline = timelineBuilder.build(),
    )
  }

  override fun close() {
    for (runFile in spilledRuns) {
      deleteTempFileQuietly(runFile)
    }
    spilledRuns.clear()
    currentChunk.clear()
  }

  private class TimelineBuilder {
    private var bucketCounts = TreeMap<Long, Long>()
    private var bucketSpanSeconds = 1L

    fun add(timestamp: Duration) {
      val seconds = timestamp.coerceAtLeast(Duration.ZERO).inWholeSeconds
      val bucket = seconds / bucketSpanSeconds
      bucketCounts[bucket] = (bucketCounts[bucket] ?: 0L) + 1L
      if (bucketCounts.size > MAX_SPARSE_TIMELINE_BUCKETS && bucketSpanSeconds <= Long.MAX_VALUE / 2) {
        compactBuckets()
      }
    }

    fun build(): List<TimelineEntry> {
      if (bucketCounts.isEmpty()) return emptyList()
      val maxBucket = bucketCounts.lastKey()
      return if (bucketSpanSeconds == 1L && maxBucket < MAX_DENSE_TIMELINE_BUCKETS) {
        (0L..maxBucket).map { bucket ->
          TimelineEntry(
            timeRange = formatBucketRange(bucket),
            eventCount = (bucketCounts[bucket] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          )
        }
      } else {
        bucketCounts.entries.map { (bucket, count) ->
          TimelineEntry(
            timeRange = formatBucketRange(bucket),
            eventCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          )
        }
      }
    }

    private fun compactBuckets() {
      val compacted = TreeMap<Long, Long>()
      for ((bucket, count) in bucketCounts) {
        val mergedBucket = bucket / 2
        compacted[mergedBucket] = (compacted[mergedBucket] ?: 0L) + count
      }
      bucketCounts = compacted
      bucketSpanSeconds *= 2
    }

    private fun formatBucketRange(bucket: Long): String {
      val fromSeconds = saturatingMultiply(bucket, bucketSpanSeconds)
      val toSeconds =
        if (bucket == Long.MAX_VALUE) {
          Long.MAX_VALUE
        } else {
          saturatingMultiply(bucket + 1L, bucketSpanSeconds)
        }
      return "${formatDurationSeconds(fromSeconds)} - ${formatDurationSeconds(toSeconds)}"
    }

    private fun saturatingMultiply(a: Long, b: Long): Long {
      if (a <= 0L || b <= 0L) return 0L
      return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }
  }

  private class RunningAggregate {
    private val carProperties = HashMap<Int, CarPropertyValue>()
    private var locations: List<PositionProto> = emptyList()
    private val sensorEvents = HashMap<String, SensorEvent>()
    private var estimatedBytes: Long = 0L

    fun add(record: SensorRecord) {
      if (record.carPropertyValuesCount > 0) {
        for ((key, value) in record.carPropertyValuesMap) {
          val previous = carProperties.put(key, value)
          estimatedBytes += value.serializedSize - (previous?.serializedSize ?: 0) + (if (previous == null) 16 else 0)
        }
      }
      if (record.locationsCount > 0) {
        val hasGps = record.locationsList.any { it.hasGpsPoint() }
        if (hasGps || locations.none { it.hasGpsPoint() }) {
          for (loc in locations) {
            estimatedBytes -= loc.serializedSize
          }
          locations = record.locationsList
          for (loc in locations) {
            estimatedBytes += loc.serializedSize
          }
        }
      }
      if (record.sensorEventsCount > 0) {
        for ((key, value) in record.sensorEventsMap) {
          val previous = sensorEvents.put(key, value)
          estimatedBytes += value.serializedSize - (previous?.serializedSize ?: 0) + (if (previous == null) key.length + 16 else 0)
        }
      }
    }

    fun snapshotIfWithinLimit(maxBytes: Int): SensorRecord? {
      if (estimatedBytes > maxBytes) return null
      if (carProperties.isEmpty() && locations.isEmpty() && sensorEvents.isEmpty()) {
        return SensorRecord.getDefaultInstance()
      }
      return SensorRecord.newBuilder()
        .putAllCarPropertyValues(carProperties)
        .addAllLocations(locations)
        .putAllSensorEvents(sensorEvents)
        .build()
    }
  }

  /**
   * Storage for chronologically sorted [SensorRecord]s of a loaded sensor session, either in memory (for small/medium stripped sessions) or
   * backed by a sorted temporary file with sparse seek checkpoints (for sessions with arbitrarily many sensor events).
   */
  sealed interface SensorRecordStore : AutoCloseable {
    val totalEvents: Long
    val duration: Duration

    val isEmpty: Boolean
      get() = totalEvents == 0L

    @get:VisibleForTesting val tempFileForTesting: Path?

    fun aggregateRecordsUpTo(targetOffset: Duration): SensorRecord?

    suspend fun forEachPlaybackRecord(startOffset: Duration, action: suspend (SensorRecord) -> Unit)

    override fun close()

    companion object {
      fun mergeRecordIntoAggregate(builder: SensorRecord.Builder, record: SensorRecord) {
        if (record.carPropertyValuesCount > 0) {
          builder.putAllCarPropertyValues(record.carPropertyValuesMap)
        }
        if (record.locationsCount > 0) {
          val hasGps = record.locationsList.any { it.hasGpsPoint() }
          if (hasGps || builder.locationsList.none { it.hasGpsPoint() }) {
            builder.clearLocations()
            builder.addAllLocations(record.locationsList)
          }
        }
        if (record.sensorEventsCount > 0) {
          builder.putAllSensorEvents(record.sensorEventsMap)
        }
      }
    }
  }

  /** In-memory [SensorRecordStore] backed by a chronologically sorted list of [SensorRecord]s. */
  private class InMemoryRecordStore(private val sortedRecords: List<SensorRecord>) : SensorRecordStore {
    override val totalEvents: Long
      get() = sortedRecords.size.toLong()

    override val duration: Duration
      get() = (sortedRecords.lastOrNull()?.timestampNs?.coerceAtLeast(0L) ?: 0L).nanoseconds

    override val tempFileForTesting: Path?
      get() = null

    override fun aggregateRecordsUpTo(targetOffset: Duration): SensorRecord? {
      val targetNs = targetOffset.inWholeNanoseconds
      var hasAny = false
      val builder = SensorRecord.newBuilder().setTimestampNs(targetNs)
      for (record in sortedRecords) {
        if (record.timestampNs > targetNs) break
        hasAny = true
        mergeRecordIntoAggregate(builder, record)
      }
      return if (hasAny) builder.build() else null
    }

    override suspend fun forEachPlaybackRecord(startOffset: Duration, action: suspend (SensorRecord) -> Unit) {
      val startIndex =
        if (!startOffset.isPositive()) {
          0
        } else {
          findFirstIndexStrictlyAfter(sortedRecords, startOffset)
        }
      for (index in startIndex until sortedRecords.size) {
        action(sortedRecords[index])
      }
    }

    override fun close() = Unit

    private fun findFirstIndexStrictlyAfter(sortedRecords: List<SensorRecord>, targetOffset: Duration): Int {
      val targetNs = targetOffset.inWholeNanoseconds
      var low = 0
      var high = sortedRecords.size
      while (low < high) {
        val mid = (low + high) ushr 1
        if (sortedRecords[mid].timestampNs <= targetNs) {
          low = mid + 1
        } else {
          high = mid
        }
      }
      return low
    }
  }

  /**
   * [SensorRecordStore] backed by a chronologically sorted temporary file of length-delimited [SensorRecord]s with sparse [SeekCheckpoint]s
   * for fast seeking and aggregation without loading all events into memory.
   */
  private class DiskBackedRecordStore(
    private val tempFile: Path,
    override val totalEvents: Long,
    override val duration: Duration,
    private val checkpoints: List<SeekCheckpoint>,
  ) : SensorRecordStore {
    private val lock = Any()
    private var activeReaders = 0
    private var closed = false

    override val tempFileForTesting: Path
      get() = tempFile

    override fun aggregateRecordsUpTo(targetOffset: Duration): SensorRecord? {
      val targetNs = targetOffset.inWholeNanoseconds
      return withChannel { channel ->
        val checkpoint = findAggregateCheckpoint(targetOffset)
        val builder = SensorRecord.newBuilder().setTimestampNs(targetNs)
        var hasAny = false
        if (checkpoint != null) {
          hasAny = true
          mergeRecordIntoAggregate(builder, checkpoint.aggregatedBefore!!)
          channel.position(checkpoint.fileOffset)
        } else {
          channel.position(0L)
        }

        val input = CodedInputStream.newInstance(SeekableChannelInputStream(NonClosingByteChannel(channel)), IO_BUFFER_SIZE)
        input.setSizeLimit(Int.MAX_VALUE)
        while (true) {
          val record = readNextLengthDelimitedRecord(input) ?: break
          if (record.timestampNs > targetNs) break
          hasAny = true
          mergeRecordIntoAggregate(builder, record)
        }
        if (hasAny) builder.build() else null
      }
    }

    override suspend fun forEachPlaybackRecord(startOffset: Duration, action: suspend (SensorRecord) -> Unit) {
      val startOffsetNs = startOffset.inWholeNanoseconds
      val channel = acquireChannel() ?: return
      try {
        val input =
          withContext(Dispatchers.IO) {
            val offset = if (startOffset.isPositive()) findPlaybackCheckpointOffset(startOffset) else 0L
            channel.position(offset)
            CodedInputStream.newInstance(SeekableChannelInputStream(NonClosingByteChannel(channel)), IO_BUFFER_SIZE).apply {
              setSizeLimit(Int.MAX_VALUE)
            }
          }

        while (true) {
          val record = withContext(Dispatchers.IO) { readNextLengthDelimitedRecord(input) } ?: break
          if (startOffset.isPositive() && record.timestampNs <= startOffsetNs) {
            continue
          }
          action(record)
        }
      } finally {
        releaseChannel(channel)
      }
    }

    override fun close() {
      val shouldDelete =
        synchronized(lock) {
          if (closed) return
          closed = true
          activeReaders == 0
        }
      if (shouldDelete) {
        deleteTempFileQuietly(tempFile)
      }
    }

    private fun findAggregateCheckpoint(targetOffset: Duration): SeekCheckpoint? {
      var index = findLastCheckpointIndexAtOrBefore(targetOffset)
      while (index >= 0) {
        val candidate = checkpoints[index]
        if (candidate.aggregatedBefore != null) {
          return candidate
        }
        index--
      }
      return null
    }

    private fun findPlaybackCheckpointOffset(startOffset: Duration): Long {
      val index = findLastCheckpointIndexAtOrBefore(startOffset)
      return if (index >= 0) checkpoints[index].fileOffset else 0L
    }

    private fun findLastCheckpointIndexAtOrBefore(targetOffset: Duration): Int {
      var low = 0
      var high = checkpoints.size - 1
      var result = -1
      while (low <= high) {
        val mid = (low + high) ushr 1
        if (checkpoints[mid].firstRecordTimestamp <= targetOffset) {
          result = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      }
      return result
    }

    private inline fun <T> withChannel(block: (SeekableByteChannel) -> T): T? {
      val channel = acquireChannel() ?: return null
      try {
        return block(channel)
      } finally {
        releaseChannel(channel)
      }
    }

    private fun acquireChannel(): SeekableByteChannel? =
      synchronized(lock) {
        if (closed) {
          null
        } else {
          val ch = Files.newByteChannel(tempFile, StandardOpenOption.READ)
          activeReaders++
          ch
        }
      }

    private fun releaseChannel(channel: SeekableByteChannel) {
      try {
        channel.close()
      } catch (_: IOException) {} finally {
        val shouldDelete =
          synchronized(lock) {
            activeReaders--
            closed && activeReaders == 0
          }
        if (shouldDelete) {
          deleteTempFileQuietly(tempFile)
        }
      }
    }

    private class NonClosingByteChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel by delegate {
      override fun close() = Unit
    }
  }

  private class SeekCheckpoint(
    val firstRecordTimestamp: Duration,
    val fileOffset: Long,
    /** Aggregated sensor state of all records strictly preceding [fileOffset], or `null` if the aggregate exceeded the size limit. */
    val aggregatedBefore: SensorRecord?,
  )

  private class FileRunCursor(
    val runOrder: Int,
    private val stream: InputStream,
    private val input: CodedInputStream,
    currentRecord: SensorRecord,
  ) : Closeable {
    var currentRecord: SensorRecord = currentRecord
      private set

    fun advance(): Boolean {
      val next = readNextLengthDelimitedRecord(input) ?: return false
      currentRecord = next
      return true
    }

    override fun close() {
      stream.close()
    }

    companion object {
      fun open(runOrder: Int, file: Path): FileRunCursor? {
        val stream = BufferedInputStream(Files.newInputStream(file, StandardOpenOption.READ), IO_BUFFER_SIZE)
        try {
          val input = CodedInputStream.newInstance(stream, IO_BUFFER_SIZE)
          input.setSizeLimit(Int.MAX_VALUE)
          val first = readNextLengthDelimitedRecord(input)
          if (first == null) {
            stream.close()
            return null
          }
          return FileRunCursor(runOrder, stream, input, first)
        } catch (t: Throwable) {
          try {
            stream.close()
          } catch (_: IOException) {}
          throw t
        }
      }
    }
  }

  companion object {
    private const val RECORD_HEAP_OVERHEAD_BYTES = 128
    private const val MAX_DENSE_TIMELINE_BUCKETS = 3_600L
    private const val MAX_SPARSE_TIMELINE_BUCKETS = 10_000
    private const val MAX_CHECKPOINTS = 8_192
    private const val MAX_CHECKPOINT_AGGREGATE_BYTES = 256 * 1024

    private val RUN_CURSOR_COMPARATOR: Comparator<FileRunCursor> =
      compareBy<FileRunCursor> { it.currentRecord.timestampNs }.thenBy { it.runOrder }

    fun formatDuration(duration: Duration): String = formatDurationSeconds(duration.coerceAtLeast(Duration.ZERO).inWholeSeconds)

    private fun formatDurationSeconds(totalSeconds: Long): String {
      val clamped = totalSeconds.coerceAtLeast(0L)
      val seconds = clamped % 60
      val minutes = (clamped / 60) % 60
      val hours = clamped / 3600
      return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
      } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
      }
    }

    private fun spillChunkToTempRun(chunk: MutableList<SensorRecord>, checkCancelled: () -> Unit): Path {
      checkCancelled()
      chunk.sortBy { it.timestampNs }
      val runFile = createTempReplayFile("sensor-replay-run-")
      try {
        BufferedOutputStream(Files.newOutputStream(runFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), IO_BUFFER_SIZE)
          .use { out ->
            val codedOut = CodedOutputStream.newInstance(out, IO_BUFFER_SIZE)
            for ((index, record) in chunk.withIndex()) {
              if ((index and 0x3FF) == 0) {
                checkCancelled()
              }
              val size = record.serializedSize
              codedOut.writeUInt32NoTag(size)
              record.writeTo(codedOut)
            }
            codedOut.flush()
          }
        return runFile
      } catch (t: Throwable) {
        deleteTempFileQuietly(runFile)
        throw t
      }
    }

    private fun mergeRunsToDiskStore(
      initialRuns: MutableList<Path>,
      totalEvents: Long,
      checkpointIntervalBytes: Long,
      maxMergeFanIn: Int,
      checkCancelled: () -> Unit,
    ): DiskBackedRecordStore {
      val activeRuns = ArrayList(initialRuns)
      initialRuns.clear()
      val nextPassRuns = ArrayList<Path>()
      var finalFile: Path? = null

      try {
        // Perform intermediate k-way merge passes while preserving original run order for stable timestamp tie-breaking.
        while (activeRuns.size > maxMergeFanIn) {
          var idx = 0
          while (idx < activeRuns.size) {
            val end = minOf(idx + maxMergeFanIn, activeRuns.size)
            val group = activeRuns.subList(idx, end)
            if (group.size == 1) {
              nextPassRuns.add(group[0])
            } else {
              val mergedRun = mergeGroupToIntermediateRun(group, checkCancelled)
              for (consumed in group) {
                deleteTempFileQuietly(consumed)
              }
              nextPassRuns.add(mergedRun)
            }
            idx = end
          }
          activeRuns.clear()
          activeRuns.addAll(nextPassRuns)
          nextPassRuns.clear()
        }

        // Final merge pass: write sorted session file and build sparse seek checkpoints.
        finalFile = createTempReplayFile("sensor-replay-session-")
        var lastTimestampNs = 0L
        val checkpoints = ArrayList<SeekCheckpoint>()
        var effectiveInterval = checkpointIntervalBytes
        var nextCheckpointOffset = effectiveInterval
        val runningAggregate = RunningAggregate()

        mergeCursors(activeRuns) { queue ->
          BufferedOutputStream(
              Files.newOutputStream(finalFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
              IO_BUFFER_SIZE,
            )
            .use { out ->
              val codedOut = CodedOutputStream.newInstance(out, IO_BUFFER_SIZE)
              var bytesWritten = 0L
              var mergedCount = 0L

              while (queue.isNotEmpty()) {
                if ((mergedCount and 0x3FFL) == 0L) {
                  checkCancelled()
                }
                val cursor = queue.poll()
                val record = cursor.currentRecord

                if (bytesWritten >= nextCheckpointOffset) {
                  if (checkpoints.size >= MAX_CHECKPOINTS && effectiveInterval <= Long.MAX_VALUE / 2) {
                    thinCheckpointsInPlace(checkpoints)
                    effectiveInterval *= 2L
                  }
                  checkpoints.add(
                    SeekCheckpoint(
                      firstRecordTimestamp = record.timestampNs.nanoseconds,
                      fileOffset = bytesWritten,
                      aggregatedBefore = runningAggregate.snapshotIfWithinLimit(MAX_CHECKPOINT_AGGREGATE_BYTES),
                    )
                  )
                  nextCheckpointOffset =
                    if (bytesWritten > Long.MAX_VALUE - effectiveInterval) Long.MAX_VALUE else bytesWritten + effectiveInterval
                }

                val size = record.serializedSize
                val entryBytes = CodedOutputStream.computeUInt32SizeNoTag(size) + size
                codedOut.writeUInt32NoTag(size)
                record.writeTo(codedOut)
                bytesWritten += entryBytes

                runningAggregate.add(record)
                lastTimestampNs = record.timestampNs
                mergedCount++

                if (cursor.advance()) {
                  queue.add(cursor)
                } else {
                  cursor.close()
                }
              }
              codedOut.flush()
            }
        }

        for (consumed in activeRuns) {
          deleteTempFileQuietly(consumed)
        }
        activeRuns.clear()

        return DiskBackedRecordStore(
          tempFile = finalFile,
          totalEvents = totalEvents,
          duration = (if (totalEvents > 0L) lastTimestampNs.coerceAtLeast(0L) else 0L).nanoseconds,
          checkpoints = checkpoints,
        )
      } catch (t: Throwable) {
        finalFile?.let { deleteTempFileQuietly(it) }
        for (created in nextPassRuns) {
          deleteTempFileQuietly(created)
        }
        for (remaining in activeRuns) {
          deleteTempFileQuietly(remaining)
        }
        throw t
      }
    }

    private fun mergeGroupToIntermediateRun(group: List<Path>, checkCancelled: () -> Unit): Path {
      val outRun = createTempReplayFile("sensor-replay-run-")
      try {
        mergeCursors(group) { queue ->
          BufferedOutputStream(
              Files.newOutputStream(outRun, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
              IO_BUFFER_SIZE,
            )
            .use { out ->
              val codedOut = CodedOutputStream.newInstance(out, IO_BUFFER_SIZE)
              var mergedCount = 0L
              while (queue.isNotEmpty()) {
                if ((mergedCount and 0x3FFL) == 0L) {
                  checkCancelled()
                }
                val cursor = queue.poll()
                val record = cursor.currentRecord
                val size = record.serializedSize
                codedOut.writeUInt32NoTag(size)
                record.writeTo(codedOut)
                mergedCount++
                if (cursor.advance()) {
                  queue.add(cursor)
                } else {
                  cursor.close()
                }
              }
              codedOut.flush()
            }
        }
        return outRun
      } catch (t: Throwable) {
        deleteTempFileQuietly(outRun)
        throw t
      }
    }

    private inline fun mergeCursors(runFiles: List<Path>, block: (PriorityQueue<FileRunCursor>) -> Unit) {
      val opened = ArrayList<FileRunCursor>(runFiles.size)
      try {
        val queue = PriorityQueue(runFiles.size.coerceAtLeast(1), RUN_CURSOR_COMPARATOR)
        for ((order, file) in runFiles.withIndex()) {
          val cursor = FileRunCursor.open(order, file) ?: continue
          opened.add(cursor)
          queue.add(cursor)
        }
        block(queue)
      } finally {
        for (cursor in opened) {
          try {
            cursor.close()
          } catch (_: IOException) {}
        }
      }
    }

    private fun thinCheckpointsInPlace(checkpoints: ArrayList<SeekCheckpoint>) {
      var writeIndex = 0
      for (readIndex in checkpoints.indices step 2) {
        checkpoints[writeIndex++] = checkpoints[readIndex]
      }
      checkpoints.subList(writeIndex, checkpoints.size).clear()
    }

    private fun createTempReplayFile(prefix: String): Path {
      return Files.createTempFile(prefix, ".bin").also { it.toFile().deleteOnExit() }
    }

    private fun readNextLengthDelimitedRecord(input: CodedInputStream): SensorRecord? {
      input.resetSizeCounter()
      if (input.isAtEnd) return null
      val length = input.readRawVarint32()
      if (length < 0 || input.totalBytesRead > Int.MAX_VALUE - length) {
        throw InvalidProtocolBufferException("Invalid sensor record length: $length")
      }
      val oldLimit = input.pushLimit(length)
      val record = SensorRecord.parseFrom(input)
      input.checkLastTagWas(0)
      if (input.bytesUntilLimit != 0) {
        throw InvalidProtocolBufferException("Truncated sensor record")
      }
      input.popLimit(oldLimit)
      return record
    }

    private fun deleteTempFileQuietly(path: Path) {
      try {
        Files.deleteIfExists(path)
      } catch (_: IOException) {}
    }
  }
}
