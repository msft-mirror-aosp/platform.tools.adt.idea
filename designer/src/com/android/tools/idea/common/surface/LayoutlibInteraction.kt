/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.common.surface

import com.android.ide.common.rendering.api.RenderSession
import com.android.tools.idea.common.model.Coordinates
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import java.awt.Cursor

/**
 * An implementation of [Interaction] that forwards mouse and keyboard interaction events from the
 * Swing EDT to LayoutLib asynchronously via [LayoutlibSceneManager].
 */
class LayoutlibInteraction(private val sceneView: SceneView) : Interaction {
  /**
   * True when this interaction was started by a pointer press (`MousePressedEvent`) and should only
   * be finished by a pointer release or cancellation rather than a keyboard release.
   */
  var isPointerInteraction: Boolean = false
    private set

  override fun commit(event: InteractionEvent) {
    when (event) {
      is MouseReleasedEvent -> {
        isPointerInteraction = false
        val androidX = Coordinates.getAndroidX(sceneView, event.eventObject.x)
        val androidY = Coordinates.getAndroidY(sceneView, event.eventObject.y)
        (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerTouchEventAsync(
          RenderSession.TouchEventType.RELEASE,
          androidX,
          androidY,
        )
        sceneView.surface.repaint()
      }
      is KeyReleasedEvent -> {
        (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerKeyEventAsync(event.eventObject)
      }
      else -> {}
    }
  }

  override fun begin(event: InteractionEvent) {
    when (event) {
      is MousePressedEvent -> {
        isPointerInteraction = true
        val androidX = Coordinates.getAndroidX(sceneView, event.eventObject.x)
        val androidY = Coordinates.getAndroidY(sceneView, event.eventObject.y)
        (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerTouchEventAsync(
          RenderSession.TouchEventType.PRESS,
          androidX,
          androidY,
        )
      }
      is KeyPressedEvent -> (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerKeyEventAsync(event.eventObject)
      else -> {}
    }
  }

  override fun cancel(event: InteractionEvent) {
    isPointerInteraction = false
    sceneView.scene.mouseCancel()
    sceneView.surface.repaint()
  }

  override fun getCursor(): Cursor? = sceneView.scene.mouseCursor

  override fun update(event: InteractionEvent) {
    when (event) {
      is MouseDraggedEvent -> {
        val mouseX = event.eventObject.x
        val mouseY = event.eventObject.y
        sceneView.context.setMouseLocation(mouseX, mouseY)
        val androidX = Coordinates.getAndroidX(sceneView, mouseX)
        val androidY = Coordinates.getAndroidY(sceneView, mouseY)
        (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerTouchEventAsync(
          RenderSession.TouchEventType.DRAG,
          androidX,
          androidY,
        )
        sceneView.surface.repaint()
      }
      is KeyPressedEvent -> (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerKeyEventAsync(event.eventObject)
      is KeyReleasedEvent -> (sceneView.sceneManager as? LayoutlibSceneManager)?.triggerKeyEventAsync(event.eventObject)
      else -> {}
    }
  }
}
