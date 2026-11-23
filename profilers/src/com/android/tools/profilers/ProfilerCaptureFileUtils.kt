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
package com.android.tools.profilers

import com.android.tools.idea.protobuf.ByteString
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.ProfilerTaskMetadataProto
import com.android.tools.profiler.proto.Trace
import com.android.tools.profiler.proto.Transport
import com.android.tools.profilers.cpu.CpuCaptureParserUtil
import com.android.tools.profilers.cpu.TraceMerger
import com.android.tools.profilers.cpu.TracePreProcessor
import com.android.tools.profilers.cpu.config.ProfilingConfiguration.TraceType
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.android.tools.profilers.utils.ProfilerHashUtils
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object ProfilerCaptureFileUtils {
  /** Extension of the files that CPU recordings (System Trace, Java/Kotlin Method Recording and Callstack Sample) are saved to. */
  private const val CPU_RECORDING_EXTENSION = "trace"

  /** Format of the times in recording file names, e.g. `20261116T141553`. */
  private val FILE_NAME_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

  /** Longest time between the start of a session and a capture in it that is trusted when working out when the capture started. */
  private val MAX_CAPTURE_OFFSET_MS = TimeUnit.DAYS.toMillis(1)

  /**
   * Moves a temporary capture file to [targetFile], creating its folder if needed, so it can be easily identified and opened by the editor.
   *
   * @param captureFile the source temporary file
   * @param targetFile where to move it. If a non-empty file is already there, it's returned as is.
   * @return the moved [File] if successful, or null otherwise
   */
  @JvmStatic
  fun renameToTargetFile(captureFile: File, targetFile: File): File? {
    if (!captureFile.exists() || captureFile.length() == 0L) {
      return null
    }

    if (captureFile.absolutePath == targetFile.absolutePath) {
      return targetFile
    }

    if (targetFile.exists() && targetFile.length() > 0L) {
      return targetFile
    }

    if (targetFile.exists()) {
      targetFile.delete()
    }

    // Try to rename the capture file to the target file, and don't leave behind a folder created for it if that fails.
    val folder = targetFile.parentFile
    val createdFolder = folder?.mkdirs() == true
    if (captureFile.renameTo(targetFile)) {
      return targetFile
    }
    if (createdFolder) {
      folder?.delete()
    }
    return null
  }

  /**
   * Returns the task part of recording file names: a short form of the task's name in the Home tab V2, e.g. `heap-dump` for a Heap Dump and
   * `method-trace` for a Java/Kotlin Method Recording.
   */
  private fun getTaskFileNamePrefix(taskType: ProfilerTaskType): String =
    when (taskType) {
      ProfilerTaskType.SYSTEM_TRACE -> "system-trace"
      ProfilerTaskType.LIVE_VIEW -> "live-telemetry"
      ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING -> "method-trace"
      ProfilerTaskType.CALLSTACK_SAMPLE -> "stack-sampling"
      ProfilerTaskType.HEAP_DUMP -> "heap-dump"
      ProfilerTaskType.LEAKCANARY -> "leakcanary"
      ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS -> "jk-allocations"
      ProfilerTaskType.NATIVE_ALLOCATIONS -> "native-allocations"
      ProfilerTaskType.UNSPECIFIED -> "recording"
    }

  /** Formats [epochMs] as a local time for file names, e.g. `20261116T141553`. */
  private fun formatFileNameTime(epochMs: Long): String =
    FILE_NAME_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

  /**
   * Returns the name of the file that a recording of [taskType], which started at [recordingStartEpochMs], is saved to so it can open in
   * the editor. For example, `heap-dump-20261116T141553.hprof`.
   */
  @VisibleForTesting
  internal fun getRecordingFileName(taskType: ProfilerTaskType, recordingStartEpochMs: Long, extension: String): String =
    "${getTaskFileNamePrefix(taskType)}-${formatFileNameTime(recordingStartEpochMs)}.$extension"

  /**
   * Returns the file that a capture of [taskType] in [session] is saved to so it can open in the editor, e.g.
   * `capture_123456789/heap-dump-20261116T141553.hprof` in the temp directory. Each capture gets its own folder, so captures of the same
   * task that start in the same second don't share a file, and the file's name, which the editor tab and Past Recordings show, stays
   * readable.
   *
   * @param captureStartNs when the capture started, in the device time of [session]
   * @param captureId the ID of the capture's file in the transport pipeline, which names its folder: the trace ID of a CPU recording, or
   *   the start time of a memory recording
   */
  @JvmStatic
  fun getRecordingFile(
    session: Common.Session,
    sessionMetaData: Common.SessionMetaData?,
    taskType: ProfilerTaskType,
    captureStartNs: Long,
    extension: String,
    captureId: Long,
  ): File {
    val fileName = getRecordingFileName(taskType, getRecordingStartEpochMs(session, sessionMetaData, captureStartNs), extension)
    return File(File(FileUtil.getTempDirectory(), "capture_$captureId"), fileName)
  }

  /**
   * Returns the file that the CPU recording described by [traceInfo], a System Trace, Java/Kotlin Method Recording or Callstack Sample, is
   * saved to so it can open in the editor, in a folder named after its trace ID. For example,
   * `capture_123456789/system-trace-20261116T141553.trace`.
   */
  @JvmStatic
  fun getCpuRecordingFile(session: Common.Session, sessionMetaData: Common.SessionMetaData?, traceInfo: Trace.TraceInfo): File {
    val taskType = TraceType.from(traceInfo.configuration).toTaskType()
    return getRecordingFile(session, sessionMetaData, taskType, traceInfo.fromTimestamp, CPU_RECORDING_EXTENSION, traceInfo.traceId)
  }

  /**
   * Returns the name, without the extension, that the export dialog suggests for a recording of [taskType]. For example,
   * `heap-dump-20261116T144522`, where the time is when the recording is exported ([exportEpochMs]).
   */
  @JvmStatic
  @JvmOverloads
  fun getExportFileName(taskType: ProfilerTaskType, exportEpochMs: Long = System.currentTimeMillis()): String =
    "${getTaskFileNamePrefix(taskType)}-${formatFileNameTime(exportEpochMs)}"

  /**
   * Returns when a capture that started at [captureStartNs], in the device time of [session], started in wall-clock time: when the session
   * started ([Common.SessionMetaData.getStartTimestampEpochMs]), plus how far into the session the capture started.
   *
   * The offset is only trusted from zero to a day. A capture can start before its session, e.g. a startup recording, whose tracing begins
   * before the session does, and an offset of more than a day most likely means the two timestamps come from different clocks; either way,
   * the capture is dated by the start of the session. Sessions normally record when they started; if one didn't, the capture's device
   * timestamp is converted to milliseconds and used instead, which gives a date in 1970. In every case the result is the same each time
   * it's worked out for a capture, so its saved file can be found again by name.
   */
  @VisibleForTesting
  internal fun getRecordingStartEpochMs(session: Common.Session, sessionMetaData: Common.SessionMetaData?, captureStartNs: Long): Long {
    val sessionStartEpochMs = sessionMetaData?.startTimestampEpochMs ?: 0L
    if (sessionStartEpochMs <= 0L) {
      return TimeUnit.NANOSECONDS.toMillis(captureStartNs).coerceAtLeast(0L)
    }
    val offsetMs = TimeUnit.NANOSECONDS.toMillis(captureStartNs - session.startTimestamp)
    return if (offsetMs in 0..MAX_CAPTURE_OFFSET_MS) sessionStartEpochMs + offsetMs else sessionStartEpochMs
  }

  @JvmStatic
  fun isPreprocessFailure(file: File): Boolean {
    val failureSize = TracePreProcessor.FAILURE.size()
    if (file.length() == failureSize.toLong()) {
      try {
        file.inputStream().use { isStream ->
          val bytes = isStream.readNBytes(failureSize)
          return isStream.read() == -1 && TracePreProcessor.FAILURE == ByteString.copyFrom(bytes)
        }
      } catch (_: IOException) {
        return false
      }
    }
    return false
  }

  @JvmStatic
  fun getCaptureAsFile(profilers: StudioProfilers, session: Common.Session, traceId: Long): File? {
    val traceRequest = Transport.BytesRequest.newBuilder().setStreamId(session.streamId).setId(traceId.toString()).build()
    val traceResponse = profilers.client.transportClient.getFile(traceRequest)
    if (traceResponse.filePath.isEmpty()) return null
    val captureFile = File(traceResponse.filePath)
    if (!captureFile.exists() || captureFile.length() == 0L || isPreprocessFailure(captureFile)) return null
    return captureFile
  }

  /**
   * Fetches the file of the CPU recording described by [traceInfo], a System Trace, Java/Kotlin Method Recording or Callstack Sample, and
   * moves it to the file from [getCpuRecordingFile], so it can open in the editor. If a non-empty file is already there, returns it as is.
   * Returns null if the recording's file can't be fetched or moved.
   *
   * @param session the session the recording was made in, as taken while it was live; see [writeMetadataToFile]
   */
  @JvmStatic
  fun getAndRenameCapture(profilers: StudioProfilers, session: Common.Session, traceInfo: Trace.TraceInfo): File? {
    val captureFile = getCaptureAsFile(profilers, session, traceInfo.traceId) ?: return null
    val targetFile = getCpuRecordingFile(session, profilers.sessionsManager.getSessionMetaData(session.sessionId), traceInfo)
    val finalFile = renameToTargetFile(captureFile, targetFile) ?: return null
    writeMetadataToFile(profilers, session, finalFile)

    // TRACING 2.0: Check if this was a Tracing 2.0 CPU session, and if so, wait for and Zip the Compose traces.
    // This overwrites the file inline so downstream parsers can seamlessly render everything together.
    if (TraceMerger.activeV2Sessions.containsKey(session.pid.toLong())) {
      TraceMerger.mergeAndCleanUp(session.pid.toLong(), finalFile)
    }

    return finalFile
  }

  /**
   * Writes a sidecar file containing serialized task metadata alongside the trace file, in the same folder. This metadata is used by
   * standalone editors to reconstruct telemetry dashboards accurately safely.
   *
   * @param session the session the recording was made in, as taken while it was live. Stopping a recording ends its session, so by the time
   *   the recording is saved the selected session may have ended, and would describe a new recording as a past one.
   */
  @JvmStatic
  fun writeMetadataToFile(profilers: StudioProfilers, session: Common.Session, file: File) {
    // A recording that didn't arrive can't be opened, so there's nothing to describe, and its folder may not exist.
    if (!file.isFile || file.length() == 0L) {
      return
    }
    val sessionsManager = profilers.sessionsManager
    val metadata = sessionsManager.getSessionMetaData(session.sessionId) ?: return
    try {
      val hash = ProfilerHashUtils.hashTracePath(file)
      val metadataFile = File(file.absoluteFile.parentFile, "${file.nameWithoutExtension}-$hash.metadata")
      if (!metadataFile.exists()) {
        val taskMetadata =
          ProfilerTaskMetadataProto.ProfilerTaskMetadata.newBuilder()
            .setSessionMetadata(metadata)
            .setTaskDataOrigin(resolveDataOrigin(session))
            .setTaskAttachmentPoint(resolveAttachmentPoint(session, metadata))
            .build()

        metadataFile.outputStream().use { output -> taskMetadata.writeTo(output) }
      }
    } catch (e: Exception) {
      Logger.getInstance(ProfilerCaptureFileUtils::class.java).warn("Failed to write metadata for ${file.absolutePath}", e)
    }
  }

  private fun resolveDataOrigin(session: Common.Session): ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin {
    return when {
      SessionsManager.isSessionAlive(session) -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.NEW
      SessionsManager.isSessionImported(session) -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.IMPORTED
      session != Common.Session.getDefaultInstance() -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.PAST_RECORDING
      else -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.ORIGIN_UNSPECIFIED
    }
  }

  private fun resolveAttachmentPoint(
    session: Common.Session,
    metadata: Common.SessionMetaData,
  ): ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint {
    return when {
      !SessionsManager.isSessionAlive(session) -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint.ATTACHMENT_UNSPECIFIED
      metadata.isStartupTask -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint.NEW_PROCESS
      else -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint.EXISTING_PROCESS
    }
  }

  /** Returns the corresponding [ProfilerTaskType] for a given trace [File], based on its extension. */
  @JvmStatic
  fun getFileTaskType(file: File): ProfilerTaskType? {
    val lazyTraceType = lazy { CpuCaptureParserUtil.getFileTraceType(file, TraceType.UNSPECIFIED) }
    return ProfilerFormat.find(file.extension, lazyTraceType)?.taskType
  }
}
