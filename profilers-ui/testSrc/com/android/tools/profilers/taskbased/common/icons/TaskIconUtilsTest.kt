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
package com.android.tools.profilers.taskbased.common.icons

import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.testFramework.ApplicationRule
import icons.StudioIcons
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class TaskIconUtilsTest {
  @get:Rule val applicationRule = ApplicationRule()

  @Test
  fun `getTaskIcon returns the 16x16 v2 icons with the Home tab V2`() {
    val expectedIcons =
      mapOf(
        ProfilerTaskType.SYSTEM_TRACE to StudioIcons.Profiler.TasksV2.SYSTEM_TRACE,
        ProfilerTaskType.LIVE_VIEW to StudioIcons.Profiler.TasksV2.LIVE_VIEW,
        ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING to StudioIcons.Profiler.TasksV2.JAVA_KOTLIN_METHOD_TRACE,
        ProfilerTaskType.CALLSTACK_SAMPLE to StudioIcons.Profiler.TasksV2.CALLSTACK_SAMPLE,
        ProfilerTaskType.HEAP_DUMP to StudioIcons.Profiler.TasksV2.HEAP_DUMP,
        ProfilerTaskType.LEAKCANARY to StudioIcons.Profiler.TasksV2.FIND_MEMORY_LEAKS,
        ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS to StudioIcons.Profiler.TasksV2.JAVA_KOTLIN_ALLOCATIONS,
        ProfilerTaskType.NATIVE_ALLOCATIONS to StudioIcons.Profiler.TasksV2.NATIVE_ALLOCATIONS,
      )
    assertThat(expectedIcons.keys).containsExactlyElementsIn(ProfilerTaskType.entries - ProfilerTaskType.UNSPECIFIED)
    for ((taskType, icon) in expectedIcons) {
      assertThat(TaskIconUtils.getTaskIcon(taskType, isProfilerHomeTabV2Enabled = true)).isSameAs(icon)
    }
  }

  @Test
  fun `getTaskIcon returns the previous icons without the Home tab V2`() {
    assertThat(TaskIconUtils.getTaskIcon(ProfilerTaskType.SYSTEM_TRACE, isProfilerHomeTabV2Enabled = false))
      .isSameAs(StudioIcons.Profiler.Tasks.SYSTEM_TRACE)
    assertThat(TaskIconUtils.getTaskIcon(ProfilerTaskType.LEAKCANARY, isProfilerHomeTabV2Enabled = false))
      .isSameAs(StudioIcons.Profiler.Tasks.FIND_MEMORY_LEAKS)
  }

  @Test
  fun `getTaskIcon throws for the UNSPECIFIED task type`() {
    for (isProfilerHomeTabV2Enabled in listOf(true, false)) {
      assertThrows(IllegalStateException::class.java) {
        TaskIconUtils.getTaskIcon(ProfilerTaskType.UNSPECIFIED, isProfilerHomeTabV2Enabled)
      }
    }
  }

  @Test
  fun `16x16 v2 icons have light and dark variants`() {
    val iconNames =
      listOf(
        "callstack-sample",
        "find-memory-leaks",
        "heap-dump",
        "java-kotlin-allocations",
        "java-kotlin-method-trace",
        "live-view",
        "native-allocations",
        "system-trace",
      )
    for (iconName in iconNames) {
      for (path in listOf("$ICON_DIR/$iconName.svg", "$ICON_DIR/${iconName}_dark.svg")) {
        assertWithMessage(path).that(StudioIcons::class.java.classLoader.getResource(path)).isNotNull()
      }
    }
  }

  companion object {
    private const val ICON_DIR = "studio/icons/profiler/tasks-v2"
  }
}
