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
package com.android.tools.idea.streaming.emulator

import com.android.tools.idea.streaming.core.AbstractGlassesInputPanel
import com.intellij.openapi.Disposable

/** The panel containing controls specific to AI glasses AVD. */
class EmulatorGlassesInputPanel(
  private val emulator: EmulatorController,
  parentDisposable: Disposable,
) : AbstractGlassesInputPanel(emulator.emulatorConfig.touchpadSize?.let { EmulatorTouchpadPanel(emulator, it) }) {

  init {
    if (emulator.emulatorConfig.hasLedIndicators) {
      add(createHorizontalGlue())
      add(LedIndicatorPanel(emulator, parentDisposable))
    }
  }
}
