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

import com.android.SdkConstants.PRIMARY_DISPLAY_ID
import com.android.emulator.ImageConverter
import com.android.emulator.control.Image
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.ImageFormat.ImgFormat
import com.android.emulator.control.ParameterValue
import com.android.emulator.control.PhysicalModelValue
import com.android.emulator.control.PhysicalModelValue.PhysicalType
import com.android.emulator.control.SensorValue.SensorType
import com.android.ide.common.util.Cancelable
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.adtui.ImageUtils
import com.android.tools.idea.streaming.StagingIcons
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.core.cleanNegativeZero
import com.android.tools.idea.streaming.emulator.EmptyStreamObserver
import com.android.tools.idea.streaming.emulator.EmulatorConfiguration.PostureDescriptor
import com.android.tools.idea.streaming.emulator.EmulatorConfiguration.PostureDescriptor.ValueType
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.NotificationTracker
import com.android.tools.idea.streaming.emulator.dialogs.Device3DWidget.OperationMode
import com.android.utils.throwIfCancellation
import com.intellij.ide.setToolTipText
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import javax.swing.JButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for configuring emulator device pose (rotation, position, and folding/rollable state), providing functionality similar to the
 * Device Pose tab in extended controls.
 */
internal class DevicePoseDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("device.pose.dialog.title", emulator.emulatorId.avdName)) {

  private enum class DeviceOrientation {
    PORTRAIT,
    LANDSCAPE,
    REVERSE_PORTRAIT,
    REVERSE_LANDSCAPE,
  }

  private val isAutomotive = emulator.emulatorConfig.deviceType == DeviceType.AUTOMOTIVE

  override val dimensionServiceKey: String
    get() = if (isAutomotive) "${super.dimensionServiceKey}.automotive" else super.dimensionServiceKey

  private val postures = emulator.emulatorConfig.postures
  private val hasPostures = postures.isNotEmpty()
  private val isRollable = postures.firstOrNull()?.valueType == ValueType.ROLL_PERCENTAGE
  private val minFoldValue = postures.minOfOrNull { it.minValue }?.toFloat() ?: 0f
  private val maxFoldValue = postures.maxOfOrNull { it.maxValue }?.toFloat() ?: 180f

  private var operationMode: OperationMode = if (isAutomotive) OperationMode.MOVE else OperationMode.ROTATE

  /** Current Euler angles `(x, y, z)` in degrees `[-180, 180]`. */
  private var rotationDegrees: Vec3 = Vec3.ZERO

  /** Target device position `(x, y, z)` in meters in `[-0.1, 0.1]`. */
  private var positionMeters: Vec3 = Vec3.ZERO

  /** Current posture descriptor when [hasPostures] is true. */
  private var currentPosture: PostureDescriptor? = postures.lastOrNull()

  /** Current hinge angle in degrees or rollable percentage when [hasPostures] is true. */
  private var foldValue: Float = maxFoldValue

  private var accelerometerValues: Vec3 = Vec3.ZERO
  private var gyroscopeValues: Vec3 = Vec3.ZERO
  private var magnetometerValues: Vec3 = Vec3.ZERO
  private var deviceOrientation: DeviceOrientation? = null
  private var screenImage: BufferedImage? = null

  private var rotationModified: Boolean = false
  private var positionModified: Boolean = false
  private var foldModified: Boolean = false
  private var foldVersion: Long = 0
  private var appliedFoldVersion: Long = 0

  private val rotationChannel = Channel<Vec3>(Channel.CONFLATED)
  private val positionChannel = Channel<Vec3>(Channel.CONFLATED)
  private val foldChannel = Channel<Pair<Float, Long>>(Channel.CONFLATED)
  private val sensorRefreshChannel = Channel<Unit>(Channel.CONFLATED)

  @Volatile private var screenshotFeed: Cancelable? = null
  @Volatile private var screenshotReceiver: ScreenshotReceiver? = null

  private lateinit var controlsPanel: DialogPanel
  private var device3DWidget: Device3DWidget? = null

  init {
    if (!isAutomotive) {
      requestScreenshotFeed()
    }
    // Load initial state from the emulator and forward subsequent updates.
    val initialStateJob = coroutineScope.launch {
      try {
        val rotProto = emulator.getPhysicalModel(PhysicalType.ROTATION)
        val posProto = emulator.getPhysicalModel(PhysicalType.POSITION)
        val foldProto =
          if (hasPostures) {
            emulator.getPhysicalModel(if (isRollable) PhysicalType.ROLLABLE0 else PhysicalType.HINGE_ANGLE0)
          } else {
            null
          }
        val postureProto = if (hasPostures) emulator.getPhysicalModel(PhysicalType.POSTURE) else null
        withContext(uiContext) {
          applyInitialState(rotProto, posProto, foldProto, postureProto)
        }
        sensorRefreshChannel.trySend(Unit)
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
      }
    }
    val sensorRefreshJob = coroutineScope.launch {
      for (unit in sensorRefreshChannel) {
        refreshSensors()
      }
    }
    onDispose {
      cancelScreenshotFeed()
      initialStateJob.cancel()
      sensorRefreshJob.cancel()
      sensorRefreshChannel.close()
      rotationChannel.close()
      positionChannel.close()
      foldChannel.close()
    }

    coroutineScope.launch {
      for ((x, y, z) in rotationChannel) {
        try {
          val modelValue =
            PhysicalModelValue.newBuilder()
              .setTarget(PhysicalType.ROTATION)
              .setValue(ParameterValue.newBuilder().addData(x).addData(y).addData(z))
              .build()
          emulator.setPhysicalModel(modelValue)
          sensorRefreshChannel.trySend(Unit)
        } catch (t: Throwable) {
          t.throwIfCancellation()
        }
      }
    }

    coroutineScope.launch {
      for ((x, y, z) in positionChannel) {
        try {
          val modelValue =
            PhysicalModelValue.newBuilder()
              .setTarget(PhysicalType.POSITION)
              .setValue(ParameterValue.newBuilder().addData(x).addData(y).addData(z))
              .build()
          emulator.setPhysicalModel(modelValue)
        } catch (t: Throwable) {
          t.throwIfCancellation()
        }
      }
    }

    coroutineScope.launch {
      for ((fold, version) in foldChannel) {
        try {
          val type = if (isRollable) PhysicalType.ROLLABLE0 else PhysicalType.HINGE_ANGLE0
          val modelValue = PhysicalModelValue.newBuilder().setTarget(type).setValue(ParameterValue.newBuilder().addData(fold)).build()
          emulator.setPhysicalModel(modelValue)
          if (dialogWrapper != null) {
            val postureProto = emulator.getPhysicalModel(PhysicalType.POSTURE)
            val posture = postureFromProto(postureProto)
            withContext(uiContext) {
              if (version == foldVersion) {
                appliedFoldVersion = version
                if (posture != null) {
                  applyEmulatorPosture(posture)
                }
              }
            }
          }
        } catch (t: Throwable) {
          t.throwIfCancellation()
        }
      }
    }

    if (hasPostures) {
      val postureJob = coroutineScope.launch {
        NotificationTracker.forEmulator(emulator).currentPosture.collect { posture ->
          if (posture != null) {
            withContext(uiContext) {
              if (appliedFoldVersion == foldVersion) {
                applyEmulatorPosture(posture)
              }
            }
          }
        }
      }
      onDispose { postureJob.cancel() }
    }

    val sensorPollingJob = coroutineScope.launch {
      while (isActive) {
        delay(SENSOR_POLL_INTERVAL)
        sensorRefreshChannel.trySend(Unit)
      }
    }
    onDispose { sensorPollingJob.cancel() }
  }

  private fun applyInitialState(
    rotProto: PhysicalModelValue,
    posProto: PhysicalModelValue,
    foldProto: PhysicalModelValue?,
    postureProto: PhysicalModelValue?,
  ) {
    if (!rotationModified && rotProto.value.dataCount >= 3) {
      rotationDegrees =
        Vec3(
          rotProto.value.getData(0).coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
          rotProto.value.getData(1).coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
          rotProto.value.getData(2).coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
        )
    }
    if (!positionModified && posProto.value.dataCount >= 3) {
      positionMeters =
        Vec3(
          posProto.value.getData(0).coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
          posProto.value.getData(1).coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
          posProto.value.getData(2).coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
        )
    }
    if (hasPostures && !foldModified) {
      val matchedPosture = postureFromProto(postureProto) ?: NotificationTracker.forEmulator(emulator).currentPosture.value
      if (foldProto != null && foldProto.value.dataCount >= 1) {
        foldValue = foldProto.value.getData(0).coerceIn(minFoldValue, maxFoldValue)
      } else if (matchedPosture != null) {
        foldValue = defaultPostureValue(matchedPosture).toFloat().coerceIn(minFoldValue, maxFoldValue)
      }
      val resolvedPosture = matchedPosture ?: postures.find { foldValue >= it.minValue && foldValue <= it.maxValue }
      if (resolvedPosture != null) {
        currentPosture = resolvedPosture
      }
    }
    updateUi()
  }

  private fun postureFromProto(postureProto: PhysicalModelValue?): PostureDescriptor? {
    val postureNum = postureProto?.value?.takeIf { it.dataCount >= 1 }?.getData(0)?.roundToInt() ?: return null
    return postures.find { it.posture.number == postureNum }
  }

  private fun applyEmulatorPosture(posture: PostureDescriptor) {
    if (currentPosture != posture || foldValue < posture.minValue || foldValue > posture.maxValue) {
      currentPosture = posture
      if (foldValue < posture.minValue || foldValue > posture.maxValue) {
        foldValue = defaultPostureValue(posture).toFloat().coerceIn(minFoldValue, maxFoldValue)
      }
      updateUi()
    }
  }

  private suspend fun refreshSensors() {
    val accel = querySensor(SensorType.ACCELERATION)
    val gyro = querySensor(SensorType.GYROSCOPE)
    val mag = querySensor(SensorType.MAGNETIC_FIELD)
    if (accel != null || gyro != null || mag != null) {
      withContext(uiContext) {
        var changed = false
        if (accel != null) {
          val prevOrientation = deviceOrientation
          updateOrientationFromAccelerometer(accel)
          if (accelerometerValues != accel || deviceOrientation != prevOrientation) {
            accelerometerValues = accel
            changed = true
          }
        }
        if (gyro != null && gyroscopeValues != gyro) {
          gyroscopeValues = gyro
          changed = true
        }
        if (mag != null && magnetometerValues != mag) {
          magnetometerValues = mag
          changed = true
        }
        if (changed) {
          updateUi()
        }
      }
    }
  }

  private suspend fun querySensor(sensorType: SensorType): Vec3? = runCatching {
    val value = emulator.getSensor(sensorType).value
    if (value.dataCount >= 3) Vec3(value.getData(0), value.getData(1), value.getData(2)) else null
  }
    .onFailure {
      it.throwIfCancellation()
    }
    .getOrNull()

  private fun requestScreenshotFeed() {
    cancelScreenshotFeed()
    try {
      val imageFormat =
        ImageFormat.newBuilder()
          .setDisplay(PRIMARY_DISPLAY_ID)
          .setFormat(ImgFormat.RGB888)
          .setWidth(SCREENSHOT_MAX_SIZE)
          .setHeight(SCREENSHOT_MAX_SIZE)
          .build()
      val receiver = ScreenshotReceiver()
      screenshotReceiver = receiver
      screenshotFeed = emulator.streamScreenshot(imageFormat, receiver)
    } catch (t: Throwable) {
      t.throwIfCancellation()
      cancelScreenshotFeed()
    }
  }

  private fun cancelScreenshotFeed() {
    screenshotReceiver = null
    screenshotFeed?.cancel()
    screenshotFeed = null
  }

  private inner class ScreenshotReceiver : EmptyStreamObserver<Image>() {

    private val pendingScreenshot = AtomicReference<BufferedImage?>()

    override fun onNext(message: Image) {
      if (screenshotReceiver != this) {
        return
      }
      val imageFormat = message.format
      val width = imageFormat.width
      val height = imageFormat.height
      if (width <= 0 || height <= 0 || message.image.size() != width * height * 3) {
        return
      }

      @Suppress("UndesirableClassUsage") val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val pixels = (image.raster.dataBuffer as DataBufferInt).data
      ImageConverter.unpackRgb888(message.image, pixels)
      val unrotated = ImageUtils.rotateByQuadrants(image, -imageFormat.rotation.rotationValue)

      if (pendingScreenshot.getAndSet(unrotated) == null) {
        coroutineScope.launch(uiContext) {
          val latest = pendingScreenshot.getAndSet(null)
          if (screenshotReceiver == this@ScreenshotReceiver && latest != null) {
            screenImage = latest
            device3DWidget?.screenImage = latest
          }
        }
      }
    }

    override fun onError(t: Throwable) {
      if (screenshotReceiver == this && t is EmulatorController.RetryException) {
        coroutineScope.launch(uiContext) {
          if (screenshotReceiver == this@ScreenshotReceiver) {
            requestScreenshotFeed()
          }
        }
      }
    }
  }

  private fun updateOrientationFromAccelerometer(accel: Vec3) {
    val len = hypot(hypot(accel.x.toDouble(), accel.y.toDouble()), accel.z.toDouble()).toFloat()
    if (len <= 1e-6f) return
    val nx = accel.x / len
    val ny = accel.y / len
    val nz = accel.z / len
    val portraitAxis = if (isAutomotive) nz else ny
    deviceOrientation =
      when {
        abs(portraitAxis - 1f) < ACCELEROMETER_COARSE_THRESHOLD -> DeviceOrientation.PORTRAIT
        abs(nx - 1f) < ACCELEROMETER_COARSE_THRESHOLD -> DeviceOrientation.LANDSCAPE
        abs(portraitAxis + 1f) < ACCELEROMETER_COARSE_THRESHOLD -> DeviceOrientation.REVERSE_PORTRAIT
        abs(nx + 1f) < ACCELEROMETER_COARSE_THRESHOLD -> DeviceOrientation.REVERSE_LANDSCAPE
        else -> null
      }
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    controlsPanel =
      panel {
        if (!isAutomotive) {
          // Rotate sliders
          createSliderRow(
            labelText = message("device.pose.dialog.label.x.rot"),
            sliderName = "xRotSlider",
            fieldName = "xRotField",
            minVal = MIN_ROTATION_DEG,
            maxVal = MAX_ROTATION_DEG,
            getter = { rotationDegrees.x },
            setter = { setEulerRotationFromUi(rotationDegrees.copy(x = it)) },
            formatter = ::formatDegrees,
          )
          createSliderRow(
            labelText = message("device.pose.dialog.label.y.rot"),
            sliderName = "yRotSlider",
            fieldName = "yRotField",
            minVal = MIN_ROTATION_DEG,
            maxVal = MAX_ROTATION_DEG,
            getter = { rotationDegrees.y },
            setter = { setEulerRotationFromUi(rotationDegrees.copy(y = it)) },
            formatter = ::formatDegrees,
          )
          createSliderRow(
            labelText = message("device.pose.dialog.label.z.rot"),
            sliderName = "zRotSlider",
            fieldName = "zRotField",
            minVal = MIN_ROTATION_DEG,
            maxVal = MAX_ROTATION_DEG,
            getter = { rotationDegrees.z },
            setter = { setEulerRotationFromUi(rotationDegrees.copy(z = it)) },
            formatter = ::formatDegrees,
          )
        }

        if (hasPostures) {
          // Fold / Rollable controls
          createSliderRow(
            labelText = if (isRollable) message("device.pose.dialog.label.roll") else message("device.pose.dialog.label.hinge"),
            sliderName = "foldSlider",
            fieldName = "foldField",
            minVal = minFoldValue,
            maxVal = maxFoldValue,
            getter = { foldValue },
            setter = { setFoldValueFromUi(it) },
            formatter = if (isRollable) ::formatPercentage else ::formatDegrees,
          )
        }

        // Move sliders
        createSliderRow(
          labelText = message("device.pose.dialog.label.x"),
          sliderName = "xMoveSlider",
          fieldName = "xMoveField",
          minVal = MIN_POSITION_METERS,
          maxVal = MAX_POSITION_METERS,
          stepsPerUnit = SLIDER_STEPS_PER_METER,
          getter = { positionMeters.x },
          setter = { setPositionFromUi(positionMeters.copy(x = it)) },
          formatter = ::formatMeters,
        )
        createSliderRow(
          labelText = message("device.pose.dialog.label.y"),
          sliderName = "yMoveSlider",
          fieldName = "yMoveField",
          minVal = MIN_POSITION_METERS,
          maxVal = MAX_POSITION_METERS,
          stepsPerUnit = SLIDER_STEPS_PER_METER,
          getter = { positionMeters.y },
          setter = { setPositionFromUi(positionMeters.copy(y = it)) },
          formatter = ::formatMeters,
        )
        createSliderRow(
          labelText = message("device.pose.dialog.label.z"),
          sliderName = "zMoveSlider",
          fieldName = "zMoveField",
          minVal = MIN_POSITION_METERS,
          maxVal = MAX_POSITION_METERS,
          stepsPerUnit = SLIDER_STEPS_PER_METER,
          getter = { positionMeters.z },
          setter = { setPositionFromUi(positionMeters.copy(z = it)) },
          formatter = ::formatMeters,
        )

        if (!isAutomotive) {
          row {
            createOrientationButton(
              name = "portraitButton",
              icon = StagingIcons.PORTRAIT,
              tooltip = message("device.pose.dialog.button.portrait"),
              orientation = DeviceOrientation.PORTRAIT,
            )
            createOrientationButton(
              name = "landscapeButton",
              icon = StagingIcons.LANDSCAPE,
              tooltip = message("device.pose.dialog.button.landscape"),
              orientation = DeviceOrientation.LANDSCAPE,
            )
            createOrientationButton(
              name = "reversePortraitButton",
              icon = StagingIcons.REVERSE_PORTRAIT,
              tooltip = message("device.pose.dialog.button.reverse.portrait"),
              orientation = DeviceOrientation.REVERSE_PORTRAIT,
            )
            createOrientationButton(
              name = "reverseLandscapeButton",
              icon = StagingIcons.REVERSE_LANDSCAPE,
              tooltip = message("device.pose.dialog.button.reverse.landscape"),
              orientation = DeviceOrientation.REVERSE_LANDSCAPE,
            )
            for (posture in postures) {
              createPostureButton(posture)
            }
          }
        }

        group(message("device.pose.dialog.group.sensor.values"), indent = false) {
          createSensorRow(message("device.pose.dialog.label.accelerometer"), "accelerometer") { accelerometerValues }
          createSensorRow(message("device.pose.dialog.label.gyroscope"), "gyroscope") { gyroscopeValues }
          createSensorRow(message("device.pose.dialog.label.magnetometer"), "magnetometer") { magnetometerValues }
        }
      }
        .apply { name = "controlsPanel" }

    val rightPanel =
      if (!isAutomotive) {
        val widget =
          Device3DWidget(
              emulator = emulator,
              onRotationChanged = ::setEulerRotationFromUi,
              onPositionChanged = ::setPositionFromUi,
              onFoldValueChanged = ::setFoldValueFromUi,
            )
            .apply {
              operationMode = this@DevicePoseDialog.operationMode
              rotationDegrees = this@DevicePoseDialog.rotationDegrees
              positionMeters = this@DevicePoseDialog.positionMeters
              foldValue = this@DevicePoseDialog.foldValue
              currentPosture = this@DevicePoseDialog.currentPosture?.posture
              screenImage = this@DevicePoseDialog.screenImage
            }
        device3DWidget = widget
        panel {
          row {
            cell(widget).align(AlignX.CENTER).align(AlignY.TOP)
          }
          row {
            panel {
                buttonsGroup {
                  row {
                      radioButton(message("device.pose.dialog.mode.rotate")).applyToComponent {
                        name = "rotateRadioButton"
                        isSelected = operationMode == OperationMode.ROTATE
                        addActionListener {
                          if (!isUpdatingUi && isSelected) {
                            setMode(OperationMode.ROTATE)
                          }
                        }
                        registerUiUpdater {
                          isSelected = operationMode == OperationMode.ROTATE
                        }
                      }
                      if (hasPostures) {
                        val foldLabel = if (isRollable) message("device.pose.dialog.mode.roll") else message("device.pose.dialog.mode.fold")
                        radioButton(foldLabel).applyToComponent {
                          name = "foldRadioButton"
                          isSelected = operationMode == OperationMode.FOLD
                          addActionListener {
                            if (!isUpdatingUi && isSelected) {
                              setMode(OperationMode.FOLD)
                            }
                          }
                          registerUiUpdater {
                            isSelected = operationMode == OperationMode.FOLD
                          }
                        }
                      }
                      radioButton(message("device.pose.dialog.mode.move")).applyToComponent {
                        name = "moveRadioButton"
                        isSelected = operationMode == OperationMode.MOVE
                        addActionListener {
                          if (!isUpdatingUi && isSelected) {
                            setMode(OperationMode.MOVE)
                          }
                        }
                        registerUiUpdater {
                          isSelected = operationMode == OperationMode.MOVE
                        }
                      }
                    }
                    .layout(RowLayout.PARENT_GRID)
                }
              }
              .align(AlignX.CENTER)
          }
        }
          .also {
            widget.preferredWidth = (controlsPanel.preferredSize.height - it.preferredSize.height).coerceAtLeast(JBUIScale.scale(100))
          }
      } else {
        null
      }

    return panel {
      row {
        cell(controlsPanel).align(AlignY.TOP).align(AlignX.FILL).resizableColumn()

        if (rightPanel != null) {
          cell(rightPanel).align(AlignY.TOP)
        }
      }

      device3DWidget?.let { widget ->
        registerUiUpdater {
          widget.operationMode = operationMode
          widget.rotationDegrees = rotationDegrees
          widget.positionMeters = positionMeters
          widget.foldValue = foldValue
          widget.currentPosture = currentPosture?.posture
          widget.screenImage = screenImage
        }
      }
      controlsPanel.minimumSize = controlsPanel.preferredSize
    }
  }

  private fun Row.createOrientationButton(
    name: String,
    icon: Icon,
    tooltip: String,
    orientation: DeviceOrientation,
  ) {
    cell(SquareButton(icon, tooltip) { setRotationQuadrants(orientation.ordinal) }).accessibleName(tooltip).applyToComponent {
      this.name = name
      isSelected = deviceOrientation == orientation
      registerUiUpdater {
        isSelected = deviceOrientation == orientation
      }
    }
  }

  private fun Row.createPostureButton(posture: PostureDescriptor) {
    cell(SquareButton(posture.icon, posture.displayName) { setPostureFromUi(posture) })
      .accessibleName(posture.displayName)
      .applyToComponent {
        name = "postureButton_${posture.posture.name}"
        isSelected = currentPosture == posture
        registerUiUpdater {
          isSelected = currentPosture == posture
        }
      }
  }

  private fun com.intellij.ui.dsl.builder.Panel.createSensorRow(
    labelText: String,
    namePrefix: String,
    getter: () -> Vec3,
  ): Row {
    return row(labelText) {
        createSensorValueCell("${namePrefix}XLabel") { getter().x }
        createSensorValueCell("${namePrefix}YLabel") { getter().y }
        createSensorValueCell("${namePrefix}ZLabel") { getter().z }
      }
      .layout(RowLayout.PARENT_GRID)
  }

  private fun Row.createSensorValueCell(name: String, getter: () -> Float) {
    cell(SensorValueLabel(name, formatSensorValue(getter()))).gap(RightGap.SMALL).applyToComponent {
      registerUiUpdater {
        val formatted = formatSensorValue(getter())
        if (text != formatted) {
          text = formatted
        }
      }
    }
  }

  private fun com.intellij.ui.dsl.builder.Panel.createSliderRow(
    labelText: String,
    sliderName: String,
    fieldName: String,
    minVal: Float,
    maxVal: Float,
    stepsPerUnit: Int = SLIDER_STEPS_PER_UNIT,
    getter: () -> Float,
    setter: (Float) -> Unit,
    formatter: (Float) -> String,
  ): Row {
    val minSteps = (minVal * stepsPerUnit).roundToInt()
    val maxSteps = (maxVal * stepsPerUnit).roundToInt()
    return row(labelText) {
        slider(minSteps, maxSteps, 0, 0)
          .accessibleName(StringUtil.stripHtml(labelText, false).removeSuffix(":"))
          .align(AlignX.FILL)
          .resizableColumn()
          .gap(RightGap.SMALL)
          .applyToComponent {
            name = sliderName
            paintLabels = false
            paintTicks = false
            value = (getter() * stepsPerUnit).roundToInt().coerceIn(minSteps, maxSteps)
            preferredSize = Dimension(JBUIScale.scale(SLIDER_WIDTH), preferredSize.height)
            putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(right = 3))
            addChangeListener {
              if (!isUpdatingUi) {
                val newVal = (value.toFloat() / stepsPerUnit).coerceIn(minVal, maxVal)
                setter(newVal)
              }
            }
            registerUiUpdater {
              val targetStep = (getter() * stepsPerUnit).roundToInt().coerceIn(minSteps, maxSteps)
              if (value != targetStep) {
                value = targetStep
              }
            }
          }
        cell(SliderValueField(fieldName, formatter(getter()))).applyToComponent {
          registerUiUpdater {
            val formatted = formatter(getter())
            if (text != formatted) {
              text = formatted
            }
          }
        }
      }
      .layout(RowLayout.PARENT_GRID)
  }

  private fun setMode(mode: OperationMode) {
    if (operationMode != mode) {
      operationMode = mode
      updateUi()
    }
  }

  private fun setRotationQuadrants(quadrants: Int) {
    setPositionFromUi(Vec3.ZERO)
    setEulerRotationFromUi(Vec3(0f, 0f, ((quadrants + 2).mod(4) - 2) * 90f))
  }

  private fun setEulerRotationFromUi(deg: Vec3) {
    val clamped =
      Vec3(
        deg.x.coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
        deg.y.coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
        deg.z.coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
      )
    if (rotationDegrees != clamped) {
      rotationDegrees = clamped
      rotationModified = true
      stateModified = true
      updateUi()
      rotationChannel.trySend(clamped)
    }
  }

  private fun setPositionFromUi(posMeters: Vec3) {
    val clamped =
      Vec3(
        posMeters.x.coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
        posMeters.y.coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
        posMeters.z.coerceIn(MIN_POSITION_METERS, MAX_POSITION_METERS),
      )
    if (positionMeters != clamped) {
      positionMeters = clamped
      positionModified = true
      stateModified = true
      updateUi()
      positionChannel.trySend(clamped)
    }
  }

  private fun setPostureFromUi(posture: PostureDescriptor) {
    val defaultVal = defaultPostureValue(posture).toFloat().coerceIn(minFoldValue, maxFoldValue)
    currentPosture = posture
    foldValue = defaultVal
    foldModified = true
    stateModified = true
    updateUi()
    foldChannel.trySend(defaultVal to ++foldVersion)
  }

  private fun setFoldValueFromUi(value: Float) {
    val clamped = value.coerceIn(minFoldValue, maxFoldValue)
    if (foldValue != clamped) {
      foldValue = clamped
      foldModified = true
      stateModified = true
      updateUi()
      foldChannel.trySend(clamped to ++foldVersion)
    }
  }

  private fun defaultPostureValue(posture: PostureDescriptor): Double {
    val minVal = postures.minOfOrNull { it.minValue } ?: Double.MAX_VALUE
    val maxVal = postures.maxOfOrNull { it.maxValue } ?: Double.MIN_VALUE
    return when {
      posture.minValue == minVal -> minVal
      posture.maxValue == maxVal -> maxVal
      else -> (posture.minValue + posture.maxValue) / 2
    }
  }

  private class SquareButton(icon: Icon?, tooltip: String, action: () -> Unit) : JButton() {
    init {
      this.icon = icon
      isOpaque = false
      setToolTipText(HtmlChunk.text(tooltip))
      putClientProperty("JButton.buttonType", "square")
      putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps(3))
      addActionListener { action() }
    }

    override fun setSelected(b: Boolean) {
      super.setSelected(b)
      putClientProperty("JButton.backgroundColor", if (b) JBUI.CurrentTheme.ActionButton.pressedBackground() else null)
      repaint()
    }

    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      return Dimension(size.height, size.height)
    }

    override fun getMinimumSize(): Dimension = preferredSize
  }

  private class SliderValueField(name: String, initialText: String) : JBLabel(initialText, RIGHT) {
    init {
      this.name = name
    }

    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      val fm = getFontMetrics(font)
      val insets = insets
      val minWidth = maxOf(fm.stringWidth("-180.0\u00B0"), fm.stringWidth("-0.00 m")) + insets.left + insets.right
      return Dimension(maxOf(size.width, minWidth), size.height)
    }

    override fun getMinimumSize(): Dimension = preferredSize
  }

  private class SensorValueLabel(name: String, initialText: String) : JBLabel(initialText, RIGHT) {
    init {
      this.name = name
    }

    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      val insets = insets
      val minWidth = getFontMetrics(font).stringWidth("-180.00") + insets.left + insets.right
      return Dimension(maxOf(size.width, minWidth), size.height)
    }

    override fun getMinimumSize(): Dimension = preferredSize
  }

  companion object {
    internal const val SLIDER_STEPS_PER_UNIT = 10
    internal const val SLIDER_STEPS_PER_METER = 100

    internal const val MIN_ROTATION_DEG = -180f
    internal const val MAX_ROTATION_DEG = 180f

    internal const val MIN_POSITION_METERS = -0.1f
    internal const val MAX_POSITION_METERS = 0.1f

    private const val SCREENSHOT_MAX_SIZE = 360

    private const val SLIDER_WIDTH = 180
    private val SENSOR_POLL_INTERVAL = 200.milliseconds
    private const val ACCELEROMETER_COARSE_THRESHOLD = 0.1f

    private fun formatDegrees(deg: Float): String = String.format(Locale.US, "%.1f\u00B0", deg).cleanNegativeZero()

    private fun formatMeters(meters: Float): String = String.format(Locale.US, "%.2f m", meters).cleanNegativeZero()

    private fun formatPercentage(pct: Float): String = String.format(Locale.US, "%.1f%%", pct).cleanNegativeZero()

    private fun formatSensorValue(v: Float): String = String.format(Locale.US, "%.2f", v).cleanNegativeZero()
  }
}
