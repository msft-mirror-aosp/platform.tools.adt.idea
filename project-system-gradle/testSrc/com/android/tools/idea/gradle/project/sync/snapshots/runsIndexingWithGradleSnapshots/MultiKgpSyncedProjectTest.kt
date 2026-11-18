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
package com.android.tools.idea.gradle.project.sync.snapshots.runsIndexingWithGradleSnapshots

import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS
import com.android.tools.idea.gradle.project.sync.CapturePlatformModelsProjectResolverExtension
import com.android.tools.idea.gradle.project.sync.HighlightProjectTestDef
import com.android.tools.idea.gradle.project.sync.snapshots.GradleSyncLoggedEventsTestDef
import com.android.tools.idea.gradle.project.sync.snapshots.IdeModelSnapshotComparisonTestDefinition
import com.android.tools.idea.gradle.project.sync.snapshots.ProjectStructureSnapshotTestDef
import com.android.tools.idea.gradle.project.sync.snapshots.SyncedProjectTestBase
import com.android.tools.idea.gradle.project.sync.snapshots.SyncedProjectTestDef
import com.android.tools.idea.gradle.project.sync.snapshots.TemplateBasedTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.patchAndroidKotlinMultiplatformJdk
import com.android.tools.idea.gradle.util.AGP_BUILT_IN_KOTLIN_VERSION
import com.android.tools.idea.gradle.util.AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION
import com.android.tools.idea.navigator.AndroidProjectViewSnapshotComparisonTestDef
import com.android.tools.idea.navigator.SourceProvidersTestDef
import com.android.tools.idea.sdk.IdeSdks
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironment
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor.Companion.AGP_CURRENT
import com.android.tools.idea.testing.SnapshotSuffix
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.TestProjectToSnapshotPaths
import com.android.tools.idea.testing.withExplicitKotlinGradlePlugin
import com.android.tools.idea.testing.withJdk
import com.android.tools.idea.testing.withKotlin
import com.android.tools.idea.util.toVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.IconManager
import com.intellij.ui.icons.CoreIconManager
import com.intellij.util.PathUtil
import java.io.File
import org.jetbrains.android.AndroidTestBase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private fun JavaSdkVersion.featureString(): String = maxLanguageLevel.toJavaVersion().toFeatureString()

/**
 * KGP version layers tested against `AGP_CURRENT` in [MultiKgpSyncedProjectTest].
 *
 * Each [MultiKgpTestProject] declares a single [MultiKgpTestProject.baselineLayer] that owns its baseline snapshot (`[LATEST]` for KMP so
 * it matches `CurrentAgp`, and `[DEFAULT]` for all other projects). Non-owning layers run with their own [SnapshotSuffix] and rewrite
 * per-layer identity tokens (KGP `versionString`, Kotlin major.minor level, and Gradle daemon JDK) on the expected snapshot at compare time
 * so layer-specific golden files are checked in only when the synced model genuinely differs.
 *
 * When updating snapshots from Bazel on sharded targets (`shard_count = 5`), pass `--test_sharding_strategy=disabled` (or run update twice)
 * so each project's `baselineLayer` writes its baseline before non-owning layers deduplicate against it.
 */
enum class StudioKgpTestLayer(
  val softwareEnvironment: AgpVersionSoftwareEnvironment,
  val layerSuffix: SnapshotSuffix,
  private val ideModelVersionToken: String,
  private val kotlinMajorMinorToken: String,
) {
  DEFAULT(
    softwareEnvironment = AGP_CURRENT.withKotlin(AGP_BUILT_IN_KOTLIN_VERSION),
    layerSuffix = SnapshotSuffix.KGP_DEFAULT,
    ideModelVersionToken = "<AGP_BUILT_IN_KOTLIN_VERSION>",
    kotlinMajorMinorToken = "<KOTLIN-MAJOR-MINOR-VERSION>",
  ),
  MINIMUM(
    softwareEnvironment =
      AGP_CURRENT.withKotlin(AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION).withJdk(JavaSdkVersion.JDK_21).withExplicitKotlinGradlePlugin(true),
    layerSuffix = SnapshotSuffix.KGP_MIN,
    ideModelVersionToken = "<AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION>",
    kotlinMajorMinorToken = "<MINIMUM-KOTLIN-MAJOR-MINOR-VERSION>",
  ),
  LATEST(
    softwareEnvironment = AGP_CURRENT.withKotlin(KOTLIN_VERSION_FOR_TESTS).withExplicitKotlinGradlePlugin(true),
    layerSuffix = SnapshotSuffix.KGP_LATEST,
    ideModelVersionToken = "<KOTLIN_VERSION_FOR_TESTS>",
    kotlinMajorMinorToken = "<KOTLIN-MAJOR-MINOR-VERSION>",
  );

  fun snapshotSuffixFor(project: MultiKgpTestProject): SnapshotSuffix? = if (this == project.baselineLayer) null else layerSuffix

  fun updateExpectedIdeModelSnapshot(expected: String): String =
    expected
      .replace("<AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION>", ideModelVersionToken)
      .replace("<AGP_BUILT_IN_KOTLIN_VERSION>", ideModelVersionToken)
      .replace("<KOTLIN_VERSION_FOR_TESTS>", ideModelVersionToken)

  fun updateExpectedProjectStructureSnapshot(expected: String): String {
    var updated =
      expected
        .replace("<MINIMUM-KOTLIN-MAJOR-MINOR-VERSION>", kotlinMajorMinorToken)
        .replace("<KOTLIN-MAJOR-MINOR-VERSION>", kotlinMajorMinorToken)
    val layerJdk = softwareEnvironment.jdkVersion
    val defaultJdk = IdeSdks.DEFAULT_JDK_VERSION
    if (layerJdk != null && layerJdk != defaultJdk) {
      val defaultFeature = defaultJdk.featureString()
      val layerFeature = layerJdk.featureString()
      updated =
        updated
          .replace("<JDK_NAME>", "<JDK_NAME-$layerFeature>")
          .replace("<JDK_VERSION>", "<JDK_VERSION-$layerFeature>")
          .replace("Platform                      : JVM ($defaultFeature)", "Platform                      : JVM ($layerFeature)")
    }
    return updated
  }
}

/** Wide-spanning test projects synced across all [StudioKgpTestLayer] versions (`MINIMUM`, `DEFAULT`, `LATEST`). */
enum class MultiKgpTestProject(
  override val template: String,
  override val pathToOpen: String = "",
  override val testName: String? = null,
  val baselineLayer: StudioKgpTestLayer = StudioKgpTestLayer.DEFAULT,
  override val isCompatibleWith: (AgpVersionSoftwareEnvironmentDescriptor) -> Boolean = { it == AGP_CURRENT },
  override val autoMigratePackageAttribute: Boolean = true,
  override val builtInKotlinEnabled: Boolean? = null,
  override val setup: () -> () -> Unit = { {} },
  override val patch: (AgpVersionSoftwareEnvironment.(projectRoot: File) -> Unit)? = null,
  override val expectedSyncIssues: Set<Int> = emptySet(),
  override val verifyOpened: ((Project) -> Unit)? = null,
  override val switchVariant: TemplateBasedTestProject.VariantSelection? = null,
) : TemplateBasedTestProject {
  ANDROID_KOTLIN_MULTIPLATFORM(
    TestProjectToSnapshotPaths.ANDROID_KOTLIN_MULTIPLATFORM,
    baselineLayer = StudioKgpTestLayer.LATEST,
    patch = { projectRoot ->
      patchAndroidKotlinMultiplatformJdk(projectRoot)
      // Same IntelliJ VFS workaround as TestProject.ANDROID_KOTLIN_MULTIPLATFORM (b/493857612): load the children of the included
      // builds so that their settings.gradle files are found after the Gradle wrapper is created.
      projectRoot.toVirtualFile()?.let { VfsUtilCore.processFilesRecursively(it) { true } }
    },
  ),
  KOTLIN_KAPT(TestProjectToSnapshotPaths.KOTLIN_KAPT),
  KOTLIN_KAPT_LEGACY(
    TestProjectToSnapshotPaths.KOTLIN_KAPT,
    testName = "legacyKotlin",
    builtInKotlinEnabled = false,
    patch = { projectRoot ->
      projectRoot.resolve("gradle.properties").appendText("\nandroid.builtInKotlin=false\nandroid.newDsl=false\n")
    },
  );

  override fun getTestDataDirectoryWorkspaceRelativePath(): String = "tools/adt/idea/android/testData/snapshots"

  override fun getAdditionalRepos(): Collection<File> =
    listOf(File(AndroidTestBase.getTestDataPath(), PathUtil.toSystemDependentName(TestProjectPaths.PSD_SAMPLE_REPO)))
}

/**
 * Parameterized snapshot & sync integration test verifying every [MultiKgpTestProject] across all supported [StudioKgpTestLayer] versions
 * (`MINIMUM`, `DEFAULT`, `LATEST`) inside `runs_indexing_with_gradle_snapshots`.
 */
@RunsInEdt
@RunWith(Parameterized::class)
class MultiKgpSyncedProjectTest(
  private val kgpLayer: StudioKgpTestLayer,
  private val project: MultiKgpTestProject,
) :
  SyncedProjectTestBase<MultiKgpTestProject>(
    agpVersion = AGP_CURRENT,
    softwareEnvironment = kgpLayer.softwareEnvironment,
  ) {

  companion object {
    /**
     * Lists each project's [MultiKgpTestProject.baselineLayer] before its non-owning layers, so that an unsharded snapshot update writes
     * the baseline before the other layers deduplicate against it.
     */
    @JvmStatic
    @Parameterized.Parameters(name = "{0}_{1}")
    fun parameters(): Collection<Array<Any>> =
      MultiKgpTestProject.entries.flatMap { project ->
        val layers = listOf(project.baselineLayer) + StudioKgpTestLayer.entries.filter { it != project.baselineLayer }
        layers.map { layer -> arrayOf<Any>(layer, project) }
      }
  }

  @Test fun testSyncedProject() = testProject(project)

  override fun getTestDefs(testProject: MultiKgpTestProject): List<SyncedProjectTestDef> {
    val suffix = kgpLayer.snapshotSuffixFor(testProject)
    val isBaselineOwner = suffix == null
    return listOfNotNull(
      IdeModelSnapshotComparisonTestDefinition(
        testProject,
        skipV1toV2Comparison = testProject == MultiKgpTestProject.ANDROID_KOTLIN_MULTIPLATFORM,
        kgpSnapshotSuffix = suffix,
        updateExpectedSnapshot = if (isBaselineOwner) null else kgpLayer::updateExpectedIdeModelSnapshot,
      ),
      ProjectStructureSnapshotTestDef(
        testProject,
        kgpSnapshotSuffix = suffix,
        updateExpectedSnapshot = if (isBaselineOwner) null else kgpLayer::updateExpectedProjectStructureSnapshot,
      ),
      SourceProvidersTestDef(testProject, kgpSnapshotSuffix = suffix),
      if (testProject != MultiKgpTestProject.KOTLIN_KAPT) {
        AndroidProjectViewSnapshotComparisonTestDef(testProject, kgpSnapshotSuffix = suffix)
      } else {
        null
      },
      if (testProject == MultiKgpTestProject.KOTLIN_KAPT || testProject == MultiKgpTestProject.KOTLIN_KAPT_LEGACY) {
        GradleSyncLoggedEventsTestDef.kotlinKaptVersionsTestDef(testProject)
      } else {
        null
      },
    ) +
      if (testProject == MultiKgpTestProject.ANDROID_KOTLIN_MULTIPLATFORM) {
        HighlightProjectTestDef.androidKotlinMultiplatformTests(testProject)
      } else {
        emptyList()
      }
  }

  override fun setupAdditionalEnvironment() {
    CapturePlatformModelsProjectResolverExtension.registerTestHelperProjectResolver(
      CapturePlatformModelsProjectResolverExtension.IdeModels(),
      projectRule.testRootDisposable,
    )
  }

  init {
    try {
      IconManager.activate(CoreIconManager())
      IconLoader.activate()
    } catch (e: Throwable) {
      e.printStackTrace()
    }
  }
}
