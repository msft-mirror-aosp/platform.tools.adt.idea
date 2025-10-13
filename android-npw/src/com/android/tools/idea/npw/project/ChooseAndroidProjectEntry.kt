/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.npw.project

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.android.tools.idea.gemini.GeminiPluginApi
import com.android.tools.idea.npw.model.NewProjectModel
import com.android.tools.idea.npw.model.NewProjectModuleModel
import com.android.tools.idea.npw.template.TemplateResolver
import com.android.tools.idea.wizard.template.Template
import com.android.tools.idea.wizard.template.Template.NoActivity
import com.android.tools.idea.wizard.template.WizardUiContext
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

private val extensionList = listOf("jpg", "jpeg", "png")

abstract class ChooseAndroidProjectEntry() {
  @Composable abstract fun AndroidProjectListEntry(isSelected: Boolean, isFocused: Boolean)

  @Composable abstract fun AndroidProjectEntryDetails()

  abstract val canGoForward: State<Boolean>

  abstract fun onProceeding(
    newProjectModuleModel: NewProjectModuleModel,
    model: NewProjectModel,
  ): Unit
}

class FormFactorProjectEntry(
  val formFactorTitle: String,
  val templates: List<Template>,
  selectedTemplate: Template?,
) : ChooseAndroidProjectEntry() {
  var selectedTemplate by mutableStateOf(selectedTemplate)

  @Composable
  override fun AndroidProjectListEntry(isSelected: Boolean, isFocused: Boolean) {
    ListCell(formFactorTitle, null, isSelected, isFocused)
  }

  @Composable
  override fun AndroidProjectEntryDetails() {
    TemplateGrid(
      templates = templates,
      selectedTemplate = selectedTemplate,
      onTemplateClick = { template -> selectedTemplate = template },
    )
  }

  override val canGoForward = derivedStateOf { selectedTemplate != null }

  override fun onProceeding(newProjectModuleModel: NewProjectModuleModel, model: NewProjectModel) {
    selectedTemplate?.let { template ->
      newProjectModuleModel.formFactor.set(template.formFactor)
      newProjectModuleModel.newRenderTemplate.setNullableValue(template)
      val hasExtraDetailStep = template.uiContexts.contains(WizardUiContext.NewProjectExtraDetail)
      newProjectModuleModel.extraRenderTemplateModel.newTemplate =
        if (hasExtraDetailStep) template else NoActivity
    }
  }
}

class GeminiProjectEntry() : ChooseAndroidProjectEntry() {
  val textFieldState = TextFieldState()
  val attachedImages = SnapshotStateList<VirtualFile>()

  @Composable
  override fun AndroidProjectListEntry(isSelected: Boolean, isFocused: Boolean) {
    GeminiListCell(isSelected, isFocused)
  }

  @Composable
  override fun AndroidProjectEntryDetails() {
    GeminiRightPanel(
      textFieldState = textFieldState,
      geminiPluginAvailable = GeminiPluginApi.getInstance().isAvailable(),
      hasContextSharing = true,
      attachedImages = attachedImages,
      onAttachImage = {
        val selectedFiles =
          FileChooser.chooseFiles(
            FileChooserDescriptor(
                /* chooseFiles = */ true,
                /* chooseFolders = */ false,
                /* chooseJars = */ false,
                /* chooseJarsAsFiles = */ false,
                /* chooseJarContents = */ false,
                /* chooseMultiple = */ true,
              )
              .withExtensionFilter("Image files", *extensionList.toTypedArray()),
            /* project = */ null,
            /* toSelect = */ null,
          )

        selectedFiles.forEach { selectedFile ->
          // Show an error dialog if the file exceeds the maximum allowed size
          if (selectedFile.length > 25 * 1024 * 1024) {
            Messages.showErrorDialog(
              "Attachments should not exceed the maximum file size (25 MB).",
              "Maximum File Size Exceeded",
            )
          } else {
            attachedImages += selectedFile
          }
        }
      },
    )
  }

  override val canGoForward = derivedStateOf { textFieldState.text.isNotEmpty() }

  override fun onProceeding(newProjectModuleModel: NewProjectModuleModel, model: NewProjectModel) {
    val templateToUse =
      TemplateResolver.getAllTemplates().firstOrNull { it.name == "Architecture Sample" }
    newProjectModuleModel.newRenderTemplate.setNullableValue(templateToUse ?: NoActivity)
    model.prompt.set(textFieldState.text.toString())
    model.imageAttachments.set(attachedImages)
  }
}
