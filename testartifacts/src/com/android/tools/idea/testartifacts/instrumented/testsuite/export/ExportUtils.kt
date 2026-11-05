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

import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.UiThread
import com.android.annotations.concurrency.WorkerThread
import com.android.tools.idea.testartifacts.AndroidTestBundle
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.AndroidTestResultsTreeNode
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidDevice
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.export.ExportTestResultsAction
import com.intellij.execution.testframework.export.ExportTestResultsConfiguration
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.io.URLUtil
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.time.Duration
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.xml.XMLConstants
import javax.xml.transform.OutputKeys
import javax.xml.transform.sax.SAXTransformerFactory
import javax.xml.transform.sax.TransformerHandler
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

private val LOG = Logger.getInstance("com.android.tools.idea.testartifacts.instrumented.testsuite.export.ExportUtils")

/** JAXP feature controlling whether XSLT extension functions may be used. It defaults to false since JBR/JDK 25. */
private const val ENABLE_EXTENSION_FUNCTIONS_FEATURE = "jdk.xml.enableExtensionFunctions"

/** Exports a given [rootResultsNode] into a AndroidTestMatrix XML file. */
@AnyThread
fun exportAndroidTestMatrixResultXmlFile(
  project: Project,
  toolWindowId: String?,
  exportConfig: ExportTestResultsConfiguration,
  exportFile: File,
  executionDuration: Duration,
  rootResultsNode: AndroidTestResultsTreeNode,
  runConfiguration: RunConfiguration,
  devices: List<AndroidDevice>,
  onFinishedFunc: () -> Unit = {},
) {
  ProgressManager.getInstance()
    .run(
      object :
        Task.Backgroundable(
          project,
          AndroidTestBundle.message("export.test.results.task.name"),
          false,
          PerformInBackgroundOption.ALWAYS_BACKGROUND,
        ) {
        override fun run(indicator: ProgressIndicator) {
          indicator.isIndeterminate = true
          if (isDisallowedNetworkPath(exportFile.path, project.basePath)) {
            LOG.warn("Rejected network output path for exported test results: ${exportFile.path}")
            showBalloon(
              project,
              toolWindowId,
              MessageType.ERROR,
              AndroidTestBundle.message(
                "export.test.results.failed",
                AndroidTestBundle.message("export.test.results.network.path.not.supported", exportFile.path),
              ),
              null,
            )
            return
          }
          val outputText =
            try {
              createOutputText(exportConfig, executionDuration, rootResultsNode, runConfiguration, devices, toolWindowId) ?: return
            } catch (e: ProcessCanceledException) {
              throw e
            } catch (e: Exception) {
              // Without this, any failure while generating the report (e.g. a failing XSL transformation) would abort
              // this background task silently and the user would be left without any output nor error message.
              reportExportFailure(project, toolWindowId, e)
              return
            }
          val (resultFile, errorMessage) =
            invokeAndWaitIfNeeded {
              runWriteAction {
                exportFile.parentFile.mkdirs()

                val parent = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(exportFile.parentFile)
                if (parent?.isValid != true) {
                  return@runWriteAction Pair(null, AndroidTestBundle.message("failed.to.create.output.file", exportFile.path))
                }

                try {
                  val resultFile = parent.findChild(exportFile.name) ?: parent.createChildData(this, exportFile.name)
                  VfsUtil.saveText(resultFile, outputText)

                  Pair(resultFile, null)
                } catch (e: IOException) {
                  LOG.warn("Failed to write the exported test results into ${exportFile.path}", e)
                  Pair(null, e.message ?: AndroidTestBundle.message("failed.to.create.output.file", exportFile.path))
                }
              }
            }

          if (!errorMessage.isNullOrBlank()) {
            showBalloon(
              project,
              toolWindowId,
              MessageType.ERROR,
              AndroidTestBundle.message("export.test.results.failed", errorMessage),
              null,
            )
            return
          }
          if (resultFile == null) {
            return
          }

          if (exportConfig.isOpenResults) {
            openEditorOrBrowser(resultFile, project, exportConfig.exportFormat == ExportTestResultsConfiguration.ExportFormat.Xml)
          } else {
            val listener = HyperlinkListener { e ->
              if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                openEditorOrBrowser(resultFile, project, exportConfig.exportFormat == ExportTestResultsConfiguration.ExportFormat.Xml)
              }
            }
            showBalloon(
              project,
              toolWindowId,
              MessageType.INFO,
              AndroidTestBundle.message("export.test.results.succeeded", exportFile.name),
              listener,
            )
          }
        }

        override fun onFinished() {
          onFinishedFunc()
        }
      }
    )
}

@WorkerThread
private fun createOutputText(
  exportConfig: ExportTestResultsConfiguration,
  executionDuration: Duration,
  rootResultsNode: AndroidTestResultsTreeNode,
  runConfiguration: RunConfiguration,
  devices: List<AndroidDevice>,
  toolWindowId: String?,
): String? {
  val transformerHandler = createTransformerHandler(exportConfig, runConfiguration, toolWindowId) ?: return null
  val writer = StringWriter()
  transformerHandler.setResult(StreamResult(writer))
  AndroidTestResultsXmlFormatter(executionDuration, rootResultsNode, devices, runConfiguration, transformerHandler).execute()
  return writer.toString()
}

@WorkerThread
private fun createTransformerHandler(
  exportConfig: ExportTestResultsConfiguration,
  runConfiguration: RunConfiguration,
  toolWindowId: String?,
): TransformerHandler? {
  val transformerFactory =
    (SAXTransformerFactory.newDefaultInstance() as SAXTransformerFactory).apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      // Explicitly disable extension functions via API so JVM system properties (-Djdk.xml.enableExtensionFunctions=true)
      // cannot re-enable them for untrusted user templates.
      setFeature(ENABLE_EXTENSION_FUNCTIONS_FEATURE, false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      // Blocks external <xsl:include>/<xsl:import> targets as well as XSLT document() URI lookups.
      setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }
  return when (exportConfig.exportFormat) {
    ExportTestResultsConfiguration.ExportFormat.Xml -> {
      transformerFactory.newTransformerHandler().apply {
        transformer.apply {
          setOutputProperty(OutputKeys.INDENT, "yes")
          setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        }
      }
    }
    ExportTestResultsConfiguration.ExportFormat.BundledTemplate -> {
      val xslSource = StreamSource(URLUtil.openStream(ExportTestResultsAction::class.java.getResource("intellij-export.xsl")))
      // The platform's bundled template calls the EXSLT extension function `str:tokenize`. Extension functions are
      // disabled by default since JBR/JDK 25, so they have to be enabled explicitly.
      transformerFactory.setFeature(ENABLE_EXTENSION_FUNCTIONS_FEATURE, true)
      transformerFactory.newTransformerHandler(xslSource).apply {
        transformer.apply {
          setParameter(
            "TITLE",
            AndroidTestBundle.message("export.test.results.filename", runConfiguration.name, runConfiguration.type.displayName),
          )
        }
      }
    }
    else -> {
      val userTemplatePath = exportConfig.userTemplatePath
      if (userTemplatePath.isNullOrBlank()) {
        showBalloon(
          runConfiguration.project,
          toolWindowId,
          MessageType.ERROR,
          AndroidTestBundle.message("export.test.results.custom.template.path.empty"),
          null,
        )
        return null
      }
      if (isDisallowedNetworkPath(userTemplatePath, runConfiguration.project.basePath)) {
        LOG.warn("Rejected network custom XSL template path: $userTemplatePath")
        showBalloon(
          runConfiguration.project,
          toolWindowId,
          MessageType.ERROR,
          AndroidTestBundle.message(
            "export.test.results.failed",
            AndroidTestBundle.message("export.test.results.network.path.not.supported", userTemplatePath),
          ),
          null,
        )
        return null
      }
      val xslFile = File(userTemplatePath)
      if (!xslFile.isFile) {
        showBalloon(
          runConfiguration.project,
          toolWindowId,
          MessageType.ERROR,
          AndroidTestBundle.message("export.test.results.custom.template.not.found", xslFile.path),
          null,
        )
        return null
      }
      transformerFactory.newTransformerHandler(StreamSource(xslFile)).apply {
        transformer.apply {
          setParameter(
            "TITLE",
            AndroidTestBundle.message("export.test.results.filename", runConfiguration.name, runConfiguration.type.displayName),
          )
        }
      }
    }
  }
}

/**
 * Returns true if [path] starts with a disallowed Windows network or special namespace prefix.
 *
 * Intentionally rejects UNC shares (`\\host\share`, `//host/share`, `\\?\UNC\host\share`) as well as Windows extended-length, Win32 device,
 * and NT object manager prefixes (`\\?\`, `\\.\`, `\??\`), with two narrow exceptions when no `.` or `..` traversal segments are present:
 * - Local WSL shares (`\\wsl$\` and `\\wsl.localhost\`).
 * - Standard UNC paths strictly inside [allowedProjectBasePath], so projects already opened from a UNC share can use project-internal paths
 *   without contacting any new remote host.
 *
 * Mapped network drive letters (e.g. `Z:\`) cannot be detected syntactically.
 */
internal fun isDisallowedNetworkPath(path: String?, allowedProjectBasePath: String? = null): Boolean {
  if (path.isNullOrBlank()) return false
  val normalized = path.trim().replace('/', '\\')
  if (normalized.startsWith("\\\\wsl$\\", ignoreCase = true) || normalized.startsWith("\\\\wsl.localhost\\", ignoreCase = true)) {
    return normalized.split('\\').any { it == ".." || it == "." }
  }
  if (!normalized.startsWith("\\\\") && !normalized.startsWith("\\??\\")) {
    return false
  }
  if (!allowedProjectBasePath.isNullOrBlank() && isUncPathInsideProject(normalized, allowedProjectBasePath)) {
    return false
  }
  return true
}

private fun isUncPathInsideProject(normalizedPath: String, projectBasePath: String): Boolean {
  if (!normalizedPath.startsWith("\\\\")) return false
  val normalizedBase = projectBasePath.trim().replace('/', '\\').trimEnd('\\')
  if (!normalizedBase.startsWith("\\\\") || normalizedBase.startsWith("\\\\?\\") || normalizedBase.startsWith("\\\\.\\")) {
    return false
  }
  fun hasValidSegments(segments: List<String>): Boolean = segments.all { it.isNotEmpty() && it != "." && it != ".." }

  val baseSegments = normalizedBase.removePrefix("\\\\").split('\\')
  val pathSegments = normalizedPath.trimEnd('\\').removePrefix("\\\\").split('\\')
  // Require at least host + share (`\\host\share`); a bare host (`\\host`) is not a valid UNC share root.
  return baseSegments.size >= 2 &&
    pathSegments.size >= baseSegments.size &&
    hasValidSegments(baseSegments) &&
    hasValidSegments(pathSegments) &&
    pathSegments.subList(0, baseSegments.size).zip(baseSegments).all { (a, b) -> a.equals(b, ignoreCase = true) }
}

/** Logs [error] and notifies the user that the test results could not be exported. */
@AnyThread
private fun reportExportFailure(project: Project, toolWindowId: String?, error: Throwable) {
  LOG.warn("Failed to export Android test results", error)
  showBalloon(
    project,
    toolWindowId,
    MessageType.ERROR,
    AndroidTestBundle.message("export.test.results.failed", error.message ?: error.javaClass.name),
    null,
  )
}

@AnyThread
private fun showBalloon(project: Project, toolWindowId: String?, type: MessageType, text: String, listener: HyperlinkListener?) {
  val toolWindowId = toolWindowId ?: return
  ApplicationManager.getApplication().invokeLater {
    if (project.isDisposed) {
      return@invokeLater
    }
    if (ToolWindowManager.getInstance(project).getToolWindow(toolWindowId) != null) {
      ToolWindowManager.getInstance(project).notifyByBalloon(toolWindowId, type, text, null, listener)
    }
  }
}

@UiThread
private fun openEditorOrBrowser(result: VirtualFile, project: Project, editor: Boolean) {
  ApplicationManager.getApplication().invokeLater {
    if (editor) {
      FileEditorManager.getInstance(project).openFile(result, true)
    } else {
      BrowserUtil.browse(result)
    }
  }
}
