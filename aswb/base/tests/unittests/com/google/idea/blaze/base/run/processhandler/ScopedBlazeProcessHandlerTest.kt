/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.run.processhandler

import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.async.process.BinaryPathRemapper
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot
import com.google.idea.blaze.base.run.processhandler.ScopedBlazeProcessHandler.ProcessTerminator
import com.google.idea.blaze.base.run.processhandler.ScopedBlazeProcessHandler.ScopedProcessHandlerDelegate
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.base.scope.BlazeScope
import com.google.idea.common.experiments.ExperimentService
import com.google.idea.common.experiments.MockExperimentService
import com.intellij.execution.process.LocalProcessService
import com.intellij.execution.process.LocalProcessServiceImpl
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.CoreProgressManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ScopedBlazeProcessHandlerTest : BlazeTestCase() {
  private lateinit var tempDir: File
  private lateinit var workspaceRoot: WorkspaceRoot
  private val activeHandlers = mutableListOf<ScopedBlazeProcessHandler>()

  override fun initTest(applicationServices: Container, projectServices: Container) {
    super.initTest(applicationServices, projectServices)
    applicationServices.register(ExperimentService::class.java, MockExperimentService())
    applicationServices.register(LocalProcessService::class.java, LocalProcessServiceImpl())
    applicationServices.register(ProgressManager::class.java, CoreProgressManager())
    registerExtensionPoint(BinaryPathRemapper.EP_NAME, BinaryPathRemapper::class.java)
  }

  @Before
  fun setUpWorkspace() {
    tempDir = Files.createTempDirectory("scoped_process_test").toFile()
    workspaceRoot = WorkspaceRoot(tempDir)
  }

  @After
  fun tearDownProcesses() {
    for (handler in activeHandlers) {
      if (!handler.isProcessTerminated) {
        handler.killLocalProcess()
        handler.waitFor(2000)
      }
    }
    tempDir.deleteRecursively()
  }

  @Test
  fun localTerminator_destroyProcessGracefully_terminatesProcessAndReleasesContext() {
    val scopeEnded = CountDownLatch(1)
    val onExitCalled = CountDownLatch(1)
    val handler =
      createHandler(
        command = listOf("/bin/sh", "-c", "sleep 30"),
        processTerminator = ProcessTerminator.Local,
        onScopeEnd = { scopeEnded.countDown() },
        onExit = { onExitCalled.countDown() },
      )

    handler.startNotify()
    handler.destroyProcess()
    assertThat(handler.waitFor(5000)).isTrue()
    assertThat(scopeEnded.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(onExitCalled.await(5, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun localTerminator_killProcess_terminatesProcessIgnoringSigInt() {
    val startedFile = File(tempDir, "started")
    val handler =
      createHandler(
        command =
          listOf(
            "/bin/sh",
            "-c",
            "trap '' INT; touch ${startedFile.absolutePath}; sleep 30",
          ),
        processTerminator = ProcessTerminator.Local,
        onScopeEnd = {},
        onExit = {},
      )

    handler.startNotify()
    waitForFile(startedFile)

    handler.killProcess()
    assertThat(handler.waitFor(5000)).isTrue()
  }

  @Test
  fun customTerminator_destroyProcessGracefully_whenHandled_skipsLocalSignal() {
    val softKillCalled = AtomicBoolean(false)
    val terminator =
      object : ProcessTerminator {
        override fun destroyProcessGracefully(processHandler: ScopedBlazeProcessHandler): Boolean {
          softKillCalled.set(true)
          return true
        }

        override fun killProcess(processHandler: ScopedBlazeProcessHandler) {
          processHandler.killLocalProcess()
        }
      }
    val handler =
      createHandler(
        command = listOf("/bin/sh", "-c", "sleep 30"),
        processTerminator = terminator,
        onScopeEnd = {},
        onExit = {},
      )

    handler.startNotify()
    handler.destroyProcess()
    assertThat(softKillCalled.get()).isTrue()
    assertThat(handler.process.isAlive).isTrue()

    handler.killProcess()
    assertThat(handler.waitFor(5000)).isTrue()
  }

  @Test
  fun customTerminator_destroyProcessGracefully_canFallBackToLocalGracefulDestroy() {
    val softKillCalled = AtomicBoolean(false)
    val terminator =
      object : ProcessTerminator {
        override fun destroyProcessGracefully(processHandler: ScopedBlazeProcessHandler): Boolean {
          softKillCalled.set(true)
          return processHandler.destroyLocalProcessGracefully()
        }

        override fun killProcess(processHandler: ScopedBlazeProcessHandler) {
          processHandler.killLocalProcess()
        }
      }
    val handler =
      createHandler(
        command = listOf("/bin/sh", "-c", "sleep 30"),
        processTerminator = terminator,
        onScopeEnd = {},
        onExit = {},
      )

    handler.startNotify()
    handler.destroyProcess()
    assertThat(softKillCalled.get()).isTrue()
    assertThat(handler.waitFor(5000)).isTrue()
  }

  @Test
  fun customTerminator_killProcess_runsCustomActionBeforeKillingLocalProcess() {
    val events = mutableListOf<String>()
    val onExitCalled = CountDownLatch(1)
    val terminator =
      object : ProcessTerminator {
        override fun destroyProcessGracefully(processHandler: ScopedBlazeProcessHandler): Boolean = false

        override fun killProcess(processHandler: ScopedBlazeProcessHandler) {
          try {
            events.add("remote_kill")
          } finally {
            events.add("local_kill")
            processHandler.killLocalProcess()
          }
        }
      }
    val handler =
      createHandler(
        command = listOf("/bin/sh", "-c", "sleep 30"),
        processTerminator = terminator,
        onScopeEnd = {},
        onExit = { onExitCalled.countDown() },
      )

    handler.startNotify()
    handler.killProcess()
    assertThat(handler.waitFor(5000)).isTrue()
    assertThat(onExitCalled.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(events).containsExactly("remote_kill", "local_kill").inOrder()
  }

  private fun createHandler(
    command: List<String>,
    processTerminator: ProcessTerminator,
    onScopeEnd: () -> Unit,
    onExit: () -> Unit,
  ): ScopedBlazeProcessHandler {
    val delegate =
      object : ScopedProcessHandlerDelegate {
        override fun onBlazeContextStart(context: BlazeContext) {
          context.push(
            object : BlazeScope {
              override fun onScopeEnd(context: BlazeContext) {
                onScopeEnd()
              }
            }
          )
        }

        override fun createProcessListeners(context: BlazeContext): ImmutableList<ProcessListener> =
          ImmutableList.of(
            object : ProcessAdapter() {
              override fun processTerminated(event: ProcessEvent) {
                onExit()
              }
            }
          )
      }
    return ScopedBlazeProcessHandler(
        project,
        command,
        workspaceRoot,
        emptyMap(),
        delegate,
        processTerminator,
      )
      .also { activeHandlers.add(it) }
  }

  private fun waitForFile(file: File) {
    val deadline = System.currentTimeMillis() + 5000
    while (!file.exists() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }
    assertThat(file.exists()).isTrue()
  }
}
