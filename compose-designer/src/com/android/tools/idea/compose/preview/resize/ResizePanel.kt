/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.resize

import com.android.resources.UiMode
import com.android.sdklib.devices.Device
import com.android.sdklib.devices.State
import com.android.tools.adtui.stdui.ERROR_VALUE
import com.android.tools.adtui.stdui.OUTLINE_PROPERTY
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.ConfigurationListener
import com.android.tools.configurations.ConversionUtil
import com.android.tools.configurations.DeviceSize
import com.android.tools.configurations.deviceSizeDp
import com.android.tools.configurations.deviceSizePx
import com.android.tools.configurations.dpi
import com.android.tools.configurations.updateScreenSize
import com.android.tools.idea.actions.CONFIGURATIONS
import com.android.tools.idea.actions.DeviceChangeListener
import com.android.tools.idea.actions.DeviceMenuAction
import com.android.tools.idea.actions.HAS_BEEN_RESIZED
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.compose.preview.analytics.ComposeResizeToolingUsageTracker
import com.android.tools.idea.compose.preview.analytics.resizeMode
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.compose.preview.util.previewElement
import com.android.tools.idea.preview.Colors
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.preview.MAX_DIMENSION_DP
import com.android.tools.preview.MIN_DIMENSION_DP
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.getUiModeForDevice
import com.android.tools.res.FrameworkOverlay
import com.google.wireless.android.sdk.stats.ResizeComposePreviewEvent
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.fields.IntegerField
import com.intellij.ui.scale.JBUIScale.scale
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.UIUtil.invokeLaterIfNeeded
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting

val RESIZE_PANEL_INSTANCE_KEY = DataKey.create<ResizePanel>("ResizePanel")

/**
 * Panel that allows resizing the preview by selecting a device or entering custom dimensions. It is displayed in the toolbar of the Compose
 * Preview.
 *
 * The panel looks like: [ Device Picker ▼ ] [ WidthTF ] x [ HeightTF ] [ dp ▼ ] [↩]
 *
 * The width and height can be edited in dp or px, like in the @Preview picker.
 */
class ResizePanel(parentDisposable: Disposable) : JBPanel<ResizePanel>(), Disposable, UiDataProvider {

  private val log = Logger.getInstance(ResizePanel::class.java)

  private data class BaselineSnapshot(
    val device: Device?,
    val deviceState: State?,
    val cutoutOverlay: FrameworkOverlay = FrameworkOverlay.CUTOUT_NONE,
    val deviceOverlay: FrameworkOverlay? = null,
    val uiMode: UiMode = UiMode.NORMAL,
    val theme: String? = null,
  )

  @Volatile private var currentFocusedPreviewElement: PsiComposePreviewElementInstance? = null
  @Volatile private var currentSceneManager: LayoutlibSceneManager? = null

  // Read by the toolbar actions on a background thread.
  @Volatile private var currentConfiguration: Configuration? = null

  @Volatile private var baselineSnapshot: BaselineSnapshot? = null

  /** Indicates whether the preview has been resized using this panel at least once since the panel was last cleared or initialized. */
  @Volatile
  var hasBeenResized: Boolean = false
    private set

  /**
   * Indicates whether the preview has been resized to custom dimensions (e.g. via the width/height text fields or canvas drag), rather than
   * a preset device from the dropdown or the original baseline device.
   */
  val isCustomSized: Boolean
    get() =
      hasBeenResized &&
        currentConfiguration?.cachedDevice?.id == Configuration.CUSTOM_DEVICE_ID &&
        currentConfiguration?.cachedDevice != baselineSnapshot?.device

  @Volatile private var isPanelEnabled: Boolean = false
  @Volatile private var isDisposed: Boolean = false

  /**
   * Serializes binding the panel to a preview ([setSceneManager]) and unbinding it ([clear], [detachListeners], [dispose]). They run on the
   * UI thread and on background threads, e.g. when Focus mode binds the panel while a finishing refresh binds it too. Without it, both
   * calls could see no bound preview and both attach their listeners, leaking one [ConfigurationResizeListener] that keeps re-rendering the
   * preview.
   */
  private val bindingLock = Any()

  /**
   * Listener responsible for reacting to device configuration changes to trigger a re-render of the preview and update its LayoutParams.
   * This instance is created and managed by ResizePanel for the current [com.android.tools.idea.common.scene.SceneManager].
   */
  @Volatile private var renderTriggerListener: ConfigurationResizeListener? = null

  /**
   * Listener responsible for updating ResizePanel's own UI elements (e.g., text fields, visibility) when the device configuration changes,
   * potentially due to external factors or actions from this panel itself.
   */
  private val resizePanelUiUpdaterListener = ConfigurationListener { flags ->
    if ((flags and ConfigurationListener.CFG_DEVICE) != 0) {
      synchronized(bindingLock) {
        val config = currentConfiguration
        if (config != null) {
          val baseline = baselineSnapshot
          // Check if the current device or state differs from the original snapshot,
          // indicating that the preview has been resized.
          hasBeenResized = config.cachedDevice != baseline?.device || config.cachedDeviceState != baseline?.deviceState
        }
        updatePanelFromConfiguration()
      }
    }
    true
  }

  private val dimensionInputsAction = DimensionInputsAction()

  /** Creates and configures the revert button. */
  private fun createRevertButton(): JComponent {
    val revertAction = RevertAction()
    val actionGroup = DefaultActionGroup(revertAction)
    val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)
    actionToolbar.targetComponent = this
    actionToolbar.component.isOpaque = false

    // Wrap the revert button in a panel to prevent it from being stretched by the BorderLayout,
    // and to align it vertically.
    return JBPanel<Nothing>().apply {
      isOpaque = false
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(Box.createVerticalGlue())
      add(actionToolbar.component)
      add(Box.createVerticalGlue())
    }
  }

  @VisibleForTesting
  internal inner class RevertAction : DumbAwareAction(message("resize.panel.revert.tooltip"), null, AllIcons.Actions.Rollback) {
    override fun actionPerformed(e: AnActionEvent) {
      revertResizing()
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = isPanelEnabled && hasBeenResized
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }
  }

  init {
    layout = BorderLayout()
    border = JBEmptyBorder(2)
    background = Colors.DEFAULT_BACKGROUND_COLOR

    // The main action group for the toolbar. Does not include the revert button.
    val mainActionGroup =
      DefaultActionGroup().apply {
        add(DevicePickerAction())
        add(dimensionInputsAction)
      }

    val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, mainActionGroup, true)
    actionToolbar.targetComponent = this
    actionToolbar.component.isOpaque = false // Make the toolbar itself transparent

    val flowingComponentsPanel =
      JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, scale(4), scale(2))).apply {
        isOpaque = false
        add(actionToolbar.component)
      }

    add(flowingComponentsPanel, BorderLayout.CENTER)

    val revertButton = createRevertButton()
    add(revertButton, BorderLayout.EAST)
    Disposer.register(parentDisposable, this)
    isEnabled = false
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[CONFIGURATIONS] = listOfNotNull(currentConfiguration)
    sink[HAS_BEEN_RESIZED] = hasBeenResized
  }

  /**
   * Reverts the preview to its original device and state. This is called when the user clicks the revert button or selects the "Original"
   * device option from the dropdown.
   */
  private fun revertResizing() {
    dimensionInputsAction.resetErrors()
    currentSceneManager?.sceneRenderConfiguration?.needsInflation?.set(true)
    val baseline = baselineSnapshot
    currentConfiguration?.let { config ->
      config.startBulkEditing()
      config.cutoutOverlay = baseline?.cutoutOverlay ?: FrameworkOverlay.CUTOUT_NONE
      config.setDevice(baseline?.device, false)
      config.setEffectiveDevice(baseline?.device, baseline?.deviceState)
      config.deviceOverlay = baseline?.deviceOverlay
      config.uiMode = baseline?.uiMode ?: UiMode.NORMAL
      config.setTheme(baseline?.theme)
      config.updated(ConfigurationListener.CFG_DEVICE)
      config.finishBulkEditing()
    }
    invokeLaterIfNeeded { dimensionInputsAction.updateTextFieldsFromConfiguration() }
    ComposeResizeToolingUsageTracker.logResizeReverted(
      currentSceneManager?.scene?.designSurface,
      currentSceneManager?.resizeMode ?: ResizeComposePreviewEvent.ResizeMode.COMPOSABLE_RESIZE,
    )
  }

  /**
   * Called before a preview render begins. Detaches listeners and disables inputs to prevent edits while rendering, but preserves resize
   * state so it can be re-applied to the new model.
   */
  fun onBeforeRender() {
    synchronized(bindingLock) {
      detachListeners()
      isPanelEnabled = false
    }
    invokeLaterIfNeeded { isEnabled = isPanelEnabled }
  }

  fun onAfterRenderCompleted() {
    updatePanelFromConfiguration()
  }

  private fun detachListeners() =
    synchronized(bindingLock) {
      currentConfiguration?.removeListener(resizePanelUiUpdaterListener)
      renderTriggerListener?.let { existingListener ->
        Disposer.dispose(existingListener)
        currentConfiguration?.removeListener(existingListener)
      }
      renderTriggerListener = null
    }

  /** Clears the panel's state, removing any existing configuration. */
  fun clear() =
    synchronized(bindingLock) {
      detachListeners()
      currentSceneManager = null
      currentConfiguration = null
      currentFocusedPreviewElement = null
      baselineSnapshot = null
      hasBeenResized = false
      isPanelEnabled = false
      // Disable (and hide) the dimension inputs until a new configuration is set, e.g. while the preview refreshes.
      updatePanelFromConfiguration()
    }

  /**
   * Applies the current resize overrides to the given [sceneManager] if it matches the currently focused preview element in this panel.
   *
   * Also updates the baseline configuration snapshot and requests re-inflation if the effective device or dimensions have changed.
   */
  fun applyOverrides(sceneManager: LayoutlibSceneManager) {
    val element = sceneManager.model.dataProvider?.previewElement()
    if (!matchesPreviewElement(element)) return
    val configuration = sceneManager.model.configuration
    val newBaselineDevice = configuration.device
    val newBaselineDeviceState = configuration.deviceState
    synchronized(bindingLock) {
      if (!matchesPreviewElement(element)) return
      updateBaselineFromConfiguration(configuration, newBaselineDevice, newBaselineDeviceState)
      val oldDevice = configuration.cachedDevice
      val oldSize = configuration.deviceSizeDp()
      val oldUiMode = configuration.uiMode
      val oldTheme = configuration.theme
      applyToConfiguration(configuration)
      if (
        hasBeenResized &&
          (oldDevice != configuration.cachedDevice ||
            oldSize != configuration.deviceSizeDp() ||
            oldUiMode != configuration.uiMode ||
            oldTheme != configuration.theme)
      ) {
        sceneManager.sceneRenderConfiguration.needsInflation.set(true)
      }
    }
  }

  /**
   * Updates the baseline original configuration snapshot from [baselineConfig]. This is called before applying user resize overrides, so
   * [baselineConfig] represents the configuration directly defined by the code / @Preview annotation.
   */
  @VisibleForTesting
  fun updateBaselineFromConfiguration(baselineConfig: Configuration) {
    if (baselineConfig === currentConfiguration) return
    val newBaselineDevice = baselineConfig.device
    val newBaselineDeviceState = baselineConfig.deviceState
    synchronized(bindingLock) {
      updateBaselineFromConfiguration(baselineConfig, newBaselineDevice, newBaselineDeviceState)
    }
  }

  private fun updateBaselineFromConfiguration(
    baselineConfig: Configuration,
    newBaselineDevice: Device?,
    newBaselineDeviceState: State?,
  ) {
    if (isDisposed || currentConfiguration == null || baselineConfig === currentConfiguration) return
    val oldBaselineDevice = baselineSnapshot?.device
    if (oldBaselineDevice != null && newBaselineDevice != null && oldBaselineDevice.id != newBaselineDevice.id) {
      // The user edited the @Preview annotation in source code to a new device.
      // Reset the override so the code annotation takes effect.
      clear()
      return
    }
    baselineSnapshot =
      BaselineSnapshot(
        device = newBaselineDevice,
        deviceState = newBaselineDeviceState,
        cutoutOverlay = baselineConfig.cutoutOverlay ?: FrameworkOverlay.CUTOUT_NONE,
        deviceOverlay = baselineConfig.deviceOverlay,
        uiMode = baselineConfig.uiMode ?: UiMode.NORMAL,
        theme = baselineConfig.theme,
      )
  }

  /** Checks if the given [element] matches the currently focused preview element in this panel. */
  fun matchesPreviewElement(element: PsiComposePreviewElementInstance?): Boolean {
    if (element == null || currentFocusedPreviewElement == null) return false
    return currentFocusedPreviewElement?.instanceId == element.instanceId
  }

  /**
   * Applies the current resize overrides (effective device, dimensions, UI mode, overlays, and theme) to [targetConfiguration] if the
   * preview has been resized.
   */
  @VisibleForTesting
  fun applyToConfiguration(targetConfiguration: Configuration) {
    if (!hasBeenResized) return
    val config = currentConfiguration ?: return
    if (targetConfiguration === config) return
    val device = config.cachedDevice ?: return
    val deviceState = config.cachedDeviceState ?: device.defaultState

    targetConfiguration.startBulkEditing()
    targetConfiguration.cutoutOverlay = config.cutoutOverlay
    targetConfiguration.setEffectiveDevice(device, deviceState)
    targetConfiguration.deviceOverlay = config.deviceOverlay
    targetConfiguration.uiMode = config.uiMode
    targetConfiguration.setTheme(config.theme)
    targetConfiguration.finishBulkEditing()
  }

  /**
   * Handles the selection of a device from the device picker dropdown. Updates the current configuration's effective device and logs the
   * event.
   *
   * @param selectedItem The selected [Device].
   */
  private fun handleDeviceSelection(selectedItem: Device) {
    dimensionInputsAction.resetErrors()
    currentSceneManager?.sceneRenderConfiguration?.needsInflation?.set(true)
    currentConfiguration?.let { config ->
      config.startBulkEditing()
      config.cutoutOverlay = FrameworkOverlay.CUTOUT_NONE
      config.setEffectiveDevice(selectedItem, selectedItem.defaultState)
      config.uiMode = getUiModeForDevice(selectedItem)
      config.setTheme(null)
      config.updated(ConfigurationListener.CFG_DEVICE)
      config.finishBulkEditing()
    }
    dimensionInputsAction.updateTextFieldsFromConfiguration()
    ComposeResizeToolingUsageTracker.logResizeStopped(
      currentSceneManager?.scene?.designSurface,
      currentSceneManager?.resizeMode ?: ResizeComposePreviewEvent.ResizeMode.COMPOSABLE_RESIZE,
      ResizeComposePreviewEvent.ResizeSource.DROPDOWN,
      selectedItem.id,
    )
  }

  /**
   * Sets the [LayoutlibSceneManager] for the [ResizePanel], providing context for its operations. This method is called when the focused
   * preview element (and thus its SceneManager) changes.
   *
   * @param sceneManager The [LayoutlibSceneManager] associated with the currently focused preview, or null if none.
   */
  fun setSceneManager(sceneManager: LayoutlibSceneManager?) {
    if (sceneManager == null) {
      clear()
      return
    }

    val model = sceneManager.model
    val newConfiguration = model.configuration
    val newPreviewElement = model.dataProvider?.previewElement()
    val newDevice = newConfiguration.device
    val newDeviceState = newConfiguration.deviceState

    synchronized(bindingLock) {
      if (isDisposed || Disposer.isDisposed(sceneManager)) {
        clear()
        return
      }
      val isSameElement =
        currentFocusedPreviewElement != null &&
          newPreviewElement != null &&
          currentFocusedPreviewElement?.instanceId == newPreviewElement.instanceId

      if (!isSameElement) {
        clear()
      } else {
        detachListeners()
      }

      val resizeListener = ConfigurationResizeListener(sceneManager, newConfiguration, isCustomSized = { isCustomSized })
      if (resizeListener.isDisposed()) {
        clear()
        return
      }

      currentSceneManager = sceneManager
      currentConfiguration = newConfiguration
      currentFocusedPreviewElement = newPreviewElement

      if (!isSameElement || baselineSnapshot == null) {
        baselineSnapshot =
          BaselineSnapshot(
            device = newDevice,
            deviceState = newDeviceState,
            cutoutOverlay = newConfiguration.cutoutOverlay ?: FrameworkOverlay.CUTOUT_NONE,
            deviceOverlay = newConfiguration.deviceOverlay,
            uiMode = newConfiguration.uiMode ?: UiMode.NORMAL,
            theme = newConfiguration.theme,
          )
      }

      val baseline = baselineSnapshot
      hasBeenResized = newDevice != baseline?.device || newDeviceState != baseline?.deviceState

      newConfiguration.addListener(resizePanelUiUpdaterListener)
      newConfiguration.addListener(resizeListener)
      renderTriggerListener = resizeListener
      // The dimension text fields are synced with the new configuration by DimensionInputsAction.updateCustomComponent on the next toolbar
      // update, which also avoids overwriting the user's input while they are editing the fields.
      updatePanelFromConfiguration()
    }
  }

  private fun updatePanelFromConfiguration() {
    synchronized(bindingLock) {
      val config = currentConfiguration
      // cachedDeviceState is already populated by setSceneManager (on BGT) and updated in-place on resize.
      isPanelEnabled = !isDisposed && config != null && config.cachedDeviceState != null
    }
    invokeLaterIfNeeded {
      // The ActionToolbar will automatically update the actions via their update methods.
      // No need to manually trigger updates here.
      isEnabled = isPanelEnabled
    }
  }

  /**
   * Applies values typed into the width/height fields that have not been committed yet. Edits are normally committed on Enter or when the
   * field loses focus, which doesn't happen when e.g. a toolbar button is clicked.
   *
   * Must be called on the UI thread.
   *
   * @return false if the typed values are invalid.
   */
  fun commitPendingEdits(): Boolean = dimensionInputsAction.commitDimensionChanges()

  override fun dispose() {
    synchronized(bindingLock) {
      isDisposed = true
      clear()
    }
  }

  override fun setVisible(isVisible: Boolean) {
    if (this.isVisible == isVisible) return
    if (isVisible) {
      // When the panel is becoming visible, ensure that the text fields are updated from the
      // current configuration. This is to prevent stale values from being displayed, for example
      // when the user has cleared the text fields and hidden the panel.
      dimensionInputsAction.updateTextFieldsFromConfiguration()
    }
    super.setVisible(isVisible)
  }

  @TestOnly
  fun getCurrentPreviewElementForTest(): PsiComposePreviewElementInstance? {
    return currentFocusedPreviewElement
  }

  @TestOnly
  fun selectDeviceForTest(device: Device) {
    handleDeviceSelection(device)
  }

  /**
   * An action that displays a device picker dropdown. This action delegates the creation of the dropdown and the handling of the device
   * selection to a [DeviceMenuAction] to reuse the complex logic for building the device menu.
   */
  @VisibleForTesting
  internal inner class DevicePickerAction :
    DumbAwareAction("Device", "Select a device to resize the preview", AllIcons.General.ArrowDown), CustomComponentAction {
    private val deviceMenuAction =
      DeviceMenuAction(
        deviceChangeListener =
          object : DeviceChangeListener {
            override fun onDeviceChanged(oldDevice: Device?, newDevice: Device) {
              handleDeviceSelection(newDevice)
            }

            override fun onRevertToOriginal() {
              revertResizing()
            }
          },
        deviceFilter = { device ->
          // Only offer AI Glasses devices if the baseline preview device is an AI Glasses device,
          // and only offer non-AI-Glasses devices if the baseline preview device is a standard device.
          val baselineDevice = baselineSnapshot?.device ?: currentConfiguration?.device
          Device.isAiGlasses(baselineDevice) == Device.isAiGlasses(device)
        },
      )

    override fun actionPerformed(e: AnActionEvent) {
      deviceMenuAction.actionPerformed(e)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
      val button = ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
      button.isFocusable = true
      button.setHorizontalTextPosition(SwingConstants.LEADING) // Set text to be before the icon
      return button
    }

    override fun update(e: AnActionEvent) {
      deviceMenuAction.update(e)
      e.presentation.isEnabled = isPanelEnabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }
  }

  /** A [CustomComponentAction] that creates and manages the dimension input fields (width and height) and the unit they are edited in. */
  private inner class DimensionInputsAction : DumbAwareAction(), CustomComponentAction {
    private val widthTextField = createDimensionTextField("Width")
    private val heightTextField = createDimensionTextField("Height")

    /** The unit of the values in the width and height fields. Changed with [unitComboBox] and remembered across panels and sessions. */
    private var unit: DimUnit = loadUnit()

    private val unitComboBox =
      ComboBox(arrayOf(DimUnit.dp, DimUnit.px)).apply {
        item = unit
        toolTipText = message("resize.panel.unit.tooltip")
        accessibleContext.accessibleName = message("resize.panel.unit.tooltip")
        addItemListener { e ->
          if (e.stateChange == ItemEvent.SELECTED) {
            changeUnit(e.item as DimUnit)
          }
        }
      }

    init {
      setUpTextFieldListeners()
    }

    private fun changeUnit(newUnit: DimUnit) {
      if (newUnit == unit) return
      unit = newUnit
      saveUnit(newUnit)
      // Show the current size in the new unit. Values typed in the old unit were committed when the fields lost focus to the combo box;
      // this only drops input that was invalid.
      updateTextFieldsFromConfiguration()
    }

    fun resetErrors() {
      clearError(widthTextField)
      clearError(heightTextField)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
      val panel =
        JPanel(FlowLayout(FlowLayout.CENTER, scale(4), scale(2))).apply {
          isOpaque = false
          add(widthTextField)
          add(JBLabel("X"))
          add(heightTextField)
          add(unitComboBox)
        }

      // Initial update of the text fields
      updateTextFieldsFromConfiguration()

      return panel
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
      // Hide the inputs when there is no configuration (e.g. while the preview is refreshing), like the device picker.
      e.presentation.isEnabledAndVisible = isPanelEnabled && currentConfiguration != null
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
      component.isVisible = presentation.isVisible
      val isEnabled = presentation.isEnabled
      widthTextField.isEnabled = isEnabled
      heightTextField.isEnabled = isEnabled
      unitComboBox.isEnabled = isEnabled
      if (!widthTextField.hasFocus() && !heightTextField.hasFocus() && !hasErrors()) {
        updateTextFieldsFromConfiguration()
      } else {
        // Don't overwrite what the user is typing, but keep the accepted range in sync with the configuration (e.g. a new density).
        val config = currentConfiguration ?: return
        if (updateValueRange(config)) {
          // Whether the typed values are errors depends on the range.
          validate(widthTextField)
          validate(heightTextField)
        }
      }
    }

    fun updateTextFieldsFromConfiguration() {
      resetErrors()
      val config = currentConfiguration ?: return
      updateValueRange(config)
      val (width, height) = config.deviceSizeIn(unit)
      if (widthTextField.value != width || widthTextField.text != width.toString()) {
        widthTextField.value = width
      }

      if (heightTextField.value != height || heightTextField.text != height.toString()) {
        heightTextField.value = height
      }
    }

    /**
     * Sets the values accepted by the width and height fields to the size limits in the current [unit] for the density of [config].
     *
     * @return true if the range changed.
     */
    private fun updateValueRange(config: Configuration): Boolean {
      val range = unit.valueRange(config.dpi())
      var changed = false
      for (field in listOf(widthTextField, heightTextField)) {
        if (field.minValue == range.first && field.maxValue == range.last) continue
        field.minValue = range.first
        field.maxValue = range.last
        // The preferred width depends on the number of digits of the maximum value.
        field.revalidate()
        changed = true
      }
      return changed
    }

    private fun createDimensionTextField(valueName: String): IntegerField {
      return DimensionTextField(valueName).apply {
        columns = 4
        (document as? AbstractDocument)?.documentFilter =
          object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String, attr: AttributeSet?) {
              if (string.all { it.isDigit() }) {
                super.insertString(fb, offset, string, attr)
              }
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
              if (text?.all { it.isDigit() } != false) {
                super.replace(fb, offset, length, text, attrs)
              }
            }
          }
        document.addDocumentListener(
          object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
              validate(this@apply)
            }
          }
        )
      }
    }

    private fun setUpTextFieldListeners() {
      val onEnterListener = ActionListener { commitDimensionChanges() }
      widthTextField.addActionListener(onEnterListener)
      heightTextField.addActionListener(onEnterListener)

      val focusListener =
        object : FocusAdapter() {
          override fun focusLost(e: FocusEvent?) {
            commitDimensionChanges()
            // After attempting to commit, if the fields are still in an error state
            // because of invalid input, and the focus has moved outside of the
            // dimension input fields, revert them to the configuration values.
            if (hasErrors()) {
              updateTextFieldsFromConfiguration()
            }
          }
        }
      widthTextField.addFocusListener(focusListener)
      heightTextField.addFocusListener(focusListener)
    }

    /** Returns true if any of the dimension text fields are in an error state. */
    private fun hasErrors(): Boolean {
      return widthTextField.getClientProperty(OUTLINE_PROPERTY) == ERROR_VALUE ||
        heightTextField.getClientProperty(OUTLINE_PROPERTY) == ERROR_VALUE
    }

    /**
     * Validates the dimension fields and, if they are both valid, updates the [Configuration].
     *
     * @return true if both fields are valid.
     */
    fun commitDimensionChanges(): Boolean {
      val widthIsValid = validate(widthTextField)
      val heightIsValid = validate(heightTextField)
      if (widthIsValid && heightIsValid) {
        updateConfigurationFromTextFields()
      }
      return widthIsValid && heightIsValid
    }

    private fun validate(field: IntegerField): Boolean {
      try {
        field.validateContent()
        clearError(field)
        return true
      } catch (e: ConfigurationException) {
        setError(field, e.localizedMessage)
        return false
      }
    }

    private fun setError(field: IntegerField, message: String) {
      field.putClientProperty(OUTLINE_PROPERTY, ERROR_VALUE)
      field.toolTipText = message
    }

    private fun clearError(field: IntegerField) {
      field.putClientProperty(OUTLINE_PROPERTY, null)
      field.toolTipText = null
    }

    private fun updateConfigurationFromTextFields() {
      val config = currentConfiguration ?: return
      val newSize = DeviceSize(widthTextField.value, heightTextField.value)
      if (newSize == config.deviceSizeIn(unit)) {
        return
      }
      val dpi = config.dpi()
      if (dpi <= 0) {
        log.warn("Cannot update screen size, invalid DPI: $dpi")
        return
      }
      // Values typed in px are applied as they are, without a round trip through dp.
      val (sizePx, sizeDp) =
        when (unit) {
          DimUnit.dp -> newSize.convert(dpi, ConversionUtil::dpToPx) to newSize
          DimUnit.px -> newSize to newSize.convert(dpi, ConversionUtil::pxToDp)
        }
      config.updateScreenSize(sizePx.width, sizePx.height)
      ComposeResizeToolingUsageTracker.logResizeStopped(
        currentSceneManager?.scene?.designSurface,
        currentSceneManager?.resizeMode ?: ResizeComposePreviewEvent.ResizeMode.COMPOSABLE_RESIZE,
        sizeDp.width,
        sizeDp.height,
        dpi,
        ResizeComposePreviewEvent.ResizeSource.TEXT_FIELD,
      )
      updateTextFieldsFromConfiguration()
    }

    override fun actionPerformed(e: AnActionEvent) {
      // This action is just for showing the component, it doesn't have a direct action
    }
  }
}

/** The [PropertiesComponent] key of the last [DimUnit] selected in the Resize toolbar, shared by all projects. */
@VisibleForTesting internal const val RESIZE_PANEL_UNIT_PROPERTY = "compose.preview.resize.panel.unit"

/** The unit the width and height are edited in by default. */
private val DEFAULT_UNIT = DimUnit.dp

private fun loadUnit(): DimUnit {
  val name = PropertiesComponent.getInstance().getValue(RESIZE_PANEL_UNIT_PROPERTY) ?: return DEFAULT_UNIT
  return DimUnit.entries.firstOrNull { it.name == name } ?: DEFAULT_UNIT
}

private fun saveUnit(unit: DimUnit) {
  PropertiesComponent.getInstance().setValue(RESIZE_PANEL_UNIT_PROPERTY, unit.name, DEFAULT_UNIT.name)
}

/** The values accepted in this unit: the same size limits as in dp, converted with the given [dpi]. */
private fun DimUnit.valueRange(dpi: Int): IntRange =
  if (this == DimUnit.dp || dpi <= 0) MIN_DIMENSION_DP..MAX_DIMENSION_DP
  else maxOf(1, ConversionUtil.dpToPx(MIN_DIMENSION_DP, dpi))..ConversionUtil.dpToPx(MAX_DIMENSION_DP, dpi)

/** Returns the current width and height of the device in the given [unit]. */
private fun Configuration.deviceSizeIn(unit: DimUnit): DeviceSize =
  when (unit) {
    DimUnit.dp -> deviceSizeDp()
    DimUnit.px -> deviceSizePx()
  }

/** Converts the width and height with [conversion], e.g. [ConversionUtil.dpToPx], for the given [dpi]. */
private fun DeviceSize.convert(dpi: Int, conversion: (Int, Int) -> Int): DeviceSize =
  DeviceSize(conversion(width, dpi), conversion(height, dpi))

/** An [IntegerField] that is wide enough to show its maximum value completely. */
private class DimensionTextField(valueName: String) : IntegerField(valueName, MIN_DIMENSION_DP, MAX_DIMENSION_DP) {
  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    // JTextField sizes `columns` by the width of 'm' plus the border insets only, but the IntelliJ text field UI also keeps text margins
    // inside the border, which cut off digits. The UI includes these margins in its own preferred size (independent of the text when
    // `columns` is set), so use the larger of both, and widen it if the digits of the largest value and the caret need more room than the
    // columns, e.g. with a monospaced font.
    val uiWidth = ui?.getPreferredSize(this)?.width ?: size.width
    val fontMetrics = getFontMetrics(font)
    val maxValueWidth = maxValue.toString().length * ('0'..'9').maxOf { fontMetrics.charWidth(it) } + 1
    size.width = maxOf(size.width, uiWidth) + maxOf(0, maxValueWidth - columns * fontMetrics.charWidth('m'))
    return size
  }
}
