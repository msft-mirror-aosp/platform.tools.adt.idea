/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.lang.multiDexKeep

import com.android.SdkConstants
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.caret
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.psi.PsiClass
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.android.AndroidTestBase
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class MultiDexKeepReferenceTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val myFixture by lazy { projectRule.fixture as JavaCodeInsightTestFixture }
  private val myModule
    get() = projectRule.module

  @Before
  fun setUp() {
    myFixture.testDataPath = AndroidTestBase.getTestDataPath()
    myFixture.copyFileToProject(SdkConstants.FN_ANDROID_MANIFEST_XML, SdkConstants.FN_ANDROID_MANIFEST_XML)
    PsiTestUtil.addLibrary(myModule, "mylib", "", myFixture.testDataPath + "/maven/myjar/myjar-1.0.jar")

    myFixture.addClass(
      """
      package com.example.myapplication;

      public class MainActivity  {
        public static class Inner {}
      }

      """
        .trimIndent()
    )
    myFixture.addClass(
      """
      package com.example.myapplication;

      public class OtherClass  {

      }

      """
        .trimIndent()
    )
  }

  @Test
  fun testResolve() {
    myFixture.configureByText(
      MultiDexKeepFileType.INSTANCE,
      """
        com/example/myapplication/MainActivity.class${caret}
      """
        .trimIndent(),
    )

    assertThat(myFixture.elementAtCaret).isInstanceOf(PsiClass::class.java)
    val psiClass = myFixture.elementAtCaret as PsiClass
    assertThat(psiClass.qualifiedName).isEqualTo("com.example.myapplication.MainActivity")
  }

  @Test
  fun testResolve_innerClass() {
    myFixture.configureByText(
      MultiDexKeepFileType.INSTANCE,
      """
        com/example/myapplication/MainActivity${'$'}Inner.class${caret}
      """
        .trimIndent(),
    )

    assertThat(myFixture.elementAtCaret).isInstanceOf(PsiClass::class.java)
    val psiClass = myFixture.elementAtCaret as PsiClass
    assertThat(psiClass.qualifiedName).isEqualTo("com.example.myapplication.MainActivity.Inner")
  }

  @Test
  fun testCodeCompletionOnEmptyFile() {
    myFixture.configureByText(MultiDexKeepFileType.INSTANCE, "")

    myFixture.completeBasic()

    assertThat(myFixture.lookupElementStrings)
      .containsExactly(
        "com/example/myapplication/MainActivity.class",
        "com/example/myapplication/MainActivity${'$'}Inner.class",
        "com/example/myapplication/OtherClass.class",
        "p1/p2/R.class",
        "com/myjar/MyJarClass.class",
      )
  }

  @Test
  fun testCodeCompletion() {
    myFixture.configureByText(
      MultiDexKeepFileType.INSTANCE,
      """
        com/example/myapplication${caret}
      """
        .trimIndent(),
    )

    myFixture.completeBasic()

    assertThat(myFixture.lookupElementStrings)
      .containsExactly(
        "com/example/myapplication/MainActivity.class",
        "com/example/myapplication/MainActivity${'$'}Inner.class",
        "com/example/myapplication/OtherClass.class",
      )
  }
}
