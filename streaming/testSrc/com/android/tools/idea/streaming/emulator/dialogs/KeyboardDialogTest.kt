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
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.KeyboardAwareFocusOwner
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.KEY_RELEASED
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_ALT
import java.awt.event.KeyEvent.VK_BACK_SPACE
import java.awt.event.KeyEvent.VK_CONTROL
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_META
import java.awt.event.KeyEvent.VK_SHIFT
import java.awt.event.KeyEvent.VK_TAB
import javax.swing.JButton
import javax.swing.JToggleButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [KeyboardDialog]. */
@RunsInEdt
class KeyboardDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      PortableUiFontRule(),
      EdtRule(),
      HeadlessDialogRule(),
    )

  private val project
    get() = projectRule.project

  private fun createAndStartDesktopEmulator(): Pair<FakeEmulator, EmulatorController> {
    val avdFolder = FakeEmulator.createDesktopAvd(emulatorRule.avdRoot)
    val fakeEmulator = emulatorRule.newEmulator(avdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  @Test
  fun testInitialStateAndAdditionalKeys() {
    val (fakeEmulator, emulatorController) = createAndStartDesktopEmulator()
    val dialog = KeyboardDialog(emulatorController).show(project)
    assertThat(dialog.title).isEqualTo("Keyboard (${emulatorController.emulatorId.avdName})")
    val rootPane = dialog.rootPane
    assertThat(rootPane.defaultButton).isNull()
    val ui = FakeUi(rootPane, parentDisposable = projectRule.disposable)
    ui.layoutAndDispatchEvents()

    val keyButtons =
      listOf(
        "forwardButton" to ("Forward" to 159),
        "refreshButton" to ("Refresh" to 173),
        "fullScreenButton" to ("Full Screen" to 372),
        "screenCaptureButton" to ("Screen Capture" to 99),
        "brightnessDownButton" to ("Display Brightness Down" to 224),
        "brightnessUpButton" to ("Display Brightness Up" to 225),
        "mediaPreviousButton" to ("Previous Track" to 165),
        "mediaPlayPauseButton" to ("Play / Pause" to 164),
        "mediaNextButton" to ("Next Track" to 163),
        "micMuteButton" to ("System Microphone Mute" to 248),
        "volumeMuteButton" to ("Mute Speakers" to 113),
        "screenLockButton" to ("Screen Lock" to 152),
      )

    val ctrlButton = ui.getComponent<JToggleButton> { it.name == "modifierCtrlButton" }
    val altButton = ui.getComponent<JToggleButton> { it.name == "modifierAltButton" }
    val shiftButton = ui.getComponent<JToggleButton> { it.name == "modifierShiftButton" }
    val metaButton = ui.getComponent<JToggleButton> { it.name == "modifierMetaButton" }
    assertThat(ctrlButton.text).isEqualTo("Ctrl")
    assertThat(altButton.text).isEqualTo("Alt")
    assertThat(shiftButton.text).isEqualTo("Shift")
    assertThat(metaButton.text).isEqualTo("Meta")
    assertThat(ctrlButton.isSelected).isFalse()
    assertThat(altButton.isSelected).isFalse()
    assertThat(shiftButton.isSelected).isFalse()
    assertThat(metaButton.isSelected).isFalse()
    assertThat(listOf(ctrlButton.width, altButton.width, shiftButton.width, metaButton.width).distinct()).hasSize(1)
    assertThat(listOf(ctrlButton.height, altButton.height, shiftButton.height, metaButton.height).distinct()).hasSize(1)

    val keyCaptureField = ui.getComponent<JBTextField> { it.name == "keyCaptureField" }
    assertThat(keyCaptureField.isEditable).isTrue()
    assertThat(keyCaptureField.transferHandler).isNull()
    assertThat(keyCaptureField.text).isEmpty()
    keyCaptureField.replaceSelection("unexpected")
    assertThat(keyCaptureField.text).isEmpty()
    assertThat(keyCaptureField.emptyText.text).isEqualTo("Press key")
    assertThat(keyCaptureField.focusTraversalKeysEnabled).isFalse()
    assertThat(keyCaptureField.accessibleContext.accessibleName).isEqualTo("Key")
    val focusOwner = keyCaptureField as KeyboardAwareFocusOwner
    assertThat(focusOwner.skipKeyEventDispatcher(KeyEvent(keyCaptureField, KEY_PRESSED, 0, 0, VK_BACK_SPACE, '\b'))).isTrue()
    assertThat(focusOwner.skipKeyEventDispatcher(KeyEvent(keyCaptureField, KEY_PRESSED, 0, 0, VK_ESCAPE, '\u001b'))).isTrue()

    val sendButton = ui.getComponent<IconButton> { it.name == "sendButton" }
    assertThat(sendButton.text).isEmpty()
    assertThat(sendButton.icon).isEqualTo(StudioIcons.AppQualityInsights.SEND)
    assertThat(sendButton.tooltipText).isEqualTo("Send")
    assertThat(sendButton.accessibleContext.accessibleName).isEqualTo("Send")
    assertThat(sendButton.isEnabled).isFalse()

    var inputStreamCall: FakeEmulator.GrpcCallRecord? = null
    for ((name, expected) in keyButtons) {
      val (tooltip, keyCode) = expected
      val button = ui.getComponent<IconButton> { it.name == name }
      assertThat(button.tooltipText).isEqualTo(tooltip)
      assertThat(button.accessibleContext.accessibleName).isEqualTo(tooltip)
      val pos = ui.getPosition(button)
      val clickX = pos.x + button.width / 2
      val clickY = pos.y + button.height / 2

      ui.mouse.press(clickX, clickY)
      if (inputStreamCall == null) {
        inputStreamCall =
          fakeEmulator.getNextGrpcCall(
            2.seconds,
            { it.methodName == "android.emulation.control.EmulatorController/streamInputEvent" },
          )
      }
      assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { codeType: Evdev keyCode: $keyCode }")

      ui.mouse.release()
      assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds)))
        .isEqualTo("key_event { codeType: Evdev eventType: keyup keyCode: $keyCode }")
    }

    // Closing the dialog while a key button is pressed sends a keyup event.
    val forwardButton = ui.getComponent<IconButton> { it.name == "forwardButton" }
    val pos = ui.getPosition(forwardButton)
    ui.mouse.press(pos.x + forwardButton.width / 2, pos.y + forwardButton.height / 2)
    assertThat(shortDebugString(inputStreamCall!!.getNextRequest(2.seconds))).isEqualTo("key_event { codeType: Evdev keyCode: 159 }")

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    assertThat(dialog.isShowing).isFalse()
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds)))
      .isEqualTo("key_event { codeType: Evdev eventType: keyup keyCode: 159 }")
  }

  @Test
  fun testKeyCombinationComposer() {
    val (fakeEmulator, emulatorController) = createAndStartDesktopEmulator()
    val dialog = KeyboardDialog(emulatorController).show(project)
    val ui = FakeUi(dialog.rootPane, parentDisposable = projectRule.disposable)
    ui.layoutAndDispatchEvents()

    val ctrlButton = ui.getComponent<JToggleButton> { it.name == "modifierCtrlButton" }
    val altButton = ui.getComponent<JToggleButton> { it.name == "modifierAltButton" }
    val shiftButton = ui.getComponent<JToggleButton> { it.name == "modifierShiftButton" }
    val metaButton = ui.getComponent<JToggleButton> { it.name == "modifierMetaButton" }
    val keyCaptureField = ui.getComponent<JBTextField> { it.name == "keyCaptureField" }
    val sendButton = ui.getComponent<JButton> { it.name == "sendButton" }

    // Modifier-only key presses highlight/unhighlight the corresponding buttons and do not populate the field.
    val modifierCases =
      listOf(
        VK_SHIFT to shiftButton,
        VK_CONTROL to ctrlButton,
        VK_ALT to altButton,
        VK_META to metaButton,
      )
    for ((modifierKeyCode, modifierButton) in modifierCases) {
      keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, modifierKeyCode, KeyEvent.CHAR_UNDEFINED))
      assertThat(modifierButton.isSelected).isTrue()
      assertThat(modifierButton.getClientProperty("JButton.backgroundColor")).isEqualTo(JBUI.CurrentTheme.ActionButton.pressedBackground())
      assertThat(keyCaptureField.text).isEmpty()
      assertThat(sendButton.isEnabled).isFalse()

      keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_RELEASED, System.nanoTime(), 0, modifierKeyCode, KeyEvent.CHAR_UNDEFINED))
      assertThat(modifierButton.isSelected).isFalse()
      assertThat(modifierButton.getClientProperty("JButton.backgroundColor")).isNull()
    }

    // Capturing a regular letter key shows lowercase without Shift, uppercase with Shift, and enables Send.
    keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, VK_A, 'a'))
    assertThat(keyCaptureField.text).isEqualTo("a")
    assertThat(sendButton.isEnabled).isTrue()

    ui.clickOn(ctrlButton)
    ui.clickOn(shiftButton)
    assertThat(keyCaptureField.text).isEqualTo("A")
    assertThat(ctrlButton.isSelected).isTrue()
    assertThat(ctrlButton.getClientProperty("JButton.backgroundColor")).isEqualTo(JBUI.CurrentTheme.ActionButton.pressedBackground())
    assertThat(shiftButton.isSelected).isTrue()
    assertThat(shiftButton.getClientProperty("JButton.backgroundColor")).isEqualTo(JBUI.CurrentTheme.ActionButton.pressedBackground())

    ui.clickOn(sendButton)
    assertThat(ctrlButton.isSelected).isFalse()
    assertThat(ctrlButton.getClientProperty("JButton.backgroundColor")).isNull()
    assertThat(shiftButton.isSelected).isFalse()
    assertThat(shiftButton.getClientProperty("JButton.backgroundColor")).isNull()
    assertThat(keyCaptureField.text).isEmpty()
    assertThat(sendButton.isEnabled).isFalse()

    val inputStreamCall =
      fakeEmulator.getNextGrpcCall(
        2.seconds,
        { it.methodName == "android.emulation.control.EmulatorController/streamInputEvent" },
      )
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Shift\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Control\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keypress key: \"a\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Control\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Shift\" }")

    // Capturing Tab works and does not traverse focus; Alt+Meta+Tab sends the expected sequence and resets modifiers.
    keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, VK_TAB, '\t'))
    assertThat(keyCaptureField.text).isEqualTo(KeyEvent.getKeyText(VK_TAB))

    ui.clickOn(altButton)
    ui.clickOn(metaButton)
    assertThat(altButton.isSelected).isTrue()
    assertThat(metaButton.isSelected).isTrue()
    ui.clickOn(sendButton)
    assertThat(altButton.isSelected).isFalse()
    assertThat(metaButton.isSelected).isFalse()
    assertThat(keyCaptureField.text).isEmpty()
    assertThat(sendButton.isEnabled).isFalse()

    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Alt\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Meta\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keypress key: \"Tab\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Meta\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Alt\" }")

    // Capturing Backspace works and sends "Backspace".
    keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, VK_BACK_SPACE, '\b'))
    assertThat(keyCaptureField.text).isEqualTo(KeyEvent.getKeyText(VK_BACK_SPACE))
    assertThat(sendButton.isEnabled).isTrue()
    ui.clickOn(sendButton)
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds)))
      .isEqualTo("key_event { eventType: keypress key: \"Backspace\" }")
    assertThat(keyCaptureField.text).isEmpty()
    assertThat(sendButton.isEnabled).isFalse()

    // Holding Ctrl on the keyboard while capturing Esc and clicking Send sends Control+Escape.
    keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, VK_CONTROL, KeyEvent.CHAR_UNDEFINED))
    assertThat(ctrlButton.isSelected).isTrue()
    keyCaptureField.dispatchEvent(KeyEvent(keyCaptureField, KEY_PRESSED, System.nanoTime(), 0, VK_ESCAPE, '\u001b'))
    assertThat(keyCaptureField.text).isEqualTo(KeyEvent.getKeyText(VK_ESCAPE))
    assertThat(sendButton.isEnabled).isTrue()
    ui.clickOn(sendButton)
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Control\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keypress key: \"Escape\" }")
    assertThat(shortDebugString(inputStreamCall.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Control\" }")
    assertThat(ctrlButton.isSelected).isFalse()
    assertThat(keyCaptureField.text).isEmpty()
    assertThat(sendButton.isEnabled).isFalse()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }
}
