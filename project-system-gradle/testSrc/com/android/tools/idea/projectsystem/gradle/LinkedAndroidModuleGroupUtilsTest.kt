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

import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntitySource
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import org.jetbrains.android.AndroidTestCase
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LinkedAndroidModuleGroupUtilsTest : AndroidTestCase() {

  private val buildRoot = "/project"
  private val holderPath = GradleHolderProjectPath(buildRoot, ":app")

  private fun createExtraModule(name: String): Module = runWriteAction {
    ModuleManager.getInstance(project).newModule(project.basePath + "/$name.iml", StdModuleTypes.JAVA.id)
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

  private fun attachMockAndroidModel(holderModule: Module) {
    val mockModel = mock(GradleAndroidModelImpl::class.java)
    val mockVariant = mock(IdeVariantCoreImpl::class.java)
    `when`(mockVariant.testSuiteArtifacts).thenReturn(emptyList())
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
  }

  fun testResolvedAndroidModuleGroup() {
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val testFixturesModule = createExtraModule("app.testFixtures")
    val screenshotTestModule = createExtraModule("app.screenshotTest")

    attachGradleProjectPath(myModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(testFixturesModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.TEST_FIXTURES))
    attachGradleProjectPath(screenshotTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.SCREENSHOT_TEST))
    attachMockAndroidModel(myModule)

    val allModules = listOf(myModule, mainModule, unitTestModule, androidTestModule, testFixturesModule, screenshotTestModule)

    allModules.forEach { module ->
      assertThat(module.isLinkedAndroidModule()).isTrue()
      assertThat(module.getAllLinkedModules()).containsExactlyElementsIn(allModules).inOrder()
      assertThat(module.getHolderModule()).isSameAs(myModule)
      assertThat(module.getMainModule()).isSameAs(mainModule)
      assertThat(module.getUnitTestModule()).isSameAs(unitTestModule)
      assertThat(module.getAndroidTestModule()).isSameAs(androidTestModule)
      assertThat(module.getTestFixturesModule()).isSameAs(testFixturesModule)
      assertThat(module.getScreenshotTestModule()).isSameAs(screenshotTestModule)
    }

    assertThat(myModule.isHolderModule()).isTrue()
    assertThat(mainModule.isHolderModule()).isFalse()
    assertThat(unitTestModule.isHolderModule()).isFalse()

    assertThat(myModule.isMainModule()).isFalse()
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

  fun testNonAndroidAndUnlinkedModules() {
    val mainModule = createExtraModule("app.main")
    val commonMainModule = createExtraModule("app.commonMain")
    val javaHolder = createExtraModule("javalib")
    val javaMain = createExtraModule("javalib.main")

    attachGradleProjectPath(myModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(commonMainModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("commonMain")))
    attachMockAndroidModel(myModule)

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

  fun testMissingHolderOrMainModuleFallback() {
    val orphanMain = createExtraModule("orphan.main")
    val missingHolderPath = GradleHolderProjectPath(buildRoot, ":missing")
    attachGradleProjectPath(orphanMain, missingHolderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))

    assertThat(orphanMain.isLinkedAndroidModule()).isFalse()
    assertThat(orphanMain.getHolderModule()).isSameAs(orphanMain)
    assertThat(orphanMain.isHolderModule()).isTrue()

    // Holder with GradleAndroidModelEntity but no main source set module
    attachGradleProjectPath(myModule, holderPath)
    attachMockAndroidModel(myModule)
    assertThat(myModule.isLinkedAndroidModule()).isFalse()
    assertThat(myModule.getMainModule()).isSameAs(myModule)
    assertThat(myModule.isMainModule()).isTrue()
  }

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
}
