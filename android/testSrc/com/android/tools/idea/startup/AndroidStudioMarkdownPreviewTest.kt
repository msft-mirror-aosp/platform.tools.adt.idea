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
package com.android.tools.idea.startup

import com.android.tools.idea.IdeInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.intellij.plugins.markdown.ui.preview.MarkdownEditorWithPreview
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

/** Ensures Markdown files can be opened with a working preview renderer in Android Studio (b/588514732). */
class AndroidStudioMarkdownPreviewTest {
  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()

  @get:Rule
  val ruleChain = RuleChain(projectRule, EdtRule(), disposableRule)

  @Test
  @RunsInEdt
  fun testMarkdownPreviewProviderIsAvailable() {
    Assume.assumeTrue(IdeInfo.getInstance().isAndroidStudio)
    assertWithMessage("Expected at least one available Markdown preview panel provider in Android Studio")
      .that(MarkdownHtmlPanelProvider.hasAvailableProviders())
      .isTrue()

    val providers = MarkdownHtmlPanelProvider.getAvailableProviders()
    assertThat(providers).isNotEmpty()

    val file = LightVirtualFile("test.md", "# Heading\n\nSome **bold** text.")
    val panel = providers.first().createHtmlPanel(projectRule.project, file)
    Disposer.register(disposableRule.disposable, panel)
    assertThat(panel.component).isNotNull()
  }

  @Test
  @RunsInEdt
  fun testMarkdownFileOpensWithPreviewLayout() {
    Assume.assumeTrue(IdeInfo.getInstance().isAndroidStudio)
    val project = projectRule.project
    val file = LightVirtualFile("test.md", "# Heading\n\nSome **bold** text.")

    val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
    val splitProvider = providers.filterIsInstance<TextEditorWithPreviewProvider>().firstOrNull()
    assertWithMessage("Expected a split preview FileEditorProvider for .md files; found: ${providers.map { it.editorTypeId }}")
      .that(splitProvider)
      .isNotNull()

    val editor = splitProvider!!.createEditor(project, file)
    Disposer.register(disposableRule.disposable, editor)
    assertThat(editor).isInstanceOf(MarkdownEditorWithPreview::class.java)
    val markdownEditor = editor as MarkdownEditorWithPreview
    assertThat(markdownEditor.getLayout()).isNotEqualTo(TextEditorWithPreview.Layout.SHOW_EDITOR)
  }
}
