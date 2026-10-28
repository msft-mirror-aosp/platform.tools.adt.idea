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
package com.android.tools.idea.streaming.emulator.actions

import com.android.sdklib.AndroidVersion
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.findModelessDialog
import com.android.tools.adtui.swing.modelessDialogs
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.StagingIcons
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorViewRule
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.testutil.newEmulatorView
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test

/** Tests for [EmulatorExtendedControlsActionGroup]. */
@RunsInEdt
class EmulatorExtendedControlsActionGroupTest {
  private val emulatorViewRule = EmulatorViewRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      ApplicationRule(),
      ClipboardSynchronizationDisablementRule(),
      emulatorViewRule,
      EdtRule(),
      HeadlessDialogRule(),
    )

  @Test
  fun testFlagEnabledAndDisabledVisibility() {
    val view = emulatorViewRule.newEmulatorView()
    val actionId = "android.emulator.extended.controls"
    val locationActionId = "android.emulator.location"
    val displaysActionId = "android.emulator.displays"
    val cellularActionId = "android.emulator.cellular"
    val batteryActionId = "android.emulator.battery"
    val cameraActionId = "android.emulator.camera"
    val phoneActionId = "android.emulator.phone"
    val devicePoseActionId = "android.emulator.device.pose"
    val sensorsActionId = "android.emulator.sensors"
    val fingerprintActionId = "android.emulator.fingerprint"
    val microphoneActionId = "android.emulator.microphone"

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(false, emulatorViewRule.disposable)
    val presentationDisabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationDisabled.isVisible).isFalse()
    assertThat(presentationDisabled.isEnabled).isFalse()

    val locationDisabled = updateAndGetActionPresentation(locationActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(locationDisabled.isVisible).isFalse()
    assertThat(locationDisabled.isEnabled).isFalse()

    val displaysDisabled = updateAndGetActionPresentation(displaysActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(displaysDisabled.isVisible).isFalse()
    assertThat(displaysDisabled.isEnabled).isFalse()

    val cellularDisabled = updateAndGetActionPresentation(cellularActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cellularDisabled.isVisible).isFalse()
    assertThat(cellularDisabled.isEnabled).isFalse()

    val batteryDisabled = updateAndGetActionPresentation(batteryActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(batteryDisabled.isVisible).isFalse()
    assertThat(batteryDisabled.isEnabled).isFalse()

    val cameraDisabled = updateAndGetActionPresentation(cameraActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cameraDisabled.isVisible).isFalse()
    assertThat(cameraDisabled.isEnabled).isFalse()

    val phoneDisabled = updateAndGetActionPresentation(phoneActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(phoneDisabled.isVisible).isFalse()
    assertThat(phoneDisabled.isEnabled).isFalse()

    val devicePoseDisabled = updateAndGetActionPresentation(devicePoseActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(devicePoseDisabled.isVisible).isFalse()
    assertThat(devicePoseDisabled.isEnabled).isFalse()

    val sensorsDisabled = updateAndGetActionPresentation(sensorsActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(sensorsDisabled.isVisible).isFalse()
    assertThat(sensorsDisabled.isEnabled).isFalse()

    val fingerprintDisabled = updateAndGetActionPresentation(fingerprintActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(fingerprintDisabled.isVisible).isFalse()
    assertThat(fingerprintDisabled.isEnabled).isFalse()

    val microphoneDisabled = updateAndGetActionPresentation(microphoneActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(microphoneDisabled.isVisible).isFalse()
    assertThat(microphoneDisabled.isEnabled).isFalse()

    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, emulatorViewRule.disposable)
    val presentationEnabled = updateAndGetActionPresentation(actionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(presentationEnabled.isVisible).isTrue()
    assertThat(presentationEnabled.isEnabled).isTrue()

    val locationEnabled = updateAndGetActionPresentation(locationActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(locationEnabled.isVisible).isTrue()
    assertThat(locationEnabled.isEnabled).isTrue()

    val displaysEnabled = updateAndGetActionPresentation(displaysActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(displaysEnabled.isVisible).isTrue()
    assertThat(displaysEnabled.isEnabled).isTrue()

    val cellularEnabled = updateAndGetActionPresentation(cellularActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cellularEnabled.isVisible).isTrue()
    assertThat(cellularEnabled.isEnabled).isTrue()

    val batteryEnabled = updateAndGetActionPresentation(batteryActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(batteryEnabled.isVisible).isTrue()
    assertThat(batteryEnabled.isEnabled).isTrue()

    val cameraEnabled = updateAndGetActionPresentation(cameraActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cameraEnabled.isVisible).isTrue()
    assertThat(cameraEnabled.isEnabled).isTrue()

    val phoneEnabled = updateAndGetActionPresentation(phoneActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(phoneEnabled.isVisible).isTrue()
    assertThat(phoneEnabled.isEnabled).isTrue()

    val devicePoseEnabled = updateAndGetActionPresentation(devicePoseActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(devicePoseEnabled.isVisible).isTrue()
    assertThat(devicePoseEnabled.isEnabled).isTrue()

    val sensorsEnabled = updateAndGetActionPresentation(sensorsActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(sensorsEnabled.isVisible).isTrue()
    assertThat(sensorsEnabled.isEnabled).isTrue()

    val fingerprintEnabled = updateAndGetActionPresentation(fingerprintActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(fingerprintEnabled.isVisible).isTrue()
    assertThat(fingerprintEnabled.isEnabled).isTrue()

    val microphoneEnabled = updateAndGetActionPresentation(microphoneActionId, view, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(microphoneEnabled.isVisible).isTrue()
    assertThat(microphoneEnabled.isEnabled).isTrue()

    val automotiveView = emulatorViewRule.newEmulatorView(avdCreator = { path -> FakeEmulator.createAutomotiveAvd(path) })
    val batteryAutomotive = updateAndGetActionPresentation(batteryActionId, automotiveView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(batteryAutomotive.isVisible).isFalse()
    assertThat(batteryAutomotive.isEnabled).isFalse()

    val cameraAutomotive = updateAndGetActionPresentation(cameraActionId, automotiveView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(cameraAutomotive.isVisible).isFalse()
    assertThat(cameraAutomotive.isEnabled).isFalse()

    val devicePoseAutomotive =
      updateAndGetActionPresentation(devicePoseActionId, automotiveView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(devicePoseAutomotive.isVisible).isTrue()
    assertThat(devicePoseAutomotive.isEnabled).isTrue()

    val sensorsAutomotive = updateAndGetActionPresentation(sensorsActionId, automotiveView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(sensorsAutomotive.isVisible).isTrue()
    assertThat(sensorsAutomotive.isEnabled).isTrue()

    val watchView = emulatorViewRule.newEmulatorView(avdCreator = { path -> FakeEmulator.createWatchAvd(path) })
    val devicePoseWatch = updateAndGetActionPresentation(devicePoseActionId, watchView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(devicePoseWatch.isVisible).isTrue()
    assertThat(devicePoseWatch.isEnabled).isTrue()

    val sensorsWatch = updateAndGetActionPresentation(sensorsActionId, watchView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(sensorsWatch.isVisible).isTrue()
    assertThat(sensorsWatch.isEnabled).isTrue()

    val tvView = emulatorViewRule.newEmulatorView(avdCreator = { path -> FakeEmulator.createTvAvd(path) })
    val devicePoseTv = updateAndGetActionPresentation(devicePoseActionId, tvView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(devicePoseTv.isVisible).isFalse()
    assertThat(devicePoseTv.isEnabled).isFalse()

    val sensorsTv = updateAndGetActionPresentation(sensorsActionId, tvView, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(sensorsTv.isVisible).isFalse()
    assertThat(sensorsTv.isEnabled).isFalse()

    val api22View =
      emulatorViewRule.newEmulatorView(avdCreator = { path -> FakeEmulator.createPhoneAvd(path, androidVersion = AndroidVersion(22, 0)) })
    val fingerprintApi22 = updateAndGetActionPresentation(fingerprintActionId, api22View, emulatorViewRule.project, ActionPlaces.TOOLBAR)
    assertThat(fingerprintApi22.isVisible).isFalse()
    assertThat(fingerprintApi22.isEnabled).isFalse()
  }

  @Test
  fun testChildrenAndOpenDialogs() {
    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, emulatorViewRule.disposable)
    val view = emulatorViewRule.newEmulatorView()
    val avdName = view.emulator.emulatorId.avdName

    val actionManager = ActionManager.getInstance()
    val group = actionManager.getAction("android.emulator.extended.controls") as DefaultActionGroup
    assertThat(group.isPopup).isTrue()

    val children = group.getChildren(actionManager)
    assertThat(children).hasLength(10)
    val locationAction = children[0]
    assertThat(locationAction.templatePresentation.text).isEqualTo("Location")
    assertThat(locationAction.templatePresentation.icon).isEqualTo(StagingIcons.LOCATION)

    val displaysAction = children[1]
    assertThat(displaysAction.templatePresentation.text).isEqualTo("Displays")
    assertThat(displaysAction.templatePresentation.icon).isEqualTo(StagingIcons.DISPLAYS)

    val cellularAction = children[2]
    assertThat(cellularAction.templatePresentation.text).isEqualTo("Cellular")
    assertThat(cellularAction.templatePresentation.icon).isEqualTo(StagingIcons.CELLULAR)

    val batteryAction = children[3]
    assertThat(batteryAction.templatePresentation.text).isEqualTo("Battery")
    assertThat(batteryAction.templatePresentation.icon).isEqualTo(StagingIcons.BATTERY)

    val cameraAction = children[4]
    assertThat(cameraAction.templatePresentation.text).isEqualTo("Camera")
    assertThat(cameraAction.templatePresentation.icon).isEqualTo(StagingIcons.CAMERA)

    val phoneAction = children[5]
    assertThat(phoneAction.templatePresentation.text).isEqualTo("Phone")
    assertThat(phoneAction.templatePresentation.icon).isEqualTo(StagingIcons.PHONE)

    val devicePoseAction = children[6]
    assertThat(devicePoseAction.templatePresentation.text).isEqualTo("Device Pose")
    assertThat(devicePoseAction.templatePresentation.icon).isEqualTo(StagingIcons.DEVICE_POSE)

    val sensorsAction = children[7]
    assertThat(sensorsAction.templatePresentation.text).isEqualTo("Sensors")
    assertThat(sensorsAction.templatePresentation.icon).isEqualTo(StagingIcons.SENSORS)

    val fingerprintAction = children[8]
    assertThat(fingerprintAction.templatePresentation.text).isEqualTo("Fingerprint")
    assertThat(fingerprintAction.templatePresentation.icon).isEqualTo(StagingIcons.FINGERPRINT)

    val microphoneAction = children[9]
    assertThat(microphoneAction.templatePresentation.text).isEqualTo("Microphone")
    assertThat(microphoneAction.templatePresentation.icon).isEqualTo(StagingIcons.MICROPHONE)

    executeAction(locationAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    val locationDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(locationDlg.title).isEqualTo("Location ($avdName)")
    assertThat(modelessDialogs).containsExactly(locationDlg)

    executeAction(displaysAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(locationDlg.isShowing).isFalse()
    val displaysDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(displaysDlg.title).isEqualTo("Displays ($avdName)")
    assertThat(modelessDialogs).containsExactly(displaysDlg)

    // Executing the same action again reuses the existing open dialog instead of opening a second one.
    executeAction(displaysAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(modelessDialogs).containsExactly(displaysDlg)

    // Executing another extended controls action for the same AVD closes the previous dialog.
    executeAction(cellularAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(displaysDlg.isShowing).isFalse()
    val cellularDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(cellularDlg.title).isEqualTo("Cellular ($avdName)")
    assertThat(modelessDialogs).containsExactly(cellularDlg)

    executeAction(batteryAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(cellularDlg.isShowing).isFalse()
    val batteryDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(batteryDlg.title).isEqualTo("Battery ($avdName)")
    assertThat(modelessDialogs).containsExactly(batteryDlg)

    executeAction(cameraAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(batteryDlg.isShowing).isFalse()
    val cameraDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(cameraDlg.title).isEqualTo("Camera ($avdName)")
    assertThat(modelessDialogs).containsExactly(cameraDlg)

    executeAction(phoneAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(cameraDlg.isShowing).isFalse()
    val phoneDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(phoneDlg.title).isEqualTo("Phone ($avdName)")
    assertThat(modelessDialogs).containsExactly(phoneDlg)

    executeAction(devicePoseAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(phoneDlg.isShowing).isFalse()
    val devicePoseDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(devicePoseDlg.title).isEqualTo("Device Pose ($avdName)")
    assertThat(modelessDialogs).containsExactly(devicePoseDlg)

    executeAction(sensorsAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(devicePoseDlg.isShowing).isFalse()
    val sensorsDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(sensorsDlg.title).isEqualTo("Sensors ($avdName)")
    assertThat(modelessDialogs).containsExactly(sensorsDlg)

    executeAction(fingerprintAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(sensorsDlg.isShowing).isFalse()
    val fingerprintDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(fingerprintDlg.title).isEqualTo("Fingerprint ($avdName)")
    assertThat(modelessDialogs).containsExactly(fingerprintDlg)

    executeAction(microphoneAction, view, emulatorViewRule.project, ActionPlaces.POPUP)
    assertThat(fingerprintDlg.isShowing).isFalse()
    val microphoneDlg = findModelessDialog<DialogWrapper>()!!
    assertThat(microphoneDlg.title).isEqualTo("Microphone ($avdName)")
    assertThat(modelessDialogs).containsExactly(microphoneDlg)

    // A second AVD can have its own extended controls dialog open concurrently.
    val view2 = emulatorViewRule.newEmulatorView(avdCreator = { path -> FakeEmulator.createAutomotiveAvd(path) })
    val avdName2 = view2.emulator.emulatorId.avdName
    executeAction(cellularAction, view2, emulatorViewRule.project, ActionPlaces.POPUP)
    val cellularDlg2 = findModelessDialog<DialogWrapper> { it.title == "Cellular ($avdName2)" }!!
    assertThat(modelessDialogs).containsExactly(microphoneDlg, cellularDlg2)

    microphoneDlg.close(DialogWrapper.CLOSE_EXIT_CODE)
    cellularDlg2.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(modelessDialogs).isEmpty()
  }
}
