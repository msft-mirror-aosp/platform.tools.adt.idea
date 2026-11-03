/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.profilers.memory

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Memory.HeapDumpInfo
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.ProfilersTestData
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.perfetto.config.PerfettoTraceConfigBuilders
import com.android.tools.profilers.sessions.SessionArtifact
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import perfetto.protos.PerfettoConfig

class HprofSessionArtifactTest {

  private val timer = FakeTimer()
  private val services = FakeIdeProfilerServices()
  private val transportService = FakeTransportService(timer, false)

  @get:Rule var myGrpcChannel = FakeGrpcChannel("SessionsManagerTestChannel", transportService)

  private lateinit var myProfilers: StudioProfilers

  @Before
  fun setup() {
    myProfilers = StudioProfilers(ProfilerClient(myGrpcChannel.channel), services, FakeTimer())
  }

  @Test
  fun testOngoingCapture() {
    val ongoingInfo = HeapDumpInfo.newBuilder().setStartTime(1).setEndTime(Long.MAX_VALUE).build()
    val ongoingArtifact =
      HprofSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), Common.SessionMetaData.getDefaultInstance(), ongoingInfo)
    assertThat(ongoingArtifact.isOngoing).isTrue()

    val finishedInfo = HeapDumpInfo.newBuilder().setStartTime(1).setEndTime(2).build()
    val finishedArtifact =
      HprofSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), Common.SessionMetaData.getDefaultInstance(), finishedInfo)
    assertThat(finishedArtifact.isOngoing).isFalse()
  }

  @Test
  fun testSubtitle() {
    val ongoingInfo = HeapDumpInfo.newBuilder().setStartTime(1).setEndTime(Long.MAX_VALUE).build()
    val finishedInfo = HeapDumpInfo.newBuilder().setStartTime(TimeUnit.SECONDS.toNanos(5)).setEndTime(TimeUnit.SECONDS.toNanos(10)).build()

    val ongoingCaptureArtifact =
      HprofSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), Common.SessionMetaData.getDefaultInstance(), ongoingInfo)
    assertThat(ongoingCaptureArtifact.subtitle).isEqualTo(SessionArtifact.CAPTURING_SUBTITLE)

    val finishedCaptureArtifact =
      HprofSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), Common.SessionMetaData.getDefaultInstance(), finishedInfo)
    assertThat(finishedCaptureArtifact.subtitle).isEqualTo("00:00:05.000")
  }

  @Test
  fun testExportExtensionOfImportedRecording() {
    // An imported recording is named after the file it came from, whatever heap dump format that was.
    assertThat(artifactOfSessionNamed("memory-20260918T070646.hprof").exportExtension).isEqualTo("hprof")
    assertThat(artifactOfSessionNamed("capture.prof").exportExtension).isEqualTo("prof")
    assertThat(artifactOfSessionNamed("memory-20260918T070840.perfetto-java-heap-dump").exportExtension)
      .isEqualTo("perfetto-java-heap-dump")
    // Case is not significant, the format is.
    assertThat(artifactOfSessionNamed("capture.HPROF").exportExtension).isEqualTo("hprof")
  }

  @Test
  fun testExportExtensionOfLiveArtRecording() {
    // A live ART heap dump is named after the process, so there is no format to recover and HPROF is assumed.
    assertThat(artifactOfSessionNamed("com.example.myapp").exportExtension).isEqualTo("hprof")
    assertThat(artifactOfSessionNamed("FakeProcess").exportExtension).isEqualTo("hprof")
    assertThat(
        HprofSessionArtifact(
            myProfilers,
            Common.Session.getDefaultInstance(),
            Common.SessionMetaData.getDefaultInstance(),
            HeapDumpInfo.getDefaultInstance(),
          )
          .exportExtension
      )
      .isEqualTo("hprof")
  }

  @Test
  fun testSessionArtifactsListHeapGraphsAsHeapDumps() {
    val session = ProfilersTestData.SESSION_DATA.toBuilder().setStartTimestamp(0).setEndTimestamp(Long.MAX_VALUE).build()
    addEndedMemoryTrace(session, 10, 20, PerfettoTraceConfigBuilders.JAVA_HEAP_GRAPH_DATA_SOURCE_NAME)
    addEndedMemoryTrace(session, 30, 40, "android.heapprofd")

    // A heap graph is listed as a Heap Dump that exports in its own format. A Native Allocations recording is not a Heap Dump.
    val artifacts = HprofSessionArtifact.getSessionArtifacts(myProfilers, session, Common.SessionMetaData.getDefaultInstance())
    assertThat(artifacts.map { it as HprofSessionArtifact }.map { it.artifactProto to it.exportExtension })
      .containsExactly(HeapDumpInfo.newBuilder().setStartTime(10).setEndTime(20).build() to "perfetto-java-heap-dump")
  }

  private fun artifactOfSessionNamed(sessionName: String) =
    HprofSessionArtifact(
      myProfilers,
      Common.Session.getDefaultInstance(),
      Common.SessionMetaData.newBuilder().setSessionName(sessionName).build(),
      HeapDumpInfo.newBuilder().setStartTime(1).setEndTime(2).build(),
    )

  /** Adds the end event of a Perfetto recording, reported as a memory trace, whose config uses [dataSource]. */
  private fun addEndedMemoryTrace(session: Common.Session, fromTimestamp: Long, toTimestamp: Long, dataSource: String) {
    val config =
      PerfettoConfig.TraceConfig.newBuilder()
        .addDataSources(
          PerfettoConfig.TraceConfig.DataSource.newBuilder().setConfig(PerfettoConfig.DataSourceConfig.newBuilder().setName(dataSource))
        )
        .build()
    val info =
      Trace.TraceInfo.newBuilder()
        .setFromTimestamp(fromTimestamp)
        .setToTimestamp(toTimestamp)
        .setConfiguration(Trace.TraceConfiguration.newBuilder().setPerfettoOptions(config))
        .build()
    transportService.addEventToStream(
      session.streamId,
      Common.Event.newBuilder()
        .setTimestamp(toTimestamp)
        .setGroupId(fromTimestamp)
        .setKind(Common.Event.Kind.MEMORY_TRACE)
        .setPid(session.pid)
        .setIsEnded(true)
        .setTraceData(Trace.TraceData.newBuilder().setTraceEnded(Trace.TraceData.TraceEnded.newBuilder().setTraceInfo(info)))
        .build(),
    )
  }
}
