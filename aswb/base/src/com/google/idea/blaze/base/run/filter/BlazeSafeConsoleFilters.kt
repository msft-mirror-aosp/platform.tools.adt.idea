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
@file:JvmName("BlazeSafeConsoleFilters")

package com.google.idea.blaze.base.run.filter

import com.google.common.annotations.VisibleForTesting
import com.google.idea.blaze.base.settings.BazelImportSettingsManager
import com.google.idea.blaze.base.workspace.WorkspaceSwitchManager.getWorkspaceTarget
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.Path

/**
 * Returns Blaze console [Filter]s that are safe to attach to mixed host-build + device-output consoles (such as `BLAZE_TEST` Android
 * instrumentation test consoles):
 * - [SafeTestLogFilter]: links `test.log` paths inside the workspace's `{bazel,blaze}-{testlogs,out}` directories (or their resolved
 *   symlink targets), rejecting UNC and `..` traversal paths, deferring file resolution until click (off-EDT), and continuing filtering so
 *   target labels on the same line are also linked.
 * - [BlazeTargetFilter]: pure regex match at filter time; resolves PSI target labels only on click.
 */
fun getSafeBlazeTestConsoleFilters(project: Project): List<Filter> = listOf(SafeTestLogFilter(project), BlazeTargetFilter())

class SafeTestLogFilter
@VisibleForTesting
constructor(
  private val project: Project,
  private val onNavigate: (Project, String) -> Unit = ::navigateToTestLogOffEdt,
  private val physicalWorkspaceRootProvider: (Project) -> Path? = { it.getWorkspaceTarget() },
  private val nanoTime: () -> Long = System::nanoTime,
) : Filter {

  constructor(project: Project) : this(project, onNavigate = ::navigateToTestLogOffEdt)

  @Volatile private var cachedRealPrefixes: CachedRealPrefixes? = null

  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val range = extractCandidateTestLogRange(line) ?: return null
    val filePath = line.substring(range.first, range.last + 1)
    if (!isAllowedTestLogPath(filePath)) {
      return null
    }
    val offset = entireLength - line.length
    val hyperlink = HyperlinkInfo { proj -> onNavigate(proj, filePath) }
    return Filter.Result(range.first + offset, range.last + 1 + offset, hyperlink).apply {
      nextAction = Filter.NextAction.CONTINUE_FILTERING
    }
  }

  private fun isAllowedTestLogPath(filePath: String): Boolean {
    if (!hasAllowedTestLogStructure(filePath)) {
      return false
    }

    val workspaceRoot = project.getService(BazelImportSettingsManager::class.java)?.workspaceRoot ?: return false
    val workspaceRootPrefix = workspaceRoot.toString().trimEnd('/').takeIf { it.isNotEmpty() && !it.startsWith("//") }
    if (workspaceRootPrefix != null && WORKSPACE_TESTLOG_SUBDIRS.any { filePath.startsWith("$workspaceRootPrefix/$it/") }) {
      return true
    }

    val physicalRoot = physicalWorkspaceRootProvider(project)
    val physicalRootPrefix =
      physicalRoot?.toString()?.trimEnd('/')?.takeIf { it.isNotEmpty() && !it.startsWith("//") && it != workspaceRootPrefix }
    if (physicalRootPrefix != null && WORKSPACE_TESTLOG_SUBDIRS.any { filePath.startsWith("$physicalRootPrefix/$it/") }) {
      return true
    }

    val candidateRoots = buildList {
      if (workspaceRootPrefix != null) add(workspaceRoot)
      if (physicalRoot != null && physicalRootPrefix != null) add(physicalRoot)
    }
    if (candidateRoots.isEmpty()) {
      return false
    }
    val rootPrefixes = listOfNotNull(workspaceRootPrefix, physicalRootPrefix)

    val now = nanoTime()
    val cached = cachedRealPrefixes
    val realSubdirPrefixes =
      if (cached != null && cached.rootPrefixes == rootPrefixes && now - cached.createdAtNanos in 0 until REAL_PATH_CACHE_TTL_NANOS) {
        cached.realSubdirPrefixes
      } else {
        resolveAndCacheRealSubdirPrefixes(candidateRoots, rootPrefixes, now)
      }
    return realSubdirPrefixes.any { filePath.startsWith(it) }
  }

  private fun resolveAndCacheRealSubdirPrefixes(
    candidateRoots: List<Path>,
    rootPrefixes: List<String>,
    nowNanos: Long,
  ): List<String> {
    val prefixes = buildList {
      for (root in candidateRoots) {
        for (subdir in WORKSPACE_TESTLOG_SUBDIRS) {
          ProgressManager.checkCanceled()
          val realSubdir =
            try {
              root.resolve(subdir).toRealPath().toString().trimEnd('/')
            } catch (_: IOException) {
              continue
            } catch (_: SecurityException) {
              continue
            }
          if (realSubdir.isNotEmpty() && !realSubdir.startsWith("//")) {
            add("$realSubdir/")
          }
        }
      }
    }
      .distinct()
    cachedRealPrefixes = CachedRealPrefixes(rootPrefixes, prefixes, nowNanos)
    return prefixes
  }

  private data class CachedRealPrefixes(
    val rootPrefixes: List<String>,
    val realSubdirPrefixes: List<String>,
    val createdAtNanos: Long,
  )

  companion object {
    @VisibleForTesting const val REAL_PATH_CACHE_TTL_NANOS = 5_000_000_000L
    private const val TEST_LOG_SUFFIX = "/test.log"
    private const val SEE_PREFIX = "(see "
    private val TESTLOG_SEGMENT_NAMES = setOf("testlogs", "bazel-testlogs", "blaze-testlogs")
    private val WORKSPACE_TESTLOG_SUBDIRS = listOf("bazel-testlogs", "blaze-testlogs", "bazel-out", "blaze-out")

    @VisibleForTesting
    internal fun extractCandidateTestLogRange(line: String): IntRange? {
      var endExclusive = line.length
      while (endExclusive > 0 && line[endExclusive - 1].isWhitespace()) {
        endExclusive--
      }
      if (endExclusive == 0) {
        return null
      }

      val pathStart: Int
      val pathEndExclusive: Int
      if (
        endExclusive > TEST_LOG_SUFFIX.length + 1 &&
          line[endExclusive - 1] == ')' &&
          line.regionMatches(endExclusive - 1 - TEST_LOG_SUFFIX.length, TEST_LOG_SUFFIX, 0, TEST_LOG_SUFFIX.length)
      ) {
        val seeIndex = line.lastIndexOf(SEE_PREFIX, startIndex = endExclusive - 2)
        if (seeIndex == -1) {
          return null
        }
        pathStart = seeIndex + SEE_PREFIX.length
        pathEndExclusive = endExclusive - 1
      } else if (
        endExclusive > TEST_LOG_SUFFIX.length &&
          line.regionMatches(endExclusive - TEST_LOG_SUFFIX.length, TEST_LOG_SUFFIX, 0, TEST_LOG_SUFFIX.length)
      ) {
        var firstNonWs = 0
        while (firstNonWs < endExclusive && line[firstNonWs].isWhitespace()) {
          firstNonWs++
        }
        pathStart = firstNonWs
        pathEndExclusive = endExclusive
      } else {
        return null
      }

      if (pathStart >= pathEndExclusive || line[pathStart] != '/') {
        return null
      }
      for (i in pathStart until pathEndExclusive) {
        val ch = line[i]
        if (ch == ':' || ch.isWhitespace()) {
          return null
        }
      }
      return pathStart until pathEndExclusive
    }

    private fun hasAllowedTestLogStructure(filePath: String): Boolean {
      if (!filePath.startsWith("/") || filePath.contains("//") || filePath.contains('\\')) {
        return false
      }
      val segments = filePath.substring(1).split('/')
      return segments.size >= 4 &&
        segments.last() == "test.log" &&
        segments.none { it.isEmpty() || it == "." || it == ".." } &&
        (1..segments.size - 3).any { segments[it] in TESTLOG_SEGMENT_NAMES }
    }
  }
}

private fun navigateToTestLogOffEdt(project: Project, filePath: String) {
  if (project.isDisposed) {
    return
  }
  AppExecutorUtil.getAppExecutorService().execute {
    if (project.isDisposed) {
      return@execute
    }
    val vf = FileResolver.resolveToVirtualFile(project, filePath) ?: return@execute
    ApplicationManager.getApplication()
      .invokeLater(
        {
          if (!project.isDisposed) {
            OpenFileHyperlinkInfo(project, vf, /* line= */ 0).navigate(project)
          }
        },
        ModalityState.defaultModalityState(),
        project.disposed,
      )
  }
}
