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
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/** Dialog for configuring emulator battery settings, providing functionality similar to the Battery page in extended controls. */
internal class BatteryDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("battery.dialog.title", emulator.emulatorId.avdName)) {

  private var batteryInfo: BatteryInfo = BatteryInfo()

  private val batteryStateChannel =
    createStateChannel(
      getState = EmulatorController::getBattery,
      onStateLoaded = { state ->
        batteryInfo = state.toBatteryInfo()
        updateUi()
      },
      setState = EmulatorController::setBattery,
    )

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

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      row {
          panel {
              row {
                val label = message("battery.dialog.label.charge.level")
                val accessibleLabel = label.removeSuffix(":")
                slider(0, 100, 0, 0)
                  .label(label, LabelPosition.TOP)
                  .accessibleName(accessibleLabel)
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
                    registerUiUpdater {
                      if (value != chargeLevel) {
                        value = chargeLevel
                      }
                    }
                  }
                textField().accessibleName(accessibleLabel).gap(RightGap.SMALL).applyToComponent {
                  name = "chargeLevelField"
                  horizontalAlignment = SwingConstants.RIGHT
                  columns = 3
                  text = chargeLevel.toString()
                  var updatingFromThisField = false

                  document.addDocumentListener(
                    object : DocumentAdapter() {
                      override fun textChanged(event: DocumentEvent) {
                        if (!isUpdatingUi && !updatingFromThisField) {
                          val parsed = text.trim().toIntOrNull()
                          if (parsed != null && parsed in 0..100) {
                            updatingFromThisField = true
                            try {
                              chargeLevel = parsed
                            } finally {
                              updatingFromThisField = false
                            }
                          }
                        }
                      }
                    }
                  )

                  fun commitText() {
                    if (!isUpdatingUi && !updatingFromThisField) {
                      updatingFromThisField = true
                      try {
                        val parsed = text.trim().toIntOrNull()
                        if (parsed != null) {
                          chargeLevel = parsed.coerceIn(0, 100)
                        }
                        val formatted = chargeLevel.toString()
                        if (text != formatted) {
                          text = formatted
                        }
                      } finally {
                        updatingFromThisField = false
                      }
                    }
                  }

                  addActionListener { commitText() }
                  addFocusListener(
                    object : FocusAdapter() {
                      override fun focusLost(event: FocusEvent) {
                        commitText()
                      }
                    }
                  )

                  registerUiUpdater {
                    if (!updatingFromThisField) {
                      val newText = chargeLevel.toString()
                      if (text != newText) {
                        text = newText
                      }
                    }
                  }
                }
                label("%")
              }
            }
            .align(AlignY.TOP)
            .align(AlignX.FILL)
            .resizableColumn()

          panel {
              row { enumComboBox(message("battery.dialog.label.charger.connection"), ::charger, COMBO_BOX_WIDTH) }
            }
            .align(AlignY.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          enumComboBox(message("battery.dialog.label.battery.health"), ::health, COMBO_BOX_WIDTH).resizableColumn()
          enumComboBox(message("battery.dialog.label.battery.status"), ::status, COMBO_BOX_WIDTH).resizableColumn()
        }
        .layout(RowLayout.PARENT_GRID)
    }
  }

  private fun onStateChanged() {
    stateModified = true
    updateUi()
    batteryStateChannel.trySend(batteryInfo.toBatteryState())
  }

  companion object {
    private const val COMBO_BOX_WIDTH = 180
    private const val SLIDER_WIDTH = 120

    /** Converts [BatteryInfo] to the [BatteryState] proto message. */
    private fun BatteryInfo.toBatteryState(): BatteryState {
      return BatteryState.newBuilder()
        .setHasBattery(true)
        .setIsPresent(true)
        .setChargeLevel(chargeLevel)
        .setCharger(charger.protoValue)
        .setHealthValue(health.protoNumber)
        .setStatus(status.protoValue)
        .build()
    }

    /** Converts the [BatteryState] proto message to [BatteryInfo]. */
    private fun BatteryState.toBatteryInfo(): BatteryInfo {
      val level = chargeLevel.coerceIn(0, 100)
      val chargerConnection =
        ChargerConnection.entries.find { it.protoValue.number == chargerValue }
          ?: if (chargerValue > 0) ChargerConnection.AC else ChargerConnection.NONE
      val batteryHealth = BatteryHealth.entries.find { it.protoNumber == healthValue } ?: BatteryHealth.UNKNOWN
      val batteryStatus = BatteryStatus.entries.find { it.protoValue == status } ?: BatteryStatus.UNKNOWN
      return BatteryInfo(chargeLevel = level, charger = chargerConnection, health = batteryHealth, status = batteryStatus)
    }
  }

  /** Charger connection types supported by the emulator UI. */
  private enum class ChargerConnection(val protoValue: BatteryState.BatteryCharger, private val displayValue: String) {
    NONE(BatteryState.BatteryCharger.NONE, "None"),
    AC(BatteryState.BatteryCharger.AC, "AC charger");

    override fun toString(): String = displayValue
  }

  /** Battery health states supported by the emulator. */
  private enum class BatteryHealth(val protoNumber: Int, private val displayValue: String) {
    GOOD(BatteryState.BatteryHealth.GOOD_VALUE, "Good"),
    FAILED(BatteryState.BatteryHealth.FAILED_VALUE, "Failed"),
    DEAD(BatteryState.BatteryHealth.DEAD_VALUE, "Dead"),
    OVERVOLTAGE(BatteryState.BatteryHealth.OVERVOLTAGE_VALUE, "Overvoltage"),
    OVERHEATED(BatteryState.BatteryHealth.OVERHEATED_VALUE, "Overheated"),
    // BATTERY_HEALTH_UNKNOWN = 5 in the emulator's battery_agent.h, omitted from BatteryState.BatteryHealth proto.
    UNKNOWN(5, "Unknown");

    override fun toString(): String = displayValue
  }

  /** Battery charging status values supported by the emulator. */
  private enum class BatteryStatus(val protoValue: BatteryState.BatteryStatus, private val displayValue: String) {
    UNKNOWN(BatteryState.BatteryStatus.UNKNOWN, "Unknown"),
    CHARGING(BatteryState.BatteryStatus.CHARGING, "Charging"),
    DISCHARGING(BatteryState.BatteryStatus.DISCHARGING, "Discharging"),
    NOT_CHARGING(BatteryState.BatteryStatus.NOT_CHARGING, "Not charging"),
    FULL(BatteryState.BatteryStatus.FULL, "Full");

    override fun toString(): String = displayValue
  }

  /** Holds the state of battery settings. */
  private data class BatteryInfo(
    val chargeLevel: Int = 100,
    val charger: ChargerConnection = ChargerConnection.NONE,
    val health: BatteryHealth = BatteryHealth.GOOD,
    val status: BatteryStatus = BatteryStatus.NOT_CHARGING,
  )
}
