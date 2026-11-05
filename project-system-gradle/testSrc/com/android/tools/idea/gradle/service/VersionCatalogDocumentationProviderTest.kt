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
package com.android.tools.idea.gradle.service

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth
import com.intellij.codeInsight.navigation.CtrlMouseHandler
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.android.AndroidTestBase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

@RunsInEdt
class VersionCatalogDocumentationProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  @get:Rule val testName = TestName()

  private val fixture by lazy { projectRule.fixture }

  @Before
  fun setUp() {
    fixture.testDataPath = AndroidTestBase.getTestDataPath()
  }

  private val BASE_PATH: String = "catalog/"

  @Test
  fun testLibrariesDeclarationAttributes() {
    doTest("Library group description as <br/>\"<b>androidx.core</b><small>:core-ktx:1.12.0</small>\"")
  }

  @Test
  fun testPluginsDeclarationAttributes() {
    doTest("Plugin identifier \"<b>com.android.application</b><small>:8.4.0</small>\"")
  }

  private fun doTest(expectedDoc: String) {
    val file =
      fixture.copyFileToProject(BASE_PATH + UsefulTestCase.getTestName(testName.methodName, true) + ".versions.toml", "libs.versions.toml")
    fixture.configureFromExistingVirtualFile(file)
    val offset = fixture.editor.caretModel.offset
    val element = fixture.file.findElementAt(offset)
    Truth.assertThat(CtrlMouseHandler.getInfo(element, element)).isEqualTo(expectedDoc)
  }
}
