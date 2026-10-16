/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.qsync

import com.google.common.base.Suppliers
import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.BlazeSubincludeFileResolver
import com.google.idea.blaze.qsync.CachingFileMtimeReader
import com.google.idea.blaze.qsync.ProjectRefresher
import com.google.idea.blaze.qsync.QuerySyncProjectSnapshot
import com.google.idea.blaze.qsync.RefreshParameters
import com.google.idea.blaze.qsync.calculateSubincludeStamp
import com.google.idea.blaze.qsync.project.BuildPackage
import com.google.idea.blaze.qsync.project.PostQuerySyncData
import com.google.idea.blaze.qsync.project.ProjectDefinition
import com.google.idea.blaze.qsync.project.ProjectStructureData
import com.google.idea.blaze.qsync.project.ProjectStructureRoot
import com.google.idea.blaze.qsync.project.QuerySyncLanguage
import com.google.idea.blaze.qsync.query.QueryData
import com.google.idea.blaze.qsync.query.QuerySpec
import com.google.idea.blaze.qsync.query.QuerySummary
import com.google.idea.blaze.qsync.query.QuerySummaryImpl
import com.google.idea.blaze.qsync.query.rulesMapForTests
import com.google.idea.blaze.qsync.query.sourceFilesMapForTests
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Optional
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ProjectQuerierImplTest {
  @get:Rule val tempDir = TemporaryFolder()

  private val defsBzl = Label.of("//defs:defs.bzl")
  private val otherBzl = Label.of("//defs:other.bzl")
  private val pathA = Path.of("pkg/a")
  private val pathB = Path.of("pkg/b")
  private val pathC = Path.of("pkg/c")

  /** An mtime an hour before the test runs, in whole seconds, so that it's hashed as it is. */
  private val oldMtimeMs = wholeSeconds(System.currentTimeMillis() - 3_600_000L)

  private val projectDefinition =
    ProjectDefinition(
      projectIncludes = setOf(Path.of("pkg")),
      projectExcludes = emptySet(),
      deriveTargetsFromDirectories = false,
      targetPatterns = emptyList(),
      isAndroidWorkspace = false,
      languageClasses = setOf(QuerySyncLanguage.JVM),
      testSources = emptySet(),
      systemExcludes = emptySet(),
      supportsNestedWorkspaces = true,
    )

  /** What each package's BUILD file loads: A loads defs.bzl, B loads defs.bzl and other.bzl, and C loads nothing. */
  private val subincludes = mapOf(pathA to listOf(defsBzl), pathB to listOf(defsBzl, otherBzl), pathC to listOf())

  /** The packages that each query included. */
  private val queries = mutableListOf<Set<Path>>()

  /** A fake query, which returns each queried package with its [subincludes]. */
  private val queryRunner = QueryRunner { spec, _ ->
    val packages = Regex("//([^:]+):\\*").findAll(spec.queryExpression.get()).map { Path.of(it.groupValues[1]) }.toSet()
    queries.add(packages)
    val result = QuerySummaryImpl.newBuilder()
    for (path in packages) {
      result.putSourceFiles(QueryData.SourceFile(buildFile(path), subincludes.getValue(path)))
    }
    result.build()
  }

  private fun buildFile(path: Path): Label = Label.of("//$path:BUILD")

  private fun projectStructure(stampOfA: Long = 1L): ProjectStructureData =
    ProjectStructureData.create(
      listOf(
        ProjectStructureRoot(
          Path.of("pkg"),
          mapOf(
            pathA to BuildPackage(pathA, emptyList(), stamp = stampOfA),
            pathB to BuildPackage(pathB, emptyList(), stamp = 1L),
            pathC to BuildPackage(pathC, emptyList(), stamp = 1L),
          ),
        )
      ),
      setOf(QuerySyncLanguage.JVM),
    )

  /** Creates [label] if it doesn't exist, and sets its mtime. */
  private fun setMtime(label: Label, mtimeMs: Long) {
    val path = tempDir.root.toPath().resolve(label.toFilePath())
    if (!Files.exists(path)) {
      Files.createDirectories(path.parent)
      Files.writeString(path, "")
    }
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMs))
  }

  /** Rounds [ms] down to a whole second, so that file systems with coarse mtimes store it exactly. */
  private fun wholeSeconds(ms: Long): Long = ms / 1_000L * 1_000L

  /** Returns a reader for a sync that starts at [syncStartMs]. */
  private fun readerStartingAt(syncStartMs: Long) =
    CachingFileMtimeReader(BlazeSubincludeFileResolver(tempDir.root.toPath()), syncStartMs = syncStartMs)

  /** Runs a sync that requests [requested], after the sync that produced [lastQuery]. By default, the sync starts when this is called. */
  private fun sync(
    lastQuery: PostQuerySyncData,
    requested: Set<Path>,
    projectStructure: ProjectStructureData = projectStructure(),
    mtimeReader: CachingFileMtimeReader = CachingFileMtimeReader(BlazeSubincludeFileResolver(tempDir.root.toPath())),
    queryRunner: QueryRunner = this.queryRunner,
  ): PostQuerySyncData {
    val workspaceRoot = tempDir.root.toPath()
    val refresher =
      ProjectRefresher(workspaceRoot, QuerySpec.QueryStrategy.PLAIN, Suppliers.ofInstance(Optional.empty<QuerySyncProjectSnapshot>()))
    val parameters =
      RefreshParameters(lastQuery, projectDefinition, projectStructure, requested, requireFullSync = { false }, mtimeReader = mtimeReader)
    return ProjectQuerierImpl(queryRunner, refresher).update(parameters, BlazeContext.create())
  }

  /** The first sync, which queries every package. The .bzl files were last changed an hour ago. */
  private fun firstSync(): PostQuerySyncData {
    setMtime(defsBzl, oldMtimeMs)
    setMtime(otherBzl, oldMtimeMs)
    val result = sync(PostQuerySyncData.EMPTY, setOf(pathA, pathB, pathC))
    assertThat(queries).containsExactly(setOf(pathA, pathB, pathC))
    queries.clear()
    return result
  }

  private fun PostQuerySyncData.buildPackage(path: Path): QuerySummary.BuildPackage = querySummary().getBuildPackage(buildFile(path))!!

  @Test
  fun update_stampsQueriedPackages() {
    val result = firstSync()

    val a = result.buildPackage(pathA)
    val b = result.buildPackage(pathB)
    assertThat(a.subincludeStamp).isNotEqualTo(0L)
    assertThat(a.subincludeStamp).isEqualTo(a.calculateSubincludeStamp { oldMtimeMs })
    assertThat(b.subincludeStamp).isEqualTo(b.calculateSubincludeStamp { oldMtimeMs })
    assertThat(result.buildPackage(pathC).subincludeStamp).isEqualTo(0L)
    assertThat(a.stamp).isEqualTo(1L)
  }

  @Test
  fun update_bzlChangedRightBeforeSync_stampsSyncStartTime() {
    val syncStartMs = oldMtimeMs + 60_000L
    // defs.bzl changed less than 2 s before the sync started.
    setMtime(defsBzl, syncStartMs - 1_000L)
    setMtime(otherBzl, oldMtimeMs)

    val result = sync(PostQuerySyncData.EMPTY, setOf(pathB), mtimeReader = readerStartingAt(syncStartMs))

    // So the sync start time is hashed for it.
    val b = result.buildPackage(pathB)
    assertThat(b.subincludeStamp).isEqualTo(b.calculateSubincludeStamp(mapOf(defsBzl to syncStartMs, otherBzl to oldMtimeMs)::get))
  }

  @Test
  fun update_keepsTheRestOfTheQueryResult() {
    setMtime(defsBzl, oldMtimeMs)
    val queryResult =
      QuerySummaryImpl.newBuilder()
        .putRules(
          QueryData.Rule.createForTests(Label.of("//pkg/a:lib"))
            .copy(ruleClass = "java_library", sources = listOf(Label.of("//pkg/a:A.java")))
        )
        .putSourceFiles(QueryData.SourceFile(Label.of("//pkg/a:A.java"), listOf()))
        .putSourceFiles(QueryData.SourceFile(buildFile(pathA), listOf(defsBzl)))
        .putPackagesWithErrors(pathA)
        .build()

    val summary = sync(PostQuerySyncData.EMPTY, setOf(pathA), queryRunner = QueryRunner { _, _ -> queryResult }).querySummary()

    assertThat(summary.rulesMapForTests).isEqualTo(queryResult.rulesMapForTests)
    assertThat(summary.sourceFilesMapForTests).isEqualTo(queryResult.sourceFilesMapForTests)
    assertThat(summary.packagesWithErrors).containsExactly(pathA)
  }

  @Test
  fun update_nothingChanged_noQuery() {
    val first = firstSync()

    val second = sync(first, setOf(pathA, pathB, pathC))

    assertThat(queries).isEmpty()
    assertThat(second.buildPackage(pathA).subincludeStamp).isEqualTo(first.buildPackage(pathA).subincludeStamp)
    assertThat(second.buildPackage(pathB).subincludeStamp).isEqualTo(first.buildPackage(pathB).subincludeStamp)
  }

  @Test
  fun update_buildFileChanged_restampsOnlyQueriedPackages() {
    val first = firstSync()
    setMtime(defsBzl, oldMtimeMs + 60_000L)

    // A's BUILD file changed, so A is queried.
    val second = sync(first, setOf(pathA), projectStructure(stampOfA = 2L))

    assertThat(queries).containsExactly(setOf(pathA))
    val a = second.buildPackage(pathA)
    assertThat(a.subincludeStamp).isEqualTo(a.calculateSubincludeStamp { oldMtimeMs + 60_000L })
    assertThat(a.stamp).isEqualTo(2L)
    // B also loads defs.bzl, but it wasn't queried, so it keeps the stamp from the first sync.
    assertThat(second.buildPackage(pathB).subincludeStamp).isEqualTo(first.buildPackage(pathB).subincludeStamp)
  }
}
