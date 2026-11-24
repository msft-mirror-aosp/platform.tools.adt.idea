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
import com.android.tools.idea.gradle.project.entities.getGradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModulePointer
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@RunsInEdt
class LinkedAndroidModuleGroupUtilsTest {

  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val project
    get() = projectRule.project

  private val buildRoot = "/project"
  private val holderPath = GradleHolderProjectPath(buildRoot, ":app")

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

  private fun attachMockAndroidModel(
    holderModule: Module,
    testSuites: Map<String, List<String>> = emptyMap(),
  ): GradleAndroidModelImpl {
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
          this.gradleAndroidModel =
            GradleAndroidModelEntity(
              gradleAndroidModel = mockModel,
              entitySource = moduleEntity.entitySource,
            )
        }
      }
    }
    return mockModel
  }

  @Test
  fun testResolvedAndroidModuleGroup() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val testFixturesModule = createExtraModule("app.testFixtures")
    val screenshotTestModule = createExtraModule("app.screenshotTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(testFixturesModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.TEST_FIXTURES))
    attachGradleProjectPath(screenshotTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST))
    attachMockAndroidModel(projectRule.module)

    val allModules = listOf(projectRule.module, mainModule, unitTestModule, androidTestModule, testFixturesModule, screenshotTestModule)

    allModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(allModules).inOrder()
      assertThat(module.getHolderModule()).isSameAs(projectRule.module)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isSameAs(unitTestModule)
      assertThat(module.getAndroidTestModule()).isSameAs(androidTestModule)
      assertThat(module.getTestFixturesModule()).isSameAs(testFixturesModule)
      assertThat(module.getScreenshotTestModule()).isSameAs(screenshotTestModule)
    }

    assertThat(projectRule.module.isHolderModule()).isTrue()
    assertThat(mainModule.isHolderModule()).isFalse()
    assertThat(unitTestModule.isHolderModule()).isFalse()

    assertThat(projectRule.module.isMainModule()).isFalse()
    assertThat(mainModule.isMainModule()).isTrue()
    assertThat(unitTestModule.isMainModule()).isFalse()

    assertThat(unitTestModule.isUnitTestModule()).isTrue()
    assertThat(mainModule.isUnitTestModule()).isFalse()

    assertThat(androidTestModule.isAndroidTestModule()).isTrue()
    assertThat(mainModule.isAndroidTestModule()).isFalse()

    assertThat(testFixturesModule.isTestFixturesModule()).isTrue()
    assertThat(mainModule.isTestFixturesModule()).isFalse()

    assertThat(screenshotTestModule.isScreenshotTestModule()).isTrue()
    assertThat(mainModule.isScreenshotTestModule()).isFalse()
  }

  @Test
  fun testNonAndroidAndUnlinkedModules() {
    val mainModule = createExtraModule("app.main")
    val commonMainModule = createExtraModule("app.commonMain")
    val javaHolder = createExtraModule("javalib")
    val javaMain = createExtraModule("javalib.main")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(commonMainModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("commonMain")))
    attachMockAndroidModel(projectRule.module)

    val javaHolderPath = GradleHolderProjectPath(buildRoot, ":javalib")
    attachGradleProjectPath(javaHolder, javaHolderPath)
    attachGradleProjectPath(javaMain, javaHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))

    // Non-Android source set in same Gradle project is not part of the linked Android module group
    assertThat(commonMainModule.isLinkedAndroidModule()).isFalse()
    assertThat(commonMainModule.isHolderModule()).isTrue()
    assertThat(commonMainModule.getHolderModule()).isSameAs(commonMainModule)
    assertThat(commonMainModule.isMainModule()).isTrue()
    assertThat(commonMainModule.getMainModule()).isSameAs(commonMainModule)
    assertThat(commonMainModule.getAllLinkedModules()).containsExactly(commonMainModule)

    // Pure Java Gradle modules without GradleAndroidModelEntity are not linked Android modules
    assertThat(javaHolder.isLinkedAndroidModule()).isFalse()
    assertThat(javaMain.isLinkedAndroidModule()).isFalse()
    assertThat(javaMain.getHolderModule()).isSameAs(javaMain)
  }

  @Test
  fun testMissingHolderOrMainModuleFallback() {
    val orphanMain = createExtraModule("orphan.main")
    val missingHolderPath = GradleHolderProjectPath(buildRoot, ":missing")
    attachGradleProjectPath(orphanMain, missingHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))

    assertThat(orphanMain.isLinkedAndroidModule()).isFalse()
    assertThat(orphanMain.getHolderModule()).isSameAs(orphanMain)
    assertThat(orphanMain.isHolderModule()).isTrue()

    // Holder with GradleAndroidModelEntity but no main source set module
    attachGradleProjectPath(projectRule.module, holderPath)
    attachMockAndroidModel(projectRule.module)
    assertThat(projectRule.module.isLinkedAndroidModule()).isFalse()
    assertThat(projectRule.module.getMainModule()).isSameAs(projectRule.module)
    assertThat(projectRule.module.isMainModule()).isTrue()
  }

  @Test
  fun testDisposedModule() {
    val tempHolder = createExtraModule("temp.holder")
    val tempMain = createExtraModule("temp.main")
    val tempHolderPath = GradleHolderProjectPath(buildRoot, ":temp")
    attachGradleProjectPath(tempHolder, tempHolderPath)
    attachGradleProjectPath(tempMain, tempHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachMockAndroidModel(tempHolder)

    runWriteAction {
      ModuleManager.getInstance(project).disposeModule(tempHolder)
      ModuleManager.getInstance(project).disposeModule(tempMain)
    }

    assertThat(tempMain.isLinkedAndroidModule()).isFalse()
    assertThat(tempMain.getHolderModule()).isSameAs(tempMain)
    assertThat(tempHolder.getMainModule()).isSameAs(tempHolder)
    assertThat(tempMain.isHolderModule()).isTrue()
    assertThat(tempHolder.isHolderModule()).isTrue()
    assertThat(tempHolder.isMainModule()).isTrue()
    assertThat(tempMain.isMainModule()).isTrue()
  }

  @Test
  fun testKmpAndroidModuleGroupFromUserData() {
    val kmpHolder = createExtraModule("shared")
    val kmpMain = createExtraModule("shared.androidMain")
    val kmpHostTest = createExtraModule("shared.androidHostTest")
    val kmpDeviceTest = createExtraModule("shared.androidDeviceTest")

    val pointerManager = ModulePointerManager.getInstance(project)
    val kmpGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(kmpHolder),
        main = pointerManager.create(kmpMain),
        unitTest = pointerManager.create(kmpHostTest),
        androidTest = pointerManager.create(kmpDeviceTest),
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    // In phased sync, GradleProjectPathSyncExtension populates GradleHolderProjectPath on the KMP holder module,
    // while KMP Android source-set modules do not have GradleProjectPathEntity and are linked via user data.
    attachGradleProjectPath(kmpHolder, GradleHolderProjectPath(buildRoot, ":shared"))
    attachMockAndroidModel(kmpHolder)

    val kmpModules = listOf(kmpHolder, kmpMain, kmpHostTest, kmpDeviceTest)
    kmpModules.forEach { it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, kmpGroup) }

    kmpModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(kmpModules).inOrder()
      assertThat(module.getHolderModule()).isSameAs(kmpHolder)
      assertThat(module.getMainModule()).isSameAs(kmpMain)
      assertThat(module.getUnitTestModule()).isSameAs(kmpHostTest)
      assertThat(module.getAndroidTestModule()).isSameAs(kmpDeviceTest)
      assertThat(module.getTestFixturesModule()).isNull()
      assertThat(module.getScreenshotTestModule()).isNull()
    }
    assertThat(kmpHolder.isHolderModule()).isTrue()
    assertThat(kmpMain.isHolderModule()).isFalse()
    assertThat(kmpMain.isMainModule()).isTrue()
    assertThat(kmpHostTest.isUnitTestModule()).isTrue()
    assertThat(kmpDeviceTest.isAndroidTestModule()).isTrue()
  }

  @Test
  fun testStorageFastPathDoesNotScanUserDataPointersWhenEntitiesPopulated() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val suiteModule = createExtraModule("app.mySuiteTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(suiteModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("mySuiteTest")))
    attachMockAndroidModel(projectRule.module, testSuites = mapOf("mySuite" to listOf("mySuiteTest")))
    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(true, projectRule.testRootDisposable)

    val holderPtr = mock(ModulePointer::class.java)
    val mainPtr = mock(ModulePointer::class.java)
    val unitTestPtr = mock(ModulePointer::class.java)
    val androidTestPtr = mock(ModulePointer::class.java)
    val testSuitePtr = mock(ModulePointer::class.java)
    val userDataGroup =
      LinkedAndroidGradleModuleGroup(
        holder = holderPtr,
        main = mainPtr,
        unitTest = unitTestPtr,
        androidTest = androidTestPtr,
        testFixtures = null,
        screenshotTest = null,
        testSuites = listOf(testSuitePtr),
      )
    listOf(projectRule.module, mainModule, unitTestModule, androidTestModule, suiteModule).forEach {
      it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, userDataGroup)
    }

    assertThat(mainModule.isLinkedAndroidModule()).isTrue()
    assertThat(mainModule.getHolderModule()).isSameAs(projectRule.module)
    assertThat(mainModule.getMainModule()).isSameAs(mainModule)
    assertThat(mainModule.getUnitTestModule()).isSameAs(unitTestModule)
    assertThat(mainModule.getAndroidTestModule()).isSameAs(androidTestModule)
    assertThat(mainModule.getTestFixturesModule()).isNull()
    assertThat(mainModule.getScreenshotTestModule()).isNull()
    assertThat(mainModule.isUnitTestModule()).isFalse()
    assertThat(unitTestModule.isUnitTestModule()).isTrue()
    assertThat(androidTestModule.isAndroidTestModule()).isTrue()
    assertThat(mainModule.isTestFixturesModule()).isFalse()
    assertThat(mainModule.isScreenshotTestModule()).isFalse()
    assertThat(mainModule.isTestSuiteModule()).isFalse()
    assertThat(suiteModule.isTestSuiteModule()).isTrue()
    assertThat(mainModule.getTestSuiteModules()).containsExactly(suiteModule)
    assertThat(WorkspaceModel.getInstance(project).currentSnapshot.getGradleAndroidModel(mainModule)).isNotNull()
    assertThat(projectRule.module.getAllLinkedModules())
      .containsExactly(projectRule.module, mainModule, unitTestModule, androidTestModule, suiteModule)
      .inOrder()

    verifyNoInteractions(holderPtr, mainPtr, unitTestPtr, androidTestPtr, testSuitePtr)
  }

  @Test
  fun testPartialEntitiesFallbackMergesMissingSiblingFromUserData() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")

    // Simulate cached variant switching where holder, main, and unitTest retain GradleProjectPathEntity
    // from phased sync, while androidTest was recreated via DataNode services without GradleProjectPathEntity.
    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachMockAndroidModel(projectRule.module)

    val pointerManager = ModulePointerManager.getInstance(project)
    val userDataGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(projectRule.module),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = pointerManager.create(androidTestModule),
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    val allModules = listOf(projectRule.module, mainModule, unitTestModule, androidTestModule)
    allModules.forEach { it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, userDataGroup) }

    allModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getHolderModule()).isSameAs(projectRule.module)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isSameAs(unitTestModule)
      assertThat(module.getAndroidTestModule()).isSameAs(androidTestModule)
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(allModules).inOrder()
    }
    assertThat(androidTestModule.isAndroidTestModule()).isTrue()
  }

  @Test
  fun testUnlinkedSiblingWithEntityIsNotResurrectedFromStaleUserData() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    // unitTest has a non-Android GradleProjectPathEntity (e.g. unlinked), while holder retains stale user data.
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl("unlinkedUnitTest", false)))
    attachMockAndroidModel(projectRule.module)

    val pointerManager = ModulePointerManager.getInstance(project)
    val staleGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(projectRule.module),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    projectRule.module.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, staleGroup)
    mainModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, staleGroup)
    unitTestModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, staleGroup)

    assertThat(unitTestModule.isLinkedAndroidModule()).isFalse()
    assertThat(projectRule.module.getUnitTestModule()).isNull()
    assertThat(mainModule.getUnitTestModule()).isNull()
    assertThat(projectRule.module.getAllLinkedModules()).containsExactly(projectRule.module, mainModule).inOrder()
  }

  @Test
  fun testMixedPartialEntitiesAndUnlinkedSiblingConsistency() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")

    // holder and main have standard entities, unitTest was unlinked with a non-Android entity,
    // and androidTest lacks GradleProjectPathEntity (e.g. recreated via DataNode services).
    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl("unlinkedUnitTest", false)))
    val expectedModel = attachMockAndroidModel(projectRule.module)

    val pointerManager = ModulePointerManager.getInstance(project)
    val staleGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(projectRule.module),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = pointerManager.create(androidTestModule),
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    listOf(projectRule.module, mainModule, unitTestModule, androidTestModule).forEach {
      it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, staleGroup)
    }

    val storage = WorkspaceModel.getInstance(project).currentSnapshot
    assertThat(unitTestModule.isLinkedAndroidModule()).isFalse()
    assertThat(unitTestModule.isUnitTestModule()).isFalse()
    assertThat(unitTestModule.getAllLinkedModules()).containsExactly(unitTestModule)
    assertThat(storage.getGradleAndroidModel(unitTestModule)).isNull()

    val expectedLinked = listOf(projectRule.module, mainModule, androidTestModule)
    expectedLinked.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getHolderModule()).isSameAs(projectRule.module)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isNull()
      assertThat(module.getAndroidTestModule()).isSameAs(androidTestModule)
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(expectedLinked).inOrder()
      assertThat(storage.getGradleAndroidModel(module)).isSameAs(expectedModel)
    }
    assertThat(androidTestModule.isAndroidTestModule()).isTrue()
  }

  @Test
  fun testPureUserDataFallbackWithoutGradleProjectPathEntities() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")

    val pointerManager = ModulePointerManager.getInstance(project)
    val userDataGroup =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(projectRule.module),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    val allModules = listOf(projectRule.module, mainModule, unitTestModule)
    allModules.forEach { it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, userDataGroup) }

    allModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getHolderModule()).isSameAs(projectRule.module)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isSameAs(unitTestModule)
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(allModules).inOrder()
    }
  }

  @Test
  fun testStorageFastPathDoesNotCreateModulePointers() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val testFixturesModule = createExtraModule("app.testFixtures")
    val screenshotTestModule = createExtraModule("app.screenshotTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(testFixturesModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.TEST_FIXTURES))
    attachGradleProjectPath(screenshotTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST))
    val expectedModel = attachMockAndroidModel(projectRule.module)

    val mockPointerManager = mock(ModulePointerManager::class.java)
    projectRule.projectRule.replaceProjectService(ModulePointerManager::class.java, mockPointerManager)

    val allModules = listOf(projectRule.module, mainModule, unitTestModule, androidTestModule, testFixturesModule, screenshotTestModule)
    val storage = WorkspaceModel.getInstance(project).currentSnapshot

    allModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getHolderModule()).isSameAs(projectRule.module)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isSameAs(unitTestModule)
      assertThat(module.getAndroidTestModule()).isSameAs(androidTestModule)
      assertThat(module.getTestFixturesModule()).isSameAs(testFixturesModule)
      assertThat(module.getScreenshotTestModule()).isSameAs(screenshotTestModule)
      assertThat(module.getTestSuiteModules()).isEmpty()
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(allModules).inOrder()
      assertThat(storage.getGradleAndroidModel(module)).isSameAs(expectedModel)
    }

    assertThat(projectRule.module.isHolderModule()).isTrue()
    assertThat(mainModule.isMainModule()).isTrue()
    assertThat(unitTestModule.isUnitTestModule()).isTrue()
    assertThat(androidTestModule.isAndroidTestModule()).isTrue()
    assertThat(testFixturesModule.isTestFixturesModule()).isTrue()
    assertThat(screenshotTestModule.isScreenshotTestModule()).isTrue()
    assertThat(mainModule.isTestSuiteModule()).isFalse()
    assertThat(mainModule.getTestSuiteSourceSetName()).isNull()

    verifyNoInteractions(mockPointerManager)
  }

  @Test
  fun testTestSuiteModulesWhenFlagEnabledAndDisabled() {
    val mainModule = createExtraModule("app.main")
    val suiteModule = createExtraModule("app.mySuiteTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(suiteModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("mySuiteTest")))
    attachMockAndroidModel(projectRule.module, testSuites = mapOf("mySuite" to listOf("mySuiteTest")))

    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(true, projectRule.testRootDisposable)
    assertThat(suiteModule.isLinkedAndroidModule()).isTrue()
    assertThat(suiteModule.isTestSuiteModule()).isTrue()
    assertThat(suiteModule.getTestSuiteSourceSetName()).isEqualTo("mySuiteTest")
    assertThat(suiteModule.getHolderModule()).isSameAs(projectRule.module)
    assertThat(suiteModule.getMainModule()).isSameAs(mainModule)
    assertThat(mainModule.isTestSuiteModule()).isFalse()
    assertThat(mainModule.getTestSuiteSourceSetName()).isNull()
    assertThat(mainModule.getTestSuiteModules()).containsExactly(suiteModule)
    assertThat(projectRule.module.getAllLinkedModules()).containsExactly(projectRule.module, mainModule, suiteModule).inOrder()

    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(false, projectRule.testRootDisposable)
    assertThat(suiteModule.isLinkedAndroidModule()).isFalse()
    assertThat(suiteModule.isTestSuiteModule()).isFalse()
    assertThat(suiteModule.getTestSuiteSourceSetName()).isNull()
    assertThat(suiteModule.getHolderModule()).isSameAs(suiteModule)
    assertThat(mainModule.getTestSuiteModules()).isEmpty()
    assertThat(projectRule.module.getAllLinkedModules()).containsExactly(projectRule.module, mainModule).inOrder()
  }

  @Test
  fun testWellKnownSourceSetDeclaredAsTestSuite() {
    val mainModule = createExtraModule("app.main")
    val screenshotTestModule = createExtraModule("app.screenshotTest")

    attachGradleProjectPath(projectRule.module, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(screenshotTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST))
    attachMockAndroidModel(projectRule.module, testSuites = mapOf("screenshotSuite" to listOf("screenshotTest")))

    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(true, projectRule.testRootDisposable)
    assertThat(screenshotTestModule.isScreenshotTestModule()).isTrue()
    assertThat(screenshotTestModule.isTestSuiteModule()).isTrue()
    assertThat(screenshotTestModule.getTestSuiteSourceSetName()).isEqualTo("screenshotTest")
    assertThat(mainModule.getTestSuiteModules()).containsExactly(screenshotTestModule)
    assertThat(screenshotTestModule.getAllLinkedModules()).containsExactly(projectRule.module, mainModule, screenshotTestModule).inOrder()
    assertThat(projectRule.module.getAllLinkedModules()).containsExactly(projectRule.module, mainModule, screenshotTestModule).inOrder()

    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(false, projectRule.testRootDisposable)
    assertThat(screenshotTestModule.isScreenshotTestModule()).isTrue()
    assertThat(screenshotTestModule.isTestSuiteModule()).isFalse()
    assertThat(screenshotTestModule.getTestSuiteSourceSetName()).isNull()
    assertThat(mainModule.getTestSuiteModules()).isEmpty()
  }
}
