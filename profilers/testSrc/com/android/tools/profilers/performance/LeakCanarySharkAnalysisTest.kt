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
package com.android.tools.profilers.performance

import com.android.testutils.TestUtils.resolveWorkspacePath
import com.android.tools.perflogger.Benchmark
import com.android.tools.perflogger.Metric
import com.android.tools.profilers.leakcanary.SharkHostAnalyzer
import java.time.Instant
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess

/**
 * Perfgate test for the LeakCanary task's host-side analysis: a full Shark analysis (leak detection plus retained size computation) of a
 * heap dump via [SharkHostAnalyzer.analyze].
 *
 * Each heap dump is first analyzed once untimed, so that class loading and JIT compilation stay out of the samples, then [SAMPLES] times,
 * with every run logged as a sample of the same metric. There is no regression check yet, so the results don't raise Perfgate alerts.
 */
class LeakCanarySharkAnalysisTest {
  private val timeBenchmark = Benchmark.Builder("LeakCanary Shark Analysis Time (millis)").setProject("Android Studio Profilers").build()

  @Test
  fun `measure shark analysis of single leak heap dump`() {
    testFile("single_leak", expectedLeaks = 1)
  }

  @Test
  fun `measure shark analysis of multi leak heap dump`() {
    testFile("multi_leak", expectedLeaks = 10)
  }

  private fun testFile(name: String, expectedLeaks: Int) {
    val file = resolveWorkspacePath("tools/adt/idea/profilers/testData/hprofs/$name.hprof").toFile()
    val metric = Metric("$name-Analyze")
    // Untimed warm-up run, to keep class loading and JIT compilation out of the samples.
    SharkHostAnalyzer().analyze(file) {}
    val startTimestampMs = Instant.now().toEpochMilli()

    repeat(SAMPLES) { i ->
      // Collect the previous run's garbage now, so that it isn't collected while this run is timed.
      System.gc()
      val result: HeapAnalysis
      val elapsedMillis = measureTimeMillis { result = SharkHostAnalyzer().analyze(file) {} }

      // SharkHostAnalyzer returns a (fast) failure instead of throwing, so make sure we don't benchmark a failed analysis.
      assertTrue("Shark analysis of $name failed: $result", result is HeapAnalysisSuccess)
      assertEquals(expectedLeaks, (result as HeapAnalysisSuccess).applicationLeaks.size)

      // '+ i' guarantees unique timestamps, which are used as keys in Metric.commit().
      metric.addSamples(timeBenchmark, Metric.MetricSample(startTimestampMs + i, elapsedMillis))
    }
    metric.commit()
  }

  private companion object {
    const val SAMPLES = 5
  }
}
