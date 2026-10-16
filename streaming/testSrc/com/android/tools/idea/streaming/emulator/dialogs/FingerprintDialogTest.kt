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
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import javax.swing.JButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [FingerprintDialog]. */
@RunsInEdt
class FingerprintDialogTest {

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
  fun testInitialState() {
    val dialog = FingerprintDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val fingerBox = ui.getComponent<ComboBox<*>> { it.name == "fingerComboBox" }
    val touchButton = ui.getComponent<JButton> { it.name == "touchSensorButton" }

    assertThat(fingerBox.itemCount).isEqualTo(10)
    assertThat((0 until fingerBox.itemCount).map { fingerBox.getItemAt(it).toString() })
      .containsExactly(
        "Finger 1",
        "Finger 2",
        "Finger 3",
        "Finger 4",
        "Finger 5",
        "Finger 6",
        "Finger 7",
        "Finger 8",
        "Finger 9",
        "Finger 10",
      )
      .inOrder()
    assertThat(fingerBox.selectedItem?.toString()).isEqualTo("Finger 1")
    assertThat(touchButton.text).isEqualTo("Touch Sensor")

    val closeButton = rootPane.defaultButton
    assertThat(closeButton.text).isEqualTo("Close")
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testTouchSensorPressAndRelease() {
    val dialog = FingerprintDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val fingerBox = ui.getComponent<ComboBox<*>> { it.name == "fingerComboBox" }
    val touchButton = ui.getComponent<JButton> { it.name == "touchSensorButton" }

    val buttonPos = ui.getPosition(touchButton)
    val clickX = buttonPos.x + touchButton.width / 2
    val clickY = buttonPos.y + touchButton.height / 2

    // Press and hold Touch Sensor with Finger 1 selected.
    ui.mouse.press(clickX, clickY)
    waitForCondition(5.seconds) {
      fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 45146572
    }

    // Release Touch Sensor.
    ui.mouse.release()
    waitForCondition(5.seconds) {
      !fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 0
    }

    // Select Finger 3 and press/release Touch Sensor.
    fingerBox.selectItem("Finger 3")
    ui.mouse.press(clickX, clickY)
    waitForCondition(5.seconds) {
      fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 84807873
    }

    ui.mouse.release()
    waitForCondition(5.seconds) {
      !fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 0
    }

    // Select Finger 10 and press/release Touch Sensor.
    fingerBox.selectItem("Finger 10")
    ui.mouse.press(clickX, clickY)
    waitForCondition(5.seconds) {
      fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 87754286
    }

    ui.mouse.release()
    waitForCondition(5.seconds) {
      !fakeEmulator.fingerprint.isTouching && fakeEmulator.fingerprint.touchId == 0
    }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  private fun ComboBox<*>.selectItem(text: String) {
    selectedItem = (0 until itemCount).map { getItemAt(it) }.first { it.toString() == text }
  }
}
