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

import androidx.compose.animation.tooling.ComposeAnimatedProperty
import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.TransitionInfo
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.intellij.openapi.diagnostic.Logger
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.jetbrains.annotations.VisibleForTesting

internal val animationClockLogger = Logger.getInstance(AnimationClock::class.java)

/**
 * Returns a list of the given [ComposeAnimation]'s animated properties. The properties are wrapped into a [ComposeAnimatedProperty] object
 * containing the property label and the corresponding value at the current time.
 */
internal fun AnimationClock.getAnimatedProperties(animation: ComposeAnimation): List<ComposeAnimatedProperty> =
  invokeClockMethod(emptyList()) { getAnimatedPropertiesFunction.invoke(clock, animation) as List<ComposeAnimatedProperty> }

/** Returns a list of [TransitionInfo] for the given [ComposeAnimation] sampled with [clockTimeMsStep]. */
internal fun AnimationClock.getTransitions(animation: ComposeAnimation, clockTimeMsStep: Long): List<TransitionInfo> =
  invokeClockMethod(emptyList()) { getTransitionsFunction.invoke(clock, animation, clockTimeMsStep) as List<TransitionInfo> }

/** Returns the duration (ms) of the longest animation being tracked. */
internal fun AnimationClock.getMaxDurationMs(): Long = invokeClockMethod(0L) { getMaxDurationFunction.invoke(clock) as Long }

/**
 * Returns the longest duration (ms) per iteration among the animations being tracked, or `null` if the clock's classloader is disposed.
 * This can be different from [getMaxDurationMs], for instance, when there is one or more repeatable animations with multiple iterations.
 */
internal fun AnimationClock.getMaxDurationMsPerIteration(): Long? =
  invokeClockMethod(null) { getMaxDurationPerIteration.invoke(clock) as Long }

/** Seeks each animation being tracked to the given [clockTimeMillis]. */
internal fun AnimationClock.setClockTimes(clockTimeMillis: Map<ComposeAnimation, Long>) {
  invokeClockMethod(Unit) { setClockTimesFunction.invoke(clock, clockTimeMillis) }
}

/**
 * Updates the TransitionState corresponding to the given [ComposeAnimation] in the transitionStates map, creating a TransitionState with
 * the given [fromState] and [toState].
 *
 * [fromState] and [toState] being list is the expectation on androidx, i.e. it will always receive a list and try to parse it accordingly.
 * Values passed here are always wrapped into a list: either a singleton list containing a 1-dimension value, or the list of components for
 * n-dimension values.
 */
internal fun AnimationClock.updateFromAndToStates(animation: ComposeAnimation, fromState: List<Any?>, toState: List<Any?>) {
  invokeClockMethod(Unit) { updateFromAndToStatesFunction.invoke(clock, animation, fromState, toState) }
}

/** Updates the given [ComposeAnimation]'s cached AnimatedVisibilityState with the given [state]. */
internal fun AnimationClock.updateAnimatedVisibilityState(animation: ComposeAnimation, state: Any) {
  invokeClockMethod(Unit) { updateAnimatedVisibilityStateFunction.invoke(clock, animation, state) }
}

/**
 * Returns the cached AnimatedVisibilityState corresponding to the given [ComposeAnimation] object. Falls back to
 * AnimatedVisibilityState.Enter if there is no state currently mapped to the [ComposeAnimation], or `null` if the clock's classloader is
 * disposed.
 */
internal fun AnimationClock.getAnimatedVisibilityState(animation: ComposeAnimation): Any? =
  invokeClockMethod(null) { getAnimatedVisibilityStateFunction.invoke(clock, animation) }

/**
 * Invokes [block] while guarding against a disposed [ModuleClassLoader].
 *
 * When a preview refresh swaps or disposes the backing [ModuleClassLoader], queued or in-flight reflection calls can fail with
 * [NoClassDefFoundError] or [ClassNotFoundException] wrapped in [InvocationTargetException], or throw a [LinkageError] directly. Returning
 * [defaultValue] when [classLoader] is disposed avoids crashing the animation inspector while preserving genuine errors from live
 * classloaders.
 */
internal inline fun <T> invokeWithClassLoaderGuard(
  classLoader: ClassLoader?,
  defaultValue: T,
  context: String,
  block: () -> T,
): T {
  if ((classLoader as? ModuleClassLoader)?.isDisposed == true) {
    animationClockLogger.debug("Skipping $context reflection call because its ModuleClassLoader is disposed")
    return defaultValue
  }
  return try {
    block()
  } catch (e: InvocationTargetException) {
    if ((classLoader as? ModuleClassLoader)?.isDisposed == true && e.hasClassLoadingError()) {
      animationClockLogger.debug("Ignoring $context reflection failure from disposed ModuleClassLoader", e)
      defaultValue
    } else {
      throw e
    }
  } catch (e: LinkageError) {
    if ((classLoader as? ModuleClassLoader)?.isDisposed == true) {
      animationClockLogger.debug("Ignoring $context linkage failure from disposed ModuleClassLoader", e)
      defaultValue
    } else {
      throw e
    }
  }
}

private inline fun <T> AnimationClock.invokeClockMethod(defaultValue: T, block: () -> T): T =
  invokeWithClassLoaderGuard(clock.javaClass.classLoader, defaultValue, "AnimationClock", block)

internal fun Throwable.hasClassLoadingError(): Boolean {
  var current: Throwable? = this
  val visited = mutableSetOf<Throwable>()
  while (current != null && visited.add(current)) {
    if (current is NoClassDefFoundError || current is ClassNotFoundException) return true
    current = current.cause
  }
  return false
}

/**
 * Wraps a `PreviewAnimationClock` and adds APIs to make it easier to call the clock's functions via reflection.
 *
 * @param clock Instance of `PreviewAnimationClock` that animations in the inspector are subscribed to.
 */
class AnimationClock(val clock: Any) {

  /** Whether the [ModuleClassLoader] that loaded [clock] has been disposed. */
  internal val isDisposed: Boolean
    get() = (clock.javaClass.classLoader as? ModuleClassLoader)?.isDisposed == true

  /** Function `getAnimatedProperties` of [clock]. */
  val getAnimatedPropertiesFunction by lazy { findClockFunction("getAnimatedProperties") }

  /** Function `getTransitions` of [clock]. */
  val getTransitionsFunction: Method by lazy { findClockFunction("getTransitions") }

  /** Function `getMaxDuration` of [clock]. */
  val getMaxDurationFunction by lazy { findClockFunction("getMaxDuration") }

  /** Function `getMaxDurationPerIteration` of [clock]. */
  val getMaxDurationPerIteration by lazy { findClockFunction("getMaxDurationPerIteration") }

  /** Function `setClockTimes` of [clock]. */
  val setClockTimesFunction by lazy { findClockFunction("setClockTimes") }

  /** Function `updateFromAndToStates` of [clock]. */
  val updateFromAndToStatesFunction by lazy { findClockFunction("updateFromAndToStates") }

  /** Function `updateAnimatedVisibilityState` of [clock]. */
  val updateAnimatedVisibilityStateFunction by lazy { findClockFunction("updateAnimatedVisibilityState") }

  /** Function `getAnimatedVisibilityState` of [clock]. */
  val getAnimatedVisibilityStateFunction by lazy { findClockFunction("getAnimatedVisibilityState") }

  @VisibleForTesting
  fun findClockFunction(functionName: String): Method =
    clock::class
      .java
      .methods
      .firstOrNull {
        // Convert something like `setClockTime-zrx7VqY` into `setClockTime` in order to handle
        // methods that use inline classes.
        // See https://kotlinlang.org/docs/inline-classes.html#mangling for more info.
        val normalizedName = it.name.substringBefore('-')
        normalizedName == functionName
      }
      ?.apply { isAccessible = true }!!
}
