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
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.CloseDialogAction
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.DimensionService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.event.ItemEvent
import kotlin.reflect.KMutableProperty0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls

/** Cellular network types supported by the emulator. */
enum class CellularStandard(val displayValue: String) {
  GSM("GSM"),
  HSCSD("HSCSD"),
  GPRS("GPRS"),
  EDGE("EDGE"),
  UMTS("UMTS"),
  HSDPA("HSDPA"),
  LTE("LTE"),
  FULL("Full"),
  FIVE_G("5G");

  override fun toString(): String = displayValue
}

/** Cellular signal strengths supported by the emulator. */
enum class CellularSignal(val displayValue: String) {
  NONE("None"),
  POOR("Poor"),
  MODERATE("Moderate"),
  GOOD("Good"),
  GREAT("Great");

  override fun toString(): String = displayValue
}

/** Cellular voice/data connection status. */
enum class CellularStatus(val voiceDisplayValue: String, val dataDisplayValue: String = voiceDisplayValue) {
  HOME("Home"),
  ROAMING("Roaming"),
  SEARCHING("Searching"),
  DENIED("Denied (emergency calls only)", "Denied"),
  UNREGISTERED("Unregistered (off)");

  override fun toString(): String = voiceDisplayValue
}

/** Cellular metering status. */
enum class CellularMeterStatus(val displayValue: String) {
  METERED("Metered"),
  TEMPORARILY_NOT_METERED("Temporarily not metered");

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

/** Dialog for configuring emulator cellular network settings, providing functionality similar to the Cellular page in extended controls. */
class CellularDialog(val emulator: EmulatorController) {
  private var cellularState: CellularState = CellularState()

  private var stateModified = false
  private val cellInfoChannel = Channel<CellInfo>(Channel.CONFLATED)

  init {
    emulator.createCoroutineScope().launch {
      try {
        val cellInfo = emulator.getCellInfo()
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
          if (!stateModified) {
            cellularState = cellInfo.toCellularState()
            updateUiFromState()
          }
        }
      } catch (_: Exception) {
        // Error is already logged by EmulatorController.
      }
      for (cellInfo in cellInfoChannel) {
        try {
          emulator.setCellInfo(cellInfo)
        } catch (_: Exception) {
          // Error is already logged by EmulatorController.
        }
      }
    }
  }

  var networkType: CellularStandard
    get() = cellularState.networkType
    set(value) {
      if (cellularState.networkType != value) {
        cellularState = cellularState.copy(networkType = value)
        onStateChanged()
      }
    }

  var signalStrength: CellularSignal
    get() = cellularState.signalStrength
    set(value) {
      if (cellularState.signalStrength != value) {
        cellularState = cellularState.copy(signalStrength = value)
        onStateChanged()
      }
    }

  var voiceStatus: CellularStatus
    get() = cellularState.voiceStatus
    set(value) {
      if (cellularState.voiceStatus != value) {
        cellularState = cellularState.copy(voiceStatus = value)
        onStateChanged()
      }
    }

  var dataStatus: CellularStatus
    get() = cellularState.dataStatus
    set(value) {
      if (cellularState.dataStatus != value) {
        cellularState = cellularState.copy(dataStatus = value)
        onStateChanged()
      }
    }

  var meterStatus: CellularMeterStatus
    get() = cellularState.meterStatus
    set(value) {
      if (cellularState.meterStatus != value) {
        cellularState = cellularState.copy(meterStatus = value)
        onStateChanged()
      }
    }

  lateinit var networkTypeComboBox: Cell<ComboBox<CellularStandard>>
    private set

  lateinit var signalStrengthComboBox: Cell<ComboBox<CellularSignal>>
    private set

  lateinit var voiceStatusComboBox: Cell<ComboBox<CellularStatus>>
    private set

  lateinit var dataStatusComboBox: Cell<ComboBox<CellularStatus>>
    private set

  lateinit var meterStatusComboBox: Cell<ComboBox<CellularMeterStatus>>
    private set

  private var isUpdatingUi = false

  /** Creates contents of the dialog. */
  fun createPanel(): DialogPanel {
    return panel {
      row {
        panel {
            row { networkTypeComboBox = enumComboBox(message("cellular.dialog.label.network.type"), ::networkType) }
            row { voiceStatusComboBox = enumComboBox(message("cellular.dialog.label.voice.status"), ::voiceStatus) }
            row { meterStatusComboBox = enumComboBox(message("cellular.dialog.label.meter.status"), ::meterStatus) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)

        panel {
            row { signalStrengthComboBox = enumComboBox(message("cellular.dialog.label.signal.strength"), ::signalStrength) }
            row {
              dataStatusComboBox = enumComboBox(message("cellular.dialog.label.data.status"), ::dataStatus) { it.dataDisplayValue }
            }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
      }
    }
  }

  private inline fun <reified T : Enum<T>> Row.enumComboBox(
    label: @NlsContexts.Label String,
    property: KMutableProperty0<T>,
    crossinline displayText: (T) -> String = { it.toString() },
  ): Cell<ComboBox<T>> {
    return comboBox(EnumComboBoxModel(T::class.java), renderer = textListCellRenderer { it?.let(displayText) ?: "" })
      .label(label, LabelPosition.TOP)
      .align(AlignX.FILL)
      .applyToComponent {
        name = "${property.name}ComboBox"
        selectedItem = property.get()
        setMinimumAndPreferredWidth(JBUIScale.scale(COMBO_BOX_WIDTH))
        addItemListener {
          if (!isUpdatingUi && it.stateChange == ItemEvent.SELECTED) {
            (it.item as? T)?.let { value -> property.set(value) }
          }
        }
      }
  }

  private fun onStateChanged() {
    stateModified = true
    updateUiFromState()
    applyCellInfo()
  }

  private fun updateUiFromState() {
    isUpdatingUi = true
    try {
      if (::networkTypeComboBox.isInitialized && networkTypeComboBox.component.selectedItem != cellularState.networkType) {
        networkTypeComboBox.component.selectedItem = cellularState.networkType
      }
      if (::signalStrengthComboBox.isInitialized && signalStrengthComboBox.component.selectedItem != cellularState.signalStrength) {
        signalStrengthComboBox.component.selectedItem = cellularState.signalStrength
      }
      if (::voiceStatusComboBox.isInitialized && voiceStatusComboBox.component.selectedItem != cellularState.voiceStatus) {
        voiceStatusComboBox.component.selectedItem = cellularState.voiceStatus
      }
      if (::dataStatusComboBox.isInitialized && dataStatusComboBox.component.selectedItem != cellularState.dataStatus) {
        dataStatusComboBox.component.selectedItem = cellularState.dataStatus
      }
      if (::meterStatusComboBox.isInitialized && meterStatusComboBox.component.selectedItem != cellularState.meterStatus) {
        meterStatusComboBox.component.selectedItem = cellularState.meterStatus
      }
    } finally {
      isUpdatingUi = false
    }
  }

  private fun applyCellInfo() {
    cellInfoChannel.trySend(cellularState.toCellInfo())
  }

  /** Creates the dialog wrapper. */
  fun createWrapper(project: Project? = null, parent: Component? = null): DialogWrapper {
    val dialogPanel = createPanel()
    return dialog(
        title = message("cellular.dialog.title", emulator.emulatorId.avdName),
        resizable = true,
        panel = dialogPanel,
        project = project,
        parent = parent,
        createActions = { listOf(CloseDialogAction(dialogPanel, isDefault = true)) },
      )
      .apply {
        setInitialLocationCallback {
          val dimensionService = DimensionService.getInstance()
          val savedSize = dimensionService.getSize(DIMENSION_SERVICE_KEY, project)
          if (savedSize != null) {
            setSize(savedSize.width, savedSize.height)
          }
          return@setInitialLocationCallback dimensionService.getLocation(DIMENSION_SERVICE_KEY, project)
        }

        Disposer.register(disposable) {
          cellInfoChannel.close()
          val dimensionService = DimensionService.getInstance()
          dimensionService.setLocation(DIMENSION_SERVICE_KEY, location, project)
          dimensionService.setSize(DIMENSION_SERVICE_KEY, size, project)
        }
      }
  }

  companion object {
    @NonNls private val DIMENSION_SERVICE_KEY = "#${CellularDialog::class.qualifiedName}"
    private const val COMBO_BOX_WIDTH = 220

    /** Shows the [CellularDialog] for the given [emulator]. */
    fun showCellularDialog(emulator: EmulatorController, project: Project? = null, parent: Component? = null) {
      CellularDialog(emulator).createWrapper(project, parent).show()
    }
  }
}

/** Converts [CellularState] to Modem service's [CellInfo] proto message. */
private fun CellularState.toCellInfo(): CellInfo {
  val signal = CellSignalStrength.newBuilder().setLevel(CellSignalStrength.CellSignalLevel.forNumber(signalStrength.ordinal)).build()
  return CellInfo.newBuilder()
    .setCellStandard(CellInfo.CellStandard.forNumber(networkType.ordinal + 1))
    .setCellStatusVoice(CellInfo.CellStatus.forNumber(voiceStatus.ordinal + 1))
    .setCellStatusData(CellInfo.CellStatus.forNumber(dataStatus.ordinal + 1))
    .setCellSignalStrength(signal)
    .setCellMeterStatus(CellInfo.CellMeterStatus.forNumber(meterStatus.ordinal + 1))
    .build()
}

/** Converts Modem service's [CellInfo] proto message to [CellularState]. */
private fun CellInfo.toCellularState(): CellularState {
  val network = CellularStandard.entries.getOrNull(cellStandard.number - 1) ?: CellularStandard.FULL
  val voice = CellularStatus.entries.getOrNull(cellStatusVoice.number - 1) ?: CellularStatus.HOME
  val data = CellularStatus.entries.getOrNull(cellStatusData.number - 1) ?: CellularStatus.HOME
  val signal =
    if (hasCellSignalStrength()) {
      CellularSignal.entries.getOrNull(cellSignalStrength.level.number) ?: CellularSignal.MODERATE
    } else {
      CellularSignal.MODERATE
    }
  val meter = CellularMeterStatus.entries.getOrNull(cellMeterStatus.number - 1) ?: CellularMeterStatus.METERED
  return CellularState(networkType = network, signalStrength = signal, voiceStatus = voice, dataStatus = data, meterStatus = meter)
}
