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

import com.android.emulator.control.AndroidEvent
import com.android.emulator.control.InputEvent
import com.android.emulator.control.KeyboardEvent
import com.android.emulator.control.KeyboardEvent.KeyCodeType
import com.android.emulator.control.KeyboardEvent.KeyEventType
import com.android.emulator.control.MicrophoneState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.tools.idea.streaming.emulator.NotificationTracker
import com.android.utils.throwIfCancellation
import com.intellij.ide.setToolTipText
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog for configuring virtual headset and microphone settings of an emulator. */
internal class MicrophoneDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("microphone.dialog.title", emulator.emulatorId.avdName)) {

  private val headsetState: HeadsetState =
    emulator.getUserData(HEADSET_STATE_KEY) ?: HeadsetState().also { emulator.putUserData(HEADSET_STATE_KEY, it) }

  private var hostAudioInput: Boolean = NotificationTracker.forEmulator(emulator).microphoneInput.value ?: false
  private var voiceAssistPressed: Boolean = false
  private var microphoneStateVersion: Long = 0
  private var appliedMicrophoneStateVersion: Long = 0

  private lateinit var microphoneAttachedCheckBox: JBCheckBox
  private lateinit var hostAudioInputCheckBox: JBCheckBox

  private val microphoneStateChannel = Channel<Pair<Boolean, Long>>(Channel.CONFLATED)

  init {
    val notificationJob = coroutineScope.launch {
      NotificationTracker.forEmulator(emulator).microphoneInput.collect { enabled ->
        if (enabled != null) {
          withContext(uiContext) {
            if (appliedMicrophoneStateVersion == microphoneStateVersion) {
              applyHostAudioInput(enabled)
            }
          }
        }
      }
    }

    val updateJob = coroutineScope.launch {
      for ((enabled, version) in microphoneStateChannel) {
        var failed = false
        try {
          val state = MicrophoneState.newBuilder().setRealAudioEnabled(enabled).build()
          emulator.setMicrophoneState(state)
        } catch (t: Throwable) {
          t.throwIfCancellation()
          // Error is already logged by EmulatorController.
          failed = true
        }
        withContext(uiContext) {
          if (version == microphoneStateVersion) {
            appliedMicrophoneStateVersion = version
            if (failed) {
              NotificationTracker.forEmulator(emulator).microphoneInput.value?.let {
                applyHostAudioInput(it)
              }
            }
          }
        }
      }
    }

    onDispose {
      if (emulator.connectionState == ConnectionState.CONNECTED) {
        onVoiceAssistPressed(false)
      }
      notificationJob.cancel()
      updateJob.cancel()
      microphoneStateChannel.close()
    }
  }

  override fun createPanel(): DialogPanel {
    return panel {
      row {
        checkBox(message("microphone.dialog.checkbox.headset.inserted")).applyToComponent {
          name = "headsetInsertedCheckBox"
          isSelected = headsetState.headsetInserted
          addActionListener {
            onHeadsetInsertedToggled(isSelected)
          }
        }
      }
      row {
        microphoneAttachedCheckBox =
          checkBox(message("microphone.dialog.checkbox.microphone.attached"))
            .applyToComponent {
              name = "microphoneAttachedCheckBox"
              isSelected = headsetState.microphoneAttached
              isEnabled = headsetState.headsetInserted
              addActionListener {
                onMicrophoneAttachedToggled(isSelected)
              }
            }
            .component
      }
      row {
        hostAudioInputCheckBox =
          checkBox(message("microphone.dialog.checkbox.host.audio.input"))
            .applyToComponent {
              name = "hostAudioInputCheckBox"
              isSelected = hostAudioInput
              addActionListener {
                onHostAudioInputToggled(isSelected)
              }
            }
            .component
      }
      if (emulator.emulatorConfig.deviceType != DeviceType.AUTOMOTIVE && emulator.emulatorConfig.api >= 24) {
        row {
            button(message("microphone.dialog.button.voice.assist")) {}
              .applyToComponent {
                name = "voiceAssistButton"
                setToolTipText(HtmlChunk.text(message("microphone.dialog.tooltip.voice.assist")))
                model.addChangeListener {
                  onVoiceAssistPressed(model.isPressed)
                }
              }
          }
          .topGap(TopGap.SMALL)
      }
    }
  }

  private fun applyHostAudioInput(enabled: Boolean) {
    hostAudioInput = enabled
    if (::hostAudioInputCheckBox.isInitialized) {
      hostAudioInputCheckBox.isSelected = enabled
    }
  }

  private fun onHeadsetInsertedToggled(inserted: Boolean) {
    if (headsetState.headsetInserted != inserted) {
      headsetState.headsetInserted = inserted
      microphoneAttachedCheckBox.isEnabled = inserted
      val phonesInserted = if (inserted) 1 else 0
      val micInserted = if (inserted && headsetState.microphoneAttached) 1 else 0
      sendAndroidEvent(EV_SW, SW_HEADPHONE_INSERT, phonesInserted)
      sendAndroidEvent(EV_SW, SW_MICROPHONE_INSERT, micInserted)
      sendAndroidEvent(EV_SYN, 0, 0)
    }
  }

  private fun onMicrophoneAttachedToggled(attached: Boolean) {
    if (headsetState.microphoneAttached != attached) {
      headsetState.microphoneAttached = attached
      if (headsetState.headsetInserted) {
        sendAndroidEvent(EV_SW, SW_MICROPHONE_INSERT, if (attached) 1 else 0)
        sendAndroidEvent(EV_SYN, 0, 0)
      }
    }
  }

  private fun onHostAudioInputToggled(enabled: Boolean) {
    if (hostAudioInput != enabled) {
      hostAudioInput = enabled
      val version = ++microphoneStateVersion
      microphoneStateChannel.trySend(enabled to version)
    }
  }

  private fun onVoiceAssistPressed(pressed: Boolean) {
    if (voiceAssistPressed != pressed) {
      voiceAssistPressed = pressed
      val keyboardEvent =
        KeyboardEvent.newBuilder()
          .setCodeType(KeyCodeType.Evdev)
          .setEventType(if (pressed) KeyEventType.keydown else KeyEventType.keyup)
          .setKeyCode(ANDROID_KEY_ASSIST)
      val inputEvent = InputEvent.newBuilder().setKeyEvent(keyboardEvent).build()
      emulator.getOrCreateInputEventSender().onNext(inputEvent)
    }
  }

  private fun sendAndroidEvent(type: Int, code: Int, value: Int) {
    val androidEvent = AndroidEvent.newBuilder().setType(type).setCode(code).setValue(value)
    val inputEvent = InputEvent.newBuilder().setAndroidEvent(androidEvent).build()
    emulator.getOrCreateInputEventSender().onNext(inputEvent)
  }

  private class HeadsetState(var headsetInserted: Boolean = false, var microphoneAttached: Boolean = false)

  companion object {
    private val HEADSET_STATE_KEY = Key.create<HeadsetState>("com.android.tools.idea.streaming.emulator.dialogs.HeadsetState")
  }
}

// From include/linux/input-event-codes.h in kernel source.
private const val EV_SYN = 0x00
private const val EV_SW = 0x05
private const val SW_HEADPHONE_INSERT = 0x02
private const val SW_MICROPHONE_INSERT = 0x04

// KEYCODE_ASSIST in goldfish/input/qwerty.kl.
private const val ANDROID_KEY_ASSIST = 585
