/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
import com.google.idea.blaze.base.async.process.BinaryPathRemapper
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.base.util.ProcessGroupUtil
import com.intellij.execution.ExecutionException
import com.intellij.execution.Platform
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Scoped process handler.
 *
 * A context is created during construction and is ended when the process is terminated.
 */
class ScopedBlazeProcessHandler
@Throws(ExecutionException::class)
constructor(
  project: Project,
  command: List<String>,
  workspaceRoot: WorkspaceRoot,
  environment: Map<String, String>,
  private val scopedProcessHandlerDelegate: ScopedProcessHandlerDelegate,
  private val processTerminator: ProcessTerminator,
) :
  KillableColoredProcessHandler(
    ProcessGroupUtil.newProcessGroupFor(
      CommandLineWithRemappedPath(command)
        .withWorkDirectory(workspaceRoot.directory().path)
        .withEnvironment(environment)
        .withRedirectErrorStream(true)
    )
  ) {
  /**
   * Strategy for graceful (1st Stop) and forced (2nd Stop) process termination.
   *
   * Enables invokers that launch remote processes via a local wrapper script to signal the remote process group before falling back to or
   * completing with local process tree termination via [destroyLocalProcessGracefully] and [killLocalProcess].
   */
  interface ProcessTerminator {
    /** Performs graceful process termination (1st Stop). */
    fun destroyProcessGracefully(processHandler: ScopedBlazeProcessHandler): Boolean

    /** Performs forced process termination (2nd Stop). */
    fun killProcess(processHandler: ScopedBlazeProcessHandler)

    /** Standard termination strategy that signals the local OS process tree directly. */
    object Local : ProcessTerminator {
      override fun destroyProcessGracefully(processHandler: ScopedBlazeProcessHandler): Boolean =
        processHandler.destroyLocalProcessGracefully()

      override fun killProcess(processHandler: ScopedBlazeProcessHandler) {
        processHandler.killLocalProcess()
      }
    }
  }

  /** Methods to give the caller of [ScopedBlazeProcessHandler] hooks after the context is created. */
  interface ScopedProcessHandlerDelegate {
    /** This method is called when the process starts. Any context setup (like pushing scopes on the context) should be done here. */
    fun onBlazeContextStart(context: BlazeContext)

    /** Get a list of process listeners to add to the process. */
    fun createProcessListeners(context: BlazeContext): ImmutableList<ProcessListener>
  }

  private val context: BlazeContext = BlazeContext.create()

  init {
    // The context is released in the ScopedProcessHandlerListener.
    context.hold()

    for (processListener in scopedProcessHandlerDelegate.createProcessListeners(context)) {
      addProcessListener(processListener)
    }
    addProcessListener(ScopedProcessHandlerListener())
  }

  override fun destroyProcessGracefully(): Boolean = processTerminator.destroyProcessGracefully(this)

  override fun killProcess() {
    processTerminator.killProcess(this)
  }

  /** Sends SIGINT to the local OS process tree. */
  fun destroyLocalProcessGracefully(): Boolean = super.destroyProcessGracefully()

  /** Sends SIGKILL to the local OS process tree. */
  fun killLocalProcess() {
    super.killProcess()
  }

  /**
   * Handle the [BlazeContext] held in a [ScopedBlazeProcessHandler]. This class will take care of calling methods when the process starts
   * and freeing the context when the process terminates.
   */
  private inner class ScopedProcessHandlerListener : ProcessAdapter() {
    override fun startNotified(event: ProcessEvent) {
      scopedProcessHandlerDelegate.onBlazeContextStart(context)
    }

    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
      context.release()
    }
  }

  private class CommandLineWithRemappedPath(command: List<String>) : GeneralCommandLine(command) {
    override fun prepareCommandLine(
      command: String,
      parameters: List<String>,
      platform: Platform,
    ): List<String> {
      val remapped = remapBinaryPath(command)
      return super.prepareCommandLine(remapped, parameters, platform)
    }
  }

  companion object {
    private fun remapBinaryPath(command: String): String =
      BinaryPathRemapper.remapBinary(command).map(File::getAbsolutePath).orElse(command)
  }
}
