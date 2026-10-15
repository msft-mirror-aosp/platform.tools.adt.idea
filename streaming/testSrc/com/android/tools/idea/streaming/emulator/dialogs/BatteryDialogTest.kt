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
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
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
    val dialog = BatteryDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
      val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
      val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
      val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
      val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

      assertThat(chargeLevelSlider.value).isEqualTo(100)
      assertThat(chargeLevelField.text).isEqualTo("100%")
      assertThat(chargerBox.selectedItem?.toString()).isEqualTo("None")
      assertThat(healthBox.selectedItem?.toString()).isEqualTo("Good")
      assertThat(statusBox.selectedItem?.toString()).isEqualTo("Charging")

      assertThat(healthBox.width).isEqualTo(chargerBox.width)
      assertThat(statusBox.width).isEqualTo(chargerBox.width)

      ui.clickOn(rootPane.defaultButton)
    }
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

    val dialog = BatteryDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
      val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
      val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
      val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
      val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

      waitForCondition(5.seconds) {
        chargeLevelSlider.value == 42 &&
          chargeLevelField.text == "42%" &&
          chargerBox.selectedItem?.toString() == "AC charger" &&
          healthBox.selectedItem?.toString() == "Overheated" &&
          statusBox.selectedItem?.toString() == "Discharging"
      }

      ui.clickOn(rootPane.defaultButton)
    }
  }

  @Test
  fun testDialogUiInteraction() {
    val dialog = BatteryDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val chargeLevelSlider = ui.getComponent<JSlider> { it.name == "chargeLevelSlider" }
      val chargeLevelField = ui.getComponent<JTextField> { it.name == "chargeLevelField" }
      val chargerBox = ui.getComponent<ComboBox<*>> { it.name == "chargerComboBox" }
      val healthBox = ui.getComponent<ComboBox<*>> { it.name == "healthComboBox" }
      val statusBox = ui.getComponent<ComboBox<*>> { it.name == "statusComboBox" }

      assertThat(chargeLevelSlider.value).isEqualTo(100)
      assertThat(chargeLevelField.text).isEqualTo("100%")
      assertThat(chargerBox.selectedItem?.toString()).isEqualTo("None")
      assertThat(healthBox.selectedItem?.toString()).isEqualTo("Good")
      assertThat(statusBox.selectedItem?.toString()).isEqualTo("Charging")

      chargeLevelSlider.value = 75
      assertThat(chargeLevelField.text).isEqualTo("75%")
      chargerBox.selectItem("AC charger")
      healthBox.selectItem("Overvoltage")
      statusBox.selectItem("Not charging")

      waitForCondition(5.seconds) {
        fakeEmulator.batteryStatus.hasBattery &&
          fakeEmulator.batteryStatus.isPresent &&
          fakeEmulator.batteryStatus.chargeLevel == 75 &&
          fakeEmulator.batteryStatus.charger == BatteryCharger.AC &&
          fakeEmulator.batteryStatus.health == BatteryHealth.OVERVOLTAGE &&
          fakeEmulator.batteryStatus.status == BatteryStatus.NOT_CHARGING
      }

      chargeLevelSlider.value = 15
      chargerBox.selectItem("None")
      healthBox.selectItem("Unknown")
      statusBox.selectItem("Discharging")

      val closeButton = rootPane.defaultButton
      assertThat(closeButton.text).isEqualTo("Close")
      ui.clickOn(closeButton)
    }

    waitForCondition(5.seconds) {
      fakeEmulator.batteryStatus.chargeLevel == 15 &&
        fakeEmulator.batteryStatus.charger == BatteryCharger.NONE &&
        fakeEmulator.batteryStatus.healthValue == 5 &&
        fakeEmulator.batteryStatus.status == BatteryStatus.DISCHARGING
    }
  }

  private fun ComboBox<*>.selectItem(text: String) {
    selectedItem = (0 until itemCount).map { getItemAt(it) }.first { it.toString() == text }
  }
}
