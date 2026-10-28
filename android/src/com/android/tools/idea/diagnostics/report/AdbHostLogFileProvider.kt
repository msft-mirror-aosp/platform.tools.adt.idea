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
package com.android.tools.idea.diagnostics.report

import com.android.tools.idea.adb.AdbServerStatusRetriever
import com.android.tools.idea.adb.ServerStatusState
import com.android.tools.idea.flags.StudioFlags
import com.google.common.io.ByteStreams
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path

private const val SERVER_STATUS_FILE: String = "server-status.log"
private const val DEFAULT_MAX_LOG_SIZE: Long = 10L * 1024 * 1024 // 10 MB

/** A [DiagnosticsSummaryFileProvider] for adb host log. */
class AdbHostLogFileProvider(
  private val pathProvider: PathProvider,
  private val maxLogSizeBytes: Long = DEFAULT_MAX_LOG_SIZE,
) : DiagnosticsSummaryFileProvider {

  override val name: String
    get() = "Adb Host"

  override fun getFiles(project: Project?): List<FileInfo> {
    if (!StudioFlags.ADB_HOST_LOGS_ENABLED.get()) {
      return emptyList()
    }
    if (project == null) {
      return emptyList()
    }
    val serverStatusState = AdbServerStatusRetriever.getInstance(project).serverStatusState.value
    val serverStatus = (serverStatusState as? ServerStatusState.Supported)?.status ?: return emptyList()
    val diagnosticsDir = DiagnosticsSummaryFileProvider.getDiagnosticsDirectoryPath(pathProvider.logDir)
    val serverStatusOutputFile = diagnosticsDir.resolve(SERVER_STATUS_FILE)
    Files.writeString(serverStatusOutputFile, serverStatus.toString())

    val logPath = Path(serverStatus.absoluteLogPath)
    val hostLogFile = getHostLogFile(logPath, diagnosticsDir)
    return listOfNotNull(
      hostLogFile?.let { FileInfo(it, logPath.fileName) },
      FileInfo(serverStatusOutputFile, Paths.get(SERVER_STATUS_FILE)),
    )
  }

  /**
   * Returns the path to the ADB host log file to include in the diagnostic report, or `null` if the
   * log file does not exist, is not a regular file, or cannot be truncated.
   *
   * Because the ADB daemon's `adb.log` can grow very large over time, including the entire file can
   * cause diagnostic report collection and previewing to hang or run out of memory. If the log file
   * exceeds [maxLogSizeBytes], this method seeks back [maxLogSizeBytes] from the end of the file,
   * discards the partial first line up to the first `\n`, and copies the remainder into
   * [diagnosticsDir]; otherwise, it returns [logPath] directly.
   */
  private fun getHostLogFile(logPath: Path, diagnosticsDir: Path): Path? {
    // Guard against root paths ("/") where fileName is null, and empty paths ("") or directories
    // where Files.exists() would be true for the current working directory.
    val fileName = logPath.fileName ?: return null
    if (!Files.isRegularFile(logPath)) {
      return null
    }
    return try {
      FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        if (size <= maxLogSizeBytes) {
          return@use logPath
        }
        // Seek back `maxLogSizeBytes` from the end of the file in O(1) time.
        channel.position(size - maxLogSizeBytes)
        val truncatedLogFile = diagnosticsDir.resolve(fileName)
        // Bound the stream with `ByteStreams.limit` in case the ADB server is actively appending
        // to `adb.log` while we copy it.
        ByteStreams.limit(Channels.newInputStream(channel), maxLogSizeBytes).buffered().use { inputStream ->
          // Discard bytes up to and including the first '\n' so we don't start mid-line or split
          // a multi-byte UTF-8 character.
          while (inputStream.read().let { it != -1 && it != '\n'.code }) {}
          Files.copy(inputStream, truncatedLogFile, StandardCopyOption.REPLACE_EXISTING)
        }
        truncatedLogFile
      }
    } catch (e: IOException) {
      // Return null rather than falling back to `logPath`, which may be a multi-gigabyte file.
      thisLogger().warn("Failed to truncate ADB host log file: $logPath", e)
      null
    }
  }
}

val DefaultAdbHostLogFileProvider = AdbHostLogFileProvider(DefaultPathProvider)
