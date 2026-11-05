/*
 * Copyright (C) 2024 The Android Open Source Project
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
package org.jetbrains.kotlin.android.spellchecker

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.analysis.DefaultHighlightingSettingProvider
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.spellchecker.SpellCheckerSeveritiesProvider
import com.intellij.testFramework.ExtensionTestUtil.maskExtensions
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import org.jetbrains.android.AndroidTestBase.unmaskKotlinHighlightVisitor
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class AndroidGradleKtsSpellcheckingStrategyTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val fixture by lazy { projectRule.fixture }

  @Before
  fun setUp() {
    (fixture as CodeInsightTestFixtureImpl).canChangeDocumentDuringHighlighting(true)
    maskExtensions(DefaultHighlightingSettingProvider.EP_NAME, listOf(), fixture.projectDisposable)
    maskExtensions(ProblemHighlightFilter.EP_NAME, listOf(), fixture.projectDisposable)
    unmaskKotlinHighlightVisitor(fixture)
  }

  @Test
  fun testNoTypoInDependencyCallExpression() {
    fixture.enableInspections(setOf(GrazieSpellCheckingInspection::class.java))
    val virtualFile =
      fixture
        .addFileToProject(
          "build.gradle.kts",
          // language=kotlin
          """
          dependencies {
            implementation("com.example:xyzy:1.0")
          }
          """
            .trimIndent(),
        )
        .virtualFile
    fixture.configureFromExistingVirtualFile(virtualFile)
    val typos = fixture.doHighlighting(SpellCheckerSeveritiesProvider.TYPO)
    Truth.assertThat(typos).isEmpty()
  }

  @Test
  fun testTypoInPrintCallExpression() {
    fixture.enableInspections(setOf(GrazieSpellCheckingInspection::class.java))
    val virtualFile =
      fixture
        .addFileToProject(
          "build.gradle.kts",
          // language=kotlin
          """
          dependencies {
            print("com.example:xyzy:1.0")
          }
          """
            .trimIndent(),
        )
        .virtualFile
    fixture.configureFromExistingVirtualFile(virtualFile)
    val typos = fixture.doHighlighting(SpellCheckerSeveritiesProvider.TYPO)
    Truth.assertThat(typos).hasSize(1)
    Truth.assertThat(typos[0].description).isEqualTo("Typo: In word 'xyzy'")
  }
}
