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
package com.android.tools.idea.testartifacts.testsuite

import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.model.IdeTestSuiteSource
import com.android.tools.idea.gradle.model.impl.IdeCustomSourceDirectoryImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteSourceImpl
import com.android.tools.idea.gradle.project.sync.TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY
import java.io.File

object TestSuiteTestUtils {
  /**
   * Creates an assets test suite source in [testSuitePath]. [name] defaults to the name of [testSuitePath], which matches the source set
   * name AGP gives to the only source of a single-source test suite when the directory is named after the test suite.
   */
  fun createAssetsTestSuiteSource(testSuitePath: File, name: String = testSuitePath.name): IdeTestSuiteSourceImpl {
    return IdeTestSuiteSourceImpl(
      name = name,
      type = IdeTestSuiteSource.SourceType.ASSETS,
      sourceProvider =
        IdeSourceProvider(
          name = name,
          folder = testSuitePath,
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
            listOf(
              IdeCustomSourceDirectoryImpl(sourceTypeName = TEST_SUITE_ASSETS_CUSTOM_SOURCE_DIRECTORY, myFolder = testSuitePath, path = ".")
            ),
          baselineProfileDirectories = emptyList(),
          keepRulesDirectoriesField = emptyList(),
          aarKeepRulesDirectoriesField = emptyList(),
        ),
    )
  }

  fun createHostJarTestSuiteSource(name: String, testSuitePath: File): IdeTestSuiteSourceImpl {
    return IdeTestSuiteSourceImpl(
      name = name,
      type = IdeTestSuiteSource.SourceType.HOST_JAR,
      sourceProvider =
        IdeSourceProvider(
          name = name,
          folder = testSuitePath,
          manifestFile = "AndroidManifest.xml",
          javaDirectories = listOf("java"),
          kotlinDirectories = listOf("kotlin"),
          resourcesDirectories = listOf("resources"),
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
}
