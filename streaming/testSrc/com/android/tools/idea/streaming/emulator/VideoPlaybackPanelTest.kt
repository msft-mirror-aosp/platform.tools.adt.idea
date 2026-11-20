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

import com.android.SdkConstants.PRIMARY_DISPLAY_ID
import com.android.emulator.control.AnimationState
import com.android.emulator.control.DisplayPowerModeNotification
import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.Environment
import com.android.emulator.control.SetAnimationStateRequest
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeKeyboardFocusManager
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.HeadlessRootPaneContainer
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.adtui.ui.NotificationHolderPanel
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.actions.ZoomLevelIndicator
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.DisplayIndicatorPopup
import com.android.tools.idea.streaming.core.FloatingToolbarContainer
import com.android.tools.idea.streaming.core.FloatingToolbarState
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.FakeEmulator.Companion.IGNORE_SCREENSHOT_CALL_FILTER
import com.android.tools.idea.streaming.emulator.actions.EnvironmentTracker
import com.android.tools.idea.streaming.emulator.dialogs.IconButton
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.sliderValueToPosition
import com.android.tools.idea.streaming.testutil.newEmulatorDisplayPanel
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.mockStatic
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.dialog
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.Point
import java.awt.PointerInfo
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicComboBoxUI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [VideoPlaybackPanel] and [VideoPlaybackOverlay]. */
@RunsInEdt
class VideoPlaybackPanelTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()

    private const val STREAM_ANIMATION_STATE_METHOD = "android.emulation.control.EmulatorController/streamAnimationState"
  }

  private val emulatorViewRule = EmulatorViewRule()
  @get:Rule val ruleChain = RuleChain(emulatorViewRule, ClipboardSynchronizationDisablementRule(), HeadlessDialogRule(), EdtRule())

  private val callFilter = IGNORE_SCREENSHOT_CALL_FILTER.or("android.emulation.control.EmulatorController/streamClipboard")

  @Before
  fun setUp() {
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(true, emulatorViewRule.disposable)
  }

  @Test
  fun testHiddenByDefaultAndShownOnBottomHoverOnlyForVideoEnvironment() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    assertThat(playbackPanel.isVisible).isFalse()

    // Non-video environment: hovering over bottom area does not show the playback panel.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Video environment while playback state is still STATE_UNKNOWN: hovering over bottom area must NOT show the panel.
    EnvironmentTracker.forEmulator(displayPanel.displayView.emulator)!!.environment =
      Environment.newBuilder().putEnvironment("scene.mode", "videofile:/tmp/sample.mp4").build()
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertFailsWith<TimeoutException> { waitForCondition(200.milliseconds) { playbackPanel.isVisible } }
    assertThat(playbackPanel.isVisible).isFalse()

    // Once updateAnimationState receives a known playback state while hovering over the bottom area, the panel becomes visible.
    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    waitForCondition(2.seconds) { playbackPanel.isVisible }

    // Moving mouse away hides the panel; switching to another video while away keeps it hidden by default.
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample2.mp4")
    // Switching to another video resets the panel and restarts the animation state stream. Wait for the new stream, and then for a state
    // with a distinctive duration, which can only arrive through it, to be sure that the switch has been fully processed.
    val streamCall = fakeEmulator.getNextGrpcCall(2.seconds) { it.methodName == STREAM_ANIMATION_STATE_METHOD }
    assertThat(streamCall.methodName).isEqualTo(STREAM_ANIMATION_STATE_METHOD)
    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(90_000L)
        .build()
    )
    val slider = fakeUi.getComponent<ProgressSlider>()
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    waitForCondition(2.seconds) { timeLabel.text == "00:00 / 01:30" }
    assertThat(slider.isEnabled).isTrue()
    assertThat(playbackPanel.isVisible).isFalse()

    // Hovering over upper/middle part of DisplayView keeps panel hidden.
    fakeUi.mouse.moveTo(215, 150)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Hovering over the bottom area shows the panel.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()

    // If animation state becomes STATE_UNKNOWN, the panel hides even while hovering; when it becomes known again, it reappears.
    fakeEmulator.updateEnvironmentAnimationState(AnimationState.newBuilder().setState(AnimationState.State.STATE_UNKNOWN).build())
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PAUSED)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    waitForCondition(2.seconds) { playbackPanel.isVisible }

    // A state that is neither playing nor paused, e.g. one introduced by a newer emulator, is not a known state either.
    fakeEmulator.updateEnvironmentAnimationState(AnimationState.newBuilder().setStateValue(99).setDurationMs(60_000L).build())
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PAUSED)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    waitForCondition(2.seconds) { playbackPanel.isVisible }

    // Moving mouse back up hides the panel.
    fakeUi.mouse.moveTo(215, 150)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Exiting the root pane hides the panel even if MOUSE_EXITED carries coordinates inside the panel bounds.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    fakeUi.mouse.preprocessMouseEvent(
      FakeUi.RelativePoint(fakeUi.root, 215, 345),
      MouseEvent.MOUSE_EXITED,
      0,
      0,
      0,
      false,
      System.currentTimeMillis(),
    )
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Hovering over the bottom area again shows the panel, and switching away from video hides it immediately.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "color:#000000")
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
  }

  @Test
  fun testPlayPauseControl() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
    waitForCondition(2.seconds) { playPauseButton.isEnabled && playPauseButton.icon == AllIcons.Actions.Pause }
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Pause")

    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    assertThat(playbackPanel.isVisible).isTrue()
    fakeEmulator.clearGrpcCallLog()

    // Click Pause.
    fakeUi.clickOn(playPauseButton)
    val pauseCall = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(pauseCall.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
    assertThat(shortDebugString(pauseCall.request)).isEqualTo("state { state: STATE_PAUSED }")
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")

    // Click Play.
    fakeUi.clickOn(playPauseButton)
    val playCall = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(playCall.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
    assertThat(shortDebugString(playCall.request)).isEqualTo("state { state: STATE_PLAYING }")
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Pause")
  }

  @Test
  fun testShownTemporarilyOnFocusAndSpaceKeyTogglesPlayback() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    val displayView = displayPanel.displayView
    val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
    overlay.temporaryVisibilityDuration = 500.milliseconds
    val playbackPanel = overlay.playbackPanel

    // The display view gaining focus doesn't show the panel when the environment is not a video.
    displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayView, FocusEvent.FOCUS_GAINED)) }
    assertThat(playbackPanel.isVisible).isFalse()

    var panelShown = false
    val originalCallback = overlay.onPlaybackPanelVisibilityChanged
    overlay.onPlaybackPanelVisibilityChanged = { visible ->
      panelShown = panelShown || visible
      originalCallback?.invoke(visible)
    }

    // The panel is shown for a limited time when a video starts playing.
    setEnvironment(displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
    waitForCondition(2.seconds) { panelShown && playPauseButton.isEnabled }
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // The same happens when the video is replaced by another one.
    panelShown = false
    setEnvironment(displayView.emulator, fakeEmulator, "videofile:/tmp/sample2.mp4")
    waitForCondition(2.seconds) { panelShown }
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // The display view gaining focus shows the panel for a limited time, and unrelated Environment map updates with the same scene.mode
    // neither cancel temporary visibility nor restart streamAnimationState.
    overlay.temporaryVisibilityDuration = 5.seconds
    displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayView, FocusEvent.FOCUS_GAINED)) }
    assertThat(playbackPanel.isVisible).isTrue()
    fakeEmulator.clearGrpcCallLog()
    EnvironmentTracker.forEmulator(displayView.emulator)!!.environment =
      Environment.newBuilder().putEnvironment("scene.mode", "videofile:/tmp/sample2.mp4").putEnvironment("extra.key", "value").build()
    fakeUi.layoutAndDispatchEvents()
    assertFailsWith<TimeoutException> {
      fakeEmulator.getNextGrpcCall(200.milliseconds) { it.methodName == STREAM_ANIMATION_STATE_METHOD }
    }
    assertThat(playbackPanel.isVisible).isTrue()
    overlay.temporaryVisibilityDuration = 500.milliseconds
    displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayView, FocusEvent.FOCUS_GAINED)) }
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // Hiding the panel while one of its controls holds focus returns focus to the display view without showing the panel again,
    // even though AWT Component.hide() clears or transfers focus before displayView gains focus.
    val focusManager = FakeKeyboardFocusManager(emulatorViewRule.disposable)
    overlay.focusRequester = { component ->
      EventQueue.invokeLater { focusManager.focusOwner = component }
      true
    }
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    focusManager.focusOwner = playPauseButton
    assertThat(playPauseButton.hasFocus()).isTrue()
    panelShown = false
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(focusManager.focusOwner).isSameAs(displayView)
    assertThat(playbackPanel.isVisible).isFalse()
    assertThat(panelShown).isFalse()

    // Subsequent focus gain on the display view from elsewhere still shows the panel temporarily.
    focusManager.focusOwner = null
    focusManager.focusOwner = displayView
    assertThat(playbackPanel.isVisible).isTrue()
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
    fakeEmulator.clearGrpcCallLog()

    fun sendSpaceKeyEvent(id: Int, time: Long, modifiers: Int = 0) {
      val keyCode = if (id == KeyEvent.KEY_TYPED) KeyEvent.VK_UNDEFINED else KeyEvent.VK_SPACE
      val event = KeyEvent(displayView, id, time, modifiers, keyCode, ' ')
      displayView.keyListeners.forEach {
        when (id) {
          KeyEvent.KEY_PRESSED -> it.keyPressed(event)
          KeyEvent.KEY_RELEASED -> it.keyReleased(event)
          KeyEvent.KEY_TYPED -> it.keyTyped(event)
        }
      }
    }

    var time = System.currentTimeMillis()

    // The Space key pauses the video and shows the panel for a limited time. The key is not sent to the emulator.
    fakeUi.keyboard.setFocus(displayView)
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time)
    sendSpaceKeyEvent(KeyEvent.KEY_TYPED, time)
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 50)
    // The panel is checked before waiting for anything, so that the check doesn't depend on timing.
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    val pauseCall = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(pauseCall.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
    assertThat(shortDebugString(pauseCall.request)).isEqualTo("state { state: STATE_PAUSED }")
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // The Space key resumes playback. Auto-repeat doesn't toggle the state again.
    time += 200
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time)
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time + 30)
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 60)
    val playCall = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(shortDebugString(playCall.request)).isEqualTo("state { state: STATE_PLAYING }")
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // A Space key released while a modifier key is held doesn't prevent the next press from toggling playback.
    time += 200
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 50, InputEvent.SHIFT_DOWN_MASK)
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time + 100)
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 150)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)

    // Auto-repeat on Linux produces pairs of release and press events with the same timestamp. It doesn't toggle the state again.
    time += 300
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    for (i in 1..3) {
      sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 500 + i * 30)
      sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time + 500 + i * 30)
      assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    }
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 700)
    // A press that follows a release after a longer interval is a separate keystroke.
    sendSpaceKeyEvent(KeyEvent.KEY_PRESSED, time + 900)
    sendSpaceKeyEvent(KeyEvent.KEY_RELEASED, time + 950)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
  }

  @Test
  fun testStreamRestartedAfterItEnds() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val slider = fakeUi.getComponent<ProgressSlider>()
    waitForCondition(2.seconds) { slider.isEnabled }

    // When the stream ends, the playback state becomes unknown. The stream is restarted after a delay.
    fakeEmulator.clearGrpcCallLog()
    fakeEmulator.endEnvironmentAnimationStateStream()
    waitForCondition(2.seconds) { !slider.isEnabled }
    val streamCall = fakeEmulator.getNextGrpcCall(5.seconds) { it.methodName == STREAM_ANIMATION_STATE_METHOD }
    assertThat(streamCall.methodName).isEqualTo(STREAM_ANIMATION_STATE_METHOD)
    waitForCondition(2.seconds) { slider.isEnabled }
  }

  @Test
  fun testNotShownUntilEnvironmentImageIsRendered() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    val displayView = displayPanel.displayView
    val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
    overlay.temporaryVisibilityDuration = 500.milliseconds
    val playbackPanel = overlay.playbackPanel

    // The video starts playing before the first frame is rendered.
    waitForCondition(5.seconds) { displayView.emulator.connectionState == EmulatorController.ConnectionState.CONNECTED }
    setEnvironment(displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val slider = fakeUi.getComponent<ProgressSlider>()
    waitForCondition(2.seconds) { slider.isEnabled }
    assertThat(displayView.frameNumber).isEqualTo(0u)
    assertThat(playbackPanel.isVisible).isFalse()
    // Hovering over the bottom area doesn't show the panel either.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    fakeUi.mouse.moveTo(215, 100)

    // The panel is shown for a limited time when the environment image appears. Showing is detected by a listener rather than by
    // polling, so that it is not missed if the panel is hidden again before the test gets to look at it.
    var panelShown = false
    val originalCallback = overlay.onPlaybackPanelVisibilityChanged
    overlay.onPlaybackPanelVisibilityChanged = { visible ->
      panelShown = panelShown || visible
      originalCallback?.invoke(visible)
    }
    waitForFirstFrame(displayPanel, fakeUi)
    waitForCondition(2.seconds) { panelShown }
    waitForCondition(2.seconds) { !playbackPanel.isVisible }

    // If a temporary show is triggered while bounds are empty (e.g. collapsed container) after projectionRectangle is already set,
    // restoring non-empty bounds via layout fulfills the pending temporary show even without projectionRectangle changing again.
    panelShown = false
    displayPanel.size = Dimension(0, 0)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.projectionRectangle).isNotNull()
    displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayView, FocusEvent.FOCUS_GAINED)) }
    assertThat(playbackPanel.isVisible).isFalse()
    displayPanel.size = Dimension(430, 360)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(panelShown).isTrue()
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
  }

  @Test
  fun testProgressSliderScrubbingAndStreamUpdates() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    waitForCondition(2.seconds) { slider.isEnabled }
    assertThat(timeLabel.text).isEqualTo("00:00 / 01:00")
    fakeEmulator.clearGrpcCallLog()

    // Server streams a position/duration update; UI updates without echoing setAnimationState.
    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(30_000L)
        .setDurationMs(120_000L)
        .build()
    )
    waitForCondition(2.seconds) { slider.value == 250 && timeLabel.text == "00:30 / 02:00" }
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

    // Hover over bottom area to show panel, then drag the slider outside the panel bounds.
    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    val changesSentCount = AtomicInteger()
    val originalOnChangesSent = playbackPanel.onChangesSent
    playbackPanel.onChangesSent = {
      changesSentCount.incrementAndGet()
      originalOnChangesSent?.invoke()
    }
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()

    val sliderValues = mutableListOf<Int>()
    slider.addChangeListener {
      if (sliderValues.lastOrNull() != slider.value) {
        sliderValues.add(slider.value)
      }
    }
    val sliderPos = fakeUi.getPosition(slider)
    val startX = sliderPos.x + slider.width / 4
    val endX = sliderPos.x + slider.width * 3 / 4
    val sliderCenterY = sliderPos.y + slider.height / 2

    fakeUi.mouse.press(startX, sliderCenterY)
    fakeUi.mouse.dragTo(endX, sliderCenterY)
    assertThat(slider.valueIsAdjusting).isTrue()
    assertThat(slider.value).isGreaterThan(500)

    // Drag upward outside the playback panel bounds while slider is adjusting; panel must remain visible.
    fakeUi.mouse.dragTo(endX, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(slider.valueIsAdjusting).isTrue()
    assertThat(playbackPanel.isVisible).isTrue()

    // Return to slider to release in FakeMouse, then verify releasing/finishing adjustment outside hides the panel.
    fakeUi.mouse.dragTo(endX, sliderCenterY)
    fakeUi.mouse.release()
    fakeUi.layoutAndDispatchEvents()
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(playbackPanel.isVisible).isTrue()
    val expectedPositionMs = sliderValueToPosition(slider.value, 120.seconds).inWholeMilliseconds
    assertThat(expectedPositionMs).isGreaterThan(60_000L)

    slider.valueIsAdjusting = true
    fakeUi.mouse.moveTo(endX, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    slider.valueIsAdjusting = false
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Verify that every request sent while scrubbing was a seek, that the requests were coalesced, and that the last one carried
    // the final position of the slider.
    waitForCondition(2.seconds) { changesSentCount.get() == 1 }
    val seekPositions = mutableListOf<Long>()
    while (true) {
      val call =
        try {
          fakeEmulator.getNextGrpcCall(if (seekPositions.lastOrNull() != expectedPositionMs) 2.seconds else 200.milliseconds, callFilter)
        } catch (_: TimeoutException) {
          break
        }
      assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
      val request = call.request as SetAnimationStateRequest
      call.waitForResponse(2.seconds)
      val positionMs = request.state.playbackPositionMs
      assertThat(shortDebugString(request)).isEqualTo("state { playback_position_ms: $positionMs }")
      seekPositions.add(positionMs)
    }
    assertThat(seekPositions).isNotEmpty()
    assertThat(seekPositions.last()).isEqualTo(expectedPositionMs)
    // No two consecutive requests carry the same position, and there are no more requests than distinct slider positions.
    assertThat(seekPositions.zipWithNext().filter { (previous, next) -> previous == next }).isEmpty()
    assertThat(seekPositions.size).isAtMost(sliderValues.size)
    assertThat(fakeEmulator.environmentAnimationState.playbackPositionMs).isEqualTo(expectedPositionMs)
    assertThat(changesSentCount.get()).isEqualTo(1)
  }

  @Test
  fun testPlaybackSpeedSelector() {
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    @Suppress("UNCHECKED_CAST")
    val speedComboBox = fakeUi.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" } as ComboBox<VideoPlaybackSpeed>
    waitForCondition(2.seconds) { speedComboBox.isEnabled }

    val speeds = (0 until speedComboBox.itemCount).map { speedComboBox.getItemAt(it).toString() }
    assertThat(speeds).containsExactly("0.1X", "0.2X", "0.5X", "1X", "2X", "5X").inOrder()
    assertThat(speedComboBox.selectedItem).isEqualTo(VideoPlaybackSpeed.SPEED_1X)
    fakeEmulator.clearGrpcCallLog()

    // User selects 2X speed.
    speedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_2X
    val call2x = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(call2x.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
    assertThat(shortDebugString(call2x.request)).isEqualTo("state { playback_speed: 2.0 }")

    // User selects 0.2X speed.
    speedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_0_2X
    val call02x = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(call02x.methodName).isEqualTo("android.emulation.control.EmulatorController/setAnimationState")
    assertThat(shortDebugString(call02x.request)).isEqualTo("state { playback_speed: 0.2 }")

    // Server streams speed change to 0.5X; combo box updates without echoing setAnimationState.
    waitForCondition(2.seconds) { fakeEmulator.environmentAnimationState.playbackSpeed == 0.2f }
    fakeEmulator.updateEnvironmentAnimationState(fakeEmulator.environmentAnimationState.toBuilder().setPlaybackSpeed(0.5f).build())
    waitForCondition(2.seconds) { speedComboBox.selectedItem == VideoPlaybackSpeed.SPEED_0_5X }
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

    // Server streams a speed that is not one of the presets; the combo box shows it without adding it to the list of choices.
    fakeEmulator.updateEnvironmentAnimationState(fakeEmulator.environmentAnimationState.toBuilder().setPlaybackSpeed(0.75f).build())
    waitForCondition(2.seconds) { speedComboBox.selectedItem?.toString() == "0.75X" }
    assertThat(speedComboBox.itemCount).isEqualTo(6)
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

    // User selects a preset again.
    speedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_1X
    val call1x = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(shortDebugString(call1x.request)).isEqualTo("state { playback_speed: 1.0 }")

    // Entering the combo box's heavyweight popup fires MOUSE_EXITED on the root pane with coordinates still inside the panel bounds;
    // the panel remains visible while the popup is open and hides once the popup closes without the mouse re-entering.
    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    speedComboBox.setUI(
      object : BasicComboBoxUI() {
        private var popupShown = false

        override fun setPopupVisible(c: JComboBox<*>?, v: Boolean) {
          popupShown = v
          if (v) speedComboBox.firePopupMenuWillBecomeVisible() else speedComboBox.firePopupMenuWillBecomeInvisible()
        }

        override fun isPopupVisible(c: JComboBox<*>?): Boolean = popupShown
      }
    )
    speedComboBox.showPopup()
    assertThat(speedComboBox.isPopupVisible).isTrue()
    fakeUi.mouse.preprocessMouseEvent(
      FakeUi.RelativePoint(fakeUi.root, 215, 345),
      MouseEvent.MOUSE_EXITED,
      0,
      0,
      0,
      false,
      System.currentTimeMillis(),
    )
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    speedComboBox.hidePopup()
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Collapsing panel bounds to empty while the speed popup is open immediately closes the popup and clears isInteracting.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    speedComboBox.showPopup()
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(playbackPanel.isInteracting).isTrue()
    displayPanel.size = Dimension(0, 0)
    fakeUi.layoutAndDispatchEvents()
    assertThat(speedComboBox.isPopupVisible).isFalse()
    assertThat(playbackPanel.isInteracting).isFalse()
    assertThat(playbackPanel.isVisible).isFalse()
    displayPanel.size = Dimension(430, 360)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Hiding or canceling the popup right before disposal does not invoke onStateChanged after disposal.
    var postDisposeCalls = 0
    playbackPanel.onStateChanged = { postDisposeCalls++ }
    speedComboBox.showPopup()
    speedComboBox.hidePopup()
    speedComboBox.firePopupMenuCanceled()
    Disposer.dispose(playbackPanel)
    fakeUi.layoutAndDispatchEvents()
    assertThat(postDisposeCalls).isEqualTo(0)
  }

  @Test
  fun testAlignedToProjectionRectangle() {
    // Audio Glasses has 1200x900 (4:3) environment with no inner display, so in a 600x300 panel the rendered environment is (100, 0, 400,
    // 300).
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createAudioGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(600, 300))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    val slider = fakeUi.getComponent<ProgressSlider>()
    waitForCondition(2.seconds) { slider.isEnabled }
    fakeUi.layoutAndDispatchEvents()

    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    assertThat(playbackPanel.x).isEqualTo(100)
    assertThat(playbackPanel.width).isEqualTo(400)
    assertThat(playbackPanel.y + playbackPanel.height).isEqualTo(300)

    // Hovering in the left letterbox area (x = 50) does not show the panel.
    fakeUi.mouse.moveTo(50, 290)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    val sampleX = playbackPanel.x + playbackPanel.width / 2
    val sampleY = playbackPanel.y + playbackPanel.height - 2
    val videoOnlyRgb = fakeUi.render().getRGB(sampleX, sampleY)

    // Hovering over the bottom of the rendered video area (x = 300) shows the semi-transparent panel.
    fakeUi.mouse.moveTo(300, 290)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    val blendedRgb = fakeUi.render().getRGB(sampleX, sampleY)
    assertThat(blendedRgb).isNotEqualTo(videoOnlyRgb)
    assertThat(blendedRgb).isNotEqualTo(JBColor.PanelBackground.rgb)
  }

  @Test
  fun testPanelBackgroundIsSemitransparentAndControlsAreOpaque() {
    val prevStartBg = UIManager.get("Button.startBackground")
    val prevEndBg = UIManager.get("Button.endBackground")
    try {
      // Simulate themes (such as Islands Dark) where Button.startBackground / endBackground are transparent.
      UIManager.put("Button.startBackground", Color(0x19, 0x1A, 0x1C, 0x00))
      UIManager.put("Button.endBackground", Color(0x19, 0x1A, 0x1C, 0x00))

      val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
      val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
      val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
      waitForFirstFrame(displayPanel, fakeUi)

      setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
      val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
      val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
      val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
      val speedLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackSpeedLabel" }
      val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
      @Suppress("UNCHECKED_CAST")
      val speedComboBox = fakeUi.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" } as ComboBox<VideoPlaybackSpeed>

      fakeEmulator.updateEnvironmentAnimationState(
        AnimationState.newBuilder()
          .setState(AnimationState.State.STATE_PLAYING)
          .setPlaybackSpeed(1f)
          .setPlaybackPositionMs(30_000L)
          .setDurationMs(60_000L)
          .build()
      )
      waitForCondition(2.seconds) { slider.isEnabled && slider.value == 500 }

      fakeUi.mouse.moveTo(215, 345)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()

      assertThat(timeLabel.foreground.alpha).isEqualTo(255)
      assertThat(speedLabel.foreground.alpha).isEqualTo(255)

      fun verifyPanelAndControlOpacity() {
        val w = playbackPanel.width
        val h = playbackPanel.height
        val transparentImg =
          BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            try {
              playbackPanel.paint(g)
            } finally {
              g.dispose()
            }
          }
        val overRedImg = renderPanelOverSolidColor(playbackPanel, Color.RED)
        val overCyanImg = renderPanelOverSolidColor(playbackPanel, Color.CYAN)

        val buttonRightInPanel =
          SwingUtilities.convertPoint(playPauseButton, Point(playPauseButton.width, playPauseButton.height / 2), playbackPanel)
        val speedLabelLeftInPanel = SwingUtilities.convertPoint(speedLabel, Point(0, speedLabel.height / 2), playbackPanel)
        val bgPoint = Point((buttonRightInPanel.x + speedLabelLeftInPanel.x) / 2, buttonRightInPanel.y)

        // Panel background is ~80% opaque and blends with the underlying surface.
        val bgAlpha = Color(transparentImg.getRGB(bgPoint.x, bgPoint.y), true).alpha
        assertThat(bgAlpha).isIn(200..208)
        val bgOverRed = overRedImg.getRGB(bgPoint.x, bgPoint.y)
        val bgOverCyan = overCyanImg.getRGB(bgPoint.x, bgPoint.y)
        assertThat(bgOverRed).isNotEqualTo(bgOverCyan)
        assertThat(bgOverRed).isNotEqualTo(Color.RED.rgb)

        // Control interiors (slider played/unplayed track & thumb, Play/Pause button face & icon, speed ComboBox text & arrow areas)
        // must be 100% opaque and completely unaffected by the underlying video colors.
        val controlPoints =
          listOf(
            SwingUtilities.convertPoint(slider, Point(slider.width / 4, slider.height / 2), playbackPanel),
            SwingUtilities.convertPoint(slider, Point(slider.width / 2, slider.height / 2), playbackPanel),
            SwingUtilities.convertPoint(slider, Point(slider.width * 3 / 4, slider.height / 2), playbackPanel),
            SwingUtilities.convertPoint(playPauseButton, Point(5, playPauseButton.height / 2), playbackPanel),
            SwingUtilities.convertPoint(playPauseButton, Point(playPauseButton.width / 2, playPauseButton.height / 2), playbackPanel),
            SwingUtilities.convertPoint(speedComboBox, Point(8, speedComboBox.height / 2), playbackPanel),
            SwingUtilities.convertPoint(speedComboBox, Point(speedComboBox.width / 2, speedComboBox.height / 2), playbackPanel),
            SwingUtilities.convertPoint(speedComboBox, Point(speedComboBox.width - 10, speedComboBox.height / 2), playbackPanel),
          )
        for (pt in controlPoints) {
          assertThat(Color(transparentImg.getRGB(pt.x, pt.y), true).alpha).isEqualTo(255)
          assertThat(overRedImg.getRGB(pt.x, pt.y)).isEqualTo(overCyanImg.getRGB(pt.x, pt.y))
        }
      }

      // Verify when controls are enabled.
      verifyPanelAndControlOpacity()

      // Verify when controls are disabled (e.g., STATE_UNKNOWN hides the panel and disables controls).
      fakeEmulator.updateEnvironmentAnimationState(AnimationState.newBuilder().setState(AnimationState.State.STATE_UNKNOWN).build())
      waitForCondition(2.seconds) {
        !playbackPanel.isVisible && !playPauseButton.isEnabled && !slider.isEnabled && !speedComboBox.isEnabled
      }
      fakeUi.layoutAndDispatchEvents()
      verifyPanelAndControlOpacity()
    } finally {
      UIManager.put("Button.startBackground", prevStartBg)
      UIManager.put("Button.endBackground", prevEndBg)
    }
  }

  @Test
  fun testZOrderRelativeToZoomControlsAndDisplayIndicatorHiding() {
    StudioFlags.EMBEDDED_EMULATOR_DISPLAY_OFF_INDICATOR.overrideForTest(true, emulatorViewRule.disposable)
    @Suppress("UnstableApiUsage") HeadlessDataManager.fallbackToProductionDataManager(emulatorViewRule.disposable)
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    displayPanel.zoomToolbarVisible = true
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    fakeEmulator.setDisplayPowerMode(PRIMARY_DISPLAY_ID, DisplayPowerModeNotification.PowerMode.OFF)

    val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
    val playbackPanel = overlay.playbackPanel
    val displayOffBanner = fakeUi.getComponent<DisplayIndicatorPopup>()
    val slider = fakeUi.getComponent<ProgressSlider>()
    waitForCondition(2.seconds) {
      slider.isEnabled && displayOffBanner.isVisible && fakeUi.findComponent<FloatingToolbarContainer>() != null
    }

    val zoomToolbar = fakeUi.getComponent<FloatingToolbarContainer>()
    val toolbarPanel = zoomToolbar.getComponent(0)
    waitForCondition(2.seconds) {
      ActivityTracker.getInstance().inc()
      fakeUi.updateToolbarsIfNecessary()
      fakeUi.layoutAndDispatchEvents()
      !toolbarPanel.bounds.isEmpty
    }

    // Show the playback panel by hovering over the bottom area.
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(zoomToolbar.isVisible).isTrue()
    // The "Display off" indicator is hidden while the playback panel is shown.
    assertThat(displayOffBanner.isVisible).isFalse()

    val layeredPane = overlay.parent as JLayeredPane
    val floatingToolbarLayerPane = zoomToolbar.parent
    val notificationLayerPane = displayOffBanner.parent
    val scrollPane = displayPanel.displayView.parent.parent
    assertThat(layeredPane.getComponentZOrder(notificationLayerPane)).isLessThan(layeredPane.getComponentZOrder(floatingToolbarLayerPane))
    assertThat(layeredPane.getComponentZOrder(floatingToolbarLayerPane)).isLessThan(layeredPane.getComponentZOrder(overlay))
    assertThat(layeredPane.getComponentZOrder(overlay)).isLessThan(layeredPane.getComponentZOrder(scrollPane))

    // Verify hit-testing where the collapsed zoom toolbar overlaps the playback panel resolves to the zoom toolbar.
    val toolbarRect = SwingUtilities.convertRectangle(toolbarPanel.parent, toolbarPanel.bounds, displayPanel)
    val panelRect = SwingUtilities.convertRectangle(playbackPanel.parent, playbackPanel.bounds, displayPanel)

    val zoomOverlap = toolbarRect.intersection(panelRect)
    assertThat(zoomOverlap.isEmpty).isFalse()
    val zoomHit = SwingUtilities.getDeepestComponentAt(displayPanel, zoomOverlap.centerX.toInt(), zoomOverlap.centerY.toInt())
    assertThat(zoomHit).isNotNull()
    assertThat(SwingUtilities.isDescendingFrom(zoomHit, zoomToolbar)).isTrue()
    assertThat(SwingUtilities.isDescendingFrom(zoomHit, playbackPanel)).isFalse()

    // Expanding the zoom toolbar while the playback panel is shown hides the playback panel immediately (and restores the "Display off"
    // indicator).
    zoomToolbar.triggerActivation()
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    assertThat(displayOffBanner.isVisible).isTrue()
    waitForCondition(2.seconds) { zoomToolbar.activationFactor == 1.0 }

    // Moving the mouse to hover over the bottom area outside the zoom toolbar's non-activation area shows the playback panel again,
    // collapses the zoom toolbar, and hides the "Display off" indicator.
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(zoomToolbar.isActive).isFalse()
    assertThat(displayOffBanner.isVisible).isFalse()
    waitForCondition(2.seconds) { zoomToolbar.activationFactor == 0.0 }

    // When the playback panel is hidden, the previously expanded zoom toolbar expands again and the "Display off" indicator reappears;
    // the indicator stays hidden when the display is turned on.
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    assertThat(zoomToolbar.isActive).isTrue()
    assertThat(displayOffBanner.isVisible).isTrue()

    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(displayOffBanner.isVisible).isFalse()
    fakeEmulator.setDisplayPowerMode(PRIMARY_DISPLAY_ID, DisplayPowerModeNotification.PowerMode.ON)
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    assertFailsWith<TimeoutException> { waitForCondition(200.milliseconds) { displayOffBanner.isVisible } }
  }

  @Test
  fun testVisibilityLossDuringSliderDrag() {
    StudioFlags.EMBEDDED_EMULATOR_DISPLAY_OFF_INDICATOR.overrideForTest(true, emulatorViewRule.disposable)
    @Suppress("UnstableApiUsage") HeadlessDataManager.fallbackToProductionDataManager(emulatorViewRule.disposable)
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    displayPanel.zoomToolbarVisible = true
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
    fakeEmulator.setDisplayPowerMode(PRIMARY_DISPLAY_ID, DisplayPowerModeNotification.PowerMode.OFF)

    val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
    val playbackPanel = overlay.playbackPanel
    val displayOffBanner = fakeUi.getComponent<DisplayIndicatorPopup>()
    val slider = fakeUi.getComponent<ProgressSlider>()
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    waitForCondition(2.seconds) {
      slider.isEnabled && displayOffBanner.isVisible && fakeUi.findComponent<FloatingToolbarContainer>() != null
    }

    val zoomToolbar = fakeUi.getComponent<FloatingToolbarContainer>()
    val toolbarPanel = zoomToolbar.getComponent(0)
    waitForCondition(2.seconds) {
      ActivityTracker.getInstance().inc()
      fakeUi.updateToolbarsIfNecessary()
      fakeUi.layoutAndDispatchEvents()
      !toolbarPanel.bounds.isEmpty
    }
    zoomToolbar.triggerActivation()
    waitForCondition(2.seconds) { zoomToolbar.activationFactor == 1.0 }

    val changesSentCount = AtomicInteger()
    val originalOnChangesSent = playbackPanel.onChangesSent
    playbackPanel.onChangesSent = {
      changesSentCount.incrementAndGet()
      originalOnChangesSent?.invoke()
    }

    // Hover over the bottom area to show the playback panel (collapsing the zoom toolbar and hiding the "Display off" indicator).
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(zoomToolbar.isActive).isFalse()
    assertThat(displayOffBanner.isVisible).isFalse()

    // Start dragging the slider and wait until the seek RPC completes so pendingStreamRestart is armed.
    val sliderPos = fakeUi.getPosition(slider)
    val startX = sliderPos.x + slider.width / 4
    val endX = sliderPos.x + slider.width * 3 / 4
    val sliderCenterY = sliderPos.y + slider.height / 2
    fakeUi.mouse.press(startX, sliderCenterY)
    fakeUi.mouse.dragTo(endX, sliderCenterY)
    assertThat(slider.valueIsAdjusting).isTrue()
    assertThat(playbackPanel.isInteracting).isTrue()
    waitForCondition(2.seconds) { playbackPanel.pendingStreamRestart }
    assertThat(changesSentCount.get()).isEqualTo(0)

    // Hide the display panel mid-drag (simulating hiding/collapsing the tool window without delivering MOUSE_RELEASED to the slider).
    displayPanel.isVisible = false
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(playbackPanel.isInteracting).isFalse()
    assertThat(playbackPanel.isVisible).isFalse()
    assertThat(playbackPanel.pendingStreamRestart).isFalse()
    assertThat(changesSentCount.get()).isEqualTo(1)
    assertThat(zoomToolbar.isActive).isTrue()
    assertThat(displayOffBanner.isVisible).isTrue()

    // Showing the display panel again with the cursor away from the hover area keeps the playback panel hidden, leaves zoom toolbar and
    // display indicator restored, and resumes tracking incoming playback position updates.
    displayPanel.isVisible = true
    fakeUi.mouse.moveTo(215, 100)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()
    assertThat(zoomToolbar.isActive).isTrue()
    assertThat(displayOffBanner.isVisible).isTrue()

    fakeEmulator.updateEnvironmentAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(45_000L)
        .setDurationMs(60_000L)
        .build()
    )
    waitForCondition(2.seconds) { slider.value == 750 && timeLabel.text == "00:45 / 01:00" }
  }

  @Test
  fun testZoomToolbarCoordination() {
    @Suppress("UnstableApiUsage") HeadlessDataManager.fallbackToProductionDataManager(emulatorViewRule.disposable)
    val prevHorizontal = FloatingToolbarState.getInstance().zoomToolbarHorizontal
    try {
      FloatingToolbarState.getInstance().zoomToolbarHorizontal = false
      val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
      displayPanel.zoomToolbarVisible = true
      val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
      val fakeEmulator = emulatorViewRule.getFakeEmulator(displayPanel.displayView)
      waitForFirstFrame(displayPanel, fakeUi)

      setEnvironment(displayPanel.displayView.emulator, fakeEmulator, "videofile:/tmp/sample.mp4")
      val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
      val playbackPanel = overlay.playbackPanel
      val slider = fakeUi.getComponent<ProgressSlider>()
      waitForCondition(2.seconds) { slider.isEnabled && fakeUi.findComponent<FloatingToolbarContainer>() != null }

      fun waitForZoomToolbarLayout(): FloatingToolbarContainer {
        val toolbar = fakeUi.getComponent<FloatingToolbarContainer>()
        val child = toolbar.getComponent(0)
        waitForCondition(2.seconds) {
          ActivityTracker.getInstance().inc()
          fakeUi.updateToolbarsIfNecessary()
          fakeUi.layoutAndDispatchEvents()
          !child.bounds.isEmpty
        }
        return toolbar
      }

      val zoomToolbarVertical = waitForZoomToolbarLayout()
      val toolbarPanelVertical = zoomToolbarVertical.getComponent(0)
      val collapsedRect = SwingUtilities.convertRectangle(toolbarPanelVertical.parent, toolbarPanelVertical.bounds, displayPanel)
      val collapsedMargin = (min(collapsedRect.width, collapsedRect.height) / 2.0).roundToInt()
      val centerY = collapsedRect.centerY.toInt()
      val centerX = collapsedRect.centerX.toInt()

      // 1. Hovering over the collapsed zoom toolbar or within half of its smaller dimension on any side does not activate the panel.
      fakeUi.mouse.moveTo(centerX, centerY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()

      fakeUi.mouse.moveTo(collapsedRect.x - collapsedMargin, centerY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()

      fakeUi.mouse.moveTo((collapsedRect.x + collapsedRect.width + collapsedMargin - 1).coerceAtMost(displayPanel.width - 1), centerY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()

      fakeUi.mouse.moveTo(centerX, (collapsedRect.y + collapsedRect.height + collapsedMargin - 1).coerceAtMost(displayPanel.height - 1))
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()

      // Moving 1px outside the left margin (still inside the bottom panel area) activates the playback panel. Because the zoom toolbar was
      // already collapsed, hiding the playback panel afterward leaves the zoom toolbar collapsed.
      fakeUi.mouse.moveTo(collapsedRect.x - collapsedMargin - 1, centerY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()
      fakeUi.mouse.moveTo(100, 100)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarVertical.isActive).isFalse()

      // 2. Moving over the collapsed zoom toolbar while the playback panel is already shown keeps it shown, until the user clicks the zoom
      // indicator to expand the zoom toolbar.
      fakeUi.mouse.moveTo(collapsedRect.x - collapsedMargin - 1, centerY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()
      val zoomIndicatorButton = fakeUi.getComponent<ActionButton> { it.action is ZoomLevelIndicator }
      fakeUi.clickOn(zoomIndicatorButton)
      fakeUi.layoutAndDispatchEvents()
      assertThat(zoomToolbarVertical.isActive).isTrue()
      assertThat(playbackPanel.isVisible).isFalse()
      waitForCondition(2.seconds) { zoomToolbarVertical.activationFactor == 1.0 }
      fakeUi.layoutAndDispatchEvents()

      // 3. Hovering inside or within the margin of the expanded vertical zoom toolbar does not activate the playback panel.
      val expandedVerticalRect = SwingUtilities.convertRectangle(toolbarPanelVertical.parent, toolbarPanelVertical.bounds, displayPanel)
      assertThat(expandedVerticalRect.height).isGreaterThan(expandedVerticalRect.width)
      val verticalMargin = (min(expandedVerticalRect.width, expandedVerticalRect.height) / 2.0).roundToInt()
      fakeUi.mouse.moveTo(expandedVerticalRect.x - verticalMargin, 345)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarVertical.isActive).isTrue()

      // Moving 1px left of the non-activation area activates the playback panel and collapses the zoom toolbar; when the playback panel
      // hides again, the previously expanded zoom toolbar re-expands automatically.
      fakeUi.mouse.moveTo(expandedVerticalRect.x - verticalMargin - 1, 345)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()
      assertThat(zoomToolbarVertical.isActive).isFalse()
      waitForCondition(2.seconds) { zoomToolbarVertical.activationFactor == 0.0 }

      // 4. Moving away hides the playback panel and re-expands the zoom toolbar; showing the playback panel temporarily (e.g. on display
      // focus) collapses it again, and expanding the zoom toolbar while shown temporarily hides the playback panel.
      fakeUi.mouse.moveTo(100, 100)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarVertical.isActive).isTrue()
      waitForCondition(2.seconds) { zoomToolbarVertical.activationFactor == 1.0 }
      overlay.temporaryVisibilityDuration = 5.seconds
      displayPanel.displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayPanel.displayView, FocusEvent.FOCUS_GAINED)) }
      assertThat(playbackPanel.isVisible).isTrue()
      assertThat(zoomToolbarVertical.isActive).isFalse()
      waitForCondition(2.seconds) { zoomToolbarVertical.activationFactor == 0.0 }

      fakeUi.clickOn(zoomIndicatorButton)
      fakeUi.layoutAndDispatchEvents()
      assertThat(zoomToolbarVertical.isActive).isTrue()
      assertThat(playbackPanel.isVisible).isFalse()
      overlay.temporaryVisibilityDuration = Duration.ZERO

      // 5. Switch to horizontal zoom toolbar orientation: non-activation area covers the horizontal toolbar plus half its height on each
      // side, activating the playback panel collapses the horizontal zoom toolbar, and hiding the playback panel re-expands it (including
      // when a temporary show expires while the cursor rests on the horizontal zoom toolbar).
      fakeUi.mouse.moveTo(100, 100)
      FloatingToolbarState.getInstance().zoomToolbarHorizontal = true
      val zoomToolbarHorizontal = waitForZoomToolbarLayout()
      assertThat(zoomToolbarHorizontal.isActive).isTrue()
      waitForCondition(2.seconds) { zoomToolbarHorizontal.activationFactor == 1.0 }
      fakeUi.layoutAndDispatchEvents()

      val toolbarPanelHorizontal = zoomToolbarHorizontal.getComponent(0)
      val expandedHorizontalRect =
        SwingUtilities.convertRectangle(toolbarPanelHorizontal.parent, toolbarPanelHorizontal.bounds, displayPanel)
      assertThat(expandedHorizontalRect.width).isGreaterThan(expandedHorizontalRect.height)
      val horizontalMargin = (min(expandedHorizontalRect.width, expandedHorizontalRect.height) / 2.0).roundToInt()
      val expandedCenterY = expandedHorizontalRect.centerY.toInt()

      fakeUi.mouse.moveTo(expandedHorizontalRect.x, expandedCenterY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarHorizontal.isActive).isTrue()

      // Temporary show while the stationary cursor rests on the expanded horizontal zoom toolbar collapses it and re-expands it once the
      // temporary show expires, even if showTemporarily is triggered again after the zoom toolbar has already collapsed.
      overlay.temporaryVisibilityDuration = 5.seconds
      displayPanel.displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayPanel.displayView, FocusEvent.FOCUS_GAINED)) }
      assertThat(playbackPanel.isVisible).isTrue()
      assertThat(zoomToolbarHorizontal.isActive).isFalse()
      waitForCondition(2.seconds) { zoomToolbarHorizontal.activationFactor == 0.0 }
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()
      overlay.temporaryVisibilityDuration = 200.milliseconds
      displayPanel.displayView.focusListeners.forEach { it.focusGained(FocusEvent(displayPanel.displayView, FocusEvent.FOCUS_GAINED)) }
      assertThat(playbackPanel.isVisible).isTrue()
      waitForCondition(2.seconds) { !playbackPanel.isVisible }
      assertThat(zoomToolbarHorizontal.isActive).isTrue()
      waitForCondition(2.seconds) { zoomToolbarHorizontal.activationFactor == 1.0 }
      overlay.temporaryVisibilityDuration = Duration.ZERO

      fakeUi.mouse.moveTo(expandedHorizontalRect.x - horizontalMargin, expandedCenterY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarHorizontal.isActive).isTrue()

      fakeUi.mouse.moveTo(expandedHorizontalRect.x - horizontalMargin - 1, expandedCenterY)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isTrue()
      assertThat(zoomToolbarHorizontal.isActive).isFalse()
      waitForCondition(2.seconds) { zoomToolbarHorizontal.activationFactor == 0.0 }

      fakeUi.mouse.moveTo(100, 100)
      fakeUi.layoutAndDispatchEvents()
      assertThat(playbackPanel.isVisible).isFalse()
      assertThat(zoomToolbarHorizontal.isActive).isTrue()
      waitForCondition(2.seconds) { zoomToolbarHorizontal.activationFactor == 1.0 }
    } finally {
      FloatingToolbarState.getInstance().zoomToolbarHorizontal = prevHorizontal
    }
  }

  @Test
  fun testRequestFailuresAndPartialBatchRevert() {
    val displayView = emulatorViewRule.newEmulatorDisplayView({ FakeEmulator.createDisplayGlassesAvd(it) })
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayView)
    waitForCondition(5.seconds) { displayView.emulator.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val playbackPanel =
      VideoPlaybackPanel(displayView.emulator, emulatorViewRule.disposable).apply {
        isVisible = true
        size = Dimension(430, preferredSize.height)
      }
    val fakeUi = FakeUi(playbackPanel, parentDisposable = emulatorViewRule.disposable).apply { layoutAndDispatchEvents() }
    val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
    val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
    @Suppress("UNCHECKED_CAST")
    val speedComboBox = fakeUi.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" } as ComboBox<VideoPlaybackSpeed>
    val changesSentCount = AtomicInteger()
    playbackPanel.onChangesSent = { changesSentCount.incrementAndGet() }

    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(12_000L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(speedComboBox.selectedItem).isEqualTo(VideoPlaybackSpeed.SPEED_1X)
    assertThat(slider.value).isEqualTo(200)
    assertThat(timeLabel.text).isEqualTo("00:12 / 01:00")

    // 1. Earlier request in a batch succeeds (speed = 2X) and a subsequent request queued in the same drain loop fails (pause):
    // only the failed pause is reverted, while 2X speed is preserved.
    var r1Started = CountDownLatch(1)
    var r1Release = CountDownLatch(1)
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) { request ->
      if (request.state.hasPlaybackSpeed()) {
        r1Started.countDown()
        assertThat(r1Release.await(5, TimeUnit.SECONDS)).isTrue()
        Status.OK
      } else {
        Status.INTERNAL
      }
    }
    speedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_2X
    waitForCondition(2.seconds) { r1Started.count == 0L }
    fakeUi.clickOn(playPauseButton)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    r1Release.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == 1 }
    assertThat(speedComboBox.selectedItem).isEqualTo(VideoPlaybackSpeed.SPEED_2X)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)

    // 2. Earlier request in a batch fails (pause) and a subsequent request in the same drain loop succeeds (seek to 30s):
    // pause is reverted, while the accepted 30s seek is preserved.
    r1Started = CountDownLatch(1)
    r1Release = CountDownLatch(1)
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) { request ->
      if (request.state.hasState()) {
        r1Started.countDown()
        assertThat(r1Release.await(5, TimeUnit.SECONDS)).isTrue()
        Status.INTERNAL
      } else {
        Status.OK
      }
    }
    fakeUi.clickOn(playPauseButton)
    waitForCondition(2.seconds) { r1Started.count == 0L }
    slider.value = 500
    assertThat(timeLabel.text).isEqualTo("00:30 / 01:00")
    r1Release.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == 2 }
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(slider.value).isEqualTo(500)
    assertThat(timeLabel.text).isEqualTo("00:30 / 01:00")

    // 3. Earlier seek in a batch fails (15s) and a subsequent seek in the same drain loop succeeds (45s): 45s is preserved.
    r1Started = CountDownLatch(1)
    r1Release = CountDownLatch(1)
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) { request ->
      if (request.state.playbackPositionMs == 15_000L) {
        r1Started.countDown()
        assertThat(r1Release.await(5, TimeUnit.SECONDS)).isTrue()
        Status.INTERNAL
      } else {
        Status.OK
      }
    }
    slider.value = 250
    waitForCondition(2.seconds) { r1Started.count == 0L }
    slider.value = 750
    r1Release.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == 3 }
    assertThat(slider.value).isEqualTo(750)
    assertThat(timeLabel.text).isEqualTo("00:45 / 01:00")

    // 4. A failing in-flight request from before reset() does not revert a request queued for a new animation.
    r1Started = CountDownLatch(1)
    r1Release = CountDownLatch(1)
    val callIndex = AtomicInteger()
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) {
      if (callIndex.getAndIncrement() == 0) {
        r1Started.countDown()
        assertThat(r1Release.await(5, TimeUnit.SECONDS)).isTrue()
        Status.INTERNAL
      } else {
        Status.OK
      }
    }
    fakeUi.clickOn(playPauseButton)
    waitForCondition(2.seconds) { r1Started.count == 0L }
    playbackPanel.reset()
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(90_000L)
        .build()
    )
    fakeUi.clickOn(playPauseButton)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    r1Release.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == 4 }
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)

    // 5. A seek that fails while dragging the slider stays at the thumb position during the drag and reverts when the drag finishes.
    fakeEmulator.clearGrpcCallLog()
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) { request ->
      if (request.state.hasPlaybackPositionMs()) Status.INTERNAL else Status.OK
    }
    slider.valueIsAdjusting = true
    slider.value = 500
    assertThat(timeLabel.text).isEqualTo("00:45 / 01:30")
    // Queue a succeeding play toggle after the seek is in flight so changesSentCount signals that both requests have completed on the EDT.
    fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    fakeUi.clickOn(playPauseButton)
    waitForCondition(2.seconds) { changesSentCount.get() == 5 }
    assertThat(slider.value).isEqualTo(500)
    slider.valueIsAdjusting = false
    assertThat(slider.value).isEqualTo(0)
    assertThat(timeLabel.text).isEqualTo("00:00 / 01:30")
    fakeEmulator.clearGrpcCallHandlers()

    // 6. Receiving an unknown state while dragging the slider fires onStateChanged once, after controls are reset.
    slider.valueIsAdjusting = true
    slider.value = 400
    fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    val stateChangedNotifications = mutableListOf<Pair<Int, Boolean>>()
    playbackPanel.onStateChanged = { stateChangedNotifications.add(slider.value to slider.isEnabled) }
    playbackPanel.updateAnimationState(AnimationState.newBuilder().setState(AnimationState.State.STATE_UNKNOWN).build())
    assertThat(stateChangedNotifications).containsExactly(0 to false)
    playbackPanel.onStateChanged = null

    // 7. Recreating the gRPC channel mid-call throws RetryException, which retries setAnimationState instead of reverting.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    val retryCalls = AtomicInteger()
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) {
      if (retryCalls.incrementAndGet() == 1) {
        displayView.emulator.connectGrpcOrIncreaseMaxInboundMessageSize(20 * 1024 * 1024)
        Status.CANCELLED
      } else {
        Status.OK
      }
    }
    val beforeRetrySentCount = changesSentCount.get()
    fakeUi.clickOn(playPauseButton)
    waitForCondition(2.seconds) { changesSentCount.get() == beforeRetrySentCount + 1 }
    assertThat(retryCalls.get()).isEqualTo(2)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(fakeEmulator.environmentAnimationState.state).isEqualTo(AnimationState.State.STATE_PAUSED)
    fakeEmulator.clearGrpcCallHandlers()

    // 8. Requests queued or completing while a modal dialog is open still run to completion and fire onChangesSent.
    val modalDialog = dialog(title = "Modal", panel = JPanel(), project = emulatorViewRule.project)
    val beforeModalSentCount = changesSentCount.get()
    createModalDialogAndInteractWithIt(modalDialog::show) { dlg ->
      fakeUi.clickOn(playPauseButton)
      waitForCondition(2.seconds) { changesSentCount.get() == beforeModalSentCount + 1 }
      assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
      assertThat(fakeEmulator.environmentAnimationState.state).isEqualTo(AnimationState.State.STATE_PLAYING)
      dlg.close(DialogWrapper.CLOSE_EXIT_CODE)
    }

    // 9a. Receiving a non-seekable duration (0) mid-drag while a seek is in flight and another seek + pause are queued:
    // cancels the drag, resets slider/label to 00:00 / 00:00, discards queued/retried seeks while preserving play/pause changes,
    // and fires onStateChanged once after controls are updated.
    val sliderPos = fakeUi.getPosition(slider)
    val sliderCenterY = sliderPos.y + slider.height / 2
    val seekStarted = CountDownLatch(1)
    val seekRelease = CountDownLatch(1)
    val seekCalls = AtomicInteger()
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) { request ->
      if (request.state.hasPlaybackPositionMs()) {
        seekCalls.incrementAndGet()
        seekStarted.countDown()
        assertThat(seekRelease.await(5, TimeUnit.SECONDS)).isTrue()
      }
      Status.OK
    }
    val beforeInFlightCancelSentCount = changesSentCount.get()
    fakeUi.mouse.press(sliderPos.x + slider.width / 4, sliderCenterY)
    waitForCondition(2.seconds) { seekStarted.count == 0L }
    fakeUi.mouse.dragTo(sliderPos.x + slider.width * 3 / 4, sliderCenterY)
    playbackPanel.togglePlayback()
    assertThat(playbackPanel.isInteracting).isTrue()
    val nonSeekableNotifications = mutableListOf<Triple<Boolean, Int, Boolean>>()
    playbackPanel.onStateChanged = {
      nonSeekableNotifications.add(Triple(playbackPanel.isInteracting, slider.value, slider.isEnabled))
    }
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder().setState(AnimationState.State.STATE_PLAYING).setPlaybackPositionMs(0L).setDurationMs(0L).build()
    )
    assertThat(nonSeekableNotifications).containsExactly(Triple(false, 0, false))
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:00")
    assertThat(changesSentCount.get()).isEqualTo(beforeInFlightCancelSentCount)
    seekRelease.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == beforeInFlightCancelSentCount + 1 }
    assertThat(seekCalls.get()).isEqualTo(1)
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:00")
    fakeUi.mouse.release()
    assertThat(playbackPanel.isInteracting).isFalse()
    playbackPanel.onStateChanged = null
    fakeEmulator.clearGrpcCallHandlers()

    // 9b. Restoring a positive duration re-enables the slider; if a seek already completed mid-drag (setting pendingStreamRestart),
    // receiving duration 0 flushes onChangesSent synchronously.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder().setState(AnimationState.State.STATE_PLAYING).setPlaybackPositionMs(20_000L).setDurationMs(60_000L).build()
    )
    assertThat(slider.isEnabled).isTrue()
    assertThat(slider.value).isEqualTo(333)
    assertThat(timeLabel.text).isEqualTo("00:20 / 01:00")

    val beforeFlushSentCount = changesSentCount.get()
    fakeUi.mouse.press(sliderPos.x + slider.width / 4, sliderCenterY)
    fakeUi.mouse.dragTo(sliderPos.x + slider.width * 3 / 4, sliderCenterY)
    waitForCondition(2.seconds) { playbackPanel.pendingStreamRestart }
    assertThat(changesSentCount.get()).isEqualTo(beforeFlushSentCount)
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder().setState(AnimationState.State.STATE_PLAYING).setPlaybackPositionMs(0L).setDurationMs(0L).build()
    )
    assertThat(changesSentCount.get()).isEqualTo(beforeFlushSentCount + 1)
    assertThat(playbackPanel.pendingStreamRestart).isFalse()
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:00")
    fakeUi.mouse.release()

    // 10. If a seek succeeds mid-drag (setting pendingStreamRestart) and a subsequent seek fails after the drag ends,
    // finishRequests() still flushes pendingStreamRestart and fires onChangesSent.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder().setState(AnimationState.State.STATE_PLAYING).setPlaybackPositionMs(0L).setDurationMs(60_000L).build()
    )
    val secondSeekStarted = CountDownLatch(1)
    val secondSeekRelease = CountDownLatch(1)
    val item10SeekCalls = AtomicInteger()
    fakeEmulator.setGrpcCallHandler(EmulatorControllerGrpc.getSetAnimationStateMethod()) {
      if (item10SeekCalls.incrementAndGet() == 1) {
        Status.OK
      } else {
        secondSeekStarted.countDown()
        assertThat(secondSeekRelease.await(5, TimeUnit.SECONDS)).isTrue()
        Status.INTERNAL
      }
    }
    val beforeItem10SentCount = changesSentCount.get()
    fakeUi.mouse.press(sliderPos.x + slider.width / 4, sliderCenterY)
    val firstDragValue = slider.value
    waitForCondition(2.seconds) { playbackPanel.pendingStreamRestart }
    fakeUi.mouse.dragTo(sliderPos.x + slider.width * 3 / 4, sliderCenterY)
    waitForCondition(2.seconds) { secondSeekStarted.count == 0L }
    fakeUi.mouse.release()
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.pendingStreamRestart).isTrue()
    assertThat(changesSentCount.get()).isEqualTo(beforeItem10SentCount)
    secondSeekRelease.countDown()
    waitForCondition(2.seconds) { changesSentCount.get() == beforeItem10SentCount + 1 }
    assertThat(playbackPanel.pendingStreamRestart).isFalse()
    assertThat(slider.value).isEqualTo(firstDragValue)
    fakeEmulator.clearGrpcCallHandlers()
  }

  @Test
  fun testNonGlassesDeviceHasNoVideoPlaybackPanel() {
    val displayPanel = emulatorViewRule.newEmulatorDisplayPanel()
    val fakeUi = createFakeUi(displayPanel, Dimension(300, 600))
    assertThat(fakeUi.findComponent<VideoPlaybackPanel>()).isNull()
  }

  @Test
  fun testDisabledFlagHasNoVideoPlaybackOverlay() {
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(false, emulatorViewRule.disposable)
    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val fakeUi = createFakeUi(displayPanel, Dimension(430, 360))
    assertThat(fakeUi.findComponent<VideoPlaybackOverlay>()).isNull()
    assertThat(fakeUi.findComponent<VideoPlaybackPanel>()).isNull()
  }

  @Test
  fun test360VideoPlaybackAndHiddenDuringShiftCameraRotation() {
    val mouseInfo = mockStatic<MouseInfo>(emulatorViewRule.disposable)
    val pointerInfo = mock<PointerInfo>()
    whenever(pointerInfo.location).thenReturn(Point(215, 180))
    mouseInfo.`when`<PointerInfo> { MouseInfo.getPointerInfo() }.thenReturn(pointerInfo)

    val displayPanel = createGlassesDisplayPanel { FakeEmulator.createDisplayGlassesAvd(it) }
    val container = HeadlessRootPaneContainer(NotificationHolderPanel(displayPanel))
    container.rootPane.size = Dimension(430, 360)
    val fakeUi =
      FakeUi(container.rootPane, parentDisposable = emulatorViewRule.disposable).apply {
        layoutAndDispatchEvents()
        getComponent<VideoPlaybackOverlay>().temporaryVisibilityDuration = Duration.ZERO
      }
    val displayView = displayPanel.displayView
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayView)
    waitForFirstFrame(displayPanel, fakeUi)

    val focusManager = FakeKeyboardFocusManager(emulatorViewRule.disposable)
    focusManager.focusOwner = displayView

    setEnvironment(displayView.emulator, fakeEmulator, "video360:/tmp/sample360.mp4")

    val overlay = fakeUi.getComponent<VideoPlaybackOverlay>()
    val playbackPanel = fakeUi.getComponent<VideoPlaybackPanel>()
    val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }

    // Pressing and releasing Shift while the panel is hidden with no mouse movement keeps it hidden.
    fakeUi.mouse.moveTo(215, 180)
    waitForCondition(2.seconds) { playPauseButton.isEnabled }
    assertThat(playbackPanel.isVisible).isFalse()
    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()
    assertThat(playbackPanel.isVisible).isFalse()
    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isFalse()

    // Hovering over the bottom area shows the playback panel for a 360-degree video; Shift does not steal mouse input while hovering the
    // panel.
    whenever(pointerInfo.location).thenReturn(Point(215, 345))
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.layoutAndDispatchEvents()
    waitForCondition(2.seconds) { playbackPanel.isVisible && playPauseButton.isEnabled }
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isTrue()
    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()

    // When the panel is shown temporarily with the cursor over the scene, holding Shift rotates the camera and hides the panel.
    overlay.temporaryVisibilityDuration = 10.seconds
    whenever(pointerInfo.location).thenReturn(Point(215, 180))
    fakeUi.mouse.moveTo(215, 180)
    fakeUi.keyboard.press(KeyEvent.VK_SPACE)
    fakeUi.keyboard.release(KeyEvent.VK_SPACE)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()

    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()
    assertThat(playbackPanel.isVisible).isFalse()

    // Moving the mouse into the bottom area while Shift is held does not show the playback panel.
    whenever(pointerInfo.location).thenReturn(Point(220, 345))
    fakeUi.mouse.moveTo(220, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isFalse()

    // Releasing Shift keeps it suppressed until the mouse actually moves in normal mode.
    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isFalse()

    whenever(pointerInfo.location).thenReturn(Point(225, 345))
    fakeUi.mouse.moveTo(225, 345)
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isVisible).isTrue()

    // Starting a 360-degree video while Shift is already held defers temporary visibility until Shift is released.
    whenever(pointerInfo.location).thenReturn(Point(215, 180))
    fakeUi.mouse.moveTo(215, 180)
    setEnvironment(displayView.emulator, fakeEmulator, "image360:/tmp/photo360.jpg")
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()

    setEnvironment(displayView.emulator, fakeEmulator, "video360:/tmp/second360.mp4")
    waitForCondition(2.seconds) { playPauseButton.isEnabled }
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()
    assertThat(playbackPanel.isVisible).isFalse()

    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()?.text).isEqualTo("Hold Shift to rotate camera")

    // If the cursor is inside playbackPanel.bounds when releasing Shift makes a deferred panel visible, the camera prompt must stay hidden.
    setEnvironment(displayView.emulator, fakeEmulator, "image360:/tmp/photo360.jpg")
    waitForCondition(2.seconds) { !playbackPanel.isVisible }
    whenever(pointerInfo.location).thenReturn(Point(215, 345))
    fakeUi.mouse.moveTo(215, 345)
    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()?.text).isEqualTo("Rotate camera with mouse or arrow keys")

    setEnvironment(displayView.emulator, fakeEmulator, "video360:/tmp/third360.mp4")
    waitForCondition(2.seconds) { playPauseButton.isEnabled }
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isTrue()
    assertThat(playbackPanel.isVisible).isFalse()

    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isTrue()
    assertThat(fakeUi.findComponent<EditorNotificationPanel>()).isNull()

    // A non-360 videofile: environment does not activate virtual scene camera on Shift and keeps the panel visible.
    setEnvironment(displayView.emulator, fakeEmulator, "videofile:/tmp/flat.mp4")
    waitForCondition(2.seconds) { playbackPanel.isVisible && playPauseButton.isEnabled }
    fakeUi.keyboard.press(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
    assertThat(displayView.isOperatingVirtualSceneCamera).isFalse()
    assertThat(playbackPanel.isVisible).isTrue()
    fakeUi.keyboard.release(KeyEvent.VK_SHIFT)
    fakeUi.layoutAndDispatchEvents()
  }

  private fun createGlassesDisplayPanel(avdCreator: (Path) -> Path): EmulatorDisplayPanel =
    emulatorViewRule.newEmulatorDisplayPanel(avdCreator)

  private fun createFakeUi(displayPanel: EmulatorDisplayPanel, size: Dimension): FakeUi {
    displayPanel.size = size
    return FakeUi(displayPanel, parentDisposable = emulatorViewRule.disposable).apply {
      layoutAndDispatchEvents()
      // Unless a test is specifically about it, don't show the playback panel temporarily without the mouse hovering over it.
      findComponent<VideoPlaybackOverlay>()?.temporaryVisibilityDuration = Duration.ZERO
    }
  }

  private fun waitForFirstFrame(displayPanel: EmulatorDisplayPanel, fakeUi: FakeUi) {
    waitForCondition(5.seconds) {
      fakeUi.layoutAndDispatchEvents()
      fakeUi.render()
      displayPanel.displayView.frameNumber > 0u
    }
    fakeUi.layoutAndDispatchEvents()
  }

  private fun renderPanelOverSolidColor(panel: VideoPlaybackPanel, backdrop: Color): BufferedImage =
    BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB).also { img ->
      val g = img.createGraphics()
      try {
        g.color = backdrop
        g.fillRect(0, 0, panel.width, panel.height)
        panel.paint(g)
      } finally {
        g.dispose()
      }
    }

  private fun setEnvironment(emulatorController: EmulatorController, fakeEmulator: FakeEmulator, sceneMode: String) {
    val env = Environment.newBuilder().putEnvironment("scene.mode", sceneMode).build()
    runBlocking { emulatorController.setEnvironment(env) }
    EnvironmentTracker.forEmulator(emulatorController)!!.environment = env
    fakeEmulator.clearGrpcCallLog()
  }
}
