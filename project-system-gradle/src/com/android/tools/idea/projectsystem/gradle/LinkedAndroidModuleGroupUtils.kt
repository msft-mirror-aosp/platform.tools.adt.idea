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

import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSet
import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.project.entities.GradleProjectPathSymbolicId
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.util.androidFacet
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModulePointer
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.backend.workspace.WorkspaceModel
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
  constructor(
    holder: Module,
    main: Module,
    unitTest: Module?,
    androidTest: Module?,
    testFixtures: Module?,
    screenshotTest: Module?,
    testSuites: Collection<Module>,
  ) : this(
    holder = ModulePointerManager.getInstance(holder.project).create(holder),
    main = ModulePointerManager.getInstance(holder.project).create(main),
    unitTest = unitTest?.let { ModulePointerManager.getInstance(holder.project).create(it) },
    androidTest = androidTest?.let { ModulePointerManager.getInstance(holder.project).create(it) },
    testFixtures = testFixtures?.let { ModulePointerManager.getInstance(holder.project).create(it) },
    screenshotTest = screenshotTest?.let { ModulePointerManager.getInstance(holder.project).create(it) },
    testSuites = testSuites.map { ModulePointerManager.getInstance(holder.project).create(it) },
  )

  fun getModules() =
    listOfNotNull(holder, main, unitTest, androidTest, testFixtures, screenshotTest, *testSuites.toTypedArray()).mapNotNull {
      it.resolve()
    }

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

private fun Module.getGradleProjectPathFromStorage(storage: EntityStorage): GradleProjectPath? =
  findModuleEntity(storage)?.gradleProjectPath?.gradleProjectPath

private fun GradleProjectPath.resolveModule(storage: EntityStorage): Module? =
  storage.resolve(GradleProjectPathSymbolicId(this))?.module?.findModule(storage)?.takeIf { !it.isDisposed }

private fun Module.resolveAndroidModuleGroup(storage: EntityStorage): LinkedAndroidGradleModuleGroup? {
  if (isDisposed || project.isDisposed) return null
  val userDataGroup = resolveAndroidModuleGroupFromUserData()
  if (userDataGroup != null && userDataGroup.getModules().any { it.getGradleProjectPathFromStorage(storage) == null }) {
    // Fallback for modes where GradleProjectPathEntity is not populated on the source-set modules
    // (e.g. KMP, native, non-phased DataNode sync, or cached variant switching via DataNode services).
    return userDataGroup
  }
  val gpp = getGradleProjectPathFromStorage(storage) ?: return null
  val holderPath = gpp.toHolder()
  val holderModule = holderPath.resolveModule(storage) ?: return null
  val holderEntity = holderModule.findModuleEntity(storage)
  val androidModel = holderEntity?.gradleAndroidModel?.gradleAndroidModel ?: return null
  val standardMain = holderPath.resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.MAIN) ?: return null
  return holderPath.resolveAndroidModuleGroup(storage, holderModule, standardMain, androidModel).takeIf {
    it.getModules().contains(this)
  }
}

private fun ModulePointer?.resolve(): Module? = this?.module?.takeIf { !it.isDisposed }

private fun Module.resolveAndroidModuleGroupFromUserData(): LinkedAndroidGradleModuleGroup? {
  val group = getUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP) ?: return null
  if (group.holder.resolve() == null || group.main.resolve() == null) return null
  return group
}

private fun GradleHolderProjectPath.resolveSourceSetModule(
  storage: EntityStorage,
  sourceSet: IdeModuleSourceSet,
): Module? = toSourceSetPath(sourceSet).resolveModule(storage)

private fun GradleHolderProjectPath.resolveSourceSetModule(
  storage: EntityStorage,
  sourceSetName: String,
): Module? = resolveSourceSetModule(storage, IdeModuleSourceSetImpl.wellKnownOrCreate(sourceSetName))

private fun GradleHolderProjectPath.resolveAndroidModuleGroup(
  storage: EntityStorage,
  holderModule: Module,
  mainModule: Module,
  androidModel: GradleAndroidModel,
): LinkedAndroidGradleModuleGroup =
  LinkedAndroidGradleModuleGroup(
    holder = holderModule,
    main = mainModule,
    unitTest = resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.UNIT_TEST),
    androidTest = resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.ANDROID_TEST),
    testFixtures = resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.TEST_FIXTURES),
    screenshotTest = resolveSourceSetModule(storage, IdeModuleWellKnownSourceSet.SCREENSHOT_TEST),
    testSuites =
      androidModel.selectedVariant.testSuiteArtifacts.mapNotNull { resolveSourceSetModule(storage, it.suiteName) },
  )

private fun Module.currentStorage(): EntityStorage = WorkspaceModel.getInstance(project).currentSnapshot

internal fun Module.isHolderModule(storage: EntityStorage): Boolean =
  resolveAndroidModuleGroup(storage)?.let { it.holder.resolve() == this } ?: true

fun Module.isHolderModule(): Boolean = isHolderModule(currentStorage())

internal fun Module.getHolderModule(storage: EntityStorage): Module =
  resolveAndroidModuleGroup(storage)?.holder.resolve() ?: this

fun Module.getHolderModule(): Module = getHolderModule(currentStorage())

internal fun Module.isMainModule(storage: EntityStorage): Boolean =
  resolveAndroidModuleGroup(storage)?.let { it.main.resolve() == this } ?: true

fun Module.isMainModule(): Boolean = isMainModule(currentStorage())

internal fun Module.getMainModule(storage: EntityStorage): Module = resolveAndroidModuleGroup(storage)?.main.resolve() ?: this

fun Module.getMainModule(): Module = getMainModule(currentStorage())

internal fun Module.getUnitTestModule(storage: EntityStorage): Module? = resolveAndroidModuleGroup(storage)?.unitTest.resolve()

fun Module.isUnitTestModule(): Boolean = getUnitTestModule() == this

fun Module.getUnitTestModule(): Module? = getUnitTestModule(currentStorage())

internal fun Module.getAndroidTestModule(storage: EntityStorage): Module? =
  resolveAndroidModuleGroup(storage)?.androidTest.resolve()

fun Module.isAndroidTestModule(): Boolean = getAndroidTestModule() == this

fun Module.getAndroidTestModule(): Module? = getAndroidTestModule(currentStorage())

internal fun Module.getScreenshotTestModule(storage: EntityStorage): Module? =
  resolveAndroidModuleGroup(storage)?.screenshotTest.resolve()

fun Module.isScreenshotTestModule(): Boolean = getScreenshotTestModule() == this

fun Module.getScreenshotTestModule(): Module? = getScreenshotTestModule(currentStorage())

internal fun Module.getTestFixturesModule(storage: EntityStorage): Module? =
  resolveAndroidModuleGroup(storage)?.testFixtures.resolve()

fun Module.isTestFixturesModule(): Boolean = getTestFixturesModule() == this

fun Module.getTestFixturesModule(): Module? = getTestFixturesModule(currentStorage())

internal fun Module.getTestSuiteModules(storage: EntityStorage): List<Module> =
  resolveAndroidModuleGroup(storage)?.testSuites?.mapNotNull { it.resolve() } ?: emptyList()

fun Module.getTestSuiteModules(): List<Module> = getTestSuiteModules(currentStorage())

fun Module.isTestSuiteModule(): Boolean = getTestSuiteModules().contains(this)

/**
 * Utility method to find out if a module is derived from an Android Gradle project. This will return true if the given module is the module
 * representing any of the Android source sets (main/unitTest/androidTest/screenshotTest/testFixtures/testSuites) or the holder module used
 * as the parent of these source set modules.
 */
internal fun Module.isLinkedAndroidModule(storage: EntityStorage): Boolean = resolveAndroidModuleGroup(storage) != null

fun Module.isLinkedAndroidModule(): Boolean = isLinkedAndroidModule(currentStorage())

internal fun Module.getAllLinkedModules(storage: EntityStorage): List<Module> =
  resolveAndroidModuleGroup(storage)?.getModules() ?: listOf(this)

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
