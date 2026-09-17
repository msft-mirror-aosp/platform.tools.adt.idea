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

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.facet.FacetManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.runInInitMode
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Rule
import org.junit.Test

/** Test for [GradleWrapperInspectionSuppressor]. */
@RunsInEdt
class GradleWrapperInspectionSuppressorTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  @Test
  fun testSuppressionInAndroidProject() {
    val fixture = projectRule.fixture
    val module = projectRule.module
    val suppressor = GradleWrapperInspectionSuppressor()

    val wrapperFile =
      fixture.addFileToProject(
        "gradle/wrapper/gradle-wrapper.properties",
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n",
      )
    val otherPropertiesFile =
      fixture.addFileToProject(
        "foo.properties",
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n",
      )

    val wrapperProperty = wrapperFile.findElementAt(wrapperFile.text.indexOf("distributionUrl"))!!
    val otherProperty = otherPropertiesFile.findElementAt(otherPropertiesFile.text.indexOf("distributionUrl"))!!

    // In an Android project, LatestMinorVersion should be suppressed on gradle-wrapper.properties.
    assertThat(suppressor.isSuppressedFor(wrapperProperty, "LatestMinorVersion")).isTrue()

    // Other inspection IDs should not be suppressed.
    assertThat(suppressor.isSuppressedFor(wrapperProperty, "OtherInspection")).isFalse()

    // Non gradle-wrapper.properties files should not be suppressed.
    assertThat(suppressor.isSuppressedFor(otherProperty, "LatestMinorVersion")).isFalse()

    // Remove the Android facet to simulate a non-Android project.
    runWriteActionAndWait {
      val facetModel = FacetManager.getInstance(module).createModifiableModel()
      facetModel.removeFacet(AndroidFacet.getInstance(module)!!)
      facetModel.commit()
    }

    // Now it should no longer be suppressed on gradle-wrapper.properties.
    assertThat(suppressor.isSuppressedFor(wrapperProperty, "LatestMinorVersion")).isFalse()
  }

  @Test
  fun testHighlightingSuppressesDuplicateWarning() {
    val fixture = projectRule.fixture
    val module = projectRule.module

    val wrapperFile =
      fixture.addFileToProject(
        "gradle/wrapper/gradle-wrapper.properties",
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n",
      )
    fixture.configureFromExistingVirtualFile(wrapperFile.virtualFile)

    val inspectionProfile = InspectionProfileManager.getInstance().currentProfile
    val latestMinorVersionInspection = runInInitMode {
      inspectionProfile.getUnwrappedTool("LatestMinorVersion", wrapperFile)
    }
    checkNotNull(latestMinorVersionInspection) { "LatestMinorVersion inspection should be available" }
    fixture.enableInspections(latestMinorVersionInspection, AndroidLintAndroidGradlePluginVersionInspection())

    // In an Android project, LatestMinorVersion is suppressed so the redundant
    // "A newer minor version of Gradle is available" warning is not reported.
    val warningsInAndroid = fixture.doHighlighting(HighlightSeverity.WARNING)
    val toolIdsInAndroid = warningsInAndroid.mapNotNull { it.inspectionToolId }
    val descriptionsInAndroid = warningsInAndroid.mapNotNull { it.description }
    assertThat(toolIdsInAndroid).doesNotContain("LatestMinorVersion")
    assertThat(descriptionsInAndroid).doesNotContain("A newer minor version of Gradle is available")

    // Remove Android facet to simulate a non-Android project.
    runWriteActionAndWait {
      val facetModel = FacetManager.getInstance(module).createModifiableModel()
      facetModel.removeFacet(AndroidFacet.getInstance(module)!!)
      facetModel.commit()
    }

    // Outside an Android project, LatestMinorVersion is NOT suppressed and reports the warning.
    val warningsOutsideAndroid = fixture.doHighlighting(HighlightSeverity.WARNING)
    val toolIdsOutsideAndroid = warningsOutsideAndroid.mapNotNull { it.inspectionToolId }
    val descriptionsOutsideAndroid = warningsOutsideAndroid.mapNotNull { it.description }
    assertThat(toolIdsOutsideAndroid).contains("LatestMinorVersion")
    assertThat(descriptionsOutsideAndroid).contains("A newer minor version of Gradle is available")
  }
}
