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
package com.android.tools.idea.console

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.UrlFilter
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.VisibleForTesting

/** A [UrlFilter] that ignores `file:` URLs targeting UNC or remote-host paths. */
class SafeUrlFilter @JvmOverloads constructor(project: Project? = null) : UrlFilter(project) {
  override fun buildHyperlinkInfo(url: String): HyperlinkInfo {
    return when (url.isUncUrl()) {
      true -> NopHyperlinkInfo
      false -> super.buildHyperlinkInfo(url)
    }
  }

  @VisibleForTesting
  internal object NopHyperlinkInfo : HyperlinkInfo {
    override fun navigate(project: Project) {}
  }
}

private fun String.isUncUrl(): Boolean {
  if (!startsWith("file:", ignoreCase = true)) return false
  val raw = substring(5)
  // file://host/... (any authority, incl. localhost) is remote.
  if (raw.startsWith("//") && !raw.startsWith("///")) return true
  // Mirror UrlFilter: "file:///x" -> "/x", "file:x" -> "x"; then decode separators.
  val path =
    (if (raw.startsWith("///")) raw.substring(2) else raw).replace("%2f", "/", ignoreCase = true).replace("%5c", "\\", ignoreCase = true)
  val leading = path.takeWhile { it == '/' || it == '\\' }
  return '\\' in leading || leading.length >= 2
}
