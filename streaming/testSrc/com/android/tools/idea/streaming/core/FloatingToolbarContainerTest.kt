/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.adtui.swing.findAllDescendants
import com.android.tools.adtui.swing.popup.JBPopupRule
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.border.EmptyBorder
import kotlin.time.Duration.Companion.seconds
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Test for [FloatingToolbarContainer]. */
@RunsInEdt
class FloatingToolbarContainerTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule() // Enable icon loading in a headless test environment.
  }

  private val disposableRule = DisposableRule()
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/FloatingToolbarContainerTest/golden")
  private val popupRule = JBPopupRule()

  @get:Rule val rule = RuleChain(ApplicationRule(), disposableRule, goldenImageRule, popupRule, EdtRule())
  private val panel by lazy { createHostPanel() }
  private val fakeUi by lazy { FakeUi(panel, parentDisposable = disposableRule.disposable) }

  @Before
  fun setUp() {
    TestToggleAction.toggleState = "Right Bottom"
  }

  @Test
  fun testCollapsibleVertical() {
    val toolbar =
      FloatingToolbarContainer(horizontal = false, inactiveAlpha = 0.5, collapsedStateSelector = { it.isSelected }, activateOnHover = true)
    toolbar.createTestToolbars()
    panel.add(toolbar, BorderLayout.EAST)
    assertAppearance("CollapsibleVerticalInactive")

    fakeUi.mouse.moveTo(toolbar.x + toolbar.width / 2, toolbar.y + toolbar.height - toolbar.width / 2)
    assertAppearance("CollapsibleVerticalActive")

    fakeUi.mouse.moveTo(0, 0)
    assertAppearance("CollapsibleVerticalInactive")

    toolbar.toggleActiveState()
    assertThat(toolbar.isActive).isTrue()
    assertAppearance("CollapsibleVerticalActive")

    toolbar.toggleActiveState()
    assertThat(toolbar.isActive).isFalse()
    assertAppearance("CollapsibleVerticalInactive")

    // Change the selected toolbar button and check how the toolbar updates itself.
    val rightTopAction = fakeUi.getComponent<ActionButton> { it.presentation.text == "Right Top" }.action
    executeAction(rightTopAction, panel, place = ActionPlaces.KEYBOARD_SHORTCUT)
    assertAppearance("CollapsibleVerticalRightTopSelected")
  }

  @Test
  fun testNonCollapsibleHorizontal() {
    val toolbar = FloatingToolbarContainer(horizontal = true, inactiveAlpha = 0.7, activateOnHover = true)
    toolbar.createTestToolbars()
    panel.add(toolbar, BorderLayout.SOUTH)
    assertAppearance("NonCollapsibleHorizontalInactive")

    fakeUi.mouse.moveTo(toolbar.x + toolbar.width - toolbar.height / 2, toolbar.y + toolbar.height / 2)
    assertAppearance("NonCollapsibleHorizontalActive")

    fakeUi.mouse.moveTo(0, 0)
    assertAppearance("NonCollapsibleHorizontalInactive")
  }

  @Test
  fun testEmptyToolbar() {
    val toolbar =
      FloatingToolbarContainer(horizontal = false, inactiveAlpha = 0.8).apply {
        addToolbar("FloatingToolbar", DefaultActionGroup())
        addToolbar("FloatingToolbar", DefaultActionGroup())
      }
    panel.add(toolbar, BorderLayout.EAST)
    fakeUi.updateToolbarsIfNecessary()
    // Empty toolbar should not be visible.
    val image = fakeUi.render()
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        assertThat(image.getRGB(x, y)).isEqualTo(panel.background.rgb)
      }
    }
  }

  @Test
  fun testToolbarWithOnlyCollapserButton() {
    val toolbar =
      FloatingToolbarContainer(horizontal = false, inactiveAlpha = 0.8, initiallyActive = true).apply {
        addToolbar("FloatingToolbar", DefaultActionGroup(FloatingToolbarContainer.CollapserAction(horizontal = false)))
      }
    panel.add(toolbar, BorderLayout.EAST)
    fakeUi.updateToolbarsIfNecessary()
    // A toolbar containing no other buttons other than collapser should not be visible.
    val image = fakeUi.render()
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        assertThat(image.getRGB(x, y)).isEqualTo(panel.background.rgb)
      }
    }
  }

  @Test
  fun testCollapserButtonTooltip() {
    Registry.get("ide.tooltip.initialReshowDelay").setValue(0, disposableRule.disposable)
    val collapserWithoutOrientation = FloatingToolbarContainer.CollapserAction(horizontal = false)
    val collapserWithOrientation = FloatingToolbarContainer.CollapserAction(horizontal = false) {}
    val toolbar =
      FloatingToolbarContainer(horizontal = false, inactiveAlpha = 0.8, initiallyActive = true).apply {
        addToolbar(
          "FloatingToolbar",
          DefaultActionGroup(
            TestAction("Fit Content", AllIcons.General.FitContent),
            collapserWithoutOrientation,
            collapserWithOrientation,
          ),
        )
      }
    panel.add(toolbar, BorderLayout.EAST)
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()

    val buttonWithoutOrientation = fakeUi.getComponent<ActionButton> { it.action === collapserWithoutOrientation }
    assertThat(buttonWithoutOrientation.accessibleContext.accessibleName).isEqualTo("Collapse Toolbar")
    val tooltip1 = HelpTooltip.getTooltipFor(buttonWithoutOrientation)!!
    assertThat(tooltip1).isEqualTo(HelpTooltip().setTitle(HtmlChunk.text("Click to collapse the toolbar.")))
    @Suppress("UnstableApiUsage")
    assertThat(tooltip1.createTipPanel().findAllDescendants<JLabel>().map { it.text }.toList())
      .containsExactly("<html><div>Click to collapse the toolbar.</div></html>")
    val pos1 = fakeUi.getPosition(buttonWithoutOrientation)
    fakeUi.mouse.moveTo(pos1.x + buttonWithoutOrientation.width / 2, pos1.y + buttonWithoutOrientation.height / 2)
    assertThat(popupRule.fakePopupFactory.getNextPopup(2.seconds)).isNotNull()

    val buttonWithOrientation = fakeUi.getComponent<ActionButton> { it.action === collapserWithOrientation }
    assertThat(buttonWithOrientation.accessibleContext.accessibleName).isEqualTo("Collapse Toolbar")
    val tooltip2 = HelpTooltip.getTooltipFor(buttonWithOrientation)!!
    assertThat(tooltip2)
      .isEqualTo(
        HelpTooltip()
          .setTitle(
            HtmlChunk.fragment(
              HtmlChunk.text("Click to collapse the toolbar."),
              HtmlChunk.br(),
              HtmlChunk.text("Right-click to change the toolbar orientation."),
            )
          )
      )
    @Suppress("UnstableApiUsage")
    assertThat(tooltip2.createTipPanel().findAllDescendants<JLabel>().map { it.text }.toList())
      .containsExactly("<html><div>Click to collapse the toolbar.<br/>Right-click to change the toolbar orientation.</div></html>")
    val pos2 = fakeUi.getPosition(buttonWithOrientation)
    fakeUi.mouse.moveTo(pos2.x + buttonWithOrientation.width / 2, pos2.y + buttonWithOrientation.height / 2)
    assertThat(popupRule.fakePopupFactory.getNextPopup(2.seconds)).isNotNull()

    // Verify tooltips remain intact across subsequent toolbar updates.
    ActivityTracker.getInstance().inc()
    fakeUi.updateToolbarsIfNecessary()
    assertThat(HelpTooltip.getTooltipFor(buttonWithoutOrientation)).isSameAs(tooltip1)
    assertThat(HelpTooltip.getTooltipFor(buttonWithOrientation)).isSameAs(tooltip2)
  }

  @Test
  fun testOnActivationChangedDuringDeferredDeactivation() {
    val events = mutableListOf<Boolean>()
    val toolbar =
      FloatingToolbarContainer(horizontal = false, inactiveAlpha = 0.5, collapsedStateSelector = { it.isSelected }).apply {
        createTestToolbars()
        onActivationChanged = { events.add(it) }
      }
    panel.add(toolbar, BorderLayout.EAST)
    fakeUi.updateToolbarsIfNecessary()

    // Triggering delayed deactivation while ActivationAnimator is still running fires onActivationChanged(false) only once,
    // including after ActivationAnimator completes and starts DeactivationAnimator.
    toolbar.triggerActivation()
    toolbar.triggerDeactivation(delayMillis = 100, slow = true)
    assertThat(toolbar.isActive).isFalse()
    assertThat(events).containsExactly(true, false).inOrder()
    fakeUi.layoutAndDispatchEvents()
    assertThat(toolbar.isActive).isFalse()
    assertThat(toolbar.activationFactor).isEqualTo(0.0)
    assertThat(events).containsExactly(true, false).inOrder()

    // Re-activating while pendingDeactivation is set and then deactivating again alternates notifications cleanly.
    events.clear()
    toolbar.triggerActivation()
    toolbar.triggerDeactivation(delayMillis = 100, slow = true)
    toolbar.triggerActivation()
    assertThat(toolbar.isActive).isTrue()
    fakeUi.layoutAndDispatchEvents()
    assertThat(toolbar.activationFactor).isEqualTo(1.0)
    toolbar.triggerDeactivation()
    fakeUi.layoutAndDispatchEvents()
    assertThat(events).containsExactly(true, false, true, false).inOrder()
  }

  @Test
  fun testDynamicAuxiliaryToolbar() {
    var auxVisible = false
    var auxSelected = false
    val auxAction =
      object : ToggleAction("Pan", "Pan", AllIcons.Actions.MoveTo2) {
        override fun update(event: AnActionEvent) {
          super.update(event)
          event.presentation.isEnabledAndVisible = auxVisible
        }

        override fun isSelected(event: AnActionEvent): Boolean = auxSelected

        override fun setSelected(event: AnActionEvent, state: Boolean) {
          auxSelected = state
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      }
    val mainIndicator = TestAction("Indicator", AllIcons.General.FitContent)
    val toolbar =
      FloatingToolbarContainer(
          horizontal = false,
          inactiveAlpha = 0.8,
          collapsedStateSelector = { it.action === mainIndicator || it.isSelected },
          initiallyActive = false,
        )
        .apply {
          addToolbar("AuxToolbar", DefaultActionGroup(auxAction))
          addToolbar("MainToolbar", DefaultActionGroup(TestAction("Zoom In", AllIcons.General.Add), mainIndicator))
        }
    panel.add(toolbar, BorderLayout.EAST)
    ActivityTracker.getInstance().inc()
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()

    val auxPanel = toolbar.getComponent(0)
    val mainPanel = toolbar.getComponent(1)

    // When auxAction is not visible, auxPanel takes zero space even when expanded.
    assertThat(auxPanel.height).isEqualTo(0)
    toolbar.toggleActiveState()
    waitForCondition(1.seconds) { toolbar.activationFactor == 1.0 }
    fakeUi.layoutAndDispatchEvents()
    assertThat(auxPanel.height).isEqualTo(0)

    // Make auxAction visible while unselected: visible when expanded with a gap above mainPanel, hidden when collapsed.
    auxVisible = true
    ActivityTracker.getInstance().inc()
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    assertThat(auxPanel.height).isGreaterThan(0)
    val expandedGap = mainPanel.y - (auxPanel.y + auxPanel.height)
    assertThat(expandedGap).isGreaterThan(0)

    toolbar.toggleActiveState()
    waitForCondition(1.seconds) { toolbar.activationFactor == 0.0 }
    fakeUi.layoutAndDispatchEvents()
    assertThat(auxPanel.height).isEqualTo(0)
    assertThat(mainPanel.height).isGreaterThan(0)

    // Select auxAction: remains visible when collapsed, separated from mainPanel by the same gap.
    auxSelected = true
    ActivityTracker.getInstance().inc()
    fakeUi.updateToolbarsIfNecessary()
    fakeUi.layoutAndDispatchEvents()
    assertThat(auxPanel.height).isGreaterThan(0)
    assertThat(mainPanel.y - (auxPanel.y + auxPanel.height)).isEqualTo(expandedGap)
    assertThat(mainPanel.y + mainPanel.height).isEqualTo(toolbar.height)

    // Deselect auxAction while collapsed: hides immediately.
    val auxButton = fakeUi.getComponent<ActionButton> { it.action === auxAction }
    val pos = fakeUi.getPosition(auxButton)
    fakeUi.mouse.click(pos.x + auxButton.width / 2, pos.y + auxButton.height / 2)
    fakeUi.layoutAndDispatchEvents()
    assertThat(auxSelected).isFalse()
    assertThat(auxPanel.height).isEqualTo(0)
  }

  private fun createHostPanel(): BorderLayoutPanel {
    return BorderLayoutPanel().apply {
      setSize(200, 200)
      background = Color(150, 200, 255)
      val scrollBarWidth = UIUtil.getScrollBarWidth()
      border = EmptyBorder(scrollBarWidth, scrollBarWidth, scrollBarWidth, scrollBarWidth)
    }
  }

  private fun FloatingToolbarContainer.createTestToolbars() {
    val actionGroup1 =
      DefaultActionGroup().apply {
        add(TestToggleAction("Left Top", AllIcons.Actions.MoveToLeftTop))
        add(TestToggleAction("Right Top", AllIcons.Actions.MoveToRightTop))
        add(TestToggleAction("Right Bottom", AllIcons.Actions.MoveToRightBottom))
        add(TestToggleAction("Left Bottom", AllIcons.Actions.MoveToLeftBottom))
      }
    addToolbar("FloatingToolbar", actionGroup1)

    val actionGroup2 = DefaultActionGroup().apply { add(TestAction("Fit Content", AllIcons.General.FitContent)) }
    addToolbar("FloatingToolbar", actionGroup2)
  }

  private fun assertAppearance(goldenImageName: String) {
    ActivityTracker.getInstance().inc()
    fakeUi.updateToolbarsIfNecessary()
    val image = fakeUi.render()
    goldenImageRule.assertImageSimilar(goldenImageName, image, 0.0)
  }

  private class TestAction(name: String, icon: Icon) : AnAction(name, name, icon) {

    override fun actionPerformed(event: AnActionEvent) {}
  }

  private class TestToggleAction(name: String, icon: Icon) : ToggleAction(name, name, icon) {

    override fun isSelected(event: AnActionEvent): Boolean = toggleState == templateText

    override fun setSelected(event: AnActionEvent, state: Boolean) {
      if (state) {
        toggleState = templateText
      }
    }

    companion object {
      var toggleState: String = "Right Bottom"
    }
  }
}
