/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.tools.adtui.TreeWalker
import com.android.tools.idea.compose.preview.analytics.AnimationToolingUsageTracker
import com.android.tools.idea.preview.animation.Card
import com.android.tools.idea.preview.animation.TimelinePanel
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderDiagnosticsRead
import com.android.tools.rendering.classloading.NopModuleClassLoadedDiagnostics
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import java.awt.Component
import java.awt.Dimension
import java.util.stream.Collectors
import javax.swing.JLabel
import org.junit.Assert.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

val NoopComposeAnimationTracker = ComposeAnimationTracker(AnimationToolingUsageTracker.getInstance(null))

object TestUtils {

  fun createComposeAnimation(label: String? = null, type: ComposeAnimationType = ComposeAnimationType.ANIMATED_VALUE) =
    object : ComposeAnimation {
      override val animationObject = Any()
      override val type = type
      override val label = label
      override val states = setOf(Any())
    }

  fun assertBigger(minimumSize: Dimension, actualSize: Dimension) =
    assertTrue(minimumSize.width <= actualSize.width && minimumSize.height <= actualSize.height)

  fun findTimeline(parent: Component): TimelinePanel =
    TreeWalker(parent).descendantStream().filter { it is TimelinePanel }.collect(Collectors.toList()).map { it as TimelinePanel }.first()

  fun Card.findLabel(): JLabel =
    TreeWalker(this.component).descendantStream().filter { it is JLabel }.collect(Collectors.toList()).map { it as JLabel }.first()

  fun Component.findComboBox(): ComboBoxAction.ComboBoxButton =
    TreeWalker(this)
      .descendantStream()
      .filter { it is ComboBoxAction.ComboBoxButton }
      .collect(Collectors.toList())
      .map { it as ComboBoxAction.ComboBoxButton }
      .first()
}

internal class ConfigurableDurationClock(private val maxDurationPerIterationMs: Long) {
  private val delegate = TestClock()

  @Suppress("unused") fun getAnimatedProperties(animation: Any) = delegate.getAnimatedProperties(animation)

  @Suppress("unused") fun getTransitions(animation: Any, clockTimeMsStep: Long) = delegate.getTransitions(animation, clockTimeMsStep)

  @Suppress("unused") fun getMaxDuration() = delegate.getMaxDuration()

  @Suppress("unused") fun getMaxDurationPerIteration(): Long = maxDurationPerIterationMs

  @Suppress("unused") fun setClockTimes(clockTimeMillis: Map<ComposeAnimation, Long>) = delegate.setClockTimes(clockTimeMillis)

  @Suppress("unused")
  fun updateFromAndToStates(animation: ComposeAnimation, fromState: Any, toState: Any) =
    delegate.updateFromAndToStates(animation, fromState, toState)

  @Suppress("unused")
  fun updateAnimatedVisibilityState(animation: Any, state: Any) = delegate.updateAnimatedVisibilityState(animation, state)

  @Suppress("unused") fun `getAnimatedVisibilityState-xga21d`(animation: Any) = delegate.`getAnimatedVisibilityState-xga21d`(animation)
}

internal class FakeModuleClassLoader(
  parent: ClassLoader?,
  definedClasses: Map<String, ByteArray>,
  var disposed: Boolean = false,
  private val onFindClass: ((String) -> Unit)? = null,
) :
  ModuleClassLoader(
    parent,
    object : DelegatingClassLoader.Loader {
      override fun loadClass(fqcn: String): ByteArray? {
        onFindClass?.invoke(fqcn)
        return definedClasses[fqcn]
      }
    },
  ) {

  override val stats: ModuleClassLoaderDiagnosticsRead = NopModuleClassLoadedDiagnostics
  override val isDisposed: Boolean
    get() = disposed

  override fun isCompatibleParentClassLoader(parent: ClassLoader?): Boolean = true

  override fun areDependenciesUpToDate(): Boolean = true

  override val isUserCodeUpToDate: Boolean = true

  override fun hasLoadedClass(fqcn: String): Boolean = false

  override val projectLoadedClasses: Set<String> = emptySet()
  override val nonProjectLoadedClasses: Set<String> = emptySet()
  override val projectClassesTransform: ClassTransform = ClassTransform.identity
  override val nonProjectClassesTransform: ClassTransform = ClassTransform.identity

  override fun dispose() {
    disposed = true
  }
}

internal fun loadRemappedClassInModuleClassLoader(
  sourceClass: Class<*>,
  remappedName: String,
  disposed: Boolean = false,
  onFindClass: ((String) -> Unit)? = null,
): Pair<Any, FakeModuleClassLoader> {
  val remapper = SimpleRemapper(Type.getInternalName(sourceClass), remappedName.replace('.', '/'))
  val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
  val classBytes = sourceClass.classLoader.getResourceAsStream("${sourceClass.name.replace('.', '/')}.class")!!.use { it.readBytes() }
  ClassReader(classBytes).accept(ClassRemapper(writer, remapper), ClassReader.EXPAND_FRAMES)

  val classLoader = FakeModuleClassLoader(sourceClass.classLoader, mapOf(remappedName to writer.toByteArray()), disposed, onFindClass)
  val instance = classLoader.loadClass(remappedName).getDeclaredConstructor().newInstance()
  return instance to classLoader
}
