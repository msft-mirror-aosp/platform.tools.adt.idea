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

import com.android.tools.idea.compose.preview.interactive.NavigationItemInfo
import com.android.tools.idea.preview.analytics.InteractiveNopTracker
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test

class InteractivePreviewNavigationControllerTest {

  @get:Rule val projectRule = ProjectRule()

  private fun createController(
    onAfterPanelUpdate: () -> Unit = {},
    executeInRenderSessionAsync: (Runnable) -> Unit = { it.run() },
  ) =
    InteractivePreviewNavigationController(
        usageTrackerProvider = { InteractiveNopTracker() },
        onAfterPanelUpdate = onAfterPanelUpdate,
        fpsUpdater = MutableSharedFlow(),
        executeInRenderSessionAsync = executeInRenderSessionAsync,
      )
      .apply {
        setIsTrustedNavigationEventDispatcherOwnerForTestOnly { it is NavigationEventDispatcherOwner }
      }

  @Test
  fun testBackPressCompletedFromViewAdapterObj() {
    var backPress = false
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressCompletedCallback = { backPress = true })
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressCompleted()
    assertThat(backPress).isTrue()
  }

  @Test
  fun testBackPressStartedFromViewAdapterObj() {
    var startedEdge: String? = null
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressStartedCallback = { startedEdge = it })
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressStart(BackNavigationEdge.EDGE_LEFT)
    assertThat(startedEdge).isEqualTo(BackNavigationEdge.EDGE_LEFT.name)
  }

  @Test
  fun testBackPressProgressFromViewAdapterObj() {
    var progressValue = -1f
    var progressEdge: String? = null
    val composeViewAdapterObjFake =
      TestComposeViewAdapterViewObj(
        onBackPressProgressCallback = { progress, edge ->
          progressValue = progress
          progressEdge = edge
        }
      )
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressProgress(0.5f, BackNavigationEdge.EDGE_RIGHT)
    assertThat(progressValue).isEqualTo(0.5f)
    assertThat(progressEdge).isEqualTo(BackNavigationEdge.EDGE_RIGHT.name)
  }

  @Test
  fun testBackPressCancelledFromViewAdapterObj() {
    var cancelled = false
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressCancelledCallback = { cancelled = true })
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressCancelled()
    assertThat(cancelled).isTrue()
  }

  @Test
  fun testCanBackPressFromViewAdapterObj() {
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = true)
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    assertThat(controller.canBackPress()).isTrue()
  }

  @Test
  fun testBackPressCompletedFromLocalNavigationDispatcher() {
    var backPress = false
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = false)
    val localNavigationEventDispatcherObj =
      TestNavigationEventDispatcherObj(canBackPress = true, onBackPressCompletedCallback = { backPress = true })
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressCompleted()
    assertThat(backPress).isTrue()
  }

  @Test
  fun testBackPressStartedFromLocalNavigationDispatcher() {
    var startedEdge: String? = null
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = false)
    val localNavigationEventDispatcherObj =
      TestNavigationEventDispatcherObj(canBackPress = true, onBackPressStartedCallback = { startedEdge = it })
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressStart(BackNavigationEdge.EDGE_LEFT)
    assertThat(controller.canBackPress()).isTrue()
    assertThat(startedEdge).isEqualTo(BackNavigationEdge.EDGE_LEFT.name)
  }

  @Test
  fun testBackPressCancelledFromLocalNavigationDispatcher() {
    var cancelled = false
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = false)
    val localNavigationEventDispatcherObj =
      TestNavigationEventDispatcherObj(canBackPress = true, onBackPressCancelledCallback = { cancelled = true })
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressCancelled()
    assertThat(cancelled).isTrue()
  }

  @Test
  fun testCanBackPressFromLocalNavigationDispatcher() {
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = false)
    val localNavigationEventDispatcherObj = TestNavigationEventDispatcherObj(canBackPress = true)
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, composeViewAdapterObjFake, hasNavDisplay = false)
    assertThat(controller.canBackPress()).isTrue()
  }

  @Test
  fun testBackPressFromLocalNavigationDispatcher() {
    var progressValue = -1f
    var progressEdge: String? = null

    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(canBackPress = false)
    val localNavigationEventDispatcherObj =
      TestNavigationEventDispatcherObj(
        canBackPress = true,
        onBackPressProgressCallback = { progress, edge ->
          progressValue = progress
          progressEdge = edge
        },
      )
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, composeViewAdapterObjFake, hasNavDisplay = false)
    controller.backPressProgress(0.5f, BackNavigationEdge.EDGE_RIGHT)
    assertThat(controller.canBackPress()).isTrue()
    assertThat(progressValue).isEqualTo(0.5f)
    assertThat(progressEdge).isEqualTo(BackNavigationEdge.EDGE_RIGHT.name)
  }

  @Test
  fun testCanPerformBackNavigation() {
    val controller = createController()
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressCompletedCallback = {})
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    assertThat(controller.canPerformBackNavigation()).isTrue()
  }

  @Test
  fun testCanShowNavigationPanel_hasNavDisplayTrue() {
    val controller = createController()
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressProgressCallback = { _, _ -> })
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = true)
    assertThat(controller.canShowNavigationPanel()).isTrue()
  }

  @Test
  fun testCanShowNavigationPanel_hasNavDisplayFalse() {
    val controller = createController()
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj(onBackPressProgressCallback = { _, _ -> })
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)
    assertThat(controller.canShowNavigationPanel()).isFalse()
  }

  @Test
  fun testShowAndHideNavigationControls() {
    var panelUpdated = false
    val controller = createController(onAfterPanelUpdate = { panelUpdated = true })
    val instance =
      SingleComposePreviewElementInstance(
        "composableMethodName",
        PreviewDisplaySettings(
          name = "A name",
          baseName = "A base name",
          parameterName = null,
          group = "group1",
          showDecoration = true,
          background = PreviewDisplaySettings.Background.Color("#000"),
          organizationName = "organizationName",
          organizationGroup = "organizationGroup",
        ),
        null,
        null,
        PreviewConfiguration.cleanAndGet(),
      )

    assertThat(controller.isNavigationControlsShown()).isFalse()
    assertThat(controller.getBottomPanelComponent()).isNull()

    runInEdtAndWait { controller.showNavigationControls(instance) }
    assertThat(controller.isNavigationControlsShown()).isTrue()
    assertThat(controller.getBottomPanelComponent()).isNotNull()
    assertThat(panelUpdated).isTrue()

    panelUpdated = false
    runInEdtAndWait { controller.hideNavigationControls() }
    assertThat(controller.isNavigationControlsShown()).isFalse()
    assertThat(controller.getBottomPanelComponent()).isNull()
    assertThat(panelUpdated).isTrue()
  }

  @Test
  fun testBackNavigationEdgeVisibleNames() {
    assertThat(BackNavigationEdge.EDGE_LEFT.visibleName).isEqualTo("Swipe Left")
    assertThat(BackNavigationEdge.EDGE_RIGHT.visibleName).isEqualTo("Swipe Right")
    assertThat(BackNavigationEdge.EDGE_NONE.visibleName).isEqualTo("None")
  }

  @Test
  fun testGetNavigationHistoryFromDispatcherOwner() {
    val localNavigationEventDispatcherObj = TestNavigationEventDispatcherObj(canBackPress = true, history = listOf("Screen1", "Screen2"))
    val controller = createController()
    controller.updateObjects(localNavigationEventDispatcherObj, TestComposeViewAdapterViewObj(), hasNavDisplay = false)
    assertThat(controller.getNavigationHistory()).containsExactly("Screen1", "Screen2").inOrder()
  }

  @Test
  fun testGetNavigationHistoryFromComposeViewAdapterObj() {
    val composeViewAdapterObj = TestComposeViewAdapterViewObj(history = listOf("ScreenA", "ScreenB"))
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObj, hasNavDisplay = false)
    assertThat(controller.getNavigationHistory()).containsExactly("ScreenA", "ScreenB").inOrder()
  }

  @Test
  fun testActionsDispatchedViaExecuteInRenderSessionAsync() {
    var renderSessionActionCount = 0
    val controller =
      createController(
        executeInRenderSessionAsync = { runnable ->
          renderSessionActionCount++
          runnable.run()
        }
      )
    val composeViewAdapterObj = TestComposeViewAdapterViewObj(canBackPress = true, history = listOf("State1", "State0"))
    controller.updateObjects(null, composeViewAdapterObj, hasNavDisplay = false)
    assertThat(controller.canBackPress()).isTrue()
    assertThat(controller.getNavigationHistory()).containsExactly("State1", "State0").inOrder()

    controller.backPressStart(BackNavigationEdge.EDGE_LEFT)
    assertThat(renderSessionActionCount).isEqualTo(1)

    controller.backPressCompleted()
    assertThat(renderSessionActionCount).isEqualTo(2)

    controller.backPressCancelled()
    assertThat(renderSessionActionCount).isEqualTo(3)

    var parsedItems = emptyList<NavigationItemInfo>()
    controller.parseNavigationItem("State0") { parsedItems = it }
    assertThat(renderSessionActionCount).isEqualTo(4)
    assertThat(parsedItems.map { it.navKey }).containsExactly("State0")

    // Verify exceptions during parsing are caught and invoke onParsed with an empty list
    val throwingItem =
      object {
        override fun toString(): String = throw SecurityException("Blocked by sandbox")
      }
    controller.parseNavigationItem(throwingItem) { parsedItems = it }
    assertThat(renderSessionActionCount).isEqualTo(5)
    assertThat(parsedItems).isEmpty()
  }

  @Test
  fun testUntrustedNavigationEventDispatcherOwnerIsRejected() {
    // Malicious attacker-supplied class from an untrusted origin
    class EvilOwner {
      var cancelledCalled = false

      fun onBackPressCancelled() {
        cancelledCalled = true
      }
    }

    val evilOwner = EvilOwner()
    val controller =
      InteractivePreviewNavigationController(
        usageTrackerProvider = { InteractiveNopTracker() },
        fpsUpdater = MutableSharedFlow(),
      )
    val composeViewAdapterObjFake = TestComposeViewAdapterViewObj()
    controller.updateObjects(evilOwner, composeViewAdapterObjFake, hasNavDisplay = false)

    // EvilOwner is rejected because it is untrusted, falling back to composeViewAdapterObjFake
    controller.backPressCancelled()
    assertThat(evilOwner.cancelledCalled).isFalse()
  }

  @Test
  fun testHideNavigationControlsPreservesDispatcherOwnerAndMethods() {
    // Set up the controller with an active dispatcher owner and NavDisplay in the view tree
    val controller = createController()
    val composeViewAdapterObjFake =
      TestComposeViewAdapterViewObj(
        canBackPress = true,
        onBackPressProgressCallback = { _, _ -> },
        onBackPressCompletedCallback = {},
        history = listOf("Screen1", "Screen2"),
      )
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = true)
    assertThat(controller.canBackPress()).isTrue()
    assertThat(controller.canPerformBackNavigation()).isTrue()
    assertThat(controller.canShowNavigationPanel()).isTrue()
    assertThat(controller.getNavigationHistory()).isNotEmpty()

    // Hide the navigation controls during an active interactive session
    runInEdtAndWait { controller.hideNavigationControls() }

    // Verify the navigation state and canShowNavigationPanel() remain intact so the user can re-enable the panel
    assertThat(controller.canBackPress()).isTrue()
    assertThat(controller.canPerformBackNavigation()).isTrue()
    assertThat(controller.canShowNavigationPanel()).isTrue()
    assertThat(controller.getNavigationHistory()).containsExactly("Screen1", "Screen2").inOrder()
  }

  @Test
  fun testClearClearsDispatcherOwnerAndMethods() {
    // Set up the controller with an active dispatcher owner and NavDisplay in the view tree
    val controller = createController()
    val composeViewAdapterObjFake =
      TestComposeViewAdapterViewObj(
        canBackPress = true,
        onBackPressProgressCallback = { _, _ -> },
        onBackPressCompletedCallback = {},
        history = listOf("Screen1", "Screen2"),
      )
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = true)
    assertThat(controller.canBackPress()).isTrue()
    assertThat(controller.canPerformBackNavigation()).isTrue()
    assertThat(controller.canShowNavigationPanel()).isTrue()
    assertThat(controller.getNavigationHistory()).isNotEmpty()

    // Clear the controller when exiting Interactive Mode
    controller.clear()

    // Verify all dispatcher references, cached reflection methods, and navigation flags are cleared
    assertThat(controller.canBackPress()).isFalse()
    assertThat(controller.canPerformBackNavigation()).isFalse()
    assertThat(controller.canShowNavigationPanel()).isFalse()
    assertThat(controller.getNavigationHistory()).isEmpty()
  }

  @Test
  fun testScreenLevelBackHandlerPreservesSceneNavigationHistoryAndCanBackPress() {
    // Define a fake BackHandlerInfo matching androidx.activity.compose.BackHandler's non-destination info
    data class BackHandlerInfo(val owner: Any, val compositeKey: Long)

    // Define a fake NavigationEventHandler holding isBackEnabled, currentInfo, and backInfo
    class FakeRegisteredHandler(
      var isBackEnabled: Boolean,
      var currentInfo: Any,
      var backInfo: List<Any> = emptyList(),
    )

    // Define a fake NavigationEventProcessor holding overlayHandlers and defaultHandlers
    class FakeNavigationEventProcessor(
      val overlayHandlers: List<FakeRegisteredHandler> = emptyList(),
      val defaultHandlers: List<FakeRegisteredHandler> = emptyList(),
    )

    // Define a fake NavigationEventDispatcher exposing sharedProcessor
    class FakeDispatcher(val sharedProcessor: FakeNavigationEventProcessor)

    // Define a fake NavigationEventDispatcherOwner exposing navigationEventDispatcher, canBackPress, and getHistory
    class FakeDispatcherOwnerWithHandlers(
      val navigationEventDispatcher: FakeDispatcher,
      var canBackPressValue: Boolean,
      var rawHistory: List<Any>,
    ) {
      fun canBackPress(): Boolean = canBackPressValue

      fun getHistory(): List<Any> = rawHistory
    }

    // Set up root RouteA with an active screen-level BackHandler(enabled = true)
    val backHandlerInfo = BackHandlerInfo(owner = "FakeOwner", compositeKey = 42L)
    val screenBackHandler = FakeRegisteredHandler(isBackEnabled = true, currentInfo = backHandlerInfo)
    val navDisplayHandler = FakeRegisteredHandler(isBackEnabled = true, currentInfo = "RouteA", backInfo = emptyList())
    val processor = FakeNavigationEventProcessor(defaultHandlers = listOf(screenBackHandler, navDisplayHandler))
    val dispatcherOwner =
      FakeDispatcherOwnerWithHandlers(
        navigationEventDispatcher = FakeDispatcher(processor),
        // At root [RouteA], history.currentIndex is 0 so owner.canBackPress() returns false
        canBackPressValue = false,
        // NavigationEventProcessor puts activeHandler.currentInfo (BackHandlerInfo) into history
        rawHistory = listOf(backHandlerInfo),
      )

    // Configure controller to trust FakeDispatcherOwnerWithHandlers
    val controller =
      createController().apply {
        setIsTrustedNavigationEventDispatcherOwnerForTestOnly { it is FakeDispatcherOwnerWithHandlers }
      }
    controller.updateObjects(dispatcherOwner, TestComposeViewAdapterViewObj(), hasNavDisplay = true)

    // Verify RouteA is shown in navigation history instead of BackHandlerInfo, and back press is enabled while BackHandler is active
    assertThat(controller.getNavigationHistory()).containsExactly("RouteA").inOrder()
    assertThat(controller.canBackPress()).isTrue()

    // Simulate clicking Navigate Back on RouteA, which disables the screen-level BackHandler
    screenBackHandler.isBackEnabled = false
    dispatcherOwner.rawHistory = listOf("RouteA")

    // Verify RouteA remains in navigation history and back press becomes disabled at root once BackHandler is disabled
    assertThat(controller.getNavigationHistory()).containsExactly("RouteA").inOrder()
    assertThat(controller.canBackPress()).isFalse()

    // Simulate navigating forward to RouteB where RouteB has an active screen-level BackHandler(enabled = true)
    screenBackHandler.isBackEnabled = true
    navDisplayHandler.backInfo = listOf("RouteA")
    navDisplayHandler.currentInfo = "RouteB"
    dispatcherOwner.canBackPressValue = true
    dispatcherOwner.rawHistory = listOf("RouteA", backHandlerInfo)

    // Verify both RouteA and RouteB are preserved in navigation history and BackHandlerInfo is excluded
    assertThat(controller.getNavigationHistory()).containsExactly("RouteA", "RouteB").inOrder()
    assertThat(controller.canBackPress()).isTrue()
  }

  @Test
  fun testMultipleRootNavDisplaysAndNestedDestinationHandlersPreserveAllCurrentInfos() {
    // Define a fake OnBackPressedCallbackInfo matching androidx.activity.OnBackPressedCallbackInfo
    data class OnBackPressedCallbackInfo(val callback: Any)

    class FakeRegisteredHandler(
      var isBackEnabled: Boolean,
      var currentInfo: Any,
      var backInfo: List<Any> = emptyList(),
    )

    class FakeNavigationEventProcessor(
      val overlayHandlers: List<FakeRegisteredHandler> = emptyList(),
      val defaultHandlers: List<FakeRegisteredHandler> = emptyList(),
    )

    class FakeDispatcher(val sharedProcessor: FakeNavigationEventProcessor)

    class FakeDispatcherOwnerWithHandlers(
      val navigationEventDispatcher: FakeDispatcher,
      var canBackPressValue: Boolean,
      var rawHistory: List<Any>,
    ) {
      fun canBackPress(): Boolean = canBackPressValue

      fun getHistory(): List<Any> = rawHistory
    }

    // Register two root NavDisplay handlers (innermost first in LIFO defaultHandlers) with empty backInfo
    val outerNavHandler = FakeRegisteredHandler(isBackEnabled = true, currentInfo = "OuterA", backInfo = emptyList())
    val innerNavHandler = FakeRegisteredHandler(isBackEnabled = true, currentInfo = "Inner1", backInfo = emptyList())
    val callbackInfo = OnBackPressedCallbackInfo(callback = "Callback")
    val callbackHandler = FakeRegisteredHandler(isBackEnabled = false, currentInfo = callbackInfo)
    val processor = FakeNavigationEventProcessor(defaultHandlers = listOf(callbackHandler, innerNavHandler, outerNavHandler))
    val dispatcherOwner =
      FakeDispatcherOwnerWithHandlers(
        navigationEventDispatcher = FakeDispatcher(processor),
        canBackPressValue = false,
        rawHistory = emptyList(),
      )

    val controller =
      createController().apply {
        setIsTrustedNavigationEventDispatcherOwnerForTestOnly { it is FakeDispatcherOwnerWithHandlers }
      }
    controller.updateObjects(dispatcherOwner, TestComposeViewAdapterViewObj(), hasNavDisplay = true)

    // Verify multiple root NavDisplays with empty backInfo do not falsely enable Navigate Back
    assertThat(controller.canBackPress()).isFalse()
    assertThat(controller.getNavigationHistory()).containsExactly("OuterA", "Inner1").inOrder()

    // Advance both outer and inner NavDisplays and enable the screen-level callback handler
    outerNavHandler.backInfo = listOf("OuterA")
    outerNavHandler.currentInfo = "OuterB"
    innerNavHandler.backInfo = listOf("Inner1")
    innerNavHandler.currentInfo = "Inner2"
    callbackHandler.isBackEnabled = true
    dispatcherOwner.rawHistory = listOf("OuterA", "Inner1", callbackInfo)

    // Verify both OuterB and Inner2 currentInfos are preserved in outer-to-inner order
    assertThat(controller.canBackPress()).isTrue()
    assertThat(controller.getNavigationHistory()).containsExactly("OuterA", "OuterB", "Inner1", "Inner2").inOrder()
  }
}
