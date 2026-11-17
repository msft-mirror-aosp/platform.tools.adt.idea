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
package com.android.tools.idea.testing

import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotComparisonTestUtilsTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val updatePropertyName = "TEST_UPDATE_SNAPSHOTS_DIR"

  @After
  fun tearDown() {
    StudioFlags.PHASED_SYNC_ENABLED.clearOverride()
    System.clearProperty(updatePropertyName)
  }

  private fun createContext(
    snapshotDir: String,
    kgpSuffix: SnapshotSuffix? = null,
    testName: String = "testSample_V2",
  ): SnapshotComparisonTest =
    object : SnapshotComparisonTest {
      override val updateSnapshotsJvmProperty: String = updatePropertyName
      override val snapshotDirectoryWorkspaceRelativePath: String = snapshotDir
      override val kgpSnapshotSuffix: SnapshotSuffix? = kgpSuffix

      override fun getName(): String = testName
    }

  /** Returns the snapshot file name and expected text that a comparison would use, without updating or writing any file. */
  private fun SnapshotComparisonTest.expectedSnapshot(): Pair<String, String> = getAndMaybeUpdateSnapshot("", "", doNotUpdate = true)

  @Test
  fun snapshotSuffixOrderAndScoping() {
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable()).containsExactly("")
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable(SnapshotSuffix.KGP_MIN)).containsExactly("_kgp_min", "").inOrder()
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable(SnapshotSuffix.KGP_DEFAULT)).containsExactly("_kgp_default", "").inOrder()

    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable()).containsExactly("_phased", "").inOrder()
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable(SnapshotSuffix.KGP_MIN))
      .containsExactly("_kgp_min_phased", "_kgp_min", "_phased", "")
      .inOrder()
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable(SnapshotSuffix.KGP_DEFAULT))
      .containsExactly("_kgp_default_phased", "_kgp_default", "_phased", "")
      .inOrder()
    assertThat(SnapshotSuffix.snapshotSuffixesApplicable(SnapshotSuffix.KGP_LATEST))
      .containsExactly("_kgp_latest_phased", "_kgp_latest", "_phased", "")
      .inOrder()
  }

  @Test
  fun legacyPhasedSyncPreservesTargetSelectionWhenNoKgpSuffixActive() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val workspaceRoot = tempFolder.newFolder("workspace_legacy")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val context = createContext(snapshotDir, kgpSuffix = null)
    val baselineFile = snapshotsFolder.resolve("sample_V2.txt")
    val phasedFile = snapshotsFolder.resolve("sample_V2_phased.txt")

    // Case 1: Only baseline X.txt exists -> both Bazel output target and update mode target X.txt (never creates X_phased.txt).
    baselineFile.writeText("old-baseline")
    assertThat(context.resolveBazelOutputSnapshotFile("sample_V2", "", baselineFile)).isEqualTo(baselineFile)
    context.assertIsEqualToSnapshot("new-baseline")
    assertThat(baselineFile.readText()).isEqualTo("new-baseline")
    assertThat(phasedFile.exists()).isFalse()

    // Case 2: Both X_phased.txt and X.txt exist -> both Bazel output target and update mode target X_phased.txt.
    phasedFile.writeText("old-phased")
    assertThat(context.resolveBazelOutputSnapshotFile("sample_V2", "", phasedFile)).isEqualTo(phasedFile)
    context.assertIsEqualToSnapshot("updated-phased")
    assertThat(phasedFile.readText()).isEqualTo("updated-phased")
    assertThat(baselineFile.readText()).isEqualTo("new-baseline")
  }

  @Test
  fun missingBaselineOutsideUpdateModeThrowsStrictly() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val orphanSnapshotsDir = tempFolder.newFolder("orphan_snapshots")
    orphanSnapshotsDir.resolve("sample_V2_phased.txt").writeText("orphan-phased")

    val context = createContext(snapshotDir = orphanSnapshotsDir.absolutePath, kgpSuffix = SnapshotSuffix.KGP_MIN)
    assertThrows(FileNotFoundException::class.java) { context.assertNoDuplicatedSnapshots() }
  }

  @Test
  fun kgpLayerBazelOutputTargetUsesPhasedVariantOnlyWhenPhasedSnapshotExists() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val workspaceRoot = tempFolder.newFolder("workspace_bazel_output")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val baselineFile = snapshotsFolder.resolve("sample_V2.txt").apply { writeText("baseline") }
    val phasedFile = snapshotsFolder.resolve("sample_V2_phased.txt")
    val minFile = snapshotsFolder.resolve("sample_V2_kgp_min.txt")
    val minPhasedFile = snapshotsFolder.resolve("sample_V2_kgp_min_phased.txt")
    val minContext = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_MIN)

    // No layer file and no sample_V2_phased.txt exist -> targets non-phased sample_V2_kgp_min.txt, not sample_V2_kgp_min_phased.txt.
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", baselineFile)).isEqualTo(minFile)

    // Non-phased layer file exists -> targets sample_V2_kgp_min.txt.
    minFile.writeText("min")
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minFile)).isEqualTo(minFile)

    // Phased layer file exists -> targets sample_V2_kgp_min_phased.txt.
    minPhasedFile.writeText("min-phased")
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minPhasedFile)).isEqualTo(minPhasedFile)

    // sample_V2_phased.txt exists and no layer file does -> targets sample_V2_kgp_min_phased.txt.
    minFile.delete()
    minPhasedFile.delete()
    phasedFile.writeText("baseline-phased")
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", phasedFile)).isEqualTo(minPhasedFile)

    // sample_V2_phased.txt and the non-phased layer file exist -> still targets sample_V2_kgp_min_phased.txt.
    minFile.writeText("min")
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minFile)).isEqualTo(minPhasedFile)

    // Phased Sync disabled -> targets sample_V2_kgp_min.txt whichever phased files exist.
    minPhasedFile.writeText("min-phased")
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minFile)).isEqualTo(minFile)
  }

  @Test
  fun kgpLayerUpdateWithoutBaselinePhasedFileNeverCreatesPhasedLayerFile() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val workspaceRoot = tempFolder.newFolder("workspace_layer_update_phased")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val baselineFile = snapshotsFolder.resolve("sample_V2.txt").apply { writeText("baseline") }
    val minFile = snapshotsFolder.resolve("sample_V2_kgp_min.txt")
    val minPhasedFile = snapshotsFolder.resolve("sample_V2_kgp_min_phased.txt")
    val minContext = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_MIN)

    // Case 1: No layer file exists -> a diverging layer writes sample_V2_kgp_min.txt (never creates sample_V2_kgp_min_phased.txt).
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", baselineFile)).isEqualTo(minFile)
    minContext.assertIsEqualToSnapshot("min-v1")
    assertThat(minFile.readText()).isEqualTo("min-v1")
    assertThat(minPhasedFile.exists()).isFalse()

    // Case 2: Only sample_V2_kgp_min.txt exists -> it is updated in place (never creates sample_V2_kgp_min_phased.txt).
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minFile)).isEqualTo(minFile)
    minContext.assertIsEqualToSnapshot("min-v2")
    assertThat(minFile.readText()).isEqualTo("min-v2")
    assertThat(minPhasedFile.exists()).isFalse()

    // Case 3: Only sample_V2_kgp_min.txt exists and the layer matches the baseline again -> it is deleted and nothing is created.
    minContext.assertIsEqualToSnapshot("baseline")
    assertThat(minFile.exists()).isFalse()
    assertThat(minPhasedFile.exists()).isFalse()

    // Case 4: Both layer files exist -> sample_V2_kgp_min_phased.txt is updated and sample_V2_kgp_min.txt is left untouched.
    minFile.writeText("min")
    minPhasedFile.writeText("min-phased-v1")
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minPhasedFile)).isEqualTo(minPhasedFile)
    minContext.assertIsEqualToSnapshot("min-phased-v2")
    assertThat(minPhasedFile.readText()).isEqualTo("min-phased-v2")
    assertThat(minFile.readText()).isEqualTo("min")
    assertThat(baselineFile.readText()).isEqualTo("baseline")

    // Outside update mode, the Phased Sync run passes against sample_V2_kgp_min_phased.txt and the full sync run against
    // sample_V2_kgp_min.txt.
    System.clearProperty(updatePropertyName)
    val nonUpdateMinContext = createContext(snapshotsFolder.absolutePath, kgpSuffix = SnapshotSuffix.KGP_MIN)
    nonUpdateMinContext.assertIsEqualToSnapshot("min-phased-v2")
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    nonUpdateMinContext.assertIsEqualToSnapshot("min")
  }

  @Test
  fun kgpLayerUpdateWithBaselinePhasedFileKeepsPhasedSyncAndFullSyncLayerFilesApart() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val workspaceRoot = tempFolder.newFolder("workspace_layer_update_baseline_phased")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val baselineFile = snapshotsFolder.resolve("sample_V2.txt").apply { writeText("baseline") }
    val phasedFile = snapshotsFolder.resolve("sample_V2_phased.txt").apply { writeText("baseline-phased") }
    val minFile = snapshotsFolder.resolve("sample_V2_kgp_min.txt")
    val minPhasedFile = snapshotsFolder.resolve("sample_V2_kgp_min_phased.txt")
    val minContext = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_MIN)
    val nonUpdateMinContext = createContext(snapshotsFolder.absolutePath, kgpSuffix = SnapshotSuffix.KGP_MIN)

    // Case 1: No layer file exists -> a layer diverging under Phased Sync writes sample_V2_kgp_min_phased.txt, not sample_V2_kgp_min.txt.
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", phasedFile)).isEqualTo(minPhasedFile)
    minContext.assertIsEqualToSnapshot("min-phased-v1")
    assertThat(minPhasedFile.readText()).isEqualTo("min-phased-v1")
    assertThat(minFile.exists()).isFalse()

    // Case 2: The same layer diverging under full sync writes sample_V2_kgp_min.txt and leaves sample_V2_kgp_min_phased.txt untouched.
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", baselineFile)).isEqualTo(minFile)
    minContext.assertIsEqualToSnapshot("min-full")
    assertThat(minFile.readText()).isEqualTo("min-full")
    assertThat(minPhasedFile.readText()).isEqualTo("min-phased-v1")

    // Outside update mode, the full sync run passes against sample_V2_kgp_min.txt and the Phased Sync run against
    // sample_V2_kgp_min_phased.txt.
    System.clearProperty(updatePropertyName)
    nonUpdateMinContext.assertIsEqualToSnapshot("min-full")
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    nonUpdateMinContext.assertIsEqualToSnapshot("min-phased-v1")
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    // Case 3: Only sample_V2_kgp_min.txt exists and the Phased Sync output matches sample_V2_phased.txt -> sample_V2_kgp_min.txt is kept
    // for full sync, and sample_V2_kgp_min_phased.txt is written because sample_V2_kgp_min.txt would otherwise take precedence.
    minPhasedFile.delete()
    assertThat(minContext.resolveBazelOutputSnapshotFile("sample_V2", "", minFile)).isEqualTo(minPhasedFile)
    minContext.assertIsEqualToSnapshot("baseline-phased")
    assertThat(minFile.readText()).isEqualTo("min-full")
    assertThat(minPhasedFile.readText()).isEqualTo("baseline-phased")

    // Outside update mode, the Phased Sync run then passes against sample_V2_kgp_min_phased.txt.
    System.clearProperty(updatePropertyName)
    nonUpdateMinContext.assertIsEqualToSnapshot("baseline-phased")
    assertThat(nonUpdateMinContext.expectedSnapshot()).isEqualTo("sample_V2_kgp_min_phased.txt" to "baseline-phased")
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    // Case 4: The layer matches the baseline again under full sync -> sample_V2_kgp_min.txt is deleted, and so is
    // sample_V2_kgp_min_phased.txt, which now duplicates sample_V2_phased.txt.
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    minContext.assertIsEqualToSnapshot("baseline")
    assertThat(minFile.exists()).isFalse()
    assertThat(minPhasedFile.exists()).isFalse()
    assertThat(phasedFile.readText()).isEqualTo("baseline-phased")
    assertThat(baselineFile.readText()).isEqualTo("baseline")
  }

  @Test
  fun updateAndCompareRewriterDeduplicatesAndRejectsTokenOnlyLayerSnapshots() {
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    val workspaceRoot = tempFolder.newFolder("workspace_rewriter")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val defaultContext = createContext(snapshotDir, kgpSuffix = null)
    val latestContext = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_LATEST)
    val minContext = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_MIN)
    val latestUpdater: (String) -> String = { it.replace("<AGP_BUILT_IN_KOTLIN_VERSION>", "<KOTLIN_VERSION_FOR_TESTS>") }
    val minUpdater: (String) -> String = { it.replace("<AGP_BUILT_IN_KOTLIN_VERSION>", "<AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION>") }
    val defaultText = "versionString : <AGP_BUILT_IN_KOTLIN_VERSION>\nmodel : v1"
    val latestText = "versionString : <KOTLIN_VERSION_FOR_TESTS>\nmodel : v1"
    val minText = "versionString : <AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION>\nmodel : min-only"

    // 1. DEFAULT writes baseline with <AGP_BUILT_IN_KOTLIN_VERSION>.
    defaultContext.assertIsEqualToSnapshot(defaultText)
    assertThat(snapshotsFolder.resolve("sample_V2.txt").exists()).isTrue()

    // 2. LATEST differs only in the rewritten token -> reuses baseline without creating sample_V2_kgp_latest.txt.
    latestContext.assertIsEqualToUpdatedSnapshot(latestText, snapshotOnSaveUpdater = { it }, snapshotOnCompareUpdater = latestUpdater)
    assertThat(snapshotsFolder.resolve("sample_V2_kgp_latest.txt").exists()).isFalse()

    // 3. MINIMUM with structural difference writes sample_V2_kgp_min.txt without overwriting baseline.
    minContext.assertIsEqualToUpdatedSnapshot(minText, snapshotOnSaveUpdater = { it }, snapshotOnCompareUpdater = minUpdater)
    assertThat(snapshotsFolder.resolve("sample_V2.txt").readText()).isEqualTo(defaultText)
    assertThat(snapshotsFolder.resolve("sample_V2_kgp_min.txt").readText()).isEqualTo(minText)

    // 4. Outside update mode, every layer passes against the files written above: DEFAULT against the baseline, LATEST against the
    // rewritten baseline (it does not read sample_V2_kgp_min.txt), and MINIMUM against its own sample_V2_kgp_min.txt.
    System.clearProperty(updatePropertyName)
    val nonUpdateDefaultContext = createContext(snapshotsFolder.absolutePath, kgpSuffix = null)
    val nonUpdateLatestContext = createContext(snapshotsFolder.absolutePath, kgpSuffix = SnapshotSuffix.KGP_LATEST)
    val nonUpdateMinContext = createContext(snapshotsFolder.absolutePath, kgpSuffix = SnapshotSuffix.KGP_MIN)
    nonUpdateDefaultContext.assertIsEqualToSnapshot(defaultText)
    nonUpdateLatestContext.assertIsEqualToUpdatedSnapshot(
      latestText,
      snapshotOnSaveUpdater = { it },
      snapshotOnCompareUpdater = latestUpdater,
    )
    nonUpdateMinContext.assertIsEqualToUpdatedSnapshot(minText, snapshotOnSaveUpdater = { it }, snapshotOnCompareUpdater = minUpdater)
    assertThat(nonUpdateLatestContext.expectedSnapshot()).isEqualTo("sample_V2.txt" to defaultText)
    assertThat(nonUpdateMinContext.expectedSnapshot()).isEqualTo("sample_V2_kgp_min.txt" to minText)

    // 5a. Outside update mode, a checked-in token-only _kgp_latest.txt file fails assertNoDuplicatedSnapshots.
    val redundantLatest = snapshotsFolder.resolve("sample_V2_kgp_latest.txt")
    redundantLatest.writeText(latestText)
    assertThrows(AssertionError::class.java) {
      nonUpdateLatestContext.assertNoDuplicatedSnapshots(snapshotOnCompareUpdater = latestUpdater)
    }
    assertThat(redundantLatest.exists()).isTrue()

    // 5b. In update mode, assertNoDuplicatedSnapshots automatically deletes the redundant token-only _kgp_latest.txt file.
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)
    latestContext.assertNoDuplicatedSnapshots(snapshotOnCompareUpdater = latestUpdater)
    assertThat(redundantLatest.exists()).isFalse()
  }

  @Test
  fun nonBaselineKgpDefaultLayerDivergenceWritesOwnFileLeavingLatestBaselineUntouched() {
    StudioFlags.PHASED_SYNC_ENABLED.override(false)
    val workspaceRoot = tempFolder.newFolder("workspace_kmp_divergence")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    // Simulate KMP project where LATEST owns the baseline (kgpSuffix = null) and DEFAULT is a non-baseline layer (KGP_DEFAULT).
    val latestBaselineOwner = createContext(snapshotDir, kgpSuffix = null, testName = "testKmp_V2")
    val defaultNonOwner = createContext(snapshotDir, kgpSuffix = SnapshotSuffix.KGP_DEFAULT, testName = "testKmp_V2")
    val nonUpdateLatestBaselineOwner = createContext(snapshotsFolder.absolutePath, kgpSuffix = null, testName = "testKmp_V2")
    val nonUpdateDefaultNonOwner =
      createContext(snapshotsFolder.absolutePath, kgpSuffix = SnapshotSuffix.KGP_DEFAULT, testName = "testKmp_V2")
    val defaultUpdater: (String) -> String = { it.replace("<KOTLIN_VERSION_FOR_TESTS>", "<AGP_BUILT_IN_KOTLIN_VERSION>") }

    val latestBaselineText = "versionString : <KOTLIN_VERSION_FOR_TESTS>\nswiftExport : enabled"
    latestBaselineOwner.assertIsEqualToSnapshot(latestBaselineText)
    val baselineFile = snapshotsFolder.resolve("kmp_V2.txt")
    val defaultLayerFile = snapshotsFolder.resolve("kmp_V2_kgp_default.txt")
    assertThat(baselineFile.readText()).isEqualTo(latestBaselineText)

    // When DEFAULT differs only in version token, no _kgp_default.txt file is created and baseline stays owned by LATEST.
    val tokenOnlyDefaultText = "versionString : <AGP_BUILT_IN_KOTLIN_VERSION>\nswiftExport : enabled"
    defaultNonOwner.assertIsEqualToUpdatedSnapshot(
      tokenOnlyDefaultText,
      snapshotOnSaveUpdater = { it },
      snapshotOnCompareUpdater = defaultUpdater,
    )
    assertThat(defaultLayerFile.exists()).isFalse()
    assertThat(baselineFile.readText()).isEqualTo(latestBaselineText)

    // Outside update mode, DEFAULT then passes against the rewritten kmp_V2.txt.
    System.clearProperty(updatePropertyName)
    nonUpdateDefaultNonOwner.assertIsEqualToUpdatedSnapshot(
      tokenOnlyDefaultText,
      snapshotOnSaveUpdater = { it },
      snapshotOnCompareUpdater = defaultUpdater,
    )
    assertThat(nonUpdateDefaultNonOwner.expectedSnapshot()).isEqualTo("kmp_V2.txt" to latestBaselineText)
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    // Once DEFAULT and LATEST diverge in a real model line, DEFAULT writes kmp_V2_kgp_default.txt without touching kmp_V2.txt.
    val divergedDefaultText = "versionString : <AGP_BUILT_IN_KOTLIN_VERSION>\nswiftExport : disabled"
    defaultNonOwner.assertIsEqualToUpdatedSnapshot(
      divergedDefaultText,
      snapshotOnSaveUpdater = { it },
      snapshotOnCompareUpdater = defaultUpdater,
    )
    assertThat(defaultLayerFile.readText()).isEqualTo(divergedDefaultText)
    assertThat(baselineFile.readText()).isEqualTo(latestBaselineText)

    // Outside update mode, DEFAULT then passes against kmp_V2_kgp_default.txt and LATEST still passes against kmp_V2.txt.
    System.clearProperty(updatePropertyName)
    nonUpdateDefaultNonOwner.assertIsEqualToUpdatedSnapshot(
      divergedDefaultText,
      snapshotOnSaveUpdater = { it },
      snapshotOnCompareUpdater = defaultUpdater,
    )
    assertThat(nonUpdateDefaultNonOwner.expectedSnapshot()).isEqualTo("kmp_V2_kgp_default.txt" to divergedDefaultText)
    nonUpdateLatestBaselineOwner.assertIsEqualToSnapshot(latestBaselineText)
    assertThat(nonUpdateLatestBaselineOwner.expectedSnapshot()).isEqualTo("kmp_V2.txt" to latestBaselineText)
  }
}
