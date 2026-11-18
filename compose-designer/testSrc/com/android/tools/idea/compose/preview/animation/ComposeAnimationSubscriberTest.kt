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
package com.android.tools.idea.compose.preview.animation

import androidx.compose.animation.tooling.ComposeAnimation
import com.android.testutils.TestUtils.resolveWorkspacePath
import com.android.testutils.delayUntilCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.compose.preview.animation.TestUtils.createComposeAnimation
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneRenderConfiguration
import com.android.tools.rendering.classloading.NopClassLocator
import com.android.tools.rendering.classloading.PreviewAnimationClockMethodTransform
import com.android.tools.rendering.classloading.loaders.AsmTransformingLoader
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.android.tools.rendering.classloading.toClassTransform
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.runInEdtAndGet
import java.awt.Dimension
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.android.uipreview.createUrlClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeAnimationSubscriberTest : AnimationPreviewTests() {

  @Test
  fun subscribeAndUnsubscribe() = runTest {
    val animationPreview = createAnimationPreview(backgroundScope)
    try {
      ComposeAnimationSubscriber.setHandler(animationPreview)

      val animation = createComposeAnimation()
      advanceUntilIdle()
      assertTrue(animationPreview.hasNoAnimationsForTests())

      ComposeAnimationSubscriber.onAnimationSubscribed(TestClock(), animation)
      advanceUntilIdle()
      assertFalse(animationPreview.hasNoAnimationsForTests())

      val otherAnimation = createComposeAnimation()
      ComposeAnimationSubscriber.onAnimationUnsubscribed(otherAnimation)
      advanceUntilIdle()
      assertFalse(animationPreview.hasNoAnimationsForTests())

      ComposeAnimationSubscriber.onAnimationUnsubscribed(animation)
      advanceUntilIdle()
      assertTrue(animationPreview.hasNoAnimationsForTests())
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          cancelAndJoinPreviews(animationPreview)
        }
      }
    }
  }

  @Test
  fun setNullHandlerClearsSubscriptions() = runTest {
    val animationPreview = createAnimationPreview(backgroundScope)
    try {
      ComposeAnimationSubscriber.setHandler(animationPreview)

      ComposeAnimationSubscriber.onAnimationSubscribed(TestClock(), createComposeAnimation())
      advanceUntilIdle()
      assertFalse(animationPreview.hasNoAnimationsForTests())

      ComposeAnimationSubscriber.setHandler(null)
      advanceUntilIdle()
      assertTrue(animationPreview.hasNoAnimationsForTests())
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          cancelAndJoinPreviews(animationPreview)
        }
      }
    }
  }

  @Test
  fun subscriptionNewClockClearsPreviousClockAnimations() = runTest {
    val animationPreview = createAnimationPreview(backgroundScope)
    try {
      ComposeAnimationSubscriber.setScopeForTests(backgroundScope)
      ComposeAnimationSubscriber.setHandler(animationPreview)
      advanceUntilIdle()
      assertNull(animationPreview.tabbedPane.parent)
      assertEquals(0, animationPreview.animations.size)

      val clock = TestClock()
      ComposeAnimationSubscriber.onAnimationSubscribed(clock, createComposeAnimation())
      advanceUntilIdle()
      assertNotNull(animationPreview.tabbedPane.parent)
      assertEquals(1, animationPreview.animations.size)

      val anotherClock = TestClock()
      ComposeAnimationSubscriber.onAnimationSubscribed(anotherClock, createComposeAnimation())
      advanceUntilIdle()
      assertEquals(1, animationPreview.animations.size)

      ComposeAnimationSubscriber.onAnimationSubscribed(anotherClock, createComposeAnimation())
      advanceUntilIdle()
      assertEquals(2, animationPreview.animations.size)
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          try {
            ComposeAnimationSubscriber.setScopeForTests(null)
          } finally {
            cancelAndJoinPreviews(animationPreview)
          }
        }
      }
    }
  }

  @Test
  @Throws(IOException::class, ClassNotFoundException::class)
  fun classLoaderRedirectsSubscriptionToAnimationManager() = runTest {
    val animationPreview = createAnimationPreview(scope = backgroundScope).apply { this.component.size = Dimension(400, 400) }
    var ui: FakeUi? = null
    try {
      ui =
        withContext(Dispatchers.EDT) {
          FakeUi(animationPreview.component).apply {
            layoutAndDispatchEvents()
            updateToolbarsIfNecessary()
          }
        }
      ComposeAnimationSubscriber.setScopeForTests(scope = backgroundScope)
      ComposeAnimationSubscriber.setHandler(animationPreview)
      class PreviewAnimationClockClassLoader :
        DelegatingClassLoader(
          this.javaClass.classLoader,
          AsmTransformingLoader(
            toClassTransform({ PreviewAnimationClockMethodTransform(it) }),
            ClassLoaderLoader(
              createUrlClassLoader(
                listOf(resolveWorkspacePath("tools/adt/idea/compose-designer/testData/classloader").resolve("composeanimation.jar"))
              )
            ),
            NopClassLocator,
          ),
        ) {
        fun loadPreviewAnimationClock(): Class<*> = loadClass("androidx.compose.ui.tooling.animation.PreviewAnimationClock")
      }

      // Subscribe to animation
      val previewAnimationClockClassLoader = PreviewAnimationClockClassLoader()
      val previewAnimationClock = previewAnimationClockClassLoader.loadPreviewAnimationClock()
      val notifySubscribe = previewAnimationClock.getDeclaredMethod("notifySubscribe", ComposeAnimation::class.java)
      val animation = createComposeAnimation()
      notifySubscribe.invoke(previewAnimationClock.newInstance(), animation)
      advanceUntilIdle()
      delayUntilCondition(10, 5.seconds) { !animationPreview.hasNoAnimationsForTests() && animationPreview.animations.isNotEmpty() }
      assertFalse(animationPreview.hasNoAnimationsForTests())

      // Unsubscribe from animation
      val notifyUnsubscribe = previewAnimationClock.getDeclaredMethod("notifyUnsubscribe", ComposeAnimation::class.java)
      notifyUnsubscribe.invoke(previewAnimationClock.newInstance(), animation)
      advanceUntilIdle()
      delayUntilCondition(10, 5.seconds) { animationPreview.hasNoAnimationsForTests() && animationPreview.animations.isEmpty() }
      assertTrue(animationPreview.hasNoAnimationsForTests())
    } finally {
      // Clean up
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          try {
            ComposeAnimationSubscriber.setScopeForTests(null)
          } finally {
            try {
              ui?.let {
                withContext(Dispatchers.EDT) {
                  it.layoutAndDispatchEvents()
                  it.updateToolbarsIfNecessary()
                }
              }
            } finally {
              cancelAndJoinPreviews(animationPreview)
            }
          }
        }
      }
    }
  }

  @Test
  fun clockSwapUpdatesClockBeforeRemovingOldAnimations() = runTest {
    val configuration =
      mock<LayoutlibSceneRenderConfiguration>().apply {
        whenever(this.executeCallbacksAfterRender).thenReturn(AtomicBoolean(false))
        whenever(this.doubleRender).thenReturn(AtomicBoolean(false))
      }
    val sceneManager =
      mock<LayoutlibSceneManager>().apply {
        whenever(this.sceneRenderConfiguration).then { configuration }
        whenever(this.executeInRenderSessionAsync(any(), any(), any())).thenAnswer { invocation ->
          invocation.getArgument<Runnable>(0).run()
          CompletableFuture.completedFuture<Void>(null)
        }
      }
    val preview = runInEdtAndGet {
      ComposeAnimationPreview(backgroundScope, surface.project, NoopComposeAnimationTracker, { sceneManager }, surface)
    }
    try {
      ComposeAnimationSubscriber.setHandler(preview)

      val clockACalls = AtomicInteger(0)
      val clockA = CountingDurationClock(allCalls = clockACalls)
      val clockBMaxDurationCalls = AtomicInteger(0)
      val clockB = CountingDurationClock(maxDurationCalls = clockBMaxDurationCalls)

      ComposeAnimationSubscriber.onAnimationSubscribed(clockA, createComposeAnimation("a1"))
      ComposeAnimationSubscriber.onAnimationSubscribed(clockA, createComposeAnimation("a2"))
      advanceUntilIdle()
      assertEquals(2, preview.animations.size)

      clockACalls.set(0)
      clockBMaxDurationCalls.set(0)
      ComposeAnimationSubscriber.onAnimationSubscribed(clockB, createComposeAnimation("b1"))
      advanceUntilIdle()

      assertSame(clockB, preview.animationClock?.clock)
      assertEquals(0, clockACalls.get())
      // updateMaxDuration is called only once for the newly added animation, not per removed manager.
      assertEquals(1, clockBMaxDurationCalls.get())
      assertEquals(1, preview.animations.size)
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          cancelAndJoinPreviews(preview)
        }
      }
    }
  }

  @Test
  fun subscriptionWithDisposedModuleClassLoaderIsIgnored() = runTest {
    val preview = createAnimationPreview(backgroundScope).also { it.animationClock = null }
    try {
      ComposeAnimationSubscriber.setHandler(preview)

      val (disposedClock, _) =
        loadRemappedClassInModuleClassLoader(TestClock::class.java, "test.remapped.DisposedSubscriberClock", disposed = true)
      ComposeAnimationSubscriber.onAnimationSubscribed(disposedClock, createComposeAnimation("ignored"))
      advanceUntilIdle()

      assertNull(preview.animationClock)
      assertTrue(preview.hasNoAnimationsForTests())
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          cancelAndJoinPreviews(preview)
        }
      }
    }
  }

  @Test
  fun staleQueuedCallbacksAreDroppedAfterSetHandler() = runTest {
    val preview1 = createAnimationPreview(backgroundScope)
    val preview2 = createAnimationPreview(backgroundScope)
    try {
      ComposeAnimationSubscriber.setScopeForTests(backgroundScope)
      ComposeAnimationSubscriber.setHandler(preview1)

      val staleSubscribeAnimation = createComposeAnimation("stale-sub")
      ComposeAnimationSubscriber.animationSubscribed(TestClock(), staleSubscribeAnimation)

      // Swap handlers before the queued coroutine executes.
      ComposeAnimationSubscriber.setHandler(preview2)
      advanceUntilIdle()
      assertTrue(preview2.hasNoAnimationsForTests())

      // Queue an unsubscribe on a tracking handler, swap handlers before execution, and ensure it's dropped.
      val removedAnimations = mutableListOf<ComposeAnimation>()
      val trackingHandler =
        object : ComposeAnimationHandler {
          override var animationClock: AnimationClock? = null

          override fun addAnimation(animation: ComposeAnimation): Job = CompletableDeferred(Unit)

          override fun removeAnimation(animation: ComposeAnimation): Job {
            removedAnimations.add(animation)
            return CompletableDeferred(Unit)
          }

          override fun removeAllAnimations(): Job = CompletableDeferred(Unit)
        }
      ComposeAnimationSubscriber.setHandler(trackingHandler)
      val staleUnsubscribeAnimation = createComposeAnimation("stale-unsub")
      ComposeAnimationSubscriber.animationUnsubscribed(staleUnsubscribeAnimation)

      // Swap to another handler (using trackingHandler again increments generation as well).
      ComposeAnimationSubscriber.setHandler(preview2)
      advanceUntilIdle()
      assertTrue(removedAnimations.isEmpty())
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          try {
            ComposeAnimationSubscriber.setScopeForTests(null)
          } finally {
            cancelAndJoinPreviews(preview1, preview2)
          }
        }
      }
    }
  }

  @Test
  fun concurrentClockSwapSerializesClearingBeforeSubsequentSubscriptions() = runTest {
    val clockA = TestClock()
    val clockB = TestClock()
    val b1 = createComposeAnimation("b1")
    val b2 = createComposeAnimation("b2")
    val events = mutableListOf<String>()
    val removeAllStarted = CompletableDeferred<Unit>()
    val allowRemoveAllToFinish = CompletableDeferred<Unit>()
    val fakeHandler =
      object : ComposeAnimationHandler {
        override var animationClock: AnimationClock? = AnimationClock(clockA)

        override fun addAnimation(animation: ComposeAnimation): Job {
          val label =
            when (animation) {
              b1 -> "b1"
              b2 -> "b2"
              else -> animation.toString()
            }
          events.add("add($label)")
          return CompletableDeferred(Unit)
        }

        override fun removeAnimation(animation: ComposeAnimation): Job = CompletableDeferred(Unit)

        override fun removeAllAnimations(): Job {
          events.add("removeAll")
          removeAllStarted.complete(Unit)
          return allowRemoveAllToFinish
        }
      }

    try {
      ComposeAnimationSubscriber.setHandler(fakeHandler)
      val job1 = launch { ComposeAnimationSubscriber.onAnimationSubscribed(clockB, b1) }
      val job2 = launch { ComposeAnimationSubscriber.onAnimationSubscribed(clockB, b2) }

      removeAllStarted.await()
      runCurrent()
      assertEquals(listOf("removeAll"), events)

      allowRemoveAllToFinish.complete(Unit)
      joinAll(job1, job2)

      assertEquals(listOf("removeAll", "add(b1)", "add(b2)"), events)
      assertSame(clockB, fakeHandler.animationClock?.clock)
    } finally {
      withContext(NonCancellable) {
        try {
          allowRemoveAllToFinish.complete(Unit)
        } finally {
          ComposeAnimationSubscriber.setHandler(null)
        }
      }
    }
  }

  @Test
  fun clearHandlerDoesNotClearNewerHandler() = runTest {
    val preview1 = createAnimationPreview(backgroundScope)
    val preview2 = createAnimationPreview(backgroundScope)
    try {
      ComposeAnimationSubscriber.setScopeForTests(backgroundScope)
      ComposeAnimationSubscriber.setHandler(preview1)
      ComposeAnimationSubscriber.setHandler(preview2)

      // Clearing stale preview1 must drain preview1 without clearing active preview2 or dropping callbacks for preview2.
      ComposeAnimationSubscriber.clearHandler(preview1)
      assertSame(preview2, ComposeAnimationSubscriber.getHandlerForTests())

      val anim = createComposeAnimation("after-stale-clear")
      ComposeAnimationSubscriber.animationSubscribed(TestClock(), anim)
      advanceUntilIdle()
      delayUntilCondition(10, 5.seconds) { !preview2.hasNoAnimationsForTests() && preview2.animations.isNotEmpty() }
      assertFalse(preview2.hasNoAnimationsForTests())
    } finally {
      withContext(NonCancellable) {
        try {
          ComposeAnimationSubscriber.setHandler(null)
        } finally {
          try {
            ComposeAnimationSubscriber.setScopeForTests(null)
          } finally {
            cancelAndJoinPreviews(preview1, preview2)
          }
        }
      }
    }
  }
}

private class CountingDurationClock(
  private val maxDurationCalls: AtomicInteger = AtomicInteger(0),
  private val allCalls: AtomicInteger = AtomicInteger(0),
) {
  private val delegate = TestClock()

  @Suppress("unused")
  fun getAnimatedProperties(animation: Any) = delegate.getAnimatedProperties(animation).also { allCalls.incrementAndGet() }

  @Suppress("unused")
  fun getTransitions(animation: Any, clockTimeMsStep: Long) =
    delegate.getTransitions(animation, clockTimeMsStep).also { allCalls.incrementAndGet() }

  @Suppress("unused") fun getMaxDuration() = delegate.getMaxDuration().also { allCalls.incrementAndGet() }

  @Suppress("unused")
  fun getMaxDurationPerIteration(): Long {
    maxDurationCalls.incrementAndGet()
    allCalls.incrementAndGet()
    return delegate.getMaxDurationPerIteration()
  }

  @Suppress("unused")
  fun setClockTimes(clockTimeMillis: Map<ComposeAnimation, Long>) {
    allCalls.incrementAndGet()
    delegate.setClockTimes(clockTimeMillis)
  }

  @Suppress("unused")
  fun updateFromAndToStates(animation: ComposeAnimation, fromState: Any, toState: Any) {
    allCalls.incrementAndGet()
    delegate.updateFromAndToStates(animation, fromState, toState)
  }

  @Suppress("unused")
  fun updateAnimatedVisibilityState(animation: Any, state: Any) {
    allCalls.incrementAndGet()
    delegate.updateAnimatedVisibilityState(animation, state)
  }

  @Suppress("unused")
  fun `getAnimatedVisibilityState-xga21d`(animation: Any) =
    delegate.`getAnimatedVisibilityState-xga21d`(animation).also { allCalls.incrementAndGet() }
}
