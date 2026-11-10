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

import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.builder.model.v2.models.Versions
import com.android.ide.gradle.model.artifacts.AdditionalClassifierArtifactsModel
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.idea.gradleTooling.KotlinGradleModel
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptGradleModel
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider

class PhasedSyncAdditionalModelProvider(val syncOptions: SyncActionOptions, val cachedModels: ModelProviderCachedData) :
  ProjectImportModelProvider {
  override fun getPhase() = GradleModelFetchPhase.ADDITIONAL_MODEL_PHASE

  override fun populateModels(
    controller: BuildController,
    buildModels: Collection<GradleBuild>,
    modelConsumer: ProjectImportModelProvider.GradleModelConsumer,
  ) {
    if (cachedModels.hasSyncError) {
      return
    }
    // Cache the requested models so that the model providers running after this one don't need to request them again.
    @Suppress("NAME_SHADOWING") val controller = controller.toCachingController(cachedModels)
    val actions = buildModels.flatMap { buildModel ->
      buildModel.projects.mapNotNull { gradleProject ->
        val data = cachedModels.data[gradleProject] ?: return@mapNotNull null
        BuildAction { controller ->
          val kotlinModel = controller.fetchModel<KotlinGradleModel>(gradleProject, syncOptions, data.selectedVariantName)
          val additionalArtifactsResult =
            controller.fetchAdditionalClassifierArtifactsModelForPhasedSync(gradleProject, syncOptions, cachedModels)
          Triple(gradleProject, kotlinModel, additionalArtifactsResult)
        }
      }
    }

    controller.run(actions).filterNotNull().forEach { (gradleProject, kotlinModel, additionalArtifactsResult) ->
      kotlinModel?.let { modelConsumer.consumeProjectModel(gradleProject, it, KotlinGradleModel::class.java) }
      additionalArtifactsResult
        ?.onSuccess { additionalArtifacts ->
          additionalArtifacts?.let {
            modelConsumer.consumeProjectModel(gradleProject, it, AdditionalClassifierArtifactsModel::class.java)
          }
        }
        ?.onFailure { exception ->
          val current = cachedModels.exceptionsAndSyncIssues[gradleProject]
          cachedModels.exceptionsAndSyncIssues[gradleProject] =
            IdeAndroidSyncIssuesAndExceptions(
              syncIssues = current?.syncIssues.orEmpty(),
              exceptions = current?.exceptions.orEmpty() + exception,
            )
        }
      createIdeAndroidModels(cachedModels, gradleProject)?.let {
        modelConsumer.consumeProjectModel(gradleProject, it, IdeAndroidModels::class.java)
      }
    }

    val gradleVersion = controller.findModel(buildModels.first(), BuildEnvironment::class.java)?.gradle?.gradleVersion
    val isParallelModelFetchSupported = gradleVersion != null && GradleVersion.version(gradleVersion) >= GradleVersion.version("7.4.2")
    val hasAndroidModules = buildModels.any { it.projects.any { p -> cachedModels.versions[p] != null } }
    modelConsumer.consumeBuildModel(
      buildModels.first(),
      IdeSyncExecutionReport(
        parallelFetchForV2ModelsEnabled =
          syncOptions.flags.studioFlagParallelSyncEnabled && isParallelModelFetchSupported && hasAndroidModules
      ),
      IdeSyncExecutionReport::class.java,
    )

    // Now, for Java modules, as a fallback we request the Kotlin, KAPT models.
    controller
      .run(
        buildModels.flatMap { buildModel ->
          buildModel.projects.mapNotNull { gradleProject ->
            if (cachedModels.versions[gradleProject] == null && controller.findModel(gradleProject, Versions::class.java) == null) {
              BuildAction { controller ->
                val kotlinModel = controller.findModel(gradleProject, KotlinGradleModel::class.java)
                val kaptModel = controller.findModel(gradleProject, KaptGradleModel::class.java)
                gradleProject to
                  listOf(
                    kotlinModel to KotlinGradleModel::class.java,
                    kaptModel to KaptGradleModel::class.java,
                  )
              }
            } else null
          }
        }
      )
      .filterNotNull()
      .forEach { (gradleProject, models) ->
        models.forEach { (model, modelClass) ->
          @Suppress("UNCHECKED_CAST") model?.let { modelConsumer.consumeProjectModel(gradleProject, it, modelClass as Class<Any>) }
        }
      }

    // Finally, fetch Ide Sync Issues
    controller
      .run(
        buildModels.flatMap { buildModel ->
          buildModel.projects
            .filter { gradleProject -> cachedModels.versions[gradleProject] != null }
            .mapNotNull { gradleProject ->
              BuildAction {
                val syncIssues =
                  controller.findModel(gradleProject, ProjectSyncIssues::class.java)?.syncIssues?.toV2SyncIssueData() ?: listOf()

                val result =
                  IdeAndroidSyncIssuesAndExceptions(
                    syncIssues = syncIssues + cachedModels.exceptionsAndSyncIssues[gradleProject]?.syncIssues.orEmpty(),
                    exceptions = cachedModels.exceptionsAndSyncIssues[gradleProject]?.exceptions.orEmpty(),
                  )
                gradleProject to result
              }
            }
        }
      )
      .filterNotNull()
      .forEach { (gradleProject, syncIssues) ->
        if (syncIssues.syncIssues.isNotEmpty() || syncIssues.exceptions.isNotEmpty()) {
          modelConsumer.consumeProjectModel(gradleProject, syncIssues, IdeAndroidSyncIssuesAndExceptions::class.java)
        }
      }

    if (!cachedModels.shouldRunLegacyModelProviders) {
      recordCheckpointData(MeasurementCheckpoint.ANDROID_FINISHED, syncOptions)
      buildModels.forEach { model ->
        if (syncOptions.flags.studioDebugMode) {
          populateDebugInfo(model, modelConsumer, this.javaClass)
        }
      }
      cachedModels.clear()
    }
  }
}

private fun createIdeAndroidModels(cachedModels: ModelProviderCachedData, gradleProject: BasicGradleProject) =
  cachedModels.data[gradleProject]?.let { data ->
    val fetchedVariants =
      checkNotNull(cachedModels.fetchedVariants[gradleProject]) {
        "No fetched variants found for ${gradleProject.path}"
      }
    IdeAndroidModels(
      data.ideAndroidProject as IdeAndroidProjectImpl,
      fetchedVariants,
      data.selectedVariantName,
      selectedAbiName = null,
      v2NativeModule = null,
      kaptGradleModel = cachedModels.kaptGradleModel[gradleProject],
    )
  }
