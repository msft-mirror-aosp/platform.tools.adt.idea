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
package com.android.tools.profilers.leakcanary

import com.android.tools.leakcanarylib.data.Leak
import com.android.tools.profilers.FakeIdeProfilerServices
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

class LeakInsightModelTest {

  @Test
  fun fetchInsight_success() = runBlocking {
    val services =
      object : FakeIdeProfilerServices() {
        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return flowOf("This is an AI insight about the leak.")
        }
      }
    val model = LeakInsightModel(services, this)
    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak1")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    model.fetchInsight(leak)

    val state = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state).isInstanceOf(LoadingState.Ready::class.java)
    val readyState = state as LoadingState.Ready
    assertThat(readyState.value?.rawInsight).isEqualTo("This is an AI insight about the leak.")
  }

  @Test
  fun fetchInsight_genericFailure() = runBlocking {
    val services =
      object : FakeIdeProfilerServices() {
        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return flow { throw RuntimeException("Some generic error occurred") }
        }
      }
    val model = LeakInsightModel(services, this)
    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak2")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    model.fetchInsight(leak)

    val state = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state).isInstanceOf(LoadingState.Failure::class.java)
    val failureState = state as LoadingState.Failure
    assertThat(failureState.message).isEqualTo("Some generic error occurred")
  }

  @Test
  fun fetchInsight_networkFailure() = runBlocking {
    val services =
      object : FakeIdeProfilerServices() {
        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return flow { throw java.net.UnknownHostException("cloudcode-pa.googleapis.com") }
        }
      }
    val model = LeakInsightModel(services, this)
    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak3")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    model.fetchInsight(leak)

    val state = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state).isInstanceOf(LoadingState.Failure::class.java)
    val failureState = state as LoadingState.Failure
    assertThat(failureState.message).isEqualTo(LeakInsightModel.NETWORK_ERROR_MESSAGE)
  }

  @Test
  fun fetchInsight_unauthorizedState() = runBlocking {
    val services =
      object : FakeIdeProfilerServices() {
        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return flow { throw IllegalStateException("AI Assistant is not available.") }
        }
      }
    val model = LeakInsightModel(services, this)
    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak4")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    model.fetchInsight(leak)

    val state = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state).isInstanceOf(LoadingState.Unauthorized::class.java)
    val unauthorizedState = state as LoadingState.Unauthorized
    assertThat(unauthorizedState.message).isEqualTo("AI Assistant is not available.")
  }

  @Test
  fun showOnboarding_delegatesToIdeServicesWhenAiUnavailable() = runBlocking {
    val services = FakeIdeProfilerServices()
    services.setIsAiAvailable(false)
    val model = LeakInsightModel(services, this)

    assertThat(services.isShowAiOnboardingCalled).isFalse()
    model.showOnboarding()
    assertThat(services.isShowAiOnboardingCalled).isTrue()
  }

  @Test
  fun showOnboarding_fetchesInsightWhenAiAlreadyAvailable() = runBlocking {
    val services =
      object : FakeIdeProfilerServices() {
        override val isAiAvailable: Boolean = true

        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return flowOf("Generated insight on re-click.")
        }
      }
    val model = LeakInsightModel(services, this)
    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak_retry")
    whenever(leak.toString()).thenReturn("dummy trace")
    model.onLeakSelection(leak)

    model.showOnboarding()

    assertThat(services.isShowAiOnboardingCalled).isFalse()
    val state = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state).isInstanceOf(LoadingState.Ready::class.java)
    val readyState = state as LoadingState.Ready
    assertThat(readyState.value?.rawInsight).isEqualTo("Generated insight on re-click.")
  }

  @Test
  fun onLeakSelection_invalidatesStaleUnauthorizedCacheWhenAiBecomesAvailable() = runBlocking {
    var aiAvailable = false
    val services =
      object : FakeIdeProfilerServices() {
        override val isAiAvailable: Boolean
          get() = aiAvailable

        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return if (aiAvailable) {
            flowOf("Generated fresh insight.")
          } else {
            flow { throw IllegalStateException("AI Assistant is not available.") }
          }
        }
      }
    val model = LeakInsightModel(services, this)
    model.setInsightAutoGenerateEnabled(true)

    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak_stale_test")
    whenever(leak.toString()).thenReturn("dummy trace")

    // 1. First fetch while AI is unavailable -> produces Unauthorized state
    model.onLeakSelection(leak)
    val state1 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state1).isInstanceOf(LoadingState.Unauthorized::class.java)

    // 2. User signs in / AI becomes available
    aiAvailable = true

    // 3. User selects leak again -> stale Unauthorized cache should be invalidated and insight fetched
    model.onLeakSelection(null)
    model.onLeakSelection(leak)

    val state2 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state2).isInstanceOf(LoadingState.Ready::class.java)
    val readyState = state2 as LoadingState.Ready
    assertThat(readyState.value?.rawInsight).isEqualTo("Generated fresh insight.")
  }

  @Test
  fun onAiBecameAvailable_autoGenerateEnabled_fetchesInsight() = runBlocking {
    var aiAvailable = false
    val services =
      object : FakeIdeProfilerServices() {
        override val isAiAvailable: Boolean
          get() = aiAvailable

        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return if (aiAvailable) {
            flowOf("Auto-generated insight.")
          } else {
            flow { throw IllegalStateException("AI Assistant is not available.") }
          }
        }
      }
    val model = LeakInsightModel(services, this)
    model.setInsightAutoGenerateEnabled(true)

    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak_ai_became_available_1")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    val state1 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state1).isInstanceOf(LoadingState.Unauthorized::class.java)

    aiAvailable = true
    model.onAiBecameAvailable()

    val state2 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state2).isInstanceOf(LoadingState.Ready::class.java)
    val readyState = state2 as LoadingState.Ready
    assertThat(readyState.value?.rawInsight).isEqualTo("Auto-generated insight.")
  }

  @Test
  fun onAiBecameAvailable_fetchesInsightWhenAiBecomesAvailableEvenWhenAutoGenerateDisabled() = runBlocking {
    var aiAvailable = false
    val services =
      object : FakeIdeProfilerServices() {
        override val isAiAvailable: Boolean
          get() = aiAvailable

        override fun fetchLeakInsight(rawTrace: String): Flow<String> {
          return if (aiAvailable) {
            flowOf("Insight generated upon onboarding completion.")
          } else {
            flow { throw IllegalStateException("AI Assistant is not available.") }
          }
        }
      }
    val model = LeakInsightModel(services, this)
    model.setInsightAutoGenerateEnabled(false)

    val leak = mock(Leak::class.java)
    whenever(leak.signature).thenReturn("leak_ai_became_available_2")
    whenever(leak.toString()).thenReturn("dummy trace")

    model.onLeakSelection(leak)
    model.fetchInsight(leak)
    val state1 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state1).isInstanceOf(LoadingState.Unauthorized::class.java)

    aiAvailable = true
    model.onAiBecameAvailable()

    val state2 = model.currentInsight.first { it !is LoadingState.Loading }
    assertThat(state2).isInstanceOf(LoadingState.Ready::class.java)
    val readyState = state2 as LoadingState.Ready
    assertThat(readyState.value?.rawInsight).isEqualTo("Insight generated upon onboarding completion.")
  }
}
