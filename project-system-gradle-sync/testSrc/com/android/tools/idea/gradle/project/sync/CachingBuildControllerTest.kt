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

import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.builder.model.v2.models.VariantDependencies
import com.android.builder.model.v2.models.Versions
import com.android.builder.model.v2.models.ndk.NativeModelBuilderParameter
import com.android.builder.model.v2.models.ndk.NativeModule
import com.android.ide.gradle.model.AdditionalClassifierArtifactsModelParameter
import com.android.ide.gradle.model.ArtifactIdentifierImpl
import com.android.ide.gradle.model.LegacyAndroidGradlePluginProperties
import com.android.ide.gradle.model.LegacyAndroidGradlePluginPropertiesModelParameters
import com.android.ide.gradle.model.artifacts.AdditionalClassifierArtifactsModel
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.BuildModel
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.ProjectIdentifier
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.jetbrains.kotlin.idea.gradleTooling.KotlinGradleModel
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptGradleModel
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider.GradleModelConsumer
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

class CachingBuildControllerTest {
  @get:Rule val tempFolder = TemporaryFolder()
  private val cachedModels = ModelProviderCachedData(disableLegacyModelProvidersForSupportedProjects = false)
  private val gradle = FakeGradle()
  private val app = gradleProject(buildRootDir = File("/root"), projectPath = ":app")

  @Test
  fun `models without parameters are served from the cache to all model providers`() {
    val first = gradle.controller().toCachingController(cachedModels).findModel(app, Versions::class.java)
    val second = gradle.controller().toCachingController(cachedModels).getModel(app, Versions::class.java)

    assertThat(second).isSameAs(first)
    assertThat(gradle.requests).hasSize(1)
  }

  @Test
  fun `null results are cached for findModel but getModel is delegated again`() {
    gradle.nullModels += NativeModule::class.java
    val controller = gradle.controller().toCachingController(cachedModels)

    assertThat(controller.findModel(app, NativeModule::class.java)).isNull()
    assertThat(controller.findModel(app, NativeModule::class.java)).isNull()
    assertThat(gradle.requests).hasSize(1)

    assertThat(controller.getModel(app, NativeModule::class.java)).isNull()
    assertThat(gradle.requests).hasSize(2)
  }

  @Test
  fun `models are cached per project and build`() {
    val lib = gradleProject(buildRootDir = File("/root"), projectPath = ":lib")
    val includedApp = gradleProject(buildRootDir = File("/included"), projectPath = ":app")
    val sameApp = gradleProject(buildRootDir = File("/root"), projectPath = ":app")
    val controller = gradle.controller().toCachingController(cachedModels)

    val appModel = controller.findModel(app, BasicAndroidProject::class.java)

    assertThat(controller.findModel(lib, BasicAndroidProject::class.java)).isNotSameAs(appModel)
    assertThat(controller.findModel(includedApp, BasicAndroidProject::class.java)).isNotSameAs(appModel)
    assertThat(controller.findModel(sameApp, BasicAndroidProject::class.java)).isSameAs(appModel)
    assertThat(gradle.requests).hasSize(3)
  }

  @Test
  fun `models with ModelBuilderService Parameter are cached by parameter value`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    fun findKaptModel(value: String) =
      controller.findModel(app, KaptGradleModel::class.java, ModelBuilderService.Parameter::class.java) { it.value = value }

    val debug = findKaptModel("debug,debugUnitTest")

    assertThat(findKaptModel("debug,debugUnitTest")).isSameAs(debug)
    assertThat(findKaptModel("release,releaseUnitTest")).isNotSameAs(debug)
    assertThat(gradle.requests).hasSize(2)
  }

  @Test
  fun `models with ModelBuilderParameter are cached by parameter values`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    fun findVariantDependencies(variantName: String, dontBuildRuntimeClasspath: Boolean) =
      controller.findModel(app, VariantDependencies::class.java, ModelBuilderParameter::class.java) {
        it.variantName = variantName
        it.dontBuildRuntimeClasspath = dontBuildRuntimeClasspath
      }

    val debug = findVariantDependencies("debug", dontBuildRuntimeClasspath = false)

    assertThat(findVariantDependencies("debug", dontBuildRuntimeClasspath = false)).isSameAs(debug)
    assertThat(findVariantDependencies("debug", dontBuildRuntimeClasspath = true)).isNotSameAs(debug)
    assertThat(findVariantDependencies("release", dontBuildRuntimeClasspath = false)).isNotSameAs(debug)
    assertThat(gradle.requests).hasSize(3)
  }

  @Test
  fun `models with LegacyAndroidGradlePluginPropertiesModelParameters are cached by parameter values`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    fun findLegacyProperties(namespace: Boolean) =
      controller.findModel(
        app,
        LegacyAndroidGradlePluginProperties::class.java,
        LegacyAndroidGradlePluginPropertiesModelParameters::class.java,
      ) {
        it.namespace = namespace
      }

    val legacyProperties = findLegacyProperties(namespace = true)

    assertThat(findLegacyProperties(namespace = true)).isSameAs(legacyProperties)
    assertThat(findLegacyProperties(namespace = false)).isNotSameAs(legacyProperties)
    assertThat(gradle.requests).hasSize(2)
  }

  @Test
  fun `models requested only once per sync are always delegated`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    fun findNativeModule() =
      controller.findModel(app, NativeModule::class.java, NativeModelBuilderParameter::class.java) {
        it.variantsToGenerateBuildInformation = emptyList()
        it.abisToGenerateBuildInformation = emptyList()
      }
    fun findAdditionalClassifierArtifacts() =
      controller.findModel(app, AdditionalClassifierArtifactsModel::class.java, AdditionalClassifierArtifactsModelParameter::class.java) {
        it.artifactIdentifiers = listOf(ArtifactIdentifierImpl("com.google.guava", "guava", "33.0"))
      }

    assertThat(findNativeModule()).isNotSameAs(findNativeModule())
    assertThat(findAdditionalClassifierArtifacts()).isNotSameAs(findAdditionalClassifierArtifacts())
    assertThat(gradle.requests).hasSize(4)
    assertThat(cachedModels.modelCache).isEmpty()
  }

  @Test
  fun `requests targeting a project with an unrecognized parameter type fail`() {
    val controller = gradle.controller().toCachingController(cachedModels)

    val exception =
      assertFailsWith<IllegalStateException> {
        controller.findModel(app, Versions::class.java, UnknownParameter::class.java) { it.value = "value" }
      }

    assertThat(exception.message).contains(UnknownParameter::class.java.name)
    assertThat(gradle.requests).isEmpty()
  }

  @Test
  fun `ProjectSyncIssues and models not targeting a project are not cached`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    val gradleBuild = Mockito.mock(GradleBuild::class.java)

    assertThat(controller.findModel(app, ProjectSyncIssues::class.java))
      .isNotSameAs(controller.findModel(app, ProjectSyncIssues::class.java))
    assertThat(controller.findModel(gradleBuild, Versions::class.java)).isNotSameAs(controller.findModel(gradleBuild, Versions::class.java))
    // Requests not targeting a project are passed through, whatever their parameter type.
    controller.findModel(gradleBuild, Versions::class.java, UnknownParameter::class.java) { it.value = "value" }
    assertThat(gradle.requests).hasSize(5)
    assertThat(cachedModels.modelCache).isEmpty()
  }

  @Test
  fun `models requested by nested build actions are cached`() {
    val controller = gradle.controller().toCachingController(cachedModels)

    val models = controller.run(List(2) { BuildAction { nestedController -> nestedController.findModel(app, Versions::class.java) } })

    assertThat(models[1]).isSameAs(models[0])
    assertThat(controller.findModel(app, Versions::class.java)).isSameAs(models[0])
    assertThat(gradle.requests).hasSize(1)
  }

  @Test
  fun `clear drops the cached models`() {
    val controller = gradle.controller().toCachingController(cachedModels)
    val model = controller.findModel(app, Versions::class.java)

    cachedModels.clear()

    assertThat(controller.findModel(app, Versions::class.java)).isNotSameAs(model)
    assertThat(gradle.requests).hasSize(2)
  }

  @Test
  fun `PhasedSyncAdditionalModelProvider populates cache for legacy providers without recording ANDROID_FINISHED`() {
    val statsDir = tempFolder.newFolder("stats")
    val javaLib = gradleProject(buildRootDir = File("/root"), projectPath = ":javaLib")
    val unsupportedAndroidApp = gradleProject(buildRootDir = File("/root"), projectPath = ":unsupportedAndroidApp")
    val gradleBuild = gradleBuild(buildRootDir = File("/root"), projects = listOf(app, javaLib, unsupportedAndroidApp))
    val options = syncOptions(statsDir = statsDir.absolutePath)
    cachedModels.versions[app] = Mockito.mock(Versions::class.java)
    cachedModels.data[app] =
      CachedAndroidProjectData(
        modelVersions = Mockito.mock(ModelVersions::class.java),
        selectedVariantName = "debug",
        ideAndroidProject = Mockito.mock(IdeAndroidProjectImpl::class.java),
        shouldSkipRuntimeClassPathForLibraries = false,
        allOutgoingProjectDependencies = emptyList(),
      )
    cachedModels.fetchedVariants[app] = emptyList()
    gradle.nullProjectModels += (javaLib to Versions::class.java)

    PhasedSyncAdditionalModelProvider(options, cachedModels).populateModels(gradle.controller(), listOf(gradleBuild), NoOpConsumer)

    // When legacy providers will run after PhasedSyncAdditionalModelProvider, it must keep the cache populated and leave
    // ANDROID_FINISHED to AndroidExtraModelProvider.
    assertThat(cachedModels.shouldRunLegacyModelProviders).isTrue()
    assertThat(statsDir.listFiles().orEmpty().filter { it.name.endsWith(MeasurementCheckpoint.ANDROID_FINISHED.name) }).isEmpty()

    // Unsupported Android modules (where cachedModels.versions[project] == null, but Versions is present) must not be queried as
    // non-Android Java modules without variant parameters.
    assertThat(cachedModels.modelCache.keys.map { it.projectPath to it.modelType })
      .doesNotContain(":unsupportedAndroidApp" to KotlinGradleModel::class.java)
    assertThat(cachedModels.modelCache.keys.map { it.projectPath to it.modelType })
      .doesNotContain(":unsupportedAndroidApp" to KaptGradleModel::class.java)

    val requestsAfterPhasedSync = gradle.requests.size
    val cachingController = gradle.controller().toCachingController(cachedModels)
    assertThat(cachingController.fetchModel<KotlinGradleModel>(app, options, "debug")).isNotNull()
    assertThat(cachingController.findModel(javaLib, KotlinGradleModel::class.java)).isNotNull()
    assertThat(cachingController.findModel(javaLib, KaptGradleModel::class.java)).isNotNull()
    assertThat(gradle.requests).hasSize(requestsAfterPhasedSync)
  }

  @Test
  fun `PhasedSyncAdditionalModelProvider records ANDROID_FINISHED and clears cache when legacy providers are disabled`() {
    val statsDir = tempFolder.newFolder("stats_phased_only")
    val phasedOnlyCache =
      ModelProviderCachedData(disableLegacyModelProvidersForSupportedProjects = true).apply {
        markAllProjectsSupportedByPhasedSync()
        versions[app] = Mockito.mock(Versions::class.java)
      }
    val gradleBuild = gradleBuild(buildRootDir = File("/root"), projects = listOf(app))

    PhasedSyncAdditionalModelProvider(syncOptions(statsDir = statsDir.absolutePath), phasedOnlyCache)
      .populateModels(gradle.controller(), listOf(gradleBuild), NoOpConsumer)

    assertThat(phasedOnlyCache.shouldRunLegacyModelProviders).isFalse()
    assertThat(statsDir.listFiles().orEmpty().filter { it.name.endsWith(MeasurementCheckpoint.ANDROID_FINISHED.name) }).hasSize(1)
    assertThat(phasedOnlyCache.modelCache).isEmpty()
  }

  interface UnknownParameter {
    var value: String
  }

  /** A fake of the Gradle side of [BuildController], which returns a new model instance for each model request. */
  private class FakeGradle {
    val requests = mutableListOf<Class<*>>()
    val nullModels = mutableSetOf<Class<*>>()
    val nullProjectModels = mutableSetOf<Pair<Any?, Class<*>>>()

    fun controller(): BuildController =
      Mockito.mock(BuildController::class.java) { invocation ->
        when (invocation.method.name) {
          "findModel",
          "getModel" -> {
            val target = invocation.arguments[0]
            val modelType = invocation.arguments[1] as Class<*>
            requests += modelType
            if (modelType in nullModels || (target to modelType) in nullProjectModels) null else Mockito.mock(modelType)
          }
          "run" -> (invocation.arguments[0] as Collection<*>).map { (it as BuildAction<*>).execute(controller()) }
          else -> null
        }
      }
  }

  private fun gradleProject(buildRootDir: File, projectPath: String): BasicGradleProject {
    val buildIdentifier = Mockito.mock(BuildIdentifier::class.java)
    whenever(buildIdentifier.rootDir).thenReturn(buildRootDir)
    val projectIdentifier = Mockito.mock(ProjectIdentifier::class.java)
    whenever(projectIdentifier.buildIdentifier).thenReturn(buildIdentifier)
    whenever(projectIdentifier.projectPath).thenReturn(projectPath)
    val gradleProject = Mockito.mock(BasicGradleProject::class.java)
    whenever(gradleProject.projectIdentifier).thenReturn(projectIdentifier)
    return gradleProject
  }

  private fun gradleBuild(buildRootDir: File, projects: List<BasicGradleProject>): GradleBuild {
    val buildIdentifier = Mockito.mock(BuildIdentifier::class.java)
    whenever(buildIdentifier.rootDir).thenReturn(buildRootDir)
    val domainProjects = Mockito.mock(DomainObjectSet::class.java) as DomainObjectSet<BasicGradleProject>
    whenever(domainProjects.iterator()).thenAnswer { projects.iterator() }
    val gradleBuild = Mockito.mock(GradleBuild::class.java)
    whenever(gradleBuild.buildIdentifier).thenReturn(buildIdentifier)
    whenever(gradleBuild.projects).thenReturn(domainProjects)
    return gradleBuild
  }

  private fun syncOptions(statsDir: String): SingleVariantSyncActionOptions =
    SingleVariantSyncActionOptions(
      flags =
        GradleSyncStudioFlags(
          modelConsumerVersion = ModelConsumerVersion(1, 0, "test"),
          studioLatestKnownAgpVersion = "9.0.0",
          studioFlagParallelSyncEnabled = true,
          studioFlagParallelSyncPrefetchVariantsEnabled = false,
          studioFlagUseV2BuilderModels = true,
          studioFlagDisableForcedUpgrades = false,
          studioFlagSyncStatsOutputDirectory = statsDir,
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
          studioFlagPhasedSyncEnabled = true,
        ),
      syncTestMode = SyncTestMode.PRODUCTION,
      selectedVariants = SelectedVariants(emptyMap()),
      switchVariantRequest = null,
      additionalClassifierArtifactsAction = AdditionalClassifierArtifactsActionOptions(emptyList()),
    )

  private object NoOpConsumer : GradleModelConsumer {
    override fun consumeBuildModel(buildModel: BuildModel, `object`: Any, clazz: Class<*>) = Unit

    override fun consumeProjectModel(projectModel: BasicGradleProject, `object`: Any, clazz: Class<*>) = Unit
  }
}
