/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.testartifacts.instrumented.testsuite.actions

import com.android.annotations.concurrency.UiThread
import com.android.tools.idea.testartifacts.AndroidTestBundle
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultsTreeNode
import com.android.tools.idea.testartifacts.instrumented.testsuite.export.exportAndroidTestMatrixResultXmlFile
import com.android.tools.idea.testartifacts.instrumented.testsuite.export.isDisallowedNetworkPath
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.google.common.annotations.VisibleForTesting
import com.intellij.CommonBundle
import com.intellij.execution.ExecutionBundle
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.export.ExportTestResultsConfiguration
import com.intellij.execution.testframework.export.ExportTestResultsDialog
import com.intellij.icons.AllIcons
import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PathUtil
import java.io.File
import java.time.Duration

private val LOG = Logger.getInstance(ExportAndroidTestResultsAction::class.java)

/**
 * Exports Android instrumentation test results into XML or HTML.
 *
 * This class is an alternative implementation of [com.intellij.execution.testframework.export.ExportTestResultsAction]. This class exports
 * XML file which is compatible with the original ExportTestResultsAction with additional information for Android Test Matrix view.
 */
class ExportAndroidTestResultsAction :
  DumbAwareAction(
    ActionsBundle.message("action.ExportTestResults.text"),
    ActionsBundle.message("action.ExportTestResults.description"),
    AllIcons.ToolbarDecorator.Export,
  ) {

  var executionDuration: Duration? = null
  var devices: List<AndroidDevice>? = null
  var rootResultsNode: AndroidTestResultsTreeNode? = null
  var runConfiguration: RunConfiguration? = null
  var toolWindowId: String? = null

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  @UiThread
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled =
      e.project != null &&
        devices?.isEmpty() == false &&
        rootResultsNode?.results?.getTestResultSummary()?.isTerminalState == true &&
        runConfiguration != null &&
        executionDuration != null
  }

  @UiThread
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val rootResultsNode = rootResultsNode ?: return
    val runConfiguration = runConfiguration ?: return
    val executionDuration = executionDuration ?: return
    val devices = devices ?: return
    val exportConfig = ExportTestResultsConfiguration.getInstance(project)
    sanitizeExportConfiguration(exportConfig, project.basePath)
    val defaultFileName =
      AndroidTestBundle.message("export.test.results.filename", PathUtil.suggestFileName(runConfiguration.name)) +
        "." +
        exportConfig.exportFormat.defaultExtension
    val exportFile = showExportTestResultsDialog(exportConfig, project, defaultFileName) ?: return
    exportAndroidTestMatrixResultXmlFile(
      project,
      toolWindowId,
      exportConfig,
      exportFile,
      executionDuration,
      rootResultsNode,
      runConfiguration,
      devices,
    )
  }

  /**
   * Clears disallowed network/UNC paths pre-seeded in `.idea/workspace.xml` before opening [ExportTestResultsDialog], preventing the
   * platform dialog constructor from synchronously probing untrusted remote SMB shares on the EDT.
   *
   * For projects opened from a UNC share, paths strictly inside [projectBasePath] are intentionally kept so the default project export
   * paths continue to work; [ExportTestResultsDialog] and [File.exists] still access that share on the UI thread, which is acceptable
   * because the IDE already reads from it for the project itself.
   *
   * Known limitation (b/511027523): if the user interactively types or pastes a network path into the custom template field while the
   * dialog is open, the platform's [com.intellij.execution.testframework.export.ExportTestResultsForm.validate] still invokes [File.isFile]
   * synchronously on the UI thread.
   */
  @VisibleForTesting
  internal fun sanitizeExportConfiguration(exportConfig: ExportTestResultsConfiguration, projectBasePath: String?) {
    if (isDisallowedNetworkPath(exportConfig.userTemplatePath, projectBasePath)) {
      LOG.warn("Clearing network custom XSL template path from ExportTestResultsConfiguration: ${exportConfig.userTemplatePath}")
      exportConfig.userTemplatePath = ""
      if (exportConfig.exportFormat == ExportTestResultsConfiguration.ExportFormat.UserTemplate) {
        exportConfig.exportFormat = ExportTestResultsConfiguration.ExportFormat.BundledTemplate
      }
    }
    if (isDisallowedNetworkPath(exportConfig.outputFolder, projectBasePath)) {
      LOG.warn("Clearing network output folder from ExportTestResultsConfiguration: ${exportConfig.outputFolder}")
      exportConfig.outputFolder = ""
    }
  }

  internal sealed interface OutputFileValidation {
    data class Valid(val file: File) : OutputFileValidation

    data class Invalid(val errorMessage: String) : OutputFileValidation
  }

  /**
   * Validates the dialog inputs and resolves the target output [File]. As a side effect, clears rejected network `userTemplatePath` or
   * `outputFolder` values on [exportConfig] so reopening [ExportTestResultsDialog] on retry does not probe them on the UI thread.
   */
  @UiThread
  @VisibleForTesting
  internal fun validateAndResolveOutputFile(
    exportConfig: ExportTestResultsConfiguration,
    project: Project,
    rawFileName: String,
  ): OutputFileValidation {
    val basePath = project.basePath
    val fileName = rawFileName.trim()
    if (fileName.isEmpty()) {
      return OutputFileValidation.Invalid(ExecutionBundle.message("export.test.results.output.filename.empty"))
    }
    if (
      exportConfig.exportFormat == ExportTestResultsConfiguration.ExportFormat.UserTemplate &&
        isDisallowedNetworkPath(exportConfig.userTemplatePath, basePath)
    ) {
      val rejectedTemplatePath = exportConfig.userTemplatePath
      exportConfig.userTemplatePath = ""
      return OutputFileValidation.Invalid(AndroidTestBundle.message("export.test.results.network.path.not.supported", rejectedTemplatePath))
    }
    if (isDisallowedNetworkPath(fileName)) {
      return OutputFileValidation.Invalid(AndroidTestBundle.message("export.test.results.network.path.not.supported", fileName))
    }
    val file = getOutputFile(exportConfig, project, fileName)
    // Check raw outputFolder alongside file.path because File normalization on POSIX collapses leading "//" into "/".
    val rejectedOutputPath = listOf(exportConfig.outputFolder, file.path).firstOrNull { isDisallowedNetworkPath(it, basePath) }
    if (rejectedOutputPath != null) {
      if (isDisallowedNetworkPath(exportConfig.outputFolder, basePath)) {
        exportConfig.outputFolder = ""
      }
      return OutputFileValidation.Invalid(AndroidTestBundle.message("export.test.results.network.path.not.supported", rejectedOutputPath))
    }
    return OutputFileValidation.Valid(file)
  }

  @UiThread
  private fun showExportTestResultsDialog(exportConfig: ExportTestResultsConfiguration, project: Project, defaultFileName: String): File? {
    do {
      val dialog = ExportTestResultsDialog(project, exportConfig, defaultFileName)
      if (!dialog.showAndGet()) {
        return null
      }
      val file =
        when (val validation = validateAndResolveOutputFile(exportConfig, project, dialog.fileName)) {
          is OutputFileValidation.Valid -> validation.file
          is OutputFileValidation.Invalid -> {
            Messages.showErrorDialog(project, validation.errorMessage, CommonBundle.getErrorTitle())
            continue
          }
        }
      if (!file.exists()) {
        return file
      }
      if (
        Messages.showOkCancelDialog(
          project,
          AndroidTestBundle.message("export.test.results.file.exists.message", file.name),
          AndroidTestBundle.message("export.test.results.file.exists.title"),
          AndroidTestBundle.message("export.test.results.overwrite.button.text"),
          CommonBundle.getCancelButtonText(),
          Messages.getQuestionIcon(),
        ) == Messages.OK
      ) {
        return file
      }
    } while (true)
  }

  @UiThread
  private fun getOutputFile(exportConfig: ExportTestResultsConfiguration, project: Project, filename: String): File {
    val outputFolder: File
    val outputFolderPath = exportConfig.outputFolder
    outputFolder =
      if (!StringUtil.isEmptyOrSpaces(outputFolderPath)) {
        if (FileUtil.isAbsolute(outputFolderPath)) {
          File(outputFolderPath)
        } else {
          File(File(project.basePath), exportConfig.outputFolder)
        }
      } else {
        File(project.basePath)
      }
    return File(outputFolder, filename)
  }
}
