/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.device.explorer.files.fs

import kotlin.time.Duration

/**
 * Utility class used to ensure that progress report from a long running activity is throttled to the given interval.
 *
 * <p>Usage:
 * <pre>
 *   myThrottledProgress = ThrottledProgress(100.milliseconds)
 *   (...)
 *   fun reportProgress() {
 *     if (myThrottledProgress.check()) {
 *       // Code to report progress here...
 *     }
 *   }
 * </pre>
 */
class ThrottledProgress(
  interval: Duration,
  private val nanoTimeSupplier: () -> Long = System::nanoTime,
) {
  private var lastNotifyNanoTime: Long? = null
  private val intervalNanos = interval.inWholeNanoseconds

  /**
   * Returns `true` if caller should report progress, i.e. if the time elapsed since the last time we returned `true` exceeds the specified
   * interval.
   */
  fun check(): Boolean {
    val currentNanoTime = nanoTimeSupplier()
    val lastNotifiedNanos = lastNotifyNanoTime

    // If first time or enough time has passed, return `true`
    if (lastNotifiedNanos == null || currentNanoTime - lastNotifiedNanos >= intervalNanos) {
      lastNotifyNanoTime = currentNanoTime
      return true
    }

    return false
  }
}
