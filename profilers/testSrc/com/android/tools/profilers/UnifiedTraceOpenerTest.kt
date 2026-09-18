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
import com.android.tools.profilers.memory.HprofSessionArtifact
import com.android.tools.profilers.sessions.SessionItem
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedTraceOpenerTest {

  private val timer = FakeTimer()
  private val services = FakeIdeProfilerServices()
  private val transportService = FakeTransportService(timer, false)

  @get:Rule var grpcChannel = FakeGrpcChannel("UnifiedTraceOpenerTestChannel", transportService)
  @get:Rule var daemonFiles = TemporaryFolder()

  private lateinit var profilers: StudioProfilers
  private lateinit var opener: UnifiedTraceOpener

  /** Capture files live in the shared temp directory, so keep the name unique to this test and delete it afterwards. */
  private val startTime = System.nanoTime()
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
    val cached =
      File(FileUtil.getTempDirectory(), "capture_$startTime.perfetto-java-heap-dump").also {
        it.writeText("capture")
        cacheFiles.add(it)
      }
    // Serve a different file from the daemon, which is what gets opened if the cached capture is looked up under the wrong name.
    transportService.addFile(startTime.toString(), daemonFiles.newFile("from-daemon").absolutePath)

    val opened = opener.openUnifiedTrace(SESSION, recordingOf(heapDumpArtifact("dump.perfetto-java-heap-dump")))

    assertThat(opened).isTrue()
    assertThat(services.openedFile).isEqualTo(cached)
  }

  private fun heapDumpArtifact(sessionName: String) =
    HprofSessionArtifact(
      profilers,
      SESSION,
      Common.SessionMetaData.newBuilder().setSessionName(sessionName).build(),
      HeapDumpInfo.newBuilder().setStartTime(startTime).setEndTime(startTime + 1).build(),
    )

  private fun recordingOf(artifact: HprofSessionArtifact): Map<Long, SessionItem> =
    mapOf(
      SESSION_ID to SessionArtifactUtils.createSessionItem(profilers, SESSION, SESSION_ID, ProfilerTaskType.HEAP_DUMP, listOf(artifact))
    )

  companion object {
    private const val SESSION_ID = 1L
    private val SESSION: Common.Session = Common.Session.newBuilder().setSessionId(SESSION_ID).setStreamId(1).build()
  }
}
