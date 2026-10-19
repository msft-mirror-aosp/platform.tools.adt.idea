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
package com.android.tools.idea.streaming.device

import com.android.tools.idea.streaming.core.AbstractTouchpadPanel
import com.android.tools.idea.streaming.core.scaledUnbiased
import java.awt.Dimension

/** Represents the touchpad of a physical Android device, e.g. AI glasses. */
internal class DeviceTouchpadPanel(
  private val deviceClient: DeviceClient,
  private val displayId: Int,
  private val displaySize: Dimension,
) : AbstractTouchpadPanel(NOMINAL_TOUCHPAD_SIZE) {

  private var wasDown = false

  override fun sendTouches(touches: List<Touch>) {
    val deviceController = deviceClient.deviceController ?: return
    val activeTouches = touches.filter { it.pressure > 0 }
    val pointers: List<MotionEventMessage.Pointer>
    val action: Int
    val buttonState: Int
    val actionButton: Int

    if (activeTouches.isNotEmpty()) {
      pointers = activeTouches.map { it.toPointer() }
      if (!wasDown) {
        wasDown = true
        action = MotionEventMessage.ACTION_DOWN
        buttonState = MotionEventMessage.BUTTON_PRIMARY
        actionButton = MotionEventMessage.BUTTON_PRIMARY
      } else {
        action = MotionEventMessage.ACTION_MOVE
        buttonState = MotionEventMessage.BUTTON_PRIMARY
        actionButton = 0
      }
    } else {
      pointers = touches.map { it.toPointer() }
      wasDown = false
      action = MotionEventMessage.ACTION_UP
      buttonState = 0
      actionButton = MotionEventMessage.BUTTON_PRIMARY
    }

    val message =
      MotionEventMessage(
        pointers = pointers,
        action = action,
        buttonState = buttonState,
        actionButton = actionButton,
        displayId = displayId,
        isMouse = false,
      )
    deviceController.sendControlMessage(message)
  }

  private fun Touch.toPointer(): MotionEventMessage.Pointer {
    return MotionEventMessage.Pointer(
      x.scaledUnbiased(NOMINAL_TOUCHPAD_SIZE.width, displaySize.width),
      y.scaledUnbiased(NOMINAL_TOUCHPAD_SIZE.height, displaySize.height),
      id,
    )
  }

  companion object {
    private val NOMINAL_TOUCHPAD_SIZE = Dimension(1542, 297)
  }
}
