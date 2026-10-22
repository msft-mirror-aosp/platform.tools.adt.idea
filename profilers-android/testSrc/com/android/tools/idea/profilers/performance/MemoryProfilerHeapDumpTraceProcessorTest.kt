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
package com.android.tools.idea.profilers.performance

import com.android.testutils.TestUtils.resolveWorkspacePath
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.adtui.model.Range
import com.android.tools.idea.profilers.perfetto.traceprocessor.TraceProcessorServiceImpl
import com.android.tools.idea.transport.TransportService
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.idea.transport.faketransport.TransportServiceTestImpl
import com.android.tools.perflogger.Benchmark
import com.android.tools.perflogger.Metric
import com.android.tools.profiler.perfetto.proto.TraceProcessor.QueryParameters
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.memory.MemoryProfiler
import com.android.tools.profilers.memory.adapters.CaptureObject
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.registerServiceInstance
import java.time.Instant
import kotlin.system.measureNanoTime
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Perfgate test for loading and querying an ART heap dump (hprof) through the Trace Processor backend, which is the default in
 * [FakeIdeProfilerServices]. [MemoryProfilerHeapDumpTest] covers the legacy perflib path on the same files, and the load metrics here are
 * logged to the same graphs so both backends can be compared side by side.
 *
 * Note: the memory metric only covers the Studio JVM. The heap graph itself lives in the out-of-process Trace Processor Daemon.
 */
class MemoryProfilerHeapDumpTraceProcessorTest {
  private val loadBenchmark =
    benchmarkMemoryAndTime(
      "Heap Dump",
      "TraceProcessor-Load-Capture",
      memoryAnalyzer = HEAP_DUMP_MEMORY_ANALYZER,
      timeAnalyzer = HEAP_DUMP_TIME_ANALYZER,
    )
  private val queryTimeBenchmark =
    Benchmark.Builder("Heap Dump Trace Processor Query Time (micros)").setProject("Android Studio Profilers").build()
  private val ideServices = FakeIdeProfilerServices()
  private val timer = FakeTimer()
  private val transportService = FakeTransportService(timer)
  private lateinit var traceProcessorService: TraceProcessorServiceImpl

  @get:Rule val appRule = ApplicationRule()

  @get:Rule val grpcChannel = FakeGrpcChannel(javaClass.simpleName, transportService)

  @Before
  fun setUp() {
    ApplicationManager.getApplication().registerServiceInstance(TransportService::class.java, TransportServiceTestImpl(transportService))
    // Use a real instance of the Trace Processor service instead of the fake one.
    traceProcessorService = TraceProcessorServiceImpl()
    ideServices.setTraceProcessorService(traceProcessorService)
  }

  @After
  fun tearDown() {
    // Shut down the daemon so we don't leave dangling processes.
    Disposer.dispose(traceProcessorService)
  }

  @Test
  fun `measure loading and querying of github heap dump`() {
    testFile("github")
  }

  @Test
  fun `measure loading and querying of sunflower heap dump`() {
    testFile("sunflower")
  }

  private fun testFile(name: String) {
    val file = resolveWorkspacePath("tools/adt/idea/profilers/testData/hprofs/performance/$name.hprof").toFile()
    val profilers = StudioProfilers(ProfilerClient(grpcChannel.channel), ideServices, timer)
    assertThat(profilers.sessionsManager.importSessionFromFile(file)).isTrue()
    val dumpInfo = MemoryProfiler.getHeapDumpsForSession(profilers.client, profilers.session, Range(Double.MIN_VALUE, Double.MAX_VALUE))[0]
    val capture =
      HeapDumpCaptureObject(profilers.client, profilers.session, dumpInfo, null, ideServices.featureTracker, ideServices) { file }
    try {
      // Load: spawns the daemon, parses the hprof, fetches the class overview and preloads the leak / bitmap filter instances.
      // load() returns true whenever the file exists, so check isError to make sure we didn't benchmark a failed load.
      loadBenchmark(name) { capture.load(null, null) }
      assertThat(capture.isError).isFalse()
      assertThat(capture.isTraceProcessor).isTrue()

      val traceId = dumpInfo.startTime
      // Expanding a class in the heap dump view: first page of instances in the default "app" heap sorted by retained size, as issued by
      // TraceProcessorHeapDumpClassSet. java.lang.String has more than a page of instances in every app heap dump.
      val instancesRequest =
        QueryParameters.HeapDumpInstancesParameters.newBuilder()
          .addClassNames("java.lang.String")
          .setOffset(0)
          .setLimit(INSTANCES_PAGE_SIZE)
          .setSortAttribute(QueryParameters.SortAttribute.SORT_RETAINED_SIZE)
          .setSortDescending(true)
          .setHeapName(CaptureObject.APP_HEAP_NAME)
          .build()
      timeQuery(name, "Instances-Page", validate = { assertThat(it.instanceList).hasSize(INSTANCES_PAGE_SIZE) }) {
        traceProcessorService.getInstances(traceId, instancesRequest, ideServices)
      }
    } finally {
      capture.unload()
    }
  }

  /**
   * Runs [work] [QUERY_WARMUPS] times to warm up, then [QUERY_SAMPLES] times timed, logging every timed run, in microseconds, as a sample
   * of the same metric. Warming up keeps one-time costs (e.g. the first, cold query) out of the samples.
   *
   * Perfgate aggregates the samples of each run by their minimum: interference from other work on the machine only ever adds time, so the
   * fastest sample is the most stable estimate of the query's cost.
   *
   * Each result is checked with [validate], outside the timed block, before its sample is logged. A failed Trace Processor query doesn't
   * throw: it logs a warning and quickly returns an empty result, which would become the minimum.
   */
  private fun <T> timeQuery(name: String, query: String, validate: (T) -> Unit, work: () -> T) {
    repeat(QUERY_WARMUPS) { validate(work()) }
    val metric = Metric("$name-$query-Query")
    // Samples are keyed by timestamp in the perfgate output, so give each one a distinct timestamp: queries can finish within the same
    // millisecond and would otherwise overwrite each other.
    val startTimestampMs = Instant.now().toEpochMilli()
    repeat(QUERY_SAMPLES) { i ->
      val result: T
      val elapsedMicros = measureNanoTime { result = work() } / 1_000
      validate(result)
      metric.addSamples(queryTimeBenchmark, Metric.MetricSample(startTimestampMs + i, elapsedMicros))
    }
    metric.setAnalyzers(queryTimeBenchmark, setOf(HEAP_DUMP_TIME_ANALYZER))
    metric.commit()
  }

  private companion object {
    // Mirrors LazyMemoryObjectTreeNode.NUM_CHILDREN_PER_PAGE (package-private), the page size the heap dump view requests.
    const val INSTANCES_PAGE_SIZE = 100
    const val QUERY_WARMUPS = 2
    const val QUERY_SAMPLES = 20
  }
}
