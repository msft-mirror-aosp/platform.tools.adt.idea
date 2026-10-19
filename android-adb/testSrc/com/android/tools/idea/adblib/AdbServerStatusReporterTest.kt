/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.idea.adblib

import com.android.adblib.AdbFeatures
import com.android.adblib.ServerStatus
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.tools.adblib.testutils.FakeAdbServerAdbLibRule
import com.android.tools.idea.adb.AdbServerStatusRetriever
import com.android.tools.idea.adb.ServerStatusState
import com.android.tools.idea.testing.disposable
import com.google.wireless.android.sdk.stats.AdbServerStatus
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AdbServerStatusReporterTest {
  private val projectRule = ProjectRule()
  private val fakeAdbRule = FakeAdbServerAdbLibRule()

  @get:Rule val ruleChain = RuleChain.outerRule(projectRule).around(fakeAdbRule)!!

  @Test
  fun reportsServerStatus() {
    val status = firstReportedStatus()
    assertEquals("35.0.2", status.version)
  }

  @Test
  fun reportsUnsupportedWhenServerStatusIsUnsupported() {
    fakeAdbRule.adbServer.features -= AdbFeatures.SERVER_STATUS

    assertEquals(ServerStatus.UNKNOWN, firstReportedStatus().version)
  }

  @Test
  fun reportsOnlyWhenStatusChanges() = runBlockingWithTimeout {
    val serverStatusState = MutableStateFlow<ServerStatusState>(ServerStatusState.NotConnected)
    val retriever = mock<AdbServerStatusRetriever>()
    whenever(retriever.serverStatusState).thenReturn(serverStatusState)
    projectRule.project.replaceService(AdbServerStatusRetriever::class.java, retriever, projectRule.disposable)
    val reportedStatuses = CopyOnWriteArrayList<AdbServerStatus>()
    val reporter = AdbServerStatusReporter { reportedStatuses.add(it) }

    // The same reporter instance runs for every open project: simulate two projects connected to the same adb server.
    val jobs = List(2) { launch { reporter.execute(projectRule.project) } }
    yieldUntil { serverStatusState.subscriptionCount.value == 2 }

    serverStatusState.value = ServerStatusState.Unsupported
    yieldUntil { reportedStatuses.isNotEmpty() }
    // adb server reconnects with the same status
    serverStatusState.value = ServerStatusState.NotConnected
    yield()
    serverStatusState.value = ServerStatusState.Unsupported
    yield()
    // adb server is upgraded
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "37.0.0", traceLevel = "adb"))
    yieldUntil { reportedStatuses.size == 2 }
    // unlogged field changes (e.g. ADB_TRACE)
    serverStatusState.value = ServerStatusState.Supported(ServerStatus(version = "37.0.0", traceLevel = "all"))
    yield()

    assertEquals(listOf(ServerStatus.UNKNOWN, "37.0.0"), reportedStatuses.map { it.version })
    jobs.forEach { it.cancel() }
  }

  private fun firstReportedStatus(): AdbServerStatus = runBlockingWithTimeout {
    val reportedStatus = CompletableDeferred<AdbServerStatus>()
    val job = launch { AdbServerStatusReporter { reportedStatus.complete(it) }.execute(projectRule.project) }
    reportedStatus.await().also { job.cancel() }
  }
}
