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

import com.android.emulator.control.ParameterValue
import com.android.emulator.control.PhysicalModelValue
import com.android.emulator.control.PhysicalModelValue.PhysicalType
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.cleanNegativeZero
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for configuring emulator virtual sensors (ambient temperature, magnetic field, proximity, light, pressure, relative humidity, and
 * heart rate), providing functionality similar to the Additional sensors tab in extended controls.
 */
internal class SensorsDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("sensors.dialog.title", emulator.emulatorId.avdName)) {

  private val hasHeartRateSensor = emulator.emulatorConfig.hasHeartRateSensor

  override val dimensionServiceKey: String
    get() = if (hasHeartRateSensor) "${super.dimensionServiceKey}.heartRate" else super.dimensionServiceKey

  private val temperatureSensor = SingleValueSensor(PhysicalType.TEMPERATURE, MIN_TEMPERATURE, MAX_TEMPERATURE, unitIncrement = 5f)
  private val proximitySensor = SingleValueSensor(PhysicalType.PROXIMITY, MIN_PROXIMITY, MAX_PROXIMITY, unitIncrement = 0.1f)
  private val lightSensor = SingleValueSensor(PhysicalType.LIGHT, MIN_LIGHT, MAX_LIGHT, stepsPerUnit = 1, unitIncrement = 400f)
  private val pressureSensor = SingleValueSensor(PhysicalType.PRESSURE, MIN_PRESSURE, MAX_PRESSURE, unitIncrement = 10f)
  private val humiditySensor = SingleValueSensor(PhysicalType.HUMIDITY, MIN_HUMIDITY, MAX_HUMIDITY, unitIncrement = 1f)
  private val heartRateSensor =
    SingleValueSensor(PhysicalType.HEART_RATE, MIN_HEART_RATE, MAX_HEART_RATE, unitIncrement = 5f, enabled = hasHeartRateSensor)

  private var magneticField: Vec3 = Vec3.ZERO
  private var magneticFieldInitialized: Boolean = false
  private var magneticFieldModified: Boolean = false

  private val magneticFieldChannel =
    createSensorChannel(
      type = PhysicalType.MAGNETIC_FIELD,
      onStateLoaded = { paramValue ->
        if (!magneticFieldModified && paramValue.dataCount >= 3) {
          magneticField =
            Vec3(
              paramValue.getData(0).coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
              paramValue.getData(1).coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
              paramValue.getData(2).coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
            )
        }
        magneticFieldInitialized = true
      },
    )

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    val sampleTextField = JBTextField("40000.0")
    val textFieldWidth = sampleTextField.preferredSize.width
    val textFieldPaddedWidth = textFieldWidth - sampleTextField.insets.left - sampleTextField.insets.right
    val sliderWidth = calculateSliderWidth(textFieldPaddedWidth)
    return panel {
      row {
          label(message("sensors.dialog.label.ambient.temperature")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP, right = COLUMN_GAP))
          label(message("sensors.dialog.label.magnetic.field")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP))
        }
        .layout(RowLayout.PARENT_GRID)
      row {
          createSliderPanel(
              sliderName = "temperatureSlider",
              fieldName = "temperatureField",
              accessibleLabel = message("sensors.dialog.label.ambient.temperature").removeSuffix(":"),
              sensor = temperatureSensor,
              sliderWidth = sliderWidth,
              textFieldWidth = textFieldWidth,
            )
            .align(AlignX.FILL)
            .resizableColumn()
            .customize(UnscaledGaps(right = COLUMN_GAP))
          panel {
              row {
                createMagneticFieldCell(
                    fieldName = "magNorthField",
                    accessibleName = message("sensors.dialog.accessible.magnetic.north"),
                    textFieldWidth = textFieldWidth,
                    getter = { magneticField.x },
                    setter = { setMagneticFieldFromUi(magneticField.copy(x = it)) },
                  )
                  .resizableColumn()
                createMagneticFieldCell(
                    fieldName = "magEastField",
                    accessibleName = message("sensors.dialog.accessible.magnetic.east"),
                    textFieldWidth = textFieldWidth,
                    getter = { magneticField.y },
                    setter = { setMagneticFieldFromUi(magneticField.copy(y = it)) },
                  )
                  .align(AlignX.CENTER)
                  .resizableColumn()
                createMagneticFieldCell(
                    fieldName = "magVerticalField",
                    accessibleName = message("sensors.dialog.accessible.magnetic.up"),
                    textFieldWidth = textFieldWidth,
                    getter = { magneticField.z },
                    setter = { setMagneticFieldFromUi(magneticField.copy(z = it)) },
                  )
                  .align(AlignX.RIGHT)
                  .resizableColumn()
              }
            }
            .align(AlignX.FILL)
            .resizableColumn()
        }
        .layout(RowLayout.PARENT_GRID)
      row {
          label(message("sensors.dialog.label.proximity")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP, right = COLUMN_GAP))
          label(message("sensors.dialog.label.light")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP))
        }
        .layout(RowLayout.PARENT_GRID)
      row {
          createSliderPanel(
              sliderName = "proximitySlider",
              fieldName = "proximityField",
              accessibleLabel = message("sensors.dialog.label.proximity").removeSuffix(":"),
              sensor = proximitySensor,
              sliderWidth = sliderWidth,
              textFieldWidth = textFieldWidth,
            )
            .align(AlignX.FILL)
            .resizableColumn()
            .customize(UnscaledGaps(right = COLUMN_GAP))
          createSliderPanel(
              sliderName = "lightSlider",
              fieldName = "lightField",
              accessibleLabel = message("sensors.dialog.label.light").removeSuffix(":"),
              sensor = lightSensor,
              sliderWidth = sliderWidth,
              textFieldWidth = textFieldWidth,
            )
            .align(AlignX.FILL)
            .resizableColumn()
        }
        .layout(RowLayout.PARENT_GRID)
      row {
          label(message("sensors.dialog.label.pressure")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP, right = COLUMN_GAP))
          label(message("sensors.dialog.label.humidity")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP))
        }
        .layout(RowLayout.PARENT_GRID)
      row {
          createSliderPanel(
              sliderName = "pressureSlider",
              fieldName = "pressureField",
              accessibleLabel = message("sensors.dialog.label.pressure").removeSuffix(":"),
              sensor = pressureSensor,
              sliderWidth = sliderWidth,
              textFieldWidth = textFieldWidth,
            )
            .align(AlignX.FILL)
            .resizableColumn()
            .customize(UnscaledGaps(right = COLUMN_GAP))
          createSliderPanel(
              sliderName = "humiditySlider",
              fieldName = "humidityField",
              accessibleLabel = message("sensors.dialog.label.humidity").removeSuffix(":"),
              sensor = humiditySensor,
              sliderWidth = sliderWidth,
              textFieldWidth = textFieldWidth,
            )
            .align(AlignX.FILL)
            .resizableColumn()
        }
        .layout(RowLayout.PARENT_GRID)
      if (hasHeartRateSensor) {
        row {
            label(message("sensors.dialog.label.heart.rate")).customize(UnscaledGaps(bottom = LABEL_BOTTOM_GAP, right = COLUMN_GAP))
            cell()
          }
          .layout(RowLayout.PARENT_GRID)
        row {
            createSliderPanel(
                sliderName = "heartRateSlider",
                fieldName = "heartRateField",
                accessibleLabel = message("sensors.dialog.label.heart.rate").removeSuffix(":"),
                sensor = heartRateSensor,
                sliderWidth = sliderWidth,
                textFieldWidth = textFieldWidth,
              )
              .align(AlignX.FILL)
              .resizableColumn()
              .customize(UnscaledGaps(right = COLUMN_GAP))
            cell()
          }
          .layout(RowLayout.PARENT_GRID)
      }
    }
  }

  private fun calculateSliderWidth(textFieldPaddedWidth: Int): Int {
    val defaultGap = JBUIScale.scale(DEFAULT_HORIZONTAL_GAP)
    val sliderVisualPadding = JBUIScale.scale(SLIDER_VISUAL_PADDING * 2)
    val labelKeys =
      listOf(
        "sensors.dialog.label.ambient.temperature",
        "sensors.dialog.label.magnetic.field",
        "sensors.dialog.label.proximity",
        "sensors.dialog.label.light",
        "sensors.dialog.label.pressure",
        "sensors.dialog.label.humidity",
        "sensors.dialog.label.heart.rate",
      )
    val maxLabelWidth = labelKeys.maxOf { JLabel(message(it)).preferredSize.width }
    val minRowWidth = maxOf(3 * textFieldPaddedWidth + 2 * defaultGap, maxLabelWidth)
    return maxOf(JBUIScale.scale(SLIDER_WIDTH), minRowWidth - textFieldPaddedWidth - defaultGap + sliderVisualPadding)
  }

  private fun Row.createSliderPanel(
    sliderName: String,
    fieldName: String,
    accessibleLabel: String,
    sensor: SingleValueSensor,
    sliderWidth: Int,
    textFieldWidth: Int,
  ) = panel {
    val stepsPerUnit = sensor.stepsPerUnit
    val minSteps = (sensor.minValue * stepsPerUnit).roundToInt()
    val maxSteps = (sensor.maxValue * stepsPerUnit).roundToInt()
    val unitStep = (sensor.unitIncrement * stepsPerUnit).roundToInt().coerceAtLeast(1)
    row {
      slider(minSteps, maxSteps, 0, 0).accessibleName(accessibleLabel).align(AlignX.FILL).resizableColumn().applyToComponent {
        name = sliderName
        paintLabels = false
        paintTicks = false
        value = (sensor.value * stepsPerUnit).roundToInt().coerceIn(minSteps, maxSteps)
        isEnabled = sensor.initialized
        preferredSize = Dimension(sliderWidth, preferredSize.height)
        minimumSize = Dimension(sliderWidth, minimumSize.height)
        // JSlider is rendered with some internal margins that make it appear misaligned compared to other widgets.
        // Setting this property fixes that.
        putClientProperty(
          DslComponentProperty.VISUAL_PADDINGS,
          UnscaledGaps(left = SLIDER_VISUAL_PADDING, right = SLIDER_VISUAL_PADDING),
        )
        actionMap.put(
          "positiveUnitIncrement",
          object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
              value = (value + unitStep).coerceIn(minSteps, maxSteps)
            }
          },
        )
        actionMap.put(
          "negativeUnitIncrement",
          object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
              value = (value - unitStep).coerceIn(minSteps, maxSteps)
            }
          },
        )
        addChangeListener {
          if (!isUpdatingUi) {
            val newVal = (value.toFloat() / stepsPerUnit).coerceIn(sensor.minValue, sensor.maxValue)
            sensor.setFromUi(newVal)
          }
        }
        registerUiUpdater {
          isEnabled = sensor.initialized
          val targetStep = (sensor.value * stepsPerUnit).roundToInt().coerceIn(minSteps, maxSteps)
          if (value != targetStep) {
            value = targetStep
          }
        }
      }
      textField().accessibleName(accessibleLabel).applyToComponent {
        configureNumericField(
          fieldName = fieldName,
          textFieldWidth = textFieldWidth,
          minVal = sensor.minValue,
          maxVal = sensor.maxValue,
          isInitialized = { sensor.initialized },
          getter = { sensor.value },
          setter = sensor::setFromUi,
        )
      }
    }
  }

  private fun Row.createMagneticFieldCell(
    fieldName: String,
    accessibleName: String,
    textFieldWidth: Int,
    getter: () -> Float,
    setter: (Float) -> Unit,
  ) =
    textField().accessibleName(accessibleName).applyToComponent {
      configureNumericField(
        fieldName = fieldName,
        textFieldWidth = textFieldWidth,
        minVal = MIN_MAGNETIC_FIELD,
        maxVal = MAX_MAGNETIC_FIELD,
        isInitialized = { magneticFieldInitialized },
        getter = getter,
        setter = setter,
      )
    }

  private fun JTextField.configureNumericField(
    fieldName: String,
    textFieldWidth: Int,
    minVal: Float,
    maxVal: Float,
    isInitialized: () -> Boolean,
    getter: () -> Float,
    setter: (Float) -> Unit,
  ) {
    name = fieldName
    horizontalAlignment = SwingConstants.RIGHT
    columns = 0
    preferredSize = Dimension(textFieldWidth, preferredSize.height)
    minimumSize = Dimension(textFieldWidth, minimumSize.height)
    val initialized = isInitialized()
    text = if (initialized) formatSensorValue(getter()) else ""
    isEnabled = initialized
    var updatingFromThisField = false

    document.addDocumentListener(
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          if (!isUpdatingUi && !updatingFromThisField) {
            val parsed = text.trim().toFloatOrNull()
            if (parsed != null && parsed in minVal..maxVal) {
              updatingFromThisField = true
              try {
                setter(parsed)
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
          val parsed = text.trim().toFloatOrNull()
          if (parsed != null && parsed.isFinite()) {
            val clamped = parsed.coerceIn(minVal, maxVal)
            setter(formatSensorValue(clamped).toFloat().coerceIn(minVal, maxVal))
          }
          val formatted = formatSensorValue(getter())
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
        override fun focusLost(e: FocusEvent) {
          commitText()
        }
      }
    )

    registerUiUpdater {
      val ready = isInitialized()
      isEnabled = ready
      if (ready && !updatingFromThisField) {
        val formatted = formatSensorValue(getter())
        if (text != formatted) {
          text = formatted
        }
      }
    }
  }

  private fun setMagneticFieldFromUi(value: Vec3) {
    val clamped =
      Vec3(
        value.x.coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
        value.y.coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
        value.z.coerceIn(MIN_MAGNETIC_FIELD, MAX_MAGNETIC_FIELD),
      )
    if (magneticField != clamped) {
      clearError()
      magneticField = clamped
      magneticFieldModified = true
      stateModified = true
      updateUi()
      magneticFieldChannel.trySend(ParameterValue.newBuilder().addData(clamped.x).addData(clamped.y).addData(clamped.z).build())
    }
  }

  private fun createSensorChannel(
    type: PhysicalType,
    onStateLoaded: (ParameterValue) -> Unit,
  ): SendChannel<ParameterValue> {
    val channel = Channel<ParameterValue>(Channel.CONFLATED)
    val initialStateJob = coroutineScope.launch {
      try {
        val modelValue = emulator.getPhysicalModel(type)
        withContext(uiContext) {
          onStateLoaded(modelValue.value)
          updateUi()
        }
      } catch (e: Exception) {
        e.throwIfCancellation()
        withContext(uiContext) {
          showError(e.message ?: e.javaClass.simpleName)
        }
      }
    }
    onDispose {
      initialStateJob.cancel()
      channel.close()
    }
    coroutineScope.launch {
      initialStateJob.join()
      for (value in channel) {
        try {
          val modelValue = PhysicalModelValue.newBuilder().setTarget(type).setValue(value).build()
          emulator.setPhysicalModel(modelValue)
        } catch (e: Exception) {
          e.throwIfCancellation()
          // Error is already logged by EmulatorController.
        }
      }
    }
    return channel
  }

  private inner class SingleValueSensor(
    val type: PhysicalType,
    val minValue: Float,
    val maxValue: Float,
    val stepsPerUnit: Int = SLIDER_STEPS_PER_UNIT,
    val unitIncrement: Float = 1f,
    enabled: Boolean = true,
  ) {
    var value: Float = 0f
      private set

    var initialized: Boolean = false
      private set

    private var modified: Boolean = false
    private val channel: SendChannel<ParameterValue>? =
      if (enabled) {
        createSensorChannel(
          type = type,
          onStateLoaded = { paramValue ->
            if (!modified && paramValue.dataCount >= 1) {
              value = paramValue.getData(0).coerceIn(minValue, maxValue)
            }
            initialized = true
          },
        )
      } else {
        null
      }

    fun setFromUi(newValue: Float) {
      val clamped = newValue.coerceIn(minValue, maxValue)
      if (!initialized || value != clamped) {
        clearError()
        value = clamped
        initialized = true
        modified = true
        stateModified = true
        updateUi()
        channel?.trySend(ParameterValue.newBuilder().addData(clamped).build())
      }
    }
  }

  companion object {
    internal const val SLIDER_STEPS_PER_UNIT = 10

    internal const val MIN_TEMPERATURE = -273.1f
    internal const val MAX_TEMPERATURE = 100.0f

    internal const val MIN_MAGNETIC_FIELD = -1000.0f
    internal const val MAX_MAGNETIC_FIELD = 1000.0f

    internal const val MIN_PROXIMITY = 0.0f
    internal const val MAX_PROXIMITY = 10.0f

    internal const val MIN_LIGHT = 0.0f
    internal const val MAX_LIGHT = 40000.0f

    internal const val MIN_PRESSURE = 0.0f
    internal const val MAX_PRESSURE = 1100.0f

    internal const val MIN_HUMIDITY = 0.0f
    internal const val MAX_HUMIDITY = 100.0f

    internal const val MIN_HEART_RATE = 0.0f
    internal const val MAX_HEART_RATE = 500.0f

    private const val SLIDER_WIDTH = 180
    private const val SLIDER_VISUAL_PADDING = 11
    private const val DEFAULT_HORIZONTAL_GAP = 16
    private const val COLUMN_GAP = 30
    private const val LABEL_BOTTOM_GAP = 4

    private fun formatSensorValue(value: Float): String = String.format(Locale.ROOT, "%.1f", value).cleanNegativeZero()
  }
}
