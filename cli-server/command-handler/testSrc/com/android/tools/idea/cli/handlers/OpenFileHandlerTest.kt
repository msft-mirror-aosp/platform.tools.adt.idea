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
package com.android.tools.idea.cli.handlers

import com.android.tools.idea.cli.studio.openFileRequest
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class OpenFileHandlerTest {
  private val projectRule = ProjectRule()
  private val tempDir = TempDirectory()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(tempDir)

  private val project
    get() = projectRule.project

  @After
  fun tearDown() {
    runInEdtAndWait { FileEditorManagerEx.getInstanceEx(project).closeAllFiles() }
  }

  @Test
  fun opensFileCreatedOutsideOfVfs() {
    // Load the directory and its children into the VFS, then create a file behind its back. This mirrors
    // a project directory already known to Studio getting a new file from an external tool.
    tempDir.virtualFileRoot.children
    val file = Files.writeString(tempDir.rootPath.resolve("CreatedExternally.kt"), "fun f() {}")
    assertThat(LocalFileSystem.getInstance().findFileByNioFile(file)).isNull()

    OpenFileHandler().handle({ project }, openFileRequest { filePath = file.toString() }.toByteArray())

    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file)
    assertThat(virtualFile).isNotNull()
    assertThat(FileEditorManager.getInstance(project).isFileOpen(virtualFile!!)).isTrue()
  }

  @Test
  fun failsForNonExistentFile() {
    val missing = tempDir.rootPath.resolve("Missing.kt")

    val exception = runCatching {
      OpenFileHandler().handle({ project }, openFileRequest { filePath = missing.toString() }.toByteArray())
    }
      .exceptionOrNull()

    assertThat(exception).hasMessageThat().isEqualTo("File not found")
  }
}
