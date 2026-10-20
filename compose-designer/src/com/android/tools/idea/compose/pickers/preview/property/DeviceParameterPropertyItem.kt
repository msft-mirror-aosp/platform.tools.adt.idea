/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.compose.pickers.preview.property

import com.android.annotations.concurrency.UiThread
import com.android.ide.common.util.enumValueOfOrNull
import com.android.tools.adtui.model.stdui.EDITOR_NO_ERROR
import com.android.tools.adtui.model.stdui.EditingValidation
import com.android.tools.environment.Logger
import com.android.tools.idea.compose.pickers.base.model.PsiCallPropertiesModel
import com.android.tools.idea.compose.pickers.base.property.MemoryParameterPropertyItem
import com.android.tools.idea.compose.pickers.base.property.PsiCallParameterPropertyItem
import com.android.tools.idea.compose.pickers.common.editingsupport.BooleanValidator
import com.android.tools.idea.compose.pickers.common.editingsupport.IntegerStrictValidator
import com.android.tools.idea.compose.pickers.preview.editingsupport.DeviceSpecDimValidator
import com.android.tools.idea.compose.pickers.preview.model.CurrentDeviceKey
import com.android.tools.idea.compose.pickers.preview.tracking.PickerTrackerHelper
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.preview.util.AvailableDevicesKey
import com.android.tools.preview.MAX_DIMENSION_DP
import com.android.tools.preview.MIN_DIMENSION_DP
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.preview.config.Cutout
import com.android.tools.preview.config.DeviceConfig
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.KeyboardKind
import com.android.tools.preview.config.MutableDeviceConfig
import com.android.tools.preview.config.Navigation
import com.android.tools.preview.config.Orientation
import com.android.tools.preview.config.PARAMETER_HARDWARE_CHIN_SIZE
import com.android.tools.preview.config.PARAMETER_HARDWARE_CUTOUT
import com.android.tools.preview.config.PARAMETER_HARDWARE_DENSITY
import com.android.tools.preview.config.PARAMETER_HARDWARE_DEVICE
import com.android.tools.preview.config.PARAMETER_HARDWARE_DIM_UNIT
import com.android.tools.preview.config.PARAMETER_HARDWARE_HEIGHT
import com.android.tools.preview.config.PARAMETER_HARDWARE_IS_ROUND
import com.android.tools.preview.config.PARAMETER_HARDWARE_NAVIGATION
import com.android.tools.preview.config.PARAMETER_HARDWARE_ORIENTATION
import com.android.tools.preview.config.PARAMETER_HARDWARE_WIDTH
import com.android.tools.preview.config.PARAMETER_HEIGHT
import com.android.tools.preview.config.PARAMETER_HEIGHT_DP
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_CAMERA
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_DISTANCE
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_KEYBOARD
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_MIC
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_POINTER
import com.android.tools.preview.config.PARAMETER_MEDIA_QUERY_WINDOW_POSTURE
import com.android.tools.preview.config.PARAMETER_SHOW_DECORATION
import com.android.tools.preview.config.PARAMETER_SHOW_SYSTEM_UI
import com.android.tools.preview.config.PARAMETER_WIDTH
import com.android.tools.preview.config.PARAMETER_WIDTH_DP
import com.android.tools.preview.config.PointerPrecision
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_CUTOUT
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_DPI
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_HEIGHT_DP
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_NAVIGATION
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_SHAPE
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_UNIT
import com.android.tools.preview.config.Preview.DeviceSpec.DEFAULT_WIDTH_DP
import com.android.tools.preview.config.Preview.DeviceSpec.MAX_CHIN_SIZE
import com.android.tools.preview.config.Shape
import com.android.tools.preview.config.ViewingDistance
import com.android.tools.preview.config.WindowPosture
import com.android.tools.preview.config.findByIdOrName
import com.android.tools.preview.config.getDefaultPreviewDevice
import com.android.tools.preview.config.toDeviceConfig
import com.android.tools.preview.config.toMutableConfig
import com.google.wireless.android.sdk.stats.EditorPickerEvent.EditorPickerAction.PreviewPickerModification.PreviewPickerValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import com.intellij.util.text.nullize
import kotlin.math.roundToInt
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument

/** Parameters that set the size of the preview in dp, replacing the screen size of the device unless the system UI is shown. */
private val CUSTOM_DIMENSION_PARAMETERS = listOf(PARAMETER_WIDTH_DP, PARAMETER_HEIGHT_DP, PARAMETER_WIDTH, PARAMETER_HEIGHT)

/** Hardware properties that show the size of the preview, which `widthDp`/`heightDp` set when the system UI is not shown. */
private val PREVIEW_DIMENSION_PROPERTIES =
  setOf(PARAMETER_HARDWARE_WIDTH, PARAMETER_HARDWARE_HEIGHT, PARAMETER_HARDWARE_ORIENTATION, PARAMETER_HARDWARE_DIM_UNIT)

/** Name of the undoable command that edits the custom preview dimensions from the hardware properties. */
private const val EDIT_CUSTOM_DIMENSIONS_COMMAND = "Preview Size Modification"

/**
 * A [PsiCallParameterPropertyItem] for the Device parameter. Contains internal properties used to modify the device hardware, those are not
 * actual parameters on file and are only kept on memory.
 */
internal class DeviceParameterPropertyItem(
  project: Project,
  model: PsiCallPropertiesModel,
  addNewArgumentToResolvedCall: (KtValueArgument, KtPsiFactory) -> KtValueArgument?,
  parameterName: Name,
  parameterTypeNameIfStandard: Name?,
  argumentExpression: KtExpression?,
  defaultValue: String?,
  initialValue: String? = null,
) :
  PsiCallParameterPropertyItem(
    project,
    model,
    addNewArgumentToResolvedCall,
    parameterName,
    parameterTypeNameIfStandard,
    argumentExpression,
    defaultValue,
    initialValue = initialValue,
  ) {
  private val log = Logger.getInstance(this.javaClass)

  private val defaultDeviceValues: DeviceConfig =
    ConfigurationManager.findExistingInstance(model.module)?.getDefaultPreviewDevice()?.toDeviceConfig()
      ?: DeviceConfig(
        shape = DEFAULT_SHAPE,
        width = DEFAULT_WIDTH_DP.toFloat(),
        height = DEFAULT_HEIGHT_DP.toFloat(),
        dimUnit = DEFAULT_UNIT,
        dpi = DEFAULT_DPI,
        cutout = DEFAULT_CUTOUT,
        navigation = DEFAULT_NAVIGATION,
      )

  override var name: String = PARAMETER_HARDWARE_DEVICE

  val innerProperties =
    listOf<MemoryParameterPropertyItem>(
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_WIDTH,
        defaultValue = defaultDeviceValues.widthString,
        inputValidation = DeviceSpecDimValidator(strictPositive = true),
        getter = { it.widthString },
      ) { config, newValue ->
        newValue.toFloatOrNull()?.let { config.width = it }
        PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_HEIGHT,
        defaultValue = defaultDeviceValues.heightString,
        inputValidation = DeviceSpecDimValidator(strictPositive = true),
        getter = { it.heightString },
      ) { config, newValue ->
        newValue.toFloatOrNull()?.let { config.height = it }
        PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_DIM_UNIT,
        defaultValue = defaultDeviceValues.dimUnit.name,
        getter = { it.dimUnit.name },
      ) { config, newValue ->
        val newUnit = enumValueOfOrNull<DimUnit>(newValue)
        newUnit?.let {
          config.dimUnit = newUnit
          newUnit.trackableValue
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_DENSITY,
        defaultValue = defaultDeviceValues.dpi.toString(),
        inputValidation = IntegerStrictValidator,
        getter = { it.dpi.toString() },
      ) { config, newValue ->
        val newDpi = newValue.toIntOrNull()
        newDpi?.let {
          config.dpi = newDpi
          PickerTrackerHelper.densityBucketOfDeviceConfig(config)
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_ORIENTATION,
        defaultValue = defaultDeviceValues.orientation.name,
        getter = { it.orientation.name },
      ) { config, newValue ->
        val newOrientation = enumValueOfOrNull<Orientation>(newValue)
        newOrientation?.let {
          config.orientation = newOrientation
          newOrientation.trackableValue
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_IS_ROUND,
        defaultValue = defaultDeviceValues.isRound.toString(),
        inputValidation = BooleanValidator,
        getter = { it.isRound.toString() },
      ) { config, newValue ->
        val newIsRound = newValue.toBooleanStrictOrNull()
        newIsRound?.let {
          config.shape = if (it) Shape.Round else Shape.Normal
          if (it) PreviewPickerValue.SHAPE_ROUND else PreviewPickerValue.SHAPE_NORMAL
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_CHIN_SIZE,
        defaultValue = defaultDeviceValues.chinSizeString,
        inputValidation = DeviceSpecDimValidator(strictPositive = false, maxFloatAllowed = MAX_CHIN_SIZE),
        getter = { it.chinSizeString },
      ) { config, newValue ->
        val newChinSize = newValue.toFloatOrNull()
        newChinSize?.let {
          if (it > 0) {
            config.shape = Shape.Round
          }
          config.chinSize = newChinSize
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(name = PARAMETER_HARDWARE_CUTOUT, defaultValue = defaultDeviceValues.cutout.name, getter = { it.cutout.name }) {
        config,
        newValue ->
        val newCutout = enumValueOfOrNull<Cutout>(newValue)
        newCutout?.let {
          config.cutout = newCutout
          newCutout.trackableValue
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_HARDWARE_NAVIGATION,
        defaultValue = defaultDeviceValues.navigation.name,
        getter = { it.navigation.name },
      ) { config, newValue ->
        val newNavigation = enumValueOfOrNull<Navigation>(newValue)
        newNavigation?.let {
          config.navigation = newNavigation
          newNavigation.trackableValue
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_WINDOW_POSTURE,
        defaultValue = defaultDeviceValues.windowPosture.name,
        getter = { it.windowPosture.name },
      ) { config, newValue ->
        val newPosture = enumValueOfOrNull<WindowPosture>(newValue)
        newPosture?.let {
          config.windowPosture = newPosture
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_POINTER,
        defaultValue = defaultDeviceValues.pointerPrecision.name,
        getter = { it.pointerPrecision.name },
      ) { config, newValue ->
        val newPointer = enumValueOfOrNull<PointerPrecision>(newValue)
        newPointer?.let {
          config.pointerPrecision = newPointer
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_KEYBOARD,
        defaultValue = defaultDeviceValues.keyboardKind.name,
        getter = { it.keyboardKind.name },
      ) { config, newValue ->
        val newKeyboard = enumValueOfOrNull<KeyboardKind>(newValue)
        newKeyboard?.let {
          config.keyboardKind = newKeyboard
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_CAMERA,
        defaultValue = defaultDeviceValues.hasCamera.toString(),
        inputValidation = BooleanValidator,
        getter = { it.hasCamera.toString() },
      ) { config, newValue ->
        val newHasCamera = newValue.toBooleanStrictOrNull()
        newHasCamera?.let {
          config.hasCamera = it
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_MIC,
        defaultValue = defaultDeviceValues.hasMicrophone.toString(),
        inputValidation = BooleanValidator,
        getter = { it.hasMicrophone.toString() },
      ) { config, newValue ->
        val newHasMic = newValue.toBooleanStrictOrNull()
        newHasMic?.let {
          config.hasMicrophone = it
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
      DevicePropertyItem(
        name = PARAMETER_MEDIA_QUERY_DISTANCE,
        defaultValue = defaultDeviceValues.viewingDistance.name,
        getter = { it.viewingDistance.name },
      ) { config, newValue ->
        val newDistance = enumValueOfOrNull<ViewingDistance>(newValue)
        newDistance?.let {
          config.viewingDistance = newDistance
          PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        } ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      },
    )

  private var lastValueToDevice: Pair<String, DeviceConfig>? = null

  /**
   * Whether `widthDp`/`heightDp` (or the legacy `width`/`height`) replace the screen size of the device. Like in rendering (see
   * `getCustomDeviceSize` in `PreviewConfiguration.kt`), they only do when the system UI is not shown, otherwise they just size the
   * composable inside the device frame.
   */
  private val customDimensionsOverrideDevice: Boolean
    get() {
      val showDecorations =
        model.properties.getOrNull("", PARAMETER_SHOW_DECORATION)?.value ?: model.properties.getOrNull("", PARAMETER_SHOW_SYSTEM_UI)?.value
      return showDecorations?.toBooleanStrictOrNull() != true
    }

  /** Whether any of `widthDp`/`heightDp` (or the legacy `width`/`height`) is in the annotation. */
  private fun hasCustomDimensions(): Boolean = CUSTOM_DIMENSION_PARAMETERS.any { model.properties.getOrNull("", it)?.value != null }

  /** Whether `widthDp`/`heightDp` (or the legacy `width`/`height`) set the size of the preview, see [applyCustomDimensionsIfPresent]. */
  private fun customDimensionsSetPreviewSize(): Boolean =
    customDimensionsOverrideDevice &&
      (getCustomDimensionValue(PARAMETER_WIDTH, PARAMETER_WIDTH_DP) != null ||
        getCustomDimensionValue(PARAMETER_HEIGHT, PARAMETER_HEIGHT_DP) != null)

  /**
   * True while the device parameter is written by an edit of a hardware property, rather than by picking, typing or clearing the device.
   */
  private var isWritingHardwareEdit = false

  /**
   * The device parameter itself, as picked, typed or cleared by the user.
   *
   * When `widthDp`/`heightDp` are present, writing the device that is already set (or clearing an absent one) still writes it, so that
   * [beforeWriteInWriteAction] removes them: the device shown in the picker would otherwise keep a size that is not its own.
   */
  override var value: String?
    get() = super.value
    @UiThread
    set(newValue) {
      val normalizedValue = newValue?.trim()?.nullize()
      if (normalizedValue == super.value && hasCustomDimensions()) {
        val trackableValue = if (normalizedValue == null) PreviewPickerValue.CLEARED else PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
        writeNewValue(normalizedValue, false, trackableValue)
      } else {
        super.value = newValue
      }
    }

  /**
   * Removes `widthDp`/`heightDp` (and the legacy `width`/`height`) when the device is picked, typed or cleared: they take precedence over
   * the screen size of the device, so the change would have no visible effect (b/463600259). They are removed even when the system UI is
   * shown, as the picker has no field for them. Hardware edits, see [DevicePropertyItem], keep them.
   */
  @RequiresWriteLock
  override fun beforeWriteInWriteAction(newValue: String?) {
    if (isWritingHardwareEdit) return
    CUSTOM_DIMENSION_PARAMETERS.forEach { parameterName ->
      (model.properties.getOrNull("", parameterName) as? PsiCallParameterPropertyItem)?.clearArgumentInWriteAction()
    }
  }

  /** Writes [deviceSpec], the result of an edit of a hardware property, keeping `widthDp`/`heightDp`. */
  private fun writeHardwareEdit(deviceSpec: String, trackableValue: PreviewPickerValue) {
    isWritingHardwareEdit = true
    try {
      writeNewValue(deviceSpec, false, trackableValue)
    } finally {
      isWritingHardwareEdit = false
    }
  }

  /** Returns the configuration of the device the preview is rendered with, including the custom dimensions that replace its screen size. */
  private fun getCurrentDeviceConfig(): MutableDeviceConfig {
    val deviceConfig = resolveDeviceConfig()
    return if (customDimensionsOverrideDevice) deviceConfig.applyCustomDimensionsIfPresent() else deviceConfig
  }

  /** Returns the configuration of the device given by the device parameter, or of the default device when it is not set. */
  private fun resolveDeviceConfig(): MutableDeviceConfig {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val currentValue = value ?: return defaultDeviceValues.toMutableConfig()
    val availableDevices = AvailableDevicesKey.getData(model) ?: emptyList()

    val lastValue = lastValueToDevice
    if (lastValue != null && currentValue == lastValue.first) {
      // No need to parse or find Device for repeated calls.
      return lastValue.second.toMutableConfig()
    }

    // Translate the current value, the value could either be a DeviceConfig string or a Device ID
    val resolvedDeviceConfig =
      DeviceConfig.toDeviceConfigOrNull(currentValue, availableDevices)
        ?: availableDevices.findByIdOrName(currentValue, log)?.toDeviceConfig()
        ?: defaultDeviceValues

    lastValueToDevice = Pair(currentValue, resolvedDeviceConfig)
    return resolvedDeviceConfig.toMutableConfig()
  }

  /**
   * Returns the first of [parameterNames] that is in the annotation, which is the one used in rendering (see attributesToConfiguration), or
   * null if none is.
   */
  private fun findCustomDimensionParameter(vararg parameterNames: String): PsiCallParameterPropertyItem? =
    parameterNames.firstNotNullOfOrNull { parameterName ->
      (model.properties.getOrNull("", parameterName) as? PsiCallParameterPropertyItem)?.takeIf { it.value != null }
    }

  /**
   * Returns the value in dp of the first of [parameterNames] that is set, or null if none is set or its value is [UNDEFINED_DIMENSION]. The
   * value is clamped the same way as in `PreviewConfiguration.cleanAndGet`.
   */
  private fun getCustomDimensionValue(vararg parameterNames: String): Int? =
    findCustomDimensionParameter(*parameterNames)
      ?.value
      ?.toIntOrNull()
      ?.takeIf { it != UNDEFINED_DIMENSION }
      ?.coerceIn(MIN_DIMENSION_DP, MAX_DIMENSION_DP)

  /**
   * Replaces the screen size of this configuration with `widthDp`/`heightDp` (or the legacy `width`/`height`) when they are set. As in
   * rendering, a dimension that is not set keeps the one of the device in its current orientation.
   */
  private fun MutableDeviceConfig.applyCustomDimensionsIfPresent(): MutableDeviceConfig = apply {
    // The legacy parameters take precedence, see attributesToConfiguration.
    val customWidthDp = getCustomDimensionValue(PARAMETER_WIDTH, PARAMETER_WIDTH_DP)
    val customHeightDp = getCustomDimensionValue(PARAMETER_HEIGHT, PARAMETER_HEIGHT_DP)
    if (customWidthDp == null && customHeightDp == null) return@apply
    dimUnit = DimUnit.dp
    // The width and height of the configuration don't account for the orientation, swap them when the device is rotated.
    val isRotated = (orientation == Orientation.landscape) != (width > height)
    val deviceWidthDp = if (isRotated) height else width
    val deviceHeightDp = if (isRotated) width else height
    width = customWidthDp?.toFloat() ?: deviceWidthDp
    height = customHeightDp?.toFloat() ?: deviceHeightDp
    orientation = if (width > height) Orientation.landscape else Orientation.portrait
    parentDeviceId = null
  }

  /**
   * Applies an edit of [propertyName] (Width, Height, Orientation or DimensionUnit) to `widthDp`/`heightDp`, which set the size of the
   * preview in place of the screen size of the device. Width and Height (in dp) update them in place and Orientation swaps them, so that
   * the composable keeps its fixed size. As they are in dp, DimensionUnit = px moves the size into the device parameter instead, and
   * removes them.
   */
  private fun editCustomDimensions(propertyName: String, newValue: String) {
    val currentConfig = getCurrentDeviceConfig()
    var newWidth = currentConfig.width
    var newHeight = currentConfig.height
    val trackableValue: PreviewPickerValue
    when (propertyName) {
      PARAMETER_HARDWARE_WIDTH -> {
        newValue.toFloatOrNull()?.let { newWidth = it }
        trackableValue = PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
      }
      PARAMETER_HARDWARE_HEIGHT -> {
        newValue.toFloatOrNull()?.let { newHeight = it }
        trackableValue = PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED
      }
      PARAMETER_HARDWARE_ORIENTATION -> {
        val newOrientation = enumValueOfOrNull<Orientation>(newValue)
        if (newOrientation != null && newOrientation != currentConfig.orientation) {
          newWidth = currentConfig.height
          newHeight = currentConfig.width
        }
        trackableValue = newOrientation?.trackableValue ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      }
      PARAMETER_HARDWARE_DIM_UNIT -> {
        val newUnit = enumValueOfOrNull<DimUnit>(newValue)
        if (newUnit != null && newUnit != currentConfig.dimUnit) {
          currentConfig.dimUnit = newUnit
          // Not a hardware edit: the device parameter now holds the size, so widthDp/heightDp are removed.
          invokeLater { writeNewValue(currentConfig.deviceSpec(), false, newUnit.trackableValue) }
          return
        }
        trackableValue = newUnit?.trackableValue ?: PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE
      }
      else -> error("$propertyName is not a dimension of the preview")
    }

    // Only the dimensions that change are written, the one that is not set keeps following the device otherwise.
    val writes = buildList {
      if (newWidth != currentConfig.width) add(PARAMETER_WIDTH to PARAMETER_WIDTH_DP to newWidth)
      if (newHeight != currentConfig.height) add(PARAMETER_HEIGHT to PARAMETER_HEIGHT_DP to newHeight)
    }
      .mapNotNull { (parameterNames, newDimension) ->
        val (legacyParameterName, parameterName) = parameterNames
        val newDimensionDp = newDimension.roundToInt().coerceIn(MIN_DIMENSION_DP, MAX_DIMENSION_DP)
        if (newDimensionDp == getCustomDimensionValue(legacyParameterName, parameterName)) return@mapNotNull null
        val item =
          findCustomDimensionParameter(legacyParameterName, parameterName)
            ?: model.properties.getOrNull("", parameterName) as? PsiCallParameterPropertyItem
            ?: return@mapNotNull null
        item to newDimensionDp
      }

    invokeLater {
      model.tracker.registerModification(name, trackableValue, CurrentDeviceKey.getData(model))
      if (writes.isNotEmpty()) {
        runModification(EDIT_CUSTOM_DIMENSIONS_COMMAND) {
          writes.forEach { (item, newDimensionDp) -> item.writeValueInWriteAction(newDimensionDp.toString()) }
        }
      }
    }
  }

  /**
   * PropertyItem for internal device parameters, so that they all read and write to one single source.
   *
   * Edits are applied to the device parameter and keep `widthDp`/`heightDp`, except for the dimensions of the preview while `widthDp`/
   * `heightDp` set them (see [editCustomDimensions]).
   */
  private inner class DevicePropertyItem(
    name: String,
    defaultValue: String?,
    inputValidation: EditingValidation = { EDITOR_NO_ERROR },
    private val getter: (MutableDeviceConfig) -> String,
    private val setter: (MutableDeviceConfig, String) -> PreviewPickerValue,
  ) : MemoryParameterPropertyItem(name, defaultValue, inputValidation) {
    override var value: String?
      get() = getter(getCurrentDeviceConfig())
      set(newValue) {
        newValue ?: return
        if (name in PREVIEW_DIMENSION_PROPERTIES && customDimensionsSetPreviewSize()) {
          editCustomDimensions(name, newValue)
          return
        }
        // Apply the edit to the device itself, not to the size shown in the picker, so that it keeps e.g. its parent.
        val deviceConfig = resolveDeviceConfig()
        val currentDeviceSpec = deviceConfig.deviceSpec()
        val trackableValue = setter(deviceConfig, newValue)
        val newDeviceSpec = deviceConfig.deviceSpec()
        invokeLater {
          if (newDeviceSpec != currentDeviceSpec) {
            writeHardwareEdit(newDeviceSpec, trackableValue)
          } else {
            // The edit doesn't change the device, e.g. selecting the value that is already selected: there is nothing to write.
            model.tracker.registerModification(this@DeviceParameterPropertyItem.name, trackableValue, CurrentDeviceKey.getData(model))
          }
        }
      }
  }

  private val DimUnit.trackableValue: PreviewPickerValue
    get() =
      when (this) {
        DimUnit.dp -> PreviewPickerValue.UNIT_DP
        DimUnit.px -> PreviewPickerValue.UNIT_PIXELS
      }

  private val Orientation.trackableValue: PreviewPickerValue
    get() =
      when (this) {
        Orientation.portrait -> PreviewPickerValue.ORIENTATION_PORTRAIT
        Orientation.landscape -> PreviewPickerValue.ORIENTATION_LANDSCAPE
      }

  private val Cutout.trackableValue: PreviewPickerValue
    get() =
      when (this) {
        Cutout.none -> PreviewPickerValue.CUTOUT_NONE
        Cutout.corner -> PreviewPickerValue.CUTOUT_CORNER
        Cutout.double -> PreviewPickerValue.CUTOUT_DOUBLE
        Cutout.punch_hole -> PreviewPickerValue.CUTOUT_HOLE
        Cutout.tall -> PreviewPickerValue.CUTOUT_TALL
      }

  private val Navigation.trackableValue: PreviewPickerValue
    get() =
      when (this) {
        Navigation.buttons -> PreviewPickerValue.NAVIGATION_BUTTONS
        Navigation.gesture -> PreviewPickerValue.NAVIGATION_GESTURE
      }
}
