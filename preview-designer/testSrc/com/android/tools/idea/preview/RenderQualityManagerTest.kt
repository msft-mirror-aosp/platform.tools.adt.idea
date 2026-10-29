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
package com.android.tools.idea.preview

import com.android.ide.common.rendering.api.Result
import com.android.tools.idea.DesignSurfaceTestUtil.createZoomControllerFake
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ProjectRule
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Class used for bundling together some data used across the tests in [RenderQualityManagerTest]. This class is conceptually acting as a
 * "preview tool" using a RenderQualityManager.
 */
private class TestPreviewTool {
  var errorMargin: Float = 0.1f
  var targetQuality: Float = 1f
  var currentQuality: Float = 1f
  var screenScale: Float = 0f
  var qualityChangeNeededLatch = CountDownLatch(0)
  val debounceTime: Long = 500

  fun qualityChangeMightBeNeeded() {
    assertTrue(qualityChangeNeededLatch.count > 0, "Unexpected qualityChangeMightBeNeeded")
    qualityChangeNeededLatch.countDown()
  }
}

private class TestRenderQualityPolicy(private val tool: TestPreviewTool) : RenderQualityPolicy {
  override val acceptedErrorMargin: Float
    get() = tool.errorMargin

  // Note that the debounce time cannot change as it is only used in the initialization
  // of a DefaultRenderQualityManager
  override val debounceTimeMillis: Long = tool.debounceTime

  override fun getTargetQuality(scale: Double, isVisible: Boolean): Float {
    return tool.targetQuality
  }

  override fun getTargetScreenScale(scale: Double, targetQuality: Float): Float {
    return tool.screenScale
  }
}

class RenderQualityManagerTest {
  @JvmField @Rule val projectRule = ProjectRule()

  private lateinit var tool: TestPreviewTool
  private lateinit var qualityManager: DefaultRenderQualityManager

  private lateinit var sceneViewMock: SceneView
  private val sceneViewRectangle = Rectangle(Point(10, 10), Dimension(10, 10))
  private lateinit var sceneManagerMock: LayoutlibSceneManager

  private lateinit var surfaceMock: NlDesignSurface
  private val panningChanged = MutableSharedFlow<Unit>()
  private val zoomChanged = MutableSharedFlow<Unit>()
  private var scrollRectangle: Rectangle? = null

  @Before
  fun setUp() {
    tool = TestPreviewTool()

    sceneViewMock = mock<SceneView>()
    sceneManagerMock = mock<LayoutlibSceneManager>()
    whenever(sceneManagerMock.sceneViews).then {
      return@then listOf(sceneViewMock)
    }
    whenever(sceneManagerMock.lastRenderQuality).then {
      return@then tool.currentQuality
    }

    surfaceMock = mock<NlDesignSurface>()
    whenever(surfaceMock.panningChanged).thenReturn(panningChanged)
    whenever(surfaceMock.modelChanged).thenReturn(MutableSharedFlow())
    whenever(surfaceMock.zoomChanged).thenReturn(zoomChanged)

    whenever(surfaceMock.findSceneViewRectangles()).then {
      return@then mapOf(Pair(sceneViewMock, sceneViewRectangle))
    }
    whenever(surfaceMock.currentScrollRectangle).then {
      return@then scrollRectangle
    }
    whenever(surfaceMock.zoomController).thenReturn(createZoomControllerFake(1.0))

    Disposer.register(projectRule.disposable, surfaceMock)
    Disposer.register(projectRule.disposable, sceneManagerMock)

    qualityManager = DefaultRenderQualityManager(surfaceMock, TestRenderQualityPolicy(tool)) { tool.qualityChangeMightBeNeeded() }
  }

  @Test
  fun testDebounceTime() = runBlocking {
    // Only 1 change of quality should be triggered when many changes happen but the
    // debounce time is larger than the time passed between those changes.
    tool.qualityChangeNeededLatch = CountDownLatch(1)
    repeat(20) {
      // Alternate between both notifications as they should have the same effect
      if (it % 2 == 0) zoomChanged.emit(Unit) else panningChanged.emit(Unit)
      delay(tool.debounceTime / 5)
    }
    tool.qualityChangeNeededLatch.await()

    // Every change should trigger a change of quality when the time between them is
    // larger than the debounce time.
    tool.qualityChangeNeededLatch = CountDownLatch(2)
    repeat(2) {
      // Alternate between both notifications as they should have the same effect
      if (it % 2 == 0) zoomChanged.emit(Unit) else panningChanged.emit(Unit)
      delay(tool.debounceTime * 5)
    }

    tool.qualityChangeNeededLatch.await()
  }

  @Test
  fun testErrorMargin() = runBlocking {
    tool.errorMargin = 0.2f
    tool.currentQuality = 0.5f

    tool.targetQuality = 0.75f
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))

    tool.targetQuality = 0.25f
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))

    tool.targetQuality = 0.65f
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))

    tool.targetQuality = 0.35f
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))
  }

  @Test
  fun testCancelledExceptionTriggersQualityChange() = runBlocking {
    tool.errorMargin = 0.2f
    tool.currentQuality = 0.5f

    tool.targetQuality = 0.75f
    val renderCancelledResult = mock<RenderResult>()
    val mockResult = mock<Result>()
    whenever(mockResult.isSuccess).thenReturn(false)
    whenever(mockResult.exception).thenReturn(CancellationException())
    whenever(renderCancelledResult.renderResult).thenReturn(mockResult)
    whenever(sceneManagerMock.renderResult).thenReturn(renderCancelledResult)
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))
  }

  @Test
  fun testCancelledExceptionTriggersQualityChange_EvenForSameQuality() = runBlocking {
    tool.errorMargin = 0.2f
    tool.currentQuality = 0.5f

    tool.targetQuality = 0.5f
    val renderCancelledResult = mock<RenderResult>()
    val mockResult = mock<Result>()
    whenever(mockResult.isSuccess).thenReturn(false)
    whenever(mockResult.exception).thenReturn(CancellationException())
    whenever(renderCancelledResult.renderResult).thenReturn(mockResult)
    whenever(sceneManagerMock.renderResult).thenReturn(renderCancelledResult)
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))
  }

  @Test
  fun testErrorDoesNotTriggerQualityChange() = runBlocking {
    tool.errorMargin = 0.2f
    tool.currentQuality = 0.5f

    tool.targetQuality = 0.75f
    val renderCancelledResult = mock<RenderResult>()
    val mockResult = mock<Result>()
    whenever(mockResult.isSuccess).thenReturn(false)
    whenever(mockResult.exception).thenReturn(IllegalStateException())
    whenever(renderCancelledResult.renderResult).thenReturn(mockResult)
    whenever(sceneManagerMock.renderResult).thenReturn(renderCancelledResult)
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))
  }

  // Regression test for b/336947005
  @Test
  fun testSceneViewChangeIsDetected() = runBlocking {
    qualityManager.needsQualityChange(sceneManagerMock)
    assertTrue(qualityManager.sceneViewRectanglesContainsForTest(sceneViewMock))

    sceneViewMock = mock<SceneView>()
    assertFalse(qualityManager.sceneViewRectanglesContainsForTest(sceneViewMock))

    // when checking quality targets, the new sceneView should be detected and loaded into the map
    qualityManager.needsQualityChange(sceneManagerMock)
    assertTrue(qualityManager.sceneViewRectanglesContainsForTest(sceneViewMock))
  }

  // Regression test for b/442315045
  @Test
  fun testTargetScaleChangeTriggersQualityChange() {
    tool.errorMargin = 0.05f
    tool.currentQuality = 0.95f
    tool.targetQuality = 0.95f
    // mdpi device
    whenever(sceneManagerMock.sceneScalingFactor).thenReturn(1f)

    whenever(sceneManagerMock.lastRenderScale).thenReturn(1f)
    tool.screenScale = 4f
    assertEquals(4f, qualityManager.getTargetScale(sceneManagerMock))
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))
    // Upscaled renders are refreshed after the ones whose quality decreases
    assertTrue(qualityManager.compareTargetToLastRender(sceneManagerMock) > 0)

    // Small zoom jitter within acceptedErrorMargin does not trigger re-render
    whenever(sceneManagerMock.lastRenderScale).thenReturn(4f)
    tool.screenScale = 4.1f
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))

    // Zooming out keeps the sharper render until it is more than twice as large as needed
    tool.screenScale = 2.5f
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))
    tool.screenScale = 1.9f
    assertTrue(qualityManager.needsQualityChange(sceneManagerMock))

    // Large previews already capped by ConstrainedRenderSizeProvider.MAX_UPSCALED_IMAGE_SIZE do not re-render when zooming in further
    val resultMock = mock<RenderResult>()
    val okStatus = mock<Result>()
    val loggerMock = mock<RenderLogger>()
    whenever(okStatus.isSuccess).thenReturn(true)
    whenever(resultMock.renderResult).thenReturn(okStatus)
    whenever(resultMock.logger).thenReturn(loggerMock)
    // 2000 x 2000 = 4M px ~= MAX_UPSCALED_IMAGE_SIZE -> max target scale at 0.95 quality ~= 1.03f
    whenever(resultMock.rootViewDimensions).thenReturn(Dimension(2000, 2000))
    whenever(sceneManagerMock.renderResult).thenReturn(resultMock)
    whenever(sceneManagerMock.lastRenderScale).thenReturn(3f)
    tool.screenScale = 6f
    assertFalse(qualityManager.needsQualityChange(sceneManagerMock))
  }

  @Test
  fun testTargetScaleFollowsDeviceDensity() {
    tool.screenScale = 4f
    // The same screen scale needs less upscaling on denser devices, e.g. after switching device in place from the ResizePanel
    whenever(sceneManagerMock.sceneScalingFactor).thenReturn(1f) // mdpi
    assertEquals(4f, qualityManager.getTargetScale(sceneManagerMock), 0.001f)
    whenever(sceneManagerMock.sceneScalingFactor).thenReturn(2.625f) // 420dpi
    assertEquals(4f / 2.625f, qualityManager.getTargetScale(sceneManagerMock), 0.001f)
    whenever(sceneManagerMock.sceneScalingFactor).thenReturn(4f) // xxxhdpi: no upscaling
    assertEquals(1f, qualityManager.getTargetScale(sceneManagerMock), 0.001f)
    // A screen scale of 0f disables upscaling
    tool.screenScale = 0f
    whenever(sceneManagerMock.sceneScalingFactor).thenReturn(1f)
    assertEquals(1f, qualityManager.getTargetScale(sceneManagerMock), 0.001f)
  }

  @Test
  fun testDefaultPolicyTargetScreenScale() {
    var upscalingEnabled = true
    val policy = DefaultRenderQualityPolicy(screenScalingFactorProvider = { 2.0 }, isUpscalingEnabled = { upscalingEnabled })

    // 150% zoom on a 2x HiDPI screen: 3 physical pixels per dp
    assertEquals(3f, policy.getTargetScreenScale(scale = 1.5, targetQuality = 0.95f), 0.001f)
    // Offscreen or deactivated previews at lowestQuality are not upscaled
    assertEquals(0f, policy.getTargetScreenScale(scale = 1.5, targetQuality = DefaultRenderQualityPolicy.lowestQuality))
    // Upscaling can be disabled, e.g. in interactive or animation preview
    upscalingEnabled = false
    assertEquals(0f, policy.getTargetScreenScale(scale = 1.5, targetQuality = 0.95f))
  }
}
