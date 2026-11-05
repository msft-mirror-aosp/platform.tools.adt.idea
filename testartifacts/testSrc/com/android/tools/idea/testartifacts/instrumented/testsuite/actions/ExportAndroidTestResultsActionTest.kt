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
package com.android.tools.idea.testartifacts.instrumented.testsuite.actions

import com.android.tools.idea.testartifacts.instrumented.testsuite.actions.ExportAndroidTestResultsAction.OutputFileValidation
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.testframework.export.ExportTestResultsConfiguration
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
@RunsInEdt
class ExportAndroidTestResultsActionTest {
  @get:Rule val rules: RuleChain = RuleChain.outerRule(ApplicationRule()).around(EdtRule())

  private val localProjectDir: File = File(System.getProperty("java.io.tmpdir"), "studioTestProject").absoluteFile
  private val localReportsDir: File = File(System.getProperty("java.io.tmpdir"), "studioTestReports").absoluteFile

  @Test
  fun sanitizeExportConfiguration_clearsNetworkPathsAndResetsUserTemplateFormat() {
    val action = ExportAndroidTestResultsAction()
    val config =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = "\\\\example.invalid\\share\\report-style.xsl"
        outputFolder = "//example.invalid/share/out"
      }

    action.sanitizeExportConfiguration(config, localProjectDir.path)

    assertThat(config.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.BundledTemplate)
    assertThat(config.userTemplatePath).isEmpty()
    assertThat(config.outputFolder).isEmpty()
  }

  @Test
  fun sanitizeExportConfiguration_preservesXmlFormatWhenClearingNetworkTemplatePath() {
    val action = ExportAndroidTestResultsAction()
    val config =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        userTemplatePath = "\\\\example.invalid\\share\\report-style.xsl"
        outputFolder = localReportsDir.path
      }

    action.sanitizeExportConfiguration(config, localProjectDir.path)

    assertThat(config.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.Xml)
    assertThat(config.userTemplatePath).isEmpty()
    assertThat(config.outputFolder).isEqualTo(localReportsDir.path)
  }

  @Test
  fun sanitizeExportConfiguration_preservesLocalWslAndInProjectUncPaths() {
    val action = ExportAndroidTestResultsAction()
    val localTemplatePath = File(localProjectDir, "docs/report-style.xsl").path
    val localOutputPath = File(localProjectDir, "reports").path
    val localConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = localTemplatePath
        outputFolder = localOutputPath
      }

    action.sanitizeExportConfiguration(localConfig, localProjectDir.path)

    assertThat(localConfig.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.UserTemplate)
    assertThat(localConfig.userTemplatePath).isEqualTo(localTemplatePath)
    assertThat(localConfig.outputFolder).isEqualTo(localOutputPath)

    val wslConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = "\\\\wsl$\\Ubuntu\\home\\dev\\report-style.xsl"
        outputFolder = "\\\\wsl.localhost\\Ubuntu\\home\\dev\\reports"
      }

    action.sanitizeExportConfiguration(wslConfig, localProjectDir.path)

    assertThat(wslConfig.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.UserTemplate)
    assertThat(wslConfig.userTemplatePath).isEqualTo("\\\\wsl$\\Ubuntu\\home\\dev\\report-style.xsl")
    assertThat(wslConfig.outputFolder).isEqualTo("\\\\wsl.localhost\\Ubuntu\\home\\dev\\reports")

    val uncBasePath = "//corp.example.invalid/team/project"
    val inProjectUncConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = "\\\\corp.example.invalid\\team\\project\\tools\\report-style.xsl"
        outputFolder = "\\\\corp.example.invalid\\team\\project\\reports"
      }

    action.sanitizeExportConfiguration(inProjectUncConfig, uncBasePath)

    assertThat(inProjectUncConfig.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.UserTemplate)
    assertThat(inProjectUncConfig.userTemplatePath).isEqualTo("\\\\corp.example.invalid\\team\\project\\tools\\report-style.xsl")
    assertThat(inProjectUncConfig.outputFolder).isEqualTo("\\\\corp.example.invalid\\team\\project\\reports")

    val externalUncOnUncProjectConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = "\\\\other.example.invalid\\share\\report-style.xsl"
        outputFolder = "\\\\corp.example.invalid\\team\\project-other\\reports"
      }

    action.sanitizeExportConfiguration(externalUncOnUncProjectConfig, uncBasePath)

    assertThat(externalUncOnUncProjectConfig.exportFormat).isEqualTo(ExportTestResultsConfiguration.ExportFormat.BundledTemplate)
    assertThat(externalUncOnUncProjectConfig.userTemplatePath).isEmpty()
    assertThat(externalUncOnUncProjectConfig.outputFolder).isEmpty()
  }

  @Test
  fun validateAndResolveOutputFile_rejectsBlankFileName() {
    val action = ExportAndroidTestResultsAction()
    val project = mockProject(localProjectDir.path)
    val config = ExportTestResultsConfiguration().apply { exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml }

    val result = action.validateAndResolveOutputFile(config, project, "   ")

    assertThat(result).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((result as OutputFileValidation.Invalid).errorMessage).isNotEmpty()
  }

  @Test
  fun validateAndResolveOutputFile_rejectsAndClearsNetworkUserTemplateOnlyInUserTemplateMode() {
    val action = ExportAndroidTestResultsAction()
    val project = mockProject(localProjectDir.path)
    val userTemplateConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.UserTemplate
        userTemplatePath = "\\\\example.invalid\\share\\report-style.xsl"
        outputFolder = localReportsDir.path
      }

    val rejected = action.validateAndResolveOutputFile(userTemplateConfig, project, "report.html")

    assertThat(rejected).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((rejected as OutputFileValidation.Invalid).errorMessage).contains("Network paths are not supported")
    assertThat(userTemplateConfig.userTemplatePath).isEmpty()

    val xmlConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        userTemplatePath = "\\\\example.invalid\\share\\unused.xsl"
        outputFolder = localReportsDir.path
      }

    val allowedForXml = action.validateAndResolveOutputFile(xmlConfig, project, "report.xml")

    assertThat(allowedForXml).isEqualTo(OutputFileValidation.Valid(File(localReportsDir, "report.xml")))
  }

  @Test
  fun validateAndResolveOutputFile_rejectsExternalNetworkPathsAndAllowsInProjectUncPaths() {
    val action = ExportAndroidTestResultsAction()
    val localProject = mockProject(localProjectDir.path)

    val networkFolderConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        outputFolder = "//example.invalid/share/out"
      }
    val folderRejected = action.validateAndResolveOutputFile(networkFolderConfig, localProject, "report.xml")
    assertThat(folderRejected).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((folderRejected as OutputFileValidation.Invalid).errorMessage)
      .isEqualTo("Network paths are not supported: '//example.invalid/share/out'")
    assertThat(networkFolderConfig.outputFolder).isEmpty()

    val localFolderConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        outputFolder = localReportsDir.path
      }
    val fileNameRejected = action.validateAndResolveOutputFile(localFolderConfig, localProject, "\\\\example.invalid\\share\\report.xml")
    assertThat(fileNameRejected).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((fileNameRejected as OutputFileValidation.Invalid).errorMessage)
      .isEqualTo("Network paths are not supported: '\\\\example.invalid\\share\\report.xml'")
    assertThat(localFolderConfig.outputFolder).isEqualTo(localReportsDir.path)

    // Projects opened from a UNC share can export to their default or project-internal relative folder,
    // but cannot escape project.basePath via '..' or point to an external UNC share.
    val uncBasePath = "\\\\corp.example.invalid\\team\\project"
    val uncProject = mockProject(uncBasePath)
    val defaultOutputOnUncProject =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        outputFolder = ""
      }
    val allowedInProjectUnc = action.validateAndResolveOutputFile(defaultOutputOnUncProject, uncProject, "report.xml")
    assertThat(allowedInProjectUnc).isEqualTo(OutputFileValidation.Valid(File(File(uncBasePath), "report.xml")))

    val traversalOutputOnUncProject =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        outputFolder = "..\\outside"
      }
    val traversalRejected = action.validateAndResolveOutputFile(traversalOutputOnUncProject, uncProject, "report.xml")
    assertThat(traversalRejected).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((traversalRejected as OutputFileValidation.Invalid).errorMessage).contains("Network paths are not supported")

    val externalShareOnUncProject =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.Xml
        outputFolder = "//other.example.invalid/share/out"
      }
    val externalShareRejected = action.validateAndResolveOutputFile(externalShareOnUncProject, uncProject, "report.xml")
    assertThat(externalShareRejected).isInstanceOf(OutputFileValidation.Invalid::class.java)
    assertThat((externalShareRejected as OutputFileValidation.Invalid).errorMessage)
      .isEqualTo("Network paths are not supported: '//other.example.invalid/share/out'")
    assertThat(externalShareOnUncProject.outputFolder).isEmpty()
  }

  @Test
  fun validateAndResolveOutputFile_allowsLocalAndWslPaths() {
    val action = ExportAndroidTestResultsAction()
    val project = mockProject(localProjectDir.path)
    val localConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.BundledTemplate
        outputFolder = "reports"
      }

    val localResult = action.validateAndResolveOutputFile(localConfig, project, "  result.html  ")
    assertThat(localResult).isEqualTo(OutputFileValidation.Valid(File(File(localProjectDir, "reports"), "result.html")))

    val wslProject = mockProject("\\\\wsl$\\Ubuntu\\home\\dev\\project")
    val wslConfig =
      ExportTestResultsConfiguration().apply {
        exportFormat = ExportTestResultsConfiguration.ExportFormat.BundledTemplate
        outputFolder = ""
      }

    val wslResult = action.validateAndResolveOutputFile(wslConfig, wslProject, "result.html")
    assertThat(wslResult).isInstanceOf(OutputFileValidation.Valid::class.java)
  }

  private fun mockProject(basePath: String): Project = mock<Project>().apply { whenever(this.basePath).thenReturn(basePath) }
}
