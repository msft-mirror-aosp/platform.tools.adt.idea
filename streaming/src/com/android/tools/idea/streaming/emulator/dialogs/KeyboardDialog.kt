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

import com.android.emulator.control.InputEvent
import com.android.emulator.control.KeyboardEvent
import com.android.emulator.control.KeyboardEvent.KeyCodeType
import com.android.emulator.control.KeyboardEvent.KeyEventType
import com.android.tools.idea.streaming.StagingIcons
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.EmulatorKeyStroke
import com.android.tools.idea.streaming.emulator.VK_TO_DOM_KEY_NAME
import com.android.tools.idea.streaming.emulator.sendKeyStroke
import com.intellij.ide.KeyboardAwareFocusOwner
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.META_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultCaret
import javax.swing.text.DocumentFilter

/**
 * Dialog for sending additional keys and key combinations to an Android emulator, providing functionality similar to the Keyboard page in
 * extended controls.
 */
internal class KeyboardDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("keyboard.dialog.title", emulator.emulatorId.avdName)) {

  private val pressedKeys = mutableSetOf<Int>()
  private val modifierButtons = mutableListOf<Pair<JToggleButton, Int>>()
  private var capturedKeyCode: Int = KeyEvent.VK_UNDEFINED
  private var capturedKeyName: String? = null
  private var isUpdatingKeyCaptureText: Boolean = false
  private lateinit var keyCaptureField: JBTextField
  private lateinit var sendButton: JButton

  init {
    onDispose {
      releaseAllPressedKeys()
    }
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    releaseAllPressedKeys()
    modifierButtons.clear()
    capturedKeyCode = KeyEvent.VK_UNDEFINED
    capturedKeyName = null

    return panel {
      row {
        label(message("keyboard.dialog.label.action.keys"))
      }
      row {
        cell(createAdditionalKeysRow())
      }

      row {
          label(message("keyboard.dialog.label.key.combination"))
        }
        .topGap(TopGap.SMALL)
      row {
        val modifiers =
          listOf(
            ModifierSpec("modifierCtrlButton", message("keyboard.dialog.modifier.ctrl"), CTRL_DOWN_MASK),
            ModifierSpec("modifierShiftButton", message("keyboard.dialog.modifier.shift"), SHIFT_DOWN_MASK),
            ModifierSpec("modifierAltButton", message("keyboard.dialog.modifier.alt"), ALT_DOWN_MASK),
            ModifierSpec("modifierMetaButton", message("keyboard.dialog.modifier.meta"), META_DOWN_MASK),
          )
        for (spec in modifiers) {
          val toggleButton =
            object : JToggleButton(spec.label) {
                override fun getPreferredSize(): Dimension {
                  val size = super.getPreferredSize()
                  val maxTextWidth =
                    modifierButtons.maxOfOrNull { (button, _) ->
                      button.getFontMetrics(button.font).stringWidth(button.text)
                    } ?: 0
                  return Dimension(maxTextWidth + JBUIScale.scale(24), size.height)
                }

                override fun getMinimumSize(): Dimension = preferredSize

                override fun updateUI() {
                  super.updateUI()
                  updateBackgroundColor()
                }

                private fun updateBackgroundColor() {
                  putClientProperty("JButton.backgroundColor", if (isSelected) JBUI.CurrentTheme.ActionButton.pressedBackground() else null)
                  repaint()
                }
              }
              .apply {
                name = spec.componentName
                addItemListener {
                  putClientProperty("JButton.backgroundColor", if (isSelected) JBUI.CurrentTheme.ActionButton.pressedBackground() else null)
                  repaint()
                  if (spec.mask == SHIFT_DOWN_MASK) {
                    updateKeyCaptureText()
                  }
                }
              }
          modifierButtons.add(toggleButton to spec.mask)
          cell(toggleButton)
        }

        label("+")

        keyCaptureField = createKeyCaptureField()
        cell(keyCaptureField)

        val sendLabel = message("keyboard.dialog.button.send")
        sendButton =
          IconButton(StudioIcons.AppQualityInsights.SEND, sendLabel) { sendKeyCombination() }
            .apply {
              name = "sendButton"
              accessibleContext.accessibleName = sendLabel
            }
        cell(sendButton)
      }
    }
      .apply {
        updateSendButton()
      }
  }

  private fun createAdditionalKeysRow(): JPanel {
    val rowPanel = JPanel(GridLayout(1, ACTION_KEYS.size, JBUIScale.scale(4), 0))
    for (keySpec in ACTION_KEYS) {
      val tooltip = message(keySpec.tooltipKey)
      val button =
        IconButton(keySpec.icon, tooltip) {}
          .apply {
            name = keySpec.componentName
            accessibleContext.accessibleName = tooltip
            model.addChangeListener {
              val pressed = model.isPressed
              if (pressed && pressedKeys.add(keySpec.evdevCode)) {
                sendEvdevKeyEvent(keySpec.evdevCode, KeyEventType.keydown)
              } else if (!pressed && pressedKeys.remove(keySpec.evdevCode)) {
                sendEvdevKeyEvent(keySpec.evdevCode, KeyEventType.keyup)
              }
            }
          }
      rowPanel.add(button)
    }
    return rowPanel
  }

  private fun createKeyCaptureField(): JBTextField {
    return object : JBTextField(), KeyboardAwareFocusOwner {
        init {
          enableEvents(AWTEvent.KEY_EVENT_MASK)
        }

        override fun skipKeyEventDispatcher(event: KeyEvent): Boolean = true

        override fun processKeyEvent(e: KeyEvent) {
          val modifierMask = KEY_CODE_TO_MODIFIER_MASK[e.keyCode]
          if (modifierMask != null) {
            if (e.id == KeyEvent.KEY_PRESSED) {
              setModifierSelected(modifierMask, true)
            } else if (e.id == KeyEvent.KEY_RELEASED) {
              setModifierSelected(modifierMask, false)
            }
          } else if (e.id == KeyEvent.KEY_PRESSED) {
            val domKeyName = VK_TO_DOM_KEY_NAME[e.keyCode]
            if (!domKeyName.isNullOrEmpty()) {
              capturedKeyCode = e.keyCode
              capturedKeyName = domKeyName
              updateKeyCaptureText()
              updateSendButton()
            }
          }
          e.consume()
        }
      }
      .apply {
        name = "keyCaptureField"
        columns = 8
        focusTraversalKeysEnabled = false
        enableInputMethods(false)
        transferHandler = null
        (document as? AbstractDocument)?.documentFilter =
          object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
              if (isUpdatingKeyCaptureText) {
                super.insertString(fb, offset, string, attr)
              }
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
              if (isUpdatingKeyCaptureText) {
                super.replace(fb, offset, length, text, attrs)
              }
            }

            override fun remove(fb: FilterBypass, offset: Int, length: Int) {
              if (isUpdatingKeyCaptureText) {
                super.remove(fb, offset, length)
              }
            }
          }
        emptyText.text = message("keyboard.dialog.key.placeholder")
        accessibleContext.accessibleName = message("keyboard.dialog.accessible.key.capture")
        caret =
          object : DefaultCaret() {
            override fun isVisible(): Boolean = false
          }
      }
  }

  private fun updateKeyCaptureText() {
    if (!::keyCaptureField.isInitialized) {
      return
    }
    isUpdatingKeyCaptureText = true
    try {
      keyCaptureField.text =
        when (capturedKeyCode) {
          KeyEvent.VK_UNDEFINED -> ""
          in KeyEvent.VK_A..KeyEvent.VK_Z -> {
            val baseChar = if (isShiftSelected()) 'A' else 'a'
            (baseChar + (capturedKeyCode - KeyEvent.VK_A)).toString()
          }
          else -> KeyEvent.getKeyText(capturedKeyCode)
        }
      keyCaptureField.caretPosition = 0
    } finally {
      isUpdatingKeyCaptureText = false
    }
  }

  private fun isShiftSelected(): Boolean = modifierButtons.any { (button, mask) -> mask == SHIFT_DOWN_MASK && button.isSelected }

  private fun setModifierSelected(mask: Int, selected: Boolean) {
    for ((button, buttonMask) in modifierButtons) {
      if (buttonMask == mask) {
        button.isSelected = selected
      }
    }
  }

  private fun sendKeyCombination() {
    val keyName = capturedKeyName ?: return
    var modifiers = 0
    for ((button, mask) in modifierButtons) {
      if (button.isSelected) {
        modifiers = modifiers or mask
        button.isSelected = false
      }
    }
    capturedKeyCode = KeyEvent.VK_UNDEFINED
    capturedKeyName = null
    updateKeyCaptureText()
    updateSendButton()
    emulator.sendKeyStroke(EmulatorKeyStroke(keyName, modifiers))
  }

  private fun updateSendButton() {
    if (::sendButton.isInitialized) {
      sendButton.isEnabled = capturedKeyName != null
    }
  }

  private fun sendEvdevKeyEvent(evdevCode: Int, eventType: KeyEventType) {
    val keyboardEvent = KeyboardEvent.newBuilder().setCodeType(KeyCodeType.Evdev).setEventType(eventType).setKeyCode(evdevCode)
    val inputEvent = InputEvent.newBuilder().setKeyEvent(keyboardEvent).build()
    emulator.getOrCreateInputEventSender().onNext(inputEvent)
  }

  private fun releaseAllPressedKeys() {
    for (evdevCode in pressedKeys.toList()) {
      sendEvdevKeyEvent(evdevCode, KeyEventType.keyup)
    }
    pressedKeys.clear()
  }

  private data class KeySpec(
    val componentName: String,
    val tooltipKey: String,
    val icon: Icon,
    val evdevCode: Int,
  )

  private data class ModifierSpec(
    val componentName: String,
    val label: String,
    val mask: Int,
  )

  companion object {
    private val KEY_CODE_TO_MODIFIER_MASK =
      mapOf(
        KeyEvent.VK_CONTROL to CTRL_DOWN_MASK,
        KeyEvent.VK_ALT to ALT_DOWN_MASK,
        KeyEvent.VK_ALT_GRAPH to ALT_DOWN_MASK,
        KeyEvent.VK_SHIFT to SHIFT_DOWN_MASK,
        KeyEvent.VK_META to META_DOWN_MASK,
        KeyEvent.VK_WINDOWS to META_DOWN_MASK,
      )

    // Linux input event codes from include/uapi/linux/input-event-codes.h.
    private const val LINUX_KEY_SYSRQ = 99
    private const val LINUX_KEY_MUTE = 113
    private const val LINUX_KEY_SCREENLOCK = 152
    private const val LINUX_KEY_FORWARD = 159
    private const val LINUX_KEY_NEXTSONG = 163
    private const val LINUX_KEY_PLAYPAUSE = 164
    private const val LINUX_KEY_PREVIOUSSONG = 165
    private const val LINUX_KEY_REFRESH = 173
    private const val LINUX_KEY_BRIGHTNESSDOWN = 224
    private const val LINUX_KEY_BRIGHTNESSUP = 225
    private const val LINUX_KEY_MICMUTE = 248
    private const val LINUX_KEY_FULL_SCREEN = 372

    private val ACTION_KEYS =
      listOf(
        KeySpec("forwardButton", "keyboard.dialog.key.forward", StagingIcons.KEY_FORWARD, LINUX_KEY_FORWARD),
        KeySpec("refreshButton", "keyboard.dialog.key.refresh", StagingIcons.KEY_REFRESH, LINUX_KEY_REFRESH),
        KeySpec("fullScreenButton", "keyboard.dialog.key.full.screen", StagingIcons.KEY_FULL_SCREEN, LINUX_KEY_FULL_SCREEN),
        KeySpec("screenCaptureButton", "keyboard.dialog.key.screen.capture", StagingIcons.KEY_SCREEN_CAPTURE, LINUX_KEY_SYSRQ),
        KeySpec("brightnessDownButton", "keyboard.dialog.key.brightness.down", StagingIcons.KEY_BRIGHTNESS_DOWN, LINUX_KEY_BRIGHTNESSDOWN),
        KeySpec("brightnessUpButton", "keyboard.dialog.key.brightness.up", StagingIcons.KEY_BRIGHTNESS_UP, LINUX_KEY_BRIGHTNESSUP),
        KeySpec("mediaPreviousButton", "keyboard.dialog.key.media.previous", StagingIcons.KEY_MEDIA_PREVIOUS, LINUX_KEY_PREVIOUSSONG),
        KeySpec("mediaPlayPauseButton", "keyboard.dialog.key.play.pause", StagingIcons.KEY_PLAY_PAUSE, LINUX_KEY_PLAYPAUSE),
        KeySpec("mediaNextButton", "keyboard.dialog.key.media.next", StagingIcons.KEY_MEDIA_NEXT, LINUX_KEY_NEXTSONG),
        KeySpec("micMuteButton", "keyboard.dialog.key.mic.mute", StagingIcons.KEY_MIC_MUTE, LINUX_KEY_MICMUTE),
        KeySpec("volumeMuteButton", "keyboard.dialog.key.volume.mute", StagingIcons.KEY_VOLUME_MUTE, LINUX_KEY_MUTE),
        KeySpec("screenLockButton", "keyboard.dialog.key.lock", StagingIcons.KEY_LOCK, LINUX_KEY_SCREENLOCK),
      )
  }
}
