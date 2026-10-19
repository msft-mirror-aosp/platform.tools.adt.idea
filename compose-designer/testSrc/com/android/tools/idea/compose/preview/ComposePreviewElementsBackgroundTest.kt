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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.compose.ComposePreviewElementsModel
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.concurrency.FlowableCollection
import com.android.tools.idea.concurrency.asCollection
import com.android.tools.idea.preview.BackgroundManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Tests for the backgrounds applied by [ComposePreviewElementsModel.instantiatedPreviewElementsFlow] from the [BackgroundManager]. */
class ComposePreviewElementsBackgroundTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val backgroundManager
    get() = BackgroundManager.getInstance(projectRule.project)

  private fun pointer(): SmartPsiElementPointer<PsiElement> = mock { on { project } doReturn projectRule.project }

  private fun previewElement(name: String, definition: SmartPsiElementPointer<PsiElement>?, body: SmartPsiElementPointer<PsiElement>) =
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

  private suspend fun instantiate(vararg previewElements: PsiComposePreviewElementInstance) =
    ComposePreviewElementsModel.instantiatedPreviewElementsFlow(MutableStateFlow(FlowableCollection.Present(previewElements.toList())))
      .first()
      .asCollection()
      .toList()

  @Test
  fun backgroundIsOnlyAppliedToTheSelectedAnnotation(): Unit = runBlocking {
    // Two @Preview annotations in the same function. They have the same name, e.g. if no name parameter is used, so only the definition
    // distinguishes them.
    val body = pointer()
    val preview1 = previewElement("Preview", definition = pointer(), body = body)
    val preview2 = previewElement("Preview", definition = pointer(), body = body)
    val background = PreviewDisplaySettings.Background.Image {}

    backgroundManager.setBackground(preview1, background)

    val result = instantiate(preview1, preview2)
    assertThat(result.map { it.displaySettings.background }).containsExactly(background, PreviewDisplaySettings.Background.None).inOrder()
  }

  @Test
  fun backgroundIsOnlyAppliedToTheSelectedMultiPreviewLeaf(): Unit = runBlocking {
    // All the leaves of a MultiPreview share the same definition.
    val body = pointer()
    val multiPreview = pointer()
    val light = previewElement("Preview - Light", definition = multiPreview, body = body)
    val dark = previewElement("Preview - Dark", definition = multiPreview, body = body)
    val background = PreviewDisplaySettings.Background.Image {}

    backgroundManager.setBackground(dark, background)

    val result = instantiate(light, dark)
    assertThat(result.map { it.displaySettings.background }).containsExactly(PreviewDisplaySettings.Background.None, background).inOrder()
  }

  @Test
  fun backgroundIsAppliedUsingThePreviewBodyWhenThereIsNoDefinition(): Unit = runBlocking {
    val preview = previewElement("Preview", definition = null, body = pointer())
    val background = PreviewDisplaySettings.Background.Image {}

    backgroundManager.setBackground(preview, background)

    assertThat(instantiate(preview).single().displaySettings.background).isEqualTo(background)
  }
}
