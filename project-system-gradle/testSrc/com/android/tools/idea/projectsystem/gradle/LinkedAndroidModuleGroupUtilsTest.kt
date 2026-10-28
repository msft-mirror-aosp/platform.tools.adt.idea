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

import com.android.tools.idea.projectsystem.ModuleHierarchyProvider
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.testFramework.LoggedErrorProcessor
import org.jetbrains.android.AndroidTestCase

class LinkedAndroidModuleGroupUtilsTest : AndroidTestCase() {

  private fun createExtraModule(name: String): Module = runWriteAction {
    ModuleManager.getInstance(project).newModule(project.basePath + "/$name.iml", StdModuleTypes.JAVA.id)
  }

  private fun captureLoggedErrors(block: () -> Unit): List<String> {
    val errors = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<RuntimeException>(
      object : LoggedErrorProcessor() {
        override fun processError(
          category: String,
          message: String,
          details: Array<out String>,
          t: Throwable?,
        ): Set<Action> {
          errors.add(message)
          return Action.NONE
        }
      },
      block,
    )
    return errors
  }

  fun testToStringAndDiagnosticString() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val mainModule = createExtraModule("app.main")
    val suiteModule = createExtraModule("app.suite")
    val groupWithoutSuites =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(myModule),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create("app.unitTest"),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    assertThat(groupWithoutSuites.toString())
      .isEqualTo(
        "holder=${myModule.name}, main=app.main, unitTest=app.unitTest, " + "androidTest=null, testFixtures=null, screenshotTest=null"
      )
    assertThat(groupWithoutSuites.toDiagnosticString())
      .isEqualTo(
        "holder=${myModule.name}(resolved=true), main=app.main(resolved=true), " +
          "unitTest=app.unitTest(resolved=false), androidTest=null, " +
          "testFixtures=null, screenshotTest=null"
      )

    val groupWithSuites =
      groupWithoutSuites.copy(testSuites = listOf(pointerManager.create(suiteModule), pointerManager.create("app.missingSuite")))
    assertThat(groupWithSuites.toString())
      .isEqualTo(
        "holder=${myModule.name}, main=app.main, unitTest=app.unitTest, " +
          "androidTest=null, testFixtures=null, screenshotTest=null, " +
          "testSuites=[app.suite, app.missingSuite]"
      )
    assertThat(groupWithSuites.toDiagnosticString())
      .isEqualTo(
        "holder=${myModule.name}(resolved=true), main=app.main(resolved=true), " +
          "unitTest=app.unitTest(resolved=false), androidTest=null, " +
          "testFixtures=null, screenshotTest=null, " +
          "testSuites=[app.suite(resolved=true), app.missingSuite(resolved=false)]"
      )
  }

  fun testResolvedHolderAndMainModules() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(myModule),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    listOf(myModule, mainModule, unitTestModule).forEach {
      it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)
    }

    val errors = captureLoggedErrors {
      assertThat(myModule.isHolderModule()).isTrue()
      assertThat(mainModule.isHolderModule()).isFalse()
      assertThat(unitTestModule.isHolderModule()).isFalse()

      assertThat(myModule.getHolderModule()).isSameAs(myModule)
      assertThat(mainModule.getHolderModule()).isSameAs(myModule)
      assertThat(unitTestModule.getHolderModule()).isSameAs(myModule)

      assertThat(myModule.isMainModule()).isFalse()
      assertThat(mainModule.isMainModule()).isTrue()
      assertThat(unitTestModule.isMainModule()).isFalse()

      assertThat(myModule.getMainModule()).isSameAs(mainModule)
      assertThat(mainModule.getMainModule()).isSameAs(mainModule)
      assertThat(unitTestModule.getMainModule()).isSameAs(mainModule)
    }
    assertThat(errors).isEmpty()
  }

  fun testMissingHolderPointerFallbackAndLogging() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create("missing.holder"),
        main = pointerManager.create(mainModule),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    mainModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)
    unitTestModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)

    val errors = captureLoggedErrors {
      assertThat(mainModule.getHolderModule()).isSameAs(mainModule)
      assertThat(mainModule.isHolderModule()).isFalse()
      assertThat(unitTestModule.isHolderModule()).isFalse()
    }

    assertThat(errors).hasSize(3)
    assertThat(errors[0]).contains("Missing mandatory module missing.holder in group for")
    assertThat(errors[0]).contains("isDisposed=false")
    assertThat(errors[0])
      .contains("group=[holder=missing.holder(resolved=false), main=app.main(resolved=true), unitTest=app.unitTest(resolved=true)")
  }

  fun testMissingMainPointerFallbackAndLogging() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val unitTestModule = createExtraModule("app.unitTest")
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(myModule),
        main = pointerManager.create("missing.main"),
        unitTest = pointerManager.create(unitTestModule),
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    myModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)
    unitTestModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)

    val errors = captureLoggedErrors {
      assertThat(myModule.getMainModule()).isSameAs(myModule)
      assertThat(myModule.isMainModule()).isFalse()
      assertThat(unitTestModule.isMainModule()).isFalse()
    }

    assertThat(errors).hasSize(3)
    assertThat(errors[0]).contains("Missing mandatory module missing.main in group for")
    assertThat(errors[0])
      .contains("group=[holder=${myModule.name}(resolved=true), main=missing.main(resolved=false), unitTest=app.unitTest(resolved=true)")
  }

  fun testDisposedModuleDoesNotLogMissingMandatoryModuleError() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val tempHolder = createExtraModule("temp.holder")
    val tempMain = createExtraModule("temp.main")
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(tempHolder),
        main = pointerManager.create(tempMain),
        unitTest = null,
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    tempHolder.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)
    tempMain.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)

    runWriteAction {
      ModuleManager.getInstance(project).disposeModule(tempHolder)
      ModuleManager.getInstance(project).disposeModule(tempMain)
    }
    assertThat(tempHolder.isDisposed).isTrue()
    assertThat(tempMain.isDisposed).isTrue()

    val errors = captureLoggedErrors {
      assertThat(tempMain.getHolderModule()).isSameAs(tempMain)
      assertThat(tempHolder.getMainModule()).isSameAs(tempHolder)
      assertThat(tempMain.isHolderModule()).isFalse()
      assertThat(tempHolder.isHolderModule()).isTrue()
      assertThat(tempHolder.isMainModule()).isFalse()
      assertThat(tempMain.isMainModule()).isTrue()
    }
    assertThat(errors).isEmpty()
  }

  fun testGradleModuleSystemResolvesHolderModuleOnce() {
    val pointerManager = ModulePointerManager.getInstance(project)
    val mainModule = createExtraModule("app.main")
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create("missing.holder"),
        main = pointerManager.create(mainModule),
        unitTest = null,
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    mainModule.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)

    val errors = captureLoggedErrors {
      GradleModuleSystem(
        mainModule,
        ProjectBuildModelHandler(project),
        object : ModuleHierarchyProvider {},
      )
    }
    assertThat(errors).hasSize(1)
  }
}
