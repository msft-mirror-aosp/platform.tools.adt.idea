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

import com.android.emulator.control.Fingerprint
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Dialog for simulating fingerprint sensor touches on the emulator, similar to the Fingerprint page in extended controls. */
internal class FingerprintDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("fingerprint.dialog.title", emulator.emulatorId.avdName)) {

  private var finger: Finger = Finger.FINGER_1
  private var isTouching: Boolean = false

  private val fingerprintChannel =
    Channel<Fingerprint>(Channel.UNLIMITED).also { channel ->
      onDispose { channel.close() }
      coroutineScope.launch {
        for (fingerprint in channel) {
          try {
            emulator.sendFingerprint(fingerprint)
          } catch (t: Throwable) {
            t.throwIfCancellation()
            // Error is already logged by EmulatorController.
          }
        }
      }
    }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      row {
        enumComboBox(message("fingerprint.dialog.label.fingerprint"), ::finger, COMBO_BOX_WIDTH).resizableColumn().applyToComponent {
          maximumRowCount = Finger.entries.size
        }
        button(message("fingerprint.dialog.button.touch.sensor")) {}
          .applyToComponent {
            name = "touchSensorButton"
            model.addChangeListener {
              onTouchStateChanged(model.isPressed)
            }
          }
      }
    }
  }

  private fun onTouchStateChanged(pressed: Boolean) {
    if (isTouching != pressed) {
      isTouching = pressed
      val touchId = if (pressed) finger.touchId else 0
      val fingerprint = Fingerprint.newBuilder().setIsTouching(pressed).setTouchId(touchId).build()
      fingerprintChannel.trySend(fingerprint)
    }
  }

  companion object {
    private const val COMBO_BOX_WIDTH = 220
  }

  /** Predefined fingerprints supported by the emulator. */
  private enum class Finger(private val displayValue: String, val touchId: Int) {
    FINGER_1("Finger 1", 45146572),
    FINGER_2("Finger 2", 192618075),
    FINGER_3("Finger 3", 84807873),
    FINGER_4("Finger 4", 189675793),
    FINGER_5("Finger 5", 132710472),
    FINGER_6("Finger 6", 36321043),
    FINGER_7("Finger 7", 139425534),
    FINGER_8("Finger 8", 15301340),
    FINGER_9("Finger 9", 105702233),
    FINGER_10("Finger 10", 87754286);

    override fun toString(): String = displayValue
  }
}
