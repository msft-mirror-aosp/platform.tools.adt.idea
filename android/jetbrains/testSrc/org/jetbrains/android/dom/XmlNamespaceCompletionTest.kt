/*
 * Copyright (C) 2018 The Android Open Source Project
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
package org.jetbrains.android.dom

import com.android.SdkConstants
import com.android.tools.idea.testing.caret
import com.google.common.truth.Truth.assertThat
import org.jetbrains.android.AndroidTestCase

class XmlNamespaceCompletionTest : AndroidTestCase() {

  fun testValuesResources() {
    myFixture.configureFromExistingVirtualFile(
      myFixture
        .addFileToProject(
          "res/values/values.xml",
          // language=xml
          """
          <resources xmlns:foo="${caret}">
            <string name="some_string">Some string</string>
          </resources>
          """
            .trimIndent(),
        )
        .virtualFile
    )

    myFixture.completeBasic()

    assertThat(myFixture.lookupElementStrings)
      .containsExactly(
        SdkConstants.ANDROID_URI,
        SdkConstants.TOOLS_URI,
        SdkConstants.AUTO_URI,
        SdkConstants.XLIFF_URI,
      )
  }

  fun testDrawableResources() {
    myFixture.configureFromExistingVirtualFile(
      myFixture
        .addFileToProject(
          "res/drawable/drawable.xml",
          // language=xml
          """
          <bitmap xmlns:foo="${caret}"/>
          """
            .trimIndent(),
        )
        .virtualFile
    )

    myFixture.completeBasic()

    // Then
    assertThat(myFixture.lookupElementStrings)
      .containsExactly(
        SdkConstants.ANDROID_URI,
        SdkConstants.TOOLS_URI,
        SdkConstants.AUTO_URI,
        SdkConstants.AAPT_URI,
      )
  }

  fun testMipmapResources() {
    myFixture.configureFromExistingVirtualFile(
      myFixture
        .addFileToProject(
          "res/mipmap/mipmap.xml",
          // language=xml
          """
          <adaptive-icon xmlns:foo="${caret}">
          </adaptive-icon>
          """
            .trimIndent(),
        )
        .virtualFile
    )

    myFixture.completeBasic()

    assertThat(myFixture.lookupElementStrings).containsExactly(SdkConstants.ANDROID_URI, SdkConstants.TOOLS_URI)
  }

  fun testLayoutResources() {
    myFixture.configureFromExistingVirtualFile(
      myFixture
        .addFileToProject(
          "res/layout/layout.xml",
          // language=xml
          """
          <android.support.constraint.ConstraintLayout xmlns:foo="${caret}">
          </android.support.constraint.ConstraintLayout>
          """
            .trimIndent(),
        )
        .virtualFile
    )

    myFixture.completeBasic()

    assertThat(myFixture.lookupElementStrings).containsExactly(SdkConstants.ANDROID_URI, SdkConstants.TOOLS_URI, SdkConstants.AUTO_URI)
  }
}
