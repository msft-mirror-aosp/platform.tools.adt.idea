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
package com.android.tools.idea.device.explorer

import com.android.tools.idea.device.explorer.DeviceExplorerToolWindowFactory.Companion.TOOL_WINDOW_ID
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.replaceService
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DeviceExplorerServiceTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val project: Project
    get() = projectRule.project

  private val toolWindowManager = mock<ToolWindowManager>()
  private val toolWindow = mock<ToolWindow>()

  @Before
  fun setUp() {
    project.replaceService(ToolWindowManager::class.java, toolWindowManager, projectRule.testRootDisposable)
  }

  @Test
  fun showToolWindow_whenToolWindowFound_showsAndReturnsTrue() {
    // Prepare
    whenever(toolWindowManager.getToolWindow(TOOL_WINDOW_ID)).thenReturn(toolWindow)

    // Act
    val shown = DeviceExplorerService.showToolWindow(project)

    // Assert
    assertThat(shown).isTrue()
    verify(toolWindow).show()
  }

  @Test
  fun openAndShowDevice_whenToolWindowNotFound_doesNothing() {
    // Should not throw or crash when tool window is not found
    DeviceExplorerService.openAndShowDevice(project, "serial_123")
  }
}
