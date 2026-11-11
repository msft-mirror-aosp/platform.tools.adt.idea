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
import com.android.ide.common.repository.AgpVersion
import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.IdeArtifactName
import com.android.tools.idea.gradle.model.impl.FileImpl
import com.android.tools.idea.gradle.model.impl.IdeAndroidArtifactCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeAndroidGradlePluginProjectFlagsImpl
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.model.impl.IdeApiVersionImpl
import com.android.tools.idea.gradle.model.impl.IdeDependenciesCoreDirect
import com.android.tools.idea.gradle.model.impl.IdeJavaArtifactCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeProjectPathImpl
import com.android.tools.idea.gradle.model.impl.IdeSourceProviderContainerImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.model.impl.toImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Proxy
import org.gradle.api.Action
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.BuildModel
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.ProjectIdentifier
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptGradleModel
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptSourceSetModel
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider.GradleModelConsumer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

class PhasedSyncKaptPatchTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `PhasedSyncAdditionalModelProvider patches IdeAndroidModels for KAPT`() {
    val rootDir = tempFolder.newFolder("project")
    val appDir = File(rootDir, "app").apply { mkdirs() }
    val kaptDir = File(appDir, "build/generated/source/kaptKotlin/debug")
    val kaptUnitTestDir = File(appDir, "build/generated/source/kaptKotlin/debugUnitTest")

    val appProject = createBasicGradleProject(rootDir, appDir, ":app")
    val gradleBuild = createGradleBuild(rootDir, listOf(appProject))

    val kaptDebugSourceSet =
      Mockito.mock(KaptSourceSetModel::class.java).apply {
        whenever(sourceSetName).thenReturn("debug")
        whenever(generatedKotlinSourcesDirFile).thenReturn(kaptDir)
      }
    val kaptUnitTestSourceSet =
      Mockito.mock(KaptSourceSetModel::class.java).apply {
        whenever(sourceSetName).thenReturn("debugUnitTest")
        whenever(generatedKotlinSourcesDirFile).thenReturn(kaptUnitTestDir)
      }
    val kaptModel =
      Mockito.mock(KaptGradleModel::class.java).apply {
        whenever(isEnabled).thenReturn(true)
        whenever(sourceSets).thenReturn(listOf(kaptDebugSourceSet, kaptUnitTestSourceSet))
      }

    val cachedModels = ModelProviderCachedData(disableLegacyModelProvidersForSupportedProjects = true)
    cachedModels.markAllProjectsSupportedByPhasedSync()
    val versions =
      Mockito.mock(Versions::class.java).apply {
        whenever(agp).thenReturn("8.0.0")
        whenever(versions).thenReturn(emptyMap())
      }
    cachedModels.versions[appProject] = versions
    val initialVariant = createTestVariant()
    cachedModels.fetchedVariants[appProject] = listOf(initialVariant)
    cachedModels.data[appProject] =
      CachedAndroidProjectData(
        selectedVariantName = "debug",
        modelVersions = ModelVersions(AgpVersion.parse("8.0.0"), ModelVersion(Int.MIN_VALUE, Int.MIN_VALUE, "8.0.0"), null),
        ideAndroidProject = createTestAndroidProject(rootDir, appDir, listOf(initialVariant)),
        shouldSkipRuntimeClassPathForLibraries = false,
        allOutgoingProjectDependencies = emptyList(),
      )
    cachedModels.setKaptGradleModel(appProject, kaptModel)

    val patchedProject = cachedModels.data[appProject]!!.ideAndroidProject as IdeAndroidProjectImpl
    assertThat(patchedProject.isKaptEnabled).isTrue()
    val patchedCoreVariant = patchedProject.coreVariants.single() as IdeVariantCoreImpl
    assertThat(patchedCoreVariant.mainArtifact.generatedSourceFolders).contains(kaptDir.toImpl())
    assertThat(patchedCoreVariant.hostTestArtifacts.single().generatedSourceFolders).contains(kaptUnitTestDir.toImpl())

    val controller = createEmptyBuildController()
    val consumedProjectModels = mutableListOf<Triple<BasicGradleProject, Class<*>, Any>>()
    val consumer =
      object : GradleModelConsumer {
        override fun consumeBuildModel(buildModel: BuildModel, `object`: Any, clazz: Class<*>) = Unit

        override fun consumeProjectModel(projectModel: BasicGradleProject, `object`: Any, clazz: Class<*>) {
          consumedProjectModels += Triple(projectModel, clazz, `object`)
        }
      }

    PhasedSyncAdditionalModelProvider(createSingleVariantSyncOptions(), cachedModels)
      .populateModels(controller, listOf(gradleBuild), consumer)

    val ideAndroidModels = consumedProjectModels.single { it.second == IdeAndroidModels::class.java }.third as IdeAndroidModels
    assertThat(ideAndroidModels.androidProject.isKaptEnabled).isTrue()
    val variant = ideAndroidModels.fetchedVariants.single()
    assertThat(variant.mainArtifact.generatedSourceFolders).contains(kaptDir.toImpl())
    assertThat(variant.hostTestArtifacts.single().generatedSourceFolders).contains(kaptUnitTestDir.toImpl())
  }

  @Test
  fun `PhasedSyncAdditionalModelProvider populates all fetched variants in AllVariantsSyncActionOptions`() {
    val rootDir = tempFolder.newFolder("projectAllVariants")
    val appDir = File(rootDir, "app").apply { mkdirs() }
    val kaptDebugDir = File(appDir, "build/generated/source/kaptKotlin/debug")
    val kaptReleaseDir = File(appDir, "build/generated/source/kaptKotlin/release")

    val appProject = createBasicGradleProject(rootDir, appDir, ":app")
    val gradleBuild = createGradleBuild(rootDir, listOf(appProject))

    val kaptDebugSourceSet =
      Mockito.mock(KaptSourceSetModel::class.java).apply {
        whenever(sourceSetName).thenReturn("debug")
        whenever(generatedKotlinSourcesDirFile).thenReturn(kaptDebugDir)
      }
    val kaptReleaseSourceSet =
      Mockito.mock(KaptSourceSetModel::class.java).apply {
        whenever(sourceSetName).thenReturn("release")
        whenever(generatedKotlinSourcesDirFile).thenReturn(kaptReleaseDir)
      }
    val kaptModel =
      Mockito.mock(KaptGradleModel::class.java).apply {
        whenever(isEnabled).thenReturn(true)
        whenever(sourceSets).thenReturn(listOf(kaptDebugSourceSet, kaptReleaseSourceSet))
      }

    val cachedModels = ModelProviderCachedData(disableLegacyModelProvidersForSupportedProjects = true)
    cachedModels.markAllProjectsSupportedByPhasedSync()
    val versions =
      Mockito.mock(Versions::class.java).apply {
        whenever(agp).thenReturn("8.0.0")
        whenever(versions).thenReturn(emptyMap())
      }
    cachedModels.versions[appProject] = versions
    val debugVariant = createTestVariant("debug")
    val releaseVariant = createTestVariant("release")
    cachedModels.fetchedVariants[appProject] = listOf(debugVariant, releaseVariant)
    cachedModels.data[appProject] =
      CachedAndroidProjectData(
        selectedVariantName = "debug",
        modelVersions = ModelVersions(AgpVersion.parse("8.0.0"), ModelVersion(Int.MIN_VALUE, Int.MIN_VALUE, "8.0.0"), null),
        ideAndroidProject = createTestAndroidProject(rootDir, appDir, listOf(debugVariant, releaseVariant)),
        shouldSkipRuntimeClassPathForLibraries = false,
        allOutgoingProjectDependencies = emptyList(),
      )
    cachedModels.setKaptGradleModel(appProject, kaptModel)

    val controller = createEmptyBuildController()
    val consumedProjectModels = mutableListOf<Triple<BasicGradleProject, Class<*>, Any>>()
    val consumer =
      object : GradleModelConsumer {
        override fun consumeBuildModel(buildModel: BuildModel, `object`: Any, clazz: Class<*>) = Unit

        override fun consumeProjectModel(projectModel: BasicGradleProject, `object`: Any, clazz: Class<*>) {
          consumedProjectModels += Triple(projectModel, clazz, `object`)
        }
      }

    val allVariantsOptions =
      AllVariantsSyncActionOptions(
        flags = createSingleVariantSyncOptions().flags,
        syncTestMode = SyncTestMode.PRODUCTION,
        additionalClassifierArtifactsAction = AdditionalClassifierArtifactsActionOptions(emptyList()),
      )
    PhasedSyncAdditionalModelProvider(allVariantsOptions, cachedModels).populateModels(controller, listOf(gradleBuild), consumer)

    val ideAndroidModels = consumedProjectModels.single { it.second == IdeAndroidModels::class.java }.third as IdeAndroidModels
    assertThat(ideAndroidModels.selectedVariantName).isEqualTo("debug")
    assertThat(ideAndroidModels.fetchedVariants.map { it.name }).containsExactly("debug", "release").inOrder()
    assertThat(ideAndroidModels.fetchedVariants[0].mainArtifact.generatedSourceFolders).contains(kaptDebugDir.toImpl())
    assertThat(ideAndroidModels.fetchedVariants[1].mainArtifact.generatedSourceFolders).contains(kaptReleaseDir.toImpl())
  }

  private fun createTestAndroidProject(
    rootDir: File,
    appDir: File,
    coreVariants: List<IdeVariantCoreImpl> = emptyList(),
  ): IdeAndroidProjectImpl =
    IdeAndroidProjectImpl(
      agpVersion = "8.0.0",
      projectPath = IdeProjectPathImpl(rootBuildId = rootDir.toImpl(), buildId = rootDir.toImpl(), projectPath = ":app"),
      projectType = IdeAndroidProjectType.PROJECT_TYPE_APP,
      defaultSourceProvider = IdeSourceProviderContainerImpl(sourceProvider = null, extraSourceProviders = emptyList()),
      multiVariantData = null,
      basicVariants = emptyList(),
      coreVariants = coreVariants,
      flavorDimensions = emptyList(),
      compileTarget = "android-34",
      bootClasspath = emptyList(),
      signingConfigs = emptyList(),
      lintOptions = null,
      javaCompileOptions = null,
      buildFolder = File(appDir, "build").toImpl(),
      resourcePrefix = null,
      buildToolsVersion = "34.0.0",
      isBaseSplit = true,
      dynamicFeatures = emptyList(),
      baseFeature = null,
      viewBindingOptions = null,
      dependenciesInfo = null,
      groupId = "com.example",
      namespace = "com.example.app",
      agpFlags = Mockito.mock(IdeAndroidGradlePluginProjectFlagsImpl::class.java),
      variantsBuildInformation = emptyList(),
      lintChecksJars = emptyList(),
      testNamespace = null,
      isKaptEnabled = false,
      desugarLibraryConfigFiles = emptyList(),
      defaultVariantName = "debug",
      lintJar = null,
      testSuites = emptyList(),
    )

  private fun createTestVariant(variantName: String = "debug"): IdeVariantCoreImpl {
    val mainArtifact =
      IdeAndroidArtifactCoreImpl(
        name = IdeArtifactName.MAIN,
        compileTaskName = "compile${variantName.replaceFirstChar { it.uppercase() }}JavaWithJavac",
        assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercase() }}",
        classesFolder = emptyList<FileImpl>(),
        variantSourceProvider = null,
        multiFlavorSourceProvider = null,
        ideSetupTaskNames = emptyList(),
        generatedSourceFolders = emptyList<FileImpl>(),
        isTestArtifact = false,
        compileClasspathCore = IdeDependenciesCoreDirect(emptyList()),
        runtimeClasspathCore = IdeDependenciesCoreDirect(emptyList()),
        unresolvedDependencies = emptyList(),
        applicationId = "com.example.app",
        signingConfigName = null,
        isSigned = false,
        generatedResourceFolders = emptyList<FileImpl>(),
        additionalRuntimeApks = emptyList<FileImpl>(),
        testOptions = null,
        abiFilters = emptySet(),
        buildInformation = Mockito.mock(com.android.tools.idea.gradle.model.impl.IdeBuildTasksAndOutputInformationImpl::class.java),
        codeShrinker = null,
        desugaredMethodsFiles = emptyList<FileImpl>(),
        generatedClassPaths = emptyMap<String, FileImpl>(),
        bytecodeTransforms = null,
        generatedAssetFolders = emptyList<FileImpl>(),
        mappingR8TextFile = null,
        mappingR8PartitionFile = null,
      )
    val unitTestArtifact =
      IdeJavaArtifactCoreImpl(
        name = IdeArtifactName.UNIT_TEST,
        compileTaskName = "compile${variantName.replaceFirstChar { it.uppercase() }}UnitTestJavaWithJavac",
        assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercase() }}UnitTest",
        classesFolder = emptyList<FileImpl>(),
        variantSourceProvider = null,
        multiFlavorSourceProvider = null,
        ideSetupTaskNames = emptyList(),
        generatedSourceFolders = emptyList<FileImpl>(),
        isTestArtifact = true,
        compileClasspathCore = IdeDependenciesCoreDirect(emptyList()),
        runtimeClasspathCore = IdeDependenciesCoreDirect(emptyList()),
        unresolvedDependencies = emptyList(),
        mockablePlatformJar = null,
        generatedClassPaths = emptyMap<String, FileImpl>(),
        bytecodeTransforms = null,
      )
    return IdeVariantCoreImpl(
      name = variantName,
      displayName = variantName,
      mainArtifact = mainArtifact,
      testSuiteArtifacts = emptyList(),
      hostTestArtifacts = listOf(unitTestArtifact),
      deviceTestArtifacts = emptyList(),
      testFixturesArtifact = null,
      buildType = variantName,
      productFlavors = emptyList(),
      minSdkVersion = Mockito.mock(IdeApiVersionImpl::class.java),
      targetSdkVersion = null,
      maxSdkVersion = null,
      versionCode = 1,
      versionNameWithSuffix = "1.0",
      versionNameSuffix = null,
      instantAppCompatible = false,
      vectorDrawablesUseSupportLibrary = false,
      resourceConfigurations = emptyList(),
      resValues = emptyMap(),
      proguardFiles = emptyList(),
      consumerProguardFiles = emptyList(),
      manifestPlaceholders = emptyMap(),
      testInstrumentationRunner = null,
      testInstrumentationRunnerArguments = emptyMap(),
      testedTargetVariants = emptyList(),
      runTestInSeparateProcess = false,
      deprecatedPreMergedApplicationId = null,
      deprecatedPreMergedTestApplicationId = null,
      desugaredMethodsFiles = emptyList(),
      experimentalProperties = emptyMap(),
    )
  }

  private fun createEmptyBuildController(): BuildController =
    Proxy.newProxyInstance(BuildController::class.java.classLoader, arrayOf(BuildController::class.java)) { proxy, method, args ->
      when (method.name) {
        "run" -> {
          @Suppress("UNCHECKED_CAST") val actions = args[0] as Collection<BuildAction<Any?>>
          actions.map { it.execute(proxy as BuildController) }
        }
        "findModel" -> {
          if (args.size == 4) {
            val paramType = args[2] as Class<*>
            @Suppress("UNCHECKED_CAST") val action = args[3] as Action<Any>
            action.execute(Mockito.mock(paramType))
          }
          null
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

  private fun createSingleVariantSyncOptions(): SingleVariantSyncActionOptions {
    val flags =
      GradleSyncStudioFlags(
        modelConsumerVersion = ModelConsumerVersion(1, 0, "test"),
        studioLatestKnownAgpVersion = "9.0.0",
        studioFlagParallelSyncEnabled = true,
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
