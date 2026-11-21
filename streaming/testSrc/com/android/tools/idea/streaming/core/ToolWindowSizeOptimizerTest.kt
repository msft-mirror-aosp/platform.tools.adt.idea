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

import com.android.emulator.control.DisplayConfiguration
import com.android.emulator.control.Posture.PostureValue
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
import com.intellij.ide.DataManager
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import icons.StudioIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.whenever

/** Tests for [ToolWindowSizeOptimizer]. */
@RunsInEdt
class ToolWindowSizeOptimizerTest {

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

  @Before
  fun setUp() {
    waitUntilIndexesAreReady(project)
  }

  @After
  fun tearDown() {
    Disposer.dispose(toolWindow.disposable)
    dispatchAllEventsInIdeEventQueue() // Finish asynchronous processing triggered by hiding the tool window.
    waitUntilIndexesAreReady(project)
  }

  @Test
  fun testDockedHorizontal() {
    val (_, phoneView, ui) = startPhone()

    // Double-clicking inside the display image should not resize the tool window.
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    val insideEvent =
      MouseEvent(
        phoneView,
        MouseEvent.MOUSE_CLICKED,
        System.currentTimeMillis(),
        0,
        phoneView.width / 2,
        phoneView.height / 2,
        2,
        false,
        MouseEvent.BUTTON1,
      )
    phoneView.dispatchEvent(insideEvent)
    assertThat(insideEvent.isConsumed).isTrue()
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))

    // Boundary click on the edge of the display content rectangle should not trigger resizing.
    val contentRect = phoneView.projectionRectangle!!
    val contentX = (contentRect.x / phoneView.screenScalingFactor).roundToInt()
    val contentY = (contentRect.y / phoneView.screenScalingFactor).roundToInt()
    doubleClickInView(ui, phoneView, contentX, contentY)
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))

    // Double-clicking in empty space while in Pan mode must be ignored and not resize the tool window.
    phoneView.preferredSize = Dimension(200, 800)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.isPannable).isTrue()
    phoneView.isPanning = true
    assertThat(phoneView.isPanning).isTrue()
    val panEmptySpaceEvent =
      MouseEvent(phoneView, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, phoneView.height / 2, 2, false, MouseEvent.BUTTON1)
    phoneView.dispatchEvent(panEmptySpaceEvent)
    assertThat(panEmptySpaceEvent.isConsumed).isFalse()
    val panViewPos = ui.getPosition(phoneView)
    assertThat(phoneView.panOverlay).isNotNull()
    assertThat(SwingUtilities.getDeepestComponentAt(toolWindow.decorator, panViewPos.x + 2, panViewPos.y + phoneView.height / 2))
      .isSameAs(phoneView.panOverlay)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))
    phoneView.resetZoom()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.isPanning).isFalse()

    // Docked RIGHT: shrink width to remove horizontal empty space.
    val emptySpaceEvent =
      MouseEvent(phoneView, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, phoneView.height / 2, 2, false, MouseEvent.BUTTON1)
    phoneView.dispatchEvent(emptySpaceEvent)
    assertThat(emptySpaceEvent.isConsumed).isTrue()
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isEqualTo(500)
    assertMatchesAspectRatio(phoneView)

    // Calling resizeToolWindowToRemoveEmptySpace when no size change occurs leaves toolbarPanel.bounds identical.
    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!
    val toolbarPanel = (devicePanel.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
    val toolbarBoundsBefore = Rectangle(toolbarPanel.bounds)
    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    assertThat(toolbarPanel.bounds).isEqualTo(toolbarBoundsBefore)

    // Docked RIGHT: when shrinking is not possible, expand width to remove vertical empty space.
    toolWindow.decorator.size = Dimension(150, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.width).isGreaterThan(150)
    assertThat(toolWindow.decorator.height).isEqualTo(500)
    assertMatchesAspectRatio(phoneView)

    // Docked RIGHT with width=385 (where quantized fitScale rounds down to 384px < 385px): should still expand width, not shrink by 1px.
    toolWindow.decorator.size = Dimension(385, 925)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.width).isGreaterThan(385)
    assertThat(toolWindow.decorator.height).isEqualTo(925)
    assertMatchesAspectRatio(phoneView)

    // Docked LEFT: shrink and expand width.
    toolWindow.setAnchor(ToolWindowAnchor.LEFT, null)
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertMatchesAspectRatio(phoneView)

    // Large negative delta should clamp to minimum positive size.
    toolWindow.stretchWidth(-1000)
    assertThat(toolWindow.decorator.width).isAtLeast(1)
  }

  @Test
  fun testDockedVertical() {
    val (_, phoneView, ui) = startPhone()

    // 5. Docked BOTTOM: shrink height when vertical empty space exists, and expand height when horizontal empty space exists.
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(150, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertThat(toolWindow.decorator.width).isEqualTo(150)
    assertMatchesAspectRatio(phoneView)

    toolWindow.decorator.size = Dimension(300, 300)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.height).isGreaterThan(300)
    assertThat(toolWindow.decorator.width).isEqualTo(300)
    assertMatchesAspectRatio(phoneView)

    // Docked BOTTOM with availableHeight=772 (where quantized fitScale rounds down to 769px < 772px): should still expand height.
    val toolbarHeight = toolWindow.decorator.height - phoneView.height
    toolWindow.decorator.size = Dimension(500, 772 + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.height).isGreaterThan(772 + toolbarHeight)
    assertThat(toolWindow.decorator.width).isEqualTo(500)
    assertMatchesAspectRatio(phoneView)

    // 6. Docked TOP: shrink height.
    toolWindow.setAnchor(ToolWindowAnchor.TOP, null)
    toolWindow.decorator.size = Dimension(150, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertMatchesAspectRatio(phoneView)

    // Large negative delta should clamp to minimum positive size.
    toolWindow.stretchHeight(-1000)
    assertThat(toolWindow.decorator.height).isAtLeast(1)
  }

  @Test
  fun testUndocked() {
    val (_, phoneView, ui) = startPhone()

    // 7. Undocked mode (WINDOWED and FLOATING): shrink width/height.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isAtLeast(500)
    assertMatchesAspectRatio(phoneView)

    toolWindow.setType(ToolWindowType.FLOATING, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(150, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertThat(toolWindow.decorator.width).isEqualTo(150)
    assertMatchesAspectRatio(phoneView)
  }

  @Test
  fun testSecondaryDisplay() {
    val (phone, phoneView, ui) = startPhone(Dimension(900, 500))

    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    runBlocking {
      phone.changeSecondaryDisplays(listOf(DisplayConfiguration.newBuilder().setDisplay(1).setWidth(600).setHeight(800).build()))
    }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 2 }
    ui.layoutAndDispatchEvents()
    val secondaryView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber && it.displayId == 1 }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, secondaryView) > 0u }
    doubleClickInView(ui, secondaryView, 2, secondaryView.height / 2)
    assertMatchesAspectRatio(secondaryView)

    // Shrinking firstComponent below SplitPanel's 0.1 minimumProportion must not shrink secondComponent.
    val splitPanel = ui.getComponent<SplitPanel>()
    splitPanel.splitType = SplitType.HORIZONTAL
    splitPanel.proportion = 0.15
    toolWindow.decorator.size = Dimension(1500, 180)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    renderAndGetFrameNumber(ui, secondaryView)
    val secondaryWidthBefore = secondaryView.width
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(1500)
    assertThat(splitPanel.proportion).isWithin(1e-6).of(0.1)
    assertThat(secondaryView.width).isEqualTo(secondaryWidthBefore)

    // Shrinking secondComponent above SplitPanel's 0.9 maximumProportion must not shrink firstComponent.
    splitPanel.proportion = 0.85
    toolWindow.decorator.size = Dimension(1500, 180)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    renderAndGetFrameNumber(ui, secondaryView)
    val phoneWidthBefore = phoneView.width
    doubleClickInView(ui, secondaryView, 2, secondaryView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(1500)
    assertThat(splitPanel.proportion).isWithin(1e-6).of(0.9)
    assertThat(phoneView.width).isEqualTo(phoneWidthBefore)
  }

  @Test
  fun testDisplayScalingFactor() {
    val tempFolder = emulatorRule.avdRoot
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    toolWindow.show()
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 1 }
    val watchContent = contentManager.contents.first()
    contentManager.setSelectedContent(watchContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.decorator.size = Dimension(500, 550)
    val ui = createFakeUi(toolWindow.decorator)
    val watchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, watchView) > 0u }
    assertThat(watchView.naturalContentSize).isEqualTo(Dimension(320, 320))

    // Shrink width to 1x (320) rather than fractional > 1 (~520).
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.width).isEqualTo(320)

    // Expand width from 200 to 1x (320) rather than fractional > 1 (~520) when height is 550.
    toolWindow.decorator.size = Dimension(200, 550)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(320)

    // Expand width from 200 to 2x (640) when height is 880 (watchView.height >= 640).
    toolWindow.decorator.size = Dimension(200, 880)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(640)

    // Undocked mode (WINDOWED) with scale > 1: shrinks both width and height to integer scale 2x (640x640).
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(800, 900)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, 2, 2)
    assertThat(watchView.size).isEqualTo(Dimension(640, 640))
  }

  @Test
  fun testPreserveZoomLevelGreaterThanOne() {
    val tempFolder = emulatorRule.avdRoot
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    toolWindow.show()
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 1 }
    val watchContent = contentManager.contents.first()
    contentManager.setSelectedContent(watchContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.decorator.size = Dimension(500, 550)
    val ui = createFakeUi(toolWindow.decorator)
    val watchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, watchView) > 0u }
    assertThat(watchView.naturalContentSize).isEqualTo(Dimension(320, 320))

    // Docked mode with fractional scale > 1 (from ZoomType.FIT): preserves fractional scale > 1 while shrinking width.
    val toolbarHeight = toolWindow.decorator.height - watchView.height
    toolWindow.decorator.size = Dimension(600, 500 + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    watchView.zoom(ZoomType.FIT)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    val fractionalScale = watchView.scale
    assertThat(fractionalScale).isEqualTo(500.0 / 320)
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.scale).isEqualTo(fractionalScale)
    assertThat(watchView.width).isEqualTo(500)

    // Docked mode with explicit ZoomType.IN (scale = 2.0) and vertical scrollbar: shrinks width to fit 2x image + vertical scrollbar.
    toolWindow.decorator.size = Dimension(800, 500 + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    watchView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(2.0)
    val scrollPane = (watchView.parent as JViewport).parent as JScrollPane
    assertThat(scrollPane.verticalScrollBar.isVisible).isTrue()
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.scale).isEqualTo(2.0)
    assertThat(watchView.width).isEqualTo(640)
    assertThat(scrollPane.verticalScrollBar.isVisible).isTrue()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()

    // Windowed mode with preserved ZoomType.IN (scale = 2.0) and vertical scrollbar:
    // transitioning to WINDOWED shrinks width and expands height to 640x640.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(2.0)
    assertThat(watchView.size).isEqualTo(Dimension(640, 640))
    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()

    // Docked mode with scale = 2.0 and extra vertical space (height >= 3x): double-clicking vertical empty space preserves scale = 2.0.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.decorator.size = Dimension(640, 1050)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(2.0)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.scale).isEqualTo(2.0)
    assertThat(watchView.width).isEqualTo(640)

    // Unzoomed fitScale = 1.0 with slack on the adjustable axis and space for 2x on the non-shrinkable axis:
    // expands to 2x (640x640) to eliminate empty space on the non-shrinkable axis rather than shrinking to 1x (320).
    watchView.resetZoom()
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.decorator.size = Dimension(400, 640 + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(watchView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.width).isEqualTo(640)
    assertThat(watchView.scale).isEqualTo(2.0)

    watchView.resetZoom()
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(640, 400 + toolbarHeight)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(watchView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.height).isEqualTo(640 + toolbarHeight)
    assertThat(watchView.scale).isEqualTo(2.0)
  }

  @Test
  fun testSplitLayout() {
    val tempFolder = emulatorRule.avdRoot
    val phone = emulatorRule.newEmulator(FakeEmulator.createPhoneAvd(tempFolder))
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    toolWindow.show()
    phone.start()
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 2 }
    val watchContent = contentManager.contents.find { it.displayName?.startsWith("Android Wear") == true }!!
    contentManager.setSelectedContent(watchContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    FakeToolWindow.split(watchContent, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(1000, 550)
    val ui = createFakeUi(toolWindow.decorator)
    val splitWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, splitWatchView) > 0u }
    doubleClickInView(ui, splitWatchView, 2, splitWatchView.height / 2)
    assertThat(splitWatchView.width).isEqualTo(320)

    // Splitter with a single visible child should not include dividerWidth or overwrite splitter.proportion.
    val splitter = splitWatchView.findAncestor<Splitter>()!!
    splitter.proportion = 0.4f
    val other = if (splitter.firstComponent.isAncestorOf(splitWatchView)) splitter.secondComponent else splitter.firstComponent
    other.isVisible = false
    toolWindow.decorator.size = Dimension(1000, 550)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, splitWatchView)
    doubleClickInView(ui, splitWatchView, 2, splitWatchView.height / 2)
    assertThat(splitWatchView.width).isEqualTo(320)
    assertThat(splitter.proportion).isEqualTo(0.4f)
    other.isVisible = true

    FakeToolWindow.unsplit(watchContent.manager!!, watchContent)
    dispatchAllEventsInIdeEventQueue()
    FakeToolWindow.split(watchContent, SwingConstants.TOP)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(350, 1200)
    ui.layoutAndDispatchEvents()
    val topWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, topWatchView) > 0u }
    doubleClickInView(ui, topWatchView, topWatchView.width / 2, 2)
    assertThat(topWatchView.size).isEqualTo(Dimension(320, 320))

    // Nested vertical splitter with clamped maximumProportion inside a vertically non-resizable tool window
    // adjusts both inner and outer splitters without clipping the target view.
    val outerSplitter = topWatchView.findAncestor<Splitter>()!!
    val topComponent = outerSplitter.firstComponent
    val nestedSplitter =
      object : Splitter(true, 0.8f) {
          override fun getMaximumProportion(): Float = 0.85f
        }
        .apply {
          firstComponent = topComponent
          secondComponent = JPanel().apply { size = Dimension(320, 100) }
        }
    outerSplitter.firstComponent = nestedSplitter
    outerSplitter.proportion = 0.6f
    toolWindow.decorator.size = Dimension(350, 1200)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, topWatchView)
    doubleClickInView(ui, topWatchView, topWatchView.width / 2, 2)
    assertThat(topWatchView.size).isEqualTo(Dimension(320, 320))
    outerSplitter.firstComponent = topComponent

    FakeToolWindow.unsplit(watchContent.manager!!, watchContent)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testInnerFraming() {
    val tempFolder = emulatorRule.avdRoot
    val glasses = emulatorRule.newEmulator(FakeEmulator.createDisplayGlassesAvd(tempFolder))
    toolWindow.show()
    glasses.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contentsRecursively.any { it.displayName?.startsWith("Display Glasses") == true } }
    val glassesController = RunningEmulatorCatalog.getInstance().emulators.find { it.emulatorId.serialNumber == glasses.serialNumber }!!
    waitForCondition(5.seconds) { glassesController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    val glassesContent = contentManager.contentsRecursively.find { it.displayName?.startsWith("Display Glasses") == true }!!
    contentManager.setSelectedContent(glassesContent)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.decorator.size = Dimension(1500, 600)
    val ui = createFakeUi(toolWindow.decorator)
    val glassesView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == glasses.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, glassesView) > 0u }
    assertThat(glassesView.framing).isEqualTo(ZoomablePanel.Framing.INNER)
    doubleClickInView(ui, glassesView, 2, glassesView.height / 2)
    assertThat(glassesView.width).isEqualTo(450)
  }

  @Test
  fun testToolbarWrapping() {
    val (_, phoneView, ui) = startPhone(Dimension(500, 500))
    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!

    // Create a toolbar whose height wraps from 25 to 60 when width <= wrapThreshold,
    // and from 60 to 85 when width <= wrapThreshold2.
    var wrapThreshold = 300
    var wrapThreshold2 = 0
    var lastChildLayoutWidth = 0
    val childToolbar =
      object : JPanel() {
        override fun doLayout() {
          super.doLayout()
          lastChildLayoutWidth = width
        }
      }
    val wrappingToolbar =
      object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
          val h =
            when (width) {
              in 1..wrapThreshold2 -> 85
              in 1..wrapThreshold -> 60
              else -> 25
            }
          return Dimension(width, h)
        }
      }
    wrappingToolbar.add(childToolbar, BorderLayout.CENTER)
    wrappingToolbar.size = Dimension(500, 25)
    devicePanel.add(wrappingToolbar, BorderLayout.NORTH)

    // In WINDOWED mode with toolbar wrapping: window height expands rather than downscaling the display image.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    val initialHeight = toolWindow.decorator.height
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)

    // The width shrinks to fit the phone display image (width <= 300).
    assertThat(toolWindow.decorator.width).isLessThan(500)
    // The height expands to accommodate the wrapped toolbar without downscaling the display image.
    assertThat(toolWindow.decorator.height).isGreaterThan(initialHeight)
    assertThat(lastChildLayoutWidth).isEqualTo(wrappingToolbar.width)

    // In WINDOWED mode when window.minimumSize clamps width above wrapThreshold so the toolbar stays unwrapped (25px),
    // the tentative toolbar-wrap height expansion (+35px) must be rolled back on the window.
    val window = SwingUtilities.getWindowAncestor(toolWindow.decorator)!!
    window.size = Dimension(500, 500)
    toolWindow.decorator.size = Dimension(500, 500)
    wrappingToolbar.size = Dimension(500, 25)
    window.minimumSize = Dimension(wrapThreshold + 50, 100)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isEqualTo(wrapThreshold + 50)
    assertThat(wrappingToolbar.height).isEqualTo(25)
    assertThat(toolWindow.decorator.height).isEqualTo(500)
    window.minimumSize = null

    // In DOCKED mode with a vertical splitter: display image downscales to preserve the split pane boundary.
    val phoneContent = contentManager.contents.first()
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    FakeToolWindow.split(phoneContent, SwingConstants.TOP)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.decorator.size = Dimension(500, 800)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    val splitter = phoneView.findAncestor<Splitter>()!!
    val initialProportion = splitter.proportion
    val initialImageWidth = phoneView.width
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)

    // Display image downscales to preserve the split pane boundary.
    assertThat(phoneView.width).isLessThan(initialImageWidth)
    assertThat(splitter.proportion).isEqualTo(initialProportion)
    assertMatchesAspectRatio(phoneView)

    // In DOCKED mode with a vertical splitter and small vertical slack (0 < availableHeight - imageHeight < toolbarHeightDelta):
    // Display image still downscales to fit effectiveHeight and preserve the split pane boundary.
    val naturalSize = phoneView.naturalContentSize
    wrapThreshold = naturalSize.width
    val topPaneTargetHeight = naturalSize.height + 25 + 10 // 10px of vertical slack (< 35px toolbarHeightDelta)
    toolWindow.decorator.size = Dimension(naturalSize.width + 100, topPaneTargetHeight * 2 + splitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.height).isEqualTo(naturalSize.height + 10)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    val singleWrapWidth = phoneView.width
    assertThat(singleWrapWidth).isLessThan(naturalSize.width)
    assertThat(splitter.proportion).isEqualTo(initialProportion)
    assertMatchesAspectRatio(phoneView)

    // Cascading toolbar wrapping: narrowing to singleWrapWidth triggers a second wrap (to 95px),
    // which further reduces effectiveHeight and narrows phoneView again.
    wrapThreshold2 = singleWrapWidth
    wrappingToolbar.size = Dimension(naturalSize.width + 100, 25)
    toolWindow.decorator.size = Dimension(naturalSize.width + 100, topPaneTargetHeight * 2 + splitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(phoneView.height).isEqualTo(naturalSize.height + 10)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(phoneView.width).isLessThan(singleWrapWidth)
    assertThat(splitter.proportion).isEqualTo(initialProportion)
    assertMatchesAspectRatio(phoneView)
    assertThat(lastChildLayoutWidth).isEqualTo(wrappingToolbar.width)

    // Zoomed view (2x) in a vertical split when widening unwraps the toolbar (toolbarHeightDelta < 0):
    // 1) Freed toolbar height (35px) covers a 20px vertical deficit, eliminating vBarPrefWidth padding and vertical scrollbar.
    wrapThreshold2 = 0
    val zoomedWidth = naturalSize.width * 2
    val zoomedHeight = naturalSize.height * 2
    wrapThreshold = zoomedWidth - 50
    val scrollPane = (phoneView.parent as JViewport).parent as JScrollPane
    splitter.proportion = 0.5f
    val topPaneHeightEliminateVBar = zoomedHeight - 20 + 60
    wrappingToolbar.size = Dimension(naturalSize.width, 60)
    toolWindow.decorator.size = Dimension(naturalSize.width, topPaneHeightEliminateVBar * 2 + splitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    phoneView.zoom(ZoomType.ACTUAL)
    phoneView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(phoneView.scale).isEqualTo(2.0)
    assertThat(scrollPane.verticalScrollBar.isVisible).isTrue()

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.width).isEqualTo(zoomedWidth)
    assertThat(scrollPane.height).isEqualTo(zoomedHeight)
    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()

    // 2) Freed toolbar height (35px) partially reduces a 50px vertical deficit: zoomed view grows into all 35px
    // without shrinking devicePanel's share of the vertical splitter.
    splitter.proportion = 0.5f
    val topPaneHeightPartial = zoomedHeight - 50 + 60
    wrappingToolbar.size = Dimension(naturalSize.width, 60)
    toolWindow.decorator.size = Dimension(naturalSize.width, topPaneHeightPartial * 2 + splitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    phoneView.zoom(ZoomType.ACTUAL)
    phoneView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(phoneView.scale).isEqualTo(2.0)
    val devicePanelHeightBefore = devicePanel.height

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(devicePanel.height).isEqualTo(devicePanelHeightBefore)
    assertThat(scrollPane.height).isEqualTo(zoomedHeight - 15)
    assertThat(toolWindow.decorator.width).isEqualTo(zoomedWidth + scrollPane.verticalScrollBar.width)

    // 3) If stretchWidth is clamped at <= wrapThreshold so the toolbar stays wrapped at 60px instead of unwrapping to 25px,
    // effectiveToolbarHeightDelta must be recomputed against the pre-stretch toolbar height (60px -> delta 0),
    // preserving devicePanel's vertical splitter share and scrollPane height instead of shrinking devicePanel by 15px.
    splitter.proportion = 0.5f
    wrappingToolbar.size = Dimension(naturalSize.width, 60)
    toolWindow.decorator.size = Dimension(naturalSize.width, topPaneHeightEliminateVBar * 2 + splitter.dividerWidth)
    toolWindow.decorator.maximumSize = Dimension(wrapThreshold, topPaneHeightEliminateVBar * 2 + splitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    assertThat(phoneView.scale).isEqualTo(2.0)
    val clampedDevicePanelHeightBefore = devicePanel.height
    val clampedScrollPaneHeightBefore = scrollPane.height

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.width).isEqualTo(wrapThreshold)
    assertThat(wrappingToolbar.height).isEqualTo(60)
    assertThat(devicePanel.height).isEqualTo(clampedDevicePanelHeightBefore)
    assertThat(scrollPane.height).isEqualTo(clampedScrollPaneHeightBefore)
    toolWindow.decorator.maximumSize = null

    // 4) Nested vertical splitters above devicePanel when docked RIGHT width stretch is clamped above wrapThreshold:
    // rolling back a planned toolbar wrap (toolbarHeightDelta > 0 -> effectiveToolbarHeightDelta = 0) must not shrink the
    // inner vertical splitter inside the outer vertical splitter when the inner splitter's height was capped.
    phoneView.resetZoom()
    val midContent = ContentFactory.getInstance().createContent(JPanel(), "Mid", false)
    phoneContent.manager!!.addContent(midContent)
    FakeToolWindow.split(midContent, SwingConstants.BOTTOM)
    dispatchAllEventsInIdeEventQueue()

    wrapThreshold = naturalSize.width + 20
    wrappingToolbar.size = Dimension(naturalSize.width + 100, 25)
    val nestedTotalHeight = (naturalSize.height + 25) * 3 + splitter.dividerWidth * 2
    toolWindow.decorator.size = Dimension(naturalSize.width + 100, nestedTotalHeight)
    val vInnerNested = phoneView.findAncestor<Splitter>()!!
    val vOuterNested = vInnerNested.findAncestor<Splitter>()!!
    vOuterNested.proportion = 2f / 3f
    vInnerNested.proportion = 0.5f
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(naturalSize.width + 100, 100))
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    val vOuterProportionBefore = vOuterNested.proportion
    val vInnerHeightBefore = vInnerNested.height

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.width).isEqualTo(naturalSize.width + 100)
    assertThat(wrappingToolbar.height).isEqualTo(25)
    assertThat(vInnerNested.height).isEqualTo(vInnerHeightBefore)
    assertThat(vOuterNested.proportion).isEqualTo(vOuterProportionBefore)
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(0, 0))

    val midManager = midContent.manager!!
    FakeToolWindow.unsplit(midManager, phoneContent)
    dispatchAllEventsInIdeEventQueue()
    phoneContent.manager!!.removeContent(midContent, true)
    dispatchAllEventsInIdeEventQueue()

    // 5) Docked RIGHT vertical split when a planned toolbar wrap (toolbarHeightDelta > 0) rolls back to unwrapped on a clamped stretch:
    // a) Early break (initialImageHeight <= effectiveHeight): scrollPane shrinks to exact naturalSize.height without +35px inflation.
    wrapThreshold = naturalSize.width + 20
    wrappingToolbar.size = Dimension(naturalSize.width + 100, 25)
    val slackStartTopHeight = naturalSize.height + 200 + 25
    toolWindow.decorator.size = Dimension(naturalSize.width + 100, slackStartTopHeight * 2 + splitter.dividerWidth)
    splitter.proportion = 0.5f
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(naturalSize.width + 100, 100))
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(wrappingToolbar.height).isEqualTo(25)
    assertThat(scrollPane.height).isEqualTo(naturalSize.height)

    // b) Partial clamping against effectiveHeight (zoomed 2x with 20px vertical slack < 35px planned wrap):
    // scrollPane shrinks to exact zoomedHeight rather than inflating back to zoomedHeight + 20.
    wrapThreshold = zoomedWidth + 20
    wrappingToolbar.size = Dimension(zoomedWidth + 100, 25)
    val partialSlackTopHeight = zoomedHeight + 20 + 25
    toolWindow.decorator.size = Dimension(zoomedWidth + 100, partialSlackTopHeight * 2 + splitter.dividerWidth)
    splitter.proportion = 0.5f
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(zoomedWidth + 100, 100))
    ui.layoutAndDispatchEvents()
    phoneView.zoom(ZoomType.ACTUAL)
    phoneView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(phoneView.scale).isEqualTo(2.0)

    ToolWindowSizeOptimizer(phoneView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(wrappingToolbar.height).isEqualTo(25)
    assertThat(scrollPane.height).isEqualTo(zoomedHeight)
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(0, 0))

    FakeToolWindow.unsplit(phoneContent.manager!!, phoneContent)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testZoomedWithScrollbars() {
    val (_, phoneView, ui) = startPhone(Dimension(500, 500))
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)

    // Zoom in so that scrollbars appear.
    phoneView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    val viewport = phoneView.parent as JViewport
    val scrollPane = viewport.parent as JScrollPane
    assertThat(scrollPane.verticalScrollBar.isVisible || scrollPane.horizontalScrollBar.isVisible).isTrue()

    // Double-clicking in empty space should reset zoom, removing scrollbars, and optimize size using full scroll pane dimensions (Fix 5).
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)

    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()
    assertThat(phoneView.isPreferredSizeSet).isFalse()
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isEqualTo(500)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertMatchesAspectRatio(phoneView)
  }

  @Test
  fun testResizeOnTransitionToDetachedSingleDevice() {
    val (_, phoneView, ui) = startPhone(Dimension(500, 500))

    // Single device transitioning from DOCKED to WINDOWED.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isAtLeast(500)
    assertMatchesAspectRatio(phoneView)

    // Switching from WINDOWED to FLOATING (already detached) should not override user manual resizing.
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    toolWindow.setType(ToolWindowType.FLOATING, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))

    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!
    val toolbarPanel = (devicePanel.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
    val singleRowToolbarHeight = toolbarPanel.height

    // Re-docking and transitioning to FLOATING removes vertical empty space.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(150, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    toolWindow.setType(ToolWindowType.FLOATING, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertThat(toolWindow.decorator.width).isEqualTo(150)
    assertThat(toolbarPanel.height).isGreaterThan(singleRowToolbarHeight)
    assertMatchesAspectRatio(phoneView)

    // Re-docking into a wide pane unwraps the toolbar back to a single row in a single layout pass.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layout()
    assertThat(toolbarPanel.height).isEqualTo(singleRowToolbarHeight)
  }

  @Test
  fun testResizeOnTransitionToDetachedMultipleDisplays() {
    val (phone, phoneView, ui) = startPhone(Dimension(900, 500))

    // Two displays.
    runBlocking {
      phone.changeSecondaryDisplays(listOf(DisplayConfiguration.newBuilder().setDisplay(1).setWidth(600).setHeight(800).build()))
    }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 2 }
    ui.layoutAndDispatchEvents()
    val secondaryView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber && it.displayId == 1 }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, secondaryView) > 0u }

    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    renderAndGetFrameNumber(ui, secondaryView)
    assertMatchesAspectRatio(phoneView)
    assertMatchesAspectRatio(secondaryView)

    // 3 displays in nested splitters.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(800, 900)
    ui.layoutAndDispatchEvents()
    runBlocking {
      phone.changeSecondaryDisplays(
        listOf(
          DisplayConfiguration.newBuilder().setDisplay(1).setWidth(600).setHeight(800).build(),
          DisplayConfiguration.newBuilder().setDisplay(2).setWidth(800).setHeight(600).build(),
        )
      )
    }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 3 }
    toolWindow.decorator.size = Dimension(1200, 900)
    ui.layoutAndDispatchEvents()
    val displayViews3 = ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { displayViews3.all { renderAndGetFrameNumber(ui, it) > 0u } }
    val scalesBefore3 = displayViews3.associateWith { roundDownToNaturalNumberOrNearestSmallFraction(it.scale) }

    toolWindow.setType(ToolWindowType.FLOATING, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    for (view in displayViews3) {
      renderAndGetFrameNumber(ui, view)
      assertThat(roundDownToNaturalNumberOrNearestSmallFraction(view.scale)).isEqualTo(scalesBefore3.getValue(view))
    }
    assertThat(toolWindow.decorator.width).isLessThan(1200)
  }

  @Test
  fun testResizeOnTransitionToDetachedSplitWithWatch() {
    val (phone, phoneView, ui) = startPhone(Dimension(1200, 600))
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(emulatorRule.avdRoot, skinFolder = null))
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contentsRecursively.size == 2 }
    val watchContent = contentManager.contentsRecursively.find { it.displayName?.startsWith("Android Wear") == true }!!

    // Phone with twop displays split horizontally with the watch.
    runBlocking {
      phone.changeSecondaryDisplays(listOf(DisplayConfiguration.newBuilder().setDisplay(1).setWidth(600).setHeight(800).build()))
    }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 2 }
    ui.layoutAndDispatchEvents()
    val secondaryView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber && it.displayId == 1 }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, secondaryView) > 0u }
    FakeToolWindow.split(watchContent, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    val splitWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, splitWatchView) > 0u }
    val allViewsBeforeHorizontalSplit = ui.findAllComponents<EmulatorView>()
    val scalesBeforeHorizontalSplit = allViewsBeforeHorizontalSplit.associateWith {
      roundDownToNaturalNumberOrNearestSmallFraction(it.scale)
    }

    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    for (view in allViewsBeforeHorizontalSplit) {
      renderAndGetFrameNumber(ui, view)
      assertThat(roundDownToNaturalNumberOrNearestSmallFraction(view.scale)).isEqualTo(scalesBeforeHorizontalSplit.getValue(view))
    }
    assertMatchesAspectRatio(phoneView)
    assertMatchesAspectRatio(secondaryView)
    assertThat(splitWatchView.width).isEqualTo(320)
    assertThat(splitWatchView.scale).isEqualTo(1.0)

    // Vertical split between single-display phone and watch: watch preserves 320x320 (100% zoom) and phone preserves its zoom level.
    toolWindow.setType(ToolWindowType.DOCKED, null)
    dispatchAllEventsInIdeEventQueue()
    runBlocking { phone.changeSecondaryDisplays(emptyList()) }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 1 }
    FakeToolWindow.unsplit(watchContent.manager!!, watchContent)
    dispatchAllEventsInIdeEventQueue()
    FakeToolWindow.split(watchContent, SwingConstants.TOP)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(500, 1200)
    ui.layoutAndDispatchEvents()
    val topWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    val bottomPhoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, topWatchView) > 0u && renderAndGetFrameNumber(ui, bottomPhoneView) > 0u }
    val phoneScaleBeforeVerticalDetach = roundDownToNaturalNumberOrNearestSmallFraction(bottomPhoneView.scale)

    toolWindow.setType(ToolWindowType.FLOATING, null)
    dispatchAllEventsInIdeEventQueue()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, topWatchView)
    renderAndGetFrameNumber(ui, bottomPhoneView)
    assertThat(topWatchView.size).isEqualTo(Dimension(320, 320))
    assertThat(topWatchView.scale).isEqualTo(1.0)
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(bottomPhoneView.scale)).isEqualTo(phoneScaleBeforeVerticalDetach)

    FakeToolWindow.unsplit(watchContent.manager!!, watchContent)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testResizeOnTransitionToDetachedSplitPhones() {
    // Horizontal split between two phones where one is width-constrained (with vertical empty space) and the other is
    // height-constrained (with horizontal empty space and a wrapping toolbar), and stale floatingBounds crushes the window before
    // optimization: both devices preserve their zoom levels and horizontal empty space is eliminated.
    val dockedSize = Dimension(959, 992)
    val (_, phoneView, ui) = startPhone(dockedSize)
    val phoneContent = contentManager.contentsRecursively.find { it.displayName?.startsWith("Pixel") == true }!!

    val foldable = emulatorRule.newEmulator(FakeEmulator.createFoldableAvd(emulatorRule.avdRoot))
    foldable.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contentsRecursively.any { it.displayName?.contains("Fold") == true } }
    val foldableController = RunningEmulatorCatalog.getInstance().emulators.first { it.emulatorId.avdName.contains("Fold") }
    waitForCondition(5.seconds) { foldableController.connectionState == EmulatorController.ConnectionState.CONNECTED }
    val foldableContent = contentManager.contentsRecursively.first { it.displayName?.contains("Fold") == true }
    FakeToolWindow.split(foldableContent, SwingConstants.RIGHT)
    phoneContent.manager!!.setSelectedContent(phoneContent)
    dispatchAllEventsInIdeEventQueue()

    val foldableView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == foldable.serialNumber }
    foldable.setPosture(PostureValue.POSTURE_CLOSED)
    waitForCondition(5.seconds) {
      ui.layoutAndDispatchEvents()
      renderAndGetFrameNumber(ui, foldableView) > 0u && foldableView.deviceDisplaySize == Dimension(1080, 2092)
    }

    val foldablePanel = foldableView.findAncestor<AbstractDevicePanel<*>>()!!
    val wrappingFoldableToolbar =
      object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension = Dimension(width, if (width in 1..450) 112 else 85)
      }
    wrappingFoldableToolbar.size = Dimension(577, 85)
    foldablePanel.add(wrappingFoldableToolbar, BorderLayout.NORTH)

    val horizontalSplitter = foldableView.findAncestor<Splitter>()!!
    horizontalSplitter.proportion = 0.39f
    toolWindow.decorator.size = dockedSize
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    renderAndGetFrameNumber(ui, foldableView)

    val phoneWidthBefore = phoneView.width
    val phoneScaleBefore = roundDownToNaturalNumberOrNearestSmallFraction(phoneView.scale)
    val foldableScaleBefore = roundDownToNaturalNumberOrNearestSmallFraction(foldableView.scale)
    assertThat(foldableView.width).isGreaterThan(phoneWidthBefore)

    // Simulate ToolWindowManagerImpl applying stale floatingBounds (264 x 1501) before ToolWindowSizeOptimizer.optimizeSize runs.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    toolWindow.decorator.size = Dimension(264, 1501)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    renderAndGetFrameNumber(ui, foldableView)

    assertThat(phoneView.width).isEqualTo(phoneWidthBefore)
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(phoneView.scale)).isEqualTo(phoneScaleBefore)
    assertThat(roundDownToNaturalNumberOrNearestSmallFraction(foldableView.scale)).isEqualTo(foldableScaleBefore)
    assertMatchesAspectRatio(foldableView)
    assertThat(toolWindow.decorator.width).isLessThan(dockedSize.width)

    FakeToolWindow.unsplit(foldableContent.manager!!, null)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testNoResizeWhenLayoutInspectorActive() {
    val (_, phoneView, ui) = startPhone()
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)

    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!
    assertThat(devicePanel.isLayoutInspectorActive).isFalse()

    // Simulate Embedded Layout Inspector wrapping the streaming content panel.
    val streamingContentPanel = DataManager.getInstance().getDataContext(devicePanel).getData(STREAMING_CONTENT_PANEL_KEY)!!
    val originalParent = streamingContentPanel.parent
    val wrapper = JPanel(BorderLayout())
    originalParent.remove(streamingContentPanel)
    wrapper.add(streamingContentPanel, BorderLayout.CENTER)
    originalParent.add(wrapper, BorderLayout.CENTER)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    assertThat(devicePanel.isLayoutInspectorActive).isTrue()
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.size).isEqualTo(Dimension(500, 500))

    // Unwrap the content panel (simulating disabling Layout Inspector) and verify resizing works again.
    originalParent.remove(wrapper)
    originalParent.add(streamingContentPanel, BorderLayout.CENTER)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    assertThat(devicePanel.isLayoutInspectorActive).isFalse()
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertMatchesAspectRatio(phoneView)
  }

  @Test
  fun testVerticalToolbarOptimization() {
    val (_, phoneView, ui) = startPhone(Dimension(500, 500))
    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!
    devicePanel.toolbarPlacement = ToolbarPlacement.VERTICAL
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    assertThat(devicePanel.toolbarPlacement).isEqualTo(ToolbarPlacement.VERTICAL)
    assertThat(devicePanel.toolbarPanel.isVisible).isTrue()
    val toolbarWidth = devicePanel.toolbarPanel.width
    assertThat(toolbarWidth).isGreaterThan(0)

    // Docked RIGHT: shrink width to remove horizontal empty space.
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isEqualTo(500)
    assertMatchesAspectRatio(phoneView)

    // Docked BOTTOM: shrink height when vertical empty space exists.
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(300, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertThat(toolWindow.decorator.width).isEqualTo(300)
    assertMatchesAspectRatio(phoneView)

    // Docked BOTTOM: expand height when horizontal empty space exists.
    toolWindow.decorator.size = Dimension(300, 300)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.height).isGreaterThan(300)
    assertThat(toolWindow.decorator.width).isEqualTo(300)
    assertMatchesAspectRatio(phoneView)

    // Undocked (WINDOWED): shrink width and height.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isAtLeast(500)
    assertMatchesAspectRatio(phoneView)
  }

  @Test
  fun testNoToolbarOptimization() {
    val (_, phoneView, ui) = startPhone(Dimension(500, 500))
    val devicePanel = phoneView.findAncestor<AbstractDevicePanel<*>>()!!
    devicePanel.toolbarPlacement = ToolbarPlacement.NONE
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    assertThat(devicePanel.toolbarPlacement).isEqualTo(ToolbarPlacement.NONE)
    assertThat(devicePanel.toolbarPanel.isVisible).isFalse()

    // Docked RIGHT: shrink width to remove horizontal empty space.
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isEqualTo(500)
    assertMatchesAspectRatio(phoneView)

    // Docked BOTTOM: shrink height when vertical empty space exists.
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(150, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)

    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isLessThan(600)
    assertThat(toolWindow.decorator.width).isEqualTo(150)
    assertMatchesAspectRatio(phoneView)
  }

  @Test
  fun testDimensionTiedToAnotherToolWindow() {
    val (phone, watch) = startPhoneAndWatch()
    val watchContent = contentManager.contents.find { it.displayName?.startsWith("Android Wear") == true }!!

    // Simulate Running Devices docked RIGHT above another docked tool window (e.g., Device Explorer) in a vertical Splitter.
    val otherToolWindow = JPanel()
    val dockingSplitter = createDockingSplitter(isVertical = true, otherToolWindow).apply { size = Dimension(500, 1001) }
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    val ui = createFakeUi(dockingSplitter)
    ui.layoutAndDispatchEvents()
    val phoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, phoneView) > 0u }

    // Docked RIGHT with horizontal empty space: must NOT shrink width because width is tied to otherToolWindow.
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isEqualTo(500)
    assertThat(otherToolWindow.width).isEqualTo(500)

    // Docked RIGHT with vertical empty space: SHOULD expand width to match aspect ratio.
    dockingSplitter.size = Dimension(150, 1001)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.width).isGreaterThan(150)
    assertThat(otherToolWindow.width).isEqualTo(toolWindow.decorator.width)
    assertMatchesAspectRatio(phoneView)

    // Hiding the other tool window allows Running Devices to shrink again.
    otherToolWindow.isVisible = false
    dockingSplitter.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertMatchesAspectRatio(phoneView)
    otherToolWindow.isVisible = true

    // Docked BOTTOM beside another tool window in a horizontal Splitter (tied height):
    // does not shrink height when vertical empty space exists, and expands height when horizontal empty space exists.
    dockingSplitter.orientation = false
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    dockingSplitter.size = Dimension(301, 600)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, phoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isEqualTo(600)
    assertThat(otherToolWindow.height).isEqualTo(600)

    dockingSplitter.size = Dimension(601, 300)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.height).isGreaterThan(300)
    assertThat(otherToolWindow.height).isEqualTo(toolWindow.decorator.height)
    assertMatchesAspectRatio(phoneView)

    // Watch display (320x320) docked RIGHT with tied width: does not shrink from 400 to 320 when height allows 1x,
    // but expands from 400 to 640 (2x) when height allows 2x.
    dockingSplitter.orientation = true
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    contentManager.setSelectedContent(watchContent)
    dispatchAllEventsInIdeEventQueue()
    dockingSplitter.size = Dimension(400, 1101)
    ui.layoutAndDispatchEvents()
    val watchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, watchView) > 0u }
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(toolWindow.decorator.width).isEqualTo(400)

    dockingSplitter.size = Dimension(400, 1761)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.width).isEqualTo(640)
    assertThat(toolWindow.decorator.width).isEqualTo(640)

    // Explicit zoom > 1.0 (2x = 640px) with tied width: does not shrink from 800, but expands from 400.
    val watchScrollPane = (watchView.parent as JViewport).parent as JScrollPane
    dockingSplitter.size = Dimension(800, 1101)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    watchView.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchView.scale).isEqualTo(2.0)
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(toolWindow.decorator.width).isEqualTo(800)

    dockingSplitter.size = Dimension(400, 1101)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    assertThat(watchScrollPane.horizontalScrollBar.isVisible).isTrue()
    assertThat(watchScrollPane.verticalScrollBar.isVisible).isTrue()
    ToolWindowSizeOptimizer(watchView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    val expectedZoomedWidth = 640 + watchScrollPane.verticalScrollBar.width
    assertThat(toolWindow.decorator.width).isEqualTo(expectedZoomedWidth)
    assertThat(otherToolWindow.width).isEqualTo(expectedZoomedWidth)
    assertThat(watchScrollPane.verticalScrollBar.isVisible).isTrue()
    assertThat(watchScrollPane.horizontalScrollBar.isVisible).isFalse()
  }

  @Test
  fun testInternalSplitWithTiedToolWindowDimension() {
    val (phone, watch) = startPhoneAndWatch()
    val phoneContent = contentManager.contents.find { it.displayName?.startsWith("Pixel") == true }!!
    val watchContent = contentManager.contents.find { it.displayName?.startsWith("Android Wear") == true }!!

    val otherToolWindow = JPanel()
    val dockingSplitter = createDockingSplitter(isVertical = true, otherToolWindow)
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    val ui = createFakeUi(dockingSplitter)

    // Internal horizontal split inside Running Devices while outer width is tied to otherToolWindow:
    // shrinking a split pane adjusts the internal splitter without shrinking Running Devices,
    // while expanding a split pane expands Running Devices without shrinking the sibling pane.
    FakeToolWindow.split(watchContent, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    dockingSplitter.size = Dimension(900, 1101)
    ui.layoutAndDispatchEvents()
    val splitWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    val splitPhoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, splitWatchView) > 0u && renderAndGetFrameNumber(ui, splitPhoneView) > 0u }
    doubleClickInView(ui, splitWatchView, 2, splitWatchView.height / 2)
    assertThat(splitWatchView.width).isEqualTo(320)
    assertThat(toolWindow.decorator.width).isEqualTo(900)

    val internalSplitter = splitWatchView.findAncestor<Splitter>()!!
    internalSplitter.proportion = 0.6f // Phone ~299px, Watch ~200px in 500px width.
    dockingSplitter.size = Dimension(500, 1101)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, splitWatchView)
    renderAndGetFrameNumber(ui, splitPhoneView)
    val phoneWidthBefore = splitPhoneView.width
    doubleClickInView(ui, splitWatchView, splitWatchView.width / 2, 2)
    assertThat(splitWatchView.width).isEqualTo(320)
    assertThat(splitPhoneView.width).isEqualTo(phoneWidthBefore)
    assertThat(toolWindow.decorator.width).isGreaterThan(500)

    FakeToolWindow.unsplit(watchContent.manager!!, phoneContent)
    dispatchAllEventsInIdeEventQueue()

    // Internal vertical split inside Running Devices while docked RIGHT with tied outer width:
    // double-clicking horizontal empty space in phoneView steals vertical empty space from watchView without reducing watchView's zoom,
    // and double-clicking again once watchView has no empty space left leaves both panes unchanged.
    FakeToolWindow.split(watchContent, SwingConstants.TOP)
    dispatchAllEventsInIdeEventQueue()
    dockingSplitter.size = Dimension(500, 2001)
    ui.layoutAndDispatchEvents()
    val vSplitWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    val vSplitPhoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, vSplitWatchView) > 0u && renderAndGetFrameNumber(ui, vSplitPhoneView) > 0u }
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)
    val phoneHeightBefore = vSplitPhoneView.height
    doubleClickInView(ui, vSplitPhoneView, 2, vSplitPhoneView.height / 2)
    assertThat(toolWindow.decorator.width).isEqualTo(500)
    assertThat(vSplitWatchView.height).isEqualTo(320)
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)
    assertThat(vSplitPhoneView.height).isGreaterThan(phoneHeightBefore)

    val phoneHeightAfterSteal = vSplitPhoneView.height
    doubleClickInView(ui, vSplitPhoneView, 2, vSplitPhoneView.height / 2)
    assertThat(vSplitWatchView.height).isEqualTo(320)
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)
    assertThat(vSplitPhoneView.height).isEqualTo(phoneHeightAfterSteal)

    // With tied width = 640px (!canShrinkWidth && canShrinkHeight) and watch at 640x400 (fitScale = 1.0, maxScaleX = 2.0):
    // expands watch height to 640px (2x) when sibling slack allows it, and falls back to shrinking to 320px (1x) when capped below 640px.
    val vInternalSplitter = vSplitWatchView.findAncestor<Splitter>()!!
    val watchScrollPane = (vSplitWatchView.parent as JViewport).parent as JScrollPane
    val watchPaneChrome = vInternalSplitter.firstComponent.height - watchScrollPane.height
    dockingSplitter.size = Dimension(640, 4401) // Running Devices = 640x2200: phone (~1740px > 1423px) has >300px of vertical slack.
    vInternalSplitter.proportion = (400 + watchPaneChrome).toFloat() / (2200 - vInternalSplitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, vSplitWatchView)
    renderAndGetFrameNumber(ui, vSplitPhoneView)
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(vSplitWatchView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(watchScrollPane.height).isEqualTo(640)
    assertThat(vSplitWatchView.scale).isEqualTo(2.0)

    // When phone is height-constrained (< 1423px, 0 vertical slack -> maxHeight for watch = 400 < 640), watch shrinks from 400px to 320px.
    vSplitWatchView.resetZoom()
    dockingSplitter.size = Dimension(640, 2001) // Running Devices = 640x1000: phone (~540px < 1423px) has 0 vertical slack.
    vInternalSplitter.proportion = (400 + watchPaneChrome).toFloat() / (1000 - vInternalSplitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, vSplitWatchView)
    renderAndGetFrameNumber(ui, vSplitPhoneView)
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(vSplitWatchView).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(watchScrollPane.height).isEqualTo(320)
    assertThat(vSplitWatchView.scale).isEqualTo(1.0)

    FakeToolWindow.unsplit(watchContent.manager!!, phoneContent)
    dispatchAllEventsInIdeEventQueue()

    // Docked BOTTOM with tied outer height and an internal horizontal split:
    // steals horizontal space from watch only when doing so does not reduce watch's zoom level.
    dockingSplitter.orientation = false
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    FakeToolWindow.split(watchContent, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    dockingSplitter.size = Dimension(601, 600)
    ui.layoutAndDispatchEvents()
    val bottomSplitWatchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    val bottomSplitPhoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }
    waitForCondition(5.seconds) {
      renderAndGetFrameNumber(ui, bottomSplitWatchView) > 0u && renderAndGetFrameNumber(ui, bottomSplitPhoneView) > 0u
    }
    val bottomWatchScaleBefore = bottomSplitWatchView.scale
    val bottomWatchWidthBefore = bottomSplitWatchView.width
    val bottomPhoneWidthBefore = bottomSplitPhoneView.width
    doubleClickInView(ui, bottomSplitPhoneView, bottomSplitPhoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isEqualTo(600)
    assertThat(bottomSplitWatchView.width).isEqualTo(bottomWatchWidthBefore)
    assertThat(bottomSplitWatchView.scale).isEqualTo(bottomWatchScaleBefore)
    assertThat(bottomSplitPhoneView.width).isEqualTo(bottomPhoneWidthBefore)

    val bottomInternalSplitter = bottomSplitWatchView.findAncestor<Splitter>()!!
    bottomInternalSplitter.proportion = 0.25f
    dockingSplitter.size = Dimension(1601, 600) // Running Devices = 800x600: phone ~198px, watch ~595px (1x = 320px).
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, bottomSplitWatchView)
    renderAndGetFrameNumber(ui, bottomSplitPhoneView)
    assertThat(bottomSplitWatchView.scale).isEqualTo(1.0)
    doubleClickInView(ui, bottomSplitPhoneView, bottomSplitPhoneView.width / 2, 2)
    assertThat(toolWindow.decorator.height).isEqualTo(600)
    assertThat(bottomSplitWatchView.scale).isEqualTo(1.0)
    assertMatchesAspectRatio(bottomSplitPhoneView)

    // Nested Splitter inside the sibling pane (multi-display SplitPanel with honorMinimumSize = true)
    // sized smaller than the sum of its children's minimum sizes: must not throw and must not steal space.
    runBlocking {
      phone.changeSecondaryDisplays(listOf(DisplayConfiguration.newBuilder().setDisplay(1).setWidth(600).setHeight(800).build()))
    }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 2 }
    val splitPanel = ui.getComponent<SplitPanel>()
    splitPanel.splitType = SplitType.HORIZONTAL
    splitPanel.firstComponent.minimumSize = Dimension(250, 200)
    splitPanel.secondComponent.minimumSize = Dimension(250, 200)
    bottomInternalSplitter.proportion = 0.6f
    dockingSplitter.size = Dimension(1201, 600) // Running Devices = 600x600: SplitPanel ~356px (< 250 + 250), watch ~237px (< 320px).
    ui.layoutAndDispatchEvents()
    val secondaryPhoneView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == phone.serialNumber && it.displayId == 1 }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, secondaryPhoneView) > 0u }
    renderAndGetFrameNumber(ui, bottomSplitWatchView)
    val splitPanelWidthBefore = splitPanel.width
    val watchWidthBeforeNestedCheck = bottomSplitWatchView.width
    assertThat(splitPanel.splitType).isEqualTo(SplitType.HORIZONTAL)
    assertThat(splitPanelWidthBefore).isLessThan(500)
    doubleClickInView(ui, bottomSplitWatchView, bottomSplitWatchView.width / 2, 2)
    assertThat(splitPanel.width).isEqualTo(splitPanelWidthBefore)
    assertThat(bottomSplitWatchView.width).isEqualTo(watchWidthBeforeNestedCheck)
    splitPanel.firstComponent.minimumSize = null
    splitPanel.secondComponent.minimumSize = null
    runBlocking { phone.changeSecondaryDisplays(emptyList()) }
    waitForCondition(5.seconds) { ui.findAllComponents<EmulatorView> { it.deviceSerialNumber == phone.serialNumber }.size == 1 }

    FakeToolWindow.unsplit(watchContent.manager!!, phoneContent)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testZoomedInInternalSplitStealsOnlyUnusedSiblingSpace() {
    val tempFolder = emulatorRule.avdRoot
    val watch1 = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    val watch2 = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    toolWindow.show()
    watch1.start()
    watch2.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 2 }
    val watch1Content = contentManager.contents[0]
    val watch2Content = contentManager.contents[1]
    contentManager.setSelectedContent(watch1Content)
    dispatchAllEventsInIdeEventQueue()

    // 1. Docked BOTTOM with an internal horizontal split and watch1 zoomed to 2x (640x640):
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    FakeToolWindow.split(watch2Content, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(1100, 700)
    val ui = createFakeUi(toolWindow.decorator)
    ui.layoutAndDispatchEvents()
    val hSplitter = ui.getComponent<Splitter>()
    val hWatch1View = ui.getComponent<EmulatorView> { hSplitter.firstComponent.isAncestorOf(it) }
    val hWatch2View = ui.getComponent<EmulatorView> { hSplitter.secondComponent.isAncestorOf(it) }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, hWatch1View) > 0u && renderAndGetFrameNumber(ui, hWatch2View) > 0u }
    val hWatch1ScrollPane = (hWatch1View.parent as JViewport).parent as JScrollPane

    // Enough sibling slack (watch2 ~693px > 320px): watch1 widens to full 640px without reducing watch2's zoom.
    hSplitter.proportion = 400f / (1100 - hSplitter.dividerWidth)
    ui.layoutAndDispatchEvents()
    hWatch1View.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(hWatch1View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(hWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(hWatch1ScrollPane.width).isEqualTo(640)
    assertThat(hWatch2View.scale).isEqualTo(1.0)
    assertThat(hWatch1ScrollPane.horizontalScrollBar.isVisible).isFalse()

    // Partial sibling slack (watch2 = 400px -> 80px slack above 320px): watch1 steals 80px and reserves horizontal scrollbar height.
    toolWindow.decorator.size = Dimension(800 + hSplitter.dividerWidth, 700)
    hSplitter.proportion = 0.5f
    ui.layoutAndDispatchEvents()
    hWatch1View.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(hWatch1View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(hWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(hWatch1ScrollPane.width).isEqualTo(480)
    assertThat(hWatch2View.width).isEqualTo(320)
    assertThat(hWatch2View.scale).isEqualTo(1.0)
    assertThat(hWatch1ScrollPane.horizontalScrollBar.isVisible).isTrue()
    assertThat(hWatch1ScrollPane.verticalScrollBar.isVisible).isFalse()

    // Zero sibling slack (watch2 = 300px < 320px): watch1 steals 0px so watch2's zoom level is not reduced.
    toolWindow.decorator.size = Dimension(700 + hSplitter.dividerWidth, 700)
    hSplitter.proportion = 400f / 700f
    ui.layoutAndDispatchEvents()
    assertThat(hWatch1View.scale).isEqualTo(2.0)
    renderAndGetFrameNumber(ui, hWatch2View)
    val watch2ScaleBefore = hWatch2View.scale
    ToolWindowSizeOptimizer(hWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, hWatch2View)
    assertThat(hWatch1ScrollPane.width).isEqualTo(400)
    assertThat(hWatch2View.width).isEqualTo(300)
    assertThat(hWatch2View.scale).isEqualTo(watch2ScaleBefore)

    // Docked RIGHT when toolWindow.stretchWidth is clamped to the frame: internal splitter proportion
    // is reconciled against the clamped width so watch2 keeps 320px (1x zoom preserved).
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.decorator.size = Dimension(720 + hSplitter.dividerWidth, 800)
    hSplitter.proportion = 400f / 720f // watch1 = 400px, watch2 = 320px (1x).
    toolWindow.decorator.maximumSize = Dimension(800 + hSplitter.dividerWidth, 800)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, hWatch2View)
    assertThat(hWatch1View.scale).isEqualTo(2.0)
    assertThat(hWatch2View.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(hWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, hWatch2View)
    assertThat(toolWindow.decorator.width).isEqualTo(800 + hSplitter.dividerWidth)
    assertThat(hWatch1ScrollPane.width).isEqualTo(480)
    assertThat(hWatch2View.width).isEqualTo(320)
    assertThat(hWatch2View.scale).isEqualTo(1.0)
    toolWindow.decorator.maximumSize = null

    FakeToolWindow.unsplit(watch2Content.manager!!, watch1Content)
    dispatchAllEventsInIdeEventQueue()

    // 2. Docked RIGHT with an internal vertical split and watch1 still zoomed to 2x (640x640):
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    FakeToolWindow.split(watch2Content, SwingConstants.BOTTOM)
    dispatchAllEventsInIdeEventQueue()
    toolWindow.decorator.size = Dimension(600, 1400)
    ui.layoutAndDispatchEvents()
    val vSplitter = ui.getComponent<Splitter>()
    val vWatch1View = ui.getComponent<EmulatorView> { vSplitter.firstComponent.isAncestorOf(it) }
    val vWatch2View = ui.getComponent<EmulatorView> { vSplitter.secondComponent.isAncestorOf(it) }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, vWatch1View) > 0u && renderAndGetFrameNumber(ui, vWatch2View) > 0u }
    val vWatch1ScrollPane = (vWatch1View.parent as JViewport).parent as JScrollPane
    vSplitter.proportion = 0.4f
    ui.layoutAndDispatchEvents()
    assertThat(vWatch1View.scale).isEqualTo(2.0)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    assertThat(vWatch1ScrollPane.height).isLessThan(640)

    // Enough sibling slack (watch2 at 1x = 320px): watch1 grows height to 640px and expands width to 640px while watch2 stays at 1x.
    ToolWindowSizeOptimizer(vWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(vWatch1ScrollPane.height).isEqualTo(640)
    assertThat(toolWindow.decorator.width).isEqualTo(640)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    assertThat(vWatch1ScrollPane.verticalScrollBar.isVisible).isFalse()

    // When watch2 is at 2x (640px), watch1 only steals watch2's slack above 640px so watch2 stays at 2x.
    toolWindow.decorator.size = Dimension(800, 1550)
    vSplitter.proportion = 0.4f
    ui.layoutAndDispatchEvents()
    vWatch1View.zoom(ZoomType.IN)
    ui.layoutAndDispatchEvents()
    assertThat(vWatch1View.scale).isEqualTo(2.0)
    assertThat(vWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(vWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(vWatch2View.height).isEqualTo(640)
    assertThat(vWatch2View.scale).isEqualTo(2.0)
    assertThat(toolWindow.decorator.width).isEqualTo(640 + vWatch1ScrollPane.verticalScrollBar.width)

    // Partial sibling slack (watch2 at 1x = 320px): watch1 steals only watch2's unused vertical space and reserves scrollbar width.
    toolWindow.decorator.size = Dimension(600, 1150)
    vSplitter.proportion = 0.4f
    ui.layoutAndDispatchEvents()
    assertThat(vWatch1View.scale).isEqualTo(2.0)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    ToolWindowSizeOptimizer(vWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    assertThat(vWatch2View.height).isEqualTo(320)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    assertThat(vWatch1ScrollPane.height).isLessThan(640)
    assertThat(toolWindow.decorator.width).isEqualTo(640 + vWatch1ScrollPane.verticalScrollBar.width)
    assertThat(vWatch1ScrollPane.verticalScrollBar.isVisible).isTrue()
    assertThat(vWatch1ScrollPane.horizontalScrollBar.isVisible).isFalse()

    // Docked BOTTOM when toolWindow.stretchHeight is clamped to the frame: internal vertical splitter proportion
    // is reconciled against the clamped height so watch2 keeps 320px (1x zoom preserved).
    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(700, 900)
    ui.layoutAndDispatchEvents()
    val watch2ChromeHeight = vSplitter.secondComponent.height - vWatch2View.height
    val vTotalStart = 900 - vSplitter.dividerWidth
    vSplitter.proportion = (vTotalStart - (320 + watch2ChromeHeight)).toFloat() / vTotalStart
    toolWindow.decorator.maximumSize = Dimension(700, 980)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, vWatch2View)
    assertThat(vWatch1View.scale).isEqualTo(2.0)
    assertThat(vWatch2View.height).isEqualTo(320)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    val vWatch1HeightBeforeClamp = vWatch1ScrollPane.height
    ToolWindowSizeOptimizer(vWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, vWatch2View)
    assertThat(toolWindow.decorator.height).isEqualTo(980)
    assertThat(vWatch1ScrollPane.height).isEqualTo(vWatch1HeightBeforeClamp + 80)
    assertThat(vWatch2View.height).isEqualTo(320)
    assertThat(vWatch2View.scale).isEqualTo(1.0)
    toolWindow.decorator.maximumSize = null

    FakeToolWindow.unsplit(watch2Content.manager!!, watch1Content)
    dispatchAllEventsInIdeEventQueue()

    // 3. Nested 3-way split (outer horizontal Splitter -> inner vertical Splitter containing watch1 above watch2):
    // when watch1 narrows its branch via the outer horizontal Splitter, watch2's toolbar wraps taller in that narrowed branch;
    // both ToolWindowSizeOptimizer and applyDesiredViewportSize must evaluate watch2's zoom-preserving height at the narrowed width.
    val dummyContent = ContentFactory.getInstance().createContent(JPanel(), "Dummy", false)
    contentManager.addContent(dummyContent)
    FakeToolWindow.split(dummyContent, SwingConstants.RIGHT)
    dispatchAllEventsInIdeEventQueue()
    FakeToolWindow.split(watch2Content, SwingConstants.BOTTOM)
    dispatchAllEventsInIdeEventQueue()

    toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
    toolWindow.decorator.size = Dimension(1250, 1500)
    ui.layoutAndDispatchEvents()
    val hOuter = ui.getComponent<Splitter> { !it.isVertical }
    val vInner = ui.getComponent<Splitter> { it.isVertical }
    assertThat(hOuter.firstComponent.isAncestorOf(vInner)).isTrue()
    val nWatch1View = ui.getComponent<EmulatorView> { vInner.firstComponent.isAncestorOf(it) }
    val nWatch2View = ui.getComponent<EmulatorView> { vInner.secondComponent.isAncestorOf(it) }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, nWatch1View) > 0u && renderAndGetFrameNumber(ui, nWatch2View) > 0u }
    val nWatch1ScrollPane = (nWatch1View.parent as JViewport).parent as JScrollPane
    val nWatch2ScrollPane = (nWatch2View.parent as JViewport).parent as JScrollPane
    val watch2DevicePanel = nWatch2View.findAncestor<AbstractDevicePanel<*>>()!!
    (watch2DevicePanel.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)?.let { watch2DevicePanel.remove(it) }
    var watch2WrapThreshold = 700
    val wrappingToolbar =
      object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
          val h = if (width in 1..watch2WrapThreshold) 60 else 25
          return Dimension(width, h)
        }
      }
    wrappingToolbar.size = Dimension(1000, 25)
    watch2DevicePanel.add(wrappingToolbar, BorderLayout.NORTH)

    // Measure watch1/watch2 chrome heights at 650px width (wrapped toolbar: 60px) vs 1000px width (unwrapped toolbar: 25px).
    hOuter.proportion = 650f / (1250 - hOuter.dividerWidth)
    vInner.proportion = 0.35f
    ui.layoutAndDispatchEvents()
    val decoratorChrome = toolWindow.decorator.height - vInner.height
    val watch1Chrome = vInner.firstComponent.height - nWatch1ScrollPane.height
    val watch2NarrowChrome = vInner.secondComponent.height - nWatch2ScrollPane.height

    hOuter.proportion = 1000f / (1250 - hOuter.dividerWidth)
    ui.layoutAndDispatchEvents()
    val watch2WideChrome = vInner.secondComponent.height - nWatch2ScrollPane.height
    assertThat(watch2NarrowChrome - watch2WideChrome).isEqualTo(35)

    // Size vInner so watch2 has exact 2x height (640px) once narrowed (640 + watch2NarrowChrome), and watch1 (< 640px) is at 2x.
    val vInnerTotal = 480 + watch1Chrome + 640 + watch2NarrowChrome + vInner.dividerWidth
    val startHeight3Way = vInnerTotal + decoratorChrome
    toolWindow.decorator.size = Dimension(1250, startHeight3Way)
    val vInnerSplitTotal = vInnerTotal - vInner.dividerWidth
    vInner.proportion = (vInnerSplitTotal - (640 + watch2NarrowChrome)).toFloat() / vInnerSplitTotal
    toolWindow.decorator.maximumSize = Dimension(1250, startHeight3Way + 40)
    ui.layoutAndDispatchEvents()
    if (nWatch1View.scale < 2.0) {
      nWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(nWatch1View.scale).isEqualTo(2.0)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(nWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(toolWindow.decorator.height).isEqualTo(startHeight3Way + 40)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    toolWindow.decorator.maximumSize = null

    // When watch2 starts with zero pre-wrap slack (640 + watch2WideChrome, so required + delta = 640 + watch2NarrowChrome > currentSize):
    // a) Partial clamped expansion (+100px): reconcileSplitterChildSize reserves +35px of the expansion for watch2's wrapped toolbar
    //    (keeping watch2 at 640px = 2x) and gives the remaining +65px to watch1.
    val zeroSlackVInnerTotal = 480 + watch1Chrome + (640 + watch2WideChrome) + vInner.dividerWidth
    val zeroSlackStartHeight = zeroSlackVInnerTotal + decoratorChrome
    val zeroSlackVSplitTotal = zeroSlackVInnerTotal - vInner.dividerWidth
    toolWindow.decorator.size = Dimension(1250, zeroSlackStartHeight)
    hOuter.proportion = 1000f / (1250 - hOuter.dividerWidth)
    vInner.proportion = (zeroSlackVSplitTotal - (640 + watch2WideChrome)).toFloat() / zeroSlackVSplitTotal
    toolWindow.decorator.maximumSize = Dimension(1250, zeroSlackStartHeight + 100)
    ui.layoutAndDispatchEvents()
    if (nWatch1View.scale < 2.0) {
      nWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(nWatch1View.scale).isEqualTo(2.0)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(nWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(toolWindow.decorator.height).isEqualTo(zeroSlackStartHeight + 100)
    assertThat(nWatch1ScrollPane.height).isEqualTo(480 + 65)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    toolWindow.decorator.maximumSize = null

    // b) Unclamped expansion: root expands by +160px (for watch1) + +35px (for watch2's wrapped toolbar) so both reach/keep full 2x.
    toolWindow.decorator.size = Dimension(1250, zeroSlackStartHeight)
    hOuter.proportion = 1000f / (1250 - hOuter.dividerWidth)
    vInner.proportion = (zeroSlackVSplitTotal - (640 + watch2WideChrome)).toFloat() / zeroSlackVSplitTotal
    ui.layoutAndDispatchEvents()
    if (nWatch1View.scale < 2.0) {
      nWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(nWatch1View.scale).isEqualTo(2.0)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(nWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(toolWindow.decorator.height).isEqualTo(zeroSlackStartHeight + 160 + 35)
    assertThat(nWatch1ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)

    // Also verify docked RIGHT (canExpandHeight = false): watch1 starts at 620px height (20px short of 640px) and watch2 starts
    // with 45px pre-wrap slack (only 10px post-wrap slack once hOuter narrows vInner from 1000px to <= 700px).
    // ToolWindowSizeOptimizer must evaluate adjustedMaxHeight at the narrowed vInner width (630 < 640) and reserve vBarPrefWidth.
    val rightVInnerTotal = 620 + watch1Chrome + (640 + watch2NarrowChrome + 10) + vInner.dividerWidth
    val rightHeight3Way = rightVInnerTotal + decoratorChrome
    val rightVInnerSplitTotal = rightVInnerTotal - vInner.dividerWidth
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.decorator.size = Dimension(1250, rightHeight3Way)
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(1250, 100))
    hOuter.proportion = 1000f / (1250 - hOuter.dividerWidth)
    vInner.proportion = (rightVInnerSplitTotal - (640 + watch2NarrowChrome + 10)).toFloat() / rightVInnerSplitTotal
    ui.layoutAndDispatchEvents()
    if (nWatch1View.scale < 2.0) {
      nWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(nWatch1View.scale).isEqualTo(2.0)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(nWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(toolWindow.decorator.width).isEqualTo(1250)
    assertThat(nWatch1ScrollPane.height).isEqualTo(630)
    assertThat(nWatch2ScrollPane.height).isEqualTo(640)
    assertThat(nWatch2View.height).isEqualTo(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    assertThat(nWatch1ScrollPane.verticalScrollBar.isVisible).isTrue()
    assertThat(nWatch1ScrollPane.horizontalScrollBar.isVisible).isFalse()
    whenever(toolWindow.decorator.minimumSize).thenReturn(Dimension(0, 0))

    // Docked RIGHT when adjustedMaxHeight flips needVBar and widens desiredSize.width across watch1's own toolbar unwrap threshold:
    // convergence re-runs so watch1 uses the freed toolbar height (reaching full 640px without a vertical scrollbar) while watch2 keeps 2x.
    val vBarPrefWidth = nWatch1ScrollPane.verticalScrollBar.preferredSize.width
    val watch1WrapThreshold = 640 + vBarPrefWidth - 2
    val startInnerWidth = 640 + vBarPrefWidth / 2
    val watch1DevicePanel = nWatch1View.findAncestor<AbstractDevicePanel<*>>()!!
    val originalWatch1Toolbar = (watch1DevicePanel.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
    if (originalWatch1Toolbar != null) {
      watch1DevicePanel.remove(originalWatch1Toolbar)
    }
    val wrappingToolbar1 =
      object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
          val h = if (width in 1..watch1WrapThreshold) 60 else 25
          return Dimension(width, h)
        }
      }
    wrappingToolbar1.size = Dimension(startInnerWidth, 60)
    watch1DevicePanel.add(wrappingToolbar1, BorderLayout.NORTH)
    watch2WrapThreshold = startInnerWidth - 2

    hOuter.proportion = startInnerWidth.toFloat() / (1250 - hOuter.dividerWidth)
    vInner.proportion = 0.5f
    ui.layoutAndDispatchEvents()
    val watch1WrappedChrome = vInner.firstComponent.height - nWatch1ScrollPane.height
    val cycleVInnerTotal = 620 + watch1WrappedChrome + (640 + watch2WideChrome + 25) + vInner.dividerWidth
    val cycleVInnerSplitTotal = cycleVInnerTotal - vInner.dividerWidth
    toolWindow.decorator.size = Dimension(1250, cycleVInnerTotal + decoratorChrome)
    hOuter.proportion = startInnerWidth.toFloat() / (1250 - hOuter.dividerWidth)
    vInner.proportion = (cycleVInnerSplitTotal - (640 + watch2WideChrome + 25)).toFloat() / cycleVInnerSplitTotal
    ui.layoutAndDispatchEvents()
    if (nWatch1View.scale < 2.0) {
      nWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(wrappingToolbar1.height).isEqualTo(60)
    assertThat(wrappingToolbar.height).isEqualTo(25)
    assertThat(nWatch1View.scale).isEqualTo(2.0)
    assertThat(nWatch2View.scale).isEqualTo(2.0)
    ToolWindowSizeOptimizer(nWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, nWatch2View)
    assertThat(wrappingToolbar1.height).isEqualTo(25)
    assertThat(nWatch1ScrollPane.height).isEqualTo(640)
    assertThat(nWatch1ScrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(nWatch2ScrollPane.height).isAtLeast(640)
    assertThat(nWatch2View.scale).isEqualTo(2.0)

    watch1DevicePanel.remove(wrappingToolbar1)
    if (originalWatch1Toolbar != null) {
      watch1DevicePanel.add(originalWatch1Toolbar, BorderLayout.NORTH)
    }

    FakeToolWindow.unsplit(watch2Content.manager!!, watch1Content)
    dispatchAllEventsInIdeEventQueue()
    FakeToolWindow.unsplit(dummyContent.manager!!, watch1Content)
    dispatchAllEventsInIdeEventQueue()
    contentManager.removeContent(dummyContent, true)
    dispatchAllEventsInIdeEventQueue()
    FakeToolWindow.split(watch2Content, SwingConstants.BOTTOM)
    dispatchAllEventsInIdeEventQueue()

    // 4. Detached WINDOWED mode with an internal vertical splitter when window clamping restricts BOTH width and height:
    // minOtherHeights must still be refreshed at the actual clamped width (keeping watch2's toolbar wrapped at 60px) so watch2 keeps 1x.
    watch2WrapThreshold = 620
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    dispatchAllEventsInIdeEventQueue()
    val window = SwingUtilities.getWindowAncestor(toolWindow.decorator)!!
    val rootPane = toolWindow.decorator.parent as Container
    var windowMaxSize: Dimension? = null
    doAnswer { invocation ->
        val maxSize = windowMaxSize
        val w = invocation.getArgument<Int>(0).let { if (maxSize != null) it.coerceAtMost(maxSize.width) else it }
        val h = invocation.getArgument<Int>(1).let { if (maxSize != null) it.coerceAtMost(maxSize.height) else it }
        rootPane.setSize(w, h)
        for (child in rootPane.components) {
          child.setSize(w, h)
        }
        rootPane.validate()
        null
      }
      .whenever(window)
      .setSize(anyInt(), anyInt())

    val vWindowed = ui.getComponent<Splitter> { it.isVertical }
    val wWatch1View = ui.getComponent<EmulatorView> { vWindowed.firstComponent.isAncestorOf(it) }
    val wWatch2View = ui.getComponent<EmulatorView> { vWindowed.secondComponent.isAncestorOf(it) }
    val wWatch1ScrollPane = (wWatch1View.parent as JViewport).parent as JScrollPane
    val wWatch2ScrollPane = (wWatch2View.parent as JViewport).parent as JScrollPane
    window.setSize(580, 1200)
    vWindowed.proportion = 0.5f
    ui.layoutAndDispatchEvents()
    val windowedDecoratorChrome = toolWindow.decorator.height - vWindowed.height
    val wWatch1Chrome = vWindowed.firstComponent.height - wWatch1ScrollPane.height
    val wWatch2NarrowChrome = vWindowed.secondComponent.height - wWatch2ScrollPane.height
    val windowedVTotal = 480 + wWatch1Chrome + 320 + wWatch2NarrowChrome + vWindowed.dividerWidth
    val windowedStartHeight = windowedVTotal + windowedDecoratorChrome
    window.setSize(580, windowedStartHeight)
    val windowedVSplitTotal = windowedVTotal - vWindowed.dividerWidth
    vWindowed.proportion = (windowedVSplitTotal - (320 + wWatch2NarrowChrome)).toFloat() / windowedVSplitTotal
    ui.layoutAndDispatchEvents()
    if (wWatch1View.scale < 2.0) {
      wWatch1View.zoom(ZoomType.IN)
      ui.layoutAndDispatchEvents()
    }
    renderAndGetFrameNumber(ui, wWatch2View)
    assertThat(wWatch1View.scale).isEqualTo(2.0)
    assertThat(wWatch2ScrollPane.height).isEqualTo(320)
    assertThat(wWatch2View.scale).isEqualTo(1.0)

    windowMaxSize = Dimension(600, windowedStartHeight + 40)
    ToolWindowSizeOptimizer(wWatch1View).resizeToolWindowToRemoveEmptySpace()
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, wWatch2View)
    assertThat(toolWindow.decorator.width).isEqualTo(600)
    assertThat(toolWindow.decorator.height).isEqualTo(windowedStartHeight + 40)
    assertThat(wWatch2ScrollPane.height).isEqualTo(320)
    assertThat(wWatch2View.height).isEqualTo(320)
    assertThat(wWatch2View.scale).isEqualTo(1.0)
    windowMaxSize = null
    toolWindow.setType(ToolWindowType.DOCKED, null)
    dispatchAllEventsInIdeEventQueue()

    FakeToolWindow.unsplit(watch2Content.manager!!, watch1Content)
    dispatchAllEventsInIdeEventQueue()
  }

  @Test
  fun testSideBySideDockedToolWindows() {
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(emulatorRule.avdRoot, skinFolder = null))
    toolWindow.show()
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 1 }
    contentManager.setSelectedContent(contentManager.contents.first())
    dispatchAllEventsInIdeEventQueue()

    // Side-by-side docked tool windows on RIGHT (rightHorizontalSplit — horizontal outer Splitter):
    // Running Devices both shrinks and expands to its exact target width while preserving otherToolWindow's width
    // (even when otherToolWindow's minimumSize exceeds its current width, matching ToolWindowPane's splitter).
    val otherToolWindow = JPanel().apply { minimumSize = Dimension(600, 300) }
    val dockingSplitter =
      createDockingSplitter(isVertical = false, otherToolWindow).apply {
        size = Dimension(1001, 550) // Running Devices = 500px, otherToolWindow = 500px.
      }
    toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
    toolWindow.setType(ToolWindowType.DOCKED, null)
    val ui = createFakeUi(dockingSplitter)
    ui.layoutAndDispatchEvents()
    val watchView = ui.getComponent<EmulatorView> { it.deviceSerialNumber == watch.serialNumber }
    waitForCondition(5.seconds) { renderAndGetFrameNumber(ui, watchView) > 0u }

    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.width).isEqualTo(320)
    assertThat(otherToolWindow.width).isEqualTo(500)

    dockingSplitter.proportion = 200f / 700f
    dockingSplitter.size = Dimension(701, 550) // Running Devices = 200px, otherToolWindow = 500px.
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(320)
    assertThat(otherToolWindow.width).isEqualTo(500)

    // Also verify when Running Devices is secondComponent in the side-by-side Splitter.
    dockingSplitter.swapComponents()
    dockingSplitter.proportion = 0.5f
    dockingSplitter.size = Dimension(1001, 550) // otherToolWindow = 500px, Running Devices = 500px.
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.width).isEqualTo(320)
    assertThat(otherToolWindow.width).isEqualTo(500)

    dockingSplitter.proportion = 500f / 700f
    dockingSplitter.size = Dimension(701, 550) // otherToolWindow = 500px, Running Devices = 200px.
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(320)
    assertThat(otherToolWindow.width).isEqualTo(500)

    // Clamped expand and clamped shrink preserve otherToolWindow's width and allocate remaining delta to Running Devices.
    dockingSplitter.maximumSize = Dimension(761, 550) // Caps total width below 821px (500 + 1 + 320).
    dockingSplitter.proportion = 500f / 700f
    dockingSplitter.size = Dimension(701, 550) // otherToolWindow = 500px, Running Devices = 200px.
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(260)
    assertThat(otherToolWindow.width).isEqualTo(500)
    dockingSplitter.maximumSize = null

    dockingSplitter.minimumSize = Dimension(901, 550) // Floors total width above 821px (500 + 1 + 320).
    dockingSplitter.proportion = 0.5f
    dockingSplitter.size = Dimension(1001, 550) // otherToolWindow = 500px, Running Devices = 500px.
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, 2, watchView.height / 2)
    assertThat(watchView.width).isEqualTo(400)
    assertThat(otherToolWindow.width).isEqualTo(500)
    dockingSplitter.minimumSize = null
  }

  private fun renderAndGetFrameNumber(fakeUi: FakeUi, displayView: AbstractDisplayView): UInt {
    fakeUi.render() // The frame number may get updated as a result of rendering.
    return displayView.frameNumber
  }

  private fun createFakeUi(root: Component): FakeUi = FakeUi(root, parentDisposable = testRootDisposable)

  private fun createDockingSplitter(isVertical: Boolean, otherToolWindow: JComponent): OnePixelSplitter =
    OnePixelSplitter(isVertical, 0.5f).apply {
      setHonorComponentsMinimumSize(false)
      firstComponent = toolWindow.decorator
      secondComponent = otherToolWindow
    }

  private fun startPhoneAndWatch(): Pair<FakeEmulator, FakeEmulator> {
    val tempFolder = emulatorRule.avdRoot
    val phone = emulatorRule.newEmulator(FakeEmulator.createPhoneAvd(tempFolder))
    val watch = emulatorRule.newEmulator(FakeEmulator.createWatchAvd(tempFolder, skinFolder = null))
    toolWindow.show()
    phone.start()
    watch.start()
    runBlocking { RunningEmulatorCatalog.getInstance().updateNow().await() }
    waitForCondition(10.seconds) { contentManager.contents.size == 2 }
    val phoneContent = contentManager.contents.find { it.displayName?.startsWith("Pixel") == true }!!
    contentManager.setSelectedContent(phoneContent)
    dispatchAllEventsInIdeEventQueue()
    return Pair(phone, watch)
  }

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
    ui.layoutAndDispatchEvents()
    return PhoneTestContext(phone, phoneView, ui)
  }

  private fun doubleClickInView(ui: FakeUi, view: AbstractDisplayView, x: Int, y: Int) {
    val pos = ui.getPosition(view)
    assertThat(ui.root.contains(pos.x + x, pos.y + y)).isTrue()
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
