/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.testartifacts.instrumented.testsuite.model.benchmark

import com.android.tools.idea.perfetto.PerfettoTraceWebLoader
import com.android.tools.idea.project.AndroidNotification
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.util.Locale

private val BENCHMARK_TRACE_FILE_PREFIX_V2 = BenchmarkOutput.BENCHMARK_TRACE_FILE_PREFIX
private val BENCHMARK_TRACE_FILE_PREFIX_V3 = "uri://"

/** Hosts of the Android documentation pages that androidx.benchmark links to from its output. */
private val TRUSTED_WEB_LINK_HOSTS = setOf("d.android.com", "developer.android.com")

/** Maximum number of characters of a rejected link shown in the warning notification. */
private const val MAX_DISPLAYED_LINK_LENGTH = 200

class BenchmarkLinkListener(
  private val project: Project,
  private val isPerfettoWebLoaderEnabled: Boolean = Registry.`is`(PerfettoTraceWebLoader.FEATURE_REGISTRY_KEY, false),
  private val openTraceInPerfettoWebLoader: (file: File, queryParams: String?) -> Unit = PerfettoTraceWebLoader::loadTrace,
) : HyperlinkListener {
  override fun hyperlinkClicked(link: String) {
    when {
      link.startsWith(BENCHMARK_TRACE_FILE_PREFIX_V2) || link.startsWith(BENCHMARK_TRACE_FILE_PREFIX_V3) -> {
        val link = convertLinkToV3Format(link) // replace the V2 prefix with a V3 prefix (V3 is backwards compatible)
        check(link.startsWith(BENCHMARK_TRACE_FILE_PREFIX_V3)) // from this point we only deal with the V3 format

        // check if the file exists
        val fileName = link.drop(BENCHMARK_TRACE_FILE_PREFIX_V3.length).substringBefore('?') // drop query params (and the prefix)
        val tempRoot = BenchmarkOutput.getBenchmarkTraceDirectory()
        val localPath =
          try {
            if (BenchmarkOutput.isSafeRelativeFilePath(fileName)) tempRoot.resolve(fileName).normalize() else null
          } catch (e: InvalidPathException) {
            null
          }
        if (localPath == null || !localPath.startsWith(tempRoot)) {
          showInvalidPathWarning(fileName)
          return
        }
        val localFileName = localPath.fileName?.toString().orEmpty()
        if (!BenchmarkOutput.isBenchmarkTraceFile(localFileName)) {
          AndroidNotification.getInstance(project)
            .showBalloon(
              "Unsupported benchmark file",
              "Only trace files can be opened from benchmark results (${toDisplayText(localFileName)})",
              NotificationType.WARNING,
            )
          return
        }
        val localFile = localPath.toFile()
        if (!localFile.isFile) {
          AndroidNotification.getInstance(project)
            .showBalloon(
              "Benchmark file not found",
              "Unable to open trace file (${toDisplayText(localFile.name)})",
              NotificationType.WARNING,
            )
          // TODO (gijosh): Check if we have a task that is currently pulling the file
          return
        }
        val realPath =
          try {
            localFile.toPath().toRealPath()
          } catch (e: Exception) {
            null
          }
        if (realPath == null || !realPath.startsWith(tempRoot)) {
          showInvalidPathWarning(fileName)
          return
        }
        val virtualFileTrace = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile) ?: return
        val fd = OpenFileDescriptor(project, virtualFileTrace)

        if (isPerfettoWebLoaderEnabled && isUiPerfettoDevSupportedFile(fileName)) {
          // open the trace in Perfetto Web UI (if applicable)
          val queryParams = Regex("\\?(.*)$").find(link)?.groupValues?.getOrNull(1)
          openTraceInPerfettoWebLoader(fd.file.toIoFile(), queryParams)
        } else {
          // open the trace in the Studio Profiler
          // TODO(b/364596134): pass selection parameters to PerfettoParser to zoom-in on a relevant part of the trace
          FileEditorManager.getInstance(project).openEditor(fd, true)
        }
      }
      link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) -> {
        val uri = toTrustedWebUri(link)
        if (uri == null) {
          AndroidNotification.getInstance(project)
            .showBalloon(
              "Benchmark link not opened",
              "Only links to Android documentation can be opened (${toDisplayText(link)})",
              NotificationType.WARNING,
            )
          return
        }
        BrowserLauncher.instance.browse(uri)
      }

      else -> {
        /* ignore unrecognized links */
      }
    }
  }

  /**
   * Benchmark output is produced on the device, so its links are untrusted. Returns the parsed [URI] only for plain `https` links (no user
   * info or explicit port) to a host in [TRUSTED_WEB_LINK_HOSTS], or `null` otherwise.
   */
  private fun toTrustedWebUri(link: String): URI? {
    val uri =
      try {
        URI(link)
      } catch (e: URISyntaxException) {
        return null
      }
    if (!"https".equals(uri.scheme, ignoreCase = true)) return null
    if (uri.rawUserInfo != null || uri.port != -1) return null
    val host = uri.host?.lowercase(Locale.US) ?: return null
    return if (host in TRUSTED_WEB_LINK_HOSTS) uri else null
  }

  // TODO(b/b/376667704): confirm if perf traces are also supported (and add to supported extensions)
  private fun isUiPerfettoDevSupportedFile(fileName: String) = fileName.endsWith(".perfetto-trace")

  private fun showInvalidPathWarning(fileName: String) {
    AndroidNotification.getInstance(project)
      .showBalloon("Invalid benchmark path", "Rejected path traversal: ${toDisplayText(fileName)}", NotificationType.WARNING)
  }

  /** Benchmark links come from the device, so text taken from them is trimmed and escaped before it is shown in a notification. */
  private fun toDisplayText(text: String): String = StringUtil.escapeXmlEntities(StringUtil.trimMiddle(text, MAX_DISPLAYED_LINK_LENGTH))

  private fun convertLinkToV3Format(link: String): String =
    when {
      link.startsWith(BENCHMARK_TRACE_FILE_PREFIX_V2) -> BENCHMARK_TRACE_FILE_PREFIX_V3 + link.drop(BENCHMARK_TRACE_FILE_PREFIX_V2.length)
      link.startsWith(BENCHMARK_TRACE_FILE_PREFIX_V3) -> link // no-op
      else -> error("Unsupported Benchmark link format: $link")
    }

  private fun VirtualFile.toIoFile(): File = VfsUtil.virtualToIoFile(this)
}
