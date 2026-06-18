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
package com.android.tools.idea.profilers

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.sherlock.common.system.utils.FeatureFlags
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import org.junit.After
import org.junit.Rule
import org.junit.Test

class SherlockFlagsTest {

  // Required for Sherlock's FeatureFlags to be able to access IdeInfo service.
  @get:Rule val projectRule = ProjectRule()

  @After
  fun tearDown() {
    StudioFlags.PROFILER_PERFETTO_QUERY_GENERATION.clearOverride()
    StudioFlags.PROFILER_PERFETTO_AI_TRACE_ANALYSIS.clearOverride()
  }

  @Test
  fun testQueryGenerationFlag() {
    // Verify the default value.
    assertThat(FeatureFlags.queryGenerationEnabled.get()).isEqualTo(StudioFlags.PROFILER_PERFETTO_QUERY_GENERATION.get())

    // Verify we can override the value, and can read properly.
    StudioFlags.PROFILER_PERFETTO_QUERY_GENERATION.override(true)
    assertThat(FeatureFlags.queryGenerationEnabled.get()).isTrue()

    StudioFlags.PROFILER_PERFETTO_QUERY_GENERATION.override(false)
    assertThat(FeatureFlags.queryGenerationEnabled.get()).isFalse()
  }

  @Test
  fun testAiTraceAnalysisFlag() {
    // Verify the default value.
    assertThat(FeatureFlags.aiTraceAnalysisEnabled.get()).isEqualTo(StudioFlags.PROFILER_PERFETTO_AI_TRACE_ANALYSIS.get())

    // Verify we can override the value, and can read properly.
    StudioFlags.PROFILER_PERFETTO_AI_TRACE_ANALYSIS.override(true)
    assertThat(FeatureFlags.aiTraceAnalysisEnabled.get()).isTrue()

    StudioFlags.PROFILER_PERFETTO_AI_TRACE_ANALYSIS.override(false)
    assertThat(FeatureFlags.aiTraceAnalysisEnabled.get()).isFalse()
  }
}
