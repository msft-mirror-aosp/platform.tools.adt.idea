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
package com.android.tools.idea.gradle.project.sync.memory

import com.android.testutils.TestUtils
import com.android.tools.idea.gradle.project.sync.gradle.CaptureType
import com.android.tools.idea.gradle.project.sync.gradle.EventRecorder
import com.android.tools.idea.gradle.project.sync.gradle.HeapPeakTracker
import com.android.tools.idea.gradle.project.sync.gradle.MeasurementPluginConfig
import com.android.tools.idea.gradle.project.sync.gradle.MeasurementService
import com.android.tools.idea.gradle.project.sync.gradle.MeasurementServiceParams
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeasurementPluginHeapPeakTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @After
  fun tearDown() {
    HeapPeakTracker.stopBuild()
    MeasurementPluginConfig.outputPath = ""
    MeasurementPluginConfig.captureTypes = emptySet()
  }

  @Test
  fun tracksPostGcNonEdenHighWaterMarkAndIgnoresConcurrentNotifications() {
    val heapPools = setOf("G1 Eden Space", "G1 Survivor Space", "G1 Old Gen")
    HeapPeakTracker.resetForTest(initialBytes = 0L)

    // Evacuating young GC pause: Survivor + Old = 120 MiB + 900 MiB = 1,020 MiB (Eden should be ignored even if non-zero).
    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool =
        mapOf(
          "G1 Eden Space" to 64L * 1024 * 1024,
          "G1 Survivor Space" to 120L * 1024 * 1024,
          "G1 Old Gen" to 900L * 1024 * 1024,
          "Metaspace" to 250L * 1024 * 1024,
        ),
    )

    // Concurrent GC pause notification: fires without evacuating Eden and must not overwrite the high-water mark.
    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Concurrent GC",
      heapPoolNames = heapPools,
      afterUsageByPool =
        mapOf(
          "G1 Eden Space" to 2_048L * 1024 * 1024,
          "G1 Survivor Space" to 200L * 1024 * 1024,
          "G1 Old Gen" to 3_500L * 1024 * 1024,
        ),
    )

    // Subsequent evacuating pause after transient objects died: high-water mark must retain the 1,020 MiB peak.
    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool =
        mapOf(
          "G1 Eden Space" to 0L,
          "G1 Survivor Space" to 40L * 1024 * 1024,
          "G1 Old Gen" to 700L * 1024 * 1024,
        ),
    )

    assertThat(HeapPeakTracker.currentPeakHeapBytes()).isAtLeast(1_020L * 1024 * 1024)
    assertThat(HeapPeakTracker.currentPeakHeapBytes()).isLessThan(3_000L * 1024 * 1024)
  }

  @Test
  fun ensureStartedPreservesActiveBuildPeakAndRestartsAfterStopBuild() {
    val heapPools = setOf("G1 Eden Space", "G1 Survivor Space", "G1 Old Gen")
    HeapPeakTracker.resetForTest(initialBytes = 0L)

    // Simulate a warm Configuration Cache hit where MeasurementService.init calls ensureStarted() without beforeSettings.
    HeapPeakTracker.ensureStarted()
    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool = mapOf("G1 Survivor Space" to 150L * 1024 * 1024, "G1 Old Gen" to 1_250L * 1024 * 1024),
    )
    val expectedPeak = 1_400L * 1024 * 1024
    assertThat(HeapPeakTracker.currentPeakHeapBytes()).isAtLeast(expectedPeak)

    // Calling ensureStarted() again while the build is active must not reset the accumulated high-water mark.
    HeapPeakTracker.ensureStarted()
    assertThat(HeapPeakTracker.currentPeakHeapBytes()).isAtLeast(expectedPeak)

    // Once stopBuild() completes the build, a subsequent ensureStarted() starts a fresh build window.
    HeapPeakTracker.stopBuild()
    HeapPeakTracker.ensureStarted()
    assertThat(HeapPeakTracker.currentPeakHeapBytes()).isLessThan(expectedPeak)
  }

  @Test
  fun measurementServiceOnlyStartsHeapPeakTrackerWhenHeapPeakCaptureEnabled() {
    HeapPeakTracker.resetForTest(initialBytes = 0L)
    MeasurementPluginConfig.captureTypes = setOf(CaptureType.TIMESTAMP)
    object : MeasurementService() {
      override fun getParameters(): MeasurementServiceParams = throw UnsupportedOperationException()
    }
    assertThat(HeapPeakTracker.isBuildActive()).isFalse()

    MeasurementPluginConfig.captureTypes = setOf(CaptureType.HEAP_PEAK)
    object : MeasurementService() {
      override fun getParameters(): MeasurementServiceParams = throw UnsupportedOperationException()
    }
    assertThat(HeapPeakTracker.isBuildActive()).isTrue()
  }

  @Test
  fun currentPeakHeapBytesRatchetsHighWaterMarkAcrossCheckpoints() {
    val heapPools = setOf("G1 Eden Space", "G1 Survivor Space", "G1 Old Gen")
    HeapPeakTracker.resetForTest(initialBytes = 0L)

    // Humongous allocation present in Old Gen at CONFIGURATION_PEAK_HEAP before any GC pause runs.
    val configPeak = 4_200L * 1024 * 1024
    assertThat(HeapPeakTracker.currentPeakHeapBytes(currentNonEdenBytes = configPeak)).isEqualTo(configPeak)

    // Full GC at CONFIGURATION_FINISHED reclaims part of Old Gen, followed by smaller post-GC occupancy during sync.
    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool = mapOf("G1 Survivor Space" to 100L * 1024 * 1024, "G1 Old Gen" to 2_800L * 1024 * 1024),
    )

    // SYNC_PEAK_HEAP must retain at least the earlier CONFIGURATION_PEAK_HEAP high-water mark.
    assertThat(HeapPeakTracker.currentPeakHeapBytes(currentNonEdenBytes = 3_100L * 1024 * 1024)).isEqualTo(configPeak)
  }

  @Test
  fun captureHeapPeakWritesPeakFilesAndHistogramRuleRecordsAllSixMetrics() {
    val snapshotsDir = tempFolder.newFolder("snapshots")
    val testOutputsDir = tempFolder.newFolder("test_outputs").toPath()
    MeasurementPluginConfig.outputPath = snapshotsDir.absolutePath
    MeasurementPluginConfig.captureTypes = setOf(CaptureType.HEAP_HISTOGRAM, CaptureType.HEAP_PEAK)

    HeapPeakTracker.startBuild()
    val heapPools = setOf("G1 Eden Space", "G1 Survivor Space", "G1 Old Gen")
    val simulatedConfigPeak = 3_900L * 1024 * 1024
    val simulatedSyncPeak = 8_250L * 1024 * 1024

    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool = mapOf("G1 Survivor Space" to 300L * 1024 * 1024, "G1 Old Gen" to 3_600L * 1024 * 1024),
    )
    EventRecorder.captureHeapPeak(EventRecorder.CONFIGURATION_PEAK_HEAP_SUFFIX)

    HeapPeakTracker.onGcAfterUsage(
      gcName = "G1 Young Generation",
      heapPoolNames = heapPools,
      afterUsageByPool = mapOf("G1 Survivor Space" to 450L * 1024 * 1024, "G1 Old Gen" to 7_800L * 1024 * 1024),
    )
    EventRecorder.captureHeapPeak(EventRecorder.SYNC_PEAK_HEAP_SUFFIX)
    HeapPeakTracker.stopBuild()

    writeSyntheticHistogram(snapshotsDir, 1700000001000L, "CONFIGURATION_FINISHED", 3_400L * 1024 * 1024)
    writeSyntheticHistogram(snapshotsDir, 1700000002000L, "ANDROID_STARTED", 4_350L * 1024 * 1024)
    writeSyntheticHistogram(snapshotsDir, 1700000003000L, "ANDROID_FINISHED", 5_570L * 1024 * 1024)
    writeSyntheticHistogram(snapshotsDir, 1700000004000L, "SYNC_FINISHED", 5_280L * 1024 * 1024)

    val rule = CaptureSyncMemoryFromHistogramRule("TestProject", disableAnalyzers = false)
    rule.recordHistogramValues("TestProject", snapshotsDir, testOutputsDir)

    val movedFiles = testOutputsDir.toFile().listFiles()!!.map { it.name }
    val configPeakFile = movedFiles.single { it.endsWith("_CONFIGURATION_PEAK_HEAP.peak") }
    val syncPeakFile = movedFiles.single { it.endsWith("_SYNC_PEAK_HEAP.peak") }
    assertThat(testOutputsDir.resolve(configPeakFile).toFile().readText().trim().toLong()).isEqualTo(simulatedConfigPeak)
    assertThat(testOutputsDir.resolve(syncPeakFile).toFile().readText().trim().toLong()).isEqualTo(simulatedSyncPeak)
    assertThat(movedFiles).hasSize(6)

    val perfLogFiles = TestUtils.getTestOutputDir().toFile().walk().filter { it.isFile && it.extension == "json" }.associateBy { it.name }
    assertThat(perfLogFiles.getValue("TestProject_Configuration_Finished.json").readText()).contains("\"analyzers\"")
    assertThat(perfLogFiles.getValue("TestProject_Configuration_Peak_Heap.json").readText()).doesNotContain("\"analyzers\"")
    assertThat(perfLogFiles.getValue("TestProject_Sync_Peak_Heap.json").readText()).doesNotContain("\"analyzers\"")
  }

  private fun writeSyntheticHistogram(dir: File, timestampMs: Long, checkpoint: String, totalBytes: Long) {
    val file = dir.resolve("${timestampMs}_$checkpoint.histogram")
    file.writeText(
      """
      num     #instances         #bytes  class name (module)
      -------------------------------------------------------
         1:          1000       $totalBytes  [B (java.base)
      Total          1000       $totalBytes
      """
        .trimIndent()
    )
  }
}
