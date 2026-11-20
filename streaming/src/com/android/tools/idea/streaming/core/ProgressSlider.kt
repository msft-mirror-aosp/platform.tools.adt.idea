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

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JSlider
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicSliderUI

/** Horizontal [JSlider] with a filled circular thumb and a highlighted track segment to the left of the thumb. */
internal class ProgressSlider(min: Int = 0, max: Int = 100, value: Int = 0) : JSlider(HORIZONTAL, min, max, value) {

  private var cancellingDrag = false

  init {
    isOpaque = false
    paintTicks = false
    paintLabels = false
    putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(FOCUS_RING_THICKNESS))
  }

  override fun isEnabled(): Boolean = super.isEnabled() || cancellingDrag

  override fun setEnabled(enabled: Boolean) {
    super.setEnabled(enabled)
    if (!enabled) {
      (ui as? ProgressSliderUI)?.cancelDrag()
      if (valueIsAdjusting) {
        valueIsAdjusting = false
      }
    }
  }

  override fun setValueIsAdjusting(b: Boolean) {
    cancellingDrag = false
    if (!b && (ui as? ProgressSliderUI)?.cancelDrag() == true) {
      return
    }
    super.setValueIsAdjusting(b)
  }

  override fun updateUI() {
    setUI(ProgressSliderUI(this))
  }

  private class ProgressSliderUI(private val progressSlider: ProgressSlider) : BasicSliderUI(progressSlider) {

    fun cancelDrag(): Boolean {
      if (!isDragging) return false
      progressSlider.cancellingDrag = true
      try {
        trackListener.mouseReleased(MouseEvent(slider, MouseEvent.MOUSE_RELEASED, 0L, 0, 0, 0, 1, false, MouseEvent.BUTTON1))
      } finally {
        progressSlider.cancellingDrag = false
      }
      return true
    }

    override fun installDefaults(slider: JSlider) {
      LookAndFeel.installColorsAndFont(slider, "Slider.background", "Slider.foreground", "Slider.font")
      focusInsets = JBUI.emptyInsets()
    }

    override fun createTrackListener(slider: JSlider): TrackListener =
      object : TrackListener() {
        override fun mousePressed(e: MouseEvent) {
          if (!slider.isEnabled || !SwingUtilities.isLeftMouseButton(e)) return
          calculateGeometry()
          val centerX = thumbRect.x + thumbRect.width / 2.0
          val centerY = thumbRect.y + thumbRect.height / 2.0
          val radius = JBUI.scale(if (slider.hasFocus()) THUMB_RADIUS + FOCUS_RING_THICKNESS else THUMB_RADIUS).toDouble()
          val dx = e.x - centerX
          val dy = e.y - centerY
          if (dx * dx + dy * dy <= radius * radius && thumbRect.contains(e.x, e.y)) {
            super.mousePressed(e)
            if (isDragging) {
              slider.valueIsAdjusting = true
            }
            return
          }

          slider.valueIsAdjusting = true
          slider.value = valueForXPosition(e.x)
          calculateThumbLocation()
          val thumbCenterX = thumbRect.x + thumbRect.width / 2
          val thumbCenterY = thumbRect.y + thumbRect.height / 2
          super.mousePressed(
            MouseEvent(
              slider,
              e.id,
              e.`when`,
              e.modifiersEx,
              thumbCenterX,
              thumbCenterY,
              e.xOnScreen,
              e.yOnScreen,
              e.clickCount,
              e.isPopupTrigger,
              e.button,
            )
          )
          currentMouseX = e.x
          currentMouseY = e.y
        }
      }

    override fun getBaseline(c: JComponent, width: Int, height: Int): Int {
      super.getBaseline(c, width, height)
      return -1
    }

    override fun getThumbSize(): Dimension {
      val halfSize = JBUI.scale(THUMB_RADIUS) + JBUI.scale(FOCUS_RING_THICKNESS)
      val size = halfSize * 2
      return Dimension(size, size)
    }

    override fun calculateTrackRect() {
      super.calculateTrackRect()
      if (slider.orientation == HORIZONTAL) {
        trackRect.y = contentRect.y + (contentRect.height - trackRect.height) / 2
      }
    }

    override fun setThumbLocation(x: Int, y: Int) {
      super.setThumbLocation(x, y)
      slider.repaint()
    }

    override fun paintTrack(g: Graphics) {
      if (slider.orientation != HORIZONTAL || trackRect.width <= 1) return
      val g2d = g.create() as Graphics2D
      try {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val trackThickness = if (slider.isEnabled && slider.hasFocus()) FOCUSED_TRACK_THICKNESS else TRACK_THICKNESS
        val trackHeight = JBUI.scale(trackThickness).toDouble()
        val trackLeft = trackRect.x.toDouble()
        val trackRight = (trackRect.x + trackRect.width - 1).toDouble()
        val trackWidth = trackRight - trackLeft
        if (trackWidth <= 0.0) return

        val centerY = thumbRect.y + thumbRect.height / 2.0
        val trackY = centerY - trackHeight / 2.0
        val thumbCenterX = (thumbRect.x + thumbRect.width / 2.0).coerceIn(trackLeft, trackRight)

        val playedColor = if (slider.isEnabled) activeColor() else disabledActiveColor()
        val unplayedColor = if (slider.isEnabled) TRACK_COLOR else DISABLED_TRACK_COLOR

        val overlap = minOf(trackHeight, JBUI.scale(THUMB_RADIUS).toDouble())
        val playedWidth = thumbCenterX - trackLeft
        val remainingWidth = trackRight - thumbCenterX

        if (remainingWidth > 0.0) {
          val unplayedStart = if (playedWidth > 0.0) (thumbCenterX - overlap).coerceAtLeast(trackLeft) else trackLeft
          g2d.paint = unplayedColor
          g2d.fill(RoundRectangle2D.Double(unplayedStart, trackY, trackRight - unplayedStart, trackHeight, trackHeight, trackHeight))
        }

        if (playedWidth > 0.0) {
          val playedEnd = if (remainingWidth > 0.0) (thumbCenterX + overlap).coerceAtMost(trackRight) else trackRight
          g2d.paint = playedColor
          g2d.fill(RoundRectangle2D.Double(trackLeft, trackY, playedEnd - trackLeft, trackHeight, trackHeight, trackHeight))
        }
      } finally {
        g2d.dispose()
      }
    }

    override fun paintThumb(g: Graphics) {
      if (slider.orientation != HORIZONTAL) return
      val g2d = g.create() as Graphics2D
      try {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val thumbRadius = JBUI.scale(THUMB_RADIUS).toDouble()
        val focusThickness = JBUI.scale(FOCUS_RING_THICKNESS).toDouble()
        val centerX = thumbRect.x + thumbRect.width / 2.0
        val centerY = thumbRect.y + thumbRect.height / 2.0

        if (slider.isEnabled && slider.hasFocus() && focusThickness > 0.0) {
          val ringRadius = thumbRadius + focusThickness / 2.0
          g2d.paint = focusColor()
          g2d.stroke = BasicStroke(focusThickness.toFloat())
          g2d.draw(Ellipse2D.Double(centerX - ringRadius, centerY - ringRadius, ringRadius * 2.0, ringRadius * 2.0))
        }

        val diameter = thumbRadius * 2.0
        g2d.paint = if (slider.isEnabled) activeColor() else disabledActiveColor()
        g2d.fill(Ellipse2D.Double(centerX - thumbRadius, centerY - thumbRadius, diameter, diameter))
      } finally {
        g2d.dispose()
      }
    }

    override fun paintFocus(g: Graphics) = Unit
  }

  companion object {
    private const val THUMB_RADIUS = 7
    private const val FOCUS_RING_THICKNESS = 3
    private const val TRACK_THICKNESS = 4
    private const val FOCUSED_TRACK_THICKNESS = 6

    private val OPAQUE_BACKGROUND: Color = JBColor.lazy { ColorUtil.toAlpha(JBColor.PanelBackground, 255) }
    private val TRACK_COLOR: Color = JBColor.lazy {
      ColorUtil.alphaBlending(JBColor.namedColor("Slider.trackColor", JBColor(0xC9CCD6, 0x5A5D63)), OPAQUE_BACKGROUND)
    }
    private val DISABLED_TRACK_COLOR: Color = JBColor.lazy {
      ColorUtil.alphaBlending(JBColor.namedColor("Component.disabledBorderColor", JBColor(0xDFE1E5, 0x43454A)), OPAQUE_BACKGROUND)
    }

    private fun activeColor(): Color = ColorUtil.alphaBlending(JBUI.CurrentTheme.DefaultTabs.underlineColor(), OPAQUE_BACKGROUND)

    private fun disabledActiveColor(): Color =
      ColorUtil.alphaBlending(JBUI.CurrentTheme.DefaultTabs.inactiveUnderlineColor(), OPAQUE_BACKGROUND)

    private fun focusColor(): Color = ColorUtil.alphaBlending(JBUI.CurrentTheme.Focus.focusColor(), OPAQUE_BACKGROUND)
  }
}
