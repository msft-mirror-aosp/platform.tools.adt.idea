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
@file:Suppress("UnstableApiUsage")

package com.android.tools.idea.gradle.project.sync

import com.android.builder.model.v2.ide.AndroidGradlePluginProjectFlags
import com.android.builder.model.v2.models.AndroidDsl
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.builder.model.v2.models.Versions
import com.android.builder.model.v2.models.ndk.NativeModelBuilderParameter
import com.android.builder.model.v2.models.ndk.NativeModule
import com.android.ide.gradle.model.GradlePluginModel
import com.android.ide.gradle.model.GradlePropertiesModel
import com.android.ide.gradle.model.LegacyV1AgpVersionModel
import com.android.ide.gradle.model.dependencies.DeclaredDependencies
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.project.sync.ModelResult.Companion.ignoreExceptionsAndGet
import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase
import com.intellij.openapi.diagnostic.logger
import java.util.Collections
import kotlin.collections.all
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.map
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptGradleModel
import org.jetbrains.plugins.gradle.model.GradleTaskModel
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider

class PhasedSyncProjectModelProvider(val syncOptions: SyncActionOptions, val cachedModels: ModelProviderCachedData) :
  ProjectImportModelProvider {
  /**
   * This is just indicating which phase the provider will on. To match the names it can technically run on
   * [GradleModelFetchPhase.PROJECT_MODEL_PHASE] but we populate source sets with this information, so it's kept in the source set phase.
   * The source of the clash is the mismatch between the platform models and our models. Platform models for the projects don't have source
   * set information whereas Android models do, so some sort of inconsistency will always be there with the current definition of phases.
   */
  override fun getPhase(): GradleModelFetchPhase = GradleModelFetchPhase.PROJECT_SOURCE_SET_PHASE

  override fun populateModels(
    controller: BuildController,
    buildModels: MutableCollection<out GradleBuild>,
    modelConsumer: ProjectImportModelProvider.GradleModelConsumer,
  ) {
    recordCheckpointData(MeasurementCheckpoint.ANDROID_STARTED, syncOptions)
    // No need to worry about BuildSrc here for Gradle < 8.0 as JetBrains itself disabled PhasedSync for BuildSrc projects.
    val containsMultipleBuilds = buildModels.size > 1 || buildModels.any { build -> build.includedBuilds.isNotEmpty() }
    if (containsMultipleBuilds && syncOptions is SingleVariantSyncActionOptions) {
      // TODO(b/384022658): Variant resolution for Phased sync doesn't work across composite builds yet, so skip phased sync on
      //  single-variant composite builds rather than populating SOURCE_SET_MODEL_PHASE with an unresolved variant.
      return
    }
    // Cache the requested models so that the model providers running after this one don't need to request them again.
    @Suppress("NAME_SHADOWING") val controller = controller.toCachingController(cachedModels)
    // Synchronized because the nested actions below add to it in parallel.
    val exceptionsPerProject: MutableList<Pair<BasicGradleProject, Throwable>> = Collections.synchronizedList(mutableListOf())
    // .first call seems a bit silly, but it's what we do on the other side too
    val rootBuildId = BuildId(buildModels.first().buildIdentifier.rootDir)
    // Build root directory is used for dependency models only, not used in a meaningful way here.
    // Apart from that, we use interned models only to intern strings.
    val buildRootDirectory = null
    val internedModels = InternedModels(buildRootDirectory)
    // Run a check for all subprojects in parallel to check if they are all supported.
    val allSubProjectsSupported =
      controller.all(
        buildModels.flatMap { buildModel ->
          buildModel.projects.mapNotNull { gradleProject ->
            BuildAction {
              val versions = controller.findModel(gradleProject, Versions::class.java)
              val supported =
                if (versions == null) {
                  // Non-Android subprojects are imported by the platform (incl. rootProject), whereas V1 and KMP require legacy providers.
                  controller.findModel(gradleProject, LegacyV1AgpVersionModel::class.java) == null &&
                    !gradleProject.hasKotlinMultiplatformPlugin(controller)
                } else {
                  versions.hasV2Modules() &&
                    !gradleProject.hasKotlinMultiplatformPlugin(controller) &&
                    !gradleProject.hasNativeModels(controller)
                }
              if (supported && versions != null) {
                // We only set the versions for projects that we actually support with Phased sync and we can fetch.
                cachedModels.versions[gradleProject] = versions
              }
              supported
            }
          }
        }
      )

    if (allSubProjectsSupported) {
      // Marking projects as fully supported by phased Sync means we don't need to run the legacy providers and when Phased Sync model
      // providers would have finished fetching, we would have all the information needed to finish importing the project.
      cachedModels.markAllProjectsSupportedByPhasedSync()
    } else {
      // No need to run Phased Sync for any project here and we should run legacy Providers only. Keep the cached models but clear Versions
      // models.
      cachedModels.versions.clear()
      return
    }

    try {
      cachedModels.versions.values.forEach {
        it.convert().checkAgpVersionCompatibility(syncOptions.flags)
      }
      verifyIncompatibleAgpVersionsAreNotUsedOrFailSync(cachedModels, controller, buildModels)
    } catch (e: AndroidSyncException) {
      reportSyncExceptionAndClearCachedModels(e, buildModels, modelConsumer)
      return
    }

    val results =
      controller
        .run(
          buildModels.flatMap { buildModel ->
            buildModel.projects.mapNotNull { gradleProject ->
              BuildAction {
                runCatching {
                  val versions = cachedModels.versions[gradleProject] ?: return@BuildAction null
                  val modelVersions = versions.convert()
                  val basicAndroidProject = controller.findModel(gradleProject, BasicAndroidProject::class.java)!!
                  val androidProject = controller.findModel(gradleProject, AndroidProject::class.java)!!
                  val androidDsl = controller.findModel(gradleProject, AndroidDsl::class.java)!!
                  val gradlePropertiesModel = controller.findModel(gradleProject, GradlePropertiesModel::class.java)!!
                  val legacyAndroidGradlePluginProperties = getLegacyAndroidGradlePluginProperties(controller, gradleProject, modelVersions)

                  val defaultVariantName =
                    basicAndroidProject.variants.toList().getDefaultVariant(androidDsl.buildTypes, androidDsl.productFlavors)
                  // GradleModelConsumer.consumeProjectModel stores at most one model per (project, Class), and IdeBasicVariantName /
                  // IdeVariantCore are read exclusively by the WorkspaceModel sync contributors to configure the IDE's single active
                  // variant. When syncing all variants, fall back to defaultVariantName here so the sync contributors and IdeAndroidModels
                  // receive a valid active variant while all variants are fetched and delivered via IdeAndroidModels.
                  val selectedVariantName =
                    when (syncOptions) {
                      is SingleVariantSyncActionOptions ->
                        computeVariantNameToBeSynced(syncOptions, gradleProject.moduleId(), basicAndroidProject, defaultVariantName)
                      is AllVariantsSyncActionOptions -> defaultVariantName
                    }
                      ?: throw createNoVariantsFoundSyncException(
                        gradleProject,
                        controller.findModel(gradleProject, ProjectSyncIssues::class.java)?.syncIssues?.toV2SyncIssueData().orEmpty() +
                          legacyAndroidGradlePluginProperties.getProblemsAsSyncIssues(),
                      )

                  val modelCache = modelCacheV2Impl(internedModels, modelVersions, syncTestMode = syncOptions.syncTestMode)
                  val ideAndroidProject =
                    modelCache
                      .androidProjectFrom(
                        rootBuildId,
                        buildId = BuildId(gradleProject.projectIdentifier.buildIdentifier.rootDir),
                        basicAndroidProject,
                        androidProject,
                        modelVersions,
                        androidDsl,
                        legacyAndroidGradlePluginProperties = legacyAndroidGradlePluginProperties,
                        gradlePropertiesModel,
                        defaultVariantName,
                      )
                      .let { it.exceptions.takeIf { it.isNotEmpty() }?.first()?.let { throw it } ?: it.ignoreExceptionsAndGet()!! }
                  gradleProject to
                    AndroidProjectData(
                      versions,
                      modelVersions,
                      basicAndroidProject,
                      androidProject,
                      androidDsl,
                      controller.findModel(gradleProject, DeclaredDependencies::class.java)!!,
                      controller.findModel(gradleProject, GradlePluginModel::class.java)!!,
                      controller.findModel(gradleProject, GradleTaskModel::class.java)!!,
                      ideAndroidProject,
                      selectedVariantName,
                      syncOptions.flags.studioFlagSkipRuntimeClasspathForLibraries &&
                        shouldSkipRuntimeClasspathForLibraries(androidProject.flags, gradlePropertiesModel),
                      legacyAndroidGradlePluginProperties,
                    )
                }
                  .onFailure { exceptionsPerProject += gradleProject to it }
                  .getOrNull()
              }
            }
          }
        )
        .filterNotNull()

    val syncExceptionsByProject =
      exceptionsPerProject.mapNotNull { (project, exception) -> (exception as? AndroidSyncException)?.let { project to it } }.toMap()
    if (syncExceptionsByProject.isNotEmpty()) {
      val e = buildModels.asSequence().flatMap { it.projects }.firstNotNullOf { syncExceptionsByProject[it] }
      reportSyncExceptionAndClearCachedModels(e, buildModels, modelConsumer)
      return
    }

    // Prepare Android projects to map dynamic features to their target Apps.
    val indexedModels =
      IndexedModels(
        dynamicFeatureToBaseFeatureMap =
          results
            .flatMap { (gradleProject, data) ->
              val projectIdentifier = gradleProject.projectIdentifier
              val rootDir = projectIdentifier.buildIdentifier.rootDir
              data.ideAndroidProject.dynamicFeatures.map { ModuleId(it, rootDir.path) }.map { it to gradleProject.toModuleId() }
            }
            .toMap()
      )

    fun IdeAndroidProjectImpl.populateBaseFeature(gradleProject: BasicGradleProject): IdeAndroidProjectImpl {
      return if (this.projectType != IdeAndroidProjectType.PROJECT_TYPE_DYNAMIC_FEATURE) this
      else copy(baseFeature = indexedModels.dynamicFeatureToBaseFeatureMap[gradleProject.toModuleId()]?.gradlePath)
    }
    // Update results so that ideAndroidProject has baseFeature populated
    val updatedResults = results.map { (gradleProject, data) ->
      gradleProject to data.copy(ideAndroidProject = data.ideAndroidProject.populateBaseFeature(gradleProject))
    }

    // Consume the required models.
    updatedResults.forEach { (gradleProject, data) ->
      // Required models
      modelConsumer.consumeProjectModel(gradleProject, data.versions, Versions::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.basicAndroidProject, BasicAndroidProject::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.androidProject, AndroidProject::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.androidDsl, AndroidDsl::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.declaredDependencies, DeclaredDependencies::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.gradlePluginModel, GradlePluginModel::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.gradleTaskModel, GradleTaskModel::class.java)
      modelConsumer.consumeProjectModel(gradleProject, data.ideAndroidProject, IdeAndroidProject::class.java)
    }

    // Store the list of issues from the variant resolution if any.
    val variantsResolutionIssues = mutableMapOf<BasicGradleProject, Throwable>()
    if (syncOptions is SingleVariantSyncActionOptions) {
      setupProjectsVariantsAndConsume(updatedResults, syncOptions, modelConsumer, cachedModels, variantsResolutionIssues)
    } else {
      updatedResults.forEach { (gradleProject, data) ->
        cacheAndroidDataAndConsumeProjectVariant(
          gradleProject,
          data.selectedVariantName,
          modelConsumer,
          cachedModels,
          data.modelVersions,
          data.ideAndroidProject,
          data.shouldSkipRuntimeClassPathForLibraries,
          data.declaredDependencies.allOutgoingProjectsDependenciesToConfigurations.keys.toList(),
        )
      }
    }

    // Fetch the KAPT models here now that we have the correct selected variant value for all the projects.
    controller
      .run(
        buildModels.flatMap { buildModel ->
          buildModel.projects.mapNotNull { gradleProject ->
            BuildAction {
              // If this is not a project that we support , then we should not fetch the KAPT model in this case.
              val data = cachedModels.data[gradleProject] ?: return@BuildAction null
              val selectedVariantName =
                when (syncOptions) {
                  is SingleVariantSyncActionOptions -> data.selectedVariantName
                  // Without a selected variant name, the KAPT model is requested for all variants.
                  is AllVariantsSyncActionOptions -> null
                }
              val kaptModel = controller.fetchKaptGradleModel(gradleProject, selectedVariantName)
              kaptModel?.let { gradleProject to it }
            }
          }
        }
      )
      .filterNotNull()
      .forEach { (gradleProject, kaptModel) ->
        cachedModels.setKaptGradleModel(gradleProject, kaptModel)
        modelConsumer.consumeProjectModel(gradleProject, kaptModel, KaptGradleModel::class.java)
      }

    populateGradleProjectModel(controller, buildModels, modelConsumer, exceptionsPerProject)

    val legacyProblems = updatedResults.associate { (gradleProject, data) ->
      gradleProject to data.legacyAndroidGradlePluginProperties.getProblemsAsSyncIssues()
    }
    // Cache the exceptions and sync issues caught till this stage.
    val exceptionsPerProjectMap =
      exceptionsPerProject.groupBy({ it.first }) { it.second }.filter { (_, exceptions) -> exceptions.isNotEmpty() }

    buildModels.flatMap { buildModel ->
      buildModel.projects.mapNotNull { gradleProject ->
        cachedModels.exceptionsAndSyncIssues[gradleProject] =
          IdeAndroidSyncIssuesAndExceptions(
            syncIssues =
              variantsResolutionIssues[gradleProject]?.let { listOf(it.toIdeSyncIssue()) }.orEmpty() +
                legacyProblems[gradleProject].orEmpty(),
            exceptions = exceptionsPerProjectMap[gradleProject] ?: emptyList(),
          )
      }
    }
  }

  private fun reportSyncExceptionAndClearCachedModels(
    e: AndroidSyncException,
    buildModels: Collection<GradleBuild>,
    modelConsumer: ProjectImportModelProvider.GradleModelConsumer,
  ) {
    modelConsumer.consumeBuildModel(
      buildModels.first(),
      IdeAndroidSyncError(
        type = e.type,
        message = e.message.orEmpty(),
        stackTrace = e.stackTrace.map { it.toString() },
        buildPath = e.buildPath,
        modulePath = e.modulePath,
        syncIssues = e.syncIssues,
      ),
      IdeAndroidSyncError::class.java,
    )
    cachedModels.clear() // Clear versions so later phased sync providers don't process modules
    cachedModels.markBuildWithSyncErrors()
  }
}

private fun verifyIncompatibleAgpVersionsAreNotUsedOrFailSync(
  cachedModels: ModelProviderCachedData,
  controller: BuildController,
  buildModels: Collection<GradleBuild>,
) {
  // Fail Sync if we do not use the same AGP version across all the android projects.
  if (cachedModels.versions.values.map { it.agp }.distinct().size > 1) {
    val buildIdMap = buildBuildPathMap(controller, buildModels).entries.associate { (buildPath, buildId) -> buildId to buildPath }
    val agpVersionsAndGradleBuilds =
      cachedModels.versions.map { (gradleProject, versions) ->
        // Both buildIdMap (via buildBuildPathMap) and gradleProject use Gradle's BuildIdentifier.rootDir directly.
        val buildId = BuildId(gradleProject.projectIdentifier.buildIdentifier.rootDir)
        val buildPath = buildIdMap[buildId] ?: error("Build not found: $buildId \n available builds $buildIdMap")
        versions.agp to buildPath
      }
    throw AgpVersionsMismatch(agpVersionsAndGradleBuilds)
  }
}

private fun populateGradleProjectModel(
  controller: BuildController,
  buildModels: MutableCollection<out GradleBuild>,
  modelConsumer: ProjectImportModelProvider.GradleModelConsumer,
  exceptionsPerProject: MutableList<Pair<BasicGradleProject, Throwable>>,
) {
  buildModels
    .map { it.rootProject }
    .distinct()
    .forEach { projectModel ->
      runCatching {
        val basicModelsMap = projectModel.getAllChildren { it.children.toList() }.associateBy { it.path }

        controller.findModel(projectModel, GradleProject::class.java)?.let {
          it
            .getAllChildren { it.children.toList() }
            .forEach { modelConsumer.consumeProjectModel(basicModelsMap[it.path]!!, it, GradleProject::class.java) }
        }
      }
        .onFailure { exceptionsPerProject += projectModel to it }
    }
}

private val LOG = logger<PhasedSyncProjectModelProvider>()

/** Use [Modules.createUniqueModuleId] to provide module id. */
private fun computeVariantNameToBeSynced(
  syncOptions: SingleVariantSyncActionOptions,
  moduleId: String,
  basicAndroidProject: BasicAndroidProject,
  defaultVariantName: String?,
): String? {
  if (basicAndroidProject.variants.isEmpty()) {
    return null
  }
  // newly user-selected variant
  return syncOptions.switchVariantRequest
    .takeIf { it?.moduleId == moduleId }
    ?.variantName
    ?.also { LOG.debug("Picked user-selected variant $it for $moduleId") }
    // variants selected by the last sync, only if it still exists
    ?: syncOptions.selectedVariants
      .getSelectedVariant(moduleId)
      .takeIf { basicAndroidProject.variants.map { it.name }.contains(it) }
      ?.also { LOG.debug("Picked selected variant from last sync $it for $moduleId") }
    // default variant as specified by the build script (computation could still end up being null)
    ?: defaultVariantName.also { LOG.debug("Picked the default variant $it for $moduleId") }
}

/** Cache the current variant's buildTypes + fallbacks, and flavors + fallbacks. */
fun BasicGradleProject.moduleId() = Modules.createUniqueModuleId(projectIdentifier.buildIdentifier.rootDir, path)

fun <T> T.getAllChildren(childrenFunction: (T) -> List<out T>): List<T> {
  val result = mutableListOf<T>(this)
  val stack = ArrayDeque<T>(result)
  while (stack.isNotEmpty()) {
    val next = stack.removeLast()
    val children = childrenFunction(next)
    result.addAll(children)
    stack.addAll(children)
  }
  return result
}

private fun shouldSkipRuntimeClasspathForLibraries(flags: AndroidGradlePluginProjectFlags, gradlePropertiesModel: GradlePropertiesModel) =
  !AndroidGradlePluginProjectFlags.BooleanFlag.ENABLE_COMPILE_RUNTIME_CLASSPATH_ALIGNMENT.getValue(
    flags,
    true,
  ) || // true because we always used to align
    AndroidGradlePluginProjectFlags.BooleanFlag.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS.getValue(
      flags,
      gradlePropertiesModel.excludeLibraryComponentsFromConstraints,
    )

private fun BasicGradleProject.hasKotlinMultiplatformPlugin(controller: BuildController) =
  controller.findModel(this, GradlePluginModel::class.java)?.hasKotlinMultiPlatform() == true

private fun BasicGradleProject.hasNativeModels(controller: BuildController) =
  controller.findModel(this, NativeModule::class.java, NativeModelBuilderParameter::class.java) {
    it.variantsToGenerateBuildInformation = emptyList()
    it.abisToGenerateBuildInformation = emptyList()
  } != null

private fun Versions.hasV2Modules() = this.convert()[ModelFeature.HAS_V2_MODELS] == true

/** Helper method to run a collection of [BuildAction]s that return booleans to make sure they are all true. */
private fun BuildController.all(actions: Collection<BuildAction<Boolean>>) = run(actions).all { it }
