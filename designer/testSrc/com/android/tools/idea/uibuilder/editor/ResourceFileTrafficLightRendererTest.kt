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

import com.android.tools.idea.projectsystem.NamedIdeaSourceProvider
import com.android.tools.idea.projectsystem.NamedIdeaSourceProviderBuilder
import com.android.tools.idea.projectsystem.SourceProviderManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.androidFacet
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.daemon.impl.TrafficLightRenderer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

  @Test
  fun createRendererReturnsCustomRendererForQualifiedResourceSubdirectory(): Unit = runBlocking {
    projectRule.fixture.addFileToProject("AndroidManifest.xml", "<manifest/>")
    val file = projectRule.fixture.addFileToProject("res/layout-land/my_layout.xml", "<LinearLayout/>")

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
  fun createRendererSkipsResourceFolderResolutionForNonResourceFile(): Unit = runBlocking {
    val file = projectRule.fixture.addFileToProject("src/Preview.kt", "")
    val baseProvider = NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").build()
    val failingProvider =
      object : NamedIdeaSourceProvider by baseProvider {
        override val resDirectories: Iterable<VirtualFile>
          get() = throw AssertionError("Should not resolve resDirectories for non-resource files")

        override val resDirectoryUrls: Iterable<String>
          get() = throw AssertionError("Should not access resDirectoryUrls for non-resource files")
      }
    SourceProviderManager.replaceForTest(projectRule.module.androidFacet!!, projectRule.testRootDisposable, failingProvider)

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), file) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererReturnsNullForInvalidResourceSubdirectoryName(): Unit = runBlocking {
    val file = projectRule.fixture.addFileToProject("res/not_a_resource_folder/foo.xml", "<root/>")
    val baseProvider = NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").build()
    val failingProvider =
      object : NamedIdeaSourceProvider by baseProvider {
        override val resDirectories: Iterable<VirtualFile>
          get() = throw AssertionError("Should not resolve resDirectories for invalid resource subfolder")

        override val resDirectoryUrls: Iterable<String>
          get() = throw AssertionError("Should not access resDirectoryUrls for invalid resource subfolder")
      }
    SourceProviderManager.replaceForTest(projectRule.module.androidFacet!!, projectRule.testRootDisposable, failingProvider)

    val renderer = withContext(Dispatchers.Default) { contributor.createRenderer(mockEditor(), file) }
    assertThat(renderer).isNull()
  }

  @Test
  fun createRendererYieldsToPendingWriteAction(): Unit = runBlocking {
    // Use a parent directory name matching a ResourceFolderType so the pre-filter passes and
    // ResourceFolderManager is consulted inside the read action.
    val file = projectRule.fixture.addFileToProject("src/com/example/layout/Fake.kt", "")
    val enteredReadAction = CountDownLatch(1)
    val attempts = AtomicInteger(0)
    val writeActionCompleted = AtomicBoolean(false)

    fun onLookup() {
      if (attempts.incrementAndGet() == 1) {
        enteredReadAction.countDown()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadlineNanos) {
          ProgressManager.checkCanceled()
          Thread.sleep(10)
        }
        throw AssertionError("Read action was not canceled when a write action became pending")
      }
    }

    val baseProvider = NamedIdeaSourceProviderBuilder.create("main", "AndroidManifest.xml").build()
    val blockingOnFirstAttemptProvider =
      object : NamedIdeaSourceProvider by baseProvider {
        override val resDirectories: Iterable<VirtualFile>
          get() {
            onLookup()
            return emptyList()
          }

        override val resDirectoryUrls: Iterable<String>
          get() {
            onLookup()
            return emptyList()
          }
      }
    SourceProviderManager.replaceForTest(
      projectRule.module.androidFacet!!,
      projectRule.testRootDisposable,
      blockingOnFirstAttemptProvider,
    )

    val rendererDeferred = async(Dispatchers.Default) { contributor.createRenderer(mockEditor(), file) }
    assertThat(enteredReadAction.await(10, TimeUnit.SECONDS)).isTrue()

    withContext(Dispatchers.EDT) { runWriteActionAndWait { writeActionCompleted.set(true) } }

    assertThat(rendererDeferred.await()).isNull()
    assertThat(writeActionCompleted.get()).isTrue()
    assertThat(attempts.get()).isAtLeast(2)
  }
}
