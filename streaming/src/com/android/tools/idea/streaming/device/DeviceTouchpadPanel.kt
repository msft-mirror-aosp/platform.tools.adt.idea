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

  private val activePointers = arrayOfNulls<MotionEventMessage.Pointer>(touches.size)
  private var lastDeviceController: DeviceController? = null

  override fun sendTouches(touches: List<Touch>) {
    val deviceController = deviceClient.deviceController
    if (deviceController !== lastDeviceController) {
      lastDeviceController = deviceController
      activePointers.fill(null)
    }
    if (deviceController == null) {
      return
    }
    val downPointers = touches.mapNotNull { if (it.pressure > 0) it.toPointer() else null }

    if (downPointers.isEmpty()) {
      val pointers = touches.filter { activePointers[it.id] != null }.map { it.toPointer() }
      activePointers.fill(null)
      if (pointers.isNotEmpty()) {
        deviceController.sendMotionEvent(pointers, MotionEventMessage.ACTION_UP)
      }
      return
    }

    if (activePointers.all { it == null }) {
      for (pointer in downPointers) {
        activePointers[pointer.pointerId] = pointer
      }
      deviceController.sendMotionEvent(downPointers, MotionEventMessage.ACTION_DOWN)
      return
    }

    for (touch in touches) {
      if (touch.pressure == 0 && activePointers[touch.id] != null) {
        val pointer = touch.toPointer()
        activePointers[touch.id] = pointer
        val pointers = activePointers.filterNotNull()
        val pointerIndex = pointers.indexOf(pointer)
        deviceController.sendMotionEvent(
          pointers,
          MotionEventMessage.ACTION_POINTER_UP or (pointerIndex shl MotionEventMessage.ACTION_POINTER_INDEX_SHIFT),
        )
        activePointers[touch.id] = null
      }
    }

    for (pointer in downPointers) {
      if (activePointers[pointer.pointerId] == null) {
        activePointers[pointer.pointerId] = pointer
        val pointers = activePointers.filterNotNull()
        val pointerIndex = pointers.indexOf(pointer)
        deviceController.sendMotionEvent(
          pointers,
          MotionEventMessage.ACTION_POINTER_DOWN or (pointerIndex shl MotionEventMessage.ACTION_POINTER_INDEX_SHIFT),
        )
      }
    }

    var moved = false
    for (pointer in downPointers) {
      if (pointer != activePointers[pointer.pointerId]) {
        activePointers[pointer.pointerId] = pointer
        moved = true
      }
    }

    if (moved) {
      deviceController.sendMotionEvent(activePointers.filterNotNull(), MotionEventMessage.ACTION_MOVE)
    }
  }

  private fun DeviceController.sendMotionEvent(pointers: List<MotionEventMessage.Pointer>, action: Int) {
    sendControlMessage(
      MotionEventMessage(pointers = pointers, action = action, buttonState = 0, actionButton = 0, displayId = displayId, isMouse = false)
    )
  }

  private fun Touch.toPointer(): MotionEventMessage.Pointer {
    return MotionEventMessage.Pointer(
      x.scaledUnbiased(NOMINAL_TOUCHPAD_SIZE.width, displaySize.width),
      // Glasses projection expects Y coordinates pointing up from the bottom of the touchpad.
      (NOMINAL_TOUCHPAD_SIZE.height - 1 - y).scaledUnbiased(NOMINAL_TOUCHPAD_SIZE.height, displaySize.height),
      id,
    )
  }

  companion object {
    private val NOMINAL_TOUCHPAD_SIZE = Dimension(1542, 297)
  }
}
