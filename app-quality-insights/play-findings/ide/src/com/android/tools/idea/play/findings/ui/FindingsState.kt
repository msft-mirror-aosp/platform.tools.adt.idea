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
import com.android.tools.idea.insights.LoadingState

/**
 * Represents the UI and data state of the Play Console Findings tab.
 *
 * @property currentFindings The [LoadingState] of the findings list for the active package.
 * @property selectedFinding The currently selected [AppFinding], or null if no finding is selected.
 * @property packageName The package name for which findings are currently loaded.
 */
data class FindingsState(
  val currentFindings: LoadingState<List<AppFinding>> = LoadingState.Loading,
  val selectedFinding: AppFinding? = null,
  val packageName: String? = null,
)
