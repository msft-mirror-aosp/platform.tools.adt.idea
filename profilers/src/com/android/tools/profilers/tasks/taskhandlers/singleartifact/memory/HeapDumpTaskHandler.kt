/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.profilers.tasks.taskhandlers.singleartifact.memory

import com.android.tools.profiler.proto.Common
import com.android.tools.profilers.SupportLevel
import com.android.tools.profilers.memory.HprofSessionArtifact
import com.android.tools.profilers.memory.MainMemoryProfilerStage
import com.android.tools.profilers.memory.MemoryProfiler
import com.android.tools.profilers.sessions.SessionArtifact
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.taskbased.home.StartTaskSelectionError
import com.android.tools.profilers.taskbased.home.StartTaskSelectionError.StartTaskSelectionErrorCode
import com.android.tools.profilers.taskbased.home.TaskSelectionVerificationUtils.getMinApiStartTaskErrorMessage
import com.android.tools.profilers.tasks.args.TaskArgs
import com.android.tools.profilers.tasks.args.singleartifact.memory.HeapDumpTaskArgs

/** This class defines the task handler to perform a heap dump task. */
class HeapDumpTaskHandler(private val sessionsManager: SessionsManager) : MemoryTaskHandler(sessionsManager) {
  override fun startCapture(stage: MainMemoryProfilerStage) {
    stage.startHeapDumpCapture()
  }

  override fun stopCapture(stage: MainMemoryProfilerStage) {
    // Stopping a Heap Dump capture is not determined by the user so this method definition is empty.
  }

  override fun loadTask(args: TaskArgs): Boolean {
    if (args !is HeapDumpTaskArgs) {
      handleError("The task arguments (TaskArgs) supplied are not of the expected type (HeapDumpTaskArgs)")
      return false
    }

    val heapDumpTaskArtifact = args.getMemoryCaptureArtifact()
    if (heapDumpTaskArtifact == null) {
      handleError("The task arguments (HeapDumpTaskArgs) supplied do not contains a valid artifact to load")
      return false
    }
    loadCapture(heapDumpTaskArtifact)
    return true
  }

  override fun createStartTaskArgs(isStartupTask: Boolean) = HeapDumpTaskArgs(false, null)

  override fun createLoadingTaskArgs(artifact: SessionArtifact<*>) = HeapDumpTaskArgs(false, artifact as HprofSessionArtifact)

  override fun checkSupportForDeviceAndProcess(device: Common.Device, process: Common.Process): StartTaskSelectionError? {
    val supportLevel = SupportLevel.of(process.exposureLevel)
    if (supportLevel.isFeatureSupported(SupportLevel.Feature.MEMORY_HEAP_DUMP)) {
      return null
    }

    if (!MemoryProfiler.recordsHeapDumpAsJavaHeapGraph(supportLevel, sessionsManager.studioProfilers.ideServices.featureConfig)) {
      return StartTaskSelectionError(StartTaskSelectionErrorCode.TASK_REQUIRES_DEBUGGABLE_PROCESS)
    }

    // Profileable processes cannot be dumped with `am dumpheap`; they are captured as a Perfetto heap graph, which needs a newer device.
    if (device.featureLevel < MemoryProfiler.JAVA_HEAP_GRAPH_MIN_API) {
      return StartTaskSelectionError(
        StartTaskSelectionErrorCode.TASK_FROM_NOW_USING_API_BELOW_MIN,
        getMinApiStartTaskErrorMessage(MemoryProfiler.JAVA_HEAP_GRAPH_MIN_API),
      )
    }

    return null
  }

  override fun supportsArtifact(artifact: SessionArtifact<*>?) = artifact is HprofSessionArtifact

  override fun getTaskName(): String = "Heap Dump"
}
