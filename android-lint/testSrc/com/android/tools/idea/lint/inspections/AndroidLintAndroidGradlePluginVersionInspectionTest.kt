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
package com.android.tools.idea.lint.inspections

import com.android.ide.common.repository.AgpVersion
import com.android.tools.idea.lint.AndroidLintIdeSupport.AndroidAgpUpgradeInfo
import com.android.tools.idea.lint.common.LintIdeSupport
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.android.tools.lint.checks.GradleDetector
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.LintMap
import com.android.tools.lint.detector.api.Location
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Tests for [AndroidLintAndroidGradlePluginVersionInspection]. */
@RunsInEdt
class AndroidLintAndroidGradlePluginVersionInspectionTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  @Test
  fun testGetMessageWithIntermediateRecommendedVersion() {
    val fixture = projectRule.fixture
    val buildFile =
      fixture.addFileToProject(
        "build.gradle",
        "plugins { id 'com.android.application' version '7.3.0' }\n",
      )
    val element = buildFile.findElementAt(0)!!

    var stubTargetVersion: AgpVersion? = AgpVersion.parse("8.13.2")
    val support =
      object : LintIdeSupport() {
        override fun computeAgpUpgradeInfo(project: Project): AgpUpgradeInfo? {
          val version = stubTargetVersion ?: return null
          return AndroidAgpUpgradeInfo(project, version)
        }
      }
    ApplicationManager.getApplication()
      .replaceService(LintIdeSupport::class.java, support, projectRule.testRootDisposable)

    val inspection = AndroidLintAndroidGradlePluginVersionInspection()
    val defaultMessage =
      "A newer version of com.android.tools.build:gradle than 7.3.0 is available: 9.3.3"
    val defaultMessageWithParen =
      "A newer version of com.android.tools.build:gradle than 7.3.0 is available: 9.3.3. " +
        "(There is also a newer version of 7.3.𝑥 available, if upgrading to 9.3.3 is difficult: 7.3.1)"

    val buildGradleIncident =
      Incident(GradleDetector.AGP_DEPENDENCY, defaultMessage, Location.create(File("build.gradle")))
        .apply { clientProperties = LintMap().apply { put(GradleDetector.KEY_REVISION, "9.3.3") } }

    // 1. Intermediate upgrade version (8.13.2 < 9.3.3) appends the recommendation:
    assertThat(inspection.getMessage(element, element, buildGradleIncident, defaultMessage))
      .isEqualTo(
        "A newer version of com.android.tools.build:gradle than 7.3.0 is available: 9.3.3. " +
          "Using the AGP Upgrade Assistant to upgrade first to 8.13.2 is recommended."
      )

    // 2. When defaultMessage already ends with ')', ". " is still used:
    assertThat(
        inspection.getMessage(element, element, buildGradleIncident, defaultMessageWithParen)
      )
      .isEqualTo(
        "A newer version of com.android.tools.build:gradle than 7.3.0 is available: 9.3.3. " +
          "(There is also a newer version of 7.3.𝑥 available, if upgrading to 9.3.3 is difficult: 7.3.1). " +
          "Using the AGP Upgrade Assistant to upgrade first to 8.13.2 is recommended."
      )

    // 3. When targetVersion >= KEY_REVISION (9.3.3 == 9.3.3 or 9.4.0 > 9.3.3), defaultMessage is unchanged:
    stubTargetVersion = AgpVersion.parse("9.3.3")
    assertThat(inspection.getMessage(element, element, buildGradleIncident, defaultMessage))
      .isEqualTo(defaultMessage)
    stubTargetVersion = AgpVersion.parse("9.4.0")
    assertThat(inspection.getMessage(element, element, buildGradleIncident, defaultMessage))
      .isEqualTo(defaultMessage)

    // 4. When computeAgpUpgradeInfo returns null, defaultMessage is unchanged:
    stubTargetVersion = null
    assertThat(inspection.getMessage(element, element, buildGradleIncident, defaultMessage))
      .isEqualTo(defaultMessage)

  }
}
