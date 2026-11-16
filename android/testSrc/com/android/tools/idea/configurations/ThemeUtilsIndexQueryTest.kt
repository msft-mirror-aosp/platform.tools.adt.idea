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
package com.android.tools.idea.configurations

import com.android.tools.configurations.DefaultThemeProvider
import com.android.tools.idea.model.MergedManifestModificationTracker
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.androidFacet
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.intellij.lang.annotations.Language
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Language("XML")
private val appManifest =
  """
  <?xml version="1.0" encoding="utf-8"?>
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="google.simpleapplication">
      <application android:allowBackup="true"
          android:label="@string/app_name"
          android:theme="@style/AppTheme"
          android:supportsRtl="true">
          <activity
              android:name=".MyActivity"
              android:theme="@style/AppTheme">
              <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
          </activity>
      </application>
  </manifest>
  """
    .trimIndent()

class ThemeUtilsIndexQueryTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()
  private val facet: AndroidFacet
    get() = projectRule.module.androidFacet!!

  @Before
  fun setUp() {
    projectRule.fixture.addFileToProject("AndroidManifest.xml", appManifest)
  }

  @Test
  fun testQueryFromIndex() {
    Truth.assertThat(facet.module.getAppThemeName()).isEqualTo("@style/AppTheme")
    Truth.assertThat(facet.module.getAllActivityThemeNames()).containsExactly("@style/AppTheme")
    Truth.assertThat(facet.module.getThemeNameForActivity("google.simpleapplication.MyActivity")).isEqualTo("@style/AppTheme")
  }

  /**
   * Regression test for b/322507246.
   *
   * [getAllActivityThemeNames] should not deadlock if called in the UI thread without the read lock. (It is allowed to return incomplete
   * data).
   */
  @Test
  fun testQueryDoesNotDeadlock() {
    DumbModeTestUtils.runInDumbModeSynchronously(projectRule.project) {
      ApplicationManager.getApplication().invokeAndWait {
        Truth.assertThat(setOf("@style/AppTheme")).containsAllIn(facet.module.getAllActivityThemeNames())
      }
    }
  }

  private fun createConfiguration() =
    ConfigurationManager.getOrCreateInstance(facet.module)
      .getConfiguration(
        projectRule.fixture
          .addFileToProject("res/layout/layout.xml", "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>")
          .virtualFile
      )

  /** Regression test for b/585187652: a theme computed while the manifest index was not ready must not be cached. */
  @Test
  fun testDefaultThemeComputedBeforeManifestIndexIsReadyIsNotCached() {
    val configuration = createConfiguration()
    val indexReady = AtomicBoolean(false)
    val computedThemes = ArrayDeque(listOf("@style/FallbackTheme", "@style/AppTheme"))
    val computeCount = AtomicInteger()
    val provider =
      StudioThemeInfoProvider(facet.module, { indexReady.get() }) {
        computeCount.incrementAndGet()
        // AndroidManifestIndex becomes ready while the theme is being computed; no tracker changes.
        indexReady.set(true)
        computedThemes.removeFirst()
      }

    assertThat(provider.getDefaultTheme(configuration)).isEqualTo("@style/FallbackTheme")
    assertThat(provider.getDefaultTheme(configuration)).isEqualTo("@style/AppTheme")
    // Ready before and after: now cached (a third computation would throw NoSuchElementException).
    assertThat(provider.getDefaultTheme(configuration)).isEqualTo("@style/AppTheme")
    assertThat(computeCount.get()).isEqualTo(2)
  }

  @Test
  fun testDefaultThemeComputedInDumbModeIsNotCached() {
    val configuration = createConfiguration()
    val computeCount = AtomicInteger()
    val provider =
      StudioThemeInfoProvider(facet.module, { it.isMainManifestIndexReady() }) {
        computeCount.incrementAndGet()
        "@style/FallbackTheme"
      }
    DumbModeTestUtils.runInDumbModeSynchronously(projectRule.project) {
      provider.getDefaultTheme(configuration)
      provider.getDefaultTheme(configuration)
    }
    assertThat(computeCount.get()).isEqualTo(2)
  }

  /**
   * Regression test for b/585187652: [DefaultThemeProvider.computeDefaultThemeForConfiguration] on a background thread without a
   * pre-existing read lock must wait for smart mode rather than acquiring a read lock first and immediately falling back to the framework
   * default theme when dumb mode is active.
   */
  @Test
  fun testComputeDefaultThemeWaitsForSmartMode() {
    val configuration = createConfiguration()
    // Pre-warm target and resource repositories so findPostSplashTheme does not mask a non-waiting manifest theme fallback.
    configuration.target
    configuration.resourceResolver

    val startedLatch = CountDownLatch(1)
    var future: Future<String>? = null
    try {
      DumbModeTestUtils.runInDumbModeSynchronously(projectRule.project) {
        MergedManifestModificationTracker.getInstance(facet.module).manifestChanged()
        val submitted =
          AppExecutorUtil.getAppExecutorService().submit<String> {
            startedLatch.countDown()
            DefaultThemeProvider.computeDefaultThemeForConfiguration(configuration)
          }
        future = submitted
        assertThat(startedLatch.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(runCatching { submitted.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull()).isInstanceOf(TimeoutException::class.java)
      }

      assertThat(future!!.get(10, TimeUnit.SECONDS)).isEqualTo("@style/AppTheme")
    } finally {
      future?.cancel(true)
    }
  }
}
