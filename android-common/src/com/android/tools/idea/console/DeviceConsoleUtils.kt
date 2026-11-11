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
@file:JvmName("DeviceConsoleUtils")

package com.android.tools.idea.console

import com.intellij.execution.filters.ExceptionFilters
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.FilterMixin
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.filters.TextConsoleBuilderImpl
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Returns the allowlisted console [Filter]s safe for rendering untrusted output coming from an Android device or test process (stack-trace
 * navigation via PSI index + [SafeUrlFilter]).
 *
 * Platform `consoleFilterProvider` extensions are intentionally excluded because host-build filters such as `GradleConsoleFilter` and
 * `JavaDebuggerAttachFilter` perform host filesystem I/O or render outbound debugger-attach inlays on attacker-controlled lines. Exception
 * filters are wrapped to reject any `at ...(...)` line whose `(...)` location contains path separators (`/` or `\`) or a drive-letter `:`
 * before `.kt:`, preventing `KotlinExceptionFilter.parseNativeStackTraceLine` from resolving paths via `StandardFileSystems.local()`.
 */
@JvmOverloads
fun getDeviceConsoleFilters(
  project: Project,
  scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
): List<Filter> = ExceptionFilters.getFilters(project, scope).map(::wrapSafeExceptionFilter) + SafeUrlFilter(project)

/**
 * Creates a [ConsoleViewImpl] for displaying untrusted device/test output, opting out of platform predefined `consoleFilterProvider`
 * extensions and installing only [getDeviceConsoleFilters].
 */
@JvmOverloads
fun createDeviceConsoleView(
  project: Project,
  viewer: Boolean = false,
  scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
): ConsoleViewImpl =
  ConsoleViewImpl(project, scope, viewer, /* usePredefinedMessageFilter= */ false).apply {
    getDeviceConsoleFilters(project, scope).forEach(::addMessageFilter)
  }

/**
 * Creates a [TextConsoleBuilder] for displaying untrusted device/test output, opting out of platform predefined `consoleFilterProvider`
 * extensions and installing only [getDeviceConsoleFilters].
 */
@JvmOverloads
fun createDeviceTextConsoleBuilder(
  project: Project,
  scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
): TextConsoleBuilder =
  TextConsoleBuilderImpl(project, scope).apply {
    setUsePredefinedMessageFilter(false)
    filters(getDeviceConsoleFilters(project, scope))
  }

private fun wrapSafeExceptionFilter(delegate: Filter): Filter =
  if (delegate is FilterMixin) {
    HeavySafeExceptionFilter(delegate, delegate)
  } else {
    SafeExceptionFilter(delegate)
  }

private open class SafeExceptionFilter(private val delegate: Filter) : Filter {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    if (hasUnsafeNativeStackTracePath(line)) {
      return null
    }
    return delegate.applyFilter(line, entireLength)
  }

  override fun isDumbAware(): Boolean = DumbService.isDumbAware(delegate)
}

// Heavy exception filters only resolve frames via PSI/index, so applyHeavyFilter can delegate directly.
private class HeavySafeExceptionFilter(delegate: Filter, mixin: FilterMixin) : SafeExceptionFilter(delegate), FilterMixin by mixin

private fun hasUnsafeNativeStackTracePath(line: String): Boolean {
  val trimmed = line.trim()
  if (!trimmed.startsWith("at") || !trimmed.endsWith(')')) {
    return false
  }
  val start = trimmed.lastIndexOf('(') + 1
  if (start < 1) {
    return false
  }
  val insideParens = trimmed.subSequence(start, trimmed.length - 1)
  if (insideParens.any { it == '/' || it == '\\' }) {
    return true
  }
  val ktColon = trimmed.indexOf(".kt:", start)
  return ktColon != -1 && (start until ktColon).any { trimmed[it] == ':' }
}
