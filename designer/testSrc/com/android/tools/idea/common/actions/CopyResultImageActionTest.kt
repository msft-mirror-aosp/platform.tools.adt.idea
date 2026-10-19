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
package com.android.tools.idea.common.actions

import com.android.ide.common.rendering.api.RecyclableImage
import com.android.tools.idea.actions.DESIGN_SURFACE
import com.android.tools.idea.common.model.DefaultSelectionModel
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.rendering.RenderResult
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.ClipboardSynchronizer
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CopyResultImageActionTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private lateinit var surface: NlDesignSurface
  private lateinit var selectionModel: DefaultSelectionModel

  @Before
  fun setUp() {
    selectionModel = DefaultSelectionModel()
    surface = mock<NlDesignSurface>()
    whenever(surface.selectionModel).thenReturn(selectionModel)
    CopyPasteManagerEx.getInstance().setContents(StringSelection("initial"))
  }

  @After
  fun tearDown() {
    ClipboardSynchronizer.getInstance().resetContent()
    CopyPasteManagerEx.getInstanceEx().removeIf { true }
  }

  private fun createEvent(action: CopyResultImageAction) =
    TestActionEvent.createTestEvent(
      action,
      SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, projectRule.project)
        .add(DESIGN_SURFACE, surface)
        .build(),
    )

  private fun createRenderResultWithImage(image: BufferedImage): RenderResult {
    val recyclableImage = mock<RecyclableImage>()
    whenever(recyclableImage.copy).thenReturn(image)
    val renderResult = mock<RenderResult>()
    whenever(renderResult.renderedImage).thenReturn(recyclableImage)
    return renderResult
  }

  @Test
  fun updateHidesActionWhenNoRenderResultIsAvailable() {
    val action = CopyResultImageAction()
    val event = createEvent(action)

    action.update(event)

    assertThat(event.presentation.isVisible).isFalse()
  }

  @Test
  fun copiesImageFromSceneViewAtMousePosition() {
    // CopyResultImageAction queries DesignSurface.sceneViewAtMousePosition directly rather than
    // receiving mouse coordinates. Here we mock that property to verify the fallback behavior
    // when no component is selected.
    val expectedImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    val renderResult = createRenderResultWithImage(expectedImage)
    val sceneManager = mock<LayoutlibSceneManager>()
    whenever(sceneManager.renderResult).thenReturn(renderResult)
    val sceneView = mock<SceneView>()
    whenever(sceneView.sceneManager).thenReturn(sceneManager)
    whenever(surface.sceneViewAtMousePosition).thenReturn(sceneView)

    val action = CopyResultImageAction()
    val event = createEvent(action)

    action.update(event)
    assertThat(event.presentation.isVisible).isTrue()

    action.actionPerformed(event)

    val contents = CopyPasteManagerEx.getInstance().contents
    assertThat(contents).isNotNull()
    assertThat(contents!!.isDataFlavorSupported(DataFlavor.imageFlavor)).isTrue()
    assertThat(contents.isDataFlavorSupported(DataFlavor.stringFlavor)).isFalse()
    assertThat(contents.getTransferData(DataFlavor.imageFlavor)).isEqualTo(expectedImage)
    val thrown = runCatching { contents.getTransferData(DataFlavor.stringFlavor) }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(UnsupportedFlavorException::class.java)
  }

  @Test
  fun copiesImageFromSelectedComponentModel() {
    val expectedImage = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
    val renderResult = createRenderResultWithImage(expectedImage)
    val sceneManager = mock<LayoutlibSceneManager>()
    whenever(sceneManager.renderResult).thenReturn(renderResult)
    val model = mock<NlModel>()
    val component = mock<NlComponent>()
    whenever(component.model).thenReturn(model)
    selectionModel.setSelection(listOf(component))
    whenever(surface.getSceneManager(model)).thenReturn(sceneManager)

    // Even if there is a SceneView at the mouse position, the selected component takes precedence.
    val mouseImage = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
    val mouseRenderResult = createRenderResultWithImage(mouseImage)
    val mouseSceneManager = mock<LayoutlibSceneManager>()
    whenever(mouseSceneManager.renderResult).thenReturn(mouseRenderResult)
    val mouseSceneView = mock<SceneView>()
    whenever(mouseSceneView.sceneManager).thenReturn(mouseSceneManager)
    whenever(surface.sceneViewAtMousePosition).thenReturn(mouseSceneView)

    val action = CopyResultImageAction()
    val event = createEvent(action)

    action.update(event)
    assertThat(event.presentation.isVisible).isTrue()

    action.actionPerformed(event)

    val contents = CopyPasteManagerEx.getInstance().contents
    assertThat(contents).isNotNull()
    assertThat(contents!!.isDataFlavorSupported(DataFlavor.imageFlavor)).isTrue()
    assertThat(contents.getTransferData(DataFlavor.imageFlavor)).isEqualTo(expectedImage)
  }
}
