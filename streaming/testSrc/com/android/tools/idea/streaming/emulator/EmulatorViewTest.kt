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
package com.android.tools.idea.streaming.emulator

import com.android.emulator.control.Environment
import com.android.emulator.control.Posture.PostureValue
import com.android.mockito.kotlin.whenever
import com.android.testutils.GoldenImageRule
import com.android.testutils.ImageDiffUtil
import com.android.testutils.waitForCondition
import com.android.tools.adtui.ImageUtils
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.common.AdtUiCursorType
import com.android.tools.adtui.common.AdtUiCursorsProvider
import com.android.tools.adtui.common.TestAdtUiCursorsProvider
import com.android.tools.adtui.common.replaceAdtUiCursorWithPredefinedCursor
import com.android.tools.adtui.swing.FakeKeyboardFocusManager
import com.android.tools.adtui.swing.FakeMouse
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessRootPaneContainer
import com.android.tools.adtui.swing.HiDpiRule
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.popup.ActionPopupMenuRule
import com.android.tools.adtui.swing.replaceKeyboardFocusManager
import com.android.tools.adtui.ui.NotificationHolderPanel
import com.android.tools.analytics.UsageTrackerRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.EmulatorSettings
import com.android.tools.idea.streaming.core.AndroidInputEvent
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.DeviceInputListener
import com.android.tools.idea.streaming.core.DeviceInputListenerManager
import com.android.tools.idea.streaming.core.ZoomType
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.tools.idea.streaming.emulator.FakeEmulator.Companion.IGNORE_SCREENSHOT_CALL_FILTER
import com.android.tools.idea.streaming.emulator.FakeEmulator.GrpcCallRecord
import com.android.tools.idea.streaming.emulator.actions.EnvironmentTracker
import com.android.tools.idea.streaming.emulator.xr.EmulatorXrInputController
import com.android.tools.idea.streaming.testutil.newEmulatorDisplayPanel
import com.android.tools.idea.streaming.xr.TRANSLATION_STEP_SIZE
import com.android.tools.idea.streaming.xr.XrInputMode
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.mockStatic
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions.ACTION_COPY
import com.intellij.openapi.actionSystem.IdeActions.ACTION_CUT
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_DOWN_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_UP_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_CARET_UP_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_LINE_END_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_MOVE_LINE_START_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_NEXT_WORD
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_NEXT_WORD_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_PREVIOUS_WORD
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_PREVIOUS_WORD_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_TEXT_END
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_TEXT_END_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_TEXT_START
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_TEXT_START_WITH_SELECTION
import com.intellij.openapi.actionSystem.IdeActions.ACTION_PASTE
import com.intellij.openapi.actionSystem.IdeActions.ACTION_REDO
import com.intellij.openapi.actionSystem.IdeActions.ACTION_SELECT_ALL
import com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Cursor
import java.awt.DefaultKeyboardFocusManager
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.PointerInfo
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent.ALT_DOWN_MASK
import java.awt.event.InputEvent.BUTTON1_DOWN_MASK
import java.awt.event.InputEvent.BUTTON2_DOWN_MASK
import java.awt.event.InputEvent.BUTTON3_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.CHAR_UNDEFINED
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.KEY_RELEASED
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_BACK_SPACE
import java.awt.event.KeyEvent.VK_CONTROL
import java.awt.event.KeyEvent.VK_D
import java.awt.event.KeyEvent.VK_DELETE
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_E
import java.awt.event.KeyEvent.VK_END
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_HOME
import java.awt.event.KeyEvent.VK_J
import java.awt.event.KeyEvent.VK_KP_DOWN
import java.awt.event.KeyEvent.VK_KP_LEFT
import java.awt.event.KeyEvent.VK_KP_RIGHT
import java.awt.event.KeyEvent.VK_KP_UP
import java.awt.event.KeyEvent.VK_LEFT
import java.awt.event.KeyEvent.VK_M
import java.awt.event.KeyEvent.VK_PAGE_DOWN
import java.awt.event.KeyEvent.VK_PAGE_UP
import java.awt.event.KeyEvent.VK_Q
import java.awt.event.KeyEvent.VK_RIGHT
import java.awt.event.KeyEvent.VK_S
import java.awt.event.KeyEvent.VK_SHIFT
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_TAB
import java.awt.event.KeyEvent.VK_UP
import java.awt.event.KeyEvent.VK_W
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.geom.Rectangle2D
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import javax.swing.JButton
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [EmulatorView], [EmulatorDisplayPanel] and some emulator toolbar actions. */
@RunsInEdt
class EmulatorViewTest {

  companion object {
    @JvmField @ClassRule val iconLoaderRule = IconLoaderRule()
  }

  private val emulatorViewRule = EmulatorViewRule()
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/EmulatorViewTest/golden")
  private val actionPopupRule = ActionPopupMenuRule()
  private val hiDpiRule = HiDpiRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      ApplicationRule(),
      ClipboardSynchronizationDisablementRule(),
      emulatorViewRule,
      goldenImageRule,
      actionPopupRule,
      EdtRule(),
      hiDpiRule,
    )
  @get:Rule val usageTrackerRule = UsageTrackerRule()
  private lateinit var view: EmulatorView
  private val fakeEmulator: FakeEmulator by lazy { emulatorViewRule.getFakeEmulator(view) }
  private lateinit var fakeUi: FakeUi

  private val project
    get() = emulatorViewRule.project

  private val testRootDisposable
    get() = emulatorViewRule.disposable

  private lateinit var mouseInfo: MockedStatic<MouseInfo>
  private lateinit var focusManager: FakeKeyboardFocusManager

  @Before
  fun setUp() {
    mouseInfo = mockStatic(testRootDisposable)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenReturn(Point(10, 20))
    mouseInfo.whenever<PointerInfo> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)
    focusManager = FakeKeyboardFocusManager(testRootDisposable)
    ActionManager.getInstance() // Instantiate ActionManager to trigger loading of keyboard shortcuts.
  }

  @After
  fun tearDown() {
    EmulatorNotificationDispatcher.getInstance().reset()
  }

  @Test
  fun testResizingRotationAndMouseInput() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())
    val inputEvents = LinkedBlockingDeque<AndroidInputEvent>()
    val inputListener =
      object : DeviceInputListener {
        override fun eventSent(event: AndroidInputEvent) {
          inputEvents.add(event)
        }
      }
    val inputListenerManager = project.getService(DeviceInputListenerManager::class.java)
    inputListenerManager.addDeviceInputListener(fakeEmulator.serialNumber, inputListener)

    // Check initial appearance.
    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    var call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 363 height: 547")
    assertAppearance("EmulatorView1")
    assertThat(call.completion.isCancelled).isFalse() // The call has not been canceled.
    assertThat(call.completion.isDone).isFalse() // The call is still ongoing.

    // Check zoom.
    fakeUi.root.size = Dimension(250, 405)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 454 height: 738")
    val skinHeight = 3245
    assertThat(view.scale).isWithin(1e-4).of(fakeUi.root.height * fakeUi.screenScalingFactor / skinHeight)
    assertThat(view.canZoom(ZoomType.IN)).isTrue()
    assertThat(view.canZoom(ZoomType.OUT)).isFalse()
    assertThat(view.canZoom(ZoomType.ACTUAL)).isTrue()
    assertThat(view.canZoom(ZoomType.FIT)).isFalse()

    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 720 height: 1481")
    assertThat(view.canZoom(ZoomType.IN)).isTrue()
    assertThat(view.canZoom(ZoomType.OUT)).isTrue()
    assertThat(view.canZoom(ZoomType.ACTUAL)).isTrue()
    assertThat(view.canZoom(ZoomType.FIT)).isTrue()

    view.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 1440 height: 2960")
    assertThat(view.canZoom(ZoomType.IN)).isTrue()
    assertThat(view.canZoom(ZoomType.OUT)).isTrue()
    assertThat(view.canZoom(ZoomType.ACTUAL)).isFalse()
    assertThat(view.canZoom(ZoomType.FIT)).isTrue()

    view.zoom(ZoomType.OUT)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 720 height: 1481")
    assertThat(view.canZoom(ZoomType.IN)).isTrue()
    assertThat(view.canZoom(ZoomType.OUT)).isTrue()
    assertThat(view.canZoom(ZoomType.ACTUAL)).isTrue()
    assertThat(view.canZoom(ZoomType.FIT)).isTrue()

    view.zoom(ZoomType.FIT)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 454 height: 738")
    assertThat(view.canZoom(ZoomType.IN)).isTrue()
    assertThat(view.canZoom(ZoomType.OUT)).isFalse()
    assertThat(view.canZoom(ZoomType.ACTUAL)).isTrue()
    assertThat(view.canZoom(ZoomType.FIT)).isFalse()

    // Check resizing.
    val previousCall = call
    fakeUi.root.size = Dimension(250, 200)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 454 height: 364")
    assertAppearance("EmulatorView2")
    assertThat(previousCall.completion.isCancelled).isTrue() // The previous call is canceled.
    assertThat(call.completion.isCancelled).isFalse() // The latest call has not been canceled.
    assertThat(call.completion.isDone).isFalse() // The latest call is still ongoing.

    // Check rotation.
    emulatorViewRule.executeAction("android.device.rotate.left", view)
    call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setPhysicalModel")
    assertThat(shortDebugString(call.request)).isEqualTo("target: ROTATION value { data: 0.0 data: 0.0 data: 90.0 }")
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 456 height: 363")
    waitForCondition(2.seconds) { view.displayOrientationQuadrants == 1 }
    assertAppearance("EmulatorView3")

    // Check mouse input in landscape orientation.
    fakeUi.mouse.press(10, 153)
    val inputEventCall = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(inputEventCall.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(inputEventCall.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 42 y: 61 buttons: 1 }")
    (inputEvents.take() as AndroidInputEvent.TouchEvent).apply {
      assertThat(deviceSerialNumber).isEqualTo(fakeEmulator.serialNumber)
      assertThat(touches).containsExactly(AndroidInputEvent.TouchEvent.Touch(42, 61, 0))
    }

    fakeUi.mouse.dragTo(215, 48)
    assertThat(shortDebugString(inputEventCall.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1404 y: 2723 buttons: 1 }")
    (inputEvents.take() as AndroidInputEvent.TouchEvent).apply {
      assertThat(deviceSerialNumber).isEqualTo(fakeEmulator.serialNumber)
      assertThat(touches).containsExactly(AndroidInputEvent.TouchEvent.Touch(1404, 2723, 0))
    }

    fakeUi.mouse.release()
    assertThat(shortDebugString(inputEventCall.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1404 y: 2723 }")
    (inputEvents.take() as AndroidInputEvent.TouchEvent).apply {
      assertThat(deviceSerialNumber).isEqualTo(fakeEmulator.serialNumber)
      assertThat(touches).isEmpty()
    }

    // Mouse events outside the display image shouldn't trigger listeners.
    inputEvents.clear()
    fakeUi.mouse.press(50, 7)
    fakeUi.mouse.release()
    assertThat(inputEvents).isEmpty()
    inputListenerManager.removeDeviceInputListener(fakeEmulator.serialNumber, inputListener)

    // Check clockwise rotation in a zoomed-in state.
    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 740 height: 360")
    assertThat(view.canZoom(ZoomType.OUT)).isTrue()
    assertThat(view.canZoom(ZoomType.FIT)).isTrue()
    emulatorViewRule.executeAction("android.device.rotate.right", view)
    call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setPhysicalModel")
    assertThat(shortDebugString(call.request)).isEqualTo("target: ROTATION value { data: 0.0 data: 0.0 data: 0.0 }")
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 454 height: 364")
    assertThat(view.canZoom(ZoomType.OUT)).isFalse() // zoom-in mode canceled by the rotation.
    assertThat(view.canZoom(ZoomType.FIT)).isFalse()
    assertAppearance("EmulatorView2")

    // Check mouse input in portrait orientation.
    fakeUi.mouse.press(82, 7)
    assertThat(shortDebugString(inputEventCall.request)).isEqualTo("mouse_event { x: 36 y: 44 buttons: 1 }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(inputEventCall.request)).isEqualTo("mouse_event { x: 36 y: 44 }")

    // Mouse events outside the display image should be ignored.
    fakeUi.mouse.press(50, 7)
    fakeUi.mouse.release()

    // Make sure event listeners aren't called after they're unregistered.
    assertThat(inputEvents).isEmpty()

    // Check hiding the device frame.
    view.deviceFrameVisible = false
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 500 height: 400")
    assertAppearance("EmulatorView4")
  }

  @Test
  fun testRightClick() {
    StudioFlags.RUNNING_DEVICES_CONTEXT_MENU.overrideForTest(true, testRootDisposable)
    hiDpiRule.setRetinaMode()
    hiDpiRule.setSysScale(2.0f)
    fakeUi = FakeUi(createEmulatorDisplayPanel())
    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    view.rightClicksAreSentToDevice = true
    fakeUi.mouse.press(40, 30, button = FakeMouse.Button.RIGHT)
    val inputEventCall = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(shortDebugString(inputEventCall.request)).isEqualTo("mouse_event { x: 83 y: 257 buttons: 2 }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(inputEventCall.request)).isEqualTo("mouse_event { x: 83 y: 257 }")

    // Simulate Windows right-click (isPopupTrigger = false on press, true on release) when context menu is enabled:
    // the button-down sent on press must still be balanced by a button-up on release when the popup menu opens.
    view.rightClicksAreSentToDevice = false
    val pointInView = SwingUtilities.convertPoint(fakeUi.root, Point(40, 30), view)
    val now = System.currentTimeMillis()
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON3_DOWN_MASK, pointInView.x, pointInView.y, 1, false, MouseEvent.BUTTON3)
    )
    assertThat(shortDebugString(inputEventCall.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 83 y: 257 buttons: 2 }")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, 0, pointInView.x, pointInView.y, 1, true, MouseEvent.BUTTON3))
    assertThat(shortDebugString(inputEventCall.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 83 y: 257 }")
    assertThat(actionPopupRule.popup?.isVisible).isTrue()
  }

  @Test
  fun testKeyboardInput() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.keyboard.setFocus(view)
    var call: GrpcCallRecord? = null
    // Printable ASCII characters.
    for (c in ' '..'~') {
      fakeUi.keyboard.type(c.code)
      if (call == null) {
        call = fakeEmulator.getNextGrpcCall(2.seconds)
      }
      assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
      val expectedText =
        when (c) {
          '\"',
          '\'',
          '\\' -> "\\$c"
          else -> c.toString()
        }
      assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { text: \"$expectedText\" }")
    }

    val trivialKeyStrokeCases =
      mapOf(
        VK_ENTER to "Enter",
        VK_TAB to "Tab",
        VK_ESCAPE to "Escape",
        VK_BACK_SPACE to "Backspace",
        VK_DELETE to if (SystemInfo.isMac) "Backspace" else "Delete",
        VK_LEFT to "ArrowLeft",
        VK_KP_LEFT to "ArrowLeft",
        VK_RIGHT to "ArrowRight",
        VK_KP_RIGHT to "ArrowRight",
        VK_DOWN to "ArrowDown",
        VK_KP_DOWN to "ArrowDown",
        VK_UP to "ArrowUp",
        VK_KP_UP to "ArrowUp",
        VK_HOME to "Home",
        VK_END to "End",
        VK_PAGE_DOWN to "PageDown",
        VK_PAGE_UP to "PageUp",
      )
    for ((hostKeyStroke, emulatorKeyName) in trivialKeyStrokeCases) {
      fakeUi.keyboard.pressAndRelease(hostKeyStroke)
      assertThat(shortDebugString(call!!.getNextRequest(1.seconds)))
        .isEqualTo("key_event { eventType: keypress key: \"$emulatorKeyName\" }")
    }

    val keyStrokeCases =
      mapOf(
        getKeyStroke(ACTION_CUT) to listOf("key: \"Control\"", "eventType: keypress key: \"x\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_COPY) to listOf("key: \"Control\"", "eventType: keypress key: \"c\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_PASTE) to listOf("key: \"Control\"", "eventType: keypress key: \"v\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_SELECT_ALL) to
          listOf("key: \"Control\"", "eventType: keypress key: \"a\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_LEFT_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"ArrowLeft\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_RIGHT_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"ArrowRight\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_DOWN_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"ArrowDown\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_UP_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"ArrowUp\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_PREVIOUS_WORD) to
          listOf("key: \"Control\"", "eventType: keypress key: \"ArrowLeft\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_EDITOR_NEXT_WORD) to
          listOf("key: \"Control\"", "eventType: keypress key: \"ArrowRight\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_EDITOR_PREVIOUS_WORD_WITH_SELECTION) to
          listOf(
            "key: \"Shift\"",
            "key: \"Control\"",
            "eventType: keypress key: \"ArrowLeft\"",
            "eventType: keyup key: \"Control\"",
            "eventType: keyup key: \"Shift\"",
          ),
        getKeyStroke(ACTION_EDITOR_NEXT_WORD_WITH_SELECTION) to
          listOf(
            "key: \"Shift\"",
            "key: \"Control\"",
            "eventType: keypress key: \"ArrowRight\"",
            "eventType: keyup key: \"Control\"",
            "eventType: keyup key: \"Shift\"",
          ),
        getKeyStroke(ACTION_EDITOR_MOVE_LINE_START_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"Home\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_LINE_END_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"End\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_PAGE_DOWN_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"PageDown\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_MOVE_CARET_PAGE_UP_WITH_SELECTION) to
          listOf("key: \"Shift\"", "eventType: keypress key: \"PageUp\"", "eventType: keyup key: \"Shift\""),
        getKeyStroke(ACTION_EDITOR_TEXT_START) to
          listOf("key: \"Control\"", "eventType: keypress key: \"Home\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_EDITOR_TEXT_END) to
          listOf("key: \"Control\"", "eventType: keypress key: \"End\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_EDITOR_TEXT_START_WITH_SELECTION) to
          listOf(
            "key: \"Shift\"",
            "key: \"Control\"",
            "eventType: keypress key: \"Home\"",
            "eventType: keyup key: \"Control\"",
            "eventType: keyup key: \"Shift\"",
          ),
        getKeyStroke(ACTION_EDITOR_TEXT_END_WITH_SELECTION) to
          listOf(
            "key: \"Shift\"",
            "key: \"Control\"",
            "eventType: keypress key: \"End\"",
            "eventType: keyup key: \"Control\"",
            "eventType: keyup key: \"Shift\"",
          ),
        getKeyStroke(ACTION_UNDO) to listOf("key: \"Control\"", "eventType: keypress key: \"z\"", "eventType: keyup key: \"Control\""),
        getKeyStroke(ACTION_REDO) to
          listOf(
            "key: \"Shift\"",
            "key: \"Control\"",
            "eventType: keypress key: \"z\"",
            "eventType: keyup key: \"Control\"",
            "eventType: keyup key: \"Shift\"",
          ),
      )
    for ((hostKeyStroke, keyboardEventMessages) in keyStrokeCases) {
      fakeUi.keyboard.hit(hostKeyStroke)
      for (message in keyboardEventMessages) {
        assertThat(shortDebugString(call!!.getNextRequest(1.seconds))).isEqualTo("key_event { $message }")
      }
    }

    // Ctrl+Tab should be ignored.
    with(fakeUi.keyboard) {
      press(VK_CONTROL)
      pressAndRelease(VK_TAB)
      release(VK_CONTROL)
    }

    val mockFocusManager: DefaultKeyboardFocusManager = mock()
    whenever(mockFocusManager.processKeyEvent(any<Component>(), any<KeyEvent>())).thenCallRealMethod()
    replaceKeyboardFocusManager(mockFocusManager, testRootDisposable)

    mockFocusManager.processKeyEvent(view, KeyEvent(view, KEY_PRESSED, System.nanoTime(), SHIFT_DOWN_MASK, VK_TAB, VK_TAB.toChar()))

    verify(mockFocusManager, atLeast(1)).focusNextComponent(eq(view))
  }

  @Test
  fun testFolding() {
    hiDpiRule.setRetinaMode()
    val panel = createEmulatorDisplayPanel { path -> FakeEmulator.createFoldableAvd(path) }
    fakeUi = FakeUi(panel)

    fakeUi.root.size = Dimension(200, 200)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    assertAppearance("FoldingOpen")

    fakeUi.mouse.press(135, 160)
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1528 y: 1635 buttons: 1 }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1528 y: 1635 }")

    fakeEmulator.setPosture(PostureValue.POSTURE_CLOSED)
    waitForCondition(1.seconds) {
      NotificationTracker.forEmulator(view.emulator).currentPosture.value?.posture == PostureValue.POSTURE_CLOSED
    }
    getStreamScreenshotCallAndWaitForFrame()
    assertAppearance("FoldingClosed")

    // Check that in a folded state mouse coordinates are interpreted differently.
    fakeUi.mouse.press(135, 160)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 914 y: 1720 buttons: 1 }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 914 y: 1720 }")
  }

  /** Checks that the mouse button release event is sent when the mouse leaves the device display. */
  @Test
  fun testSwipe() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    fakeUi.mouse.press(100, 100)
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 733 y: 1014 buttons: 1 }")
    fakeUi.mouse.dragTo(140, 100)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1166 y: 1014 buttons: 1 }")
    fakeUi.mouse.dragTo(180, 100)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1439 y: 1014 }")

    // Further drag steps outside or back inside the display do not emit duplicate releases or resurrect buttons: 1.
    fakeUi.mouse.dragTo(190, 100)
    fakeUi.mouse.dragTo(100, 100)
    fakeUi.mouse.release()

    // Events with no supported button (buttonToMask == 0) are ignored.
    val now = System.currentTimeMillis()
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, 0, 100, 100, 1, false, MouseEvent.NOBUTTON))
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, 0, 100, 100, 1, false, MouseEvent.NOBUTTON))

    // Verify no events were queued between the boundary release above and this hover move.
    fakeUi.mouse.moveTo(110, 100)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 841 y: 1014 }")

    // Exiting EmulatorView directly while dragging also sends a clamped release event.
    fakeUi.mouse.press(100, 100)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 733 y: 1014 buttons: 1 }")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_EXITED, now, BUTTON1_DOWN_MASK, 210, 100, 0, false, MouseEvent.NOBUTTON))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1439 y: 1014 }")
    fakeUi.mouse.release()
  }

  @Test
  fun testMultiTouch() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel(), testRootDisposable)

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()

    val mousePosition = Point(150, 75)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenAnswer { Point(mousePosition) }
    mouseInfo.whenever<Any?> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    fakeUi.keyboard.setFocus(view)
    fakeUi.mouse.moveTo(mousePosition)
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.layoutAndDispatchEvents()
    assertAppearance("MultiTouch1")
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1274 y: 744 }")

    fakeUi.mouse.press(mousePosition)
    assertAppearance("MultiTouch2")
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1274 y: 744 pressure: 1024 expiration: NEVER_EXPIRE }" +
          " touches { x: 165 y: 2215 identifier: 1 pressure: 1024 expiration: NEVER_EXPIRE } }"
      )

    mousePosition.x -= 20
    mousePosition.y += 20
    fakeUi.mouse.dragTo(mousePosition)
    assertAppearance("MultiTouch3")
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1058 y: 960 pressure: 1024 expiration: NEVER_EXPIRE }" +
          " touches { x: 381 y: 1999 identifier: 1 pressure: 1024 expiration: NEVER_EXPIRE } }"
      )

    fakeUi.mouse.release()
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1058 y: 960 expiration: NEVER_EXPIRE }" +
          " touches { x: 381 y: 1999 identifier: 1 expiration: NEVER_EXPIRE } }"
      )

    fakeUi.keyboard.release(VK_CONTROL)
    assertAppearance("MultiTouch4")

    // Releasing Ctrl mid-drag lifts the multi-touch pinch and continues dragging as a single pointer.
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.mouse.press(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1058 y: 960 pressure: 1024 expiration: NEVER_EXPIRE }" +
          " touches { x: 381 y: 1999 identifier: 1 pressure: 1024 expiration: NEVER_EXPIRE } }"
      )
    fakeUi.keyboard.release(VK_CONTROL)
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1058 y: 960 expiration: NEVER_EXPIRE }" +
          " touches { x: 381 y: 1999 identifier: 1 expiration: NEVER_EXPIRE } }"
      )
    mousePosition.x += 10
    fakeUi.mouse.dragTo(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    fakeUi.mouse.release()
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons: 1")

    // Exiting Pan mode refreshes pointer coordinates before evaluating multi-touch mode.
    view.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    assertThat(view.isPannable).isTrue()
    val cleanImage = ImageUtils.scale(fakeUi.render(), 0.5)

    view.isPanning = true
    // Pointer leaves the view during Pan mode while Ctrl is held; multi-touch must stay off on Pan exit.
    mousePosition.setLocation(-500, -500)
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = false
    mousePosition.setLocation(100, 150)
    ImageDiffUtil.assertImageSimilar("NoMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    fakeUi.keyboard.release(VK_CONTROL)

    // Pointer outside the visible viewport (within scrolled view's virtual bounds) must not enter multi-touch on Pan exit.
    view.isPanning = true
    mousePosition.setLocation(100, 400)
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = false
    mousePosition.setLocation(100, 150)
    ImageDiffUtil.assertImageSimilar("NoOffViewportMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    fakeUi.keyboard.release(VK_CONTROL)

    // Pointer enters the view during Pan mode while Ctrl is held; multi-touch turns on immediately on Pan exit.
    view.isPanning = true
    mousePosition.setLocation(100, 150)
    fakeUi.keyboard.press(VK_CONTROL)
    // Releasing Ctrl while focus is on another component must not leave multi-touch stuck on when exiting Pan mode.
    val otherComponent = JButton()
    fakeUi.keyboard.setFocus(otherComponent)
    fakeUi.keyboard.release(VK_CONTROL)
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("NoStaleMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    fakeUi.keyboard.setFocus(view)
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.mouse.moveTo(mousePosition)
    val expectedMultiTouch = ImageUtils.scale(fakeUi.render(), 0.5)
    assertThat(expectedMultiTouch.getRGB(0, 0, expectedMultiTouch.width, expectedMultiTouch.height, null, 0, expectedMultiTouch.width))
      .isNotEqualTo(cleanImage.getRGB(0, 0, cleanImage.width, cleanImage.height, null, 0, cleanImage.width))
    fakeUi.keyboard.release(VK_CONTROL)
    mousePosition.setLocation(-500, -500)
    view.isPanning = true
    mousePosition.setLocation(100, 150)
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("MultiTouchOnPanExit", expectedMultiTouch, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)

    // Releasing Ctrl while another component has focus clears the active multi-touch overlay immediately without mouse movement.
    fakeUi.keyboard.setFocus(otherComponent)
    fakeUi.keyboard.release(VK_CONTROL)
    ImageDiffUtil.assertImageSimilar("ClearedUnfocusedMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)

    // Deactivating the window while another component has focus clears lastModifiers and active multi-touch overlay.
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = true
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("MultiTouchBeforeDeactivate", expectedMultiTouch, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    val viewWindow = SwingUtilities.getWindowAncestor(view)
    Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK).forEach {
      it.eventDispatched(WindowEvent(viewWindow, WindowEvent.WINDOW_DEACTIVATED))
    }
    ImageDiffUtil.assertImageSimilar("ClearedOnWindowDeactivated", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    fakeUi.keyboard.release(VK_CONTROL)

    // Holding Ctrl in a different window must not enter multi-touch on environment/camera updates or Pan exit.
    val otherWindowButton = JButton()
    val otherWindowUi = FakeUi(otherWindowButton, parentDisposable = testRootDisposable)
    otherWindowUi.keyboard.setFocus(otherWindowButton)
    otherWindowUi.keyboard.press(VK_CONTROL)
    view.updateCameraPromptAndMultiTouchFeedback()
    ImageDiffUtil.assertImageSimilar("NoCrossWindowEnvMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    view.isPanning = true
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("NoCrossWindowPanExitMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    otherWindowUi.keyboard.release(VK_CONTROL)

    // Another active window covering the pointer on screen must prevent entering multi-touch on Pan exit.
    otherWindowUi.root.size = Dimension(300, 300)
    otherWindowUi.layout()
    focusManager.setActiveWindow(SwingUtilities.getWindowAncestor(otherWindowButton))
    fakeUi.keyboard.setFocus(view)
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = true
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("NoActiveWindowOccludedMultiTouch", cleanImage, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    focusManager.setActiveWindow(viewWindow)
    view.isPanning = true
    view.isPanning = false
    ImageDiffUtil.assertImageSimilar("MultiTouchAfterUnoccluded", expectedMultiTouch, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)

    // Non-primary mouse buttons are ignored in multi-touch mode and are not resurrected if Ctrl is released while held.
    val viewPoint = SwingUtilities.convertPoint(fakeUi.root, mousePosition, view)
    val now = System.currentTimeMillis()
    view.dispatchEvent(
      MouseEvent(
        view,
        MouseEvent.MOUSE_PRESSED,
        now,
        CTRL_DOWN_MASK or BUTTON2_DOWN_MASK,
        viewPoint.x,
        viewPoint.y,
        1,
        false,
        MouseEvent.BUTTON2,
      )
    )
    fakeUi.keyboard.release(VK_CONTROL)
    view.dispatchEvent(
      MouseEvent(
        view,
        MouseEvent.MOUSE_PRESSED,
        now,
        BUTTON1_DOWN_MASK or BUTTON2_DOWN_MASK,
        viewPoint.x,
        viewPoint.y,
        1,
        false,
        MouseEvent.BUTTON1,
      )
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, BUTTON2_DOWN_MASK, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, 0, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON2))
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.mouse.press(mousePosition, FakeMouse.Button.MIDDLE)
    view.isPanning = true
    fakeUi.mouse.release()
    view.isPanning = false

    // Primary button multi-touch gesture is cleanly released when entering Pan mode.
    fakeUi.mouse.press(mousePosition)
    val pressInMultiTouch = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(pressInMultiTouch).contains("touch_event")
    assertThat(pressInMultiTouch).contains("pressure: 1024")
    view.isPanning = true
    val releaseOnPanEntry = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(releaseOnPanEntry).contains("touch_event")
    assertThat(releaseOnPanEntry).doesNotContain("pressure:")
    fakeUi.mouse.release()
    view.isPanning = false
    fakeUi.keyboard.release(VK_CONTROL)

    // Pressing Ctrl mid-BUTTON1-press without dragging and entering Pan mode cleanly releases BUTTON1 without an unmatched touch_event.
    fakeUi.mouse.press(mousePosition)
    val singlePress = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(singlePress).contains("mouse_event")
    assertThat(singlePress).contains("buttons: 1")
    fakeUi.keyboard.press(VK_CONTROL)
    view.isPanning = true
    val singleReleaseOnPanEntry = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(singleReleaseOnPanEntry).contains("mouse_event")
    assertThat(singleReleaseOnPanEntry).doesNotContain("buttons:")
    fakeUi.mouse.release()
    view.isPanning = false
    fakeUi.keyboard.release(VK_CONTROL)

    // Pressing Ctrl mid-BUTTON1-drag activates multi-touch; entering Pan mode (or releasing BUTTON1 with Ctrl held) releases both streams.
    fakeUi.mouse.press(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    fakeUi.keyboard.press(VK_CONTROL)
    mousePosition.x += 5
    fakeUi.mouse.dragTo(mousePosition)
    val midDragTouch = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(midDragTouch).contains("touch_event")
    assertThat(midDragTouch).contains("pressure: 1024")
    view.isPanning = true
    val panTouchRelease = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(panTouchRelease).contains("touch_event")
    assertThat(panTouchRelease).doesNotContain("pressure:")
    val panMouseRelease = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(panMouseRelease).contains("mouse_event")
    assertThat(panMouseRelease).doesNotContain("buttons:")
    fakeUi.mouse.release()
    view.isPanning = false
    fakeUi.keyboard.release(VK_CONTROL)
    mousePosition.x -= 5
    fakeUi.mouse.moveTo(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")

    fakeUi.mouse.press(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    fakeUi.keyboard.press(VK_CONTROL)
    mousePosition.x += 5
    fakeUi.mouse.dragTo(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("pressure: 1024")
    fakeUi.mouse.release()
    val directTouchRelease = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(directTouchRelease).contains("touch_event")
    assertThat(directTouchRelease).doesNotContain("pressure:")
    val directMouseRelease = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(directMouseRelease).contains("mouse_event")
    assertThat(directMouseRelease).doesNotContain("buttons:")
    fakeUi.keyboard.release(VK_CONTROL)
    mousePosition.x -= 5
    fakeUi.mouse.moveTo(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")

    // Missed button releases are also synced on out-of-bounds mousePressed, mouseReleased with wasButtonDown == false,
    // mouseDragged with remaining held button, and out-of-bounds mouseEntered for non-primary buttons (using prevMouseCoordinates).
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, -10, -10, 1, false, MouseEvent.BUTTON1))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")

    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, 0, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON2))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")

    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    view.dispatchEvent(
      MouseEvent(
        view,
        MouseEvent.MOUSE_PRESSED,
        now,
        BUTTON1_DOWN_MASK or BUTTON2_DOWN_MASK,
        viewPoint.x,
        viewPoint.y,
        1,
        false,
        MouseEvent.BUTTON2,
      )
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 5")
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_DRAGGED, now, BUTTON2_DOWN_MASK, viewPoint.x + 2, viewPoint.y, 0, false, MouseEvent.NOBUTTON)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 4")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 4")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_ENTERED, now, 0, -10, -10, 0, false, MouseEvent.NOBUTTON))
    val tertiaryReleaseAtPrevCoords = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(tertiaryReleaseAtPrevCoords).doesNotContain("buttons:")
    assertThat(tertiaryReleaseAtPrevCoords).doesNotContain("x: 0 ")

    // Missed BUTTON1 release during multi-touch drag: subsequent mouseMoved with Ctrl held releases the pinch, clears active-drag
    // feedback back to hover intensity, and does not emit a duplicate release when Ctrl is later released.
    fakeUi.keyboard.press(VK_CONTROL)
    view.dispatchEvent(
      MouseEvent(
        view,
        MouseEvent.MOUSE_PRESSED,
        now,
        CTRL_DOWN_MASK or BUTTON1_DOWN_MASK,
        viewPoint.x,
        viewPoint.y,
        1,
        false,
        MouseEvent.BUTTON1,
      )
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("pressure: 1024")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, now, CTRL_DOWN_MASK, viewPoint.x, viewPoint.y, 0, false))
    val recoveredPinchRelease = shortDebugString(call.getNextRequest(1.seconds))
    assertThat(recoveredPinchRelease).contains("touch_event")
    assertThat(recoveredPinchRelease).doesNotContain("pressure:")
    ImageDiffUtil.assertImageSimilar("HoverIntensityAfterMissedRelease", expectedMultiTouch, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)
    fakeUi.keyboard.release(VK_CONTROL)
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, now, 0, viewPoint.x, viewPoint.y, 0, false))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).startsWith("mouse_event {")

    // Missed BUTTON1 release during single-pointer drag: subsequent mouseEntered clears lastTouchCoordinates and releases BUTTON1,
    // so pressing/releasing Ctrl afterward shows hover intensity and emits no spurious touch_event.
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, viewPoint.x, viewPoint.y, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).contains("buttons: 1")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_ENTERED, now, 0, viewPoint.x, viewPoint.y, 0, false))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).doesNotContain("buttons:")
    fakeUi.keyboard.press(VK_CONTROL)
    ImageDiffUtil.assertImageSimilar(
      "HoverIntensityAfterSingleMissedRelease",
      expectedMultiTouch,
      ImageUtils.scale(fakeUi.render(), 0.5),
      0.0,
    )
    fakeUi.keyboard.release(VK_CONTROL)
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, now, 0, viewPoint.x, viewPoint.y, 0, false))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).startsWith("mouse_event {")
  }

  @Test
  fun testDeviceButtonActions() {
    createEmulatorDisplayPanel()

    // Check EmulatorBackButtonAction.
    emulatorViewRule.executeAction("android.device.back.button", view, place = ActionPlaces.KEYBOARD_SHORTCUT)
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keypress key: \"GoBack\" }")

    // Check EmulatorHomeButtonAction.
    emulatorViewRule.executeAction("android.device.home.button", view, place = ActionPlaces.KEYBOARD_SHORTCUT)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keypress key: \"GoHome\" }")

    // Check EmulatorOverviewButtonAction.
    emulatorViewRule.executeAction("android.device.overview.button", view, place = ActionPlaces.MOUSE_SHORTCUT)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keypress key: \"AppSwitch\" }")
  }

  @Test
  fun testSkinButtons() {
    hiDpiRule.setRetinaMode()
    val panel = createEmulatorDisplayPanel { path: Path -> FakeEmulator.createAvdWithSkinButtons(path) }
    fakeUi = FakeUi(panel, testRootDisposable)

    // Check initial appearance.
    fakeUi.root.size = Dimension(110, 200)
    fakeUi.layoutAndDispatchEvents()
    val call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 180 height: 275")
    fakeUi.mouse.moveTo(23, 166)
    assertAppearance("SkinButtons1")
    fakeUi.mouse.press(23, 166)
    val streamInputCall = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"GoBack\" }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"GoBack\" }")

    // Zoom in (0.34x -> 0.5x) so scrollbars appear and the view becomes pannable.
    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    assertThat(view.isPannable).isTrue()

    val pointerPos = Point(-500, -500)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenAnswer { Point(pointerPos) }
    mouseInfo.whenever<Any?> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    view.isPanning = true
    // Zoom in again (0.5x -> 1.0x) while in Pan mode with the pointer over the GoBack skin button:
    // paintComponent must not highlight the skin button during Pan mode.
    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    val viewport = view.parent as JViewport
    val buttonViewPos = Point((view.width * 95) / 585, (view.height * 964) / 1161)
    view.scrollPosition = Point((buttonViewPos.x - 30).coerceAtLeast(0), (buttonViewPos.y - 100).coerceAtLeast(0))
    fakeUi.layoutAndDispatchEvents()
    val cleanPanRender = ImageUtils.scale(fakeUi.render(), 0.5)

    val buttonPanelPos = Point(buttonViewPos.x - viewport.viewPosition.x, buttonViewPos.y - viewport.viewPosition.y)
    pointerPos.location = buttonPanelPos
    // Force a projectionRectangle update while in Pan mode with the pointer over GoBack.
    view.zoom(ZoomType.OUT)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    view.scrollPosition = Point((buttonViewPos.x - 30).coerceAtLeast(0), (buttonViewPos.y - 100).coerceAtLeast(0))
    fakeUi.layoutAndDispatchEvents()
    val updatedButtonPanelPos = Point(buttonViewPos.x - viewport.viewPosition.x, buttonViewPos.y - viewport.viewPosition.y)
    pointerPos.location = updatedButtonPanelPos
    getStreamScreenshotCallAndWaitForFrame()
    ImageDiffUtil.assertImageSimilar("UnhighlightedInPan", cleanPanRender, ImageUtils.scale(fakeUi.render(), 0.5), 0.0)

    // Exiting Pan mode with the pointer unmoved restores skin button highlight immediately.
    view.isPanning = false
    val highlightedOnPanExit = ImageUtils.scale(fakeUi.render(), 0.5)
    assertThat(
        highlightedOnPanExit.getRGB(0, 0, highlightedOnPanExit.width, highlightedOnPanExit.height, null, 0, highlightedOnPanExit.width)
      )
      .isNotEqualTo(cleanPanRender.getRGB(0, 0, cleanPanRender.width, cleanPanRender.height, null, 0, cleanPanRender.width))
    fakeUi.mouse.moveTo(updatedButtonPanelPos)
    ImageDiffUtil.assertImageSimilar("HighlightedOnPanExit", ImageUtils.scale(fakeUi.render(), 0.5), highlightedOnPanExit, 0.0)
    fakeUi.mouse.press(updatedButtonPanelPos)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"GoBack\" }")
    fakeUi.mouse.release()
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"GoBack\" }")

    // Entering Pan mode while holding a skin button releases the pressed skin button immediately.
    fakeUi.mouse.press(updatedButtonPanelPos)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"GoBack\" }")
    view.isPanning = true
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"GoBack\" }")
    fakeUi.mouse.release()
    view.isPanning = false
  }

  @Test
  fun testMouseMoveSendGrpc() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    fakeUi.mouse.moveTo(135, 190)

    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 }") // No pressed buttons.
  }

  @Test
  fun testMouseMoveNotSendWhenMultiTouch() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    fakeUi.keyboard.setFocus(view)
    fakeUi.keyboard.press(VK_CONTROL)

    fakeUi.mouse.moveTo(135, 190)
    fakeUi.mouse.press(135, 190)

    // Here we expect the gRPC call from `press()`, as `moveTo()` should not trigger any gRPC calls.
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1109 y: 1989 pressure: 1024 expiration: NEVER_EXPIRE }" + // Non-zero pressure.
          " touches { x: 330 y: 970 identifier: 1 pressure: 1024 expiration: NEVER_EXPIRE } }"
      )

    fakeUi.keyboard.release(VK_CONTROL)
  }

  @Test
  fun testMouseMoveNotSendWhenCameraOperating() {
    val container = createRootContainer()
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    // Activate the virtual scene camera.
    focusManager.focusOwner = view
    fakeEmulator.virtualSceneCameraActive = true
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() != null }

    // Start operating camera
    fakeUi.keyboard.press(VK_SHIFT)

    // Move mouse.
    fakeUi.mouse.moveTo(135, 190)
    var call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/rotateVirtualSceneCamera")
    fakeUi.mouse.press(135, 190)

    // Stop operating camera.
    fakeUi.keyboard.release(VK_SHIFT)

    // Here we expect the gRPC call from `press()`, as `moveTo()` should not send any gRPC call.
    call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 buttons: 1 }")

    // Releasing the mouse button (or syncing a missed release) while operating the camera must still send the button-up event.
    fakeUi.keyboard.press(VK_SHIFT)
    assertThat(view.isOperatingVirtualSceneCamera).isTrue()
    fakeUi.mouse.release()
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 }")

    val viewPoint = SwingUtilities.convertPoint(fakeUi.root, Point(135, 190), view)
    val now = System.currentTimeMillis()
    view.dispatchEvent(
      MouseEvent(
        view,
        MouseEvent.MOUSE_PRESSED,
        now,
        SHIFT_DOWN_MASK or BUTTON1_DOWN_MASK,
        viewPoint.x,
        viewPoint.y,
        1,
        false,
        MouseEvent.BUTTON1,
      )
    )
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 buttons: 1 }")
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_ENTERED, now, SHIFT_DOWN_MASK, viewPoint.x, viewPoint.y, 0, false))
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 }")
    fakeUi.keyboard.release(VK_SHIFT)
  }

  @Test
  fun testDisconnectDuringPanModeDoesNotReactivateCamera() {
    val container = createRootContainer()
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    val pointerLocation = Point(100, 150)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenAnswer { Point(pointerLocation) }
    mouseInfo.whenever<Any?> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    view.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    assertThat(view.isPannable).isTrue()

    focusManager.focusOwner = view
    fakeUi.keyboard.setFocus(view)
    fakeEmulator.virtualSceneCameraActive = true
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>() != null }

    // Exiting Pan mode after a size change defers the post-projectionRectangle pointer/camera refresh out of paintComponent
    // so NotificationHolderPanel's child hierarchy is not mutated mid-paint.
    view.isPanning = true
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNull()
    view.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    view.isPanning = false
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNotNull()
    pointerLocation.setLocation(-500, -500)
    fakeUi.render()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNotNull()
    fakeUi.layoutAndDispatchEvents()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNull()

    // Restore camera prompt state before testing disconnect during Pan mode.
    pointerLocation.setLocation(100, 150)
    view.updateCameraPromptAndMultiTouchFeedback()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNotNull()

    // Entering Pan mode hides the camera prompt; holding Shift and disconnecting must not re-show the prompt or start the controller.
    view.isPanning = true
    assertThat(view.isPanning).isTrue()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNull()
    fakeUi.keyboard.press(VK_SHIFT)
    fakeEmulator.stop()
    waitForCondition(5.seconds) { !view.isConnected && view.displayRectangle == null }
    fakeUi.layoutAndDispatchEvents()

    assertThat(view.isPanning).isFalse()
    assertThat(view.isOperatingVirtualSceneCamera).isFalse()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNull()
    fakeUi.keyboard.release(VK_SHIFT)
  }

  @Test
  fun testMouseButtonFilledInGrpc() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    view.rightClicksAreSentToDevice = true
    val params = listOf(Pair(FakeMouse.Button.RIGHT, "buttons: 2"), Pair(FakeMouse.Button.MIDDLE, "buttons: 4"))
    var call: GrpcCallRecord? = null
    for ((button, expected) in params) {
      fakeUi.mouse.press(135, 190, button)

      if (call == null) {
        call = fakeEmulator.getNextGrpcCall(2.seconds)
        assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
      }
      assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 $expected }")

      fakeUi.mouse.release()

      assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 }") // No pressed buttons.
    }
  }

  @Test
  fun testMouseDragHasPressedButton() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    fakeUi.mouse.press(135, 190, FakeMouse.Button.RIGHT)

    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1109 y: 1989 buttons: 2 }")

    fakeUi.mouse.dragDelta(5, 0)

    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1163 y: 1989 buttons: 2 }")

    fakeUi.mouse.release()

    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1163 y: 1989 }") // No pressed buttons.
  }

  @Test
  fun testMouseWheel() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    var call: GrpcCallRecord? = null
    for (shift in booleanArrayOf(false, true)) {
      val axis = if (shift) "x" else "y"
      if (shift) {
        fakeUi.keyboard.press(VK_SHIFT)
      }
      for (rotation in intArrayOf(1, -1)) {
        fakeUi.mouse.wheel(100, 100, rotation)
        if (call == null) {
          call = fakeEmulator.getNextGrpcCall(2.seconds)
          assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
        }
        assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("wheel_event { d$axis: ${-rotation * 40} }")
      }
      if (shift) {
        fakeUi.keyboard.release(VK_SHIFT)
      }
    }
  }

  @Test
  fun testTouchpadScrolling() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()

    var call: GrpcCallRecord? = null
    var cumulativeRotation = 0.0
    for (sign in intArrayOf(1, -1)) {
      for (i in 1..100) {
        fakeUi.mouse.wheel(100, 100, 0, 0.1 * sign.toDouble())
        cumulativeRotation += 0.08 * sign
        if (cumulativeRotation.absoluteValue >= 1) {
          cumulativeRotation -= sign
          if (call == null) {
            call = fakeEmulator.getNextGrpcCall(2.seconds)
            assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
          }
          assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("wheel_event { dy: ${-sign} }")
        }
      }
    }
  }

  @Test
  fun testKeysForMnemonicsShouldNotBeConsumed() {
    createEmulatorDisplayPanel()
    val altMPressedEvent = KeyEvent(view, KEY_PRESSED, System.nanoTime(), ALT_DOWN_MASK, VK_M, VK_M.toChar())
    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(view, altMPressedEvent)
    assertThat(altMPressedEvent.isConsumed).isFalse()

    val altMReleasedEvent = KeyEvent(view, KEY_RELEASED, System.nanoTime(), ALT_DOWN_MASK, VK_M, VK_M.toChar())
    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(view, altMReleasedEvent)
    assertThat(altMReleasedEvent.isConsumed).isFalse()
  }

  @Test
  fun testKeyPreprocessingSkippedWhenHardwareInputEnabled() {
    createEmulatorDisplayPanel()
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)
    assertThat(view.skipKeyEventDispatcher(KeyEvent(view, KEY_PRESSED, System.nanoTime(), 0, VK_M, VK_M.toChar()))).isTrue()
    assertThat(view.skipKeyEventDispatcher(KeyEvent(view, KEY_PRESSED, System.nanoTime(), 0, VK_SPACE, VK_SPACE.toChar()))).isTrue()
  }

  @Test
  fun testFocusTraversalKeysAreSentToDeviceWhenHardwareInputEnabled() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())
    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()
    fakeUi.keyboard.setFocus(view)
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    fakeUi.keyboard.press(VK_SHIFT)
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Shift\" }")

    fakeUi.keyboard.pressAndRelease(VK_TAB)
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("key_event { key: \"Tab\" }")
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Tab\" }")

    fakeUi.keyboard.release(VK_SHIFT)
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("key_event { eventType: keyup key: \"Shift\" }")
  }

  @Test
  fun testKeyPreprocessingNotSkippedForActionTogglingHardwareInput() {
    createEmulatorDisplayPanel()
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)
    val keymapManager = KeymapManager.getInstance()
    keymapManager.activeKeymap.addShortcut("android.streaming.hardware.input", KeyboardShortcut.fromString("control shift J"))

    assertThat(
        view.skipKeyEventDispatcher(KeyEvent(view, KEY_PRESSED, System.nanoTime(), SHIFT_DOWN_MASK or CTRL_DOWN_MASK, VK_J, CHAR_UNDEFINED))
      )
      .isFalse()
  }

  @Test
  fun testCtrlAndAlphabeticalKeysSentWhenHardwareInputEnabled() {
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    emulatorViewRule.executeAction("android.streaming.hardware.input", view)
    fakeUi.keyboard.setFocus(view)

    fakeUi.keyboard.press(VK_CONTROL)
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"Control\" }")

    fakeUi.keyboard.press(VK_S)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"s\" }")

    fakeUi.keyboard.release(VK_S)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"s\" }")

    fakeUi.keyboard.release(VK_CONTROL)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"Control\" }")
  }

  @Test
  fun testHideCameraNotificationDuringHardwareInput() {
    val container = createRootContainer()
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    // Activate the virtual scene camera
    focusManager.focusOwner = view
    fakeEmulator.virtualSceneCameraActive = true
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() != null }

    // Enable hardware input
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Check if notification panel is disappeared
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() == null }

    // Disable hardware input
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Check if notification panel is re-appeared
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() != null }
  }

  @Test
  fun testCameraNotificationHasOperatingMessageWhenHardwareInputDisabledWithShift() {
    val container = createRootContainer()
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    // Enable hardware input
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Activate the virtual scene camera
    focusManager.focusOwner = view
    fakeEmulator.virtualSceneCameraActive = true

    // Disable hardware input with shift key
    executeAction("android.streaming.hardware.input", view, project, modifiers = SHIFT_DOWN_MASK)

    // Check if notification panel is disappeared
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() != null }

    assertThat(fakeUi.findComponent<EditorNotificationPanel>()?.text)
      .isEqualTo("Move camera with WASDQE keys, rotate with mouse or arrow keys")
  }

  @Test
  fun testAudioGlassesCameraRotation360Image() {
    checkAudioGlassesCameraRotation360("image360:/some/path/image.jpg")
  }

  @Test
  fun testAudioGlassesCameraRotation360Video() {
    checkAudioGlassesCameraRotation360("video360:/some/path/video.mp4")
  }

  private fun checkAudioGlassesCameraRotation360(sceneMode: String) {
    val container = createRootContainer { path -> FakeEmulator.createAudioGlassesAvd(path) }
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    // Initially, environment is empty, no prompt.
    focusManager.focusOwner = view
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() == null }

    val environmentTracker = EnvironmentTracker.forEmulator(view.emulator)!!
    environmentTracker.environment = Environment.newBuilder().putEnvironment("scene.mode", sceneMode).build()

    // Now, prompt should be "Hold Shift to rotate camera".
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>()?.text == "Hold Shift to rotate camera" }

    // Press Shift, prompt should change to "Rotate camera with mouse or arrow keys".
    fakeUi.keyboard.press(VK_SHIFT)
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>()?.text == "Rotate camera with mouse or arrow keys" }

    fakeEmulator.clearGrpcCallLog()
    val callFilter =
      FakeEmulator.DEFAULT_CALL_FILTER.or(
        "android.emulation.control.EmulatorController/streamClipboard",
        "android.emulation.control.EmulatorController/streamScreenshot",
      )

    // Camera translation keys must be ignored in 360-degree mode, while arrow-key rotation still works.
    for (key in listOf(VK_W, VK_A, VK_S, VK_D, VK_Q, VK_E)) {
      fakeUi.keyboard.press(key)
      fakeUi.keyboard.release(key)
    }
    fakeUi.keyboard.press(VK_LEFT)
    fakeUi.keyboard.release(VK_LEFT)
    val call = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/rotateVirtualSceneCamera")

    // Release Shift, prompt should revert.
    fakeUi.keyboard.release(VK_SHIFT)
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>()?.text == "Hold Shift to rotate camera" }
  }

  @Test
  fun testAudioGlassesCameraRotationAndTranslation3d() {
    val container = createRootContainer { path -> FakeEmulator.createAudioGlassesAvd(path) }
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    // Initially, environment is empty, no prompt.
    focusManager.focusOwner = view
    waitForCondition(200, MILLISECONDS) { fakeUi.findComponent<EditorNotificationPanel>() == null }

    // Set environment to a 3D scene (.obj).
    val environmentTracker = EnvironmentTracker.forEmulator(view.emulator)!!
    val env = Environment.newBuilder().putEnvironment("scene.mode", "mesh3d:/some/path/scene.obj").build()
    environmentTracker.environment = env

    // Now, prompt should be "Hold Shift to control camera" (since translation is allowed)
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>()?.text == "Hold Shift to control camera" }

    // Press Shift, prompt should change to "Move camera with WASDQE keys, rotate with mouse or arrow keys"
    fakeUi.keyboard.press(VK_SHIFT)
    val keys = EmulatorSettings.getInstance().cameraVelocityControls.keys
    waitForCondition(2.seconds) {
      fakeUi.findComponent<EditorNotificationPanel>()?.text == "Move camera with $keys keys, rotate with mouse or arrow keys"
    }

    // Release Shift, prompt should revert
    fakeUi.keyboard.release(VK_SHIFT)
    waitForCondition(2.seconds) { fakeUi.findComponent<EditorNotificationPanel>()?.text == "Hold Shift to control camera" }
  }

  @Test
  fun testAudioGlassesCameraTranslation3dEnabled() {
    val container = createRootContainer { path -> FakeEmulator.createAudioGlassesAvd(path) }
    container.rootPane.size = Dimension(200, 300)
    fakeUi = FakeUi(container.rootPane, parentDisposable = testRootDisposable)

    focusManager.focusOwner = view
    val environmentTracker = EnvironmentTracker.forEmulator(view.emulator)!!
    val env = Environment.newBuilder().putEnvironment("scene.mode", "mesh3d:/some/path/scene.obj").build()
    environmentTracker.environment = env

    // Hold Shift to enable camera controller.
    fakeUi.keyboard.press(VK_SHIFT)
    val keys = EmulatorSettings.getInstance().cameraVelocityControls.keys
    waitForCondition(2.seconds) {
      fakeUi.findComponent<EditorNotificationPanel>()?.text == "Move camera with $keys keys, rotate with mouse or arrow keys"
    }

    val callFilter =
      FakeEmulator.DEFAULT_CALL_FILTER.or(
        "android.emulation.control.EmulatorController/streamClipboard",
        "android.emulation.control.EmulatorController/streamScreenshot",
      )

    // Try to move camera with W key.
    fakeUi.keyboard.press(VK_W)
    val call = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setVirtualSceneCameraVelocity")
    assertThat(shortDebugString(call.request)).isEqualTo("z: -1.0")
    fakeUi.keyboard.release(VK_W)

    // Stopping velocity
    val stopCall = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(stopCall.methodName).isEqualTo("android.emulation.control.EmulatorController/setVirtualSceneCameraVelocity")
    assertThat(shortDebugString(stopCall.request)).isEqualTo("")
  }

  @Test
  fun testDisableMultiTouchDuringHardwareInput() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())
    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()

    val mousePosition = Point(150, 75)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenReturn(mousePosition)
    mouseInfo.whenever<Any?> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    // Start multitouch
    fakeUi.keyboard.setFocus(view)
    fakeUi.mouse.moveTo(mousePosition)
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.layoutAndDispatchEvents()
    assertAppearance("MultiTouch1")
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1274 y: 744 }")

    // Enable hardware input
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Check if multitouch indicator is hidden
    fakeUi.layoutAndDispatchEvents()
    assertAppearance("MultiTouch4")

    // Check if mouse down event is generated instead of touch
    fakeUi.mouse.press(mousePosition)
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("mouse_event { x: 1274 y: 744 buttons: 1 }")

    // Disable hardware input
    executeAction("android.streaming.hardware.input", view, project, modifiers = CTRL_DOWN_MASK)

    // Check if multitouch indicator is shown
    fakeUi.layoutAndDispatchEvents()
    assertAppearance("MultiTouch2")

    // Drag mouse
    mousePosition.x -= 20
    mousePosition.y += 20
    fakeUi.mouse.dragTo(mousePosition)

    // Check if touch event is generated
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds)))
      .isEqualTo(
        "touch_event { touches { x: 1058 y: 960 pressure: 1024 expiration: NEVER_EXPIRE }" +
          " touches { x: 381 y: 1999 identifier: 1 pressure: 1024 expiration: NEVER_EXPIRE } }"
      )
  }

  @Test
  fun testMetaKeysReleasedWhenHardwareInputDisabled() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    // Enable hardware input.
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Press Ctrl.
    focusManager.focusOwner = view
    fakeUi.keyboard.press(VK_CONTROL)

    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"Control\" }")

    // Disable hardware input.
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"Control\" }")
  }

  @Test
  fun testMetaKeysReleasedWhenLostFocusDuringHardwareInput() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    // Enable hardware input.
    emulatorViewRule.executeAction("android.streaming.hardware.input", view)

    // Press Ctrl.
    focusManager.focusOwner = view
    fakeUi.keyboard.press(VK_CONTROL)

    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { key: \"Control\" }")

    // Lose focus.
    focusManager.focusOwner = null

    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("key_event { eventType: keyup key: \"Control\" }")
  }

  @Test
  fun testClipboardSynchronization() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    focusManager.focusOwner = view

    CopyPasteManager.getInstance().setContents(StringSelection(""))

    val settings = EmulatorSettings.getInstance()
    settings.synchronizeClipboard = true
    var call = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamClipboard")
    val copyPasteManager = CopyPasteManager.getInstance()
    copyPasteManager.setContents(StringSelection("host clipboard"))
    call = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setClipboard")
    assertThat(shortDebugString(call.getNextRequest(1.seconds))).isEqualTo("text: \"host clipboard\"")
    call = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamClipboard")
    fakeEmulator.clipboard = "device clipboard"
    waitForCondition(2.seconds) { copyPasteManager.getContents<String>(DataFlavor.stringFlavor) != "host clipboard" }
    assertThat(copyPasteManager.getContents<String>(DataFlavor.stringFlavor)).isEqualTo("device clipboard")
    settings.synchronizeClipboard = false
    call.waitForCancellation(2.seconds)
  }

  @Test
  fun testMetricsCollection() {
    hiDpiRule.setRetinaMode()
    fakeUi = FakeUi(createEmulatorDisplayPanel())

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()

    Disposer.dispose(view)
    val mirroringSessions = usageTrackerRule.deviceMirroringSessions()
    assertThat(mirroringSessions.size).isEqualTo(1)
    val mirroringSessionPattern =
      Regex(
        "kind: DEVICE_MIRRORING_SESSION\n" +
          "studio_session_id: \".+\"\n" +
          "product_details \\{\n" +
          "\\s*version: \".*\"\n" +
          "}\n" +
          "device_info \\{\n" +
          "\\s*device_type: LOCAL_EMULATOR\n" +
          "}\n" +
          "ide_brand: ANDROID_STUDIO\n" +
          "idea_is_internal: \\w+\n" +
          "device_mirroring_session \\{\n" +
          "\\s*device_kind: VIRTUAL\n" +
          "\\s*duration_sec: \\d+\n" +
          "\\s*first_frame_delay_millis: \\d+\n" +
          "}\n"
      )
    assertThat(mirroringSessionPattern.matches(mirroringSessions[0].toString())).isTrue()
  }

  @Test
  fun testXrZoom() {
    hiDpiRule.setRetinaMode()
    val panel = createEmulatorDisplayPanel { path -> FakeEmulator.createXrHeadsetAvd(path) }
    fakeUi = FakeUi(panel)

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()

    view.zoom(ZoomType.IN)
    val call = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("xr_head_movement_event { delta_z: -$TRANSLATION_STEP_SIZE }")
    view.zoom(ZoomType.OUT)
    assertThat(shortDebugString(call.getNextRequest(2.seconds))).isEqualTo("xr_head_movement_event { delta_z: $TRANSLATION_STEP_SIZE }")
  }

  @Test
  fun testDisplayGlasses() {
    hiDpiRule.setRetinaMode()
    val panel = createEmulatorDisplayPanel { path -> FakeEmulator.createDisplayGlassesAvd(path) }
    fakeUi = FakeUi(panel)

    fakeUi.root.size = Dimension(200, 300)
    fakeUi.layoutAndDispatchEvents()
    var call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 1066 height: 900")
    assertAppearance("DisplayGlasses1")
    assertThat(view.displayRectangle).isEqualTo(Rectangle2D.Double(0.125, 100.0, 399.75, 400.0))

    executeAction("android.streaming.zoom.fit", view, project)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 400 height: 600")
    assertAppearance("DisplayGlasses2")
    assertThat(view.displayRectangle).isEqualTo(Rectangle2D.Double(125.0, 225.0, 150.0, 150.0))

    executeAction("android.streaming.zoom.in", view, project)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 600 height: 580")
    assertAppearance("DisplayGlasses3")

    executeAction("android.streaming.zoom.out", view, project)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 400 height: 600")

    executeAction("android.streaming.zoom.fit.inner", view, project)
    fakeUi.root.size = Dimension(250, 300)
    fakeUi.layoutAndDispatchEvents()
    call = getStreamScreenshotCallAndWaitForFrame()
    assertThat(shortDebugString(call.request)).isEqualTo("format: RGB888 width: 1200 height: 900")
    assertAppearance("DisplayGlasses4")

    executeAction("android.streaming.zoom.fit.inner", view, project)
    fakeUi.layoutAndDispatchEvents()
    assertAppearance("DisplayGlasses5")
  }

  @Test
  fun testXrCursorAfterMissedButtonRelease() {
    ApplicationManager.getApplication().replaceService(AdtUiCursorsProvider::class.java, TestAdtUiCursorsProvider(), testRootDisposable)
    val handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    val handPressedCursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    val eyeCursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
    val eyePressedCursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.XR_HAND_INPUT, handCursor)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.XR_HAND_INPUT_PRESSED, handPressedCursor)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.XR_EYE_INPUT, eyeCursor)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.XR_EYE_INPUT_PRESSED, eyePressedCursor)

    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenAnswer { Point(100, 100) }
    mouseInfo.whenever<Any?> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    val panel = createEmulatorDisplayPanel { path -> FakeEmulator.createXrHeadsetAvd(path) }
    fakeUi = FakeUi(panel, testRootDisposable)
    fakeUi.root.size = Dimension(400, 400)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()

    val xrInputController = EmulatorXrInputController.getInstance(project, view.emulator)
    val now = System.currentTimeMillis()
    for ((inputMode, cursors) in
      listOf(XrInputMode.HAND to (handCursor to handPressedCursor), XrInputMode.EYE to (eyeCursor to eyePressedCursor))) {
      val (defaultCursor, pressedCursor) = cursors
      xrInputController.inputMode = inputMode

      // Missed BUTTON1 release followed by MOUSE_MOVED restores the unpressed XR cursor immediately.
      view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1))
      assertThat(view.cursor).isEqualTo(pressedCursor)
      view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, now, 0, 110, 100, 0, false, MouseEvent.NOBUTTON))
      assertThat(view.cursor).isEqualTo(defaultCursor)

      // Missed BUTTON1 release followed by MOUSE_ENTERED restores the unpressed XR cursor immediately.
      view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, 110, 100, 1, false, MouseEvent.BUTTON1))
      assertThat(view.cursor).isEqualTo(pressedCursor)
      view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_ENTERED, now, 0, 120, 100, 0, false, MouseEvent.NOBUTTON))
      assertThat(view.cursor).isEqualTo(defaultCursor)
    }
  }

  @Test
  fun testReleaseClampedWhenDisplayShiftsBeforeEnteringPanMode() {
    fakeUi = FakeUi(createEmulatorDisplayPanel(), parentDisposable = testRootDisposable)
    fakeUi.root.size = Dimension(1200, 300)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    view.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()
    assertThat(view.isPannable).isTrue()

    // Press primary button inside display, then widen the panel so projectionRectangle shifts right of the press coordinates.
    val now = System.currentTimeMillis()
    val pressX = (view.displayRectangle!!.centerX / view.screenScalingFactor).toInt()
    val pressY = 150
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, pressX, pressY, 1, false, MouseEvent.BUTTON1))
    val inputCall = getNextGrpcCallIgnoringStreamScreenshot()
    assertThat(inputCall.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(inputCall.getNextRequest(1.seconds))).contains("buttons: 1")

    fakeUi.root.size = Dimension(5000, 300)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()
    assertThat(pressX * view.screenScalingFactor).isLessThan(view.displayRectangle!!.minX)

    // Entering Pan mode clamps the release event into deviceDisplayRegion instead of dropping it.
    view.isPanning = true
    val mouseReleaseMsg = shortDebugString(inputCall.getNextRequest(1.seconds))
    assertThat(mouseReleaseMsg).startsWith("mouse_event {")
    assertThat(mouseReleaseMsg).doesNotContain("buttons:")

    // Same for multi-touch drag terminated when entering Pan mode after projectionRectangle shifts.
    view.isPanning = false
    val pressX2 = (view.displayRectangle!!.centerX / view.screenScalingFactor).toInt()
    fakeUi.keyboard.setFocus(view)
    fakeUi.keyboard.press(VK_CONTROL)
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, CTRL_DOWN_MASK or BUTTON1_DOWN_MASK, pressX2, pressY, 1, false, MouseEvent.BUTTON1)
    )
    assertThat(shortDebugString(inputCall.getNextRequest(1.seconds))).contains("pressure: 1024")

    fakeUi.root.size = Dimension(12000, 300)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()
    assertThat(pressX2 * view.screenScalingFactor).isLessThan(view.displayRectangle!!.minX)

    view.isPanning = true
    val touchReleaseMsg = shortDebugString(inputCall.getNextRequest(1.seconds))
    assertThat(touchReleaseMsg).startsWith("touch_event {")
    assertThat(touchReleaseMsg).doesNotContain("pressure:")
    fakeUi.keyboard.release(VK_CONTROL)

    // Non-primary button (BUTTON2, where lastTouchCoordinates is null) still sends release on Pan entry after setBounds clears
    // mouseCoordinates.
    view.isPanning = false
    val pressX3 = (view.displayRectangle!!.centerX / view.screenScalingFactor).toInt()
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON2_DOWN_MASK, pressX3, pressY, 1, false, MouseEvent.BUTTON2))
    assertThat(shortDebugString(inputCall.getNextRequest(1.seconds))).contains("buttons: 4")

    fakeUi.root.size = Dimension(14000, 320)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()

    view.isPanning = true
    val middleReleaseMsg = shortDebugString(inputCall.getNextRequest(1.seconds))
    assertThat(middleReleaseMsg).startsWith("mouse_event {")
    assertThat(middleReleaseMsg).doesNotContain("buttons:")

    // In orientation 1, dragging onto the exact top boundary pixel of projectionRectangle after pressing BUTTON1 -> Ctrl hits the clamped
    // drag fallback while insideTouchScreen is true, and must release both the touch_event pinch and the mouse_event button.
    view.isPanning = false
    fakeUi.root.size = Dimension(1200, 1200)
    fakeUi.layoutAndDispatchEvents()
    emulatorViewRule.executeAction("android.device.rotate.left", view)
    assertThat(getNextGrpcCallIgnoringStreamScreenshot().methodName)
      .isEqualTo("android.emulation.control.EmulatorController/setPhysicalModel")
    getStreamScreenshotCallAndWaitForFrame()
    fakeUi.render()
    val rotatedRect = view.displayRectangle!!
    val centerX = rotatedRect.centerX.toInt()
    val centerY = rotatedRect.centerY.toInt()
    val topBoundaryY = rotatedRect.minY.toInt()
    assertThat(rotatedRect.contains(centerX.toDouble(), topBoundaryY.toDouble())).isTrue()

    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_PRESSED, now, BUTTON1_DOWN_MASK, centerX, centerY, 1, false, MouseEvent.BUTTON1))
    assertThat(shortDebugString(inputCall.getNextRequest(1.seconds))).contains("buttons: 1")
    fakeUi.keyboard.setFocus(view)
    fakeUi.keyboard.press(VK_CONTROL)
    view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_DRAGGED, now, CTRL_DOWN_MASK or BUTTON1_DOWN_MASK, centerX + 5, centerY, 0, false))
    assertThat(shortDebugString(inputCall.getNextRequest(1.seconds))).contains("pressure: 1024")
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_DRAGGED, now, CTRL_DOWN_MASK or BUTTON1_DOWN_MASK, centerX, topBoundaryY, 0, false)
    )
    val clampedDragTouchRelease = shortDebugString(inputCall.getNextRequest(1.seconds))
    assertThat(clampedDragTouchRelease).startsWith("touch_event {")
    assertThat(clampedDragTouchRelease).doesNotContain("pressure:")
    val clampedDragMouseRelease = shortDebugString(inputCall.getNextRequest(1.seconds))
    assertThat(clampedDragMouseRelease).startsWith("mouse_event {")
    assertThat(clampedDragMouseRelease).doesNotContain("buttons:")
    view.dispatchEvent(
      MouseEvent(view, MouseEvent.MOUSE_RELEASED, now, CTRL_DOWN_MASK, centerX, topBoundaryY, 1, false, MouseEvent.BUTTON1)
    )
    fakeUi.keyboard.release(VK_CONTROL)
  }

  private fun createRootContainer(avdCreator: ((Path) -> Path)? = null): HeadlessRootPaneContainer =
    HeadlessRootPaneContainer(NotificationHolderPanel(createEmulatorDisplayPanel(avdCreator)))

  private fun createEmulatorDisplayPanel(avdCreator: ((Path) -> Path)? = null): EmulatorDisplayPanel =
    emulatorViewRule.newEmulatorDisplayPanel(avdCreator).apply { view = displayView }

  @Throws(TimeoutException::class)
  private fun getStreamScreenshotCallAndWaitForFrame(): GrpcCallRecord {
    val call = fakeEmulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/streamScreenshot")
    call.waitForResponse(2.seconds)
    waitForFrame()
    return call
  }

  @Throws(TimeoutException::class)
  private fun getNextGrpcCallIgnoringStreamScreenshot(): GrpcCallRecord =
    fakeEmulator.getNextGrpcCall(2.seconds, IGNORE_SCREENSHOT_CALL_FILTER)

  @Throws(TimeoutException::class)
  private fun waitForFrame() {
    waitForCondition(2.seconds) {
      view.emulator.connectionState == ConnectionState.CONNECTED &&
        view.displayOrientationQuadrants == fakeEmulator.displayRotation.number &&
        NotificationTracker.forEmulator(view.emulator).currentPosture.value?.posture == fakeEmulator.devicePosture
      fakeEmulator.frameNumber > 0u && renderAndGetFrameNumber() == fakeEmulator.frameNumber
    }
  }

  private fun renderAndGetFrameNumber(): UInt {
    fakeUi.render() // The frame number may get updated as a result of rendering.
    return view.frameNumber
  }

  private fun assertAppearance(goldenImageName: String) {
    val image = fakeUi.render()
    val scaledDownImage = ImageUtils.scale(image, 0.5)
    goldenImageRule.assertImageSimilar(goldenImageName, scaledDownImage, 0.0)
  }
}

private fun UsageTrackerRule.deviceMirroringSessions(): List<AndroidStudioEvent> =
  usages.filter { it.studioEvent.kind == AndroidStudioEvent.EventKind.DEVICE_MIRRORING_SESSION }.map { it.studioEvent }

private fun getKeyStroke(action: String) = KeymapUtil.getKeyStroke(KeymapUtil.getActiveKeymapShortcuts(action))!!
