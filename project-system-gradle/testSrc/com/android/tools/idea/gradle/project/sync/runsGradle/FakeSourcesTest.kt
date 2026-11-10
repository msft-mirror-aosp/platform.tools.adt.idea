/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.runsGradle

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.LibraryFilePaths
import com.android.tools.idea.gradle.project.sync.snapshots.TestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.projectsystem.gradle.getMainModule
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.android.tools.idea.testing.findModule
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.requestSyncAndWait
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import java.io.File
import org.junit.Rule
import org.junit.Test

class FakeSourcesTest {
  @get:Rule val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun testFakeSources() {
    val preparedProject = projectRule.prepareTestProject(TestProject.WITH_GRADLE_METADATA)
    preparedProject.open {
      val module = project.findModule("app.main")
      val rootManager = ModuleRootManager.getInstance(module)
      val sourcesRoots = rootManager.orderEntries().sources().roots
      val jars = sourcesRoots.map { it.name }
      // This library is published with doctype="fake-source" to verify that we can find
      // such sources (which in practice includes several androidx libraries)
      assertThat(jars).contains("library-1.1-sources.jar")
    }
  }

  @Test
  fun testFakeSources_viaAdditionalClassifierArtifactsModel() {
    verifyFakeSourcesViaAdditionalClassifierArtifactsModel(
      projectRule,
      AgpVersionSoftwareEnvironmentDescriptor.AGP_CURRENT,
      disableMultiVariantAdditionalArtifactSupport = true,
    )
  }

  companion object {
    /**
     * Verifies that when `AdditionalClassifierArtifactsModel` is used (either on AGP < 8.10 or when multi-variant additional artifact
     * support is disabled) and legacy model providers are disabled, Phased Sync attaches library sources, populates [LibraryFilePaths], and
     * re-attaches cached library sources on subsequent resyncs when `AdditionalClassifierArtifactsModel` is skipped.
     */
    fun verifyFakeSourcesViaAdditionalClassifierArtifactsModel(
      projectRule: IntegrationTestEnvironmentRule,
      agpVersion: AgpVersionSoftwareEnvironmentDescriptor,
      disableMultiVariantAdditionalArtifactSupport: Boolean = false,
    ) {
      StudioFlags.PHASED_SYNC_DISABLE_LEGACY_MODEL_PROVIDERS_FOR_SUPPORTED_PROJECTS.overrideForTest(true, projectRule.testRootDisposable)
      if (disableMultiVariantAdditionalArtifactSupport) {
        StudioFlags.GRADLE_MULTI_VARIANT_ADDITIONAL_ARTIFACT_SUPPORT.overrideForTest(false, projectRule.testRootDisposable)
      }

      val preparedProject = projectRule.prepareTestProject(TestProject.WITH_GRADLE_METADATA, agpVersion = agpVersion)
      val expectedPom = preparedProject.root.resolve("repo/org/gradle/sample/library/1.1/library-1.1.pom")
      preparedProject.open { project ->
        assertSourcesAttachedAndCached(project, expectedPom)

        // Drop the LibraryEntity so Phased Sync must recreate it rather than reusing the existing entity: on resync all resolved library
        // IDs are reported via LibraryFilePaths.retrieveCachedLibs() (so AdditionalClassifierArtifactsModel is skipped), and Phased Sync
        // must re-attach library-1.1-sources.jar from the LibraryFilePaths cache.
        removeLibraryEntity(project, "Gradle: org.gradle.sample:library:1.1")
        assertThat(sourceJarNames(project)).doesNotContain("library-1.1-sources.jar")

        project.requestSyncAndWait()
        assertSourcesAttachedAndCached(project, expectedPom)
      }
    }

    private fun sourceJarNames(project: Project): List<String> {
      val mainModule = project.gradleModule(":app")!!.getMainModule()
      return ModuleRootManager.getInstance(mainModule).orderEntries().sources().roots.map { it.name }
    }

    private fun assertSourcesAttachedAndCached(project: Project, expectedPom: File) {
      assertThat(sourceJarNames(project)).contains("library-1.1-sources.jar")
      val libraryFilePaths = LibraryFilePaths.getInstance(project)
      assertThat(libraryFilePaths.retrieveCachedLibs()).contains("org.gradle.sample:library:1.1")
      assertThat(libraryFilePaths.findPomPathForLibrary("Gradle: org.gradle.sample:library:1.1", File("nonexistent")))
        .isEqualTo(expectedPom)
    }

    @Suppress("UnstableApiUsage")
    private fun removeLibraryEntity(project: Project, libraryName: String) {
      runWriteActionAndWait {
        project.workspaceModel.updateProjectModel("Remove $libraryName for resync cache test") { storage ->
          val entity = checkNotNull(storage.resolve(LibraryId(libraryName, LibraryTableId.ProjectLibraryTableId)))
          storage.removeEntity(entity)
        }
      }
    }
  }
}
