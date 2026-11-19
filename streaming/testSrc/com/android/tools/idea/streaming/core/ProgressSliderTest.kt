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

import com.android.tools.adtui.swing.FakeKeyboardFocusManager
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.PortableUiFontRule
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.event.ChangeListener
import org.junit.Rule
import org.junit.Test

/** Tests for [ProgressSlider]. */
@RunsInEdt
class ProgressSliderTest {

  private val disposableRule = DisposableRule()

  @get:Rule val ruleChain = RuleChain(ApplicationRule(), EdtRule(), PortableUiFontRule(), disposableRule)

  @Test
  fun testCircularThumbAndHighlightedPlayedTrack() {
    val slider = ProgressSlider(0, 1000, 500)
    val panel =
      JPanel(BorderLayout()).apply {
        isOpaque = false
        size = Dimension(JBUI.scale(200), JBUI.scale(40))
        add(slider, BorderLayout.CENTER)
      }
    val ui = FakeUi(panel, disposableRule.disposable)
    val image = ui.render()

    val centerX = image.width / 2
    val centerY = image.height / 2
    val expectedPlayedRgb = JBUI.CurrentTheme.DefaultTabs.underlineColor().rgb

    // Played segment to the left of the thumb is highlighted; unplayed segment to the right is distinct.
    val leftTrackRgb = image.getRGB(JBUI.scale(40), centerY)
    val rightTrackRgb = image.getRGB(image.width - JBUI.scale(40), centerY)
    assertThat(leftTrackRgb).isEqualTo(expectedPlayedRgb)
    assertThat(rightTrackRgb ushr 24).isEqualTo(0xFF)
    assertThat(rightTrackRgb).isNotEqualTo(expectedPlayedRgb)

    // Thumb is a filled circle centered on the track.
    assertThat(image.getRGB(centerX, centerY)).isEqualTo(expectedPlayedRgb)
    assertThat(image.getRGB(centerX, centerY - JBUI.scale(5))).isEqualTo(expectedPlayedRgb)
    assertThat(image.getRGB(centerX, centerY + JBUI.scale(5))).isEqualTo(expectedPlayedRgb)

    // Bounding-square corners outside the circle radius are transparent.
    assertThat(image.getRGB(centerX - JBUI.scale(7), centerY - JBUI.scale(7)) ushr 24).isEqualTo(0)
    assertThat(image.getRGB(centerX + JBUI.scale(6), centerY - JBUI.scale(7)) ushr 24).isEqualTo(0)
    assertThat(image.getRGB(centerX - JBUI.scale(7), centerY + JBUI.scale(6)) ushr 24).isEqualTo(0)
    assertThat(image.getRGB(centerX + JBUI.scale(6), centerY + JBUI.scale(6)) ushr 24).isEqualTo(0)
  }

  @Test
  fun testBoundaryValuesFocusDisabledStateAndMouseDrag() {
    val slider = ProgressSlider(0, 1000, 0)
    val panel =
      JPanel(BorderLayout()).apply {
        isOpaque = false
        size = Dimension(JBUI.scale(200), JBUI.scale(40))
        add(slider, BorderLayout.CENTER)
      }
    val ui = FakeUi(panel, disposableRule.disposable)
    val centerY = panel.height / 2
    val expectedPlayedRgb = JBUI.CurrentTheme.DefaultTabs.underlineColor().rgb

    // At value = 0, outer focus margin to the left of the thumb is transparent and right track is unplayed.
    val imageAtZero = ui.render()
    assertThat(imageAtZero.getRGB(1, centerY) ushr 24).isEqualTo(0)
    assertThat(imageAtZero.getRGB(imageAtZero.width - JBUI.scale(40), centerY)).isNotEqualTo(expectedPlayedRgb)

    // Drag thumb from 0 to 1000 and verify value and full-width played track highlight.
    var sawAdjusting = false
    slider.addChangeListener {
      if (slider.valueIsAdjusting) {
        sawAdjusting = true
      }
    }
    val startX = JBUI.scale(10)
    val endX = panel.width - JBUI.scale(10)
    ui.mouse.press(startX, centerY)
    ui.mouse.dragTo(endX, centerY)
    ui.mouse.release()
    assertThat(sawAdjusting).isTrue()
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(slider.value).isEqualTo(1000)

    val imageAtMax = ui.render()
    assertThat(imageAtMax.getRGB(JBUI.scale(40), centerY)).isEqualTo(expectedPlayedRgb)
    assertThat(imageAtMax.getRGB(panel.width - JBUI.scale(40), centerY)).isEqualTo(expectedPlayedRgb)

    // Clicking or pressing-and-dragging directly on the track jumps immediately to the clicked point and scrubs from there.
    slider.value = 0
    ui.mouse.rightClick(panel.width / 2, centerY)
    assertThat(slider.value).isEqualTo(0)

    ui.mouse.click(panel.width / 2, centerY)
    assertThat(slider.value).isIn(495..505)
    assertThat(slider.valueIsAdjusting).isFalse()

    slider.value = 0
    ui.mouse.press(JBUI.scale(60), centerY)
    assertThat(slider.valueIsAdjusting).isTrue()
    assertThat(slider.value).isGreaterThan(200)
    ui.mouse.dragTo(endX, centerY)
    assertThat(slider.value).isEqualTo(1000)
    ui.mouse.release()
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(slider.value).isEqualTo(1000)

    // Focus enlarges the thumb via the focus ring, thickens the track, and updateUI() retains ProgressSliderUI.
    slider.value = 500
    slider.updateUI()
    assertThat(slider.ui.javaClass.simpleName).isEqualTo("ProgressSliderUI")
    val unfocusedMidImage = ui.render()
    assertThat(unfocusedMidImage.getRGB(JBUI.scale(40), centerY - JBUI.scale(3)) ushr 24).isEqualTo(0)
    assertThat(unfocusedMidImage.getRGB(unfocusedMidImage.width - JBUI.scale(40), centerY - JBUI.scale(3)) ushr 24).isEqualTo(0)

    FakeKeyboardFocusManager(disposableRule.disposable).setFocusOwner(slider)
    assertThat(slider.hasFocus()).isTrue()
    val focusedImage = ui.render()
    val centerX = focusedImage.width / 2
    assertThat(focusedImage.getRGB(centerX, centerY)).isEqualTo(expectedPlayedRgb)
    assertThat(focusedImage.getRGB(centerX, centerY - JBUI.scale(8)) ushr 24).isGreaterThan(0)
    assertThat(focusedImage.getRGB(JBUI.scale(40), centerY - JBUI.scale(3))).isEqualTo(expectedPlayedRgb)
    assertThat(focusedImage.getRGB(JBUI.scale(40), centerY + JBUI.scale(2))).isEqualTo(expectedPlayedRgb)
    assertThat(focusedImage.getRGB(focusedImage.width - JBUI.scale(40), centerY - JBUI.scale(3)) ushr 24).isEqualTo(0xFF)
    assertThat(focusedImage.getRGB(focusedImage.width - JBUI.scale(40), centerY - JBUI.scale(4)) ushr 24).isEqualTo(0)

    // Disabled state still distinguishes played vs unplayed track segments and draws a filled circular thumb.
    slider.isEnabled = false
    val disabledImage = ui.render()
    val disabledLeftRgb = disabledImage.getRGB(JBUI.scale(40), centerY)
    val disabledRightRgb = disabledImage.getRGB(disabledImage.width - JBUI.scale(40), centerY)
    assertThat(disabledLeftRgb).isNotEqualTo(disabledRightRgb)
    assertThat(disabledImage.getRGB(centerX, centerY)).isEqualTo(disabledLeftRgb)
    assertThat(disabledImage.getRGB(centerX, centerY - JBUI.scale(5))).isEqualTo(disabledLeftRgb)
    assertThat(disabledImage.getRGB(centerX - JBUI.scale(7), centerY - JBUI.scale(7)) ushr 24).isEqualTo(0)

    // Disabling mid-drag or clearing valueIsAdjusting mid-drag terminates the active drag and updates thumb geometry.
    slider.isEnabled = true
    slider.value = 0
    var enabledDuringAdjustmentEnd: Boolean? = null
    val dragCancelListener = ChangeListener {
      if (!slider.valueIsAdjusting && enabledDuringAdjustmentEnd == null) {
        enabledDuringAdjustmentEnd = slider.isEnabled
      }
    }
    ui.mouse.press(startX, centerY)
    ui.mouse.dragTo(endX, centerY)
    assertThat(slider.valueIsAdjusting).isTrue()
    slider.addChangeListener(dragCancelListener)
    slider.isEnabled = false
    slider.removeChangeListener(dragCancelListener)
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(enabledDuringAdjustmentEnd).isFalse()
    slider.value = 0
    ui.mouse.release()
    assertThat(slider.valueIsAdjusting).isFalse()

    slider.isEnabled = true
    slider.value = 500
    val reenabledMidImage = ui.render()
    assertThat(reenabledMidImage.getRGB(centerX, centerY)).isEqualTo(expectedPlayedRgb)
    assertThat(reenabledMidImage.getRGB(reenabledMidImage.width - JBUI.scale(40), centerY)).isNotEqualTo(expectedPlayedRgb)

    slider.value = 0
    ui.mouse.press(startX, centerY)
    ui.mouse.dragTo(endX, centerY)
    assertThat(slider.valueIsAdjusting).isTrue()
    slider.valueIsAdjusting = false
    slider.value = 0
    ui.mouse.dragTo(panel.width / 2, centerY)
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(slider.value).isEqualTo(0)
    val resetAfterCancelImage = ui.render()
    assertThat(resetAfterCancelImage.getRGB(resetAfterCancelImage.width - JBUI.scale(40), centerY)).isNotEqualTo(expectedPlayedRgb)
    ui.mouse.release()

    // Losing visibility mid-drag also terminates the active drag and clears valueIsAdjusting.
    slider.value = 0
    ui.mouse.press(startX, centerY)
    ui.mouse.dragTo(endX, centerY)
    assertThat(slider.valueIsAdjusting).isTrue()
    panel.isVisible = false
    assertThat(slider.valueIsAdjusting).isFalse()
    panel.isVisible = true
    slider.value = 0
    ui.mouse.dragTo(panel.width / 2, centerY)
    assertThat(slider.valueIsAdjusting).isFalse()
    assertThat(slider.value).isEqualTo(0)
    ui.mouse.release()

    // No text baseline (-1), so Kotlin UI DSL vertically center-aligns the slider with adjacent labels.
    assertThat(slider.getBaseline(slider.width, slider.height)).isEqualTo(-1)
    lateinit var rowSlider: ProgressSlider
    lateinit var rowLabel: JBLabel
    val dslPanel = panel {
      row {
        rowSlider = ProgressSlider(0, 1000, 500)
        cell(rowSlider).align(AlignX.FILL).resizableColumn()
        rowLabel = JBLabel("00:00 / 00:00")
        cell(rowLabel)
      }
    }
    dslPanel.size = Dimension(JBUI.scale(300), dslPanel.preferredSize.height)
    FakeUi(dslPanel, disposableRule.disposable)
    val sliderCenterY = rowSlider.y + rowSlider.height / 2.0
    val labelCenterY = rowLabel.y + rowLabel.height / 2.0
    assertThat(sliderCenterY).isWithin(1.0).of(labelCenterY)
  }
}
