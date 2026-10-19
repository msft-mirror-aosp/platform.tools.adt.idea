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
package com.android.tools.idea.preview

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BackgroundManagerTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val project
    get() = projectRule.project

  private suspend fun createPointer(fileName: String = "Test.kt"): SmartPsiElementPointer<PsiElement> {
    val psiFile = projectRule.fixture.addFileToProject(fileName, "fun test() {}")
    return readAction { SmartPointerManager.getInstance(project).createSmartPsiElementPointer<PsiElement>(psiFile) }
  }

  private fun previewElement(
    name: String,
    definition: SmartPsiElementPointer<PsiElement>?,
    body: SmartPsiElementPointer<PsiElement>? = null,
  ): PsiPreviewElement =
    SingleComposePreviewElementInstance(
      methodFqn = "TestKt.Preview",
      displaySettings =
        PreviewDisplaySettings(
          name = name,
          baseName = "Preview",
          parameterName = null,
          group = null,
          showDecoration = false,
          background = PreviewDisplaySettings.Background.None,
          organizationGroup = "",
          organizationName = "",
        ),
      previewElementDefinition = definition,
      previewBody = body,
      configuration = PreviewConfiguration.cleanAndGet(),
    )

  @Test
  fun `setBackground and getBackground work correctly`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val element = previewElement("Preview", createPointer())
    val background = PreviewDisplaySettings.Background.Image {}

    manager.setBackground(element, background)
    assertEquals(background, manager.getBackground(element))
  }

  @Test
  fun `setBackground with null removes the background`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val element = previewElement("Preview", createPointer())
    val background = PreviewDisplaySettings.Background.Image {}

    manager.setBackground(element, background)
    assertEquals(background, manager.getBackground(element))

    manager.setBackground(element, null)
    assertNull(manager.getBackground(element))
  }

  @Test
  fun `modificationTracker updates on background changes`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val element = previewElement("Preview", createPointer())
    val background1 = PreviewDisplaySettings.Background.Image {}
    val background2 = PreviewDisplaySettings.Background.Image {}

    val initialCount = manager.modificationTracker.modificationCount

    // Set for the first time
    manager.setBackground(element, background1)
    assertEquals(initialCount + 1, manager.modificationTracker.modificationCount)

    // Set the same background, no modification
    manager.setBackground(element, background1)
    assertEquals(initialCount + 1, manager.modificationTracker.modificationCount)

    // Set a different background
    manager.setBackground(element, background2)
    val countAfterSecondSet = manager.modificationTracker.modificationCount
    assertEquals(initialCount + 2, countAfterSecondSet)

    // Remove background
    manager.setBackground(element, null)
    val countAfterRemove = manager.modificationTracker.modificationCount
    assertEquals(initialCount + 3, countAfterRemove)

    // Remove again, no modification
    manager.setBackground(element, null)
    assertEquals(initialCount + 3, manager.modificationTracker.modificationCount)
  }

  @Test
  fun `modificationFlow emits on background changes`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val element = previewElement("Preview", createPointer())
    val background = PreviewDisplaySettings.Background.Image {}

    val initialCount = manager.modificationFlow.first()

    manager.setBackground(element, background)
    val countAfterSet = manager.modificationFlow.first()
    assertEquals(initialCount + 1, countAfterSet)

    manager.setBackground(element, null)
    val countAfterRemove = manager.modificationFlow.first()
    assertEquals(initialCount + 2, countAfterRemove)
  }

  @Test
  fun `backgrounds are scoped to each preview`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val body = createPointer("Body.kt")
    // Two @Preview annotations in the same function. They have the same name, e.g. if no name parameter is used.
    val annotation1 = previewElement("Preview", createPointer("Annotation1.kt"), body)
    val annotation2 = previewElement("Preview", createPointer("Annotation2.kt"), body)
    // Two leaves of a MultiPreview, which share the definition.
    val multiPreview = createPointer("MultiPreview.kt")
    val light = previewElement("Preview - Light", multiPreview, body)
    val dark = previewElement("Preview - Dark", multiPreview, body)
    val background = PreviewDisplaySettings.Background.Image {}

    manager.setBackground(annotation1, background)
    manager.setBackground(dark, background)
    assertEquals(background, manager.getBackground(annotation1))
    assertNull(manager.getBackground(annotation2))
    assertNull(manager.getBackground(light))
    assertEquals(background, manager.getBackground(dark))

    manager.setBackground(dark, null)
    assertNull(manager.getBackground(dark))
    assertEquals(background, manager.getBackground(annotation1))
  }

  @Test
  fun `previewBody is used when there is no definition`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val body = createPointer()
    val background = PreviewDisplaySettings.Background.Image {}

    manager.setBackground(previewElement("Preview", definition = null, body = body), background)
    assertEquals(background, manager.getBackground(previewElement("Preview", definition = null, body = body)))
  }

  @Test
  fun `preview without definition nor body is ignored`() = runTest {
    val manager = BackgroundManager.getInstance(project)
    val element = previewElement("Preview", definition = null)
    val initialCount = manager.modificationTracker.modificationCount

    manager.setBackground(element, PreviewDisplaySettings.Background.Image {})
    assertNull(manager.getBackground(element))
    assertEquals(initialCount, manager.modificationTracker.modificationCount)
  }
}
