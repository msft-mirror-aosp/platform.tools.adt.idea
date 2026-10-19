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
import com.android.screenshottest.producers.isClassDeclarationWithPreviewTestAnnotatedMethods
import com.android.screenshottest.ui.PreviewDetails
import com.android.screenshottest.ui.UpdateReferenceImagesDialog
import com.android.screenshottest.util.UpdateReferenceImagesDialogManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCase
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestSuite
import com.android.tools.idea.testartifacts.instrumented.testsuite.view.AndroidTestSuiteView
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
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunsInEdt
class UpdateReferenceImagesInClassActionTest {

  private val projectRule = createScreenshotTestProjectRule()

  @get:Rule val ruleChain = RuleChain(projectRule, FlagRule(StudioFlags.ENABLE_SCREENSHOT_TESTING, true))

  private val action = UpdateReferenceImagesInClassAction()
  private val createdDialogs = mutableListOf<UpdateReferenceImagesDialog>()

  @Before
  fun setUp() {
    UpdateReferenceImagesDialogManager.getInstance(projectRule.project).resetForTests()
    linkGradleProjectModules(projectRule)
    stubScreenshotTestAnnotations(projectRule)
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
    val registered = ActionManager.getInstance().getAction("com.android.screenshottest.action.UpdateReferenceImagesInClassAction")
    assertTrue(registered is UpdateReferenceImagesInClassAction)
    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
  }

  @Test
  fun testUpdate_enabledForClassWithPreviewTestMethod() {
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MyScreenshotTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import com.android.tools.screenshot.PreviewTest

        class MyScreenshotTest {
          @PreviewTest
          @Preview(showBackground = true)
          @Composable
          fun GreetingPreview() {
            println("Hello")
          }
        }
        """
          .trimIndent(),
      )

    val ktClass = PsiTreeUtil.findChildOfType(ktFile, KtClass::class.java)!!
    val ktFunction = PsiTreeUtil.findChildOfType(ktFile, KtNamedFunction::class.java)!!
    val leafInsideMethod = ktFunction.bodyExpression!!.firstChild

    // Enabled on KtFile, KtClass, KtNamedFunction, and leaf element inside the class
    assertTrue(isEnabledAndVisible(ktFile))
    assertTrue(isEnabledAndVisible(ktClass))
    assertTrue(isEnabledAndVisible(ktFunction))
    assertTrue(isEnabledAndVisible(leafInsideMethod))
  }

  @Test
  fun testUpdate_enabledForTopLevelPreviewTestFunction() {
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/TopLevelScreenshotTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        @Composable
        fun TopLevelPreview() {
          println("Top level")
        }
        """
          .trimIndent(),
      )

    val ktFunction = PsiTreeUtil.findChildOfType(ktFile, KtNamedFunction::class.java)!!
    val leafInsideFunction = ktFunction.bodyExpression!!.firstChild

    // Enabled on KtFile, top-level KtNamedFunction, and leaf element inside top-level function
    assertTrue(isEnabledAndVisible(ktFile))
    assertTrue(isEnabledAndVisible(ktFunction))
    assertTrue(isEnabledAndVisible(leafInsideFunction))
  }

  @Test
  fun testUpdate_javaClassAndPsiClassOwnerSupport() {
    val javaFileWithTest =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/JavaScreenshotTest.java",
        """
        package com.example;

        import com.android.tools.screenshot.PreviewTest;

        public class JavaScreenshotTest {
          @PreviewTest
          public void previewMethod() {}
        }
        """
          .trimIndent(),
      ) as PsiJavaFile

    val javaClassWithTest = javaFileWithTest.classes.single()
    assertTrue(isEnabledAndVisible(javaFileWithTest))
    assertTrue(isEnabledAndVisible(javaClassWithTest))

    val javaFileWithoutTest =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/JavaRegularClass.java",
        """
        package com.example;

        public class JavaRegularClass {
          public void regularMethod() {}
        }
        """
          .trimIndent(),
      ) as PsiJavaFile

    assertFalse(isEnabledAndVisible(javaFileWithoutTest))
    assertFalse(isEnabledAndVisible(javaFileWithoutTest.classes.single()))
  }

  @Test
  fun testUpdate_disabledWhenNoPreviewTestAnnotation() {
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/NoPreviewTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        class NoPreviewTest {
          @Preview(showBackground = true)
          @Composable
          fun GreetingPreview() {}
        }

        class EmptyClass
        """
          .trimIndent(),
      )

    val classes = PsiTreeUtil.findChildrenOfType(ktFile, KtClass::class.java).toList()
    val ktFunction = PsiTreeUtil.findChildOfType(ktFile, KtNamedFunction::class.java)!!

    assertFalse(isEnabledAndVisible(ktFile))
    classes.forEach { assertFalse(isEnabledAndVisible(it)) }
    assertFalse(isEnabledAndVisible(ktFunction))
  }

  @Test
  fun testUpdate_disabledForDirectory() {
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/DirCheckTest.kt",
        """
        package com.example
        import com.android.tools.screenshot.PreviewTest
        class DirCheckTest {
          @PreviewTest fun test() {}
        }
        """
          .trimIndent(),
      )

    assertFalse(isEnabledAndVisible(ktFile.containingDirectory))
  }

  @Test
  fun testUpdate_disabledOutsideScreenshotTestSourceSet() {
    val content =
      """
      package com.example
      import com.android.tools.screenshot.PreviewTest
      class OutsideSourceSetTest {
        @PreviewTest fun test() {}
      }
      """
        .trimIndent()

    val mainFile = projectRule.fixture.addFileToProject("app/src/main/java/com/example/MainTest.kt", content)
    val unitTestFile = projectRule.fixture.addFileToProject("app/src/test/java/com/example/UnitTest.kt", content)
    val androidTestFile =
      projectRule.fixture.addFileToProject(
        "app/src/androidTest/java/com/example/AndroidTest.kt",
        content,
      )

    assertFalse(isEnabledAndVisible(mainFile))
    assertFalse(isEnabledAndVisible(unitTestFile))
    assertFalse(isEnabledAndVisible(androidTestFile))
  }

  @Test
  fun testUpdate_disabledWhenStudioFlagDisabled() {
    StudioFlags.ENABLE_SCREENSHOT_TESTING.override(false)
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/FlagDisabledTest.kt",
        """
        package com.example
        import com.android.tools.screenshot.PreviewTest
        class FlagDisabledTest {
          @PreviewTest fun test() {}
        }
        """
          .trimIndent(),
      )

    assertFalse(isEnabledAndVisible(ktFile))
  }

  @Test
  fun testUpdate_disabledForNonDebuggableBuildTypes() {
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/ReleaseVariantTest.kt",
        """
        package com.example
        import com.android.tools.screenshot.PreviewTest
        class ReleaseVariantTest {
          @PreviewTest fun test() {}
        }
        """
          .trimIndent(),
      )

    assertTrue(isEnabledAndVisible(ktFile))

    withNonDebuggableBuildTypes(projectRule.project) {
      val module = ModuleUtilCore.findModuleForPsiElement(ktFile)!!
      assertFalse(module.getModuleSystem().isDebuggable)
      assertFalse(isEnabledAndVisible(ktFile))
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
        "src/screenshotTest/java/com/example/NonAndroidTest.kt",
        """
        package com.example
        import com.android.tools.screenshot.PreviewTest
        class NonAndroidTest {
          @PreviewTest fun test() {}
        }
        """
          .trimIndent(),
      )
        as KtFile
    assertTrue(
      "Class must have @PreviewTest method so update() reaches the AndroidFacet check",
      isClassDeclarationWithPreviewTestAnnotatedMethods(nonAndroidFile.classes.single()),
    )
    val module = ModuleUtilCore.findModuleForPsiElement(nonAndroidFile)
    assertNotNull("File should belong to :nonAndroidModule", module)
    assertTrue(module!!.name.contains("nonAndroidModule"))
    assertNull("Module should not have an AndroidFacet", AndroidFacet.getInstance(module))

    assertFalse(isEnabledAndVisible(nonAndroidFile))
  }

  @Test
  fun testUpdate_disabledForNonClassOwnerFile() {
    val plainTextFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/notes.txt",
        "Plain text file inside screenshotTest source root",
      )
    assertFalse(isEnabledAndVisible(plainTextFile))
    assertFalse(isEnabledAndVisible(plainTextFile.firstChild))
  }

  @Test
  fun testActionPerformed_createsUpdateConfigurationForKotlinAndJavaClasses() {
    val capturedSettings = installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(projectRule.project, createdDialogs)

    // 1. Kotlin class
    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/ActionPerformedTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        class ActionPerformedTest {
          @PreviewTest
          @Composable
          fun GreetingPreview() {}
        }
        """
          .trimIndent(),
      )
    val ktClass = PsiTreeUtil.findChildOfType(ktFile, KtClass::class.java)!!

    action.actionPerformed(createActionEvent(action, projectRule.project, ktClass))

    val ktDialog = dialogs.single()
    verify(ktDialog).show()
    ktDialog.close(DialogWrapper.CANCEL_EXIT_CODE)

    val ktSettings = capturedSettings.single()
    assertEquals("Screenshot Tests in com.example.ActionPerformedTest", ktSettings.name)
    assertTrue(ktSettings.isTemporary)
    assertFalse(ktSettings.isActivateToolWindowBeforeRun)

    val ktGradleConfig = ktSettings.configuration as GradleRunConfiguration
    assertEquals(true, ktGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(
        ":app:testDebugScreenshotTestDefaultTestSuite",
        "--tests",
        "\"com.example.ActionPerformedTest\"",
      ),
      ktGradleConfig.settings.taskNames,
    )

    // 2. Java class
    val javaFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/JavaScreenshotTest.java",
        """
        package com.example;

        import com.android.tools.screenshot.PreviewTest;

        public class JavaScreenshotTest {
          @PreviewTest
          public void previewMethod() {}
        }
        """
          .trimIndent(),
      ) as PsiJavaFile
    val javaClass = javaFile.classes.single()

    action.actionPerformed(createActionEvent(action, projectRule.project, javaClass))

    assertEquals(2, dialogs.size)
    verify(dialogs[1]).show()
    dialogs[1].close(DialogWrapper.CANCEL_EXIT_CODE)

    assertEquals(2, capturedSettings.size)
    val javaSettings = capturedSettings[1]
    assertEquals("Screenshot Tests in com.example.JavaScreenshotTest", javaSettings.name)
    val javaGradleConfig = javaSettings.configuration as GradleRunConfiguration
    assertEquals(true, javaGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(
        ":app:testDebugScreenshotTestDefaultTestSuite",
        "--tests",
        "\"com.example.JavaScreenshotTest\"",
      ),
      javaGradleConfig.settings.taskNames,
    )
  }

  @Test
  fun testActionPerformed_createsUpdateConfigurationForMethodAndTopLevelFunction() {
    val capturedSettings = installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(projectRule.project, createdDialogs)

    // 1. Member function inside a class -> ScreenshotTestMethodGradleConfigurationProducer
    val classFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/MemberMethodTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        class MemberMethodTest {
          @PreviewTest
          @Composable
          fun GreetingPreview() {}
        }
        """
          .trimIndent(),
      )
    val memberFunction = PsiTreeUtil.findChildOfType(classFile, KtNamedFunction::class.java)!!

    action.actionPerformed(createActionEvent(action, projectRule.project, memberFunction))
    dialogs.last().close(DialogWrapper.CANCEL_EXIT_CODE)

    val memberSettings = capturedSettings.single()
    assertEquals("Screenshot Tests in GreetingPreview", memberSettings.name)
    val memberGradleConfig = memberSettings.configuration as GradleRunConfiguration
    assertEquals(true, memberGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(
        ":app:testDebugScreenshotTestDefaultTestSuite",
        "--tests",
        "\"com.example.MemberMethodTest.GreetingPreview\"",
      ),
      memberGradleConfig.settings.taskNames,
    )

    // 2. Top-level function -> compiles to TopLevelScreenshotTestKt.TopLevelPreview
    val topLevelFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/TopLevelScreenshotTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        @Composable
        fun TopLevelPreview() {}
        """
          .trimIndent(),
      )
    val topLevelFunction = PsiTreeUtil.findChildOfType(topLevelFile, KtNamedFunction::class.java)!!

    action.actionPerformed(createActionEvent(action, projectRule.project, topLevelFunction))
    dialogs.last().close(DialogWrapper.CANCEL_EXIT_CODE)

    assertEquals(2, capturedSettings.size)
    val topLevelMethodSettings = capturedSettings[1]
    assertEquals("Screenshot Tests in TopLevelPreview", topLevelMethodSettings.name)
    val topLevelMethodGradleConfig = topLevelMethodSettings.configuration as GradleRunConfiguration
    assertEquals(true, topLevelMethodGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(
        ":app:testDebugScreenshotTestDefaultTestSuite",
        "--tests",
        "\"com.example.TopLevelScreenshotTestKt.TopLevelPreview\"",
      ),
      topLevelMethodGradleConfig.settings.taskNames,
    )

    // 3. KtFile with top-level function -> compiles to facade class TopLevelScreenshotTestKt
    action.actionPerformed(createActionEvent(action, projectRule.project, topLevelFile))
    dialogs.last().close(DialogWrapper.CANCEL_EXIT_CODE)

    assertEquals(3, capturedSettings.size)
    val topLevelFileSettings = capturedSettings[2]
    assertEquals("Screenshot Tests in TopLevelScreenshotTest.kt", topLevelFileSettings.name)
    val topLevelFileGradleConfig = topLevelFileSettings.configuration as GradleRunConfiguration
    assertEquals(true, topLevelFileGradleConfig.getUserData<Boolean>(IS_SCREENSHOT_UPDATE_CONFIGURATION))
    assertEquals(
      listOf(
        ":app:testDebugScreenshotTestDefaultTestSuite",
        "--tests",
        "\"com.example.TopLevelScreenshotTestKt\"",
      ),
      topLevelFileGradleConfig.settings.taskNames,
    )
  }

  @Test
  fun testActionPerformed_forwardsTestSuiteEventsToDialog() {
    val project = projectRule.project
    installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(project, createdDialogs)

    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/SuiteForwardingTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        class SuiteForwardingTest {
          @PreviewTest
          @Composable
          fun GreetingPreview() {}
        }
        """
          .trimIndent(),
      )
    val ktClass = PsiTreeUtil.findChildOfType(ktFile, KtClass::class.java)!!

    action.actionPerformed(createActionEvent(action, project, ktClass))
    val dialog = dialogs.single()

    val device = mock(AndroidDevice::class.java)
    val suite = mock(AndroidTestSuite::class.java)
    val testCase =
      AndroidTestCase(
        id = "case1",
        methodName = "GreetingPreview",
        className = "com.example.SuiteForwardingTest",
        packageName = "com.example",
        result = AndroidTestCaseResult.PASSED,
        additionalTestArtifacts =
          mutableMapOf(
            "PreviewScreenshot.methodName" to "GreetingPreview",
            "PreviewScreenshot.previewName" to "GreetingPreview_0",
          ),
      )

    val suitePublisher = project.messageBus.syncPublisher(AndroidTestSuiteView.ANDROID_TEST_SUITE_TOPIC)
    suitePublisher.onTestSuiteStarted(device, suite)
    suitePublisher.onTestCaseFinished(device, suite, testCase)
    suitePublisher.onTestSuiteFinished(device, suite)

    // UpdateScreenshotTestResultsListener checks all rendered previews unconditionally (isChecked = true),
    // so OK becomes enabled once onTestSuiteFinished is processed on the EDT.
    PlatformTestUtil.waitWithEventsDispatching(
      "Dialog OK action should be enabled after suite finishes with checked preview",
      { dialog.isOKActionEnabled },
      10,
    )

    val expectedDetails =
      PreviewDetails(
        testId = "com.example.SuiteForwardingTest.GreetingPreview.GreetingPreview_0",
        className = "com.example.SuiteForwardingTest",
        methodName = "GreetingPreview",
        previewName = "GreetingPreview_0",
        testResult = AndroidTestCaseResult.PASSED,
      )
    verify(dialog).onRenderingStarted()
    verify(dialog).updateDialogWithTestResult(expectedDetails, true)
    verify(dialog).onTestSuiteFinished()
  }

  @Test
  fun testActionPerformed_linksOnlyMatchingProcessHandlerAndDestroysOnCancel() {
    val project = projectRule.project
    val capturedSettings = installMockExecutionManager(projectRule)
    val dialogs = installSpyDialogFactory(project, createdDialogs)

    val ktFile =
      projectRule.fixture.addFileToProject(
        "app/src/screenshotTest/java/com/example/ProcessCancelTest.kt",
        """
        package com.example

        import androidx.compose.runtime.Composable
        import com.android.tools.screenshot.PreviewTest

        class ProcessCancelTest {
          @PreviewTest
          @Composable
          fun GreetingPreview() {}
        }
        """
          .trimIndent(),
      )
    val ktClass = PsiTreeUtil.findChildOfType(ktFile, KtClass::class.java)!!

    action.actionPerformed(createActionEvent(action, project, ktClass))

    val dialog = dialogs.single()
    val updateSettings = capturedSettings.single()
    val execPublisher = project.messageBus.syncPublisher(ExecutionManager.EXECUTION_TOPIC)

    // Unrelated ExecutionEnvironment must NOT be linked to the dialog
    val otherEnv = createMockExecutionEnvironment()
    val otherHandler = mock(ProcessHandler::class.java)
    execPublisher.processStarted(DefaultRunExecutor.EXECUTOR_ID, otherEnv, otherHandler)

    // Matching ExecutionEnvironment MUST be linked to the dialog
    val matchingEnv = createMockExecutionEnvironment(updateSettings)
    val matchingHandler = mock(ProcessHandler::class.java)
    execPublisher.processStarted(DefaultRunExecutor.EXECUTOR_ID, matchingEnv, matchingHandler)

    dialog.doCancelAction()
    verify(otherHandler, never()).destroyProcess()
    verify(matchingHandler).destroyProcess()
  }

  private fun isEnabledAndVisible(element: PsiElement): Boolean = updateAndGetEnabledAndVisible(action, projectRule.project, element)
}
