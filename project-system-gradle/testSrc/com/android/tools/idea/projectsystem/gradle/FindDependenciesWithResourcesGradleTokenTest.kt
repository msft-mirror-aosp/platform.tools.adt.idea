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
package com.android.tools.idea.projectsystem.gradle

import com.android.projectmodel.ExternalAndroidLibrary
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteComponentCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteSourceImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntitySource
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.projectsystem.DependencyScopeType
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@RunsInEdt
class FindDependenciesWithResourcesGradleTokenTest {

  @get:Rule val projectRule = AndroidProjectRule.onDisk().onEdt()

  private val project
    get() = projectRule.project

  private val token = FindDependenciesWithResourcesGradleToken()
  private val holderPath = GradleHolderProjectPath("/project", ":app")

  private fun createExtraModule(name: String): Module = runWriteAction {
    ModuleManager.getInstance(project).newModule(project.basePath + "/$name.iml", JavaModuleType.getModuleType().id)
  }

  private fun attachGradleProjectPath(module: Module, path: GradleProjectPath) {
    runWriteAction {
      WorkspaceModel.getInstance(project).updateProjectModel("Attach GradleProjectPath") { storage ->
        val moduleEntity = storage.resolve(ModuleId(module.name))!!
        storage.modifyModuleEntity(moduleEntity) {
          this.gradleProjectPath = GradleProjectPathEntity(path, GradleProjectPathEntitySource)
        }
      }
    }
  }

  private fun attachMockAndroidModel(holderModule: Module, testSuites: Map<String, List<String>> = emptyMap()) {
    val mockModel = mock(GradleAndroidModelImpl::class.java)
    val mockVariant = mock(IdeVariantCoreImpl::class.java)
    val components =
      testSuites.keys.map { suiteName ->
        mock(IdeTestSuiteComponentCoreImpl::class.java).also { `when`(it.suiteName).thenReturn(suiteName) }
      }
    `when`(mockVariant.testSuiteComponents).thenReturn(components)
    if (testSuites.isNotEmpty()) {
      val mockProject = mock(IdeAndroidProjectImpl::class.java)
      val suites = testSuites.map { (suiteName, sourceSetNames) ->
        val sources = sourceSetNames.map { sourceName ->
          mock(IdeTestSuiteSourceImpl::class.java).also { `when`(it.name).thenReturn(sourceName) }
        }
        mock(IdeTestSuiteImpl::class.java).also {
          `when`(it.name).thenReturn(suiteName)
          `when`(it.sources).thenReturn(sources)
        }
      }
      `when`(mockProject.testSuites).thenReturn(suites)
      `when`(mockModel.androidProject).thenReturn(mockProject)
    }
    `when`(mockModel.moduleName).thenReturn(holderModule.name)
    `when`(mockModel.selectedVariant).thenReturn(mockVariant)
    runWriteAction {
      WorkspaceModel.getInstance(project).updateProjectModel("Attach GradleAndroidModel") { storage ->
        val moduleEntity = storage.resolve(ModuleId(holderModule.name))!!
        storage.modifyModuleEntity(moduleEntity) {
          this.gradleAndroidModel = GradleAndroidModelEntity(gradleAndroidModel = mockModel, entitySource = moduleEntity.entitySource)
        }
      }
    }
  }

  private fun assertResolvedScope(
    module: Module,
    expectedScope: DependencyScopeType,
    isRClassTransitive: Boolean = false,
  ) {
    val expectedLib = mock(ExternalAndroidLibrary::class.java)
    val moduleSystem = mock(GradleModuleSystem::class.java)
    `when`(moduleSystem.isRClassTransitive).thenReturn(isRClassTransitive)
    `when`(moduleSystem.getAndroidLibraryDependencies(expectedScope)).thenReturn(listOf(expectedLib))

    val result = token.findDependencies(moduleSystem, module)

    assertThat(result).containsExactly(expectedLib)
    verify(moduleSystem).getAndroidLibraryDependencies(expectedScope)
  }

  @Test
  fun testRClassTransitiveAlwaysUsesMainScope() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachMockAndroidModel(projectRule.module)

    assertResolvedScope(unitTestModule, DependencyScopeType.MAIN, isRClassTransitive = true)
  }

  @Test
  fun testResolvesScopeForAllSourceSetsFromStorage() {
    val holderModule = projectRule.module
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val testFixturesModule = createExtraModule("app.testFixtures")
    val screenshotTestModule = createExtraModule("app.screenshotTest")
    val testSuiteModule = createExtraModule("app.mySuiteTest")
    val commonMainModule = createExtraModule("app.commonMain")
    val unlinkedModule = createExtraModule("unlinked")

    attachGradleProjectPath(holderModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(testFixturesModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.TEST_FIXTURES))
    attachGradleProjectPath(screenshotTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST))
    attachGradleProjectPath(testSuiteModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("mySuiteTest")))
    attachGradleProjectPath(commonMainModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("commonMain")))
    attachMockAndroidModel(holderModule, testSuites = mapOf("mySuite" to listOf("mySuiteTest", "unitTest")))
    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(true, projectRule.testRootDisposable)

    val mockPointerManager = mock(ModulePointerManager::class.java)
    projectRule.projectRule.replaceProjectService(ModulePointerManager::class.java, mockPointerManager)

    assertResolvedScope(holderModule, DependencyScopeType.MAIN)
    assertResolvedScope(mainModule, DependencyScopeType.MAIN)
    assertResolvedScope(unitTestModule, DependencyScopeType.UNIT_TEST)
    assertResolvedScope(androidTestModule, DependencyScopeType.ANDROID_TEST)
    assertResolvedScope(testFixturesModule, DependencyScopeType.TEST_FIXTURES)
    assertResolvedScope(screenshotTestModule, DependencyScopeType.SCREENSHOT_TEST)
    assertResolvedScope(testSuiteModule, DependencyScopeType.MAIN)
    assertResolvedScope(commonMainModule, DependencyScopeType.MAIN)
    assertResolvedScope(unlinkedModule, DependencyScopeType.MAIN)

    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(false, projectRule.testRootDisposable)
    assertResolvedScope(testSuiteModule, DependencyScopeType.MAIN)
    assertResolvedScope(unitTestModule, DependencyScopeType.UNIT_TEST)

    verifyNoInteractions(mockPointerManager)
  }

  @Test
  fun testNonAndroidOrIncompleteStorageGroupFallsBackToMainScope() {
    // Pure Java module with testFixtures and unitTest source sets (no GradleAndroidModelEntity).
    val javaHolder = createExtraModule("javalib")
    val javaMain = createExtraModule("javalib.main")
    val javaUnitTest = createExtraModule("javalib.unitTest")
    val javaTestFixtures = createExtraModule("javalib.testFixtures")
    val javaHolderPath = GradleHolderProjectPath("/project", ":javalib")
    attachGradleProjectPath(javaHolder, javaHolderPath)
    attachGradleProjectPath(javaMain, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(javaUnitTest, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(javaTestFixtures, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.TEST_FIXTURES))

    assertResolvedScope(javaUnitTest, DependencyScopeType.MAIN)
    assertResolvedScope(javaTestFixtures, DependencyScopeType.MAIN)

    // Android holder with GradleAndroidModelEntity and unitTest, but missing main source set module.
    val orphanUnitTest = createExtraModule("orphan.unitTest")
    val orphanHolderPath = GradleHolderProjectPath("/project", ":orphan")
    attachGradleProjectPath(projectRule.module, orphanHolderPath)
    attachGradleProjectPath(orphanUnitTest, orphanHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachMockAndroidModel(projectRule.module)

    assertResolvedScope(orphanUnitTest, DependencyScopeType.MAIN)
  }

  @Test
  fun testDisposedModuleUsesMainScope() {
    val tempHolder = createExtraModule("temp")
    val tempMain = createExtraModule("temp.main")
    val tempUnitTest = createExtraModule("temp.unitTest")
    val tempHolderPath = GradleHolderProjectPath("/project", ":temp")
    attachGradleProjectPath(tempHolder, tempHolderPath)
    attachGradleProjectPath(tempMain, tempHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(tempUnitTest, tempHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachMockAndroidModel(tempHolder)

    runWriteAction {
      ModuleManager.getInstance(project).disposeModule(tempUnitTest)
    }

    assertResolvedScope(tempUnitTest, DependencyScopeType.MAIN)
  }

  @Test
  fun testResolvesScopeFromUserDataFallback() {
    val kmpHolder = createExtraModule("shared")
    val kmpMain = createExtraModule("shared.androidMain")
    val kmpHostTest = createExtraModule("shared.androidHostTest")
    val kmpDeviceTest = createExtraModule("shared.androidDeviceTest")
    val kmpTestFixtures = createExtraModule("shared.androidTestFixtures")
    val kmpScreenshotTest = createExtraModule("shared.androidScreenshotTest")
    val kmpTestSuite = createExtraModule("shared.customTestSuite")

    val pointerManager = ModulePointerManager.getInstance(project)
    val kmpGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(kmpHolder),
        main = pointerManager.create(kmpMain),
        unitTest = pointerManager.create(kmpHostTest),
        androidTest = pointerManager.create(kmpDeviceTest),
        testFixtures = pointerManager.create(kmpTestFixtures),
        screenshotTest = pointerManager.create(kmpScreenshotTest),
        testSuites = listOf(pointerManager.create(kmpTestSuite)),
      )
    attachGradleProjectPath(kmpHolder, GradleHolderProjectPath("/project", ":shared"))
    attachMockAndroidModel(kmpHolder)
    listOf(kmpHolder, kmpMain, kmpHostTest, kmpDeviceTest, kmpTestFixtures, kmpScreenshotTest, kmpTestSuite).forEach {
      it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, kmpGroup)
    }

    assertResolvedScope(kmpHolder, DependencyScopeType.MAIN)
    assertResolvedScope(kmpMain, DependencyScopeType.MAIN)
    assertResolvedScope(kmpHostTest, DependencyScopeType.UNIT_TEST)
    assertResolvedScope(kmpDeviceTest, DependencyScopeType.ANDROID_TEST)
    assertResolvedScope(kmpTestFixtures, DependencyScopeType.TEST_FIXTURES)
    assertResolvedScope(kmpScreenshotTest, DependencyScopeType.SCREENSHOT_TEST)
    assertResolvedScope(kmpTestSuite, DependencyScopeType.MAIN)
  }

  @Test
  fun testStaleUserDataIgnoredWhenAllModulesHaveGradleProjectPath() {
    // Pure Java modules (no GradleAndroidModelEntity) with complete GradleProjectPathEntity entries
    // and stale LINKED_ANDROID_GRADLE_MODULE_GROUP user data should match storage (MAIN), not stale user data.
    val javaHolder = createExtraModule("stale")
    val javaMain = createExtraModule("stale.main")
    val javaUnitTest = createExtraModule("stale.unitTest")
    val javaHolderPath = GradleHolderProjectPath("/project", ":stale")
    attachGradleProjectPath(javaHolder, javaHolderPath)
    attachGradleProjectPath(javaMain, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(javaUnitTest, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))

    val pointerManager = ModulePointerManager.getInstance(project)
    val staleGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(javaHolder),
        main = pointerManager.create(javaMain),
        unitTest = pointerManager.create(javaUnitTest),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    listOf(javaHolder, javaMain, javaUnitTest).forEach { it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, staleGroup) }

    assertResolvedScope(javaUnitTest, DependencyScopeType.MAIN)
  }
}
