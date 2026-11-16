package com.android.tools.profilers.memory

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.idea.transport.faketransport.commands.MemoryAllocTracking
import com.android.tools.profiler.proto.Commands
import com.android.tools.profiler.proto.Memory
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.Utils
import com.android.tools.profilers.WithFakeTimer
import com.android.tools.profilers.memory.BaseStreamingMemoryProfilerStage.LiveAllocationSamplingMode.FULL
import com.android.tools.profilers.memory.BaseStreamingMemoryProfilerStage.LiveAllocationSamplingMode.SAMPLED
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.spy
import org.mockito.kotlin.whenever

@RunWith(Parameterized::class)
class AllocationStageTest(private val isLive: Boolean) : WithFakeTimer {
  override val timer = FakeTimer()
  private val transportService = FakeTransportService(timer)
  @Rule @JvmField val grpcChannel = FakeGrpcChannel("LiveAllocationStageTestChannel", transportService)
  private lateinit var profilers: StudioProfilers
  private lateinit var stage: AllocationStage
  private lateinit var mockLoader: FakeCaptureObjectLoader
  private lateinit var observer: MemoryAspectObserver
  private lateinit var ideProfilerServices: FakeIdeProfilerServices

  @Before
  fun setup() {
    ideProfilerServices = FakeIdeProfilerServices()
    profilers = StudioProfilers(ProfilerClient(grpcChannel.channel), ideProfilerServices, timer)
    Utils.startSessionOnFakeProcess(profilers, timer)
    mockLoader = FakeCaptureObjectLoader()
    stage =
      if (isLive) spy(AllocationStage.makeLiveStage(profilers, mockLoader))
      else AllocationStage.makeStaticStage(profilers, minTrackingTimeUs = 1.0, maxTrackingTimeUs = 5.0)
    observer = MemoryAspectObserver(stage.aspect, stage.captureSelection.aspect)
  }

  private fun setupDeviceAndStage() {
    // Advance the clock to make sure StudioProfilers has a chance to select device + process.
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    profilers.stage = stage

    if (isLive) {
      stage.liveAllocationSamplingMode = FULL
      stage.startLiveDataTimeline()
    }
  }

  @Test
  fun `agent not attached during stage enter will not start tracking in taskBasedUx`() {
    assumeTrue(isLive)
    (transportService.getRegisteredCommand(Commands.Command.CommandType.START_ALLOC_TRACKING) as MemoryAllocTracking).apply {
      trackStatus = Memory.TrackStatus.newBuilder().setStatus(Memory.TrackStatus.Status.SUCCESS).build()
    }
    // Mark agent attached as false
    whenever(stage.isAgentAttached).thenReturn(false)

    // Wait for the 'update' of the studio profiler to be done
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    stage.liveAllocationSamplingMode = SAMPLED
    profilers.stage = stage
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Verify allocation not started for taskBasedux when the agent is not attached
    assertFalse { stage.hasStartedTracking }

    // Call stopTrackingDueToUnattachableAgent to mark agent has error and end tracking
    stage.stopTrackingDueToUnattachableAgent()
    assertTrue { stage.hasEndedTracking }
    assertTrue { stage.hasAgentError }
  }

  @Test
  fun `stage starts tracking when entered then stops when stopped`() {
    setupDeviceAndStage()
    assumeTrue(isLive)
    stage.liveAllocationSamplingMode = SAMPLED
    val handler = transportService.getRegisteredCommand(Commands.Command.CommandType.STOP_ALLOC_TRACKING) as MemoryAllocTracking
    val prevCommand = handler.lastCommand
    assertThat(stage.hasEndedTracking).isFalse()

    stage.stopTracking()
    assertThat(stage.hasEndedTracking).isTrue()
    tickOneSec()
    assertThat(stage.liveAllocationSamplingMode).isEqualTo(SAMPLED) // Stop command doesn't change sampling mode
    assertThat(handler.lastCommand.type).isEqualTo(Commands.Command.CommandType.STOP_ALLOC_TRACKING)
    assertThat(handler.lastCommand.commandId).isNotEqualTo(prevCommand.commandId)
  }

  @Test
  fun `selected range expands over time until stopped`() {
    // The agent is attached, so the live stage starts tracking on enter. Make that succeed, otherwise the session is ended.
    (transportService.getRegisteredCommand(Commands.Command.CommandType.START_ALLOC_TRACKING) as MemoryAllocTracking).trackStatus =
      Memory.TrackStatus.newBuilder().setStatus(Memory.TrackStatus.Status.SUCCESS).build()
    setupDeviceAndStage()
    assumeTrue(isLive)
    val (lo1, hi1) = tickOneSecThen(::getSelectedRange)
    val (lo2, hi2) = tickOneSecThen(::getSelectedRange)
    assertThat(lo1).isEqualTo(stage.minTrackingTimeUs)
    assertThat(lo2).isEqualTo(lo1)
    assertThat(hi2).isGreaterThan(hi1)

    stage.stopTracking()
    val (lo3, hi3) = tickOneSecThen(::getSelectedRange)
    assertThat(lo3).isEqualTo(lo1)
    assertThat(hi3).isEqualTo(hi2)
  }

  @Test
  fun `selecting all satisfies that almost all are selected`() {
    setupDeviceAndStage()
    tickOneSecThen(stage::selectAll)
    assertThat(stage.isAlmostAllSelected()).isTrue()
  }

  @Test
  fun `exiting stage disables live allocations and issues stop command`() {
    assumeTrue(isLive)
    setupDeviceAndStage()
    val handler = transportService.getRegisteredCommand(Commands.Command.CommandType.STOP_ALLOC_TRACKING) as MemoryAllocTracking
    val prevCommand = handler.lastCommand
    stage.exit()
    tickOneSec()
    assertThat(handler.lastCommand.type).isEqualTo(Commands.Command.CommandType.STOP_ALLOC_TRACKING)
    assertThat(handler.lastCommand.commandId).isNotEqualTo(prevCommand.commandId)
  }

  private fun getSelectedRange() = with(stage.timeline.selectionRange) { Pair(min, max) }

  companion object {
    @Parameterized.Parameters @JvmStatic fun isLiveAllocationStage() = listOf(false, true)
  }
}
