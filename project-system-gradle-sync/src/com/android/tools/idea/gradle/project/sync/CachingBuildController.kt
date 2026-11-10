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

import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.builder.model.v2.models.ndk.NativeModelBuilderParameter
import com.android.ide.gradle.model.AdditionalClassifierArtifactsModelParameter
import com.android.ide.gradle.model.ArtifactIdentifier
import com.android.ide.gradle.model.LegacyAndroidGradlePluginPropertiesModelParameters
import java.io.File
import org.gradle.api.Action
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.Model
import org.gradle.tooling.model.ProjectModel
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService

/**
 * Wraps this [BuildController] so that models requested by one model provider are served from [cachedData] when another model provider
 * requests them again during the same sync, e.g. [AndroidExtraModelProvider] re-requesting the models already fetched by
 * [PhasedSyncProjectModelProvider].
 */
internal fun BuildController.toCachingController(cachedData: ModelProviderCachedData): BuildController =
  if (this is CachingBuildController && this.cachedData === cachedData) this else CachingBuildController(this, cachedData)

/**
 * Key of a model cached in [ModelProviderCachedData.modelCache].
 *
 * Only requests targeting a Gradle project have a key (except for [ProjectSyncIssues]); requests without a project target are never cached.
 * A parameterized request is keyed by its recorded parameters, which is only possible for the known parameter types listed in
 * [recordParameters]. Requests with a parameter type in [UNCACHED_PARAMETER_TYPES] have no key and are always delegated, as their models
 * are only requested once per sync. There is no key for a request with any other parameter type either, which fails instead, so that every
 * new parameterized request needs an explicit decision on whether and how to cache it.
 */
internal data class ModelCacheKey(
  val buildRootDir: File,
  val projectPath: String,
  val modelType: Class<*>,
  /** The recorded parameters of a parameterized request (see [recordParameters]), or null for a request without parameters. */
  val parameters: Any? = null,
)

/**
 * A [BuildController] that caches the models requested for a Gradle project in [ModelProviderCachedData.modelCache], and delegates to
 * [delegate] on a cache miss. The cache is shared by all model providers of a sync and dropped by [ModelProviderCachedData.clear].
 *
 * To keep this simple and safe, only the following requests are cached:
 * - Requests targeting a Gradle project ([ProjectModel]), keyed by the build root directory and project path so that projects of different
 *   included builds never collide. Requests without a target or targeting any other model are not cached.
 * - Requests without parameters, and requests with one of the known parameter types used by our model providers (see [recordParameters]),
 *   keyed by the recorded parameter values. Requests with a parameter type in [UNCACHED_PARAMETER_TYPES] are always delegated, and requests
 *   targeting a Gradle project with any other parameter type fail.
 * - [ProjectSyncIssues] is never cached, as later model requests can register additional sync issues.
 *
 * `null` results of `findModel` are cached as well, but never returned for `getModel` requests, which are delegated again instead so that
 * they fail exactly like they would without the cache.
 */
private class CachingBuildController(private val delegate: BuildController, val cachedData: ModelProviderCachedData) :
  BuildController by delegate {

  override fun <T> getModel(target: Model?, modelType: Class<T>): T =
    getOrCompute(target.cacheKey(modelType), allowNull = false) { delegate.getModel(target, modelType) }

  override fun <T> findModel(target: Model?, modelType: Class<T>): T? =
    getOrCompute(target.cacheKey(modelType), allowNull = true) { delegate.findModel(target, modelType) }

  override fun <T, P : Any> getModel(target: Model?, modelType: Class<T>, parameterType: Class<P>, parameterInitializer: Action<in P>): T =
    getOrCompute(target.cacheKey(modelType, parameterType, parameterInitializer), allowNull = false) {
      delegate.getModel(target, modelType, parameterType, parameterInitializer)
    }

  override fun <T, P : Any> findModel(
    target: Model?,
    modelType: Class<T>,
    parameterType: Class<P>,
    parameterInitializer: Action<in P>,
  ): T? =
    getOrCompute(target.cacheKey(modelType, parameterType, parameterInitializer), allowNull = true) {
      delegate.findModel(target, modelType, parameterType, parameterInitializer)
    }

  /** Nested build actions receive their own [BuildController], which needs to be wrapped as well to share the same cache. */
  @Suppress("UnstableApiUsage")
  override fun <T : Any?> run(actions: Collection<out BuildAction<out T>>?): List<T> {
    if (actions == null) return delegate.run(null)
    return delegate.run(actions.map { action -> BuildAction { controller -> action.execute(controller.toCachingController(cachedData)) } })
  }

  /**
   * Returns the cached model for [key], or computes and caches it. Concurrent requests for the same key may both compute the model, which
   * is harmless, and avoids holding any lock while requesting models from Gradle.
   */
  @Suppress("UNCHECKED_CAST")
  private fun <T> getOrCompute(key: ModelCacheKey?, allowNull: Boolean, compute: () -> T): T {
    if (key == null) return compute()
    val cached = cachedData.modelCache[key]
    if (cached === NULL_MODEL && allowNull) return null as T
    if (cached != null && cached !== NULL_MODEL) return cached as T
    return try {
      compute().also { cachedData.modelCache.putIfAbsent(key, it ?: NULL_MODEL) }
    } catch (t: Throwable) {
      // Cache results even if null so we can avoid doing it later. This will be helpful for heavy Ops.
      cachedData.modelCache.putIfAbsent(key, NULL_MODEL)
      throw t
    }
  }
}

private val NULL_MODEL = Any()

private fun Model?.cacheKey(modelType: Class<*>, parameters: Any? = null): ModelCacheKey? {
  if (this !is ProjectModel || modelType == ProjectSyncIssues::class.java) return null
  return ModelCacheKey(projectIdentifier.buildIdentifier.rootDir, projectIdentifier.projectPath, modelType, parameters)
}

private fun <P : Any> Model?.cacheKey(modelType: Class<*>, parameterType: Class<P>, parameterInitializer: Action<in P>): ModelCacheKey? {
  if (this !is ProjectModel || parameterType in UNCACHED_PARAMETER_TYPES) return null
  return cacheKey(modelType, recordParameters(parameterType, parameterInitializer))
}

/**
 * Known parameter types used by our model providers whose requests are always delegated without caching, as each of their models is only
 * requested once per sync, so there is nothing to gain from caching them.
 */
private val UNCACHED_PARAMETER_TYPES: Set<Class<*>> =
  setOf(
    // Used for the `NativeModule` model (see [findNativeModuleModel] and [findNativeVariantAbiModel]). Phased sync also requests it, but
    // only
    // to check whether a project has native models; for the projects it supports, that null result could not be reused for the getModel
    // request of AndroidExtraModelProvider anyway.
    NativeModelBuilderParameter::class.java
  )

/**
 * Records the parameter values that [parameterInitializer] sets, for the known parameter types used by our model providers. The returned
 * value is a data class, so that requests with equal parameters share the same cache key.
 *
 * Fails for any other parameter type, as caching its requests would need to be considered explicitly.
 */
internal fun <P : Any> recordParameters(parameterType: Class<P>, parameterInitializer: Action<in P>): Any {
  val recorder: Any =
    when (parameterType) {
      ModelBuilderParameter::class.java -> RecordedModelBuilderParameter()
      ModelBuilderService.Parameter::class.java -> RecordedModelBuilderServiceParameter()
      LegacyAndroidGradlePluginPropertiesModelParameters::class.java -> RecordedLegacyAndroidGradlePluginPropertiesModelParameters()
      AdditionalClassifierArtifactsModelParameter::class.java -> RecordedAdditionalClassifierArtifactsModelParameter()
      else ->
        error(
          "Unexpected parameter type ${parameterType.name}: add a recorder for it to cache its requests, or add it to UNCACHED_PARAMETER_TYPES."
        )
    }
  @Suppress("UNCHECKED_CAST") parameterInitializer.execute(recorder as P)
  return recorder
}

/**
 * Records a [ModelBuilderParameter], used for the `VariantDependencies`, `VariantDependenciesAdjacencyList` and `ProjectGraph` models (see
 * [findVariantDependenciesV2Model] and [VariantDiscovery]).
 */
private data class RecordedModelBuilderParameter(
  override var variantName: String = "",
  override var dontBuildRuntimeClasspath: Boolean = false,
  override var dontBuildUnitTestRuntimeClasspath: Boolean = false,
  override var dontBuildScreenshotTestRuntimeClasspath: Boolean = false,
  override var dontBuildAndroidTestRuntimeClasspath: Boolean = false,
  override var dontBuildTestFixtureRuntimeClasspath: Boolean = false,
  override var dontBuildHostTestRuntimeClasspath: Map<String, Boolean> = emptyMap(),
  override var additionalArtifactsInModel: Boolean = false,
) : ModelBuilderParameter

/**
 * Records a [ModelBuilderService.Parameter], used for the `KotlinGradleModel` and `KaptGradleModel` models (see
 * [findKotlinModelsForAndroidProject] and [fetchModel]).
 */
private data class RecordedModelBuilderServiceParameter(var recordedValue: String? = null) : ModelBuilderService.Parameter {
  override fun getValue(): String? = recordedValue

  override fun setValue(value: String?) {
    recordedValue = value
  }
}

/**
 * Records a [LegacyAndroidGradlePluginPropertiesModelParameters], used for the `LegacyAndroidGradlePluginProperties` model (see
 * [getLegacyAndroidGradlePluginProperties]).
 */
private data class RecordedLegacyAndroidGradlePluginPropertiesModelParameters(
  override var componentToApplicationIdMap: Boolean = false,
  override var namespace: Boolean = false,
  override var dataBinding: Boolean = false,
  override var mappingFile: Boolean = false,
  override var matchingFallbacks: Boolean = false,
  override var missingDimensionStrategies: Boolean = false,
  override var sourceProvidersConfigurationNames: Boolean = false,
) : LegacyAndroidGradlePluginPropertiesModelParameters

/**
 * Records an [AdditionalClassifierArtifactsModelParameter], used for the `AdditionalClassifierArtifactsModel` model (see
 * [fetchAdditionalClassifierArtifactsModel]). Keyed as a [Set] because the model builder resolves the unordered set of artifacts.
 */
private data class RecordedAdditionalClassifierArtifactsModelParameter(var recordedIdentifiers: Set<ArtifactIdentifier> = emptySet()) :
  AdditionalClassifierArtifactsModelParameter {
  override fun getArtifactIdentifiers(): Collection<ArtifactIdentifier> = recordedIdentifiers

  override fun setArtifactIdentifiers(ids: Collection<ArtifactIdentifier>) {
    recordedIdentifiers = ids.toSet()
  }
}
