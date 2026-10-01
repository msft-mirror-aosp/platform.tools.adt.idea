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
package com.android.tools.idea.compose.preview.interactive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.preview.BackNavigationEdge
import com.android.tools.idea.compose.preview.InteractivePreviewNavigationController
import com.android.tools.idea.compose.preview.TestComposeViewAdapterViewObj
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.preview.analytics.InteractivePreviewUsageTracker
import com.android.tools.idea.testing.flags.overrideForTest
import com.intellij.testFramework.ProjectRule
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationControlsPanelUiTest {
  @get:Rule val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun testBottomNavigationContentUi() {
    val canBackPressMutable = mutableStateOf(true)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    var backPressCallCount = 0
    var backPressProgressCallCount = 0
    var backPressTrackProgressCallCount = 0
    var edgeDropdownPressCallCount = 0

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        canBackPress = { canBackPressMutable.value },
        onBackPress = { backPressCallCount++ },
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> backPressProgressCallCount++ },
        onBackPressTrackProgress = { backPressTrackProgressCallCount++ },
        onEdgeDropdownPress = { edgeDropdownPressCallCount++ },
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify main panel is displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.panel).assertIsDisplayed()

    // Emulate the update from the fps counter
    fpsUpdater.tryEmit(Unit)

    // Verify Back button triggers onBackPress
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).assertIsDisplayed().performClick()
    composeTestRule.mainClock.advanceTimeBy(100L)
    composeTestRule.waitForIdle()
    assertEquals(1, backPressCallCount)

    // Verify Dropdown selection triggers onEdgeDropdownPress
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.edgeDropdown).assertIsDisplayed().assertIsEnabled().performClick()
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed().performClick()
    assertEquals(1, edgeDropdownPressCallCount)
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed()

    // Verify divider is displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.divider).assertIsDisplayed()

    // Verify Progress slider triggers start, progress and track callbacks
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.progressSlider).assertIsDisplayed().assertIsEnabled().performTouchInput {
      swipeRight()
    }
    assertTrue("Progress callback should be called", backPressProgressCallCount > 0)
    assertEquals(1, backPressTrackProgressCallCount)

    // Verify behavior when back navigation is unavailable
    canBackPressMutable.value = false
    fpsUpdater.tryEmit(Unit)

    // Verify button and slider are disabled
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).assertIsDisplayed().assertIsNotEnabled()
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.progressSlider).assertIsDisplayed().assertIsNotEnabled()

    // Verify tooltip appears when back button is disabled
    composeTestRule.onNodeWithText(message("action.navigate.back.button.disabled.tooltip")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).performMouseInput { moveTo(center) }
    composeTestRule.mainClock.advanceTimeBy(1201L) // org.jetbrains.jewel.ui.component.styling.TooltipMetrics delay is 1200ms
    composeTestRule.onNodeWithText(message("action.navigate.back.button.disabled.tooltip")).assertIsDisplayed()

    // Verify clicking disabled button does not increment counter
    val countBeforeClick = backPressCallCount
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).performClick()
    assertEquals("Callback should not be triggered when button is disabled", countBeforeClick, backPressCallCount)
  }

  @Test
  fun testBackButtonTriggersStartDelayAndCompleted() {
    var backPressStartCalledWithEdge: BackNavigationEdge? = null
    var backPressCalled = false

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        canBackPress = { true },
        onBackPress = { backPressCalled = true },
        onBackPressStart = { backPressStartCalledWithEdge = it },
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = MutableSharedFlow(),
      )
    }

    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).performClick()

    // Immediately after click, onBackPressStart should be called, but onBackPress should wait for delay
    assertEquals(BackNavigationEdge.EDGE_NONE, backPressStartCalledWithEdge)

    // Advance clock past the delay (100ms)
    composeTestRule.mainClock.advanceTimeBy(100L)
    composeTestRule.waitForIdle()

    assertTrue("onBackPress should be called after delay", backPressCalled)
  }

  @Test
  fun testProgressSliderResetsOnBackPressCompletedFlow() {
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val backPressCompletedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
        backPressCompletedFlow = backPressCompletedFlow,
      )
    }

    // Verify initially the slider is at 0f (label should display 0.0)
    composeTestRule.onNodeWithText(message("action.navigate.back.predictive.back.progress", 0.0f)).assertIsDisplayed()

    // Drag the slider to update the value
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.progressSlider).assertIsDisplayed().performTouchInput { swipeRight() }

    // Since swipeRight drags the slider, the label should be updated to a positive float value (e.g., 1.0f or something similar)
    // We can verify that it is NOT at 0.0f anymore
    composeTestRule.onNodeWithText(message("action.navigate.back.predictive.back.progress", 0.0f)).assertDoesNotExist()

    // Trigger back press completion from the flow
    backPressCompletedFlow.tryEmit(Unit)
    composeTestRule.waitForIdle()

    // Verify the slider resets back to 0.0f
    composeTestRule.onNodeWithText(message("action.navigate.back.predictive.back.progress", 0.0f)).assertIsDisplayed()
  }

  @Test
  fun testNavigationControlsContentEdgeDropdownPressCancelsBackPress() {
    var edgeDropdownPressTracked = false
    var cancelledCalled = false

    val tracker =
      object : InteractivePreviewUsageTracker {
        override fun logInteractiveSession(fps: Int, durationMs: Int, userInteractions: Int) {}

        override fun logStartupTime(timeMs: Int, peers: Int) {}

        override fun trackNavigationPanelProgressPress() {}

        override fun trackNavigationPanelBackPress() {}

        override fun trackNavigationPanelVisibilityChange(isShown: Boolean) {}

        override fun trackNavigationPanelEdgeDropdownPress() {
          edgeDropdownPressTracked = true
        }

        override fun trackNavigateButtonClick() {}
      }

    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressCancelledCallback = { cancelledCalled = true })

    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val controller =
      InteractivePreviewNavigationController(usageTrackerProvider = { tracker }, fpsUpdater = fpsUpdater).apply {
        updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
      }

    composeTestRule.setContent {
      NavigationControlsContent(
        interactivePreviewNavigationController = controller,
        fpsUpdater = fpsUpdater,
        isEdgeNavigationImplemented = { true },
      )
    }

    // Verify initially dropdown shows "None"
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_NONE.visibleName).assertIsDisplayed()

    // Click on the edge dropdown to select "Right"
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.edgeDropdown).assertIsDisplayed().performClick()
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed().performClick()

    // Verify dropdown has updated to "Right"
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed()

    // Assert that both edge tracking and back press cancellation are triggered correctly
    assertTrue("Edge dropdown press tracking should be triggered", edgeDropdownPressTracked)
    assertTrue("Back press cancellation should be triggered", cancelledCalled)
  }

  @Test
  fun testEdgeDropdownDisabledWhenShowEdgeNavigationIsFalse() {
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { false },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify main panel is displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.panel).assertIsDisplayed()

    // Verify divider is displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.divider).assertIsDisplayed()
    // Verify Back button is displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).assertIsDisplayed()
    // Verify Dropdown is displayed but disabled
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.edgeDropdown).assertIsDisplayed().assertIsNotEnabled()

    // Verify tooltip appears when edge dropdown is disabled
    composeTestRule.onNodeWithText(message("action.navigate.back.navigation.edge.disabled.tooltip")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.edgeDropdown).performMouseInput { moveTo(center) }
    composeTestRule.mainClock.advanceTimeBy(1201L) // org.jetbrains.jewel.ui.component.styling.TooltipMetrics delay is 1200ms
    composeTestRule.onNodeWithText(message("action.navigate.back.navigation.edge.disabled.tooltip")).assertIsDisplayed()
  }

  @Test
  fun testVisualStackRendering() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    var historyList = emptyList<String>()

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify visual stack displays the empty state message when navigation history is empty
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertIsDisplayed()
    composeTestRule.onNodeWithText(message("action.navigate.back.stack.empty")).assertIsDisplayed()

    // Now, let's set a navigation history with elements and recompose
    historyList = listOf("Preview A", "Preview B")
    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify visual stack is now displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertIsDisplayed()
    composeTestRule.onNodeWithText("Preview A", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Preview B", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText(message("action.navigate.back.stack.empty")).assertDoesNotExist()
  }

  @Test
  fun testVisualStackRenderingWithNavigationHistory() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val historyList = listOf("ScreenX", "ScreenY", "ScreenZ")

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify visual stack displays the dynamic navigation history screen names
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenX", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenY", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenZ", substring = true).assertIsDisplayed()
  }

  @Test
  fun testVisualStackRenderingWithMultiScreenEntry() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // A non-data class inherits Object.toString() ("...Home@hex"), verifying reflection extracts the simple class name.
    class Home
    data class Product(val id: Int)
    val historyList = listOf(listOf(Home(), Product(id = 1)))

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertIsDisplayed()
    composeTestRule.onNodeWithText("Home").assertIsDisplayed()
    composeTestRule.onNodeWithText("Product(id=1)").assertIsDisplayed()
  }

  @Test
  fun testVisualStackRenderingWithNavigationInfoObjects() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    class FakeNavEntry(@Suppress("unused") private val key: Any)
    class FakeScene(@Suppress("unused") val entries: List<FakeNavEntry>)
    open class FakeSceneInfo(private val backingScene: FakeScene) {
      var sceneReadCount = 0
      @Suppress("unused")
      val scene: FakeScene
        get() {
          sceneReadCount++
          return backingScene
        }
    }

    val firstSceneInfo = FakeSceneInfo(FakeScene(listOf(FakeNavEntry("ScreenReflected1"))))
    val secondSceneInfo = FakeSceneInfo(FakeScene(listOf(FakeNavEntry("ScreenA"), FakeNavEntry("ScreenB"))))
    val thirdSceneInfo = FakeSceneInfo(FakeScene(listOf(FakeNavEntry("ScreenReflected3"))))
    var historyList = listOf(firstSceneInfo, secondSceneInfo)

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenReflected1").assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenA").assertIsDisplayed()
    composeTestRule.onNodeWithText("ScreenB").assertIsDisplayed()
    assertEquals(1, firstSceneInfo.sceneReadCount)
    assertEquals(1, secondSceneInfo.sceneReadCount)

    // Push a third item and trigger recomposition via fpsUpdater; existing items must not be re-parsed via reflection.
    historyList = listOf(firstSceneInfo, secondSceneInfo, thirdSceneInfo)
    fpsUpdater.tryEmit(Unit)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("ScreenReflected3").assertIsDisplayed()
    assertEquals(1, firstSceneInfo.sceneReadCount)
    assertEquals(1, secondSceneInfo.sceneReadCount)
    assertEquals(1, thirdSceneInfo.sceneReadCount)
  }

  @Test
  fun testVisualStackItemsAreReadOnlyAndShowCurrentBadge() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(
      true,
      projectRule.project,
    )
    val fpsUpdater =
      MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )
    val historyList = listOf("Screen1", "Screen2")

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    composeTestRule.onNodeWithText(message("action.navigate.back.stack.current")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Screen2").assertIsDisplayed().assertHasNoClickAction()
    composeTestRule.onNodeWithText("Screen1").assertIsDisplayed().assertHasNoClickAction()
  }

  @Test
  fun testVisualStackNotRenderedWhenFlagDisabled() {
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(false, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val historyList = listOf("ScreenX", "ScreenY", "ScreenZ")

    composeTestRule.setContent {
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify main panel and navigation controls are displayed
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.panel).assertIsDisplayed()
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.backButton).assertIsDisplayed()

    // Verify visual stack is NOT displayed when flag is disabled
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.visualStack).assertDoesNotExist()
  }

  @Test
  fun testNavigationControlsContentSandboxesParse() {
    // Enable the visual back stack feature flag for the test
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    var isRenderSessionReady = false
    var renderSessionActionCount = 0

    val tracker =
      object : InteractivePreviewUsageTracker {
        override fun logInteractiveSession(fps: Int, durationMs: Int, userInteractions: Int) {}

        override fun logStartupTime(timeMs: Int, peers: Int) {}

        override fun trackNavigationPanelProgressPress() {}

        override fun trackNavigationPanelBackPress() {}

        override fun trackNavigationPanelVisibilityChange(isShown: Boolean) {}

        override fun trackNavigationPanelEdgeDropdownPress() {}

        override fun trackNavigateButtonClick() {}
      }

    // Set up a fake adapter with two items in history
    val composeViewAdapterObjFake =
      TestComposeViewAdapterViewObj(
        canBackPress = true,
        history = listOf("Screen0", "Screen1"),
      )

    val controller =
      InteractivePreviewNavigationController(
          usageTrackerProvider = { tracker },
          fpsUpdater = fpsUpdater,
          executeInRenderSessionAsync = { runnable ->
            if (isRenderSessionReady) {
              renderSessionActionCount++
              runnable.run()
            }
          },
        )
        .apply { updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = true) }

    composeTestRule.setContent {
      NavigationControlsContent(
        interactivePreviewNavigationController = controller,
        fpsUpdater = fpsUpdater,
        isEdgeNavigationImplemented = { true },
      )
    }

    // When the initial render session execution is dropped, the next fpsUpdater tick retries and parses both items
    composeTestRule.waitForIdle()
    assertEquals(0, renderSessionActionCount)
    isRenderSessionReady = true
    fpsUpdater.tryEmit(Unit)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Screen0").assertIsDisplayed()
    composeTestRule.onNodeWithText("Screen1").assertIsDisplayed()
    assertEquals(2, renderSessionActionCount)

    // Subsequent fpsUpdater ticks should reuse cached parsed items without dispatching to the render session
    fpsUpdater.tryEmit(Unit)
    composeTestRule.waitForIdle()
    assertEquals(2, renderSessionActionCount)
  }

  @Test
  fun testStateRestorationPreservesSliderPositionAndSelectedEdge() {
    var currentSaveableStateRegistry = SaveableStateRegistry(emptyMap()) { true }
    val isPanelVisible = mutableStateOf(true)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    composeTestRule.setContent {
      if (isPanelVisible.value) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides currentSaveableStateRegistry) {
          NavigationControlsPanel(
            isEdgeNavigationImplemented = { true },
            canBackPress = { true },
            onBackPress = {},
            onBackPressStart = {},
            onBackPressProgress = { _, _ -> },
            onBackPressTrackProgress = {},
            onEdgeDropdownPress = {},
            fpsUpdater = fpsUpdater,
          )
        }
      }
    }

    // Change the edge to EDGE_RIGHT
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.edgeDropdown).assertIsDisplayed().performClick()
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed().performClick()
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed()

    // Drag the slider to change slider position away from initial 0.0
    composeTestRule.onNodeWithTag(NavigationControlsPanelTestTags.progressSlider).assertIsDisplayed().performTouchInput { swipeRight() }
    composeTestRule.onNodeWithText(message("action.navigate.back.predictive.back.progress", 0.0f)).assertDoesNotExist()

    // Save the state from the current registry and simulate unmounting/re-mounting
    val savedState = currentSaveableStateRegistry.performSave()
    isPanelVisible.value = false
    composeTestRule.waitForIdle()

    // Restore state using the saved registry values
    currentSaveableStateRegistry = SaveableStateRegistry(savedState) { true }
    isPanelVisible.value = true
    composeTestRule.waitForIdle()

    // Verify selected edge is still EDGE_RIGHT after restoration
    composeTestRule.onNodeWithText(BackNavigationEdge.EDGE_RIGHT.visibleName).assertIsDisplayed()

    // Verify slider position remains preserved (not reset to 0.0f)
    composeTestRule.onNodeWithText(message("action.navigate.back.predictive.back.progress", 0.0f)).assertDoesNotExist()
  }

  @Test
  fun testVisualStackCurrentItemTextUsesAccessibleContrastColor() {
    // Enable the visual back stack feature flag and capture the active Jewel theme colors
    StudioFlags.COMPOSE_INTERACTIVE_PREVIEW_PREDICTIVE_BACK_STACK_VISUAL.overrideForTest(true, projectRule.project)
    val fpsUpdater = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val historyList = listOf("Screen1", "Screen2")
    var expectedNormalTextColor = Color.Unspecified
    var focusedOutlineAccentColor = Color.Unspecified
    var panelBackgroundColor = Color.Unspecified

    composeTestRule.setContent {
      expectedNormalTextColor = JewelTheme.globalColors.text.normal
      focusedOutlineAccentColor = JewelTheme.globalColors.outlines.focused
      panelBackgroundColor = JewelTheme.globalColors.panelBackground
      NavigationControlsPanel(
        isEdgeNavigationImplemented = { true },
        getNavigationHistory = { historyList },
        canBackPress = { true },
        onBackPress = {},
        onBackPressStart = {},
        onBackPressProgress = { _, _ -> },
        onBackPressTrackProgress = {},
        onEdgeDropdownPress = {},
        fpsUpdater = fpsUpdater,
      )
    }

    // Verify the active card route text, the "Current" badge text, and the inactive card route text all use text.normal
    val currentRouteTextColor = retrieveNodeTextColor("Screen2")
    val currentBadgeTextColor = retrieveNodeTextColor(message("action.navigate.back.stack.current"))
    val previousRouteTextColor = retrieveNodeTextColor("Screen1")

    assertEquals(expectedNormalTextColor, currentRouteTextColor)
    assertEquals(expectedNormalTextColor, currentBadgeTextColor)
    assertEquals(expectedNormalTextColor, previousRouteTextColor)
    assertNotEquals(focusedOutlineAccentColor, currentRouteTextColor)
    assertNotEquals(focusedOutlineAccentColor, currentBadgeTextColor)

    // Verify WCAG 1.4.3 / GAR 2026 minimum 4.5:1 contrast ratio against both the 12% tinted card and 20% tinted badge backgrounds
    val activeCardBackgroundColor = focusedOutlineAccentColor.copy(alpha = 0.12f).compositeOver(panelBackgroundColor)
    val currentBadgeBackgroundColor = focusedOutlineAccentColor.copy(alpha = 0.2f).compositeOver(activeCardBackgroundColor)
    val routeTextContrastRatio = calculateContrastRatio(currentRouteTextColor, activeCardBackgroundColor)
    val currentBadgeContrastRatio = calculateContrastRatio(currentBadgeTextColor, currentBadgeBackgroundColor)

    assertTrue(
      "Expected active route text contrast >= 4.5:1 against card background, but was $routeTextContrastRatio",
      routeTextContrastRatio >= 4.5f,
    )
    assertTrue(
      "Expected 'Current' badge text contrast >= 4.5:1 against badge background, but was $currentBadgeContrastRatio",
      currentBadgeContrastRatio >= 4.5f,
    )
  }

  private fun retrieveNodeTextColor(nodeText: String): Color {
    // Query the unmerged semantics tree so each Text node inside the clickable Row exposes its own TextLayoutResult
    val textLayoutResults = mutableListOf<TextLayoutResult>()
    val semanticsNode = composeTestRule.onNodeWithText(nodeText, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
    semanticsNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(textLayoutResults)
    return textLayoutResults.single().layoutInput.style.color
  }

  private fun calculateContrastRatio(foregroundColor: Color, backgroundColor: Color): Float {
    // Compute WCAG 2.x relative luminance contrast ratio between two opaque colors
    val foregroundLuminance = foregroundColor.luminance()
    val backgroundLuminance = backgroundColor.luminance()
    val lighterLuminance = max(foregroundLuminance, backgroundLuminance)
    val darkerLuminance = min(foregroundLuminance, backgroundLuminance)
    return (lighterLuminance + 0.05f) / (darkerLuminance + 0.05f)
  }
}
