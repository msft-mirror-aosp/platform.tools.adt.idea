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

import com.android.tools.idea.gradle.model.IdeTestSuite
import com.android.tools.idea.gradle.model.IdeTestSuiteSource
import com.android.tools.idea.gradle.model.IdeVariantCore
import com.android.tools.idea.gradle.model.sourceSetNames
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.sync.TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY
import com.android.tools.idea.projectsystem.gradle.GradleSourceSetProjectPath
import com.android.tools.idea.projectsystem.gradle.getGradleProjectPath
import com.android.tools.idea.projectsystem.gradle.getTestSuiteModules
import com.android.tools.idea.projectsystem.gradle.getTestSuiteSourceSetName
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object TestSuiteUtils {

  /**
   * Finds the [IdeTestSuite] that contains the given [VirtualFile].
   *
   * A file is contained in a test suite if it is the manifest of one of its sources or is in one of their source directories. The root
   * directory of a test suite is not contained in it, see [getTestSuiteAtRoot].
   */
  fun getTestSuiteContainingFile(testSuites: List<IdeTestSuite>, virtualFile: VirtualFile): IdeTestSuite? {
    val file = virtualFile.toLocalIoFile() ?: return null

    // A file will only belong to one test suite so just return the first match.
    return testSuites.firstOrNull { isFileInTestSuite(file, it, getTestSuiteRoot(it, testSuites)) }
  }

  /** Returns the [IdeTestSuite] that has the given [VirtualFile] as its root directory. */
  fun getTestSuiteAtRoot(testSuites: List<IdeTestSuite>, virtualFile: VirtualFile): IdeTestSuite? {
    val file = virtualFile.toLocalIoFile() ?: return null

    // Test suites have distinct roots so just return the first match.
    return testSuites.firstOrNull { FileUtil.filesEqual(getTestSuiteRoot(it, testSuites), file) }
  }

  /**
   * Returns the [IdeTestSuite]s in [testSuites] that match [virtualFile]:
   * 1. the test suite that has [virtualFile] as its root directory or contains it, or else
   * 2. when [virtualFile] is a directory, all test suites whose root directory, source roots or source directories are in it.
   */
  fun findMatchingTestSuites(testSuites: List<IdeTestSuite>, virtualFile: VirtualFile): List<IdeTestSuite> {
    val file = virtualFile.toLocalIoFile() ?: return emptyList()
    val roots = testSuites.associateWith { getTestSuiteRoot(it, testSuites) }

    val containingSuite =
      testSuites.firstOrNull { FileUtil.filesEqual(roots[it], file) } ?: testSuites.firstOrNull { isFileInTestSuite(file, it, roots[it]) }
    if (containingSuite != null) {
      return listOf(containingSuite)
    }

    if (!virtualFile.isDirectory) {
      return emptyList()
    }
    return testSuites.filter { isTestSuiteInDirectory(it, roots[it], file) }
  }

  /**
   * Represents a target for a test suite that can be executed.
   *
   * A single test suite can have multiple targets (e.g., one for emulators, one for physical devices). This class holds the information
   * needed to run a specific target.
   *
   * @param targetName The name of the target.
   * @param testTaskName The name of the Gradle task used to run this test suite target.
   */
  data class TestSuiteTarget(val targetName: String, val testTaskName: String)

  /**
   * Returns the list of [TestSuiteTarget] for the given [testSuiteName] that are applicable to the currently selected variant.
   *
   * Only targets that run on a connected device, i.e. have no target device, will be returned.
   *
   * TODO(b/447100167): Add support for running targets configured with GMD devices
   */
  fun getTestSuiteTargets(selectedVariant: IdeVariantCore, testSuiteName: String): List<TestSuiteTarget> {
    val testSuiteComponent = selectedVariant.testSuiteComponents.find { it.suiteName == testSuiteName } ?: return emptyList()
    return testSuiteComponent.targets.filter { it.targetedDevices.isEmpty() }.map { TestSuiteTarget(it.targetName, it.testTaskName) }
  }

  private fun isFileInTestSuite(file: File, testSuite: IdeTestSuite, root: File?): Boolean {
    if (FileUtil.filesEqual(root, file)) {
      return false
    }
    return testSuite.sources.any { source ->
      (source.type != IdeTestSuiteSource.SourceType.ASSETS && FileUtil.filesEqual(source.sourceProvider.manifestFile, file)) ||
        getSourceDirectories(source).any { FileUtil.isAncestor(it, file, false) }
    }
  }

  private fun isTestSuiteInDirectory(testSuite: IdeTestSuite, root: File?, directory: File): Boolean {
    return (root != null && FileUtil.isAncestor(directory, root, false)) || testSuite.sources.any { isSourceInDirectory(it, directory) }
  }

  private fun isSourceInDirectory(source: IdeTestSuiteSource, directory: File): Boolean {
    val sourceRoot = getSourceRoot(source)
    return (sourceRoot != null && FileUtil.isAncestor(directory, sourceRoot, false)) ||
      getSourceDirectories(source).any { FileUtil.isAncestor(directory, it, false) }
  }

  /**
   * Returns the source directories of [source]. Besides the manifest, AGP test suites only support Java and Kotlin sources and Java
   * resources in host jar and test APK sources, and assets in assets sources.
   */
  private fun getSourceDirectories(source: IdeTestSuiteSource): Sequence<File> {
    val sourceProvider = source.sourceProvider
    return when (source.type) {
      IdeTestSuiteSource.SourceType.ASSETS ->
        sourceProvider.customSourceDirectories
          .asSequence()
          .filter { it.sourceTypeName == TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY }
          .map { it.directory }

      IdeTestSuiteSource.SourceType.HOST_JAR,
      IdeTestSuiteSource.SourceType.TEST_APK ->
        sequenceOf(sourceProvider.javaDirectories, sourceProvider.kotlinDirectories, sourceProvider.resourcesDirectories).flatten()
    }
  }

  /**
   * Returns the root directory of [source]: the directory of its manifest, which AGP always reports in the root directory of the source, or
   * the assets directory of an assets source without a manifest.
   */
  private fun getSourceRoot(source: IdeTestSuiteSource): File? {
    source.sourceProvider.manifestFile?.let {
      return it.parentFile
    }
    if (source.type != IdeTestSuiteSource.SourceType.ASSETS) {
      return null
    }
    return source.sourceProvider.customSourceDirectories
      .firstOrNull { it.sourceTypeName == TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY }
      ?.directory
  }

  /**
   * Temporary function to fetch the file that represents the test suite root directory. This will be made available within the IdeTestSuite
   * model in the future.
   *
   * AGP puts the only source of a test suite in `src/<suite>`, and each source of a test suite with several sources in
   * `src/<suite>/<test|androidTest|assetsTest>`, relative to the Gradle project directory. So the root directory of a test suite is the
   * root directory of its only source, or the common parent directory of the root directories of its sources. This is resolved from the
   * paths in the model only, so it does not depend on the location of the Gradle project directory or on the files on disk.
   *
   * The common parent directory is not the root directory of a test suite with several sources if it also contains sources of another test
   * suite in [testSuites], e.g. `src`.
   *
   * TODO(b/445649353): Access the root directory via the IdeTestSuite model
   */
  fun getTestSuiteRoot(testSuite: IdeTestSuite, testSuites: List<IdeTestSuite>): File? {
    val sourceRoots = testSuite.sources.mapNotNull { getSourceRoot(it) }
    if (testSuite.sources.size <= 1) {
      return sourceRoots.singleOrNull()
    }

    val commonParent = sourceRoots.map { it.parentFile }.distinct().singleOrNull() ?: return null
    val containsOtherTestSuite = testSuites.any { other ->
      other.name != testSuite.name && other.sources.any { isSourceInDirectory(it, commonParent) }
    }
    return commonParent.takeUnless { containsOtherTestSuite }
  }

  /**
   * Returns the root directory of [testSuite] without checking it against other test suites, see [getTestSuiteRoot]. Prefer passing the
   * test suites of the module when they are available.
   */
  fun getTestSuiteRoot(testSuite: IdeTestSuite): File? = getTestSuiteRoot(testSuite, listOf(testSuite))

  /** Returns the root directory [File] for the given [testSuiteModule]. */
  fun getTestSuiteRoot(testSuiteModule: Module): File? {
    val sourceSetName = testSuiteModule.getTestSuiteSourceSetName() ?: return null
    val androidModel = GradleAndroidModel.get(testSuiteModule) ?: return null
    val testSuite = androidModel.testSuites.find { sourceSetName in it.sourceSetNames } ?: return null

    return getTestSuiteRoot(testSuite, androidModel.testSuites)
  }

  /** Returns the test suite name for the given [testSuiteModule], or `null` if it is not a test suite module. */
  fun getTestSuiteNameFromModule(testSuiteModule: Module): String? {
    val sourceSetName = testSuiteModule.getTestSuiteSourceSetName() ?: return null
    return GradleAndroidModel.get(testSuiteModule)?.testSuites?.find { sourceSetName in it.sourceSetNames }?.name
  }

  /** Returns the [Module] for the test suite associated with the given [runConfiguration]. */
  fun getTestSuiteModule(runConfiguration: TestSuiteRunConfiguration): Module? {
    val appModule =
      runReadAction {
        val file = VfsUtil.findFileByIoFile(File(runConfiguration.settings.externalProjectPath), false) ?: return@runReadAction null
        ProjectFileIndex.getInstance(runConfiguration.project).getModuleForFile(file, false)
      } ?: return null

    val testTaskName = runConfiguration.getTaskNames().firstOrNull() ?: return null
    val androidModel = GradleAndroidModel.get(appModule) ?: return null
    val testSuiteName = getTestSuiteNameWithTestTaskName(androidModel.variants, testTaskName) ?: return null
    val suite = androidModel.testSuites.find { it.name == testSuiteName } ?: return null

    // Test suite modules are source set modules, so read their source set name from their Gradle project path rather than with
    // getTestSuiteSourceSetName(), which resolves the module group again for each module.
    return appModule.getTestSuiteModules().find {
      (it.getGradleProjectPath() as? GradleSourceSetProjectPath)?.sourceSet?.sourceSetName in suite.sourceSetNames
    }
  }

  /**
   * Returns the test suite name associated with the given [testTaskName].
   *
   * This is an N^3 solution, so pretty in-efficient, but it is currently the only way to get the test suite name from a run configuration -
   * since the user can manually change the task name.
   *
   * TODO(b/458035847): Replace with a more efficient lookup when the test suite name, variant and target are stored against the run
   *   configuration.
   */
  private fun getTestSuiteNameWithTestTaskName(variants: List<IdeVariantCore>, testTaskName: String): String? {
    for (variant in variants) {
      for (testSuiteComponent in variant.testSuiteComponents) {
        for (target in testSuiteComponent.targets) {
          if (target.testTaskName == testTaskName) {
            return testSuiteComponent.suiteName
          }
        }
      }
    }
    return null
  }

  private fun VirtualFile.toLocalIoFile(): File? =
    if (fileSystem == LocalFileSystem.getInstance()) VfsUtilCore.virtualToIoFile(this) else null
}
