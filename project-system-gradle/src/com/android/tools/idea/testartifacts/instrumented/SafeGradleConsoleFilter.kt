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
package com.android.tools.idea.testartifacts.instrumented

import com.android.tools.idea.gradle.project.build.output.GenericFileFilter
import com.android.tools.idea.gradle.project.build.output.getCachedWindowsDriveRoots
import com.android.tools.idea.gradle.project.build.output.hasUnsafeWindowsVfsLookup
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.gradle.execution.GradleConsoleFilter

/**
 * Returns Gradle console [Filter]s safe for mixed Gradle task + device output streams (such as `AndroidTestSuiteView`'s raw output
 * console), linking only non-UNC paths already present in VFS cache.
 */
@JvmOverloads
fun createGradleRawOutputFilters(
  project: Project,
  localFileSystem: LocalFileSystem = LocalFileSystem.getInstance(),
): List<Filter> =
  listOf(
    GenericFileFilter(project, localFileSystem),
    SafeGradleConsoleFilter(project, localFileSystem),
  )

/**
 * Wrapper around [GradleConsoleFilter] that guards against UNC/network root lookups and uncached host path stats.
 *
 * [GradleConsoleFilter] eagerly calls `StandardFileSystems.local().findFileByPath(...)` on paths between `Build file '` and `' line: `,
 * triggering synchronous SMB/UNC root lookups on Windows. This wrapper extracts the candidate path with the same prefix/suffix rules,
 * rejects UNC, relative/`..`, Windows 8.3 short-name, and uncached Windows drive-root paths before touching VFS, and requires the file to
 * already be cached in VFS via [LocalFileSystem.findFileByPathIfCached] before delegating to [GradleConsoleFilter].
 */
class SafeGradleConsoleFilter
@JvmOverloads
constructor(
  project: Project,
  private val localFileSystem: LocalFileSystem = LocalFileSystem.getInstance(),
  private val delegate: Filter = GradleConsoleFilter(project),
  private val isWindows: Boolean = SystemInfo.isWindows,
  private val cachedWindowsDriveRootsProvider: () -> Set<Char> = { getCachedWindowsDriveRoots(localFileSystem) },
) : Filter {

  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val fileName = extractCandidateFileName(line) ?: return null
    if (fileName.startsWith("\\\\") || fileName.startsWith("//")) {
      return null
    }
    if (!isWindows && fileName.contains('\\')) {
      return null
    }
    val normalizedPath = if (isWindows) fileName.replace('\\', '/') else fileName
    if (!isSafeLocalAbsolutePath(normalizedPath, isWindows)) {
      return null
    }
    if (hasUnsafeWindowsVfsLookup(normalizedPath, isWindows, cachedWindowsDriveRootsProvider)) {
      return null
    }
    if (localFileSystem.findFileByPathIfCached(normalizedPath) == null) {
      return null
    }
    return delegate.applyFilter(line, entireLength)
  }

  companion object {
    // Matches the exact prefix/suffix table and scan order of org.jetbrains.plugins.gradle.execution.GradleConsoleFilter.
    private val PREFIX_SUFFIX_PAIRS =
      listOf(
        "Build file '" to "' line: ",
        "build file '" to "': ",
        "Settings file '" to "' line: ",
        "settings file '" to "': ",
        "Initialization script '" to "' line: ",
        "Script '" to "' line: ",
      )

    internal fun extractCandidateFileName(line: String): String? {
      var matchedPrefix: String? = null
      var matchedSuffix: String? = null
      var prefixIndex = -1
      for ((prefix, suffix) in PREFIX_SUFFIX_PAIRS) {
        val index = line.indexOf(prefix)
        if (index != -1) {
          matchedPrefix = prefix
          matchedSuffix = suffix
          prefixIndex = index
          break
        }
      }
      if (matchedPrefix == null || matchedSuffix == null) {
        return null
      }
      val afterPrefix = line.substring(prefixIndex + matchedPrefix.length)
      val suffixIndex = afterPrefix.indexOf(matchedSuffix)
      if (suffixIndex <= 0) {
        return null
      }
      return afterPrefix.substring(0, suffixIndex)
    }

    internal fun isSafeLocalAbsolutePath(normalizedPath: String, isWindows: Boolean = SystemInfo.isWindows): Boolean {
      if (normalizedPath.startsWith("//")) {
        return false
      }
      if (isWindows) {
        val isWindowsDriveRoot =
          normalizedPath.length >= 3 &&
            normalizedPath[0].uppercaseChar() in 'A'..'Z' &&
            normalizedPath[1] == ':' &&
            normalizedPath[2] == '/'
        if (!isWindowsDriveRoot) {
          return false
        }
      } else if (!normalizedPath.startsWith("/")) {
        return false
      }
      val segments = normalizedPath.split('/')
      return segments.none { it == ".." }
    }
  }
}
