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
package com.android.tools.profilers.memory;

import static com.android.tools.profilers.Notification.createError;

import com.android.tools.profilers.Notification;
import com.android.tools.profilers.memory.adapters.CaptureObject;
import com.android.tools.profilers.memory.adapters.HeapDumpCaptureObject;
import com.android.tools.profilers.memory.adapters.LegacyAllocationCaptureObject;
import org.jetbrains.annotations.NotNull;

final class MemoryProfilerNotifications {
  private MemoryProfilerNotifications() {}

  @NotNull
  static final Notification HEAP_DUMP_CAPTURE_FAILURE = createError(
    "Heap dump capture failed",
    "The profiler was unable to parse heap dump data. Try capturing again, or "
  );

  @NotNull
  static final Notification NATIVE_ALLOCATIONS_PARSING_FAILURE = createError(
    "Native allocations were not recorded",
    "The profiler was unable to parse native allocation data. Try recording again, or "
  );

  @NotNull
  static final Notification JAVA_KOTLIN_ALLOCATIONS_PARSING_FAILURE = createError(
    "Java/Kotlin allocations were not recorded",
    "The profiler was unable to parse Java/Kotlin allocation data. Try recording again, or "
  );

  @NotNull
  static Notification getCaptureFailure(@NotNull CaptureObject capture) {
    if (capture instanceof HeapDumpCaptureObject) {
      return HEAP_DUMP_CAPTURE_FAILURE;
    }
    if (capture instanceof LegacyAllocationCaptureObject) {
      return JAVA_KOTLIN_ALLOCATIONS_PARSING_FAILURE;
    }
    return NATIVE_ALLOCATIONS_PARSING_FAILURE;
  }
}

