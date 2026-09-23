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
import com.intellij.openapi.ui.Splitter
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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingConstants
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

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

  @After
  fun tearDown() {
    Disposer.dispose(toolWindow.disposable)
    dispatchAllEventsInIdeEventQueue() // Finish asynchronous processing triggered by hiding the tool window.
  }

  @Test
  fun testDockedHorizontal() {
    val (_, phoneView, ui) = startPhone()

    // 1. Double-clicking inside the display image should not resize the tool window.
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

    // 2. Docked RIGHT: shrink width to remove horizontal empty space.
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

    // 3. Docked RIGHT: when shrinking is not possible, expand width to remove vertical empty space.
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

    // 4. Docked LEFT: shrink and expand width.
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
    toolWindow.decorator.size = Dimension(500, 500)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, phoneView)
    doubleClickInView(ui, phoneView, 2, phoneView.height / 2)
    assertThat(toolWindow.decorator.width).isLessThan(500)
    assertThat(toolWindow.decorator.height).isAtLeast(500)
    assertMatchesAspectRatio(phoneView)

    toolWindow.setType(ToolWindowType.FLOATING, null)
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
    val (phone, _, ui) = startPhone(Dimension(900, 500))

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

    // Expand width from 200 to 2x (640) when height is 750 (watchView.height >= 640).
    toolWindow.decorator.size = Dimension(200, 750)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, watchView.width / 2, 2)
    assertThat(watchView.width).isEqualTo(640)

    // Undocked mode (WINDOWED) with scale > 1: shrinks both width and height to integer scale 2x (640x640).
    toolWindow.setType(ToolWindowType.WINDOWED, null)
    toolWindow.decorator.size = Dimension(800, 800)
    ui.layoutAndDispatchEvents()
    renderAndGetFrameNumber(ui, watchView)
    doubleClickInView(ui, watchView, 2, 2)
    assertThat(watchView.size).isEqualTo(Dimension(640, 640))
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
    toolWindow.decorator.size = Dimension(350, 900)
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
    toolWindow.decorator.size = Dimension(350, 900)
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
            when {
              width in 1..wrapThreshold2 -> 85
              width in 1..wrapThreshold -> 60
              else -> 25
            }
          return Dimension(width, h)
        }
      }
    wrappingToolbar.add(childToolbar, BorderLayout.CENTER)
    wrappingToolbar.size = Dimension(500, 25)
    devicePanel.add(wrappingToolbar, BorderLayout.NORTH)

    // 1. In WINDOWED mode with toolbar wrapping:
    // Window height expands rather than downscaling the display image.
    toolWindow.setType(ToolWindowType.WINDOWED, null)
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

    // 2. In DOCKED mode with a vertical splitter:
    // Display image downscales to preserve the split pane boundary.
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

    // 4. Cascading toolbar wrapping: narrowing to singleWrapWidth triggers a second wrap (to 95px),
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

  private fun renderAndGetFrameNumber(fakeUi: FakeUi, displayView: AbstractDisplayView): UInt {
    fakeUi.render() // The frame number may get updated as a result of rendering.
    return displayView.frameNumber
  }

  private fun createFakeUi(root: Component): FakeUi = FakeUi(root, createFakeWindow = true, parentDisposable = testRootDisposable)

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
