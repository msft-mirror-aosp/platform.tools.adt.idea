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

import com.android.emulation.control.incubating.VehiclePropStatus
import com.android.emulation.control.incubating.VehiclePropValue
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleGear
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleIgnitionState
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.ComboBoxModel
import javax.swing.JList
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.UIManager
import kotlin.math.roundToInt
import org.jetbrains.annotations.VisibleForTesting

/**
 * "Car sensor data" tab of [CarDataDialog], providing quick controls for vehicle speed, gear selection, ignition state, parking brake,
 * night mode, and low fuel indicator.
 */
internal class CarSensorDataTab(private val owner: CarDataDialog) {

  @VisibleForTesting
  enum class SpeedUnit(val toMetersPerSecond: Float, val minSpeed: Int, val maxSpeed: Int, val majorTickSpacing: Int) {
    MPH(MILES_PER_HOUR_TO_METERS_PER_SEC, MIN_SPEED_MPH, MAX_SPEED_MPH, MAJOR_TICK_SPACING_MPH),
    KMH(KILOMETERS_PER_HOUR_TO_METERS_PER_SEC, MIN_SPEED_KMH, MAX_SPEED_KMH, MAJOR_TICK_SPACING_KMH);

    val displayName: String
      get() =
        when (this) {
          MPH -> message("car.data.dialog.unit.mph")
          KMH -> message("car.data.dialog.unit.kmh")
        }
  }

  @VisibleForTesting
  enum class GearOption(val vhalValue: Int, val label: String) {
    NEUTRAL(VehicleGear.GEAR_NEUTRAL, message("car.data.dialog.gear.neutral")),
    REVERSE(VehicleGear.GEAR_REVERSE, message("car.data.dialog.gear.reverse")),
    PARK(VehicleGear.GEAR_PARK, message("car.data.dialog.gear.park")),
    DRIVE(VehicleGear.GEAR_DRIVE, message("car.data.dialog.gear.drive")),
    FIRST(VehicleGear.GEAR_1, message("car.data.dialog.gear.1")),
    SECOND(VehicleGear.GEAR_2, message("car.data.dialog.gear.2")),
    THIRD(VehicleGear.GEAR_3, message("car.data.dialog.gear.3")),
    FOURTH(VehicleGear.GEAR_4, message("car.data.dialog.gear.4")),
    FIFTH(VehicleGear.GEAR_5, message("car.data.dialog.gear.5")),
    SIXTH(VehicleGear.GEAR_6, message("car.data.dialog.gear.6")),
    SEVENTH(VehicleGear.GEAR_7, message("car.data.dialog.gear.7")),
    EIGHTH(VehicleGear.GEAR_8, message("car.data.dialog.gear.8")),
    NINTH(VehicleGear.GEAR_9, message("car.data.dialog.gear.9"));

    companion object {
      private val BY_VALUE = entries.associateBy { it.vhalValue }

      fun fromVhalValue(value: Int): GearOption? = BY_VALUE[value]
    }
  }

  @VisibleForTesting
  enum class IgnitionOption(val vhalValue: Int, val label: String) {
    UNDEFINED(VehicleIgnitionState.UNDEFINED, message("car.data.dialog.ignition.undefined")),
    LOCK(VehicleIgnitionState.LOCK, message("car.data.dialog.ignition.lock")),
    OFF(VehicleIgnitionState.OFF, message("car.data.dialog.ignition.off")),
    ACC(VehicleIgnitionState.ACC, message("car.data.dialog.ignition.acc")),
    ON(VehicleIgnitionState.ON, message("car.data.dialog.ignition.on")),
    START(VehicleIgnitionState.START, message("car.data.dialog.ignition.start"));

    companion object {
      private val BY_VALUE = entries.associateBy { it.vhalValue }

      fun fromVhalValue(value: Int): IgnitionOption? = BY_VALUE[value]
    }
  }

  private var speedMetersPerSecond: Float = 0f
  private var speedDisplaySupported: Boolean = false
  private var speedUnit: SpeedUnit = SpeedUnit.MPH
  private var selectedGear: GearOption = GearOption.PARK
  private var selectedIgnition: IgnitionOption = IgnitionOption.LOCK
  private var parkingBrakeOn: Boolean = false
  private var nightModeOn: Boolean = false
  private var fuelLevelLow: Boolean = false

  private var speedInitialized: Boolean = false
  private var gearInitialized: Boolean = false
  private var ignitionInitialized: Boolean = false
  private var parkingBrakeInitialized: Boolean = false
  private var nightModeInitialized: Boolean = false
  private var fuelLevelLowInitialized: Boolean = false

  private var isSyncingControls: Boolean = false

  private lateinit var speedSlider: JSlider
  private lateinit var speedField: JBTextField
  private lateinit var speedUnitComboBox: ComboBox<SpeedUnit>
  private lateinit var gearComboBox: ComboBox<GearOption>
  private lateinit var ignitionComboBox: ComboBox<IgnitionOption>
  private lateinit var parkingBrakeCheckBox: JBCheckBox
  private lateinit var nightModeCheckBox: JBCheckBox
  private lateinit var fuelLevelLowCheckBox: JBCheckBox

  fun createPanel(): DialogPanel {
    val initialSpeed = currentDisplaySpeed()
    return panel {
      row {
          panel {
              row {
                  slider(speedUnit.minSpeed, speedUnit.maxSpeed, 0, speedUnit.majorTickSpacing)
                    .label(message("car.data.dialog.label.car.speed"), LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .applyToComponent {
                      speedSlider = this
                      name = "carSpeedSlider"
                      value = initialSpeed.coerceIn(speedUnit.minSpeed, speedUnit.maxSpeed)
                      isEnabled = speedInitialized
                      paintLabels = false
                      paintTicks = true
                      accessibleContext.accessibleName = message("car.data.dialog.accessible.car.speed")
                      preferredSize = Dimension(JBUIScale.scale(SLIDER_WIDTH), preferredSize.height)
                      // JSlider is rendered with some internal margins that make it appear misaligned compared to other widgets.
                      // Setting this property fixes that.
                      putClientProperty(
                        DslComponentProperty.VISUAL_PADDINGS,
                        UnscaledGaps(left = SLIDER_VISUAL_PADDING, right = SLIDER_VISUAL_PADDING),
                      )
                      addChangeListener {
                        if (!isSyncingControls && speedInitialized) {
                          setDisplaySpeedFromUi(value)
                        }
                      }
                    }

                  cell(JBTextField(if (speedInitialized) initialSpeed.toString() else "")).gap(RightGap.SMALL).applyToComponent {
                    speedField = this
                    name = "carSpeedField"
                    columns = 3
                    horizontalAlignment = SwingConstants.RIGHT
                    isEnabled = speedInitialized
                    accessibleContext.accessibleName = message("car.data.dialog.accessible.car.speed")
                    val commitSpeed = {
                      if (!isSyncingControls && speedInitialized) {
                        val parsed = text.trim().toIntOrNull()
                        val current = currentDisplaySpeed()
                        if (parsed == current) {
                          owner.clearErrorMessage()
                          syncSpeedControls(parsed)
                        } else if (parsed == null || parsed !in speedUnit.minSpeed..speedUnit.maxSpeed) {
                          owner.showErrorMessage(message("car.data.dialog.error.speed.range", speedUnit.minSpeed, speedUnit.maxSpeed))
                          text = current.toString()
                        } else {
                          owner.clearErrorMessage()
                          setDisplaySpeedFromUi(parsed)
                        }
                      }
                    }
                    addActionListener { commitSpeed() }
                    addFocusListener(
                      object : FocusAdapter() {
                        override fun focusLost(e: FocusEvent) {
                          commitSpeed()
                        }
                      }
                    )
                    addKeyListener(
                      object : KeyAdapter() {
                        override fun keyPressed(event: KeyEvent) {
                          if (!speedInitialized || isSyncingControls || event.modifiersEx != 0) return
                          val step =
                            when (event.keyCode) {
                              KeyEvent.VK_UP,
                              KeyEvent.VK_KP_UP -> 1
                              KeyEvent.VK_DOWN,
                              KeyEvent.VK_KP_DOWN -> -1
                              else -> return
                            }
                          val minSpeed = speedUnit.minSpeed
                          val maxSpeed = speedUnit.maxSpeed
                          val current = currentDisplaySpeed()
                          val base = (text.trim().toIntOrNull() ?: current).coerceIn(minSpeed, maxSpeed)
                          val newSpeed = (base + step).coerceIn(minSpeed, maxSpeed)
                          if (newSpeed != current) {
                            setDisplaySpeedFromUi(newSpeed)
                          } else {
                            owner.clearErrorMessage()
                            syncSpeedControls(newSpeed)
                          }
                          event.consume()
                        }
                      }
                    )
                  }

                  cell(
                      FitContentComboBox(EnumComboBoxModel(SpeedUnit::class.java)).apply {
                        renderer = textListCellRenderer { it?.displayName.orEmpty() }
                      }
                    )
                    .applyToComponent {
                      speedUnitComboBox = this
                      name = "carSpeedUnitComboBox"
                      selectedItem = speedUnit
                      isEnabled = speedInitialized
                      accessibleContext.accessibleName = message("car.data.dialog.accessible.speed.unit")
                      addItemListener { event ->
                        if (!isSyncingControls && speedInitialized && event.stateChange == ItemEvent.SELECTED) {
                          (event.item as? SpeedUnit)?.let { newUnit ->
                            speedUnit = newUnit
                            owner.clearErrorMessage()
                            syncSpeedControls()
                          }
                        }
                      }
                    }
                }
                .layout(RowLayout.INDEPENDENT)
            }
            .align(AlignX.FILL)
            .resizableColumn()

          cell()
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          comboBox(EnumComboBoxModel(GearOption::class.java), renderer = textListCellRenderer { it?.label.orEmpty() })
            .label(message("car.data.dialog.label.gear"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              gearComboBox = this
              name = "gearComboBox"
              selectedItem = selectedGear
              isEnabled = gearInitialized
              setMinimumAndPreferredWidth(JBUIScale.scale(DROPDOWN_WIDTH))
              addItemListener { event ->
                if (!isSyncingControls && gearInitialized && event.stateChange == ItemEvent.SELECTED) {
                  (event.item as? GearOption)?.let { onGearSelectedFromUi(it) }
                }
              }
            }

          comboBox(EnumComboBoxModel(IgnitionOption::class.java), renderer = textListCellRenderer { it?.label.orEmpty() })
            .label(message("car.data.dialog.label.ignition.state"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              ignitionComboBox = this
              name = "ignitionComboBox"
              selectedItem = selectedIgnition
              isEnabled = ignitionInitialized
              setMinimumAndPreferredWidth(JBUIScale.scale(DROPDOWN_WIDTH))
              addItemListener { event ->
                if (!isSyncingControls && ignitionInitialized && event.stateChange == ItemEvent.SELECTED) {
                  (event.item as? IgnitionOption)?.let { onIgnitionSelectedFromUi(it) }
                }
              }
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          checkBox(message("car.data.dialog.checkbox.parking.brake")).applyToComponent {
            parkingBrakeCheckBox = this
            name = "parkingBrakeCheckBox"
            isSelected = parkingBrakeOn
            isEnabled = parkingBrakeInitialized
            addActionListener {
              if (!isSyncingControls && parkingBrakeInitialized) {
                parkingBrakeOn = isSelected
                owner.clearErrorMessage()
                owner.sendBooleanProperty(VehicleProperty.PARKING_BRAKE_ON, parkingBrakeOn)
              }
            }
          }

          checkBox(message("car.data.dialog.checkbox.night.mode")).applyToComponent {
            nightModeCheckBox = this
            name = "nightModeCheckBox"
            isSelected = nightModeOn
            isEnabled = nightModeInitialized
            addActionListener {
              if (!isSyncingControls && nightModeInitialized) {
                nightModeOn = isSelected
                owner.clearErrorMessage()
                owner.sendBooleanProperty(VehicleProperty.NIGHT_MODE, nightModeOn)
              }
            }
          }

          checkBox(message("car.data.dialog.checkbox.fuel.level.low")).applyToComponent {
            fuelLevelLowCheckBox = this
            name = "fuelLevelLowCheckBox"
            isSelected = fuelLevelLow
            isEnabled = fuelLevelLowInitialized
            addActionListener {
              if (!isSyncingControls && fuelLevelLowInitialized) {
                fuelLevelLow = isSelected
                owner.clearErrorMessage()
                owner.sendBooleanProperty(VehicleProperty.FUEL_LEVEL_LOW, fuelLevelLow)
              }
            }
          }
        }
        .layout(RowLayout.INDEPENDENT)
    }
      .apply { border = JBUI.Borders.empty(12, 4, 4, 4) }
  }

  private fun currentDisplaySpeed(): Int {
    return (speedMetersPerSecond / speedUnit.toMetersPerSecond).roundToInt()
  }

  private fun setDisplaySpeedFromUi(displaySpeed: Int) {
    val clamped = displaySpeed.coerceIn(speedUnit.minSpeed, speedUnit.maxSpeed)
    speedMetersPerSecond = clamped * speedUnit.toMetersPerSecond
    owner.clearErrorMessage()
    syncSpeedControls(clamped)
    sendSpeedProperties(speedMetersPerSecond)
  }

  private fun sendSpeedProperties(metersPerSecond: Float) {
    owner.sendFloatProperty(VehicleProperty.PERF_VEHICLE_SPEED, metersPerSecond)
    if (speedDisplaySupported) {
      owner.sendFloatProperty(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY, metersPerSecond, isOptionalCompanion = true)
    }
  }

  fun onSpeedDisplaySupported() {
    if (!speedDisplaySupported) {
      speedDisplaySupported = true
      owner.sendFloatProperty(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY, speedMetersPerSecond, isOptionalCompanion = true)
    }
  }

  fun onSpeedDisplayWriteFailed() {
    speedDisplaySupported = false
  }

  private fun onGearSelectedFromUi(gear: GearOption) {
    selectedGear = gear
    owner.clearErrorMessage()
    if (gear == GearOption.PARK && speedInitialized) {
      speedMetersPerSecond = 0f
      syncSpeedControls(0)
      sendSpeedProperties(0f)
    }
    owner.sendInt32Property(VehicleProperty.GEAR_SELECTION, gear.vhalValue)
  }

  private fun onIgnitionSelectedFromUi(ignition: IgnitionOption) {
    selectedIgnition = ignition
    owner.clearErrorMessage()
    owner.sendInt32Property(VehicleProperty.IGNITION_STATE, ignition.vhalValue)
  }

  private fun syncSpeedControls(displaySpeed: Int = currentDisplaySpeed()) {
    if (!::speedSlider.isInitialized || !::speedField.isInitialized || !::speedUnitComboBox.isInitialized) return
    isSyncingControls = true
    try {
      speedSlider.isEnabled = speedInitialized
      speedField.isEnabled = speedInitialized
      speedUnitComboBox.isEnabled = speedInitialized
      val clampedSliderValue = displaySpeed.coerceIn(speedUnit.minSpeed, speedUnit.maxSpeed)
      if (
        speedSlider.minimum != speedUnit.minSpeed || speedSlider.maximum != speedUnit.maxSpeed || speedSlider.value != clampedSliderValue
      ) {
        speedSlider.model.setRangeProperties(clampedSliderValue, 0, speedUnit.minSpeed, speedUnit.maxSpeed, false)
      }
      if (speedSlider.majorTickSpacing != speedUnit.majorTickSpacing) {
        speedSlider.majorTickSpacing = speedUnit.majorTickSpacing
      }
      val formatted = if (speedInitialized) displaySpeed.toString() else ""
      if (speedField.text != formatted) {
        speedField.text = formatted
      }
    } finally {
      isSyncingControls = false
    }
  }

  /** Updates UI controls if [propValue] corresponds to one of the car sensor properties. */
  fun applyPropertyValue(propValue: VehiclePropValue) {
    if (propValue.status != VehiclePropStatus.AVAILABLE) return
    isSyncingControls = true
    try {
      when (propValue.prop) {
        VehicleProperty.PERF_VEHICLE_SPEED -> {
          if (propValue.floatValuesCount > 0) {
            speedMetersPerSecond = propValue.getFloatValues(0)
            speedInitialized = true
            syncSpeedControls()
          }
        }
        VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY -> {
          speedDisplaySupported = true
        }
        VehicleProperty.GEAR_SELECTION -> {
          if (propValue.int32ValuesCount > 0) {
            gearInitialized = true
            if (::gearComboBox.isInitialized) {
              gearComboBox.isEnabled = true
            }
            GearOption.fromVhalValue(propValue.getInt32Values(0))?.let { gear ->
              selectedGear = gear
              if (::gearComboBox.isInitialized && gearComboBox.selectedItem != gear) {
                gearComboBox.selectedItem = gear
              }
            }
          }
        }
        VehicleProperty.IGNITION_STATE -> {
          if (propValue.int32ValuesCount > 0) {
            ignitionInitialized = true
            if (::ignitionComboBox.isInitialized) {
              ignitionComboBox.isEnabled = true
            }
            IgnitionOption.fromVhalValue(propValue.getInt32Values(0))?.let { ignition ->
              selectedIgnition = ignition
              if (::ignitionComboBox.isInitialized && ignitionComboBox.selectedItem != ignition) {
                ignitionComboBox.selectedItem = ignition
              }
            }
          }
        }
        VehicleProperty.PARKING_BRAKE_ON -> {
          if (propValue.int32ValuesCount > 0) {
            parkingBrakeOn = propValue.getInt32Values(0) != 0
            parkingBrakeInitialized = true
            if (::parkingBrakeCheckBox.isInitialized) {
              parkingBrakeCheckBox.isEnabled = true
              if (parkingBrakeCheckBox.isSelected != parkingBrakeOn) {
                parkingBrakeCheckBox.isSelected = parkingBrakeOn
              }
            }
          }
        }
        VehicleProperty.NIGHT_MODE -> {
          if (propValue.int32ValuesCount > 0) {
            nightModeOn = propValue.getInt32Values(0) != 0
            nightModeInitialized = true
            if (::nightModeCheckBox.isInitialized) {
              nightModeCheckBox.isEnabled = true
              if (nightModeCheckBox.isSelected != nightModeOn) {
                nightModeCheckBox.isSelected = nightModeOn
              }
            }
          }
        }
        VehicleProperty.FUEL_LEVEL_LOW -> {
          if (propValue.int32ValuesCount > 0) {
            fuelLevelLow = propValue.getInt32Values(0) != 0
            fuelLevelLowInitialized = true
            if (::fuelLevelLowCheckBox.isInitialized) {
              fuelLevelLowCheckBox.isEnabled = true
              if (fuelLevelLowCheckBox.isSelected != fuelLevelLow) {
                fuelLevelLowCheckBox.isSelected = fuelLevelLow
              }
            }
          }
        }
      }
    } finally {
      isSyncingControls = false
    }
  }

  private class FitContentComboBox<E>(model: ComboBoxModel<E>) : ComboBox<E>(model) {
    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      val renderer = renderer ?: return size
      @Suppress("UNCHECKED_CAST") val list = (popup?.list as? JList<E>) ?: JBList()
      var maxItemWidth = 0
      for (i in 0 until model.size) {
        val component = renderer.getListCellRendererComponent(list, model.getElementAt(i), -1, false, false)
        component.font = font
        maxItemWidth = maxOf(maxItemWidth, component.preferredSize.width)
      }
      val padding = UIManager.getInsets("ComboBox.padding") ?: JBUI.emptyInsets()
      val insets = insets
      val width =
        maxItemWidth +
          padding.left +
          JBUI.CurrentTheme.Component.ARROW_AREA_WIDTH.get() +
          maxOf(insets.left + insets.right, 2 * insets.right)
      return Dimension(width, size.height)
    }
  }

  companion object {
    private const val MIN_SPEED_MPH = -30
    private const val MAX_SPEED_MPH = 160
    private const val MAJOR_TICK_SPACING_MPH = 30
    private const val MIN_SPEED_KMH = -50
    private const val MAX_SPEED_KMH = 250
    private const val MAJOR_TICK_SPACING_KMH = 50
    private const val SLIDER_WIDTH = 140
    private const val SLIDER_VISUAL_PADDING = 11
    private const val DROPDOWN_WIDTH = 275

    private const val MILES_PER_HOUR_TO_METERS_PER_SEC = 1609.34f / 3600.0f
    private const val KILOMETERS_PER_HOUR_TO_METERS_PER_SEC = 1000.0f / 3600.0f
  }
}
