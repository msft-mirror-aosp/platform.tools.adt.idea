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
package com.android.tools.profilers.taskbased.common.icons

import androidx.compose.runtime.Composable
import com.android.tools.profilers.tasks.ProfilerTaskType
import icons.StudioIcons
import icons.StudioIconsCompose
import javax.swing.Icon
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

object TaskIconUtils {
  /** Return the corresponding large task icon painter for a task type. To be used in the task selection grid. */
  @Composable
  fun getLargeTaskIconKey(taskType: ProfilerTaskType): IntelliJIconKey {
    val iconKey =
      when (taskType) {
        ProfilerTaskType.UNSPECIFIED -> throw IllegalStateException("No task icon is available for the UNSPECIFIED task type.")
        ProfilerTaskType.CALLSTACK_SAMPLE -> StudioIconsCompose.Profiler.Taskslarge.CallstackSampleLarge
        ProfilerTaskType.SYSTEM_TRACE -> StudioIconsCompose.Profiler.Taskslarge.SystemTraceLarge
        ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING -> StudioIconsCompose.Profiler.Taskslarge.JavaKotlinMethodTraceLarge
        ProfilerTaskType.HEAP_DUMP -> StudioIconsCompose.Profiler.Taskslarge.HeapDumpLarge
        ProfilerTaskType.NATIVE_ALLOCATIONS -> StudioIconsCompose.Profiler.Taskslarge.NativeAllocationsLarge
        ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS -> StudioIconsCompose.Profiler.Taskslarge.JavaKotlinAllocationsLarge
        ProfilerTaskType.LIVE_VIEW -> StudioIconsCompose.Profiler.Taskslarge.LiveViewLarge
        ProfilerTaskType.LEAKCANARY -> StudioIconsCompose.Profiler.Taskslarge.FindMemoryLeaksLarge
      }
    return iconKey
  }

  /** Return the corresponding task icon painter for a task type. To be used in the v2 task selection grid. */
  @Composable
  fun getTaskIconKey(taskType: ProfilerTaskType): IntelliJIconKey {
    val iconKey =
      when (taskType) {
        ProfilerTaskType.UNSPECIFIED -> throw IllegalStateException("No task icon is available for the UNSPECIFIED task type.")
        ProfilerTaskType.CALLSTACK_SAMPLE -> StudioIconsCompose.Profiler.Tasksv2large.CallstackSampleLarge
        ProfilerTaskType.SYSTEM_TRACE -> StudioIconsCompose.Profiler.Tasksv2large.SystemTraceLarge
        ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING -> StudioIconsCompose.Profiler.Tasksv2large.JavaKotlinMethodTraceLarge
        ProfilerTaskType.HEAP_DUMP -> StudioIconsCompose.Profiler.Tasksv2large.HeapDumpLarge
        ProfilerTaskType.NATIVE_ALLOCATIONS -> StudioIconsCompose.Profiler.Tasksv2large.NativeAllocationsLarge
        ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS -> StudioIconsCompose.Profiler.Tasksv2large.JavaKotlinAllocationsLarge
        ProfilerTaskType.LIVE_VIEW -> StudioIconsCompose.Profiler.Tasksv2large.LiveViewLarge
        ProfilerTaskType.LEAKCANARY -> StudioIconsCompose.Profiler.Tasksv2large.FindMemoryLeaksLarge
      }
    return iconKey
  }

  /**
   * Utility to fetch the corresponding task icon for a task type. To be used for the task tab icon, in the Profiler tool window and in the
   * editor. With the Home tab V2, returns the 16x16 version of the icon in the v2 task selection grid (see [getTaskIconKey]).
   */
  fun getTaskIcon(taskType: ProfilerTaskType, isProfilerHomeTabV2Enabled: Boolean): Icon {
    if (isProfilerHomeTabV2Enabled) {
      return when (taskType) {
        ProfilerTaskType.UNSPECIFIED -> throw IllegalStateException("No task icon is available for the UNSPECIFIED task type.")
        ProfilerTaskType.CALLSTACK_SAMPLE -> StudioIcons.Profiler.TasksV2.CALLSTACK_SAMPLE
        ProfilerTaskType.SYSTEM_TRACE -> StudioIcons.Profiler.TasksV2.SYSTEM_TRACE
        ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING -> StudioIcons.Profiler.TasksV2.JAVA_KOTLIN_METHOD_TRACE
        ProfilerTaskType.HEAP_DUMP -> StudioIcons.Profiler.TasksV2.HEAP_DUMP
        ProfilerTaskType.NATIVE_ALLOCATIONS -> StudioIcons.Profiler.TasksV2.NATIVE_ALLOCATIONS
        ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS -> StudioIcons.Profiler.TasksV2.JAVA_KOTLIN_ALLOCATIONS
        ProfilerTaskType.LIVE_VIEW -> StudioIcons.Profiler.TasksV2.LIVE_VIEW
        ProfilerTaskType.LEAKCANARY -> StudioIcons.Profiler.TasksV2.FIND_MEMORY_LEAKS
      }
    }
    return when (taskType) {
      ProfilerTaskType.UNSPECIFIED -> throw IllegalStateException("No task icon is available for the UNSPECIFIED task type.")
      ProfilerTaskType.CALLSTACK_SAMPLE -> StudioIcons.Profiler.Tasks.CALLSTACK_SAMPLE
      ProfilerTaskType.SYSTEM_TRACE -> StudioIcons.Profiler.Tasks.SYSTEM_TRACE
      ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING -> StudioIcons.Profiler.Tasks.JAVA_KOTLIN_METHOD_TRACE
      ProfilerTaskType.HEAP_DUMP -> StudioIcons.Profiler.Tasks.HEAP_DUMP
      ProfilerTaskType.NATIVE_ALLOCATIONS -> StudioIcons.Profiler.Tasks.NATIVE_ALLOCATIONS
      ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS -> StudioIcons.Profiler.Tasks.JAVA_KOTLIN_ALLOCATIONS
      ProfilerTaskType.LIVE_VIEW -> StudioIcons.Profiler.Tasks.LIVE_VIEW
      ProfilerTaskType.LEAKCANARY -> StudioIcons.Profiler.Tasks.FIND_MEMORY_LEAKS
    }
  }
}
