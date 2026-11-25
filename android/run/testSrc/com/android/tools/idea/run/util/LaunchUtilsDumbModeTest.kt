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
package com.android.tools.idea.run.util

import com.android.tools.idea.projectsystem.NamedIdeaSourceProvider
import com.android.tools.idea.projectsystem.SourceProviders
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.replaceService
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

private const val TIMEOUT_SECONDS = 30L

class LaunchUtilsDumbModeTest {
  @get:Rule val projectRule = AndroidProjectRule.onDisk()

  private val facet
    get() = AndroidFacet.getInstance(projectRule.module)!!

  @Test
  fun testIsWatchFeatureRequiredInDumbMode() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.type.watch" android:required="true" />
      </manifest>
      """
    )
    setupDumbMode()
    assertThat(checkWatchFeatureRequired()).isTrue()
  }

  @Test
  fun testIsWatchFeatureRequiredInDumbMode_NotRequired() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.camera" android:required="true" />
      </manifest>
      """
    )
    setupDumbMode()
    assertThat(checkWatchFeatureRequired()).isFalse()
  }

  /*
  FOCUS
   */
  @Test
  fun testIsWatchFeatureRequiredInDumbMode_RequiredFalse() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.type.watch" android:required="false" />
      </manifest>
      """
    )
    setupDumbMode()
    assertThat(checkWatchFeatureRequired()).isFalse()
  }

  @Test
  fun testIsWatchFeatureRequiredInDumbMode_MultipleManifests() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.camera" android:required="true" />
      </manifest>
      """,
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.type.watch" android:required="true" />
      </manifest>
      """,
    )
    setupDumbMode()
    assertThat(checkWatchFeatureRequired()).isTrue()
  }

  @Test
  fun testIsWatchFeatureRequiredWithoutOuterReadAction() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.type.watch" android:required="true" />
      </manifest>
      """
    )
    setupDumbMode()
    val result =
      AppExecutorUtil.getAppExecutorService()
        .submit<Boolean> { LaunchUtils.isWatchFeatureRequired(facet) }
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(result).isTrue()
  }

  @Test
  fun testIsWatchFeatureRequiredYieldsToPendingWriteAction() {
    val manifestFile =
      projectRule.fixture
        .addFileToProject(
          "src/manifest_yield/AndroidManifest.xml",
          """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-feature android:name="android.hardware.type.watch" android:required="true" />
          </manifest>
          """
            .trimIndent(),
        )
        .virtualFile
    val enteredReadAction = CountDownLatch(1)
    val attempts = AtomicInteger(0)
    val writeActionCompleted = AtomicBoolean(false)

    val sourceProvider = mock<NamedIdeaSourceProvider>()
    whenever(sourceProvider.manifestFiles).thenAnswer {
      if (attempts.incrementAndGet() == 1) {
        enteredReadAction.countDown()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadlineNanos) {
          ProgressManager.checkCanceled()
          Thread.sleep(10)
        }
        throw AssertionError("Read action was not canceled when a write action became pending")
      }
      listOf(manifestFile)
    }
    SourceProviders.replaceForTest(facet, projectRule.testRootDisposable, sourceProvider)
    setupDumbMode()

    val future =
      AppExecutorUtil.getAppExecutorService().submit<Boolean> {
        LaunchUtils.isWatchFeatureRequired(facet)
      }
    assertThat(enteredReadAction.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

    runWriteActionAndWait { writeActionCompleted.set(true) }

    assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(writeActionCompleted.get()).isTrue()
    assertThat(attempts.get()).isAtLeast(2)
  }

  @Test
  fun testIsWatchFeatureRequiredOnEdtWithoutOuterReadAction() {
    setupManifests(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-feature android:name="android.hardware.type.watch" android:required="true" />
      </manifest>
      """
    )
    setupDumbMode()
    val result = AtomicBoolean(false)
    SwingUtilities.invokeAndWait {
      val app = ApplicationManager.getApplication()
      assertThat(app.isDispatchThread).isTrue()
      assertThat(app.isReadAccessAllowed).isFalse()
      result.set(LaunchUtils.isWatchFeatureRequired(facet))
    }
    assertThat(result.get()).isTrue()
  }

  @Test
  fun testIsWatchFeatureRequiredPropagatesCannotReadExceptionInsideCancellableReadAction() {
    val manifestFile =
      projectRule.fixture
        .addFileToProject(
          "src/manifest_cancellable/AndroidManifest.xml",
          """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-feature android:name="android.hardware.type.watch" android:required="true" />
          </manifest>
          """
            .trimIndent(),
        )
        .virtualFile
    val enteredReadAction = CountDownLatch(1)
    val attempts = AtomicInteger(0)
    val writeActionCompleted = AtomicBoolean(false)

    val sourceProvider = mock<NamedIdeaSourceProvider>()
    whenever(sourceProvider.manifestFiles).thenAnswer {
      if (attempts.incrementAndGet() == 1) {
        enteredReadAction.countDown()
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadlineNanos) {
          ProgressManager.checkCanceled()
          Thread.sleep(10)
        }
        throw AssertionError("Cancellable read action was not canceled when a write action became pending")
      }
      listOf(manifestFile)
    }
    SourceProviders.replaceForTest(facet, projectRule.testRootDisposable, sourceProvider)
    setupDumbMode()

    val cannotReadCount = AtomicInteger(0)
    val future =
      AppExecutorUtil.getAppExecutorService().submit<Boolean> {
        val loopDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        var watchRequired: Boolean? = null
        while (watchRequired == null && System.nanoTime() < loopDeadlineNanos) {
          try {
            watchRequired = ReadAction.computeCancellableUnsafe<Boolean, RuntimeException> { LaunchUtils.isWatchFeatureRequired(facet) }
          } catch (_: ReadAction.CannotReadException) {
            cannotReadCount.incrementAndGet()
            Thread.sleep(10)
          }
        }
        watchRequired
          ?: throw AssertionError(
            "Timed out waiting for cancellable read action to succeed after ${cannotReadCount.get()} CannotReadException retries"
          )
      }
    assertThat(enteredReadAction.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

    runWriteActionAndWait { writeActionCompleted.set(true) }

    assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(writeActionCompleted.get()).isTrue()
    assertThat(cannotReadCount.get()).isAtLeast(1)
    assertThat(attempts.get()).isAtLeast(2)
  }

  @Test
  fun testIsWatchFeatureRequiredReturnsFalseWhenDisposed() {
    val disposedFacet = mock<AndroidFacet>()
    whenever(disposedFacet.isDisposed).thenReturn(true)
    val disposedFacetResult =
      AppExecutorUtil.getAppExecutorService()
        .submit<Boolean> { LaunchUtils.isWatchFeatureRequired(disposedFacet) }
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(disposedFacetResult).isFalse()

    val disposedModule = mock<Module>()
    whenever(disposedModule.isDisposed).thenReturn(true)
    val facetWithDisposedModule = mock<AndroidFacet>()
    whenever(facetWithDisposedModule.isDisposed).thenReturn(false)
    whenever(facetWithDisposedModule.module).thenReturn(disposedModule)
    val disposedModuleResult =
      AppExecutorUtil.getAppExecutorService()
        .submit<Boolean> { LaunchUtils.isWatchFeatureRequired(facetWithDisposedModule) }
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(disposedModuleResult).isFalse()

    val disposedProject = mock<Project>()
    whenever(disposedProject.isDisposed).thenReturn(true)
    whenever(disposedProject.getService(any<Class<Any>>())).thenThrow(AlreadyDisposedException("Project is already disposed"))
    val moduleWithDisposedProject = mock<Module>()
    whenever(moduleWithDisposedProject.isDisposed).thenReturn(false)
    whenever(moduleWithDisposedProject.project).thenReturn(disposedProject)
    val facetWithDisposedProject = mock<AndroidFacet>()
    whenever(facetWithDisposedProject.isDisposed).thenReturn(false)
    whenever(facetWithDisposedProject.module).thenReturn(moduleWithDisposedProject)
    val disposedProjectResult =
      AppExecutorUtil.getAppExecutorService()
        .submit<Boolean> { LaunchUtils.isWatchFeatureRequired(facetWithDisposedProject) }
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(disposedProjectResult).isFalse()
  }

  private fun setupDumbMode() {
    val mockDumbService = mock<DumbService>()
    whenever(mockDumbService.isDumb).thenReturn(true)
    projectRule.project.replaceService(DumbService::class.java, mockDumbService, projectRule.testRootDisposable)
  }

  private fun setupManifests(vararg contents: String) {
    val manifestFiles = contents.mapIndexed { index, content ->
      projectRule.fixture.addFileToProject("src/manifest_$index/AndroidManifest.xml", content.trimIndent()).virtualFile
    }
    val sourceProvider = mock<NamedIdeaSourceProvider>()
    whenever(sourceProvider.manifestFiles).thenReturn(manifestFiles)
    SourceProviders.replaceForTest(facet, projectRule.testRootDisposable, sourceProvider)
  }

  private fun checkWatchFeatureRequired(): Boolean {
    return ReadAction.nonBlocking<Boolean> { LaunchUtils.isWatchFeatureRequired(facet) }
      .submit(AppExecutorUtil.getAppExecutorService())
      .get()
  }
}
