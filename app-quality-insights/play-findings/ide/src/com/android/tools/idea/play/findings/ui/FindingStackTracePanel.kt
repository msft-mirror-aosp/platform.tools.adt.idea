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
package com.android.tools.idea.play.findings.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import com.android.tools.adtui.compose.LocalIsInUiTest
import com.android.tools.idea.insights.ui.StackTraceConsoleBuilder
import com.android.tools.idea.insights.ui.StackTraceConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ToggleableIconButton
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Creates and configures a [StackTraceConsoleView] for displaying a finding's crash stack trace. */
internal fun createFindingStackTraceConsoleView(
  project: Project,
  parentDisposable: Disposable,
): StackTraceConsoleView =
  StackTraceConsoleBuilder(project).console.apply {
    Disposer.register(parentDisposable, this)
    component // Initializes the underlying editor
  }

/** Toggle button that enables/disables soft wraps on [consoleView], placed in the sub-tab strip header row like other AQI tabs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FindingStackTraceSoftWrapButton(
  consoleView: StackTraceConsoleView,
  modifier: Modifier = Modifier,
) {
  var useSoftWraps by remember {
    mutableStateOf(consoleView.editor?.settings?.isUseSoftWraps == true)
  }

  Tooltip(
    tooltip = { Text("Soft-Wrap") },
    modifier = modifier,
  ) {
    ToggleableIconButton(
      value = useSoftWraps,
      onValueChange = { enabled ->
        useSoftWraps = enabled
        consoleView.editor?.settings?.isUseSoftWraps = enabled
      },
      modifier = Modifier.testTag("finding_stack_trace_soft_wrap"),
    ) {
      Icon(
        key = AllIconsKeys.Actions.ToggleSoftWrap,
        contentDescription = "Soft-Wrap",
      )
    }
  }
}

/** Displays a crash stack trace using [StackTraceConsoleView] with clickable source links. */
@Composable
internal fun FindingStackTracePanel(
  stackTrace: String,
  consoleView: StackTraceConsoleView,
  modifier: Modifier = Modifier,
) {
  DisposableEffect(stackTrace) {
    consoleView.printRawStackTrace(stackTrace)
    onDispose { consoleView.clearConsoleText() }
  }

  Box(modifier = modifier.testTag("finding_stack_trace")) {
    if (!LocalIsInUiTest.current) {
      SwingPanel(
        background = JewelTheme.globalColors.panelBackground,
        modifier = Modifier.fillMaxSize(),
        factory = { consoleView.component },
        update = { consoleView.updateEditorBackground() },
      )
    }
  }
}

private fun StackTraceConsoleView.printRawStackTrace(stackTrace: String) {
  val firstLine = stackTrace.substringBefore('\n')
  if (firstLine.contains("Exception") || firstLine.contains("Error")) {
    print("$firstLine\n", ConsoleViewContentType.ERROR_OUTPUT)
    val rest = stackTrace.substringAfter('\n', "")
    if (rest.isNotEmpty()) {
      print("$rest\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }
  } else {
    print("$stackTrace\n", ConsoleViewContentType.NORMAL_OUTPUT)
  }
  flushDeferredText()
  revalidate()
  scrollTo(0)
}
