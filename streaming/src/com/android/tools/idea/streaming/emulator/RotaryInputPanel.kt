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

import com.android.emulator.control.InputEvent as InputEventMessage
import com.android.emulator.control.WheelEvent
import com.android.tools.adtui.common.primaryPanelBackground
import com.android.tools.idea.streaming.core.getNormalizedScrollAmount
import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.InputEvent.BUTTON1_DOWN_MASK
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Panel simulating the rotary input control of a Wear OS watch. */
internal class RotaryInputPanel(private val emulator: EmulatorController) : JPanel(null) {

  /** Current rotation angle of the dial in degrees `[0, 360)`, where 0 puts the indicator dot at 9 o'clock (left). */
  private var dialAngle: Int = 0
  private var accumulatedScrollAngle: Double = 0.0
  private var dragging: Boolean = false

  init {
    background = primaryPanelBackground
    border = JBUI.Borders.compound(IdeBorderFactory.createBorder(JBColor.border(), SideBorder.TOP), JBUI.Borders.empty(10))
    setToolTipText(HtmlChunk.text("Use mouse, trackpad or mouse wheel to turn the rotary input"))

    val mouseAdapter =
      object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
          if (SwingUtilities.isLeftMouseButton(event) && isInsideDial(event.x, event.y)) {
            dragging = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            updateAngleFromMouse(event.x, event.y)
          }
        }

        override fun mouseDragged(event: MouseEvent) {
          if (dragging && (event.modifiersEx and BUTTON1_DOWN_MASK) != 0) {
            updateAngleFromMouse(event.x, event.y)
          }
        }

        override fun mouseReleased(event: MouseEvent) {
          if (SwingUtilities.isLeftMouseButton(event)) {
            dragging = false
            updateCursor(event.x, event.y)
          }
        }

        override fun mouseMoved(event: MouseEvent) {
          updateCursor(event.x, event.y)
        }

        override fun mouseExited(event: MouseEvent) {
          if (!dragging) {
            cursor = null
          }
        }

        override fun mouseWheelMoved(event: MouseWheelEvent) {
          accumulatedScrollAngle += event.getNormalizedScrollAmount(1.0) * DEGREES_PER_WHEEL_CLICK
          val angleDelta = accumulatedScrollAngle.toInt()
          if (angleDelta != 0) {
            accumulatedScrollAngle -= angleDelta
            setDialAngle(dialAngle + angleDelta)
          }
        }
      }
    addMouseListener(mouseAdapter)
    addMouseMotionListener(mouseAdapter)
    addMouseWheelListener(mouseAdapter)
  }

  override fun getPreferredSize(): Dimension {
    val scale = JBUIScale.scale(DEFAULT_SCALE).toDouble()
    val insets = insets
    val width = (REF_WIDTH * scale).roundToInt() + insets.left + insets.right
    val height = (REF_HEIGHT * scale).roundToInt() + insets.top + insets.bottom
    return Dimension(width, height)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

      val scale = computeScale()
      g2.translate(computeOriginX(scale), computeOriginY(scale))
      g2.scale(scale, scale)

      g2.color = WATCH_STRAP_COLOR
      g2.fill(WATCH_STRAP_SHAPE)
      g2.color = WATCH_BODY_COLOR
      g2.fill(WATCH_BODY_SHAPE)
      g2.color = WATCH_BEVEL_COLOR
      g2.fill(WATCH_BEVEL_AND_BACK_SHAPE)

      g2.translate(DIAL_CENTER_X, DIAL_CENTER_Y)
      g2.color = DIAL_TRACK_COLOR
      g2.fill(DIAL_TRACK_SHAPE)

      g2.rotate(Math.toRadians(dialAngle.toDouble()))
      g2.color = DIAL_CONTROL_COLOR
      g2.fill(DIAL_DOT_AND_GEAR_SHAPE)
    } finally {
      g2.dispose()
    }
  }

  private fun computeScale(): Double {
    val insets = insets
    val availWidth = (width - insets.left - insets.right).coerceAtLeast(0)
    val availHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
    val maxScale = JBUIScale.scale(DEFAULT_SCALE).toDouble()
    return minOf(maxScale, availWidth / REF_WIDTH, availHeight / REF_HEIGHT).coerceAtLeast(0.1)
  }

  private fun computeOriginX(scale: Double): Double {
    val insets = insets
    val availWidth = (width - insets.left - insets.right).coerceAtLeast(0)
    return insets.left + (availWidth - REF_WIDTH * scale) / 2.0
  }

  private fun computeOriginY(scale: Double): Double {
    val insets = insets
    val availHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
    return insets.top + (availHeight - REF_HEIGHT * scale) / 2.0
  }

  private fun updateCursor(x: Int, y: Int) {
    cursor = if (isInsideDial(x, y)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
  }

  private fun isInsideDial(x: Int, y: Int): Boolean {
    val scale = computeScale()
    val dialCenterX = computeOriginX(scale) + DIAL_CENTER_X * scale
    val dialCenterY = computeOriginY(scale) + DIAL_CENTER_Y * scale
    return hypot(x - dialCenterX, y - dialCenterY) <= DIAL_INTERACTION_RADIUS * scale
  }

  private fun updateAngleFromMouse(x: Int, y: Int) {
    val scale = computeScale()
    val dx = x - (computeOriginX(scale) + DIAL_CENTER_X * scale)
    val dy = y - (computeOriginY(scale) + DIAL_CENTER_Y * scale)
    if (dx == 0.0 && dy == 0.0) {
      return
    }
    val newAngle = Math.toDegrees(atan2(-dy, -dx)).roundToInt()
    setDialAngle(newAngle)
  }

  private fun setDialAngle(newAngle: Int) {
    val normalizedAngle = newAngle.mod(360)
    val delta = dialDeltaToRotaryInputDelta(normalizedAngle, dialAngle)
    if (delta != 0) {
      dialAngle = normalizedAngle
      repaint()
      sendRotaryEvent(delta)
    }
  }

  private fun sendRotaryEvent(delta: Int) {
    // WheelEventSender in the emulator divides dy by 8 to obtain the rotary angle in degrees.
    val wheelEvent = WheelEvent.newBuilder().setDy(delta * ROTARY_DY_SCALING_FACTOR)
    val inputEvent = InputEventMessage.newBuilder().setWheelEvent(wheelEvent).build()
    emulator.getOrCreateInputEventSender().onNext(inputEvent)
  }

  companion object {
    private const val WATCH_OFFSET_X = 29.0
    private const val REF_WIDTH = 184.0
    private const val REF_HEIGHT = 212.0
    private const val DEFAULT_SCALE = 0.5f

    private const val DIAL_CENTER_X = 46.0
    private const val DIAL_CENTER_Y = 106.0
    private const val DIAL_RADIUS = 46.0
    private const val DIAL_INTERACTION_RADIUS = DIAL_RADIUS * 2
    private const val DIAL_SVG_SIZE = 64.0
    private const val DIAL_SVG_SCALE = (DIAL_RADIUS * 2.0) / DIAL_SVG_SIZE

    private const val DEGREES_PER_WHEEL_CLICK = 15.0
    private const val ROTARY_DY_SCALING_FACTOR = 8

    private val WATCH_STRAP_COLOR = JBColor(0x1F1F20, 0x1F1F20)
    private val WATCH_BODY_COLOR = JBColor(0x2D2D2D, 0x2D2D2D)
    private val WATCH_BEVEL_COLOR = JBColor(0x444444, 0x444444)
    private val DIAL_TRACK_COLOR = JBColor(Color(0, 0, 0, 31), Color(255, 255, 255, 31))
    private val DIAL_CONTROL_COLOR = JBColor(0x4083C9, 0x355880)

    private val WATCH_STRAP_SHAPE: Shape = createWatchStrap()
    private val WATCH_BODY_SHAPE: Shape = createWatchBody()
    private val WATCH_BEVEL_AND_BACK_SHAPE: Shape = createWatchBevelAndBack()
    private val DIAL_TRACK_SHAPE: Shape = createDialTrack()
    private val DIAL_DOT_AND_GEAR_SHAPE: Shape = createDialDotAndGear()

    /**
     * Returns the angle difference between two dial angles in `[-180, 180)`, inverted to match the rotation direction of the watch rotary
     * input.
     */
    private fun dialDeltaToRotaryInputDelta(newAngle: Int, oldAngle: Int): Int = (oldAngle - newAngle + 540) % 360 - 180

    private fun createCircle(centerX: Double, centerY: Double, radius: Double): Shape =
      Ellipse2D.Double(centerX - radius, centerY - radius, radius * 2, radius * 2)

    private fun createWatchStrap(): Shape {
      val path =
        Path2D.Double().apply {
          moveTo(152.378, 142.02)
          curveTo(152.378, 180.02, 121.453, 210.86, 83.443, 210.86)
          curveTo(45.423, 210.86, 14.498, 180.02, 14.498, 142.02)
          lineTo(25.338, 142.02)
          curveTo(25.338, 174.02, 51.318, 200.02, 83.358, 200.02)
          curveTo(115.388, 200.02, 141.378, 174.02, 141.378, 142.02)
          lineTo(141.378, 69.02)
          curveTo(141.378, 37.02, 115.388, 11.02, 83.358, 11.02)
          curveTo(51.318, 11.02, 25.338, 37.02, 25.338, 69.02)
          lineTo(14.498, 69.02)
          curveTo(14.498, 31.02, 45.318, 0.0, 83.338, 0.0)
          curveTo(102.338, 0.0, 119.653, 7.79, 132.113, 20.25)
          curveTo(144.563, 32.71, 152.378, 50.02, 152.378, 69.02)
          closePath()
        }
      return AffineTransform.getTranslateInstance(WATCH_OFFSET_X, 0.0).createTransformedShape(path)
    }

    private fun createWatchBody(): Shape {
      val path =
        Path2D.Double().apply {
          moveTo(33.667, 171.09)
          curveTo(33.667, 176.107, 29.6, 180.173, 24.584, 180.173)
          lineTo(1.667, 180.173)
          lineTo(1.667, 29.563)
          lineTo(24.584, 29.563)
          curveTo(29.601, 29.563, 33.667, 33.63, 33.667, 38.646)
          closePath()
        }
      return AffineTransform.getTranslateInstance(WATCH_OFFSET_X, 0.0).createTransformedShape(path)
    }

    private fun createWatchBevelAndBack(): Shape {
      val path =
        Path2D.Double().apply {
          moveTo(1.667, 180.174)
          lineTo(0.0, 178.974)
          lineTo(0.0, 30.763)
          lineTo(1.667, 29.563)
          closePath()
          moveTo(37.758, 169.762)
          lineTo(33.667, 171.84)
          lineTo(33.667, 38.396)
          lineTo(37.758, 40.474)
          curveTo(41.319, 89.528, 41.718, 119.272, 37.758, 169.762)
          closePath()
        }
      return AffineTransform.getTranslateInstance(WATCH_OFFSET_X, 0.0).createTransformedShape(path)
    }

    private fun createDialTrack(): Shape {
      val outerRadius = 28.328 * DIAL_SVG_SCALE
      val innerRadius = 26.867 * DIAL_SVG_SCALE
      return Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
        append(createCircle(0.0, 0.0, outerRadius), false)
        append(createCircle(0.0, 0.0, innerRadius), false)
      }
    }

    private fun createDialDotAndGear(): Shape {
      val path = Path2D.Double()
      // Highlighted dot on the left of the track ring (at cx=5, cy=32.208, r=5 in 64x64 SVG coordinates).
      path.append(createCircle(-27.0, 0.208, 5.0), false)

      // 12-tooth gear at the center of the dial.
      val toothCap =
        Path2D.Double().apply {
          moveTo(6.1, -1.157)
          curveTo(6.739, -1.157, 7.257, -0.639, 7.257, 0.0)
          curveTo(7.257, 0.639, 6.739, 1.157, 6.1, 1.157)
        }
      val gear = Path2D.Double()
      for (i in 0 until 12) {
        val rotatedTooth = AffineTransform.getRotateInstance(i * PI / 6.0).createTransformedShape(toothCap)
        gear.append(rotatedTooth, i > 0)
      }
      gear.closePath()
      path.append(gear, false)

      return AffineTransform.getScaleInstance(DIAL_SVG_SCALE, DIAL_SVG_SCALE).createTransformedShape(path)
    }
  }
}
