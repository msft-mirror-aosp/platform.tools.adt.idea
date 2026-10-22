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

import com.android.emulator.control.DisplayConfiguration
import com.android.emulator.control.DisplayConfigurations
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.NotificationTracker
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.AnActionButton
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.gridLayout.GridLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import javax.swing.JComponent
import javax.swing.JLayeredPane
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [DisplaysDialog]. */
@RunsInEdt
class DisplaysDialogTest {

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

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    val testAvdFolder = FakeEmulator.createPhoneAvd(emulatorRule.avdRoot)
    fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    assertThat(emulators).hasSize(1)
    emulatorController = emulators.first()
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  }

  @Test
  fun testInitialStateFromDefaults() {
    val dialog = DisplaysDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)
    val table = ui.getComponent<TableView<*>>()

    waitForCondition(5.seconds) { table.rowCount == 1 }
    assertThat(table.getValueAt(0, 0)).isEqualTo("Primary")
    assertThat(table.getValueAt(0, 1)).isEqualTo("1440")
    assertThat(table.getValueAt(0, 2)).isEqualTo("2960")
    assertThat(table.getValueAt(0, 3)).isEqualTo("480 dpi")

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulatorWithPresetAndConfigurableDisplays() {
    runBlocking {
      emulatorController.setDisplayConfigurations(
        DisplayConfigurations.newBuilder()
          .addDisplays(DisplayConfiguration.newBuilder().setDisplay(1).setWidth(720).setHeight(1280).setDpi(213))
          .addDisplays(DisplayConfiguration.newBuilder().setDisplay(2).setWidth(1000).setHeight(1500).setDpi(240))
          .addDisplays(DisplayConfiguration.newBuilder().setDisplay(6).setWidth(400).setHeight(600).setDpi(120))
          .build()
      )
    }

    val dialog = DisplaysDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)
    val table = ui.getComponent<TableView<*>>()
    val actionsPanel = ui.getComponent<CommonActionsPanel>()

    waitForCondition(5.seconds) { table.rowCount == 4 }
    assertThat(table.getValueAt(1, 0)).isEqualTo("Preset")
    assertThat(table.getValueAt(1, 1)).isEqualTo("400")
    assertThat(table.getValueAt(1, 2)).isEqualTo("600")
    assertThat(table.getValueAt(1, 3)).isEqualTo("120 dpi")

    assertThat(table.getValueAt(2, 0)).isEqualTo("720p")
    assertThat(table.getValueAt(2, 1)).isEqualTo("720")
    assertThat(table.getValueAt(2, 2)).isEqualTo("1280")
    assertThat(table.getValueAt(2, 3)).isEqualTo("213 dpi")

    assertThat(table.getValueAt(3, 0)).isEqualTo("Custom")
    assertThat(table.getValueAt(3, 1)).isEqualTo("1000")
    assertThat(table.getValueAt(3, 2)).isEqualTo("1500")
    assertThat(table.getValueAt(3, 3)).isEqualTo("240 dpi")
    table.selectionModel.setSelectionInterval(1, 1)
    assertThat(isPresentationEnabled(getEditAction(actionsPanel))).isFalse()
    assertThat(isPresentationEnabled(getRemoveAction(actionsPanel))).isFalse()

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testDialogTableUiInteractionAndValidation() {
    val dialog = DisplaysDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)
    val table = ui.getComponent<TableView<*>>()
    val actionsPanel = ui.getComponent<CommonActionsPanel>()

    // Wait for the primary display to be populated in the table and selected.
    waitForCondition(5.seconds) { table.rowCount == 1 }
    assertThat(table.selectedRow).isEqualTo(0)
    assertThat(table.getValueAt(0, 0)).isEqualTo("Primary")
    assertThat(isPresentationEnabled(getAddAction(actionsPanel))).isTrue()
    assertThat(isPresentationEnabled(getEditAction(actionsPanel))).isFalse()
    assertThat(isPresentationEnabled(getRemoveAction(actionsPanel))).isFalse()

    // Add display 1 with default preset (480p).
    addDisplay(actionsPanel)
    assertThat(table.rowCount).isEqualTo(2)
    assertThat(table.selectedRow).isEqualTo(1)
    assertThat(table.getValueAt(1, 0)).isEqualTo("480p")
    assertThat(table.getValueAt(1, 1)).isEqualTo("480")
    assertThat(table.getValueAt(1, 2)).isEqualTo("720")
    assertThat(table.getValueAt(1, 3)).isEqualTo("142 dpi")
    assertThat(isPresentationEnabled(getEditAction(actionsPanel))).isTrue()
    assertThat(isPresentationEnabled(getRemoveAction(actionsPanel))).isTrue()

    // Add display 2 and display 3, reaching capacity.
    addDisplay(actionsPanel)
    addDisplay(actionsPanel)
    assertThat(table.rowCount).isEqualTo(4)
    assertThat(isPresentationEnabled(getAddAction(actionsPanel))).isFalse()

    // Remove display 3 and display 2.
    performAction(getRemoveAction(actionsPanel))
    performAction(getRemoveAction(actionsPanel))
    assertThat(table.rowCount).isEqualTo(2)
    assertThat(isPresentationEnabled(getAddAction(actionsPanel))).isTrue()

    // Edit display 1 to custom dimensions and test validation.
    table.selectionModel.setSelectionInterval(1, 1)
    editDisplayWithCustomValidation(actionsPanel, width = 900, height = 1600, dpi = 240)
    assertThat(table.getValueAt(1, 0)).isEqualTo("Custom")
    assertThat(table.getValueAt(1, 1)).isEqualTo("900")
    assertThat(table.getValueAt(1, 2)).isEqualTo("1600")
    assertThat(table.getValueAt(1, 3)).isEqualTo("240 dpi")

    waitForCondition(5.seconds) {
      fakeEmulator.displays.size == 2 &&
        fakeEmulator.displays[1].display == 1 &&
        fakeEmulator.displays[1].width == 900 &&
        fakeEmulator.displays[1].height == 1600 &&
        fakeEmulator.displays[1].dpi == 240
    }

    val closeButton = rootPane.defaultButton
    assertThat(closeButton.text).isEqualTo("Close")
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testUpdateFromDisplayListDoesNotModifyTableWhenContentsAreIdentical() {
    val secondaryDisplay1 = DisplayConfiguration.newBuilder().setDisplay(1).setWidth(720).setHeight(1280).setDpi(213).build()

    // Ensure NotificationTracker is subscribed and receives a notification with only the primary display.
    val notificationTracker = NotificationTracker.forEmulator(emulatorController)
    waitForCondition(5.seconds) { notificationTracker.microphoneInput.value != null }
    runBlocking {
      fakeEmulator.changeSecondaryDisplays(listOf(secondaryDisplay1))
      fakeEmulator.changeSecondaryDisplays(emptyList())
    }
    waitForCondition(5.seconds) { notificationTracker.displayConfigurations.value?.size == 1 }

    // Pause gRPC so that the initial getDisplayConfigurations call does not complete until resumed.
    fakeEmulator.clearGrpcCallLog()
    fakeEmulator.pauseGrpc()
    val dialog = DisplaysDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)
    val table = ui.getComponent<TableView<*>>()

    // The table is initially populated from NotificationTracker while getDisplayConfigurations is paused.
    waitForCondition(5.seconds) { table.rowCount == 1 }
    val initialItem = table.items[0]
    var tableModelEventsCount = 0
    table.model.addTableModelListener { tableModelEventsCount++ }

    // Resuming gRPC delivers identical display configurations from getDisplayConfigurations, which should not modify the table.
    fakeEmulator.resumeGrpc()
    val call = fakeEmulator.getNextGrpcCall(5.seconds) { true }
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/getDisplayConfigurations")
    waitForCondition(5.seconds) { call.completion.isDone }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertThat(table.items[0]).isSameAs(initialItem)
    assertThat(tableModelEventsCount).isEqualTo(0)

    // Updating with a different display configuration should modify the table.
    runBlocking { fakeEmulator.changeSecondaryDisplays(listOf(secondaryDisplay1)) }
    waitForCondition(5.seconds) { table.items.size == 2 }
    assertThat(table.items[0]).isNotSameAs(initialItem)
    assertThat(tableModelEventsCount).isGreaterThan(0)

    val closeButton = rootPane.defaultButton
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  private fun getAddAction(actionsPanel: CommonActionsPanel) = actionsPanel.getAnAction(CommonActionsPanel.Buttons.ADD)!!

  private fun getEditAction(actionsPanel: CommonActionsPanel) = actionsPanel.getAnAction(CommonActionsPanel.Buttons.EDIT)!!

  private fun getRemoveAction(actionsPanel: CommonActionsPanel) = actionsPanel.getAnAction(CommonActionsPanel.Buttons.REMOVE)!!

  private fun addDisplay(actionsPanel: CommonActionsPanel) {
    val addAction = getAddAction(actionsPanel)
    createModalDialogAndInteractWithIt({ performAction(addAction) }) { dialog ->
      val rootPane = dialog.rootPane
      val ui = FakeUi(rootPane)
      ui.clickOn(rootPane.defaultButton)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  @Suppress("UnstableApiUsage")
  private fun editDisplayWithCustomValidation(actionsPanel: CommonActionsPanel, width: Int, height: Int, dpi: Int) {
    val editAction = getEditAction(actionsPanel)
    createModalDialogAndInteractWithIt({ performAction(editAction) }) { dialog ->
      val rootPane = dialog.rootPane
      val ui = FakeUi(rootPane)
      val comboBox = ui.getComponent<ComboBox<*>> { it.name == "displayTypeComboBox" }
      val widthField = ui.getComponent<JBTextField> { it.name == "widthField" }
      val heightField = ui.getComponent<JBTextField> { it.name == "heightField" }
      val dpiField = ui.getComponent<JBTextField> { it.name == "dpiField" }

      // The right edge of the Density text box is aligned with the right edge of the Preset selector.
      val panel = comboBox.parent as? JComponent
      val gridLayout = panel?.layout as? GridLayout
      val comboBoxRight = comboBox.x + comboBox.width - JBUIScale.scale(gridLayout?.getConstraints(comboBox)?.visualPaddings?.right ?: 0)
      val dpiFieldRight = dpiField.x + dpiField.width - JBUIScale.scale(gridLayout?.getConstraints(dpiField)?.visualPaddings?.right ?: 0)
      assertThat(dpiFieldRight).isEqualTo(comboBoxRight)
      val comboBoxLeft = comboBox.x + JBUIScale.scale(gridLayout?.getConstraints(comboBox)?.visualPaddings?.left ?: 0)
      val widthFieldLeft = widthField.x + JBUIScale.scale(gridLayout?.getConstraints(widthField)?.visualPaddings?.left ?: 0)
      assertThat(widthFieldLeft).isEqualTo(comboBoxLeft)

      // Width, Height and Density text boxes are always enabled, even with a preset selected.
      assertThat(widthField.isEnabled).isTrue()
      assertThat(heightField.isEnabled).isTrue()
      assertThat(dpiField.isEnabled).isTrue()
      assertThat(comboBox.selectedItem?.toString()).isEqualTo("480p (720\u00D7480)")

      // Changing text box values updates the selected preset.
      widthField.text = "1080"
      heightField.text = "1920"
      dpiField.text = "320"
      assertThat(comboBox.selectedItem?.toString()).isEqualTo("1080p (1920\u00D71080)")

      // Selecting a preset from combo box updates text boxes, which remain enabled.
      val preset480p = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }.first { it.toString().startsWith("480p") }
      comboBox.selectedItem = preset480p
      assertThat(widthField.text).isEqualTo("480")
      assertThat(heightField.text).isEqualTo("720")
      assertThat(dpiField.text).isEqualTo("142")
      assertThat(widthField.isEnabled).isTrue()
      assertThat(heightField.isEnabled).isTrue()
      assertThat(dpiField.isEnabled).isTrue()

      // Entering values that do not match any preset selects Custom.
      widthField.text = "900"
      assertThat(comboBox.selectedItem?.toString()).isEqualTo("Custom")

      val okButton = rootPane.defaultButton
      assertThat(okButton.isEnabled).isTrue()

      // Set invalid DPI (< 120), OK button should be disabled.
      dpiField.text = "80"
      assertThat(okButton.isEnabled).isFalse()

      // Set valid custom dimensions and DPI.
      widthField.text = width.toString()
      heightField.text = height.toString()
      dpiField.text = dpi.toString()
      assertThat(okButton.isEnabled).isTrue()

      ui.clickOn(okButton)
    }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }

  private fun performAction(action: AnAction) {
    if (action is AnActionButton) {
      val contextComponent =
        object : JLayeredPane() {
          override fun isShowing(): Boolean = true
        }
      action.withContextComponent(contextComponent) {
        executeAction(action)
      }
    } else {
      executeAction(action)
    }
  }

  private fun isPresentationEnabled(action: AnAction): Boolean {
    return if (action is AnActionButton) {
      val contextComponent =
        object : JLayeredPane() {
          override fun isShowing(): Boolean = true
        }
      action.withContextComponent(contextComponent) {
        updateAndGetActionPresentation(action).isEnabled
      }
    } else {
      updateAndGetActionPresentation(action).isEnabled
    }
  }

  private fun <T> AnActionButton.withContextComponent(component: JComponent, function: () -> T): T {
    val savedContextComponent = contextComponent
    try {
      contextComponent = component
      return function()
    } finally {
      contextComponent = savedContextComponent
    }
  }
}
