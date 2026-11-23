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
package com.android.tools.idea.streaming.core

import com.android.tools.adtui.common.AdtUiCursorType
import com.android.tools.adtui.common.AdtUiCursorsProvider
import com.android.tools.adtui.util.scaled
import com.intellij.ide.ActivityTracker
import com.intellij.ui.JreHiDpiUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.MouseEventAdapter
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent.BUTTON1_DOWN_MASK
import java.awt.event.InputEvent.BUTTON2_DOWN_MASK
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private val ZOOM_LEVELS = doubleArrayOf(0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0)

/** A [BorderLayoutPanel] with zoom and pan support. */
internal abstract class ZoomablePanel : BorderLayoutPanel(), Zoomable {

  override val screenScalingFactor: Double
    get() = if (JreHiDpiUtil.isJreHiDPI(graphicsConfiguration)) JBUIScale.sysScale(this).toDouble() else 1.0

  /** Width in physical pixels. */
  protected val physicalWidth
    get() = width.scaled(screenScalingFactor)

  /** Height in physical pixels. */
  protected val physicalHeight
    get() = height.scaled(screenScalingFactor)

  /** Size in physical pixels. */
  protected val physicalSize
    get() = Dimension(physicalWidth, physicalHeight)

  override val scale: Double
    get() = roundDownIfNecessary(computeScaleToFit(framing, computeMaxImageSize()))

  override val naturalContentSize: Dimension
    get() = computeActualSize(framing)

  internal val explicitlySetPreferredSize: Dimension?
    get() = if (isPreferredSizeSet) preferredSize else null

  /**
   * An integer number represented as Double. If zero, indicates that fractional scale above 1 is not allowed. Otherwise, indicates that
   * fractional scale is allowed between `fractionalScaleRange` and `fractionalScaleRange` + 1.
   */
  private var fractionalScaleRange: Double = 0.0

  /** Indicates whether the view is scaled so that its inner part fits into the available space. */
  internal open var framing: Framing = Framing.OUTER

  internal var zoomScrollState: ZoomScrollState
    get() = ZoomScrollState(framing, explicitlySetPreferredSize, findAncestor<CenterAnchoredViewport>()?.viewCenterOffset ?: Point())
    set(value) {
      framing = value.framing
      if (value.preferredViewSize != null) {
        preferredSize = value.preferredViewSize
        findAncestor<CenterAnchoredViewport>()?.viewCenterOffset = value.viewCenterOffset
      }
    }

  override var isPanning: Boolean = false
    set(value) {
      val newValue = value && isPannable
      if (field != newValue) {
        field = newValue
        panOverlay?.isVisible = newValue
        if (newValue) {
          addMouseWheelListener(panMouseListener)
        } else {
          removeMouseWheelListener(panMouseListener)
          panDragState = null
        }
        if (!isDisposed) {
          panningStateChanged(newValue)
          updateCursor()
          ActivityTracker.getInstance().inc()
        }
      }
    }

  override val isPannable: Boolean
    get() {
      if (isDisposed || !canZoom()) {
        return false
      }
      val scrollPane = scrollPane ?: return false
      return scrollPane.width > 0 &&
        scrollPane.height > 0 &&
        (scrollPane.verticalScrollBar.isVisible || scrollPane.horizontalScrollBar.isVisible)
    }

  private val viewport: JViewport?
    get() = parent as? JViewport

  private val scrollPane: JScrollPane?
    get() = viewport?.parent as? JScrollPane

  /** Returns true if the panel has been disposed and should ignore pan, cursor, and overlay updates. */
  protected open val isDisposed: Boolean
    get() = false

  private val panMouseListener = PanMouseListener()
  internal var panOverlay: JComponent? = null
    set(value) {
      val newValue = if (isDisposed) null else value
      if (field === newValue) {
        return
      }
      field?.let { overlay ->
        overlay.removeMouseListener(panMouseListener)
        overlay.removeMouseMotionListener(panMouseListener)
        overlay.removeMouseWheelListener(panMouseListener)
        overlay.isVisible = false
        overlay.cursor = null
      }
      panDragState = null
      field = newValue
      if (newValue != null) {
        newValue.addMouseListener(panMouseListener)
        newValue.addMouseMotionListener(panMouseListener)
        newValue.addMouseWheelListener(panMouseListener)
        newValue.isVisible = isPanning
      }
      if (!isDisposed) {
        updateCursor()
      }
    }

  private var panDragState: PanDragState? = null

  private val isGrabbing: Boolean
    get() = panDragState != null

  init {
    addMouseListener(panMouseListener)
    addMouseMotionListener(panMouseListener)
  }

  /** Returns the size of the content at 100% zoom. */
  internal abstract fun computeActualSize(framing: Framing): Dimension

  /** Returns true if the panel contains zoomable content. */
  protected abstract fun canZoom(): Boolean

  protected fun roundDownIfNecessary(scale: Double): Double {
    val roundedScale = roundDownIfGreaterThanOne(scale)
    return if (roundedScale == fractionalScaleRange) scale else roundedScale
  }

  override fun zoom(zoomType: ZoomType): Boolean {
    val oldFractionalScaleRange = fractionalScaleRange
    val newFraming = zoomType.toFraming()
    if (zoomType == ZoomType.FIT || zoomType == ZoomType.FIT_INNER) {
      if (fractionalScaleRange == 0.0) {
        // Allow fractional scale greater than one.
        fractionalScaleRange = roundDownIfGreaterThanOne(computeScaleToFitInParent(newFraming))
      }
    } else {
      fractionalScaleRange = 0.0
    }
    val scaledSize = computeZoomedSize(zoomType)
    if (scaledSize == preferredSize && fractionalScaleRange == oldFractionalScaleRange && newFraming == framing) {
      return false
    }
    preferredSize = scaledSize
    framing = newFraming
    revalidate()
    repaint()
    return true
  }

  override fun canZoom(zoomType: ZoomType): Boolean {
    return canZoom() &&
      when (zoomType) {
        ZoomType.IN -> computeZoomedSize(zoomType) != explicitlySetPreferredSize

        ZoomType.OUT,
        ZoomType.ACTUAL -> computeZoomedSize(zoomType) != explicitlySetPreferredSize || isFractionalGreaterThanOne(scale)

        ZoomType.FIT,
        ZoomType.FIT_INNER -> {
          if (zoomType == ZoomType.FIT_INNER && !hasInnerPart) {
            return false
          }
          if (isPreferredSizeSet || framing != zoomType.toFraming()) {
            return true
          }
          if (fractionalScaleRange != 0.0) {
            return false
          }
          val scaleToFit = computeScaleToFitInParent(zoomType.toFraming())
          val roundedScale = roundDownIfGreaterThanOne(scaleToFit)
          return roundedScale < scaleToFit
        }
      }
  }

  fun resetZoom() {
    preferredSize = null
    fractionalScaleRange = 0.0
    revalidate()
  }

  /** Called when [isPanning] changes. */
  protected open fun panningStateChanged(panning: Boolean) {}

  /** May be overridden to show a context menu if [event] is a popup trigger. Returns true if a popup was shown. */
  protected open fun handlePanPopup(event: MouseEvent): Boolean = false

  /** Returns a custom cursor for the panel, or null to use the default cursor. */
  protected open fun computeCustomCursor(): Cursor? {
    if (isPanning) {
      val cursorType = if (isGrabbing) AdtUiCursorType.GRABBING else AdtUiCursorType.GRAB
      return AdtUiCursorsProvider.getInstance().getCursor(cursorType)
    }
    return null
  }

  /** Updates the cursor of the panel and [panOverlay] according to [computeCustomCursor]. */
  protected fun updateCursor() {
    val newCursor = computeCustomCursor()
    val currentCursor = if (isCursorSet) cursor else null
    if (currentCursor != newCursor) {
      cursor = newCursor
    }
    panOverlay?.cursor = if (isPanning) newCursor else null
  }

  /** Terminates an active pan drag gesture, if any, and refreshes the cursor. */
  protected fun endPanning() {
    if (panDragState != null) {
      panDragState = null
      if (!isDisposed) {
        updateCursor()
      }
    }
  }

  /** Terminates an active pan drag gesture if neither pan mouse button is pressed in [modifiersEx]. */
  protected fun endPanningIfButtonsReleased(modifiersEx: Int) {
    if ((modifiersEx and PAN_BUTTON_MASK) == 0) {
      endPanning()
    }
  }

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    val sizeChanged = width != this.width || height != this.height
    super.setBounds(x, y, width, height)
    if (fractionalScaleRange != 0.0 && fractionalScaleRange != computeBaseScale()) {
      fractionalScaleRange = 0.0
    }
    if (sizeChanged) {
      ActivityTracker.getInstance().inc() // Trigger a toolbar update.
    }
  }

  /** Computes the maximum allowed size of the device display image in physical pixels. */
  protected fun computeMaxImageSize(): Dimension = (explicitlySetPreferredSize ?: size).scaled(screenScalingFactor)

  /** Computes the preferred size in virtual pixels after the given zoom operation. The preferred size is null for zoom to fit. */
  private fun computeZoomedSize(zoomType: ZoomType): Dimension? {
    val newFraming = zoomType.toFraming()
    val newScale =
      when (zoomType) {
        ZoomType.IN -> {
          val scale = scale
          val lastZoomLevelIndex = ZOOM_LEVELS.size - 1
          var index = (findZoomLevelsPosition(scale) + 1).coerceAtMost(lastZoomLevelIndex)
          if (index < lastZoomLevelIndex && areSamePercentages(scale, ZOOM_LEVELS[index])) {
            ++index
          }
          ZOOM_LEVELS[index]
        }

        ZoomType.OUT -> {
          val scale = scale
          if (scale > ZOOM_LEVELS[0]) {
            var index = findZoomLevelsPosition(scale)
            if (index > 0 && scale == ZOOM_LEVELS[index]) {
              --index
            }
            val nextScale = ZOOM_LEVELS[index]
            val fitScale = roundDownIfGreaterThanOne(computeScaleToFitInParent(newFraming))
            if (areSamePercentages(nextScale, fitScale) || (nextScale < fitScale && fitScale <= 1)) {
              return null
            }
            if (fitScale <= 1 || nextScale >= 1) nextScale else scale
          } else {
            scale
          }
        }

        ZoomType.FIT,
        ZoomType.FIT_INNER -> return null

        ZoomType.ACTUAL -> {
          if (roundDownIfGreaterThanOne(computeScaleToFitInParent(newFraming)) == 1.0) {
            return null
          }
          1.0
        }
      }
    val newScaledSize = computeActualSize(newFraming).scaled(newScale)
    return newScaledSize.scaled(1 / screenScalingFactor)
  }

  /** Returns the index of the highest zoom level not exceeding [scale], or -1 if there is no such level. */
  private fun findZoomLevelsPosition(scale: Double): Int {
    val n = ZOOM_LEVELS.size
    for (i in 0 until n) {
      if (scale < ZOOM_LEVELS[i]) {
        return i - 1
      }
    }
    return n - 1
  }

  /** Checks is the two given numbers would look the same when expressed as integer percentages. */
  private fun areSamePercentages(d1: Double, d2: Double) = (d1 * 100).roundToInt() == (d2 * 100).roundToInt()

  /**
   * Computes the scale factor that fits the content for the current [framing] into [computeMaxImageSize], with values above 1.0 rounded
   * down to an integer (ignoring [fractionalScaleRange]).
   */
  private fun computeBaseScale(): Double = roundDownIfGreaterThanOne(computeScaleToFit(framing, computeMaxImageSize()))

  private fun computeScaleToFitInParent(framing: Framing) = computeScaleToFit(framing, computeAvailableSize())

  private fun computeScaleToFit(framing: Framing, availableSize: Dimension): Double =
    computeScaleToFit(computeActualSize(framing), availableSize)

  private fun computeScaleToFit(actualSize: Dimension, availableSize: Dimension): Double {
    if (actualSize.width == 0 || actualSize.height == 0) {
      return 1.0
    }
    return min(availableSize.width.toDouble() / actualSize.width, availableSize.height.toDouble() / actualSize.height)
  }

  private fun roundDownIfGreaterThanOne(scale: Double): Double = if (scale <= 1.0) scale else floor(scale)

  private fun isFractionalGreaterThanOne(scale: Double): Boolean = scale > 1.0 && floor(scale) != scale

  /** Returns the size of the containing scroll pane without insets. */
  private fun computeAvailableSize(): Dimension = parent?.parent?.sizeWithoutInsets?.scaled(screenScalingFactor) ?: Dimension(0, 0)

  private fun ZoomType.toFraming(): Framing = if (this == ZoomType.FIT_INNER) Framing.INNER else Framing.OUTER

  enum class Framing {
    OUTER,
    INNER,
  }

  /** Zoom and scroll state of the panel. */
  class ZoomScrollState(val framing: Framing, val preferredViewSize: Dimension?, val viewCenterOffset: Point)

  private inner class PanMouseListener : MouseAdapter() {

    override fun mousePressed(event: MouseEvent) {
      if (!isPanning) {
        return
      }
      requestFocusInWindow()
      if (handlePanPopup(event)) {
        endPanning()
        return
      }
      val viewport = viewport ?: return
      if (event.button == MouseEvent.BUTTON1 || event.button == MouseEvent.BUTTON2) {
        panDragState =
          PanDragState(SwingUtilities.convertPoint(event.component, event.point, viewport), viewport.viewPosition, width, height)
        updateCursor()
      }
    }

    override fun mouseReleased(event: MouseEvent) {
      if (!isPanning) {
        return
      }
      if (handlePanPopup(event) || (event.modifiersEx and PAN_BUTTON_MASK) == 0) {
        endPanning()
      }
    }

    override fun mouseEntered(event: MouseEvent) {
      if (!isPanning) {
        return
      }
      if (isGrabbing && (event.modifiersEx and PAN_BUTTON_MASK) == 0) {
        endPanning()
      }
      updateCursor()
    }

    override fun mouseMoved(event: MouseEvent) {
      if (!isPanning) {
        return
      }
      if (isGrabbing && (event.modifiersEx and PAN_BUTTON_MASK) == 0) {
        endPanning()
      }
      updateCursor()
    }

    override fun mouseDragged(event: MouseEvent) {
      if (!isPanning) {
        return
      }
      val state = panDragState ?: return
      val viewport = viewport ?: return
      if ((event.modifiersEx and PAN_BUTTON_MASK) != 0) {
        val viewportPoint = SwingUtilities.convertPoint(event.component, event.point, viewport)
        val currentViewPosition = viewport.viewPosition
        val sizeChanged = width != state.viewWidth || height != state.viewHeight
        if (sizeChanged || currentViewPosition.x != state.lastViewPosition.x) {
          state.startViewportPoint.x = state.lastViewportPoint.x
          state.startViewPosition.x = currentViewPosition.x
        }
        if (sizeChanged || currentViewPosition.y != state.lastViewPosition.y) {
          state.startViewportPoint.y = state.lastViewportPoint.y
          state.startViewPosition.y = currentViewPosition.y
        }
        state.viewWidth = width
        state.viewHeight = height
        viewport.viewPosition =
          Point(
            (state.startViewPosition.x + state.startViewportPoint.x - viewportPoint.x).coerceIn(
              0,
              (width - viewport.width).coerceAtLeast(0),
            ),
            (state.startViewPosition.y + state.startViewportPoint.y - viewportPoint.y).coerceIn(
              0,
              (height - viewport.height).coerceAtLeast(0),
            ),
          )
        state.lastViewportPoint = viewportPoint
        state.lastViewPosition = viewport.viewPosition
      } else {
        endPanning()
      }
    }

    override fun mouseWheelMoved(event: MouseWheelEvent) {
      if (!isPanning) {
        return
      }
      val scrollPane = scrollPane ?: return
      MouseEventAdapter.redispatch(event, scrollPane)
    }
  }

  private class PanDragState(viewportPoint: Point, viewPosition: Point, var viewWidth: Int, var viewHeight: Int) {
    val startViewportPoint = Point(viewportPoint)
    val startViewPosition = Point(viewPosition)
    var lastViewportPoint: Point = viewportPoint
    var lastViewPosition: Point = viewPosition
  }
}

private const val PAN_BUTTON_MASK = BUTTON1_DOWN_MASK or BUTTON2_DOWN_MASK
