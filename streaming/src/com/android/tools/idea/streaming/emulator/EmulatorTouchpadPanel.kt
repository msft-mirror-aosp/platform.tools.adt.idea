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

import com.android.emulator.control.InputEvent as InputEventMessage
import com.android.emulator.control.Touch as ProtoTouch
import com.android.emulator.control.Touch.EventExpiration.NEVER_EXPIRE
import com.android.tools.idea.streaming.core.AbstractTouchpadPanel
import java.awt.Dimension

/** Represents the touchpad of an AVD, e.g. AI glasses. */
internal class EmulatorTouchpadPanel(
  private val emulator: EmulatorController,
  touchpadSize: Dimension,
) : AbstractTouchpadPanel(touchpadSize) {

  private val protoTouches = Array(2) { id -> ProtoTouch.newBuilder().setIdentifier(id).setExpiration(NEVER_EXPIRE) }
  private val inputEvent = InputEventMessage.newBuilder()
  private val touchpadEvent = inputEvent.touchpadEventBuilder
  private var lastSentInputEvent: InputEventMessage? = null

  override fun sendTouches(touches: List<Touch>) {
    touchpadEvent.clearTouches()
    for (touch in touches) {
      val protoTouch = protoTouches[touch.id]
      protoTouch.x = touch.x
      protoTouch.y = touch.y
      protoTouch.pressure = touch.pressure
      touchpadEvent.addTouches(protoTouch)
    }
    if (touchpadEvent.touchesCount > 0) {
      val inputEvent = inputEvent.build()
      if (inputEvent != lastSentInputEvent) {
        emulator.getOrCreateInputEventSender().onNext(inputEvent)
        lastSentInputEvent = inputEvent
      }
    }
  }
}
