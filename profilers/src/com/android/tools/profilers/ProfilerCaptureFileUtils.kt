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
   * Renames a temporary capture file to an identifiable file within the system's temporary directory.
   *
   * This is used when a capture is created and needs to be saved with a specific name so it can be easily identified and opened by the
   * editor.
   *
   * @param captureFile the source temporary file
   * @param targetFileName the desired name for the file
   * @return the renamed [File] if successful, or null otherwise
   */
  @JvmStatic
  fun renameToTargetFile(captureFile: File, targetFileName: String): File? {
    if (!captureFile.exists() || captureFile.length() == 0L) {
      return null
    }

    // Use the system temp directory as the destination folder for the permanent file.
    val outputDir = File(FileUtil.getTempDirectory())
    val traceFile = File(outputDir, targetFileName)

    if (captureFile.absolutePath == traceFile.absolutePath) {
      return traceFile
    }

    if (traceFile.exists() && traceFile.length() > 0L) {
      return traceFile
    }

    if (traceFile.exists()) {
      traceFile.delete()
    }

    // Try to rename the capture file to the target file.
    if (captureFile.renameTo(traceFile)) {
      return traceFile
    }
    return null
  }

  @JvmStatic
  fun getCaptureFile(traceFileName: String): File {
    return File(FileUtil.getTempDirectory(), traceFileName)
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
   * Returns the name of the file that a capture of [taskType] in [session] is saved to so it can open in the editor. For example,
   * `heap-dump-20261116T141553.hprof`.
   *
   * @param captureStartNs when the capture started, in the device time of [session]
   */
  @JvmStatic
  fun getRecordingFileName(
    session: Common.Session,
    sessionMetaData: Common.SessionMetaData?,
    taskType: ProfilerTaskType,
    captureStartNs: Long,
    extension: String,
  ): String = getRecordingFileName(taskType, getRecordingStartEpochMs(session, sessionMetaData, captureStartNs), extension)

  /**
   * Returns the file that the CPU recording described by [traceInfo], a System Trace, Java/Kotlin Method Recording or Callstack Sample, is
   * saved to so it can open in the editor. For example, `system-trace-20261116T141553.trace`.
   */
  @JvmStatic
  fun getCpuRecordingFile(session: Common.Session, sessionMetaData: Common.SessionMetaData?, traceInfo: Trace.TraceInfo): File {
    val taskType = TraceType.from(traceInfo.configuration).toTaskType()
    return getCaptureFile(getRecordingFileName(session, sessionMetaData, taskType, traceInfo.fromTimestamp, CPU_RECORDING_EXTENSION))
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
  fun getCaptureAsFile(profilers: StudioProfilers, traceId: Long): File? {
    val traceRequest = Transport.BytesRequest.newBuilder().setStreamId(profilers.session.streamId).setId(traceId.toString()).build()
    val traceResponse = profilers.client.transportClient.getFile(traceRequest)
    if (traceResponse.filePath.isEmpty()) return null
    val captureFile = File(traceResponse.filePath)
    if (!captureFile.exists() || captureFile.length() == 0L || isPreprocessFailure(captureFile)) return null
    return captureFile
  }

  /**
   * Fetches the file of the CPU recording described by [traceInfo], a System Trace, Java/Kotlin Method Recording or Callstack Sample, and
   * moves it into the temp directory under the name from [getCpuRecordingFile], so it can open in the editor. If a non-empty file with that
   * name is already there, returns it as is. Returns null if the recording's file can't be fetched or moved.
   */
  @JvmStatic
  fun getAndRenameCapture(profilers: StudioProfilers, session: Common.Session, traceInfo: Trace.TraceInfo): File? {
    val captureFile = getCaptureAsFile(profilers, traceInfo.traceId) ?: return null
    val targetFile = getCpuRecordingFile(session, profilers.sessionsManager.getSessionMetaData(session.sessionId), traceInfo)
    val finalFile = renameToTargetFile(captureFile, targetFile.name) ?: return null
    writeMetadataToFile(profilers, session, finalFile)

    // TRACING 2.0: Check if this was a Tracing 2.0 CPU session, and if so, wait for and Zip the Compose traces.
    // This overwrites the file inline so downstream parsers can seamlessly render everything together.
    if (TraceMerger.activeV2Sessions.containsKey(session.pid.toLong())) {
      TraceMerger.mergeAndCleanUp(session.pid.toLong(), finalFile)
    }

    return finalFile
  }

  /**
   * Writes a sidecar file containing serialized task metadata alongside the trace file. This metadata is used by standalone editors to
   * reconstruct telemetry dashboards accurately safely.
   */
  @JvmStatic
  fun writeMetadataToFile(profilers: StudioProfilers, session: Common.Session, file: File) {
    val sessionsManager = profilers.sessionsManager
    val metadata = sessionsManager.getSessionMetaData(session.sessionId) ?: return
    try {
      val hash = ProfilerHashUtils.hashTracePath(file)
      val tempDir = FileUtil.getTempDirectory()
      val metadataFile = File(tempDir, "${file.nameWithoutExtension}-$hash.metadata")
      if (!metadataFile.exists()) {
        val taskMetadata =
          ProfilerTaskMetadataProto.ProfilerTaskMetadata.newBuilder()
            .setSessionMetadata(metadata)
            .setTaskDataOrigin(resolveDataOrigin(sessionsManager, session))
            .setTaskAttachmentPoint(resolveAttachmentPoint(sessionsManager))
            .build()

        metadataFile.outputStream().use { output -> taskMetadata.writeTo(output) }
      }
    } catch (e: Exception) {
      Logger.getInstance(ProfilerCaptureFileUtils::class.java).warn("Failed to write metadata for ${file.absolutePath}", e)
    }
  }

  private fun resolveDataOrigin(
    sessionsManager: SessionsManager,
    session: Common.Session,
  ): ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin {
    return when {
      sessionsManager.isSessionAlive -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.NEW
      SessionsManager.isSessionImported(session) -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.IMPORTED
      session != Common.Session.getDefaultInstance() -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.PAST_RECORDING
      else -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskDataOrigin.ORIGIN_UNSPECIFIED
    }
  }

  private fun resolveAttachmentPoint(sessionsManager: SessionsManager): ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint {
    return when {
      !sessionsManager.isSessionAlive -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint.ATTACHMENT_UNSPECIFIED
      sessionsManager.isCurrentTaskStartup -> ProfilerTaskMetadataProto.ProfilerTaskMetadata.TaskAttachmentPoint.NEW_PROCESS
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
