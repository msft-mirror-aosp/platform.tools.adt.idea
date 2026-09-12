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
package com.android.tools.idea.streaming.core

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBBox
import com.intellij.util.ui.JBUI.Borders
import javax.swing.BoxLayout

/** The panel containing controls specific to AI glasses. */
abstract class AbstractGlassesInputPanel(touchpadPanel: AbstractTouchpadPanel?) : JBBox(BoxLayout.X_AXIS) {

  init {
    border = Borders.compound(Borders.customLineBottom(JBColor.border()), Borders.empty(5, 10))
    if (touchpadPanel != null) {
      add(touchpadPanel)
    }

    val actionManager = ActionManager.getInstance()
    val actionGroup = actionManager.getAction("StreamingToolbarGlasses") as? ActionGroup
    if (actionGroup != null) {
      val toolbar = actionManager.createActionToolbar("GlassesInputPanel", actionGroup, true)
      toolbar.targetComponent = this
      add(toolbar.component)
    }
  }
}
