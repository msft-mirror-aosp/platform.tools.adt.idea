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
  private lateinit var provider: AdbHostLogFileProvider

  @Before
  fun setUp() {
    provider = AdbHostLogFileProvider(PathProvider(temporaryFolder.root.path, null, null, null))
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
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "35.0.2", absoluteLogPath = "/tmp/adb.log"))

    assertThat(provider.getFiles(projectRule.project).map { it.destination })
      .containsExactly(Paths.get("adb.log"), Paths.get("server-status.log"))
  }
}
