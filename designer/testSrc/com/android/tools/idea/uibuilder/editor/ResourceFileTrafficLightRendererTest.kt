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
package com.android.tools.idea.uibuilder.editor

import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.daemon.impl.TrafficLightRenderer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class ResourceFileTrafficLightRendererTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val contributor = ResourceFileTrafficLightRendererContributor()

  private suspend fun openEditorInEdt(file: PsiFile): Editor =
    withContext(Dispatchers.EDT) {
      projectRule.fixture.configureFromExistingVirtualFile(file.virtualFile)
      projectRule.fixture.editor
    }

  private fun mockEditor(isDisposed: Boolean = false): Editor =
    mock(Editor::class.java).apply { whenever(this.isDisposed).thenReturn(isDisposed) }

  @Test
  fun createRendererReturnsCustomRendererForResourceFile(): Unit = runBlocking {
    projectRule.fixture.addFileToProject("AndroidManifest.xml", "<manifest/>")
    val file = projectRule.fixture.addFileToProject("res/layout/my_layout.xml", "<LinearLayout/>")

    var renderer: TrafficLightRenderer? = null
    try {
      val editor = openEditorInEdt(file)
      renderer = withContext(Dispatchers.Default) { contributor.createRenderer(editor, file) }
      assertThat(renderer).isInstanceOf(ResourceFileTrafficLightRender::class.java)
    } finally {
      closeProjectEditorsAndWaitForTrafficLight(
        projectRule.project,
        listOfNotNull(renderer?.let { r -> { Disposer.dispose(r) } }),
      )
    }
  }

  @Test
  fun createRendererReturnsNullForNonResourceFile(): Unit = runBlocking {
    val file = projectRule.fixture.addFileToProject("src/Preview.kt", "")

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), file) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererReturnsNullForNullFile(): Unit = runBlocking {
    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), null) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererReturnsNullWhenEditorIsDisposed(): Unit = runBlocking {
    val file = projectRule.fixture.addFileToProject("res/layout/my_layout.xml", "<LinearLayout/>")

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(isDisposed = true), file) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererReturnsNullWhenFileIsDeleted(): Unit = runBlocking {
    val file = projectRule.fixture.addFileToProject("res/layout/my_layout.xml", "<LinearLayout/>")

    withContext(Dispatchers.EDT) { runWriteActionAndWait { file.virtualFile.parent.delete(this) } }

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), file) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererReturnsNullWhenAncestorDirectoryIsInvalid(): Unit = runBlocking {
    val invalidParent = mock(VirtualFile::class.java)
    whenever(invalidParent.isValid).thenReturn(false)

    val virtualFile = mock(VirtualFile::class.java)
    whenever(virtualFile.isValid).thenReturn(true)
    whenever(virtualFile.parent).thenReturn(invalidParent)

    val psiFile = mock(PsiFile::class.java)
    whenever(psiFile.isValid).thenReturn(true)
    whenever(psiFile.project).thenReturn(projectRule.project)
    whenever(psiFile.originalFile).thenReturn(psiFile)
    whenever(psiFile.virtualFile).thenReturn(virtualFile)
    whenever(psiFile.containingDirectory).thenThrow(AssertionError("Should not access containingDirectory when ancestor is invalid"))

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), psiFile) }
    assertThat(renderer).isNull()
  }
}
