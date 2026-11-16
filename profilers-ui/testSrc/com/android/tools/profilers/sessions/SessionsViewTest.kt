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
package com.android.tools.profilers.sessions

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.adtui.model.stdui.CommonAction
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Common.Process.ExposureLevel.PROFILEABLE
import com.android.tools.profilers.FakeIdeProfilerComponents
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.Utils.debuggableProcess
import com.android.tools.profilers.Utils.newDevice
import com.android.tools.profilers.Utils.onlineDevice
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import java.awt.event.ActionEvent
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class SessionsViewTest {

  private val myTimer = FakeTimer()
  private val myTransportService = FakeTransportService(myTimer, false)
  private val myIdeProfilerServices = FakeIdeProfilerServices()

  @get:Rule var myGrpcChannel = FakeGrpcChannel("SessionsViewTestChannel", myTransportService)
  @get:Rule val myEdtRule = EdtRule()

  private lateinit var myProfilers: StudioProfilers
  private lateinit var mySessionsManager: SessionsManager
  private lateinit var mySessionsView: SessionsView

  @Before
  fun setup() {
    myProfilers = StudioProfilers(ProfilerClient(myGrpcChannel.channel), myIdeProfilerServices, myTimer)
    mySessionsManager = myProfilers.sessionsManager
    mySessionsView = SessionsView(myProfilers, FakeIdeProfilerComponents())
  }

  @Test
  fun testProcessDropdownUpToDateForProfileables() {
    val device1 = onlineDevice {
      deviceId = NEW_DEVICE_ID_1
      manufacturer = "Manufacturer1"
      model = "Model1"
    }
    val device2 = onlineDevice {
      deviceId = NEW_DEVICE_ID_2
      manufacturer = "Manufacturer2"
      model = "Model2"
    }
    val process1 = debuggableProcess {
      pid = 10
      deviceId = NEW_DEVICE_ID_1
      name = "Process1"
      exposureLevel = PROFILEABLE
    }
    val otherProcess1 = debuggableProcess {
      pid = 20
      deviceId = NEW_DEVICE_ID_1
      name = "Other1"
    }
    val otherProcess2 = debuggableProcess {
      pid = 30
      deviceId = NEW_DEVICE_ID_2
      name = "Other2"
      exposureLevel = PROFILEABLE
    }
    // Process* is preferred, Other* should be in the other processes flyout.
    myProfilers.setPreferredProcess("Manufacturer1 Model1", "Process", null)

    var selectionAction = mySessionsView.processSelectionAction
    assertThat(selectionAction.childrenActionCount).isEqualTo(3)
    var loadAction = selectionAction.childrenActions.first { c -> c.text == "Load from file..." }
    assertThat(loadAction.isEnabled).isTrue()
    assertThat(loadAction.childrenActionCount).isEqualTo(0)
    assertThat(selectionAction.childrenActions[1]).isInstanceOf(CommonAction.SeparatorAction::class.java)
    assertThat(selectionAction.childrenActions[2].text).isEqualTo(SessionsView.NO_SUPPORTED_DEVICES)
    assertThat(selectionAction.childrenActions[2].isEnabled).isFalse()

    myTransportService.addDevice(device1)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    assertThat(selectionAction.childrenActionCount).isEqualTo(3)
    assertThat(selectionAction.childrenActions[1]).isInstanceOf(CommonAction.SeparatorAction::class.java)
    loadAction = selectionAction.childrenActions.first { c -> c.text == "Load from file..." }
    assertThat(loadAction.isEnabled).isTrue()
    assertThat(loadAction.childrenActionCount).isEqualTo(0)
    var deviceAction1 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer1 Model1" }
    assertThat(deviceAction1.isEnabled).isTrue()
    assertThat(deviceAction1.childrenActionCount).isEqualTo(1)
    assertThat(deviceAction1.childrenActions[0].text).isEqualTo(SessionsView.NO_DEBUGGABLE_OR_PROFILEABLE_PROCESSES)
    assertThat(deviceAction1.childrenActions[0].isEnabled).isFalse()

    myTransportService.addProcess(device1, process1)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    myProfilers.setProcess(device1, process1)
    assertThat(selectionAction.childrenActionCount).isEqualTo(3)
    deviceAction1 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer1 Model1" }
    assertThat(deviceAction1.isEnabled).isTrue()
    assertThat(deviceAction1.childrenActionCount).isEqualTo(3)
    var processAction1 = deviceAction1.childrenActions.first { c -> c.text == "Process1 (10) (profileable)" }
    assertThat(processAction1.childrenActionCount).isEqualTo(0)

    myTransportService.addProcess(device1, otherProcess1)
    try {
      myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    } catch (e: NullPointerException) {
      /* Ignore error from HelpToolTip in test but not in production */
    }
    assertThat(selectionAction.childrenActionCount).isEqualTo(3)
    deviceAction1 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer1 Model1" }
    assertThat(deviceAction1.isEnabled).isTrue()
    assertThat(deviceAction1.childrenActionCount).isEqualTo(4) // process1 + separator + "other debuggables" + "other profileables"
    processAction1 = deviceAction1.childrenActions.first { c -> c.text == "Process1 (10) (profileable)" }
    assertThat(deviceAction1.childrenActions[1]).isInstanceOf(CommonAction.SeparatorAction::class.java)
    var processAction2 =
      deviceAction1.childrenActions
        .first { c -> c.text == "Other debuggable processes" }
        .childrenActions
        .first { c -> c.text == "Other1 (20)" }

    // Test the reverse case of having only "other" processes
    myTransportService.addDevice(device2)
    myTransportService.addProcess(device2, otherProcess2)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    assertThat(selectionAction.childrenActionCount).isEqualTo(4)
    deviceAction1 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer1 Model1" }
    assertThat(deviceAction1.isEnabled).isTrue()
    assertThat(deviceAction1.childrenActionCount).isEqualTo(4) // process1 + separator + "other debuggables" + "other profileables"
    processAction1 = deviceAction1.childrenActions.first { c -> c.text == "Process1 (10) (profileable)" }
    assertThat(deviceAction1.childrenActions[1]).isInstanceOf(CommonAction.SeparatorAction::class.java)
    processAction2 =
      deviceAction1.childrenActions
        .first { c -> c.text == "Other debuggable processes" }
        .childrenActions
        .first { c -> c.text == "Other1 (20)" }
    var deviceAction2 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer2 Model2" }
    assertThat(deviceAction2.isEnabled).isTrue()
    assertThat(deviceAction2.childrenActionCount).isEqualTo(2) // No separator + "no other debuggables" + "other profileables"
    var processAction3 =
      deviceAction2.childrenActions
        .first { c -> c.text == "Other profileable processes" }
        .childrenActions
        .first { c -> c.text == "Other2 (30)" }
  }

  @Test
  fun testUnsupportedDeviceDropdown() {
    val unsupportedReasonText = "Unsupported"
    val device = onlineDevice {
      deviceId = NEW_DEVICE_ID_1
      manufacturer = "Manufacturer1"
      model = "Model1"
      unsupportedReason = unsupportedReasonText
    }
    myTransportService.addDevice(device)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)

    var selectionAction = mySessionsView.processSelectionAction
    assertThat(selectionAction.childrenActionCount).isEqualTo(3)
    var loadAction = selectionAction.childrenActions.first { c -> c.text == "Load from file..." }
    assertThat(loadAction.isEnabled).isTrue()
    assertThat(loadAction.childrenActionCount).isEqualTo(0)
    assertThat(selectionAction.childrenActions[1]).isInstanceOf(CommonAction.SeparatorAction::class.java)
    var deviceAction1 = selectionAction.childrenActions.first { c -> c.text == "Manufacturer1 Model1" }
    assertThat(deviceAction1.isEnabled).isTrue()
    assertThat(deviceAction1.childrenActionCount).isEqualTo(1)
    assertThat(deviceAction1.childrenActions[0].text).isEqualTo(unsupportedReasonText)
    assertThat(deviceAction1.childrenActions[0].isEnabled).isFalse()
  }

  @Test
  fun testProcessDropdownHideDeadDevicesAndProcesses() {
    val deadDevice =
      newDevice(Common.Device.State.DISCONNECTED) {
        deviceId = NEW_DEVICE_ID_1
        manufacturer = "Manufacturer1"
        model = "Model1"
      }
    val onlineDevice = onlineDevice {
      deviceId = NEW_DEVICE_ID_2
      manufacturer = "Manufacturer2"
      model = "Model2"
    }
    val deadProcess1 = debuggableProcess {
      pid = 10
      deviceId = NEW_DEVICE_ID_1
      name = "Process1"
      state = Common.Process.State.DEAD
    }
    val aliveProcess1 = debuggableProcess {
      pid = 20
      deviceId = NEW_DEVICE_ID_1
      name = "Process2"
    }
    val deadProcess2 = debuggableProcess {
      pid = 30
      deviceId = NEW_DEVICE_ID_2
      name = "Process3"
      state = Common.Process.State.DEAD
    }
    val aliveProcess2 = debuggableProcess {
      pid = 40
      deviceId = NEW_DEVICE_ID_2
      name = "Process4"
    }
    val deadProcess3 = debuggableProcess {
      pid = 50
      deviceId = NEW_DEVICE_ID_2
      name = "Dead"
      state = Common.Process.State.DEAD
    }
    // Also test processes that can be grouped in the fly-out menu.
    myProfilers.setPreferredProcess("Manufacturer2 Model2", "Process4", null)

    myTransportService.addDevice(deadDevice)
    myTransportService.addDevice(onlineDevice)
    myTransportService.addProcess(deadDevice, deadProcess1)
    myTransportService.addProcess(deadDevice, aliveProcess1)
    myTransportService.addProcess(onlineDevice, deadProcess2)
    myTransportService.addProcess(onlineDevice, aliveProcess2)
    myTransportService.addProcess(onlineDevice, deadProcess3)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    var selectionAction = mySessionsView.processSelectionAction
    assertThat(selectionAction.childrenActions.any { c -> c.text == "Manufacturer1 Model1" }).isFalse()
    val aliveDeviceAction = selectionAction.childrenActions.first { c -> c.text == "Manufacturer2 Model2" }
    assertThat(aliveDeviceAction.childrenActionCount).isEqualTo(3) // the process + no other debuggables + no other profileables
    val processAction1 = aliveDeviceAction.childrenActions.first { c -> c.text == "Process4 (40) (debuggable)" }
    assertThat(processAction1.childrenActionCount).isEqualTo(0)
  }

  @Test
  fun testDropdownActionsTriggerProcessChange() {
    val device1 = onlineDevice {
      deviceId = NEW_DEVICE_ID_1
      manufacturer = "Manufacturer1"
      model = "Model1"
    }
    val device2 = onlineDevice {
      deviceId = NEW_DEVICE_ID_2
      manufacturer = "Manufacturer2"
      model = "Model2"
    }
    val process1 = debuggableProcess {
      pid = 10
      deviceId = NEW_DEVICE_ID_1
      name = "Process1"
    }
    val process2 = debuggableProcess {
      pid = 20
      deviceId = NEW_DEVICE_ID_1
      name = "Process2"
    }
    val process3 = debuggableProcess {
      pid = 10
      deviceId = NEW_DEVICE_ID_2
      name = "Process3"
    }
    // Mark all process as preferred processes as we are not testing the other processes flyout here.
    myProfilers.setPreferredProcess(null, "Process", null)

    myTransportService.addDevice(device1)
    myTransportService.addProcess(device1, process1)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)

    myTransportService.addProcess(device1, process2)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    var selectionAction = mySessionsView.processSelectionAction
    var processAction2 =
      selectionAction.childrenActions
        .first { c -> c.text == "Manufacturer1 Model1" }
        .childrenActions
        .first { c -> c.text == "Process2 (20) (debuggable)" }
    processAction2.actionPerformed(ActionEvent(processAction2, 0, ""))
    assertThat(myProfilers.device).isEqualTo(device1)
    assertThat(myProfilers.process).isEqualTo(process2)

    myTransportService.addDevice(device2)
    myTransportService.addProcess(device2, process3)
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    var processAction3 =
      selectionAction.childrenActions
        .first { c -> c.text == "Manufacturer2 Model2" }
        .childrenActions
        .first { c -> c.text == "Process3 (10) (debuggable)" }
    processAction3.actionPerformed(ActionEvent(processAction3, 0, ""))
    assertThat(myProfilers.device).isEqualTo(device2)
    assertThat(myProfilers.process).isEqualTo(process3)
  }

  companion object {
    const val NEW_DEVICE_ID_1 = 1L
    const val NEW_DEVICE_ID_2 = 2L
  }
}
