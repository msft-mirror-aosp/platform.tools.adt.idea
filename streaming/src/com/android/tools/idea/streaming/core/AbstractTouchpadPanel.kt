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
package com.android.tools.idea.streaming.core

import com.android.tools.adtui.util.scaled
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.util.height
import com.intellij.ui.util.width
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.InputEvent.BUTTON1_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseEvent.BUTTON1
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Represents the touchpad of an Android device or AVD, e.g. AI glasses. */
abstract class AbstractTouchpadPanel(protected val touchpadSize: Dimension) : BorderLayoutPanel() {

  /**
   * A touch point in touchpad coordinates (`0 until touchpadSize.width`, `0 until touchpadSize.height`), with `(0, 0)` at the top-left
   * corner and [y] increasing downward.
   */
  data class Touch(val id: Int, var x: Int = 0, var y: Int = 0, var pressure: Int = 0)

  private var multiTouchMode: Boolean = false
    set(value) {
      if (value != field) {
        terminateDragging()
        field = value
        repaint()
      }
    }

  /** Last coordinates of the mouse pointer while touch feedback is visible inside the touchpad, or null otherwise. */
  private var lastMouseCoordinates: Point? = null
    set(value) {
      if (value != field) {
        field = value
        repaint()
      }
    }

  private var mouseButtonPressed = false
    set(value) {
      if (value != field) {
        field = value
        repaint()
      }
    }

  private var previousTouchpadPoint: Point? = null
  private val mouseListener = MyMouseListener()
  private val forwardingMouseListener = ForwardingMouseListener()
  protected val touches: Array<Touch> = Array(2) { id -> Touch(id) }

  init {
    border = JBUI.Borders.customLine(JBColor.border())
    background = JBColor(0xF9FAFE, 0x393B40)
    val color = JBUI.CurrentTheme.Tooltip.grayedForeground()
    addToCenter(JBLabel("Hold shift for two-finger mode", SwingConstants.CENTER).apply { foreground = color })
    addMouseListener(mouseListener)
    addMouseMotionListener(mouseListener)
    addKeyListener(MyKeyListener())
  }

  /** Allows touchpad drag gestures to start on [component] (e.g., an enclosing margin panel) and cross into this touchpad. */
  internal fun registerDragSource(component: Component) {
    component.addMouseListener(forwardingMouseListener)
    component.addMouseMotionListener(forwardingMouseListener)
  }

  override fun getPreferredSize(): Dimension {
    val insets = insets
    return Dimension(
      JBUIScale.scale(PREFERRED_HEIGHT.scaled(touchpadSize.width, touchpadSize.height)) + insets.width,
      JBUIScale.scale(PREFERRED_HEIGHT) + insets.height,
    )
  }

  override fun getMinimumSize(): Dimension = preferredSize

  override fun getMaximumSize(): Dimension = preferredSize

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    drawTouchFeedback(graphics)
  }

  private fun drawTouchFeedback(graphics: Graphics) {
    val point = lastMouseCoordinates ?: return
    val insets = insets
    val w = width - insets.width
    val h = height - insets.height
    val touchpadRectangle = Rectangle(insets.left, insets.top, w, h)
    val scale = w.toDouble() / touchpadSize.width
    val radius = TOUCH_FEEDBACK_RADIUS.scaled(scale)
    val fingers =
      if (multiTouchMode) {
        val fingerDistance = FINGER_DISTANCE.scaled(scale)
        val x1 = point.x - fingerDistance / 2
        listOf(Point(x1, point.y), Point(x1 + fingerDistance, point.y))
      } else {
        listOf(point)
      }
    if (fingers.none { touchpadRectangle.contains(it) }) {
      return
    }

    val g = graphics.create() as Graphics2D
    try {
      @Suppress("UsePropertyAccessSyntax") // False positive.
      g.setRenderingHints(
        RenderingHints(
          mapOf(
            RenderingHints.KEY_ANTIALIASING to RenderingHints.VALUE_ANTIALIAS_ON,
            RenderingHints.KEY_RENDERING to RenderingHints.VALUE_RENDER_QUALITY,
          )
        )
      )
      g.clip = touchpadRectangle
      g.color = JBUI.CurrentTheme.Tooltip.grayedForeground()
      for (finger in fingers) {
        drawTouchFeedback(g, finger, radius)
      }
    } finally {
      g.dispose()
    }
  }

  private fun drawTouchFeedback(g: Graphics2D, point: Point, radius: Int) {
    if (mouseButtonPressed) {
      g.fillCircle(point, radius)
    } else {
      g.drawCircle(point, radius)
    }
  }

  private fun updateMouseState(x: Int, y: Int, modifiers: Int) {
    mouseButtonPressed = (modifiers and BUTTON1_DOWN_MASK) != 0
    multiTouchMode = (modifiers and SHIFT_DOWN_MASK) != 0
    lastMouseCoordinates = if (hasVisibleTouchFeedback(x, y)) Point(x, y) else null
  }

  private fun hasVisibleTouchFeedback(x: Int, y: Int): Boolean {
    val insets = insets
    val w = width - insets.width
    val h = height - insets.height
    if (w <= 0 || h <= 0) {
      return false
    }
    val touchpadRectangle = Rectangle(insets.left, insets.top, w, h)
    if (!multiTouchMode) {
      return touchpadRectangle.contains(x, y)
    }
    val fingerDistance = FINGER_DISTANCE.scaled(w.toDouble() / touchpadSize.width)
    val x1 = x - fingerDistance / 2
    return touchpadRectangle.contains(x1, y) || touchpadRectangle.contains(x1 + fingerDistance, y)
  }

  // Terminates ongoing dragging if any.
  private fun terminateDragging() {
    val touchesToSend = mutableListOf<Touch>()
    for (touch in touches) {
      if (touch.pressure != 0) {
        touch.pressure = 0
        touchesToSend.add(touch.copy())
      }
    }
    if (touchesToSend.isNotEmpty()) {
      sendTouches(touchesToSend)
    }
  }

  private fun toTouchpadCoordinates(x: Int, y: Int): Point {
    val insets = insets
    val w = width - insets.width
    val h = height - insets.height
    val touchpadX = (x - insets.left).scaledUnbiased(w, touchpadSize.width)
    val touchpadY = (y - insets.top).scaledUnbiased(h, touchpadSize.height)
    return Point(touchpadX, touchpadY)
  }

  private fun handleMousePressed(x: Int, y: Int, modifiers: Int, requestFocusImmediately: Boolean = true) {
    if (width <= insets.width || height <= insets.height) {
      return
    }
    if (requestFocusImmediately) {
      requestFocusInWindow()
    }
    updateMouseState(x, y, modifiers)
    if (mouseButtonPressed) {
      val curr = toTouchpadCoordinates(x, y)
      previousTouchpadPoint = curr
      sendTouchpadEvent(curr.x, curr.y, true)
      if (!requestFocusImmediately && touches.any { it.pressure != 0 }) {
        requestFocusInWindow()
      }
    } else {
      previousTouchpadPoint = null
    }
  }

  private fun handleMouseDragged(x: Int, y: Int, modifiers: Int) {
    if (width <= insets.width || height <= insets.height) {
      return
    }
    updateMouseState(x, y, modifiers)
    if (!mouseButtonPressed) {
      previousTouchpadPoint = null
      return
    }
    val curr = toTouchpadCoordinates(x, y)
    val prev = previousTouchpadPoint
    previousTouchpadPoint = curr
    if (prev == null) {
      sendTouchpadEvent(curr.x, curr.y, true)
      return
    }

    val halfFingerDistance = if (multiTouchMode) FINGER_DISTANCE / 2 else 0
    val minMidX = -halfFingerDistance
    val maxMidX = touchpadSize.width - 1 + halfFingerDistance
    val maxY = touchpadSize.height - 1
    val wasVerticallyInside = prev.y in 0..maxY
    val isVerticallyInside = curr.y in 0..maxY

    when {
      !wasVerticallyInside && !isVerticallyInside -> {
        // Fast vertical flick that jumped completely across the touchpad in a single mouse frame.
        val clipped = clipSegment(prev.x, prev.y, curr.x, curr.y, minMidX, maxMidX, 0, maxY)
        if (clipped != null) {
          ensureFocused()
          sendTouchpadEvent(clipped.entry.x, clipped.entry.y, true)
          if (clipped.exit != clipped.entry) {
            sendTouchpadEvent(clipped.exit.x, clipped.exit.y, true)
          }
          sendTouchpadEvent(clipped.exit.x, clipped.exit.y, false)
        }
      }
      !wasVerticallyInside && isVerticallyInside -> {
        // Entered across the top or bottom border: anchor ACTION_DOWN at the border intersection.
        val clipped = clipSegment(prev.x, prev.y, curr.x, curr.y, minMidX, maxMidX, 0, maxY)
        if (clipped != null) {
          ensureFocused()
          sendTouchpadEvent(clipped.entry.x, clipped.entry.y, true)
          if (curr != clipped.entry) {
            sendTouchpadEvent(curr.x, curr.y, true)
          }
        } else {
          sendTouchpadEvent(curr.x, curr.y, true)
        }
      }
      wasVerticallyInside && !isVerticallyInside -> {
        // Exited across the top or bottom border: emit ACTION_MOVE at the border intersection before ACTION_UP.
        val clipped = clipSegment(prev.x, prev.y, curr.x, curr.y, minMidX, maxMidX, 0, maxY)
        if (clipped != null && touches.any { it.pressure != 0 }) {
          ensureFocused()
          if (clipped.exit != prev) {
            sendTouchpadEvent(clipped.exit.x, clipped.exit.y, true)
          }
          sendTouchpadEvent(clipped.exit.x, clipped.exit.y, false)
        } else {
          sendTouchpadEvent(curr.x, curr.y, true)
        }
      }
      else -> {
        if (curr.x in minMidX..maxMidX) {
          ensureFocused()
        } else if (prev.x !in minMidX..maxMidX) {
          val clipped = clipSegment(prev.x, prev.y, curr.x, curr.y, minMidX, maxMidX, 0, maxY)
          if (clipped != null) {
            ensureFocused()
            sendTouchpadEvent(clipped.entry.x, clipped.entry.y, true)
            if (clipped.exit != clipped.entry) {
              sendTouchpadEvent(clipped.exit.x, clipped.exit.y, true)
            }
            sendTouchpadEvent(clipped.exit.x, clipped.exit.y, false)
            return
          }
        }
        sendTouchpadEvent(curr.x, curr.y, true)
      }
    }
  }

  private fun handleMouseReleased(x: Int, y: Int, button: Int, modifiers: Int) {
    if (width <= insets.width || height <= insets.height) {
      previousTouchpadPoint = null
      return
    }
    updateMouseState(x, y, modifiers)
    if (button == BUTTON1) {
      previousTouchpadPoint = null
      val curr = toTouchpadCoordinates(x, y)
      sendTouchpadEvent(curr.x, curr.y, false)
    }
  }

  private fun ensureFocused() {
    if (!isFocusOwner) {
      requestFocusInWindow()
    }
  }

  private fun sendTouchpadEvent(touchpadX: Int, touchpadY: Int, withPressure: Boolean) {
    val pressure = if (withPressure) PRESSURE_RANGE_MAX else 0
    val touchesToSend = mutableListOf<Touch>()
    if (multiTouchMode) {
      addTouchWithAdjustments(0, touchpadX - FINGER_DISTANCE / 2, touchpadY, pressure, touchesToSend)
      addTouchWithAdjustments(1, touchpadX + FINGER_DISTANCE / 2, touchpadY, pressure, touchesToSend)
    } else {
      addTouchWithAdjustments(0, touchpadX, touchpadY, pressure, touchesToSend)
    }
    if (touchesToSend.isNotEmpty()) {
      sendTouches(touchesToSend)
    }
  }

  private fun addTouchWithAdjustments(id: Int, x: Int, y: Int, pressure: Int, touchesToSend: MutableList<Touch>) {
    if (isInsideTouchpad(x, y)) {
      addTouch(id, x, y, pressure, touchesToSend)
    } else if (touches[id].pressure != 0) {
      // The pointer crosses the touchpad boundary while dragging.
      addTouch(id, x.coerceIn(0, touchpadSize.width - 1), y.coerceIn(0, touchpadSize.height - 1), 0, touchesToSend)
    }
  }

  private fun addTouch(id: Int, x: Int, y: Int, pressure: Int, touchesToSend: MutableList<Touch>) {
    val touch = touches[id]
    touch.x = x
    touch.y = y
    touch.pressure = pressure
    touchesToSend.add(touch.copy())
  }

  /** Sends [touches] to the device or emulator. Note that [Touch.y] is top-down (`0` at the top edge). */
  protected abstract fun sendTouches(touches: List<Touch>)

  private fun isInsideTouchpad(x: Int, y: Int): Boolean = 0 <= x && x < touchpadSize.width && 0 <= y && y < touchpadSize.height

  private inner class MyMouseListener : MouseAdapter() {

    override fun mousePressed(event: MouseEvent) {
      handleMousePressed(event.x, event.y, event.modifiersEx)
    }

    override fun mouseReleased(event: MouseEvent) {
      handleMouseReleased(event.x, event.y, event.button, event.modifiersEx)
    }

    override fun mouseExited(event: MouseEvent) {
      if (!mouseButtonPressed) {
        lastMouseCoordinates = null
      }
    }

    override fun mouseDragged(event: MouseEvent) {
      handleMouseDragged(event.x, event.y, event.modifiersEx)
    }

    override fun mouseMoved(event: MouseEvent) {
      updateMouseState(event.x, event.y, event.modifiersEx)
    }
  }

  private inner class ForwardingMouseListener : MouseAdapter() {

    override fun mousePressed(event: MouseEvent) {
      if (event.button == BUTTON1) {
        val point = SwingUtilities.convertPoint(event.component, event.point, this@AbstractTouchpadPanel)
        handleMousePressed(point.x, point.y, event.modifiersEx, requestFocusImmediately = false)
      }
    }

    override fun mouseDragged(event: MouseEvent) {
      val point = SwingUtilities.convertPoint(event.component, event.point, this@AbstractTouchpadPanel)
      handleMouseDragged(point.x, point.y, event.modifiersEx)
    }

    override fun mouseReleased(event: MouseEvent) {
      if (event.button == BUTTON1) {
        val point = SwingUtilities.convertPoint(event.component, event.point, this@AbstractTouchpadPanel)
        handleMouseReleased(point.x, point.y, event.button, event.modifiersEx)
      }
    }
  }

  private inner class MyKeyListener : KeyAdapter() {

    override fun keyPressed(event: KeyEvent) {
      updateMultiTouchMode(event)
    }

    override fun keyReleased(event: KeyEvent) {
      updateMultiTouchMode(event)
    }

    private fun updateMultiTouchMode(event: KeyEvent) {
      multiTouchMode = (event.modifiersEx and SHIFT_DOWN_MASK) != 0
    }
  }
}

private class ClippedSegment(val entry: Point, val exit: Point)

/** Clips segment `(x0, y0) -> (x1, y1)` to `[minX, maxX] x [minY, maxY]` using Liang-Barsky, returning null if disjoint. */
private fun clipSegment(x0: Int, y0: Int, x1: Int, y1: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): ClippedSegment? {
  if (minX > maxX || minY > maxY) {
    return null
  }
  val dx = (x1 - x0).toDouble()
  val dy = (y1 - y0).toDouble()
  var tEnter = 0.0
  var tExit = 1.0

  fun clipAxis(p: Double, q: Double): Boolean {
    if (p == 0.0) {
      return q >= 0.0
    }
    val t = q / p
    if (p < 0.0) {
      if (t > tExit) return false
      if (t > tEnter) tEnter = t
    } else {
      if (t < tEnter) return false
      if (t < tExit) tExit = t
    }
    return true
  }

  if (
    !clipAxis(-dx, (x0 - minX).toDouble()) ||
      !clipAxis(dx, (maxX - x0).toDouble()) ||
      !clipAxis(-dy, (y0 - minY).toDouble()) ||
      !clipAxis(dy, (maxY - y0).toDouble())
  ) {
    return null
  }

  val entryX = (x0 + tEnter * dx).roundToInt().coerceIn(minX, maxX)
  val entryY = (y0 + tEnter * dy).roundToInt().coerceIn(minY, maxY)
  val exitX = (x0 + tExit * dx).roundToInt().coerceIn(minX, maxX)
  val exitY = (y0 + tExit * dy).roundToInt().coerceIn(minY, maxY)
  return ClippedSegment(Point(entryX, entryY), Point(exitX, exitY))
}

private const val PREFERRED_HEIGHT = 40
private const val TOUCH_FEEDBACK_RADIUS: Int = 100
private const val FINGER_DISTANCE: Int = 260
const val PRESSURE_RANGE_MAX = 0x400
