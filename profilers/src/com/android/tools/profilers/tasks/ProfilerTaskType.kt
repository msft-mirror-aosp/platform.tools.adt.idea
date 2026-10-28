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
package com.android.tools.profilers.tasks

enum class ProfilerTaskType(val description: String, val prefersProfileable: Boolean, val rank: Int) {
  SYSTEM_TRACE("System Trace", true, 0),
  LIVE_VIEW("Live View", true, 1),
  JAVA_KOTLIN_METHOD_RECORDING("Java/Kotlin Method Recording", true, 2),
  CALLSTACK_SAMPLE("Callstack Sample", true, 3),
  HEAP_DUMP("Heap Dump", false, 4),
  LEAKCANARY("LeakCanary", false, 5),
  JAVA_KOTLIN_ALLOCATIONS("Java/Kotlin Allocations", false, 6),
  NATIVE_ALLOCATIONS("Native Allocations", true, 7),
  UNSPECIFIED("Unspecified", false, Int.MAX_VALUE);

  /**
   * Returns the short name of the task shown in the task-based UI, e.g. in the task grid and the task tab titles. With the Home tab V2,
   * some tasks are shown under a new name, e.g. "Live Telemetry" for [LIVE_VIEW].
   */
  fun getShortName(isProfilerHomeTabV2Enabled: Boolean): String =
    when (this) {
      HEAP_DUMP,
      SYSTEM_TRACE,
      JAVA_KOTLIN_ALLOCATIONS,
      NATIVE_ALLOCATIONS -> description
      LIVE_VIEW -> if (isProfilerHomeTabV2Enabled) "Live Telemetry" else ""
      LEAKCANARY -> if (isProfilerHomeTabV2Enabled) "LeakCanary" else ""
      JAVA_KOTLIN_METHOD_RECORDING -> if (isProfilerHomeTabV2Enabled) "Method Trace" else description
      CALLSTACK_SAMPLE -> if (isProfilerHomeTabV2Enabled) "Stack Sampling" else "Callstack Sample"
      UNSPECIFIED -> ""
    }

  companion object {
    fun getNthRankedTask(rank: Int) = entries.find { it.rank == rank } ?: UNSPECIFIED
  }
}
