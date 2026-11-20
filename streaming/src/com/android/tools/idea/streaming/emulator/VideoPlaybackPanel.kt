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
import com.android.emulator.control.AnimationState.State.STATE_PAUSED
import com.android.emulator.control.AnimationState.State.STATE_PLAYING
import com.android.emulator.control.SetAnimationStateRequest
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.emulator.dialogs.IconButton
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.SLIDER_MAX
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.formatTimeProgress
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.positionToSliderValue
import com.android.tools.idea.streaming.emulator.dialogs.SensorSessionPlayer.Companion.sliderValueToPosition
import com.android.utils.throwIfCancellation
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Color
import java.awt.Cursor
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

/**
 * Playback speed of an AI Glasses video environment. The speed selector offers [PRESETS] to choose from, but is also able to display any
 * other speed reported by the emulator.
 */
internal data class VideoPlaybackSpeed(val multiplier: Float) {

  override fun toString(): String = String.format(Locale.ROOT, "%.2f", multiplier).trimEnd('0').trimEnd('.') + "X"

  companion object {
    val SPEED_0_1X = VideoPlaybackSpeed(0.1f)
    val SPEED_0_2X = VideoPlaybackSpeed(0.2f)
    val SPEED_0_5X = VideoPlaybackSpeed(0.5f)
    val SPEED_1X = VideoPlaybackSpeed(1f)
    val SPEED_2X = VideoPlaybackSpeed(2f)
    val SPEED_5X = VideoPlaybackSpeed(5f)

    /** The speeds offered by the speed selector. */
    val PRESETS: List<VideoPlaybackSpeed> = listOf(SPEED_0_1X, SPEED_0_2X, SPEED_0_5X, SPEED_1X, SPEED_2X, SPEED_5X)

    /** Returns the preset matching [multiplier], or a speed that is not one of the presets if there is no matching preset. */
    fun fromMultiplier(multiplier: Float): VideoPlaybackSpeed =
      PRESETS.firstOrNull { abs(it.multiplier - multiplier) < 0.005f } ?: VideoPlaybackSpeed(multiplier)
  }
}

/**
 * Semi-transparent playback control panel containing a [ProgressSlider], time progress label, Play/Pause button, and playback speed
 * selector, laid out similarly to `SensorReplayDialog`.
 */
internal class VideoPlaybackPanel(
  private val emulator: EmulatorController,
  parentDisposable: Disposable,
) : BorderLayoutPanel(), Disposable {

  private val coroutineScope = createCoroutineScope()
  private val sendTrigger = Channel<Unit>(Channel.CONFLATED)

  private lateinit var playbackSlider: ProgressSlider
  private lateinit var playbackTimeLabel: JBLabel
  private lateinit var playPauseButton: IconButton
  private lateinit var playbackSpeedComboBox: ComboBox<VideoPlaybackSpeed>

  @Volatile private var isDisposed = false
  private var isUpdatingUi = false
  private var wasSliderAdjusting = false
  private var lastSliderValue = 0
  /** The state reflected by the controls, or null if the controls have not been updated yet. */
  private var controlsState: ControlsState? = null

  var hasAnimation = false
    private set

  private var isPlaying = false
  private var emulatorIsPlaying = false
  private var emulatorSpeed: VideoPlaybackSpeed = VideoPlaybackSpeed.SPEED_1X
  private var duration: Duration = Duration.ZERO
  private var currentPosition: Duration = Duration.ZERO
  private var emulatorPosition: Duration = Duration.ZERO

  private var changeGeneration = 0L
  private var seekGeneration = 0L
  private var requestInFlight = false
  private var batchAcceptedAnyChange = false
  private var lastRequestedPositionMs: Long? = null
  // User changes that haven't been sent to the emulator yet.
  private var queuedState: AnimationState.State? = null
  private var queuedSpeed: Float? = null
  private var queuedPositionMs: Long? = null
  // User changes that have been sent to the emulator, but are not yet known to be reflected in the states received from it.
  private var inFlightState: AnimationState.State? = null
  private var inFlightSpeed: Float? = null
  private var inFlightPositionMs: Long? = null
  // User changes accepted by the emulator during the current batch of requests.
  private var acceptedState: AnimationState.State? = null
  private var acceptedSpeed: Float? = null
  private var acceptedPositionMs: Long? = null
  private var pendingPositionRevert = false
  @VisibleForTesting
  internal var pendingStreamRestart = false
    private set

  var onStateChanged: (() -> Unit)? = null

  /**
   * Called when all user changes have been sent to the emulator. The states received from the emulator before this call don't necessarily
   * reflect the changes, so the callback is expected to make sure that such states are not passed to [updateAnimationState] afterward.
   */
  var onChangesSent: (() -> Unit)? = null

  val isInteracting: Boolean
    get() =
      (::playbackSlider.isInitialized && playbackSlider.valueIsAdjusting) ||
        (::playbackSpeedComboBox.isInitialized && playbackSpeedComboBox.isPopupVisible)

  fun cancelInteraction() {
    if (::playbackSpeedComboBox.isInitialized && playbackSpeedComboBox.isPopupVisible) {
      playbackSpeedComboBox.hidePopup()
    }
    if (::playbackSlider.isInitialized && playbackSlider.valueIsAdjusting) {
      updateUi { playbackSlider.valueIsAdjusting = false }
    }
  }

  init {
    Disposer.register(parentDisposable, this)
    name = "videoPlaybackPanel"
    isOpaque = false
    isVisible = false
    cursor = Cursor.getDefaultCursor()
    val mouseBlocker = object : MouseAdapter() {}
    addMouseListener(mouseBlocker)
    addMouseMotionListener(mouseBlocker)
    addMouseWheelListener { it.consume() }

    val contentPanel = panel {
      row {
        playbackSlider =
          ProgressSlider(0, SLIDER_MAX, 0).apply {
            name = "playbackSlider"
            accessibleContext.accessibleName = message("video.playback.accessible.playback.progress")
            addChangeListener { onPlaybackSliderChanged() }
          }
        cell(playbackSlider).align(AlignX.FILL).resizableColumn()

        playbackTimeLabel =
          JBLabel(formatTimeProgress(Duration.ZERO, Duration.ZERO)).apply {
            name = "playbackTimeLabel"
            foreground = LABEL_FOREGROUND_COLOR
          }
        cell(playbackTimeLabel)
      }

      row {
        val playLabel = message("video.playback.button.play")
        playPauseButton =
          OpaqueIconButton(AllIcons.Actions.Execute, playLabel) { togglePlayback() }
            .apply {
              name = "playPauseButton"
            }
        cell(playPauseButton).accessibleName(playLabel)

        placeholder().resizableColumn()

        val speedLabelText = message("video.playback.label.playback.speed")
        val speedLabel =
          JBLabel(speedLabelText).apply {
            name = "playbackSpeedLabel"
            foreground = LABEL_FOREGROUND_COLOR
          }
        comboBox(
            DefaultComboBoxModel(VideoPlaybackSpeed.PRESETS.toTypedArray()),
            renderer = textListCellRenderer { it?.toString() ?: "" },
          )
          .label(speedLabel, LabelPosition.LEFT)
          .accessibleName(speedLabelText.removeSuffix(":"))
          .applyToComponent {
            name = "playbackSpeedComboBox"
            isOpaque = false
            background = JBColor.lazy {
              val base = if (isEnabled) COMBO_BOX_BACKGROUND_COLOR else UIUtil.getComboBoxDisabledBackground()
              ColorUtil.alphaBlending(base, OPAQUE_PANEL_BACKGROUND)
            }
            playbackSpeedComboBox = this
            selectedItem = VideoPlaybackSpeed.SPEED_1X
            addItemListener { event ->
              if (!isUpdatingUi && event.stateChange == ItemEvent.SELECTED) {
                (event.item as? VideoPlaybackSpeed)?.let(::onSpeedSelected)
              }
            }
            addPopupMenuListener(
              object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {}

                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                  EventQueue.invokeLater {
                    if (!isDisposed) {
                      onStateChanged?.invoke()
                    }
                  }
                }

                override fun popupMenuCanceled(event: PopupMenuEvent) {
                  EventQueue.invokeLater {
                    if (!isDisposed) {
                      onStateChanged?.invoke()
                    }
                  }
                }
              }
            )
          }
      }
    }
      .apply {
        isOpaque = false
        border = JBUI.Borders.empty(VERTICAL_PADDING, HORIZONTAL_PADDING)
      }

    addToCenter(contentPanel)
    @Suppress("UnstableApiUsage") ClientProperty.putRecursive(this, IdeBackgroundUtil.NO_BACKGROUND, true)
    updateControlsState()

    coroutineScope.launch {
      val uiContext = Dispatchers.EDT + ModalityState.any().asContextElement()
      for (unit in sendTrigger) {
        var next = withContext(uiContext) { takeQueuedRequest() }
        while (next != null) {
          var succeeded = false
          var retry = false
          try {
            emulator.setAnimationState(next.request)
            succeeded = true
          } catch (_: EmulatorController.RetryException) {
            currentCoroutineContext().ensureActive()
            retry = true
          } catch (e: Exception) {
            e.throwIfCancellation()
          }
          val sent = next
          next =
            withContext(uiContext) {
              if (retry) {
                val retryRequest = prepareRetry(sent) ?: takeQueuedRequest()
                if (retryRequest == null) {
                  finishRequests()
                }
                retryRequest
              } else {
                onRequestCompleted(sent, succeeded)
                val following = takeQueuedRequest()
                if (following == null) {
                  finishRequests()
                }
                following
              }
            }
        }
      }
    }
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.color = ColorUtil.withAlpha(JBColor.PanelBackground, BACKGROUND_ALPHA)
      g2.fillRect(0, 0, width, height)
    } finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  /** Called when a state is received from the animation state stream. */
  fun updateAnimationState(state: AnimationState) {
    val previousHasAnimation = hasAnimation
    val reportedState = if (state.hasState()) state.state else null
    // Any state other than playing or paused, including the ones introduced by future versions of the emulator, is not a known one.
    if (reportedState != null && reportedState != STATE_PLAYING && reportedState != STATE_PAUSED) {
      hasAnimation = false
      isPlaying = false
      emulatorIsPlaying = false
      duration = Duration.ZERO
      currentPosition = Duration.ZERO
      emulatorPosition = Duration.ZERO
      wasSliderAdjusting = false
      clearPendingChanges()
      if (::playbackSpeedComboBox.isInitialized && playbackSpeedComboBox.isPopupVisible) {
        playbackSpeedComboBox.hidePopup()
      }
      updateUi {
        playbackSlider.valueIsAdjusting = false
        playbackSlider.value = 0
        playbackTimeLabel.text = formatTimeProgress(Duration.ZERO, Duration.ZERO)
        if (playbackSpeedComboBox.selectedItem != emulatorSpeed) {
          playbackSpeedComboBox.model.selectedItem = emulatorSpeed
        }
      }
      updateControlsState()
      if (previousHasAnimation != hasAnimation) {
        onStateChanged?.invoke()
      }
      return
    }

    if (reportedState != null) {
      hasAnimation = true
      if (acceptedState == null) {
        emulatorIsPlaying = reportedState == STATE_PLAYING
      }
      val pendingPlay = queuedState ?: inFlightState
      if (pendingPlay == null || reportedState == pendingPlay) {
        isPlaying = reportedState == STATE_PLAYING
      }
    }

    if (state.hasDurationMs()) {
      duration = state.durationMs.coerceAtLeast(0L).milliseconds
      if (duration.isPositive()) {
        emulatorPosition = emulatorPosition.coerceAtMost(duration)
      }
    }

    val seekable = hasAnimation && duration.isPositive()
    val cancelledSliderAdjusting = !seekable && playbackSlider.valueIsAdjusting
    if (!seekable) {
      if (cancelledSliderAdjusting || queuedPositionMs != null || inFlightPositionMs != null || pendingPositionRevert) {
        currentPosition = emulatorPosition
      }
      discardPendingSeeks()
    }
    if (cancelledSliderAdjusting) {
      wasSliderAdjusting = false
      updateUi { playbackSlider.valueIsAdjusting = false }
    }

    if (state.hasPlaybackSpeed() && state.playbackSpeed.isFinite() && state.playbackSpeed > 0f) {
      val speed = VideoPlaybackSpeed.fromMultiplier(state.playbackSpeed)
      if (acceptedSpeed == null) {
        emulatorSpeed = speed
      }
      if (queuedSpeed == null && inFlightSpeed == null) {
        updateUi {
          if (playbackSpeedComboBox.selectedItem != speed) {
            // The selection is changed through the model because JComboBox.setSelectedItem ignores items that are not in the model,
            // and the current speed is not necessarily one of the presets.
            playbackSpeedComboBox.model.selectedItem = speed
          }
        }
      }
    }

    val pendingSeek = playbackSlider.valueIsAdjusting || queuedPositionMs != null || inFlightPositionMs != null
    if (state.hasPlaybackPositionMs()) {
      val pos = state.playbackPositionMs.coerceAtLeast(0L).milliseconds
      val clampedPos = if (duration.isPositive()) pos.coerceAtMost(duration) else pos
      if (acceptedPositionMs == null) {
        emulatorPosition = clampedPos
      }
      if (!pendingSeek) {
        currentPosition = clampedPos
        lastRequestedPositionMs = null
        updateUi {
          playbackSlider.value = positionToSliderValue(currentPosition, duration)
          playbackTimeLabel.text = formatTimeProgress(currentPosition, duration)
        }
      }
    }
    if (playbackSlider.valueIsAdjusting) {
      // Duration may have updated while slider is adjusting; refresh time label against current slider value.
      val adjustingPos = sliderValueToPosition(playbackSlider.value, duration)
      playbackTimeLabel.text = formatTimeProgress(adjustingPos, duration)
    } else if (pendingSeek || !state.hasPlaybackPositionMs()) {
      updateUi {
        playbackSlider.value = positionToSliderValue(currentPosition, duration)
        playbackTimeLabel.text = formatTimeProgress(currentPosition, duration)
      }
    }

    updateControlsState()
    if (cancelledSliderAdjusting) {
      flushPendingStreamRestart()
    }
    if (previousHasAnimation != hasAnimation || cancelledSliderAdjusting) {
      onStateChanged?.invoke()
    }
  }

  fun reset() {
    val previousHasAnimation = hasAnimation
    hasAnimation = false
    isPlaying = false
    emulatorIsPlaying = false
    emulatorSpeed = VideoPlaybackSpeed.SPEED_1X
    duration = Duration.ZERO
    currentPosition = Duration.ZERO
    emulatorPosition = Duration.ZERO
    wasSliderAdjusting = false
    clearPendingChanges()
    if (::playbackSpeedComboBox.isInitialized && playbackSpeedComboBox.isPopupVisible) {
      playbackSpeedComboBox.hidePopup()
    }
    updateUi {
      if (::playbackSlider.isInitialized) {
        playbackSlider.valueIsAdjusting = false
        playbackSlider.value = 0
      }
      if (::playbackTimeLabel.isInitialized) {
        playbackTimeLabel.text = formatTimeProgress(Duration.ZERO, Duration.ZERO)
      }
      if (::playbackSpeedComboBox.isInitialized) {
        playbackSpeedComboBox.selectedItem = VideoPlaybackSpeed.SPEED_1X
      }
    }
    updateControlsState()
    if (previousHasAnimation) {
      onStateChanged?.invoke()
    }
  }

  /** Toggles between playing and paused states. */
  fun togglePlayback() {
    if (!hasAnimation) return
    isPlaying = !isPlaying
    queuedState = if (isPlaying) STATE_PLAYING else STATE_PAUSED
    updateControlsState()
    sendTrigger.trySend(Unit)
  }

  private fun onSpeedSelected(speed: VideoPlaybackSpeed) {
    if (!hasAnimation) return
    queuedSpeed = speed.multiplier
    sendTrigger.trySend(Unit)
  }

  private fun onPlaybackSliderChanged() {
    val adjusting = playbackSlider.valueIsAdjusting
    val finishedAdjusting = wasSliderAdjusting && !adjusting
    wasSliderAdjusting = adjusting
    // The slider notifies its listeners not only when its value changes, but also when the user starts or finishes adjusting it.
    val sliderValue = playbackSlider.value
    val valueChanged = sliderValue != lastSliderValue
    lastSliderValue = sliderValue
    if (valueChanged && !isUpdatingUi && hasAnimation && duration.isPositive()) {
      pendingPositionRevert = false
      val targetPosition = sliderValueToPosition(playbackSlider.value, duration)
      currentPosition = targetPosition
      playbackTimeLabel.text = formatTimeProgress(targetPosition, duration)
      val positionMs = targetPosition.inWholeMilliseconds.coerceAtLeast(0L)
      if (positionMs != lastRequestedPositionMs) {
        lastRequestedPositionMs = positionMs
        queuedPositionMs = positionMs
        sendTrigger.trySend(Unit)
      }
    }
    if (finishedAdjusting) {
      if (pendingPositionRevert) {
        pendingPositionRevert = false
        revertPlaybackPosition()
      }
      flushPendingStreamRestart()
      onStateChanged?.invoke()
    }
  }

  private fun flushPendingStreamRestart() {
    if (pendingStreamRestart && !requestInFlight && queuedState == null && queuedSpeed == null && queuedPositionMs == null) {
      pendingStreamRestart = false
      onChangesSent?.invoke()
    }
  }

  private fun takeQueuedRequest(): QueuedRequest? {
    val state = queuedState
    val speed = queuedSpeed
    val posMs = queuedPositionMs
    if (state == null && speed == null && posMs == null) return null
    return buildQueuedRequest(state, speed, posMs)
  }

  private fun prepareRetry(sent: QueuedRequest): QueuedRequest? {
    if (sent.generation != changeGeneration) {
      return null // The changes were discarded while the request was in transit.
    }
    val keepPrevSeek = sent.seekGeneration == seekGeneration
    if (queuedState == null && queuedSpeed == null && queuedPositionMs == null && keepPrevSeek) {
      return sent
    }
    val prev = sent.request.state
    val state = queuedState ?: if (prev.hasState()) prev.state else null
    val speed = queuedSpeed ?: if (prev.hasPlaybackSpeed()) prev.playbackSpeed else null
    val posMs = queuedPositionMs ?: if (keepPrevSeek && prev.hasPlaybackPositionMs()) prev.playbackPositionMs else null
    if (state == null && speed == null && posMs == null) {
      return null
    }
    return buildQueuedRequest(state, speed, posMs)
  }

  private fun buildQueuedRequest(state: AnimationState.State?, speed: Float?, posMs: Long?): QueuedRequest {
    requestInFlight = true
    queuedState = null
    queuedSpeed = null
    queuedPositionMs = null
    // The changes sent earlier remain in flight until finishRequests is called.
    inFlightState = state ?: inFlightState
    inFlightSpeed = speed ?: inFlightSpeed
    inFlightPositionMs = posMs ?: inFlightPositionMs

    val stateBuilder = AnimationState.newBuilder()
    state?.let(stateBuilder::setState)
    speed?.let(stateBuilder::setPlaybackSpeed)
    posMs?.let(stateBuilder::setPlaybackPositionMs)
    val request = SetAnimationStateRequest.newBuilder().setState(stateBuilder.build()).build()
    return QueuedRequest(request, changeGeneration, seekGeneration)
  }

  private fun onRequestCompleted(sent: QueuedRequest, succeeded: Boolean) {
    if (sent.generation != changeGeneration) {
      return // The changes were discarded while the request was in transit.
    }
    if (succeeded) {
      batchAcceptedAnyChange = true
    }
    val update = sent.request.state
    if (update.hasState()) {
      if (succeeded) {
        acceptedState = update.state
        emulatorIsPlaying = update.state == STATE_PLAYING
      } else if (queuedState == null) {
        inFlightState = acceptedState
        isPlaying = emulatorIsPlaying
        updateControlsState()
      }
    }
    if (update.hasPlaybackSpeed()) {
      if (succeeded) {
        acceptedSpeed = update.playbackSpeed
        emulatorSpeed = VideoPlaybackSpeed.fromMultiplier(update.playbackSpeed)
      } else if (queuedSpeed == null) {
        inFlightSpeed = acceptedSpeed
        updateUi {
          if (playbackSpeedComboBox.selectedItem != emulatorSpeed) {
            playbackSpeedComboBox.model.selectedItem = emulatorSpeed
          }
        }
      }
    }
    if (update.hasPlaybackPositionMs() && sent.seekGeneration == seekGeneration) {
      if (succeeded) {
        acceptedPositionMs = update.playbackPositionMs
        val pos = update.playbackPositionMs.coerceAtLeast(0L).milliseconds
        emulatorPosition = if (duration.isPositive()) pos.coerceAtMost(duration) else pos
      } else if (queuedPositionMs == null) {
        inFlightPositionMs = acceptedPositionMs
        lastRequestedPositionMs = acceptedPositionMs
        if (playbackSlider.valueIsAdjusting) {
          pendingPositionRevert = true
        } else {
          revertPlaybackPosition()
        }
      }
    }
  }

  private fun revertPlaybackPosition() {
    currentPosition = if (duration.isPositive()) emulatorPosition.coerceAtMost(duration) else emulatorPosition
    updateUi {
      playbackSlider.value = positionToSliderValue(currentPosition, duration)
      playbackTimeLabel.text = formatTimeProgress(currentPosition, duration)
    }
  }

  /** Called when all queued requests have been sent. */
  private fun finishRequests() {
    requestInFlight = false
    val hadNonSeekChange = inFlightState != null || inFlightSpeed != null
    val acceptedAny = batchAcceptedAnyChange
    batchAcceptedAnyChange = false
    inFlightState = null
    inFlightSpeed = null
    inFlightPositionMs = null
    acceptedState = null
    acceptedSpeed = null
    acceptedPositionMs = null
    if (!acceptedAny) {
      if (pendingStreamRestart && !playbackSlider.valueIsAdjusting) {
        pendingStreamRestart = false
        onChangesSent?.invoke()
      }
      return // All changes in the batch either failed or were discarded while in transit.
    }
    if (playbackSlider.valueIsAdjusting && !hadNonSeekChange) {
      pendingStreamRestart = true
    } else {
      pendingStreamRestart = false
      // The controls keep showing the values chosen by the user until a state produced after the changes is received.
      onChangesSent?.invoke()
    }
  }

  private fun discardPendingSeeks() {
    if (
      queuedPositionMs != null ||
        inFlightPositionMs != null ||
        acceptedPositionMs != null ||
        lastRequestedPositionMs != null ||
        pendingPositionRevert
    ) {
      seekGeneration++
      lastRequestedPositionMs = null
      queuedPositionMs = null
      inFlightPositionMs = null
      acceptedPositionMs = null
      pendingPositionRevert = false
    }
  }

  /** Discards the user changes that have not been sent to the emulator or are not yet reflected in the states received from it. */
  private fun clearPendingChanges() {
    changeGeneration++
    seekGeneration++
    requestInFlight = false
    batchAcceptedAnyChange = false
    lastRequestedPositionMs = null
    queuedState = null
    queuedSpeed = null
    queuedPositionMs = null
    inFlightState = null
    inFlightSpeed = null
    inFlightPositionMs = null
    acceptedState = null
    acceptedSpeed = null
    acceptedPositionMs = null
    pendingPositionRevert = false
    pendingStreamRestart = false
  }

  private class QueuedRequest(val request: SetAnimationStateRequest, val generation: Long, val seekGeneration: Long)

  private fun updateControlsState() {
    val newState = ControlsState(hasAnimation, isPlaying, seekable = hasAnimation && duration.isPositive())
    if (newState == controlsState) {
      return
    }
    controlsState = newState
    val buttonLabel = if (isPlaying) message("video.playback.button.pause") else message("video.playback.button.play")
    playPauseButton.icon = if (isPlaying) AllIcons.Actions.Pause else AllIcons.Actions.Execute
    playPauseButton.setToolTipText(HtmlChunk.text(buttonLabel))
    playPauseButton.accessibleContext.accessibleName = buttonLabel
    playPauseButton.isEnabled = hasAnimation
    playbackSlider.isEnabled = newState.seekable
    playbackSpeedComboBox.isEnabled = hasAnimation
  }

  private data class ControlsState(val hasAnimation: Boolean, val isPlaying: Boolean, val seekable: Boolean)

  private inline fun updateUi(block: () -> Unit) {
    val previous = isUpdatingUi
    isUpdatingUi = true
    try {
      block()
    } finally {
      isUpdatingUi = previous
    }
  }

  override fun dispose() {
    isDisposed = true
    onStateChanged = null
    onChangesSent = null
    sendTrigger.close()
  }

  private class OpaqueIconButton(icon: Icon, tooltip: String, action: () -> Unit) : IconButton(icon, tooltip, action) {
    init {
      putClientProperty("JButton.backgroundColor", BUTTON_BACKGROUND_COLOR)
    }

    override fun paintComponent(g: Graphics) {
      if (!isEnabled && isContentAreaFilled) {
        val g2 = g.create() as Graphics2D
        try {
          GraphicsUtil.setupAAPainting(g2)
          val r = Rectangle(size)
          JBInsets.removeFrom(r, JBUI.insets(1))
          val bw = DarculaUIUtil.BW.float
          val arc = DarculaUIUtil.BUTTON_ARC.float
          g2.color = DISABLED_CONTROL_BACKGROUND_COLOR
          g2.fill(RoundRectangle2D.Float(r.x + bw + 0.5f, r.y + bw + 0.5f, r.width - bw * 2 - 1f, r.height - bw * 2 - 1f, arc, arc))
        } finally {
          g2.dispose()
        }
      }
      super.paintComponent(g)
    }
  }

  companion object {
    private const val HORIZONTAL_PADDING = 10
    private const val VERTICAL_PADDING = 6
    private const val BACKGROUND_ALPHA = 0.8

    private val OPAQUE_PANEL_BACKGROUND: Color = JBColor.lazy { ColorUtil.toAlpha(JBColor.PanelBackground, 255) }

    private val LABEL_FOREGROUND_COLOR: Color = JBColor.lazy {
      ColorUtil.alphaBlending(UIUtil.getLabelForeground(), OPAQUE_PANEL_BACKGROUND)
    }

    private val BUTTON_BACKGROUND_COLOR: Color = JBColor.lazy {
      ColorUtil.alphaBlending(JBUI.CurrentTheme.Button.buttonColorStart(), OPAQUE_PANEL_BACKGROUND)
    }

    private val COMBO_BOX_BACKGROUND_COLOR: Color =
      JBColor.namedColor(
        "ComboBox.nonEditableBackground",
        JBColor.namedColor("ComboBox.darcula.nonEditableBackground", JBColor(0xfcfcfc, 0x3c3f41)),
      )

    private val DISABLED_CONTROL_BACKGROUND_COLOR: Color = JBColor.lazy {
      ColorUtil.alphaBlending(UIUtil.getComboBoxDisabledBackground(), OPAQUE_PANEL_BACKGROUND)
    }
  }
}
