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
package com.android.tools.idea.compose.preview.resize

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_LAYOUT_HEIGHT
import com.android.SdkConstants.ATTR_LAYOUT_WIDTH
import com.android.SdkConstants.VALUE_MATCH_PARENT
import com.android.SdkConstants.VALUE_WRAP_CONTENT
import com.android.ide.common.resources.Locale
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.devices.Device
import com.android.sdklib.devices.Hardware
import com.android.sdklib.devices.Screen
import com.android.sdklib.devices.Software
import com.android.sdklib.devices.State
import com.android.sdklib.internal.avd.AvdInfo
import com.android.tools.adtui.ZoomController
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.ConfigurationListener.CFG_ACTIVITY
import com.android.tools.configurations.ConfigurationListener.CFG_NIGHT_MODE
import com.android.tools.configurations.ConfigurationModelModule
import com.android.tools.configurations.ConfigurationSettings
import com.android.tools.configurations.ResourceResolverCache
import com.android.tools.configurations.updateScreenSize
import com.android.tools.idea.common.model.NlDataProvider
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.compose.preview.PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneRenderConfiguration
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.rendering.RenderResult
import com.google.common.collect.ImmutableList
import com.intellij.openapi.util.Disposer
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class ConfigurationResizeListenerTest {

  @get:Rule val edtRule = EdtRule()
  @get:Rule val applicationRule = ApplicationRule()

  private val needsInflation = AtomicBoolean(false)
  private val layoutlibSceneManagerConfiguration =
    mock<LayoutlibSceneRenderConfiguration>().also { whenever(it.needsInflation).thenReturn(needsInflation) }
  val configurationSettings = TestConfigurationSettingsImpl()

  private var showDecoration = false
    set(value) {
      whenever(layoutlibSceneManagerConfiguration.showDecorations).thenReturn(value)
      field = value
    }

  @Test
  fun `listener ignores changes without CFG_DEVICE`() = runTest {
    val sceneManager = mock<LayoutlibSceneManager>()
    val configuration = createConfiguration(500, 600)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val listener = ConfigurationResizeListener(sceneManager, configuration, dispatcher).also { advanceUntilIdle() }

    listener.changed(CFG_NIGHT_MODE or CFG_ACTIVITY) // No CFG_DEVICE
    advanceUntilIdle()

    verify(sceneManager, never()).requestRenderWithNewSize(any(), any())
    Disposer.dispose(sceneManager)
  }

  private fun createSceneManager(showDecorations: Boolean = true, isCanvasResizing: Boolean = true): LayoutlibSceneManager {
    this.showDecoration = showDecorations
    needsInflation.set(false)
    return mock<LayoutlibSceneManager>().also {
      whenever(it.sceneRenderConfiguration).thenReturn(layoutlibSceneManagerConfiguration)
      val surface = mock<NlDesignSurface>()
      val zoomController = mock<ZoomController>()
      whenever(surface.zoomController).thenReturn(zoomController)
      whenever(surface.isCanvasResizing).thenReturn(isCanvasResizing)
      whenever(it.designSurface).thenReturn(surface)
      whenever(it.renderResult).thenReturn(mock<RenderResult>())
    }
  }

  @Test
  fun `listener triggers render with showDecoration true`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()

    assertTrue(needsInflation.get())
    verify(sceneManager, times(1)).requestRenderWithNewSize(700, 800)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener triggers render with swapped x and y due to landscape orientation`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    val newDevice = device(500, 1000)
    val state = newDevice.defaultState.deepCopy()!!.apply { orientation = ScreenOrientation.LANDSCAPE }
    configuration.setEffectiveDevice(newDevice, state)
    advanceUntilIdle()

    verify(sceneManager, times(1)).requestRenderWithNewSize(1000, 500)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener triggers render when device changes even if dimensions are identical`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    val newDeviceWithSameSize = device(500, 600)
    configuration.setEffectiveDevice(newDeviceWithSameSize, newDeviceWithSameSize.defaultState)
    advanceUntilIdle()

    verify(sceneManager, times(1)).requestRenderWithNewSize(500, 600)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener triggers render with showDecoration false`() = runTest {
    val sceneManager = createSceneManager(false)
    val configuration = createConfiguration(500, 600)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()

    assertTrue(needsInflation.get())
    verify(sceneManager, times(1)).requestRenderWithNewSize(700, 800)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener still renders when viewObject is null after a previous render`() = runTest {
    val sceneManager = createSceneManager(false)
    val configuration = createConfiguration(500, 600)

    whenever(sceneManager.viewObject).thenReturn(null) // e.g. the last render failed
    whenever(sceneManager.renderResult).thenReturn(mock<RenderResult>())

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)
    configuration.updateScreenSize(700, 800)

    advanceUntilIdle()

    assertTrue(needsInflation.get())
    verify(sceneManager, times(1)).requestRenderWithNewSize(700, 800)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener does not render when the preview has never been rendered`() = runTest {
    val sceneManager = createSceneManager(false)
    val configuration = createConfiguration(500, 600)

    whenever(sceneManager.viewObject).thenReturn(null)
    whenever(sceneManager.renderResult).thenReturn(null)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)
    configuration.updateScreenSize(700, 800)

    advanceUntilIdle()

    assertFalse(needsInflation.get())
    verify(sceneManager, never()).requestRenderWithNewSize(any(), any())
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `multiple device changes are handled sequentially`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val listener = ConfigurationResizeListener(sceneManager, configuration, dispatcher).also { advanceUntilIdle() }
    configuration.addListener(listener)

    // Simulate multiple, rapid device changes.
    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    configuration.updateScreenSize(900, 1000)
    advanceUntilIdle()
    configuration.updateScreenSize(1100, 1200)
    advanceUntilIdle()

    // Verify that requestRenderWithNewSize was called for each size.
    verify(sceneManager, times(1)).requestRenderWithNewSize(700, 800)
    verify(sceneManager, times(1)).requestRenderWithNewSize(900, 1000)
    verify(sceneManager, times(1)).requestRenderWithNewSize(1100, 1200)
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener is removed on SceneManager disposal`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    assertFalse(listener.isDisposed())
    configuration.addListener(listener)

    Disposer.dispose(sceneManager) // Simulate disposal
    assertTrue(listener.isDisposed())
    configuration.updateScreenSize(12, 12)
    advanceUntilIdle()
    verify(sceneManager, never()).requestRenderWithNewSize(any(), any())
    Disposer.dispose(sceneManager)
  }

  @Test
  fun `listener created for a disposed SceneManager is disposed`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)
    Disposer.dispose(sceneManager)

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    assertTrue(listener.isDisposed())
  }

  @Test
  fun `collectLatest continues after exception in requestRender`() = runTest {
    val sceneManager = createSceneManager(true)
    val configuration = createConfiguration(500, 600)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val listener = ConfigurationResizeListener(sceneManager, configuration, dispatcher).also { advanceUntilIdle() }
    configuration.addListener(listener)

    // Make requestRenderWithNewSize always throw an exception
    whenever(sceneManager.requestRenderWithNewSize(any(), any())).thenThrow(RuntimeException("Simulated error"))

    // Simulate multiple device changes.  Every call should throw, but collectLatest should keep
    // going.
    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    configuration.updateScreenSize(900, 1000)
    advanceUntilIdle()
    configuration.updateScreenSize(1100, 1200)
    advanceUntilIdle()

    // Verify that requestRenderWithNewSize was called the expected number of times.
    verify(sceneManager, times(3)).requestRenderWithNewSize(any(), any())
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener triggers zoom-to-fit when not canvas resizing and scale differs significantly`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)

    // Required scale 0.25 (much smaller than current scale 1.0).
    whenever(zoomController.getFitScale()).thenReturn(0.25)

    // Setup the listener with a custom configuration size.
    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    // Verify that resetZoomToFitSettings was called with the expected parameters.
    verify(zoomController, times(1)).resetZoomToFitSettings(shouldWaitForResize = false, shouldWaitForLayoutCreated = false)

    // Verify that zoom-to-fit was triggered because the scale differs significantly.
    verify(zoomController, times(1)).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener does not trigger zoom-to-fit when scale does not differ significantly`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)

    // Required scale 1.1 (close to current scale 1.0, and current scale is not larger than required scale).
    whenever(zoomController.getFitScale()).thenReturn(1.1)

    // Setup the listener with a custom configuration size.
    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    // Verify that zoom-to-fit was not triggered because the scale does not differ significantly.
    verify(zoomController, never()).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener does not trigger zoom-to-fit when preview is only moderately larger than viewport`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)

    // Required scale 0.8 (preview is 1.25x viewport, not more than double).
    whenever(zoomController.getFitScale()).thenReturn(0.8)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, never()).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener does not trigger zoom-to-fit during canvas resizing even if scale differs significantly`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = true)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)
    // Required scale 0.1 (much smaller than current scale 1.0).
    whenever(zoomController.getFitScale()).thenReturn(0.1)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, never()).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener triggers zoom-to-fit when preview is more than double the viewport`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)
    // Required scale 0.45 (current scale 1.0 > 0.45 * 2.0 = 0.9, preview is >2x viewport)
    whenever(zoomController.getFitScale()).thenReturn(0.45)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, times(1)).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener triggers zoom-to-fit when preview is less than half the viewport`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)
    // Required scale 2.5 (required scale 2.5 > 1.0 * 2.0 = 2.0, preview is <0.5x viewport)
    whenever(zoomController.getFitScale()).thenReturn(2.5)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, times(1)).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener does not trigger zoom-to-fit when preview is moderately smaller than viewport`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)
    // Required scale 1.8 (within 0.5x to 2.0x range)
    whenever(zoomController.getFitScale()).thenReturn(1.8)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, never()).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener does not trigger zoom-to-fit when requiredScale is invalid`() = runTest {
    val sceneManager = createSceneManager(showDecorations = true, isCanvasResizing = false)
    val zoomController = sceneManager.designSurface.zoomController
    whenever(zoomController.scale).thenReturn(1.0)
    // Scale 0.0 is considered invalid
    whenever(zoomController.getFitScale()).thenReturn(0.0)

    val configuration = createConfiguration(500, 600)
    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }
    configuration.addListener(listener)

    configuration.updateScreenSize(700, 800)
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()

    verify(zoomController, never()).zoomToFit()
    Disposer.dispose(sceneManager)
  }

  @RunsInEdt
  @Test
  fun `listener updates layout dimensions to match_parent for custom size and wrap_content for preset device or revert`() = runTest {
    val sceneManager = createSceneManager(showDecorations = false)
    val configuration = createConfiguration(500, 600)
    val originalDevice = configuration.cachedDevice

    val preview = FakePreviewModel(sceneManager)
    var isCustomSized = false

    val listener =
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler), isCustomSized = { isCustomSized })
        .also { advanceUntilIdleIncludingEdt() }
    configuration.addListener(listener)

    try {
      // 1. Custom WxH resize sets layout_width and layout_height to match_parent.
      isCustomSized = true
      configuration.updateScreenSize(700, 800)
      advanceUntilIdleIncludingEdt()

      assertEquals(VALUE_MATCH_PARENT, preview.layoutWidth)
      assertEquals(VALUE_MATCH_PARENT, preview.layoutHeight)
      verify(sceneManager, times(1)).requestRenderWithNewSize(700, 800)

      // 2. When the preview is no longer custom sized, e.g. a preset device is selected, wrap_content is restored (b/421356464).
      isCustomSized = false
      val presetDevice = device(1080, 2424)
      configuration.setEffectiveDevice(presetDevice, presetDevice.defaultState)
      advanceUntilIdleIncludingEdt()

      assertEquals(VALUE_WRAP_CONTENT, preview.layoutWidth)
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutHeight)
      verify(sceneManager, times(1)).requestRenderWithNewSize(1080, 2424)

      // 3. Another custom WxH resize sets match_parent again, and reverting to originalDevice restores wrap_content.
      isCustomSized = true
      configuration.updateScreenSize(900, 1000)
      advanceUntilIdleIncludingEdt()
      assertEquals(VALUE_MATCH_PARENT, preview.layoutWidth)
      assertEquals(VALUE_MATCH_PARENT, preview.layoutHeight)

      isCustomSized = false
      configuration.setEffectiveDevice(originalDevice, originalDevice?.defaultState)
      advanceUntilIdleIncludingEdt()
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutWidth)
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutHeight)

      // 4. A custom-sized preview with System UI keeps its configured dimensions, since it already fills the device screen.
      preview.previewElement = SingleComposePreviewElementInstance.forTesting("com.example.MyPreview", showDecorations = true)
      isCustomSized = true
      configuration.updateScreenSize(600, 700)
      advanceUntilIdleIncludingEdt()
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutWidth)
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutHeight)
      verify(sceneManager, times(1)).requestRenderWithNewSize(600, 700)
    } finally {
      Disposer.dispose(sceneManager)
    }
  }

  @RunsInEdt
  @Test
  fun `listener fixes the layout size left by a dropped resize when it is attached`() = runTest {
    // A refresh built the preview XML while the preview was custom sized, but ResizePanel.applyOverrides then dropped the resize (e.g.
    // the @Preview device was changed in code), so the XML still has match_parent while the preview is no longer custom sized.
    val sceneManager = createSceneManager(showDecorations = false)
    val configuration = createConfiguration(500, 600)
    val preview = FakePreviewModel(sceneManager, layoutWidth = VALUE_MATCH_PARENT, layoutHeight = VALUE_MATCH_PARENT)

    try {
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler), isCustomSized = { false }).also {
        advanceUntilIdleIncludingEdt()
      }

      assertEquals(VALUE_WRAP_CONTENT, preview.layoutWidth)
      assertEquals(VALUE_WRAP_CONTENT, preview.layoutHeight)
      assertTrue(needsInflation.get())
      verify(sceneManager, times(1)).requestRenderWithNewSize(500, 600)
    } finally {
      Disposer.dispose(sceneManager)
    }
  }

  @RunsInEdt
  @Test
  fun `listener does not re-render when attached if the layout size is already up to date`() = runTest {
    val sceneManager = createSceneManager(showDecorations = false)
    val configuration = createConfiguration(500, 600)
    val preview = FakePreviewModel(sceneManager, layoutWidth = VALUE_MATCH_PARENT, layoutHeight = VALUE_MATCH_PARENT)

    try {
      ConfigurationResizeListener(sceneManager, configuration, StandardTestDispatcher(testScheduler), isCustomSized = { true }).also {
        advanceUntilIdleIncludingEdt()
      }

      assertEquals(VALUE_MATCH_PARENT, preview.layoutWidth)
      assertEquals(VALUE_MATCH_PARENT, preview.layoutHeight)
      assertFalse(needsInflation.get())
      verify(sceneManager, never()).requestRenderWithNewSize(any(), any())
    } finally {
      Disposer.dispose(sceneManager)
    }
  }

  /**
   * Runs the listener's coroutine to completion when it hops to `Dispatchers.EDT` midway: the EDT block runs from the IDE event queue, and
   * the rest of the coroutine then resumes on the test dispatcher.
   */
  private fun TestScope.advanceUntilIdleIncludingEdt() {
    advanceUntilIdle()
    dispatchAllEventsInIdeEventQueue()
    advanceUntilIdle()
  }

  /**
   * Mocks the [NlModel] of [sceneManager] with a preview XML file whose root tag keeps its `layout_width` and `layout_height` in
   * [layoutWidth] and [layoutHeight], and with [previewElement] as the model's preview element.
   */
  private class FakePreviewModel(
    sceneManager: LayoutlibSceneManager,
    var layoutWidth: String = VALUE_WRAP_CONTENT,
    var layoutHeight: String = VALUE_WRAP_CONTENT,
  ) {
    var previewElement: PsiComposePreviewElementInstance =
      SingleComposePreviewElementInstance.forTesting("com.example.MyPreview", configuration = PreviewConfiguration.cleanAndGet())

    init {
      val rootTag = mock<XmlTag>()
      whenever(rootTag.isValid).thenReturn(true)
      whenever(rootTag.getAttributeValue(ATTR_LAYOUT_WIDTH, ANDROID_URI)).thenAnswer { layoutWidth }
      whenever(rootTag.getAttributeValue(ATTR_LAYOUT_HEIGHT, ANDROID_URI)).thenAnswer { layoutHeight }
      whenever(rootTag.setAttribute(eq(ATTR_LAYOUT_WIDTH), eq(ANDROID_URI), any())).thenAnswer {
        layoutWidth = it.getArgument(2)
        null
      }
      whenever(rootTag.setAttribute(eq(ATTR_LAYOUT_HEIGHT), eq(ANDROID_URI), any())).thenAnswer {
        layoutHeight = it.getArgument(2)
        null
      }
      val xmlFile = mock<XmlFile>()
      whenever(xmlFile.isValid).thenReturn(true)
      whenever(xmlFile.rootTag).thenReturn(rootTag)
      val model = mock<NlModel>()
      whenever(model.file).thenReturn(xmlFile)
      whenever(model.dataProvider)
        .thenReturn(
          object : NlDataProvider(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE) {
            override fun getData(dataId: String) = previewElement.takeIf { dataId == PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE.name }
          }
        )
      whenever(sceneManager.model).thenReturn(model)
    }
  }

  // Helper function to create a Configuration
  private fun createConfiguration(width: Int, height: Int): Configuration {
    val configuration = Configuration.create(configurationSettings, FolderConfiguration.createDefault())
    configuration.setDevice(device(width, height), true)

    return configuration
  }

  private fun device(width: Int, height: Int): Device =
    Device.Builder()
      .apply {
        setTagId("")
        setName("Custom")
        setId(Configuration.CUSTOM_DEVICE_ID)
        setManufacturer("")
        addSoftware(Software())
        addState(
          State().apply {
            name = "default"
            isDefaultState = true
            orientation = ScreenOrientation.PORTRAIT
            hardware =
              Hardware().apply {
                screen =
                  Screen().apply {
                    yDimension = height
                    xDimension = width
                    pixelDensity = Density.MEDIUM
                  }
              }
          }
        )
      }
      .build()
}

/** Test implementation of [ConfigurationSettings]. */
class TestConfigurationSettingsImpl : ConfigurationSettings {

  override var defaultDevice: Device? = null
  override var locale: Locale = Locale.ANY
  override var target: IAndroidTarget? = null
  override var stateVersion: Int = 1
  override lateinit var configModule: ConfigurationModelModule
  override lateinit var resolverCache: ResourceResolverCache
  override var localesInProject: ImmutableList<Locale> = ImmutableList.of()
  override var devices: ImmutableList<Device> = ImmutableList.of()
  override var projectTarget: IAndroidTarget? = null
  override var highestApiTarget: IAndroidTarget? = null
  override var targets: Array<IAndroidTarget> = arrayOf()
  override var recentDevices: List<Device> = listOf()
  override var avdDevices: List<Device> = listOf()

  var selectDeviceCallCount = 0
  var getTargetCallCount = 0
  var createDeviceForAvdCallCount = 0
  var getDeviceByIdCallCount = 0

  var getTargetReturn: IAndroidTarget? = null
  var getDeviceByIdReturn: Device? = null
  var createDeviceForAvdReturn: Device? = null

  override fun selectDevice(device: Device) {
    defaultDevice = device
    selectDeviceCallCount++
  }

  override fun getTarget(minVersion: Int): IAndroidTarget? {
    getTargetCallCount++
    return getTargetReturn
  }

  override fun createDeviceForAvd(avd: AvdInfo): Device? {
    createDeviceForAvdCallCount++
    return createDeviceForAvdReturn
  }

  override fun getDeviceById(id: String): Device? {
    getDeviceByIdCallCount++
    return getDeviceByIdReturn
  }
}
