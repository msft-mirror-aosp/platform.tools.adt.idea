/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync

import com.android.ide.gradle.model.LegacyAndroidGradlePluginProperties
import com.android.tools.idea.gradle.model.IdeSyncIssue
import com.android.tools.idea.gradle.model.impl.IdeSyncIssueImpl
import java.io.PrintWriter
import java.io.StringWriter

internal inline fun <T> safeGet(original: () -> T, default: T): T =
  try {
    original()
  } catch (ignored: UnsupportedOperationException) {
    default
  }

internal fun LegacyAndroidGradlePluginProperties?.getProblemsAsSyncIssues(): List<IdeSyncIssue> {
  return this?.problems.orEmpty().map { problem ->
    IdeSyncIssueImpl(
      message = problem.message ?: "Unknown error in LegacyAndroidGradlePluginProperties",
      data = null,
      multiLineMessage = problem.stackTraceAsMultiLineMessage(),
      severity = IdeSyncIssue.SEVERITY_WARNING,
      type = IdeSyncIssue.TYPE_GENERIC,
    )
  }
}

public fun Throwable.stackTraceAsMultiLineMessage(): List<String> =
  StringWriter().use { stringWriter ->
    PrintWriter(stringWriter).use { printStackTrace(it) }
    stringWriter.toString().split(System.lineSeparator())
  }
