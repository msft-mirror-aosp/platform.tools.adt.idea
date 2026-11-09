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
package com.android.tools.profilers.leakcanary

import com.android.testutils.TestUtils
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.adtui.model.Range
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.idea.transport.faketransport.commands.CommandHandler
import com.android.tools.leakcanarylib.data.Analysis
import com.android.tools.leakcanarylib.data.AnalysisFailure
import com.android.tools.leakcanarylib.data.AnalysisSuccess
import com.android.tools.leakcanarylib.data.GcRootType
import com.android.tools.leakcanarylib.data.Leak
import com.android.tools.leakcanarylib.data.LeakTrace
import com.android.tools.leakcanarylib.data.LeakTraceNodeType
import com.android.tools.leakcanarylib.data.LeakType
import com.android.tools.leakcanarylib.data.LeakingStatus
import com.android.tools.leakcanarylib.data.Node
import com.android.tools.leakcanarylib.data.ReferencingField
import com.android.tools.profiler.proto.Commands
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.LeakCanary
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisStatus
import com.android.tools.profilers.FakeFeatureTracker
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.Notification
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.WithFakeTimer
import com.android.tools.profilers.cpu.config.LeakCanaryConfiguration
import com.android.tools.profilers.cpu.config.LeakCanaryMode
import com.android.tools.profilers.cpu.config.ProfilingConfiguration
import com.android.tools.profilers.tasks.analytics.LeakCanaryProcessingErrorCode
import com.android.tools.profilers.tasks.analytics.LeakCanaryStartErrorCode
import com.android.tools.profilers.tasks.analytics.LeakCanaryUiAction
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.UsefulTestCase.assertEmpty
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LeakCanaryModelTest : WithFakeTimer {
  @get:org.junit.Rule val applicationRule = ApplicationRule()
  override val timer = FakeTimer()
  private val transportService = FakeTransportService(timer)

  /**
   * Executes a blocking block of code (like stage.startListening()) on a background thread while continuously polling the FakeTimer and
   * transportPoller on the main thread. This prevents deadlocks when the background task awaits a transport event.
   */
  private fun runWithPolling(block: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit { block() }
    while (!future.isDone) {
      timer.tick(FakeTimer.ONE_SECOND_IN_NS)
      profilers.transportPoller.poll()
      Thread.sleep(10)
    }
    future.get()
    executor.shutdown()
  }

  @Rule @JvmField val grpcChannel = FakeGrpcChannel("LeakCanaryModelTestChannel", transportService)
  private lateinit var profilers: StudioProfilers
  private lateinit var stage: LeakCanaryModel
  private lateinit var ideProfilerServices: FakeIdeProfilerServices
  private lateinit var mockHeapDumper: LeakCanaryHeapDumper
  private val extraConfigs = mutableListOf<ProfilingConfiguration>()
  private var customInsightFlow: Flow<String>? = null

  @Before
  fun setup() {
    customInsightFlow = null
    ideProfilerServices =
      object : FakeIdeProfilerServices() {
        override fun getTaskCpuProfilerConfigs(apiLevel: Int): List<ProfilingConfiguration> {
          return super.getTaskCpuProfilerConfigs(apiLevel) + extraConfigs
        }

        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return customInsightFlow ?: super.fetchLeakInsight(rawTrace)
        }
      }
    profilers = StudioProfilers(ProfilerClient(grpcChannel.channel), ideProfilerServices, timer)

    // Spy on profilers to mock the current process and session, which are required
    // by the LeakCanary model to attach the JVMTI agent and fetch thresholds.
    profilers = org.mockito.Mockito.spy(profilers)
    org.mockito.Mockito.`when`(profilers.process).thenReturn(FakeTransportService.FAKE_PROCESS)
    val mockSession =
      Common.Session.newBuilder()
        .setPid(FakeTransportService.FAKE_PROCESS.pid)
        .setStreamId(FakeTransportService.FAKE_DEVICE_ID)
        .setSessionId(1L)
        .setStartTimestamp(timer.currentTimeNs)
        .setEndTimestamp(Long.MAX_VALUE)
        .build()
    org.mockito.Mockito.`when`(profilers.session).thenReturn(mockSession)

    // Register default command handlers for the JVMTI agent attachment sequence
    // and device mode setup to prevent tests from failing during pre-flight checks.
    val handler = FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0)
    transportService.setCommandHandler(Commands.Command.CommandType.ATTACH_AGENT, handler)
    transportService.setCommandHandler(Commands.Command.CommandType.SET_STUDIO_LEAKCANARY_MODE, handler)
    transportService.setCommandHandler(Commands.Command.CommandType.FORCE_DUMP_LEAKCANARY_ON_DEVICE, handler)

    mockHeapDumper = mock(LeakCanaryHeapDumper::class.java)
    stage = LeakCanaryModel(profilers, mockHeapDumper, Dispatchers.Unconfined)
  }

  @Test
  fun `Leak canary stage enter - success case with multiple events`() {
    val startTime = System.currentTimeMillis()
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(
        timer,
        profilers,
        listOf(
          "SingleApplicationLeak.txt", // 1 application leak
          "SingleApplicationLeakAnalyzeCmd.txt", // 1 application leak
          "MultiApplicationLeak.txt", // 2 application leak with different signature
          "NoLeak.txt",
        ),
        startTime,
      ),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(
        timer,
        profilers,
        listOf(
          "SingleApplicationLeak.txt", // 1 application leak
          "SingleApplicationLeakAnalyzeCmd.txt", // 1 application leak
          "MultiApplicationLeak.txt", // 2 application leak with different signature
          "NoLeak.txt",
        ),
        startTime,
      ),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    runWithPolling { stage.startListening() }
    // Wait for listener to receive events
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Verify leak events
    assertEquals(4, stage.leaks.value.size) // 4 events are sent
    stage.stopListening()
    // First leak is selected by default
    assertEquals(stage.leaks.value[0], stage.selectedLeak.value)
    // After stage exit we get all events
    assertEquals(4, stage.leaks.value.size) // 4 events are sent
    assertEquals(4, stage.leaks.value.size)
    // First leak is selected by default
    assertEquals(stage.leaks.value[0], stage.selectedLeak.value)
  }

  @Test
  fun `loadFromPastSession deserializes structured LeakCanaryAnalysisData proto with multiple occurrences`() {
    ideProfilerServices.enableLeakCanaryOccurrences(true)
    val trace1 =
      LeakTrace(
        gcRootType = GcRootType.JAVA_FRAME,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.OccurrenceOneActivity",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Destroyed",
              retainedHeapSize = "1.5 kB",
              retainedObjectCount = 12,
              notes = emptyList(),
              referencingField = null,
            )
          ),
      )
    val trace2 =
      LeakTrace(
        gcRootType = GcRootType.JNI_GLOBAL,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.OccurrenceTwoActivity",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Destroyed",
              retainedHeapSize = "2.5 kB",
              retainedObjectCount = 24,
              notes = emptyList(),
              referencingField = null,
            )
          ),
      )
    val multiOccurrenceLeak =
      Leak(
        type = LeakType.APPLICATION_LEAKS,
        retainedByteSize = 4000,
        signature = "proto_sig_123",
        leakTraceCount = 2,
        displayedLeakTrace = listOf(trace1, trace2),
      )
    val analysisSuccess =
      AnalysisSuccess(
        heapDumpFile = File("/tmp/proto_test.hprof"),
        createdAtTimeMillis = 1000L,
        dumpDurationMillis = 50L,
        analysisDurationMillis = 150L,
        metadata = mapOf("LeakCanary version" to "2.14"),
        leaks = listOf(multiOccurrenceLeak),
      )

    val eventTimestamp = 5000L
    val structuredProto = LeakCanaryProtoAdapter.toProto(analysisSuccess)
    val event =
      Common.Event.newBuilder()
        .setGroupId(profilers.session.pid.toLong())
        .setPid(profilers.session.pid)
        .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS)
        .setLeakcanaryAnalysis(structuredProto)
        .setTimestamp(eventTimestamp)
        .build()
    transportService.addEventToStream(profilers.session.streamId, event)

    stage.loadFromPastSession(1000L, 10000L, profilers.session)

    assertEquals(1, stage.leaks.value.size)
    val loadedLeak = stage.leaks.value.first()
    assertEquals("proto_sig_123", loadedLeak.signature)
    assertEquals(2, loadedLeak.leakTraceCount)
    assertEquals(2, loadedLeak.displayedLeakTrace.size)
    assertEquals("com.example.OccurrenceOneActivity", loadedLeak.displayedLeakTrace[0].nodes.first().className)
    assertEquals("com.example.OccurrenceTwoActivity", loadedLeak.displayedLeakTrace[1].nodes.first().className)

    // Verify that if the same structured Protobuf session is loaded while the feature flag is OFF,
    // it still deserializes without data loss and clamps displayedLeakTrace to 1 occurrence.
    stage.clearLeaks()
    ideProfilerServices.enableLeakCanaryOccurrences(false)
    stage.loadFromPastSession(1000L, 10000L, profilers.session)

    assertEquals(1, stage.leaks.value.size)
    val clampedLeak = stage.leaks.value.first()
    assertEquals("proto_sig_123", clampedLeak.signature)
    assertEquals(1, clampedLeak.displayedLeakTrace.size)
    assertEquals("com.example.OccurrenceOneActivity", clampedLeak.displayedLeakTrace.first().nodes.first().className)
  }

  @Test
  fun `Leak canary stage enter - load from past`() {
    val startTime = System.currentTimeMillis()
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(
        timer,
        profilers,
        listOf(
          "SingleApplicationLeak.txt", // 1 application leak
          "SingleApplicationLeakAnalyzeCmd.txt", // 1 application leak
          "MultiApplicationLeak.txt", // 2 application leak with different signature
          "NoLeak.txt",
        ),
        startTime,
      ),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    runWithPolling { stage.startListening() }
    // Wait for listener to receive events
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    stage.stopListening()
    val endTime = System.currentTimeMillis()
    assertEquals(4, stage.leaks.value.size)

    stage.clearLeaks()
    assertEmpty(stage.leaks.value)

    stage.loadFromPastSession(startTime, endTime, profilers.session)
    assertEquals(4, stage.leaks.value.size)

    // First leak is selected by default
    assertEquals(stage.leaks.value[0], stage.selectedLeak.value)
  }

  @Test
  fun `Leak canary stage enter - Invalid leaks are skipped`() {
    val startTime = System.currentTimeMillis()
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf("SingleApplicationLeak.txt", "InValidLeak.txt"), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    runWithPolling { stage.startListening() }
    // Wait for listener to receive events
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Verify leakEvents
    assertEquals(1, stage.leaks.value.size) // 1 event is sent
    assertTrue(stage.isRecording.value)
    stage.stopListening()
    // After stage exit we get all events
    assertEquals(1, stage.leaks.value.size) // 1 event are sent
    assertEquals(1, stage.leaks.value.size)
    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `Leak canary stage enter - no leak events`() {
    val startTime = System.currentTimeMillis()
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    runWithPolling { stage.startListening() }
    // Wait for the listener to receive events
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Verify leakEvents
    assertEmpty(stage.leaks.value) // No events are sent
    stage.stopListening()
    // After stage exit we get all events
    assertEmpty(stage.leaks.value) // No events are sent
  }

  @Test
  fun `Leak canary stage enter - all leaks detected are not valid and test start and stop leakInfo events`() {
    val startTime = System.currentTimeMillis()
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(
        timer,
        profilers,
        listOf("InValidLeak.txt", "InValidLeak.txt", "InValidLeak.txt", "InValidLeak.txt"),
        startTime,
      ),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), startTime),
    )
    runWithPolling { stage.startListening() }
    // Wait for listener to receive events
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Verify leakEvents
    assertEmpty(stage.leaks.value) // 0 event are sent
    assertEquals(true, stage.isRecording.value)
    stage.stopListening()
    val endTime = System.currentTimeMillis()
    // After stage exit we get all events
    assertEmpty(stage.leaks.value) // 0 event are sent

    val leakInfoEvents =
      LeakCanaryModel.getLeakCanaryAnalysisInfo(profilers.client, profilers.session, Range(startTime.toDouble(), endTime.toDouble()))
    assertEquals(1, leakInfoEvents.size) // Fetching only ended events
    assertEquals(Common.Event.Kind.LEAKCANARY_ANALYSIS_STATUS, leakInfoEvents[0].kind)
    assertTrue(leakInfoEvents[0].isEnded)
    assertEquals(LeakCanary.LeakCanaryAnalysisEnded.Status.SUCCESS, leakInfoEvents[0].leakCanaryAnalysisStatus.analysisEnded.status)
    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `checkLeakCanaryThreshold updates state`() {
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", 10)

    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )

    stage.setLeakCanaryMode(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE)
    runWithPolling { stage.startListening() }
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    assertEquals(10, stage.retainedObjectThreshold.value)
    assertEquals(-1, ideProfilerServices.temporaryProfilerPreferences.getInt("LEAKCANARY_THRESHOLD", -1))
  }

  @Test
  fun `checkLeakCanaryThreshold fetches from command when missing`() {
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", -1)

    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0, retainedObjectThreshold = 7),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )

    stage.setLeakCanaryMode(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE)
    runWithPolling { stage.startListening() }
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)

    assertEquals(7, stage.retainedObjectThreshold.value)
    assertEquals(-1, ideProfilerServices.temporaryProfilerPreferences.getInt("LEAKCANARY_THRESHOLD", -1))
  }

  @Test
  fun `stopListening sends stop count tracking command in ON_DEVICE mode`() {
    assertStopCountTrackingSentOnce(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE)
  }

  @Test
  fun `stopListening sends stop count tracking command in ON_HOST mode`() {
    assertStopCountTrackingSentOnce(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST)
  }

  /** Starts and stops a recording in the given mode, and checks that the stop count tracking command was sent once. */
  private fun assertStopCountTrackingSentOnce(mode: Commands.StartLeakCanaryTaskData.LeakCanaryMode) {
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", 10)
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    val stopCountHandler = CountingCommandHandler(timer)
    transportService.setCommandHandler(Commands.Command.CommandType.STOP_LEAKCANARY_OBJECT_COUNT_TRACKING, stopCountHandler)

    stage.setLeakCanaryMode(mode)
    runWithPolling { stage.startListening() }
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    assertEquals(mode, stage.leakcanaryMode)
    assertEquals(0, stopCountHandler.count)

    stage.stopListening()

    assertEquals(1, stopCountHandler.count)
  }

  @Test
  fun `perfa events are used only in ON_DEVICE with the flag on`() {
    startOnDevice(occurrencesEnabled = true)
    assertTrue(stage.usesOnDeviceEvents)
    stage.stopListening()

    startOnDevice(occurrencesEnabled = false)
    assertFalse(stage.usesOnDeviceEvents)
  }

  @Test
  fun `on-device errors are ignored in ON_HOST with the flag on`() {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)
    assertFalse(stage.usesOnDeviceEvents)

    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_UNSUPPORTED_VERSION))

    assertTrue(stage.isRecording.value)
    assertEquals(null, ideProfilerServices.notification)
  }

  @Test
  fun `perfa count is shown in ON_DEVICE and does not force a heap dump`() {
    val forceDumpHandler = CountingCommandHandler(timer)
    transportService.setCommandHandler(Commands.Command.CommandType.FORCE_DUMP_LEAKCANARY_ON_DEVICE, forceDumpHandler)
    startOnDevice(occurrencesEnabled = true)

    sendPerfaEvent(countEvent(10)) // Above the threshold of 5.

    assertEquals(10, stage.objectRetainedCount.value)
    assertEquals(0, forceDumpHandler.count)
    assertFalse(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `perfa progress is shown in ON_DEVICE`() {
    startOnDevice(occurrencesEnabled = true)

    sendPerfaEvent(progressEvent(42))

    assertEquals(42, stage.analysisProgress.value)
  }

  @Test
  fun `perfa count and progress are ignored in ON_DEVICE with the flag off`() {
    startOnDevice(occurrencesEnabled = false)

    sendPerfaEvent(countEvent(3))
    sendPerfaEvent(progressEvent(42))

    assertEquals(0, stage.objectRetainedCount.value)
    assertEquals(0, stage.analysisProgress.value)
  }

  @Test
  fun `logcat and perfa analysis events are both used with perfa events`() {
    startOnDevice(occurrencesEnabled = true)

    // Logcat still sends leaks added by hand with the LeakCanary:Manual tag.
    sendPerfaEvent(FakeLeakCanaryCommandHandler.getLeakCanaryEvent(profilers, "SingleApplicationLeak.txt"))
    assertEquals(1, stage.leaks.value.size)

    sendPerfaEvent(structuredResultEvent())
    assertEquals(2, stage.leaks.value.size)
  }

  @Test
  fun `logcat analysis events are still used in ON_DEVICE with the flag off`() {
    startOnDevice(occurrencesEnabled = false)

    sendPerfaEvent(FakeLeakCanaryCommandHandler.getLeakCanaryEvent(profilers, "SingleApplicationLeak.txt"))

    assertEquals(1, stage.leaks.value.size)
  }

  @Test
  fun `perfa result keeps the count and saves the device LeakCanary version`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(countEvent(3))
    assertFalse(isSharkVersionEmitted())

    sendPerfaEvent(structuredResultEvent())

    assertEquals(1, stage.leaks.value.size)
    assertEquals(3, stage.objectRetainedCount.value)
    assertTrue(isSharkVersionEmitted())
  }

  @Test
  fun `logcat result resets the count in ON_DEVICE with the flag off`() {
    startOnDevice(occurrencesEnabled = false)
    stage.setObjectRetainedCount(3)

    sendPerfaEvent(FakeLeakCanaryCommandHandler.getLeakCanaryEvent(profilers, "SingleApplicationLeak.txt"))

    assertEquals(0, stage.objectRetainedCount.value)
  }

  @Test
  fun `perfa analysis failure keeps recording and shows no message`() {
    startOnDevice(occurrencesEnabled = true)

    val failure =
      AnalysisFailure(
        heapDumpFile = File("/tmp/test.hprof"),
        createdAtTimeMillis = 1000L,
        dumpDurationMillis = 50L,
        analysisDurationMillis = 150L,
        exception = Exception("Shark failed"),
      )

    sendPerfaEvent(analysisEvent(LeakCanaryProtoAdapter.toProto(failure)))

    assertTrue(stage.isRecording.value)
    assertEquals(null, ideProfilerServices.notification)
  }

  @Test
  fun `unsupported version error shows a message and stops`() {
    assertDeviceErrorStops(
      Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_UNSUPPORTED_VERSION,
      "Android Studio doesn't support this LeakCanary version. If you added LeakCanary yourself, use the version from studio-leakcanary instead.",
      LeakCanaryProcessingErrorCode.UNKNOWN_ERROR,
    )
  }

  @Test
  fun `heap dump failed error shows a warning and keeps recording`() {
    assertDeviceWarningKeepsRecording(
      Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED,
      "Heap dump failed on the device, so this heap dump has no leak results. Check that the device has enough free storage.",
      LeakCanaryProcessingErrorCode.HEAP_DUMP_GENERATION_FAILED,
    )
  }

  @Test
  fun `save failed error shows a warning and keeps recording`() {
    assertDeviceWarningKeepsRecording(
      Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED,
      "Couldn't save the LeakCanary analysis result on the device. Check that the device has enough free storage.",
      LeakCanaryProcessingErrorCode.UNKNOWN_ERROR,
    )
  }

  @Test
  fun `result read failed error shows a warning and keeps recording`() {
    assertDeviceWarningKeepsRecording(
      Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED,
      "Couldn't get the LeakCanary analysis result from the device, so this heap dump has no leak results.",
      LeakCanaryProcessingErrorCode.UNKNOWN_ERROR,
    )
  }

  @Test
  fun `heap dump failed error ends the wait after force dump`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)
    assertTrue(stage.isForceDumpExecuting.value)

    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED))

    assertFalse(stage.isForceDumpExecuting.value)
    assertEquals(null, onDeviceAnswerWaitNs())
    assertTrue(stage.isRecording.value)
  }

  @Test
  fun `progress sent before a save failed error is ignored`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)
    val errorTimestamp = nextEventTimestamp + 10

    // The error is handled first, and the older progress comes in a later poll.
    transportService.addEventToStream(
      profilers.session.streamId,
      deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED)
        .toBuilder()
        .setTimestamp(errorTimestamp)
        .build(),
    )
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    transportService.addEventToStream(
      profilers.session.streamId,
      progressEvent(98).toBuilder().setTimestamp(errorTimestamp - 1).build(),
    )
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)

    assertEquals(0, stage.analysisProgress.value)
    assertFalse(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `save failed error clears the progress`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(98))

    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED))

    assertEquals(0, stage.analysisProgress.value)
    assertTrue(stage.isRecording.value)
  }

  @Test
  fun `save failed error finishes a pending stop`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(98))
    stage.requestStopRecording()
    assertTrue(stage.isStopping.value)

    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED))

    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `the warning shows again each time the error comes`() {
    startOnDevice(occurrencesEnabled = true)
    val error = deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_HEAP_DUMP_FAILED)
    sendPerfaEvent(error)
    assertEquals("LeakCanary Warning", ideProfilerServices.notification?.title)

    ideProfilerServices.showNotification(Notification(Notification.Severity.INFO, "other", "other", null))
    sendPerfaEvent(error)

    assertEquals("LeakCanary Warning", ideProfilerServices.notification?.title)
    assertTrue(stage.isRecording.value)
  }

  @Test
  fun `on-device errors are ignored with the flag off`() {
    startOnDevice(occurrencesEnabled = false)

    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_SAVE_FAILED))
    sendPerfaEvent(deviceErrorEvent(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_ON_DEVICE_RESULT_READ_FAILED))

    assertTrue(stage.isRecording.value)
    assertEquals(null, ideProfilerServices.notification)
  }

  @Test
  fun `app context error sends only start failed telemetry and stops`() {
    val loggedErrors = sendOlderDeviceError(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_APP_CONTEXT_NULL)

    val tracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    assertEquals(LeakCanaryStartErrorCode.APP_CONTEXT_NULL, tracker.lastTaskStartFailedMetadata?.leakCanaryStartStatus)
    assertEquals(0, tracker.taskProcessingFailedCount)
    assertEquals(listOf("LeakCanary Fatal Error (UNKNOWN_ERROR): Failed to get Application Context on device."), loggedErrors)
    assertEquals("LeakCanary Task Failed", ideProfilerServices.notification?.title)
    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `logcat parsing error sends processing failed telemetry once`() {
    sendOlderDeviceError(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_LOGCAT_PARSING_FAILURE)

    val tracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    assertEquals(1, tracker.taskProcessingFailedCount)
    assertEquals(LeakCanaryProcessingErrorCode.PARSING_FAILURE, tracker.lastTaskProcessingFailedMetadata?.leakCanaryProcessingStatus)
    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `broadcast delivery error sends processing failed telemetry once`() {
    sendOlderDeviceError(Common.LeakCanaryDeviceError.ErrorType.LEAKCANARY_ERROR_BROADCAST_DELIVERY_FAILED)

    val tracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    assertEquals(1, tracker.taskProcessingFailedCount)
    assertEquals(
      LeakCanaryProcessingErrorCode.BROADCAST_DELIVERY_FAILED,
      tracker.lastTaskProcessingFailedMetadata?.leakCanaryProcessingStatus,
    )
    assertFalse(stage.isRecording.value)
  }

  @Test
  fun `force dump is enabled again after 60 seconds without an answer`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)

    stage.forceHeapDump(isUserInitiated = true)
    assertTrue(stage.isForceDumpExecuting.value)

    timer.tick(TimeUnit.SECONDS.toNanos(59))
    assertTrue(stage.isForceDumpExecuting.value)
    timer.tick(TimeUnit.SECONDS.toNanos(2))
    assertFalse(stage.isForceDumpExecuting.value)
    assertTrue(stage.isRecording.value)
  }

  @Test
  fun `progress starts the 60 second wait again`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)

    timer.tick(TimeUnit.SECONDS.toNanos(50))
    sendPerfaEvent(progressEvent(10)) // Also ticks 1 second.
    timer.tick(TimeUnit.SECONDS.toNanos(50))

    assertTrue(stage.isForceDumpExecuting.value)
    timer.tick(TimeUnit.SECONDS.toNanos(10))
    assertFalse(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `stop finishes after 60 seconds without an answer`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)

    stage.requestStopRecording() // Forces a heap dump first.
    assertTrue(stage.isStopping.value)

    timer.tick(TimeUnit.SECONDS.toNanos(59))
    assertTrue(stage.isRecording.value)
    timer.tick(TimeUnit.SECONDS.toNanos(2))
    assertFalse(stage.isRecording.value)
    assertFalse(stage.isStopping.value)
  }

  @Test
  fun `stop during a running analysis finishes after 60 seconds without an answer`() {
    // The stage gets a real task tracker only when it is entered. Without it, telemetry is dropped.
    profilers.stage = stage
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(30))

    stage.requestStopRecording() // No new heap dump, an analysis is already running.
    assertTrue(stage.isStopping.value)

    timer.tick(TimeUnit.SECONDS.toNanos(59))
    assertTrue(stage.isRecording.value)
    timer.tick(TimeUnit.SECONDS.toNanos(2))
    assertFalse(stage.isRecording.value)
    val tracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    assertEquals(LeakCanaryUiAction.CANCELLED_DURING_ANALYSIS, tracker.lastLeakCanaryUiAction)
  }

  @Test
  fun `stop finishes when the perfa result comes`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.requestStopRecording()

    sendPerfaEvent(structuredResultEvent())

    assertFalse(stage.isRecording.value)
    assertEquals(1, stage.leaks.value.size)
  }

  @Test
  fun `stop during a running analysis finishes when the perfa result comes`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(30))
    stage.requestStopRecording()
    assertTrue(stage.isStopping.value)

    sendPerfaEvent(structuredResultEvent())

    assertFalse(stage.isRecording.value)
    assertEquals(1, stage.leaks.value.size)
  }

  @Test
  fun `progress sent before the result does not show the analysis as running again`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)

    // Both arrive in the same poll. The result is handled first, because its listener was registered first.
    addPerfaEvent(progressEvent(98))
    addPerfaEvent(structuredResultEvent())
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)

    assertEquals(0, stage.analysisProgress.value)
    assertFalse(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `progress of the next analysis is shown after a result`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(98))
    sendPerfaEvent(structuredResultEvent())
    assertEquals(0, stage.analysisProgress.value)

    sendPerfaEvent(progressEvent(10))

    assertEquals(10, stage.analysisProgress.value)
  }

  @Test
  fun `timeout after force dump clears a stalled progress`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)
    sendPerfaEvent(progressEvent(40))

    timer.tick(TimeUnit.SECONDS.toNanos(61))

    assertEquals(0, stage.analysisProgress.value)
    assertFalse(stage.isForceDumpExecuting.value)
    assertTrue(stage.isRecording.value)
  }

  @Test
  fun `perfa analysis failure ends the wait and enables force dump again`() {
    startOnDevice(occurrencesEnabled = true)
    stage.setObjectRetainedCount(1)
    stage.requestStopRecording() // Forces a heap dump first.
    assertTrue(stage.isStopping.value)

    val failure =
      AnalysisFailure(
        heapDumpFile = File("/tmp/test.hprof"),
        createdAtTimeMillis = 1000L,
        dumpDurationMillis = 50L,
        analysisDurationMillis = 150L,
        exception = Exception("Shark failed"),
      )
    sendPerfaEvent(analysisEvent(LeakCanaryProtoAdapter.toProto(failure)))

    // The failure is an answer, so the pending Stop finishes right away.
    assertFalse(stage.isRecording.value)
    assertFalse(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `count at the threshold still forces a heap dump in ON_HOST with the flag on`() {
    assertOnHostCountForcesHeapDump(occurrencesEnabled = true)
  }

  @Test
  fun `count at the threshold still forces a heap dump in ON_HOST with the flag off`() {
    assertOnHostCountForcesHeapDump(occurrencesEnabled = false)
  }

  /** Starts ON_HOST and checks that Studio dumps the heap only when the count reaches the threshold. */
  private fun assertOnHostCountForcesHeapDump(occurrencesEnabled: Boolean) {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled)

    sendPerfaEvent(countEvent(stage.retainedObjectThreshold.value - 1))
    verify(mockHeapDumper, never()).triggerAndAnalyze()

    sendPerfaEvent(countEvent(stage.retainedObjectThreshold.value))
    verify(mockHeapDumper).triggerAndAnalyze()
  }

  @Test
  fun `no 60 second wait with the flag off`() {
    startOnDevice(occurrencesEnabled = false)
    stage.setObjectRetainedCount(1)
    stage.forceHeapDump(isUserInitiated = true)

    timer.tick(TimeUnit.SECONDS.toNanos(61))

    assertTrue(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `outdated library banner shows in ON_DEVICE with the flag on`() {
    saveVersionForSession("0.0.1")
    ideProfilerServices.setHasStudioLeakCanaryBuildFile(true)
    profilers.preferredProcessName = FakeTransportService.FAKE_PROCESS_NAME

    startOnDevice(occurrencesEnabled = true)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
    assertTrue(stage.canOpenBuildFile.value)
  }

  @Test
  fun `outdated library banner shows in ON_HOST with the flag on`() {
    saveVersionForSession("0.0.1")

    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
  }

  @Test
  fun `no outdated library banner with the flag off`() {
    saveVersionForSession("0.0.1")

    startOnDevice(occurrencesEnabled = false)

    assertEquals(null, stage.outdatedLibraryVersion.value)
  }

  @Test
  fun `no outdated library banner for the minimum version`() {
    saveVersionForSession(MIN_STUDIO_LEAKCANARY_VERSION)

    startOnDevice(occurrencesEnabled = true)

    assertEquals(null, stage.outdatedLibraryVersion.value)
  }

  @Test
  fun `no outdated library banner when no version was saved`() {
    startOnDevice(occurrencesEnabled = true)

    assertEquals(null, stage.outdatedLibraryVersion.value)
  }

  @Test
  fun `no Open build file link without a build file`() {
    saveVersionForSession("0.0.1")
    ideProfilerServices.setHasStudioLeakCanaryBuildFile(false)
    profilers.preferredProcessName = FakeTransportService.FAKE_PROCESS_NAME

    startOnDevice(occurrencesEnabled = true)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
    assertFalse(stage.canOpenBuildFile.value)
  }

  @Test
  fun `no Open build file link when the profiled app is not the project app`() {
    saveVersionForSession("0.0.1")
    ideProfilerServices.setHasStudioLeakCanaryBuildFile(true)
    profilers.preferredProcessName = "com.other.app"

    startOnDevice(occurrencesEnabled = true)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
    assertFalse(stage.canOpenBuildFile.value)
  }

  @Test
  fun `Open build file link resets on the next recording`() {
    saveVersionForSession("0.0.1")
    ideProfilerServices.setHasStudioLeakCanaryBuildFile(true)
    profilers.preferredProcessName = FakeTransportService.FAKE_PROCESS_NAME
    startOnDevice(occurrencesEnabled = true)
    assertTrue(stage.canOpenBuildFile.value)
    stage.stopListening()

    saveVersionForSession(MIN_STUDIO_LEAKCANARY_VERSION)
    startOnDevice(occurrencesEnabled = true)

    assertEquals(null, stage.outdatedLibraryVersion.value)
    assertFalse(stage.canOpenBuildFile.value)
  }

  @Test
  fun `version in the threshold the model asks for is saved and checked`() {
    startWithModelThresholdFetch(occurrencesEnabled = true, threshold = 5)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
    assertEquals(
      "0.0.1",
      loadStudioLeakCanaryVersion(ideProfilerServices.temporaryProfilerPreferences, profilers.session.streamId, profilers.session.pid),
    )
  }

  @Test
  fun `version in the threshold the model asks for is ignored with the flag off`() {
    startWithModelThresholdFetch(occurrencesEnabled = false, threshold = 5)

    assertEquals(null, stage.outdatedLibraryVersion.value)
    assertEquals(
      null,
      loadStudioLeakCanaryVersion(ideProfilerServices.temporaryProfilerPreferences, profilers.session.streamId, profilers.session.pid),
    )
  }

  @Test
  fun `version in the threshold the model asks for is ignored when the library did not answer`() {
    startWithModelThresholdFetch(occurrencesEnabled = true, threshold = 0)

    assertEquals(null, stage.outdatedLibraryVersion.value)
    assertEquals(
      null,
      loadStudioLeakCanaryVersion(ideProfilerServices.temporaryProfilerPreferences, profilers.session.streamId, profilers.session.pid),
    )
  }

  /** Starts ON_DEVICE with no saved threshold, so the model asks for it. The reply has [threshold] and the version "0.0.1". */
  private fun startWithModelThresholdFetch(occurrencesEnabled: Boolean, threshold: Int) {
    ideProfilerServices.enableLeakCanaryOccurrences(occurrencesEnabled)
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", -1)
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0, retainedObjectThreshold = threshold, studioLeakCanaryVersion = "0.0.1"),
    )

    stage.setLeakCanaryMode(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE)
    runWithPolling { stage.startListening() }
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // The reply reached the model.
    assertEquals(threshold, stage.retainedObjectThreshold.value)
  }

  @Test
  fun `config banner waits while the outdated library banner shows`() {
    extraConfigs.add(LeakCanaryConfiguration("LeakCanary").apply { source = LeakCanaryMode.STUDIO })
    saveVersionForSession("0.0.1")

    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)

    assertEquals("0.0.1", stage.outdatedLibraryVersion.value)
    assertFalse(stage.isBannerVisible.value)

    stage.dismissOutdatedLibraryBanner()

    assertEquals(null, stage.outdatedLibraryVersion.value)
    assertTrue(stage.isBannerVisible.value)
  }

  @Test
  fun `open build file opens the studio-leakcanary declaration`() {
    stage.openBuildFile()

    assertEquals(1, ideProfilerServices.openStudioLeakCanaryDeclarationCount)
  }

  @Test
  fun `Manual trace shows its leaks in ON_HOST with the flag on`() {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)

    sendPerfaEvent(manualTraceEvent())

    assertTrue(stage.leaks.value.isNotEmpty())
    assertEquals(stage.leaks.value.first(), stage.selectedLeak.value)
  }

  @Test
  fun `saved copy of a host result is not shown again in ON_HOST`() {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)

    sendPerfaEvent(structuredResultEvent())

    assertTrue(stage.leaks.value.isEmpty())
  }

  @Test
  fun `Manual trace is not read in ON_HOST with the flag off`() {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = false)

    sendPerfaEvent(manualTraceEvent())

    assertTrue(stage.leaks.value.isEmpty())
  }

  @Test
  fun `Manual trace during a host heap dump keeps Force dump grey in ON_HOST`() {
    `when`(mockHeapDumper.triggerAndAnalyze()).thenReturn(true)
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST, occurrencesEnabled = true)
    stage.forceHeapDump(isUserInitiated = true)
    assertTrue(stage.isForceDumpExecuting.value)

    sendPerfaEvent(manualTraceEvent())

    assertTrue(stage.leaks.value.isNotEmpty())
    assertTrue(stage.isForceDumpExecuting.value)
  }

  @Test
  fun `Manual trace during a device analysis keeps progress and Stop in ON_DEVICE`() {
    startOnDevice(occurrencesEnabled = true)
    sendPerfaEvent(progressEvent(30))
    stage.requestStopRecording() // An analysis is running, so Stop waits for its result.

    sendPerfaEvent(manualTraceEvent())

    assertTrue(stage.leaks.value.isNotEmpty())
    assertEquals(30, stage.analysisProgress.value)
    assertTrue(stage.isStopping.value)
    assertTrue(stage.isRecording.value)
    // The 60 second wait for the device answer still runs.
    timer.tick(TimeUnit.SECONDS.toNanos(61))
    assertFalse(stage.isRecording.value)
  }

  /** Saves the studio-leakcanary version for the app process of the session, as the pre-start check does. */
  private fun saveVersionForSession(version: String) {
    saveStudioLeakCanaryVersion(
      ideProfilerServices.temporaryProfilerPreferences,
      profilers.session.streamId,
      profilers.session.pid,
      version,
    )
  }

  /** Starts a recording in ON_DEVICE mode with the given occurrences flag. */
  private fun startOnDevice(occurrencesEnabled: Boolean) {
    startRecording(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE, occurrencesEnabled)
  }

  /** Starts a recording in the given mode with the given occurrences flag. */
  private fun startRecording(mode: Commands.StartLeakCanaryTaskData.LeakCanaryMode, occurrencesEnabled: Boolean) {
    ideProfilerServices.enableLeakCanaryOccurrences(occurrencesEnabled)
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", 5)
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    stage.setLeakCanaryMode(mode)
    runWithPolling { stage.startListening() }
    assertEquals(mode, stage.leakcanaryMode)
  }

  /** Sends an event as perfa would, and lets the poller deliver it. */
  private fun sendPerfaEvent(event: Common.Event) {
    addPerfaEvent(event)
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
  }

  /** Adds an event as perfa would, without delivering it yet. Each event gets a later time than the one before, like in the daemon. */
  private fun addPerfaEvent(event: Common.Event) {
    transportService.addEventToStream(profilers.session.streamId, event.toBuilder().setTimestamp(nextEventTimestamp++).build())
  }

  // Event times must be after the session start, which the listeners use as their start time. The wall clock in ms is far larger than
  // the fake timer's start, so it works.
  private var nextEventTimestamp = System.currentTimeMillis()

  private fun countEvent(count: Int): Common.Event =
    Common.Event.newBuilder()
      .setPid(profilers.session.pid)
      .setKind(Common.Event.Kind.LEAKCANARY_OBJECT_COUNT)
      .setLeakcanaryObjectCount(Common.LeakCanaryObjectCountData.newBuilder().setCount(count))
      .build()

  private fun progressEvent(percent: Int): Common.Event =
    Common.Event.newBuilder()
      .setPid(profilers.session.pid)
      .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS_PROGRESS)
      .setLeakcanaryAnalysisProgress(Common.LeakCanaryAnalysisProgress.newBuilder().setPercent(percent))
      .build()

  private fun deviceErrorEvent(errorType: Common.LeakCanaryDeviceError.ErrorType): Common.Event =
    Common.Event.newBuilder()
      .setPid(profilers.session.pid)
      .setKind(Common.Event.Kind.LEAKCANARY_DEVICE_ERROR)
      .setLeakcanaryDeviceError(Common.LeakCanaryDeviceError.newBuilder().setErrorType(errorType))
      .build()

  private fun analysisEvent(data: LeakCanary.LeakCanaryAnalysisData): Common.Event =
    Common.Event.newBuilder()
      .setGroupId(profilers.session.pid.toLong())
      .setPid(profilers.session.pid)
      .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS)
      .setLeakcanaryAnalysis(data)
      .build()

  /** A LeakCanary:Manual trace, as the Logcat handler sends it: text only. */
  private fun manualTraceEvent(): Common.Event = FakeLeakCanaryCommandHandler.getLeakCanaryEvent(profilers, "SingleApplicationLeak.txt")

  /** A structured result with one leak, as perfa sends it. */
  private fun structuredResultEvent(): Common.Event =
    analysisEvent(
      LeakCanaryProtoAdapter.toProto(
        AnalysisSuccess(
          heapDumpFile = File("/tmp/test.hprof"),
          createdAtTimeMillis = 1000L,
          dumpDurationMillis = 50L,
          analysisDurationMillis = 150L,
          metadata = mapOf("LeakCanary version" to "2.14"),
          leaks = listOf(oneTraceLeak()),
        )
      )
    )

  private fun oneTraceLeak(): Leak =
    Leak(
      type = LeakType.APPLICATION_LEAKS,
      retainedByteSize = 1000,
      signature = "perfa_sig",
      leakTraceCount = 1,
      displayedLeakTrace =
        listOf(
          LeakTrace(
            gcRootType = GcRootType.JAVA_FRAME,
            nodes =
              listOf(
                Node(
                  nodeType = LeakTraceNodeType.INSTANCE,
                  className = "com.example.LeakingActivity",
                  leakingStatus = LeakingStatus.YES,
                  leakingStatusReason = "Destroyed",
                  retainedHeapSize = "1 kB",
                  retainedObjectCount = 1,
                  notes = emptyList(),
                  referencingField = null,
                )
              ),
          )
        ),
    )

  private fun isSharkVersionEmitted(): Boolean {
    val field = LeakCanaryModel::class.java.getDeclaredField("isSharkVersionEmitted")
    field.isAccessible = true
    return (field.get(stage) as AtomicBoolean).get()
  }

  /** Time since the wait for a device answer started, or null when Studio is not waiting. */
  private fun onDeviceAnswerWaitNs(): Long? {
    val field = LeakCanaryModel::class.java.getDeclaredField("onDeviceAnswerWaitNs")
    field.isAccessible = true
    return field.get(stage) as Long?
  }

  /** Starts ON_DEVICE with the flag on, sends the device error, and checks the error pop-up, the telemetry and the stop. */
  private fun assertDeviceErrorStops(
    errorType: Common.LeakCanaryDeviceError.ErrorType,
    message: String,
    telemetryCode: LeakCanaryProcessingErrorCode,
  ) {
    sendDeviceErrorAndCheckPopup(
      errorType,
      Notification.Severity.ERROR,
      "LeakCanary Task Failed",
      message,
      telemetryCode,
      expectedErrorLogs = listOf("LeakCanary Fatal Error ($telemetryCode): $message"),
    )
    assertFalse(stage.isRecording.value)
  }

  /** Starts ON_DEVICE with the flag on, sends the device error, and checks the warning pop-up, the telemetry and that recording goes on. */
  private fun assertDeviceWarningKeepsRecording(
    errorType: Common.LeakCanaryDeviceError.ErrorType,
    message: String,
    telemetryCode: LeakCanaryProcessingErrorCode,
  ) {
    // A warning is not a Studio bug, so nothing may be logged as an error.
    sendDeviceErrorAndCheckPopup(
      errorType,
      Notification.Severity.WARNING,
      "LeakCanary Warning",
      message,
      telemetryCode,
      expectedErrorLogs = emptyList(),
    )
    assertTrue(stage.isRecording.value)
  }

  /** Starts a recording with a real task tracker, sends one of the older device errors, and returns the errors that were logged. */
  private fun sendOlderDeviceError(errorType: Common.LeakCanaryDeviceError.ErrorType): List<String> {
    // The stage gets a real task tracker only when it is entered. Without it, telemetry is dropped.
    profilers.stage = stage
    startOnDevice(occurrencesEnabled = false)

    // A logged error fails the test unless it is caught here.
    val loggedErrors = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
      object : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
          loggedErrors.add(message)
          return Action.NONE
        }
      }
    ) {
      sendPerfaEvent(deviceErrorEvent(errorType))
    }
    return loggedErrors
  }

  private fun sendDeviceErrorAndCheckPopup(
    errorType: Common.LeakCanaryDeviceError.ErrorType,
    severity: Notification.Severity,
    title: String,
    message: String,
    telemetryCode: LeakCanaryProcessingErrorCode,
    expectedErrorLogs: List<String>,
  ) {
    // The stage gets a real task tracker only when it is entered. Without it, telemetry is dropped.
    profilers.stage = stage
    startOnDevice(occurrencesEnabled = true)

    // A logged error fails the test unless it is caught here.
    val loggedErrors = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
      object : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
          loggedErrors.add(message)
          return Action.NONE
        }
      }
    ) {
      sendPerfaEvent(deviceErrorEvent(errorType))
    }

    assertEquals(expectedErrorLogs, loggedErrors)
    assertEquals(severity, ideProfilerServices.notification?.severity)
    assertEquals(title, ideProfilerServices.notification?.title)
    // The balloon shows HTML, so the text is escaped (for example, ' becomes &#39;).
    assertEquals(escapeXmlEntities(message), ideProfilerServices.notification?.text)
    val tracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    assertEquals(telemetryCode, tracker.lastTaskProcessingFailedMetadata?.leakCanaryProcessingStatus)
  }

  @Test
  fun `set mode command sends occurrences flag when on`() {
    assertSetModeSendsOccurrences(true)
  }

  @Test
  fun `set mode command sends occurrences flag when off`() {
    assertSetModeSendsOccurrences(false)
  }

  /** Starts a recording with the given flag value, and checks the set mode command carries it. */
  private fun assertSetModeSendsOccurrences(enabled: Boolean) {
    ideProfilerServices.enableLeakCanaryOccurrences(enabled)
    ideProfilerServices.temporaryProfilerPreferences.setInt("LEAKCANARY_THRESHOLD", 10)
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    val setModeHandler = CountingCommandHandler(timer)
    transportService.setCommandHandler(Commands.Command.CommandType.SET_STUDIO_LEAKCANARY_MODE, setModeHandler)

    stage.setLeakCanaryMode(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE)
    runWithPolling { stage.startListening() }

    assertEquals(1, setModeHandler.count)
    val data = setModeHandler.lastCommand!!.setStudioLeakcanaryMode
    assertEquals(Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_DEVICE, data.mode)
    assertEquals(enabled, data.profilerLeakcanaryOccurrences)
  }

  @Test
  fun `checkLeakCanaryThreshold reads from config in Studio mode`() {
    val config = LeakCanaryConfiguration("LeakCanary")
    config.source = LeakCanaryMode.STUDIO
    config.threshold = 10
    extraConfigs.add(config)

    stage.updateModeFromSettings()
    assertEquals(10, stage.retainedObjectThreshold.value)

    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0, retainedObjectThreshold = 5),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )

    runWithPolling { stage.startListening() }
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)

    // Threshold should still be 10 (from config), not 5 (from command which shouldn't run)
    assertEquals(10, stage.retainedObjectThreshold.value)
  }

  // A leaking node is found in the leak trace.
  @Test
  fun `test getLeakClassName when leaking node is found`() {
    val previousNode =
      createTestNode(className = "MainActivity", leakingStatus = LeakingStatus.NO, referenceName = "mLeakyView", isLikelyCause = true)
    val leakingNode = createTestNode(className = "LeakyView", leakingStatus = LeakingStatus.YES)
    val leakTrace = LeakTrace(GcRootType.NATIVE_STACK, nodes = listOf(previousNode, leakingNode))
    val leak =
      Leak(
        type = LeakType.APPLICATION_LEAKS,
        retainedByteSize = 1024,
        signature = "leak_signature_123",
        leakTraceCount = 1,
        displayedLeakTrace = listOf(leakTrace),
      )
    val result = LeakCanaryModel.getLeakClassName(leak)
    assertEquals("MainActivity.mLeakyView", result)
  }

  // Leaking node is UNKNOWN and it's the last node
  @Test
  fun `test getLeakClassName when leaking node is UNKNOWN and it is the last node`() {
    val node1 = createTestNode(className = "IntermediateClass", leakingStatus = LeakingStatus.NO, referenceName = "mReference")
    val unknownNode =
      createTestNode(className = "UnknownLeaker", leakingStatus = LeakingStatus.UNKNOWN, referenceName = "ReferenceName_UNKNOWN")
    val leakTrace = LeakTrace(GcRootType.NATIVE_STACK, nodes = listOf(node1, unknownNode))
    val leak =
      Leak(
        type = LeakType.APPLICATION_LEAKS,
        retainedByteSize = 700,
        signature = "leak_signature_789",
        leakTraceCount = 1,
        displayedLeakTrace = listOf(leakTrace),
      )
    val result = LeakCanaryModel.getLeakClassName(leak)
    assertEquals("IntermediateClass.mReference", result)
  }

  // The input leak object is null.
  @Test
  fun `test getLeakClassName when leak is null`() {
    val leak: Leak? = null
    val result = LeakCanaryModel.getLeakClassName(leak)
    assertEquals("", result)
  }

  // Leaking node is UNKNOWN and there's a previous NO node
  @Test
  fun `test getLeakClassName when leaking node is UNKNOWN and there is a previous NO node`() {
    val noNode = createTestNode(className = "noNode", leakingStatus = LeakingStatus.NO, referenceName = "someField")
    val yesNode = createTestNode(className = "yesNode", leakingStatus = LeakingStatus.YES)
    val unknownNode =
      createTestNode(className = "UncertainLeaker", leakingStatus = LeakingStatus.UNKNOWN, referenceName = "ReferenceName_UNKNOWN")

    val leakTrace = LeakTrace(GcRootType.NATIVE_STACK, nodes = listOf(noNode, yesNode, unknownNode))
    val leak =
      Leak(
        type = LeakType.LIBRARY_LEAKS,
        retainedByteSize = 900,
        signature = "leak_signature_abc",
        leakTraceCount = 1,
        displayedLeakTrace = listOf(leakTrace),
      )
    val result = LeakCanaryModel.getLeakClassName(leak)
    assertEquals("noNode.someField", result)
  }

  // Leaking node is UNKNOWN and there's a YES node after UNKNOWN node
  @Test
  fun `test getLeakClassName when leaking node is UNKNOWN and there is a YES node after UNKNOWN`() {
    val previousNoNode = createTestNode(className = "AnotherPrevious", leakingStatus = LeakingStatus.NO, referenceName = "someField")
    val unknownNode =
      createTestNode(className = "UncertainLeaker", leakingStatus = LeakingStatus.UNKNOWN, referenceName = "ReferenceName_UNKNOWN")
    val nextNode = createTestNode(className = "NextNodeInTrace", leakingStatus = LeakingStatus.YES)
    val leakTrace = LeakTrace(GcRootType.NATIVE_STACK, nodes = listOf(previousNoNode, unknownNode, nextNode))
    val leak =
      Leak(
        type = LeakType.LIBRARY_LEAKS,
        retainedByteSize = 900,
        signature = "leak_signature_abc",
        leakTraceCount = 1,
        displayedLeakTrace = listOf(leakTrace),
      )
    val result = LeakCanaryModel.getLeakClassName(leak)
    assertEquals("AnotherPrevious.someField", result)
  }

  @Test
  fun `analyzeLeakWithStudioBot delegates to ideServices`() {
    val leak = mock(Leak::class.java)
    `when`(leak.toString()).thenReturn("trace")
    stage.analyzeLeakWithStudioBot(leak)
    assertEquals("trace", ideProfilerServices.lastLeakRawTrace)
    assertEquals<Leak?>(leak, ideProfilerServices.lastLeak)
  }

  @Test
  fun `manual leak parsing and adding to leaks`() {
    val file = TestUtils.resolveWorkspacePath("${FakeLeakCanaryCommandHandler.TEST_DATA_PATH}/SingleApplicationLeak.txt").toFile()
    val rawTrace = file.readText()

    val analysis = Analysis.fromString(rawTrace)
    assertTrue(analysis is AnalysisSuccess)
    stage.addLeaks(analysis.leaks)

    assertEquals(1, stage.leaks.value.size)
    assertEquals("androidx.constraintlayout.widget.ConstraintLayout", stage.leaks.value[0].displayedLeakTrace[0].nodes.last().className)
  }

  @Test
  fun `requestStopRecording with retained objects triggers dump and waits`() {
    // Setup command handlers to avoid errors
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )

    runWithPolling { stage.startListening() }
    stage.setObjectRetainedCount(1)
    stage.setAnalysisProgress(0)

    stage.leakcanaryMode = Commands.StartLeakCanaryTaskData.LeakCanaryMode.ON_HOST

    // Request stop
    stage.requestStopRecording()

    // Verify triggerAndAnalyze was called
    verify(mockHeapDumper).triggerAndAnalyze()

    // Verify stopping state
    assertTrue(stage.isStopping.value)
    assertTrue(stage.isRecording.value)

    // Simulate analysis success event
    val analysisEvent = FakeLeakCanaryCommandHandler.getLeakCanaryEvent(profilers, "SingleApplicationLeak.txt")
    transportService.addEventToStream(profilers.session.streamId, analysisEvent)

    // Tick to process event
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)

    // Verify recording stopped
    assertFalse(stage.isRecording.value)
    assertFalse(stage.isStopping.value)
  }

  @Test
  fun `requestStopRecording with no retained objects stops immediately`() {
    // Setup
    transportService.setCommandHandler(
      Commands.Command.CommandType.START_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.STOP_LEAKCANARY_TASK,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )
    transportService.setCommandHandler(
      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD,
      FakeLeakCanaryCommandHandler(timer, profilers, listOf(), 0),
    )

    runWithPolling { stage.startListening() }
    stage.setObjectRetainedCount(0)

    stage.requestStopRecording()

    // Should stop immediately
    assertFalse(stage.isRecording.value)
    assertFalse(stage.isStopping.value)
  }

  @Test
  fun `test fetchInsight transitions state to Ready on success`() {
    val leak = createMockLeak()

    customInsightFlow = flow {
      emit("Memory leak identified in class.")
      emit(" Solution is simple.")
    }

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Ready)
    val insight = state.value
    assertTrue(insight != null)
    assertEquals("Memory leak identified in class. Solution is simple.", insight.rawInsight)
    assertEquals("AI Assistant", insight.modelName)
    assertEquals(null, insight.feedback)
  }

  @Test
  fun `test fetchInsight resolves response in one shot once flow completes`() = runBlocking {
    val leak = createMockLeak()

    customInsightFlow = flow {
      emit("Memory leak identified in class.")
      yield()
      emit(" Solution is simple.")
    }

    val states = mutableListOf<LoadingState<AiInsight?>>()
    val job = launch(Dispatchers.Unconfined) { stage.insightModel.currentInsight.collect { states.add(it) } }

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    job.cancel()

    // Since we now resolve in one shot, the states should transition directly:
    // 1. Ready(null) (initial state)
    // 2. Loading()
    // 3. Ready(finalInsight)
    assertEquals(3, states.size)
    assertTrue(states[0] is LoadingState.Ready && (states[0] as LoadingState.Ready).value == null)
    assertTrue(states[1] is LoadingState.Loading)

    assertTrue(states[2] is LoadingState.Ready)
    val finalInsight = (states[2] as LoadingState.Ready).value
    assertEquals("Memory leak identified in class. Solution is simple.", finalInsight?.rawInsight)
  }

  @Test
  fun `test isInsightAutoGenerateEnabled toggle updates preferences`() {
    assertFalse(stage.insightModel.isInsightAutoGenerateEnabled.value)

    stage.insightModel.setInsightAutoGenerateEnabled(true)
    assertTrue(stage.insightModel.isInsightAutoGenerateEnabled.value)
    assertTrue(ideProfilerServices.persistentProfilerPreferences.getBoolean("leakcanary.insight.auto.generate", false))

    stage.insightModel.setInsightAutoGenerateEnabled(false)
    assertFalse(stage.insightModel.isInsightAutoGenerateEnabled.value)
    assertFalse(ideProfilerServices.persistentProfilerPreferences.getBoolean("leakcanary.insight.auto.generate", true))
  }

  @Test
  fun `test LeakCanary AI Insights telemetry`() {
    val fakeTracker = ideProfilerServices.featureTracker as FakeFeatureTracker
    val leak = createMockLeak()
    stage.onLeakSelection(leak)

    // Test visibility telemetry
    stage.insightModel.setInsightVisible(false)
    assertEquals(LeakCanaryUiAction.INSIGHT_PANEL_CLOSED, fakeTracker.lastLeakCanaryUiAction)
    stage.insightModel.setInsightVisible(true)
    assertEquals(LeakCanaryUiAction.INSIGHT_PANEL_OPENED, fakeTracker.lastLeakCanaryUiAction)
    stage.insightModel.setInsightVisible(false)
    assertEquals(LeakCanaryUiAction.INSIGHT_PANEL_CLOSED, fakeTracker.lastLeakCanaryUiAction)

    // Test auto-generate toggling telemetry
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    assertEquals(LeakCanaryUiAction.INSIGHT_AUTO_GENERATE_ENABLED, fakeTracker.lastLeakCanaryUiAction)
    stage.insightModel.setInsightAutoGenerateEnabled(false)
    assertEquals(LeakCanaryUiAction.INSIGHT_AUTO_GENERATE_DISABLED, fakeTracker.lastLeakCanaryUiAction)

    // Test fetch success telemetry
    customInsightFlow = flowOf("Leak explanation")
    stage.insightModel.setInsightVisible(true)
    stage.insightModel.fetchInsight(leak)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_SUCCEEDED, fakeTracker.lastLeakCanaryUiAction)

    // Test fetch failure telemetry
    customInsightFlow = flow { throw RuntimeException("Error") }
    stage.insightModel.fetchInsight(leak)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_FAILED, fakeTracker.lastLeakCanaryUiAction)

    // Test fetch cancellation telemetry
    customInsightFlow = flow {
      delay(1000)
      emit("Should not run")
    }
    stage.insightModel.fetchInsight(leak)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_TRIGGERED, fakeTracker.lastLeakCanaryUiAction)
    stage.insightModel.clearInsights()
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_CANCELLED, fakeTracker.lastLeakCanaryUiAction)

    // Test fetch cancellation when closing the panel mid-fetch
    customInsightFlow = flow {
      delay(1000)
      emit("Should not run")
    }
    stage.insightModel.setInsightVisible(true)
    stage.insightModel.fetchInsight(leak)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_TRIGGERED, fakeTracker.lastLeakCanaryUiAction)
    stage.insightModel.setInsightVisible(false)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_CANCELLED, fakeTracker.lastLeakCanaryUiAction)

    // Reload successful insight to allow feedback submissions
    customInsightFlow = flowOf("Leak explanation")
    stage.insightModel.fetchInsight(leak)
    assertEquals(LeakCanaryUiAction.INSIGHT_FETCH_SUCCEEDED, fakeTracker.lastLeakCanaryUiAction)

    // Test sentiment feedback telemetry
    stage.insightModel.submitInsightFeedback(InsightFeedback.THUMBS_UP)
    assertEquals(LeakCanaryUiAction.INSIGHT_SENTIMENT_UP, fakeTracker.lastLeakCanaryUiAction)

    // Clear feedback (should track CLEARED)
    stage.insightModel.submitInsightFeedback(null)
    assertEquals(LeakCanaryUiAction.INSIGHT_SENTIMENT_CLEARED, fakeTracker.lastLeakCanaryUiAction)

    // Set other action to change the tracker state
    stage.trackUiAction(LeakCanaryUiAction.INSIGHT_COPY_CLICKED)
    assertEquals(LeakCanaryUiAction.INSIGHT_COPY_CLICKED, fakeTracker.lastLeakCanaryUiAction)

    // Clear feedback again (already null, should NOT track CLEARED, so last action remains COPY_CLICKED)
    stage.insightModel.submitInsightFeedback(null)
    assertEquals(LeakCanaryUiAction.INSIGHT_COPY_CLICKED, fakeTracker.lastLeakCanaryUiAction)

    // Test Thumbs Down
    stage.insightModel.submitInsightFeedback(InsightFeedback.THUMBS_DOWN)
    assertEquals(LeakCanaryUiAction.INSIGHT_SENTIMENT_DOWN, fakeTracker.lastLeakCanaryUiAction)

    // Test manually tracked UI actions (stage fix, copy action)
    stage.trackUiAction(LeakCanaryUiAction.INSIGHT_GENERATE_FIX_CLICKED)
    assertEquals(LeakCanaryUiAction.INSIGHT_GENERATE_FIX_CLICKED, fakeTracker.lastLeakCanaryUiAction)
    stage.trackUiAction(LeakCanaryUiAction.INSIGHT_COPY_CLICKED)
    assertEquals(LeakCanaryUiAction.INSIGHT_COPY_CLICKED, fakeTracker.lastLeakCanaryUiAction)
  }

  @Test
  fun `test fetchInsight transitions state to Failure on flow collection error`() {
    val leak = createMockLeak()

    customInsightFlow = flow { throw RuntimeException("AI Assistant Service Unavailable") }

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Failure)
    assertEquals("AI Assistant Service Unavailable", (state as LoadingState.Failure).message)
  }

  @Test
  fun `test fetchInsight transitions state to Failure on empty response`() {
    val leak = createMockLeak()

    customInsightFlow = emptyFlow()

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Failure)
    assertEquals("AI Assistant returned an empty response.", (state as LoadingState.Failure).message)
  }

  @Test
  fun `test fetchInsight caches results and returns from cache on re-selection`() {
    val leak1 = createMockLeak("trace 1", "sig 1")
    val leak2 = createMockLeak("trace 2", "sig 2")

    // Setup first flow emission
    customInsightFlow = flowOf("Insight 1")

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak1)

    // Verify leak1 loaded
    val state1 = stage.insightModel.currentInsight.value
    assertTrue(state1 is LoadingState.Ready)
    assertEquals("Insight 1", (state1 as LoadingState.Ready).value?.rawInsight)

    // Setup second flow emission
    customInsightFlow = flowOf("Insight 2")
    stage.onLeakSelection(leak2)

    // Verify leak2 loaded
    val state2 = stage.insightModel.currentInsight.value
    assertTrue(state2 is LoadingState.Ready)
    assertEquals("Insight 2", (state2 as LoadingState.Ready).value?.rawInsight)

    // Change flow to throw error so that if it is called again, it will fail
    customInsightFlow = flow { throw RuntimeException("Should not be called") }

    // Re-select leak1
    stage.onLeakSelection(leak1)

    // Verify it returned from cache successfully
    val state1Recalled = stage.insightModel.currentInsight.value
    assertTrue(state1Recalled is LoadingState.Ready)
    assertEquals("Insight 1", state1Recalled.value?.rawInsight)
  }

  @Test
  fun `test fetchInsight skips resolution if panel is hidden`() {
    val leak = createMockLeak()

    customInsightFlow = flow { emit("Should not run") }

    stage.insightModel.setInsightVisible(false)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Ready)
    assertEquals(null, (state as LoadingState.Ready).value)
  }

  @Test
  fun `test submitInsightFeedback updates insight state correctly`() {
    val leak = createMockLeak()

    customInsightFlow = flow { emit("Insight text") }

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(true)
    stage.onLeakSelection(leak)

    // Initial feedback is null
    val state1 = stage.insightModel.currentInsight.value
    assertTrue(state1 is LoadingState.Ready)
    assertEquals(null, state1.value?.feedback)

    // Submit THUMBS_UP
    stage.insightModel.submitInsightFeedback(InsightFeedback.THUMBS_UP)
    val state2 = stage.insightModel.currentInsight.value
    assertTrue(state2 is LoadingState.Ready)
    assertEquals(InsightFeedback.THUMBS_UP, state2.value?.feedback)

    // Submit null to toggle feedback off
    stage.insightModel.submitInsightFeedback(null)
    val state3 = stage.insightModel.currentInsight.value
    assertTrue(state3 is LoadingState.Ready)
    assertEquals(null, state3.value?.feedback)
  }

  @Test
  fun `test enabling auto-generate with selected leak triggers fetchInsight`() {
    val leak = createMockLeak()
    customInsightFlow = flowOf("Insight text")

    stage.insightModel.setInsightVisible(true)
    stage.insightModel.setInsightAutoGenerateEnabled(false)
    stage.onLeakSelection(leak)

    // No insight fetched yet because auto-generate is disabled
    val state1 = stage.insightModel.currentInsight.value
    assertTrue(state1 is LoadingState.Ready && state1.value == null)

    // Enable auto-generate
    stage.insightModel.setInsightAutoGenerateEnabled(true)

    // Should trigger fetchInsight and complete
    val state2 = stage.insightModel.currentInsight.value
    assertTrue(state2 is LoadingState.Ready)
    assertEquals("Insight text", (state2 as LoadingState.Ready).value?.rawInsight)
  }

  @Test
  fun `test fetchInsight skips resolution if auto-generate is disabled when panel becomes visible`() {
    val leak = createMockLeak()
    customInsightFlow = flow { emit("Should not run") }

    stage.insightModel.setInsightVisible(false)
    stage.insightModel.setInsightAutoGenerateEnabled(false)
    stage.onLeakSelection(leak)

    // Make visible
    stage.insightModel.setInsightVisible(true)

    // Should NOT trigger fetchInsight, and state should remain Ready(null)
    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Ready && state.value == null)
  }

  @Test
  fun `test enabling auto-generate when panel is hidden does not trigger fetchInsight`() {
    val leak = createMockLeak()
    customInsightFlow = flow { emit("Should not run") }

    stage.insightModel.setInsightVisible(false)
    stage.insightModel.setInsightAutoGenerateEnabled(false)
    stage.onLeakSelection(leak)

    // Enable auto-generate while hidden
    stage.insightModel.setInsightAutoGenerateEnabled(true)

    // Should NOT trigger fetchInsight, state remains Ready(null), visibility remains false
    val state = stage.insightModel.currentInsight.value
    assertTrue(state is LoadingState.Ready && state.value == null)
    assertFalse(stage.insightModel.isInsightVisible.value)
  }

  private fun createTestNode(
    className: String,
    leakingStatus: LeakingStatus = LeakingStatus.UNKNOWN,
    referenceName: String? = null,
    isLikelyCause: Boolean = false,
  ): Node {
    val referencingField = referenceName?.let {
      ReferencingField(
        className = className,
        type = ReferencingField.ReferencingFieldType.STATIC_FIELD,
        isLikelyCause = isLikelyCause,
        referenceName = it,
      )
    }
    return Node(
      nodeType = LeakTraceNodeType.INSTANCE,
      className = className,
      leakingStatus = leakingStatus,
      leakingStatusReason = "",
      retainedHeapSize = "2 KB",
      retainedObjectCount = 2024,
      notes = emptyList(),
      referencingField = referencingField,
    )
  }

  private fun createMockLeak(trace: String = "sample trace", signature: String = "leak_sig"): Leak {
    val leak = mock(Leak::class.java)
    `when`(leak.toString()).thenReturn(trace)
    `when`(leak.signature).thenReturn(signature)
    return leak
  }

  @Test
  fun `Scope Dropdown Filtering Tests`() = runBlocking {
    val appLeak1 =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
      }
    val appLeak2 =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
      }
    val libLeak =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.LIBRARY_LEAKS)
        `when`(this.signature).thenReturn("lib_leak_sig")
      }
    stage.addLeaks(listOf(appLeak1, appLeak2, libLeak))

    stage.setFilterScope(LeakFilterScope.ALL)
    assertEquals(listOf(appLeak1, appLeak2, libLeak), stage.filteredLeaks.value)

    stage.setFilterScope(LeakFilterScope.APP)
    assertEquals(listOf(appLeak1, appLeak2), stage.filteredLeaks.value)

    stage.setFilterScope(LeakFilterScope.LIBRARY)
    assertEquals(listOf(libLeak), stage.filteredLeaks.value)
  }

  @Test
  fun `Standard Filter Pattern and Substring Matching Tests`() = runBlocking {
    val ref1 =
      mock(ReferencingField::class.java).apply {
        `when`(this.className).thenReturn("com.example.shop.MainActivity")
        `when`(this.referenceName).thenReturn("mActivity")
      }
    val mockNode1 =
      mock(Node::class.java).apply {
        `when`(this.leakingStatus).thenReturn(LeakingStatus.UNKNOWN)
        `when`(this.referencingField).thenReturn(ref1)
      }
    val mockTrace1 = mock(LeakTrace::class.java).apply { `when`(this.nodes).thenReturn(listOf(mockNode1)) }
    val appLeak1 =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
        `when`(this.displayedLeakTrace).thenReturn(listOf(mockTrace1))
      }

    val ref2 =
      mock(ReferencingField::class.java).apply {
        `when`(this.className).thenReturn("com.example.shop.CartViewModel")
        `when`(this.referenceName).thenReturn("mState")
      }
    val mockNode2 =
      mock(Node::class.java).apply {
        `when`(this.leakingStatus).thenReturn(LeakingStatus.UNKNOWN)
        `when`(this.referencingField).thenReturn(ref2)
      }
    val mockTrace2 = mock(LeakTrace::class.java).apply { `when`(this.nodes).thenReturn(listOf(mockNode2)) }
    val appLeak2 =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
        `when`(this.displayedLeakTrace).thenReturn(listOf(mockTrace2))
      }
    stage.addLeaks(listOf(appLeak1, appLeak2))
    stage.setMatchCase(false)
    stage.setSearchQuery("mainactivity")
    delay(300)
    assertEquals(listOf(appLeak1), stage.filteredLeaks.value)

    stage.setMatchCase(true)
    stage.setSearchQuery("mainactivity")
    delay(300)
    assertEquals(emptyList<Leak>(), stage.filteredLeaks.value)

    stage.setMatchCase(false)
    stage.setSearchQuery("mactivity")
    delay(300)
    assertEquals(listOf(appLeak1), stage.filteredLeaks.value)

    stage.setMatchCase(false)
    stage.setSearchQuery("doesnotexist")
    delay(300)
    assertEquals(emptyList<Leak>(), stage.filteredLeaks.value)
  }

  @Test
  fun `Selection Transition and Preservation Tests`() = runBlocking {
    val ref1 = mock(ReferencingField::class.java).apply { `when`(this.className).thenReturn("com.example.shop.MainActivity") }
    val mockNode1 =
      mock(Node::class.java).apply {
        `when`(this.leakingStatus).thenReturn(LeakingStatus.UNKNOWN)
        `when`(this.referencingField).thenReturn(ref1)
      }
    val mockTrace1 = mock(LeakTrace::class.java).apply { `when`(this.nodes).thenReturn(listOf(mockNode1)) }
    val appLeak =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
        `when`(this.displayedLeakTrace).thenReturn(listOf(mockTrace1))
      }

    val ref2 = mock(ReferencingField::class.java).apply { `when`(this.className).thenReturn("com.example.shop.LibraryClass") }
    val mockNode2 =
      mock(Node::class.java).apply {
        `when`(this.leakingStatus).thenReturn(LeakingStatus.UNKNOWN)
        `when`(this.referencingField).thenReturn(ref2)
      }
    val mockTrace2 = mock(LeakTrace::class.java).apply { `when`(this.nodes).thenReturn(listOf(mockNode2)) }
    val libLeak =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.LIBRARY_LEAKS)
        `when`(this.signature).thenReturn("lib_leak_sig")
        `when`(this.displayedLeakTrace).thenReturn(listOf(mockTrace2))
      }
    stage.addLeaks(listOf(appLeak, libLeak))

    stage.onLeakSelection(appLeak)
    assertEquals(appLeak, stage.selectedLeak.value)

    stage.setFilterScope(LeakFilterScope.LIBRARY)
    assertEquals(libLeak, stage.selectedLeak.value)

    stage.setFilterScope(LeakFilterScope.ALL)
    assertEquals(libLeak, stage.selectedLeak.value)
  }

  @Test
  fun `Session Reset Tests`() {
    stage.setFilterScope(LeakFilterScope.APP)
    stage.setSearchQuery("test")
    stage.setMatchCase(true)
    stage.setUseRegex(true)

    stage.resetFilters()

    assertEquals(LeakFilterScope.ALL, stage.filterScope.value)
    assertEquals("", stage.searchQuery.value)
    assertEquals(false, stage.matchCase.value)
    assertEquals(false, stage.useRegex.value)
  }

  @Test
  fun `Regex Debounce and Syntax Safety Tests`() = runBlocking {
    val ref1 = mock(ReferencingField::class.java).apply { `when`(this.className).thenReturn("com.example.shop.MainActivity") }
    val mockNode1 =
      mock(Node::class.java).apply {
        `when`(this.leakingStatus).thenReturn(LeakingStatus.UNKNOWN)
        `when`(this.referencingField).thenReturn(ref1)
      }
    val mockTrace1 = mock(LeakTrace::class.java).apply { `when`(this.nodes).thenReturn(listOf(mockNode1)) }
    val appLeak1 =
      mock(Leak::class.java).apply {
        `when`(this.type).thenReturn(LeakType.APPLICATION_LEAKS)
        `when`(this.signature).thenReturn("app_leak_sig")
        `when`(this.displayedLeakTrace).thenReturn(listOf(mockTrace1))
      }
    stage.addLeaks(listOf(appLeak1))

    stage.setUseRegex(true)
    stage.setSearchQuery("[") // Invalid regex
    delay(300)

    // After debounce, the invalid regex should result in empty list
    assertEquals(0, stage.filteredLeaks.value.size)

    // Clear query
    stage.setSearchQuery("")
    delay(300)
    assertEquals(1, stage.filteredLeaks.value.size)
  }

  @Test
  fun testLeakOccurrenceIndexResetsOnNewSelection() {
    val mockLeak1 = mock(Leak::class.java)
    val mockLeak2 = mock(Leak::class.java)
    val mockTrace = mock(LeakTrace::class.java)
    `when`(mockLeak1.signature).thenReturn("leak1")
    `when`(mockLeak1.displayedLeakTrace).thenReturn(listOf(mockTrace, mockTrace, mockTrace))
    `when`(mockLeak2.signature).thenReturn("leak2")
    `when`(mockLeak2.displayedLeakTrace).thenReturn(listOf(mockTrace))

    stage.onLeakSelection(mockLeak1)
    stage.selectNextOccurrence()
    assertEquals(1, stage.selectedLeakOccurrenceIndex.value)

    stage.onLeakSelection(mockLeak2)
    assertEquals(0, stage.selectedLeakOccurrenceIndex.value)
  }

  @Test
  fun testSelectNextOccurrenceBounds() {
    val mockLeak = mock(Leak::class.java)
    val mockTrace = mock(LeakTrace::class.java)

    `when`(mockLeak.signature).thenReturn("leak")
    `when`(mockLeak.displayedLeakTrace).thenReturn(listOf(mockTrace, mockTrace, mockTrace))

    stage.onLeakSelection(mockLeak)
    assertEquals(0, stage.selectedLeakOccurrenceIndex.value)

    stage.selectNextOccurrence()
    assertEquals(1, stage.selectedLeakOccurrenceIndex.value)

    stage.selectNextOccurrence()
    assertEquals(2, stage.selectedLeakOccurrenceIndex.value)

    stage.selectNextOccurrence()
    assertEquals(2, stage.selectedLeakOccurrenceIndex.value)
  }

  @Test
  fun testSelectPreviousOccurrenceBounds() {
    val mockLeak = mock(Leak::class.java)
    val mockTrace = mock(LeakTrace::class.java)
    `when`(mockLeak.signature).thenReturn("leak")
    `when`(mockLeak.displayedLeakTrace).thenReturn(listOf(mockTrace, mockTrace))

    stage.onLeakSelection(mockLeak)
    assertEquals(0, stage.selectedLeakOccurrenceIndex.value)

    stage.selectPreviousOccurrence()
    assertEquals(0, stage.selectedLeakOccurrenceIndex.value)
  }
}

/** Counts how many times a command was sent and keeps the last one. Sends no events. */
class CountingCommandHandler(timer: FakeTimer) : CommandHandler(timer) {
  var count = 0
  var lastCommand: Commands.Command? = null

  override fun handleCommand(command: Commands.Command, events: MutableList<Common.Event>) {
    count++
    lastCommand = command
  }
}

class FakeLeakCanaryCommandHandler(
  timer: FakeTimer,
  val profilers: StudioProfilers,
  val leaksToSendFiles: List<String>,
  val startTimestamp: Long,
  val retainedObjectThreshold: Int = 5,
  val studioLeakCanaryVersion: String = "",
) : CommandHandler(timer) {
  override fun handleCommand(command: Commands.Command, events: MutableList<Common.Event>) {

    when (command.type) {
      Commands.Command.CommandType.START_LEAKCANARY_TASK -> {
        // Start tracking info event
        events.add(
          Common.Event.newBuilder()
            .setGroupId(profilers.session.pid.toLong())
            .setPid(profilers.session.pid)
            .setIsEnded(false)
            .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS_STATUS)
            .setLeakCanaryAnalysisStatus(
              LeakCanaryAnalysisStatus.newBuilder()
                .setAnalysisStarted(LeakCanary.LeakCanaryAnalysisStarted.newBuilder().setTimestamp(startTimestamp).build())
                .build()
            )
            .setTimestamp(startTimestamp)
            .build()
        )
        leaksToSendFiles.forEach { leakToSendFile -> events.add(getLeakCanaryEvent(profilers, leakToSendFile)) }
      }

      Commands.Command.CommandType.STOP_LEAKCANARY_TASK -> {
        // Stop tracking info event
        events.add(
          Common.Event.newBuilder()
            .setGroupId(profilers.session.pid.toLong())
            .setPid(profilers.session.pid)
            .setIsEnded(true)
            .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS_STATUS)
            .setLeakCanaryAnalysisStatus(
              LeakCanaryAnalysisStatus.newBuilder()
                .setAnalysisEnded(
                  LeakCanary.LeakCanaryAnalysisEnded.newBuilder()
                    .setStatus(LeakCanary.LeakCanaryAnalysisEnded.Status.SUCCESS)
                    .setStartTimestamp(startTimestamp)
                    .setEndTimestamp(System.currentTimeMillis())
                    .build()
                )
                .build()
            )
            .setTimestamp(System.currentTimeMillis())
            .build()
        )
      }

      Commands.Command.CommandType.GET_LEAKCANARY_THRESHOLD -> {
        events.add(
          Common.Event.newBuilder()
            .setPid(profilers.session.pid)
            .setCommandId(command.commandId)
            .setTimestamp(System.currentTimeMillis())
            .setKind(Common.Event.Kind.LEAKCANARY_THRESHOLD)
            .setLeakcanaryThreshold(
              Common.LeakCanaryThresholdData.newBuilder()
                .setThreshold(retainedObjectThreshold)
                .setStudioLeakcanaryVersion(studioLeakCanaryVersion)
                .build()
            )
            .build()
        )
      }

      Commands.Command.CommandType.ATTACH_AGENT -> {
        events.add(
          Common.Event.newBuilder()
            .setGroupId(profilers.session.pid.toLong())
            .setPid(profilers.session.pid)
            .setKind(Common.Event.Kind.AGENT)
            .setAgentData(Common.AgentData.newBuilder().setStatus(Common.AgentData.Status.ATTACHED).build())
            .setTimestamp(System.currentTimeMillis())
            .build()
        )
      }

      else -> {}
    }
  }

  companion object {
    const val TEST_DATA_PATH = "tools/adt/idea/profilers/testData/sampleLeaks/"

    fun getLeakCanaryEvent(profilers: StudioProfilers, leakToSendFile: String): Common.Event {
      val file = TestUtils.resolveWorkspacePath("${TEST_DATA_PATH}/$leakToSendFile").toFile()
      val fileContent = file.readText()
      val currentTime = System.currentTimeMillis()
      return Common.Event.newBuilder()
        .setGroupId(profilers.session.pid.toLong())
        .setPid(profilers.session.pid)
        .setKind(Common.Event.Kind.LEAKCANARY_ANALYSIS)
        .setLeakcanaryAnalysis(LeakCanary.LeakCanaryAnalysisData.newBuilder().setData(fileContent).build())
        .setTimestamp(currentTime)
        .build()
    }
  }
}
