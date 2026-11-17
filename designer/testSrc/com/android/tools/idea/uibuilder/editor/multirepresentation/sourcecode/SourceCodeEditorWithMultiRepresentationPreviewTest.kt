/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.editor.multirepresentation.sourcecode

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.editor.closeProjectEditorsAndWaitForTrafficLight
import com.android.tools.idea.uibuilder.editor.disposeEditorAndWaitForTrafficLight
import com.android.tools.idea.uibuilder.editor.hasActiveErrorStripeJobs
import com.android.tools.idea.uibuilder.editor.multirepresentation.TestPreviewRepresentationProvider
import com.android.tools.idea.uibuilder.editor.multirepresentation.TextEditorWithMultiRepresentationPreview
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.daemon.impl.ErrorStripeUpdateManager
import com.intellij.codeInsight.daemon.impl.TrafficLightRenderer
import com.intellij.codeInsight.daemon.impl.TrafficLightRendererContributor
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorMarkupModel
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.awt.event.HierarchyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SourceCodeEditorWithMultiRepresentationPreviewTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  private val fixture: CodeInsightTestFixture
    get() = projectRule.fixture

  private val createdEditors = CopyOnWriteArrayList<Pair<SourceCodeEditorProvider, FileEditor>>()

  private fun <T : FileEditor> registerEditor(editorProvider: SourceCodeEditorProvider, editor: T): T = editor.also {
    createdEditors.add(editorProvider to it)
    Disposer.register(projectRule.testRootDisposable, it)
  }

  private suspend fun disposeTrackedEditor(editorProvider: SourceCodeEditorProvider, editor: FileEditor) {
    createdEditors.removeIf { (provider, candidate) -> provider === editorProvider && candidate === editor }
    disposeEditorAndWaitForTrafficLight(projectRule.project, editor) { editorProvider.disposeEditor(editor) }
  }

  @After
  fun tearDown(): Unit = runBlocking {
    val remaining = createdEditors.toList()
    createdEditors.clear()
    val additionalDisposals = remaining.map { (provider, editor) -> { provider.disposeEditor(editor) } }
    closeProjectEditorsAndWaitForTrafficLight(projectRule.project, additionalDisposals)
  }

  // Regression test for b/254613979
  @Test
  fun testNavigationMovesToSplitMode() = runBlocking {
    val file = fixture.addFileToProject("src/Preview.kt", "")
    val editorProvider = SourceCodeEditorProvider.forTesting(listOf(TestPreviewRepresentationProvider("Representation1", true)))
    val editor =
      withContext(Dispatchers.EDT) {
        registerEditor(
          editorProvider,
          editorProvider.createEditor(file.project, file.virtualFile) as TextEditorWithMultiRepresentationPreview<*>,
        )
      }
    withContext(Dispatchers.EDT) { fixture.openFileInEditor(file.virtualFile) }
    editor.selectNotify()

    // Wait for representations to be fully initialized
    waitUntil { editor.preview.representationNames.isNotEmpty() }

    // ChangeEditorSplitActions in IntelliJ 2024.1 uses the data provider to find about the editor.
    // Here, we inject the "open" editor so it finds it during testing.
    (DataManager.getInstance() as HeadlessDataManager).setTestDataProvider(
      {
        when (it) {
          PlatformCoreDataKeys.FILE_EDITOR.name -> editor
          else -> null
        }
      },
      fixture.testRootDisposable,
    )

    withContext(Dispatchers.EDT) {
      // Check we are in design mode before navigation is triggered
      editor.selectDesignMode(false)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    waitUntil { editor.isDesignMode() }
    withContext(Dispatchers.EDT) {
      editor.navigateTo(OpenFileDescriptor(projectRule.project, file.virtualFile, 0))
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    waitUntil("The editor was expected to switch to split mode when navigating to source") { editor.isSplitMode() }
  }

  @Test
  fun testToolbarReleasedWhenEditorIsDisposed() = runBlocking {
    val file = fixture.addFileToProject("src/Preview.kt", "")
    val editorProvider = SourceCodeEditorProvider.forTesting(listOf(TestPreviewRepresentationProvider("Representation1", true)))
    val editor =
      withContext(Dispatchers.EDT) {
        registerEditor(
          editorProvider,
          editorProvider.createEditor(file.project, file.virtualFile) as TextEditorWithMultiRepresentationPreview<*>,
        )
      }
    withContext(Dispatchers.EDT) { fixture.openFileInEditor(file.virtualFile) }
    editor.selectNotify()

    // Wait for representations to be fully initialized
    waitUntil { editor.preview.representationNames.isNotEmpty() }

    assertThat(editor.getToolbarComponentForTests()).isNotNull()
    disposeTrackedEditor(editorProvider, editor)
    assertThat(editor.getToolbarComponentForTests()).isNull()
  }

  @Test
  // Regression test for b/487042085
  fun testEditorIsActivatedOnlyWhenTheFileIsSelectedInTheEditor() = runBlocking {
    val file = fixture.addFileToProject("src/Preview.kt", "")
    val editorProvider = SourceCodeEditorProvider.forTesting(listOf(TestPreviewRepresentationProvider("Representation1", true)))
    val editor =
      withContext(Dispatchers.EDT) {
        registerEditor(
          editorProvider,
          editorProvider.createEditor(file.project, file.virtualFile) as TextEditorWithMultiRepresentationPreview<*>,
        )
      }
    // The editor is waiting for its first activation
    assertFalse(editor.hasBeenActivatedForTest())

    // If we selectNotify without opening the file in the editor, the editor should not be activated
    editor.selectNotify()
    assertFalse(editor.hasBeenActivatedForTest())

    // Now, if we open the file in the editor and call selectNotify, the activation should happen
    withContext(Dispatchers.EDT) { fixture.openFileInEditor(file.virtualFile) }
    editor.selectNotify()

    assertTrue(editor.hasBeenActivatedForTest())
  }

  @Test
  // Regression test for b/545671285
  fun testEditorActivatesWhenComponentHierarchyBecomesShowing() = runBlocking {
    val file = fixture.addFileToProject("src/Preview.kt", "")
    val editorProvider = SourceCodeEditorProvider.forTesting(listOf(TestPreviewRepresentationProvider("Representation1", true)))
    val editor =
      withContext(Dispatchers.EDT) {
        registerEditor(
          editorProvider,
          editorProvider.createEditor(file.project, file.virtualFile) as TextEditorWithMultiRepresentationPreview<*>,
        )
      }
    // Simulate startup where selectNotify is called before FileEditorManager.selectedFiles is populated
    editor.selectNotify()
    assertFalse(editor.hasBeenActivatedForTest())

    // When startup completes, the file is selected in FileEditorManager and the component becomes showing
    withContext(Dispatchers.EDT) {
      fixture.openFileInEditor(file.virtualFile)
      editor.isComponentShowingForTesting = true
      val event =
        HierarchyEvent(
          editor.component,
          HierarchyEvent.HIERARCHY_CHANGED,
          editor.component,
          editor.component,
          HierarchyEvent.SHOWING_CHANGED.toLong(),
        )
      for (listener in editor.component.hierarchyListeners) {
        listener.hierarchyChanged(event)
      }
    }

    assertTrue(editor.hasBeenActivatedForTest())
  }

  @Test
  fun testSetStateCommitsDocument() = runBlocking {
    val file = fixture.addFileToProject("src/Preview.kt", "")
    val editorProvider = SourceCodeEditorProvider.forTesting(listOf(TestPreviewRepresentationProvider("Representation1", true)))
    val psiDocumentManager = PsiDocumentManager.getInstance(projectRule.project)
    val editorScope = AndroidCoroutineScope(projectRule.testRootDisposable)
    var createdEditor: TextEditorWithMultiRepresentationPreview<*>? = null
    try {
      withContext(Dispatchers.EDT) {
        fixture.openFileInEditor(file.virtualFile)
        fixture.type("some text")
        assertFalse(psiDocumentManager.isCommitted(fixture.editor.document))
        // Modify document and open the editor in the same EDT block to check for the implicit commit
        createdEditor =
          registerEditor(
            editorProvider,
            editorProvider.createFileEditor(file.project, file.virtualFile, fixture.editor.document, editorScope)
              as TextEditorWithMultiRepresentationPreview<*>,
          )
        assertTrue(psiDocumentManager.isCommitted(fixture.editor.document))
      }
    } finally {
      createdEditor?.let { disposeTrackedEditor(editorProvider, it) }
    }
  }

  @Test
  fun testCloseProjectEditorsWaitsForInFlightErrorStripeJob() = runBlocking {
    val enteredLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)
    val intercepted = AtomicBoolean(false)
    val disposable = Disposer.newDisposable(projectRule.testRootDisposable)
    try {
      ExtensionPointName.create<TrafficLightRendererContributor>("com.intellij.trafficLightRendererContributor")
        .point
        .registerExtension(
          object : TrafficLightRendererContributor {
            override fun createRenderer(editor: Editor, psiFile: PsiFile?): TrafficLightRenderer? {
              if (editor.project === projectRule.project && intercepted.compareAndSet(false, true)) {
                enteredLatch.countDown()
                assertTrue(releaseLatch.await(5, TimeUnit.SECONDS))
              }
              return null
            }
          },
          LoadingOrder.FIRST,
          disposable,
        )

      val file = fixture.addFileToProject("src/Preview.kt", "")
      val editor =
        withContext(Dispatchers.EDT) {
          fixture.openFileInEditor(file.virtualFile)
          val ed = fixture.editor
          (ed.markupModel as EditorMarkupModel).isErrorStripeVisible = true
          ErrorStripeUpdateManager.getInstance(projectRule.project).launchRepaintErrorStripePanel(ed, file)
          ed
        }
      assertTrue(enteredLatch.await(5, TimeUnit.SECONDS), "Expected createRenderer to be invoked")
      assertTrue(hasActiveErrorStripeJobs(projectRule.project))

      val closeJob = async(Dispatchers.IO) { closeProjectEditorsAndWaitForTrafficLight(projectRule.project) }
      withContext(Dispatchers.IO) {
        waitUntil("Expected closeAllFiles to dispose editor before awaiting ErrorStripeUpdateManager") { editor.isDisposed }
        assertThat(withTimeoutOrNull(300.milliseconds) { closeJob.join() }).isNull()
      }
      assertFalse("Expected closeProjectEditorsAndWaitForTrafficLight to wait while createRenderer is in flight", closeJob.isCompleted)

      releaseLatch.countDown()
      closeJob.await()
      assertTrue(editor.isDisposed)
      assertFalse(hasActiveErrorStripeJobs(projectRule.project))
      assertThat(FileEditorManager.getInstance(projectRule.project).openFiles).isEmpty()
    } finally {
      releaseLatch.countDown()
      Disposer.dispose(disposable)
    }
  }
}
