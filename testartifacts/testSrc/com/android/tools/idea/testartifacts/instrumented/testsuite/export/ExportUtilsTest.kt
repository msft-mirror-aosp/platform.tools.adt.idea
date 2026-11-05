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
package com.android.tools.idea.testartifacts.instrumented.testsuite.export

import com.android.sdklib.AndroidVersion
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfigurationType
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultStats
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResults
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultsTreeNode
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDeviceType
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.benchmark.BenchmarkOutput
import com.android.tools.idea.util.toIoFile
import com.google.common.io.Files
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.export.ExportTestResultsConfiguration
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.testFramework.replaceService
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
@RunsInEdt
class ExportUtilsTest {
  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val temporaryDirectoryRule = TemporaryDirectory()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule).around(temporaryDirectoryRule)

  @Test
  fun exportToXml() {
    val fileContent = exportAndGetContent(ExportTestResultsConfiguration.ExportFormat.Xml)
    assertThat(fileContent).contains("""<suite name="testDeviceName1" duration="1234" status="passed">""")
    assertThat(fileContent).contains("""<suite name="testDeviceName2" duration="7777" status="failed">""")
    assertThat(fileContent).contains("""<androidTestMatrix executionDuration="1234">""")
    assertThat(fileContent)
      .contains("""<device id="testDeviceId1" deviceName="testDeviceName1" deviceType="LOCAL_EMULATOR" version="23">""")
    assertThat(fileContent)
      .contains("""<device id="testDeviceId2" deviceName="testDeviceName2" deviceType="LOCAL_PHYSICAL_DEVICE" version="24">""")
    assertThat(fileContent).contains("""<testsuite deviceId="testDeviceId1" testCount="1" result="PASSED">""")
    assertThat(fileContent)
      .contains(
        """<testcase id="testpackage.testclass.testmethod" methodName="testmethod" className="testclass" packageName="testpackage" result="PASSED" logcat="" errorStackTrace="" startTimestampMillis="0" endTimestampMillis="1234" benchmark=""/>"""
      )
  }

  @Test
  fun exportToHtml() {
    val fileContent = exportAndGetContent(ExportTestResultsConfiguration.ExportFormat.BundledTemplate)
    assertThat(fileContent).contains("<title>Test Results &mdash; testRunConfig</title>")
  }

  @Test
  fun exportToHtmlWithTestCaseOutput() {
    val fileContent =
      exportAndGetContent(
        ExportTestResultsConfiguration.ExportFormat.BundledTemplate,
        logcat = "logcat line 1\nlogcat line 2",
        errorStackTrace = "error stack trace line",
      )

    assertThat(fileContent).contains("<title>Test Results &mdash; testRunConfig</title>")
    assertThat(fileContent).contains("logcat line 1")
    assertThat(fileContent).contains("logcat line 2")
    assertThat(fileContent).contains("error stack trace line")
  }

  @Test
  fun exportWithUserTemplate() {
    val xslFile =
      createTempIoFile(
        "custom.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:param name="TITLE"/>
          <xsl:output method="html"/>
          <xsl:template match="/">
            <html>
              <head><title><xsl:value-of select="${'$'}TITLE"/></title></head>
              <body>Run: <xsl:value-of select="testrun/@name"/></body>
            </html>
          </xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
      )

    val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = xslFile.path)

    assertThat(result.outputFile.isFile).isTrue()
    assertThat(result.balloonType).isEqualTo(MessageType.INFO)
    val fileContent = Files.asCharSource(result.outputFile, Charsets.UTF_8).read()
    assertThat(fileContent).contains("<title>Test Results - testRunConfig</title>")
    assertThat(fileContent).contains("Run: testRunConfig")
  }

  @Test
  fun exportWithUserTemplate_blocksExtensionFunctionsEvenWhenSystemPropertyEnabled() {
    val xslFile =
      createTempIoFile(
        "extfn.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0"
            xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:math="http://xml.apache.org/xalan/java/java.lang.Math">
          <xsl:output method="html"/>
          <xsl:template match="/">
            <html><body><xsl:value-of select="math:max(40, 42)"/></body></html>
          </xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
      )

    val propertyName = "jdk.xml.enableExtensionFunctions"
    val previousValue = System.getProperty(propertyName)
    System.setProperty(propertyName, "true")
    try {
      val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = xslFile.path)
      assertThat(result.outputFile.exists()).isFalse()
      assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
      // Deliberate tripwire against JBR's JAXP diagnostic text so runtime updates fail loudly if feature enforcement changes.
      assertThat(result.balloonText).contains("enableExtensionFunctions")
    } finally {
      if (previousValue == null) {
        System.clearProperty(propertyName)
      } else {
        System.setProperty(propertyName, previousValue)
      }
    }
  }

  @Test
  fun exportWithUserTemplate_blocksExternalDtd() {
    val dtdFile = createTempIoFile("external.dtd", "<!ENTITY injected 'EXTERNAL_DTD_CONTENT'>")
    val xslFile =
      createTempIoFile(
        "doctype.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE xsl:stylesheet SYSTEM "${dtdFile.toURI()}">
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:template match="/">
            <html><body>&injected;</body></html>
          </xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
      )

    val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = xslFile.path)
    assertThat(result.outputFile.exists()).isFalse()
    assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
    // Deliberate tripwire against JBR's JAXP diagnostic text so runtime updates fail loudly if feature enforcement changes.
    assertThat(result.balloonText).contains("accessExternalDTD")
  }

  @Test
  fun exportWithUserTemplate_blocksExternalStylesheetInclude() {
    val sharedDir = runWriteAction { temporaryDirectoryRule.createVirtualDir("includeDir") }
    val includedFile =
      createTempIoFile(
        "included.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"/>
        """
          .trimIndent(),
        sharedDir,
      )
    val xslFile =
      createTempIoFile(
        "include.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:include href="${includedFile.name}"/>
          <xsl:template match="/"><html><body>included</body></html></xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
        sharedDir,
      )

    val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = xslFile.path)
    assertThat(result.outputFile.exists()).isFalse()
    assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
    // Deliberate tripwire against JBR's JAXP diagnostic text so runtime updates fail loudly if feature enforcement changes.
    assertThat(result.balloonText).contains("accessExternalStylesheet")
  }

  @Test
  fun exportWithUserTemplate_blocksDocumentFunction() {
    val sharedDir = runWriteAction { temporaryDirectoryRule.createVirtualDir("docFnDir") }
    val markerFile = createTempIoFile("marker.xml", "<marker>EXTERNAL_DOC_MARKER</marker>", sharedDir)
    val xslFile =
      createTempIoFile(
        "document_fn.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:template match="/">
            <html><body><xsl:value-of select="document('${markerFile.name}')/marker"/></body></html>
          </xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
        sharedDir,
      )

    val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = xslFile.path)
    assertThat(result.outputFile.exists()).isFalse()
    assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
    // Deliberate tripwire against JBR's JAXP diagnostic text so runtime updates fail loudly if feature enforcement changes.
    assertThat(result.balloonText).contains("accessExternalStylesheet")
  }

  @Test
  fun exportWithUserTemplate_rejectsBlankAndNetworkTemplatePaths() {
    val localXsl =
      createTempIoFile(
        "valid_local.xsl",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:template match="/"><html><body>ok</body></html></xsl:template>
        </xsl:stylesheet>
        """
          .trimIndent(),
      )
    // On POSIX, a leading "//" resolves to "/" unless rejected before File normalization.
    val posixUncStylePath = "//" + localXsl.absolutePath.replace('\\', '/').trimStart('/')

    for (blankPath in listOf(null, "", "   ")) {
      val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = blankPath)
      assertThat(result.outputFile.exists()).isFalse()
      assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
      assertThat(result.balloonText).isEqualTo("User-defined template path is empty")
    }

    for (networkPath in listOf("\\\\example.invalid\\share\\template.xsl", posixUncStylePath)) {
      val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.UserTemplate, userTemplatePath = networkPath)
      assertThat(result.outputFile.exists()).isFalse()
      assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
      assertThat(result.balloonText).contains("Network paths are not supported")
    }
  }

  @Test
  fun exportRejectsNetworkOutputFile() {
    val uncOutputFile = File("\\\\example.invalid\\share\\output.xml")
    val result = exportToNewFile(ExportTestResultsConfiguration.ExportFormat.Xml, outputFileOverride = uncOutputFile)

    assertThat(result.balloonType).isEqualTo(MessageType.ERROR)
    assertThat(result.balloonText).contains("Network paths are not supported")
    assertThat(result.balloonText).contains("example.invalid")
  }

  @Test
  fun isDisallowedNetworkPath_detectsDisallowedUncAndAllowsLocalWslAndInProjectPaths() {
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("//host/share/template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\?\\UNC\\host\\share\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\?\\C:\\long\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\.\\C:\\device\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\??\\UNC\\host\\share\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\wsl${'$'}remote\\share\\template.xsl")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\wsl${'$'}\\Ubuntu\\..\\..\\host\\share\\template.xsl")).isTrue()

    assertThat(isDisallowedNetworkPath(null)).isFalse()
    assertThat(isDisallowedNetworkPath("")).isFalse()
    assertThat(isDisallowedNetworkPath("/tmp/template.xsl")).isFalse()
    assertThat(isDisallowedNetworkPath("C:\\Users\\dev\\template.xsl")).isFalse()
    assertThat(isDisallowedNetworkPath("docs/template.xsl")).isFalse()
    assertThat(isDisallowedNetworkPath("\\\\wsl${'$'}\\Ubuntu\\home\\dev\\template.xsl")).isFalse()
    assertThat(isDisallowedNetworkPath("\\\\wsl.localhost\\Ubuntu\\home\\dev\\template.xsl")).isFalse()

    // Standard UNC paths strictly inside allowedProjectBasePath (without '.' or '..' traversal) are permitted.
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\proj\\reports\\out.xml", allowedProjectBasePath = "//host/share/proj")).isFalse()
    assertThat(isDisallowedNetworkPath("\\\\HOST\\Share\\Proj\\out.xml", allowedProjectBasePath = "\\\\host\\share\\proj\\")).isFalse()
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\proj", allowedProjectBasePath = "\\\\host\\share\\proj")).isFalse()
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\proj-other\\out.xml", allowedProjectBasePath = "\\\\host\\share\\proj")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\proj\\.\\out.xml", allowedProjectBasePath = "\\\\host\\share\\proj")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\host\\share\\proj\\..\\other\\out.xml", allowedProjectBasePath = "\\\\host\\share\\proj"))
      .isTrue()
    assertThat(isDisallowedNetworkPath("\\\\other\\share\\proj\\out.xml", allowedProjectBasePath = "\\\\host\\share\\proj")).isTrue()
    assertThat(isDisallowedNetworkPath("\\\\?\\UNC\\host\\share\\proj\\out.xml", allowedProjectBasePath = "\\\\?\\UNC\\host\\share\\proj"))
      .isTrue()
    assertThat(isDisallowedNetworkPath("\\\\host\\out.xml", allowedProjectBasePath = "\\\\host")).isTrue()
  }

  private class ExportAttemptResult(
    val outputFile: File,
    val balloonType: MessageType?,
    val balloonText: String?,
  )

  private fun exportAndGetContent(
    format: ExportTestResultsConfiguration.ExportFormat,
    logcat: String = "",
    errorStackTrace: String = "",
    userTemplatePath: String? = null,
  ): String {
    lateinit var outputVirtualFile: VirtualFile
    runWriteAction {
      val inputDir = temporaryDirectoryRule.createVirtualDir("outputDir")
      outputVirtualFile = inputDir.createChildData(this, "output.${format.defaultExtension}")
    }

    val outputFile = outputVirtualFile.toIoFile()
    val exportConfig =
      mock<ExportTestResultsConfiguration>().apply {
        whenever(exportFormat).thenReturn(format)
        whenever(this.userTemplatePath).thenReturn(userTemplatePath)
      }

    val runConfig =
      mock<RunConfiguration>().apply {
        whenever(name).thenReturn("testRunConfig")
        whenever(type).thenReturn(AndroidTestRunConfigurationType.getInstance())
        whenever(project).thenReturn(projectRule.project)
      }
    val (devices, resultsNode) = createDevicesAndResultsNode(logcat, errorStackTrace)

    val countDownLatch = CountDownLatch(1)
    var fileContent: String? = null
    exportAndroidTestMatrixResultXmlFile(
      projectRule.project,
      "run",
      exportConfig,
      outputFile,
      Duration.ofMillis(1234),
      resultsNode,
      runConfig,
      devices,
    ) {
      fileContent = Files.asCharSource(outputFile, Charsets.UTF_8).read()
      countDownLatch.countDown()
    }
    assertThat(countDownLatch.await(30, TimeUnit.SECONDS)).isTrue()

    return requireNotNull(fileContent)
  }

  private fun exportToNewFile(
    format: ExportTestResultsConfiguration.ExportFormat,
    userTemplatePath: String? = null,
    outputFileOverride: File? = null,
  ): ExportAttemptResult {
    val outputFile =
      outputFileOverride
        ?: run {
          lateinit var outputDir: File
          runWriteAction { outputDir = temporaryDirectoryRule.createVirtualDir("outputDir").toIoFile() }
          File(outputDir, "output.${format.defaultExtension}")
        }

    var capturedBalloonType: MessageType? = null
    var capturedBalloonText: String? = null
    val mockToolWindow = mock<ToolWindow>()
    val mockToolWindowManager =
      mock<ToolWindowManager>().apply {
        whenever(getToolWindow(eq("run"))).thenReturn(mockToolWindow)
        whenever(notifyByBalloon(eq("run"), any(), any(), anyOrNull(), anyOrNull())).thenAnswer { invocation ->
          capturedBalloonType = invocation.getArgument(1)
          capturedBalloonText = invocation.getArgument(2)
          null
        }
      }
    projectRule.project.replaceService(ToolWindowManager::class.java, mockToolWindowManager, disposableRule.disposable)

    val exportConfig =
      mock<ExportTestResultsConfiguration>().apply {
        whenever(exportFormat).thenReturn(format)
        whenever(this.userTemplatePath).thenReturn(userTemplatePath)
      }
    val runConfig =
      mock<RunConfiguration>().apply {
        whenever(name).thenReturn("testRunConfig")
        whenever(type).thenReturn(AndroidTestRunConfigurationType.getInstance())
        whenever(project).thenReturn(projectRule.project)
      }
    val (devices, resultsNode) = createDevicesAndResultsNode()

    val countDownLatch = CountDownLatch(1)
    exportAndroidTestMatrixResultXmlFile(
      projectRule.project,
      "run",
      exportConfig,
      outputFile,
      Duration.ofMillis(1234),
      resultsNode,
      runConfig,
      devices,
    ) {
      countDownLatch.countDown()
    }
    assertThat(countDownLatch.await(30, TimeUnit.SECONDS)).isTrue()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    return ExportAttemptResult(outputFile, capturedBalloonType, capturedBalloonText)
  }

  private fun createTempIoFile(fileName: String, content: String, parentDir: VirtualFile? = null): File {
    lateinit var virtualFile: VirtualFile
    runWriteAction {
      val dir = parentDir ?: temporaryDirectoryRule.createVirtualDir("xslDir")
      virtualFile = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
      virtualFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
    }
    return virtualFile.toIoFile()
  }

  private fun createDevicesAndResultsNode(
    logcat: String = "",
    errorStackTrace: String = "",
  ): Pair<List<AndroidDevice>, AndroidTestResultsTreeNode> {
    val device1 =
      AndroidDevice(
        "testDeviceId1",
        "testDeviceName1",
        "testDeviceName1",
        AndroidDeviceType.LOCAL_EMULATOR,
        AndroidVersion(23),
        mutableMapOf("processorName" to "testProcessorName1"),
      )
    val device2 =
      AndroidDevice(
        "testDeviceId2",
        "testDeviceName2",
        "testDeviceName1",
        AndroidDeviceType.LOCAL_PHYSICAL_DEVICE,
        AndroidVersion(24),
        mutableMapOf("processorName" to "testProcessorName2"),
      )

    val rootResults =
      mock<AndroidTestResults>().apply {
        whenever(getTotalDuration()).thenReturn(Duration.ofMillis(9011L))
        whenever(getResultStats()).thenReturn(AndroidTestResultStats(passed = 1, failed = 1))

        whenever(getTestCaseResult(eq(device1))).thenReturn(AndroidTestCaseResult.PASSED)
        whenever(getDuration(eq(device1))).thenReturn(Duration.ofMillis(1234L))

        whenever(getTestCaseResult(eq(device2))).thenReturn(AndroidTestCaseResult.FAILED)
        whenever(getDuration(eq(device2))).thenReturn(Duration.ofMillis(7777L))
      }
    val classResults =
      mock<AndroidTestResults>().apply {
        whenever(methodName).thenReturn("")
        whenever(className).thenReturn("testclass")
        whenever(packageName).thenReturn("testpackage")

        whenever(getTestCaseResult(eq(device1))).thenReturn(AndroidTestCaseResult.PASSED)
        whenever(getDuration(eq(device1))).thenReturn(Duration.ofMillis(1234L))

        whenever(getTestCaseResult(eq(device2))).thenReturn(AndroidTestCaseResult.FAILED)
        whenever(getDuration(eq(device2))).thenReturn(Duration.ofMillis(7777L))
      }
    val caseResults =
      mock<AndroidTestResults>().apply {
        whenever(methodName).thenReturn("testmethod")
        whenever(className).thenReturn("testclass")
        whenever(packageName).thenReturn("testpackage")

        whenever(getTestCaseResult(eq(device1))).thenReturn(AndroidTestCaseResult.PASSED)
        whenever(getDuration(eq(device1))).thenReturn(Duration.ofMillis(1234L))
        whenever(getLogcat(eq(device1))).thenReturn(logcat)
        whenever(getErrorStackTrace(eq(device1))).thenReturn(errorStackTrace)
        whenever(getBenchmark(eq(device1))).thenReturn(BenchmarkOutput.Empty)

        whenever(getTestCaseResult(eq(device2))).thenReturn(AndroidTestCaseResult.FAILED)
        whenever(getDuration(eq(device2))).thenReturn(Duration.ofMillis(7777L))
        whenever(getLogcat(eq(device2))).thenReturn(logcat)
        whenever(getErrorStackTrace(eq(device2))).thenReturn(errorStackTrace)
        whenever(getBenchmark(eq(device2))).thenReturn(BenchmarkOutput.Empty)
      }

    return Pair(
      listOf(device1, device2),
      AndroidTestResultsTreeNode(
        rootResults,
        sequenceOf(AndroidTestResultsTreeNode(classResults, sequenceOf(AndroidTestResultsTreeNode(caseResults, sequenceOf())))),
      ),
    )
  }
}
