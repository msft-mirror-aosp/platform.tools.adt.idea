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

import com.android.flags.junit.FlagRule
import com.android.screenshottest.producers.IS_SCREENSHOT_UPDATE_CONFIGURATION
import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.screenshottest.util.UpdateReferenceImagesDialogManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getModuleSystem
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunsInEdt
class UpdateReferenceImagesInDirectoryActionTest {

  private val projectRule = createScreenshotTestProjectRule()

  @get:Rule val ruleChain = RuleChain(projectRule, FlagRule(StudioFlags.ENABLE_SCREENSHOT_TESTING, true))

  private val action = UpdateReferenceImagesInDirectoryAction()
  private val createdDialogs = mutableListOf<UpdateReferenceImagesDialog>()

  @Before
  fun setUp() {
    UpdateReferenceImagesDialogManager.getInstance(projectRule.project).resetForTests()
    linkGradleProjectModules(projectRule)
  }

  @After
  fun tearDown() {
    for (dialog in createdDialogs) {
      if (!dialog.isDisposed) {
        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
      }
    }
    createdDialogs.clear()
    UpdateReferenceImagesDialogManager.getInstance(projectRule.project).resetForTests()
  }

  @Test
  fun testActionRegisteredAndUpdateThread() {
    val registered = ActionManager.getInstance().getAction("com.android.screenshottest.action.UpdateReferenceImagesInDirectoryAction")
    assertTrue(registered is UpdateReferenceImagesInDirectoryAction)
    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
  }

  @Test
  fun testUpdate_enabledForScreenshotTestDirectoriesAndAncestors() {
    projectRule.fixture.addFileToProject(
      "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
      "package com.example\nclass MyScreenshotTest",
    )

    val examplePkgDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java/com/example")
    val comPkgDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java/com")
    val javaRootDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java")
    val screenshotTestDir = findPsiDirectory(projectRule, "app/src/screenshotTest")
    val srcDir = findPsiDirectory(projectRule, "app/src")
    val appDir = findPsiDirectory(projectRule, "app")

    assertTrue(isEnabledAndVisible(examplePkgDir))
    assertTrue(isEnabledAndVisible(comPkgDir))
    assertTrue(isEnabledAndVisible(javaRootDir))
    assertTrue(isEnabledAndVisible(screenshotTestDir))
    assertTrue(isEnabledAndVisible(srcDir))
    assertTrue(isEnabledAndVisible(appDir))
  }

  @Test
  fun testUpdate_disabledForNonScreenshotTestDirectories() {
    projectRule.fixture.addFileToProject(
      "app/src/main/java/com/example/MainActivity.kt",
      "package com.example\nclass MainActivity",
    )
    projectRule.fixture.addFileToProject(
      "app/src/test/java/com/example/ExampleUnitTest.kt",
      "package com.example\nclass ExampleUnitTest",
    )
    projectRule.fixture.addFileToProject(
      "app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt",
      "package com.example\nclass ExampleInstrumentedTest",
    )

    assertFalse(isEnabledAndVisible(findPsiDirectory(projectRule, "app/src/main/java/com/example")))
    assertFalse(isEnabledAndVisible(findPsiDirectory(projectRule, "app/src/main/java")))
    assertFalse(isEnabledAndVisible(findPsiDirectory(projectRule, "app/src/test/java/com/example")))
    assertFalse(isEnabledAndVisible(findPsiDirectory(projectRule, "app/src/androidTest/java/com/example")))
  }

  @Test
  fun testUpdate_disabledForNonDirectoryPsiElements() {
    val screenshotFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        """
        package com.example
        class MyScreenshotTest {
          fun previewMethod() {}
        }
        """
          .trimIndent(),
      )
    val ktClass = PsiTreeUtil.findChildOfType(screenshotFile, KtClass::class.java)!!
    val ktFunction = PsiTreeUtil.findChildOfType(screenshotFile, KtNamedFunction::class.java)!!

    assertFalse(isEnabledAndVisible(screenshotFile))
    assertFalse(isEnabledAndVisible(ktClass))
    assertFalse(isEnabledAndVisible(ktFunction))
  }

  @Test
  fun testUpdate_disabledWhenStudioFlagDisabled() {
    StudioFlags.ENABLE_SCREENSHOT_TESTING.override(false)
    val screenshotFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        "package com.example\nclass MyScreenshotTest",
      )

    assertFalse(isEnabledAndVisible(screenshotFile.containingDirectory))
  }

  @Test
  fun testUpdate_disabledForNonDebuggableBuildTypes() {
    val screenshotFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        "package com.example\nclass MyScreenshotTest",
      )
    val directory = screenshotFile.containingDirectory
    assertTrue(isEnabledAndVisible(directory))

    withNonDebuggableBuildTypes(projectRule.project) {
      val module = ModuleUtilCore.findModuleForPsiElement(directory)!!
      assertFalse(module.getModuleSystem().isDebuggable)
      assertFalse(isEnabledAndVisible(directory))
    }
  }

  @Test
  fun testUpdate_disabledForMissingLocation() {
    val noLocationContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, projectRule.project).build()
    val noLocationEvent = TestActionEvent.createTestEvent(action, noLocationContext)
    action.update(noLocationEvent)
    assertFalse(noLocationEvent.presentation.isEnabled)
    assertFalse(noLocationEvent.presentation.isVisible)
  }

  @Test
  fun testUpdate_disabledForNonAndroidModule() {
    val nonAndroidFile =
      addFileToNonAndroidModule(
        projectRule,
        "src/screenshotTest/java/com/example/NonAndroid.kt",
        "package com.example\nclass NonAndroid",
      )
    val nonAndroidDir = nonAndroidFile.containingDirectory
    val module = ModuleUtilCore.findModuleForPsiElement(nonAndroidDir)
    assertNotNull("Directory should belong to :nonAndroidModule", module)
    assertTrue(module!!.name.contains("nonAndroidModule"))
    assertNull("Module should not have an AndroidFacet", AndroidFacet.getInstance(module))

    assertFalse(isEnabledAndVisible(nonAndroidDir))
  }

  @Test
  fun testActionPerformed_executesUpdateConfigurationForPackageSourceRootAndModuleRoot() {
    val project = projectRule.project
    val capturedSettings = installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(project, createdDialogs)

    val screenshotFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        "package com.example\nclass MyScreenshotTest",
      )
    val examplePkgDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java/com/example")
    val javaRootDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java")
    val appDir = findPsiDirectory(projectRule, "app")

    val appModule = ModuleUtilCore.findModuleForPsiElement(screenshotFile)!!
    PsiTestUtil.addSourceRoot(appModule, javaRootDir.virtualFile, true)

    // 1. Package directory -> ScreenshotTestAllInPackageGradleConfigurationProducer
    action.actionPerformed(createActionEvent(action, project, examplePkgDir))
    val pkgDialog = dialogs[0]
    verify(pkgDialog).show()
    pkgDialog.close(DialogWrapper.CANCEL_EXIT_CODE)

    // 2. Source root directory (app/src/screenshotTest/java) -> ScreenshotTestAllInDirectoryGradleConfigurationProducer
    action.actionPerformed(createActionEvent(action, project, javaRootDir))
    val sourceRootDialog = dialogs[1]
    assertNotSame(pkgDialog, sourceRootDialog)
    verify(sourceRootDialog).show()
    sourceRootDialog.close(DialogWrapper.CANCEL_EXIT_CODE)

    // 3. Module directory (app) -> ScreenshotTestAllInDirectoryGradleConfigurationProducer
    action.actionPerformed(createActionEvent(action, project, appDir))
    val appDirDialog = dialogs[2]
    assertNotSame(sourceRootDialog, appDirDialog)
    verify(appDirDialog).show()
    appDirDialog.close(DialogWrapper.CANCEL_EXIT_CODE)

    assertEquals(3, capturedSettings.size)

    val pkgSettings = capturedSettings[0]
    assertTrue(pkgSettings.isTemporary)
    assertFalse(pkgSettings.isActivateToolWindowBeforeRun)
    val pkgGradleConfig = pkgSettings.configuration as GradleRunConfiguration
    assertEquals(true, pkgGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(":app:testDebugScreenshotTestDefaultTestSuite", "--tests", "\"com.example.*\""),
      pkgGradleConfig.settings.taskNames,
    )

    val sourceRootSettings = capturedSettings[1]
    assertTrue(sourceRootSettings.isTemporary)
    assertFalse(sourceRootSettings.isActivateToolWindowBeforeRun)
    val sourceRootGradleConfig = sourceRootSettings.configuration as GradleRunConfiguration
    assertEquals(true, sourceRootGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(":app:testDebugScreenshotTestDefaultTestSuite"),
      sourceRootGradleConfig.settings.taskNames,
    )

    val appDirSettings = capturedSettings[2]
    assertTrue(appDirSettings.isTemporary)
    assertFalse(appDirSettings.isActivateToolWindowBeforeRun)
    val appDirGradleConfig = appDirSettings.configuration as GradleRunConfiguration
    assertEquals(true, appDirGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(":app:testDebugScreenshotTestDefaultTestSuite"),
      appDirGradleConfig.settings.taskNames,
    )
  }

  @Test
  fun testActionPerformed_processTerminatedOnMatchingEnvironmentTriggersOnBuildFailed() {
    val project = projectRule.project
    val capturedSettings = installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(project, createdDialogs)

    val screenshotFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        "package com.example\nclass MyScreenshotTest",
      )
    val javaRootDir = findPsiDirectory(projectRule, "app/src/screenshotTest/java")
    val appModule = ModuleUtilCore.findModuleForPsiElement(screenshotFile)!!
    PsiTestUtil.addSourceRoot(appModule, javaRootDir.virtualFile, true)

    action.actionPerformed(createActionEvent(action, project, javaRootDir))
    val dialog = dialogs.single()
    val dirSettings = capturedSettings.single()

    val execPublisher = project.messageBus.syncPublisher(ExecutionManager.EXECUTION_TOPIC)
    val processHandler = mock(ProcessHandler::class.java)

    // 1. Non-matching ExecutionEnvironment with non-zero exit code must NOT trigger onBuildFailed
    val otherEnv = createMockExecutionEnvironment()
    execPublisher.processTerminated(DefaultRunExecutor.EXECUTOR_ID, otherEnv, processHandler, 1)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    verify(dialog, never()).onBuildFailed()
    assertFalse(dialog.isDisposed)

    // 2. Matching ExecutionEnvironment with exit code 0 must NOT trigger onBuildFailed
    val matchingEnv = createMockExecutionEnvironment(dirSettings)
    execPublisher.processTerminated(DefaultRunExecutor.EXECUTOR_ID, matchingEnv, processHandler, 0)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    verify(dialog, never()).onBuildFailed()
    assertFalse(dialog.isDisposed)

    // 3. Matching ExecutionEnvironment with non-zero exit code MUST trigger onBuildFailed and close dialog
    execPublisher.processTerminated(DefaultRunExecutor.EXECUTOR_ID, matchingEnv, processHandler, 1)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    verify(dialog).onBuildFailed()
    assertEquals(DialogWrapper.CANCEL_EXIT_CODE, dialog.exitCode)
  }

  private fun isEnabledAndVisible(element: PsiElement): Boolean = updateAndGetEnabledAndVisible(action, projectRule.project, element)
}
