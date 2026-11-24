/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.projectsystem.gradle

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdeBaseArtifact
import com.android.tools.idea.gradle.model.IdeBaseArtifactCore
import com.android.tools.idea.gradle.model.IdeVariant
import com.android.tools.idea.gradle.model.IdeVariantCore
import com.android.tools.idea.gradle.model.findTestSuite
import com.android.tools.idea.gradle.model.findTestSuiteArtifact
import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSet
import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.sourceSetNames
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathSymbolicId
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.util.androidFacet
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModulePointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.workspaceModel.ide.legacyBridge.findModule
import com.intellij.workspaceModel.ide.legacyBridge.findModuleEntity
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.VisibleForTesting

/**
 * This class, along with the key [LINKED_ANDROID_GRADLE_MODULE_GROUP] is used to track and group modules that are based on the same Gradle
 * project. In Gradle projects, instances of this class will be attached to all Android modules. This class should not be accessed directly
 * from outside the Gradle project system.
 */
data class LinkedAndroidGradleModuleGroup(
  val holder: ModulePointer,
  val main: ModulePointer,
  val unitTest: ModulePointer?,
  val androidTest: ModulePointer?,
  val testFixtures: ModulePointer?,
  val screenshotTest: ModulePointer?,
  val testSuites: Collection<ModulePointer>,
) {
  fun getModules() =
    listOfNotNull(holder, main, unitTest, androidTest, testFixtures, screenshotTest, *testSuites.toTypedArray())
      .mapNotNull { it.resolve() }
      .distinct()

  override fun toString(): String =
    "holder=${holder.moduleName}, main=${main.moduleName}, unitTest=${unitTest?.moduleName}, " +
      "androidTest=${androidTest?.moduleName}, testFixtures=${testFixtures?.moduleName}, screenshotTest=${screenshotTest?.moduleName}" +
      if (testSuites.isNotEmpty()) ", testSuites=${testSuites.map { it.moduleName }}" else ""

  @VisibleForTesting
  fun toDiagnosticString(): String {
    fun ModulePointer?.describe(): String = if (this == null) "null" else "$moduleName(resolved=${module != null})"
    val suites =
      if (testSuites.isNotEmpty()) {
        ", testSuites=${testSuites.map { "${it.moduleName}(resolved=${it.module != null})" }}"
      } else {
        ""
      }
    return "holder=${holder.describe()}, main=${main.describe()}, unitTest=${unitTest.describe()}, " +
      "androidTest=${androidTest.describe()}, testFixtures=${testFixtures.describe()}, " +
      "screenshotTest=${screenshotTest.describe()}$suites"
  }
}

/**
 * Key used to store [LinkedAndroidGradleModuleGroup] on all modules that are part of the same Gradle project. This key should not be
 * accessed from outside the Gradle project system.
 */
val LINKED_ANDROID_GRADLE_MODULE_GROUP = Key.create<LinkedAndroidGradleModuleGroup>("linked.android.gradle.module.group")

internal sealed interface ResolvedLinkedAndroidModule {
  val holderModule: Module
  val mainModule: Module

  class FromStorage(
    val holderPath: GradleHolderProjectPath,
    override val holderModule: Module,
    override val mainModule: Module,
    val gradleAndroidModelEntity: GradleAndroidModelEntity,
    val sourceSet: IdeModuleSourceSet?,
    val isFallbackTestSuite: Boolean = false,
  ) : ResolvedLinkedAndroidModule

  class FromUserData(
    val group: LinkedAndroidGradleModuleGroup,
    override val holderModule: Module,
    override val mainModule: Module,
  ) : ResolvedLinkedAndroidModule
}

private sealed interface StorageModuleResolution {
  class Resolved(val resolved: ResolvedLinkedAndroidModule.FromStorage) : StorageModuleResolution

  data object Excluded : StorageModuleResolution

  data object Unavailable : StorageModuleResolution
}

private fun Module.getGradleProjectPathFromStorage(storage: EntityStorage): GradleProjectPath? =
  findModuleEntity(storage)?.gradleProjectPath?.gradleProjectPath

private fun GradleProjectPath.resolveModule(storage: EntityStorage): Module? =
  storage.resolve(GradleProjectPathSymbolicId(this))?.module?.findModule(storage)?.takeIf { !it.isDisposed }

internal fun Module.resolveLinkedAndroidModule(storage: EntityStorage): ResolvedLinkedAndroidModule? {
  if (isDisposed || project.isDisposed) return null
  return when (val resolution = resolveLinkedAndroidModuleFromStorage(storage)) {
    is StorageModuleResolution.Resolved -> resolution.resolved
    StorageModuleResolution.Excluded -> null
    // Fallback for modes where GradleProjectPathEntity is not populated on the source-set modules
    // (e.g. KMP, native, non-phased DataNode sync, or cached variant switching via DataNode services).
    StorageModuleResolution.Unavailable -> resolveLinkedAndroidModuleFromUserData(storage)
  }
}

private fun Module.resolveLinkedAndroidModuleFromStorage(storage: EntityStorage): StorageModuleResolution {
  val thisEntity = findModuleEntity(storage) ?: return StorageModuleResolution.Unavailable
  val gpp = thisEntity.gradleProjectPath?.gradleProjectPath ?: return StorageModuleResolution.Unavailable

  val holderPath: GradleHolderProjectPath
  val holderEntity: ModuleEntity
  val holderModule: Module
  val sourceSet: IdeModuleSourceSet?
  when (gpp) {
    is GradleHolderProjectPath -> {
      holderPath = gpp
      holderEntity = thisEntity
      holderModule = this
      sourceSet = null
    }
    is GradleSourceSetProjectPath -> {
      holderPath = gpp.toHolder()
      holderEntity = storage.resolve(GradleProjectPathSymbolicId(holderPath))?.module ?: return StorageModuleResolution.Unavailable
      holderModule = holderEntity.findModule(storage)?.takeIf { !it.isDisposed } ?: return StorageModuleResolution.Unavailable
      sourceSet = gpp.sourceSet
    }
  }

  val gradleAndroidModelEntity = holderEntity.gradleAndroidModel ?: return StorageModuleResolution.Unavailable
  val mainModule =
    if (sourceSet == IdeModuleWellKnownSourceSet.MAIN) {
      this
    } else {
      holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.MAIN) ?: return StorageModuleResolution.Unavailable
    }

  if (sourceSet != null && !sourceSet.isAndroidGroupMember(gradleAndroidModelEntity.gradleAndroidModel)) {
    return StorageModuleResolution.Excluded
  }

  return StorageModuleResolution.Resolved(
    ResolvedLinkedAndroidModule.FromStorage(
      holderPath = holderPath,
      holderModule = holderModule,
      mainModule = mainModule,
      gradleAndroidModelEntity = gradleAndroidModelEntity,
      sourceSet = sourceSet,
    )
  )
}

private fun GradleAndroidModel.expectedTestSuiteSourceSets(): Set<String> =
  if (StudioFlags.AGP_TEST_SUITES_ENABLED.get()) {
    selectedVariant.testSuiteComponents.flatMapTo(linkedSetOf()) { androidProject.findTestSuite(it)?.sourceSetNames.orEmpty() }
  } else {
    emptySet()
  }

private fun IdeModuleSourceSet.isTestSuiteSourceSet(androidModel: GradleAndroidModel): Boolean =
  StudioFlags.AGP_TEST_SUITES_ENABLED.get() &&
    androidModel.selectedVariant.testSuiteComponents.any { component ->
      androidModel.androidProject.findTestSuite(component)?.sourceSetNames.orEmpty().any {
        IdeModuleSourceSetImpl.wellKnownOrCreate(it) == this
      }
    }

private fun IdeModuleSourceSet.isAndroidGroupMember(androidModel: GradleAndroidModel): Boolean =
  when (this) {
    IdeModuleWellKnownSourceSet.MAIN,
    IdeModuleWellKnownSourceSet.UNIT_TEST,
    IdeModuleWellKnownSourceSet.ANDROID_TEST,
    IdeModuleWellKnownSourceSet.TEST_FIXTURES,
    IdeModuleWellKnownSourceSet.SCREENSHOT_TEST -> true
    else -> isTestSuiteSourceSet(androidModel)
  }

private fun ResolvedLinkedAndroidModule.FromStorage.isTestSuite(): Boolean {
  if (isFallbackTestSuite) return true
  val ss = sourceSet ?: return false
  return ss !is IdeModuleWellKnownSourceSet || ss.isTestSuiteSourceSet(gradleAndroidModelEntity.gradleAndroidModel)
}

private fun Module.resolveLinkedAndroidModuleFromUserData(storage: EntityStorage): ResolvedLinkedAndroidModule? {
  val group = getUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP) ?: return null
  val holder = group.holder.resolve() ?: return null
  val main = group.main.resolve() ?: return null

  // When holder and main are already resolved from storage, match only missing-entity siblings so
  // mixed partial-entity states remain consistent and never resurrect unlinked siblings that carry
  // a non-Android GradleProjectPathEntity.
  val holderResolution = holder.resolveLinkedAndroidModuleFromStorage(storage)
  if (holderResolution is StorageModuleResolution.Resolved && holderResolution.resolved.mainModule == main) {
    return resolveMissingEntitySiblingFromUserData(holderResolution.resolved, group, storage)
  }

  if (!group.hasModuleWithoutGradleProjectPath(storage, holder, main)) return null
  return ResolvedLinkedAndroidModule.FromUserData(group = group, holderModule = holder, mainModule = main)
}

private fun Module.resolveMissingEntitySiblingFromUserData(
  holderResolved: ResolvedLinkedAndroidModule.FromStorage,
  userDataGroup: LinkedAndroidGradleModuleGroup,
  storage: EntityStorage,
): ResolvedLinkedAndroidModule.FromStorage? {
  if (getGradleProjectPathFromStorage(storage) != null) return null

  val matchedWellKnownSourceSet =
    when {
      userDataGroup.unitTest.resolve() == this &&
        holderResolved.holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.UNIT_TEST) == null ->
        IdeModuleWellKnownSourceSet.UNIT_TEST
      userDataGroup.androidTest.resolve() == this &&
        holderResolved.holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.ANDROID_TEST) == null ->
        IdeModuleWellKnownSourceSet.ANDROID_TEST
      userDataGroup.testFixtures.resolve() == this &&
        holderResolved.holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.TEST_FIXTURES) == null ->
        IdeModuleWellKnownSourceSet.TEST_FIXTURES
      userDataGroup.screenshotTest.resolve() == this &&
        holderResolved.holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.SCREENSHOT_TEST) == null ->
        IdeModuleWellKnownSourceSet.SCREENSHOT_TEST
      else -> null
    }
  if (matchedWellKnownSourceSet != null) {
    return ResolvedLinkedAndroidModule.FromStorage(
      holderPath = holderResolved.holderPath,
      holderModule = holderResolved.holderModule,
      mainModule = holderResolved.mainModule,
      gradleAndroidModelEntity = holderResolved.gradleAndroidModelEntity,
      sourceSet = matchedWellKnownSourceSet,
    )
  }

  val expectedSuites = holderResolved.gradleAndroidModelEntity.gradleAndroidModel.expectedTestSuiteSourceSets()
  val hasMissingTestSuites =
    expectedSuites.isNotEmpty() &&
      expectedSuites.count { holderResolved.holderPath.resolveSourceSetModule(storage, it) != null } < expectedSuites.size
  if (hasMissingTestSuites && userDataGroup.testSuites.any { it.resolve() == this }) {
    return ResolvedLinkedAndroidModule.FromStorage(
      holderPath = holderResolved.holderPath,
      holderModule = holderResolved.holderModule,
      mainModule = holderResolved.mainModule,
      gradleAndroidModelEntity = holderResolved.gradleAndroidModelEntity,
      sourceSet = null,
      isFallbackTestSuite = true,
    )
  }
  return null
}

private fun LinkedAndroidGradleModuleGroup.hasModuleWithoutGradleProjectPath(
  storage: EntityStorage,
  holderModule: Module,
  mainModule: Module,
): Boolean =
  holderModule.getGradleProjectPathFromStorage(storage) == null ||
    mainModule.getGradleProjectPathFromStorage(storage) == null ||
    unitTest?.resolveWithoutGradleProjectPath(storage) != null ||
    androidTest?.resolveWithoutGradleProjectPath(storage) != null ||
    testFixtures?.resolveWithoutGradleProjectPath(storage) != null ||
    screenshotTest?.resolveWithoutGradleProjectPath(storage) != null ||
    testSuites.any { it.resolveWithoutGradleProjectPath(storage) != null }

private fun Module.resolveSiblingForStorage(
  resolved: ResolvedLinkedAndroidModule.FromStorage,
  storage: EntityStorage,
  targetSourceSet: IdeModuleWellKnownSourceSet,
  userDataSelector: (LinkedAndroidGradleModuleGroup) -> ModulePointer?,
): Module? {
  if (resolved.sourceSet == targetSourceSet) return this
  return resolved.holderPath.resolveSourceSetModule(storage, targetSourceSet)
    ?: resolveFallbackSiblingFromUserData(resolved, storage, userDataSelector)
}

private fun Module.resolveSiblingSourceSetModule(
  storage: EntityStorage,
  targetSourceSet: IdeModuleWellKnownSourceSet,
  userDataSelector: (LinkedAndroidGradleModuleGroup) -> ModulePointer?,
): Module? =
  when (val resolved = resolveLinkedAndroidModule(storage)) {
    null -> null
    is ResolvedLinkedAndroidModule.FromUserData -> userDataSelector(resolved.group).resolve()
    is ResolvedLinkedAndroidModule.FromStorage -> resolveSiblingForStorage(resolved, storage, targetSourceSet, userDataSelector)
  }

private fun Module.isSourceSetModule(
  storage: EntityStorage,
  targetSourceSet: IdeModuleWellKnownSourceSet,
  userDataSelector: (LinkedAndroidGradleModuleGroup) -> ModulePointer?,
): Boolean =
  when (val resolved = resolveLinkedAndroidModule(storage)) {
    null -> false
    is ResolvedLinkedAndroidModule.FromStorage -> resolved.sourceSet == targetSourceSet
    is ResolvedLinkedAndroidModule.FromUserData -> userDataSelector(resolved.group).resolve() == this
  }

private fun Module.resolveFallbackSiblingFromUserData(
  resolved: ResolvedLinkedAndroidModule.FromStorage,
  storage: EntityStorage,
  userDataSelector: (LinkedAndroidGradleModuleGroup) -> ModulePointer?,
): Module? {
  val userDataGroup = getUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP) ?: return null
  val candidate = userDataSelector(userDataGroup)?.resolveWithoutGradleProjectPath(storage) ?: return null
  if (userDataGroup.holder.resolve() != resolved.holderModule || userDataGroup.main.resolve() != resolved.mainModule) {
    return null
  }
  return candidate
}

private fun Module.resolveTestSuiteModules(
  resolved: ResolvedLinkedAndroidModule.FromStorage,
  storage: EntityStorage,
): List<Module> {
  val expectedSourceSets = resolved.gradleAndroidModelEntity.gradleAndroidModel.expectedTestSuiteSourceSets()
  val storageSuites = expectedSourceSets.mapNotNull { resolved.holderPath.resolveSourceSetModule(storage, it) }
  if (storageSuites.size >= expectedSourceSets.size) return storageSuites
  val extraSuites = resolveFallbackTestSuitesFromUserData(resolved, storage)
  return if (extraSuites.isEmpty()) storageSuites else (storageSuites + extraSuites).distinct()
}

private fun Module.resolveFallbackTestSuitesFromUserData(
  resolved: ResolvedLinkedAndroidModule.FromStorage,
  storage: EntityStorage,
): List<Module> {
  val userDataGroup = getUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP) ?: return emptyList()
  if (userDataGroup.testSuites.isEmpty()) return emptyList()
  val candidates = userDataGroup.testSuites.mapNotNull { it.resolveWithoutGradleProjectPath(storage) }
  if (candidates.isEmpty()) return emptyList()
  if (userDataGroup.holder.resolve() != resolved.holderModule || userDataGroup.main.resolve() != resolved.mainModule) {
    return emptyList()
  }
  return candidates
}

private fun ModulePointer.resolveWithoutGradleProjectPath(storage: EntityStorage): Module? =
  resolve()?.takeIf { it.getGradleProjectPathFromStorage(storage) == null }

private fun ModulePointer?.resolve(): Module? = this?.module?.takeIf { !it.isDisposed }

private fun GradleHolderProjectPath.resolveSourceSetModule(
  storage: EntityStorage,
  sourceSet: IdeModuleSourceSet,
): Module? = toSourceSetPath(sourceSet).resolveModule(storage)

private fun GradleHolderProjectPath.resolveSourceSetModule(
  storage: EntityStorage,
  sourceSetName: String,
): Module? = resolveSourceSetModule(storage, IdeModuleSourceSetImpl.wellKnownOrCreate(sourceSetName))

private fun Module.currentStorage(): EntityStorage = WorkspaceModel.getInstance(project).currentSnapshot

internal fun Module.isHolderModule(storage: EntityStorage): Boolean =
  resolveLinkedAndroidModule(storage)?.let { it.holderModule == this } ?: true

fun Module.isHolderModule(): Boolean = isHolderModule(currentStorage())

internal fun Module.getHolderModule(storage: EntityStorage): Module = resolveLinkedAndroidModule(storage)?.holderModule ?: this

fun Module.getHolderModule(): Module = getHolderModule(currentStorage())

internal fun Module.isMainModule(storage: EntityStorage): Boolean =
  resolveLinkedAndroidModule(storage)?.let { it.mainModule == this } ?: true

fun Module.isMainModule(): Boolean = isMainModule(currentStorage())

internal fun Module.getMainModule(storage: EntityStorage): Module = resolveLinkedAndroidModule(storage)?.mainModule ?: this

fun Module.getMainModule(): Module = getMainModule(currentStorage())

internal fun Module.getUnitTestModule(storage: EntityStorage): Module? =
  resolveSiblingSourceSetModule(storage, IdeModuleWellKnownSourceSet.UNIT_TEST) { it.unitTest }

fun Module.isUnitTestModule(): Boolean = isSourceSetModule(currentStorage(), IdeModuleWellKnownSourceSet.UNIT_TEST) { it.unitTest }

fun Module.getUnitTestModule(): Module? = getUnitTestModule(currentStorage())

internal fun Module.getAndroidTestModule(storage: EntityStorage): Module? =
  resolveSiblingSourceSetModule(storage, IdeModuleWellKnownSourceSet.ANDROID_TEST) { it.androidTest }

fun Module.isAndroidTestModule(): Boolean = isSourceSetModule(currentStorage(), IdeModuleWellKnownSourceSet.ANDROID_TEST) { it.androidTest }

fun Module.getAndroidTestModule(): Module? = getAndroidTestModule(currentStorage())

internal fun Module.getScreenshotTestModule(storage: EntityStorage): Module? =
  resolveSiblingSourceSetModule(storage, IdeModuleWellKnownSourceSet.SCREENSHOT_TEST) { it.screenshotTest }

fun Module.isScreenshotTestModule(): Boolean =
  isSourceSetModule(currentStorage(), IdeModuleWellKnownSourceSet.SCREENSHOT_TEST) { it.screenshotTest }

fun Module.getScreenshotTestModule(): Module? = getScreenshotTestModule(currentStorage())

internal fun Module.getTestFixturesModule(storage: EntityStorage): Module? =
  resolveSiblingSourceSetModule(storage, IdeModuleWellKnownSourceSet.TEST_FIXTURES) { it.testFixtures }

fun Module.isTestFixturesModule(): Boolean =
  isSourceSetModule(currentStorage(), IdeModuleWellKnownSourceSet.TEST_FIXTURES) { it.testFixtures }

fun Module.getTestFixturesModule(): Module? = getTestFixturesModule(currentStorage())

internal fun Module.getTestSuiteModules(storage: EntityStorage): List<Module> =
  when (val resolved = resolveLinkedAndroidModule(storage)) {
    null -> emptyList()
    is ResolvedLinkedAndroidModule.FromUserData -> resolved.group.testSuites.mapNotNull { it.resolve() }
    is ResolvedLinkedAndroidModule.FromStorage -> resolveTestSuiteModules(resolved, storage)
  }

fun Module.getTestSuiteModules(): List<Module> = getTestSuiteModules(currentStorage())

internal fun Module.isTestSuiteModule(storage: EntityStorage): Boolean =
  when (val resolved = resolveLinkedAndroidModule(storage)) {
    null -> false
    is ResolvedLinkedAndroidModule.FromStorage -> resolved.isTestSuite()
    is ResolvedLinkedAndroidModule.FromUserData -> resolved.group.testSuites.any { it.resolve() == this }
  }

fun Module.isTestSuiteModule(): Boolean = isTestSuiteModule(currentStorage())

/** The source set name of this test suite module, e.g. `mixedTest`, or null if this module is not a test suite module. */
fun Module.getTestSuiteSourceSetName(): String? =
  when (val resolved = resolveLinkedAndroidModule(currentStorage())) {
    is ResolvedLinkedAndroidModule.FromStorage ->
      when {
        resolved.isFallbackTestSuite -> (getGradleProjectPath() as? GradleSourceSetProjectPath)?.sourceSet?.sourceSetName
        resolved.isTestSuite() -> resolved.sourceSet?.sourceSetName
        else -> null
      }
    is ResolvedLinkedAndroidModule.FromUserData ->
      if (resolved.group.testSuites.any { it.resolve() == this }) {
        (getGradleProjectPath() as? GradleSourceSetProjectPath)?.sourceSet?.sourceSetName
      } else {
        null
      }
    null -> null
  }

/**
 * Returns the artifact of [variant] built from the source set of this test suite module, or null if this module is not a test suite module
 * or its source set has no artifact (an assets source).
 */
fun Module.findTestSuiteArtifact(variant: IdeVariantCore): IdeBaseArtifactCore? =
  getTestSuiteSourceSetName()?.let { variant.findTestSuiteArtifact(it) }

/**
 * Returns the artifact, with its dependencies, of [variant] built from the source set of this test suite module, or null if this module is
 * not a test suite module or its source set has no artifact (an assets source).
 */
fun Module.findTestSuiteArtifact(variant: IdeVariant): IdeBaseArtifact? =
  getTestSuiteSourceSetName()?.let { variant.findTestSuiteArtifact(it) }

/**
 * Utility method to find out if a module is derived from an Android Gradle project. This will return true if the given module is the module
 * representing any of the Android source sets (main/unitTest/androidTest/screenshotTest/testFixtures/testSuites) or the holder module used
 * as the parent of these source set modules.
 */
internal fun Module.isLinkedAndroidModule(storage: EntityStorage): Boolean = resolveLinkedAndroidModule(storage) != null

fun Module.isLinkedAndroidModule(): Boolean = isLinkedAndroidModule(currentStorage())

internal fun Module.getAllLinkedModules(storage: EntityStorage): List<Module> =
  when (val resolved = resolveLinkedAndroidModule(storage)) {
    null -> listOf(this)
    is ResolvedLinkedAndroidModule.FromUserData -> resolved.group.getModules()
    is ResolvedLinkedAndroidModule.FromStorage -> buildList {
        add(resolved.holderModule)
        add(resolved.mainModule)
        resolveSiblingForStorage(resolved, storage, IdeModuleWellKnownSourceSet.UNIT_TEST) { it.unitTest }?.let(::add)
        resolveSiblingForStorage(resolved, storage, IdeModuleWellKnownSourceSet.ANDROID_TEST) { it.androidTest }?.let(::add)
        resolveSiblingForStorage(resolved, storage, IdeModuleWellKnownSourceSet.TEST_FIXTURES) { it.testFixtures }?.let(::add)
        resolveSiblingForStorage(resolved, storage, IdeModuleWellKnownSourceSet.SCREENSHOT_TEST) { it.screenshotTest }?.let(::add)
        addAll(resolveTestSuiteModules(resolved, storage))
      }
        .distinct()
  }

fun Module.getAllLinkedModules(): List<Module> = getAllLinkedModules(currentStorage())

/** Returns all [AndroidFacet]s on the project. It uses a sequence in order to avoid allocations. */
fun Project.androidFacetsForNonHolderModules(): Sequence<AndroidFacet> {
  return ProjectFacetManager.getInstance(this)
    .getModulesWithFacet(AndroidFacet.ID)
    .asSequence()
    .let {
      if (ApplicationManager.getApplication().isUnitTestMode) {
        // We are running some tests that don't set up real-world project structure, so fetch all modules.
        // See http://b/258162266 for more details.
        it
      } else {
        // Holder module has associated facet, but it can be ignored.
        it.filter { module -> !module.isHolderModule() }
      }
    }
    .mapNotNull { it.androidFacet }
}
