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
package com.android.tools.idea.testartifacts.testsuite.runconfiguration

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.model.IdeTestSuiteSource
import com.android.tools.idea.gradle.model.impl.IdeCustomSourceDirectoryImpl
import com.android.tools.idea.gradle.model.impl.IdeJUnitEngineInfoImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteComponentCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteSourceImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteTargetImpl
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.sync.TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY
import com.android.tools.idea.testartifacts.testsuite.TestSuiteTestUtils.createAssetsTestSuiteSource
import com.android.tools.idea.testartifacts.testsuite.TestSuiteTestUtils.createHostJarTestSuiteSource
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.createMainSourceProviderForDefaultTestProjectStructure
import com.android.tools.idea.testing.onEdt
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.resolveFromRootOrRelative
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.RunsInEdt
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class TestSuiteUtilsTest {

  @get:Rule
  val rule =
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
                    name = "myTestSuite",
                    sources = listOf(createAssetsTestSuiteSource(testSuitePath = moduleBasePath.resolve("src/myTestSuite"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
                    targetedVariants = listOf("debug"),
                  ),
                  IdeTestSuiteImpl(
                    name = "myTestSuiteWithoutTargets",
                    sources = listOf(createAssetsTestSuiteSource(testSuitePath = moduleBasePath.resolve("src/myTestSuiteWithoutTargets"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
                    targetedVariants = listOf("debug"),
                  ),
                  IdeTestSuiteImpl(
                    name = "myTestSuiteWithNonTargetedVariant",
                    sources =
                      listOf(createAssetsTestSuiteSource(testSuitePath = moduleBasePath.resolve("src/myTestSuiteWithNonTargetedVariant"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
                    targetedVariants = listOf("release"),
                  ),
                  IdeTestSuiteImpl(
                    name = "myTestSuiteWithMultipleTargets",
                    sources =
                      listOf(createAssetsTestSuiteSource(testSuitePath = moduleBasePath.resolve("src/myTestSuiteWithMultipleTargets"))),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
                    targetedVariants = listOf("debug"),
                  ),
                  IdeTestSuiteImpl(
                    name = "mixed",
                    sources =
                      listOf(
                        createHostJarTestSuiteSource("mixedTest", moduleBasePath.resolve("src/mixed/test")),
                        createAssetsTestSuiteSource(testSuitePath = moduleBasePath.resolve("src/mixed/assetsTest"), name = "mixedAssets"),
                      ),
                    junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
                    targetedVariants = listOf("debug"),
                  ),
                )
              },
              testSuiteComponentsStub = { variant ->
                when (variant) {
                  "debug" ->
                    listOf(
                      IdeTestSuiteComponentCoreImpl(
                        suiteName = "myTestSuite",
                        targets =
                          listOf(
                            IdeTestSuiteTargetImpl(
                              targetName = "connectedTest",
                              testTaskName = "myTestSuiteTaskName",
                              targetedDevices = emptyList(),
                            )
                          ),
                      ),
                      IdeTestSuiteComponentCoreImpl(
                        suiteName = "myTestSuiteWithoutTargets",
                        targets = emptyList(),
                      ),
                      IdeTestSuiteComponentCoreImpl(
                        suiteName = "myTestSuiteWithMultipleTargets",
                        targets =
                          listOf(
                            IdeTestSuiteTargetImpl(
                              targetName = "target1",
                              testTaskName = "myTarget1TaskName",
                              targetedDevices = emptyList(),
                            ),
                            IdeTestSuiteTargetImpl(
                              targetName = "target2",
                              testTaskName = "myTarget2TaskName",
                              targetedDevices = emptyList(),
                            ),
                            IdeTestSuiteTargetImpl(
                              targetName = "target3",
                              testTaskName = "myTarget3TaskName",
                              targetedDevices = listOf("deviceId"),
                            ),
                          ),
                      ),
                      IdeTestSuiteComponentCoreImpl(
                        suiteName = "mixed",
                        targets =
                          listOf(
                            IdeTestSuiteTargetImpl(
                              targetName = "default",
                              testTaskName = "mixedSuiteTaskName",
                              targetedDevices = emptyList(),
                            )
                          ),
                      ),
                    )

                  "release" ->
                    listOf(
                      IdeTestSuiteComponentCoreImpl(
                        suiteName = "myTestSuiteWithNonTargetedVariant",
                        targets =
                          listOf(
                            IdeTestSuiteTargetImpl(
                              targetName = "connectedTest",
                              testTaskName = "myTestSuiteWithNonTargetedVariantTaskName",
                              targetedDevices = emptyList(),
                            )
                          ),
                      )
                    )

                  else -> emptyList()
                }
              },
            ),
        ),
      )
      .onEdt()

  lateinit var testFile: PsiFile
  lateinit var testSuiteModule: Module
  lateinit var gradleAndroidModel: GradleAndroidModel

  @Before
  fun setUp() {
    testFile = rule.fixture.addFileToProject("app/src/myTestSuite/assets/test.xml", "")
    testSuiteModule = ModuleUtilCore.findModuleForFile(testFile.virtualFile, rule.project)!!
    gradleAndroidModel = GradleAndroidModel.get(testSuiteModule)!!
  }

  @Test
  fun getTestSuiteContainingFile_returnsTestSuite() {
    val testSuite = TestSuiteUtils.getTestSuiteContainingFile(gradleAndroidModel.testSuites, testFile.virtualFile)

    assertNotNull(testSuite)
    assertEquals("myTestSuite", testSuite.name)
    assertEquals(setOf("engine1"), testSuite.junitEngineInfo.includedEngines)
    assertEquals(listOf("debug"), testSuite.targetedVariants)
    assertEquals(IdeTestSuiteSource.SourceType.ASSETS, testSuite.sources.first().type)
    assertEquals("myTestSuite", testSuite.sources.first().name)

    val folder = rule.fixture.project.guessProjectDir()!!.resolveFromRootOrRelative("app/src/myTestSuite")!!.toIoFile()
    assertEquals(
      IdeSourceProvider(
        name = "myTestSuite",
        folder = folder,
        manifestFile = "AndroidManifest.xml",
        javaDirectories = emptyList(),
        kotlinDirectories = emptyList(),
        resourcesDirectories = emptyList(),
        aidlDirectories = emptyList(),
        renderscriptDirectories = emptyList(),
        resDirectories = emptyList(),
        assetsDirectories = emptyList(),
        jniLibsDirectories = emptyList(),
        shadersDirectories = emptyList(),
        mlModelsDirectories = emptyList(),
        customSourceDirectories =
          listOf(IdeCustomSourceDirectoryImpl(sourceTypeName = TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY, myFolder = folder, path = ".")),
        baselineProfileDirectories = emptyList(),
        keepRulesDirectoriesField = emptyList(),
        aarKeepRulesDirectoriesField = emptyList(),
      ),
      testSuite.sources.first().sourceProvider,
    )
  }

  @Test
  fun getTestSuiteContainingFile_returnsNull_whenFileNotInTestSuite() {
    val nonTestSuiteFile = rule.fixture.addFileToProject("app/src/main/java/com/example/app/MainActivity.kt", "")

    val testSuite = TestSuiteUtils.getTestSuiteContainingFile(gradleAndroidModel.testSuites, nonTestSuiteFile.virtualFile)

    assertNull(testSuite)
  }

  @Test
  fun getTestSuiteContainingFile_returnsNull_whenVirtualFileNotInLocalFileSystem() {
    val virtualFile = LightVirtualFile("test.xml")

    val testSuite = TestSuiteUtils.getTestSuiteContainingFile(gradleAndroidModel.testSuites, virtualFile)

    assertNull(testSuite)
  }

  @Test
  fun getTestSuiteContainingFile_returnsNull_forRootTestSuiteDirectory() {
    val testSuiteRootDirectory = rule.fixture.findFileInTempDir("app/src/myTestSuite")

    val testSuite = TestSuiteUtils.getTestSuiteContainingFile(gradleAndroidModel.testSuites, testSuiteRootDirectory)

    assertNull(testSuite)
  }

  @Test
  fun getTestSuiteAtRoot_returnsTestSuite_whenFileIsTestSuiteRootDir() {
    val testSuiteRootDirectory = rule.fixture.findFileInTempDir("app/src/myTestSuite")

    val testSuite = TestSuiteUtils.getTestSuiteAtRoot(gradleAndroidModel.testSuites, testSuiteRootDirectory)

    assertNotNull(testSuite)
    assertEquals("myTestSuite", testSuite.name)
  }

  @Test
  fun getTestSuiteAtRoot_returnsNull_whenFileIsNotTestSuiteRootDir() {
    val nonRootTestSuiteDirectory = rule.fixture.findFileInTempDir("app/src")

    val testSuite = TestSuiteUtils.getTestSuiteAtRoot(gradleAndroidModel.testSuites, nonRootTestSuiteDirectory)

    assertNull(testSuite)
  }

  @Test
  fun getTestSuiteAtRoot_returnsNull_whenFileIsSubDirectoryOfTestSuiteRoot() {
    val testSuiteSubdirectory = rule.fixture.findFileInTempDir("app/src/myTestSuite/assets")

    val testSuite = TestSuiteUtils.getTestSuiteAtRoot(gradleAndroidModel.testSuites, testSuiteSubdirectory)

    assertNull(testSuite)
  }

  @Test
  fun getTestSuiteRoot_returnsTestSuiteRoot() {
    val testSuiteRoot = TestSuiteUtils.getTestSuiteRoot(testSuiteModule)

    assertNotNull(testSuiteRoot)
    assertEquals(rule.fixture.project.guessProjectDir()!!.resolveFromRootOrRelative("app/src/myTestSuite")!!.toIoFile(), testSuiteRoot)
  }

  @Test
  fun getTestSuiteRoot_returnsNull_whenModuleIsNotTestSuiteModule() {
    val appModule = ModuleUtilCore.findModuleForFile(testFile.virtualFile.parent.parent.parent, rule.project)!!
    val testSuiteRoot = TestSuiteUtils.getTestSuiteRoot(appModule)

    assertNull(testSuiteRoot)
    assertNull(TestSuiteUtils.getTestSuiteNameFromModule(appModule))
  }

  @Test
  fun multiSourceTestSuiteModule_resolvesSuiteNameRootAndModule() {
    val mixedHostFile = rule.fixture.addFileToProject("app/src/mixed/test/kotlin/HostTest.kt", "")
    val mixedTestModule = ModuleUtilCore.findModuleForFile(mixedHostFile.virtualFile, rule.project)!!
    val expectedRoot = rule.fixture.project.guessProjectDir()!!.resolveFromRootOrRelative("app/src/mixed")!!.toIoFile()

    assertEquals("mixed", TestSuiteUtils.getTestSuiteNameFromModule(mixedTestModule))
    assertEquals(expectedRoot, TestSuiteUtils.getTestSuiteRoot(mixedTestModule))

    val runConfiguration =
      TestSuiteRunConfiguration(rule.project, TestSuiteRunConfigurationType().configurationFactories[0], "Mixed Config")
    runConfiguration.addTaskName("mixedSuiteTaskName")
    runConfiguration.settings.externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(mixedTestModule)!!

    assertEquals(mixedTestModule, TestSuiteUtils.getTestSuiteModule(runConfiguration))
  }

  @Test
  fun getTestSuiteTargets_returnsSingleTarget() {
    val targets = TestSuiteUtils.getTestSuiteTargets(gradleAndroidModel.selectedVariant, "myTestSuite")

    assertEquals(1, targets.size)
    assertEquals("connectedTest", targets.first().targetName)
    assertEquals("myTestSuiteTaskName", targets.first().testTaskName)
  }

  @Test
  fun getTestSuiteTargets_ignoresTargetsWithDevices() {
    val targets = TestSuiteUtils.getTestSuiteTargets(gradleAndroidModel.selectedVariant, "myTestSuiteWithMultipleTargets")

    assertEquals(2, targets.size)
    assertEquals("target1", targets.first().targetName)
    assertEquals("myTarget1TaskName", targets.first().testTaskName)
    assertEquals("target2", targets[1].targetName)
    assertEquals("myTarget2TaskName", targets[1].testTaskName)
  }

  @Test
  fun getTestSuiteTaskName_returnsNull_whenTestSuiteNotFound() {
    val targets = TestSuiteUtils.getTestSuiteTargets(gradleAndroidModel.selectedVariant, "unknownTestSuite")

    assertTrue(targets.isEmpty())
  }

  @Test
  fun getTestSuiteTaskName_returnsNull_whenNoTargetsConfiguredOnTestSuite() {
    val targets = TestSuiteUtils.getTestSuiteTargets(gradleAndroidModel.selectedVariant, "myTestSuiteWithoutTargets")

    assertTrue(targets.isEmpty())
  }

  @Test
  fun getTestSuiteTaskName_returnsNull_whenNonTargetedVariantSelected() {
    val targets = TestSuiteUtils.getTestSuiteTargets(gradleAndroidModel.selectedVariant, "myTestSuiteWithNonTargetedVariant")

    assertTrue(targets.isEmpty())
  }

  @Test
  fun getTestSuiteModule_returnsModule() {
    val runConfiguration =
      TestSuiteRunConfiguration(rule.project, TestSuiteRunConfigurationType().configurationFactories[0], "Test Suite Config")
    runConfiguration.addTaskName("myTestSuiteTaskName")
    runConfiguration.settings.externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(testSuiteModule)!!

    val module = TestSuiteUtils.getTestSuiteModule(runConfiguration)

    assertNotNull(module)
    assertEquals(testSuiteModule, module)
  }

  @Test
  fun getTestSuiteModule_returnsNull_whenTaskNameNotFound() {
    val runConfiguration =
      TestSuiteRunConfiguration(rule.project, TestSuiteRunConfigurationType().configurationFactories[0], "Test Suite Config")
    runConfiguration.addTaskName("unknownTaskName")
    runConfiguration.settings.externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(testSuiteModule)!!

    val module = TestSuiteUtils.getTestSuiteModule(runConfiguration)

    assertNull(module)
  }

  @Test
  fun getTestSuiteModule_returnsNull_whenExternalProjectPathNotFound() {
    val runConfiguration =
      TestSuiteRunConfiguration(rule.project, TestSuiteRunConfigurationType().configurationFactories[0], "Test Suite Config")
    runConfiguration.addTaskName("myTestSuiteTaskName")
    runConfiguration.settings.externalProjectPath = "/unknown/path"

    val module = TestSuiteUtils.getTestSuiteModule(runConfiguration)

    assertNull(module)
  }

  @Test
  fun getTestSuiteContainingFile_returnsTestSuite_forTestApkAndHostJarSources() {
    val projectDir = rule.fixture.project.guessProjectDir()!!.toIoFile()
    val apkSuite =
      IdeTestSuiteImpl(
        name = "myApkSuite",
        sources = listOf(createTestApkTestSuiteSource("myApkSuite", projectDir.resolve("app/src/myApkSuite"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val hostSuite =
      IdeTestSuiteImpl(
        name = "myHostSuite",
        sources = listOf(createHostJarTestSuiteSource("myHostSuite", projectDir.resolve("app/src/myHostSuite"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val suites = listOf(apkSuite, hostSuite)

    val javaFile = rule.fixture.addFileToProject("app/src/myApkSuite/java/com/example/ApkTest.java", "")
    val kotlinFile = rule.fixture.addFileToProject("app/src/myApkSuite/kotlin/com/example/ApkTest.kt", "")
    val resourceFile = rule.fixture.addFileToProject("app/src/myApkSuite/resources/config.properties", "")
    val manifestFile = rule.fixture.addFileToProject("app/src/myApkSuite/AndroidManifest.xml", "<manifest/>")
    val nestedSameNameDir = rule.fixture.addFileToProject("app/src/myApkSuite/kotlin/myApkSuite/Test.kt", "").virtualFile.parent
    // AGP test suite sources do not support Android resources, so they are not part of the test suite.
    val androidResourceFile = rule.fixture.addFileToProject("app/src/myApkSuite/res/values/strings.xml", "<resources/>")
    val hostResourceFile = rule.fixture.addFileToProject("app/src/myHostSuite/resources/config.properties", "")
    val apkSuiteRootDir = rule.fixture.findFileInTempDir("app/src/myApkSuite")

    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, javaFile.virtualFile))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, kotlinFile.virtualFile))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, kotlinFile.virtualFile.parent.parent))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, resourceFile.virtualFile))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, manifestFile.virtualFile))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, nestedSameNameDir))
    assertNull(TestSuiteUtils.getTestSuiteContainingFile(suites, androidResourceFile.virtualFile))
    assertNull(TestSuiteUtils.getTestSuiteContainingFile(suites, apkSuiteRootDir))
    assertEquals(apkSuite, TestSuiteUtils.getTestSuiteAtRoot(suites, apkSuiteRootDir))
    assertEquals(hostSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, hostResourceFile.virtualFile))

    val assetsFolder = projectDir.resolve("app/src/customAssetsSuite")
    val unrelatedFolder = projectDir.resolve("app/src/unrelatedCustom")
    val assetsSourceWithExtraCustomDir =
      IdeTestSuiteSourceImpl(
        name = "customAssetsSuite",
        type = IdeTestSuiteSource.SourceType.ASSETS,
        sourceProvider =
          createSourceProvider(
            name = "customAssetsSuite",
            folder = assetsFolder,
            manifestFile = "AndroidManifest.xml",
            customSourceDirectories =
              createAssetsTestSuiteSource(assetsFolder).sourceProvider.customSourceDirectories +
                IdeCustomSourceDirectoryImpl(sourceTypeName = "otherCustom", myFolder = unrelatedFolder, path = "."),
          ),
      )
    val assetsSuite =
      IdeTestSuiteImpl(
        name = "customAssetsSuite",
        sources = listOf(assetsSourceWithExtraCustomDir),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val validAssetFile = rule.fixture.addFileToProject("app/src/customAssetsSuite/item.json", "{}")
    val unrelatedCustomFile = rule.fixture.addFileToProject("app/src/unrelatedCustom/item.json", "{}")
    assertEquals(assetsSuite, TestSuiteUtils.getTestSuiteContainingFile(listOf(assetsSuite), validAssetFile.virtualFile))
    assertNull(TestSuiteUtils.getTestSuiteContainingFile(listOf(assetsSuite), unrelatedCustomFile.virtualFile))
  }

  @Test
  fun getTestSuiteRoot_resolvesRootFromModel_forCustomGradleProjectDirectory() {
    // The Gradle project directory is deeper than the directory of the IDE module, and the test suite directories do not exist on disk.
    val gradleProjectDir = rule.fixture.project.guessProjectDir()!!.toIoFile().resolve("modules/feature/app")
    val hostSuite =
      IdeTestSuiteImpl(
        name = "hostSuite",
        sources = listOf(createHostJarTestSuiteSource("hostSuite", gradleProjectDir.resolve("src/hostSuite"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val assetsSuite =
      IdeTestSuiteImpl(
        name = "assetsSuite",
        sources = listOf(createAssetsTestSuiteSource(gradleProjectDir.resolve("src/assetsSuite"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val mixedSuite =
      IdeTestSuiteImpl(
        name = "mixedSuite",
        sources =
          listOf(
            createHostJarTestSuiteSource("mixedSuiteTest", gradleProjectDir.resolve("src/mixedSuite/test")),
            createTestApkTestSuiteSource("mixedSuiteAndroidTest", gradleProjectDir.resolve("src/mixedSuite/androidTest")),
            createAssetsTestSuiteSource(gradleProjectDir.resolve("src/mixedSuite/assetsTest"), name = "mixedSuiteAssets"),
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val suites = listOf(hostSuite, assetsSuite, mixedSuite)

    assertEquals(gradleProjectDir.resolve("src/hostSuite"), TestSuiteUtils.getTestSuiteRoot(hostSuite, suites))
    assertEquals(gradleProjectDir.resolve("src/assetsSuite"), TestSuiteUtils.getTestSuiteRoot(assetsSuite, suites))
    assertEquals(gradleProjectDir.resolve("src/mixedSuite"), TestSuiteUtils.getTestSuiteRoot(mixedSuite, suites))
    assertEquals(gradleProjectDir.resolve("src/mixedSuite"), TestSuiteUtils.getTestSuiteRoot(mixedSuite))

    val mixedHostFile = rule.fixture.addFileToProject("modules/feature/app/src/mixedSuite/test/kotlin/HostTest.kt", "")
    val mixedRootDir = rule.fixture.findFileInTempDir("modules/feature/app/src/mixedSuite")
    assertEquals(mixedSuite, TestSuiteUtils.getTestSuiteAtRoot(suites, mixedRootDir))
    assertEquals(mixedSuite, TestSuiteUtils.getTestSuiteContainingFile(suites, mixedHostFile.virtualFile))
    assertNull(TestSuiteUtils.getTestSuiteContainingFile(suites, mixedRootDir))
  }

  @Test
  fun getTestSuiteRoot_usesAssetsDirectoryWithoutManifest_andIsNullForOtherSourcesWithoutManifest() {
    val projectDir = rule.fixture.project.guessProjectDir()!!.toIoFile()
    val assetsRoot = projectDir.resolve("app/src/noManifestAssets")
    val assetsSuite =
      IdeTestSuiteImpl(
        name = "noManifestAssets",
        sources =
          listOf(
            IdeTestSuiteSourceImpl(
              name = "noManifestAssets",
              type = IdeTestSuiteSource.SourceType.ASSETS,
              sourceProvider =
                createSourceProvider(
                  name = "noManifestAssets",
                  folder = assetsRoot,
                  manifestFile = null,
                  customSourceDirectories =
                    listOf(
                      IdeCustomSourceDirectoryImpl(sourceTypeName = "otherCustom", myFolder = projectDir, path = "app/src/other"),
                      IdeCustomSourceDirectoryImpl(
                        sourceTypeName = TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY,
                        myFolder = assetsRoot,
                        path = ".",
                      ),
                    ),
                ),
            )
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val hostSuite =
      IdeTestSuiteImpl(
        name = "noManifestHost",
        sources =
          listOf(
            IdeTestSuiteSourceImpl(
              name = "noManifestHost",
              type = IdeTestSuiteSource.SourceType.HOST_JAR,
              sourceProvider =
                createSourceProvider(
                  name = "noManifestHost",
                  folder = projectDir.resolve("app/src/noManifestHost"),
                  manifestFile = null,
                  javaDirectories = listOf("java"),
                ),
            )
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )

    assertEquals(assetsRoot, TestSuiteUtils.getTestSuiteRoot(assetsSuite))
    assertNull(TestSuiteUtils.getTestSuiteRoot(hostSuite))
  }

  @Test
  fun getTestSuiteRoot_isNull_whenSourceRootsOfMixedSuiteDoNotShareTestSuiteDirectory() {
    val projectDir = rule.fixture.project.guessProjectDir()!!.toIoFile()
    val splitSuite =
      IdeTestSuiteImpl(
        name = "split",
        sources =
          listOf(
            createHostJarTestSuiteSource("splitTest", projectDir.resolve("app/src/splitA/test")),
            createTestApkTestSuiteSource("splitAndroidTest", projectDir.resolve("app/src/splitB/androidTest")),
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    // The sources of this test suite are directly in `src`, which also contains the sources of the other test suites.
    val flatSuite =
      IdeTestSuiteImpl(
        name = "flat",
        sources =
          listOf(
            createHostJarTestSuiteSource("flatTest", projectDir.resolve("app/src/flatTest")),
            createTestApkTestSuiteSource("flatAndroidTest", projectDir.resolve("app/src/flatAndroidTest")),
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val suites = gradleAndroidModel.testSuites + splitSuite + flatSuite
    val srcDir = rule.fixture.findFileInTempDir("app/src")

    assertNull(TestSuiteUtils.getTestSuiteRoot(splitSuite, suites))
    assertNull(TestSuiteUtils.getTestSuiteRoot(flatSuite, suites))
    assertNull(TestSuiteUtils.getTestSuiteAtRoot(suites, srcDir))
    assertEquals(suites.map { it.name }, TestSuiteUtils.findMatchingTestSuites(suites, srcDir).map { it.name })
  }

  @Test
  fun getTestSuiteRoot_supportsTestSuiteNamedSrc() {
    rule.fixture.addFileToProject("app/src/src/kotlin/SrcTest.kt", "")
    val projectDir = rule.fixture.project.guessProjectDir()!!.toIoFile()
    val srcSuite =
      IdeTestSuiteImpl(
        name = "src",
        sources = listOf(createHostJarTestSuiteSource("src", projectDir.resolve("app/src/src"))),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    val suites = gradleAndroidModel.testSuites + srcSuite
    val srcDir = rule.fixture.findFileInTempDir("app/src")
    val srcSuiteRootDir = rule.fixture.findFileInTempDir("app/src/src")

    assertEquals(projectDir.resolve("app/src/src"), TestSuiteUtils.getTestSuiteRoot(srcSuite, suites))
    assertEquals(srcSuite, TestSuiteUtils.getTestSuiteAtRoot(suites, srcSuiteRootDir))
    assertNull(TestSuiteUtils.getTestSuiteAtRoot(suites, srcDir))
    assertEquals(listOf(srcSuite), TestSuiteUtils.findMatchingTestSuites(suites, srcSuiteRootDir))
    assertEquals(suites.map { it.name }, TestSuiteUtils.findMatchingTestSuites(suites, srcDir).map { it.name })

    val mixedSrcSuite =
      IdeTestSuiteImpl(
        name = "src",
        sources =
          listOf(
            createHostJarTestSuiteSource("srcTest", projectDir.resolve("app/src/src/test")),
            createTestApkTestSuiteSource("srcAndroidTest", projectDir.resolve("app/src/src/androidTest")),
          ),
        junitEngineInfo = IdeJUnitEngineInfoImpl(includedEngines = setOf("engine1")),
        targetedVariants = listOf("debug"),
      )
    assertEquals(
      projectDir.resolve("app/src/src"),
      TestSuiteUtils.getTestSuiteRoot(mixedSrcSuite, gradleAndroidModel.testSuites + mixedSrcSuite),
    )
  }

  @Test
  fun findMatchingTestSuites_matchesContainingSuiteOrContainedSuites() {
    val nonTestSuiteDir = rule.fixture.addFileToProject("app/src/main/java/com/example/Main.kt", "").virtualFile.parent
    val mixedHostFile = rule.fixture.addFileToProject("app/src/mixed/test/kotlin/HostTest.kt", "")
    val srcDir = rule.fixture.findFileInTempDir("app/src")
    val suiteRootDir = rule.fixture.findFileInTempDir("app/src/myTestSuite")
    val mixedRootDir = rule.fixture.findFileInTempDir("app/src/mixed")
    val mixedHostSourceRootDir = rule.fixture.findFileInTempDir("app/src/mixed/test")
    val testSuites = gradleAndroidModel.testSuites

    assertEquals(listOf("myTestSuite"), TestSuiteUtils.findMatchingTestSuites(testSuites, suiteRootDir).map { it.name })
    assertEquals(listOf("myTestSuite"), TestSuiteUtils.findMatchingTestSuites(testSuites, testFile.virtualFile).map { it.name })
    assertEquals(listOf("mixed"), TestSuiteUtils.findMatchingTestSuites(testSuites, mixedRootDir).map { it.name })
    assertEquals(listOf("mixed"), TestSuiteUtils.findMatchingTestSuites(testSuites, mixedHostSourceRootDir).map { it.name })
    assertEquals(listOf("mixed"), TestSuiteUtils.findMatchingTestSuites(testSuites, mixedHostFile.virtualFile).map { it.name })
    assertEquals(testSuites.map { it.name }, TestSuiteUtils.findMatchingTestSuites(testSuites, srcDir).map { it.name })
    assertTrue(TestSuiteUtils.findMatchingTestSuites(testSuites, nonTestSuiteDir).isEmpty())
    assertTrue(TestSuiteUtils.findMatchingTestSuites(testSuites, LightVirtualFile("test.kt")).isEmpty())
  }

  /** Creates a test APK test suite source in [testSuitePath] with the source directories that AGP supports for test suites. */
  private fun createTestApkTestSuiteSource(name: String, testSuitePath: File) =
    IdeTestSuiteSourceImpl(
      name = name,
      type = IdeTestSuiteSource.SourceType.TEST_APK,
      sourceProvider =
        createSourceProvider(
          name = name,
          folder = testSuitePath,
          manifestFile = "AndroidManifest.xml",
          javaDirectories = listOf("java"),
          kotlinDirectories = listOf("kotlin"),
          resourcesDirectories = listOf("resources"),
        ),
    )

  private fun createSourceProvider(
    name: String,
    folder: File,
    manifestFile: String?,
    javaDirectories: List<String> = emptyList(),
    kotlinDirectories: List<String> = emptyList(),
    resourcesDirectories: List<String> = emptyList(),
    customSourceDirectories: List<IdeCustomSourceDirectoryImpl> = emptyList(),
  ) =
    IdeSourceProvider(
      name = name,
      folder = folder,
      manifestFile = manifestFile,
      javaDirectories = javaDirectories,
      kotlinDirectories = kotlinDirectories,
      resourcesDirectories = resourcesDirectories,
      aidlDirectories = emptyList(),
      renderscriptDirectories = emptyList(),
      resDirectories = emptyList(),
      assetsDirectories = emptyList(),
      jniLibsDirectories = emptyList(),
      shadersDirectories = emptyList(),
      mlModelsDirectories = emptyList(),
      customSourceDirectories = customSourceDirectories,
      baselineProfileDirectories = emptyList(),
      keepRulesDirectoriesField = emptyList(),
      aarKeepRulesDirectoriesField = emptyList(),
    )
}
