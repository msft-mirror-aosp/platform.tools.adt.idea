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
package com.android.tools.idea.uibuilder.editor

import com.android.testutils.delayUntilCondition
import com.intellij.codeInsight.daemon.impl.ErrorStripeUpdateManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.ThreadingAssertions
import java.lang.reflect.Field
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

// Reflects on ErrorStripeUpdateManager.coroutineScope (verified on IJ 263.7307; in headless tests
// AppMode.isCommandLine() is true so DaemonCodeAnalyzerRepaintIconHelper is inactive and
// TrafficLightRenderer creation runs on ErrorStripeUpdateManager.coroutineScope); fails loudly if renamed.
private val errorStripeScopeField: Field by lazy {
  ErrorStripeUpdateManager::class.java.getDeclaredField("coroutineScope").apply {
    isAccessible = true
  }
}

private fun activeErrorStripeJobs(project: Project): List<Job> {
  // Light projects closed via tearDownProjectAndApp set isTemporarilyDisposed = true
  // (project.isDisposed == true) without cancelling service scopes, so getServiceIfCreated
  // still returns the live ErrorStripeUpdateManager; AlreadyDisposedException means the
  // container was permanently disposed and its service scope cancelled.
  val manager =
    try {
      project.getServiceIfCreated(ErrorStripeUpdateManager::class.java)
    } catch (_: AlreadyDisposedException) {
      null
    } ?: return emptyList()
  val scope = errorStripeScopeField.get(manager) as CoroutineScope
  return scope.coroutineContext[Job]?.children?.filter { !it.isCompleted }?.toList().orEmpty()
}

internal fun hasActiveErrorStripeJobs(project: Project): Boolean = activeErrorStripeJobs(project).isNotEmpty()

private suspend fun awaitErrorStripeUpdates(project: Project, timeout: Duration = 15.seconds) {
  ThreadingAssertions.assertBackgroundThread()
  withContext(Dispatchers.Default + NonCancellable) {
    try {
      delayUntilCondition(delayPerIterationMs = 20, timeout = timeout) {
        withContext(Dispatchers.EDT) {
          val idleBeforeFlush = !hasActiveErrorStripeJobs(project)
          NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
          PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
          idleBeforeFlush && !hasActiveErrorStripeJobs(project)
        }
      }
    } catch (e: TimeoutCancellationException) {
      val stuck = activeErrorStripeJobs(project)
      stuck.forEach { it.cancel() }
      try {
        delayUntilCondition(delayPerIterationMs = 20, timeout = 2.seconds) {
          withContext(Dispatchers.EDT) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            !hasActiveErrorStripeJobs(project)
          }
        }
      } catch (_: TimeoutCancellationException) {}
      throw AssertionError(
        "Timed out after $timeout waiting for ErrorStripeUpdateManager jobs on $project: $stuck",
        e,
      )
    }
  }
}

internal suspend fun disposeEditorAndWaitForTrafficLight(
  project: Project,
  editor: FileEditor,
  disposeEditor: () -> Unit = { Disposer.dispose(editor) },
) {
  runDisposalsAndWaitForTrafficLight(project, listOf(disposeEditor), closeOpenFiles = false)
}

internal suspend fun closeProjectEditorsAndWaitForTrafficLight(
  project: Project,
  additionalDisposals: List<() -> Unit> = emptyList(),
) {
  runDisposalsAndWaitForTrafficLight(project, additionalDisposals, closeOpenFiles = true)
}

private inline fun runStepRecordingFailure(
  recordFailure: (Throwable) -> Unit,
  block: () -> Unit,
) {
  try {
    block()
  } catch (e: VirtualMachineError) {
    throw e
  } catch (e: ProcessCanceledException) {
    throw e
  } catch (e: CancellationException) {
    throw e
  } catch (t: Throwable) {
    recordFailure(t)
  }
}

private suspend fun runDisposalsAndWaitForTrafficLight(
  project: Project,
  disposals: List<() -> Unit>,
  closeOpenFiles: Boolean,
) {
  ThreadingAssertions.assertBackgroundThread()
  var primaryFailure: Throwable? = null
  fun recordFailure(t: Throwable) {
    if (t is VirtualMachineError || t is ProcessCanceledException || t is CancellationException) {
      primaryFailure?.let { if (it !== t) t.addSuppressed(it) }
      throw t
    }
    if (primaryFailure == null) {
      primaryFailure = t
    } else if (primaryFailure !== t) {
      primaryFailure!!.addSuppressed(t)
    }
  }

  try {
    withContext(Dispatchers.EDT + NonCancellable) {
      try {
        disposals.forEach { dispose ->
          runStepRecordingFailure(::recordFailure) { dispose() }
        }
      } finally {
        if (closeOpenFiles) {
          runStepRecordingFailure(::recordFailure) {
            if (!project.isDisposed && project.getServiceIfCreated(FileEditorManager::class.java) != null) {
              FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            }
          }
        }
      }
    }
  } finally {
    try {
      awaitErrorStripeUpdates(project)
    } catch (e: VirtualMachineError) {
      throw e
    } catch (e: ProcessCanceledException) {
      primaryFailure?.let { if (it !== e) e.addSuppressed(it) }
      throw e
    } catch (e: CancellationException) {
      primaryFailure?.let { if (it !== e) e.addSuppressed(it) }
      throw e
    } catch (t: Throwable) {
      recordFailure(t)
    } finally {
      if (primaryFailure != null || hasActiveErrorStripeJobs(project)) {
        withContext(Dispatchers.EDT + NonCancellable) {
          runStepRecordingFailure(::recordFailure) { NonBlockingReadActionImpl.waitForAsyncTaskCompletion() }
          runStepRecordingFailure(::recordFailure) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        }
      }
    }
  }
  primaryFailure?.let { throw it }
}
