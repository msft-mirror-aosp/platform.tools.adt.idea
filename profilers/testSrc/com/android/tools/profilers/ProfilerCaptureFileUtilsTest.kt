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

import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Trace
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import perfetto.protos.PerfettoConfig

class ProfilerCaptureFileUtilsTest {

  @Before
  fun setUp() {
    FileUtil.resetCanonicalTempPathCache(FileUtil.getTempDirectory())
  }

  @Test
  fun testRenameToTargetFile() {
    val tempDir = FileUtil.getTempDirectory()
    val sourceFile = File(tempDir, "temp_capture_123.tmp")
    sourceFile.writeText("test trace content")

    // The target's folder doesn't exist yet.
    val targetDir = File(tempDir, "capture_123")
    val targetFile = File(targetDir, "test_target_trace.trace")
    val resultFile = ProfilerCaptureFileUtils.renameToTargetFile(sourceFile, targetFile)

    assertThat(resultFile).isEqualTo(targetFile)
    assertThat(targetFile.readText()).isEqualTo("test trace content")
    assertThat(sourceFile.exists()).isFalse()

    // Cleanup
    FileUtil.delete(targetDir)
  }

  @Test
  fun recordingStartIsTheSessionStartWhenTheCaptureOffsetIsOutOfRange() {
    val beforeSession = SESSION.startTimestamp - TimeUnit.SECONDS.toNanos(1)
    val twoDaysIn = SESSION.startTimestamp + TimeUnit.DAYS.toNanos(2)

    assertThat(ProfilerCaptureFileUtils.getRecordingStartEpochMs(SESSION, SESSION_METADATA, beforeSession))
      .isEqualTo(SESSION_START_EPOCH_MS)
    assertThat(ProfilerCaptureFileUtils.getRecordingStartEpochMs(SESSION, SESSION_METADATA, twoDaysIn)).isEqualTo(SESSION_START_EPOCH_MS)
  }

  @Test
  fun recordingStartIsTheCaptureTimestampWhenTheSessionStartIsUnknown() {
    val captureStartNs = TimeUnit.SECONDS.toNanos(5)

    assertThat(ProfilerCaptureFileUtils.getRecordingStartEpochMs(SESSION, null, captureStartNs)).isEqualTo(5_000)
    assertThat(ProfilerCaptureFileUtils.getRecordingStartEpochMs(SESSION, Common.SessionMetaData.getDefaultInstance(), captureStartNs))
      .isEqualTo(5_000)
  }

  @Test
  fun cpuRecordingFileIsNamedAfterItsTaskAndStartTime() {
    // The trace started 3 seconds into the session, at 14:15:56, and is saved in a folder named after its ID.
    assertThat(ProfilerCaptureFileUtils.getCpuRecordingFile(SESSION, SESSION_METADATA, SYSTEM_TRACE))
      .isEqualTo(File(File(FileUtil.getTempDirectory(), "capture_42"), "system-trace-20261116T141556.trace"))
  }

  @Test
  fun capturesOfTheSameTaskInTheSameSecondAreSavedToDifferentFiles() {
    val first = ProfilerCaptureFileUtils.getCpuRecordingFile(SESSION, SESSION_METADATA, SYSTEM_TRACE)
    val second = ProfilerCaptureFileUtils.getCpuRecordingFile(SESSION, SESSION_METADATA, SYSTEM_TRACE.toBuilder().setTraceId(43).build())

    assertThat(second.name).isEqualTo(first.name)
    assertThat(second).isNotEqualTo(first)
  }

  private companion object {
    /** The session starts on 16 Nov 2026 at 14:15:53 in the system time zone, so the expected names don't depend on where the test runs. */
    val SESSION_START_EPOCH_MS = LocalDateTime.of(2026, 11, 16, 14, 15, 53).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val SESSION: Common.Session = Common.Session.newBuilder().setSessionId(1).setStartTimestamp(TimeUnit.SECONDS.toNanos(100)).build()

    val SESSION_METADATA: Common.SessionMetaData =
      Common.SessionMetaData.newBuilder().setSessionId(1).setStartTimestampEpochMs(SESSION_START_EPOCH_MS).build()

    /** A System Trace with ID 42 that started 3 seconds into [SESSION]. */
    val SYSTEM_TRACE: Trace.TraceInfo =
      Trace.TraceInfo.newBuilder()
        .setTraceId(42)
        .setFromTimestamp(SESSION.startTimestamp + TimeUnit.SECONDS.toNanos(3))
        .setConfiguration(Trace.TraceConfiguration.newBuilder().setPerfettoOptions(PerfettoConfig.TraceConfig.getDefaultInstance()))
        .build()
  }
}
