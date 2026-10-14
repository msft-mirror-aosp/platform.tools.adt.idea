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
package com.android.tools.idea.insights.ui

import com.android.tools.adtui.common.primaryContentBackground
import com.intellij.execution.filters.TextConsoleBuilderImpl
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewPlace
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.isDefaultForTheme
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBScrollPane
import com.intellij.unscramble.AnalyzeStacktraceUtil
import com.intellij.util.ui.JBUI

internal val AQI_CONSOLE_VIEW_PLACE = ConsoleViewPlace("AQI")

class StackTraceConsoleBuilder(project: Project) : TextConsoleBuilderImpl(project) {
  init {
    filters(AnalyzeStacktraceUtil.EP_NAME.getExtensions(project))
  }

  override fun getConsole(): StackTraceConsoleView = super.getConsole() as StackTraceConsoleView

  override fun createConsole(): StackTraceConsoleView {
    return StackTraceConsoleView(project, scope, isViewer, isUsePredefinedMessageFilter)
  }
}

class StackTraceConsoleView(project: Project, scope: GlobalSearchScope, isViewer: Boolean, usePredefinedMessage: Boolean) :
  ConsoleViewImpl(project, scope, isViewer, usePredefinedMessage) {
  override fun getPlace() = AQI_CONSOLE_VIEW_PLACE

  /**
   * Synchronously flushes deferred output and clears the editor document.
   *
   * Prefer this over [clear], which clears non-deferred text asynchronously and can race with subsequent [print] calls.
   */
  fun clearConsoleText() {
    flushDeferredText()
    editor?.document?.setText("")
  }

  override fun doCreateConsoleEditor(): EditorEx {
    return super.doCreateConsoleEditor().apply {
      contentComponent.isFocusCycleRoot = false
      contentComponent.isFocusable = true
      setCaretEnabled(false)
      scrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
      setBorder(JBUI.Borders.empty())
      updateEditorBackground(this)
    }
  }

  fun updateEditorBackground(editor: EditorEx? = this.editor as? EditorEx) {
    editor ?: return
    val globalScheme = EditorColorsManager.getInstance().globalScheme
    editor.backgroundColor =
      if (globalScheme.isDefaultForTheme(LafManager.getInstance().currentUIThemeLookAndFeel)) {
        primaryContentBackground
      } else {
        globalScheme.defaultBackground
      }
  }
}
