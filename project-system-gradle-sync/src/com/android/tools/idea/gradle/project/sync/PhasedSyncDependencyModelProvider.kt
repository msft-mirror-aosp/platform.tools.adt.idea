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
package com.android.tools.idea.gradle.project.sync

import com.android.ide.gradle.model.composites.BuildMap
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.IdeArtifactName
import com.android.tools.idea.gradle.model.IdeSyncIssue
import com.android.tools.idea.gradle.model.IdeUnresolvedDependency
import com.android.tools.idea.gradle.model.IdeVariantCore
import com.android.tools.idea.gradle.model.allArtifacts
import com.android.tools.idea.gradle.model.impl.IdeSyncIssueImpl
import com.android.tools.idea.gradle.model.impl.IdeUnresolvedLibraryTableImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.project.sync.ModelResult.Companion.ignoreExceptionsAndGet
import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase
import java.io.File
import java.util.Collections
import kotlin.collections.forEach
import kotlin.collections.orEmpty
import kotlin.to
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider

class PhasedSyncDependencyModelProvider(val syncOptions: SyncActionOptions, val cachedData: ModelProviderCachedData) :
  ProjectImportModelProvider {
  override fun getPhase() = GradleModelFetchPhase.PROJECT_SOURCE_SET_DEPENDENCY_PHASE

  override fun populateModels(
    controller: BuildController,
    buildModels: MutableCollection<out GradleBuild>,
    modelConsumer: ProjectImportModelProvider.GradleModelConsumer,
  ) {
    // This model handles Android dependencies, so for JVM projects (data.isEmpty)  we don't need to run it.
    if (cachedData.shouldRunLegacyModelProviders || cachedData.hasSyncError() || cachedData.data.isEmpty()) {
      return
    }
    // Cache the requested models so that the model providers running after this one don't need to request them again.
    @Suppress("NAME_SHADOWING") val controller = controller.toCachingController(cachedData)
    // Synchronized because the nested actions below add to it in parallel.
    val exceptionsPerProject: MutableList<Pair<BasicGradleProject, Throwable>> = Collections.synchronizedList(mutableListOf())
    val unresolvedDependenciesPerProject = mutableMapOf<BasicGradleProject, List<IdeUnresolvedDependency>>()
    val buildPathMap = buildBuildPathMap(controller, buildModels)
    val internedModels = InternedModels(buildModels.first().buildIdentifier.rootDir)
    // Build a set of module IDs across all builds that have inbound dependencies from any Android project.
    // This is used as an optimization when determining whether to skip library runtime classpaths.
    val hasInboundDependencyByModuleId = cachedData.data.values.flatMapTo(mutableSetOf()) { it.allOutgoingProjectDependencies }
    val allProjects = buildModels.flatMap { it.projects }
    val ideAndroidProjectMap = allProjects.mapNotNull { cachedData.data[it]?.let { data -> it to data.ideAndroidProject } }.toMap()
    val androidProjectPathResolver = buildAndroidProjectPathResolver(allProjects, ideAndroidProjectMap)

    val result =
      controller.run(
        buildModels.flatMap { buildModel ->
          buildModel.projects.flatMap { gradleProject ->
            val data = cachedData.data[gradleProject] ?: return@flatMap emptyList()
            val ideAndroidProject = data.ideAndroidProject
            val variantsToFetch =
              when (syncOptions) {
                is SingleVariantSyncActionOptions -> listOf(ideAndroidProject.coreVariants.single { it.name == data.selectedVariantName })
                is AllVariantsSyncActionOptions -> ideAndroidProject.coreVariants
              }
            variantsToFetch.map { variantToFetch ->
              BuildAction {
                runCatching {
                  val variantDependencies =
                    controller.findVariantDependenciesV2Model(
                      project = gradleProject,
                      modelVersions = data.modelVersions,
                      variantName = variantToFetch.name,
                    ) {
                      when {
                        !hasInboundDependencyByModuleId.contains(gradleProject.moduleId()) ->
                          it.buildAllRuntimeClasspaths(syncOptions.flags.studioFlagMultiVariantAdditionalArtifactSupport)
                        syncOptions is SingleVariantSyncActionOptions &&
                          data.shouldSkipRuntimeClassPathForLibraries &&
                          ideAndroidProject.projectType == IdeAndroidProjectType.PROJECT_TYPE_LIBRARY ->
                          it.buildOnlyTestRuntimeClasspaths(
                            buildUnitTestsRuntime = syncOptions.flags.studioFlagBuildRuntimeClasspathForLibraryUnitTests,
                            buildScreenshotTestsRuntime = syncOptions.flags.studioFlagBuildRuntimeClasspathForLibraryScreenshotTests,
                            addAdditionalArtifactsInModel = syncOptions.flags.studioFlagMultiVariantAdditionalArtifactSupport,
                          )

                        else -> it.buildAllRuntimeClasspaths(syncOptions.flags.studioFlagMultiVariantAdditionalArtifactSupport)
                      }
                    } ?: return@BuildAction null
                  val result =
                    fetchAndProcessAndroidDependenciesModel(
                      variantDependencies,
                      data.modelVersions,
                      buildModel,
                      gradleProject,
                      variantToFetch,
                      ideAndroidProject.bootClasspath,
                      internedModels,
                      androidProjectPathResolver = androidProjectPathResolver,
                      buildPathMap = buildPathMap,
                      syncOptions.syncTestMode,
                    )
                  exceptionsPerProject += result.exceptions.map { gradleProject to it }
                  result.ignoreExceptionsAndGet()?.let { gradleProject to it }
                }
                  .onFailure { exceptionsPerProject += gradleProject to it }
                  .getOrNull()
              }
            }
          }
        }
      )

    if (syncOptions.syncTestMode == SyncTestMode.TEST_EXCEPTION_WITH_UNRESOLVED_MODULE) {
      error("**internal error for tests**")
    }

    val libraryTable = internedModels.apply { prepare() }.createLibraryTable()
    cachedData.libraryTable = libraryTable
    modelConsumer.consumeBuildModel(
      buildModels.first(),
      libraryTable,
      IdeUnresolvedLibraryTableImpl::class.java,
    )

    result.filterNotNull().groupBy({ it.first }, { it.second.postProcess() }).forEach { (gradleProject, ideVariants) ->
      val data =
        checkNotNull(cachedData.data[gradleProject]) {
          "No cached Android project data found for ${gradleProject.path}"
        }
      // Store the fetched variants to later create IdeAndroidModels with them in the additional model provider. IdeAndroidModels is still
      // required by a few different code paths.
      cachedData.fetchedVariants[gradleProject] = ideVariants
      // GradleModelConsumer.consumeProjectModel stores at most one model per (project, Class), and IdeVariantCore is read exclusively by
      // SyncContributorAndroidProjectDependenciesContext to populate WorkspaceModel dependencies for the IDE's single active variant.
      // All fetched variants are stored in cachedData.fetchedVariants above and consumed together via IdeAndroidModels.
      val selectedVariant =
        checkNotNull(ideVariants.firstOrNull { it.name == data.selectedVariantName }) {
          "Selected variant '${data.selectedVariantName}' not found in fetched variants for ${gradleProject.path}: ${ideVariants.map { it.name }}"
        }
      modelConsumer.consumeProjectModel(gradleProject, selectedVariant, IdeVariantCore::class.java)
      // Fetch unresolved dependencies across the fetched variants.
      unresolvedDependenciesPerProject[gradleProject] =
        ideVariants
          .flatMap { variant ->
            buildList {
              addAll(variant.mainArtifact.unresolvedDependencies)
              variant.deviceTestArtifacts.find { it.name == IdeArtifactName.ANDROID_TEST }?.let { addAll(it.unresolvedDependencies) }
              variant.testFixturesArtifact?.let { addAll(it.unresolvedDependencies) }
              variant.hostTestArtifacts.forEach { addAll(it.unresolvedDependencies) }
              variant.testSuiteComponents.forEach { component -> component.allArtifacts.forEach { addAll(it.unresolvedDependencies) } }
            }
          }
          .distinct()
    }

    val exceptionsPerProjectMap = exceptionsPerProject.groupBy({ it.first }) { it.second }
    buildModels.forEach { buildModel ->
      buildModel.projects
        .filter { gradleProject -> cachedData.versions[gradleProject] != null }
        .forEach { gradleProject ->
          val unresolvedDependenciesIssues =
            unresolvedDependenciesPerProject[gradleProject]?.map {
              IdeSyncIssueImpl(
                message = "Unresolved dependencies",
                data = it.name.ifBlank { null },
                multiLineMessage = it.cause?.lines(),
                severity = IdeSyncIssue.SEVERITY_ERROR,
                type = if (it.name.isBlank()) IdeSyncIssue.TYPE_EXCEPTION else IdeSyncIssue.TYPE_UNRESOLVED_DEPENDENCY,
              )
            }

          cachedData.exceptionsAndSyncIssues[gradleProject] =
            IdeAndroidSyncIssuesAndExceptions(
              syncIssues = cachedData.exceptionsAndSyncIssues[gradleProject]?.syncIssues.orEmpty() + unresolvedDependenciesIssues.orEmpty(),
              exceptions =
                cachedData.exceptionsAndSyncIssues[gradleProject]?.exceptions.orEmpty() + exceptionsPerProjectMap[gradleProject].orEmpty(),
            )
        }
    }
  }
}

private fun fetchAndProcessAndroidDependenciesModel(
  variantDependencies: VariantDependenciesCompat,
  versions: ModelVersions,
  buildModel: GradleBuild,
  projectModel: BasicGradleProject,
  ideVariant: IdeVariantCore,
  bootClasspath: Collection<String>,
  internedModels: InternedModels,
  androidProjectPathResolver: AndroidProjectPathResolver,
  buildPathMap: Map<String, BuildId>,
  testSyncMode: SyncTestMode,
): ModelResult<IdeVariantWithPostProcessor> {
  // We need to be lenient when modules are being resolved as some might not be set up yet if not supported by phased sync
  // - for instance, the KMP modules are not supported and not yet populated in this case
  val modelCacheV2Impl = modelCacheV2Impl(internedModels, versions, syncTestMode = testSyncMode, lenientModuleResolution = true)

  return modelCacheV2Impl.variantFrom(
    BuildId(buildModel.buildIdentifier.rootDir),
    projectModel.projectIdentifier.projectPath,
    ideVariant as IdeVariantCoreImpl,
    variantDependencies,
    bootClasspath,
    androidProjectPathResolver,
    buildPathMap,
  )
}

/** Builds an [AndroidProjectPathResolver] instance, used to later refer to Android projects and resolve variant names for them. */
private fun buildAndroidProjectPathResolver(
  allProjects: List<BasicGradleProject>,
  ideAndroidProjects: Map<BasicGradleProject, IdeAndroidProject>,
): AndroidProjectPathResolver {
  val projectIdentifierToResolvedProjectPathMap =
    allProjects
      .mapNotNull { projectModel ->
        run {
          val ideAndroidProject = ideAndroidProjects[projectModel] ?: return@mapNotNull null
          BuildId(projectModel.projectIdentifier.buildIdentifier.rootDir) to
            projectModel.path to
            ResolvedAndroidProjectPathImpl(
              projectModel,
              buildVariantNameResolver(ideAndroidProject, ideAndroidProject.coreVariants),
              ideAndroidProject.lintJar,
            )
        }
      }
      .toMap()
  return AndroidProjectPathResolver { buildId, projectPath -> projectIdentifierToResolvedProjectPathMap[buildId to projectPath] }
}

/**
 * Map from the Gradle path (composite aware, supports nested builds) to the Gradle build root directory.
 *
 * Uses [GradleBuild.getBuildIdentifier]'s root directory rather than [BuildMap]'s value (`rootProject.projectDir`), because starting with
 * Gradle 7.4 `buildIdentifier.rootDir` is `settingsDir` and can differ from `rootProject.projectDir` when `settings.gradle` customizes
 * `rootProject.projectDir` (or when a build is symlinked, since `projectDir` is canonicalized while `rootDir` may not be).
 */
internal fun buildBuildPathMap(controller: BuildController, allBuilds: Collection<GradleBuild>): Map<String, BuildId> =
  allBuilds
    .flatMap { buildModel ->
      val buildId = BuildId(buildModel.buildIdentifier.rootDir)
      controller.findModel(buildModel.rootProject, BuildMap::class.java)?.buildIdMap?.keys.orEmpty().map { buildPath ->
        buildPath to buildId
      }
    }
    .toMap()

/** Used to refer to Android projects and resolve variant names for them. */
private data class ResolvedAndroidProjectPathImpl(
  override val gradleProject: BasicGradleProject,
  override val androidVariantResolver: AndroidVariantResolver,
  // Lint jar is not really relevant here, but required by the IdeLibrary model when resolving
  override val lintJar: File?,
) : ResolvedAndroidProjectPath
