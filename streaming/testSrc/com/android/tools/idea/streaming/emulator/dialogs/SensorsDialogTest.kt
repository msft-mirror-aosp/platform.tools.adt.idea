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

import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [SensorsDialog]. */
@RunsInEdt
class SensorsDialogTest {

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
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    fakeEmulator.pauseGrpc()
    val dialog = SensorsDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val temperatureSlider = ui.getComponent<JSlider> { it.name == "temperatureSlider" }
    val temperatureField = ui.getComponent<JTextField> { it.name == "temperatureField" }
    val proximitySlider = ui.getComponent<JSlider> { it.name == "proximitySlider" }
    val proximityField = ui.getComponent<JTextField> { it.name == "proximityField" }
    val pressureSlider = ui.getComponent<JSlider> { it.name == "pressureSlider" }
    val pressureField = ui.getComponent<JTextField> { it.name == "pressureField" }

    val magNorthField = ui.getComponent<JTextField> { it.name == "magNorthField" }
    val magEastField = ui.getComponent<JTextField> { it.name == "magEastField" }
    val magVerticalField = ui.getComponent<JTextField> { it.name == "magVerticalField" }
    val lightSlider = ui.getComponent<JSlider> { it.name == "lightSlider" }
    val lightField = ui.getComponent<JTextField> { it.name == "lightField" }
    val humiditySlider = ui.getComponent<JSlider> { it.name == "humiditySlider" }
    val humidityField = ui.getComponent<JTextField> { it.name == "humidityField" }

    assertThat(ui.findComponent<JSlider> { it.name == "heartRateSlider" }).isNull()
    assertThat(ui.findComponent<JTextField> { it.name == "heartRateField" }).isNull()

    // Sliders and text fields are disabled before receiving initial values from the emulator
    assertThat(temperatureSlider.isEnabled).isFalse()
    assertThat(temperatureField.isEnabled).isFalse()
    assertThat(temperatureField.text).isEmpty()
    assertThat(proximitySlider.isEnabled).isFalse()
    assertThat(proximityField.isEnabled).isFalse()
    assertThat(proximityField.text).isEmpty()
    assertThat(pressureSlider.isEnabled).isFalse()
    assertThat(pressureField.isEnabled).isFalse()
    assertThat(pressureField.text).isEmpty()
    assertThat(magNorthField.isEnabled).isFalse()
    assertThat(magNorthField.text).isEmpty()
    assertThat(magEastField.isEnabled).isFalse()
    assertThat(magEastField.text).isEmpty()
    assertThat(magVerticalField.isEnabled).isFalse()
    assertThat(magVerticalField.text).isEmpty()
    assertThat(lightSlider.isEnabled).isFalse()
    assertThat(lightField.isEnabled).isFalse()
    assertThat(lightField.text).isEmpty()
    assertThat(humiditySlider.isEnabled).isFalse()
    assertThat(humidityField.isEnabled).isFalse()
    assertThat(humidityField.text).isEmpty()

    fakeEmulator.resumeGrpc()
    waitForCondition(5.seconds) {
      temperatureSlider.isEnabled &&
        temperatureField.isEnabled &&
        proximitySlider.isEnabled &&
        proximityField.isEnabled &&
        pressureSlider.isEnabled &&
        pressureField.isEnabled &&
        magNorthField.isEnabled &&
        magEastField.isEnabled &&
        magVerticalField.isEnabled &&
        lightSlider.isEnabled &&
        lightField.isEnabled &&
        humiditySlider.isEnabled &&
        humidityField.isEnabled
    }

    assertThat(temperatureSlider.minimum).isEqualTo(-2731)
    assertThat(temperatureSlider.maximum).isEqualTo(1000)
    assertThat(temperatureSlider.value).isEqualTo(0)
    assertThat(temperatureField.text).isEqualTo("0.0")

    assertThat(proximitySlider.minimum).isEqualTo(0)
    assertThat(proximitySlider.maximum).isEqualTo(100)
    assertThat(proximitySlider.value).isEqualTo(10)
    assertThat(proximityField.text).isEqualTo("1.0")

    assertThat(pressureSlider.minimum).isEqualTo(0)
    assertThat(pressureSlider.maximum).isEqualTo(11000)
    assertThat(pressureSlider.value).isEqualTo(0)
    assertThat(pressureField.text).isEqualTo("0.0")

    assertThat(magNorthField.text).isEqualTo("0.0")
    assertThat(magEastField.text).isEqualTo("5.9")
    assertThat(magVerticalField.text).isEqualTo("-48.4")

    assertThat(lightSlider.minimum).isEqualTo(0)
    assertThat(lightSlider.maximum).isEqualTo(40000)
    assertThat(lightSlider.value).isEqualTo(0)
    assertThat(lightField.text).isEqualTo("0.0")

    assertThat(humiditySlider.minimum).isEqualTo(0)
    assertThat(humiditySlider.maximum).isEqualTo(1000)
    assertThat(humiditySlider.value).isEqualTo(0)
    assertThat(humidityField.text).isEqualTo("0.0")

    // Verify two-column layout positioning
    val tempSliderPos = SwingUtilities.convertPoint(temperatureSlider.parent, temperatureSlider.location, rootPane)
    val proxSliderPos = SwingUtilities.convertPoint(proximitySlider.parent, proximitySlider.location, rootPane)
    val pressSliderPos = SwingUtilities.convertPoint(pressureSlider.parent, pressureSlider.location, rootPane)
    val lightSliderPos = SwingUtilities.convertPoint(lightSlider.parent, lightSlider.location, rootPane)
    val humSliderPos = SwingUtilities.convertPoint(humiditySlider.parent, humiditySlider.location, rootPane)
    assertThat(tempSliderPos.y).isLessThan(proxSliderPos.y)
    assertThat(proxSliderPos.y).isLessThan(pressSliderPos.y)
    assertThat(lightSliderPos.y).isLessThan(humSliderPos.y)
    assertThat(tempSliderPos.x).isLessThan(lightSliderPos.x)

    // Verify all sliders have the same horizontal size
    val sliderWidth = temperatureSlider.width
    assertThat(proximitySlider.width).isEqualTo(sliderWidth)
    assertThat(pressureSlider.width).isEqualTo(sliderWidth)
    assertThat(lightSlider.width).isEqualTo(sliderWidth)
    assertThat(humiditySlider.width).isEqualTo(sliderWidth)

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulator() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    fakeEmulator.ambientTemperature = 22.5f
    fakeEmulator.magneticField = listOf(12.3f, -45.6f, 78.9f)
    fakeEmulator.proximity = 4.5f
    fakeEmulator.light = 12500.0f
    fakeEmulator.pressure = 1013.2f
    fakeEmulator.humidity = 65.4f

    val dialog = SensorsDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val temperatureSlider = ui.getComponent<JSlider> { it.name == "temperatureSlider" }
    val temperatureField = ui.getComponent<JTextField> { it.name == "temperatureField" }
    val proximitySlider = ui.getComponent<JSlider> { it.name == "proximitySlider" }
    val proximityField = ui.getComponent<JTextField> { it.name == "proximityField" }
    val pressureSlider = ui.getComponent<JSlider> { it.name == "pressureSlider" }
    val pressureField = ui.getComponent<JTextField> { it.name == "pressureField" }
    val magNorthField = ui.getComponent<JTextField> { it.name == "magNorthField" }
    val magEastField = ui.getComponent<JTextField> { it.name == "magEastField" }
    val magVerticalField = ui.getComponent<JTextField> { it.name == "magVerticalField" }
    val lightSlider = ui.getComponent<JSlider> { it.name == "lightSlider" }
    val lightField = ui.getComponent<JTextField> { it.name == "lightField" }
    val humiditySlider = ui.getComponent<JSlider> { it.name == "humiditySlider" }
    val humidityField = ui.getComponent<JTextField> { it.name == "humidityField" }

    waitForCondition(5.seconds) {
      temperatureSlider.value == 225 &&
        proximitySlider.value == 45 &&
        pressureSlider.value == 10132 &&
        lightSlider.value == 12500 &&
        humiditySlider.value == 654 &&
        magNorthField.text == "12.3" &&
        magEastField.text == "-45.6" &&
        magVerticalField.text == "78.9"
    }
    assertThat(temperatureField.text).isEqualTo("22.5")
    assertThat(proximityField.text).isEqualTo("4.5")
    assertThat(pressureField.text).isEqualTo("1013.2")
    assertThat(lightField.text).isEqualTo("12500.0")
    assertThat(humidityField.text).isEqualTo("65.4")

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testSlidersAndTextFields() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    val dialog = SensorsDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val temperatureSlider = ui.getComponent<JSlider> { it.name == "temperatureSlider" }
    val temperatureField = ui.getComponent<JTextField> { it.name == "temperatureField" }
    val proximitySlider = ui.getComponent<JSlider> { it.name == "proximitySlider" }
    val proximityField = ui.getComponent<JTextField> { it.name == "proximityField" }
    val pressureSlider = ui.getComponent<JSlider> { it.name == "pressureSlider" }
    val pressureField = ui.getComponent<JTextField> { it.name == "pressureField" }
    val magNorthField = ui.getComponent<JTextField> { it.name == "magNorthField" }
    val magEastField = ui.getComponent<JTextField> { it.name == "magEastField" }
    val magVerticalField = ui.getComponent<JTextField> { it.name == "magVerticalField" }
    val lightSlider = ui.getComponent<JSlider> { it.name == "lightSlider" }
    val lightField = ui.getComponent<JTextField> { it.name == "lightField" }
    val humiditySlider = ui.getComponent<JSlider> { it.name == "humiditySlider" }
    val humidityField = ui.getComponent<JTextField> { it.name == "humidityField" }

    waitForCondition(5.seconds) {
      temperatureSlider.isEnabled &&
        proximitySlider.isEnabled &&
        pressureSlider.isEnabled &&
        magNorthField.isEnabled &&
        lightSlider.isEnabled &&
        humiditySlider.isEnabled
    }

    // Adjust sliders
    temperatureSlider.value = -155
    assertThat(temperatureField.text).isEqualTo("-15.5")
    waitForCondition(5.seconds) { abs(fakeEmulator.ambientTemperature - (-15.5f)) < 0.05f }

    proximitySlider.value = 75
    assertThat(proximityField.text).isEqualTo("7.5")
    waitForCondition(5.seconds) { abs(fakeEmulator.proximity - 7.5f) < 0.05f }

    pressureSlider.value = 9805
    assertThat(pressureField.text).isEqualTo("980.5")
    waitForCondition(5.seconds) { abs(fakeEmulator.pressure - 980.5f) < 0.05f }

    lightSlider.value = 25000
    assertThat(lightField.text).isEqualTo("25000.0")
    waitForCondition(5.seconds) { abs(fakeEmulator.light - 25000.0f) < 0.5f }

    humiditySlider.value = 420
    assertThat(humidityField.text).isEqualTo("42.0")
    waitForCondition(5.seconds) { abs(fakeEmulator.humidity - 42.0f) < 0.05f }

    // Adjust sliders using arrow keys
    ui.keyboard.setFocus(temperatureSlider)
    ui.keyboard.pressAndRelease(KeyEvent.VK_RIGHT)
    assertThat(temperatureField.text).isEqualTo("-10.5")
    waitForCondition(5.seconds) { abs(fakeEmulator.ambientTemperature - (-10.5f)) < 0.05f }

    ui.keyboard.setFocus(lightSlider)
    ui.keyboard.pressAndRelease(KeyEvent.VK_LEFT)
    assertThat(lightField.text).isEqualTo("24600.0")
    waitForCondition(5.seconds) { abs(fakeEmulator.light - 24600.0f) < 0.5f }

    // Edit text fields directly (in-range values update slider and emulator immediately)
    temperatureField.text = "36.6"
    assertThat(temperatureSlider.value).isEqualTo(366)
    waitForCondition(5.seconds) { abs(fakeEmulator.ambientTemperature - 36.6f) < 0.05f }

    // Committing a multi-decimal value rounds both display text and underlying sensor state
    temperatureField.text = "22.56"
    temperatureField.postActionEvent()
    assertThat(temperatureField.text).isEqualTo("22.6")
    assertThat(temperatureSlider.value).isEqualTo(226)
    waitForCondition(5.seconds) { abs(fakeEmulator.ambientTemperature - 22.6f) < 0.001f }

    lightField.text = "1500.5"
    assertThat(lightSlider.value).isEqualTo(1501)
    waitForCondition(5.seconds) { abs(fakeEmulator.light - 1500.5f) < 0.05f }

    // Out-of-range value clamps on Enter / focus lost
    temperatureField.text = "500.0"
    temperatureField.postActionEvent()
    assertThat(temperatureField.text).isEqualTo("100.0")
    assertThat(temperatureSlider.value).isEqualTo(1000)
    waitForCondition(5.seconds) { abs(fakeEmulator.ambientTemperature - 100.0f) < 0.05f }

    // Invalid non-numeric text reverts to formatted current value on focus lost
    temperatureField.text = "abc"
    for (listener in temperatureField.focusListeners) {
      listener.focusLost(FocusEvent(temperatureField, FocusEvent.FOCUS_LOST))
    }
    assertThat(temperatureField.text).isEqualTo("100.0")

    // NaN and Infinity revert to formatted current value on commit
    temperatureField.text = "NaN"
    temperatureField.postActionEvent()
    assertThat(temperatureField.text).isEqualTo("100.0")
    temperatureField.text = "Infinity"
    temperatureField.postActionEvent()
    assertThat(temperatureField.text).isEqualTo("100.0")

    // Edit magnetic field text fields
    magNorthField.text = "25.0"
    magEastField.text = "-10.5"
    magVerticalField.text = "40.2"
    waitForCondition(5.seconds) {
      abs(fakeEmulator.magneticField[0] - 25.0f) < 0.05f &&
        abs(fakeEmulator.magneticField[1] - (-10.5f)) < 0.05f &&
        abs(fakeEmulator.magneticField[2] - 40.2f) < 0.05f
    }

    // Out-of-range magnetic field clamps on commit
    magNorthField.text = "-2000"
    magNorthField.postActionEvent()
    assertThat(magNorthField.text).isEqualTo("-1000.0")
    waitForCondition(5.seconds) { abs(fakeEmulator.magneticField[0] - (-1000.0f)) < 0.05f }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testWearDeviceHeartRateSensor() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator { FakeEmulator.createWatchAvd(it) }
    fakeEmulator.heartRate = 72.0f

    val dialog = SensorsDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val temperatureSlider = ui.getComponent<JSlider> { it.name == "temperatureSlider" }
    val temperatureField = ui.getComponent<JTextField> { it.name == "temperatureField" }
    val heartRateSlider = ui.getComponent<JSlider> { it.name == "heartRateSlider" }
    val heartRateField = ui.getComponent<JTextField> { it.name == "heartRateField" }
    assertThat(heartRateSlider.minimum).isEqualTo(0)
    assertThat(heartRateSlider.maximum).isEqualTo(5000)
    assertThat(heartRateSlider.width).isEqualTo(temperatureSlider.width)
    val tempFieldPos = SwingUtilities.convertPoint(temperatureField.parent, temperatureField.location, rootPane)
    val heartRateFieldPos = SwingUtilities.convertPoint(heartRateField.parent, heartRateField.location, rootPane)
    assertThat(heartRateFieldPos.x).isEqualTo(tempFieldPos.x)

    waitForCondition(5.seconds) { heartRateSlider.value == 720 }
    assertThat(heartRateField.text).isEqualTo("72.0")

    heartRateSlider.value = 1350
    assertThat(heartRateField.text).isEqualTo("135.0")
    waitForCondition(5.seconds) { abs(fakeEmulator.heartRate - 135.0f) < 0.05f }

    heartRateField.text = "85.5"
    assertThat(heartRateSlider.value).isEqualTo(855)
    waitForCondition(5.seconds) { abs(fakeEmulator.heartRate - 85.5f) < 0.05f }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }
}
