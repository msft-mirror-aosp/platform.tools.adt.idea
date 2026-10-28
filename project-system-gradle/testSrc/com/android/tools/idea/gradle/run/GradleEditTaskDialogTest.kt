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
package com.android.tools.idea.gradle.run

import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class GradleEditTaskDialogTest {
  private val disposableRule = DisposableRule()

  // EdtRule wraps DisposableRule so the dialog is also disposed on the EDT, as DialogWrapper requires.
  @get:Rule val rules = RuleChain(ApplicationRule(), EdtRule(), disposableRule)

  private fun createDialog(): GradleEditTaskDialog =
    GradleEditTaskDialog(null).also { Disposer.register(disposableRule.disposable, it.disposable) }

  @Test
  fun goalSetOnDialogIsReturnedByGetGoal() {
    val dialog = createDialog()

    dialog.setGoal(":app:assembleDebug")

    assertThat(dialog.goal).isEqualTo(":app:assembleDebug")
  }

  @Test
  fun nullGoalIsReturnedAsEmptyText() {
    // MakeBeforeRunTaskProvider.configureTask passes the task's goal, which is null for a new task.
    val dialog = createDialog()

    dialog.setGoal(null)

    assertThat(dialog.goal).isEmpty()
  }
}
