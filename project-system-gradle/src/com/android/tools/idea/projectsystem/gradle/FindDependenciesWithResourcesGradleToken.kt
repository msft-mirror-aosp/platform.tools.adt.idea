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

import com.android.projectmodel.ExternalAndroidLibrary
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.project.entities.GradleProjectPathSymbolicId
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.projectsystem.DependencyScopeType
import com.android.tools.idea.projectsystem.FindDependenciesWithResourcesToken
import com.android.tools.idea.projectsystem.GradleToken
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModulePointer
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.workspaceModel.ide.legacyBridge.findModule
import com.intellij.workspaceModel.ide.legacyBridge.findModuleEntity

class FindDependenciesWithResourcesGradleToken : FindDependenciesWithResourcesToken<GradleModuleSystem>, GradleToken {
  override fun findDependencies(moduleSystem: GradleModuleSystem, module: Module): Collection<ExternalAndroidLibrary> {
    val dependencyScope =
      if (moduleSystem.isRClassTransitive) {
        DependencyScopeType.MAIN
      } else {
        when (module.resolveWellKnownAndroidSourceSet()) {
          IdeModuleWellKnownSourceSet.UNIT_TEST -> DependencyScopeType.UNIT_TEST
          IdeModuleWellKnownSourceSet.ANDROID_TEST -> DependencyScopeType.ANDROID_TEST
          IdeModuleWellKnownSourceSet.TEST_FIXTURES -> DependencyScopeType.TEST_FIXTURES
          IdeModuleWellKnownSourceSet.SCREENSHOT_TEST -> DependencyScopeType.SCREENSHOT_TEST
          IdeModuleWellKnownSourceSet.MAIN,
          null -> DependencyScopeType.MAIN
        }
      }
    return moduleSystem.getAndroidLibraryDependencies(dependencyScope)
  }
}

// TODO(b/574782127): Consolidate with LinkedAndroidModuleGroupUtils once the in-flight
//  linked-module storage resolution changes land, instead of duplicating workspace-storage
//  and LINKED_ANDROID_GRADLE_MODULE_GROUP user-data resolution here.
private fun Module.resolveWellKnownAndroidSourceSet(): IdeModuleWellKnownSourceSet? {
  if (isDisposed || project.isDisposed) return null
  val storage = WorkspaceModel.getInstance(project).currentSnapshot
  return resolveWellKnownAndroidSourceSetFromStorage(storage) ?: resolveWellKnownAndroidSourceSetFromUserData(storage)
}

private fun Module.resolveWellKnownAndroidSourceSetFromStorage(storage: EntityStorage): IdeModuleWellKnownSourceSet? {
  val storagePath = findModuleEntity(storage)?.gradleProjectPath?.gradleProjectPath as? GradleSourceSetProjectPath ?: return null
  val sourceSet = storagePath.sourceSet as? IdeModuleWellKnownSourceSet ?: return null
  val holderPath = storagePath.toHolder()
  val holderEntity = storage.resolve(GradleProjectPathSymbolicId(holderPath))?.module ?: return null
  if (holderEntity.findModule(storage)?.isDisposed != false || holderEntity.gradleAndroidModel == null) return null
  if (sourceSet == IdeModuleWellKnownSourceSet.MAIN) return sourceSet
  val mainModule =
    storage.resolve(GradleProjectPathSymbolicId(holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN)))?.module?.findModule(storage)
  return sourceSet.takeIf { mainModule?.isDisposed == false }
}

private fun Module.resolveWellKnownAndroidSourceSetFromUserData(storage: EntityStorage): IdeModuleWellKnownSourceSet? {
  val userDataGroup =
    getUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP)?.takeIf {
      it.holder.resolve() != null && it.main.resolve() != null
    } ?: return null
  val userDataSourceSet =
    when (this) {
      userDataGroup.main.resolve() -> IdeModuleWellKnownSourceSet.MAIN
      userDataGroup.unitTest.resolve() -> IdeModuleWellKnownSourceSet.UNIT_TEST
      userDataGroup.androidTest.resolve() -> IdeModuleWellKnownSourceSet.ANDROID_TEST
      userDataGroup.testFixtures.resolve() -> IdeModuleWellKnownSourceSet.TEST_FIXTURES
      userDataGroup.screenshotTest.resolve() -> IdeModuleWellKnownSourceSet.SCREENSHOT_TEST
      else -> null
    } ?: return null
  return userDataSourceSet.takeIf {
    userDataGroup.getModules().any { it.findModuleEntity(storage)?.gradleProjectPath == null }
  }
}

private fun ModulePointer?.resolve(): Module? = this?.module?.takeIf { !it.isDisposed }
