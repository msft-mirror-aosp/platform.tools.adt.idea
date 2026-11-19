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

import com.android.sdklib.AndroidVersion
import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.STREAMING_CONTENT_PANEL_KEY
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.InplaceButton
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.JPanel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [RotaryInputPanel]. */
@RunsInEdt
class RotaryInputPanelTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/RotaryInputPanelTest/golden")

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      goldenImageRule,
      EdtRule(),
    )

  private val project
    get() = projectRule.project

  private val testRootDisposable
    get() = projectRule.disposable

  @Test
  fun testRotaryInputPanel() {
    val avdFolder = FakeEmulator.createWatchAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(33, 0))
    val emulator = emulatorRule.newEmulator(avdFolder)
    emulator.start()
    val emulatorController = getControllerOf(emulator)
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val panel = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel.createContent(true)
    panel.size = Dimension(400, 600)
    val ui = FakeUi(panel, parentDisposable = testRootDisposable)
    ui.layoutAndDispatchEvents()

    val rotaryInputPanel = ui.getComponent<RotaryInputPanel>()
    assertThat(rotaryInputPanel.toolTipText).isEqualTo("Use mouse, trackpad or mouse wheel to turn the rotary input")
    assertThat(ui.findComponent<InplaceButton> { it.name == "Close" }).isNull()
    goldenImageRule.assertImageSimilar("RotaryInputPanel", ui.render(rotaryInputPanel))

    // Locate the center of the rotary dial in root UI coordinates.
    val panelPos = ui.getPosition(rotaryInputPanel)
    val insets = rotaryInputPanel.insets
    val scale = 0.5
    val originX = panelPos.x + insets.left + (rotaryInputPanel.width - insets.left - insets.right - 184.0 * scale) / 2.0
    val originY = panelPos.y + insets.top + (rotaryInputPanel.height - insets.top - insets.bottom - 212.0 * scale) / 2.0
    val dialCenterX = (originX + 46.0 * scale).roundToInt()
    val dialCenterY = (originY + 106.0 * scale).roundToInt()
    val dragOffset = 35 // Outside the drawn dial radius (23), within the interaction radius (46).

    // Check cursor changes when hovering inside and outside the dial interaction area.
    ui.mouse.moveTo(panelPos.x + 5, panelPos.y + 5)
    assertThat(rotaryInputPanel.cursor).isEqualTo(Cursor.getDefaultCursor())
    ui.mouse.moveTo(dialCenterX, dialCenterY - dragOffset)
    assertThat(rotaryInputPanel.cursor).isEqualTo(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

    // Press at 12 o'clock (+90 degrees clockwise from initial 9 o'clock position).
    ui.mouse.press(dialCenterX, dialCenterY - dragOffset)
    val streamInputCall = emulator.getNextGrpcCall(2.seconds, FakeEmulator.IGNORE_SCREENSHOT_CALL_FILTER)
    assertThat(streamInputCall.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag to 3 o'clock (+90 degrees clockwise).
    ui.mouse.dragTo(dialCenterX + dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag to 6 o'clock (+90 degrees clockwise).
    ui.mouse.dragTo(dialCenterX, dialCenterY + dragOffset)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag counter-clockwise back to 3 o'clock (-90 degrees).
    ui.mouse.dragTo(dialCenterX + dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")

    // Drag counter-clockwise back to 12 o'clock (-90 degrees) and then 9 o'clock (-90 degrees).
    ui.mouse.dragTo(dialCenterX, dialCenterY - dragOffset)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")
    ui.mouse.dragTo(dialCenterX - dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")
    ui.mouse.release()

    // Check mouse wheel rotation.
    ui.mouse.wheel(dialCenterX, dialCenterY, 1)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -40 }")
    ui.mouse.wheel(dialCenterX, dialCenterY, -1)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 40 }")

    panel.destroyContent()
  }

  @Test
  fun testCloseAndReopenRotaryInputPanel() {
    StudioFlags.EMBEDDED_EMULATOR_EXTENDED_CONTROLS_IN_STUDIO.overrideForTest(true, testRootDisposable)
    Disposer.register(testRootDisposable) {
      PropertiesComponent.getInstance().unsetValue("com.android.tools.idea.streaming.rotary.input.visible")
    }
    val avdFolder = FakeEmulator.createWatchAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(33, 0))
    val emulator = emulatorRule.newEmulator(avdFolder)
    emulator.start()
    val emulatorController = getControllerOf(emulator)
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val panel = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel.createContent(true)
    panel.size = Dimension(400, 600)
    val ui = FakeUi(panel, parentDisposable = testRootDisposable)
    ui.layoutAndDispatchEvents()

    val rotaryInputPanel = ui.getComponent<RotaryInputPanel>()
    val closeButton = ui.getComponent<InplaceButton> { it.name == "Close" }
    assertThat(rotaryInputPanel.isVisible).isTrue()
    assertThat(closeButton.x).isGreaterThan(rotaryInputPanel.width / 2)
    assertThat(closeButton.y).isLessThan(rotaryInputPanel.height / 2)

    val view = panel.primaryDisplayView!!
    // Action is hidden while the panel is visible.
    val actionWhenVisible = updateAndGetActionPresentation("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    assertThat(actionWhenVisible.isVisible).isFalse()
    assertThat(actionWhenVisible.isEnabled).isFalse()
    // TV remote action is hidden on a Wear device.
    val tvRemoteAction = updateAndGetActionPresentation("android.emulator.tv.remote", view, project, ActionPlaces.POPUP)
    assertThat(tvRemoteAction.isVisible).isFalse()
    assertThat(tvRemoteAction.isEnabled).isFalse()

    // Click the close icon to close the panel.
    ui.clickOn(closeButton)
    ui.layoutAndDispatchEvents()
    assertThat(rotaryInputPanel.isVisible).isFalse()

    // A newly created panel preserves the closed visibility state.
    val panel2 = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel2.createContent(true)
    panel2.size = Dimension(400, 600)
    val ui2 = FakeUi(panel2, parentDisposable = testRootDisposable)
    ui2.layoutAndDispatchEvents()
    assertThat(ui2.getComponent<RotaryInputPanel>().isVisible).isFalse()
    panel2.destroyContent()

    // Action becomes visible and enabled when the panel is closed.
    val actionWhenClosed = updateAndGetActionPresentation("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    assertThat(actionWhenClosed.isVisible).isTrue()
    assertThat(actionWhenClosed.isEnabled).isTrue()

    // Activating and deactivating Layout Inspector keeps the closed panel hidden, and hides the action while active.
    val streamingContentPanel =
      CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT) { sink -> DataSink.uiDataSnapshot(sink, panel) }
        .getData(STREAMING_CONTENT_PANEL_KEY)!!
    val originalParent = streamingContentPanel.parent
    val wrapper = JPanel(BorderLayout())
    originalParent.remove(streamingContentPanel)
    wrapper.add(streamingContentPanel, BorderLayout.CENTER)
    originalParent.add(wrapper, BorderLayout.CENTER)
    ui.layoutAndDispatchEvents()
    assertThat(rotaryInputPanel.isVisible).isFalse()
    val actionDuringLayoutInspector = updateAndGetActionPresentation("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    assertThat(actionDuringLayoutInspector.isVisible).isFalse()

    originalParent.remove(wrapper)
    originalParent.add(streamingContentPanel, BorderLayout.CENTER)
    ui.layoutAndDispatchEvents()
    assertThat(rotaryInputPanel.isVisible).isFalse()
    val actionAfterLayoutInspector = updateAndGetActionPresentation("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    assertThat(actionAfterLayoutInspector.isVisible).isTrue()
    assertThat(actionAfterLayoutInspector.isEnabled).isTrue()

    // Execute the action to reopen the panel.
    executeAction("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    ui.layoutAndDispatchEvents()
    assertThat(rotaryInputPanel.isVisible).isTrue()

    // Action is hidden again once the panel is visible.
    val actionWhenReopened = updateAndGetActionPresentation("android.emulator.rotary.input", view, project, ActionPlaces.POPUP)
    assertThat(actionWhenReopened.isVisible).isFalse()
    assertThat(actionWhenReopened.isEnabled).isFalse()

    // A newly created panel preserves the reopened visibility state.
    val panel3 = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel3.createContent(true)
    panel3.size = Dimension(400, 600)
    val ui3 = FakeUi(panel3, parentDisposable = testRootDisposable)
    ui3.layoutAndDispatchEvents()
    assertThat(ui3.getComponent<RotaryInputPanel>().isVisible).isTrue()
    panel3.destroyContent()

    panel.destroyContent()
  }

  private fun getControllerOf(emulator: FakeEmulator): EmulatorController {
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    return emulators.single { emulator.serialNumber == it.emulatorId.serialNumber }
  }
}
