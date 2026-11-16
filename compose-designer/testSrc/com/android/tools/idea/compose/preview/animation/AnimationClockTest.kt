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

import androidx.compose.animation.tooling.ComposeAnimatedProperty
import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.ComposeAnimationType
import androidx.compose.animation.tooling.TransitionInfo
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class AnimationClockTest {

  private val animation =
    object : ComposeAnimation {
      override val animationObject = Any()
      override val type = ComposeAnimationType.ANIMATED_VISIBILITY
      override val states = setOf("Enter", "Exit")
    }

  @Test
  fun animationClockWrapsComposeClockViaReflection() {
    val animationClock = AnimationClock(TestClock())
    // Check that we can find a couple of methods from TestClock
    assertNotNull(animationClock.getAnimatedPropertiesFunction)
    assertNotNull(animationClock.updateAnimatedVisibilityStateFunction)
    // getAnimatedVisibilityState is mangled in TestClock, but we should be able to find it.
    assertNotNull(animationClock.getAnimatedVisibilityStateFunction)

    try {
      // We should throw an Exception if we can't find the given function in the underlying clock,
      // and it's up to the caller to handle this.
      animationClock.findClockFunction("unknownFunction")
      fail("Expected to fail, as `unknownFunction` is not a function of TestClock.")
    } catch (ignored: NullPointerException) {}

    // getAnimatedVisibilityState is a supported function, but its name is mangled. We should find
    // it when looking for the function without
    // the hash suffix, not when we specify it.
    assertNotNull(animationClock.findClockFunction("getAnimatedVisibilityState"))
    try {
      animationClock.findClockFunction("getAnimatedVisibilityState-xga21d")
      fail("Expected to fail, as `getAnimatedVisibilityState-xga21d` should not be found when looking for the mangled name.")
    } catch (ignored: NullPointerException) {}
  }

  @Test
  fun disposedModuleClassLoaderSkipsReflectionAndReturnsDefaults() {
    val (clockInstance, classLoader) =
      loadRemappedClassInModuleClassLoader(ConfigurableClock::class.java, "test.remapped.ConfigurableClock")
    val animationClock = AnimationClock(clockInstance)

    assertFalse(animationClock.isDisposed)
    assertEquals(1000L, animationClock.getMaxDurationMs())
    assertEquals(1000L, animationClock.getMaxDurationMsPerIteration())
    assertEquals(4, animationClock.getAnimatedProperties(animation).size)
    assertEquals(4, animationClock.getTransitions(animation, 1L).size)
    assertEquals("Enter", animationClock.getAnimatedVisibilityState(animation))

    classLoader.disposed = true
    assertTrue(animationClock.isDisposed)
    assertEquals(0L, animationClock.getMaxDurationMs())
    assertNull(animationClock.getMaxDurationMsPerIteration())
    assertEquals(emptyList<ComposeAnimatedProperty>(), animationClock.getAnimatedProperties(animation))
    assertEquals(emptyList<TransitionInfo>(), animationClock.getTransitions(animation, 1L))
    assertNull(animationClock.getAnimatedVisibilityState(animation))
    animationClock.setClockTimes(mapOf(animation to 10L))
    animationClock.updateFromAndToStates(animation, listOf(1), listOf(2))
    animationClock.updateAnimatedVisibilityState(animation, "Exit")
  }

  @Test
  fun invocationTargetExceptionWithClassLoadingErrorIgnoredOnlyWhenDisposed() {
    val (clockInstance, classLoader) =
      loadRemappedClassInModuleClassLoader(ConfigurableClock::class.java, "test.remapped.ConfigurableClock")
    val animationClock = AnimationClock(clockInstance)
    val setBehavior = clockInstance.javaClass.getMethod("setBehavior", Runnable::class.java)

    // Disposed during invocation + NoClassDefFoundError -> ignored, returns default.
    setBehavior.invoke(
      clockInstance,
      Runnable {
        classLoader.disposed = true
        throw NoClassDefFoundError("missing/Class")
      },
    )
    assertNull(animationClock.getMaxDurationMsPerIteration())

    // Disposed during invocation + ClassNotFoundException -> ignored, returns default.
    classLoader.disposed = false
    setBehavior.invoke(
      clockInstance,
      Runnable {
        classLoader.disposed = true
        throw ClassNotFoundException("missing.Class")
      },
    )
    assertEquals(emptyList<TransitionInfo>(), animationClock.getTransitions(animation, 1L))

    // Live classloader + NoClassDefFoundError -> rethrown.
    classLoader.disposed = false
    setBehavior.invoke(clockInstance, Runnable { throw NoClassDefFoundError("missing/Class") })
    try {
      animationClock.getMaxDurationMs()
      fail("Expected InvocationTargetException when ModuleClassLoader is not disposed")
    } catch (e: InvocationTargetException) {
      assertTrue(e.targetException is NoClassDefFoundError)
    }

    // Disposed during invocation + non-classloading error -> rethrown.
    classLoader.disposed = false
    setBehavior.invoke(
      clockInstance,
      Runnable {
        classLoader.disposed = true
        throw IllegalStateException("Unexpected error")
      },
    )
    try {
      animationClock.getMaxDurationMs()
      fail("Expected InvocationTargetException for non-classloading errors")
    } catch (e: InvocationTargetException) {
      assertTrue(e.targetException is IllegalStateException)
    }
  }

  @Test
  fun rawNoClassDefFoundErrorIgnoredOnlyWhenDisposed() {
    // Equivalent to: class BrokenSignatureClock { fun broken(): test.missing.MissingType? = null }
    val clockClassName = "test.generated.BrokenSignatureClock"
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, clockClassName.replace('.', '/'), null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode()
      visitVarInsn(Opcodes.ALOAD, 0)
      visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(Opcodes.RETURN)
      visitMaxs(1, 1)
      visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC, "broken", "()Ltest/missing/MissingType;", null, null).apply {
      visitCode()
      visitInsn(Opcodes.ACONST_NULL)
      visitInsn(Opcodes.ARETURN)
      visitMaxs(1, 1)
      visitEnd()
    }
    writer.visitEnd()
    val classBytes = writer.toByteArray()

    // Live classloader + raw NoClassDefFoundError from clock::class.java.methods -> rethrown.
    val liveLoader = FakeModuleClassLoader(javaClass.classLoader, mapOf(clockClassName to classBytes))
    val liveClock = AnimationClock(liveLoader.loadClass(clockClassName).getDeclaredConstructor().newInstance())
    try {
      liveClock.getMaxDurationMs()
      fail("Expected NoClassDefFoundError when ModuleClassLoader is not disposed")
    } catch (_: NoClassDefFoundError) {}

    // Disposed concurrently while resolving methods -> raw NoClassDefFoundError is ignored and returns null.
    lateinit var disposingLoader: FakeModuleClassLoader
    disposingLoader =
      FakeModuleClassLoader(
        javaClass.classLoader,
        mapOf(clockClassName to classBytes),
        onFindClass = { name -> if (name == "test.missing.MissingType") disposingLoader.disposed = true },
      )
    val disposedDuringLookupClock = AnimationClock(disposingLoader.loadClass(clockClassName).getDeclaredConstructor().newInstance())
    assertNull(disposedDuringLookupClock.getMaxDurationMsPerIteration())
  }
}

@Suppress("unused")
internal class ConfigurableClock {
  private var behavior: Runnable? = null

  fun setBehavior(behavior: Runnable?) {
    this.behavior = behavior
  }

  fun getAnimatedProperties(animation: Any): List<ComposeAnimatedProperty> {
    behavior?.run()
    return TestClock().getAnimatedProperties(animation)
  }

  fun getTransitions(animation: Any, clockTimeMsStep: Long): List<TransitionInfo> {
    behavior?.run()
    return TestClock().getTransitions(animation, clockTimeMsStep)
  }

  fun getMaxDuration(): Long {
    behavior?.run()
    return 1000L
  }

  fun getMaxDurationPerIteration(): Long {
    behavior?.run()
    return 1000L
  }

  fun setClockTimes(clockTimeMillis: Map<ComposeAnimation, Long>) {
    behavior?.run()
  }

  fun updateFromAndToStates(animation: ComposeAnimation, fromState: Any, toState: Any) {
    behavior?.run()
  }

  fun updateAnimatedVisibilityState(animation: Any, state: Any) {
    behavior?.run()
  }

  fun `getAnimatedVisibilityState-xga21d`(animation: Any): Any {
    behavior?.run()
    return "Enter"
  }
}
