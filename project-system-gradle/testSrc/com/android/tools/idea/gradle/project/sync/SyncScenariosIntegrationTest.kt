/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.GradleIntegrationTest
import com.android.tools.idea.testing.TestProjectToSnapshotPaths
import com.android.tools.idea.testing.onEdt
import com.android.tools.idea.testing.openPreparedProject
import com.android.tools.idea.testing.prepareGradleProject
import com.google.common.truth.Expect
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.PathUtil
import org.jetbrains.android.AndroidTestBase
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File

@RunsInEdt
class SyncScenariosIntegrationTest : GradleIntegrationTest {

  @get:Rule
  val projectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @get:Rule
  var testName = TestName()

  @get:Rule
  val expect: Expect = Expect.createAndEnableStackTrace()

  @Test
  fun testFilteredOutProductFlavors() {
    val projectRoot = prepareGradleProject(TestProjectToSnapshotPaths.MULTI_FLAVOR, "project")
    projectRoot.resolve("app").resolve("build.gradle").replaceContent { content ->
      content
        .replace(" implementation", "// implementation")
        .replace(" androidTestImplementation", "// androidTestImplementation") +
        """
              android.variantFilter { variant ->
                  variant.setIgnore(!variant.name.startsWith("firstAbcSecondAbc"))
              }
        """
    }
    openPreparedProject("project") { }
  }

  override fun getName(): String = testName.methodName
  override fun getBaseTestPath(): String = projectRule.fixture.tempDirPath
  override fun getTestDataDirectoryWorkspaceRelativePath(): String = "tools/adt/idea/android/testData/snapshots"
  override fun getAdditionalRepos(): Collection<File> =
    listOf(File(AndroidTestBase.getTestDataPath(), PathUtil.toSystemDependentName(TestProjectToSnapshotPaths.PSD_SAMPLE_REPO)))
}
private fun File.replaceContent(change: (String) -> String) {
  writeText(
    readText().let {
      val result = change(it)
      if (it == result) error("No replacements made")
      result
    }
  )
}
