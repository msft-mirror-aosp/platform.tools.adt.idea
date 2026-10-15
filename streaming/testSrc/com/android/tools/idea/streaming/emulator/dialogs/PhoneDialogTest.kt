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

import com.android.emulation.control.incubating.Call
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBTextArea
import java.nio.file.Path
import javax.swing.JButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [PhoneDialog]. */
@RunsInEdt
class PhoneDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      EdtRule(),
      PortableUiFontRule(),
      HeadlessDialogRule(),
    )

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().unsetValue(PhoneDialog.RECENT_NUMBERS_PROPERTY)
    createAndStartEmulator { FakeEmulator.createPhoneAvd(it) }
  }

  @Test
  fun testInitialState() {
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val holdCallButton = ui.getComponent<JButton> { it.name == "holdCallButton" }
      val callButton = ui.getComponent<JButton> { it.name == "callButton" }
      val smsMessageArea = ui.getComponent<JBTextArea> { it.name == "smsMessageArea" }
      val sendSmsButton = ui.getComponent<JButton> { it.name == "sendSmsButton" }

      assertThat(numberBox.isEnabled).isTrue()
      assertThat(numberBox.isEditable).isTrue()
      assertThat(numberBox.selectedItem).isEqualTo("(650) 555-1212")
      assertThat(holdCallButton.isEnabled).isFalse()
      assertThat(holdCallButton.text).isEqualTo("Hold Call")
      assertThat(callButton.isEnabled).isTrue()
      assertThat(callButton.text).isEqualTo("Call Device")
      assertThat(smsMessageArea.text).isEqualTo("Android is always a sweet treat!")
      assertThat(smsMessageArea.isEditable).isTrue()
      assertThat(sendSmsButton.isEnabled).isTrue()
      assertThat(sendSmsButton.text).isEqualTo("Send Message")
      assertThat(callButton.width).isGreaterThan(0)
      assertThat(callButton.width).isEqualTo(holdCallButton.width)
      assertThat(sendSmsButton.width).isEqualTo(callButton.width)
      assertThat(numberBox.width).isGreaterThan(callButton.width)
      assertThat(smsMessageArea.width).isGreaterThan(callButton.width)

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testInitialStateWithExistingActiveCall() {
    fakeEmulator.activeCalls =
      listOf(
        Call.newBuilder()
          .setNumber("6505559999")
          .setDirection(Call.CallDirection.CALL_DIRECTION_INBOUND)
          .setState(Call.CallState.CALL_STATE_HELD)
          .build()
      )

    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val holdCallButton = ui.getComponent<JButton> { it.name == "holdCallButton" }
      val callButton = ui.getComponent<JButton> { it.name == "callButton" }

      waitForCondition(5.seconds) {
        !numberBox.isEnabled && holdCallButton.isEnabled && holdCallButton.text == "Resume Call" && callButton.text == "End Call"
      }

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testCallHoldResumeAndEnd() {
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val holdCallButton = ui.getComponent<JButton> { it.name == "holdCallButton" }
      val callButton = ui.getComponent<JButton> { it.name == "callButton" }

      numberBox.editor.item = "+1 (650) 555-6789"
      ui.clickOn(callButton)

      waitForCondition(5.seconds) {
        fakeEmulator.activeCalls.size == 1 &&
          fakeEmulator.activeCalls[0].number == "+16505556789" &&
          !numberBox.isEnabled &&
          holdCallButton.isEnabled &&
          holdCallButton.text == "Hold Call" &&
          callButton.text == "End Call"
      }

      // Hold the call.
      ui.clickOn(holdCallButton)

      waitForCondition(5.seconds) {
        fakeEmulator.activeCalls.singleOrNull()?.state == Call.CallState.CALL_STATE_HELD && holdCallButton.text == "Resume Call"
      }

      // Resume the call.
      ui.clickOn(holdCallButton)

      waitForCondition(5.seconds) {
        fakeEmulator.activeCalls.singleOrNull()?.state == Call.CallState.CALL_STATE_ACTIVE && holdCallButton.text == "Hold Call"
      }

      // End the call.
      ui.clickOn(callButton)

      waitForCondition(5.seconds) {
        fakeEmulator.activeCalls.isEmpty() &&
          numberBox.isEnabled &&
          !holdCallButton.isEnabled &&
          holdCallButton.text == "Hold Call" &&
          callButton.text == "Call Device"
      }

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testRemoteHangupUpdatesUi() {
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val holdCallButton = ui.getComponent<JButton> { it.name == "holdCallButton" }
      val callButton = ui.getComponent<JButton> { it.name == "callButton" }

      ui.clickOn(callButton)

      waitForCondition(5.seconds) {
        fakeEmulator.activeCalls.size == 1 && callButton.text == "End Call" && holdCallButton.isEnabled
      }

      fakeEmulator.endAllCallsOnDevice()

      waitForCondition(5.seconds) {
        numberBox.isEnabled && !holdCallButton.isEnabled && callButton.text == "Call Device"
      }

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testSendSms() {
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val smsMessageArea = ui.getComponent<JBTextArea> { it.name == "smsMessageArea" }
      val sendSmsButton = ui.getComponent<JButton> { it.name == "sendSmsButton" }

      numberBox.editor.item = "(650) 555-1212"
      smsMessageArea.text = "Hello from Android Studio!"
      ui.clickOn(sendSmsButton)

      waitForCondition(5.seconds) { fakeEmulator.receivedSms.size == 1 }
      assertThat(fakeEmulator.receivedSms[0].number).isEqualTo("6505551212")
      assertThat(fakeEmulator.receivedSms[0].text).isEqualTo("Hello from Android Studio!")

      // Alphanumeric sender ID.
      numberBox.editor.item = "Android"
      smsMessageArea.text = "Verification code: 1234"
      ui.clickOn(sendSmsButton)

      waitForCondition(5.seconds) { fakeEmulator.receivedSms.size == 2 }
      assertThat(fakeEmulator.receivedSms[1].number).isEqualTo("Android")
      assertThat(fakeEmulator.receivedSms[1].text).isEqualTo("Verification code: 1234")

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testValidationErrors() {
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<*>> { it.name == "numberComboBox" }
      val callButton = ui.getComponent<JButton> { it.name == "callButton" }
      val smsMessageArea = ui.getComponent<JBTextArea> { it.name == "smsMessageArea" }
      val sendSmsButton = ui.getComponent<JButton> { it.name == "sendSmsButton" }

      assertThat(callButton.isEnabled).isTrue()
      assertThat(sendSmsButton.isEnabled).isTrue()

      // Invalid phone number disables both Call Device and Send Message buttons.
      numberBox.editor.item = "123+456$%^"
      assertThat(callButton.isEnabled).isFalse()
      assertThat(sendSmsButton.isEnabled).isFalse()

      // Fix phone number and test empty SMS message disables Send Message button.
      numberBox.editor.item = "(650) 555-1212"
      assertThat(callButton.isEnabled).isTrue()
      assertThat(sendSmsButton.isEnabled).isTrue()

      smsMessageArea.text = ""
      assertThat(callButton.isEnabled).isTrue()
      assertThat(sendSmsButton.isEnabled).isFalse()

      // Message exceeding MAX_SMS_MSG_SIZE disables Send Message button.
      smsMessageArea.text = "a".repeat(PhoneDialog.MAX_SMS_MSG_SIZE + 1)
      assertThat(callButton.isEnabled).isTrue()
      assertThat(sendSmsButton.isEnabled).isFalse()

      // Valid message re-enables Send Message button.
      smsMessageArea.text = "a".repeat(PhoneDialog.MAX_SMS_MSG_SIZE)
      assertThat(sendSmsButton.isEnabled).isTrue()

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testAutomotiveDisablesSms() {
    createAndStartEmulator { FakeEmulator.createAutomotiveAvd(it) }
    val dialog = PhoneDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val smsMessageArea = ui.getComponent<JBTextArea> { it.name == "smsMessageArea" }
      val sendSmsButton = ui.getComponent<JButton> { it.name == "sendSmsButton" }

      assertThat(smsMessageArea.isEditable).isFalse()
      assertThat(smsMessageArea.isEnabled).isFalse()
      assertThat(sendSmsButton.isEnabled).isFalse()

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testPhoneNumberValidationAndCleaning() {
    assertThat(PhoneDialog.isValidPhoneNumber("(650) 555-1212")).isTrue()
    assertThat(PhoneDialog.isValidPhoneNumber("+1 650 555-6789")).isTrue()
    assertThat(PhoneDialog.isValidPhoneNumber("1234567890123456")).isTrue()
    assertThat(PhoneDialog.isValidPhoneNumber("12345678901234567")).isFalse() // > 16 digits
    assertThat(PhoneDialog.isValidPhoneNumber("Android")).isTrue()
    assertThat(PhoneDialog.isValidPhoneNumber("TooLongAlpha")).isFalse() // > 11 chars
    assertThat(PhoneDialog.isValidPhoneNumber("")).isFalse()
    assertThat(PhoneDialog.isValidPhoneNumber("123$456")).isFalse()

    assertThat(PhoneDialog.cleanPhoneNumber("(650) 555-1212")).isEqualTo("6505551212")
    assertThat(PhoneDialog.cleanPhoneNumber("+1 650 555-6789")).isEqualTo("+16505556789")
    assertThat(PhoneDialog.cleanPhoneNumber("Android")).isEqualTo("Android")
  }

  @Test
  fun testRecentPhoneNumbers() {
    val dialogWrapper1 = PhoneDialog(emulatorController).createWrapper()
    createModalDialogAndInteractWithIt(dialogWrapper1::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<String>> { it.name == "numberComboBox" }
      val sendSmsButton = ui.getComponent<JButton> { it.name == "sendSmsButton" }

      fun items(): List<String> = List(numberBox.itemCount) { numberBox.getItemAt(it) }

      assertThat(items()).containsExactly("(650) 555-1212", "+1 650 555-6789").inOrder()

      // Selecting the second number and sending a message moves it to the top.
      numberBox.selectedItem = "+1 650 555-6789"
      ui.clickOn(sendSmsButton)
      assertThat(items()).containsExactly("+1 650 555-6789", "(650) 555-1212").inOrder()
      assertThat(numberBox.selectedItem).isEqualTo("+1 650 555-6789")

      // Using 7 new numbers keeps only the 8 most recently used numbers in decreasing recency order.
      for (i in 1..7) {
        numberBox.editor.item = "800-555-000$i"
        ui.clickOn(sendSmsButton)
      }

      val expectedNumbers =
        listOf(
          "800-555-0007",
          "800-555-0006",
          "800-555-0005",
          "800-555-0004",
          "800-555-0003",
          "800-555-0002",
          "800-555-0001",
          "+1 650 555-6789",
        )
      assertThat(items()).containsExactlyElementsIn(expectedNumbers).inOrder()

      // Re-using an existing number moves it to the top without changing the size.
      numberBox.editor.item = "800-555-0003"
      ui.clickOn(sendSmsButton)

      val expectedAfterReuse =
        listOf(
          "800-555-0003",
          "800-555-0007",
          "800-555-0006",
          "800-555-0005",
          "800-555-0004",
          "800-555-0002",
          "800-555-0001",
          "+1 650 555-6789",
        )
      assertThat(items()).containsExactlyElementsIn(expectedAfterReuse).inOrder()

      ui.clickOn(rootPane.defaultButton)
    }

    // Reopening the dialog preserves the 8 recently used numbers.
    val dialogWrapper2 = PhoneDialog(emulatorController).createWrapper()
    createModalDialogAndInteractWithIt(dialogWrapper2::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val numberBox = ui.getComponent<ComboBox<String>> { it.name == "numberComboBox" }
      val items = List(numberBox.itemCount) { numberBox.getItemAt(it) }

      assertThat(items)
        .containsExactly(
          "800-555-0003",
          "800-555-0007",
          "800-555-0006",
          "800-555-0005",
          "800-555-0004",
          "800-555-0002",
          "800-555-0001",
          "+1 650 555-6789",
        )
        .inOrder()
      assertThat(numberBox.selectedItem).isEqualTo("800-555-0003")

      ui.clickOn(rootPane.defaultButton)
    }
  }

  private fun createAndStartEmulator(avdCreator: (Path) -> Path) {
    val testAvdFolder = avdCreator(emulatorRule.avdRoot)
    fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  }
}
