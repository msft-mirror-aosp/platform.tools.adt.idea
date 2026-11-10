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
package com.android.tools.idea.gradle.project.sync

import com.android.testutils.junit4.OldAgpTest
import com.android.tools.idea.gradle.project.sync.runsGradle.FakeSourcesTest
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end verification on AGP 8.0 (where `VariantDependencies` does not embed library sources/javadocs) that Phased Sync fetches
 * `AdditionalClassifierArtifactsModel` with legacy model providers disabled, attaches library sources, populates `LibraryFilePaths`, and
 * re-attaches cached library sources on subsequent resyncs when `AdditionalClassifierArtifactsModel` is skipped.
 */
@OldAgpTest(agpVersions = ["8.0.2"], gradleVersions = ["8.0"])
class PhasedSyncAdditionalClassifierArtifactsOldAgpTest {
  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testSourcesAttachedWithoutLegacyProvidersAndReattachedFromCacheOnResync() {
    FakeSourcesTest.verifyFakeSourcesViaAdditionalClassifierArtifactsModel(
      projectRule,
      AgpVersionSoftwareEnvironmentDescriptor.AGP_80,
    )
  }
}
