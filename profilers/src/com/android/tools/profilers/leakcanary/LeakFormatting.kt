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
package com.android.tools.profilers.leakcanary

import com.android.tools.leakcanarylib.data.Leak

/**
 * Formats a single occurrence at [occurrenceIndex] from [Leak.displayedLeakTrace] with standard LeakCanary headers (`retainedByteSize`,
 * `leakTraceCount`, and `signature`).
 *
 * Used by Copy to Clipboard so that the user copies the exact occurrence trace currently being viewed.
 */
fun Leak.toSingleOccurrenceString(occurrenceIndex: Int = 0): String {
  val trace = displayedLeakTrace.getOrNull(occurrenceIndex)?.toString() ?: ""
  return (if (retainedByteSize >= 0) "$retainedByteSize bytes retained by leaking objects\n" else "") +
    (if (leakTraceCount > 1) "Displaying only 1 leak trace out of $leakTraceCount with the same signature\n" else "") +
    "Signature: $signature\n" +
    trace
}
