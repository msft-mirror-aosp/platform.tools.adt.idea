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

import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
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
    if (cachedModels.shouldRunLegacyModelProviders) {
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

    val hasAndroidModules = buildModels.any { it.projects.any { p -> cachedModels.versions[p] != null } }
    modelConsumer.consumeBuildModel(
      buildModels.first(),
      IdeSyncExecutionReport(parallelFetchForV2ModelsEnabled = syncOptions.flags.studioFlagParallelSyncEnabled && hasAndroidModules),
      IdeSyncExecutionReport::class.java,
    )

    // Now, for Java modules, as a fallback we request the Kotlin and KAPT models.
    controller
      .run(
        buildModels.flatMap { buildModel ->
          buildModel.projects.mapNotNull { gradleProject ->
            if (cachedModels.versions[gradleProject] == null) {
              BuildAction { controller ->
                gradleProject to
                  Pair(
                    controller.findModel(gradleProject, KotlinGradleModel::class.java),
                    controller.findModel(gradleProject, KaptGradleModel::class.java),
                  )
              }
            } else null
          }
        }
      )
      .filterNotNull()
      .forEach { (gradleProject, models) ->
        models.first?.let { modelConsumer.consumeProjectModel(gradleProject, it, KotlinGradleModel::class.java) }
        models.second?.let { modelConsumer.consumeProjectModel(gradleProject, it, KaptGradleModel::class.java) }
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
      kaptGradleModel = cachedModels.kaptGradleModel[gradleProject]!!,
    )
  }
