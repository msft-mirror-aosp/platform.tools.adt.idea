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

import com.android.builder.model.v2.models.Versions
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.model.impl.IdeUnresolvedLibraryTableImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.jetbrains.kotlin.idea.gradleTooling.model.kapt.KaptGradleModel

/** Class for storing per Gradle-project data between multiple model providers. */
class ModelProviderCachedData : Serializable {
  internal val versions = ConcurrentHashMap<BasicGradleProject, Versions>()
  internal val data = ConcurrentHashMap<BasicGradleProject, CachedAndroidProjectData>()
  internal val fetchedVariants = ConcurrentHashMap<BasicGradleProject, List<IdeVariantCoreImpl>>()
  internal var libraryTable: IdeUnresolvedLibraryTableImpl? = null

  internal val kaptGradleModel = ConcurrentHashMap<BasicGradleProject, KaptGradleModel>()

  internal val exceptionsAndSyncIssues = ConcurrentHashMap<BasicGradleProject, IdeAndroidSyncIssuesAndExceptions>()

  /** Models requested through [toCachingController], shared by all model providers of a sync. See [CachingBuildController]. */
  internal val modelCache = ConcurrentHashMap<ModelCacheKey, Any>()

  private var hasSyncError: Boolean = false

  private var allProjectsSupportedByPhasedSync: Boolean = false

  fun markAllProjectsSupportedByPhasedSync() {
    allProjectsSupportedByPhasedSync = true
  }

  fun markBuildWithSyncErrors() {
    hasSyncError = true
  }

  fun hasSyncError(): Boolean {
    return hasSyncError
  }

  fun setKaptGradleModel(gradleProject: BasicGradleProject, kaptModel: KaptGradleModel) {
    kaptGradleModel[gradleProject] = kaptModel
    data.computeIfPresent(gradleProject) { _, cachedData ->
      cachedData.copy(ideAndroidProject = (cachedData.ideAndroidProject as IdeAndroidProjectImpl).patchForKapt(kaptModel))
    }
    fetchedVariants.computeIfPresent(gradleProject) { _, variants -> variants.patchForKapt(kaptModel) }
  }

  /**
   * Only run legacy providers when there are Gradle subprojects that are not supported.
   *
   * Phased sync model providers always run (when enabled) and populate [modelCache], while legacy model providers run last and only when
   * this property is true.
   */
  val shouldRunLegacyModelProviders: Boolean
    get() = !allProjectsSupportedByPhasedSync

  /**
   * This class exists to share data between model providers, and the last one to execute should call this method to drop all shared state.
   * to not cause memory leaks and a previous sync attempt influencing the others.
   *
   * The model providers should be re-created each time anyway but this makes sure we can dispose of the data as early as possible and is
   * just an additional safeguard.
   */
  fun clear() {
    versions.clear()
    data.clear()
    fetchedVariants.clear()
    libraryTable = null
    kaptGradleModel.clear()
    exceptionsAndSyncIssues.clear()
    modelCache.clear()
  }
}

internal data class CachedAndroidProjectData(
  val modelVersions: ModelVersions,
  val selectedVariantName: String,
  val ideAndroidProject: IdeAndroidProject,
  val shouldSkipRuntimeClassPathForLibraries: Boolean,
  val allOutgoingProjectDependencies: List<String>,
)
