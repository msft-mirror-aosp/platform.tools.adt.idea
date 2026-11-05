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

import com.android.emulator.control.BatteryState
import com.android.emulator.control.BatteryState.BatteryCharger
import com.android.emulator.control.BatteryState.BatteryHealth
import com.android.emulator.control.BatteryState.BatteryStatus
import com.android.emulator.control.EmulatorControllerGrpc
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.dsl.builder.panel
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_KP_DOWN
import java.awt.event.KeyEvent.VK_KP_UP
import java.awt.event.KeyEvent.VK_UP
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.JTextField
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [BatteryDialog]. */
@RunsInEdt
class BatteryDialogTest {

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

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    val testAvdFolder = FakeEmulator.createPhoneAvd(emulatorRule.avdRoot)
    fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    assertThat(emulators).hasSize(1)
    emulatorController = emulators.first()
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  }

  @Test
  fun testInitialStateFromDefaults() {
    fakeEmulator.pauseGrpc()
    val dialog = BatteryDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
    val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
    val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
    val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
    val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

    assertThat(chargeLevelSlider.isEnabled).isFalse()
    assertThat(chargeLevelField.isEnabled).isFalse()
    assertThat(chargeLevelField.text).isEmpty()
    assertThat(chargerBox.isEnabled).isFalse()
    assertThat(healthBox.isEnabled).isFalse()
    assertThat(statusBox.isEnabled).isFalse()

    // Programmatic text or slider changes before initial state arrives must not mark state modified or block initialization.
    chargeLevelField.text = "50"
    chargeLevelSlider.value = 50

    fakeEmulator.resumeGrpc()
    waitForCondition(5.seconds) { chargeLevelSlider.isEnabled }

    assertThat(chargeLevelField.isEnabled).isTrue()
    assertThat(chargerBox.isEnabled).isTrue()
    assertThat(healthBox.isEnabled).isTrue()
    assertThat(statusBox.isEnabled).isTrue()
    assertThat(chargeLevelSlider.value).isEqualTo(100)
    assertThat(chargeLevelField.text).isEqualTo("100")
    assertThat(ui.findComponent<JLabel> { it.text == "%" }).isNotNull()
    assertThat(chargerBox.selectedItem?.toString()).isEqualTo("None")
    assertThat(healthBox.selectedItem?.toString()).isEqualTo("Good")
    assertThat(statusBox.selectedItem?.toString()).isEqualTo("Not charging")

    assertThat(healthBox.width).isEqualTo(chargerBox.width)
    assertThat(statusBox.width).isEqualTo(chargerBox.width)
    assertThat(healthBox.y).isEqualTo(statusBox.y)

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulator() {
    fakeEmulator.batteryStatus =
      BatteryState.newBuilder()
        .setHasBattery(true)
        .setIsPresent(true)
        .setChargeLevel(42)
        .setCharger(BatteryCharger.AC)
        .setHealth(BatteryHealth.OVERHEATED)
        .setStatus(BatteryStatus.DISCHARGING)
        .build()

    val dialog = BatteryDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
    val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
    val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
    val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
    val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

    waitForCondition(5.seconds) {
      chargeLevelSlider.isEnabled &&
        chargeLevelSlider.value == 42 &&
        chargeLevelField.text == "42" &&
        chargerBox.selectedItem?.toString() == "AC charger" &&
        healthBox.selectedItem?.toString() == "Overheated" &&
        statusBox.selectedItem?.toString() == "Discharging"
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testDialogUiInteraction() {
    val dialog = BatteryDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
    val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
    val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
    val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
    val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

    waitForCondition(5.seconds) { chargeLevelSlider.isEnabled }
    assertThat(chargeLevelSlider.value).isEqualTo(100)
    assertThat(chargeLevelField.text).isEqualTo("100")
    assertThat(chargerBox.selectedItem?.toString()).isEqualTo("None")
    assertThat(healthBox.selectedItem?.toString()).isEqualTo("Good")
    assertThat(statusBox.selectedItem?.toString()).isEqualTo("Not charging")

    chargeLevelSlider.value = 75
    assertThat(chargeLevelField.text).isEqualTo("75")
    chargerBox.selectItem("AC charger")
    healthBox.selectItem("Overvoltage")
    statusBox.selectItem("Charging")

    waitForCondition(5.seconds) {
      fakeEmulator.batteryStatus.hasBattery &&
        fakeEmulator.batteryStatus.isPresent &&
        fakeEmulator.batteryStatus.chargeLevel == 75 &&
        fakeEmulator.batteryStatus.charger == BatteryCharger.AC &&
        fakeEmulator.batteryStatus.health == BatteryHealth.OVERVOLTAGE &&
        fakeEmulator.batteryStatus.status == BatteryStatus.CHARGING
    }

    chargeLevelSlider.value = 15
    chargerBox.selectItem("None")
    healthBox.selectItem("Unknown")
    statusBox.selectItem("Discharging")

    waitForCondition(5.seconds) {
      fakeEmulator.batteryStatus.chargeLevel == 15 &&
        fakeEmulator.batteryStatus.charger == BatteryCharger.NONE &&
        fakeEmulator.batteryStatus.healthValue == 5 &&
        fakeEmulator.batteryStatus.status == BatteryStatus.DISCHARGING
    }

    // In-range text updates slider and emulator immediately and normalizes on commit.
    chargeLevelField.text = "05"
    assertThat(chargeLevelField.text).isEqualTo("05")
    assertThat(chargeLevelSlider.value).isEqualTo(5)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 5 }
    chargeLevelField.postActionEvent()
    assertThat(chargeLevelField.text).isEqualTo("5")

    // Out-of-range values clamp on commit.
    chargeLevelField.text = "150"
    chargeLevelField.postActionEvent()
    assertThat(chargeLevelField.text).isEqualTo("100")
    assertThat(chargeLevelSlider.value).isEqualTo(100)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 100 }

    chargeLevelField.text = "-10"
    for (listener in chargeLevelField.focusListeners) {
      listener.focusLost(FocusEvent(chargeLevelField, FocusEvent.FOCUS_LOST))
    }
    assertThat(chargeLevelField.text).isEqualTo("0")
    assertThat(chargeLevelSlider.value).isEqualTo(0)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 0 }

    // Invalid non-numeric text reverts to the current value on focus lost.
    chargeLevelField.text = "abc"
    for (listener in chargeLevelField.focusListeners) {
      listener.focusLost(FocusEvent(chargeLevelField, FocusEvent.FOCUS_LOST))
    }
    assertThat(chargeLevelField.text).isEqualTo("0")

    // Up/Down keys increment/decrement within 0..100 and sync slider and emulator state.
    ui.keyboard.setFocus(chargeLevelField)
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(chargeLevelField.text).isEqualTo("0")
    assertThat(chargeLevelSlider.value).isEqualTo(0)

    ui.keyboard.pressAndRelease(VK_UP)
    ui.keyboard.pressAndRelease(VK_KP_UP)
    assertThat(chargeLevelField.text).isEqualTo("2")
    assertThat(chargeLevelSlider.value).isEqualTo(2)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 2 }

    ui.keyboard.pressAndRelease(VK_KP_DOWN)
    assertThat(chargeLevelField.text).isEqualTo("1")
    assertThat(chargeLevelSlider.value).isEqualTo(1)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 1 }

    chargeLevelField.text = "100"
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(chargeLevelField.text).isEqualTo("100")
    assertThat(chargeLevelSlider.value).isEqualTo(100)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 100 }

    // Out-of-range and extreme Int values clamp to 0..100 before stepping (no overflow/underflow).
    chargeLevelField.text = "150"
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(chargeLevelField.text).isEqualTo("99")
    assertThat(chargeLevelSlider.value).isEqualTo(99)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 99 }

    chargeLevelField.text = "-10"
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(chargeLevelField.text).isEqualTo("1")
    assertThat(chargeLevelSlider.value).isEqualTo(1)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 1 }

    chargeLevelField.text = Int.MAX_VALUE.toString()
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(chargeLevelField.text).isEqualTo("100")
    assertThat(chargeLevelSlider.value).isEqualTo(100)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 100 }

    chargeLevelField.text = Int.MIN_VALUE.toString()
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(chargeLevelField.text).isEqualTo("0")
    assertThat(chargeLevelSlider.value).isEqualTo(0)
    waitForCondition(5.seconds) { fakeEmulator.batteryStatus.chargeLevel == 0 }

    val closeButton = ui.getComponent<JButton> { it.text == "Close" }
    assertThat(rootPane.defaultButton).isNull()
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateLoadFailure() {
    fakeEmulator.failGrpcCall(EmulatorControllerGrpc.getGetBatteryMethod(), Status.INTERNAL.withDescription("Battery unavailable"))
    val dialog = BatteryDialog(emulatorController).show()
    val ui = FakeUi(dialog.rootPane)

    val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
    val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
    val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }

    waitForCondition(5.seconds) { hasValidationError(ui, "Battery unavailable") }
    assertThat(chargeLevelSlider.isEnabled).isFalse()
    assertThat(chargeLevelField.isEnabled).isFalse()
    assertThat(chargeLevelField.text).isEmpty()
    assertThat(chargerBox.isEnabled).isFalse()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testShowErrorWithBlankThrowableMessage() {
    val testDialog =
      object : AbstractExtendedControlsDialog(emulatorController, "Test") {
        override fun createPanel(): DialogPanel = panel { row { label("Content") } }

        fun report(throwable: Throwable) = showError(throwable)
      }
    val wrapper = testDialog.show()
    val ui = FakeUi(wrapper.rootPane)

    testDialog.report(IllegalStateException(""))
    assertThat(hasValidationError(ui, "IllegalStateException")).isTrue()

    testDialog.report(object : RuntimeException("   ") {})
    assertThat(hasValidationError(ui, "BatteryDialogTest")).isTrue()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(wrapper.isShowing).isFalse()
  }

  private fun ComboBox<*>.selectItem(text: String) {
    selectedItem = (0 until itemCount).map { getItemAt(it) }.first { it.toString() == text }
  }
}
