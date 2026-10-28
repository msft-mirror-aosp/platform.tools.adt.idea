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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.emulator.control.MicrophoneState
import com.android.sdklib.AndroidVersion
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.NotificationTracker
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBCheckBox
import java.nio.file.Path
import javax.swing.JButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [MicrophoneDialog]. */
@RunsInEdt
class MicrophoneDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      EdtRule(),
      HeadlessDialogRule(),
    )

  private fun createAndStartEmulator(
    avdCreator: (Path) -> Path = { FakeEmulator.createPhoneAvd(it) }
  ): Pair<FakeEmulator, EmulatorController> {
    val testAvdFolder = avdCreator(emulatorRule.avdRoot)
    val fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  @Test
  fun testInitialStateFromDefaults() {
    val (_, emulatorController) = createAndStartEmulator()
    val dialog = MicrophoneDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val headsetCheckBox = ui.getComponent<JBCheckBox> { it.name == "headsetInsertedCheckBox" }
    val micAttachedCheckBox = ui.getComponent<JBCheckBox> { it.name == "microphoneAttachedCheckBox" }
    val hostAudioCheckBox = ui.getComponent<JBCheckBox> { it.name == "hostAudioInputCheckBox" }
    val voiceAssistButton = ui.getComponent<JButton> { it.name == "voiceAssistButton" }

    assertThat(headsetCheckBox.text).isEqualTo("Virtual headset plug inserted")
    assertThat(headsetCheckBox.isSelected).isFalse()
    assertThat(headsetCheckBox.isEnabled).isTrue()

    assertThat(micAttachedCheckBox.text).isEqualTo("Virtual microphone attached")
    assertThat(micAttachedCheckBox.isSelected).isFalse()
    assertThat(micAttachedCheckBox.isEnabled).isFalse()

    assertThat(hostAudioCheckBox.text).isEqualTo("Virtual microphone uses host audio input")
    assertThat(hostAudioCheckBox.isSelected).isFalse()
    assertThat(hostAudioCheckBox.isEnabled).isTrue()

    assertThat(voiceAssistButton.text).isEqualTo("Voice Assist")
    assertThat(voiceAssistButton.toolTipText).isEqualTo("Launch the voice assistant on the device")
    assertThat(voiceAssistButton.isEnabled).isTrue()

    val closeButton = ui.getComponent<JButton> { it.text == "Close" }
    assertThat(rootPane.defaultButton).isNull()
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulatorAndExternalUpdates() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    fakeEmulator.microphoneState = MicrophoneState.newBuilder().setRealAudioEnabled(true).build()
    waitForCondition(5.seconds) { NotificationTracker.forEmulator(emulatorController).microphoneInput.value == true }

    val dialog = MicrophoneDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val hostAudioCheckBox = ui.getComponent<JBCheckBox> { it.name == "hostAudioInputCheckBox" }
    assertThat(hostAudioCheckBox.isSelected).isTrue()

    // External update from emulator notification stream updates the checkbox.
    fakeEmulator.microphoneState = MicrophoneState.newBuilder().setRealAudioEnabled(false).build()
    waitForCondition(5.seconds) { !hostAudioCheckBox.isSelected }

    // Toggling the checkbox updates the emulator state.
    ui.clickOn(hostAudioCheckBox)
    assertThat(hostAudioCheckBox.isSelected).isTrue()
    waitForCondition(5.seconds) { fakeEmulator.microphoneState.realAudioEnabled }

    ui.clickOn(hostAudioCheckBox)
    assertThat(hostAudioCheckBox.isSelected).isFalse()
    waitForCondition(5.seconds) { !fakeEmulator.microphoneState.realAudioEnabled }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testHeadsetAndMicrophoneToggles() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    val dialog = MicrophoneDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val headsetCheckBox = ui.getComponent<JBCheckBox> { it.name == "headsetInsertedCheckBox" }
    val micAttachedCheckBox = ui.getComponent<JBCheckBox> { it.name == "microphoneAttachedCheckBox" }

    // Insert headset plug (with microphone attached = false).
    ui.clickOn(headsetCheckBox)
    assertThat(headsetCheckBox.isSelected).isTrue()
    assertThat(micAttachedCheckBox.isEnabled).isTrue()
    assertThat(micAttachedCheckBox.isSelected).isFalse()

    val inputStreamCall =
      fakeEmulator.getNextGrpcCall(
        2.seconds,
        { it.methodName == "android.emulation.control.EmulatorController/streamInputEvent" },
      )
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 2 value: 1 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    // Attach virtual microphone while headset is inserted.
    ui.clickOn(micAttachedCheckBox)
    assertThat(micAttachedCheckBox.isSelected).isTrue()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 value: 1 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    // Detach virtual microphone while headset is inserted.
    ui.clickOn(micAttachedCheckBox)
    assertThat(micAttachedCheckBox.isSelected).isFalse()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    // Attach virtual microphone again, then unplug headset.
    ui.clickOn(micAttachedCheckBox)
    assertThat(micAttachedCheckBox.isSelected).isTrue()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 value: 1 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    ui.clickOn(headsetCheckBox)
    assertThat(headsetCheckBox.isSelected).isFalse()
    assertThat(micAttachedCheckBox.isEnabled).isFalse()
    assertThat(micAttachedCheckBox.isSelected).isTrue()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 2 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    // Plug headset back in while micAttachedCheckBox is already selected.
    ui.clickOn(headsetCheckBox)
    assertThat(headsetCheckBox.isSelected).isTrue()
    assertThat(micAttachedCheckBox.isEnabled).isTrue()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 2 value: 1 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { type: 5 code: 4 value: 1 }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("android_event { }")

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()

    // Reopening the dialog preserves the headset and microphone checkbox state.
    val reopenedDialog = MicrophoneDialog(emulatorController).show()
    val reopenedUi = FakeUi(reopenedDialog.rootPane)
    val reopenedHeadsetCheckBox = reopenedUi.getComponent<JBCheckBox> { it.name == "headsetInsertedCheckBox" }
    val reopenedMicAttachedCheckBox = reopenedUi.getComponent<JBCheckBox> { it.name == "microphoneAttachedCheckBox" }
    assertThat(reopenedHeadsetCheckBox.isSelected).isTrue()
    assertThat(reopenedMicAttachedCheckBox.isEnabled).isTrue()
    assertThat(reopenedMicAttachedCheckBox.isSelected).isTrue()

    reopenedUi.clickOn(reopenedUi.getComponent<JButton> { it.text == "Close" })
    assertThat(reopenedDialog.isShowing).isFalse()
  }

  @Test
  fun testVoiceAssistPressAndRelease() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    val dialog = MicrophoneDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val voiceAssistButton = ui.getComponent<JButton> { it.name == "voiceAssistButton" }
    val buttonPos = ui.getPosition(voiceAssistButton)
    val clickX = buttonPos.x + voiceAssistButton.width / 2
    val clickY = buttonPos.y + voiceAssistButton.height / 2

    ui.mouse.press(clickX, clickY)
    val inputStreamCall =
      fakeEmulator.getNextGrpcCall(
        2.seconds,
        { it.methodName == "android.emulation.control.EmulatorController/streamInputEvent" },
      )
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { codeType: Evdev keyCode: 585 }")

    ui.mouse.release()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds)))
      .isEqualTo("key_event { codeType: Evdev eventType: keyup keyCode: 585 }")

    // Closing the dialog while the button is pressed sends a keyup event.
    ui.mouse.press(clickX, clickY)
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { codeType: Evdev keyCode: 585 }")

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds)))
      .isEqualTo("key_event { codeType: Evdev eventType: keyup keyCode: 585 }")
  }

  @Test
  fun testVoiceAssistHiddenOnAutomotiveAndApiBelow24() {
    val (_, automotiveController) = createAndStartEmulator { FakeEmulator.createAutomotiveAvd(it) }
    val autoDialog = MicrophoneDialog(automotiveController).show()
    val autoUi = FakeUi(autoDialog.rootPane)
    assertThat(autoUi.findComponent<JButton> { it.name == "voiceAssistButton" }).isNull()
    autoUi.clickOn(autoUi.getComponent<JButton> { it.text == "Close" })
    assertThat(autoDialog.isShowing).isFalse()

    val (_, api23Controller) = createAndStartEmulator { FakeEmulator.createPhoneAvd(it, androidVersion = AndroidVersion(23, 0)) }
    val api23Dialog = MicrophoneDialog(api23Controller).show()
    val api23Ui = FakeUi(api23Dialog.rootPane)
    assertThat(api23Ui.findComponent<JButton> { it.name == "voiceAssistButton" }).isNull()
    api23Ui.clickOn(api23Ui.getComponent<JButton> { it.text == "Close" })
    assertThat(api23Dialog.isShowing).isFalse()
  }
}
