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
package com.google.idea.blaze.qsync

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.query.QueryData
import com.google.idea.blaze.qsync.query.QuerySummary
import com.google.idea.blaze.qsync.query.QuerySummaryImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CachingFileMtimeReaderTest {
  @get:Rule val tempDir = TemporaryFolder()

  private val defsBzl = Label.of("//defs:defs.bzl")
  private val utilBzl = Label.of("//defs:util.bzl")
  private val externalBzl = Label.of("@rules_java//java:defs.bzl")

  /** A sync start time to give readers, long before the tests run, in whole seconds. */
  private val givenSyncStartMs = 1_700_000_000_000L

  /** The earliest time at which the sync of the reader that [newReader] created last can have started. */
  private var earliestSyncStartMs = 0L

  /** The latest time at which the sync of the reader that [newReader] created last can have started. */
  private var latestSyncStartMs = 0L

  /** Tracks the files in the main repository, under the temporary directory. */
  private val mainRepositoryFiles by lazy { BlazeSubincludeFileResolver(tempDir.root.toPath()) }

  /** Returns package //pkg, whose BUILD file loads [subincludes]. */
  private fun packageLoading(vararg subincludes: Label): QuerySummary.BuildPackage =
    QuerySummaryImpl.newBuilder()
      .putSourceFiles(QueryData.SourceFile(Label.of("//pkg:BUILD"), subincludes.toList()))
      .build()
      .getBuildPackage(Label.of("//pkg:BUILD"))!!

  /** Returns a function that returns the given value for each listed label, and null for any other label. */
  private fun mtimes(vararg mtimes: Pair<Label, Long>): (Label) -> Long? {
    val map = mapOf(*mtimes)
    return { map[it] }
  }

  private fun writeFile(label: Label, mtimeMs: Long): Path {
    val path = tempDir.root.toPath().resolve(label.toFilePath())
    Files.createDirectories(path.parent)
    Files.writeString(path, "")
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMs))
    return path
  }

  /**
   * Returns a new reader of the main repository, whose sync starts now. It reads each file when it's first asked for its value, so tests
   * write files after creating it, with mtimes relative to [earliestSyncStartMs] and [latestSyncStartMs].
   */
  private fun newReader(): CachingFileMtimeReader {
    earliestSyncStartMs = System.currentTimeMillis()
    val reader = CachingFileMtimeReader(mainRepositoryFiles)
    latestSyncStartMs = System.currentTimeMillis()
    return reader
  }

  /** Rounds [ms] down to a whole second, so that file systems with coarse mtimes store it exactly. */
  private fun wholeSeconds(ms: Long): Long = ms / 1_000L * 1_000L

  /** Asserts that [value] can be the sync start time of the reader that [newReader] created last. */
  private fun assertIsSyncStartTime(value: Long?) {
    assertThat(value).isAtLeast(earliestSyncStartMs)
    assertThat(value).isAtMost(latestSyncStartMs)
  }

  @Test
  fun calculateSubincludeStamp_noSubincludes_isZero() {
    assertThat(packageLoading().calculateSubincludeStamp { error("Unexpected read of $it") }).isEqualTo(0L)
  }

  @Test
  fun calculateSubincludeStamp_nothingStamped_isZero() {
    assertThat(packageLoading(defsBzl, externalBzl).calculateSubincludeStamp { null }).isEqualTo(0L)
  }

  @Test
  fun calculateSubincludeStamp_skipsUnstampedSubincludes() {
    val defsOnly = packageLoading(defsBzl).calculateSubincludeStamp(mtimes(defsBzl to 50L))

    assertThat(defsOnly).isNotEqualTo(0L)
    assertThat(packageLoading(defsBzl, externalBzl).calculateSubincludeStamp(mtimes(defsBzl to 50L))).isEqualTo(defsOnly)
  }

  @Test
  fun calculateSubincludeStamp_sameMtimes_sameStamp() {
    val pkg = packageLoading(defsBzl, utilBzl)

    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 70L)))
      .isEqualTo(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 70L)))
  }

  @Test
  fun calculateSubincludeStamp_anyMtimeChange_changesStamp() {
    val pkg = packageLoading(defsBzl, utilBzl)
    val recorded = pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 70L))

    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 150L))).isNotEqualTo(recorded)
    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 10L))).isNotEqualTo(recorded)
    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to MISSING_MTIME))).isNotEqualTo(recorded)
    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 51L, utilBzl to 70L))).isNotEqualTo(recorded)
  }

  @Test
  fun calculateSubincludeStamp_orderDoesNotMatter() {
    val mtimes = mtimes(defsBzl to 50L, utilBzl to 70L)

    assertThat(packageLoading(utilBzl, defsBzl).calculateSubincludeStamp(mtimes))
      .isEqualTo(packageLoading(defsBzl, utilBzl).calculateSubincludeStamp(mtimes))
  }

  @Test
  fun calculateSubincludeStamp_swappedMtimes_changesStamp() {
    val pkg = packageLoading(defsBzl, utilBzl)

    assertThat(pkg.calculateSubincludeStamp(mtimes(defsBzl to 70L, utilBzl to 50L)))
      .isNotEqualTo(pkg.calculateSubincludeStamp(mtimes(defsBzl to 50L, utilBzl to 70L)))
  }

  @Test
  fun getMtimeOrSyncStartTime_untrackedFile_isNull() {
    val reader = CachingFileMtimeReader({ null }, givenSyncStartMs)
    writeFile(defsBzl, mtimeMs = givenSyncStartMs - 60_000L)

    assertThat(reader.getMtimeOrSyncStartTime(defsBzl)).isNull()
  }

  @Test
  fun getMtimeOrSyncStartTime_missingFile_isMissing() {
    assertThat(newReader().getMtimeOrSyncStartTime(defsBzl)).isEqualTo(MISSING_MTIME)
  }

  @Test
  fun getMtimeOrSyncStartTime_fileChangedBeforeMargin_isItsMtime() {
    val reader = newReader()
    val mtimeMs = wholeSeconds(earliestSyncStartMs) - MTIME_MARGIN_MS - 1_000L
    writeFile(defsBzl, mtimeMs)

    assertThat(reader.getMtimeOrSyncStartTime(defsBzl)).isEqualTo(mtimeMs)
  }

  @Test
  fun getMtimeOrSyncStartTime_fileChangedWithinMarginOrLater_isSyncStartTime() {
    val reader = newReader()
    // Less than MTIME_MARGIN_MS before the sync start, and after it.
    writeFile(defsBzl, mtimeMs = wholeSeconds(latestSyncStartMs) - 1_000L)
    writeFile(utilBzl, mtimeMs = wholeSeconds(latestSyncStartMs) + 5_000L)

    val syncStartMs = reader.getMtimeOrSyncStartTime(defsBzl)
    assertIsSyncStartTime(syncStartMs)
    assertThat(reader.getMtimeOrSyncStartTime(utilBzl)).isEqualTo(syncStartMs)
  }

  @Test
  fun getMtimeOrSyncStartTime_givenSyncStart_usesIt() {
    val reader = CachingFileMtimeReader(mainRepositoryFiles, syncStartMs = givenSyncStartMs)
    writeFile(defsBzl, mtimeMs = givenSyncStartMs - MTIME_MARGIN_MS - 1_000L)
    writeFile(utilBzl, mtimeMs = givenSyncStartMs - 1_000L)

    assertThat(reader.getMtimeOrSyncStartTime(defsBzl)).isEqualTo(givenSyncStartMs - MTIME_MARGIN_MS - 1_000L)
    assertThat(reader.getMtimeOrSyncStartTime(utilBzl)).isEqualTo(givenSyncStartMs)
  }

  @Test
  fun getMtimeOrSyncStartTime_readsEachFileOnce() {
    val reader = newReader()
    val mtimeMs = wholeSeconds(earliestSyncStartMs) - 10_000L
    val defsPath = writeFile(defsBzl, mtimeMs)
    val utilPath = writeFile(utilBzl, mtimeMs)
    assertThat(reader.getMtimeOrSyncStartTime(defsBzl)).isEqualTo(mtimeMs)
    assertThat(reader.getMtimeOrSyncStartTime(utilBzl)).isEqualTo(mtimeMs)

    Files.setLastModifiedTime(defsPath, FileTime.fromMillis(wholeSeconds(latestSyncStartMs) + 1_000L))
    Files.delete(utilPath)

    assertThat(reader.getMtimeOrSyncStartTime(defsBzl)).isEqualTo(mtimeMs)
    assertThat(reader.getMtimeOrSyncStartTime(utilBzl)).isEqualTo(mtimeMs)
  }

  @Test
  fun subincludeStampOf_hashesTheReadersValues() {
    val reader = CachingFileMtimeReader(mainRepositoryFiles, syncStartMs = givenSyncStartMs)
    writeFile(defsBzl, mtimeMs = givenSyncStartMs - 60_000L)
    writeFile(utilBzl, mtimeMs = givenSyncStartMs - 1_000L)
    val pkg = packageLoading(defsBzl, utilBzl, externalBzl)

    // util.bzl changed less than MTIME_MARGIN_MS before the sync started, so the sync start time is hashed for it. The main repository
    // resolver doesn't track the external file.
    assertThat(reader.subincludeStampOf(pkg))
      .isEqualTo(pkg.calculateSubincludeStamp(mtimes(defsBzl to givenSyncStartMs - 60_000L, utilBzl to givenSyncStartMs)))
  }

  @Test
  fun subincludeStampOf_isTheSameEachTimeInASync() {
    val reader = CachingFileMtimeReader(mainRepositoryFiles, syncStartMs = givenSyncStartMs)
    val defsPath = writeFile(defsBzl, mtimeMs = givenSyncStartMs - 60_000L)
    val pkg = packageLoading(defsBzl)
    val stamp = reader.subincludeStampOf(pkg)

    Files.setLastModifiedTime(defsPath, FileTime.fromMillis(givenSyncStartMs - 30_000L))

    assertThat(reader.subincludeStampOf(pkg)).isEqualTo(stamp)
  }
}
