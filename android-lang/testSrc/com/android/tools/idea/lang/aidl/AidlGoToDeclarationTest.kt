/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.lang.aidl

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.moveCaret
import com.android.tools.idea.testing.onEdt
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiClass
import com.intellij.testFramework.RunsInEdt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class AidlGoToDeclarationTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val myFixture by lazy { projectRule.fixture }

  @Test
  fun testGoToDeclaration() {
    myFixture.configureByText(
      AidlFileType.INSTANCE,
      """
      package test.pkg;
      interface IMyAidlInterface {
          void otherTypes(int anInt, long aLong, boolean aBoolean, float aFloat,
                  @nullable double aDouble, String aString, inout List<String> list, String test,
                  ParcelFileDescriptor descriptor);
      }
      """
        .trimIndent(),
    )

    checkGoToDeclaration("Li|st<String>", "List in java/util/List.class")
    checkGoToDeclaration("List<Str|ing>", "String in java/lang/String.class")
    checkGoToDeclaration("ParcelFileDescrip|tor descriptor", "ParcelFileDescriptor in android/os/ParcelFileDescriptor.class")
  }

  private fun checkGoToDeclaration(caret: String, expected: String) {
    myFixture.moveCaret(caret)
    val elements = GotoDeclarationAction.findAllTargetElements(myFixture.project, myFixture.editor, myFixture.caretOffset)
    val actual =
      elements.joinToString("\n") { (it as PsiClass).name + " in " + it.containingFile.virtualFile.path.substringAfterLast("jar!/") }
    assertEquals(expected, actual)
  }
}
