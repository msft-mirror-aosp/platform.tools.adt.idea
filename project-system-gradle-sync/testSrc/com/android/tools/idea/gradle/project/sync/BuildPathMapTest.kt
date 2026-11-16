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

import com.android.builder.model.v2.models.Versions
import com.android.ide.gradle.model.composites.BuildMap
import com.android.ide.gradle.model.composites.BuildMapImpl
import com.android.tools.idea.gradle.model.impl.BuildFolderPaths
import com.android.tools.idea.gradle.model.impl.IdeBuildImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.gradle.api.Action
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.BuildModel
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.Model
import org.gradle.tooling.model.ProjectIdentifier
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.build.GradleEnvironment
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider.GradleModelConsumer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

class BuildPathMapTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `buildBuildPathMap uses buildIdentifier rootDir when rootProject projectDir is customized`() {
    val rootBuildDir = tempFolder.newFolder("rootBuild")
    val customRootProjectDir = File(rootBuildDir, "customRoot").apply { mkdirs() }
    val includedBuildDir = tempFolder.newFolder("includedBuild")
    val customIncludedProjectDir = File(includedBuildDir, "customIncluded").apply { mkdirs() }

    val rootBuild = createGradleBuild(rootBuildDir, customRootProjectDir, emptyList())
    val includedBuild = createGradleBuild(includedBuildDir, customIncludedProjectDir, emptyList())

    val controller =
      createBuildController(
        mapOf(
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to customRootProjectDir)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to customIncludedProjectDir)),
        )
      )

    val buildPathMap = buildBuildPathMap(controller, listOf(rootBuild, includedBuild))

    assertThat(buildPathMap).containsExactly(":", BuildId(rootBuildDir), ":included", BuildId(includedBuildDir))
  }

  @Test
  fun `buildBuildPathMap uses buildIdentifier rootDir for symlinked builds`() {
    val realRootDir = tempFolder.newFolder("realRoot")
    val symlinkRootDir = File(tempFolder.root, "symlinkRoot")
    Files.createSymbolicLink(symlinkRootDir.toPath(), realRootDir.toPath())

    val realIncludedDir = tempFolder.newFolder("realIncluded")
    val symlinkIncludedDir = File(tempFolder.root, "symlinkIncluded")
    Files.createSymbolicLink(symlinkIncludedDir.toPath(), realIncludedDir.toPath())

    val rootBuild = createGradleBuild(symlinkRootDir, realRootDir.canonicalFile, emptyList())
    val includedBuild = createGradleBuild(symlinkIncludedDir, realIncludedDir.canonicalFile, emptyList())

    // BuildMap reports canonicalized Project.projectDir while GradleBuild.buildIdentifier.rootDir may remain the symlink path.
    val controller =
      createBuildController(
        mapOf(
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to realRootDir.canonicalFile)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to realIncludedDir.canonicalFile)),
        )
      )

    val buildPathMap = buildBuildPathMap(controller, listOf(rootBuild, includedBuild))

    assertThat(buildPathMap).containsExactly(":", BuildId(symlinkRootDir), ":included", BuildId(symlinkIncludedDir))
  }

  @Test
  fun `buildCompositeBuildMap uses buildIdentifier rootDir when rootProject projectDir is customized`() {
    val rootBuildDir = tempFolder.newFolder("rootBuild")
    val customRootProjectDir = File(rootBuildDir, "customRoot").apply { mkdirs() }
    val includedBuildDir = tempFolder.newFolder("includedBuild")
    val customIncludedProjectDir = File(includedBuildDir, "customIncluded").apply { mkdirs() }

    val rootApp = createBasicGradleProject(rootBuildDir, File(rootBuildDir, "app"), ":app")
    val includedLib = createBasicGradleProject(includedBuildDir, File(includedBuildDir, "lib"), ":lib")
    val rootBuild = createGradleBuild(rootBuildDir, customRootProjectDir, listOf(rootApp))
    val includedBuild = createGradleBuild(includedBuildDir, customIncludedProjectDir, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootBuild to BuildEnvironment::class.java) to mockBuildEnvironment("8.13"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to customRootProjectDir)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to customIncludedProjectDir)),
        )
      )

    val compositeBuildMap = buildCompositeBuildMap(controller, rootBuild, linkedSetOf(rootBuild, includedBuild))

    assertThat(compositeBuildMap.builds)
      .containsExactly(IdeBuildImpl(":", rootBuildDir), IdeBuildImpl(":included", includedBuildDir))
      .inOrder()
  }

  @Test
  fun `buildCompositeBuildMap deduplicates root build and uses buildIdentifier rootDir for symlinked builds`() {
    val realRootDir = tempFolder.newFolder("realRoot")
    val symlinkRootDir = File(tempFolder.root, "symlinkRoot")
    Files.createSymbolicLink(symlinkRootDir.toPath(), realRootDir.toPath())

    val realIncludedDir = tempFolder.newFolder("realIncluded")
    val symlinkIncludedDir = File(tempFolder.root, "symlinkIncluded")
    Files.createSymbolicLink(symlinkIncludedDir.toPath(), realIncludedDir.toPath())

    val rootApp = createBasicGradleProject(symlinkRootDir, File(realRootDir.canonicalFile, "app"), ":app")
    val includedLib = createBasicGradleProject(symlinkIncludedDir, File(realIncludedDir.canonicalFile, "lib"), ":lib")
    val rootBuild = createGradleBuild(symlinkRootDir, realRootDir.canonicalFile, listOf(rootApp))
    val includedBuild = createGradleBuild(symlinkIncludedDir, realIncludedDir.canonicalFile, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootBuild to BuildEnvironment::class.java) to mockBuildEnvironment("8.13"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to realRootDir.canonicalFile)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to realIncludedDir.canonicalFile)),
        )
      )

    val compositeBuildMap = buildCompositeBuildMap(controller, rootBuild, linkedSetOf(rootBuild, includedBuild))

    assertThat(compositeBuildMap.builds)
      .containsExactly(IdeBuildImpl(":", symlinkRootDir), IdeBuildImpl(":included", symlinkIncludedDir))
      .inOrder()
  }

  @Test
  fun `PhasedSyncProjectModelProvider reports AgpVersionsMismatch when rootProject projectDir is customized`() {
    val rootBuildDir = tempFolder.newFolder("rootBuild")
    val customRootProjectDir = File(rootBuildDir, "customRoot").apply { mkdirs() }
    val includedBuildDir = tempFolder.newFolder("includedBuild")
    val customIncludedProjectDir = File(includedBuildDir, "customIncluded").apply { mkdirs() }

    val rootApp = createBasicGradleProject(rootBuildDir, File(rootBuildDir, "app"), ":app")
    val includedLib = createBasicGradleProject(includedBuildDir, File(includedBuildDir, "lib"), ":lib")
    val rootBuild = createGradleBuild(rootBuildDir, customRootProjectDir, listOf(rootApp))
    val includedBuild = createGradleBuild(includedBuildDir, customIncludedProjectDir, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootApp to Versions::class.java) to mockVersions("8.7.0"),
          (includedLib to Versions::class.java) to mockVersions("8.8.0"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to customRootProjectDir)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to customIncludedProjectDir)),
        )
      )

    val syncErrors = mutableListOf<IdeAndroidSyncError>()
    val cachedModels = ModelProviderCachedData()
    PhasedSyncProjectModelProvider(createAllVariantsSyncOptions(), cachedModels)
      .populateModels(controller, mutableListOf(rootBuild, includedBuild), syncErrorConsumer(syncErrors))

    assertAgpVersionsMismatchError(syncErrors, "8.7.0", "8.8.0", ":", ":included")
    assertThat(cachedModels.hasSyncError()).isTrue()
  }

  @Test
  fun `PhasedSyncProjectModelProvider reports AgpVersionsMismatch for symlinked builds`() {
    val realRootDir = tempFolder.newFolder("realRoot")
    val symlinkRootDir = File(tempFolder.root, "symlinkRoot")
    Files.createSymbolicLink(symlinkRootDir.toPath(), realRootDir.toPath())

    val realIncludedDir = tempFolder.newFolder("realIncluded")
    val symlinkIncludedDir = File(tempFolder.root, "symlinkIncluded")
    Files.createSymbolicLink(symlinkIncludedDir.toPath(), realIncludedDir.toPath())

    val rootApp = createBasicGradleProject(symlinkRootDir, File(realRootDir.canonicalFile, "app"), ":app")
    val includedLib = createBasicGradleProject(symlinkIncludedDir, File(realIncludedDir.canonicalFile, "lib"), ":lib")
    val rootBuild = createGradleBuild(symlinkRootDir, realRootDir.canonicalFile, listOf(rootApp))
    val includedBuild = createGradleBuild(symlinkIncludedDir, realIncludedDir.canonicalFile, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootApp to Versions::class.java) to mockVersions("8.7.0"),
          (includedLib to Versions::class.java) to mockVersions("8.8.0"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to realRootDir.canonicalFile)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to realIncludedDir.canonicalFile)),
        )
      )

    val syncErrors = mutableListOf<IdeAndroidSyncError>()
    val cachedModels = ModelProviderCachedData()
    PhasedSyncProjectModelProvider(createAllVariantsSyncOptions(), cachedModels)
      .populateModels(controller, mutableListOf(rootBuild, includedBuild), syncErrorConsumer(syncErrors))

    assertAgpVersionsMismatchError(syncErrors, "8.7.0", "8.8.0", ":", ":included")
    assertThat(cachedModels.hasSyncError()).isTrue()
  }

  @Test
  fun `AndroidExtraModelProviderWorker resolves build paths when rootProject projectDir is customized`() {
    val rootBuildDir = tempFolder.newFolder("rootBuild")
    val customRootProjectDir = File(rootBuildDir, "customRoot").apply { mkdirs() }
    val includedBuildDir = tempFolder.newFolder("includedBuild")
    val customIncludedProjectDir = File(includedBuildDir, "customIncluded").apply { mkdirs() }

    val rootApp = createBasicGradleProject(rootBuildDir, File(rootBuildDir, "app"), ":app")
    val includedLib = createBasicGradleProject(includedBuildDir, File(includedBuildDir, "lib"), ":lib")
    val rootBuild = createGradleBuild(rootBuildDir, customRootProjectDir, listOf(rootApp))
    val includedBuild = createGradleBuild(includedBuildDir, customIncludedProjectDir, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootBuild to BuildEnvironment::class.java) to mockBuildEnvironment("8.13"),
          (rootApp to Versions::class.java) to mockVersions("8.7.0"),
          (includedLib to Versions::class.java) to mockVersions("8.8.0"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to customRootProjectDir)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to customIncludedProjectDir)),
        )
      )

    val compositeBuildMap = buildCompositeBuildMap(controller, rootBuild, linkedSetOf(rootBuild, includedBuild))
    val syncErrors = mutableListOf<IdeAndroidSyncError>()
    AndroidExtraModelProviderWorker(
        controller = controller,
        syncCounters = SyncCounters(),
        syncOptions = createAllVariantsSyncOptions(),
        buildInfo = BuildInfo(listOf(rootBuild, includedBuild), compositeBuildMap, BuildFolderPaths()),
        consumer = syncErrorConsumer(syncErrors),
      )
      .populateBuildModels()

    assertAgpVersionsMismatchError(syncErrors, "8.7.0", "8.8.0", ":", ":included")
  }

  @Test
  fun `AndroidExtraModelProviderWorker resolves build paths for symlinked builds`() {
    val realRootDir = tempFolder.newFolder("realRoot")
    val symlinkRootDir = File(tempFolder.root, "symlinkRoot")
    Files.createSymbolicLink(symlinkRootDir.toPath(), realRootDir.toPath())

    val realIncludedDir = tempFolder.newFolder("realIncluded")
    val symlinkIncludedDir = File(tempFolder.root, "symlinkIncluded")
    Files.createSymbolicLink(symlinkIncludedDir.toPath(), realIncludedDir.toPath())

    val rootApp = createBasicGradleProject(symlinkRootDir, File(realRootDir.canonicalFile, "app"), ":app")
    val includedLib = createBasicGradleProject(symlinkIncludedDir, File(realIncludedDir.canonicalFile, "lib"), ":lib")
    val rootBuild = createGradleBuild(symlinkRootDir, realRootDir.canonicalFile, listOf(rootApp))
    val includedBuild = createGradleBuild(symlinkIncludedDir, realIncludedDir.canonicalFile, listOf(includedLib))

    val controller =
      createBuildController(
        mapOf(
          (rootBuild to BuildEnvironment::class.java) to mockBuildEnvironment("8.13"),
          (rootApp to Versions::class.java) to mockVersions("8.7.0"),
          (includedLib to Versions::class.java) to mockVersions("8.8.0"),
          (rootBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":" to realRootDir.canonicalFile)),
          (includedBuild.rootProject to BuildMap::class.java) to BuildMapImpl(mapOf(":included" to realIncludedDir.canonicalFile)),
        )
      )

    val compositeBuildMap = buildCompositeBuildMap(controller, rootBuild, linkedSetOf(rootBuild, includedBuild))
    val syncErrors = mutableListOf<IdeAndroidSyncError>()
    AndroidExtraModelProviderWorker(
        controller = controller,
        syncCounters = SyncCounters(),
        syncOptions = createAllVariantsSyncOptions(),
        buildInfo = BuildInfo(listOf(rootBuild, includedBuild), compositeBuildMap, BuildFolderPaths()),
        consumer = syncErrorConsumer(syncErrors),
      )
      .populateBuildModels()

    assertAgpVersionsMismatchError(syncErrors, "8.7.0", "8.8.0", ":", ":included")
  }

  private fun assertAgpVersionsMismatchError(syncErrors: List<IdeAndroidSyncError>, vararg expectedFragments: String) {
    assertThat(syncErrors).hasSize(1)
    assertThat(syncErrors.single().type).isEqualTo(AndroidSyncExceptionType.AGP_VERSIONS_MISMATCH)
    for (fragment in expectedFragments) {
      assertThat(syncErrors.single().message).contains(fragment)
    }
  }

  private fun syncErrorConsumer(syncErrors: MutableList<IdeAndroidSyncError>): GradleModelConsumer =
    object : GradleModelConsumer {
      override fun consumeBuildModel(buildModel: BuildModel, `object`: Any, clazz: Class<*>) {
        if (clazz == IdeAndroidSyncError::class.java) {
          syncErrors += `object` as IdeAndroidSyncError
        }
      }

      override fun consumeProjectModel(projectModel: BasicGradleProject, `object`: Any, clazz: Class<*>) = Unit
    }

  private fun mockBuildEnvironment(gradleVersion: String): BuildEnvironment {
    val gradleEnv = Mockito.mock(GradleEnvironment::class.java).apply { whenever(this.gradleVersion).thenReturn(gradleVersion) }
    return Mockito.mock(BuildEnvironment::class.java).apply { whenever(gradle).thenReturn(gradleEnv) }
  }

  private fun mockVersions(agpVersion: String): Versions =
    Mockito.mock(Versions::class.java).apply {
      whenever(agp).thenReturn(agpVersion)
      whenever(versions).thenReturn(emptyMap())
    }

  private fun createBuildController(models: Map<Pair<Model, Class<*>>, Any?>): BuildController =
    Proxy.newProxyInstance(BuildController::class.java.classLoader, arrayOf(BuildController::class.java)) { proxy, method, args ->
      when (method.name) {
        "run" -> {
          @Suppress("UNCHECKED_CAST") val actions = args[0] as Collection<BuildAction<Any?>>
          actions.map { it.execute(proxy as BuildController) }
        }
        "findModel" -> {
          val target = args[0] as Model
          val modelType = args[1] as Class<*>
          if (args.size == 4) {
            val paramType = args[2] as Class<*>
            @Suppress("UNCHECKED_CAST") val action = args[3] as Action<Any>
            action.execute(Mockito.mock(paramType))
          }
          models[target to modelType]
        }
        else -> throw UnsupportedOperationException("Unexpected BuildController call: ${method.name}")
      }
    } as BuildController

  private fun createBasicGradleProject(rootDir: File, projectDir: File, path: String): BasicGradleProject {
    val buildId = Mockito.mock(BuildIdentifier::class.java)
    whenever(buildId.rootDir).thenReturn(rootDir)
    val projectId = Mockito.mock(ProjectIdentifier::class.java)
    whenever(projectId.buildIdentifier).thenReturn(buildId)
    whenever(projectId.projectPath).thenReturn(path)

    val project = Mockito.mock(BasicGradleProject::class.java)
    whenever(project.path).thenReturn(path)
    whenever(project.projectDirectory).thenReturn(projectDir)
    whenever(project.projectIdentifier).thenReturn(projectId)
    whenever(project.children).thenReturn(domainObjectSetOf(emptyList()))
    return project
  }

  private fun createGradleBuild(
    buildRootDir: File,
    rootProjectDir: File,
    subprojects: List<BasicGradleProject>,
  ): GradleBuild {
    val buildId = Mockito.mock(BuildIdentifier::class.java)
    whenever(buildId.rootDir).thenReturn(buildRootDir)
    val rootProject = createBasicGradleProject(buildRootDir, rootProjectDir, ":")
    whenever(rootProject.children).thenReturn(domainObjectSetOf(subprojects))

    val gradleBuild = Mockito.mock(GradleBuild::class.java)
    whenever(gradleBuild.buildIdentifier).thenReturn(buildId)
    whenever(gradleBuild.rootProject).thenReturn(rootProject)
    whenever(gradleBuild.projects).thenReturn(domainObjectSetOf(listOf(rootProject) + subprojects))
    whenever(gradleBuild.includedBuilds).thenReturn(domainObjectSetOf(emptyList()))
    return gradleBuild
  }

  private fun <T> domainObjectSetOf(items: List<T>): DomainObjectSet<T> {
    val backingSet = LinkedHashSet(items)
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(DomainObjectSet::class.java.classLoader, arrayOf(DomainObjectSet::class.java)) { _, method, args ->
      when (method.name) {
        "getAll" -> items
        "getAt" -> items[args[0] as Int]
        else -> method.invoke(backingSet, *(args ?: emptyArray()))
      }
    } as DomainObjectSet<T>
  }

  private fun createAllVariantsSyncOptions(): AllVariantsSyncActionOptions {
    val flags =
      GradleSyncStudioFlags(
        modelConsumerVersion = ModelConsumerVersion(1, 0, "test"),
        studioLatestKnownAgpVersion = "9.0.0",
        studioFlagParallelSyncEnabled = false,
        studioFlagParallelSyncPrefetchVariantsEnabled = false,
        studioFlagUseV2BuilderModels = true,
        studioFlagDisableForcedUpgrades = false,
        studioFlagSyncStatsOutputDirectory = "",
        studioHprofOutputDirectory = "",
        studioHeapAnalysisOutputDirectory = "",
        studioHeapAnalysisLightweightMode = false,
        studioFlagMultiVariantAdditionalArtifactSupport = false,
        studioFlagSkipRuntimeClasspathForLibraries = false,
        studioFlagBuildRuntimeClasspathForLibraryUnitTests = false,
        studioFlagBuildRuntimeClasspathForLibraryScreenshotTests = false,
        studioFlagSupportFutureAgpVersions = false,
        studioFlagFetchKotlinModelsInParallel = true,
        studioFlagUsedPhasedSyncVariantResolution = true,
        studioFlagPhasedSyncDependencyResolutionEnabled = true,
      )
    return AllVariantsSyncActionOptions(
      flags = flags,
      syncTestMode = SyncTestMode.PRODUCTION,
      additionalClassifierArtifactsAction = AdditionalClassifierArtifactsActionOptions(emptyList()),
    )
  }
}
