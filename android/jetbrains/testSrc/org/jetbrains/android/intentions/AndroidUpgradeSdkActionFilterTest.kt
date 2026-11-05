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
package org.jetbrains.android.intentions

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.moveCaret
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.daemon.impl.IntentionActionFilter
import com.intellij.java.JavaBundle
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class AndroidUpgradeSdkActionFilterTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val myFixture by lazy { projectRule.fixture as JavaCodeInsightTestFixture }
  private val testRootDisposable by lazy { projectRule.testRootDisposable }

  // AndroidTestCase configured the module with Java 8; the rule defaults to the highest language level.
  private val languageLevel = LanguageLevel.JDK_1_8

  @Before
  fun setUp() {
    IdeaTestUtil.setModuleLanguageLevel(projectRule.module, languageLevel, testRootDisposable)
  }

  @Test
  fun testNoIntention() {
    // add enhanced switch block to trigger the intention of upgrading to jdk 14+
    assertThat(languageLevel).isLessThan(LanguageLevel.JDK_14)
    val psiClass =
      myFixture.addClass(
        """
        package p1.p2;

        class MyClass {
          void f() {
            String myField = "foo";
            switch (myField) {
                case "bar" -> System.out.println();
            }
          }
        }
        """
          .trimIndent()
      )
    myFixture.openFileInEditor(psiClass.containingFile.virtualFile)
    myFixture.moveCaret("bar|")
    assertThat(myFixture.availableIntentions.filter { it.familyName == JavaBundle.message("intention.family.name.upgrade.jdk") }).isEmpty()
    ExtensionTestUtil.maskExtensions(IntentionActionFilter.EXTENSION_POINT_NAME, listOf(), testRootDisposable)
    assertThat(myFixture.availableIntentions.filter { it.familyName == JavaBundle.message("intention.family.name.upgrade.jdk") })
      .isNotEmpty()
  }
}
