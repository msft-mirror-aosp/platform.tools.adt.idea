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

import com.android.emulator.control.AnimationState
import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.SetAnimationStateRequest
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.FakeEmulator.Companion.IGNORE_SCREENSHOT_CALL_FILTER
import com.android.tools.idea.streaming.emulator.dialogs.IconButton
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.sliderValueToPosition
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.dialog
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicComboBoxUI
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [VideoPlaybackPanel]. */
@RunsInEdt
class VideoPlaybackPanelTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val emulatorViewRule = EmulatorViewRule()
  @get:Rule val ruleChain = RuleChain(emulatorViewRule, ClipboardSynchronizationDisablementRule(), HeadlessDialogRule(), EdtRule())

  private val callFilter = IGNORE_SCREENSHOT_CALL_FILTER.or("android.emulation.control.EmulatorController/streamClipboard")

  @Test
  fun testPlayPauseAndStateTransitions() {
    val (playbackPanel, fakeUi, fakeEmulator) = createPlaybackPanel()
    val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
    val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }

    assertThat(playbackPanel.hasAnimation).isFalse()
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(slider.isEnabled).isFalse()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")

    var stateChangedCount = 0
    playbackPanel.onStateChanged = { stateChangedCount++ }

    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(playbackPanel.hasAnimation).isTrue()
    assertThat(stateChangedCount).isEqualTo(1)
    assertThat(playPauseButton.isEnabled).isTrue()
    assertThat(slider.isEnabled).isTrue()
    assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Pause)
    assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Pause")
    assertThat(timeLabel.text).isEqualTo("00:00 / 01:00")
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

    // Unknown state values disable controls and clear hasAnimation.
    playbackPanel.updateAnimationState(AnimationState.newBuilder().setStateValue(99).setDurationMs(60_000L).build())
    assertThat(playbackPanel.hasAnimation).isFalse()
    assertThat(stateChangedCount).isEqualTo(2)
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(slider.isEnabled).isFalse()

    // Restoring a known state re-enables controls, and reset() clears them again.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PAUSED)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(15_000L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(playbackPanel.hasAnimation).isTrue()
    assertThat(timeLabel.text).isEqualTo("00:15 / 01:00")

    playbackPanel.reset()
    assertThat(playbackPanel.hasAnimation).isFalse()
    assertThat(playPauseButton.isEnabled).isFalse()
    assertThat(slider.isEnabled).isFalse()
    assertThat(timeLabel.text).isEqualTo("00:00 / 00:00")
  }

  @Test
  fun testProgressSliderScrubbingAndStreamUpdates() {
    val (playbackPanel, fakeUi, fakeEmulator) = createPlaybackPanel()
    val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
    val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }

    val changesSentCount = AtomicInteger()
    playbackPanel.onChangesSent = { changesSentCount.incrementAndGet() }

    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(slider.isEnabled).isTrue()
    assertThat(timeLabel.text).isEqualTo("00:00 / 01:00")
    fakeEmulator.clearGrpcCallLog()

    // Server streams a position/duration update; UI updates without echoing setAnimationState.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(30_000L)
        .setDurationMs(120_000L)
        .build()
    )
    assertThat(slider.value).isEqualTo(250)
    assertThat(timeLabel.text).isEqualTo("00:30 / 02:00")
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

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
    assertThat(playbackPanel.isInteracting).isTrue()
    assertThat(slider.value).isGreaterThan(500)

    fakeUi.mouse.release()
    fakeUi.layoutAndDispatchEvents()
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(playbackPanel.isInteracting).isFalse()
    val expectedPositionMs = sliderValueToPosition(slider.value, 120.seconds).inWholeMilliseconds
    assertThat(expectedPositionMs).isGreaterThan(60_000L)

    slider.valueIsAdjusting = true
    assertThat(playbackPanel.isInteracting).isTrue()
    playbackPanel.cancelInteraction()
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(playbackPanel.isInteracting).isFalse()

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
    val (playbackPanel, fakeUi, fakeEmulator) = createPlaybackPanel()
    @Suppress("UNCHECKED_CAST")
    val speedComboBox = fakeUi.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" } as ComboBox<VideoPlaybackSpeed>

    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(1f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(speedComboBox.isEnabled).isTrue()

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
    waitForCondition(2.seconds) { fakeEmulator.environmentAnimationState.playbackSpeed == 0.2f }
    fakeUi.layoutAndDispatchEvents()

    // Server streams speed change to 0.5X; combo box updates without echoing setAnimationState.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(0.5f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(speedComboBox.selectedItem).isEqualTo(VideoPlaybackSpeed.SPEED_0_5X)
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

    // Server streams a speed that is not one of the presets; the combo box shows it without adding it to the list of choices.
    playbackPanel.updateAnimationState(
      AnimationState.newBuilder()
        .setState(AnimationState.State.STATE_PLAYING)
        .setPlaybackSpeed(0.75f)
        .setPlaybackPositionMs(0L)
        .setDurationMs(60_000L)
        .build()
    )
    assertThat(speedComboBox.selectedItem?.toString()).isEqualTo("0.75X")
    assertThat(speedComboBox.itemCount).isEqualTo(6)
    assertFailsWith<TimeoutException> { fakeEmulator.getNextGrpcCall(200.milliseconds, callFilter) }

    // User selects a preset again.
    speedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_1X
    val call1x = fakeEmulator.getNextGrpcCall(2.seconds, callFilter)
    assertThat(shortDebugString(call1x.request)).isEqualTo("state { playback_speed: 1.0 }")

    // Opening and closing the popup toggles isInteracting and notifies onStateChanged; cancelInteraction() hides the popup.
    var stateChanged = false
    playbackPanel.onStateChanged = { stateChanged = true }
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
    assertThat(playbackPanel.isInteracting).isTrue()
    playbackPanel.cancelInteraction()
    fakeUi.layoutAndDispatchEvents()
    assertThat(playbackPanel.isInteracting).isFalse()
    assertThat(stateChanged).isTrue()

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
  fun testPanelBackgroundIsSemitransparentAndControlsAreOpaque() {
    val prevStartBg = UIManager.get("Button.startBackground")
    val prevEndBg = UIManager.get("Button.endBackground")
    try {
      // Simulate themes (such as Islands Dark) where Button.startBackground / endBackground are transparent.
      UIManager.put("Button.startBackground", Color(0x19, 0x1A, 0x1C, 0x00))
      UIManager.put("Button.endBackground", Color(0x19, 0x1A, 0x1C, 0x00))

      val (playbackPanel, fakeUi, _) = createPlaybackPanel()
      val slider = fakeUi.getComponent<ProgressSlider> { it.name == "playbackSlider" }
      val timeLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackTimeLabel" }
      val speedLabel = fakeUi.getComponent<JBLabel> { it.name == "playbackSpeedLabel" }
      val playPauseButton = fakeUi.getComponent<IconButton> { it.name == "playPauseButton" }
      @Suppress("UNCHECKED_CAST")
      val speedComboBox = fakeUi.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" } as ComboBox<VideoPlaybackSpeed>

      playbackPanel.updateAnimationState(
        AnimationState.newBuilder()
          .setState(AnimationState.State.STATE_PLAYING)
          .setPlaybackSpeed(1f)
          .setPlaybackPositionMs(30_000L)
          .setDurationMs(60_000L)
          .build()
      )
      fakeUi.layoutAndDispatchEvents()
      assertThat(slider.isEnabled).isTrue()
      assertThat(slider.value).isEqualTo(500)

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

      // Verify when controls are disabled (e.g., STATE_UNKNOWN disables controls).
      playbackPanel.updateAnimationState(AnimationState.newBuilder().setState(AnimationState.State.STATE_UNKNOWN).build())
      assertThat(playPauseButton.isEnabled).isFalse()
      assertThat(slider.isEnabled).isFalse()
      assertThat(speedComboBox.isEnabled).isFalse()
      fakeUi.layoutAndDispatchEvents()
      verifyPanelAndControlOpacity()
    } finally {
      UIManager.put("Button.startBackground", prevStartBg)
      UIManager.put("Button.endBackground", prevEndBg)
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

  private fun createPlaybackPanel(): Triple<VideoPlaybackPanel, FakeUi, FakeEmulator> {
    val displayView = emulatorViewRule.newEmulatorDisplayView({ FakeEmulator.createDisplayGlassesAvd(it) })
    val fakeEmulator = emulatorViewRule.getFakeEmulator(displayView)
    waitForCondition(5.seconds) { displayView.emulator.connectionState == EmulatorController.ConnectionState.CONNECTED }
    val playbackPanel =
      VideoPlaybackPanel(displayView.emulator, emulatorViewRule.disposable).apply {
        isVisible = true
        size = Dimension(430, preferredSize.height)
      }
    val fakeUi = FakeUi(playbackPanel, parentDisposable = emulatorViewRule.disposable).apply { layoutAndDispatchEvents() }
    return Triple(playbackPanel, fakeUi, fakeEmulator)
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
}
