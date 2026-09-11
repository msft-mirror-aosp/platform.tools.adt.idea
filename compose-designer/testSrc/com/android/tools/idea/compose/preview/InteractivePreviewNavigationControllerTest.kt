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
  fun testBackToStateFromViewAdapterObj() {
    val composeViewAdapterObjFake =
      TestComposeViewAdapterViewObj(backStack = listOf("Preview Three", "Preview Two", "Preview One", "Preview Zero"))
    val controller = createController()
    controller.updateObjects(null, composeViewAdapterObjFake, hasNavDisplay = false)

    // Verify navigating back to an existing state succeeds and pops the back stack down to and including that state.
    val state = "Preview One"
    val result = controller.backToState(state)
    assertThat(result).isTrue()
    assertThat(composeViewAdapterObjFake.backStack).containsExactly("Preview Zero")

    // Verify back navigation to a non-existing state fails and leaves the back stack unchanged.
    val nonExistingState = "Non Existing Preview"
    val resultWithNonExistingState = controller.backToState(nonExistingState)
    assertThat(resultWithNonExistingState).isFalse()
    assertThat(composeViewAdapterObjFake.backStack).containsExactly("Preview Zero")
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
}
