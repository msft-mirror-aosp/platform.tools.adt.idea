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
    if (cachedModels.hasSyncError || cachedModels.shouldRunLegacyModelProviders) {
      return
    }
    val actions = buildModels.flatMap { buildModel ->
      buildModel.projects.mapNotNull { gradleProject ->
        if (cachedModels.versions[gradleProject] != null) {
          BuildAction { controller ->
            gradleProject to controller.fetchModel<KotlinGradleModel>(gradleProject, cachedModels.data[gradleProject]?.selectedVariantName)
          }
        } else null
      }
    }

    controller.run(actions).filterNotNull().forEach { (gradleProject, kotlinModel) ->
      kotlinModel?.let { modelConsumer.consumeProjectModel(gradleProject, it, KotlinGradleModel::class.java) }
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
            if (cachedModels.versions[gradleProject] == null) {
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

    recordCheckpointData(MeasurementCheckpoint.ANDROID_FINISHED, syncOptions)
    buildModels.forEach { model ->
      if (syncOptions.flags.studioDebugMode) {
        populateDebugInfo(model, modelConsumer, this.javaClass)
      }
    }
    cachedModels.clear()
  }
}

private fun createIdeAndroidModels(cachedModels: ModelProviderCachedData, gradleProject: BasicGradleProject) =
  cachedModels.data[gradleProject]?.let { data ->
    IdeAndroidModels(
      data.ideAndroidProject as IdeAndroidProjectImpl,
      listOf(cachedModels.selectedVariant[gradleProject]!!),
      data.selectedVariantName,
      selectedAbiName = null,
      v2NativeModule = null,
      kaptGradleModel = cachedModels.kaptGradleModel[gradleProject],
    )
  }
