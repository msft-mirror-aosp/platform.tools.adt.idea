/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.scene

import com.android.SdkConstants
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.tools.idea.common.fixtures.ComponentDescriptor
import com.android.tools.idea.common.model.NlDataProvider
import com.android.tools.idea.compose.preview.NopComposePreviewManager
import com.android.tools.idea.compose.preview.PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.NlModelBuilderUtil
import com.android.tools.idea.uibuilder.surface.NlSurfaceBuilder
import com.android.tools.idea.util.androidFacet
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.config.DeviceConfig
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.Orientation
import com.android.tools.preview.config.Shape
import com.android.tools.preview.config.createDeviceInstance
import com.android.tools.rendering.RenderResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

class ComposeScreenViewProvidersTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun `shape policy depends on the showSystemUi value`() = runBlocking {
    val model =
      withContext(Dispatchers.EDT) {
        NlModelBuilderUtil.model(
            AndroidBuildTargetReference.gradleOnly(projectRule.module.androidFacet!!),
            projectRule.fixture,
            SdkConstants.FD_RES_LAYOUT,
            "model.xml",
            ComponentDescriptor("LinearLayout"),
          )
          .build()
      }
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable, false)
    surface.addModelsWithoutRender(listOf(model))

    // Create a device with round shape
    val deviceWithRoundFrame =
      DeviceConfig(width = 600f, height = 600f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round).createDeviceInstance()
    model.configuration.setDevice(deviceWithRoundFrame, false)

    var previewElement =
      SingleComposePreviewElementInstance.forTesting<SmartPsiElementPointer<PsiElement>>(
        "TestMethod",
        displayName = "displayName",
        showDecorations = true,
      )
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }

    val composeScreenViewProvider = ComposeScreenViewProvider(NopComposePreviewManager())

    // When showDecorations is true, the scene view should always use the device shape. In this
    // case, round.
    assertTrue(composeScreenViewProvider.createPrimarySceneView(surface, surface.getSceneManager(model)!!).screenShape is Ellipse2D)

    // When showDecorations is false, the scene view should always use a square shape
    previewElement = SingleComposePreviewElementInstance.forTesting("TestMethod", displayName = "displayName", showDecorations = false)
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }
    assertTrue(composeScreenViewProvider.createPrimarySceneView(surface, surface.getSceneManager(model)!!).screenShape is Rectangle)
  }

  // Regression test for b/530122673
  @Test
  fun `shape policy dynamically updates when toggling showDecorations on existing scene view`() = runBlocking {
    val model =
      withContext(Dispatchers.EDT) {
        NlModelBuilderUtil.model(
            AndroidBuildTargetReference.gradleOnly(projectRule.module.androidFacet!!),
            projectRule.fixture,
            SdkConstants.FD_RES_LAYOUT,
            "model.xml",
            ComponentDescriptor("LinearLayout"),
          )
          .build()
      }
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable, false)
    surface.addModelsWithoutRender(listOf(model))

    // Create a device with round shape
    val deviceWithRoundFrame =
      DeviceConfig(width = 600f, height = 600f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round).createDeviceInstance()
    model.configuration.setDevice(deviceWithRoundFrame, false)

    var previewElement =
      SingleComposePreviewElementInstance.forTesting<SmartPsiElementPointer<PsiElement>>(
        "TestMethod",
        displayName = "displayName",
        showDecorations = true,
      )
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }

    val composeScreenViewProvider = ComposeScreenViewProvider(NopComposePreviewManager())
    val screenView = composeScreenViewProvider.createPrimarySceneView(surface, surface.getSceneManager(model)!!)

    // When showDecorations is true, the scene view should use the round device shape.
    assertTrue(screenView.screenShape is Ellipse2D)

    // When showDecorations is toggled to false, the same scene view instance should dynamically update to use a square shape
    previewElement = SingleComposePreviewElementInstance.forTesting("TestMethod", displayName = "displayName", showDecorations = false)
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }
    assertTrue(screenView.screenShape is Rectangle)

    // When showDecorations is toggled back to true, the same scene view instance should dynamically update back to round
    previewElement = SingleComposePreviewElementInstance.forTesting("TestMethod", displayName = "displayName", showDecorations = true)
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }
    assertTrue(screenView.screenShape is Ellipse2D)
  }

  // Regression test for b/332997031
  @Test
  fun `scene layer is not shown on hover initially`() = runBlocking {
    val model =
      withContext(Dispatchers.EDT) {
        NlModelBuilderUtil.model(
            AndroidBuildTargetReference.gradleOnly(projectRule.module.androidFacet!!),
            projectRule.fixture,
            SdkConstants.FD_RES_LAYOUT,
            "model.xml",
            ComponentDescriptor("LinearLayout"),
          )
          .build()
      }
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable, false)
    surface.addModelsWithoutRender(listOf(model))

    val composeScreenViewProvider = ComposeScreenViewProvider(NopComposePreviewManager())
    val screenView = composeScreenViewProvider.createPrimarySceneView(surface, surface.getSceneManager(model)!!)
    val getLayersMethod =
      com.android.tools.idea.common.surface.SceneView::class.java.getDeclaredMethod("getLayers").apply {
        isAccessible = true
      }
    val layers = getLayersMethod.invoke(screenView) as List<*>
    val sceneLayer = layers.filterIsInstance<com.android.tools.idea.common.surface.SceneLayer>().single()

    assertFalse(sceneLayer.isShowOnHover)
  }

  @Test
  fun `round devices use a capsule shape when the render is expanded`() = runBlocking {
    val model =
      withContext(Dispatchers.EDT) {
        NlModelBuilderUtil.model(
            AndroidBuildTargetReference.gradleOnly(projectRule.module.androidFacet!!),
            projectRule.fixture,
            SdkConstants.FD_RES_LAYOUT,
            "model.xml",
            ComponentDescriptor("LinearLayout"),
          )
          .build()
      }
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable, false)
    surface.addModelsWithoutRender(listOf(model))
    val previewElement =
      SingleComposePreviewElementInstance.forTesting<SmartPsiElementPointer<PsiElement>>(
        "TestMethod",
        displayName = "displayName",
        showDecorations = true,
      )
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }
    val sceneManager = surface.getSceneManager(model)!!
    val screenView = ComposeScreenViewProvider(NopComposePreviewManager()).createPrimarySceneView(surface, sceneManager)

    // A round device taller than wide, as a vertically expanded render would be.
    model.configuration.setDevice(
      DeviceConfig(width = 400f, height = 1200f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round).createDeviceInstance(),
      false,
    )
    assertTrue(screenView.screenShape is Ellipse2D)
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.V_SCROLL
    // Half-circles of the width at the top and bottom
    val verticalCapsule = screenView.screenShape as RoundRectangle2D
    assertTrue(verticalCapsule.height > verticalCapsule.width)
    assertEquals(verticalCapsule.width, verticalCapsule.arcWidth, 0.0)
    assertEquals(verticalCapsule.width, verticalCapsule.arcHeight, 0.0)

    // A round device wider than tall, as a horizontally expanded render would be.
    model.configuration.setDevice(
      DeviceConfig(
          width = 1200f,
          height = 400f,
          dimUnit = DimUnit.px,
          dpi = 480,
          shape = Shape.Round,
          orientation = Orientation.landscape,
        )
        .createDeviceInstance(),
      false,
    )
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.H_SCROLL
    // Half-circles of the height at the start and end
    val horizontalCapsule = screenView.screenShape as RoundRectangle2D
    assertTrue(horizontalCapsule.width > horizontalCapsule.height)
    assertEquals(horizontalCapsule.height, horizontalCapsule.arcWidth, 0.0)
    assertEquals(horizontalCapsule.height, horizontalCapsule.arcHeight, 0.0)

    // Expanded content that is not elongated along the expanded axis keeps the device shape.
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.V_SCROLL
    assertTrue(screenView.screenShape is Ellipse2D)
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.H_SCROLL

    // Expanded content that fits in the device keeps the device circle.
    model.configuration.setDevice(
      DeviceConfig(width = 600f, height = 600f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round).createDeviceInstance(),
      false,
    )
    assertTrue(screenView.screenShape is Ellipse2D)

    // Non-round devices are not clipped.
    model.configuration.setDevice(
      DeviceConfig(width = 400f, height = 1200f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Normal).createDeviceInstance(),
      false,
    )
    assertNull(screenView.screenShape)
  }

  @Test
  fun `screen shape uses render result rendering mode over scene render configuration`(): Unit = runBlocking {
    val model =
      withContext(Dispatchers.EDT) {
        NlModelBuilderUtil.model(
            AndroidBuildTargetReference.gradleOnly(projectRule.module.androidFacet!!),
            projectRule.fixture,
            SdkConstants.FD_RES_LAYOUT,
            "model.xml",
            ComponentDescriptor("LinearLayout"),
          )
          .build()
      }
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable, false)
    surface.addModelsWithoutRender(listOf(model))
    val previewElement =
      SingleComposePreviewElementInstance.forTesting<SmartPsiElementPointer<PsiElement>>(
        "TestMethod",
        displayName = "displayName",
        showDecorations = true,
      )
    model.dataProvider =
      object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
        override fun getData(dataId: String): Any? = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
      }
    model.configuration.setDevice(
      DeviceConfig(width = 400f, height = 400f, dimUnit = DimUnit.px, dpi = 480, shape = Shape.Round).createDeviceInstance(),
      false,
    )

    val sceneManager = spy(surface.getSceneManager(model)!!)
    doNothing().whenever(sceneManager).dispose()
    Disposer.register(projectRule.testRootDisposable, sceneManager)
    val screenView = ComposeScreenViewProvider(NopComposePreviewManager()).createPrimarySceneView(surface, sceneManager)

    // Render result produced in V_SCROLL keeps the capsule even if configuration changes to NORMAL.
    val expandedResult = mock<RenderResult>()
    whenever(expandedResult.rootViewDimensions).thenReturn(Dimension(400, 1200))
    whenever(expandedResult.renderingMode).thenReturn(RenderingMode.V_SCROLL)
    whenever(sceneManager.renderResult).thenReturn(expandedResult)
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.NORMAL
    assertTrue(screenView.screenShape is RoundRectangle2D)

    // Render result produced in NORMAL (e.g. fallback on old ui-tooling) does not use a capsule even if configuration requests V_SCROLL.
    val normalResult = mock<RenderResult>()
    whenever(normalResult.rootViewDimensions).thenReturn(Dimension(400, 1200))
    whenever(normalResult.renderingMode).thenReturn(RenderingMode.NORMAL)
    whenever(sceneManager.renderResult).thenReturn(normalResult)
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.V_SCROLL
    assertTrue(screenView.screenShape is Ellipse2D)

    // Render result with null rendering mode (e.g. error result without a cached image) falls back to configuration.
    val errorResult = mock<RenderResult>()
    whenever(errorResult.rootViewDimensions).thenReturn(Dimension(400, 1200))
    whenever(errorResult.renderingMode).thenReturn(null)
    whenever(sceneManager.renderResult).thenReturn(errorResult)
    sceneManager.sceneRenderConfiguration.renderingMode = RenderingMode.V_SCROLL
    assertTrue(screenView.screenShape is RoundRectangle2D)
  }
}
