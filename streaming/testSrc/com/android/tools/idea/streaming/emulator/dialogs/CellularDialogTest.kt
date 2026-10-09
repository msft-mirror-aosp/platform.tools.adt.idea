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
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
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
    val dialog = CellularDialog(emulatorController)

    assertThat(dialog.networkType).isEqualTo(CellularStandard.FULL)
    assertThat(dialog.signalStrength).isEqualTo(CellularSignal.MODERATE)
    assertThat(dialog.voiceStatus).isEqualTo(CellularStatus.HOME)
    assertThat(dialog.dataStatus).isEqualTo(CellularStatus.HOME)
    assertThat(dialog.meterStatus).isEqualTo(CellularMeterStatus.METERED)
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

    val dialog = CellularDialog(emulatorController)

    waitForCondition(5.seconds) {
      dialog.networkType == CellularStandard.LTE &&
        dialog.signalStrength == CellularSignal.GOOD &&
        dialog.voiceStatus == CellularStatus.ROAMING &&
        dialog.dataStatus == CellularStatus.SEARCHING &&
        dialog.meterStatus == CellularMeterStatus.TEMPORARILY_NOT_METERED
    }
  }

  @Test
  fun testChangingPropertiesUpdatesEmulator() {
    val dialog = CellularDialog(emulatorController)

    dialog.networkType = CellularStandard.FIVE_G
    dialog.signalStrength = CellularSignal.GREAT
    dialog.voiceStatus = CellularStatus.DENIED
    dialog.dataStatus = CellularStatus.UNREGISTERED
    dialog.meterStatus = CellularMeterStatus.TEMPORARILY_NOT_METERED

    waitForCondition(5.seconds) {
      fakeEmulator.cellInfo.cellStandard.number == CellularStandard.FIVE_G.ordinal + 1 &&
        fakeEmulator.cellInfo.cellSignalStrength.level.number == CellularSignal.GREAT.ordinal &&
        fakeEmulator.cellInfo.cellStatusVoice.number == CellularStatus.DENIED.ordinal + 1 &&
        fakeEmulator.cellInfo.cellStatusData.number == CellularStatus.UNREGISTERED.ordinal + 1 &&
        fakeEmulator.cellInfo.cellMeterStatus.number == CellularMeterStatus.TEMPORARILY_NOT_METERED.ordinal + 1
    }
  }

  @Test
  fun testDialogUiInteraction() {
    val dialog = CellularDialog(emulatorController)
    val dialogWrapper = dialog.createWrapper()

    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)

      val networkTypeBox = ui.getComponent<ComboBox<CellularStandard>> { it.name == "networkTypeComboBox" }
      val signalStrengthBox = ui.getComponent<ComboBox<CellularSignal>> { it.name == "signalStrengthComboBox" }
      val voiceStatusBox = ui.getComponent<ComboBox<CellularStatus>> { it.name == "voiceStatusComboBox" }
      val dataStatusBox = ui.getComponent<ComboBox<CellularStatus>> { it.name == "dataStatusComboBox" }
      val meterStatusBox = ui.getComponent<ComboBox<CellularMeterStatus>> { it.name == "meterStatusComboBox" }

      assertThat(networkTypeBox.selectedItem).isEqualTo(CellularStandard.FULL)
      assertThat(signalStrengthBox.selectedItem).isEqualTo(CellularSignal.MODERATE)
      assertThat(voiceStatusBox.selectedItem).isEqualTo(CellularStatus.HOME)
      assertThat(dataStatusBox.selectedItem).isEqualTo(CellularStatus.HOME)
      assertThat(meterStatusBox.selectedItem).isEqualTo(CellularMeterStatus.METERED)

      networkTypeBox.selectedItem = CellularStandard.GSM
      signalStrengthBox.selectedItem = CellularSignal.POOR
      voiceStatusBox.selectedItem = CellularStatus.ROAMING
      dataStatusBox.selectedItem = CellularStatus.DENIED
      meterStatusBox.selectedItem = CellularMeterStatus.TEMPORARILY_NOT_METERED

      val closeButton = rootPane.defaultButton
      assertThat(closeButton.text).isEqualTo("Close")
      ui.clickOn(closeButton)
    }

    assertThat(dialog.networkType).isEqualTo(CellularStandard.GSM)
    assertThat(dialog.signalStrength).isEqualTo(CellularSignal.POOR)
    assertThat(dialog.voiceStatus).isEqualTo(CellularStatus.ROAMING)
    assertThat(dialog.dataStatus).isEqualTo(CellularStatus.DENIED)
    assertThat(dialog.meterStatus).isEqualTo(CellularMeterStatus.TEMPORARILY_NOT_METERED)

    waitForCondition(5.seconds) {
      fakeEmulator.cellInfo.cellStandard.number == CellularStandard.GSM.ordinal + 1 &&
        fakeEmulator.cellInfo.cellSignalStrength.level.number == CellularSignal.POOR.ordinal &&
        fakeEmulator.cellInfo.cellStatusVoice.number == CellularStatus.ROAMING.ordinal + 1 &&
        fakeEmulator.cellInfo.cellStatusData.number == CellularStatus.DENIED.ordinal + 1 &&
        fakeEmulator.cellInfo.cellMeterStatus.number == CellularMeterStatus.TEMPORARILY_NOT_METERED.ordinal + 1
    }
  }
}
