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

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Memory.HeapDumpInfo
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.cpu.CpuCaptureSessionArtifact
import com.android.tools.profilers.memory.HprofSessionArtifact
import com.android.tools.profilers.sessions.SessionArtifact
import com.android.tools.profilers.sessions.SessionItem
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.android.tools.profilers.tasks.TaskTypeMappingUtils
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import perfetto.protos.PerfettoConfig

class UnifiedTraceOpenerTest {

  private val timer = FakeTimer()
  private val services = FakeIdeProfilerServices()
  private val transportService = FakeTransportService(timer, false)

  @get:Rule var grpcChannel = FakeGrpcChannel("UnifiedTraceOpenerTestChannel", transportService)
  @get:Rule var daemonFiles = TemporaryFolder()

  private lateinit var profilers: StudioProfilers
  private lateinit var opener: UnifiedTraceOpener

  /**
   * Saved recordings live in the shared temp directory and are named after the second the recording started, so start the session now to
   * avoid other files' names. Each test writes its file itself and deletes it afterwards.
   */
  private val startTime = System.nanoTime()
  private val sessionStartEpochMs = System.currentTimeMillis()
  private val session = Common.Session.newBuilder().setSessionId(SESSION_ID).setStreamId(1).setStartTimestamp(startTime).build()
  private val cacheFiles = mutableListOf<File>()

  @Before
  fun setup() {
    profilers = StudioProfilers(ProfilerClient(grpcChannel.channel), services, timer)
    profilers.sessionsManager.currentTaskType = ProfilerTaskType.HEAP_DUMP
    opener = UnifiedTraceOpener(profilers)
  }

  @After
  fun tearDown() {
    cacheFiles.forEach { it.delete() }
  }

  @Test
  fun testCachedCaptureIsOpenedWithTheFormatItWasImportedAs() {
    val cachedFileName =
      ProfilerCaptureFileUtils.getRecordingFileName(ProfilerTaskType.HEAP_DUMP, sessionStartEpochMs, "perfetto-java-heap-dump")
    val cached =
      File(FileUtil.getTempDirectory(), cachedFileName).also {
        it.writeText("capture")
        cacheFiles.add(it)
      }
    // Serve a different file from the daemon, which is what gets opened if the cached capture is looked up under the wrong name.
    transportService.addFile(startTime.toString(), daemonFiles.newFile("from-daemon").absolutePath)

    val opened = opener.openUnifiedTrace(session, recordingOf(ProfilerTaskType.HEAP_DUMP, heapDumpArtifact("dump.perfetto-java-heap-dump")))

    assertThat(opened).isTrue()
    assertThat(services.openedFile).isEqualTo(cached)
  }

  @Test
  fun testSavedSystemTraceIsFoundAgainByName() {
    val traceStartNs = startTime + TimeUnit.SECONDS.toNanos(3)
    val savedFileName = ProfilerCaptureFileUtils.getRecordingFileName(ProfilerTaskType.SYSTEM_TRACE, sessionStartEpochMs + 3_000, "trace")
    val saved =
      File(FileUtil.getTempDirectory(), savedFileName).also {
        it.writeText("trace")
        cacheFiles.add(it)
      }
    // Serve a different file from the daemon, which is what gets opened if the saved recording is looked up under the wrong name.
    transportService.addFile(traceStartNs.toString(), daemonFiles.newFile("from-daemon").absolutePath)

    val opened = opener.openUnifiedTrace(session, recordingOf(ProfilerTaskType.SYSTEM_TRACE, systemTraceArtifact(traceStartNs)))

    assertThat(opened).isTrue()
    assertThat(services.openedFile).isEqualTo(saved)
  }

  private fun heapDumpArtifact(sessionName: String) =
    HprofSessionArtifact(
      profilers,
      session,
      Common.SessionMetaData.newBuilder().setSessionName(sessionName).build(),
      HeapDumpInfo.newBuilder().setStartTime(startTime).setEndTime(startTime + 1).build(),
    )

  private fun systemTraceArtifact(traceStartNs: Long) =
    CpuCaptureSessionArtifact(
      profilers,
      session,
      Common.SessionMetaData.getDefaultInstance(),
      Trace.TraceInfo.newBuilder()
        .setTraceId(traceStartNs)
        .setFromTimestamp(traceStartNs)
        .setToTimestamp(traceStartNs + 1)
        .setConfiguration(Trace.TraceConfiguration.newBuilder().setPerfettoOptions(PerfettoConfig.TraceConfig.getDefaultInstance()))
        .build(),
    )

  private fun recordingOf(taskType: ProfilerTaskType, artifact: SessionArtifact<*>): Map<Long, SessionItem> {
    val metadata =
      Common.SessionMetaData.newBuilder()
        .setSessionId(SESSION_ID)
        .setTaskType(TaskTypeMappingUtils.convertTaskType(taskType))
        .setStartTimestampEpochMs(sessionStartEpochMs)
        .build()
    return mapOf(SESSION_ID to SessionItem(profilers, session, metadata).apply { setChildArtifacts(listOf(artifact)) })
  }

  companion object {
    private const val SESSION_ID = 1L
  }
}
