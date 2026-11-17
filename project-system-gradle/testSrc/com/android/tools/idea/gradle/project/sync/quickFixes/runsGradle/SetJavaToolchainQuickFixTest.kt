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
package com.android.tools.idea.gradle.project.sync.quickFixes.runsGradle

import com.android.tools.idea.gradle.project.sync.quickFixes.SetJavaToolchainQuickFix
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import org.junit.Rule
import org.junit.Test

class SetJavaToolchainQuickFixTest {

  @get:Rule val projectRule = AndroidGradleProjectRule()

  @Test
  fun testRunQuickFix() {
    projectRule.loadProject(TestProjectPaths.SIMPLE_APPLICATION)

    val project = projectRule.project
    val appBuildGradle = project.baseDir.findChild("app")!!.findChild("build.gradle")!!
    val settingsGradle = project.baseDir.findChild("settings.gradle")!!

    ApplicationManager.getApplication().invokeAndWait {
      projectRule.fixture.openFileInEditor(settingsGradle)
      projectRule.fixture.openFileInEditor(appBuildGradle)

      val appDocument = FileDocumentManager.getInstance().getDocument(appBuildGradle)!!
      val settingsDocument = FileDocumentManager.getInstance().getDocument(settingsGradle)!!
      WriteCommandAction.runWriteCommandAction(project) {
        appDocument.insertString(appDocument.textLength, "\n// Uncommitted app edit\n")
        settingsDocument.insertString(settingsDocument.textLength, "\n// Uncommitted settings edit\n")
      }
      assertThat(PsiDocumentManager.getInstance(project).isCommitted(appDocument)).isFalse()
      assertThat(PsiDocumentManager.getInstance(project).isCommitted(settingsDocument)).isFalse()

      val future = SetJavaToolchainQuickFix(17, listOf(":app")).runQuickFix(project, DataContext.EMPTY_CONTEXT)
      assertThat(future.isDone).isTrue()
      assertThat(future.get()).isNull()
    }

    val updatedAppBuildGradle = VfsUtil.loadText(appBuildGradle)
    assertThat(updatedAppBuildGradle).contains("languageVersion = JavaLanguageVersion.of(17)")
    assertThat(updatedAppBuildGradle).contains("// Uncommitted app edit")

    val updatedSettingsGradle = VfsUtil.loadText(settingsGradle)
    assertThat(updatedSettingsGradle).contains("id 'org.gradle.toolchains.foojay-resolver-convention'")
    assertThat(updatedSettingsGradle).contains("// Uncommitted settings edit")
  }
}
