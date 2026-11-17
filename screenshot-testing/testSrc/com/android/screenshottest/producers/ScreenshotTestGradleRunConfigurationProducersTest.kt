/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.screenshottest.producers

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdeArtifactName
import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.model.IdeTestSuiteSource
import com.android.tools.idea.gradle.model.SCREENSHOT_TEST_ENGINE_ID
import com.android.tools.idea.gradle.model.impl.IdeJUnitEngineInfoImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteComponentCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteSourceImpl
import com.android.tools.idea.gradle.model.impl.toImpl
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntityId
import com.android.tools.idea.gradle.project.entities.GradleModuleModelEntity
import com.android.tools.idea.gradle.project.entities.gradleModuleModel
import com.android.tools.idea.gradle.project.entities.modifyGradleAndroidModelEntity
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.gradle.project.model.GradleModuleModel
import com.android.tools.idea.projectsystem.gradle.GradleSourceSetProjectPath
import com.android.tools.idea.projectsystem.gradle.getGradleProjectPath
import com.android.tools.idea.projectsystem.gradle.getTestSuiteSourceSetName
import com.android.tools.idea.testartifacts.TestConfigurationTestingUtil
import com.android.tools.idea.testartifacts.createAndroidGradleTestConfigurationFromClass
import com.android.tools.idea.testartifacts.createAndroidGradleTestConfigurationFromDirectory
import com.android.tools.idea.testartifacts.createAndroidGradleTestConfigurationFromMethod
import com.android.tools.idea.testartifacts.testsuite.GradleRunConfigurationExtension.BooleanOptions.SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.onEdt
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationFromContextImpl
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil.findFileByIoFile
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.RunsInEdt
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class ScreenshotTestGradleRunConfigurationProducersTest {
  @get:Rule val flagRule = FlagRule(StudioFlags.ENABLE_SCREENSHOT_TESTING, true)

  @get:Rule val testSuitesFlagRule = FlagRule(StudioFlags.AGP_TEST_SUITES_ENABLED, true)

  @get:Rule val projectRule = AndroidGradleProjectRule().onEdt()

  private val SIMPLE_SCREENSHOT = "app/src/screenshotTest/java/com/example/application/MyScreenshotTest.kt"
  private val NO_PREVIEW_TEST = "app/src/screenshotTest/java/com/example/application/NoPreviewTest.kt"
  private val ONLY_PREVIEW_TEST = "app/src/screenshotTest/java/com/example/application/OnlyPreviewTest.kt"
  private val MULTI_PREVIEW = "app/src/screenshotTest/java/com/example/application/MyScreenshotTestMultiPreview.kt"
  private val DIFFERENT_PACKAGE = "app/src/screenshotTest/java/com/example/package/MyScreenshotTest.kt"
  private val EMPTY_CLASS = "app/src/screenshotTest/java/com/example/application/MyEmptyClass.kt"
  private val NO_PREVIEWS = "app/src/screenshotTest/java/com/example/application/NoPreviewsClass.kt"
  private val TOP_LEVEL = "app/src/screenshotTest/java/com/example/application/MyScreenshotTestTopLevel.kt"
  private val MIXED_TESTS_FILE = "app/src/screenshotTest/java/com/example/application/MyFileWithMixedTests.kt"

  @Before
  fun setUp() {
    projectRule.loadProject(TestProjectPaths.SIMPLE_APP_WITH_SCREENSHOT_TEST)
    stubComposeAnnotation()
    stubPreviewAnnotation()
    stubPreviewTestAnnotation()
    createProjectStructureForTest()
    val screenshotTestDir = findFileByIoFile(File(projectRule.project.basePath + "/app/src/screenshotTest"), true)
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotTestDir!!, true)
    // Without the legacy screenshot plugin, screenshot tests can only run through screenshot test suites, so declare a screenshot test
    // suite named `screenshotTest` for the `screenshotTest` sources, which runs with `test<Variant>ScreenshotTestDefaultTestSuite`.
    setScreenshotTestSuites(listOf(createScreenshotTestSuite()))
  }

  @Test
  fun testConfigurationFromClass() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.MyScreenshotTest",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.MyScreenshotTest\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromFile_includesClassAndTopLevelTests() {
    val project = projectRule.project
    // Get the PsiFile for the test file.
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, MIXED_TESTS_FILE, false)
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val runConfiguration = context.configuration?.configuration as? GradleRunConfiguration

    requireNotNull(runConfiguration) { "Run configuration should not be null for file context" }

    assertEquals("Screenshot Tests in MyFileWithMixedTests.kt", runConfiguration.name)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)

    val taskNames = runConfiguration.settings.taskNames
    assertThat(taskNames).contains(":app:testDebugScreenshotTestDefaultTestSuite")

    // Check that we have the correct number of task arguments.
    // 1 for the task, plus 2 for each "--tests" filter (2 filters total) = 5.
    assertThat(taskNames).hasSize(5)

    // Drop the base task and chunk the remaining arguments into pairs like [--tests, "filter"].
    val testFilters = taskNames.drop(1).chunked(2).filter { it.size == 2 && it[0] == "--tests" }.map { it[1] }

    // Verify that we have exactly the two expected filters.
    val expectedFilters =
      listOf(
        "\"com.example.application.MyClassInMixedFile\"",
        "\"com.example.application.MyFileWithMixedTestsKt\"",
      )
    assertThat(testFilters).hasSize(expectedFilters.size)
    assertThat(testFilters).containsAllIn(expectedFilters)
  }

  @Test
  fun testConfigurationFromEditor_fallbackToFile() {
    val project = projectRule.project
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, MIXED_TESTS_FILE, false)
    val elementInFile = psiFile.findElementAt(0)
    requireNotNull(elementInFile) { "Element at offset 0 should not be null" }

    val context = TestConfigurationTestingUtil.createContext(project, elementInFile)
    val runConfiguration = context.configuration?.configuration as? GradleRunConfiguration

    requireNotNull(runConfiguration) { "Run configuration should not be null for element in file" }

    assertEquals("Screenshot Tests in MyFileWithMixedTests.kt", runConfiguration.name)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)

    val taskNames = runConfiguration.settings.taskNames
    assertThat(taskNames).contains(":app:testDebugScreenshotTestDefaultTestSuite")
    assertThat(taskNames).hasSize(5)

    val testFilters = taskNames.drop(1).chunked(2).filter { it.size == 2 && it[0] == "--tests" }.map { it[1] }
    val expectedFilters =
      listOf(
        "\"com.example.application.MyClassInMixedFile\"",
        "\"com.example.application.MyFileWithMixedTestsKt\"",
      )
    assertThat(testFilters).hasSize(expectedFilters.size)
    assertThat(testFilters).containsAllIn(expectedFilters)
  }

  @Test
  fun testConfigurationFromClassNoPreviewTest() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.NoPreviewTest",
      )
    Assert.assertNull(runConfiguration)
  }

  @Test
  fun testConfigurationFromClassOnlyPreviewTest() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.OnlyPreviewTest",
      )
    Assert.assertNotNull(runConfiguration)
    assertEquals(true, runConfiguration!!.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.OnlyPreviewTest\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromClassEmptyClass() {
    val project = projectRule.project
    val runConfiguration = createAndroidGradleTestConfigurationFromClass(project, "com.example.application.MyEmptyClass")
    Assert.assertNull(runConfiguration)
  }

  @Test
  fun testConfigurationFromClassNoPreviewMethods() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.NoPreviewsClass",
      )
    Assert.assertNull(runConfiguration)
  }

  @Test
  fun testConfigurationFromMethod() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromMethod(
        project,
        "com.example.application.MyScreenshotTest",
        "PreviewMethod",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.MyScreenshotTest.PreviewMethod\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromMethodNoPreviewTest() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromMethod(
        project,
        "com.example.application.NoPreviewTest",
        "PreviewMethod",
      )
    Assert.assertNull(runConfiguration)
  }

  @Test
  fun testConfigurationFromMethodOnlyPreviewTest() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromMethod(
        project,
        "com.example.application.OnlyPreviewTest",
        "PreviewMethod",
      )
    Assert.assertNotNull(runConfiguration)
    assertEquals(true, runConfiguration!!.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.OnlyPreviewTest.PreviewMethod\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromMethodMultiPreview() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromMethod(
        project,
        "com.example.application.MyScreenshotTestMultiPreview",
        "PreviewMethod",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.MyScreenshotTestMultiPreview.PreviewMethod\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromMethodTopLevel() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromMethod(
        project,
        "com.example.application.MyScreenshotTestTopLevelKt",
        "PreviewMethod",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals(
      "\"com.example.application.MyScreenshotTestTopLevelKt.PreviewMethod\"",
      runConfiguration.settings.taskNames[2],
    )
  }

  @Test
  fun testConfigurationFromPackage() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromDirectory(
        project,
        "app/src/screenshotTest/java/com/example",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals("\"com.example.*\"", runConfiguration.settings.taskNames[2])
  }

  @Test
  fun testConfigurationProducerFromPackage() {
    val project = projectRule.project
    val psiFile =
      TestConfigurationTestingUtil.getPsiElement(
        project,
        "app/src/screenshotTest/java/com/example",
        true,
      )
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration = context.configurationsFromContext?.firstOrNull() as ConfigurationFromContextImpl
    assertThat(contextConfiguration.configurationProducer).isInstanceOf(ScreenshotTestAllInPackageGradleConfigurationProducer::class.java)
  }

  @Test
  fun testConfigurationFromSubPackage() {
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromDirectory(
        project,
        "app/src/screenshotTest/java/com/example/package",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(3, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertEquals("--tests", runConfiguration.settings.taskNames[1])
    assertEquals("\"com.example.package.*\"", runConfiguration.settings.taskNames[2])
  }

  @Test
  fun testConfigurationProducerFromSubPackage() {
    val project = projectRule.project
    val psiFile =
      TestConfigurationTestingUtil.getPsiElement(
        project,
        "app/src/screenshotTest/java/com/example/package",
        true,
      )
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration = context.configurationsFromContext?.firstOrNull() as ConfigurationFromContextImpl
    assertThat(contextConfiguration.configurationProducer).isInstanceOf(ScreenshotTestAllInPackageGradleConfigurationProducer::class.java)
  }

  @Test
  fun testConfigurationFromAppDirectory() {
    val project = projectRule.project
    // test app directory
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "app", true)
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration =
      context.configurationsFromContext?.firstOrNull {
        (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
      } as ConfigurationFromContextImpl?
    val runConfiguration = contextConfiguration!!.configuration as GradleRunConfiguration
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(1, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertThat(contextConfiguration.configurationProducer).isInstanceOf(ScreenshotTestAllInDirectoryGradleConfigurationProducer::class.java)
  }

  @Test
  fun testConfigurationFromSrcDirectory() {
    val project = projectRule.project
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "app/src", true)
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration =
      context.configurationsFromContext?.firstOrNull {
        (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
      } as ConfigurationFromContextImpl?
    val runConfiguration = contextConfiguration!!.configuration as GradleRunConfiguration
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(1, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertThat(contextConfiguration.configurationProducer).isInstanceOf(ScreenshotTestAllInDirectoryGradleConfigurationProducer::class.java)

    // Compare generated run-config against unrelated context. It should return false.
    // This should not cause NPE.
    assertThat(
        contextConfiguration.configurationProducer.isConfigurationFromContext(
          runConfiguration,
          TestConfigurationTestingUtil.createContext(
            project,
            TestConfigurationTestingUtil.getPsiElement(project, "nonAndroidModule", true),
          ),
        )
      )
      .isFalse()
  }

  @Test
  fun testConfigurationFromSSTSourceSetDirectory() {
    val project = projectRule.project
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "app/src/screenshotTest/java", true)
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration =
      context.configurationsFromContext?.firstOrNull {
        (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
      } as ConfigurationFromContextImpl?
    val runConfiguration = contextConfiguration!!.configuration as GradleRunConfiguration
    requireNotNull(runConfiguration)
    assertEquals(
      true,
      runConfiguration.getUserData<Boolean>(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey),
    )
    assertEquals(true, runConfiguration.isRunAsTest)
    assertEquals(1, runConfiguration.settings.taskNames.size)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
    assertThat(contextConfiguration.configurationProducer).isInstanceOf(ScreenshotTestAllInDirectoryGradleConfigurationProducer::class.java)
  }

  @Test
  fun testConfigurationFromOtherSourceSetDirectory() {
    val project = projectRule.project
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "app/src/main/java", true)
    val context = TestConfigurationTestingUtil.createContext(project, psiFile)
    val contextConfiguration =
      context.configurationsFromContext?.firstOrNull {
        (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
      } as ConfigurationFromContextImpl?
    Assert.assertNull(contextConfiguration)
  }

  @Test
  fun runConfigProducerShouldNotCrashForNonAndroidModule() {
    val project = projectRule.project
    val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "nonAndroidModule", true)

    // This should not cause NPE.
    TestConfigurationTestingUtil.createContext(project, psiFile).configuration
  }

  @Test
  fun isConfigurationFromContextReturnsFalseWhenGradleDataIsMissing() {
    val project = projectRule.project
    val methodElement =
      with(TestConfigurationTestingUtil) {
        project.getPsiElement(TestConfigurationTestingUtil.Method("com.example.application.MyScreenshotTest", "PreviewMethod"))
      }
    val packageElement = TestConfigurationTestingUtil.getPsiElement(project, "app/src/screenshotTest/java/com/example", true)
    val classElement =
      with(TestConfigurationTestingUtil) {
        project.getPsiElement(TestConfigurationTestingUtil.Class("com.example.application.MyScreenshotTest"))
      }
    val directoryElement = TestConfigurationTestingUtil.getPsiElement(project, "app/src", true)

    val methodConfigFromContext =
      TestConfigurationTestingUtil.createContext(project, methodElement).configurationsFromContext!!.first() as ConfigurationFromContextImpl
    val packageConfigFromContext =
      TestConfigurationTestingUtil.createContext(project, packageElement).configurationsFromContext!!.first()
        as ConfigurationFromContextImpl
    val classConfigFromContext =
      TestConfigurationTestingUtil.createContext(project, classElement).configurationsFromContext!!.first() as ConfigurationFromContextImpl
    val directoryConfigFromContext =
      TestConfigurationTestingUtil.createContext(project, directoryElement).configurationsFromContext!!.first {
        it.configuration.name.contains("Screenshot")
      } as ConfigurationFromContextImpl

    assertThat(methodConfigFromContext.configurationProducer).isInstanceOf(ScreenshotTestMethodGradleConfigurationProducer::class.java)
    assertThat(packageConfigFromContext.configurationProducer)
      .isInstanceOf(ScreenshotTestAllInPackageGradleConfigurationProducer::class.java)
    assertThat(classConfigFromContext.configurationProducer).isInstanceOf(ScreenshotTestClassGradleConfigurationProducer::class.java)
    assertThat(directoryConfigFromContext.configurationProducer)
      .isInstanceOf(ScreenshotTestAllInDirectoryGradleConfigurationProducer::class.java)

    // Drop ExternalSystem Gradle project data while GradleAndroidModel remains in WorkspaceModel,
    // so getScreenshotTestTaskNames() returns null when doIsConfigurationFromContext runs.
    ExternalProjectsManagerImpl.getInstance(project).forgetExternalProjectData(GradleConstants.SYSTEM_ID, project.basePath!!)

    assertThat(
        methodConfigFromContext.configurationProducer.isConfigurationFromContext(
          methodConfigFromContext.configuration as GradleRunConfiguration,
          TestConfigurationTestingUtil.createContext(project, methodElement),
        )
      )
      .isFalse()
    assertThat(
        packageConfigFromContext.configurationProducer.isConfigurationFromContext(
          packageConfigFromContext.configuration as GradleRunConfiguration,
          TestConfigurationTestingUtil.createContext(project, packageElement),
        )
      )
      .isFalse()
    val classConfiguration = classConfigFromContext.configuration as GradleRunConfiguration
    classConfiguration.settings.taskNames = emptyList()
    assertThat(
        classConfigFromContext.configurationProducer.isConfigurationFromContext(
          classConfiguration,
          TestConfigurationTestingUtil.createContext(project, classElement),
        )
      )
      .isFalse()
    assertThat(
        directoryConfigFromContext.configurationProducer.isConfigurationFromContext(
          directoryConfigFromContext.configuration as GradleRunConfiguration,
          TestConfigurationTestingUtil.createContext(project, directoryElement),
        )
      )
      .isFalse()
  }

  private fun createProjectStructureForTest() {
    // simple screenshotTest
    createRelativeFileWithContent(
      SIMPLE_SCREENSHOT,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class MyScreenshotTest {
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun PreviewMethod() {
        }
        
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      """
        .trimIndent(),
    )

    // without preview test annotation
    createRelativeFileWithContent(
      NO_PREVIEW_TEST,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class NoPreviewTest {
        @Preview(showBackground = true)
        @Composable
        fun PreviewMethod() {
        }
        
        @Preview(showBackground = true)
        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      """
        .trimIndent(),
    )

    // only preview test annotation - no preview annotation
    createRelativeFileWithContent(
      ONLY_PREVIEW_TEST,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class OnlyPreviewTest {
        @PreviewTest
        @Composable
        fun PreviewMethod() {
        }
        
        @PreviewTest
        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      """
        .trimIndent(),
    )

    // Multi preview annotations
    createRelativeFileWithContent(
      MULTI_PREVIEW,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class MyScreenshotTestMultiPreview {
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun PreviewMethod() {
        }
        
        @PreviewTest
        @MultiPreview
        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      @Preview(name = "with background", showBackground = true)
      @Preview(name = "without background", showBackground = false)
      annotation class MultiPreview

      """
        .trimIndent(),
    )

    // file in a different package
    createRelativeFileWithContent(
      DIFFERENT_PACKAGE,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      class MyScreenshotTest {
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun PreviewMethod() {
        }
        
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      """
        .trimIndent(),
    )

    // class with no methods
    createRelativeFileWithContent(
      EMPTY_CLASS,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      class MyEmptyClass {
      }

      """
        .trimIndent(),
    )

    // class with no preview methods
    createRelativeFileWithContent(
      NO_PREVIEWS,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      class NoPreviewsClass {
        @Composable
        fun PreviewMethod() {
        }

        @Composable
        fun AnotherPreviewMethod() {
        }
      }

      """
        .trimIndent(),
    )

    // top level function
    createRelativeFileWithContent(
      TOP_LEVEL,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      @PreviewTest
      @Preview(showBackground = true)
      @Composable
      fun PreviewMethod() {
      }

      """
        .trimIndent(),
    )

    // File with both a top-level test and a test in a class
    createRelativeFileWithContent(
      MIXED_TESTS_FILE,
      """
      package com.example.application

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import com.android.tools.screenshot.PreviewTest

      @PreviewTest
      @Preview
      @Composable
      fun TopLevelTestInMixedFile() {
      }

      class MyClassInMixedFile {
        @PreviewTest
        @Preview
        @Composable
        fun ClassTestInMixedFile() {
        }
      }
      """
        .trimIndent(),
    )
  }

  private fun stubPreviewTestAnnotation() {
    createRelativeFileWithContent(
      "app/src/screenshotTest/java/com/android/tools/screenshot/PreviewTest.kt",
      """
      package com.android.tools.screenshot

      @MustBeDocumented
      @Retention(AnnotationRetention.BINARY)
      @Target(
          AnnotationTarget.FUNCTION
      )
      annotation class PreviewTest {
      }
          
      """
        .trimIndent(),
    )
  }

  private fun stubComposeAnnotation() {
    createRelativeFileWithContent(
      "app/src/screenshotTest/java/androidx/compose/runtime/Composable.kt",
      """
      package androidx.compose.runtime
      @Target(
          AnnotationTarget.FUNCTION,
          AnnotationTarget.TYPE_USAGE,
          AnnotationTarget.TYPE,
          AnnotationTarget.TYPE_PARAMETER,
          AnnotationTarget.PROPERTY_GETTER
      )
      annotation class Composable
      """
        .trimIndent(),
    )
  }

  private fun stubPreviewAnnotation() {
    createRelativeFileWithContent(
      "app/src/screenshotTest/java/androidx/compose/ui/tooling/preview/Preview.kt",
      """
  package androidx.compose.ui.tooling.preview

  import kotlin.reflect.KClass

  object Devices {
      const val DEFAULT = ""

      const val NEXUS_7 = "id:Nexus 7"
      const val NEXUS_10 = "name:Nexus 10"
  }


  @Repeatable
  annotation class Preview(
    val name: String = "",
    val group: String = "",
    val apiLevel: Int = -1,
    val theme: String = "",
    val widthDp: Int = -1,
    val heightDp: Int = -1,
    val locale: String = "",
    val fontScale: Float = 1f,
    val showDecoration: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val device: String = ""
  )

  interface PreviewParameterProvider<T> {
      val values: Sequence<T>
      val count get() = values.count()
  }

  annotation class PreviewParameter(
      val provider: KClass<out PreviewParameterProvider<*>>,
      val limit: Int = Int.MAX_VALUE
  )
  """,
    )
  }

  private fun createRelativeFileWithContent(relativePath: String, content: String): File {
    val newFile = File(projectRule.project.basePath, FileUtils.toSystemDependentPath(relativePath))
    FileUtil.createIfDoesntExist(newFile)
    newFile.writeText(content)
    return newFile
  }

  private fun setModuleTasks(tasks: List<String>, hasComposeScreenshotPlugin: Boolean = false) {
    val project = projectRule.project
    val modules = ModuleManager.getInstance(project).modules
    runWriteAction {
      project.workspaceModel.updateProjectModel("Set GradleModuleModel") { storage ->
        for (module in modules) {
          val entity = storage.resolve(ModuleId(module.name)) ?: continue
          val model =
            GradleModuleModel(
              moduleNameField = module.name,
              testTasks = emptyList(),
              allTasks = tasks,
              gradlePath = if (module.name.endsWith(".lib")) ":lib" else ":app",
              rootFolderPath = File(project.basePath!!).toImpl(),
              buildFilePath = File(project.basePath!!, "build.gradle").toImpl(),
              gradleVersion = "8.0",
              agpVersion = "8.0.0",
              safeArgsJava = false,
              safeArgsKotlin = false,
              hasFtlPlugin = false,
              hasLegacyKaptPlugin = false,
              hasComposeScreenshotPlugin = hasComposeScreenshotPlugin,
            )
          entity.gradleModuleModel?.let { storage.removeEntity(it) }
          storage.modifyModuleEntity(entity) {
            this.gradleModuleModel = GradleModuleModelEntity(model, entity.entitySource)
          }
        }
      }
    }
  }

  @Test
  fun testLegacyPluginTaskResolution() {
    setModuleTasks(listOf("validateDebugScreenshotTest"), hasComposeScreenshotPlugin = true)
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.MyScreenshotTest",
      )
    requireNotNull(runConfiguration)
    assertEquals(":app:validateDebugScreenshotTest", runConfiguration.settings.taskNames[0])
  }

  @Test
  fun testLegacyPluginTaskResolutionWithoutScreenshotTestSuites() {
    setModuleTasks(listOf("validateDebugScreenshotTest"), hasComposeScreenshotPlugin = true)
    setScreenshotTestSuites(emptyList())
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.MyScreenshotTest",
      )
    requireNotNull(runConfiguration)
    assertEquals(":app:validateDebugScreenshotTest", runConfiguration.settings.taskNames[0])
  }

  @Test
  fun testNoConfigurationWithoutLegacyPluginAndScreenshotTestSuites() {
    setModuleTasks(emptyList(), hasComposeScreenshotPlugin = false)
    setScreenshotTestSuites(emptyList())
    val project = projectRule.project

    Assert.assertNull(createAndroidGradleTestConfigurationFromClass(project, "com.example.application.MyScreenshotTest"))
    Assert.assertNull(createAndroidGradleTestConfigurationFromMethod(project, "com.example.application.MyScreenshotTest", "PreviewMethod"))
    for (directory in listOf("app", "app/src", "app/src/screenshotTest/java", "app/src/screenshotTest/java/com/example")) {
      val psiElement = TestConfigurationTestingUtil.getPsiElement(project, directory, true)
      val context = TestConfigurationTestingUtil.createContext(project, psiElement)
      // Other producers, e.g. the Gradle test producers, can still create configurations, so only look for screenshot test ones.
      val configurations = context.configurationsFromContext.orEmpty()
      Assert.assertNull(
        configurations.firstOrNull {
          (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
        }
      )
    }
  }

  @Test
  fun testTestSuiteTaskResolution() {
    setModuleTasks(
      listOf("testDebugScreenshotTestDefaultTestSuite"),
      hasComposeScreenshotPlugin = false,
    )
    val project = projectRule.project
    val runConfiguration =
      createAndroidGradleTestConfigurationFromClass(
        project,
        "com.example.application.MyScreenshotTest",
      )
    requireNotNull(runConfiguration)
    assertEquals(
      ":app:testDebugScreenshotTestDefaultTestSuite",
      runConfiguration.settings.taskNames[0],
    )
  }

  private fun createHostJarTestSuiteSource(testSuitePath: File, name: String = "hostJar"): IdeTestSuiteSourceImpl {
    return IdeTestSuiteSourceImpl(
      name = name,
      type = IdeTestSuiteSource.SourceType.HOST_JAR,
      sourceProvider =
        IdeSourceProvider(
          name = name,
          folder = testSuitePath,
          manifestFile = "AndroidManifest.xml",
          javaDirectories = listOf(testSuitePath.absolutePath),
          kotlinDirectories = listOf(testSuitePath.absolutePath),
          resourcesDirectories = emptyList(),
          aidlDirectories = emptyList(),
          renderscriptDirectories = emptyList(),
          resDirectories = emptyList(),
          assetsDirectories = emptyList(),
          jniLibsDirectories = emptyList(),
          shadersDirectories = emptyList(),
          mlModelsDirectories = emptyList(),
          customSourceDirectories = emptyList(),
          baselineProfileDirectories = emptyList(),
          keepRulesDirectoriesField = emptyList(),
          aarKeepRulesDirectoriesField = emptyList(),
        ),
    )
  }

  /** Returns a screenshot test suite named `screenshotTest` whose only source is in `app/src/screenshotTest`. */
  private fun createScreenshotTestSuite(): IdeTestSuiteImpl =
    IdeTestSuiteImpl(
      name = "screenshotTest",
      sources = listOf(createHostJarTestSuiteSource(File(projectRule.project.basePath!!, "app/src/screenshotTest"))),
      junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
      targetedVariants = listOf("debug"),
    )

  /**
   * Sets the test suites of the Android models of all modules to [suites], and returns their previous models so that they can be restored.
   */
  private fun setScreenshotTestSuites(
    suites: List<IdeTestSuiteImpl>,
    removeLegacyScreenshotArtifact: Boolean = false,
    testSuiteComponents: List<IdeTestSuiteComponentCoreImpl>? = null,
  ): Map<ModuleId, GradleAndroidModelImpl> {
    val project = projectRule.project
    val modules = ModuleManager.getInstance(project).modules
    val originalModels = mutableMapOf<ModuleId, GradleAndroidModelImpl>()
    runWriteAction {
      project.workspaceModel.updateProjectModel("Set custom testSuites") { storage ->
        for (module in modules) {
          val moduleId = ModuleId(module.name)
          val entity = storage.resolve(GradleAndroidModelEntityId(moduleId)) ?: continue
          val originalModel = entity.gradleAndroidModel
          originalModels[moduleId] = originalModel
          val newAndroidProject = originalModel.data.androidProject.copy(testSuites = suites)
          val newVariants =
            originalModel.data.variants.map { variant ->
              variant.copy(
                hostTestArtifacts =
                  if (removeLegacyScreenshotArtifact) {
                    variant.hostTestArtifacts.filter { it.name != IdeArtifactName.SCREENSHOT_TEST }
                  } else {
                    variant.hostTestArtifacts
                  },
                testSuiteComponents = testSuiteComponents ?: variant.testSuiteComponents,
              )
            }
          val newData = originalModel.data.copy(androidProject = newAndroidProject, variants = newVariants)
          val customModel = GradleAndroidModelImpl(newData)
          storage.modifyGradleAndroidModelEntity(entity) { gradleAndroidModel = customModel }
        }
      }
    }
    return originalModels
  }

  private fun withCustomScreenshotTestSuites(
    suites: List<IdeTestSuiteImpl>,
    removeLegacyScreenshotArtifact: Boolean = false,
    testSuiteComponents: List<IdeTestSuiteComponentCoreImpl>? = null,
    action: () -> Unit,
  ) {
    val project = projectRule.project
    var originalModels = emptyMap<ModuleId, GradleAndroidModelImpl>()
    try {
      originalModels = setScreenshotTestSuites(suites, removeLegacyScreenshotArtifact, testSuiteComponents)
      action()
    } finally {
      runWriteAction {
        project.workspaceModel.updateProjectModel("Restore testSuites") { storage ->
          for ((moduleId, originalModel) in originalModels) {
            val entity = storage.resolve(GradleAndroidModelEntityId(moduleId)) ?: continue
            storage.modifyGradleAndroidModelEntity(entity) { gradleAndroidModel = originalModel }
          }
        }
      }
    }
  }

  @Test
  fun testConfigurationFromCustomTestSuite() {
    setModuleTasks(
      listOf("testDebugCustomScreenshotSuiteDefaultTestSuite"),
      hasComposeScreenshotPlugin = false,
    )
    val project = projectRule.project
    val customSuite =
      IdeTestSuiteImpl(
        name = "customScreenshotSuite",
        sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/screenshotTest"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
        targetedVariants = listOf("debug"),
      )
    withCustomScreenshotTestSuites(listOf(customSuite)) {
      val runConfiguration =
        createAndroidGradleTestConfigurationFromClass(
          project,
          "com.example.application.MyScreenshotTest",
        )
      requireNotNull(runConfiguration)
      assertEquals(
        ":app:testDebugCustomScreenshotSuiteDefaultTestSuite",
        runConfiguration.settings.taskNames[0],
      )
    }
  }

  @Test
  fun testConfigurationFromTestSuiteWithoutLegacyScreenshotArtifact() {
    setModuleTasks(
      listOf("testDebugCustomScreenshotSuiteDefaultTestSuite"),
      hasComposeScreenshotPlugin = false,
    )
    val project = projectRule.project
    val customSuite =
      IdeTestSuiteImpl(
        name = "customScreenshotSuite",
        sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/screenshotTest"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
        targetedVariants = listOf("debug"),
      )
    withCustomScreenshotTestSuites(listOf(customSuite), removeLegacyScreenshotArtifact = true) {
      val runConfiguration =
        createAndroidGradleTestConfigurationFromClass(
          project,
          "com.example.application.MyScreenshotTest",
        )
      requireNotNull(runConfiguration)
      assertEquals(
        ":app:testDebugCustomScreenshotSuiteDefaultTestSuite",
        runConfiguration.settings.taskNames[0],
      )
    }
  }

  @Test
  fun testConfigurationFromMultiSuiteModuleDirectory() {
    setModuleTasks(
      listOf(
        "testDebugUiScreenshotSuiteDefaultTestSuite",
        "testDebugFeatureScreenshotSuiteDefaultTestSuite",
      ),
      hasComposeScreenshotPlugin = false,
    )
    val project = projectRule.project
    val uiSuite =
      IdeTestSuiteImpl(
        name = "uiScreenshotSuite",
        sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/uiScreenshotTest"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
        targetedVariants = listOf("debug"),
      )
    val featureSuite =
      IdeTestSuiteImpl(
        name = "featureScreenshotSuite",
        sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/featureScreenshotTest"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
        targetedVariants = listOf("debug"),
      )
    // Not in the `app` directory, so it only runs if all screenshot test suites run because none matches the directory.
    val otherSuite =
      IdeTestSuiteImpl(
        name = "otherScreenshotSuite",
        sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "other/src/otherScreenshotSuite"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
        targetedVariants = listOf("debug"),
      )
    withCustomScreenshotTestSuites(listOf(uiSuite, featureSuite, otherSuite), removeLegacyScreenshotArtifact = true) {
      val psiFile = TestConfigurationTestingUtil.getPsiElement(project, "app", true)
      val context = TestConfigurationTestingUtil.createContext(project, psiFile)
      val contextConfiguration =
        context.configurationsFromContext?.firstOrNull {
          (it.configuration as? GradleRunConfiguration)?.getUserData<Boolean>(IS_SCREENSHOT_TEST_CONFIGURATION) == true
        } as ConfigurationFromContextImpl?
      val runConfiguration = contextConfiguration!!.configuration as GradleRunConfiguration
      requireNotNull(runConfiguration)
      assertEquals(
        listOf(
          ":app:testDebugUiScreenshotSuiteDefaultTestSuite",
          ":app:testDebugFeatureScreenshotSuiteDefaultTestSuite",
        ),
        runConfiguration.settings.taskNames,
      )
    }
  }

  @Test
  fun testFileInTestSuiteSourceSetModuleIsScreenshotTestSourceOnlyForScreenshotTestSuite() {
    val project = projectRule.project
    val sourceSetModule =
      ModuleManager.getInstance(project).modules.first { module ->
        val path = module.getGradleProjectPath() as? GradleSourceSetProjectPath ?: return@first false
        path.path == ":app" && path.sourceSet.sourceSetName != "main"
      }
    val sourceSetName = (sourceSetModule.getGradleProjectPath() as GradleSourceSetProjectPath).sourceSet.sourceSetName
    val facet = requireNotNull(AndroidFacet.getInstance(sourceSetModule))

    // A generated source of the test suite module, outside of the static source directories of its test suite, so it is only matched to
    // its test suite through the module.
    val testFile =
      createRelativeFileWithContent(
        "app/build/generated/ksp/moduleSuite/kotlin/com/example/modulesuite/ModuleSuiteTest.kt",
        """
        package com.example.modulesuite

        class ModuleSuiteTest
        """
          .trimIndent(),
      )
    val sourceRoot = findFileByIoFile(File(project.basePath!!, "app/build/generated/ksp/moduleSuite/kotlin"), true)!!
    PsiTestUtil.addSourceRoot(sourceSetModule, sourceRoot, true)
    try {
      val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(findFileByIoFile(testFile, true)!!))
      fun moduleSuite(engineId: String) =
        IdeTestSuiteImpl(
          name = "moduleSuite",
          sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/moduleSuite"), name = sourceSetName)),
          junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(engineId)),
          targetedVariants = listOf("debug"),
        )
      val screenshotSuite =
        IdeTestSuiteImpl(
          name = "uiScreenshotSuite",
          sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/uiScreenshotSuite"), name = "uiScreenshotSuite")),
          junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
          targetedVariants = listOf("debug"),
        )
      val components =
        listOf(
          IdeTestSuiteComponentCoreImpl(suiteName = "moduleSuite", targets = emptyList()),
          IdeTestSuiteComponentCoreImpl(suiteName = "uiScreenshotSuite", targets = emptyList()),
        )

      withCustomScreenshotTestSuites(
        suites = listOf(moduleSuite(engineId = "junit-jupiter"), screenshotSuite),
        removeLegacyScreenshotArtifact = true,
        testSuiteComponents = components,
      ) {
        assertEquals(sourceSetName, sourceSetModule.getTestSuiteSourceSetName())
        assertFalse(isScreenshotTestSourceSet(PsiLocation<PsiElement>(psiFile), facet))
      }
      withCustomScreenshotTestSuites(
        suites = listOf(moduleSuite(engineId = SCREENSHOT_TEST_ENGINE_ID), screenshotSuite),
        removeLegacyScreenshotArtifact = true,
        testSuiteComponents = components,
      ) {
        assertTrue(isScreenshotTestSourceSet(PsiLocation<PsiElement>(psiFile), facet))
      }
    } finally {
      PsiTestUtil.removeSourceRoot(sourceSetModule, sourceRoot)
      FileUtil.delete(File(project.basePath!!, "app/build/generated/ksp/moduleSuite"))
    }
  }

  @Test
  fun testConfigurationFromGeneratedSourceInTestSuiteSourceSetModule() {
    setModuleTasks(
      listOf(
        "testDebugUiScreenshotSuiteDefaultTestSuite",
        "testDebugFeatureScreenshotSuiteDefaultTestSuite",
      ),
      hasComposeScreenshotPlugin = false,
    )
    val project = projectRule.project
    val sourceSetModule =
      ModuleManager.getInstance(project).modules.first { module ->
        val path = module.getGradleProjectPath() as? GradleSourceSetProjectPath ?: return@first false
        path.path == ":app" && path.sourceSet.sourceSetName != "main"
      }
    val sourceSetName = (sourceSetModule.getGradleProjectPath() as GradleSourceSetProjectPath).sourceSet.sourceSetName

    createRelativeFileWithContent(
      "app/build/generated/ksp/uiScreenshotSuite/kotlin/com/android/tools/screenshot/GeneratedPreviewTestStub.kt",
      """
      package com.android.tools.screenshot

      annotation class PreviewTest
      """
        .trimIndent(),
    )
    createRelativeFileWithContent(
      "app/build/generated/ksp/uiScreenshotSuite/kotlin/com/example/generated/GeneratedScreenshotTest.kt",
      """
      package com.example.generated

      import com.android.tools.screenshot.PreviewTest

      class GeneratedScreenshotTest {
        @PreviewTest
        fun GeneratedPreviewMethod() {}
      }
      """
        .trimIndent(),
    )
    val generatedRoot = findFileByIoFile(File(project.basePath!!, "app/build/generated/ksp/uiScreenshotSuite/kotlin"), true)!!
    PsiTestUtil.addSourceRoot(sourceSetModule, generatedRoot, true)
    try {
      // Static suite source directories intentionally do NOT include `app/build/generated/...`.
      val uiSuite =
        IdeTestSuiteImpl(
          name = "uiScreenshotSuite",
          sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/uiScreenshotSuite"), name = sourceSetName)),
          junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
          targetedVariants = listOf("debug"),
        )
      val featureSuite =
        IdeTestSuiteImpl(
          name = "featureScreenshotSuite",
          sources = listOf(createHostJarTestSuiteSource(File(project.basePath!!, "app/src/featureScreenshotSuite"), name = "featureSuite")),
          junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
          targetedVariants = listOf("debug"),
        )
      val components =
        listOf(
          IdeTestSuiteComponentCoreImpl(suiteName = "uiScreenshotSuite", targets = emptyList()),
          IdeTestSuiteComponentCoreImpl(suiteName = "featureScreenshotSuite", targets = emptyList()),
        )
      withCustomScreenshotTestSuites(
        suites = listOf(uiSuite, featureSuite),
        removeLegacyScreenshotArtifact = true,
        testSuiteComponents = components,
      ) {
        assertEquals(sourceSetName, sourceSetModule.getTestSuiteSourceSetName())
        val runConfiguration =
          createAndroidGradleTestConfigurationFromClass(
            project,
            "com.example.generated.GeneratedScreenshotTest",
          )
        requireNotNull(runConfiguration)
        assertEquals(
          listOf(
            ":app:testDebugUiScreenshotSuiteDefaultTestSuite",
            "--tests",
            "\"com.example.generated.GeneratedScreenshotTest\"",
          ),
          runConfiguration.settings.taskNames,
        )
      }
    } finally {
      PsiTestUtil.removeSourceRoot(sourceSetModule, generatedRoot)
      FileUtil.delete(File(project.basePath!!, "app/build/generated/ksp"))
    }
  }
}
