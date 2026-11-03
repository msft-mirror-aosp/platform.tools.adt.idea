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
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.adtui.swing.replaceKeyboardFocusManager
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.adblib.testing.FakeAdbSessionRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBTextArea
import java.awt.DefaultKeyboardFocusManager
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_TAB
import java.time.Duration
import javax.swing.JButton
import javax.swing.KeyStroke
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.spy

/** Tests for [GoogleAssistantDialog]. */
@RunsInEdt
class GoogleAssistantDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val adbSessionRule = FakeAdbSessionRule(projectRule)

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      adbSessionRule,
      PortableUiFontRule(),
      EdtRule(),
      HeadlessDialogRule(),
    )

  private val project
    get() = projectRule.project

  private val adb: FakeAdbDeviceServices
    get() = AdbLibService.getSession(project).deviceServices as FakeAdbDeviceServices

  private fun createAndStartTvEmulator(): Pair<FakeEmulator, EmulatorController> {
    val avdFolder = FakeEmulator.createTvAvd(emulatorRule.avdRoot)
    val fakeEmulator = emulatorRule.newEmulator(avdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  private fun commandRequests(): List<String> = adb.shellV2Requests.map { it.command }

  @Test
  fun testSendDefaultAndCustomQueries() {
    val (fakeEmulator, emulatorController) = createAndStartTvEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)

    val defaultCommand = "am start -a android.intent.action.ASSIST --ei search_type 1 --es query \"Hey Google\""
    adb.configureShellCommand(deviceSelector, defaultCommand, "")

    val dialog = GoogleAssistantDialog(emulatorController).show(project)
    assertThat(dialog.title).isEqualTo("Google Assistant (${emulatorController.emulatorId.avdName})")
    val rootPane = dialog.rootPane
    assertThat(rootPane.defaultButton).isNull()
    val ui = FakeUi(rootPane)
    ui.layoutAndDispatchEvents()

    val queryTextArea = ui.getComponent<JBTextArea> { it.name == "queryTextArea" }
    val sendButton = ui.getComponent<JButton> { it.name == "sendButton" }

    assertThat(queryTextArea.text).isEqualTo("Hey Google")
    assertThat(queryTextArea.isEditable).isTrue()
    assertThat(queryTextArea.accessibleContext.accessibleName).isEqualTo("Query")
    assertThat(sendButton.text).isEqualTo("Send")
    assertThat(sendButton.isEnabled).isTrue()

    ui.clickOn(sendButton)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly(defaultCommand)
    assertThat(adb.shellV2Requests.single().commandTimeout).isEqualTo(Duration.ofSeconds(5))

    // A timed-out command is handled gracefully without blocking subsequent queries.
    adb.shellV2Requests.clear()
    adb.shellNumTimeouts = 1
    ui.clickOn(sendButton)
    waitForCondition(2.seconds) { adb.shellNumTimeouts == 0 }
    ui.clickOn(sendButton)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly(defaultCommand)

    // Leading/trailing whitespace is trimmed, line breaks are normalized to spaces, and shell special characters are escaped.
    adb.shellV2Requests.clear()
    val specialQuery = "  Play \"Jazz\"\n\n  & \$HOME \\ `uname`\r\n'now'  "
    val expectedSpecialCommand =
      """am start -a android.intent.action.ASSIST --ei search_type 1 --es query "Play \"Jazz\" & \${'$'}HOME \\ \`uname\` 'now'""""
    adb.configureShellCommand(deviceSelector, expectedSpecialCommand, "")

    queryTextArea.text = specialQuery
    assertThat(sendButton.isEnabled).isTrue()
    ui.clickOn(sendButton)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly(expectedSpecialCommand)

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testBlankQueryDisablesSendAndQueryPersistence() {
    val (_, emulatorController) = createAndStartTvEmulator()

    val dialog = GoogleAssistantDialog(emulatorController).show(project)
    val ui = FakeUi(dialog.rootPane)
    ui.layoutAndDispatchEvents()

    val queryTextArea = ui.getComponent<JBTextArea> { it.name == "queryTextArea" }
    val sendButton = ui.getComponent<JButton> { it.name == "sendButton" }

    queryTextArea.text = ""
    assertThat(sendButton.isEnabled).isFalse()

    queryTextArea.text = "   \n\t  "
    assertThat(sendButton.isEnabled).isFalse()

    queryTextArea.text = "What is the weather today?"
    assertThat(sendButton.isEnabled).isTrue()

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()

    val reopenedDialog = GoogleAssistantDialog(emulatorController).show(project)
    val reopenedUi = FakeUi(reopenedDialog.rootPane)
    reopenedUi.layoutAndDispatchEvents()
    val reopenedQueryTextArea = reopenedUi.getComponent<JBTextArea> { it.name == "queryTextArea" }
    val reopenedSendButton = reopenedUi.getComponent<JButton> { it.name == "sendButton" }
    assertThat(reopenedQueryTextArea.text).isEqualTo("What is the weather today?")
    assertThat(reopenedSendButton.isEnabled).isTrue()

    // Persisting a blank query keeps Send disabled when reopened.
    reopenedQueryTextArea.text = "   "
    reopenedUi.clickOn(reopenedUi.getComponent<JButton> { it.text == "Close" })
    assertThat(reopenedDialog.isShowing).isFalse()

    val thirdDialog = GoogleAssistantDialog(emulatorController).show(project)
    val thirdUi = FakeUi(thirdDialog.rootPane)
    thirdUi.layoutAndDispatchEvents()
    val thirdQueryTextArea = thirdUi.getComponent<JBTextArea> { it.name == "queryTextArea" }
    val thirdSendButton = thirdUi.getComponent<JButton> { it.name == "sendButton" }
    assertThat(thirdQueryTextArea.text).isEqualTo("   ")
    assertThat(thirdSendButton.isEnabled).isFalse()

    thirdDialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(thirdDialog.isShowing).isFalse()
  }

  @Test
  fun testKeyboardFocusTraversalAndActivation() {
    val (fakeEmulator, emulatorController) = createAndStartTvEmulator()
    val deviceSelector = DeviceSelector.fromSerialNumber(fakeEmulator.serialNumber)
    val expectedCommand = "am start -a android.intent.action.ASSIST --ei search_type 1 --es query \"Open YouTube\""
    adb.configureShellCommand(deviceSelector, expectedCommand, "")

    val dialog = GoogleAssistantDialog(emulatorController).show(project)
    val ui = FakeUi(dialog.rootPane, parentDisposable = projectRule.disposable)
    ui.layoutAndDispatchEvents()

    val queryTextArea = ui.getComponent<JBTextArea> { it.name == "queryTextArea" }
    val sendButton = ui.getComponent<JButton> { it.name == "sendButton" }

    val focusManager = spy(DefaultKeyboardFocusManager())
    replaceKeyboardFocusManager(focusManager, projectRule.disposable)

    queryTextArea.text = "Open YouTube"
    focusManager.processKeyEvent(queryTextArea, KeyEvent(queryTextArea, KEY_PRESSED, System.nanoTime(), 0, VK_TAB, VK_TAB.toChar()))
    verify(focusManager, atLeast(1)).focusNextComponent(eq(queryTextArea))
    assertThat(queryTextArea.text).isEqualTo("Open YouTube")

    focusManager.processKeyEvent(
      queryTextArea,
      KeyEvent(queryTextArea, KEY_PRESSED, System.nanoTime(), SHIFT_DOWN_MASK, VK_TAB, VK_TAB.toChar()),
    )
    verify(focusManager, atLeast(1)).focusPreviousComponent(eq(queryTextArea))

    ui.keyboard.setFocus(queryTextArea)
    ui.keyboard.hit(KeyStroke.getKeyStroke(VK_ENTER, CTRL_DOWN_MASK))
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly(expectedCommand)
    assertThat(queryTextArea.text).isEqualTo("Open YouTube")

    adb.shellV2Requests.clear()
    ui.keyboard.setFocus(sendButton)
    ui.keyboard.pressAndRelease(VK_SPACE)
    waitForCondition(2.seconds) { commandRequests().isNotEmpty() }
    assertThat(commandRequests()).containsExactly(expectedCommand)

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()
  }
}
