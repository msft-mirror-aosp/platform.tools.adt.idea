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

import com.android.emulator.control.Rotation.SkinRotation
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.DataManagerRule
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.streaming.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.EmulatorView
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.ui.FakeToolWindow
import com.android.tools.idea.testing.ui.createFakeToolWindow
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.content.ContentManager
import icons.StudioIcons
import java.awt.Component
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.JViewport
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Tests for [DetachedToolWindowReshaper]. */
@RunsInEdt
class DetachedToolWindowReshaperTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      DataManagerRule(projectRule::project),
      emulatorRule,
      EdtRule(),
    )

  private val windowFactory: StreamingToolWindowFactory by lazy { StreamingToolWindowFactory() }
  private val toolWindow: FakeToolWindow by lazy {
    createFakeToolWindow(project, testRootDisposable, RUNNING_DEVICES_TOOL_WINDOW_ID, StudioIcons.Shell.ToolWindows.EMULATOR, windowFactory)
  }

  private val contentManager: ContentManager
    get() = toolWindow.contentManager

  private val project
    get() = projectRule.project

  private val testRootDisposable
    get() = projectRule.disposable

  @After
  fun tearDown() {
    Disposer.dispose(toolWindow.disposable)
    dispatchAllEventsInIdeEventQueue() // Finish asynchronous processing triggered by hiding the tool window.
  }

  @Test
  fun testResizeDetachedWindowOnRotationAndDimensionChange() {
    val (phone, phoneView, ui) = startPhone(Dimension(500, 500))

    // 1. Detached (WINDOWED) with empty space: rotating should NOT resize the window and should reset zoom.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    phoneView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.isPreferredSizeSet).isTrue()

    phone.displayRotation = SkinRotation.LANDSCAPE
    phoneView.displayOrientationQuadrants = 1
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 1
    }
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))
    assertThat(phoneView.isPreferredSizeSet).isFalse()

    // Rotate back to portrait and remove empty space.
    phone.displayRotation = SkinRotation.PORTRAIT
    phoneView.displayOrientationQuadrants = 0
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 0
    }
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertMatchesAspectRatio(phoneView)
    val targetScaleSubOne = roundDownToNaturalNumberOrNearestSmallFraction(phoneView.scale)
    assertThat(targetScaleSubOne).isLessThan(1.0)

    // 2. Detached (WINDOWED) with no empty space and scale < 1.0: rotating preserves zoom scale and resizes viewport to match aspect ratio.
    phone.displayRotation = SkinRotation.LANDSCAPE
    phoneView.displayOrientationQuadrants = 1
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 1
    }
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(phoneView.scale)).isEqualTo(targetScaleSubOne)
    assertMatchesAspectRatio(phoneView)
    val landscapeViewSize = Dimension(phoneView.size)

    // Rotate back to portrait: restores portrait viewport dimensions and preserves zoom scale.
    phone.displayRotation = SkinRotation.PORTRAIT
    phoneView.displayOrientationQuadrants = 0
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 0
    }
    assertThat(phoneView.size).isEqualTo(Dimension(landscapeViewSize.height, landscapeViewSize.width))
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(phoneView.scale)).isEqualTo(targetScaleSubOne)
    assertMatchesAspectRatio(phoneView)

    // 3. Docked mode even with no empty space: rotating should NOT resize the tool window.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    val dockedSizeBefore = Dimension(toolWindow.decorator.size)
    phone.displayRotation = SkinRotation.LANDSCAPE
    phoneView.displayOrientationQuadrants = 1
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 1
    }
    assertThat(toolWindow.decorator.size).isEqualTo(dockedSizeBefore)

    // 4. Detached (FLOATING) with scale > 1.0 (fractional from ZoomType.FIT and integer 2.0 from ZoomType.IN).
    toolWindow.setType(ToolWindowType.FLOATING, null)
    phone.displayRotation = SkinRotation.PORTRAIT
    phoneView.displayOrientationQuadrants = 0
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 0
    }
    val toolbarHeight = toolWindow.decorator.height - phoneView.height
    val naturalSize = phoneView.naturalContentSize
    toolWindow.decorator.size = Dimension(naturalSize.width * 2, (naturalSize.height * 1.5).roundToInt() + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    phoneView.zoom(ZoomType.FIT)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    val fractionalScale = phoneView.scale
    assertThat(fractionalScale).isGreaterThan(1.0)
    val fractionalPortraitSize = Dimension(phoneView.size)

    phone.displayRotation = SkinRotation.LANDSCAPE
    phoneView.displayOrientationQuadrants = 1
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 1
    }
    assertThat(phoneView.size).isEqualTo(Dimension(fractionalPortraitSize.height, fractionalPortraitSize.width))
    assertThat(phoneView.scale).isWithin(1e-6).of(fractionalScale)

    // Integer scale = 2.0:
    val landscapeNaturalSize = phoneView.naturalContentSize
    toolWindow.decorator.size = Dimension(landscapeNaturalSize.width * 2 + 100, landscapeNaturalSize.height * 2 + 100 + toolbarHeight)
    phoneView.resetZoom()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.scale).isEqualTo(2.0)
    doubleClickInView(ui, phoneView, 2, 2)
    val scrollPane = (phoneView.parent as JViewport).parent as JScrollPane
    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()
    val scale2LandscapeSize = Dimension(phoneView.size)
    assertThat(scale2LandscapeSize).isEqualTo(Dimension(landscapeNaturalSize.width * 2, landscapeNaturalSize.height * 2))

    phone.displayRotation = SkinRotation.PORTRAIT
    phoneView.displayOrientationQuadrants = 0
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, phoneView)
      phoneView.displayOrientationQuadrants == 0
    }
    assertThat(phoneView.size).isEqualTo(Dimension(scale2LandscapeSize.height, scale2LandscapeSize.width))
    assertThat(phoneView.scale).isEqualTo(2.0)
    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()

    // 5. Folding/unfolding a foldable device (non-rotation display dimension change) in detached WINDOWED mode with no empty space.
    val foldable = emulatorRule.newEmulator(FakeEmulator.createFoldableAvd(emulatorRule.avdRoot))
    foldable.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 2 }
    val foldableController = RunningEmulatorCatalog.getInstance().emulators.first { it.emulatorId.avdName.contains("Fold") }
    waitForCondition(5.seconds) { foldableController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    val foldableContent = contentManager.contents.first { it.displayName?.contains("Fold") == true }
    contentManager.setSelectedContent(foldableContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setType(ToolWindowType.WINDOWED, null)
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    val foldableView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == foldable.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, foldableView) > 0u }
    doubleClickInView(ui, foldableView, 2, 2)
    assertMatchesAspectRatio(foldableView)
    val unfoldedTargetScale = roundDownToNaturalNumberOrNearestSmallFraction(foldableView.scale)

    foldable.setPosture(com.android.emulator.control.Posture.PostureValue.POSTURE_CLOSED)
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, foldableView)
      foldableView.deviceDisplaySize == Dimension(1080, 2092)
    }
    assertMatchesAspectRatio(foldableView)
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(foldableView.scale)).isEqualTo(unfoldedTargetScale)
  }

  private fun renderAndGetFrameNumber(fakeUi: FakeUi, displayView: AbstractDisplayView): UInt {
    fakeUi.render() // The frame number may get updated as a result of rendering.
    return displayView.frameNumber
  }

  private fun createFakeUi(root: Component): FakeUi = FakeUi(root, parentDisposable = testRootDisposable)

  private fun startPhone(initialSize: Dimension = Dimension(500, 500)): PhoneTestContext {
    val tempFolder = emulatorRule.avdRoot
    val phone = emulatorRule.newEmulator(FakeEmulator.createPhoneAvd(tempFolder))
    toolWindow.show()
    phone.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.isNotEmpty() }
    waitForCondition(5.seconds) { RunningEmulatorCatalog.getInstance().emulators.isNotEmpty() }
    val phoneController = RunningEmulatorCatalog.getInstance().emulators.first()
    waitForCondition(5.seconds) { phoneController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val phoneContent = contentManager.contents.find { it.displayName?.startsWith("Pixel") == true }!!
    contentManager.setSelectedContent(phoneContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.decorator.size = initialSize
    val ui = createFakeUi(toolWindow.decorator)
    val phoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, phoneView) > 0u }
    return PhoneTestContext(phone, phoneView, ui)
  }

  private fun doubleClickInView(ui: FakeUi, view: AbstractDisplayView, x: Int, y: Int) {
    val pos = ui.getPosition(view)
    ui.mouse.doubleClick(pos.x + x, pos.y + y)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, view)
  }

  private fun assertMatchesAspectRatio(view: AbstractDisplayView) {
    val expectedWidth = (view.height.toDouble() * view.naturalContentSize.width / view.naturalContentSize.height).roundToInt()
    assertThat(view.width).isIn((expectedWidth - 1)..(expectedWidth + 1))
  }

  private data class PhoneTestContext(val phone: FakeEmulator, val phoneView: EmulatorView, val ui: FakeUi)
}
