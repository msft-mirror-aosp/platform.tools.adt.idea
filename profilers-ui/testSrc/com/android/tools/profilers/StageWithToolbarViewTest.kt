/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.sdklib.AndroidVersion
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.adtui.stdui.ContextMenuItem
import com.android.tools.idea.transport.faketransport.FakeGrpcServer
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Common.AgentData
import com.google.common.truth.Truth
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import icons.StudioIcons
import javax.swing.JComponent
import javax.swing.JPanel
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@RunsInEdt
class StageWithToolbarViewTest(private val isTestingProfileable: Boolean) {
  @get:Rule val applicationRule = ApplicationRule()
  private val timer = FakeTimer()
  private val service =
    if (isTestingProfileable) FakeTransportService(timer, true, AndroidVersion.VersionCodes.S, Common.Process.ExposureLevel.PROFILEABLE)
    else FakeTransportService(timer)

  @JvmField @Rule val grpcChannel: FakeGrpcServer = FakeGrpcServer.createFakeGrpcServer("StageWithToolbarViewTestChannel", service)
  @get:Rule val myEdtRule = EdtRule()

  private val ideProfilerServices = FakeIdeProfilerServices()
  private lateinit var studioProfilers: StudioProfilers
  private lateinit var fakeStudioProfilersView: FakeStudioProfilersView
  private lateinit var stageWithToolbarView: StageWithToolbarView
  private val stageComponent = JPanel()

  @Before
  fun setUp() {
    studioProfilers = StudioProfilers(ProfilerClient(grpcChannel.channel), ideProfilerServices, timer)
    studioProfilers.setPreferredProcess(FakeTransportService.FAKE_DEVICE_NAME, FakeTransportService.FAKE_PROCESS_NAME, null)
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Sessions are not started automatically, so explicitly start one with the fake process.
    Utils.startSessionOnFakeProcess(studioProfilers, timer)

    val fakeIdeProfilerComponents: IdeProfilerComponents = FakeIdeProfilerComponents()
    fakeStudioProfilersView = FakeStudioProfilersView(studioProfilers, fakeIdeProfilerComponents)
    stageWithToolbarView =
      StageWithToolbarView(
        studioProfilers,
        stageComponent,
        fakeIdeProfilerComponents,
        { stage: Stage<*> -> buildStageView(stage) },
        JPanel(),
      )

    if (isTestingProfileable) {
      // We setup and profile a process, we assume that process has an agent attached by default.
      updateAgentStatus(FakeTransportService.FAKE_PROCESS.pid, ProfilersTestData.DEFAULT_AGENT_ATTACHED_RESPONSE)
    }
  }

  private fun buildStageView(stage: Stage<*>): StageView<*> =
    when (stage) {
      is NullMonitorStage -> NullMonitorStageView(fakeStudioProfilersView, stage)
      is FakeStage -> FakeStageView(fakeStudioProfilersView, stage)
      else -> {
        throw IllegalStateException("Unsupported stage found: ${stage.stageType}")
      }
    }

  @Test
  fun testGoLiveButtonStates() {
    // Check that go live is initially enabled and toggled
    val liveButton = stageWithToolbarView.goLiveButton
    val contextMenuItems = ProfilerContextMenu.createIfAbsent(stageComponent).contextMenuItems
    var attachItem: ContextMenuItem? = null
    var detachItem: ContextMenuItem? = null
    for (item in contextMenuItems) {
      if (item.text == StageWithToolbarView.ATTACH_LIVE) {
        attachItem = item
      } else if (item.text == StageWithToolbarView.DETACH_LIVE) {
        detachItem = item
      }
    }
    Truth.assertThat(attachItem).isNotNull()
    Truth.assertThat(detachItem).isNotNull()
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isTrue()
    Truth.assertThat(liveButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isSelected).isTrue()
    Truth.assertThat(attachItem!!.isEnabled).isFalse()
    Truth.assertThat(detachItem!!.isEnabled).isTrue()

    // Detaching from live should unselect the button.
    detachItem.run()
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isTrue()
    Truth.assertThat(liveButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isSelected).isFalse()
    Truth.assertThat(attachItem.isEnabled).isTrue()
    Truth.assertThat(detachItem.isEnabled).isFalse()
    Truth.assertThat(liveButton.icon).isEqualTo(StudioIcons.Profiler.Toolbar.GOTO_LIVE)
    Truth.assertThat(liveButton.toolTipText).startsWith(StageWithToolbarView.ATTACH_LIVE)

    // Attaching to live should select the button again.
    attachItem.run()
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isTrue()
    Truth.assertThat(liveButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isSelected).isTrue()
    Truth.assertThat(attachItem.isEnabled).isFalse()
    Truth.assertThat(detachItem.isEnabled).isTrue()
    Truth.assertThat(liveButton.icon).isEqualTo(StudioIcons.Profiler.Toolbar.PAUSE_LIVE)
    Truth.assertThat(liveButton.toolTipText).startsWith(StageWithToolbarView.DETACH_LIVE)

    // Stopping the session should disable and unselect the button
    endSession()
    val deadSession = studioProfilers.sessionsManager.selectedSession
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isFalse()
    Truth.assertThat(liveButton.isEnabled).isFalse()
    Truth.assertThat(liveButton.isSelected).isFalse()
    Truth.assertThat(attachItem.isEnabled).isFalse()
    Truth.assertThat(detachItem.isEnabled).isFalse()
    startSessionWithNewDeviceAndProcess()
    if (isTestingProfileable) {
      updateAgentStatus(NEW_PROCESS_ID, ProfilersTestData.DEFAULT_AGENT_ATTACHED_RESPONSE)
    }
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isTrue()

    // Live button should be selected when switching to a live session.
    Truth.assertThat(liveButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isSelected).isTrue()
    Truth.assertThat(attachItem.isEnabled).isFalse()
    Truth.assertThat(detachItem.isEnabled).isTrue()

    // Switching to a dead session should disable and unselect the button.
    studioProfilers.sessionsManager.setSession(deadSession)
    Truth.assertThat(liveButton.isEnabled).isFalse()
    Truth.assertThat(liveButton.isSelected).isFalse()
    Truth.assertThat(attachItem.isEnabled).isFalse()
    Truth.assertThat(detachItem.isEnabled).isFalse()
  }

  @Test
  fun testGoLiveButtonWhenToggleStreaming() {
    val liveButton = stageWithToolbarView.goLiveButton
    Truth.assertThat(liveButton.isEnabled).isTrue()
    studioProfilers.timeline.isStreaming = false
    Truth.assertThat(liveButton.isSelected).isFalse()
    Truth.assertThat(liveButton.icon).isEqualTo(StudioIcons.Profiler.Toolbar.GOTO_LIVE)
    Truth.assertThat(liveButton.toolTipText).startsWith(StageWithToolbarView.ATTACH_LIVE)
    studioProfilers.timeline.isStreaming = true
    Truth.assertThat(liveButton.isSelected).isTrue()
    Truth.assertThat(liveButton.icon).isEqualTo(StudioIcons.Profiler.Toolbar.PAUSE_LIVE)
    Truth.assertThat(liveButton.toolTipText).startsWith(StageWithToolbarView.DETACH_LIVE)
  }

  @Test
  fun testTimelineButtonEnableStates() {
    val zoomInButton = stageWithToolbarView.zoomInButton
    val zoomOutButton = stageWithToolbarView.zoomOutButton
    val resetButton = stageWithToolbarView.resetZoomButton
    val frameSelectionButton = stageWithToolbarView.zoomToSelectionButton
    val liveButton = stageWithToolbarView.goLiveButton

    // A live session without agent should have all controls enabled
    Truth.assertThat(studioProfilers.sessionsManager.isSessionAlive).isTrue()
    Truth.assertThat(zoomInButton.isEnabled).isTrue()
    Truth.assertThat(zoomOutButton.isEnabled).isTrue()
    Truth.assertThat(resetButton.isEnabled).isTrue()
    Truth.assertThat(frameSelectionButton.isEnabled).isFalse() // Frame selection button is dependent on selection being available.
    Truth.assertThat(liveButton.isEnabled).isTrue()

    // Updating the selection should enable the frame selection control.
    studioProfilers.timeline.selectionRange.set(studioProfilers.timeline.dataRange)
    Truth.assertThat(zoomInButton.isEnabled).isTrue()
    Truth.assertThat(zoomOutButton.isEnabled).isTrue()
    Truth.assertThat(resetButton.isEnabled).isTrue()
    Truth.assertThat(frameSelectionButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isEnabled).isTrue()

    // Stopping the session should disable the live control
    endSession()
    Truth.assertThat(zoomInButton.isEnabled).isTrue()
    Truth.assertThat(zoomOutButton.isEnabled).isTrue()
    Truth.assertThat(resetButton.isEnabled).isTrue()
    Truth.assertThat(frameSelectionButton.isEnabled).isTrue()
    Truth.assertThat(liveButton.isEnabled).isFalse()

    // Clearing the selection should disable the frame selection control.
    studioProfilers.timeline.selectionRange.clear()
    Truth.assertThat(frameSelectionButton.isEnabled).isFalse()

    // Starting a session that is waiting for an agent to initialize should not disable the controls.
    startSessionWithNewDeviceAndProcess()
    updateAgentStatus(NEW_PROCESS_ID, ProfilersTestData.DEFAULT_AGENT_UNSPECIFIED_RESPONSE)
    Truth.assertThat(zoomInButton.isEnabled).isTrue()
    Truth.assertThat(zoomOutButton.isEnabled).isTrue()
    Truth.assertThat(resetButton.isEnabled).isTrue()
    Truth.assertThat(frameSelectionButton.isEnabled).isFalse()
    Truth.assertThat(liveButton.isEnabled).isTrue()

    // Controls should remain enabled after agent is attached.
    updateAgentStatus(NEW_PROCESS_ID, ProfilersTestData.DEFAULT_AGENT_ATTACHED_RESPONSE)
    Truth.assertThat(zoomInButton.isEnabled).isTrue()
    Truth.assertThat(zoomOutButton.isEnabled).isTrue()
    Truth.assertThat(resetButton.isEnabled).isTrue()
    Truth.assertThat(frameSelectionButton.isEnabled).isFalse()
    Truth.assertThat(liveButton.isEnabled).isTrue()

    // Setting to an empty session should have all controls disabled.
    studioProfilers.sessionsManager.setSession(Common.Session.getDefaultInstance())
    Truth.assertThat(zoomInButton.isEnabled).isFalse()
    Truth.assertThat(zoomOutButton.isEnabled).isFalse()
    Truth.assertThat(resetButton.isEnabled).isFalse()
    Truth.assertThat(frameSelectionButton.isEnabled).isFalse()
    Truth.assertThat(liveButton.isEnabled).isFalse()
  }

  @Test
  fun testLoadingPanelWhileWaitingForAgentAttach() {
    Assume.assumeFalse(isTestingProfileable) // hardcoded `FAKE_DEVICE` is different than one used for the profileable test
    Truth.assertThat(stageWithToolbarView.stageViewComponent.isVisible).isTrue()
    Truth.assertThat(stageWithToolbarView.stageLoadingComponent.isVisible).isFalse()
    val process =
      Common.Process.newBuilder()
        .setPid(NEW_PROCESS_ID)
        .setDeviceId(FakeTransportService.FAKE_DEVICE_ID)
        .setState(Common.Process.State.ALIVE)
        .setName(FAKE_PROCESS_2)
        .setExposureLevel(Common.Process.ExposureLevel.DEBUGGABLE)
        .build()
    startSession(FakeTransportService.FAKE_DEVICE, process)
    updateAgentStatus(NEW_PROCESS_ID, ProfilersTestData.DEFAULT_AGENT_UNSPECIFIED_RESPONSE)

    // Agent is detached, the UI should wait and show the loading panel.
    Truth.assertThat(stageWithToolbarView.stageViewComponent.isVisible).isFalse()
    Truth.assertThat(stageWithToolbarView.stageLoadingComponent.isVisible).isTrue()
    updateAgentStatus(NEW_PROCESS_ID, ProfilersTestData.DEFAULT_AGENT_ATTACHED_RESPONSE)

    // Attach status is detected, loading should stop.
    Truth.assertThat(stageWithToolbarView.stageViewComponent.isVisible).isTrue()
    Truth.assertThat(stageWithToolbarView.stageLoadingComponent.isVisible).isFalse()
  }

  @Test
  fun nonTimelineStageHidesTimelineNavigationToolbar() {
    studioProfilers.stage = FakeStage(studioProfilers, false)
    Truth.assertThat(stageWithToolbarView.timelineNavigationToolbar.isVisible).isFalse()
  }

  @Test
  fun timelineStageShowsTimelineNavigationToolbar() {
    // The default stage is NullMonitorStage.
    Truth.assertThat(stageWithToolbarView.timelineNavigationToolbar.isVisible).isTrue()
  }

  @Test
  fun timelineNavigationToolbarPanelsAreTransparent() {
    // The timeline controls move between toolbars, e.g. into the Live Telemetry toolbar, so their panels must show the background of the
    // toolbar hosting them rather than paint their own.
    Truth.assertThat(stageWithToolbarView.timelineNavigationToolbar.isOpaque).isFalse()
    Truth.assertThat(stageWithToolbarView.timelineZoomToolbar.component.isOpaque).isFalse()
    Truth.assertThat((stageWithToolbarView.goLiveButton.parent as JComponent).isOpaque).isFalse()
  }

  private fun startSessionWithNewDeviceAndProcess() {
    val onlineDevice =
      Common.Device.newBuilder()
        .setDeviceId(NEW_DEVICE_ID.toLong())
        .setFeatureLevel(AndroidVersion.VersionCodes.O)
        .setState(Common.Device.State.ONLINE)
        .build()
    val onlineProcess =
      Common.Process.newBuilder()
        .setPid(NEW_PROCESS_ID)
        .setDeviceId(NEW_DEVICE_ID.toLong())
        .setState(Common.Process.State.ALIVE)
        .setExposureLevel(if (isTestingProfileable) Common.Process.ExposureLevel.PROFILEABLE else Common.Process.ExposureLevel.DEBUGGABLE)
        .build()
    startSession(onlineDevice, onlineProcess)
  }

  private fun startSession(device: Common.Device, process: Common.Process) {
    service.addDevice(device)
    updateProcess(device, process)
  }

  private fun updateProcess(device: Common.Device, process: Common.Process) {
    service.addProcess(device, process)
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    Utils.startSession(studioProfilers, timer, device, process)
  }

  private fun updateAgentStatus(pid: Int, agentData: AgentData) {
    val sessionStreamId = studioProfilers.session.streamId
    service.addEventToStream(
      sessionStreamId,
      Common.Event.newBuilder().setPid(pid).setKind(Common.Event.Kind.AGENT).setAgentData(agentData).build(),
    )
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
  }

  private fun endSession() {
    studioProfilers.sessionsManager.endCurrentSession()
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
  }

  private class FakeStudioProfilersView(
    override val studioProfilers: StudioProfilers,
    override val ideProfilerComponents: IdeProfilerComponents,
  ) : StudioProfilersView {
    override val component: JComponent
      get() = JPanel()

    override val stageWithToolbarView: StageWithToolbarView
      get() = TODO("Not yet implemented")

    override val stageComponent: JPanel
      get() = TODO("Not yet implemented")

    override val stageView: StageView<*>?
      get() = null

    override fun installCommonMenuItems(component: JComponent) {}

    override fun dispose() {}
  }

  companion object {
    private const val NEW_DEVICE_ID = 1
    private const val NEW_PROCESS_ID = 2
    private const val FAKE_PROCESS_2 = "FakeProcess2"

    @JvmStatic @Parameterized.Parameters fun isTestingProfileable() = listOf(false, true)
  }
}
