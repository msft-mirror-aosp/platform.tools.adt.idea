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

import com.android.emulation.control.incubating.CellInfo
import com.android.emulation.control.incubating.CellSignalStrength
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import javax.swing.JButton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [CellularDialog]. */
@RunsInEdt
class CellularDialogTest {

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
    val dialog = CellularDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val networkTypeBox = ui.getComponent<ComboBox<*>> { it.name == "networkTypeComboBox" }
    val signalStrengthBox = ui.getComponent<ComboBox<*>> { it.name == "signalStrengthComboBox" }
    val voiceStatusBox = ui.getComponent<ComboBox<*>> { it.name == "voiceStatusComboBox" }
    val dataStatusBox = ui.getComponent<ComboBox<*>> { it.name == "dataStatusComboBox" }
    val meterStatusBox = ui.getComponent<ComboBox<*>> { it.name == "meterStatusComboBox" }

    assertThat(networkTypeBox.selectedItem?.toString()).isEqualTo("Full")
    assertThat(signalStrengthBox.selectedItem?.toString()).isEqualTo("Moderate")
    assertThat(voiceStatusBox.selectedItem?.toString()).isEqualTo("Home")
    assertThat(dataStatusBox.selectedItem?.toString()).isEqualTo("Home")
    assertThat(meterStatusBox.selectedItem?.toString()).isEqualTo("Metered")

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulator() {
    fakeEmulator.cellInfo =
      CellInfo.newBuilder()
        .setCellStandard(CellInfo.CellStandard.CELL_STANDARD_LTE)
        .setCellSignalStrength(CellSignalStrength.newBuilder().setLevel(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_GOOD))
        .setCellStatusVoice(CellInfo.CellStatus.CELL_STATUS_ROAMING)
        .setCellStatusData(CellInfo.CellStatus.CELL_STATUS_SEARCHING)
        .setCellMeterStatus(CellInfo.CellMeterStatus.CELL_METER_STATUS_TEMPORARILY_NOT_METERED)
        .build()

    val dialog = CellularDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val networkTypeBox = ui.getComponent<ComboBox<*>> { it.name == "networkTypeComboBox" }
    val signalStrengthBox = ui.getComponent<ComboBox<*>> { it.name == "signalStrengthComboBox" }
    val voiceStatusBox = ui.getComponent<ComboBox<*>> { it.name == "voiceStatusComboBox" }
    val dataStatusBox = ui.getComponent<ComboBox<*>> { it.name == "dataStatusComboBox" }
    val meterStatusBox = ui.getComponent<ComboBox<*>> { it.name == "meterStatusComboBox" }

    waitForCondition(5.seconds) {
      networkTypeBox.selectedItem?.toString() == "LTE" &&
        signalStrengthBox.selectedItem?.toString() == "Good" &&
        voiceStatusBox.selectedItem?.toString() == "Roaming" &&
        dataStatusBox.selectedItem?.toString() == "Searching" &&
        meterStatusBox.selectedItem?.toString() == "Temporarily not metered"
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testDialogUiInteraction() {
    val dialog = CellularDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val networkTypeBox = ui.getComponent<ComboBox<*>> { it.name == "networkTypeComboBox" }
    val signalStrengthBox = ui.getComponent<ComboBox<*>> { it.name == "signalStrengthComboBox" }
    val voiceStatusBox = ui.getComponent<ComboBox<*>> { it.name == "voiceStatusComboBox" }
    val dataStatusBox = ui.getComponent<ComboBox<*>> { it.name == "dataStatusComboBox" }
    val meterStatusBox = ui.getComponent<ComboBox<*>> { it.name == "meterStatusComboBox" }

    assertThat(networkTypeBox.selectedItem?.toString()).isEqualTo("Full")
    assertThat(signalStrengthBox.selectedItem?.toString()).isEqualTo("Moderate")
    assertThat(voiceStatusBox.selectedItem?.toString()).isEqualTo("Home")
    assertThat(dataStatusBox.selectedItem?.toString()).isEqualTo("Home")
    assertThat(meterStatusBox.selectedItem?.toString()).isEqualTo("Metered")

    networkTypeBox.selectItem("5G")
    signalStrengthBox.selectItem("Great")
    voiceStatusBox.selectItem("Denied (emergency calls only)")
    dataStatusBox.selectItem("Unregistered (off)")
    meterStatusBox.selectItem("Temporarily not metered")

    waitForCondition(5.seconds) {
      fakeEmulator.cellInfo.cellStandard == CellInfo.CellStandard.CELL_STANDARD_5G &&
        fakeEmulator.cellInfo.cellSignalStrength.level == CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_GREAT &&
        fakeEmulator.cellInfo.cellStatusVoice == CellInfo.CellStatus.CELL_STATUS_DENIED &&
        fakeEmulator.cellInfo.cellStatusData == CellInfo.CellStatus.CELL_STATUS_UNREGISTERED &&
        fakeEmulator.cellInfo.cellMeterStatus == CellInfo.CellMeterStatus.CELL_METER_STATUS_TEMPORARILY_NOT_METERED
    }

    networkTypeBox.selectItem("GSM")
    signalStrengthBox.selectItem("Poor")
    voiceStatusBox.selectItem("Roaming")
    dataStatusBox.selectItem("Denied (emergency calls only)")
    meterStatusBox.selectItem("Temporarily not metered")

    waitForCondition(5.seconds) {
      fakeEmulator.cellInfo.cellStandard == CellInfo.CellStandard.CELL_STANDARD_GSM &&
        fakeEmulator.cellInfo.cellSignalStrength.level == CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_POOR &&
        fakeEmulator.cellInfo.cellStatusVoice == CellInfo.CellStatus.CELL_STATUS_ROAMING &&
        fakeEmulator.cellInfo.cellStatusData == CellInfo.CellStatus.CELL_STATUS_DENIED &&
        fakeEmulator.cellInfo.cellMeterStatus == CellInfo.CellMeterStatus.CELL_METER_STATUS_TEMPORARILY_NOT_METERED
    }

    val closeButton = ui.getComponent<JButton> { it.text == "Close" }
    assertThat(rootPane.defaultButton).isNull()
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  private fun ComboBox<*>.selectItem(text: String) {
    selectedItem = (0 until itemCount).map { getItemAt(it) }.first { it.toString() == text }
  }
}
