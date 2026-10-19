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

import com.android.build.FilterData
import com.android.build.OutputFile
import com.android.build.VariantOutput
import com.android.ide.gradle.model.LegacyAndroidGradlePluginProperties
import com.android.tools.idea.gradle.model.IdeSyncIssue
import com.android.tools.idea.gradle.model.impl.IdeAndroidArtifactOutputImpl
import com.android.tools.idea.gradle.model.impl.IdeFilterDataImpl
import com.android.tools.idea.gradle.model.impl.IdeSyncIssueImpl
import java.io.PrintWriter
import java.io.StringWriter

fun filterDataFrom(data: FilterData): IdeFilterDataImpl {
  return IdeFilterDataImpl(identifier = data.identifier, filterType = data.filterType)
}

fun copyFilters(output: VariantOutput): Collection<IdeFilterDataImpl> {
  return copy(
    fun(): Collection<FilterData> =
      try {
        output.filters
      } catch (ignored: UnsupportedOperationException) {
        output.outputs.flatMap(OutputFile::getFilters)
      },
    ::filterDataFrom,
  )
}

fun androidArtifactOutputFrom(output: OutputFile): IdeAndroidArtifactOutputImpl {
  return IdeAndroidArtifactOutputImpl(
    filters = copyFilters(output).toList(),
    versionCode = output.versionCode,
    outputFile = copyNewProperty({ output.outputFile }, output.mainOutputFile.outputFile),
  )
}

internal inline fun <T> safeGet(original: () -> T, default: T): T =
  try {
    original()
  } catch (ignored: UnsupportedOperationException) {
    default
  }

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
private inline fun <T : Any> copyNewProperty(propertyInvoker: () -> T, defaultValue: T): T {
  return try {
    propertyInvoker()
  } catch (ignored: UnsupportedOperationException) {
    defaultValue
  }
}

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
@Suppress("unused", "UNUSED_PARAMETER")
private inline fun <T : Collection<*>> copyNewProperty(propertyInvoker: () -> T, defaultValue: T): Unit =
  error("Cannot be called. Use copy() method.")

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
@Suppress("unused", "UNUSED_PARAMETER")
private inline fun <T : Map<*, *>> copyNewProperty(propertyInvoker: () -> T, defaultValue: T): Unit =
  error("Cannot be called. Use copy() method.")

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
@JvmName("impossibleCopyNewCollectionProperty")
@Suppress("unused", "UNUSED_PARAMETER")
private inline fun <T : Collection<*>?> copyNewProperty(propertyInvoker: () -> T): Unit = error("Cannot be called. Use copy() method.")

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
@JvmName("impossibleCopyNewMapProperty")
@Suppress("unused", "UNUSED_PARAMETER")
private inline fun <T : Map<*, *>?> copyNewProperty(propertyInvoker: () -> T): Unit = error("Cannot be called. Use copy() method.")

private inline fun <K, V> copy(original: () -> Collection<K>, mapper: (K) -> V): List<V> = safeGet(original, listOf()).map(mapper)

/**
 * NOTE: Multiple overloads are intentionally ambiguous to prevent lambdas from being used directly. Please use function references or
 * anonymous functions which seeds type inference.
 */
private inline fun <T : Any?> copyNewProperty(propertyInvoker: () -> T?): T? {
  return try {
    propertyInvoker()
  } catch (ignored: UnsupportedOperationException) {
    null
  }
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
