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
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.streaming.emulator.dialogs.Device3DWidget.OperationMode
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Tests for [Device3DWidget]. */
@RunsInEdt
class Device3DWidgetTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      EdtRule(),
    )

  @Test
  fun testMoveModeAnd3DWidgetInteraction() {
    val (_, emulatorController) = createAndStartEmulator()
    val widget = createWidget(emulatorController)
    val ui = FakeUi(widget)
    val centerX = widget.width / 2
    val centerY = widget.height / 2

    // Drag in Rotate mode rotates the device
    ui.mouse.drag(centerX, centerY, 30, 20)
    assertThat(widget.rotationDegrees).isNotEqualTo(Vec3.ZERO)

    // Switch to Move mode
    widget.operationMode = OperationMode.MOVE

    // Drag in Move mode changes X and Y position
    ui.mouse.drag(centerX, centerY, 40, -30)
    assertThat(widget.positionMeters.x).isGreaterThan(0f)
    assertThat(widget.positionMeters.y).isGreaterThan(0f)

    // Mouse wheel changes Z position
    ui.mouse.wheel(centerX, centerY, -5)
    assertThat(widget.positionMeters.z).isGreaterThan(0f)

    // Switch back to Rotate mode
    widget.operationMode = OperationMode.ROTATE
    assertThat(widget.operationMode).isEqualTo(OperationMode.ROTATE)
  }

  @Test
  fun testSkinnedDeviceOutlineExtrusionAndFaceTexture() {
    val (_, emulatorController) = createAndStartEmulator()
    val widget = createWidget(emulatorController)

    val (halfW, halfH) = widget.computeModelHalfExtents()
    val outlinePath = widget.buildOutlinePath()
    assertThat(outlinePath.contains(0.0, 0.0)).isTrue()
    // Rounded corners of the skin mean the sharp bounding-box corners lie outside the path.
    assertThat(outlinePath.contains((-halfW * 0.999f).toDouble(), (-halfH * 0.999f).toDouble())).isFalse()

    val faces = widget.getDeviceFaces()
    assertThat(widget.getDeviceFaces()).isSameAs(faces)
    val frontFaces = faces.filter { it.isFrontFace }
    assertThat(frontFaces).hasSize(1)
    // Extruded model has 1 front face, 1 back face, and > 8 perimeter side faces from the rounded contour.
    assertThat(faces.size).isGreaterThan(10)
    assertThat(frontFaces.single().vertices.all { abs(it.z - Device3DWidget.PHONE_HALF_DEPTH) < 1e-5f }).isTrue()

    val faceTexture = widget.getFaceTexture()
    assertThat(faceTexture.width).isGreaterThan(50)
    assertThat(faceTexture.height).isGreaterThan(50)
    // Center of the composite face texture contains the opaque screen image, while the extreme corner is transparent outside the skin.
    val centerAlpha = (faceTexture.getRGB(faceTexture.width / 2, faceTexture.height / 2) ushr 24) and 0xFF
    val cornerAlpha = (faceTexture.getRGB(0, 0) ushr 24) and 0xFF
    assertThat(centerAlpha).isEqualTo(255)
    assertThat(cornerAlpha).isEqualTo(0)

    // Render widget and verify the center pixel is painted with the textured front face rather than background color.
    val rendered = BufferedImage(widget.width, widget.height, BufferedImage.TYPE_INT_ARGB)
    val g = rendered.createGraphics()
    widget.paint(g)
    g.dispose()
    val centerRgb = rendered.getRGB(widget.width / 2, widget.height / 2) and 0xFFFFFF
    val cornerRgb = rendered.getRGB(2, 2) and 0xFFFFFF
    assertThat(centerRgb).isNotEqualTo(cornerRgb)

    // Verify Wear OS device model uses WEAR_HALF_DEPTH.
    val (_, watchController) = createAndStartEmulator { FakeEmulator.createWatchAvd(it) }
    val watchWidget = createWidget(watchController)
    val watchFrontFace = watchWidget.getDeviceFaces().first { it.isFrontFace }
    assertThat(watchFrontFace.vertices.all { abs(it.z - Device3DWidget.WEAR_HALF_DEPTH) < 1e-5f }).isTrue()
  }

  @Test
  fun testFoldableSkinSwitchingAndExtrusion() {
    val (_, emulatorController) = createAndStartEmulator { FakeEmulator.createFoldableAvd(it) }
    val widget = createWidget(emulatorController)

    assertThat(widget.isOuterDisplayActive()).isFalse()
    val (openedHalfW, openedHalfH) = widget.computeModelHalfExtents()
    assertThat(openedHalfW).isGreaterThan(openedHalfH)

    // When unfolded, the 3D model has 2 inner display faces (+Z) and 1 outer display face (-Z on the left leaf),
    // and the rectangular center of gravity is at the origin (0, 0, 0).
    val openedFaces = widget.getDeviceFaces()
    val openCog = widget.computeCenterOfGravity()
    assertThat(abs(openCog.x)).isLessThan(1e-5f)
    assertThat(abs(openCog.y)).isLessThan(1e-5f)
    assertThat(abs(openCog.z)).isLessThan(1e-5f)
    val openedFrontFaces = openedFaces.filter { it.isFrontFace }
    val openedOuterBackFaces = openedFaces.filter { it.isOuterBackFace }
    assertThat(openedFrontFaces).hasSize(2)
    assertThat(openedOuterBackFaces).hasSize(1)
    assertThat(openedFrontFaces.all { abs(it.normal.z - 1f) < 1e-5f }).isTrue()
    assertThat(abs(openedOuterBackFaces.single().normal.z + 1f)).isLessThan(1e-5f)

    val activeInnerTexture = widget.getFaceTexture()
    val inactiveOuterTexture = widget.getOuterFaceTexture()
    val innerCenterRgbWhenOpen = activeInnerTexture.getRGB(activeInnerTexture.width / 2, activeInnerTexture.height / 2) and 0xFFFFFF
    val outerCenterRgbWhenOpen = inactiveOuterTexture.getRGB(inactiveOuterTexture.width / 2, inactiveOuterTexture.height / 2) and 0xFFFFFF

    // Dragging right in Fold mode moves the left leaf right (folds the device, decreasing foldValue).
    val ui = FakeUi(widget)
    widget.operationMode = OperationMode.FOLD
    ui.mouse.drag(widget.width / 2, widget.height / 2, 50, 0)
    assertThat(widget.foldValue).isLessThan(180f)

    // Half-open posture (90 degrees): the left leaf (with the outer display) rotates by 90 degrees
    // while the right leaf remains stationary.
    widget.foldValue = 90f
    widget.currentPosture = PostureValue.POSTURE_HALF_OPENED
    val halfOpenFaces = widget.getDeviceFaces()
    val halfOpenFrontFaces = halfOpenFaces.filter { it.isFrontFace }
    val halfOpenOuterFace = halfOpenFaces.single { it.isOuterBackFace }
    assertThat(halfOpenFrontFaces).hasSize(2)
    val leftFrontFace = halfOpenFrontFaces[0]
    val rightFrontFace = halfOpenFrontFaces[1]
    assertThat(abs(leftFrontFace.normal.x - 1f)).isLessThan(1e-4f)
    assertThat(abs(leftFrontFace.normal.z)).isLessThan(1e-4f)
    assertThat(abs(rightFrontFace.normal.x)).isLessThan(1e-4f)
    assertThat(abs(rightFrontFace.normal.z - 1f)).isLessThan(1e-4f)
    assertThat(abs(halfOpenOuterFace.normal.x + 1f)).isLessThan(1e-4f)
    assertThat(abs(halfOpenOuterFace.normal.z)).isLessThan(1e-4f)
    assertThat(leftFrontFace.vertices.maxOf { it.z }).isGreaterThan(Device3DWidget.PHONE_HALF_DEPTH + 0.05f)
    assertThat(rightFrontFace.vertices.all { abs(it.z - Device3DWidget.PHONE_HALF_DEPTH) < 1e-5f }).isTrue()

    // Below the closed posture threshold (20 degrees), the 3D model keeps its 20-degree hinge pose
    // (does not snap to 0 degrees) while switching the active screen from the inner display to the outer display.
    widget.foldValue = 20f
    widget.currentPosture = PostureValue.POSTURE_CLOSED
    assertThat(widget.isOuterDisplayActive()).isTrue()
    val belowThresholdFaces = widget.getDeviceFaces()
    val belowThresholdFrontFaces = belowThresholdFaces.filter { it.isFrontFace }
    val belowThresholdOuterFace = belowThresholdFaces.single { it.isOuterBackFace }
    assertThat(belowThresholdFrontFaces).hasSize(2)
    // At 20 degrees (160 degree left-leaf fold), left leaf normal.x is sin(160 deg) ~= 0.342 (> 0.3, unlike 0 at 0 degrees),
    // while the right leaf stays stationary with normal = (0, 0, 1).
    assertThat(belowThresholdFrontFaces[0].normal.x).isGreaterThan(0.3f)
    assertThat(belowThresholdFrontFaces[0].normal.z).isLessThan(-0.9f)
    assertThat(abs(belowThresholdFrontFaces[1].normal.x)).isLessThan(1e-4f)
    assertThat(abs(belowThresholdFrontFaces[1].normal.z - 1f)).isLessThan(1e-4f)
    assertThat(belowThresholdOuterFace.normal.x).isLessThan(-0.3f)
    assertThat(belowThresholdOuterFace.normal.z).isGreaterThan(0.9f)

    val inactiveInnerTexture = widget.getFaceTexture()
    val activeOuterTexture = widget.getOuterFaceTexture()
    val innerCenterRgbWhenClosed = inactiveInnerTexture.getRGB(inactiveInnerTexture.width / 2, inactiveInnerTexture.height / 2) and 0xFFFFFF
    val outerCenterRgbWhenClosed = activeOuterTexture.getRGB(activeOuterTexture.width / 2, activeOuterTexture.height / 2) and 0xFFFFFF
    assertThat(innerCenterRgbWhenClosed).isNotEqualTo(innerCenterRgbWhenOpen)
    assertThat(outerCenterRgbWhenClosed).isNotEqualTo(outerCenterRgbWhenOpen)

    // At 0 degrees (fully closed), the left leaf folds 180 degrees over onto the stationary right leaf,
    // with the outer display facing +Z at Z = 3 * PHONE_HALF_DEPTH.
    widget.foldValue = 0f
    val closedFaces = widget.getDeviceFaces()
    val closedFrontFaces = closedFaces.filter { it.isFrontFace }
    val closedOuterFace = closedFaces.single { it.isOuterBackFace }
    assertThat(closedFrontFaces).hasSize(2)
    assertThat(abs(closedFrontFaces[0].normal.z + 1f)).isLessThan(1e-4f)
    assertThat(abs(closedFrontFaces[1].normal.z - 1f)).isLessThan(1e-4f)
    assertThat(abs(closedOuterFace.normal.z - 1f)).isLessThan(1e-4f)
    assertThat(closedOuterFace.vertices.all { abs(it.z - 3f * Device3DWidget.PHONE_HALF_DEPTH) < 1e-4f }).isTrue()

    // Verify both leaves are split from the open skin along the center line of the display opening (hingeX < 0 due to right-side buttons),
    // have matching top/bottom extents and hinge boundary when closed, preserve the right leaf's button protrusion, and have no miter
    // spikes protruding above or below the top/bottom edges when viewed sideways (-89.6 degrees around Y).
    val leftClosedFront = closedFrontFaces[0]
    val rightClosedFront = closedFrontFaces[1]
    val hingeX = leftClosedFront.hingeX
    assertThat(hingeX).isLessThan(0f)
    assertThat(abs(leftClosedFront.vertices.minOf { it.x } - hingeX)).isLessThan(1e-5f)
    assertThat(abs(rightClosedFront.vertices.minOf { it.x } - hingeX)).isLessThan(1e-5f)
    assertThat(abs(leftClosedFront.vertices.maxOf { it.y } - rightClosedFront.vertices.maxOf { it.y })).isLessThan(1e-4f)
    assertThat(abs(leftClosedFront.vertices.minOf { it.y } - rightClosedFront.vertices.minOf { it.y })).isLessThan(1e-4f)
    assertThat(rightClosedFront.vertices.maxOf { it.x }).isGreaterThan(leftClosedFront.vertices.maxOf { it.x })

    val closedCog = widget.computeCenterOfGravity()
    assertThat(closedCog.x).isGreaterThan(hingeX + 0.03f)
    assertThat(closedCog.z).isGreaterThan(0f)
    widget.rotationDegrees = Vec3(0f, -89.6f, 0f)
    // Rotating around Y keeps the center of gravity stationary in world space.
    val rotatedClosedCog = widget.modelToWorld(closedCog, closedCog)
    assertThat(abs(rotatedClosedCog.x - closedCog.x)).isLessThan(1e-6f)
    assertThat(abs(rotatedClosedCog.y - closedCog.y)).isLessThan(1e-6f)
    assertThat(abs(rotatedClosedCog.z - closedCog.z)).isLessThan(1e-6f)
    val sideRendered = BufferedImage(widget.width, widget.height, BufferedImage.TYPE_INT_ARGB)
    val sideGraphics = sideRendered.createGraphics()
    widget.paint(sideGraphics)
    sideGraphics.dispose()
    val bgRgb = sideRendered.getRGB(0, 0) and 0xFFFFFF
    val nonBgRows =
      (0 until widget.height).filter { y -> (0 until widget.width).any { x -> (sideRendered.getRGB(x, y) and 0xFFFFFF) != bgRgb } }
    val topRowCount = (0 until widget.width).count { x -> (sideRendered.getRGB(x, nonBgRows.first()) and 0xFFFFFF) != bgRgb }
    val bottomRowCount = (0 until widget.width).count { x -> (sideRendered.getRGB(x, nonBgRows.last()) and 0xFFFFFF) != bgRgb }
    assertThat(topRowCount).isGreaterThan(30)
    assertThat(bottomRowCount).isGreaterThan(30)
  }

  @Test
  fun testSkinlessDeviceGenericRoundedFrame() {
    val (_, emulatorController) = createAndStartEmulator { FakeEmulator.createRollableAvd(it) }
    val widget = createWidget(emulatorController)

    val (halfW, halfH) = widget.computeModelHalfExtents()
    val outlinePath = widget.buildOutlinePath()
    assertThat(outlinePath.contains(0.0, 0.0)).isTrue()
    assertThat(outlinePath.contains((-halfW * 0.999f).toDouble(), (-halfH * 0.999f).toDouble())).isFalse()

    val faces = widget.getDeviceFaces()
    assertThat(faces.filter { it.isFrontFace }).hasSize(1)
    assertThat(faces.size).isGreaterThan(10)
    val unrolledMaxX = faces.first { it.isFrontFace }.vertices.maxOf { it.x }

    widget.foldValue = 50f
    val rolledFaces = widget.getDeviceFaces()
    val rolledMaxX = rolledFaces.first { it.isFrontFace }.vertices.maxOf { it.x }
    assertThat(rolledMaxX).isLessThan(unrolledMaxX)
    widget.foldValue = 100f

    val faceTexture = widget.getFaceTexture()
    val midX = faceTexture.width / 2
    val midY = faceTexture.height / 2
    val centerAlpha = (faceTexture.getRGB(midX, midY) ushr 24) and 0xFF
    val cornerAlpha = (faceTexture.getRGB(0, 0) ushr 24) and 0xFF
    assertThat(centerAlpha).isEqualTo(255)
    assertThat(cornerAlpha).isEqualTo(0)

    val frameRgb = faceTexture.getRGB(1, midY) and 0xFFFFFF
    val leftThickness = (0 until midX).first { (faceTexture.getRGB(it, midY) and 0xFFFFFF) != frameRgb }
    val rightThickness = (0 until midX).first { (faceTexture.getRGB(faceTexture.width - 1 - it, midY) and 0xFFFFFF) != frameRgb }
    val topThickness = (0 until midY).first { (faceTexture.getRGB(midX, it) and 0xFFFFFF) != frameRgb }
    val bottomThickness = (0 until midY).first { (faceTexture.getRGB(midX, faceTexture.height - 1 - it) and 0xFFFFFF) != frameRgb }
    assertThat(leftThickness).isGreaterThan(2)
    assertThat(rightThickness).isEqualTo(leftThickness)
    assertThat(topThickness).isEqualTo(leftThickness)
    assertThat(bottomThickness).isEqualTo(leftThickness)

    val diagOuter = (0 until midX).first { ((faceTexture.getRGB(it, it) ushr 24) and 0xFF) >= 128 }
    val diagInner = (diagOuter until midX).first { (faceTexture.getRGB(it, it) and 0xFFFFFF) != frameRgb }
    val cornerThickness = (diagInner - diagOuter) * sqrt(2.0)
    assertThat(abs(cornerThickness - leftThickness)).isLessThan(2.0)
  }

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

  private fun createWidget(emulatorController: EmulatorController): Device3DWidget {
    lateinit var widget: Device3DWidget
    widget =
      Device3DWidget(
          emulator = emulatorController,
          onRotationChanged = { widget.rotationDegrees = it },
          onPositionChanged = { widget.positionMeters = it },
          onFoldValueChanged = { widget.foldValue = it },
        )
        .apply {
          setSize(300, 300)
          screenImage =
            BufferedImage(180, 360, BufferedImage.TYPE_INT_ARGB).apply {
              val g = createGraphics()
              g.color = Color(0x30, 0x80, 0xE0)
              g.fillRect(0, 0, width, height)
              g.dispose()
            }
        }
    return widget
  }
}
