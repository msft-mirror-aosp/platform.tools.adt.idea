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

import com.android.emulator.control.BatteryState
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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.JSlider
import javax.swing.SwingConstants
import kotlin.reflect.KMutableProperty0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls

/** Dialog for configuring emulator battery settings, providing functionality similar to the Battery page in extended controls. */
internal class BatteryDialog(private val emulator: EmulatorController) {
  private var batteryInfo: BatteryInfo = BatteryInfo()

  private var stateModified = false
  private val batteryStateChannel = Channel<BatteryState>(Channel.CONFLATED)

  private var chargeLevel: Int
    get() = batteryInfo.chargeLevel
    set(value) {
      val clamped = value.coerceIn(0, 100)
      if (batteryInfo.chargeLevel != clamped) {
        batteryInfo = batteryInfo.copy(chargeLevel = clamped)
        onStateChanged()
      }
    }

  private var charger: ChargerConnection
    get() = batteryInfo.charger
    set(value) {
      if (batteryInfo.charger != value) {
        batteryInfo = batteryInfo.copy(charger = value)
        onStateChanged()
      }
    }

  private var health: BatteryHealth
    get() = batteryInfo.health
    set(value) {
      if (batteryInfo.health != value) {
        batteryInfo = batteryInfo.copy(health = value)
        onStateChanged()
      }
    }

  private var status: BatteryStatus
    get() = batteryInfo.status
    set(value) {
      if (batteryInfo.status != value) {
        batteryInfo = batteryInfo.copy(status = value)
        onStateChanged()
      }
    }

  private lateinit var chargeLevelSlider: Cell<JSlider>
  private lateinit var chargeLevelField: Cell<JBTextField>
  private lateinit var chargerComboBox: Cell<ComboBox<ChargerConnection>>
  private lateinit var healthComboBox: Cell<ComboBox<BatteryHealth>>
  private lateinit var statusComboBox: Cell<ComboBox<BatteryStatus>>

  private var isUpdatingUi = false

  init {
    emulator.createCoroutineScope().launch {
      try {
        val state = emulator.getBattery()
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
          if (!stateModified) {
            batteryInfo = state.toBatteryInfo()
            updateUiFromState()
          }
        }
      } catch (_: Exception) {
        // Error is already logged by EmulatorController.
      }
      for (state in batteryStateChannel) {
        try {
          emulator.setBattery(state)
        } catch (_: Exception) {
          // Error is already logged by EmulatorController.
        }
      }
    }
  }

  /** Creates contents of the dialog. */
  private fun createPanel(): DialogPanel {
    return panel {
      row {
        panel {
            row {
              val label = message("battery.dialog.label.charge.level")
              chargeLevelSlider =
                slider(0, 100, 0, 0)
                  .label(label, LabelPosition.TOP)
                  .accessibleName(label.removeSuffix(":"))
                  .align(AlignX.FILL)
                  .resizableColumn()
                  .applyToComponent {
                    name = "chargeLevelSlider"
                    paintLabels = false
                    paintTicks = false
                    value = chargeLevel
                    preferredSize = Dimension(JBUIScale.scale(SLIDER_WIDTH), preferredSize.height)
                    // JSlider is rendered with some internal margins that make it appear misaligned compared to other widgets.
                    // Setting this property fixes that.
                    putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(left = 11, right = 11))
                    addChangeListener {
                      if (!isUpdatingUi) {
                        chargeLevel = value
                      }
                    }
                  }
              chargeLevelField =
                textField().applyToComponent {
                  name = "chargeLevelField"
                  isEditable = false
                  isFocusable = false
                  horizontalAlignment = SwingConstants.RIGHT
                  columns = 4
                  text = "$chargeLevel%"
                }
            }
            row { healthComboBox = enumComboBox(message("battery.dialog.label.battery.health"), ::health) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
          .resizableColumn()

        panel {
            row { chargerComboBox = enumComboBox(message("battery.dialog.label.charger.connection"), ::charger) }
            row { statusComboBox = enumComboBox(message("battery.dialog.label.battery.status"), ::status) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
          .resizableColumn()
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
    applyBatteryState()
  }

  private fun updateUiFromState() {
    isUpdatingUi = true
    try {
      if (::chargeLevelSlider.isInitialized && chargeLevelSlider.component.value != batteryInfo.chargeLevel) {
        chargeLevelSlider.component.value = batteryInfo.chargeLevel
      }
      if (::chargeLevelField.isInitialized) {
        val text = "${batteryInfo.chargeLevel}%"
        if (chargeLevelField.component.text != text) {
          chargeLevelField.component.text = text
        }
      }
      if (::chargerComboBox.isInitialized && chargerComboBox.component.selectedItem != batteryInfo.charger) {
        chargerComboBox.component.selectedItem = batteryInfo.charger
      }
      if (::healthComboBox.isInitialized && healthComboBox.component.selectedItem != batteryInfo.health) {
        healthComboBox.component.selectedItem = batteryInfo.health
      }
      if (::statusComboBox.isInitialized && statusComboBox.component.selectedItem != batteryInfo.status) {
        statusComboBox.component.selectedItem = batteryInfo.status
      }
    } finally {
      isUpdatingUi = false
    }
  }

  private fun applyBatteryState() {
    batteryStateChannel.trySend(batteryInfo.toBatteryState())
  }

  /** Creates the dialog wrapper. */
  fun createWrapper(project: Project? = null, parent: Component? = null): DialogWrapper {
    val dialogPanel = createPanel()
    return dialog(
        title = message("battery.dialog.title", emulator.emulatorId.avdName),
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
          batteryStateChannel.close()
          val dimensionService = DimensionService.getInstance()
          dimensionService.setLocation(DIMENSION_SERVICE_KEY, location, project)
          dimensionService.setSize(DIMENSION_SERVICE_KEY, size, project)
        }
      }
  }

  companion object {
    @NonNls private val DIMENSION_SERVICE_KEY = "#${BatteryDialog::class.qualifiedName}"
    private const val COMBO_BOX_WIDTH = 180
    private const val SLIDER_WIDTH = 120

    /** Shows the [BatteryDialog] for the given [emulator]. */
    fun showBatteryDialog(emulator: EmulatorController, project: Project? = null, parent: Component? = null) {
      BatteryDialog(emulator).createWrapper(project, parent).show()
    }

    /** Converts [BatteryInfo] to the [BatteryState] proto message. */
    private fun BatteryInfo.toBatteryState(): BatteryState {
      return BatteryState.newBuilder()
        .setHasBattery(true)
        .setIsPresent(true)
        .setChargeLevel(chargeLevel)
        .setCharger(BatteryState.BatteryCharger.forNumber(charger.ordinal))
        .setHealthValue(health.ordinal)
        .setStatus(BatteryState.BatteryStatus.forNumber(status.ordinal))
        .build()
    }

    /** Converts the [BatteryState] proto message to [BatteryInfo]. */
    private fun BatteryState.toBatteryInfo(): BatteryInfo {
      val level = chargeLevel.coerceIn(0, 100)
      val chargerConnection = ChargerConnection.entries.getOrNull(chargerValue) ?: ChargerConnection.NONE
      val batteryHealth = BatteryHealth.entries.getOrNull(healthValue) ?: BatteryHealth.GOOD
      val batteryStatus = BatteryStatus.entries.getOrNull(statusValue) ?: BatteryStatus.CHARGING
      return BatteryInfo(chargeLevel = level, charger = chargerConnection, health = batteryHealth, status = batteryStatus)
    }
  }

  /** Charger connection types supported by the emulator UI. */
  private enum class ChargerConnection(private val displayValue: String) {
    NONE("None"),
    AC("AC charger");

    override fun toString(): String = displayValue
  }

  /** Battery health states supported by the emulator. */
  private enum class BatteryHealth(private val displayValue: String) {
    GOOD("Good"),
    FAILED("Failed"),
    DEAD("Dead"),
    OVERVOLTAGE("Overvoltage"),
    OVERHEATED("Overheated"),
    UNKNOWN("Unknown");

    override fun toString(): String = displayValue
  }

  /** Battery charging status values supported by the emulator. */
  private enum class BatteryStatus(private val displayValue: String) {
    UNKNOWN("Unknown"),
    CHARGING("Charging"),
    DISCHARGING("Discharging"),
    NOT_CHARGING("Not charging"),
    FULL("Full");

    override fun toString(): String = displayValue
  }

  /** Holds the state of battery settings. */
  private data class BatteryInfo(
    val chargeLevel: Int = 100,
    val charger: ChargerConnection = ChargerConnection.NONE,
    val health: BatteryHealth = BatteryHealth.GOOD,
    val status: BatteryStatus = BatteryStatus.CHARGING,
  )
}
