/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.testing

import com.android.testutils.TestUtils
import com.android.testutils.TestUtils.resolveWorkspacePath
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil.sanitizeFileName
import com.intellij.testFramework.UsefulTestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import org.jetbrains.annotations.VisibleForTesting

/**
 * See implementing classes for usage examples.
 *
 * NOTE: It you made changes to sync or the test projects which make Snapshot tests fail in an expected way, you can re-run the tests: (1)
 * from the IDE with -DUPDATE_TEST_SNAPSHOTS to update the files; or (2) from the command-line using Bazel with:
 * ```
 * bazel test [target]  \
 * --jvmopt="-DUPDATE_TEST_SNAPSHOTS=$(bazel info workspace)" \
 * --sandbox_writable_path=$(bazel info workspace) \
 * --test_strategy=standalone \
 * --nocache_test_results \
 * --test_timeout=6000
 * ```
 */
interface SnapshotComparisonTest {
  /** The name of the property which should be set to activate "update snapshots" test execution mode. */
  val updateSnapshotsJvmProperty: String
    get() = "UPDATE_TEST_SNAPSHOTS"

  /** A testData subdirectory name where to look for snapshots. */
  val snapshotDirectoryWorkspaceRelativePath: String

  /** Optional KGP layer snapshot suffix (`KGP_MIN`, `KGP_DEFAULT`, or `KGP_LATEST`) scoped to this snapshot context. */
  val kgpSnapshotSuffix: SnapshotSuffix?
    get() = null

  /** The list of file name suffixes applicable to the currently running test. */
  val snapshotSuffixes: List<String>
    get() = SnapshotSuffix.snapshotSuffixesApplicable(kgpSnapshotSuffix)

  /** Assumed to be matched by [UsefulTestCase.getName]. */
  fun getName(): String
}

/** Snapshot filename suffixes ordered by matching priority, where [BASELINE] must be last. */
enum class SnapshotSuffix(val suffix: String) {
  KGP_MIN_PHASED("_kgp_min_phased") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_MIN

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean =
      kgpSnapshotSuffix == KGP_MIN && StudioFlags.PHASED_SYNC_ENABLED.get()

    override fun fallbackSuffixes(): List<SnapshotSuffix> = listOf(KGP_MIN, PHASED, BASELINE)
  },
  KGP_MIN("_kgp_min") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_MIN

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean = kgpSnapshotSuffix == this
  },
  KGP_DEFAULT_PHASED("_kgp_default_phased") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_DEFAULT

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean =
      kgpSnapshotSuffix == KGP_DEFAULT && StudioFlags.PHASED_SYNC_ENABLED.get()

    override fun fallbackSuffixes(): List<SnapshotSuffix> = listOf(KGP_DEFAULT, PHASED, BASELINE)
  },
  KGP_DEFAULT("_kgp_default") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_DEFAULT

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean = kgpSnapshotSuffix == this
  },
  KGP_LATEST_PHASED("_kgp_latest_phased") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_LATEST

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean =
      kgpSnapshotSuffix == KGP_LATEST && StudioFlags.PHASED_SYNC_ENABLED.get()

    override fun fallbackSuffixes(): List<SnapshotSuffix> = listOf(KGP_LATEST, PHASED, BASELINE)
  },
  KGP_LATEST("_kgp_latest") {
    override val ownerKgpSuffix: SnapshotSuffix
      get() = KGP_LATEST

    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean = kgpSnapshotSuffix == this
  },
  PHASED("_phased") {
    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean = StudioFlags.PHASED_SYNC_ENABLED.get()
  },
  BASELINE("") {
    override fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix?): Boolean = true

    override fun fallbackSuffixes(): List<SnapshotSuffix> = emptyList()
  };

  open val ownerKgpSuffix: SnapshotSuffix?
    get() = null

  abstract fun isEnabled(kgpSnapshotSuffix: SnapshotSuffix? = null): Boolean

  open fun fallbackSuffixes(): List<SnapshotSuffix> = listOf(BASELINE)

  companion object {
    fun nonBaselineSnapshots() = entries.filter { it != BASELINE }

    fun snapshotSuffixesApplicable(kgpSnapshotSuffix: SnapshotSuffix? = null): List<String> {
      require(
        kgpSnapshotSuffix == null || kgpSnapshotSuffix == KGP_MIN || kgpSnapshotSuffix == KGP_DEFAULT || kgpSnapshotSuffix == KGP_LATEST
      ) {
        "Only KGP_MIN, KGP_DEFAULT, KGP_LATEST, or null are valid KGP snapshot suffixes, got $kgpSnapshotSuffix"
      }
      return entries.filter { it.isEnabled(kgpSnapshotSuffix) }.map { it.suffix }
    }
  }
}

private val testLogsPath: String?
  get() =
    System.getenv("TEST_TARGET")
      ?.takeIf { it.isNotEmpty() }
      ?.removePrefix("//")
      ?.replace(":", "/")
      ?.let { targetPath ->
        targetPath +
          (System.getenv("TEST_SHARD_INDEX")
            ?.takeIf { it.isNotEmpty() }
            ?.let { shard -> "/shard_${shard}_of_${System.getenv("TEST_TOTAL_SHARDS")}" } ?: "")
      }

private fun SnapshotComparisonTest.assertInSnapshotTextContext() =
  Truth.assert_()
    .withMessage(
      """
    |There has been a change to the contents of snapshot test ${getName()}.
    |
    |If that change is intentional, update the expectation files.
    |
    |${updateSnapshotGuidelinesText()}
  """
        .trimMargin()
    )

private fun SnapshotComparisonTest.assertDuplicatedSnapshotsTextContext() =
  Truth.assert_()
    .withMessage(
      """
    |There are duplicated snapshot files to their baseline content ${getName()}.
    |
    |${updateSnapshotGuidelinesText()}
  """
        .trimMargin()
    )

fun SnapshotComparisonTest.assertIsEqualToSnapshot(text: String, snapshotTestSuffix: String = "") {
  val (fullSnapshotName, expectedText) = getAndMaybeUpdateSnapshot(snapshotTestSuffix, text)
  assertInSnapshotTextContext().that(text).named("Snapshot comparison for $fullSnapshotName").isEqualTo(expectedText)
  assertNoDuplicatedSnapshots(snapshotTestSuffix)
}

fun SnapshotComparisonTest.assertIsEqualToUpdatedSnapshot(
  text: String,
  snapshotTestSuffix: String = "",
  snapshotOnSaveUpdater: (String) -> String,
  snapshotOnCompareUpdater: (String) -> String,
) {
  val (fullSnapshotName, expectedText) =
    getAndMaybeUpdateSnapshot(
      snapshotTestSuffix,
      text,
      snapshotOnSaveUpdater = snapshotOnSaveUpdater,
      snapshotOnCompareUpdater = snapshotOnCompareUpdater,
    )
  assertInSnapshotTextContext()
    .that(text)
    .named("Snapshot comparison for $fullSnapshotName")
    .isEqualTo(snapshotOnCompareUpdater(expectedText))
  assertNoDuplicatedSnapshots(snapshotTestSuffix, snapshotOnCompareUpdater)
}

fun SnapshotComparisonTest.assertNoDuplicatedSnapshots(
  snapshotTestSuffix: String = "",
  snapshotOnCompareUpdater: (String) -> String = { it },
) {
  val sanitizedTestName = sanitizeFileName(UsefulTestCase.getTestName(getName(), true))
  val duplicates = mutableListOf<File>()
  val isUpdateMode = System.getProperty(updateSnapshotsJvmProperty) != null
  val activeKgpSuffix = kgpSnapshotSuffix

  val baseline = getCandidateSnapshotFile(sanitizedTestName, "${SnapshotSuffix.BASELINE.suffix}$snapshotTestSuffix")
  if (isUpdateMode && activeKgpSuffix != null && !baseline.exists()) return
  val baselineContent = baseline.readText()
  SnapshotSuffix.nonBaselineSnapshots().forEach { suffix ->
    val snapshotFile = getCandidateSnapshotFile(sanitizedTestName, "${suffix.suffix}$snapshotTestSuffix")
    if (snapshotFile.exists()) {
      val fallbackEntry =
        suffix
          .fallbackSuffixes()
          .map { it to getCandidateSnapshotFile(sanitizedTestName, "${it.suffix}$snapshotTestSuffix") }
          .firstOrNull { (_, file) -> file.exists() }
      val fallbackSuffix = fallbackEntry?.first ?: SnapshotSuffix.BASELINE
      val fallbackContent = fallbackEntry?.second?.readText() ?: baselineContent
      val snapshotContent = snapshotFile.readText()
      val isRawDuplicate = snapshotContent == fallbackContent
      val isLayerTokenDuplicate =
        activeKgpSuffix != null &&
          suffix.ownerKgpSuffix == activeKgpSuffix &&
          fallbackSuffix.ownerKgpSuffix != activeKgpSuffix &&
          snapshotContent.trimIndent() == snapshotOnCompareUpdater(fallbackContent.trimIndent())
      if (isRawDuplicate || isLayerTokenDuplicate) {
        duplicates.add(snapshotFile)
      }
    }
  }

  if (duplicates.isNotEmpty()) {
    if (isUpdateMode) {
      duplicates.forEach {
        println("Delete duplicated snapshot file: ${it.absolutePath}")
        it.delete()
      }
      return
    }
    assertDuplicatedSnapshotsTextContext().that(duplicates).named("Duplicated snapshots of ${baseline.name}").isEmpty()
  }
}

fun SnapshotComparisonTest.assertAreEqualToSnapshots(vararg checks: Pair<String, String>) {
  val (actual, expected) =
    checks
      .map { (actual, suffix) ->
        val (fullName, expected) = getAndMaybeUpdateSnapshot(suffix, actual)
        val header = "\n####################### ${fullName} #######################\n"
        header + actual to header + expected
      }
      .unzip()
      .let { it.first.joinToString(separator = "\n") to it.second.joinToString(separator = "\n") }

  assertInSnapshotTextContext()
    .that(actual)
    .named("Snapshot comparisons for ${checks.joinToString(", ") { it.second }}")
    .isEqualTo(expected)
}

fun SnapshotComparisonTest.getAndMaybeUpdateSnapshot(
  snapshotTestSuffix: String,
  text: String,
  doNotUpdate: Boolean = false,
  snapshotOnSaveUpdater: (String) -> String = { it },
  snapshotOnCompareUpdater: (String) -> String = { it },
): Pair<String, String> {
  val sanitizedTestName = sanitizeFileName(UsefulTestCase.getTestName(getName(), true))
  val (expectedText, snapshotFile) = getExpectedTextAndFileFor(sanitizedTestName, snapshotTestSuffix)

  if (doNotUpdate) {
    return snapshotFile.name to expectedText
  }

  val textToStore = snapshotOnSaveUpdater(text)
  val effectiveExpectedText = snapshotOnCompareUpdater(expectedText)

  if (System.getProperty(updateSnapshotsJvmProperty) != null) {
    val activeKgpSuffix = kgpSnapshotSuffix
    if (activeKgpSuffix == null) {
      if (!snapshotFile.isFile || effectiveExpectedText != text) {
        println("Writing to: ${snapshotFile.absolutePath}")
        snapshotFile.parentFile?.mkdirs()
        snapshotFile.writeText(textToStore)
        return snapshotFile.name to textToStore.trimIndent()
      }
      return snapshotFile.name to expectedText
    }

    val (targetFile, fallbackEntry) = resolveKgpLayerUpdateTarget(sanitizedTestName, snapshotTestSuffix, activeKgpSuffix)
    if (fallbackEntry != null) {
      val (fallbackSuffix, fallbackFile) = fallbackEntry
      val rawFallbackText = fallbackFile.readText().trimIndent()
      // A fallback owned by this layer is in stored form, as is the text to store. Any other fallback is first rewritten to the form of
      // the actual text.
      val matchesFallback =
        if (fallbackSuffix.ownerKgpSuffix == activeKgpSuffix) {
          rawFallbackText == textToStore.trimIndent()
        } else {
          snapshotOnCompareUpdater(rawFallbackText) == text
        }
      if (matchesFallback) {
        if (targetFile != fallbackFile && targetFile.exists()) {
          println("Delete duplicated snapshot file: ${targetFile.absolutePath}")
          targetFile.delete()
        }
        return fallbackFile.name to rawFallbackText
      }
    }
    println("Writing to: ${targetFile.absolutePath}")
    targetFile.parentFile?.mkdirs()
    targetFile.writeText(textToStore)
    return targetFile.name to textToStore.trimIndent()
  } else if (TestUtils.runningFromBazel()) {
    // Populate additional test output if the file needs updating
    if (!snapshotFile.isFile || effectiveExpectedText != text) {
      val outputTarget = resolveBazelOutputSnapshotFile(sanitizedTestName, snapshotTestSuffix, snapshotFile)
      val workspaceRelativePath = TestUtils.getWorkspaceRoot().relativize(outputTarget.toPath())
      println("Writing updated snapshot file to bazel additional test output.\n" + "    $workspaceRelativePath")
      TestUtils.getTestOutputDir().resolve(workspaceRelativePath).run {
        Files.createDirectories(parent)
        writeText(textToStore)
      }
    }
  }
  return snapshotFile.name to expectedText
}

/**
 * Selects the destination path inside Bazel's `outputs.zip` when a snapshot comparison differs.
 *
 * When no KGP layer suffix is active (`kgpSnapshotSuffix == null`), returns [snapshotFile] (the first existing candidate, else baseline).
 * When a KGP layer suffix is active, returns the layer file that `-DUPDATE_TEST_SNAPSHOTS` would write: the layer's `_phased` file when
 * Phased Sync is enabled and that file or the baseline `_phased` file exists, else the layer's non-phased file.
 */
@VisibleForTesting
fun SnapshotComparisonTest.resolveBazelOutputSnapshotFile(
  sanitizedTestName: String,
  snapshotTestSuffix: String,
  snapshotFile: File,
): File {
  val activeKgpSuffix = kgpSnapshotSuffix ?: return snapshotFile
  val targetSuffix = kgpLayerTargetSuffix(sanitizedTestName, snapshotTestSuffix, activeKgpSuffix)
  return getCandidateSnapshotFile(sanitizedTestName, "${targetSuffix.suffix}$snapshotTestSuffix")
}

/**
 * Selects the target layer file and first existing fallback candidate for `-DUPDATE_TEST_SNAPSHOTS` when [activeKgpSuffix] is set.
 *
 * Targets the layer's `_phased` file when Phased Sync is enabled and that file or the baseline `_phased` file exists, else the layer's
 * non-phased file, paired with the highest-priority existing fallback candidate after it.
 */
private fun SnapshotComparisonTest.resolveKgpLayerUpdateTarget(
  sanitizedTestName: String,
  snapshotTestSuffix: String,
  activeKgpSuffix: SnapshotSuffix,
): Pair<File, Pair<SnapshotSuffix, File>?> {
  val applicableEntries =
    SnapshotSuffix.entries
      .filter { it.isEnabled(activeKgpSuffix) }
      .map { it to getCandidateSnapshotFile(sanitizedTestName, "${it.suffix}$snapshotTestSuffix") }
  val targetSuffix = kgpLayerTargetSuffix(sanitizedTestName, snapshotTestSuffix, activeKgpSuffix)
  val targetIndex = applicableEntries.indexOfFirst { it.first == targetSuffix }
  val fallbackEntry = applicableEntries.drop(targetIndex + 1).firstOrNull { (_, file) -> file.exists() }
  return applicableEntries[targetIndex].second to fallbackEntry
}

/**
 * Selects the suffix of the file in which the layer [activeKgpSuffix] stores a snapshot that differs from its fallback candidates.
 *
 * With Phased Sync enabled, this is the layer's `_phased` suffix when that layer file or the baseline `_phased` file already exists, and
 * the layer's non-phased suffix otherwise. A baseline `_phased` file means that Phased Sync and full sync produce different snapshots for
 * the project, so the layer's Phased Sync snapshot must not go into the non-phased layer file, which the full sync run reads and writes.
 * Without a baseline `_phased` file, a `_phased` layer file is never created automatically. With Phased Sync disabled, this is always the
 * layer's non-phased suffix.
 */
private fun SnapshotComparisonTest.kgpLayerTargetSuffix(
  sanitizedTestName: String,
  snapshotTestSuffix: String,
  activeKgpSuffix: SnapshotSuffix,
): SnapshotSuffix {
  val phasedLayerSuffix =
    SnapshotSuffix.entries.firstOrNull { it != activeKgpSuffix && it.ownerKgpSuffix == activeKgpSuffix && it.isEnabled(activeKgpSuffix) }
  if (phasedLayerSuffix == null) return activeKgpSuffix
  val phasedCandidateFiles =
    listOf(phasedLayerSuffix, SnapshotSuffix.PHASED).map { getCandidateSnapshotFile(sanitizedTestName, "${it.suffix}$snapshotTestSuffix") }
  return if (phasedCandidateFiles.any { it.exists() }) phasedLayerSuffix else activeKgpSuffix
}

private fun SnapshotComparisonTest.getCandidateSnapshotFiles(project: String, additionalSuffix: String): List<File> {
  return snapshotSuffixes.map { getCandidateSnapshotFile(project, "$it$additionalSuffix") }
}

private fun SnapshotComparisonTest.getCandidateSnapshotFile(project: String, suffix: String): File {
  val configuredWorkspace =
    System.getProperty(updateSnapshotsJvmProperty)
      ?.takeUnless { it.isEmpty() }
      ?.let { Paths.get(it).resolve(snapshotDirectoryWorkspaceRelativePath) }
      ?: resolveWorkspacePath(snapshotDirectoryWorkspaceRelativePath)
  return configuredWorkspace.resolve("${project.substringAfter("projects/")}$suffix.txt").toFile()
}

private fun SnapshotComparisonTest.getExpectedTextAndFileFor(project: String, suffix: String): Pair<String, File> =
  getCandidateSnapshotFiles(project, suffix).let { candidateFiles ->
    candidateFiles
      .firstOrNull { it.exists() }
      ?.let {
        println("Comparing with: ${it.relativeTo(resolveWorkspacePath("").toFile())}")
        it.readText().trimIndent() to it
      }
      ?: (candidateFiles.joinToString(separator = "\n", prefix = "No snapshot files found. Candidates considered:\n\n") {
        it.relativeTo(resolveWorkspacePath("").toFile()).toString()
      } to candidateFiles.last())
  }

data class ProjectViewSettings(val hideEmptyPackages: Boolean = true, val flattenPackages: Boolean = false)

fun Project.dumpAndroidProjectView(): String = dumpAndroidProjectView(initialState = Unit) { _, _ -> Unit }

fun nameProperties(
  snapshotLines: Sequence<String>,
  attachValue: Boolean = false,
  skipTopLevel: Boolean = false,
): Sequence<Pair<String, String>> = sequence {
  val context = mutableListOf<Pair<Int, String>>()
  var previousIndentation = -1
  for (existingLine in snapshotLines) {
    val propertyValue = existingLine.trimStart().removePrefix("- ").substringAfter(':', existingLine).trim()
    val propertyName = existingLine.trimStart().removePrefix("- ").substringBefore(' ', existingLine).trim()
    val line =
      if (attachValue && propertyValue != propertyName) {
        if (propertyName.isEmpty()) propertyValue else "$propertyName ($propertyValue)"
      } else propertyName
    val indentation = existingLine.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) existingLine.length else it }
    when {
      indentation > previousIndentation -> context.add(indentation to line)
      indentation == previousIndentation -> context[context.size - 1] = indentation to line
      else -> {
        while (context.size > 1 && context[context.size - 1].first > indentation) {
          context.removeLast()
        }
        context[context.size - 1] = indentation to line
      }
    }
    previousIndentation = indentation
    if (!(skipTopLevel && indentation == 0)) {
      yield(context.joinToString(separator = "/") { (_, line) -> line } to existingLine)
    }
  }
}

private fun SnapshotComparisonTest.updateSnapshotGuidelinesText() =
  """
  To update the files from a presubmit failure, download and unzip the outputs.zip for this target:
    unzip -d $(bazel info workspace) -o outputs.zip

  For a local bazel invocation, outputs.zip will be in bazel-testlogs:
    unzip -d $(bazel info workspace) -o \
      $(bazel info bazel-testlogs)/${testLogsPath ?: "<bazel test target for ${getName()}>"}/test.outputs/outputs.zip

  Or, to re-run the test and update the expectations in place, either add -DUPDATE_TEST_SNAPSHOTS
  to the jvm options in the idea test configuration and re-run the test, or from bazel, run:
    bazel test ${System.getenv("TEST_TARGET")?.takeIf { it.isNotEmpty() } ?: "<bazel test target for ${getName()}>"} \
      --nocache_test_results \
      --sandbox_writable_path=${'$'}(bazel info workspace) \
      --strategy=TestRunner=standalone \
      --jvmopt=\"-DUPDATE_TEST_SNAPSHOTS=$(bazel info workspace)\" \
      --test_timeout=6000 \
      --test_output=streamed

  NB: All the commands above assume 'tools/base/bazel' is on your path.
  """
    .trimIndent()
