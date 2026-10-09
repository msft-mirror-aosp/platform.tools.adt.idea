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
package com.android.screenshottest.action

import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.screenshottest.util.UpdateReferenceImagesDialogManager
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntityId
import com.android.tools.idea.gradle.project.entities.modifyGradleAndroidModelEntity
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResults
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildDefaultConfigStub
import com.android.tools.idea.testing.buildScreenshotTestSourceProviderContainerStub
import com.android.tools.idea.testing.onEdt
import com.intellij.execution.DefaultExecutionTarget
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.junit.Assert.assertEquals
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`

internal fun createMockExecutionEnvironment(
  settings: RunnerAndConfigurationSettings = createMockRunnerAndConfigurationSettings()
): ExecutionEnvironment {
  val runProfile = settings.configuration
  return mock(ExecutionEnvironment::class.java).also {
    `when`(it.runnerAndConfigurationSettings).thenReturn(settings)
    `when`(it.runProfile).thenReturn(runProfile)
  }
}

private fun createMockRunnerAndConfigurationSettings(): RunnerAndConfigurationSettings {
  val mockConfig = mock(RunConfiguration::class.java)
  return mock(RunnerAndConfigurationSettings::class.java).also {
    `when`(it.configuration).thenReturn(mockConfig)
  }
}

internal fun createScreenshotTestProjectRule(): EdtAndroidProjectRule =
  AndroidProjectRule.withAndroidModels(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        gradlePath = ":app",
        selectedBuildVariant = "debug",
        projectBuilder =
          AndroidProjectBuilder(
            defaultConfig = {
              buildDefaultConfigStub()
                .copy(
                  extraSourceProviders =
                    listOfNotNull(
                      androidTestSourceProviderContainer,
                      unitTestSourceProviderContainer,
                      buildScreenshotTestSourceProviderContainerStub(),
                    )
                )
            }
          ),
      ),
      JavaModuleModelBuilder(gradlePath = ":nonAndroidModule"),
    )
    .onEdt()

internal fun linkGradleProjectModules(projectRule: EdtAndroidProjectRule) {
  val rootPath = projectRule.fixture.tempDirPath
  val gradleSettings = GradleSettings.getInstance(projectRule.project)
  val settings =
    gradleSettings.getLinkedProjectSettings(rootPath) ?: GradleProjectSettings(rootPath).also { gradleSettings.linkProject(it) }
  settings.setModules(
    ModuleManager.getInstance(projectRule.project).modules.mapNotNull { ExternalSystemApiUtil.getExternalProjectPath(it) }.toSet() +
      rootPath
  )
}

internal fun stubScreenshotTestAnnotations(projectRule: EdtAndroidProjectRule) {
  val previewTestFile =
    projectRule.fixture.addFileToProject(
      "app/src/screenshotTest/java/com/android/tools/screenshot/PreviewTest.kt",
      """
      package com.android.tools.screenshot

      @Target(AnnotationTarget.FUNCTION)
      annotation class PreviewTest
      """
        .trimIndent(),
    )
  projectRule.fixture.addFileToProject(
    "app/src/screenshotTest/java/androidx/compose/runtime/Composable.kt",
    """
    package androidx.compose.runtime

    @Target(AnnotationTarget.FUNCTION)
    annotation class Composable
    """
      .trimIndent(),
  )
  projectRule.fixture.addFileToProject(
    "app/src/screenshotTest/java/androidx/compose/ui/tooling/preview/Preview.kt",
    """
    package androidx.compose.ui.tooling.preview

    @Target(AnnotationTarget.FUNCTION)
    annotation class Preview(val showBackground: Boolean = false)
    """
      .trimIndent(),
  )

  val screenshotTestJavaDir = projectRule.fixture.tempDirFixture.findOrCreateDir("app/src/screenshotTest/java")
  val appModule = ModuleUtilCore.findModuleForPsiElement(previewTestFile)!!
  PsiTestUtil.addSourceRoot(appModule, screenshotTestJavaDir, true)
}

internal fun findPsiDirectory(projectRule: EdtAndroidProjectRule, relativePath: String): PsiDirectory {
  val virtualDir = projectRule.fixture.tempDirFixture.findOrCreateDir(relativePath)
  return PsiManager.getInstance(projectRule.project).findDirectory(virtualDir)!!
}

internal fun addFileToNonAndroidModule(
  projectRule: EdtAndroidProjectRule,
  relativePathInModule: String,
  content: String,
): PsiFile {
  val nonAndroidModule = ModuleManager.getInstance(projectRule.project).modules.first { it.name.contains("nonAndroidModule") }
  val sourceRootDir = projectRule.fixture.tempDirFixture.findOrCreateDir("nonAndroidModule/src/screenshotTest/java")
  PsiTestUtil.addSourceRoot(nonAndroidModule, sourceRootDir, true)
  projectRule.fixture.addFileToProject(
    "nonAndroidModule/src/screenshotTest/java/com/android/tools/screenshot/PreviewTest.kt",
    """
    package com.android.tools.screenshot

    @Target(AnnotationTarget.FUNCTION)
    annotation class PreviewTest
    """
      .trimIndent(),
  )
  return projectRule.fixture.addFileToProject("nonAndroidModule/$relativePathInModule", content)
}

internal fun createActionEvent(action: AnAction, project: Project, element: PsiElement): AnActionEvent {
  val module = ModuleUtilCore.findModuleForPsiElement(element)
  val dataContext =
    SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.PSI_ELEMENT, element)
      .add(PlatformCoreDataKeys.MODULE, module)
      .add(Location.DATA_KEY, PsiLocation.fromPsiElement(element))
      .build()
  return TestActionEvent.createTestEvent(action, dataContext)
}

internal fun updateAndGetEnabledAndVisible(action: AnAction, project: Project, element: PsiElement): Boolean {
  val event = createActionEvent(action, project, element)
  action.update(event)
  assertEquals(event.presentation.isEnabled, event.presentation.isVisible)
  return event.presentation.isEnabled && event.presentation.isVisible
}

internal fun installMockExecutionManager(projectRule: EdtAndroidProjectRule): MutableList<RunnerAndConfigurationSettings> {
  val project = projectRule.project
  val capturedSettings = mutableListOf<RunnerAndConfigurationSettings>()
  val mockExecutionManager =
    mock(ExecutionManager::class.java) { invocation ->
      if (invocation.method.name == "restartRunProfile") {
        assertEquals(project, invocation.getArgument(0))
        assertEquals(DefaultRunExecutor.getRunExecutorInstance(), invocation.getArgument(1))
        assertEquals(DefaultExecutionTarget.INSTANCE, invocation.getArgument(2))
        capturedSettings.add(invocation.getArgument(3))
      }
      null
    }
  projectRule.projectRule.replaceProjectService(ExecutionManager::class.java, mockExecutionManager)
  return capturedSettings
}

internal fun installSpyDialogFactory(
  project: Project,
  createdDialogs: MutableList<UpdateReferenceImagesDialog>,
): MutableList<UpdateReferenceImagesDialog> {
  val dialogs = mutableListOf<UpdateReferenceImagesDialog>()
  val manager = UpdateReferenceImagesDialogManager.getInstance(project)
  manager.dialogFactory = { proj ->
    spy(UpdateReferenceImagesDialog(proj)).also { spyDialog ->
      createdDialogs += spyDialog
      dialogs += spyDialog
      doAnswer { null }.`when`(spyDialog).show()
    }
  }
  return dialogs
}

internal fun createFakeTestResults(testCases: List<AndroidTestCase>): AndroidTestResults =
  mock(AndroidTestResults::class.java).also {
    `when`(it.getAllTestCases()).thenReturn(testCases)
  }

internal fun withNonDebuggableBuildTypes(project: Project, block: () -> Unit) {
  val modules = ModuleManager.getInstance(project).modules
  val originalModels = mutableMapOf<ModuleId, GradleAndroidModelImpl>()
  try {
    runWriteAction {
      project.workspaceModel.updateProjectModel("Set non-debuggable build types") { storage ->
        for (module in modules) {
          val moduleId = ModuleId(module.name)
          val entity = storage.resolve(GradleAndroidModelEntityId(moduleId)) ?: continue
          val originalModel = entity.gradleAndroidModel
          originalModels[moduleId] = originalModel
          val nonDebuggableBuildTypes =
            originalModel.data.androidProject.multiVariantData
              ?.buildTypes
              ?.map { it.copy(buildType = it.buildType.copy(isDebuggable = false)) }
              .orEmpty()
          val newMultiVariantData = originalModel.data.androidProject.multiVariantData?.copy(buildTypes = nonDebuggableBuildTypes)
          val newAndroidProject = originalModel.data.androidProject.copy(multiVariantData = newMultiVariantData)
          val customModel = GradleAndroidModelImpl(originalModel.data.copy(androidProject = newAndroidProject))
          storage.modifyGradleAndroidModelEntity(entity) { gradleAndroidModel = customModel }
        }
      }
    }
    block()
  } finally {
    runWriteAction {
      project.workspaceModel.updateProjectModel("Restore build types") { storage ->
        for ((moduleId, originalModel) in originalModels) {
          val entity = storage.resolve(GradleAndroidModelEntityId(moduleId)) ?: continue
          storage.modifyGradleAndroidModelEntity(entity) { gradleAndroidModel = originalModel }
        }
      }
    }
  }
}
