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
package com.android.tools.idea.debuggers.coroutine

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.flags.StudioFlags.COROUTINE_DEBUGGER_ENABLE
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableUi
import com.intellij.openapi.options.SimpleConfigurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.xdebugger.settings.DebuggerConfigurableProvider
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JPanel

private const val ID = "coroutineDebuggerSettingsConfigurable"
private const val DISPLAY_NAME = "Coroutine Debugger"

/** Class used by Intellij to get preferences for the Debugger section. */
class CoroutineDebuggerConfigurableProvider : DebuggerConfigurableProvider() {
  override fun getConfigurables(category: DebuggerSettingsCategory): List<Configurable> {
    if (!COROUTINE_DEBUGGER_ENABLE.get() && !StudioFlags.CREATION_STACK_TRACES_ENABLE.get()) {
      return emptyList()
    }

    if (category != DebuggerSettingsCategory.GENERAL) {
      return emptyList()
    }

    return listOf(
      SimpleConfigurable.create(ID, DISPLAY_NAME, CoroutineDebuggerConfigurableUi::class.java, Supplier { CoroutineDebuggerSettings })
    )
  }
}

/** Creates and manages the UI shown in the Intellij preferences for the debugger section. */
private class CoroutineDebuggerConfigurableUi : ConfigurableUi<CoroutineDebuggerSettings> {
  private val coroutineDebuggerEnabledCheckbox = JBCheckBox("Enable coroutine debugger")
  private val creationStackTracesEnabledCheckbox = JBCheckBox("Enable coroutine creation stack traces")

  // Read the flags once so that the UI and the settings it touches stay consistent for the lifetime of this instance.
  private val showCoroutineDebugger = COROUTINE_DEBUGGER_ENABLE.get()
  private val showCreationStackTraces = StudioFlags.CREATION_STACK_TRACES_ENABLE.get()

  private val component: JComponent = JPanel(VerticalLayout(0))

  init {
    if (showCoroutineDebugger) {
      component.add(coroutineDebuggerEnabledCheckbox)
    }
    if (showCreationStackTraces) {
      component.add(creationStackTracesEnabledCheckbox)
    }
  }

  override fun reset(settings: CoroutineDebuggerSettings) {
    if (showCoroutineDebugger) {
      coroutineDebuggerEnabledCheckbox.isSelected = settings.isCoroutineDebuggerEnabled()
    }
    if (showCreationStackTraces) {
      creationStackTracesEnabledCheckbox.isSelected = settings.isCreationStackTracesEnabled()
    }
  }

  override fun isModified(settings: CoroutineDebuggerSettings): Boolean {
    return (showCoroutineDebugger && coroutineDebuggerEnabledCheckbox.isSelected != settings.isCoroutineDebuggerEnabled()) ||
      (showCreationStackTraces && creationStackTracesEnabledCheckbox.isSelected != settings.isCreationStackTracesEnabled())
  }

  override fun apply(settings: CoroutineDebuggerSettings) {
    if (showCoroutineDebugger) {
      settings.setCoroutineDebuggerEnabled(coroutineDebuggerEnabledCheckbox.isSelected)
    }
    if (showCreationStackTraces) {
      settings.setCreationStackTracesEnabled(creationStackTracesEnabledCheckbox.isSelected)
    }
  }

  override fun getComponent(): JComponent {
    return component
  }
}

/** Object used to access and set the preferences for the coroutine debugger. */
object CoroutineDebuggerSettings {
  private const val COROUTINE_DEBUGGER_ENABLED_DEFAULT = false
  private const val COROUTINE_DEBUGGER_ENABLED = "coroutine.debugger.enabled"
  private const val CREATION_STACK_TRACES_ENABLED_DEFAULT = false
  private const val CREATION_STACK_TRACES_ENABLED = "coroutine.debugger.creation.stack.traces.enabled"

  fun setCoroutineDebuggerEnabled(enabled: Boolean) {
    PropertiesComponent.getInstance().setValue(COROUTINE_DEBUGGER_ENABLED, enabled, COROUTINE_DEBUGGER_ENABLED_DEFAULT)
  }

  fun isCoroutineDebuggerEnabled(): Boolean {
    return PropertiesComponent.getInstance().getBoolean(COROUTINE_DEBUGGER_ENABLED, COROUTINE_DEBUGGER_ENABLED_DEFAULT)
  }

  fun setCreationStackTracesEnabled(enabled: Boolean) {
    PropertiesComponent.getInstance().setValue(CREATION_STACK_TRACES_ENABLED, enabled, CREATION_STACK_TRACES_ENABLED_DEFAULT)
  }

  fun isCreationStackTracesEnabled(): Boolean {
    return PropertiesComponent.getInstance().getBoolean(CREATION_STACK_TRACES_ENABLED, CREATION_STACK_TRACES_ENABLED_DEFAULT)
  }

  fun reset() {
    PropertiesComponent.getInstance().unsetValue(COROUTINE_DEBUGGER_ENABLED)
    PropertiesComponent.getInstance().unsetValue(CREATION_STACK_TRACES_ENABLED)
  }
}
