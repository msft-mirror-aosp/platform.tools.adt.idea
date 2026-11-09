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

import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.testing.AndroidExecutorsRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBBox
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.event.KeyEvent.VK_SHIFT
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import javax.swing.Box.createHorizontalGlue
import javax.swing.BoxLayout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val DISPLAY_ID = 48

/** Tests for [DeviceTouchpadPanel]. */
@RunsInEdt
class DeviceTouchpadPanelTest {

  private val agentRule = FakeScreenSharingAgentRule()
  private val androidExecutorsRule = AndroidExecutorsRule(workerThreadExecutor = Executors.newCachedThreadPool())

  @get:Rule
  val ruleChain =
    RuleChain(
      ApplicationRule(),
      ClipboardSynchronizationDisablementRule(),
      agentRule,
      androidExecutorsRule,
      EdtRule(),
      PortableUiFontRule(),
    )

  private val testRootDisposable
    get() = agentRule.disposable

  private val project
    get() = agentRule.project

  private lateinit var device: FakeScreenSharingAgentRule.FakeDevice
  private lateinit var deviceClient: DeviceClient
  private val agent
    get() = device.agent

  private val touchpadPanel by lazy { createTouchpadPanel() }
  private val ui: FakeUi by lazy { createFakeUi(touchpadPanel) }

  @Before
  fun setUp() {
    device = agentRule.connectDevice("Pixel 5", 32, Dimension(1080, 2340))
    deviceClient = DeviceClient(device.handle.id, device.serialNumber, device.configuration, device.deviceState.cpuAbi)
    Disposer.register(testRootDisposable, deviceClient)
    deviceClient.establishAgentConnectionWithoutVideoStreamAsync(project)
    waitForCondition(15, SECONDS) { agent.isRunning && deviceClient.deviceController != null }
  }

  @After
  fun tearDown() {
    BitRateManager.getInstance().clear()
  }

  @Test
  fun testSingleTouch() {
    ui.mouse.press(touchpadPanel.x + 10, touchpadPanel.y + 20)
    val downMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(downMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(21, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    ui.mouse.dragTo(touchpadPanel.x + 200, touchpadPanel.y + 25)
    val moveMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(moveMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(460, 186, 0)),
          action = MotionEventMessage.ACTION_MOVE,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Zero-delta drag should not emit a redundant ACTION_MOVE.
    ui.mouse.dragTo(touchpadPanel.x + 200, touchpadPanel.y + 25)

    ui.mouse.release()
    val upMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(upMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(460, 186, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Drag over the edge of the touchpad and back into the touchpad.
    ui.mouse.press(touchpadPanel.x + 200, touchpadPanel.y + 25)
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(460, 186, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    ui.mouse.dragTo(touchpadPanel.x + 210, touchpadPanel.y + 25)
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(479, 186, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    ui.mouse.dragTo(touchpadPanel.x + 205, touchpadPanel.y + 20)
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(472, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    ui.mouse.release()
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(472, 246, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
  }

  @Test
  fun testMultiTouch() {
    ui.mouse.press(touchpadPanel.x + 5, touchpadPanel.y + 20)
    val downMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(downMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(10, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Press Shift to trigger multi-touch mode.
    ui.keyboard.press(VK_SHIFT)
    ui.mouse.dragTo(touchpadPanel.x + 15, touchpadPanel.y + 20)
    val cancelDragMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(cancelDragMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(10, 246, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    val multiDownMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(multiDownMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(114, 246, 1)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Drag so that both fingers are inside the touchpad.
    ui.mouse.dragTo(touchpadPanel.x + 100, touchpadPanel.y + 20)
    val pointerDownMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(pointerDownMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(114, 246, 1)),
          action = MotionEventMessage.ACTION_POINTER_DOWN or (0 shl MotionEventMessage.ACTION_POINTER_INDEX_SHIFT),
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
    val multiMoveMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(multiMoveMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_MOVE,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Zero-delta drag should not emit a redundant ACTION_MOVE.
    ui.mouse.dragTo(touchpadPanel.x + 100, touchpadPanel.y + 20)

    // Terminate dragging.
    ui.mouse.release()
    val multiUpMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(multiUpMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Start dragging again.
    ui.mouse.press(touchpadPanel.x + 100, touchpadPanel.y + 20)
    val startDragAgainMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(startDragAgainMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Drag closer to the right edge of the touchpad so that one of the fingers leaves the touchpad.
    ui.mouse.dragTo(touchpadPanel.x + 190, touchpadPanel.y + 20)
    val pointerUpMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(pointerUpMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(479, 246, 1)),
          action = MotionEventMessage.ACTION_POINTER_UP or (1 shl MotionEventMessage.ACTION_POINTER_INDEX_SHIFT),
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
    val oneFingerLeavesMoveMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(oneFingerLeavesMoveMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(356, 246, 0)),
          action = MotionEventMessage.ACTION_MOVE,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Drag back so that both fingers are inside the touchpad again.
    ui.mouse.dragTo(touchpadPanel.x + 100, touchpadPanel.y + 20)
    val secondPointerDownMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(secondPointerDownMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(356, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_POINTER_DOWN or (1 shl MotionEventMessage.ACTION_POINTER_INDEX_SHIFT),
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
    val secondFingerEntersMoveMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(secondFingerEntersMoveMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_MOVE,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Terminate multi-touch mode.
    ui.keyboard.release(VK_SHIFT)
    ui.mouse.dragTo(touchpadPanel.x + 200, touchpadPanel.y + 20)
    val cancelMultiDragMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(cancelMultiDragMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(148, 246, 0), MotionEventMessage.Pointer(310, 246, 1)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
    val singleDragMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(singleDragMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(460, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    // Terminate dragging.
    ui.mouse.release()
    val finalUpMessage = agent.getNextControlMessage(2.seconds) as MotionEventMessage
    assertThat(finalUpMessage)
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(460, 246, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
  }

  @Test
  fun testDisconnectionDuringDrag() {
    ui.mouse.press(touchpadPanel.x + 10, touchpadPanel.y + 20)
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(21, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    runBlocking { agent.crash() }
    waitForCondition(15, SECONDS) { !agent.isRunning && deviceClient.deviceController == null }

    // Release while disconnected.
    ui.mouse.release()

    // Reconnect and verify a new gesture starts with ACTION_DOWN.
    deviceClient.establishAgentConnectionWithoutVideoStreamAsync(project)
    waitForCondition(15, SECONDS) { agent.isRunning && deviceClient.deviceController != null }

    ui.mouse.press(touchpadPanel.x + 10, touchpadPanel.y + 20)
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(21, 246, 0)),
          action = MotionEventMessage.ACTION_DOWN,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )

    ui.mouse.release()
    assertThat(agent.getNextControlMessage(2.seconds))
      .isEqualTo(
        MotionEventMessage(
          pointers = listOf(MotionEventMessage.Pointer(21, 246, 0)),
          action = MotionEventMessage.ACTION_UP,
          buttonState = 0,
          actionButton = 0,
          displayId = DISPLAY_ID,
          isMouse = false,
        )
      )
  }

  private fun createTouchpadPanel(): DeviceTouchpadPanel = DeviceTouchpadPanel(deviceClient, DISPLAY_ID, Dimension(480, 480))

  private fun createFakeUi(touchpadPanel: DeviceTouchpadPanel): FakeUi {
    val container =
      JBBox(BoxLayout.X_AXIS).apply {
        isOpaque = true
        background = Color(0xF2F2F2)
        border = JBUI.Borders.empty(5, 10)
        add(touchpadPanel)
        add(createHorizontalGlue())
      }
    val ui = FakeUi(container)
    ui.root.size = Dimension(300, 80)
    ui.layoutAndDispatchEvents()
    return ui
  }
}
