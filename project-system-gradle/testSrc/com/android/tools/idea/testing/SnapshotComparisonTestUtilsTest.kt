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
import org.junit.After
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

  private fun createContext(snapshotDir: String, testName: String = "testSample_V2"): SnapshotComparisonTest =
    object : SnapshotComparisonTest {
      override val updateSnapshotsJvmProperty: String = updatePropertyName
      override val snapshotDirectoryWorkspaceRelativePath: String = snapshotDir

      override fun getName(): String = testName
    }

  @Test
  fun updateModeRewritesExistingTargetAndPassesWithUpdatedExpectation() {
    StudioFlags.PHASED_SYNC_ENABLED.override(true)
    val workspaceRoot = tempFolder.newFolder("workspace_legacy")
    val snapshotDir = "snapshots"
    val snapshotsFolder = workspaceRoot.resolve(snapshotDir).apply { mkdirs() }
    System.setProperty(updatePropertyName, workspaceRoot.absolutePath)

    val context = createContext(snapshotDir)
    val baselineFile = snapshotsFolder.resolve("sample_V2.txt")
    val phasedFile = snapshotsFolder.resolve("sample_V2_phased.txt")

    // Case 1: Only baseline X.txt exists -> update mode targets X.txt (never creates X_phased.txt).
    baselineFile.writeText("old-baseline")
    context.assertIsEqualToSnapshot("new-baseline")
    assertThat(baselineFile.readText()).isEqualTo("new-baseline")
    assertThat(phasedFile.exists()).isFalse()

    // Case 2: Both X_phased.txt and X.txt exist -> update mode targets X_phased.txt.
    phasedFile.writeText("old-phased")
    context.assertIsEqualToSnapshot("updated-phased")
    assertThat(phasedFile.readText()).isEqualTo("updated-phased")
    assertThat(baselineFile.readText()).isEqualTo("new-baseline")
  }
}
