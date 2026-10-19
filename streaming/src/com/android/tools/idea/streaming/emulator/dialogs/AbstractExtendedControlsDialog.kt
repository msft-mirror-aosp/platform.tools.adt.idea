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

import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.streaming.core.CloseDialogAction
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.DimensionService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.DialogManager
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.event.ItemEvent
import javax.swing.JTextField
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KMutableProperty0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

/** Base class for emulator extended controls dialogs. */
internal abstract class AbstractExtendedControlsDialog(
  protected val emulator: EmulatorController,
  private val title: @NlsContexts.DialogTitle String,
) {
  protected val coroutineScope: CoroutineScope = emulator.createCoroutineScope()

  protected val uiContext: CoroutineContext
    get() = Dispatchers.EDT + ModalityState.any().asContextElement()

  protected var stateModified: Boolean = false
  protected var isUpdatingUi: Boolean = false
    private set

  private val uiUpdaters = mutableListOf<() -> Unit>()
  private val disposeCallbacks = mutableListOf<() -> Unit>()

  /** An invisible text field used to trigger clearing of the error messages. See the [clearError] function. */
  private val validationText = JTextField()
  var dialogWrapper: DialogWrapper? = null
    private set

  var project: Project? = null
    private set

  var parent: Component? = null
    private set

  /** Creates contents of the dialog. */
  protected abstract fun createPanel(): DialogPanel

  /** Updates registered UI components and executes [block] while suppressing change listeners that check [isUpdatingUi]. */
  protected fun updateUi(block: () -> Unit = {}) {
    isUpdatingUi = true
    try {
      for (updater in uiUpdaters) {
        updater()
      }
      block()
    } finally {
      isUpdatingUi = false
    }
  }

  /** Registers an action to be executed when the dialog is disposed. */
  protected fun onDispose(callback: () -> Unit) {
    disposeCallbacks.add(callback)
  }

  /**
   * Starts a coroutine that loads the initial state from the emulator using [getState] and applies it on the EDT via [onStateLoaded]
   * (unless [stateModified] is already true), then forwards subsequent state updates sent to the returned [SendChannel] to the emulator
   * using [setState].
   */
  protected fun <S> createStateChannel(
    getState: suspend (EmulatorController) -> S,
    onStateLoaded: (S) -> Unit,
    setState: suspend (EmulatorController, S) -> Unit,
  ): SendChannel<S> {
    val channel = Channel<S>(Channel.CONFLATED)
    onDispose { channel.close() }
    coroutineScope.launch {
      try {
        val state = getState(emulator)
        withContext(uiContext) {
          if (!stateModified) {
            onStateLoaded(state)
          }
        }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
      }
      for (state in channel) {
        try {
          setState(emulator, state)
        } catch (t: Throwable) {
          t.throwIfCancellation()
          // Error is already logged by EmulatorController.
        }
      }
    }
    return channel
  }

  /**
   * Launches a coroutine that executes [action] and invokes [onSuccess] on the EDT with the result. If [action] throws a non-cancellation
   * exception, its message is displayed using [showError].
   */
  protected fun <T> launchGrpcCall(
    action: suspend (EmulatorController) -> T,
    onSuccess: (T) -> Unit = {},
  ) {
    coroutineScope.launch {
      try {
        val result = action(emulator)
        withContext(uiContext) {
          onSuccess(result)
        }
      } catch (e: Exception) {
        e.throwIfCancellation()
        withContext(uiContext) {
          e.message?.let { showError(it) }
        }
      }
    }
  }

  /** Creates a combo box bound to an enum [property] that automatically updates the property on selection and refreshes on [updateUi]. */
  protected inline fun <reified T : Enum<T>> Row.enumComboBox(
    label: @NlsContexts.Label String,
    property: KMutableProperty0<T>,
    width: Int,
    crossinline displayText: (T) -> String = { it.toString() },
  ): Cell<ComboBox<T>> {
    return comboBox(EnumComboBoxModel(T::class.java), renderer = textListCellRenderer { it?.let(displayText) ?: "" })
      .label(label, LabelPosition.TOP)
      .align(AlignX.FILL)
      .applyToComponent {
        name = "${property.name}ComboBox"
        selectedItem = property.get()
        setMinimumAndPreferredWidth(JBUIScale.scale(width))
        addItemListener {
          if (!isUpdatingUi && it.stateChange == ItemEvent.SELECTED) {
            (it.item as? T)?.let { value -> property.set(value) }
          }
        }
        registerUiUpdater {
          val value = property.get()
          if (selectedItem != value) {
            selectedItem = value
          }
        }
      }
  }

  @PublishedApi
  internal fun registerUiUpdater(updater: () -> Unit) {
    uiUpdaters.add(updater)
  }

  /** Shows a red error message at the bottom of the dialog. */
  protected fun showError(@Nls message: String) {
    (dialogWrapper as? DialogManager)?.performAction {
      validationText.text = message
      listOf(ValidationInfo(message), ValidationInfo(message, validationText))
    }
  }

  /** Clears the error message at the bottom of the dialog. */
  protected fun clearError() {
    validationText.text = ""
  }

  /** Creates the dialog wrapper. */
  open fun createWrapper(project: Project? = null, parent: Component? = null): DialogWrapper {
    openDialogs[emulator]?.takeIf { it !== this }?.close()
    this.project = project
    this.parent = parent
    val dimensionServiceKey = "#${this::class.qualifiedName}"
    uiUpdaters.clear()
    val dialogPanel = createPanel()
    return dialog(
        title = title,
        resizable = true,
        panel = dialogPanel,
        project = project,
        parent = parent,
        modality = IdeModalityType.MODELESS,
        createActions = { listOf(CloseDialogAction(dialogPanel, isDefault = true)) },
      )
      .apply {
        isModal = false
        dialogWrapper = this
        openDialogs[emulator] = this@AbstractExtendedControlsDialog

        setInitialLocationCallback {
          val dimensionService = DimensionService.getInstance()
          val savedSize = dimensionService.getSize(dimensionServiceKey, project)
          if (savedSize != null) {
            setSize(savedSize.width, savedSize.height)
          }
          return@setInitialLocationCallback dimensionService.getLocation(dimensionServiceKey, project)
        }

        Disposer.register(disposable) {
          openDialogs.remove(emulator, this@AbstractExtendedControlsDialog)
          dialogWrapper = null
          for (callback in disposeCallbacks) {
            callback()
          }
          val dimensionService = DimensionService.getInstance()
          dimensionService.setLocation(dimensionServiceKey, location, project)
          dimensionService.setSize(dimensionServiceKey, size, project)
        }
      }
  }

  /** Creates and shows the dialog. */
  fun show(project: Project? = null, parent: Component? = null): DialogWrapper = createWrapper(project, parent).apply { show() }

  /** Closes the dialog if it is currently open. */
  fun close() {
    dialogWrapper?.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  companion object {
    private val openDialogs = mutableMapOf<EmulatorController, AbstractExtendedControlsDialog>()

    /** Returns the currently open extended controls dialog for [emulator], or null if none is open. */
    fun findOpenDialog(emulator: EmulatorController): AbstractExtendedControlsDialog? = openDialogs[emulator]

    /**
     * Shows an extended controls dialog of type [T] for [emulator]. If a dialog of the same type and [project] is already open for
     * [emulator], brings it to the front instead of creating a new one; otherwise closes any other open extended controls dialog for
     * [emulator] and opens a new one created by [dialogCreator].
     */
    internal inline fun <reified T : AbstractExtendedControlsDialog> showDialog(
      emulator: EmulatorController,
      project: Project? = null,
      parent: Component? = null,
      dialogCreator: (EmulatorController) -> T,
    ): DialogWrapper {
      val existingDialog = findOpenDialog(emulator)
      if (existingDialog is T && existingDialog.project == project) {
        val existingWrapper = existingDialog.dialogWrapper
        if (existingWrapper != null && existingWrapper.isShowing) {
          existingWrapper.toFront()
          return existingWrapper
        }
      }
      existingDialog?.close()
      return dialogCreator(emulator).show(project, parent)
    }
  }
}
