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

import com.android.annotations.concurrency.AnyThread
import com.android.emulator.control.AnimationState
import com.android.emulator.control.AnimationState.State.STATE_PAUSED
import com.android.emulator.control.AnimationState.State.STATE_PLAYING
import com.android.emulator.control.SetAnimationStateRequest
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.EdtAnyModality
import com.android.tools.idea.streaming.core.PROJECTION_RECTANGLE_PROPERTY
import com.android.tools.idea.streaming.core.ProgressSlider
import com.android.tools.idea.streaming.core.anyVisibleChildContains
import com.android.tools.idea.streaming.core.containsFocus
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionStateListener
import com.android.tools.idea.streaming.emulator.actions.EnvironmentTracker
import com.android.tools.idea.streaming.emulator.actions.isVideo
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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.AbstractLayoutManager
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.HeadlessException
import java.awt.IllegalComponentStateException
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.beans.PropertyChangeListener
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * Transparent overlay container placed over [EmulatorView] that shows [VideoPlaybackPanel] at the bottom of the rendered environment area
 * when the current AI Glasses environment is a video and the mouse hovers over that bottom area.
 */
internal class VideoPlaybackOverlay(
  private val displayPanel: EmulatorDisplayPanel,
  private val emulator: EmulatorController,
) : JPanel(), ConnectionStateListener, Disposable {

  private val displayView: EmulatorView
    get() = displayPanel.displayView

  val playbackPanel = VideoPlaybackPanel(emulator, this)

  /** Called when the playback panel is shown or hidden. The argument is true if the panel has become visible. */
  var onPlaybackPanelVisibilityChanged: ((Boolean) -> Unit)? = null

  private val coroutineScope = createCoroutineScope()
  private var environmentJob: Job? = null
  /** Identifies the current animation state stream. Changing the value restarts the stream. */
  private val streamGeneration = MutableStateFlow(0)
  private var visibilityDisposable: Disposable? = null
  private var listeningToMouseEvents = false
  @Volatile private var isDisposed = false

  /** The "scene.mode" value of the current environment if it is a video, or null otherwise. */
  private var videoSceneMode: String? = null
  private val isVideoEnvironment
    get() = videoSceneMode != null

  private var mouseInsideHoverArea = false
  /**
   * True while the panel is shown for a limited time after a video started playing, the display view gained focus, or the Space key was
   * pressed.
   */
  private var shownTemporarily = false
  /** True if the panel has to be shown for a limited time as soon as the video becomes visible to the user. */
  private var temporaryVisibilityPending = false
  /** True if the panel should be shown temporarily as soon as animation state becomes known for the current video environment. */
  private var showOnAnimationStart = false
  private var visibilityUpdateScheduled = false
  private var temporaryVisibilityJob: Job? = null
  private var spaceKeyPressed = false
  /** The timestamp of the last Space key release event, or a negative number if the key has not been released yet. */
  private var spaceKeyReleaseTime = -1L

  /**
   * For how long the panel stays visible after a video started playing, the display view gained focus, or the Space key was pressed. The
   * panel is not shown on these occasions if the duration is not positive.
   */
  @VisibleForTesting var temporaryVisibilityDuration: Duration = 3.seconds
  @VisibleForTesting var focusRequester: (Component) -> Boolean = Component::requestFocusInWindow
  private var lastMousePoint: Point? = null
  private var mouseOutsideWindow = false
  private var suppressHoverUntilMouseMoves = false
  private var suppressedHoverPoint: Point? = null
  private var returningFocusToDisplayView = false

  private val displayComponentListener =
    object : ComponentAdapter() {
      override fun componentResized(event: ComponentEvent) {
        revalidate()
        repaint()
      }

      override fun componentMoved(event: ComponentEvent) {
        revalidate()
        repaint()
      }
    }

  private val projectionChangeListener = PropertyChangeListener {
    revalidate()
    repaint()
    // The projection rectangle changes while the display view is being painted. Don't change visibility of components during painting.
    EventQueue.invokeLater {
      if (!isDisposed) {
        showTemporarilyIfPending()
      }
    }
  }

  private val viewportChangeListener = ChangeListener {
    revalidate()
    repaint()
  }

  private val displayFocusListener =
    object : FocusAdapter() {
      override fun focusGained(event: FocusEvent) {
        spaceKeyPressed = false
        // Don't show the panel again when the focus returns from the panel that is being hidden.
        val previousFocusOwner = event.oppositeComponent
        val fromPlaybackPanel =
          returningFocusToDisplayView || (previousFocusOwner != null && SwingUtilities.isDescendingFrom(previousFocusOwner, playbackPanel))
        returningFocusToDisplayView = false
        if (!fromPlaybackPanel) {
          showTemporarily()
        }
      }
    }

  init {
    Disposer.register(displayPanel, this)
    name = "videoPlaybackOverlay"
    isOpaque = false
    isFocusable = false
    layout = OverlayLayout()

    playbackPanel.onStateChanged = ::onPlaybackStateChanged
    playbackPanel.onChangesSent = ::restartAnimationStateStream
    add(playbackPanel)

    displayView.addComponentListener(displayComponentListener)
    displayView.addPropertyChangeListener(PROJECTION_RECTANGLE_PROPERTY, projectionChangeListener)
    (displayView.parent as? JViewport)?.addChangeListener(viewportChangeListener)
    displayView.addFocusListener(displayFocusListener)
    displayView.keyEventInterceptor = ::handleKeyEvent
    displayView.canOperateVirtualSceneCamera = ::canOperateVirtualSceneCamera
    displayView.onOperatingVirtualSceneCameraChanged = { operating ->
      if (operating) {
        hidePlaybackPanel()
      } else {
        showTemporarilyIfPending()
        updatePanelVisibility()
      }
    }

    addHierarchyListener { event ->
      if (event.changeFlags.toInt() and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
        onShowingChanged()
      }
    }

    emulator.addConnectionStateListener(this, this)
  }

  override fun contains(x: Int, y: Int): Boolean = anyVisibleChildContains(x, y)

  @AnyThread
  override fun connectionStateChanged(emulator: EmulatorController, connectionState: ConnectionState) {
    UIUtil.invokeLaterIfNeeded {
      if (isDisposed) return@invokeLaterIfNeeded
      when (connectionState) {
        ConnectionState.CONNECTED -> startEnvironmentTrackingIfNeeded()
        ConnectionState.DISCONNECTED -> stopEnvironmentTracking()
        else -> {}
      }
    }
  }

  private fun startEnvironmentTrackingIfNeeded() {
    if (environmentJob != null) return
    val environmentTracker = EnvironmentTracker.forEmulator(emulator) ?: return
    environmentJob =
      coroutineScope.launch(Dispatchers.EDT) {
        environmentTracker.environmentFlow
          .map { environment -> environment?.takeIf { it.isVideo() }?.environmentMap?.get("scene.mode") }
          .distinctUntilChanged()
          .collectLatest { sceneMode ->
            setVideoEnvironment(sceneMode)
            if (sceneMode != null) {
              // The stream is restarted every time streamGeneration changes.
              streamGeneration.collectLatest { generation -> streamAnimationState(generation) }
            }
          }
      }
  }

  private suspend fun streamAnimationState(generation: Int) {
    var restartDelay = INITIAL_STREAM_RESTART_DELAY
    while (true) {
      var streamError: Throwable? = null
      emulator
        .streamAnimationState()
        // Only the failures of the stream itself are handled here. They are logged by EmulatorController.
        // Exceptions thrown while processing a received state are not caught.
        .catch { streamError = it }
        .collect { state ->
          restartDelay = INITIAL_STREAM_RESTART_DELAY
          // Ignore the states that were still in transit when the stream was restarted.
          if (generation == streamGeneration.value) {
            playbackPanel.updateAnimationState(state)
          }
        }
      currentCoroutineContext().ensureActive()
      val error = streamError
      if (error is EmulatorController.RetryException) {
        continue
      }
      if (generation == streamGeneration.value) {
        // The stream has ended, so the playback state is not known anymore.
        playbackPanel.reset()
      }
      if (error is StatusRuntimeException && error.status.code == Status.Code.UNIMPLEMENTED) {
        return // The emulator doesn't support animation state streaming.
      }
      // Restart the stream after a delay.
      delay(restartDelay)
      restartDelay = (restartDelay * 2).coerceAtMost(MAX_STREAM_RESTART_DELAY)
    }
  }

  /**
   * Cancels the animation state stream and starts a new one. The states arriving through the new stream are guaranteed to be produced after
   * this method was called, which is not true for the states that are still in transit through the old stream.
   */
  private fun restartAnimationStateStream() {
    streamGeneration.value++
  }

  private fun stopEnvironmentTracking() {
    environmentJob?.cancel()
    environmentJob = null
    setVideoEnvironment(null)
  }

  /** Sets the "scene.mode" value of the current video environment, or null if the current environment is not a video. */
  private fun setVideoEnvironment(sceneMode: String?) {
    val previousSceneMode = videoSceneMode
    if (sceneMode == previousSceneMode) return
    if (previousSceneMode != null) {
      // The previous video is gone. Its state and the pending user changes don't apply to whatever replaced it.
      playbackPanel.reset()
    }
    showOnAnimationStart = sceneMode != null
    videoSceneMode = sceneMode
    cancelTemporaryVisibility()
    updatePanelVisibility()
  }

  /**
   * Returns the location of the mouse pointer in the coordinate space of this component, or null if it is not available. Unlike
   * [com.android.tools.idea.streaming.core.mouseLocation], doesn't depend on [contains], which is false while the playback panel is hidden.
   */
  private fun pointerLocation(): Point? {
    if (!isShowing) {
      return null
    }
    val pointerInfo =
      try {
        MouseInfo.getPointerInfo()
      } catch (_: HeadlessException) {
        null
      } ?: return null
    val location = pointerInfo.location
    SwingUtilities.convertPointFromScreen(location, this)
    return location
  }

  private fun onShowingChanged() {
    if (isShowing) {
      if (isDisposed) return
      val disposable =
        visibilityDisposable ?: Disposer.newDisposable(this, "VideoPlaybackOverlay.visibility").also { visibilityDisposable = it }
      setUpMouseListener(disposable)
      showTemporarilyIfPending()
    } else {
      visibilityDisposable?.let(Disposer::dispose)
      visibilityDisposable = null
      listeningToMouseEvents = false
      lastMousePoint = null
      mouseOutsideWindow = false
      suppressHoverUntilMouseMoves = false
      suppressedHoverPoint = null
      mouseInsideHoverArea = false
      cancelTemporaryVisibility(clearPending = false)
      if (!isDisposed) {
        updatePanelVisibility()
      }
    }
  }

  private fun onPlaybackStateChanged() {
    if (isDisposed) return
    if (showOnAnimationStart && playbackPanel.hasAnimation) {
      showOnAnimationStart = false
      // A video started playing because of an environment change or the emulator startup.
      showTemporarily()
    }
    updatePanelVisibility()
    displayView.updateCameraPromptAndMultiTouchFeedback()
  }

  private fun canOperateVirtualSceneCamera(): Boolean {
    if (playbackPanel.isInteracting) return false
    if (!playbackPanel.isVisible) return true
    val currentMouse = lastMousePoint ?: pointerLocation() ?: return true
    return !playbackPanel.bounds.contains(currentMouse)
  }

  /**
   * Shows the playback panel for [temporaryVisibilityDuration] if the current environment is a video. The location of the panel is
   * determined by the location of the environment image, so if the image is not visible to the user yet, the panel is shown when it becomes
   * visible.
   */
  private fun showTemporarily() {
    if (!isVideoEnvironment || !playbackPanel.hasAnimation || !temporaryVisibilityDuration.isPositive()) {
      return
    }
    if (!isShowing || displayView.isOperatingVirtualSceneCamera || computePanelBounds().isEmpty) {
      temporaryVisibilityPending = true
      return
    }
    temporaryVisibilityPending = false
    temporaryVisibilityJob?.cancel()
    shownTemporarily = true
    temporaryVisibilityJob =
      coroutineScope.launch(Dispatchers.EDT) {
        delay(temporaryVisibilityDuration)
        shownTemporarily = false
        temporaryVisibilityJob = null
        updatePanelVisibility()
      }
    updatePanelVisibility()
  }

  private fun showTemporarilyIfPending() {
    if (temporaryVisibilityPending) {
      showTemporarily()
    }
  }

  private fun cancelTemporaryVisibility(clearPending: Boolean = true) {
    temporaryVisibilityJob?.cancel()
    temporaryVisibilityJob = null
    shownTemporarily = false
    if (clearPending) {
      temporaryVisibilityPending = false
    }
  }

  /**
   * Handles key events of the display view. The Space key without modifiers toggles between playing and paused states and shows the
   * playback panel for a limited time. Returns true if the event was handled.
   */
  private fun handleKeyEvent(event: KeyEvent): Boolean {
    val isSpace = if (event.id == KeyEvent.KEY_TYPED) event.keyChar == ' ' else event.keyCode == KeyEvent.VK_SPACE
    if (!isSpace) {
      return false
    }
    if (event.id == KeyEvent.KEY_RELEASED) {
      // The release is processed regardless of the modifiers and the playback state so that it always matches the preceding press.
      val wasPressed = spaceKeyPressed
      spaceKeyPressed = false
      spaceKeyReleaseTime = event.`when`
      return wasPressed
    }
    if (!isVideoEnvironment || !playbackPanel.hasAnimation || event.modifiersEx and MODIFIER_MASK != 0) {
      return false
    }
    if (event.id == KeyEvent.KEY_PRESSED) {
      // Auto-repeat is ignored. On Windows and macOS a key that is held down produces a sequence of press events without release
      // events. On Linux it produces pairs of release and press events, where the two events of a pair have the same timestamp.
      val autoRepeat =
        spaceKeyPressed || (spaceKeyReleaseTime >= 0 && event.`when` - spaceKeyReleaseTime in 0..AUTO_REPEAT_RELEASE_PRESS_INTERVAL_MILLIS)
      spaceKeyPressed = true
      if (!autoRepeat) {
        playbackPanel.togglePlayback()
        showTemporarily()
      }
    }
    return true
  }

  private fun setUpMouseListener(disposable: Disposable) {
    if (listeningToMouseEvents) return
    val glass = rootPane?.glassPane as? IdeGlassPane ?: return
    val mouseListener =
      object : MouseAdapter() {
        override fun mouseEntered(event: MouseEvent) = controlVisibility(event)

        override fun mouseExited(event: MouseEvent) = onMouseExited()

        override fun mouseMoved(event: MouseEvent) = controlVisibility(event)

        override fun mouseDragged(event: MouseEvent) = controlVisibility(event)

        override fun mouseReleased(event: MouseEvent) = controlVisibility(event)
      }
    glass.addMousePreprocessor(mouseListener, disposable)
    glass.addMouseMotionPreprocessor(mouseListener, disposable)
    listeningToMouseEvents = true
  }

  /** Hides the playback panel immediately, for example when the user expands the zoom toolbar while the panel is shown. */
  fun hidePlaybackPanel() {
    if (isDisposed) return
    cancelTemporaryVisibility()
    if (!playbackPanel.isVisible && !mouseInsideHoverArea) return
    mouseInsideHoverArea = false
    suppressHoverUntilMouseMoves = true
    suppressedHoverPoint = lastMousePoint ?: pointerLocation()
    playbackPanel.cancelInteraction()
    updatePanelVisibility()
  }

  private fun controlVisibility(event: MouseEvent) {
    mouseOutsideWindow = false
    val point = toOverlayPoint(event)
    if (displayView.isOperatingVirtualSceneCamera) {
      suppressHoverUntilMouseMoves = true
      suppressedHoverPoint = point
    } else if (
      suppressHoverUntilMouseMoves &&
        (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED) &&
        point != suppressedHoverPoint
    ) {
      suppressHoverUntilMouseMoves = false
      suppressedHoverPoint = null
    }
    lastMousePoint = point
    if (!isVideoEnvironment || !playbackPanel.hasAnimation) {
      return
    }
    updatePanelVisibility()
  }

  private fun onMouseExited() {
    lastMousePoint = null
    suppressHoverUntilMouseMoves = false
    suppressedHoverPoint = null
    mouseOutsideWindow = !playbackPanel.isInteracting
    if (!isVideoEnvironment || !playbackPanel.hasAnimation) {
      mouseInsideHoverArea = false
      return
    }
    updatePanelVisibility()
  }

  private fun toOverlayPoint(event: MouseEvent): Point? {
    val source = event.component ?: return null
    val thisRootPane = SwingUtilities.getRootPane(this)
    if (thisRootPane != null && SwingUtilities.getRootPane(source) === thisRootPane) {
      return SwingUtilities.convertPoint(source, event.point, this)
    }
    if (!isShowing || !source.isShowing) {
      return null
    }
    return try {
      val overlayOnScreen = locationOnScreen
      Point(event.xOnScreen - overlayOnScreen.x, event.yOnScreen - overlayOnScreen.y)
    } catch (_: IllegalComponentStateException) {
      null
    }
  }

  private fun currentHoverBounds(): Rectangle = if (!playbackPanel.bounds.isEmpty) playbackPanel.bounds else computePanelBounds()

  /**
   * Returns true if [point] lies inside the zoom toolbar or a margin equal to half of its smaller dimension on each side. Hovering inside
   * this area does not activate the playback panel.
   */
  private fun isInZoomToolbarNonActivationArea(point: Point): Boolean {
    val toolbarBounds = displayPanel.getZoomToolbarBoundsIn(this)
    if (toolbarBounds.isEmpty) {
      return false
    }
    val margin = (min(toolbarBounds.width, toolbarBounds.height) / 2.0).roundToInt()
    toolbarBounds.grow(margin, margin)
    return toolbarBounds.contains(point)
  }

  private fun updatePanelVisibility() {
    if (isDisposed) return
    val bounds = currentHoverBounds()
    if (
      isShowing &&
        isVideoEnvironment &&
        playbackPanel.hasAnimation &&
        !displayView.isOperatingVirtualSceneCamera &&
        !mouseOutsideWindow &&
        !suppressHoverUntilMouseMoves &&
        !bounds.isEmpty
    ) {
      val currentMouse = lastMousePoint ?: pointerLocation()
      if (currentMouse != null && bounds.contains(currentMouse)) {
        if (mouseInsideHoverArea || !isInZoomToolbarNonActivationArea(currentMouse)) {
          mouseInsideHoverArea = true
        } else if (shownTemporarily || playbackPanel.isInteracting) {
          // If showing the panel collapses an expanded zoom toolbar underneath a stationary cursor, don't let the collapsed bounds
          // hover-activate the panel later unless the mouse actually moves.
          suppressHoverUntilMouseMoves = true
          suppressedHoverPoint = currentMouse
        }
      } else {
        mouseInsideHoverArea = false
      }
    } else {
      mouseInsideHoverArea = false
    }
    val shouldShow =
      isShowing &&
        isVideoEnvironment &&
        playbackPanel.hasAnimation &&
        (mouseInsideHoverArea || shownTemporarily || playbackPanel.isInteracting) &&
        !bounds.isEmpty
    if (!shouldShow) {
      playbackPanel.cancelInteraction()
    }
    if (playbackPanel.isVisible != shouldShow) {
      val returnFocusToDisplayView = !shouldShow && isShowing && playbackPanel.containsFocus()
      returningFocusToDisplayView = returnFocusToDisplayView
      playbackPanel.isVisible = shouldShow
      if (returnFocusToDisplayView) {
        if (focusRequester(displayView)) {
          EventQueue.invokeLater { returningFocusToDisplayView = false }
        } else {
          returningFocusToDisplayView = false
        }
      }
      onPlaybackPanelVisibilityChanged?.invoke(shouldShow)
      displayView.updateCameraPromptAndMultiTouchFeedback()
      revalidate()
      repaint()
    }
  }

  private fun computePanelBounds(): Rectangle {
    if (displayView.parent == null || displayView.width <= 0 || displayView.height <= 0 || width <= 0 || height <= 0) {
      return Rectangle()
    }
    val visibleRect = displayView.visibleRect
    if (visibleRect.isEmpty) {
      return Rectangle()
    }
    // The panel is aligned with the environment image. Until the image is rendered, there is nothing to align the panel with.
    val projPhysical = displayView.projectionRectangle
    if (projPhysical == null || projPhysical.isEmpty) {
      return Rectangle()
    }
    val scale = displayView.screenScalingFactor
    val x0 = (projPhysical.x / scale).roundToInt()
    val y0 = (projPhysical.y / scale).roundToInt()
    val x1 = ((projPhysical.x + projPhysical.width) / scale).roundToInt()
    val y1 = ((projPhysical.y + projPhysical.height) / scale).roundToInt()
    val targetInView = Rectangle(x0, y0, x1 - x0, y1 - y0).intersection(visibleRect)
    if (targetInView.width <= 0 || targetInView.height <= 0) {
      return Rectangle()
    }
    val envRect = SwingUtilities.convertRectangle(displayView, targetInView, this).intersection(Rectangle(0, 0, width, height))
    if (envRect.width <= 0 || envRect.height <= 0) {
      return Rectangle()
    }
    val prefHeight = playbackPanel.preferredSize.height
    val panelHeight = prefHeight.coerceAtMost(envRect.height)
    return Rectangle(envRect.x, envRect.y + envRect.height - panelHeight, envRect.width, panelHeight)
  }

  override fun dispose() {
    isDisposed = true
    environmentJob?.cancel()
    environmentJob = null
    temporaryVisibilityJob?.cancel()
    temporaryVisibilityJob = null
    displayView.removeComponentListener(displayComponentListener)
    displayView.removePropertyChangeListener(PROJECTION_RECTANGLE_PROPERTY, projectionChangeListener)
    (displayView.parent as? JViewport)?.removeChangeListener(viewportChangeListener)
    displayView.removeFocusListener(displayFocusListener)
    displayView.keyEventInterceptor = null
    displayView.canOperateVirtualSceneCamera = { true }
    displayView.onOperatingVirtualSceneCameraChanged = null
  }

  private companion object {
    val INITIAL_STREAM_RESTART_DELAY = 1.seconds
    val MAX_STREAM_RESTART_DELAY = 30.seconds

    /** A Space key press following a release within this interval is considered to be produced by auto-repeat. */
    const val AUTO_REPEAT_RELEASE_PRESS_INTERVAL_MILLIS = 5L

    const val MODIFIER_MASK =
      InputEvent.SHIFT_DOWN_MASK or
        InputEvent.CTRL_DOWN_MASK or
        InputEvent.META_DOWN_MASK or
        InputEvent.ALT_DOWN_MASK or
        InputEvent.ALT_GRAPH_DOWN_MASK
  }

  private inner class OverlayLayout : AbstractLayoutManager() {
    override fun preferredLayoutSize(parent: Container): Dimension = playbackPanel.preferredSize

    override fun layoutContainer(parent: Container) {
      val bounds = computePanelBounds()
      val boundsChanged = playbackPanel.bounds != bounds
      playbackPanel.bounds = bounds
      if ((boundsChanged || (temporaryVisibilityPending && !bounds.isEmpty)) && !visibilityUpdateScheduled) {
        visibilityUpdateScheduled = true
        EventQueue.invokeLater {
          visibilityUpdateScheduled = false
          if (!isDisposed) {
            showTemporarilyIfPending()
            updatePanelVisibility()
          }
        }
      }
    }
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
      for (unit in sendTrigger) {
        var next = withContext(Dispatchers.EdtAnyModality) { takeQueuedRequest() }
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
            withContext(Dispatchers.EdtAnyModality) {
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
