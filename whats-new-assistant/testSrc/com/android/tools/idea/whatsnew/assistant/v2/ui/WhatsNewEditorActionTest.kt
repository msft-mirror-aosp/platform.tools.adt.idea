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
package com.android.tools.idea.whatsnew.assistant.v2.ui

import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.fileEditor.FileEditorComposite
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
@Suppress("UnstableApiUsage")
class WhatsNewEditorActionTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  @get:Rule val edtRule = EdtRule()

  private lateinit var mockFileEditorManager: FileEditorManagerEx

  @Before
  fun setUp() {
    mockFileEditorManager = mock()
    whenever(mockFileEditorManager.openFiles).thenReturn(VirtualFile.EMPTY_ARRAY)
    whenever(
        mockFileEditorManager.openFile(
          any<VirtualFile>(),
          anyOrNull(),
          any<FileEditorOpenOptions>(),
        )
      )
      .thenReturn(FileEditorComposite.EMPTY)
    projectRule.project.replaceService(
      FileEditorManager::class.java,
      mockFileEditorManager,
      projectRule.testRootDisposable,
    )
  }

  @Test
  fun openWhatsNewEditorDoesNotWaitForCompositeOpen() {
    val action = WhatsNewEditorAction()
    action.openWhatsNewEditor(projectRule.project, isAutoOpened = true)

    val fileCaptor = argumentCaptor<VirtualFile>()
    val optionsCaptor = argumentCaptor<FileEditorOpenOptions>()
    verify(mockFileEditorManager).openFile(fileCaptor.capture(), isNull(), optionsCaptor.capture())

    assertThat(fileCaptor.firstValue).isInstanceOf(WhatsNewVirtualFileImpl::class.java)
    assertThat(optionsCaptor.firstValue).isEqualTo(FileEditorOpenOptions(requestFocus = true, waitForCompositeOpen = false))
  }

  @Test
  fun openWhatsNewEditorReusesOpenWhatsNewVirtualFile() {
    val existingFile = WhatsNewVirtualFileImpl()
    whenever(mockFileEditorManager.openFiles).thenReturn(arrayOf(existingFile))

    val action = WhatsNewEditorAction()
    action.openWhatsNewEditor(projectRule.project, isAutoOpened = false)

    verify(mockFileEditorManager)
      .openFile(
        eq(existingFile),
        isNull(),
        eq(FileEditorOpenOptions(requestFocus = true, waitForCompositeOpen = false)),
      )
  }

  @Test
  fun editorProviderDoesNotRequireReadActionForAccept() {
    val provider = WhatsNewEditorProvider()
    assertThat(provider.acceptRequiresReadAction()).isFalse()
    assertThat(provider.accept(projectRule.project, WhatsNewVirtualFileImpl())).isTrue()
  }
}
