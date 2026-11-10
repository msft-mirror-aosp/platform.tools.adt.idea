/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.ide.gradle.model.AdditionalClassifierArtifactsModelParameter
import com.android.ide.gradle.model.ArtifactIdentifier
import com.android.ide.gradle.model.artifacts.AdditionalClassifierArtifactsModel
import com.android.tools.idea.gradle.model.IdeUnresolvedLibrary
import com.android.tools.idea.gradle.model.LibraryReference
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.Model
import org.gradle.tooling.model.gradle.BasicGradleProject

internal fun getAdditionalClassifierArtifactsModel(
  actionRunner: GradleInjectedSyncActionRunner,
  inputModules: List<AndroidModule>,
  libraryResolver: (LibraryReference) -> IdeUnresolvedLibrary,
  cachedLibraries: Collection<String>,
  useMultiVariantAdditionalArtifactSupport: Boolean,
) {
  if (isSourcesDownloadDisabled()) return
  val cachedLibrariesSet = cachedLibraries.toSet()

  actionRunner.runActions(
    inputModules.map { module ->
      ActionToRun(
        fun(controller: BuildController) {
          if (!shouldFetchAdditionalClassifierArtifacts(module.modelVersions, useMultiVariantAdditionalArtifactSupport)) {
            return
          }

          // Query for AdditionalClassifierArtifactsModel model.
          // Since we operate on one module at a time it is safe to run on multiple threads.
          module.additionalClassifierArtifacts =
            fetchAdditionalClassifierArtifactsModel(
              controller = controller,
              findModelRoot = module.findModelRoot,
              identifiers = module.getLibraryDependencies(libraryResolver),
              cachedLibraries = cachedLibrariesSet,
            )
        }
      ) // No known incompatibilities if Gradle is compatible.
    }
  )
}

/**
 * Fetches [AdditionalClassifierArtifactsModel] for [gradleProject] during Phased Sync's additional model phase when single-variant sync is
 * running on AGP < 8.10 (or when multi-variant additional artifact support is disabled), skipping the request when all resolved library
 * identifiers are already cached in [SingleVariantSyncActionOptions.additionalClassifierArtifactsAction].
 */
internal fun BuildController.fetchAdditionalClassifierArtifactsModelForPhasedSync(
  gradleProject: BasicGradleProject,
  syncOptions: SyncActionOptions,
  cachedModels: ModelProviderCachedData,
): Result<AdditionalClassifierArtifactsModel?>? {
  if (syncOptions !is SingleVariantSyncActionOptions) return null
  val data = cachedModels.data[gradleProject] ?: return null
  val libraryTable = cachedModels.libraryTable ?: return null
  if (!shouldFetchAdditionalClassifierArtifacts(data.modelVersions, syncOptions.flags.studioFlagMultiVariantAdditionalArtifactSupport)) {
    return null
  }
  val fetchedVariants = cachedModels.fetchedVariants[gradleProject] ?: return null
  return runCatching {
    val selectedVariant =
      fetchedVariants.firstOrNull { it.name == data.selectedVariantName }
        ?: error("Selected variant '${data.selectedVariantName}' not found for ${gradleProject.path}")
    val identifiers = collectIdentifiers(listOf(selectedVariant)) { libraryTable.libraries[it.libraryIndex] }
    val cachedLibraries = syncOptions.additionalClassifierArtifactsAction.cachedLibraries.toSet()
    val uncachedIdentifiers = filterUncachedArtifactIdentifiers(identifiers, cachedLibraries)
    fetchAdditionalClassifierArtifactsModel(this, gradleProject, uncachedIdentifiers)
  }
}

/**
 * Returns true when library `-sources.jar`, `-javadoc.jar`, and `.pom` files must be fetched via the injected
 * [AdditionalClassifierArtifactsModel] builder rather than read directly from `VariantDependencies` (i.e. on AGP < 8.10 /
 * [ModelFeature.HAS_SOURCES_LIST_AND_JAVADOC_IN_VARIANT_DEPENDENCIES], or when `GRADLE_MULTI_VARIANT_ADDITIONAL_ARTIFACT_SUPPORT` is off).
 */
internal fun shouldFetchAdditionalClassifierArtifacts(
  modelVersions: ModelVersions,
  useMultiVariantAdditionalArtifactSupport: Boolean,
): Boolean {
  if (isSourcesDownloadDisabled()) return false
  if (!modelVersions[ModelFeature.SUPPORTS_ADDITIONAL_CLASSIFIER_ARTIFACTS_MODEL]) return false
  // Studio starts to manage the MultiVariantArtifactSupport when we add source list for each library based on
  // useMultiVariantAdditionalArtifactSupport. Before that, it is uncertain whether this additional artifact model fetching should
  // be skipped because additional artifacts might be passed from the build side.
  return !(modelVersions[ModelFeature.HAS_SOURCES_LIST_AND_JAVADOC_IN_VARIANT_DEPENDENCIES] && useMultiVariantAdditionalArtifactSupport)
}

/**
 * Returns all non-local (`version != "unspecified"`) libraries in [identifiers] that are not already cached in `LibraryFilePaths`
 * ([cachedLibraries]). On resync, previously resolved libraries are omitted here and looked up from `LibraryFilePaths` on the IDE side.
 */
internal fun filterUncachedArtifactIdentifiers(
  identifiers: Collection<ArtifactIdentifier>,
  cachedLibraries: Set<String>,
): List<ArtifactIdentifier> = identifiers.filter { !cachedLibraries.contains(idToString(it)) && it.version != "unspecified" }

/**
 * Fetches [AdditionalClassifierArtifactsModel] for all uncached non-local libraries in [identifiers], or returns `null` without contacting
 * Gradle when every identifier is already cached in [cachedLibraries] (e.g. on a no-op resync) or local.
 */
internal fun fetchAdditionalClassifierArtifactsModel(
  controller: BuildController,
  findModelRoot: Model,
  identifiers: Collection<ArtifactIdentifier>,
  cachedLibraries: Set<String>,
): AdditionalClassifierArtifactsModel? =
  fetchAdditionalClassifierArtifactsModel(controller, findModelRoot, filterUncachedArtifactIdentifiers(identifiers, cachedLibraries))

internal fun fetchAdditionalClassifierArtifactsModel(
  controller: BuildController,
  findModelRoot: Model,
  uncachedIdentifiers: Collection<ArtifactIdentifier>,
): AdditionalClassifierArtifactsModel? {
  if (uncachedIdentifiers.isEmpty()) return null
  return controller.findModel(
    findModelRoot,
    AdditionalClassifierArtifactsModel::class.java,
    AdditionalClassifierArtifactsModelParameter::class.java,
  ) { parameter ->
    parameter.artifactIdentifiers = uncachedIdentifiers
  }
}

fun idToString(identifier: ArtifactIdentifier): String {
  return identifier.groupId + ":" + identifier.artifactId + ":" + identifier.version
}

// IDEA might disable source fetching
private fun isSourcesDownloadDisabled(): Boolean {
  val isJetBrains = "JetBrains" == System.getProperty("idea.vendor.name")
  val isDownloadSources = System.getProperty("idea.gradle.download.sources", "true").toBoolean()
  return isJetBrains && !isDownloadSources
}
