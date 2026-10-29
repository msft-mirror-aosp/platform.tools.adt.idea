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
package com.android.tools.idea.testartifacts.testsuite

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.SCREENSHOT_TEST_ENGINE_ID
import com.android.tools.idea.gradle.model.impl.IdeJUnitEngineInfoImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteComponentCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteImpl
import com.android.tools.idea.projectsystem.gradle.getTestSuiteModules
import com.android.tools.idea.testartifacts.scopes.GradleTestArtifactSearchScopes
import com.android.tools.idea.testartifacts.testsuite.TestSuiteTestUtils.createAssetsTestSuiteSource
import com.android.tools.idea.testartifacts.testsuite.TestSuiteTestUtils.createHostJarTestSuiteSource
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.createMainSourceProviderForDefaultTestProjectStructure
import com.android.tools.idea.testing.onEdt
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tests the test suite source set modules in [GradleTestArtifactSearchScopes]. */
@RunsInEdt
class GradleTestArtifactSearchScopesTestSuitesTest {

  private val projectRule =
    AndroidProjectRule.withAndroidModels(
        JavaModuleModelBuilder.rootModuleBuilder,
        AndroidModuleModelBuilder(
          gradlePath = ":app",
          selectedBuildVariant = "debug",
          projectBuilder =
            AndroidProjectBuilder(
              projectType = { IdeAndroidProjectType.PROJECT_TYPE_APP },
              namespace = { "com.example.app" },
              mainSourceProvider = { createMainSourceProviderForDefaultTestProjectStructure() },
              testSuites = {
                listOf(
                  IdeTestSuiteImpl(
                    name = "uiScreenshots",
                    sources = listOf(createHostJarTestSuiteSource("uiScreenshots", moduleBasePath.resolve("src/uiScreenshots"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
                    targetedVariants = listOf("debug"),
                  ),
                  IdeTestSuiteImpl(
                    name = "featureScreenshots",
                    sources =
                      listOf(
                        createHostJarTestSuiteSource("featureScreenshotsTest", moduleBasePath.resolve("src/featureScreenshots/test")),
                        createAssetsTestSuiteSource(
                          testSuitePath = moduleBasePath.resolve("src/featureScreenshots/assetsTest"),
                          name = "featureScreenshotsAssets",
                        ),
                      ),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf(SCREENSHOT_TEST_ENGINE_ID)),
                    targetedVariants = listOf("debug"),
                  ),
                  IdeTestSuiteImpl(
                    name = "hostTests",
                    sources = listOf(createHostJarTestSuiteSource("hostTests", moduleBasePath.resolve("src/hostTests"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("junit-jupiter")),
                    targetedVariants = listOf("debug"),
                  ),
                )
              },
              testSuiteComponentsStub = {
                listOf(
                  IdeTestSuiteComponentCoreImpl(suiteName = "uiScreenshots", targets = emptyList()),
                  IdeTestSuiteComponentCoreImpl(suiteName = "featureScreenshots", targets = emptyList()),
                  IdeTestSuiteComponentCoreImpl(suiteName = "hostTests", targets = emptyList()),
                )
              },
            ),
        ),
      )
      .onEdt()

  @get:Rule val ruleChain = RuleChain(projectRule, FlagRule(StudioFlags.AGP_TEST_SUITES_ENABLED, true))

  @Test
  fun screenshotTestSourceScope_includesSourcesOfScreenshotTestSuiteModulesOnly() {
    val uiScreenshotFile = projectRule.fixture.addFileToProject("app/src/uiScreenshots/kotlin/UiScreenshotTest.kt", "").virtualFile
    val featureScreenshotFile =
      projectRule.fixture.addFileToProject("app/src/featureScreenshots/test/kotlin/FeatureScreenshotTest.kt", "").virtualFile
    val featureScreenshotAsset = projectRule.fixture.addFileToProject("app/src/featureScreenshots/assetsTest/data.json", "{}").virtualFile
    val hostTestFile = projectRule.fixture.addFileToProject("app/src/hostTests/kotlin/HostTest.kt", "").virtualFile
    val mainFile = projectRule.fixture.addFileToProject("app/src/main/java/com/example/app/Main.kt", "").virtualFile
    val mainModule = ModuleUtilCore.findModuleForFile(mainFile, projectRule.project)!!
    assertEquals(4, mainModule.getTestSuiteModules().size)

    val scopes = GradleTestArtifactSearchScopes(mainModule)

    assertTrue(scopes.isScreenshotTestSource(uiScreenshotFile))
    assertTrue(scopes.isScreenshotTestSource(featureScreenshotFile))
    assertTrue(scopes.isScreenshotTestSource(featureScreenshotAsset))
    assertFalse(scopes.isScreenshotTestSource(hostTestFile))
    assertFalse(scopes.isScreenshotTestSource(mainFile))
    assertTrue(scopes.isTestSource(hostTestFile))
    assertFalse(scopes.isTestSource(mainFile))
  }
}
