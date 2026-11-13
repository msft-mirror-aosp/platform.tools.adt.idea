/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.scene

import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.tools.configurations.Configuration
import com.android.tools.idea.actions.LayoutPreviewHandler
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.LayoutScannerConfiguration
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderService.RenderTaskBuilder
import com.android.tools.rendering.RenderTask
import com.google.common.truth.Truth.assertThat
import com.intellij.psi.xml.XmlFile
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LayoutlibSceneRenderConfigurationTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val sceneRenderConfiguration =
    LayoutlibSceneRenderConfiguration(
        Mockito.mock(NlModel::class.java),
        Mockito.mock(NlDesignSurface::class.java),
        LayoutScannerConfiguration.DISABLED,
      )
      .apply { needsInflation.set(false) }

  @Test
  fun testChangingShowDecorationsForcesReinflate() {
    val defaultShowDecorations = sceneRenderConfiguration.showDecorations
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    sceneRenderConfiguration.showDecorations = !defaultShowDecorations
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()

    sceneRenderConfiguration.showDecorations = defaultShowDecorations
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()
  }

  @Test
  fun testChangingUsePrivateClassLoaderForcesReinflate() {
    val defaultUsePrivateClassLoader = sceneRenderConfiguration.usePrivateClassLoader
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    sceneRenderConfiguration.usePrivateClassLoader = !defaultUsePrivateClassLoader
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()

    sceneRenderConfiguration.usePrivateClassLoader = defaultUsePrivateClassLoader
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()
  }

  @Test
  fun testSettingTransparentRenderingForcesReinflate() {
    val defaultTransparentRendering = sceneRenderConfiguration.useTransparentRendering
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    sceneRenderConfiguration.useTransparentRendering = !defaultTransparentRendering
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()

    sceneRenderConfiguration.useTransparentRendering = defaultTransparentRendering
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()
  }

  @Test
  fun testChangingRenderingModeForcesReinflate() {
    assertThat(sceneRenderConfiguration.renderingMode).isNull()
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    // Setting the same value does not force a reinflate
    sceneRenderConfiguration.renderingMode = null
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    sceneRenderConfiguration.renderingMode = RenderingMode.SHRINK
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()

    sceneRenderConfiguration.needsInflation.set(false)
    sceneRenderConfiguration.renderingMode = RenderingMode.SHRINK
    assertThat(sceneRenderConfiguration.needsInflation.get()).isFalse()

    sceneRenderConfiguration.renderingMode = RenderingMode.V_SCROLL
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()

    sceneRenderConfiguration.needsInflation.set(false)
    sceneRenderConfiguration.renderingMode = null
    assertThat(sceneRenderConfiguration.needsInflation.get()).isTrue()
  }

  @Test
  fun testCreateRenderTaskUsesRenderingMode(): Unit = runBlocking {
    val model = mock<NlModel>()
    whenever(model.buildTarget).thenReturn(AndroidBuildTargetReference.gradleOnly(AndroidFacet.getInstance(projectRule.module)!!))
    whenever(model.file).thenReturn(mock<XmlFile>())
    val surface = mock<NlDesignSurface>()
    whenever(surface.layoutPreviewHandler).thenReturn(mock<LayoutPreviewHandler>())
    val config = LayoutlibSceneRenderConfiguration(model, surface, LayoutScannerConfiguration.DISABLED)
    val taskBuilder = mock<RenderTaskBuilder>(defaultAnswer = Mockito.RETURNS_SELF)
    whenever(taskBuilder.build(any())).thenAnswer { CompletableFuture.completedFuture(mock<RenderTask>()) }
    val renderService = mock<RenderService>()
    whenever(renderService.taskBuilder(any(), any(), any())).thenReturn(taskBuilder)

    config.createRenderTask(mock<Configuration>(), renderService, RenderLogger())
    verify(taskBuilder, never()).withRenderingMode(any())

    config.renderingMode = RenderingMode.SHRINK
    config.createRenderTask(mock<Configuration>(), renderService, RenderLogger())
    verify(taskBuilder).withRenderingMode(RenderingMode.SHRINK)

    config.renderingMode = RenderingMode.V_SCROLL
    config.createRenderTask(mock<Configuration>(), renderService, RenderLogger())
    verify(taskBuilder).withRenderingMode(RenderingMode.V_SCROLL)

    config.renderingMode = null
    config.createRenderTask(mock<Configuration>(), renderService, RenderLogger())
    verify(taskBuilder, times(2)).withRenderingMode(any())
  }
}
