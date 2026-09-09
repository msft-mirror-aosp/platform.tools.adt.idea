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
package com.android.tools.idea.npw.templateengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.ide.common.sdk.LoadStatus
import com.android.tools.adtui.device.FormFactor
import com.android.tools.idea.npw.templateengine.WizardConstants
import com.android.tools.idea.npw.templateengine.viewmodel.ConfigureProjectViewModel
import com.android.tools.idea.npw.templateengine.viewmodel.ValidationMessage
import com.android.tools.idea.stats.Distribution
import com.android.tools.idea.stats.DistributionService
import com.android.tools.idea.ui.ChooseApiLevelDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import org.jetbrains.android.util.AndroidBundle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalJewelApi::class)
@Composable
@Suppress("UnstableApiUsage")
fun TemplateEngineConfigureProjectStep(viewModel: ConfigureProjectViewModel) {
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 30.dp)) {
    if (viewModel.templateName.isNotEmpty()) {
      Text(text = viewModel.templateName, fontWeight = FontWeight.Bold)
      Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))
    }
    if (viewModel.templateDescription.isNotEmpty()) {
      Text(text = viewModel.templateDescription)
      Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Name", modifier = Modifier.width(WizardConstants.ConfigureStepConstants.LabelColumnWidth))
      TextField(state = viewModel.nameState, modifier = Modifier.width(WizardConstants.ConfigureStepConstants.ValueColumnWidth))
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Package name", modifier = Modifier.width(WizardConstants.ConfigureStepConstants.LabelColumnWidth))
      TextField(state = viewModel.packageNameState, modifier = Modifier.width(WizardConstants.ConfigureStepConstants.ValueColumnWidth))
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Save location", modifier = Modifier.width(WizardConstants.ConfigureStepConstants.LabelColumnWidth))
      TextField(
        state = viewModel.locationState,
        modifier = Modifier.width(WizardConstants.ConfigureStepConstants.ValueColumnWidth),
        trailingIcon = {
          Icon(
            key = AllIconsKeys.Nodes.Folder,
            iconClass = AllIconsKeys::class.java,
            contentDescription = "Browse...",
            modifier =
              Modifier.clickable {
                  val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                  var path: Path? =
                    try {
                      Path.of(viewModel.locationState.text.toString())
                    } catch (e: Exception) {
                      null
                    }
                  while (path != null && !path.exists()) {
                    path = path.parent
                  }
                  val toSelect = path?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
                  val selected = FileChooser.chooseFile(descriptor, null, toSelect)
                  if (selected != null) {
                    viewModel.locationState.setTextAndPlaceCursorAtEnd(selected.path)
                  }
                }
                .padding(start = 8.dp),
          )
        },
      )
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Minimum SDK", modifier = Modifier.width(WizardConstants.ConfigureStepConstants.LabelColumnWidth))

      Dropdown(
        menuContent = {
          viewModel.availableMinSdks.forEach { item ->
            selectableItem(selected = item == viewModel.selectedMinSdk, onClick = { viewModel.updateSelectedMinSdk(item) }) {
              Text(item.label)
            }
          }
        },
        modifier = Modifier.width(WizardConstants.ConfigureStepConstants.ValueColumnWidth),
      ) {
        Text(viewModel.selectedMinSdk.label)
      }
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))

    if (viewModel.formFactor == FormFactor.MOBILE) {
      ApiCompatibilityInfo(
        minApiLevel = viewModel.selectedMinSdk.minApiLevel,
        onApiLevelSelected = { viewModel.updateSelectedMinSdk(it) },
      )
    }

    viewModel.validationMessage?.let { validation ->
      when (validation) {
        is ValidationMessage.Warning -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              key = AllIconsKeys.General.Warning,
              iconClass = AllIconsKeys::class.java,
              contentDescription = "Warning",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = validation.message, color = JewelTheme.globalColors.text.warning)
          }
        }
        is ValidationMessage.Error -> {
          Text(text = validation.message, color = JewelTheme.globalColors.text.error)
        }
      }
    }
  }
}

private data class DistributionLoadState(
  val status: LoadStatus,
  val distributions: List<Distribution>?,
)

@Composable
private fun ApiCompatibilityInfo(
  minApiLevel: Int,
  onApiLevelSelected: (Int) -> Unit,
) {
  val loadState by
    produceState(initialValue = DistributionLoadState(LoadStatus.LOADING, DistributionService.getInstance().distributions)) {
      DistributionService.getInstance()
        .refresh(
          {
            ApplicationManager.getApplication()
              .invokeLater(
                { value = DistributionLoadState(LoadStatus.LOADED, DistributionService.getInstance().distributions) },
                ModalityState.any(),
              )
          },
          {
            ApplicationManager.getApplication()
              .invokeLater(
                { value = DistributionLoadState(LoadStatus.FAILED, DistributionService.getInstance().distributions) },
                ModalityState.any(),
              )
          },
          false,
        )
    }

  if (loadState.status != LoadStatus.LOADED) {
    Row(
      modifier = Modifier.padding(start = WizardConstants.ConfigureStepConstants.LabelColumnWidth),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (loadState.status == LoadStatus.LOADING) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = AndroidBundle.message("android.wizard.module.help.refreshing"),
          color = JewelTheme.globalColors.text.info,
        )
      } else if (loadState.status == LoadStatus.FAILED) {
        Text(
          text = AndroidBundle.message("android.wizard.project.loading.stats.fail"),
          color = JewelTheme.globalColors.text.info,
        )
      }
    }
    Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))
  }

  val percentage = DistributionService.getSupportedDistributionForApiLevel(minApiLevel, loadState.distributions) * 100.0
  val compatibilityText =
    when {
      percentage < 0.0 -> ""
      percentage < 1.0 -> AndroidBundle.message("android.wizard.project.help.api.distribution.less.than.one")
      else -> {
        val percentageStr = String.format(Locale.US, if (percentage >= 10.0) "%.3g%%" else "%.2g%%", percentage)
        AndroidBundle.message("android.wizard.project.help.api.distribution.approximate", percentageStr)
      }
    }

  Row(
    modifier = Modifier.padding(start = WizardConstants.ConfigureStepConstants.LabelColumnWidth),
    verticalAlignment = Alignment.Top,
  ) {
    if (compatibilityText.isNotEmpty()) {
      Icon(
        key = AllIconsKeys.General.BalloonInformation,
        contentDescription = "Information",
        modifier = Modifier.size(16.dp).padding(top = 2.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
    }
    Column {
      if (compatibilityText.isNotEmpty()) {
        Text(text = compatibilityText)
        Spacer(modifier = Modifier.height(4.dp))
      }
      Link(
        text = AndroidBundle.message("android.wizard.module.help.choose"),
        onClick = {
          val dialog = ChooseApiLevelDialog(null, minApiLevel)
          if (dialog.showAndGet()) {
            onApiLevelSelected(dialog.selectedApiLevel)
          }
        },
      )
    }
  }
  Spacer(modifier = Modifier.height(WizardConstants.ConfigureStepConstants.RowSpace))
}
