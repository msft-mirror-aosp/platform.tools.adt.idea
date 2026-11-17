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
import com.android.tools.profiler.proto.LeakCanary
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import perfetto.protos.PerfettoConfig

/** Checks the file name that the export dialog suggests for each kind of recording: its task, the time of export, and its format. */
class RecordingExportNameTest {

  private val timer = FakeTimer()
  private val transportService = FakeTransportService(timer)
  private val session = Common.Session.newBuilder().setSessionId(1).build()

  @get:Rule var grpcChannel = FakeGrpcChannel("RecordingExportNameTestChannel", transportService)

  private lateinit var profilers: StudioProfilers

  @Before
  fun setup() {
    profilers = StudioProfilers(ProfilerClient(grpcChannel.channel), FakeIdeProfilerServices(), timer)
  }

  @Test
  fun systemTrace() {
    val config = Trace.TraceConfiguration.newBuilder().setPerfettoOptions(PerfettoConfig.TraceConfig.getDefaultInstance()).build()
    val artifact = SessionArtifactUtils.createCpuCaptureSessionArtifactWithConfig(profilers, session, 1, 1, config)
    assertSuggestedName(artifact, "system-trace", "trace")
  }

  @Test
  fun heapDump() {
    assertSuggestedName(SessionArtifactUtils.createHprofSessionArtifact(profilers, session, 1, 2), "heap-dump", "hprof")
  }

  @Test
  fun nativeAllocations() {
    assertSuggestedName(SessionArtifactUtils.createHeapProfdSessionArtifact(profilers, session, 1, 2), "native-allocations", "heapprofd")
  }

  @Test
  fun javaKotlinAllocations() {
    assertSuggestedName(SessionArtifactUtils.createAllocationSessionArtifact(profilers, session, 1, 2), "jk-allocations", "asdb")
  }

  @Test
  fun legacyJavaKotlinAllocations() {
    assertSuggestedName(SessionArtifactUtils.createLegacyAllocationsSessionArtifact(profilers, session, 1, 2), "jk-allocations", "alloc")
  }

  @Test
  fun liveView() {
    assertSuggestedName(LiveViewSessionArtifact(profilers, session, Common.SessionMetaData.getDefaultInstance()), "live-telemetry", "asdb")
  }

  @Test
  fun leakCanary() {
    val artifact =
      SessionArtifactUtils.createLeakCanarySessionArtifact(profilers, session, LeakCanary.LeakCanaryAnalysisStatus.getDefaultInstance())
    assertSuggestedName(artifact, "leakcanary", "asdb")
  }

  @Test
  fun suggestedNameHasTheTimeOfExport() {
    val artifact = SessionArtifactUtils.createHprofSessionArtifact(profilers, session, 1, 2)

    val before = System.currentTimeMillis()
    val name = artifact.exportableName
    val after = System.currentTimeMillis()

    assertThat(name)
      .isAnyOf(
        ProfilerCaptureFileUtils.getExportFileName(ProfilerTaskType.HEAP_DUMP, before),
        ProfilerCaptureFileUtils.getExportFileName(ProfilerTaskType.HEAP_DUMP, after),
      )
  }

  private fun assertSuggestedName(artifact: ExportableArtifact, taskPart: String, extension: String) {
    assertThat("${artifact.exportableName}.${artifact.exportExtension}").matches("$taskPart-$TIME\\.$extension")
  }

  private companion object {
    /** The time in a suggested name, e.g. `20261116T144522`. */
    const val TIME = "\\d{8}T\\d{6}"
  }
}
