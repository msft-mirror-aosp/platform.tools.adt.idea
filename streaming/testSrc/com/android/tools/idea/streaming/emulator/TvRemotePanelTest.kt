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
package com.android.tools.idea.streaming.emulator

import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbDeviceServices
import com.android.sdklib.AndroidVersion
import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.adblib.testing.FakeAdbSessionRule
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import java.awt.Dimension
import java.awt.event.KeyEvent.VK_SPACE
import javax.swing.JButton
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [TvRemotePanel]. */
@RunsInEdt
class TvRemotePanelTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val adbSessionRule = FakeAdbSessionRule(projectRule)
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/TvRemotePanelTest/golden")

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      adbSessionRule,
      goldenImageRule,
      EdtRule(),
    )

  private val project
    get() = projectRule.project

  private val testRootDisposable
    get() = projectRule.disposable

  private val adb: FakeAdbDeviceServices
    get() = AdbLibService.getSession(project).deviceServices as FakeAdbDeviceServices

  @Test
  fun testTvRemotePanelButtonsApi34() {
    val avdFolder = FakeEmulator.createTvAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(34, 0))
    val emulator = emulatorRule.newEmulator(avdFolder)
    emulator.start()
    val emulatorController = getControllerOf(emulator)
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val deviceSelector = DeviceSelector.fromSerialNumber(emulator.serialNumber)
    val expectedCommands =
      mapOf(
        "Watchlist" to "input keyevent KEYCODE_BOOKMARK",
        "Toggle Dashboard" to "input keyevent KEYCODE_NOTIFICATION",
        "Open Settings" to "am start -n com.android.tv.settings/com.android.tv.settings.MainSettings",
        "Open Live Channels" to "am start -n com.android.tv/com.android.tv.MainActivity",
      )
    for (command in expectedCommands.values) {
      adb.configureShellCommand(deviceSelector, command, "")
    }

    val panel = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel.createContent(true)
    panel.size = Dimension(400, 600)
    val ui = FakeUi(panel, parentDisposable = testRootDisposable)
    ui.layoutAndDispatchEvents()

    val tvRemotePanel = ui.getComponent<TvRemotePanel>()
    goldenImageRule.assertImageSimilar("TvRemotePanel", ui.render(tvRemotePanel))

    val keyButtons =
      mapOf(
        "Up" to "ArrowUp",
        "Down" to "ArrowDown",
        "Left" to "ArrowLeft",
        "Right" to "ArrowRight",
        "Select" to "Enter",
        "Back" to "GoBack",
        "Home" to "GoHome",
      )

    var streamInputCall: FakeEmulator.GrpcCallRecord? = null
    for ((tooltip, expectedKey) in keyButtons) {
      val button = ui.getComponent<JButton> { it.toolTipText == tooltip }
      val pos = ui.getPosition(button)
      ui.mouse.press(pos.x + button.width / 2, pos.y + button.height / 2)
      val call =
        streamInputCall
          ?: emulator.getNextGrpcCall(2.seconds, FakeEmulator.IGNORE_SCREENSHOT_CALL_FILTER).also {
            assertThat(it.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
            streamInputCall = it
          }
      assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"$expectedKey\" }")
      ui.mouse.release()
      assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"$expectedKey\" }")
    }
    assertNotNull(streamInputCall)

    // Check keyboard SPACE activation on a key button.
    val selectButton = ui.getComponent<JButton> { it.toolTipText == "Select" }
    ui.keyboard.setFocus(selectButton)
    ui.keyboard.press(VK_SPACE)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"Enter\" }")
    ui.keyboard.release(VK_SPACE)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"Enter\" }")

    // Check ADB command buttons.
    for ((tooltip, expectedCommand) in expectedCommands) {
      adb.shellV2Requests.clear()
      val button = ui.getComponent<JButton> { it.toolTipText == tooltip }
      ui.clickOn(button)
      waitForCondition(2.seconds) { adb.shellV2Requests.isNotEmpty() }
      assertThat(adb.shellV2Requests.single().command).isEqualTo(expectedCommand)
    }

    panel.destroyContent()
  }

  @Test
  fun testLiveChannelsApi33() {
    val avdFolder = FakeEmulator.createTvAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(33, 0))
    val emulator = emulatorRule.newEmulator(avdFolder)
    emulator.start()
    val emulatorController = getControllerOf(emulator)
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val deviceSelector = DeviceSelector.fromSerialNumber(emulator.serialNumber)
    val expectedCommand = "am start -n com.google.android.tv/com.android.tv.MainActivity"
    adb.configureShellCommand(deviceSelector, expectedCommand, "")

    val panel = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel.createContent(true)
    panel.size = Dimension(400, 600)
    val ui = FakeUi(panel, parentDisposable = testRootDisposable)
    ui.layoutAndDispatchEvents()

    val button = ui.getComponent<JButton> { it.toolTipText == "Open Live Channels" }
    ui.clickOn(button)
    waitForCondition(2.seconds) { adb.shellV2Requests.isNotEmpty() }
    assertThat(adb.shellV2Requests.single().command).isEqualTo(expectedCommand)

    panel.destroyContent()
  }

  private fun getControllerOf(emulator: FakeEmulator): EmulatorController {
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    return emulators.single { emulator.serialNumber == it.emulatorId.serialNumber }
  }
}
