/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.view.MotionEvent
import com.android.tools.adtui.ZoomController
import com.android.tools.adtui.actions.ZoomType
import com.android.tools.idea.DesignSurfaceTestUtil.createZoomControllerFake
import com.android.tools.idea.common.TestPannable
import com.android.tools.idea.common.fixtures.KeyEventBuilder
import com.android.tools.idea.common.scene.Scene
import com.android.tools.idea.common.scene.SceneManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.TestSceneView
import com.android.tools.idea.uibuilder.surface.interaction.PanInteraction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.assertInstanceOf
import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

private class TestInteractableSurface(private val sceneView: SceneView? = null) : InteractableScenesSurface {
  var zoomCounter = 0
  var hoverCounter = 0

  override fun onHover(x: Int, y: Int) {
    hoverCounter++
  }

  override val zoomController: ZoomController
    get() = createZoomControllerFake { zoomCounter++ }

  override fun getSceneViewAtOrPrimary(x: Int, y: Int) = sceneView

  override val scene: Scene? = null
  override val focusedSceneView = sceneView

  override fun getSceneViewAt(x: Int, y: Int): SceneView? = if (sceneView != null && x in 0..100 && y in 0..100) sceneView else null
}

class LayoutlibInteractionHandlerTest {

  @Test
  fun testStartPanningWhenPressingSpace() {
    val handler = LayoutlibInteractionHandler(TestInteractableSurface(), TestPannable())
    val spaceKeyEvent = KeyEventBuilder(DesignSurfaceShortcut.PAN.keyCode, DesignSurfaceShortcut.PAN.keyCode.toChar()).build()
    val interaction = handler.keyPressedWithoutInteraction(spaceKeyEvent)
    assertInstanceOf<PanInteraction>(interaction)
  }

  @Test
  fun testNoInteractionWhenPressingNonSpaceKeyAndNoSceneView() {
    val handler = LayoutlibInteractionHandler(TestInteractableSurface(), TestPannable())
    val aKeyEvent = KeyEventBuilder(KeyEvent.VK_A, 'a').build()
    assertNull(handler.keyPressedWithoutInteraction(aKeyEvent))
  }

  @Test
  fun testLayoutlibInteractionWhenPressingNonSpaceKeyAndSceneViewExists() {
    val sceneManager = Mockito.mock(SceneManager::class.java)
    val handler =
      LayoutlibInteractionHandler(
        TestInteractableSurface(TestSceneView(100, 100, sceneManager)),
        TestPannable(),
      )
    val aKeyEvent = KeyEventBuilder(KeyEvent.VK_A, 'a').build()
    val interaction = handler.keyPressedWithoutInteraction(aKeyEvent)
    assertInstanceOf<LayoutlibInteraction>(interaction)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun testLayoutlibInteractionWhenMousePressed() {
    val sceneManager = Mockito.mock(SceneManager::class.java)
    val handler =
      LayoutlibInteractionHandler(
        TestInteractableSurface(TestSceneView(100, 100, sceneManager)),
        TestPannable(),
      )
    val interaction = handler.createInteractionOnPressed(10, 10, 0)
    assertInstanceOf<LayoutlibInteraction>(interaction)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun testHoverIsPassedThrough() {
    val surface = TestInteractableSurface()
    val handler = LayoutlibInteractionHandler(surface, TestPannable())
    assertEquals(0, surface.hoverCounter)
    handler.stayHovering(10, 10)
    assertEquals(1, surface.hoverCounter)
  }

  @Test
  fun testHoverDispatchesEnterMoveAndExitEvents() {
    val sceneManager = Mockito.mock(LayoutlibSceneManager::class.java)
    Mockito.`when`(sceneManager.sceneScalingFactor).thenReturn(1.0f)
    try {
      val sceneView = TestSceneView(100, 100, sceneManager)
      val surface = TestInteractableSurface(sceneView)
      val handler = LayoutlibInteractionHandler(surface, TestPannable())

      // Entering the SceneView triggers ACTION_HOVER_ENTER
      handler.hoverWhenNoInteraction(20, 30, 0)
      Mockito.verify(sceneManager).triggerHoverEventAsync(MotionEvent.ACTION_HOVER_ENTER, 20, 30)

      // Moving inside the same SceneView triggers ACTION_HOVER_MOVE
      handler.hoverWhenNoInteraction(25, 35, 0)
      Mockito.verify(sceneManager).triggerHoverEventAsync(MotionEvent.ACTION_HOVER_MOVE, 25, 35)

      // Exiting the surface triggers ACTION_HOVER_EXIT
      handler.mouseExited()
      Mockito.verify(sceneManager).triggerHoverEventAsync(MotionEvent.ACTION_HOVER_EXIT, -1, -1)
    } finally {
      Disposer.dispose(sceneManager)
    }
  }

  @Test
  fun testKeyReleaseFinishesKeyInteractionWithoutSyntheticHoverAndPreservesPointerDrag() {
    val sceneManager = Mockito.mock(LayoutlibSceneManager::class.java)
    Mockito.`when`(sceneManager.sceneScalingFactor).thenReturn(1.0f)
    try {
      val sceneView = TestSceneView(100, 100, sceneManager)
      val surface = TestInteractableSurface(sceneView)
      val handler = LayoutlibInteractionHandler(surface, TestPannable())
      val sourceComponent = javax.swing.JPanel()
      val tabPress = KeyEventBuilder(KeyEvent.VK_TAB, '\t').build()
      val tabRelease =
        java.awt.event.KeyEvent(
          sourceComponent,
          KeyEvent.KEY_RELEASED,
          tabPress.`when`,
          tabPress.modifiersEx,
          KeyEvent.VK_TAB,
          '\t',
        )

      // Key-initiated interaction is not a pointer interaction
      val keyInteraction = handler.keyPressedWithoutInteraction(tabPress) as LayoutlibInteraction
      keyInteraction.begin(KeyPressedEvent(tabPress, InteractionInformation(20, 30, 0)))
      assertEquals(false, keyInteraction.isPointerInteraction)
      keyInteraction.commit(KeyReleasedEvent(tabRelease, InteractionInformation(20, 30, 0)))
      Mockito.verify(sceneManager, Mockito.never())
        .triggerHoverEventAsync(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())

      // Pointer-initiated interaction sets isPointerInteraction = true and survives modifier key
      // release via update()
      val pointerInteraction = handler.createInteractionOnPressed(20, 30, 0) as LayoutlibInteraction
      val pressEvent =
        java.awt.event.MouseEvent(
          sourceComponent,
          java.awt.event.MouseEvent.MOUSE_PRESSED,
          tabPress.`when`,
          0,
          20,
          30,
          1,
          false,
        )
      pointerInteraction.begin(MousePressedEvent(pressEvent, InteractionInformation(20, 30, 0)))
      assertTrue(pointerInteraction.isPointerInteraction)
      pointerInteraction.update(KeyReleasedEvent(tabRelease, InteractionInformation(20, 30, 0)))
      assertTrue(pointerInteraction.isPointerInteraction)
    } finally {
      Disposer.dispose(sceneManager)
    }
  }

  @Test
  fun testZoomIsPassedThrough() {
    val surface = TestInteractableSurface()
    val handler = LayoutlibInteractionHandler(surface, TestPannable())
    assertEquals(0, surface.zoomCounter)
    handler.zoom(ZoomType.ACTUAL, 10, 10)
    assertEquals(1, surface.zoomCounter)
  }

  @Test
  fun testNoMousePressedInteractionWhenBackGestureInProgress() {
    val sceneManager = Mockito.mock(SceneManager::class.java)
    var interactionStartCalled = false

    // Create a LayoutlibInteractionHandler with isBackGestureInProgress = true
    // and a mock onInteractionStart callback to detect cancellation.
    val handler =
      LayoutlibInteractionHandler(
        TestInteractableSurface(TestSceneView(100, 100, sceneManager)),
        TestPannable(),
        isBackGestureInProgress = { true },
        onInteractionStart = { interactionStartCalled = true },
      )

    // Trigger mouse-press interaction.
    val interaction = handler.createInteractionOnPressed(10, 10, 0)

    // Verify that the interaction is blocked (returns null) and the cancellation callback was
    // invoked.
    assertNull(interaction)
    assertTrue(interactionStartCalled)

    Disposer.dispose(sceneManager)
  }

  @Test
  fun testNoKeyPressedInteractionWhenBackGestureInProgress() {
    val sceneManager = Mockito.mock(SceneManager::class.java)
    var interactionStartCalled = false

    // Create a LayoutlibInteractionHandler with isBackGestureInProgress = true
    // and a mock onInteractionStart callback to detect cancellation.
    val handler =
      LayoutlibInteractionHandler(
        TestInteractableSurface(TestSceneView(100, 100, sceneManager)),
        TestPannable(),
        isBackGestureInProgress = { true },
        onInteractionStart = { interactionStartCalled = true },
      )

    // Construct a simulated physical keypress event.
    val aKeyEvent = KeyEventBuilder(KeyEvent.VK_A, 'a').build()

    // Trigger key-press interaction.
    val interaction = handler.keyPressedWithoutInteraction(aKeyEvent)

    // Verify that the interaction is blocked (returns null) and the cancellation callback was
    // invoked.
    assertNull(interaction)
    assertTrue(interactionStartCalled)

    Disposer.dispose(sceneManager)
  }
}
