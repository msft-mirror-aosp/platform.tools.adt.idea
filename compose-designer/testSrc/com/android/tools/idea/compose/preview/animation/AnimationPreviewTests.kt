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
package com.android.tools.idea.compose.preview.animation

import com.android.SdkConstants
import com.android.tools.idea.common.fixtures.ComponentDescriptor
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.preview.animation.AnimationPreview
import com.android.tools.idea.rendering.RenderTestRule
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.NlModelBuilderUtil
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.surface.NlSurfaceBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** A base for tests creating Animation Preview. */
open class AnimationPreviewTests {

  @get:Rule val projectRule = AndroidProjectRule.withSdk()

  @get:Rule val renderRule = RenderTestRule()

  lateinit var parentDisposable: Disposable

  lateinit var surface: NlDesignSurface

  lateinit var animationPreview: ComposeAnimationPreview

  @Before
  open fun setUp() {
    parentDisposable = projectRule.testRootDisposable
    val model = runInEdtAndGet {
      NlModelBuilderUtil.model(projectRule, "layout", "layout.xml", ComponentDescriptor(SdkConstants.CLASS_COMPOSE_VIEW_ADAPTER)).build()
    }
    surface = runInEdtAndGet {
      NlSurfaceBuilder.builder(projectRule.project, parentDisposable).build()
    }
    surface.addModelsWithoutRender(listOf(model))

    val psiFile =
      projectRule.fixture.addFileToProject(
        "src/main/Test.kt",
        """
        fun main() {}
        """
          .trimIndent(),
      )

    animationPreview = runInEdtAndGet {
      createAnimationPreview(projectRule.testRootDisposable.createCoroutineScope())
    }
  }

  @After
  fun tearDownAnimationPreview() {
    if (::animationPreview.isInitialized) {
      runInEdtAndWait { animationPreview.cancelScope() }
      runBlocking { animationPreview.scope.coroutineContext.job.join() }
      runInEdtAndWait { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    }
  }

  protected fun createAnimationPreview(scope: CoroutineScope): ComposeAnimationPreview = runInEdtAndGet {
    ComposeAnimationPreview(
        scope,
        surface.project,
        NoopComposeAnimationTracker,
        { surface.model?.let { surface.getSceneManager(it) } },
        surface,
      )
      .also { it.animationClock = AnimationClock(TestClock()) }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  protected suspend fun TestScope.cancelAndJoinPreviews(vararg previews: AnimationPreview<*>) {
    withContext(NonCancellable) {
      try {
        withContext(Dispatchers.EDT) { previews.forEach { it.cancelScope() } }
        advanceUntilIdle()
        withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        previews.forEach { it.scope.coroutineContext.job.join() }
      } finally {
        advanceUntilIdle()
        withContext(Dispatchers.EDT) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
      }
    }
  }
}
