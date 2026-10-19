/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.profilers.tasks.analytics

import com.android.tools.profiler.proto.Memory
import com.android.tools.profiler.proto.Memory.TrackStatus
import com.android.tools.profiler.proto.Trace
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.LeakCanaryTaskMetadata.LeakAnalysis
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.AllocationTrackStatus
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.HeapDumpStatus
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.LeakCanaryProcessingStatus
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.LeakCanaryStartStatus
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.TraceStartStatus
import com.google.wireless.android.sdk.stats.TaskFailedMetadata.TraceStopStatus
import org.junit.Test

class TaskMetadataMappersTest {

  @Test
  fun testTrackStatusToStatsProto() {
    val trackStatus = TrackStatus.newBuilder().setStatus(TrackStatus.Status.SUCCESS).setStartTime(123456789L).build()

    val statsProto = trackStatus.toStatsProto()

    assertThat(statsProto.status).isEqualTo(AllocationTrackStatus.Status.SUCCESS)
    assertThat(statsProto.startTimeNs).isEqualTo(123456789L)
  }

  @Test
  fun testTraceStopStatusToStatsProto() {
    val traceStopStatus =
      Trace.TraceStopStatus.newBuilder()
        .setStatus(Trace.TraceStopStatus.Status.SUCCESS)
        .setErrorCode(10L)
        .setStoppingDurationNs(5000L)
        .build()

    val statsProto = traceStopStatus.toStatsProto()

    assertThat(statsProto.status).isEqualTo(TraceStopStatus.Status.SUCCESS)
    assertThat(statsProto.errorCode).isEqualTo(10L)
    assertThat(statsProto.stoppingDurationNs).isEqualTo(5000L)
  }

  @Test
  fun testTraceStartStatusToStatsProto() {
    val traceStartStatus =
      Trace.TraceStartStatus.newBuilder()
        .setStatus(Trace.TraceStartStatus.Status.SUCCESS)
        .setErrorCode(20L)
        .setStartTimeNs(987654321L)
        .build()

    val statsProto = traceStartStatus.toStatsProto()

    assertThat(statsProto.status).isEqualTo(TraceStartStatus.Status.SUCCESS)
    assertThat(statsProto.errorCode).isEqualTo(20L)
    assertThat(statsProto.startTimeNs).isEqualTo(987654321L)
  }

  @Test
  fun testHeapDumpStatusToStatsProto() {
    val heapDumpStatus =
      Memory.HeapDumpStatus.newBuilder().setStatus(Memory.HeapDumpStatus.Status.SUCCESS).setStartTime(1122334455L).build()

    val statsProto = heapDumpStatus.toStatsProto()

    assertThat(statsProto.status).isEqualTo(HeapDumpStatus.Status.SUCCESS)
    assertThat(statsProto.startTimeNs).isEqualTo(1122334455L)
  }

  @Test
  fun testUnrecognizedTrackStatus() {
    val trackStatus =
      TrackStatus.newBuilder()
        .setStatusValue(100) // Arbitrary unrecognized value
        .build()

    val statsProto = trackStatus.toStatsProto()
    assertThat(statsProto.status).isEqualTo(AllocationTrackStatus.Status.UNRECOGNIZED)
  }

  @Test
  fun testLeakCanaryUiActionToStatsProto() {
    for (action in LeakCanaryUiAction.entries) {
      val statsProto = action.toStatsProto()
      assertThat(statsProto.name).isEqualTo(action.name)
    }
  }

  @Test
  fun testLeakCanaryStartErrorCodeToStatsProto() {
    for (errorCode in LeakCanaryStartErrorCode.entries) {
      val statsProto = errorCode.toStatsProto()
      assertThat(statsProto.errorCode).isEqualTo(LeakCanaryStartStatus.ErrorCode.valueOf(errorCode.name))
    }
  }

  @Test
  fun testLeakCanaryProcessingErrorCodeToStatsProto() {
    for (errorCode in LeakCanaryProcessingErrorCode.entries) {
      val statsProto = errorCode.toStatsProto()
      assertThat(statsProto.errorCode).isEqualTo(LeakCanaryProcessingStatus.ErrorCode.valueOf(errorCode.name))
    }
  }

  @Test
  fun testLeakCanaryLeakAnalysisToStatsProto() {
    val populatedAnalysis =
      LeakCanaryLeakAnalysis(
        retainedObjectsCount = 12,
        occurrencesCount = 3,
        estimatedMemoryLeakedBytes = 4096L,
        leakingNoRows = 4,
        leakingMaybeRows = 2,
        leakingYesRows = 1,
        heapDumpAnalysisTimeMs = 1500L,
        totalRecordingTimeMs = 8000L,
        isLibraryLeak = true,
        hprofFileSizeBytes = 1048576L,
        hprofDownloadDurationMs = 350L,
      )

    val statsProto = populatedAnalysis.toStatsProto()
    assertThat(statsProto.retainedObjectsCount).isEqualTo(12)
    assertThat(statsProto.occurrencesCount).isEqualTo(3)
    assertThat(statsProto.estimatedMemoryLeakedBytes).isEqualTo(4096L)
    assertThat(statsProto.leakingNoRows).isEqualTo(4)
    assertThat(statsProto.leakingMaybeRows).isEqualTo(2)
    assertThat(statsProto.leakingYesRows).isEqualTo(1)
    assertThat(statsProto.heapDumpAnalysisTimeMs).isEqualTo(1500L)
    assertThat(statsProto.totalRecordingTimeMs).isEqualTo(8000L)
    assertThat(statsProto.isLibraryLeak).isTrue()
    assertThat(statsProto.hprofFileSizeBytes).isEqualTo(1048576L)
    assertThat(statsProto.hprofDownloadDurationMs).isEqualTo(350L)
  }

  @Test
  fun testEmptyLeakCanaryLeakAnalysisToStatsProto() {
    val emptyStatsProto = LeakCanaryLeakAnalysis().toStatsProto()
    assertThat(emptyStatsProto).isEqualTo(LeakAnalysis.getDefaultInstance())
  }
}
