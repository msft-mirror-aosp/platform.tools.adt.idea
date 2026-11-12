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

import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingSeverity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FindingsStateTest {

  private val finding1 = AppFinding("findings/1", FindingCategory.USER_EXPERIENCE, FindingSeverity.WARNING, displayName = "Finding 1")
  private val finding2 = AppFinding("findings/2", FindingCategory.USER_EXPERIENCE, FindingSeverity.WARNING, displayName = "Finding 2")

  @Test
  fun defaultState_hasNoSelectedFinding() {
    assertThat(FindingsState().selectedFinding).isNull()
  }

  @Test
  fun withSelection_updatesSelectedFinding() {
    val state = FindingsState().withSelection(finding1)
    assertThat(state.selectedFinding).isEqualTo(finding1)

    val updated = state.withSelection(finding2)
    assertThat(updated.selectedFinding).isEqualTo(finding2)

    assertThat(updated.withSelection(null).selectedFinding).isNull()
  }

  @Test
  fun withSelection_sameFinding_keepsState() {
    val state = FindingsState(selectedFinding = finding1)

    assertThat(state.withSelection(finding1)).isSameAs(state)
  }
}
