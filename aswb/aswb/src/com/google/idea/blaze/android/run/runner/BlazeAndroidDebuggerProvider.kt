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
package com.google.idea.blaze.android.run.runner

import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.AndroidDebuggerState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Extension point allowing plugins to provide custom Android debuggers and debugger states for Blaze projects. */
interface BlazeAndroidDebuggerProvider {
  /** Returns the debugger for a run or debug launch with [isNativeDebuggingEnabled]. */
  fun getDebugger(isNativeDebuggingEnabled: Boolean): AndroidDebugger<*>

  /** Returns the debuggers offered in run configuration dialogs and attach-to-process choosers. */
  fun getRunConfigurationDebuggers(): List<AndroidDebugger<*>>

  /** Returns the initialized debugger state for [debugger] within [project]. */
  fun getDebuggerState(project: Project, debugger: AndroidDebugger<*>): AndroidDebuggerState

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<BlazeAndroidDebuggerProvider> =
      ExtensionPointName.create("com.google.idea.blaze.AndroidDebuggerProvider")
  }
}
