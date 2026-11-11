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
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Proxy
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

class PhasedSyncExecutionReportTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `parallelFetchForV2ModelsEnabled requires Gradle 7_4_2`() {
    assertThat(runReport(gradleVersion = "7.4.2", parallelFlag = true)).isTrue()
    assertThat(runReport(gradleVersion = "8.0", parallelFlag = true)).isTrue()
    assertThat(runReport(gradleVersion = "7.4", parallelFlag = true)).isFalse()
    assertThat(runReport(gradleVersion = "7.3.3", parallelFlag = true)).isFalse()
    assertThat(runReport(gradleVersion = "8.0", parallelFlag = false)).isFalse()
  }

  private fun runReport(gradleVersion: String, parallelFlag: Boolean): Boolean {
    val rootDir = tempFolder.newFolder()
    val appDir = File(rootDir, "app").apply { mkdirs() }
    val appProject = createBasicGradleProject(rootDir, appDir, ":app")
    val gradleBuild = createGradleBuild(rootDir, listOf(appProject))

    val gradleEnv = Mockito.mock(GradleEnvironment::class.java).apply { whenever(this.gradleVersion).thenReturn(gradleVersion) }
    val buildEnv = Mockito.mock(BuildEnvironment::class.java).apply { whenever(this.gradle).thenReturn(gradleEnv) }
    val versions = Mockito.mock(Versions::class.java)

    val cachedModels = ModelProviderCachedData(disableLegacyModelProvidersForSupportedProjects = true)
    cachedModels.markAllProjectsSupportedByPhasedSync()
    cachedModels.versions[appProject] = versions

    val controller = createBuildController(mapOf((gradleBuild to BuildEnvironment::class.java) to buildEnv))
    val buildModels = mutableListOf<Any>()
    val consumer =
      object : GradleModelConsumer {
        override fun consumeBuildModel(buildModel: BuildModel, `object`: Any, clazz: Class<*>) {
          if (clazz == IdeSyncExecutionReport::class.java) {
            buildModels += `object`
          }
        }

        override fun consumeProjectModel(projectModel: BasicGradleProject, `object`: Any, clazz: Class<*>) = Unit
      }

    PhasedSyncAdditionalModelProvider(createSingleVariantSyncOptions(parallelFlag), cachedModels)
      .populateModels(controller, listOf(gradleBuild), consumer)

    return (buildModels.single() as IdeSyncExecutionReport).parallelFetchForV2ModelsEnabled
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

  private fun createGradleBuild(rootDir: File, projects: List<BasicGradleProject>): GradleBuild {
    val buildId = Mockito.mock(BuildIdentifier::class.java)
    whenever(buildId.rootDir).thenReturn(rootDir)
    val rootProject = createBasicGradleProject(rootDir, rootDir, ":")
    whenever(rootProject.children).thenReturn(domainObjectSetOf(projects))

    val gradleBuild = Mockito.mock(GradleBuild::class.java)
    whenever(gradleBuild.buildIdentifier).thenReturn(buildId)
    whenever(gradleBuild.rootProject).thenReturn(rootProject)
    whenever(gradleBuild.projects).thenReturn(domainObjectSetOf(projects))
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

  private fun createSingleVariantSyncOptions(parallelFlag: Boolean): SingleVariantSyncActionOptions {
    val flags =
      GradleSyncStudioFlags(
        modelConsumerVersion = ModelConsumerVersion(1, 0, "test"),
        studioLatestKnownAgpVersion = "9.0.0",
        studioFlagParallelSyncEnabled = parallelFlag,
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
    return SingleVariantSyncActionOptions(
      flags = flags,
      syncTestMode = SyncTestMode.PRODUCTION,
      selectedVariants = SelectedVariants(emptyMap()),
      switchVariantRequest = null,
      additionalClassifierArtifactsAction = AdditionalClassifierArtifactsActionOptions(emptyList()),
    )
  }
}
