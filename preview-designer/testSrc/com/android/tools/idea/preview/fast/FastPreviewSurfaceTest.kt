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
package com.android.tools.idea.preview.fast

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.editor.multirepresentation.MultiRepresentationPreview
import com.android.tools.idea.uibuilder.editor.multirepresentation.TextEditorWithMultiRepresentationPreview
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.runInEdtAndWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class FastPreviewSurfaceTest {
  @get:Rule val projectRule = AndroidProjectRule.onDisk(extraModules = listOf("lib", "other")).withKotlin()

  private val project
    get() = projectRule.project

  private val fixture
    get() = projectRule.fixture

  private lateinit var appModule: Module
  private lateinit var libModule: Module
  private lateinit var otherModule: Module

  private lateinit var libFile: PsiFile
  private lateinit var appFile: PsiFile
  private lateinit var otherFile: PsiFile

  @Before
  fun setUp() {
    appModule = projectRule.module
    val moduleManager = ModuleManager.getInstance(project)
    libModule = moduleManager.findModuleByName("lib")!!
    otherModule = moduleManager.findModuleByName("other")!!

    ModuleRootModificationUtil.addDependency(appModule, libModule)

    libFile = fixture.addFileToProject("lib/src/LibFile.kt", "fun libFunction() {}")
    appFile = fixture.addFileToProject("src/AppFile.kt", "fun appFunction() {}")
    otherFile = fixture.addFileToProject("other/src/OtherFile.kt", "fun other() {}")
  }

  @Test
  fun testFindDependentAndroidHolderModules() = runReadAction {
    // collectOutsRecursively includes the starting module itself plus all downstream dependents
    val dependentModules = project.findDependentAndroidHolderModules(listOf(libModule)) { true }
    assertEquals(setOf(libModule.androidHolderModule, appModule.androidHolderModule), dependentModules)

    // With a predicate filtering to only other modules
    val filtered = project.findDependentAndroidHolderModules(listOf(libModule)) { it != libModule.androidHolderModule }
    assertEquals(setOf(appModule.androidHolderModule), filtered)

    // When passing a module from appModule (no other module depends on app)
    val appDependents = project.findDependentAndroidHolderModules(listOf(appModule)) { it != appModule.androidHolderModule }
    assertTrue(appDependents.isEmpty())
  }

  @Test
  fun testFindOpenPreviewAndroidHolderModulesWithMultiRepresentationPreview() = runReadAction {
    val mockPreview = mock(MultiRepresentationPreview::class.java)
    whenever(mockPreview.file).thenReturn(appFile.virtualFile)
    whenever(mockPreview.representationNames).thenReturn(listOf("Representation1"))

    val openPreviewModules = project.findOpenPreviewAndroidHolderModules(arrayOf(mockPreview))
    assertEquals(setOf(appModule.androidHolderModule), openPreviewModules)
  }

  @Test
  fun testFindOpenPreviewAndroidHolderModulesWithTextEditorWithMultiRepresentationPreview() = runReadAction {
    val mockPreview = mock(MultiRepresentationPreview::class.java)
    whenever(mockPreview.file).thenReturn(appFile.virtualFile)
    whenever(mockPreview.representationNames).thenReturn(listOf("Representation1"))

    val mockTextEditor = mock(TextEditorWithMultiRepresentationPreview::class.java)
    whenever(mockTextEditor.preview).thenReturn(mockPreview)

    val openPreviewModules = project.findOpenPreviewAndroidHolderModules(arrayOf(mockTextEditor))
    assertEquals(setOf(appModule.androidHolderModule), openPreviewModules)
  }

  @Test
  fun testFindOpenPreviewAndroidHolderModulesIgnoresEmptyRepresentationsAndRegularEditors() = runReadAction {
    val emptyPreview = mock(MultiRepresentationPreview::class.java)
    whenever(emptyPreview.file).thenReturn(appFile.virtualFile)
    whenever(emptyPreview.representationNames).thenReturn(emptyList())

    val regularEditor = mock(FileEditor::class.java)

    val openPreviewModules = project.findOpenPreviewAndroidHolderModules(arrayOf(emptyPreview, regularEditor))
    assertTrue(openPreviewModules.isEmpty())
  }

  @Test
  fun testFindOpenPreviewAndroidHolderModulesDefault() {
    runInEdtAndWait {
      runReadAction {
        val openPreviewModules = project.findOpenPreviewAndroidHolderModules()
        assertTrue(openPreviewModules.isEmpty())
      }
    }
  }

  @Test
  fun testDependentModulesWithOpenPreviewIntersection() = runReadAction {
    val appPreview = mock(MultiRepresentationPreview::class.java)
    whenever(appPreview.file).thenReturn(appFile.virtualFile)
    whenever(appPreview.representationNames).thenReturn(listOf("AppPreview"))

    val otherPreview = mock(MultiRepresentationPreview::class.java)
    whenever(otherPreview.file).thenReturn(otherFile.virtualFile)
    whenever(otherPreview.representationNames).thenReturn(listOf("OtherPreview"))

    val openPreviewModules = project.findOpenPreviewAndroidHolderModules(arrayOf(appPreview, otherPreview))
    assertEquals(setOf(appModule.androidHolderModule, otherModule.androidHolderModule), openPreviewModules)

    // When libModule is out-of-date, and we exclude libModule itself (as CommonFastPreviewSurface does with
    // otherOpenPreviewModules = openPreviewModules - previewFileAndroidModule.androidHolderModule):
    val otherOpenPreviewModules = openPreviewModules - libModule.androidHolderModule
    val outOfDateModules = project.findDependentAndroidHolderModules(listOf(libModule)) { it in otherOpenPreviewModules }
    assertEquals(setOf(appModule.androidHolderModule), outOfDateModules)
  }
}
