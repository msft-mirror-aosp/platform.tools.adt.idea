/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.animation

import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.ComposeAnimationType
import com.android.testutils.delayUntilCondition
import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.findDescendant
import com.android.tools.idea.compose.preview.animation.TestUtils.createComposeAnimation
import com.android.tools.idea.compose.preview.animation.TestUtils.findComboBox
import com.android.tools.idea.compose.preview.animation.TestUtils.findLabel
import com.android.tools.idea.compose.preview.animation.managers.ComposeAnimationManager
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.preview.animation.AllTabPanel
import com.android.tools.idea.preview.animation.AnimationCard
import com.android.tools.idea.preview.animation.AnimationPreview
import com.android.tools.idea.preview.animation.DEFAULT_ANIMATION_PREVIEW_MAX_DURATION_MS
import com.android.tools.idea.preview.animation.LabelCard
import com.android.tools.idea.preview.animation.SupportedAnimationManager
import com.android.tools.idea.preview.animation.TestUtils.findAllCards
import com.android.tools.idea.preview.animation.TestUtils.findToolbar
import com.android.tools.idea.preview.animation.TimelinePanel
import com.android.tools.idea.preview.animation.UnsupportedAnimationManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneRenderConfiguration
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.observable.util.whenDisposed
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.assertInstanceOf
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.containers.getIfSingle
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ComposeAnimationPreviewTest : AnimationPreviewTests() {

  private val animations = ComposeAnimationType.values().map { createComposeAnimation(it.toString(), type = it) }

  private suspend fun subscribeAnimations(animations: List<ComposeAnimation>) {
    surface.sceneManagers.forEach { it.requestRenderAndWait() }
    animations.forEach { animationPreview.addAnimation(it).join() }
  }

  private suspend fun createFakeUiForInspector(inspector: ComposeAnimationPreview) =
    withContext(Dispatchers.EDT) {
      FakeUi(inspector.component.apply { size = Dimension(500, 400) }).apply {
        updateToolbars()
        layoutAndDispatchEvents()
      }
    }

  private fun createTransitionAnimation(
    states: Set<AnimationState> = setOf(AnimationState.State1, AnimationState.State2)
  ): ComposeAnimation {
    return object : ComposeAnimation {
      override val animationObject =
        object {
          @Suppress("unused") // Method is called via reflection.
          fun getCurrentState() = AnimationState.State1
        }
      override val type = ComposeAnimationType.TRANSITION_ANIMATION
      override val states = states
    }
  }

  @Test
  fun noAnimationsPanelShownWhenNoAnimationsAreSubscribed() = runTest {
    UIUtil.pump() // Wait for UI to dispatch all events
    // When first opening the inspector, we show the panel informing there are no supported
    // animations to be displayed
    assertNotNull(animationPreview.noAnimationsPanel())
    assertNull(animationPreview.tabbedPane.parent)
    assertEquals(0, animationPreview.animations.size)

    // After subscribing an animation, we should display the tabbedPane
    val animation = createComposeAnimation()
    animationPreview.addAnimation(animation).join()
    assertNull(animationPreview.noAnimationsPanel())
    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(1, animationPreview.animations.size)

    // After subscribing an animation, we should display the tabbedPane
    val anotherAnimation = createComposeAnimation()
    animationPreview.addAnimation(anotherAnimation).join()
    assertNull(animationPreview.noAnimationsPanel())
    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(2, animationPreview.animations.size)

    // After unsubscribing from one animation, animation panel still shown.
    animationPreview.removeAnimation(animation).join()
    assertNull(animationPreview.noAnimationsPanel())
    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(1, animationPreview.animations.size)

    // After unsubscribing all animations, we should hide the tabbed panel and again display the no
    // animations panel
    animationPreview.removeAnimation(anotherAnimation).join()
    assertNotNull(animationPreview.noAnimationsPanel())
    assertNull(animationPreview.tabbedPane.parent)
    assertEquals(0, animationPreview.animations.size)

    // After subscribing again to the animation, we should display the tabbedPane
    animationPreview.addAnimation(animation).join()
    assertNull(animationPreview.noAnimationsPanel())
    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(1, animationPreview.animations.size)
  }

  @Test
  fun oneTabPerSubscribedAnimation() = runTest {
    assertNull(animationPreview.tabbedPane.parent)
    assertEquals(0, animationPreview.animations.size)

    val animation1 = createComposeAnimation()
    animationPreview.addAnimation(animation1).join()
    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(1, animationPreview.animations.size)

    val animation2 = createComposeAnimation()
    animationPreview.addAnimation(animation2).join()
    assertEquals(2, animationPreview.animations.size)

    animationPreview.removeAnimation(animation1).join()
    assertEquals(1, animationPreview.animations.size)
  }

  @Test
  fun removingAnimationDoesNotRequestFocusOnCoordinationTab() = runTest {
    val animation1 = createComposeAnimation("1")
    val animation2 = createComposeAnimation("2")
    animationPreview.addAnimation(animation1).join()
    animationPreview.addAnimation(animation2).join()

    var requestedFocusOnSelect: Boolean? = null
    animationPreview.tabbedPane.setSelectionChangeHandler { _, requestFocus, doChangeSelection ->
      requestedFocusOnSelect = requestFocus
      doChangeSelection.run()
    }

    animationPreview.removeAnimation(animation1).join()
    assertEquals(false, requestedFocusOnSelect)
  }

  @Test
  fun comboBoxesDisplayComposeAnimationStates() = runTest {
    val animationStates = setOf(AnimationState.State1, AnimationState.State2, AnimationState.State3)
    val transitionAnimation = createTransitionAnimation(animationStates)

    subscribeAnimations(listOf(transitionAnimation))

    val ui = createFakeUiForInspector(animationPreview)

    // Find the AnimationCard toolbar
    val animationCardToolbar = animationPreview.component.findToolbar("AnimationCard") as JComponent

    // Assertions
    assertEquals(5, animationCardToolbar.componentCount)
    assertEquals("State1", animationCardToolbar.components[2].findComboBox().text) // From state
    assertEquals("State2", animationCardToolbar.components[4].findComboBox().text) // To state

    withContext(Dispatchers.EDT) {
      // Simulate clicking the "Swap" button
      ui.clickOn(animationCardToolbar.components[1])
    }

    // Check if states have been swapped
    assertEquals("State2", animationCardToolbar.components[2].findComboBox().text) // From state
    assertEquals("State1", animationCardToolbar.components[4].findComboBox().text) // To state
  }

  @Test
  fun animatedVisibilityComboBoxDisplayAllVisibilityStates() = runTest {
    val animatedVisibilityAnimation =
      object : ComposeAnimation {
        override val animationObject = Any()
        override val type = ComposeAnimationType.ANIMATED_VISIBILITY
        override val states = setOf("Enter", "Exit")
      }

    subscribeAnimations(listOf(animatedVisibilityAnimation))

    val ui = createFakeUiForInspector(animationPreview)

    // Find the AnimationCard toolbar
    val animationCardToolbar = animationPreview.component.findToolbar("AnimationCard") as JComponent

    // Assertions
    assertEquals(3, animationCardToolbar.componentCount)
    assertEquals("Enter", animationCardToolbar.components[2].findComboBox().text) // Initial state

    withContext(Dispatchers.EDT) {
      // Simulate clicking the "Swap" button
      ui.clickOn(animationCardToolbar.components[1])
    }

    // Check if state has changed
    assertEquals("Exit", animationCardToolbar.components[2].findComboBox().text)
  }

  @Test
  fun changeClockTime() = runTest {
    subscribeAnimations(listOf(createTransitionAnimation()))

    withContext(Dispatchers.EDT) {
      // We can get any of the combo boxes, since "from" and "to" states should be the same.
      val sliders = TreeWalker(animationPreview.component).descendantStream().filter { it is JSlider }.collect(Collectors.toList())
      assertEquals(1, sliders.size) //
      val timelineSlider = sliders[0] as JSlider
      timelineSlider.value = 100
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      timelineSlider.value = 200
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
    }
  }

  @Test
  fun playbackControlActions() = runTest {
    val numberOfPlaybackControls = 6 // loop, go to start, play, go to end, speed, separator

    subscribeAnimations(listOf(createTransitionAnimation()))

    withContext(Dispatchers.EDT) {
      val toolbars =
        TreeWalker(animationPreview.component)
          .descendantStream()
          .filter { it is ActionToolbarImpl }
          .collect(Collectors.toList())
          .map { it as ActionToolbarImpl }
      val playbackControls = toolbars.firstOrNull { it.place == "Animation Preview" }
      assertNotNull(playbackControls)
      val actions = playbackControls!!.actionGroup.getChildren(null)
      assertEquals(numberOfPlaybackControls, actions.size)
      val actionEvent = Mockito.mock(AnActionEvent::class.java)
      // Press loop
      val loopAction = actions[0] as ToggleAction
      loopAction.setSelected(actionEvent, true)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      // Play and pause
      val playAction = actions[2]
      playAction.actionPerformed(actionEvent)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      playAction.actionPerformed(actionEvent)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      // Go to start.
      val goToStart = actions[1]
      goToStart.actionPerformed(actionEvent)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      // Go to end.
      val toToEnd = actions[3]
      toToEnd.actionPerformed(actionEvent)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
      // Un-press loop
      loopAction.setSelected(actionEvent, false)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // Wait for all changes in UI thread
    }
  }

  @Test
  fun resizeInspector() = runTest {
    subscribeAnimations(listOf(createTransitionAnimation()))

    animationPreview.component.setSize(animationPreview.component.size.width * 2, animationPreview.component.size.height * 2)
  }

  @Test
  fun animationStatesInferredForBoolean() = runTest {
    val transitionAnimation =
      object : ComposeAnimation {
        override val animationObject = Any()
        override val type = ComposeAnimationType.TRANSITION_ANIMATION
        override val states = setOf(true)
      }

    subscribeAnimations(listOf(transitionAnimation))

    val ui = createFakeUiForInspector(animationPreview)

    // Find the AnimationCard toolbar
    val animationCardToolbar = animationPreview.component.findToolbar("AnimationCard") as JComponent

    // Assertions
    assertEquals(5, animationCardToolbar.componentCount)
    assertEquals("true", animationCardToolbar.components[2].findComboBox().text) // From state
    assertEquals("false", animationCardToolbar.components[4].findComboBox().text) // To state (inferred)

    withContext(Dispatchers.EDT) {
      // Simulate clicking the "Swap" button
      ui.clickOn(animationCardToolbar.components[1])
    }

    // Check if states have been swapped
    assertEquals("false", animationCardToolbar.components[2].findComboBox().text) // From state
    assertEquals("true", animationCardToolbar.components[4].findComboBox().text) // To state
  }

  @Test
  fun tabsAreNamedFromAnimationLabel() = runTest {
    val animation1 = createComposeAnimation("repeatedLabel")

    val animationWithSameLabel = createComposeAnimation("repeatedLabel")

    val animatedValueWithNullLabel = createComposeAnimation(type = ComposeAnimationType.ANIMATED_VALUE)

    val transitionAnimationWithNullLabel = createComposeAnimation(type = ComposeAnimationType.TRANSITION_ANIMATION)

    val animatedVisibilityWithNullLabel = createComposeAnimation(type = ComposeAnimationType.ANIMATED_VISIBILITY)

    subscribeAnimations(
      listOf(
        animation1,
        animationWithSameLabel,
        animatedValueWithNullLabel,
        transitionAnimationWithNullLabel,
        animatedVisibilityWithNullLabel,
      )
    )

    assertEquals(5, animationPreview.animations.size)
    assertEquals("repeatedLabel", animationPreview.getAnimationTitleAt(0))
    assertEquals("repeatedLabel (1)", animationPreview.getAnimationTitleAt(1)) // repeated titles get their index incremented
    assertEquals("Animated Value", animationPreview.getAnimationTitleAt(2)) // null labels use default title
    assertEquals("Transition Animation", animationPreview.getAnimationTitleAt(3)) // null labels use default title
    assertEquals("Animated Visibility", animationPreview.getAnimationTitleAt(4)) // null labels use default title
  }

  @Test
  fun `cards and timeline elements are added to coordination panel`() = runTest {
    subscribeAnimations(animations)

    withContext(Dispatchers.EDT) {
      val cards = findAllCards(animationPreview.component)
      val timeline = TestUtils.findTimeline(animationPreview.component)
      // 11 cards and 11 TimelineElements are added to coordination panel.
      assertEquals(11, cards.size)
      assertInstanceOf<AnimationCard>(cards[0])
      assertInstanceOf<LabelCard>(cards[1])
      assertInstanceOf<AnimationCard>(cards[2])
      assertInstanceOf<LabelCard>(cards[3])
      assertInstanceOf<LabelCard>(cards[4])
      assertInstanceOf<AnimationCard>(cards[5])
      assertInstanceOf<AnimationCard>(cards[6])
      assertInstanceOf<LabelCard>(cards[7])
      assertInstanceOf<AnimationCard>(cards[8])
      assertInstanceOf<LabelCard>(cards[9])
      assertInstanceOf<LabelCard>(cards[10])
      assertEquals(11, timeline.sliderUI.elements.size)
      // Only coordination tab is opened.
      assertEquals(1, animationPreview.tabbedPane.tabCount)
    }
  }

  @Test
  fun `managers are created for each animation`(): Unit = runTest {
    subscribeAnimations(animations)

    assertEquals(11, animationPreview.animations.size)
    assertInstanceOf<ComposeAnimationManager>(animationPreview.animations[0])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[1])
    assertInstanceOf<ComposeAnimationManager>(animationPreview.animations[2])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[3])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[4])
    assertInstanceOf<ComposeAnimationManager>(animationPreview.animations[5])
    assertInstanceOf<ComposeAnimationManager>(animationPreview.animations[6])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[7])
    assertInstanceOf<ComposeAnimationManager>(animationPreview.animations[8])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[9])
    assertInstanceOf<UnsupportedAnimationManager>(animationPreview.animations[10])
  }

  @Test
  fun invalidateInspector() = runTest {
    subscribeAnimations(animations)

    assertEquals(11, animationPreview.animations.size)
    assertNull(animationPreview.noAnimationsPanel())

    animationPreview.invalidatePanel().join()
    assertEquals(0, animationPreview.animations.size)
    assertNotNull(animationPreview.noAnimationsPanel())
  }

  @Test
  fun invalidateInspectorShouldClearTabsAndShowNoAnimationsPanel() = runTest {
    subscribeAnimations(listOf(createComposeAnimation()))

    assertNotNull(animationPreview.tabbedPane.parent)
    assertEquals(1, animationPreview.animations.size)
    assertNull(animationPreview.noAnimationsPanel())
    assertEquals(1, animationPreview.animationPreviewCardsCount())

    animationPreview.invalidatePanel().join()
    assertNotNull(animationPreview.noAnimationsPanel())
    assertNull(animationPreview.tabbedPane.parent)
    assertEquals(0, animationPreview.animations.size)
    assertEquals(0, animationPreview.animationPreviewCardsCount())
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun addAndRemoveAllAnimations() = runTest {
    val scope = backgroundScope.createChildScope()
    val animationPreview = createAnimationPreview(scope)

    val job1 = animationPreview.addAnimation(createComposeAnimation("1"))
    val job2 = animationPreview.addAnimation(createComposeAnimation("2"))
    joinAll(job1, job2)
    animationPreview.removeAllAnimations().join()
    runCurrent()
    advanceUntilIdle()
    assertEquals(0, animationPreview.animations.size)

    val job3 = animationPreview.addAnimation(createComposeAnimation("3"))
    val job4 = animationPreview.addAnimation(createComposeAnimation("4"))
    joinAll(job3, job4)
    runCurrent()
    advanceUntilIdle()
    assertEquals(2, animationPreview.animations.size)
    assertEquals(listOf("3", "4"), animationPreview.animations.map { it.animation.label })
    withContext(Dispatchers.EDT) {
      scope.cancel()
      val ui = FakeUi(animationPreview.component)
      ui.layoutAndDispatchEvents()
      ui.updateToolbarsIfNecessary()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun addAndRemoveAnimation() = runTest {
    val scope = backgroundScope.createChildScope()
    val animationPreview = createAnimationPreview(scope)

    val animation = createComposeAnimation("1")
    animationPreview.addAnimation(animation)
    animationPreview.removeAnimation(animation)
    animationPreview.addAnimation(createComposeAnimation("2")).join()
    runCurrent()
    advanceUntilIdle()
    assertEquals(1, animationPreview.animations.size)
    assertEquals("2", animationPreview.animations.first().animation.label)
    withContext(Dispatchers.EDT) {
      scope.cancel()
      val ui = FakeUi(animationPreview.component)
      ui.layoutAndDispatchEvents()
      ui.updateToolbarsIfNecessary()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun childrenAreDisposed() = runTest {
    val scope = backgroundScope.createChildScope()
    val animationPreview = createAnimationPreview(scope).apply { this.component.size = Dimension(200, 200) }
    animationPreview.addAnimation(createComposeAnimation("1")).join()
    animationPreview.addAnimation(createComposeAnimation("2")).join()
    animationPreview.addAnimation(createComposeAnimation("3")).join()

    val ui =
      withContext(Dispatchers.EDT) {
        val ui = FakeUi(animationPreview.component)
        ui.layoutAndDispatchEvents()
        ui.updateToolbarsIfNecessary()
        ui
      }

    var disposeCount = 0

    animationPreview.component.findDescendant<AllTabPanel>()!!.let { it.whenDisposed { disposeCount++ } }
    animationPreview.component.findDescendant<TimelinePanel>()!!.let {
      it.sliderUI.elements.forEach { element -> element.whenDisposed { disposeCount++ } }
    }
    scope.cancel()
    withContext(Dispatchers.EDT) { ui.layoutAndDispatchEvents() }
    runCurrent()
    advanceUntilIdle()
    // One AllTabPanel's dispose call and three TimelinePanel's dispose calls.
    assertEquals(4, disposeCount)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun renderIsRequested() = runTest {
    val calls = AtomicInteger(0)
    val executeCallbacksAfterRender = AtomicBoolean(false)
    val doubleRender = AtomicBoolean(false)
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.requestRenderAndWait()).then { calls.incrementAndGet() }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).then { CompletableFuture.completedFuture(null) }

        val configuration =
          mock<LayoutlibSceneRenderConfiguration>().apply {
            whenever(this.executeCallbacksAfterRender).thenReturn(executeCallbacksAfterRender)
            whenever(this.doubleRender).thenReturn(doubleRender)
          }
        whenever(this.sceneRenderConfiguration).then { configuration }
      }

    val animationPreview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(TestClock())
      }
    }

    try {
      animationPreview.addAnimation(createComposeAnimation("1", ComposeAnimationType.TRANSITION_ANIMATION)).join()
      runCurrent()
      val animationManager = animationPreview.animations.first() as SupportedAnimationManager

      /** We want to check what after syncing states - new render request was called. */
      val previousCalls = calls.get()
      executeCallbacksAfterRender.set(false)
      doubleRender.set(false)
      animationManager.syncAnimationWithState()
      runCurrent()
      advanceUntilIdle()
      delayUntilCondition(100, 5.seconds) { calls.get() > previousCalls }
      assertTrue(executeCallbacksAfterRender.get())
      assertTrue(doubleRender.get())
    } finally {
      cancelAndJoinPreview(animationPreview)
    }
  }

  @Test
  fun executeInRenderSessionSkipsWhenRenderTaskDisposed() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(true)
        whenever(this.sceneRenderConfiguration).then { configuration }
      }
    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(TestClock())
      }
    }

    try {
      preview.addAnimation(createComposeAnimation("1", ComposeAnimationType.TRANSITION_ANIMATION)).join()
      advanceUntilIdle()
      verify(sceneManager, never()).executeInRenderSessionAsync(any(), any(), any())
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun executeInRenderSessionIgnoresRenderTaskDisposedException() = runTest {
    val renderCalls = AtomicInteger(0)
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val disposedException =
      IllegalStateException("RenderTask was already disposed").apply {
        stackTrace = arrayOf(StackTraceElement("com.android.tools.rendering.RenderTask", "renderInner", "RenderTask.java", 1164))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.requestRenderAndWait()).then { renderCalls.incrementAndGet() }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(disposedException))
      }
    val preview = runInEdtAndGet {
      object :
        AnimationPreview<ComposeAnimationManager>(
          backgroundScope,
          surface.project,
          { sceneManager },
          surface,
          NoopComposeAnimationTracker,
        ) {
        override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {}

        override suspend fun updateMaxDuration(longTimeout: Boolean) {}

        suspend fun runInRenderSessionForTest(requestAnimationRender: Boolean = false, block: () -> Unit) {
          executeInRenderSession(requestAnimationRender = requestAnimationRender, function = block)
        }
      }
    }

    try {
      preview.runInRenderSessionForTest(requestAnimationRender = true) {}
      advanceUntilIdle()
      assertEquals(0, renderCalls.get())
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun executeInRenderSessionPropagatesCallbackIllegalStateException() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          val runnable = invocation.getArgument<Runnable>(0)
          CompletableFuture<Void>().apply {
            try {
              runnable.run()
              complete(null)
            } catch (t: Throwable) {
              completeExceptionally(t)
            }
          }
        }
      }
    val preview = runInEdtAndGet {
      object :
        AnimationPreview<ComposeAnimationManager>(
          backgroundScope,
          surface.project,
          { sceneManager },
          surface,
          NoopComposeAnimationTracker,
        ) {
        override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {}

        override suspend fun updateMaxDuration(longTimeout: Boolean) {}

        suspend fun runInRenderSessionForTest(block: () -> Unit) {
          executeInRenderSession(function = block)
        }
      }
    }

    try {
      try {
        preview.runInRenderSessionForTest { throw IllegalStateException("Callback failure") }
        fail("Expected callback IllegalStateException to be propagated")
      } catch (e: IllegalStateException) {
        assertEquals("Callback failure", e.message)
      }
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun executeInRenderSessionRethrowsCancellationException() = runTest {
    val childScope = backgroundScope.createChildScope()
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer {
          childScope.cancel()
          CompletableFuture.failedFuture<Void>(CancellationException("Render cancelled"))
        }
      }
    val preview = runInEdtAndGet {
      object :
        AnimationPreview<ComposeAnimationManager>(
          childScope,
          surface.project,
          { sceneManager },
          surface,
          NoopComposeAnimationTracker,
        ) {
        override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {}

        override suspend fun updateMaxDuration(longTimeout: Boolean) {}

        suspend fun runInRenderSessionForTest(block: () -> Unit) {
          executeInRenderSession(function = block)
        }
      }
    }

    try {
      try {
        preview.runInRenderSessionForTest {}
        fail("Expected CancellationException to be rethrown")
      } catch (_: CancellationException) {
        // Expected even when scope is already cancelled.
      }
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun executeInRenderSessionIgnoresForeignCancellationWhenScopeActive() = runTest {
    val childScope = backgroundScope.createChildScope()
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer {
          CompletableFuture.failedFuture<Void>(CancellationException("evicted"))
        }
      }
    val preview = runInEdtAndGet {
      object :
        AnimationPreview<ComposeAnimationManager>(
          childScope,
          surface.project,
          { sceneManager },
          surface,
          NoopComposeAnimationTracker,
        ) {
        override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {}

        override suspend fun updateMaxDuration(longTimeout: Boolean) {}

        suspend fun runInRenderSessionForTest(block: () -> Unit) {
          executeInRenderSession(function = block)
        }
      }
    }

    try {
      preview.runInRenderSessionForTest {}
      assertTrue(currentCoroutineContext().isActive)
      assertTrue(preview.scope.isActive)
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun executeInRenderSessionRetriesOnceWhenNewRenderTaskIsLive() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val disposedException =
      IllegalStateException("RenderTask was already disposed").apply {
        stackTrace = arrayOf(StackTraceElement("com.android.tools.rendering.RenderTask", "runAsyncAction", "RenderTask.java", 1511))
      }
    val attemptCount = AtomicInteger(0)
    val callbackRuns = AtomicInteger(0)
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          val attempt = attemptCount.incrementAndGet()
          if (attempt == 1) {
            CompletableFuture.failedFuture<Void>(disposedException)
          } else {
            invocation.getArgument<Runnable>(0).run()
            CompletableFuture.completedFuture<Void>(null)
          }
        }
      }
    val preview = runInEdtAndGet {
      object :
        AnimationPreview<ComposeAnimationManager>(
          backgroundScope,
          surface.project,
          { sceneManager },
          surface,
          NoopComposeAnimationTracker,
        ) {
        override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {}

        override suspend fun updateMaxDuration(longTimeout: Boolean) {}

        suspend fun runInRenderSessionForTest(block: () -> Unit) {
          executeInRenderSession(function = block)
        }
      }
    }

    try {
      preview.runInRenderSessionForTest { callbackRuns.incrementAndGet() }
      assertEquals(1, callbackRuns.get())
      verify(sceneManager, times(2)).executeInRenderSessionAsync(any(), any(), any())
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun tabbedPaneIsDisposedOnEdtWhenScopeCancelled() = runTest {
    val scope1 = backgroundScope.createChildScope()
    val preview1 = runInEdtAndGet { createAnimationPreview(scope1) }
    var disposedOnEdtFromCancelScope: Boolean? = null
    Disposer.register(preview1.tabsDisposable) { disposedOnEdtFromCancelScope = ApplicationManager.getApplication().isDispatchThread }

    withContext(Dispatchers.Default) { preview1.cancelScope() }
    advanceUntilIdle()
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    preview1.scope.coroutineContext[Job]?.join()
    assertEquals(true, disposedOnEdtFromCancelScope)
    assertTrue(preview1.tabsDisposable.isDisposed)

    val scope2 = backgroundScope.createChildScope()
    val preview2 = runInEdtAndGet { createAnimationPreview(scope2) }
    var disposedOnEdtFromScopeCancel: Boolean? = null
    Disposer.register(preview2.tabsDisposable) { disposedOnEdtFromScopeCancel = ApplicationManager.getApplication().isDispatchThread }

    withContext(Dispatchers.Default) {
      scope2.cancel()
      scope2.coroutineContext.job.join()
    }
    advanceUntilIdle()
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    preview2.scope.coroutineContext[Job]?.join()
    assertEquals(true, disposedOnEdtFromScopeCancel)
    assertTrue(preview2.tabsDisposable.isDisposed)
  }

  @Test
  fun tabsAreDisposedOnEdtWhenRootComponentIsDisposedOffEdt() = runTest {
    val rootComponent =
      object : JPanel(), Disposable {
        override fun dispose() {}
      }
    Disposer.register(projectRule.testRootDisposable, rootComponent)
    val scope = backgroundScope.createChildScope()
    val preview = runInEdtAndGet {
      ComposeAnimationPreview(
        scope,
        surface.project,
        NoopComposeAnimationTracker,
        { surface.model?.let { surface.getSceneManager(it) } },
        rootComponent,
      )
    }
    var disposedOnEdt: Boolean? = null
    Disposer.register(preview.tabsDisposable) { disposedOnEdt = ApplicationManager.getApplication().isDispatchThread }

    try {
      withContext(Dispatchers.Default) { Disposer.dispose(rootComponent) }
      advanceUntilIdle()
      withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
      assertEquals(true, disposedOnEdt)
      assertTrue(preview.tabsDisposable.isDisposed)
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun removeLastAnimationResetsMaxDurationWithoutRenderSession() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          invocation.getArgument<Runnable>(0).run()
          CompletableFuture.completedFuture<Void>(null)
        }
      }
    val clockWithCustomDuration = ConfigurableDurationClock(2500L)
    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(clockWithCustomDuration)
      }
    }
    try {
      val animation = createComposeAnimation("1", ComposeAnimationType.TRANSITION_ANIMATION)
      preview.addAnimation(animation).join()
      advanceUntilIdle()
      assertEquals(2500L, preview.maxDurationPerIteration.value)

      Mockito.clearInvocations(sceneManager)
      preview.removeAnimation(animation).join()
      advanceUntilIdle()

      assertTrue(preview.animations.isEmpty())
      assertEquals(DEFAULT_ANIMATION_PREVIEW_MAX_DURATION_MS, preview.maxDurationPerIteration.value)
      verify(sceneManager, never()).executeInRenderSessionAsync(any(), any(), any())
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun updateMaxDurationSkipsStaleResultAfterClearOrClockSwap() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    var delayedMode = false
    var pendingRunnable: Runnable? = null
    var pendingFuture: CompletableFuture<Void>? = null
    var renderSessionStarted: CompletableDeferred<Unit>? = null
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          val runnable = invocation.getArgument<Runnable>(0)
          if (delayedMode) {
            pendingRunnable = runnable
            CompletableFuture<Void>().also {
              pendingFuture = it
              renderSessionStarted?.complete(Unit)
            }
          } else {
            runnable.run()
            CompletableFuture.completedFuture<Void>(null)
          }
        }
      }

    val clockA = ConfigurableDurationClock(9999L)
    val clockB = ConfigurableDurationClock(3500L)
    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(clockA)
      }
    }
    try {
      // 1. Clearing all animations while updateMaxDuration is in flight must not overwrite the reset max duration.
      val anim1 = createComposeAnimation("1", ComposeAnimationType.TRANSITION_ANIMATION)
      val anim2 = createComposeAnimation("2", ComposeAnimationType.TRANSITION_ANIMATION)
      preview.addAnimation(anim1).join()
      preview.addAnimation(anim2).join()
      advanceUntilIdle()
      assertEquals(9999L, preview.maxDurationPerIteration.value)

      renderSessionStarted = CompletableDeferred()
      delayedMode = true
      val clearRaceJob = launch { preview.removeAnimation(anim1).join() }
      renderSessionStarted.await()

      delayedMode = false
      preview.removeAllAnimations().join()
      assertEquals(DEFAULT_ANIMATION_PREVIEW_MAX_DURATION_MS, preview.maxDurationPerIteration.value)

      pendingRunnable?.run()
      pendingFuture?.complete(null)
      clearRaceJob.join()
      assertEquals(DEFAULT_ANIMATION_PREVIEW_MAX_DURATION_MS, preview.maxDurationPerIteration.value)

      // 2. Swapping animationClock while updateMaxDuration is in flight must not overwrite the new clock's max duration.
      val anim3 = createComposeAnimation("3", ComposeAnimationType.TRANSITION_ANIMATION)
      val anim4 = createComposeAnimation("4", ComposeAnimationType.TRANSITION_ANIMATION)
      preview.addAnimation(anim3).join()
      preview.addAnimation(anim4).join()
      advanceUntilIdle()
      assertEquals(9999L, preview.maxDurationPerIteration.value)

      renderSessionStarted = CompletableDeferred()
      delayedMode = true
      val swapRaceJob = launch { preview.removeAnimation(anim3).join() }
      renderSessionStarted.await()

      delayedMode = false
      preview.animationClock = AnimationClock(clockB)
      preview.maxDurationPerIteration.value = 3500L

      pendingRunnable?.run()
      pendingFuture?.complete(null)
      swapRaceJob.join()
      assertEquals(3500L, preview.maxDurationPerIteration.value)
    } finally {
      pendingFuture?.complete(null)
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun addAnimationSkipsStaleOrDisposedClockWithoutAddingTab() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    var delayedMode = false
    var pendingRunnable: Runnable? = null
    var pendingFuture: CompletableFuture<Void>? = null
    var renderSessionStarted: CompletableDeferred<Unit>? = null
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          val runnable = invocation.getArgument<Runnable>(0)
          if (delayedMode) {
            pendingRunnable = runnable
            CompletableFuture<Void>().also {
              pendingFuture = it
              renderSessionStarted?.complete(Unit)
            }
          } else {
            runnable.run()
            CompletableFuture.completedFuture<Void>(null)
          }
        }
      }

    val disposedClock =
      loadRemappedClassInModuleClassLoader(TestClock::class.java, "test.remapped.DisposedPreviewClock", disposed = true).first
    val liveClockA = loadRemappedClassInModuleClassLoader(TestClock::class.java, "test.remapped.LivePreviewClockA").first
    val liveClockB = loadRemappedClassInModuleClassLoader(TestClock::class.java, "test.remapped.LivePreviewClockB").first

    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(disposedClock)
      }
    }
    try {
      // 1. Disposed clock upfront -> addAnimation skips without creating a tab or card.
      val anim1 = createComposeAnimation("Anim1", ComposeAnimationType.TRANSITION_ANIMATION)
      preview.addAnimation(anim1).join()
      advanceUntilIdle()
      assertTrue(preview.animations.isEmpty())
      assertEquals(0, preview.animationPreviewCardsCount())

      // 2. Clock swapped while tab.setup() is in flight -> stale tab is discarded and destroyed, and anim2 can be re-added on the new
      // clock.
      preview.animationClock = AnimationClock(liveClockA)
      renderSessionStarted = CompletableDeferred()
      delayedMode = true
      val anim2 = createComposeAnimation("Anim2", ComposeAnimationType.TRANSITION_ANIMATION)
      val staleAddJob = launch { preview.addAnimation(anim2).join() }
      renderSessionStarted.await()

      delayedMode = false
      preview.animationClock = AnimationClock(liveClockB)
      pendingRunnable?.run()
      pendingFuture?.complete(null)
      staleAddJob.join()
      advanceUntilIdle()
      assertTrue(preview.animations.isEmpty())
      assertEquals(0, preview.animationPreviewCardsCount())

      preview.addAnimation(anim2).join()
      advanceUntilIdle()
      assertEquals(1, preview.animations.size)
      assertEquals(1, preview.animationPreviewCardsCount())
    } finally {
      pendingFuture?.complete(null)
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun addAnimationIgnoresDisposedModuleClassLoaderInGetCurrentState() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          invocation.getArgument<Runnable>(0).run()
          CompletableFuture.completedFuture<Void>(null)
        }
      }

    // Equivalent to: class BrokenTransitionObject { fun getCurrentState(): test.missing.MissingStateType? = null }
    val objectClassName = "test.generated.BrokenTransitionObject"
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, objectClassName.replace('.', '/'), null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode()
      visitVarInsn(Opcodes.ALOAD, 0)
      visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(Opcodes.RETURN)
      visitMaxs(1, 1)
      visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC, "getCurrentState", "()Ltest/missing/MissingStateType;", null, null).apply {
      visitCode()
      visitInsn(Opcodes.ACONST_NULL)
      visitInsn(Opcodes.ARETURN)
      visitMaxs(1, 1)
      visitEnd()
    }
    writer.visitEnd()

    lateinit var loader: FakeModuleClassLoader
    loader =
      FakeModuleClassLoader(
        javaClass.classLoader,
        mapOf(objectClassName to writer.toByteArray()),
        onFindClass = { fqcn -> if (fqcn == "test.missing.MissingStateType") loader.disposed = true },
      )
    val brokenAnimationObject = loader.loadClass(objectClassName).getDeclaredConstructor().newInstance()
    val animation =
      object : ComposeAnimation {
        override val animationObject: Any = brokenAnimationObject
        override val type = ComposeAnimationType.TRANSITION_ANIMATION
        override val states = setOf("StateA", "StateB")
        override val label = "BrokenTransition"
      }

    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(TestClock())
      }
    }
    try {
      preview.addAnimation(animation).join()
      advanceUntilIdle()
      assertTrue(loader.disposed)
      assertEquals(1, preview.animations.size)
      assertEquals(1, preview.animationPreviewCardsCount())
      assertEquals("BrokenTransition", preview.getAnimationTitleAt(0))
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun addAnimationDoesNotAddTabWhenClockSwappedBeforeEdtAdd() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    var delayedMode = false
    var pendingRunnable: Runnable? = null
    var pendingFuture: CompletableFuture<Void>? = null
    var renderSessionStarted: CompletableDeferred<Unit>? = null
    lateinit var preview: ComposeAnimationPreview
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.isRenderSessionUnavailable()).thenReturn(false)
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          val runnable = invocation.getArgument<Runnable>(0)
          if (delayedMode && !preview.hasNoAnimationsForTests()) {
            pendingRunnable = runnable
            CompletableFuture<Void>().also {
              pendingFuture = it
              renderSessionStarted?.complete(Unit)
            }
          } else {
            runnable.run()
            CompletableFuture.completedFuture<Void>(null)
          }
        }
      }

    preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface).also {
        it.animationClock = AnimationClock(ConfigurableDurationClock(1000L))
      }
    }
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    advanceUntilIdle()
    try {
      // 1. Clock swap before addAnimationManager runs on EDT -> shouldAdd returns false, tab is destroyed, no card is added.
      renderSessionStarted = CompletableDeferred()
      delayedMode = true
      val anim1 = createComposeAnimation("SwapBeforeEdtAdd", ComposeAnimationType.TRANSITION_ANIMATION)
      val addJob1 = launch { preview.addAnimation(anim1).join() }
      renderSessionStarted.await()

      delayedMode = false
      preview.animationClock = AnimationClock(ConfigurableDurationClock(2000L))
      pendingRunnable?.run()
      pendingFuture?.complete(null)
      addJob1.join()
      advanceUntilIdle()
      assertTrue(preview.animations.isEmpty())
      assertTrue(preview.hasNoAnimationsForTests())
      assertEquals(0, preview.animationPreviewCardsCount())

      // 2. Unsubscription (removeAnimation) before addAnimationManager runs on EDT -> shouldAdd returns false, tab is destroyed.
      renderSessionStarted = CompletableDeferred()
      delayedMode = true
      val anim2 = createComposeAnimation("RemoveBeforeEdtAdd", ComposeAnimationType.TRANSITION_ANIMATION)
      val addJob2 = launch { preview.addAnimation(anim2).join() }
      renderSessionStarted.await()

      delayedMode = false
      preview.removeAnimation(anim2).join()
      pendingRunnable?.run()
      pendingFuture?.complete(null)
      addJob2.join()
      advanceUntilIdle()
      assertTrue(preview.animations.isEmpty())
      assertTrue(preview.hasNoAnimationsForTests())
      assertEquals(0, preview.animationPreviewCardsCount())
    } finally {
      pendingFuture?.complete(null)
      cancelAndJoinPreview(preview)
    }
  }

  @Test
  fun addAnimationWithoutClockSkipsClockBasedAnimationAndAllowsRetry() = runTest {
    val preview = runInEdtAndGet { createAnimationPreview(backgroundScope.createChildScope()).apply { animationClock = null } }
    try {
      val animation = createComposeAnimation("t", ComposeAnimationType.TRANSITION_ANIMATION)
      preview.addAnimation(animation).join()
      advanceUntilIdle()
      withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
      assertTrue(preview.animations.isEmpty())
      assertTrue(preview.hasNoAnimationsForTests())

      preview.animationClock = AnimationClock(TestClock())
      preview.addAnimation(animation).join()
      advanceUntilIdle()
      withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
      assertEquals(1, preview.animations.size)
    } finally {
      cancelAndJoinPreview(preview)
    }
  }

  private suspend fun TestScope.cancelAndJoinPreview(preview: AnimationPreview<*>) {
    withContext(Dispatchers.EDT) { preview.cancelScope() }
    advanceUntilIdle()
    withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    preview.scope.coroutineContext[Job]?.join()
  }

  private fun ComposeAnimationPreview.getAnimationTitleAt(index: Int): String = findAllCards(this.component)[index].findLabel().text

  private fun ComposeAnimationPreview.noAnimationsPanel() =
    TreeWalker(this.component).descendantStream().filter { it.name == "Loading Animations Panel" }.getIfSingle()

  private fun ComposeAnimationPreview.animationPreviewCardsCount(): Int = coordinationTab.cards.size
}
