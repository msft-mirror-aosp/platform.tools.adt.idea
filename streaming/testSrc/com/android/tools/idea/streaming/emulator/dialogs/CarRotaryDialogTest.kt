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

import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbDeviceServices
import com.android.sdklib.AndroidVersion
import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.adblib.testing.FakeAdbSessionRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.streaming.emulator.dialogs.CarRotaryDialog.CarRotaryControllerPanel
import com.android.tools.idea.streaming.emulator.dialogs.CarRotaryDialog.Companion.CAR_ROTARY_SERVICE_PROCESS
import com.android.tools.idea.streaming.emulator.dialogs.CarRotaryDialog.Companion.CHECK_ROTARY_SERVICE_COMMAND
import com.android.tools.idea.streaming.emulator.dialogs.CarRotaryDialog.RotaryButton
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBTextField
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_UP
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [CarRotaryDialog]. */
@RunsInEdt
class CarRotaryDialogTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val adbSessionRule = FakeAdbSessionRule(projectRule)
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/CarRotaryDialogTest/golden")

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      adbSessionRule,
      PortableUiFontRule(),
      goldenImageRule,
      EdtRule(),
      HeadlessDialogRule(),
    )

  private val project
    get() = projectRule.project

  private val adb: FakeAdbDeviceServices
    get() = AdbLibService.getSession(project).deviceServices as FakeAdbDeviceServices

  private fun createAndStartAutomotiveEmulator(): Pair<FakeEmulator, EmulatorController> {
    val avdFolder = FakeEmulator.createAutomotiveAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(32, 0))
    val fakeEmulator = emulatorRule.newEmulator(avdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  private fun configureAllRotaryCommands(deviceSelector: DeviceSelector) {
    val actionCommands =
      listOf(
        "cmd car_service inject-key 280",
        "cmd car_service inject-key 281",
        "cmd car_service inject-key 282",
        "cmd car_service inject-key 283",
        "cmd car_service inject-key 23",
        "cmd car_service inject-key 4",
      )
    for (cmd in actionCommands) {
      adb.configureShellCommand(deviceSelector, "$cmd -a down", "")
      adb.configureShellCommand(deviceSelector, "$cmd -a up", "")
    }
    adb.configureShellCommand(deviceSelector, "cmd car_service inject-rotary", "")
    adb.configureShellCommand(deviceSelector, "cmd car_service inject-rotary -c true", "")
    adb.configureShellCommand(deviceSelector, "input keyevent 3", "")
  }

  private fun commandRequests(): List<String> =
    adb.shellV2Requests.mapNotNull { request -> request.command.takeIf { it != CHECK_ROTARY_SERVICE_COMMAND } }

  @Test
  fun testDialogControlsAndButtons() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    adb.configureShellCommand(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND, "NAME\nsystem_server\n$CAR_ROTARY_SERVICE_PROCESS\n")
    configureAllRotaryCommands(deviceSelector)

    val dialog = CarRotaryDialog(emulatorController).show(project)
    assertThat(dialog.title).isEqualTo("Car Rotary (${emulatorController.emulatorId.avdName})")
    val rootPane = dialog.rootPane
    assertThat(rootPane.defaultButton).isNull()
    val ui = FakeUi(rootPane)
    ui.layoutAndDispatchEvents()

    val controllerPanel = ui.getComponent<CarRotaryControllerPanel>()
    goldenImageRule.assertImageSimilar("CarRotaryControllerPanel", ui.render(controllerPanel))

    val warningLabel = ui.getComponent<JLabel> { it.name == "warningLabel" }
    assertThat(warningLabel.isVisible).isFalse()

    val intervalField = ui.getComponent<JBTextField> { it.name == "longPressIntervalField" }
    assertThat(intervalField.text).isEqualTo("300")

    val actionButtons =
      mapOf(
        "upButton" to ("Up" to "cmd car_service inject-key 280"),
        "downButton" to ("Down" to "cmd car_service inject-key 281"),
        "leftButton" to ("Left" to "cmd car_service inject-key 282"),
        "rightButton" to ("Right" to "cmd car_service inject-key 283"),
        "centerButton" to ("Select" to "cmd car_service inject-key 23"),
        "backButton" to ("Back" to "cmd car_service inject-key 4"),
      )

    for ((buttonName, expected) in actionButtons) {
      val (expectedTooltip, baseCommand) = expected
      adb.shellV2Requests.clear()
      val button = ui.getComponent<RotaryButton> { it.name == buttonName }
      assertThat(button.toolTipText).isEqualTo(expectedTooltip)
      assertThat(button.accessibleContext.accessibleName).isEqualTo(expectedTooltip)

      val pos = ui.getPosition(button)
      ui.mouse.press(pos.x + button.hitPoint.x, pos.y + button.hitPoint.y)
      waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
      assertThat(commandRequests()).containsExactly("$baseCommand -a down")

      ui.mouse.release()
      waitForCondition(2.seconds) { commandRequests().size == 2 }
      assertThat(commandRequests()).containsExactly("$baseCommand -a down", "$baseCommand -a up").inOrder()
    }

    // Home button sends keyevent only on release.
    adb.shellV2Requests.clear()
    val homeButton = ui.getComponent<RotaryButton> { it.name == "homeButton" }
    assertThat(homeButton.toolTipText).isEqualTo("Home")
    val homePos = ui.getPosition(homeButton)
    ui.mouse.press(homePos.x + homeButton.hitPoint.x, homePos.y + homeButton.hitPoint.y)
    assertThat(commandRequests()).isEmpty()
    ui.mouse.release()
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly("input keyevent 3")

    // Single clicks on rotation buttons emit on press and do not emit an extra command on release.
    val rotationButtons =
      mapOf(
        "counterclockwiseButton" to ("Rotate counterclockwise" to "cmd car_service inject-rotary"),
        "clockwiseButton" to ("Rotate clockwise" to "cmd car_service inject-rotary -c true"),
      )
    for ((buttonName, expected) in rotationButtons) {
      val (expectedTooltip, expectedCommand) = expected
      adb.shellV2Requests.clear()
      val button = ui.getComponent<RotaryButton> { it.name == buttonName }
      assertThat(button.toolTipText).isEqualTo(expectedTooltip)
      val pos = ui.getPosition(button)
      ui.mouse.press(pos.x + button.hitPoint.x, pos.y + button.hitPoint.y)
      waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
      assertThat(commandRequests()).containsExactly(expectedCommand)
      ui.mouse.release()
      assertThat(commandRequests()).containsExactly(expectedCommand)
    }

    // Keyboard SPACE activation on focused button.
    adb.shellV2Requests.clear()
    val centerButton = ui.getComponent<RotaryButton> { it.name == "centerButton" }
    ui.keyboard.setFocus(centerButton)
    ui.keyboard.press(VK_SPACE)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly("cmd car_service inject-key 23 -a down")
    ui.keyboard.release(VK_SPACE)
    waitForCondition(2.seconds) { commandRequests().size == 2 }
    assertThat(commandRequests()).containsExactly("cmd car_service inject-key 23 -a down", "cmd car_service inject-key 23 -a up").inOrder()

    // Shape hit-testing: center of backButton's bounding box lies inside the circular D-pad cutout, not inside backButton.
    adb.shellV2Requests.clear()
    val backButton = ui.getComponent<RotaryButton> { it.name == "backButton" }
    assertThat(backButton.contains(1, 1)).isFalse()
    assertThat(backButton.contains(backButton.hitPoint.x, backButton.hitPoint.y)).isTrue()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testLongPressAutoRepeatAndIntervalPersistence() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    adb.configureShellCommand(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND, "NAME\n$CAR_ROTARY_SERVICE_PROCESS\n")
    configureAllRotaryCommands(deviceSelector)

    val dialog = CarRotaryDialog(emulatorController).show(project)
    val ui = FakeUi(dialog.rootPane)
    ui.layoutAndDispatchEvents()

    val intervalField = ui.getComponent<JBTextField> { it.name == "longPressIntervalField" }
    ui.keyboard.setFocus(intervalField)
    ui.keyboard.pressAndRelease(VK_UP)
    assertThat(intervalField.text).isEqualTo("350")
    ui.keyboard.pressAndRelease(VK_DOWN)
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(intervalField.text).isEqualTo("250")
    intervalField.text = "40"

    val cwButton = ui.getComponent<RotaryButton> { it.name == "clockwiseButton" }
    val cwPos = ui.getPosition(cwButton)
    adb.shellV2Requests.clear()

    // Holding clockwiseButton sends an initial command on press followed by repeated rotation commands, and none on release.
    ui.mouse.press(cwPos.x + cwButton.hitPoint.x, cwPos.y + cwButton.hitPoint.y)
    waitForCondition(2.seconds) { commandRequests().size >= 3 }
    assertThat(commandRequests()).contains("cmd car_service inject-rotary -c true")

    ui.mouse.release()
    val countAfterRelease = commandRequests().size
    Thread.sleep(120)
    assertThat(commandRequests()).hasSize(countAfterRelease)

    // Keyboard stepping clamps to the minimum valid interval (1 ms), and invalid values show an error outline without overwriting state.
    intervalField.text = "50"
    ui.keyboard.pressAndRelease(VK_DOWN)
    assertThat(intervalField.text).isEqualTo("1")

    intervalField.text = "50"
    intervalField.text = "0"
    assertThat(intervalField.getClientProperty("JComponent.outline")).isEqualTo("error")

    intervalField.text = ""
    assertThat(intervalField.getClientProperty("JComponent.outline")).isEqualTo("error")

    // Values >= 1000 format without grouping commas and clear the error outline.
    intervalField.text = "1200"
    assertThat(intervalField.getClientProperty("JComponent.outline")).isNull()

    // Direct text edits update and persist the interval immediately.
    intervalField.text = "450"
    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()

    val reopenedDialog = CarRotaryDialog(emulatorController).show(project)
    val reopenedUi = FakeUi(reopenedDialog.rootPane)
    reopenedUi.layoutAndDispatchEvents()
    val reopenedIntervalField = reopenedUi.getComponent<JBTextField> { it.name == "longPressIntervalField" }
    assertThat(reopenedIntervalField.text).isEqualTo("450")

    // Closing via the Close button while the field is invalid preserves the last valid interval.
    reopenedIntervalField.text = ""
    reopenedUi.clickOn(reopenedUi.getComponent<JButton> { it.text == "Close" })
    assertThat(reopenedDialog.isShowing).isFalse()

    val thirdDialog = CarRotaryDialog(emulatorController).show(project)
    val thirdUi = FakeUi(thirdDialog.rootPane)
    val thirdIntervalField = thirdUi.getComponent<JBTextField> { it.name == "longPressIntervalField" }
    assertThat(thirdIntervalField.text).isEqualTo("450")

    thirdDialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(thirdDialog.isShowing).isFalse()
  }

  @Test
  fun testDialogCloseWhileButtonPressedSendsRelease() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    adb.configureShellCommand(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND, "NAME\n$CAR_ROTARY_SERVICE_PROCESS\n")
    configureAllRotaryCommands(deviceSelector)

    val dialog = CarRotaryDialog(emulatorController).show(project)
    val ui = FakeUi(dialog.rootPane)
    ui.layoutAndDispatchEvents()

    val upButton = ui.getComponent<RotaryButton> { it.name == "upButton" }
    val upPos = ui.getPosition(upButton)
    adb.shellV2Requests.clear()

    ui.mouse.press(upPos.x + upButton.hitPoint.x, upPos.y + upButton.hitPoint.y)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly("cmd car_service inject-key 280 -a down")

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()
    waitForCondition(2.seconds) { commandRequests().size == 2 }
    assertThat(commandRequests())
      .containsExactly("cmd car_service inject-key 280 -a down", "cmd car_service inject-key 280 -a up")
      .inOrder()
  }

  @Test
  fun testCarRotaryServiceWarningVisibility() {
    val (fakeEmulator, emulatorController) = createAndStartAutomotiveEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    adb.configureShellCommand(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND, "NAME\nsystem_server\n")

    val dialog = CarRotaryDialog(emulatorController, serviceCheckInterval = 50.milliseconds).show(project)
    val ui = FakeUi(dialog.rootPane)
    ui.layoutAndDispatchEvents()

    val warningLabel = ui.getComponent<JLabel> { it.name == "warningLabel" }
    waitForCondition(2.seconds) { warningLabel.isVisible }
    assertThat(warningLabel.text).contains("CarRotaryService is not running.")

    // Once CarRotaryService starts running, the warning hides and polling stops.
    adb.configureShellCommand(deviceSelector, CHECK_ROTARY_SERVICE_COMMAND, "NAME\nsystem_server\n$CAR_ROTARY_SERVICE_PROCESS\n")
    waitForCondition(2.seconds) { !warningLabel.isVisible }

    adb.shellV2Requests.clear()
    Thread.sleep(150)
    assertThat(adb.shellV2Requests).isEmpty()

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()
  }
}
