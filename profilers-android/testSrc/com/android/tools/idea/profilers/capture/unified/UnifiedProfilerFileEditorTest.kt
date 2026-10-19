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
package com.android.tools.idea.profilers.capture.unified

import com.android.tools.idea.profilers.AndroidProfilerToolWindow
import com.android.tools.idea.profilers.AndroidProfilerToolWindowFactory
import com.android.tools.profilers.MinWidthScrollPane
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import com.intellij.mock.MockProjectEx
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.ui.content.ContentManager
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JPanel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.after
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

class UnifiedProfilerFileEditorTest {

  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()

  private lateinit var project: MockProjectEx
  private lateinit var mockFileEditorManager: FileEditorManager
  private lateinit var mockProfilerToolWindow: AndroidProfilerToolWindow

  @Before
  fun setUp() {
    project = spy(MockProjectEx(disposableRule.disposable))
    // A FileEditorManagerEx, as the live task editor's delayed singleton-tab check casts to it.
    mockFileEditorManager = mock(FileEditorManagerEx::class.java).also { whenever(it.windows).thenReturn(emptyArray()) }
    project.registerService(FileEditorManager::class.java, mockFileEditorManager)

    val mockToolWindowManager = mock(ToolWindowManager::class.java)
    val mockToolWindow = mock(ToolWindow::class.java)
    val mockContentManager = mock(ContentManager::class.java)
    whenever(mockContentManager.contentCount).thenReturn(1)
    whenever(mockToolWindow.contentManager).thenReturn(mockContentManager)
    whenever(mockToolWindowManager.getToolWindow(AndroidProfilerToolWindowFactory.ID)).thenReturn(mockToolWindow)
    project.registerService(ToolWindowManager::class.java, mockToolWindowManager)

    mockProfilerToolWindow = mock(AndroidProfilerToolWindow::class.java)
    whenever(mockProfilerToolWindow.profilersPanel).thenReturn(JPanel())
    AndroidProfilerToolWindowFactory.PROJECT_PROFILER_MAP[project] = mockProfilerToolWindow
  }

  @After
  fun tearDown() {
    AndroidProfilerToolWindowFactory.PROJECT_PROFILER_MAP.remove(project)
  }

  @Test
  fun `tab disposed without reopen notifies tab closed after transition debounce delay`() {
    val virtualFile =
      ProfilerVirtualFile(sessionId = 42L, taskType = ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS, taskName = "Java/Kotlin Allocations")

    whenever(mockFileEditorManager.getAllEditors(virtualFile)).thenReturn(FileEditor.EMPTY_ARRAY)
    val editor = UnifiedProfilerFileEditor(project, virtualFile)

    // Dispose the editor
    Disposer.dispose(editor)

    // Immediately before the debounce duration, notifyEditorTabClosed should NOT have been called yet
    verify(mockProfilerToolWindow, never()).notifyEditorTabClosed(42L)

    // After debounce duration fires, notifyEditorTabClosed should be called
    verify(mockProfilerToolWindow, timeout(2000)).notifyEditorTabClosed(42L)
  }

  @Test
  fun `tab disposed and reopened within transition debounce delay does not notify tab closed`() {
    val virtualFile =
      ProfilerVirtualFile(sessionId = 42L, taskType = ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS, taskName = "Java/Kotlin Allocations")

    val editor1 = UnifiedProfilerFileEditor(project, virtualFile)
    val editor2 = UnifiedProfilerFileEditor(project, virtualFile)
    Disposer.register(disposableRule.disposable, editor2)

    // Simulate that editor2 is still open when debounce check runs
    whenever(mockFileEditorManager.getAllEditors(virtualFile)).thenReturn(arrayOf(editor2))

    // Dispose old editor
    Disposer.dispose(editor1)

    // Verify notifyEditorTabClosed was NEVER called because editor2 remained open
    // (Mockito.after waits beyond the debounce window to allow the scheduled debounce task to execute)
    verify(mockProfilerToolWindow, after(UnifiedProfilerFileEditor.TAB_TRANSITION_DEBOUNCE_DELAY_MS + 100).never())
      .notifyEditorTabClosed(42L)
  }

  @Test
  fun `editor component is a min-width scroll pane whose minimum width does not depend on the profiler UI`() {
    whenever(mockProfilerToolWindow.profilersPanel).thenReturn(JPanel().apply { minimumSize = Dimension(5000, 10) })
    val virtualFile =
      ProfilerVirtualFile(sessionId = 42L, taskType = ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS, taskName = "Java/Kotlin Allocations")
    whenever(mockFileEditorManager.getAllEditors(virtualFile)).thenReturn(FileEditor.EMPTY_ARRAY)
    val editor = UnifiedProfilerFileEditor(project, virtualFile)
    Disposer.register(disposableRule.disposable, editor)

    assertThat(editor.component).isInstanceOf(MinWidthScrollPane::class.java)
    assertThat(editor.component.minimumSize.width).isLessThan(JBUI.scale(UnifiedProfilerFileEditor.MIN_CONTENT_WIDTH))
  }
}
