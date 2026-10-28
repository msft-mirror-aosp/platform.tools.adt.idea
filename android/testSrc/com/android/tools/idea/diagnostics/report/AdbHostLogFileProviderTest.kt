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
package com.android.tools.idea.diagnostics.report

import com.android.adblib.ServerStatus
import com.android.tools.idea.adb.AdbServerStatusRetriever
import com.android.tools.idea.adb.ServerStatusState
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AdbHostLogFileProviderTest {
  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val temporaryFolder = TemporaryFolder()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(disposableRule).around(temporaryFolder)

  private val serverStatusState = MutableStateFlow<ServerStatusState>(ServerStatusState.NotConnected)
  private lateinit var pathProvider: PathProvider
  private lateinit var provider: AdbHostLogFileProvider

  @Before
  fun setUp() {
    pathProvider = PathProvider(temporaryFolder.root.path, null, null, null)
    provider = AdbHostLogFileProvider(pathProvider)
    val retriever = mock<AdbServerStatusRetriever>()
    whenever(retriever.serverStatusState).thenReturn(serverStatusState)
    projectRule.project.replaceService(AdbServerStatusRetriever::class.java, retriever, disposableRule.disposable)
    StudioFlags.ADB_HOST_LOGS_ENABLED.overrideForTest(true, disposableRule.disposable)
  }

  @Test
  fun returnsNothingWhenNotConnected() {
    assertThat(provider.getFiles(projectRule.project)).isEmpty()
  }

  @Test
  fun returnsNothingWhenServerStatusIsUnsupported() {
    serverStatusState.value = ServerStatusState.Unsupported

    assertThat(provider.getFiles(projectRule.project)).isEmpty()
  }

  @Test
  fun returnsHostLogAndServerStatusFiles() {
    val adbLogFile = temporaryFolder.newFile("adb.log")
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = adbLogFile.path))

    assertThat(provider.getFiles(projectRule.project).map { it.destination })
      .containsExactly(Paths.get("adb.log"), Paths.get("server-status.log"))
  }

  @Test
  fun returnsOriginalHostLogWhenSizeIsWithinLimit() {
    val adbLogFile = temporaryFolder.newFile("adb.log").toPath()
    Files.writeString(adbLogFile, "0123456789")
    val limitedProvider = AdbHostLogFileProvider(pathProvider, maxLogSizeBytes = 10)
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = adbLogFile.toString()))

    val files = limitedProvider.getFiles(projectRule.project)
    val hostLogFileInfo = files.first { it.destination == Paths.get("adb.log") }

    assertThat(hostLogFileInfo.source).isEqualTo(adbLogFile)
    assertThat(Files.readString(hostLogFileInfo.source)).isEqualTo("0123456789")
  }

  @Test
  fun truncatesHostLogAndSkipsPartialFirstLineWhenSizeExceedsLimit() {
    val adbLogFile = temporaryFolder.newFile("adb.log").toPath()
    val originalContent = "first line 🚀\nsecond line\nthird line\n"
    Files.writeString(adbLogFile, originalContent)
    // Seek offset (size - 28) lands mid-line inside "first line 🚀\n" (and inside the 4-byte UTF-8 emoji).
    val limitedProvider = AdbHostLogFileProvider(pathProvider, maxLogSizeBytes = 28)
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = adbLogFile.toString()))

    val files = limitedProvider.getFiles(projectRule.project)
    val hostLogFileInfo = files.first { it.destination == Paths.get("adb.log") }

    val expectedTruncatedPath =
      DiagnosticsSummaryFileProvider.getDiagnosticsDirectoryPath(pathProvider.logDir).resolve("adb.log")
    assertThat(hostLogFileInfo.source).isEqualTo(expectedTruncatedPath)
    assertThat(Files.readString(hostLogFileInfo.source)).isEqualTo("second line\nthird line\n")
    assertThat(Files.readString(adbLogFile)).isEqualTo(originalContent)
  }

  @Test
  fun omitsHostLogWhenPathIsEmptyOrDirectoryOrRoot() {
    for (invalidPath in listOf("", "/", temporaryFolder.root.path)) {
      serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = invalidPath))

      assertThat(provider.getFiles(projectRule.project).map { it.destination })
        .containsExactly(Paths.get("server-status.log"))
    }
  }

  @Test
  fun omitsHostLogWhenTruncationFailsWithIoException() {
    val adbLogFile = temporaryFolder.newFile("adb.log").toPath()
    Files.writeString(adbLogFile, "first line\nsecond line\nthird line\n")
    val limitedProvider = AdbHostLogFileProvider(pathProvider, maxLogSizeBytes = 15)
    // Pre-create a non-empty directory at the destination path so Files.copy throws DirectoryNotEmptyException (IOException).
    val conflictingDir =
      DiagnosticsSummaryFileProvider.getDiagnosticsDirectoryPath(pathProvider.logDir).resolve("adb.log")
    Files.createDirectories(conflictingDir.resolve("nested"))
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = adbLogFile.toString()))

    assertThat(limitedProvider.getFiles(projectRule.project).map { it.destination })
      .containsExactly(Paths.get("server-status.log"))
  }
}
