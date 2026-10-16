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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import java.awt.Dimension
import javax.swing.SwingConstants

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
                  registerUiUpdater {
                    if (value != chargeLevel) {
                      value = chargeLevel
                    }
                  }
                }
              textField().applyToComponent {
                name = "chargeLevelField"
                isEditable = false
                isFocusable = false
                horizontalAlignment = SwingConstants.RIGHT
                columns = 4
                text = "$chargeLevel%"
                registerUiUpdater {
                  val newText = "$chargeLevel%"
                  if (text != newText) {
                    text = newText
                  }
                }
              }
            }
            row { enumComboBox(message("battery.dialog.label.battery.health"), ::health, COMBO_BOX_WIDTH) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
          .resizableColumn()

        panel {
            row { enumComboBox(message("battery.dialog.label.charger.connection"), ::charger, COMBO_BOX_WIDTH) }
            row { enumComboBox(message("battery.dialog.label.battery.status"), ::status, COMBO_BOX_WIDTH) }
          }
          .align(AlignY.TOP)
          .align(AlignX.FILL)
          .resizableColumn()
      }
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
