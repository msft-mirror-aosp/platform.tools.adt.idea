/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.streaming.device

import com.android.SdkConstants.PRIMARY_DISPLAY_ID
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.createTestEvent
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.common.AdtUiCursorType
import com.android.tools.adtui.common.AdtUiCursorsProvider
import com.android.tools.adtui.common.TestAdtUiCursorsProvider
import com.android.tools.adtui.common.replaceAdtUiCursorWithPredefinedCursor
import com.android.tools.adtui.swing.FakeMouse
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.adtui.swing.findDescendant
import com.android.tools.adtui.swing.popup.ActionPopupMenuRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.DeviceMirroringSettings
import com.android.tools.idea.streaming.EmulatorSettings
import com.android.tools.idea.streaming.actions.FloatingXrToolbarState
import com.android.tools.idea.streaming.actions.HardwareInputStateStorage
import com.android.tools.idea.streaming.actions.PanAction
import com.android.tools.idea.streaming.actions.ToggleFloatingXrToolbarAction
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.core.DeviceDisplayListener
import com.android.tools.idea.streaming.core.DisplayType
import com.android.tools.idea.streaming.core.DisplayView
import com.android.tools.idea.streaming.core.FloatingToolbarContainer
import com.android.tools.idea.streaming.core.FloatingToolbarState
import com.android.tools.idea.streaming.core.ZoomType
import com.android.tools.idea.streaming.core.expandFloatingToolbar
import com.android.tools.idea.streaming.device.AndroidKeyEventActionType.ACTION_DOWN
import com.android.tools.idea.streaming.device.AndroidKeyEventActionType.ACTION_DOWN_AND_UP
import com.android.tools.idea.streaming.device.AndroidKeyEventActionType.ACTION_UP
import com.android.tools.idea.streaming.device.DeviceState.Property.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP
import com.android.tools.idea.streaming.device.FakeScreenSharingAgent.Companion.defaultControlMessageFilter
import com.android.tools.idea.streaming.device.FakeScreenSharingAgent.ControlMessageFilter
import com.android.tools.idea.streaming.device.FakeScreenSharingAgentRule.FakeDevice
import com.android.tools.idea.streaming.device.actions.DeviceFoldingAction
import com.android.tools.idea.streaming.device.xr.DeviceXrInputController
import com.android.tools.idea.streaming.xr.XrInputMode
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.override
import com.android.tools.idea.testing.registerServiceInstance
import com.android.tools.idea.ui.screenrecording.ScreenRecordingSupportedCache
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent.createEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestDataProvider
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.LayeredIcon
import com.intellij.ui.components.JBLabel
import icons.StudioIcons
import icons.StudioIcons.LayoutEditor.Toolbar.PAN_TOOL
import icons.StudioIcons.LayoutEditor.Toolbar.PAN_TOOL_SELECTED
import it.unimi.dsi.fastutil.bytes.ByteArrayList
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent.BUTTON3_DOWN_MASK
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.KEY_RELEASED
import java.awt.event.KeyEvent.VK_A
import java.awt.event.KeyEvent.VK_CONTROL
import java.awt.event.KeyEvent.VK_D
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_E
import java.awt.event.KeyEvent.VK_END
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_HOME
import java.awt.event.KeyEvent.VK_LEFT
import java.awt.event.KeyEvent.VK_P
import java.awt.event.KeyEvent.VK_PAGE_DOWN
import java.awt.event.KeyEvent.VK_PAGE_UP
import java.awt.event.KeyEvent.VK_Q
import java.awt.event.KeyEvent.VK_RIGHT
import java.awt.event.KeyEvent.VK_S
import java.awt.event.KeyEvent.VK_SHIFT
import java.awt.event.KeyEvent.VK_UP
import java.awt.event.KeyEvent.VK_W
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [DeviceToolWindowPanel], [DeviceDisplayPanel] and toolbar actions that produce Android key events. */
@RunsInEdt
class DeviceToolWindowPanelTest {
  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule() // Enable icon loading in a headless test environment.

    private val controlMessageFilter = ControlMessageFilter(DisplayConfigurationRequest.TYPE, SetMaxVideoResolutionMessage.TYPE)
  }

  private val agentRule = FakeScreenSharingAgentRule()
  private val popupRule = ActionPopupMenuRule()
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/DeviceToolWindowPanelTest/golden")

  @get:Rule
  val ruleChain =
    RuleChain(
      ApplicationRule(),
      ClipboardSynchronizationDisablementRule(),
      agentRule,
      popupRule,
      PortableUiFontRule(),
      goldenImageRule,
      EdtRule(),
    )

  private lateinit var device: FakeDevice
  private val panel: DeviceToolWindowPanel by lazy { createToolWindowPanel() }
  // Fake window is necessary for the toolbars to be rendered.
  private val fakeUi: FakeUi by lazy { FakeUi(panel, parentDisposable = testRootDisposable) }
  private val project
    get() = agentRule.project

  private val testRootDisposable
    get() = agentRule.disposable

  private val agent: FakeScreenSharingAgent
    get() = device.agent

  @Before
  fun setUp() {
    @Suppress("UnstableApiUsage")
    HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable) // Necessary to properly update toolbar button states.
    @Suppress("UnstableApiUsage")
    (DataManager.getInstance() as HeadlessDataManager).setTestDataProvider(TestDataProvider(project), testRootDisposable)
    val mockScreenRecordingCache = mock<ScreenRecordingSupportedCache>()
    whenever(mockScreenRecordingCache.isScreenRecordingSupported(any())).thenReturn(true)
    project.registerServiceInstance(ScreenRecordingSupportedCache::class.java, mockScreenRecordingCache, testRootDisposable)
  }

  @Test
  fun testAppearanceAndToolbarActions() {
    device = agentRule.connectDevice("Pixel 4", 31, Dimension(1080, 2280))
    assertThat(panel.primaryDisplayView).isNull()

    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()
    assertThat((panel.icon as LayeredIcon).getIcon(0)).isEqualTo(StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_PHONE)

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }

    // Check appearance.
    waitForFrame(5.seconds)
    assertAppearance(
      "AppearanceAndToolbarActions1",
      maxPercentDifferentLinux = 0.03,
      maxPercentDifferentMac = 0.06,
      maxPercentDifferentWindows = 0.06,
    )
    assertThat(panel.preferredFocusableComponent).isEqualTo(panel.primaryDisplayView)
    assertThat(panel.icon).isNotNull()

    // Check push button actions.
    val pushButtonCases =
      listOf(
        Pair("Power", AKEYCODE_POWER),
        Pair("Volume Up", AKEYCODE_VOLUME_UP),
        Pair("Volume Down", AKEYCODE_VOLUME_DOWN),
        Pair("Back", AKEYCODE_BACK),
        Pair("Home", AKEYCODE_HOME),
        Pair("Overview", AKEYCODE_APP_SWITCH),
      )
    for ((name, keycode) in pushButtonCases) {
      val button = fakeUi.getComponent<ActionButton> { it.action.templateText == name }
      fakeUi.mousePressOn(button)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN, keycode, 0, PRIMARY_DISPLAY_ID))
      fakeUi.mouseRelease()
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_UP, keycode, 0, PRIMARY_DISPLAY_ID))
    }

    // Check DevicePowerButtonAction invoked by a keyboard shortcut.
    var action = ActionManager.getInstance().getAction("android.device.power.button")
    var keyEvent = KeyEvent(panel, KEY_RELEASED, System.currentTimeMillis(), CTRL_DOWN_MASK, VK_P, KeyEvent.CHAR_UNDEFINED)
    val dataContext = DataManager.getInstance().getDataContext(panel.primaryDisplayView)
    executeAction(action, createEvent(action, dataContext, null, ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, keyEvent))
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN_AND_UP, AKEYCODE_POWER, 0, PRIMARY_DISPLAY_ID))

    // Check DevicePowerAndVolumeUpButtonAction invoked by a keyboard shortcut.
    action = ActionManager.getInstance().getAction("android.device.power.and.volume.up.button")
    keyEvent = KeyEvent(panel, KEY_RELEASED, System.currentTimeMillis(), CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_P, KeyEvent.CHAR_UNDEFINED)
    executeAction(action, createEvent(action, dataContext, null, ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, keyEvent))
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN, AKEYCODE_VOLUME_UP, 0, PRIMARY_DISPLAY_ID))
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN_AND_UP, AKEYCODE_POWER, 0, PRIMARY_DISPLAY_ID))
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_UP, AKEYCODE_VOLUME_UP, 0, PRIMARY_DISPLAY_ID))

    // Check that the Wear OS-specific buttons are hidden.
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Button 1" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Button 2" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Palm" }).isNull()

    panel.destroyContent()
    assertThat(panel.primaryDisplayView).isNull()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
  }

  @Test
  fun testWearToolbarActions() {
    device = agentRule.connectDevice("Pixel Watch", 30, Dimension(454, 454), deviceType = DeviceType.WEAR)
    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }

    // Check appearance.
    waitForFrame()
    assertThat(panel.preferredFocusableComponent).isEqualTo(panel.primaryDisplayView)
    assertThat((panel.icon as LayeredIcon).getIcon(0)).isEqualTo(StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_WEAR)

    fakeUi.updateToolbarsIfNecessary()
    // Check push button actions.
    val pushButtonCases = listOf(Pair("Button 1", AKEYCODE_STEM_PRIMARY), Pair("Button 2", AKEYCODE_POWER), Pair("Back", AKEYCODE_BACK))
    for ((name, keycode) in pushButtonCases) {
      val button = fakeUi.getComponent<ActionButton> { it.action.templateText == name }
      fakeUi.mousePressOn(button)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN, keycode, 0, PRIMARY_DISPLAY_ID))
      fakeUi.mouseRelease()
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_UP, keycode, 0, PRIMARY_DISPLAY_ID))
    }

    // Check keypress actions.
    val keypressCases = listOf(Pair("Palm", AKEYCODE_SLEEP))
    for ((name, keycode) in keypressCases) {
      val button = fakeUi.getComponent<ActionButton> { it.action.templateText == name }
      fakeUi.mouseClickOn(button)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN_AND_UP, keycode, 0, PRIMARY_DISPLAY_ID))
    }

    // Check that the buttons not applicable to Wear OS 3 are hidden.
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Power" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Volume Up" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Volume Down" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Rotate Left" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Rotate Right" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Home" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Overview" }).isNull()

    // Check that the actions not applicable to Wear OS 3 cannot be invoked by keyboard shortcuts.
    assertThat(updateAndGetActionPresentation("android.device.power.button", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.volume.up.button", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.volume.down.button", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.rotate.left", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.rotate.right", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.home.button", panel.primaryDisplayView!!, project).isEnabled).isFalse()
    assertThat(updateAndGetActionPresentation("android.device.overview.button", panel.primaryDisplayView!!, project).isEnabled).isFalse()

    panel.destroyContent()
    assertThat(panel.primaryDisplayView).isNull()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
  }

  @Test
  fun testXrToolbarActions() {
    StudioFlags.DEVICE_MIRRORING_XR_SIMULATED_PASSTHROUGH.overrideForTest(true, testRootDisposable)
    // Move XR buttons to the Running Devices toolbar to check its appearance.
    service<FloatingXrToolbarState>()::floatingXrToolbarEnabled.override(false, testRootDisposable)
    device = agentRule.connectDevice("XR Headset", 34, Dimension(2560, 2558), deviceType = DeviceType.XR_HEADSET)
    panel.createContent(false)
    val displayView = panel.primaryDisplayView ?: fail()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()
    fakeUi.updateToolbarsIfNecessary()

    // Check that the buttons not applicable to XR devices are hidden.
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Power" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Volume Up" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Volume Down" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Rotate Left" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Rotate Right" }).isNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Back" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Home" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Overview" }).isNotNull()

    // Check XR-specific actions.
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Reset View" }).isNotNull()
    assertThat(fakeUi.findComponent<ActionButton> { it.action.templateText == "Toggle Passthrough" }).isNotNull()

    val xrInputController = DeviceXrInputController.getInstance(project, panel.deviceClient)
    assertAppearance("XrToolbarActions1", maxPercentDifferentMac = 0.04, maxPercentDifferentWindows = 0.15)

    assertThat(xrInputController.inputMode).isEqualTo(XrInputMode.MOUSE)
    val modes =
      mapOf(
        "Interact with Apps" to XrInputMode.MOUSE,
        "View Direction" to XrInputMode.VIEW_DIRECTION,
        "Move Right/Left and Up/Down" to XrInputMode.LOCATION_IN_SPACE_XY,
        "Move Forward/Backward" to XrInputMode.LOCATION_IN_SPACE_Z,
      )
    for ((actionName, mode) in modes) {
      fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == actionName })
      assertThat(xrInputController.inputMode).isEqualTo(mode)
    }

    val button = fakeUi.getComponent<ActionButton> { it.action.templateText == "Home" }
    fakeUi.mousePressOn(button)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN, AKEYCODE_ALL_APPS, 0, PRIMARY_DISPLAY_ID))
    fakeUi.mouseRelease()
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_UP, AKEYCODE_ALL_APPS, 0, PRIMARY_DISPLAY_ID))

    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "Reset View" })
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrRecenterMessage())

    waitForCondition(2.seconds) { xrInputController.passthroughCoefficient >= 0 }
    assertThat(xrInputController.passthroughCoefficient).isEqualTo(0f)
    val togglePassthroughButton = fakeUi.getComponent<ActionButton> { it.action.templateText == "Toggle Passthrough" }
    assertThat(togglePassthroughButton.isEnabled).isTrue()
    assertThat(togglePassthroughButton.isSelected).isFalse()
    fakeUi.mouseClickOn(togglePassthroughButton)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrSetPassthroughCoefficientMessage(1F))
    waitForCondition(2.seconds) { xrInputController.passthroughCoefficient != 0f }
    assertThat(xrInputController.passthroughCoefficient).isEqualTo(1f)
    fakeUi.updateToolbarsIfNecessary()
    assertThat(togglePassthroughButton.isSelected).isTrue()

    val toggleAction = ToggleFloatingXrToolbarAction()
    toggleAction.actionPerformed(createTestEvent(displayView, project, ActionPlaces.TOOLWINDOW_POPUP))
    assertAppearance("XrToolbarActions2", maxPercentDifferentMac = 0.04, maxPercentDifferentWindows = 0.15)
  }

  @Test
  fun testXrKeyboardNavigation() {
    device = agentRule.connectDevice("XR Headset", 34, Dimension(2560, 2558), deviceType = DeviceType.XR_HEADSET)
    panel.createContent(false)
    val displayView = panel.primaryDisplayView ?: fail()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "View Direction" })

    val xrInputController = DeviceXrInputController.getInstance(project, displayView.deviceClient)
    assertThat(xrInputController.inputMode).isEqualTo(XrInputMode.VIEW_DIRECTION)
    assertThat(HardwareInputStateStorage.getInstance(project).isHardwareInputEnabled(displayView.deviceId)).isFalse()

    fakeUi.keyboard.setFocus(displayView)
    fakeUi.keyboard.press(VK_ENTER)
    // Keys that are not used for navigation produce keypress events.
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(KeyEventMessage(ACTION_DOWN_AND_UP, AKEYCODE_ENTER, 0))
    fakeUi.keyboard.release(VK_ENTER)

    val velocityKeys =
      mapOf(
        VK_W to XrVelocityMessage(0f, 0f, -1f),
        VK_A to XrVelocityMessage(-1f, 0f, 0f),
        VK_S to XrVelocityMessage(0f, 0f, 1f),
        VK_D to XrVelocityMessage(1f, 0f, 0f),
        VK_Q to XrVelocityMessage(0f, -1f, 0f),
        VK_E to XrVelocityMessage(0f, 1f, 0f),
      )
    for ((k, message) in velocityKeys) {
      fakeUi.keyboard.press(k)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(message)
      fakeUi.keyboard.release(k)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(0f, 0f, 0f))
    }

    // Expectations are represented by strings to avoid rounding problems.
    val angularVelocityKeys =
      mapOf(
        VK_RIGHT to "XrAngularVelocityMessage(x = 0.0, y = -0.5235988)",
        VK_LEFT to "XrAngularVelocityMessage(x = 0.0, y = 0.5235988)",
        VK_UP to "XrAngularVelocityMessage(x = 0.5235988, y = 0.0)",
        VK_DOWN to "XrAngularVelocityMessage(x = -0.5235988, y = 0.0)",
        VK_PAGE_UP to "XrAngularVelocityMessage(x = 0.5235988, y = -0.5235988)",
        VK_PAGE_DOWN to "XrAngularVelocityMessage(x = -0.5235988, y = -0.5235988)",
        VK_HOME to "XrAngularVelocityMessage(x = 0.5235988, y = 0.5235988)",
        VK_END to "XrAngularVelocityMessage(x = -0.5235988, y = 0.5235988)",
      )
    for ((k, message) in angularVelocityKeys) {
      fakeUi.keyboard.press(k)
      assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo(message)
      fakeUi.keyboard.release(k)
      assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrAngularVelocityMessage(0f, 0f))
    }

    // Two keys pressed together.
    fakeUi.keyboard.press(VK_D)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(1f, 0f, 0f))
    fakeUi.keyboard.press(VK_E)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(1f, 1f, 0f))
    fakeUi.keyboard.press(VK_A)
    // D and A cancel each other out.
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(0f, 1f, 0f))
    fakeUi.keyboard.release(VK_D)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(-1f, 1f, 0f))
    fakeUi.keyboard.press(VK_Q)
    // E and Q cancel each other out.
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(-1f, 0f, 0f))
    fakeUi.keyboard.release(VK_E)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(-1f, -1f, 0f))

    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "Interact with Apps" })
    // Switching to Interact with Apps resets state of the navigation keys.
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(XrVelocityMessage(0f, 0f, 0f))
    fakeUi.keyboard.release(VK_A)
    fakeUi.keyboard.release(VK_Q)
  }

  @Test
  fun testXrMouseViewRotation() {
    device = agentRule.connectDevice("XR Headset", 34, Dimension(2560, 2558), deviceType = DeviceType.XR_HEADSET)
    panel.createContent(false)
    val displayView = panel.primaryDisplayView ?: fail()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "View Direction" })

    val xrInputController = DeviceXrInputController.getInstance(project, displayView.deviceClient)
    assertThat(xrInputController.inputMode).isEqualTo(XrInputMode.VIEW_DIRECTION)

    fakeUi.mouse.press(50, 70)
    fakeUi.mouse.dragTo(200, 70)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrRotationMessage(x = 0.0, y = -1.77826)")
    fakeUi.mouse.dragTo(200, 200)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrRotationMessage(x = -1.5411587, y = 0.0)")
    fakeUi.mouse.dragTo(200, 10) // Exit the DeviceView component.
    fakeUi.mouse.dragTo(100, 70) // Enter the DeviceView component in a different location.
    fakeUi.mouse.dragTo(150, 200)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrRotationMessage(x = -1.5411587, y = -0.59275335)")
  }

  @Test
  fun testXrMouseMovementInSpace() {
    device = agentRule.connectDevice("XR Headset", 34, Dimension(2560, 2558), deviceType = DeviceType.XR_HEADSET)
    panel.createContent(false)
    val displayView = panel.primaryDisplayView ?: fail()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "Move Right/Left and Up/Down" })

    val xrInputController = DeviceXrInputController.getInstance(project, displayView.deviceClient)
    assertThat(xrInputController.inputMode).isEqualTo(XrInputMode.LOCATION_IN_SPACE_XY)

    fakeUi.mouse.press(50, 70)
    fakeUi.mouse.dragTo(200, 70)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = -2.264151, y = 0.0, z = 0.0)")
    fakeUi.mouse.dragTo(200, 200)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = 0.0, y = 1.9622642, z = 0.0)")
    fakeUi.mouse.dragTo(200, 10) // Exit the DeviceView component.
    fakeUi.mouse.dragTo(100, 70) // Enter the DeviceView component in a different location.
    fakeUi.mouse.dragTo(150, 200)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = -0.754717, y = 1.9622642, z = 0.0)")
    fakeUi.mouse.release()

    // Moving forward and backward by rotating the mouse wheel.
    fakeUi.mouse.wheel(10, 100, 1)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = 0.0, y = 0.0, z = 0.083333336)")
    fakeUi.mouse.wheel(10, 100, -3)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = 0.0, y = 0.0, z = -0.25)")

    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action.templateText == "Move Forward/Backward" })
    assertThat(xrInputController.inputMode).isEqualTo(XrInputMode.LOCATION_IN_SPACE_Z)
    assertThat(HardwareInputStateStorage.getInstance(project).isHardwareInputEnabled(displayView.deviceId)).isFalse()

    fakeUi.mouse.press(50, 70)
    fakeUi.mouse.dragTo(100, 200)
    assertThat(getNextControlMessageAndWaitForFrame().toString()).isEqualTo("XrTranslationMessage(x = 0.0, y = 0.0, z = 1.9622642)")
    fakeUi.mouse.release()
  }

  @Test
  @Suppress("OverrideOnly")
  fun testFolding() {
    device = agentRule.connectDevice("Pixel Fold", 33, Dimension(2208, 1840), foldedSize = Dimension(1080, 2092))

    panel.createContent(false)
    val deviceView = panel.primaryDisplayView!!

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }

    // Check appearance.
    waitForFrame()

    val foldingGroup = ActionManager.getInstance().getAction("android.device.postures") as ActionGroup
    val event = createTestEvent(deviceView, project, ActionPlaces.TOOLBAR)
    waitForCondition(2.seconds) { deviceView.deviceController?.currentFoldingState != null }
    waitForCondition(2.seconds) {
      foldingGroup.update(event)
      event.presentation.isVisible
    }
    assertThat(event.presentation.isEnabled).isTrue()
    assertThat(event.presentation.text).isEqualTo("Fold/Unfold (currently Open)")
    val foldingActions = foldingGroup.getChildren(event)
    assertThat(foldingActions)
      .asList()
      .containsExactly(
        DeviceFoldingAction(FoldingState(0, "Closed")),
        DeviceFoldingAction(FoldingState(1, "Tent")),
        DeviceFoldingAction(FoldingState(2, "Half-Open")),
        DeviceFoldingAction(FoldingState(3, "Half-Closed")),
        DeviceFoldingAction(FoldingState(4, "Open")),
        DeviceFoldingAction(FoldingState(5, "Rear Display Mode")),
        DeviceFoldingAction(FoldingState(6, "Dual Display Mode", setOf(PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP))),
        DeviceFoldingAction(FoldingState(7, "Rear Dual Mode", setOf(PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP))),
        DeviceFoldingAction(FoldingState(8, "Flipped")),
      )
    val disabledModes = setOf("Dual Display Mode", "Rear Dual Mode")
    for (action in foldingActions) {
      action.update(event)
      assertWithMessage("Unexpected enablement state of the ${action.templateText} action")
        .that(event.presentation.isEnabled)
        .isEqualTo(action.templateText !in disabledModes)
      assertWithMessage("Unexpected visibility of the ${action.templateText} action").that(event.presentation.isVisible).isTrue()
    }
    assertThat(deviceView.deviceDisplaySize).isEqualTo(Dimension(2208, 1840))

    val nextFrameNumber = panel.primaryDisplayView!!.frameNumber + 1u
    val closingAction = foldingActions[0]
    closingAction.actionPerformed(event)
    waitForCondition(2.seconds) {
      foldingGroup.update(event)
      event.presentation.text == "Fold/Unfold (currently Closed)"
    }
    waitForFrame(minFrameNumber = nextFrameNumber)
    assertThat(deviceView.deviceDisplaySize).isEqualTo(Dimension(1080, 2092))
  }

  @Test
  fun testMultipleDisplays() {
    device = agentRule.connectDevice("Pixel 7 Pro", 33, Dimension(1440, 3120))
    val displayIds = mutableSetOf<Int>()
    panel.addDeviceDisplayListener(
      object : DeviceDisplayListener {
        override fun displayAdded(displayView: DisplayView) {
          displayIds.add(displayView.displayId)
        }

        override fun displayRemoved(displayView: DisplayView) {
          displayIds.remove(displayView.displayId)
        }
      }
    )
    assertThat(panel.primaryDisplayView).isNull()
    assertThat(displayIds).isEmpty()

    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()
    assertThat(displayIds).containsExactly(PRIMARY_DISPLAY_ID)

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }

    waitForFrame()

    val secondaryDisplayId = 1
    agent.addDisplay(secondaryDisplayId, Dimension(1080, 1920), DisplayType.EXTERNAL)
    waitForCondition(2.seconds) { fakeUi.findAllComponents<DeviceView>().size == 2 }
    assertThat(displayIds).containsExactly(PRIMARY_DISPLAY_ID, secondaryDisplayId)
    waitForFrame(displayId = PRIMARY_DISPLAY_ID)
    waitForFrame(displayId = secondaryDisplayId)
    assertAppearance("MultipleDisplays1", maxPercentDifferentMac = 0.06, maxPercentDifferentWindows = 0.06)

    agent.clearCommandLog()
    // Rotating the device. Only the internal display should rotate.
    executeAction("android.device.rotate.left", panel.primaryDisplayView!!, project)
    assertThat(getNextControlMessageAndWaitForFrame()).isEqualTo(SetDeviceOrientationMessage(orientation = 1))
    waitForFrame(displayId = PRIMARY_DISPLAY_ID)
    waitForFrame(displayId = secondaryDisplayId)
    assertAppearance("MultipleDisplays2", maxPercentDifferentMac = 0.06, maxPercentDifferentWindows = 0.06)

    agent.removeDisplay(secondaryDisplayId)
    waitForCondition(2.seconds) { fakeUi.findAllComponents<DeviceView>().size == 1 }
    assertThat(displayIds).containsExactly(PRIMARY_DISPLAY_ID)

    panel.destroyContent()
    assertThat(displayIds).isEmpty()
  }

  @Test
  fun testZoom() {
    device = agentRule.connectDevice("Pixel 4", 30, Dimension(1080, 2280))
    assertThat(panel.primaryDisplayView).isNull()

    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }

    waitForFrame()

    var deviceView = panel.primaryDisplayView!!
    // Zoom in.
    deviceView.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    assertThat(deviceView.scale).isWithin(0.0001).of(0.125)
    assertThat(deviceView.preferredSize).isEqualTo(Dimension(135, 285))
    val viewport = deviceView.parent as JViewport
    assertThat(viewport.viewSize).isEqualTo(Dimension(300, 285))
    // Scroll to the bottom.
    val scrollPosition = Point(viewport.viewPosition.x, viewport.viewSize.height - viewport.height)
    viewport.viewPosition = scrollPosition

    // Recreate panel content.
    val uiState = panel.destroyContent()
    assertThat(panel.primaryDisplayView).isNull()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
    panel.createContent(false, uiState)
    assertThat(panel.primaryDisplayView).isNotNull()
    deviceView = panel.primaryDisplayView!!
    fakeUi.layoutAndDispatchEvents()
    waitForCondition(5.seconds) { agent.videoStreamActive && panel.isConnected }
    waitForFrame()

    // Check that zoom level and scroll position are restored.
    assertThat(deviceView.scale).isWithin(0.0001).of(0.125)
    assertThat(viewport.viewSize).isEqualTo(Dimension(300, 285))
    assertThat(viewport.viewPosition).isEqualTo(scrollPosition)

    panel.destroyContent()
    assertThat(panel.primaryDisplayView).isNull()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
  }

  @Test
  fun testAudio() {
    DeviceMirroringSettings.getInstance()::redirectAudio.override(true, testRootDisposable)
    val testDataLine = TestDataLine()
    val testAudioSystemService =
      object : AudioSystemService() {
        override fun getSourceDataLine(audioFormat: AudioFormat): SourceDataLine = testDataLine
      }
    ApplicationManager.getApplication().replaceService(AudioSystemService::class.java, testAudioSystemService, testRootDisposable)

    device = agentRule.connectDevice("Pixel 7 Pro", 33, Dimension(1440, 3120))
    assertThat(panel.primaryDisplayView).isNull()

    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    val frequencyHz = 440.0
    val durationMillis = 500
    // The 5% of extra sound duration is intended to make sure that there is enough data to satisfy
    // the following wait condition.
    runBlocking { agent.beep(frequencyHz, durationMillis * 105 / 100) }
    waitForCondition(5.seconds) {
      testDataLine.dataSize >= AUDIO_SAMPLE_RATE * AUDIO_CHANNEL_COUNT * AUDIO_BYTES_PER_SAMPLE_FMT_S16 * durationMillis / 1000
    }
    val buf = testDataLine.dataAsByteBuffer()
    var volumeReached = false
    var previousValue = 0.0
    var start = Double.NaN
    for (i in 0 until buf.limit() / (AUDIO_CHANNEL_COUNT * AUDIO_BYTES_PER_SAMPLE_FMT_S16)) {
      for (channel in 1..AUDIO_CHANNEL_COUNT) {
        val v = buf.getShort().toDouble()
        when {
          start.isFinite() && i * 1000 / AUDIO_SAMPLE_RATE < durationMillis -> {
            val expected = sin((i - start) * 2 * PI * frequencyHz / AUDIO_SAMPLE_RATE) * Short.MAX_VALUE
            assertEquals(
              expected,
              v,
              Short.MAX_VALUE * 0.03,
              "Unexpected signal value in channel $channel at ${i * 1000.0 / AUDIO_SAMPLE_RATE} ms",
            )
          }
          volumeReached -> {
            if (channel == 1 && v >= 0 && previousValue < 0) {
              start = i - v / (v - previousValue)
            }
            previousValue = v
          }
          else -> {
            if (channel == 1 && v <= Short.MIN_VALUE * 0.99) {
              volumeReached = true
            }
          }
        }
      }
    }
  }

  @Test
  fun testAudioEnablementDisablement() {
    val testDataLine = TestDataLine()
    val testAudioSystemService =
      object : AudioSystemService() {
        override fun getSourceDataLine(audioFormat: AudioFormat): SourceDataLine = testDataLine
      }
    ApplicationManager.getApplication().replaceService(AudioSystemService::class.java, testAudioSystemService, testRootDisposable)

    device = agentRule.connectDevice("Pixel 7 Pro", 33, Dimension(1440, 3120))
    assertThat(panel.primaryDisplayView).isNull()

    panel.createContent(false)
    assertThat(panel.primaryDisplayView).isNotNull()

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    DeviceMirroringSettings.getInstance()::redirectAudio.override(true, testRootDisposable)
    val filter = defaultControlMessageFilter.or(SetMaxVideoResolutionMessage.TYPE)
    assertThat(agent.getNextControlMessage(1.seconds, filter)).isEqualTo(StartAudioStreamMessage())
    waitForCondition(1.seconds) { agent.audioStreamActive }

    DeviceMirroringSettings.getInstance().redirectAudio = false
    assertThat(agent.getNextControlMessage(1.seconds, filter)).isEqualTo(StopAudioStreamMessage())
    waitForCondition(1.seconds) { !agent.audioStreamActive }
  }

  @Test
  fun testFloatingToolbarOrientation() {
    val toolbarState = FloatingToolbarState.getInstance()
    toolbarState::zoomToolbarHorizontal.override(false, testRootDisposable)
    toolbarState::xrToolbarHorizontal.override(false, testRootDisposable)
    device = agentRule.connectDevice("Pixel 4", 31, Dimension(1080, 2280))
    panel.createContent(false)
    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()
    fakeUi.layoutAndDispatchEvents()

    val zoomToolbarVertical = fakeUi.getComponent<FloatingToolbarContainer>()
    assertThat(zoomToolbarVertical).isNotNull()
    assertThat(zoomToolbarVertical.width).isLessThan(zoomToolbarVertical.height)

    // Expand the toolbar and right-click on the collapser button to switch to horizontal orientation.
    fakeUi.expandFloatingToolbar()
    val collapserButtonVertical = fakeUi.getComponent<ActionButton> { it.action is FloatingToolbarContainer.CollapserAction }
    val posVertical = fakeUi.getPosition(collapserButtonVertical)
    fakeUi.mouse.rightClick(posVertical.x, posVertical.y)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val popupActions = popupRule.lastPopupActions
    assertThat(popupActions.map { it.templateText }).containsExactly("Vertical", "Horizontal").inOrder()

    executeAction(popupActions.first { it.templateText == "Horizontal" }, collapserButtonVertical)
    fakeUi.layoutAndDispatchEvents()
    assertThat(toolbarState.zoomToolbarHorizontal).isTrue()
    assertThat(toolbarState.xrToolbarHorizontal).isFalse()

    val zoomToolbarHorizontal = fakeUi.getComponent<FloatingToolbarContainer>()
    assertThat(zoomToolbarHorizontal.width).isGreaterThan(zoomToolbarHorizontal.height)
    assertThat(zoomToolbarHorizontal.isActive).isTrue()

    // Right-click on the collapser button again and switch back to vertical orientation.
    val collapserButtonHorizontal = fakeUi.getComponent<ActionButton> { it.action is FloatingToolbarContainer.CollapserAction }
    val posHorizontal = fakeUi.getPosition(collapserButtonHorizontal)
    fakeUi.mouse.rightClick(posHorizontal.x, posHorizontal.y)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    executeAction(popupRule.lastPopupActions.first { it.templateText == "Vertical" }, collapserButtonHorizontal)
    fakeUi.layoutAndDispatchEvents()
    assertThat(toolbarState.zoomToolbarHorizontal).isFalse()

    val zoomToolbarBackToVertical = fakeUi.getComponent<FloatingToolbarContainer>()
    assertThat(zoomToolbarBackToVertical.height).isGreaterThan(zoomToolbarBackToVertical.width)
  }

  @Test
  fun testProtectedContentPopup() {
    device = agentRule.connectDevice("Pixel 8", 34, Dimension(1080, 2400))
    panel.createContent(false)

    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame(5.seconds)

    val displayPanel = fakeUi.getComponent<DeviceDisplayPanel>()
    val deviceController = panel.primaryDisplayView!!.deviceController!!
    val protectedContentPopup = fakeUi.getComponent<JBLabel> { it.text == "Sensitive content hidden" }.parent
    assertThat(protectedContentPopup.isVisible).isFalse()

    // Lock screen is active, but the upper 80% of the display is not black -> popup stays hidden.
    agent.lockScreenActive = true
    val unlockedCheckFrame = agent.getFrameNumber(PRIMARY_DISPLAY_ID)
    waitForCondition(2.seconds) { deviceController.isLockScreenActive && displayPanel.lastCheckedFrameNumber >= unlockedCheckFrame }
    assertThat(protectedContentPopup.isVisible).isFalse()

    // Black frame while lock screen is active -> popup becomes visible.
    runBlocking { agent.renderBlackScreen(PRIMARY_DISPLAY_ID) }
    waitForFrame(minFrameNumber = agent.getFrameNumber(PRIMARY_DISPLAY_ID))
    waitForCondition(2.seconds) { protectedContentPopup.isVisible }

    // Non-black content extending into the upper 80% -> popup hides.
    runBlocking { agent.renderBlackScreen(PRIMARY_DISPLAY_ID, nonBlackBottomFraction = 0.25) }
    waitForFrame(minFrameNumber = agent.getFrameNumber(PRIMARY_DISPLAY_ID))
    waitForCondition(2.seconds) { !protectedContentPopup.isVisible }

    // Non-black content strictly below the upper 80% -> popup becomes visible again.
    runBlocking { agent.renderBlackScreen(PRIMARY_DISPLAY_ID, nonBlackBottomFraction = 0.15) }
    val bottomStripFrame = agent.getFrameNumber(PRIMARY_DISPLAY_ID)
    waitForFrame(minFrameNumber = bottomStripFrame)
    waitForCondition(2.seconds) { displayPanel.lastCheckedFrameNumber >= bottomStripFrame && protectedContentPopup.isVisible }

    // Lock screen becomes inactive while display is still black -> popup hides.
    agent.lockScreenActive = false
    waitForCondition(2.seconds) { !protectedContentPopup.isVisible }

    // Verify popup is shown when lock screen is active before connecting and a black frame arrives, and hidden on crash/disconnect.
    panel.destroyContent()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
    runBlocking { agent.stop() }
    waitForCondition(2.seconds) { !agent.isRunning }

    agent.lockScreenActive = true
    panel.createContent(false)
    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame(5.seconds)
    val displayPanel2 = fakeUi.getComponent<DeviceDisplayPanel>()
    val deviceController2 = panel.primaryDisplayView!!.deviceController!!
    val protectedContentPopup2 = fakeUi.getComponent<JBLabel> { it.text == "Sensitive content hidden" }.parent
    val initialFrame2 = agent.getFrameNumber(PRIMARY_DISPLAY_ID)
    waitForCondition(2.seconds) { deviceController2.isLockScreenActive && displayPanel2.lastCheckedFrameNumber >= initialFrame2 }
    assertThat(protectedContentPopup2.isVisible).isFalse()

    // Encoded black frame while lock screen is active -> popup becomes visible.
    runBlocking { agent.renderBlackScreen(PRIMARY_DISPLAY_ID) }
    waitForFrame(minFrameNumber = agent.getFrameNumber(PRIMARY_DISPLAY_ID))
    waitForCondition(2.seconds) { protectedContentPopup2.isVisible }

    // Empty video frame (screen off) while lock screen is active -> popup hides.
    runBlocking { agent.produceEmptyVideoFrame(PRIMARY_DISPLAY_ID) }
    waitForFrame(minFrameNumber = agent.getFrameNumber(PRIMARY_DISPLAY_ID))
    waitForCondition(2.seconds) { !protectedContentPopup2.isVisible }

    // Encoded black frame again -> popup becomes visible, and hides on crash/disconnect.
    runBlocking { agent.renderBlackScreen(PRIMARY_DISPLAY_ID) }
    waitForFrame(minFrameNumber = agent.getFrameNumber(PRIMARY_DISPLAY_ID))
    waitForCondition(2.seconds) { protectedContentPopup2.isVisible }

    runBlocking { agent.crash() }
    waitForCondition(2.seconds) { !protectedContentPopup2.isVisible }
  }

  @Test
  fun testNotificationsAddedAndRemovedWithoutContent() {
    device = agentRule.connectDevice("Pixel 4", 31, Dimension(1080, 2280))
    val notification = EditorNotificationPanel()

    panel.addNotification(notification)
    assertThat(SwingUtilities.isDescendingFrom(notification, panel)).isTrue()

    panel.createContent(false)
    panel.destroyContent()
    assertThat(SwingUtilities.isDescendingFrom(notification, panel)).isTrue()

    panel.removeNotification(notification)
    assertThat(SwingUtilities.isDescendingFrom(notification, panel)).isFalse()
    waitForCondition(2.seconds) { !agent.videoStreamActive }
  }

  @Test
  fun testPanMode() {
    StudioFlags.RUNNING_DEVICES_CONTEXT_MENU.overrideForTest(true, testRootDisposable)
    EmulatorSettings.getInstance()::contextMenuAdvertisementShown.override(true, testRootDisposable)
    @Suppress("UnstableApiUsage") UISettings.getInstance().state::animatedScrolling.override(false, testRootDisposable)
    val toolbarState = FloatingToolbarState.getInstance()
    toolbarState::zoomToolbarHorizontal.override(false, testRootDisposable)
    ApplicationManager.getApplication().replaceService(AdtUiCursorsProvider::class.java, TestAdtUiCursorsProvider(), testRootDisposable)
    val grabCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    val grabbingCursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.GRAB, grabCursor)
    replaceAdtUiCursorWithPredefinedCursor(AdtUiCursorType.GRABBING, grabbingCursor)

    device = agentRule.connectDevice("Pixel 4", 30, Dimension(1080, 2280))
    panel.createContent(false)
    val deviceView = panel.primaryDisplayView!!
    fakeUi.layoutAndDispatchEvents()
    waitForCondition(10.seconds) { agent.isRunning && panel.isConnected }
    waitForFrame()

    // Initially the image fits in the window without scrollbars, so the Pan button is not shown.
    assertThat(deviceView.isPannable).isFalse()
    fakeUi.expandFloatingToolbar()
    fakeUi.updateToolbarsIfNecessary()
    assertThat(fakeUi.findComponent<ActionButton> { it.action is PanAction }).isNull()

    // Zoom to actual size so both scrollbars become visible.
    deviceView.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render() // Update projectionRectangle for the new size.
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    assertThat(deviceView.isPannable).isTrue()

    val zoomToolbarVertical = fakeUi.getComponent<FloatingToolbarContainer>()
    val panToolbarPanelVertical = zoomToolbarVertical.getComponent(0)
    val zoomToolbarPanelVertical = zoomToolbarVertical.getComponent(1)
    val panButtonVertical = fakeUi.getComponent<ActionButton> { it.action is PanAction }
    assertThat(panButtonVertical.presentation.icon).isEqualTo(PAN_TOOL)
    // Pan button is at the top of the vertical Zoom toolbar separated by a gap.
    assertThat(zoomToolbarPanelVertical.y - (panToolbarPanelVertical.y + panToolbarPanelVertical.height)).isGreaterThan(0)

    // When the Zoom toolbar is collapsed and Pan mode is off, the Pan button is hidden.
    zoomToolbarVertical.toggleActiveState()
    waitForCondition(1.seconds) { zoomToolbarVertical.activationFactor == 0.0 }
    fakeUi.layoutAndDispatchEvents()
    assertThat(panToolbarPanelVertical.height).isEqualTo(0)

    // Expand the Zoom toolbar and click the Pan button to enable Pan mode.
    fakeUi.expandFloatingToolbar()
    fakeUi.mouseClickOn(panButtonVertical)
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    assertThat(deviceView.isPanning).isTrue()
    assertThat(panButtonVertical.presentation.icon).isEqualTo(PAN_TOOL_SELECTED)

    // When Pan mode is active, the Pan button stays visible even when the Zoom toolbar is collapsed.
    zoomToolbarVertical.toggleActiveState()
    waitForCondition(1.seconds) { zoomToolbarVertical.activationFactor == 0.0 }
    fakeUi.layoutAndDispatchEvents()
    assertThat(panToolbarPanelVertical.height).isGreaterThan(0)
    assertThat(zoomToolbarPanelVertical.y - (panToolbarPanelVertical.y + panToolbarPanelVertical.height)).isGreaterThan(0)

    // Switch to horizontal toolbar orientation: Pan button is on the left of the Zoom toolbar separated by a gap.
    toolbarState.zoomToolbarHorizontal = true
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    val zoomToolbarHorizontal = fakeUi.getComponent<FloatingToolbarContainer>()
    val panToolbarPanelHorizontal = zoomToolbarHorizontal.getComponent(0)
    val zoomToolbarPanelHorizontal = zoomToolbarHorizontal.getComponent(1)
    assertThat(panToolbarPanelHorizontal.width).isGreaterThan(0)
    assertThat(zoomToolbarPanelHorizontal.x - (panToolbarPanelHorizontal.x + panToolbarPanelHorizontal.width)).isGreaterThan(0)

    // Verify cursors and viewport dragging in Pan mode without sending events to the device.
    val viewport = deviceView.parent as JViewport
    val initialPosition = viewport.viewPosition
    assertThat(panel.findComponentAt(100, 100)?.cursor).isEqualTo(grabCursor)

    fakeUi.mouse.press(150, 150)
    assertThat(panel.findComponentAt(150, 150)?.cursor).isEqualTo(grabbingCursor)
    fakeUi.mouse.dragTo(110, 90)
    assertThat(viewport.viewPosition).isEqualTo(Point(initialPosition.x + 40, initialPosition.y + 60))
    fakeUi.mouse.release()
    assertThat(panel.findComponentAt(110, 90)?.cursor).isEqualTo(grabCursor)

    // Middle-button drag also pans the viewport.
    val posAfterLeftDrag = viewport.viewPosition
    fakeUi.mouse.press(110, 90, FakeMouse.Button.MIDDLE)
    assertThat(panel.findComponentAt(110, 90)?.cursor).isEqualTo(grabbingCursor)
    fakeUi.mouse.dragTo(130, 100)
    assertThat(viewport.viewPosition).isEqualTo(Point(posAfterLeftDrag.x - 20, posAfterLeftDrag.y - 10))
    fakeUi.mouse.release()
    assertThat(panel.findComponentAt(130, 100)?.cursor).isEqualTo(grabCursor)

    // Mouse wheel (vertical and Shift+horizontal) in Pan mode scrolls the viewport instead of sending scroll events to the device.
    // Space event timestamps past JBScrollPane's 200ms axis-latching window (idea.latching.scrolling.time).
    val now = System.currentTimeMillis()
    val posAfterDrag = viewport.viewPosition
    fakeUi.mouse.wheel(110, 90, 3, timestamp = now - 1000)
    waitForCondition(1.seconds) { viewport.viewPosition.y > posAfterDrag.y }
    val posAfterVerticalWheel = viewport.viewPosition
    fakeUi.keyboard.press(VK_SHIFT)
    fakeUi.mouse.wheel(110, 90, 3, timestamp = now - 500)
    waitForCondition(1.seconds) { viewport.viewPosition.x > posAfterVerticalWheel.x }
    fakeUi.keyboard.release(VK_SHIFT)

    // Wheel scrolling or zooming mid-drag re-anchors cleanly without snapping back on subsequent drag events.
    val posBeforeMidDrag = viewport.viewPosition
    fakeUi.mouse.press(150, 180)
    fakeUi.mouse.dragTo(140, 170)
    assertThat(viewport.viewPosition).isEqualTo(Point(posBeforeMidDrag.x + 10, posBeforeMidDrag.y + 10))
    val posBeforeMidDragWheel = viewport.viewPosition
    fakeUi.mouse.wheel(140, 170, 3)
    assertThat(viewport.viewPosition.y).isGreaterThan(posBeforeMidDragWheel.y)
    val posAfterMidDragWheel = viewport.viewPosition
    fakeUi.mouse.dragTo(135, 160)
    assertThat(viewport.viewPosition).isEqualTo(Point(posAfterMidDragWheel.x + 5, posAfterMidDragWheel.y + 10))
    deviceView.zoom(ZoomType.IN)
    fakeUi.layoutAndDispatchEvents()
    val posAfterMidDragZoom = viewport.viewPosition
    fakeUi.mouse.dragTo(125, 145)
    assertThat(viewport.viewPosition).isEqualTo(Point(posAfterMidDragZoom.x + 10, posAfterMidDragZoom.y + 15))
    fakeUi.mouse.release()
    deviceView.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()

    // Vertical wheel scrolling mid-drag preserves clamped horizontal overshoot.
    viewport.viewPosition = Point(0, 50)
    fakeUi.mouse.press(100, 180)
    fakeUi.mouse.dragTo(130, 180) // Overshoot past left edge by 30px (clamped to x = 0).
    assertThat(viewport.viewPosition.x).isEqualTo(0)
    val yBeforeEdgeWheel = viewport.viewPosition.y
    fakeUi.mouse.wheel(130, 180, 3)
    assertThat(viewport.viewPosition.y).isGreaterThan(yBeforeEdgeWheel)
    fakeUi.mouse.dragTo(115, 180) // Still within the 30px overshoot, so x stays at 0.
    assertThat(viewport.viewPosition.x).isEqualTo(0)
    fakeUi.mouse.dragTo(90, 180) // 10px past original grab point (100), so x scrolls to 10.
    assertThat(viewport.viewPosition.x).isEqualTo(10)
    fakeUi.mouse.release()

    // Child overlays added inside displayView (such as Embedded Layout Inspector) do not intercept Pan mode events or cursors.
    var childOverlayEvents = 0
    val childOverlay =
      object : JComponent() {
        init {
          cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
          val listener =
            object : MouseAdapter() {
              override fun mousePressed(event: MouseEvent) {
                childOverlayEvents++
              }

              override fun mouseClicked(event: MouseEvent) {
                childOverlayEvents++
              }

              override fun mouseDragged(event: MouseEvent) {
                childOverlayEvents++
              }

              override fun mouseWheelMoved(event: MouseWheelEvent) {
                childOverlayEvents++
              }
            }
          addMouseListener(listener)
          addMouseMotionListener(listener)
          addMouseWheelListener(listener)
        }
      }
    deviceView.add(childOverlay)
    fakeUi.layoutAndDispatchEvents()
    val posBeforeOverlayDrag = viewport.viewPosition
    assertThat(panel.findComponentAt(110, 90)?.cursor).isEqualTo(grabCursor)
    fakeUi.mouse.press(110, 90)
    assertThat(panel.findComponentAt(110, 90)?.cursor).isEqualTo(grabbingCursor)
    fakeUi.mouse.dragTo(100, 80)
    assertThat(viewport.viewPosition).isEqualTo(Point(posBeforeOverlayDrag.x + 10, posBeforeOverlayDrag.y + 10))
    fakeUi.mouse.release()
    assertThat(panel.findComponentAt(100, 80)?.cursor).isEqualTo(grabCursor)
    val posAfterOverlayDrag = viewport.viewPosition
    fakeUi.mouse.wheel(100, 80, 2)
    waitForCondition(1.seconds) { viewport.viewPosition.y > posAfterOverlayDrag.y }
    assertThat(childOverlayEvents).isEqualTo(0)
    deviceView.remove(childOverlay)
    fakeUi.layoutAndDispatchEvents()

    // Right-clicking in Pan mode invokes the IDE context menu even when rightClicksAreSentToDevice is true.
    deviceView.rightClicksAreSentToDevice = true
    fakeUi.mouse.rightClick(110, 90)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(popupRule.lastPopupActions).isNotEmpty()
    deviceView.rightClicksAreSentToDevice = false

    // Toggle Pan mode off by clicking the Pan button while collapsed.
    val panButtonHorizontal = fakeUi.getComponent<ActionButton> { it.action is PanAction }
    fakeUi.mouseClickOn(panButtonHorizontal)
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    assertThat(deviceView.isPanning).isFalse()
    assertThat(panToolbarPanelHorizontal.width).isEqualTo(0)
    assertThat(panel.findComponentAt(110, 90)?.cursor).isEqualTo(Cursor.getDefaultCursor())

    // Clicking on the display now sends hover + touch events to the device; no earlier pan mouse events were sent.
    fakeUi.mouse.click(100, 100)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_ENTER)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_MOVE)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_EXIT)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_DOWN)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_UP)

    // Windows-style right-click (popup trigger on release) clears BUTTON3 state so Ctrl multi-touch is not blocked afterward.
    val rightClickViewPt = SwingUtilities.convertPoint(panel, Point(105, 105), deviceView)
    val rightClickTime = System.currentTimeMillis()
    deviceView.dispatchEvent(
      MouseEvent(
        deviceView,
        MouseEvent.MOUSE_PRESSED,
        rightClickTime,
        BUTTON3_DOWN_MASK,
        rightClickViewPt.x,
        rightClickViewPt.y,
        1,
        false,
        MouseEvent.BUTTON3,
      )
    )
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_DOWN)
    deviceView.dispatchEvent(
      MouseEvent(
        deviceView,
        MouseEvent.MOUSE_RELEASED,
        rightClickTime,
        0,
        rightClickViewPt.x,
        rightClickViewPt.y,
        1,
        true,
        MouseEvent.BUTTON3,
      )
    )
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_UP)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(popupRule.lastPopupActions).isNotEmpty()
    fakeUi.keyboard.setFocus(deviceView)
    fakeUi.keyboard.press(VK_CONTROL)
    fakeUi.mouse.press(105, 105)
    val multiTouchAfterPopup = agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage
    assertThat(multiTouchAfterPopup.action).isEqualTo(MotionEventMessage.ACTION_DOWN)
    assertThat(multiTouchAfterPopup.pointers).hasSize(2)
    fakeUi.mouse.release()
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_UP)
    fakeUi.keyboard.release(VK_CONTROL)

    // Entering Pan mode while hovering sends ACTION_HOVER_EXIT; entering while holding button 2 sends ACTION_UP.
    fakeUi.mouse.moveTo(105, 105)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_ENTER)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_MOVE)
    deviceView.isPanning = true
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_HOVER_EXIT)
    deviceView.isPanning = false

    fakeUi.mouse.press(105, 105, FakeMouse.Button.MIDDLE)
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_DOWN)
    deviceView.isPanning = true
    assertThat((agent.getNextControlMessage(2.seconds, filter = controlMessageFilter) as MotionEventMessage).action)
      .isEqualTo(MotionEventMessage.ACTION_UP)
    fakeUi.mouse.release()

    // Hiding the Zoom toolbar clears Pan mode.
    assertThat(deviceView.isPanning).isTrue()
    panel.zoomToolbarVisible = false
    assertThat(deviceView.isPanning).isFalse()
    panel.zoomToolbarVisible = true

    // Re-enable Pan mode, then zoom to fit: Pan mode automatically turns off and the Pan button disappears.
    fakeUi.expandFloatingToolbar()
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.mouseClickOn(fakeUi.getComponent<ActionButton> { it.action is PanAction })
    assertThat(deviceView.isPanning).isTrue()

    deviceView.zoom(ZoomType.FIT)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.updateToolbarsIfNecessary()
    assertThat(deviceView.isPannable).isFalse()
    assertThat(deviceView.isPanning).isFalse()
    assertThat(panel.findComponentAt(100, 100)?.cursor).isEqualTo(Cursor.getDefaultCursor())
    assertThat(fakeUi.findComponent<ActionButton> { it.action is PanAction }).isNull()

    // Switching from vertical-only to horizontal-only scrollbar in one layout pass keeps Pan mode active,
    // while collapsing the scroll pane to empty bounds clears Pan mode even though scrollbars stay marked visible.
    val savedPanelSize = panel.size
    val scrollPane = viewport.parent as JScrollPane
    panel.size = Dimension(1500, 600)
    fakeUi.layoutAndDispatchEvents()
    deviceView.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    assertThat(scrollPane.verticalScrollBar.isVisible).isTrue()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isFalse()
    deviceView.isPanning = true
    assertThat(deviceView.isPanning).isTrue()

    panel.size = Dimension(600, 3000)
    fakeUi.layoutAndDispatchEvents()
    assertThat(scrollPane.verticalScrollBar.isVisible).isFalse()
    assertThat(scrollPane.horizontalScrollBar.isVisible).isTrue()
    assertThat(deviceView.isPannable).isTrue()
    assertThat(deviceView.isPanning).isTrue()

    panel.size = Dimension(0, 0)
    fakeUi.layoutAndDispatchEvents()
    assertThat(scrollPane.width == 0 || scrollPane.height == 0).isTrue()
    assertThat(deviceView.isPannable).isFalse()
    assertThat(deviceView.isPanning).isFalse()

    panel.size = savedPanelSize
    fakeUi.layoutAndDispatchEvents()

    // Panning works independently on a secondary display.
    val secondaryDisplayId = 1
    agent.addDisplay(secondaryDisplayId, Dimension(1080, 1920), DisplayType.EXTERNAL)
    waitForCondition(2.seconds) { panel.findDisplayView(secondaryDisplayId) != null }
    waitForFrame(displayId = secondaryDisplayId)
    val secondaryView = panel.findDisplayView(secondaryDisplayId)!!
    secondaryView.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()
    fakeUi.updateToolbarsIfNecessary()
    assertThat(secondaryView.isPannable).isTrue()
    executeAction(PanAction.ACTION_ID, secondaryView, project)
    assertThat(secondaryView.isPanning).isTrue()
    assertThat(deviceView.isPanning).isFalse()
    val secondaryViewport = secondaryView.parent as JViewport
    val secondaryViewportPosInPanel = fakeUi.getPosition(secondaryViewport)
    val secondaryInitialViewPos = secondaryViewport.viewPosition
    val dragX = secondaryViewportPosInPanel.x + secondaryViewport.width / 2
    val dragY = secondaryViewportPosInPanel.y + secondaryViewport.height / 2
    fakeUi.mouse.drag(dragX, dragY, -25, -35)
    assertThat(secondaryViewport.viewPosition).isEqualTo(Point(secondaryInitialViewPos.x + 25, secondaryInitialViewPos.y + 35))
    agent.removeDisplay(secondaryDisplayId)
    waitForCondition(2.seconds) { panel.findDisplayView(secondaryDisplayId) == null }

    // Disconnecting the agent clears Pan mode.
    deviceView.zoom(ZoomType.ACTUAL)
    fakeUi.layoutAndDispatchEvents()
    fakeUi.render()
    deviceView.isPanning = true
    assertThat(deviceView.isPanning).isTrue()
    runBlocking { agent.stop() }
    waitForCondition(2.seconds) { !panel.isConnected }
    assertThat(deviceView.isPanning).isFalse()

    panel.destroyContent()
  }

  private fun FakeUi.mousePressOn(component: Component) {
    val location: Point = getPosition(component)
    mouse.press(location.x, location.y)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // Allow events to propagate.
  }

  private fun FakeUi.mouseRelease() {
    mouse.release()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // Allow events to propagate.
  }

  private fun FakeUi.mouseClickOn(component: Component) {
    val location: Point = getPosition(component)
    mouse.click(location.x, location.y)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // Allow events to propagate.
  }

  private fun createToolWindowPanel(): DeviceToolWindowPanel {
    val deviceClient = DeviceClient(device.handle.id, device.serialNumber, device.configuration, device.deviceState.cpuAbi)
    Disposer.register(testRootDisposable, deviceClient)
    val panel = DeviceToolWindowPanel(testRootDisposable, project, device.handle, deviceClient)
    panel.size = Dimension(310, 300)
    panel.zoomToolbarVisible = true
    return panel
  }

  private fun getNextControlMessageAndWaitForFrame(displayId: Int = PRIMARY_DISPLAY_ID): ControlMessage {
    val message = agent.getNextControlMessage(2.seconds, filter = controlMessageFilter)
    waitForFrame(displayId = displayId)
    return message
  }

  /** Waits for all video frames to be received after the given one. */
  private fun waitForFrame(timeout: Duration = 2.seconds, displayId: Int = PRIMARY_DISPLAY_ID, minFrameNumber: UInt = 1u) {
    waitForCondition(timeout) {
      panel.isConnected &&
        agent.getFrameNumber(displayId) >= minFrameNumber &&
        renderAndGetFrameNumber(displayId) == agent.getFrameNumber(displayId)
    }
  }

  private fun renderAndGetFrameNumber(displayId: Int = PRIMARY_DISPLAY_ID): UInt {
    fakeUi.render() // The frame number may get updated as a result of rendering.
    return panel.findDisplayView(displayId)!!.frameNumber
  }

  @Suppress("SameParameterValue")
  private fun assertAppearance(
    goldenImageName: String,
    maxPercentDifferentLinux: Double = 0.0003,
    maxPercentDifferentMac: Double = 0.0003,
    maxPercentDifferentWindows: Double = 0.0003,
  ) {
    fakeUi.updateToolbarsIfNecessary()
    val maxPercentDifferent =
      when {
        SystemInfo.isMac -> maxPercentDifferentMac
        SystemInfo.isWindows -> maxPercentDifferentWindows
        else -> maxPercentDifferentLinux
      }
    // First rendering may be low quality.
    goldenImageRule.assertImageSimilar(goldenImageName, fakeUi.render(), max(maxPercentDifferent, 0.7), ignoreMissingGoldenFile = true)
    // Second rendering is guaranteed to be high quality.
    goldenImageRule.assertImageSimilar(goldenImageName, fakeUi.render(), maxPercentDifferent)
  }

  private val DeviceToolWindowPanel.isConnected
    get() = primaryDisplayView?.isConnected == true

  private fun DeviceToolWindowPanel.findDisplayView(displayId: Int): DeviceView? =
    if (displayId == PRIMARY_DISPLAY_ID) primaryDisplayView else findDescendant<DeviceView> { it.displayId == displayId }
}

private class TestDataLine : SourceDataLine {

  private val data = ByteArrayList()
  private var open = false

  val dataSize: Int
    get() = synchronized(data) { data.size }

  fun dataAsByteBuffer(): ByteBuffer =
    synchronized(data) { ByteBuffer.allocate(data.size).order(ByteOrder.LITTLE_ENDIAN).put(data.elements(), 0, data.size).flip() }

  override fun close() {
    open = false
  }

  override fun getLineInfo(): Line.Info {
    TODO("Not yet implemented")
  }

  override fun open(format: AudioFormat, bufferSize: Int) {
    open()
  }

  override fun open(format: AudioFormat) {
    open()
  }

  override fun open() {
    data.clear()
    open = true
  }

  override fun isOpen(): Boolean = open

  override fun getControls(): Array<Control> {
    TODO("Not yet implemented")
  }

  override fun isControlSupported(control: Control.Type): Boolean {
    TODO("Not yet implemented")
  }

  override fun getControl(control: Control.Type): Control {
    TODO("Not yet implemented")
  }

  override fun addLineListener(listener: LineListener) {
    TODO("Not yet implemented")
  }

  override fun removeLineListener(listener: LineListener) {
    TODO("Not yet implemented")
  }

  override fun drain() {
    TODO("Not yet implemented")
  }

  override fun flush() {}

  override fun start() {}

  override fun stop() {}

  override fun isRunning(): Boolean {
    TODO("Not yet implemented")
  }

  override fun isActive(): Boolean {
    TODO("Not yet implemented")
  }

  override fun getFormat(): AudioFormat {
    TODO("Not yet implemented")
  }

  override fun getBufferSize(): Int {
    TODO("Not yet implemented")
  }

  override fun available(): Int {
    TODO("Not yet implemented")
  }

  override fun getFramePosition(): Int {
    TODO("Not yet implemented")
  }

  override fun getLongFramePosition(): Long {
    TODO("Not yet implemented")
  }

  override fun getMicrosecondPosition(): Long {
    TODO("Not yet implemented")
  }

  override fun getLevel(): Float {
    TODO("Not yet implemented")
  }

  override fun write(bytes: ByteArray, offset: Int, len: Int): Int {
    synchronized(data) { data.addElements(data.size, bytes, offset, len) }
    return len
  }
}
