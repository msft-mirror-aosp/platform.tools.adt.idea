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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.emulator.control.Posture.PostureValue
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.streaming.StagingIcons
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [DevicePoseDialog]. */
@RunsInEdt
class DevicePoseDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      EdtRule(),
      HeadlessDialogRule(),
    )

  private fun createAndStartEmulator(
    avdCreator: (Path) -> Path = { FakeEmulator.createPhoneAvd(it) }
  ): Pair<FakeEmulator, EmulatorController> {
    val testAvdFolder = avdCreator(emulatorRule.avdRoot)
    val fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    return fakeEmulator to emulatorController
  }

  @Test
  fun testInitialStateFromDefaults() {
    val (_, emulatorController) = createAndStartEmulator()
    val dialog = DevicePoseDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val controlsPanel = ui.getComponent<JComponent> { it.name == "controlsPanel" }
    val device3DWidget = ui.getComponent<JComponent> { it.name == "device3DWidget" }
    val rotateRadio = ui.getComponent<JBRadioButton> { it.name == "rotateRadioButton" }
    val moveRadio = ui.getComponent<JBRadioButton> { it.name == "moveRadioButton" }
    val foldRadio = ui.findComponent<JBRadioButton> { it.name == "foldRadioButton" }

    val zRotSlider = ui.getComponent<JSlider> { it.name == "zRotSlider" }
    val xRotSlider = ui.getComponent<JSlider> { it.name == "xRotSlider" }
    val yRotSlider = ui.getComponent<JSlider> { it.name == "yRotSlider" }
    val zRotField = ui.getComponent<JLabel> { it.name == "zRotField" }
    val xRotField = ui.getComponent<JLabel> { it.name == "xRotField" }
    val yRotField = ui.getComponent<JLabel> { it.name == "yRotField" }

    val xMoveSlider = ui.getComponent<JSlider> { it.name == "xMoveSlider" }
    val yMoveSlider = ui.getComponent<JSlider> { it.name == "yMoveSlider" }
    val zMoveSlider = ui.getComponent<JSlider> { it.name == "zMoveSlider" }

    fun getSensorValues(prefix: String): List<String> =
      listOf("X", "Y", "Z").map { axis -> ui.getComponent<JLabel> { it.name == "${prefix}${axis}Label" }.text }

    assertThat(device3DWidget.isShowing).isTrue()
    assertThat(device3DWidget.height).isLessThan(controlsPanel.height)
    assertThat(device3DWidget.width).isEqualTo(device3DWidget.height)
    assertThat(rotateRadio.isSelected).isTrue()
    assertThat(moveRadio.isSelected).isFalse()
    assertThat(foldRadio).isNull()

    assertThat(xRotSlider.isShowing).isTrue()
    assertThat(yRotSlider.isShowing).isTrue()
    assertThat(zRotSlider.isShowing).isTrue()
    assertThat(xRotSlider.y).isLessThan(yRotSlider.y)
    assertThat(yRotSlider.y).isLessThan(zRotSlider.y)
    assertThat(ui.getComponent<JLabel> { it.labelFor === xRotSlider }.text).isEqualTo("<html>\u03B8<sub>x</sub>:</html>")
    assertThat(ui.getComponent<JLabel> { it.labelFor === yRotSlider }.text).isEqualTo("<html>\u03B8<sub>y</sub>:</html>")
    assertThat(ui.getComponent<JLabel> { it.labelFor === zRotSlider }.text).isEqualTo("<html>\u03B8<sub>z</sub>:</html>")
    assertThat(xMoveSlider.isShowing).isTrue()
    assertThat(yMoveSlider.isShowing).isTrue()
    assertThat(zMoveSlider.isShowing).isTrue()
    val initialMoveSliderWidth = xMoveSlider.width
    xMoveSlider.value = -10
    ui.layoutAndDispatchEvents()
    assertThat(xMoveSlider.width).isEqualTo(initialMoveSliderWidth)
    xMoveSlider.value = 0
    ui.layoutAndDispatchEvents()

    val controlsTopLeft = SwingUtilities.convertPoint(controlsPanel.parent, controlsPanel.location, rootPane)
    val widgetTopLeft = SwingUtilities.convertPoint(device3DWidget.parent, device3DWidget.location, rootPane)
    val rotateRadioTopLeft = SwingUtilities.convertPoint(rotateRadio.parent, rotateRadio.location, rootPane)
    val rightPanel = rotateRadio.parent
    val rightPanelTopLeft = SwingUtilities.convertPoint(rightPanel.parent, rightPanel.location, rootPane)
    assertThat(rotateRadioTopLeft.y).isAtLeast(widgetTopLeft.y + device3DWidget.height)
    assertThat(rightPanelTopLeft.y).isEqualTo(controlsTopLeft.y)
    assertThat(rightPanel.height).isEqualTo(controlsPanel.height)

    val closeButton = rootPane.defaultButton
    val closeButtonTopLeft = SwingUtilities.convertPoint(closeButton.parent, closeButton.location, rootPane)
    assertThat(closeButtonTopLeft.x + closeButton.width).isEqualTo(widgetTopLeft.x + device3DWidget.width)

    assertThat(zRotSlider.value).isEqualTo(0)
    assertThat(xRotSlider.value).isEqualTo(0)
    assertThat(yRotSlider.value).isEqualTo(0)
    assertThat(zRotField.text).isEqualTo("0.0°")
    assertThat(xRotField.text).isEqualTo("0.0°")
    assertThat(yRotField.text).isEqualTo("0.0°")

    waitForCondition(5.seconds) {
      getSensorValues("accelerometer") == listOf("0.00", "9.81", "0.00")
    }
    assertThat(getSensorValues("gyroscope")).containsExactly("0.00", "0.00", "0.00").inOrder()
    assertThat(getSensorValues("magnetometer")).containsExactly("0.00", "5.90", "-48.40").inOrder()

    // Verify 3D widget paints without throwing
    val image = BufferedImage(device3DWidget.width, device3DWidget.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    device3DWidget.paint(g)
    g.dispose()

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulator() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    fakeEmulator.deviceRotation = listOf(15f, -30f, 90f)
    fakeEmulator.devicePosition = listOf(0.05f, -0.04f, 0.03f)

    val dialog = DevicePoseDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val zRotSlider = ui.getComponent<JSlider> { it.name == "zRotSlider" }
    val xRotSlider = ui.getComponent<JSlider> { it.name == "xRotSlider" }
    val yRotSlider = ui.getComponent<JSlider> { it.name == "yRotSlider" }
    val zRotField = ui.getComponent<JLabel> { it.name == "zRotField" }
    val xRotField = ui.getComponent<JLabel> { it.name == "xRotField" }
    val yRotField = ui.getComponent<JLabel> { it.name == "yRotField" }
    val landscapeButton = ui.getComponent<JButton> { it.name == "landscapeButton" }

    waitForCondition(5.seconds) {
      zRotSlider.value == 900 && xRotSlider.value == 150 && yRotSlider.value == -300 && landscapeButton.isSelected
    }
    assertThat(zRotField.text).isEqualTo("90.0°")
    assertThat(xRotField.text).isEqualTo("15.0°")
    assertThat(yRotField.text).isEqualTo("-30.0°")

    val moveRadio = ui.getComponent<JBRadioButton> { it.name == "moveRadioButton" }
    ui.clickOn(moveRadio)

    val xMoveSlider = ui.getComponent<JSlider> { it.name == "xMoveSlider" }
    val yMoveSlider = ui.getComponent<JSlider> { it.name == "yMoveSlider" }
    val zMoveSlider = ui.getComponent<JSlider> { it.name == "zMoveSlider" }
    val xMoveField = ui.getComponent<JLabel> { it.name == "xMoveField" }
    val yMoveField = ui.getComponent<JLabel> { it.name == "yMoveField" }
    val zMoveField = ui.getComponent<JLabel> { it.name == "zMoveField" }

    assertThat(xMoveSlider.isShowing).isTrue()
    assertThat(xMoveSlider.minimum).isEqualTo(-10)
    assertThat(xMoveSlider.maximum).isEqualTo(10)
    assertThat(yMoveSlider.minimum).isEqualTo(-10)
    assertThat(yMoveSlider.maximum).isEqualTo(10)
    assertThat(zMoveSlider.minimum).isEqualTo(-10)
    assertThat(zMoveSlider.maximum).isEqualTo(10)
    assertThat(xMoveSlider.value).isEqualTo(5)
    assertThat(yMoveSlider.value).isEqualTo(-4)
    assertThat(zMoveSlider.value).isEqualTo(3)
    assertThat(xMoveField.text).isEqualTo("0.05 m")
    assertThat(yMoveField.text).isEqualTo("-0.04 m")
    assertThat(zMoveField.text).isEqualTo("0.03 m")

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testRotationPresetsAndSliders() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    val dialog = DevicePoseDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val portraitButton = ui.getComponent<JButton> { it.name == "portraitButton" }
    val landscapeButton = ui.getComponent<JButton> { it.name == "landscapeButton" }
    val reversePortraitButton = ui.getComponent<JButton> { it.name == "reversePortraitButton" }
    val reverseLandscapeButton = ui.getComponent<JButton> { it.name == "reverseLandscapeButton" }
    assertThat(ui.findComponent<JButton> { it.name == "resetButton" }).isNull()

    assertThat(portraitButton.icon).isEqualTo(StagingIcons.PORTRAIT)
    assertThat(portraitButton.toolTipText).isEqualTo("Portrait")
    assertThat(portraitButton.preferredSize.width).isEqualTo(portraitButton.preferredSize.height)
    assertThat(landscapeButton.icon).isEqualTo(StagingIcons.LANDSCAPE)
    assertThat(landscapeButton.toolTipText).isEqualTo("Landscape")
    assertThat(landscapeButton.preferredSize.width).isEqualTo(landscapeButton.preferredSize.height)
    assertThat(reversePortraitButton.icon).isEqualTo(StagingIcons.REVERSE_PORTRAIT)
    assertThat(reversePortraitButton.toolTipText).isEqualTo("Reverse Portrait")
    assertThat(reversePortraitButton.preferredSize.width).isEqualTo(reversePortraitButton.preferredSize.height)
    assertThat(reverseLandscapeButton.icon).isEqualTo(StagingIcons.REVERSE_LANDSCAPE)
    assertThat(reverseLandscapeButton.toolTipText).isEqualTo("Reverse Landscape")
    assertThat(reverseLandscapeButton.preferredSize.width).isEqualTo(reverseLandscapeButton.preferredSize.height)

    val zRotSlider = ui.getComponent<JSlider> { it.name == "zRotSlider" }
    val xRotSlider = ui.getComponent<JSlider> { it.name == "xRotSlider" }
    val yRotSlider = ui.getComponent<JSlider> { it.name == "yRotSlider" }
    val zRotField = ui.getComponent<JLabel> { it.name == "zRotField" }
    val orientationButtons = listOf(portraitButton, landscapeButton, reversePortraitButton, reverseLandscapeButton)

    fun assertHighlightedOrientationButton(expected: JButton?) {
      for (button in orientationButtons) {
        val selected = button === expected
        assertThat(button.isSelected).isEqualTo(selected)
        assertThat(button.getClientProperty("JButton.backgroundColor"))
          .isEqualTo(if (selected) JBUI.CurrentTheme.ActionButton.pressedBackground() else null)
      }
    }

    fun getSensorValues(prefix: String): List<String> =
      listOf("X", "Y", "Z").map { axis -> ui.getComponent<JLabel> { it.name == "${prefix}${axis}Label" }.text }

    waitForCondition(5.seconds) { portraitButton.isSelected }
    assertHighlightedOrientationButton(portraitButton)

    ui.clickOn(landscapeButton)
    assertThat(landscapeButton.width).isEqualTo(landscapeButton.height)
    assertThat(zRotSlider.value).isEqualTo(900)
    assertThat(zRotField.text).isEqualTo("90.0°")
    waitForCondition(5.seconds) { fakeEmulator.deviceRotation == listOf(0f, 0f, 90f) && landscapeButton.isSelected }
    assertHighlightedOrientationButton(landscapeButton)
    assertThat(getSensorValues("accelerometer")).containsExactly("9.81", "0.00", "0.00").inOrder()

    ui.clickOn(reversePortraitButton)
    assertThat(abs(zRotSlider.value)).isEqualTo(1800)
    waitForCondition(5.seconds) { abs(fakeEmulator.deviceRotation[2]) == 180f && reversePortraitButton.isSelected }
    assertHighlightedOrientationButton(reversePortraitButton)
    assertThat(getSensorValues("accelerometer")).containsExactly("0.00", "-9.81", "0.00").inOrder()

    ui.clickOn(reverseLandscapeButton)
    assertThat(zRotSlider.value).isEqualTo(-900)
    assertThat(zRotField.text).isEqualTo("-90.0°")
    waitForCondition(5.seconds) { fakeEmulator.deviceRotation == listOf(0f, 0f, -90f) && reverseLandscapeButton.isSelected }
    assertHighlightedOrientationButton(reverseLandscapeButton)
    assertThat(getSensorValues("accelerometer")).containsExactly("-9.81", "0.00", "0.00").inOrder()

    ui.clickOn(portraitButton)
    assertThat(zRotSlider.value).isEqualTo(0)
    assertThat(zRotField.text).isEqualTo("0.0°")
    waitForCondition(5.seconds) { fakeEmulator.deviceRotation == listOf(0f, 0f, 0f) && portraitButton.isSelected }
    assertHighlightedOrientationButton(portraitButton)
    assertThat(getSensorValues("accelerometer")).containsExactly("0.00", "9.81", "0.00").inOrder()

    // Adjust sliders directly
    xRotSlider.value = 450
    yRotSlider.value = -200
    zRotSlider.value = 300
    waitForCondition(5.seconds) {
      abs(fakeEmulator.deviceRotation[0] - 45f) < 0.2f &&
        abs(fakeEmulator.deviceRotation[1] - (-20f)) < 0.2f &&
        abs(fakeEmulator.deviceRotation[2] - 30f) < 0.2f &&
        !portraitButton.isSelected
    }
    assertHighlightedOrientationButton(null)

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testFoldableDeviceControls() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator { FakeEmulator.createFoldableAvd(it) }
    val dialog = DevicePoseDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val rotateRadio = ui.getComponent<JBRadioButton> { it.name == "rotateRadioButton" }
    val foldRadio = ui.getComponent<JBRadioButton> { it.name == "foldRadioButton" }
    val moveRadio = ui.getComponent<JBRadioButton> { it.name == "moveRadioButton" }
    assertThat(foldRadio.text).isEqualTo("Fold")
    assertThat(foldRadio.x).isGreaterThan(rotateRadio.x)
    assertThat(moveRadio.x).isGreaterThan(foldRadio.x)

    val reverseLandscapeButton = ui.getComponent<JButton> { it.name == "reverseLandscapeButton" }
    val closedButton = ui.getComponent<JButton> { it.name == "postureButton_POSTURE_CLOSED" }
    val halfOpenButton = ui.getComponent<JButton> { it.name == "postureButton_POSTURE_HALF_OPENED" }
    val openButton = ui.getComponent<JButton> { it.name == "postureButton_POSTURE_OPENED" }

    assertThat(closedButton.isShowing).isTrue()
    assertThat(halfOpenButton.isShowing).isTrue()
    assertThat(openButton.isShowing).isTrue()
    assertThat(closedButton.x).isGreaterThan(reverseLandscapeButton.x)
    assertThat(halfOpenButton.x).isGreaterThan(closedButton.x)
    assertThat(openButton.x).isGreaterThan(halfOpenButton.x)
    assertThat(openButton.isSelected).isTrue()
    assertThat(halfOpenButton.isSelected).isFalse()
    assertThat(closedButton.isSelected).isFalse()

    val accelXLabel = ui.getComponent<JLabel> { it.name == "accelerometerXLabel" }
    val initialClosedButtonY = SwingUtilities.convertPoint(closedButton.parent, closedButton.location, rootPane).y
    val initialSensorLabelY = SwingUtilities.convertPoint(accelXLabel.parent, accelXLabel.location, rootPane).y

    ui.clickOn(foldRadio)
    ui.layoutAndDispatchEvents()
    assertThat(SwingUtilities.convertPoint(closedButton.parent, closedButton.location, rootPane).y).isEqualTo(initialClosedButtonY)
    assertThat(SwingUtilities.convertPoint(accelXLabel.parent, accelXLabel.location, rootPane).y).isEqualTo(initialSensorLabelY)

    val foldSlider = ui.getComponent<JSlider> { it.name == "foldSlider" }
    val foldField = ui.getComponent<JLabel> { it.name == "foldField" }

    assertThat(foldSlider.isShowing).isTrue()
    assertThat(foldSlider.value).isEqualTo(1800)
    assertThat(foldField.text).isEqualTo("180.0°")

    // Move hinge angle slider to 90 degrees -> Half-Open posture
    foldSlider.value = 900
    assertThat(foldField.text).isEqualTo("90.0°")
    waitForCondition(5.seconds) {
      fakeEmulator.devicePosture == PostureValue.POSTURE_HALF_OPENED &&
        abs(fakeEmulator.hingeAngles[0] - 90f) < 0.1f &&
        halfOpenButton.isSelected
    }
    assertThat(openButton.isSelected).isFalse()

    // Click Closed posture button
    ui.clickOn(closedButton)
    assertThat(closedButton.isSelected).isTrue()
    assertThat(halfOpenButton.isSelected).isFalse()
    assertThat(foldSlider.value).isEqualTo(0)
    assertThat(foldField.text).isEqualTo("0.0°")
    waitForCondition(5.seconds) {
      fakeEmulator.devicePosture == PostureValue.POSTURE_CLOSED && abs(fakeEmulator.hingeAngles[0] - 0f) < 0.1f
    }

    // External posture change updates dialog
    fakeEmulator.setPosture(PostureValue.POSTURE_OPENED)
    waitForCondition(5.seconds) {
      foldSlider.value == 1800 && foldField.text == "180.0°" && openButton.isSelected
    }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testRollableDeviceControls() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator { FakeEmulator.createRollableAvd(it) }
    val dialog = DevicePoseDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val foldRadio = ui.getComponent<JBRadioButton> { it.name == "foldRadioButton" }
    assertThat(foldRadio.text).isEqualTo("Roll")

    ui.clickOn(foldRadio)
    val foldSlider = ui.getComponent<JSlider> { it.name == "foldSlider" }
    val foldField = ui.getComponent<JLabel> { it.name == "foldField" }

    assertThat(foldSlider.isShowing).isTrue()
    assertThat(foldSlider.value).isEqualTo(1000)
    assertThat(foldField.text).isEqualTo("100.0%")

    foldSlider.value = 800
    assertThat(foldField.text).isEqualTo("80.0%")
    waitForCondition(5.seconds) {
      fakeEmulator.devicePosture == PostureValue.POSTURE_HALF_OPENED && abs(fakeEmulator.rollablePercentages[0] - 80f) < 0.1f
    }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testAutomotiveDeviceMode() {
    val (_, phoneController) = createAndStartEmulator { FakeEmulator.createPhoneAvd(it) }
    val phoneDialog = DevicePoseDialog(phoneController).show()
    val phoneSize = phoneDialog.size
    phoneDialog.close(0)

    val (fakeEmulator, emulatorController) = createAndStartEmulator { FakeEmulator.createAutomotiveAvd(it) }
    val dialog = DevicePoseDialog(emulatorController).show()
    assertThat(dialog.size.width).isLessThan(phoneSize.width)
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    assertThat(ui.findComponent<JComponent> { it.name == "device3DWidget" }).isNull()
    assertThat(ui.findComponent<JBRadioButton> { it.name == "rotateRadioButton" }).isNull()
    assertThat(ui.findComponent<JButton> { it.name == "portraitButton" }).isNull()

    val xMoveSlider = ui.getComponent<JSlider> { it.name == "xMoveSlider" }
    val yMoveSlider = ui.getComponent<JSlider> { it.name == "yMoveSlider" }
    val zMoveSlider = ui.getComponent<JSlider> { it.name == "zMoveSlider" }
    assertThat(xMoveSlider.isShowing).isTrue()
    assertThat(yMoveSlider.isShowing).isTrue()
    assertThat(zMoveSlider.isShowing).isTrue()
    val initialMoveSliderWidth = xMoveSlider.width
    xMoveSlider.value = -10
    ui.layoutAndDispatchEvents()
    assertThat(xMoveSlider.width).isEqualTo(initialMoveSliderWidth)
    xMoveSlider.value = 0
    ui.layoutAndDispatchEvents()

    xMoveSlider.value = 8
    waitForCondition(5.seconds) {
      abs(fakeEmulator.devicePosition[0] - 0.08f) < 0.001f
    }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testScreenshotFeedLifecycle() {
    val (fakeEmulator, emulatorController) = createAndStartEmulator()
    val dialog = DevicePoseDialog(emulatorController).show()
    val streamScreenshotCall =
      fakeEmulator.getNextGrpcCall(2.seconds) {
        it.methodName == "android.emulation.control.EmulatorController/streamScreenshot"
      }
    assertThat(streamScreenshotCall.completion.isCancelled).isFalse()

    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)
    val device3DWidget = ui.getComponent<Device3DWidget> { it.name == "device3DWidget" }
    waitForCondition(5.seconds) { device3DWidget.screenImage != null }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
    streamScreenshotCall.waitForCancellation(2.seconds)
  }
}
