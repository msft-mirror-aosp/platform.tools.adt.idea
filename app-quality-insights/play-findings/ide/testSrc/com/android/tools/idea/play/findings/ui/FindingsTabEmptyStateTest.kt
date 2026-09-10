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
package com.android.tools.idea.play.findings.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [emptyTableText] empty-state wording.
 *
 * End-to-end empty-state updates in [FindingsTab] (including filter changes) are covered in [FindingsTabTest].
 */
class FindingsTabEmptyStateTest {

  @Test
  fun testNoApp_asksForAConnectedApp() {
    assertThat(emptyTableText(packageName = null, hasFindings = false)).isEqualTo(NO_APPS_TEXT)
  }

  @Test
  fun testAppWithNoFindings_reportsNoFindings() {
    assertThat(emptyTableText(packageName = "com.example.app", hasFindings = false)).isEqualTo(NO_FINDINGS_TEXT)
  }

  @Test
  fun testFindingsHiddenByFilters_doesNotClaimThereAreNone() {
    assertThat(emptyTableText(packageName = "com.example.app", hasFindings = true)).isEqualTo(NO_MATCHING_FINDINGS_TEXT)
  }
}
