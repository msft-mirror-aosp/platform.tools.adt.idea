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
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel

/** Dialog for configuring emulator cellular network settings, providing functionality similar to the Cellular page in extended controls. */
internal class CellularDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("cellular.dialog.title", emulator.emulatorId.avdName)) {

  private var cellularState: CellularState = CellularState()

  private val cellInfoChannel =
    createStateChannel(
      getState = EmulatorController::getCellInfo,
      onStateLoaded = { cellInfo ->
        cellularState = cellInfo.toCellularState()
        updateUi()
      },
      setState = EmulatorController::setCellInfo,
    )

  private var networkType: CellularStandard
    get() = cellularState.networkType
    set(value) {
      if (cellularState.networkType != value) {
        cellularState = cellularState.copy(networkType = value)
        onStateChanged()
      }
    }

  private var signalStrength: CellularSignal
    get() = cellularState.signalStrength
    set(value) {
      if (cellularState.signalStrength != value) {
        cellularState = cellularState.copy(signalStrength = value)
        onStateChanged()
      }
    }

  private var voiceStatus: CellularStatus
    get() = cellularState.voiceStatus
    set(value) {
      if (cellularState.voiceStatus != value) {
        cellularState = cellularState.copy(voiceStatus = value)
        onStateChanged()
      }
    }

  private var dataStatus: CellularStatus
    get() = cellularState.dataStatus
    set(value) {
      if (cellularState.dataStatus != value) {
        cellularState = cellularState.copy(dataStatus = value)
        onStateChanged()
      }
    }

  private var meterStatus: CellularMeterStatus
    get() = cellularState.meterStatus
    set(value) {
      if (cellularState.meterStatus != value) {
        cellularState = cellularState.copy(meterStatus = value)
        onStateChanged()
      }
    }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      row {
        panel {
            row { enumComboBox(message("cellular.dialog.label.network.type"), ::networkType, COMBO_BOX_WIDTH) }
            row { enumComboBox(message("cellular.dialog.label.voice.status"), ::voiceStatus, COMBO_BOX_WIDTH) }
            row { enumComboBox(message("cellular.dialog.label.meter.status"), ::meterStatus, COMBO_BOX_WIDTH) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)

        panel {
            row { enumComboBox(message("cellular.dialog.label.signal.strength"), ::signalStrength, COMBO_BOX_WIDTH) }
            row { enumComboBox(message("cellular.dialog.label.data.status"), ::dataStatus, COMBO_BOX_WIDTH) { it.dataDisplayValue } }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
      }
    }
  }

  private fun onStateChanged() {
    stateModified = true
    updateUi()
    cellInfoChannel.trySend(cellularState.toCellInfo())
  }

  companion object {
    private const val COMBO_BOX_WIDTH = 220

    /** Converts [CellularState] to Modem service's [CellInfo] proto message. */
    private fun CellularState.toCellInfo(): CellInfo {
      val signal = CellSignalStrength.newBuilder().setLevel(signalStrength.protoValue).build()
      return CellInfo.newBuilder()
        .setCellStandard(networkType.protoValue)
        .setCellStatusVoice(voiceStatus.protoValue)
        .setCellStatusData(dataStatus.protoValue)
        .setCellSignalStrength(signal)
        .setCellMeterStatus(meterStatus.protoValue)
        .build()
    }

    /** Converts Modem service's [CellInfo] proto message to [CellularState]. */
    private fun CellInfo.toCellularState(): CellularState {
      val network = CellularStandard.entries.find { it.protoValue == cellStandard } ?: CellularStandard.FULL
      val voice = CellularStatus.entries.find { it.protoValue == cellStatusVoice } ?: CellularStatus.HOME
      val data = CellularStatus.entries.find { it.protoValue == cellStatusData } ?: CellularStatus.HOME
      val signal =
        if (hasCellSignalStrength()) {
          CellularSignal.entries.find { it.protoValue == cellSignalStrength.level } ?: CellularSignal.MODERATE
        } else {
          CellularSignal.MODERATE
        }
      val meter = CellularMeterStatus.entries.find { it.protoValue == cellMeterStatus } ?: CellularMeterStatus.METERED
      return CellularState(networkType = network, signalStrength = signal, voiceStatus = voice, dataStatus = data, meterStatus = meter)
    }
  }

  /** Cellular network types supported by the emulator. */
  private enum class CellularStandard(val protoValue: CellInfo.CellStandard, private val displayValue: String) {
    GSM(CellInfo.CellStandard.CELL_STANDARD_GSM, "GSM"),
    HSCSD(CellInfo.CellStandard.CELL_STANDARD_HSCSD, "HSCSD"),
    GPRS(CellInfo.CellStandard.CELL_STANDARD_GPRS, "GPRS"),
    EDGE(CellInfo.CellStandard.CELL_STANDARD_EDGE, "EDGE"),
    UMTS(CellInfo.CellStandard.CELL_STANDARD_UMTS, "UMTS"),
    HSDPA(CellInfo.CellStandard.CELL_STANDARD_HSDPA, "HSDPA"),
    LTE(CellInfo.CellStandard.CELL_STANDARD_LTE, "LTE"),
    FULL(CellInfo.CellStandard.CELL_STANDARD_FULL, "Full"),
    FIVE_G(CellInfo.CellStandard.CELL_STANDARD_5G, "5G");

    override fun toString(): String = displayValue
  }

  /** Cellular signal strengths supported by the emulator. */
  private enum class CellularSignal(val protoValue: CellSignalStrength.CellSignalLevel, private val displayValue: String) {
    NONE(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_NONE_OR_UNKNOWN, "None"),
    POOR(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_POOR, "Poor"),
    MODERATE(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_MODERATE, "Moderate"),
    GOOD(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_GOOD, "Good"),
    GREAT(CellSignalStrength.CellSignalLevel.SIGNAL_STRENGTH_GREAT, "Great");

    override fun toString(): String = displayValue
  }

  /** Cellular voice/data connection status. */
  private enum class CellularStatus(
    val protoValue: CellInfo.CellStatus,
    private val voiceDisplayValue: String,
    val dataDisplayValue: String = voiceDisplayValue,
  ) {
    HOME(CellInfo.CellStatus.CELL_STATUS_HOME, "Home"),
    ROAMING(CellInfo.CellStatus.CELL_STATUS_ROAMING, "Roaming"),
    SEARCHING(CellInfo.CellStatus.CELL_STATUS_SEARCHING, "Searching"),
    DENIED(CellInfo.CellStatus.CELL_STATUS_DENIED, "Denied (emergency calls only)", "Denied"),
    UNREGISTERED(CellInfo.CellStatus.CELL_STATUS_UNREGISTERED, "Unregistered (off)");

    override fun toString(): String = voiceDisplayValue
  }

  /** Cellular metering status. */
  private enum class CellularMeterStatus(val protoValue: CellInfo.CellMeterStatus, private val displayValue: String) {
    METERED(CellInfo.CellMeterStatus.CELL_METER_STATUS_METERED, "Metered"),
    TEMPORARILY_NOT_METERED(CellInfo.CellMeterStatus.CELL_METER_STATUS_TEMPORARILY_NOT_METERED, "Temporarily not metered");

    override fun toString(): String = displayValue
  }

  /** Holds the state of cellular settings. */
  private data class CellularState(
    var networkType: CellularStandard = CellularStandard.FULL,
    var signalStrength: CellularSignal = CellularSignal.MODERATE,
    var voiceStatus: CellularStatus = CellularStatus.HOME,
    var dataStatus: CellularStatus = CellularStatus.HOME,
    var meterStatus: CellularMeterStatus = CellularMeterStatus.METERED,
  )
}
