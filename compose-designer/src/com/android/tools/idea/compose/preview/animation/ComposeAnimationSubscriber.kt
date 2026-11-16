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
import com.android.annotations.concurrency.GuardedBy
import com.google.common.util.concurrent.MoreExecutors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.TestOnly

/**
 * Responsible for animation subscription events and passing them to [ComposeAnimationHandler]. `PreviewAnimationClockMethodTransform`
 * intercepts calls to `subscribe` and `unsubscribe` calls on `ui-tooling` and redirects them to [onAnimationSubscribed] and
 * [onAnimationUnsubscribed], respectively.
 */
object ComposeAnimationSubscriber {
  private val LOG = Logger.getInstance(ComposeAnimationSubscriber::class.java)
  @Volatile private var animationHandler: ComposeAnimationHandler? = null
  @GuardedBy("handlerLock") private var handlerGeneration: Long = 0
  private val handlerLock = Any()
  private val subscriptionMutex = Mutex()

  suspend fun setHandler(handler: ComposeAnimationHandler?) {
    val previousHandler =
      synchronized(handlerLock) {
        handlerGeneration++
        val prev = animationHandler
        animationHandler = handler
        prev
      }
    previousHandler?.removeAllAnimations()?.join()
  }

  /**
   * Clears the active handler only if it is still [expected], avoiding racing with a newly opened inspector's [setHandler]. Always drains
   * [expected]'s animations even if a newer handler has already replaced it.
   */
  suspend fun clearHandler(expected: ComposeAnimationHandler) {
    val cleared =
      synchronized(handlerLock) {
        if (animationHandler !== expected) return@synchronized false
        handlerGeneration++
        animationHandler = null
        true
      }
    expected.removeAllAnimations().join()
    if (cleared) {
      LOG.debug("Cleared active animation handler")
    }
  }

  private val onSubscribedUnsubscribedExecutor =
    if (ApplicationManager.getApplication().isUnitTestMode) MoreExecutors.directExecutor()
    else AppExecutorUtil.createBoundedApplicationPoolExecutor("Animation Subscribe/Unsubscribe Callback Handler", 1)

  /** [CoroutineScope] to use in tests, when it's null, [onSubscribedUnsubscribedExecutor] will be used instead. */
  private var testScope: CoroutineScope? = null

  private fun createScope(): CoroutineScope {
    return testScope?.takeIf { ApplicationManager.getApplication().isUnitTestMode }
      ?: CoroutineScope(onSubscribedUnsubscribedExecutor.asCoroutineDispatcher())
  }

  @TestOnly
  fun setScopeForTests(scope: CoroutineScope?) {
    testScope = scope
  }

  /** Sets the panel clock and creates the corresponding tab in the [ComposeAnimationPreview]. */
  @TestOnly
  suspend fun onAnimationSubscribed(clock: Any?, animation: ComposeAnimation) {
    onAnimationSubscribedInternal(clock, animation, expectedGeneration = null)
  }

  private suspend fun onAnimationSubscribedInternal(clock: Any?, animation: ComposeAnimation, expectedGeneration: Long?) {
    if (clock == null) return
    subscriptionMutex.withLock {
      val handler =
        synchronized(handlerLock) {
          if (expectedGeneration != null && handlerGeneration != expectedGeneration) return@withLock
          animationHandler
        } ?: return@withLock
      val newClock = AnimationClock(clock)
      if (newClock.isDisposed) {
        LOG.debug("Ignoring animation subscription for disposed ModuleClassLoader")
        return@withLock
      }
      if (handler.animationClock == null) {
        handler.animationClock = newClock
      }

      // Handle the case where the clock has changed while the inspector is open. That might happen
      // when we were still processing a subscription from a previous inspector when the new inspector
      // was open. Update the clock BEFORE removing old animations so teardown/timeline updates do not
      // invoke reflection against the previous disposed clock.
      handler.animationClock?.let {
        if (it.clock != clock) {
          handler.animationClock = newClock
          handler.removeAllAnimations().join()
        }
      }
      handler.addAnimation(animation).join()
    }
  }

  /** Removes the animation from the corresponding tab in the [ComposeAnimationPreview]. */
  @TestOnly
  suspend fun onAnimationUnsubscribed(animation: ComposeAnimation) {
    onAnimationUnsubscribedInternal(animation, expectedGeneration = null)
  }

  private suspend fun onAnimationUnsubscribedInternal(animation: ComposeAnimation, expectedGeneration: Long?) {
    subscriptionMutex.withLock {
      val handler =
        synchronized(handlerLock) {
          if (expectedGeneration != null && handlerGeneration != expectedGeneration) return@withLock
          animationHandler
        }
      handler?.removeAnimation(animation)?.join()
    }
  }

  @TestOnly fun getHandlerForTests() = animationHandler

  @JvmStatic
  @Suppress("unused") // Called via reflection from PreviewAnimationClockMethodTransform
  fun animationSubscribed(clock: Any?, animation: Any?) {
    if (LOG.isDebugEnabled) {
      LOG.debug("Animation subscribed: $animation")
    }
    val generation = synchronized(handlerLock) { handlerGeneration }
    createScope().launch {
      if (synchronized(handlerLock) { handlerGeneration } != generation) return@launch
      (animation as? ComposeAnimation)?.let { onAnimationSubscribedInternal(clock, it, generation) }
    }
  }

  @JvmStatic
  @Suppress("unused") // Called via reflection from PreviewAnimationClockMethodTransform
  fun animationUnsubscribed(animation: Any?) {
    if (LOG.isDebugEnabled) {
      LOG.debug("Animation unsubscribed: $animation")
    }
    if (animation == null) return
    val generation = synchronized(handlerLock) { handlerGeneration }
    createScope().launch {
      if (synchronized(handlerLock) { handlerGeneration } != generation) return@launch
      (animation as? ComposeAnimation)?.let { onAnimationUnsubscribedInternal(it, generation) }
    }
  }
}
