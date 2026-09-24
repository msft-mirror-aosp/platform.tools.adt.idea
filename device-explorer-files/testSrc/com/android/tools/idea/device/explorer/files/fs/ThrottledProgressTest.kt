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
package com.android.tools.idea.device.explorer.files.fs

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class ThrottledProgressTest {

  @Test
  fun testInitialCheckReturnsTrue() {
    val throttled = ThrottledProgress(interval = 100.milliseconds)
    assertThat(throttled.check()).isTrue()
  }

  @Test
  fun testCheckReturnsFalse_whenIntervalIsNotExceeded() {
    var currentTimeNanos = 0L
    val throttled = ThrottledProgress(interval = 100.milliseconds) { currentTimeNanos }

    // First check always returns true
    assertThat(throttled.check()).isTrue()

    // Immediate check returns false
    assertThat(throttled.check()).isFalse()

    // Advancing time by 99 ms (< 100 ms interval) still returns false
    currentTimeNanos += 99.milliseconds.inWholeNanoseconds
    assertThat(throttled.check()).isFalse()
  }

  @Test
  fun testCheckReturnsTrue_whenIntervalIsExceeded() {
    var currentTimeNanos = 0L
    val throttled = ThrottledProgress(interval = 100.milliseconds) { currentTimeNanos }

    // First check always returns true
    assertThat(throttled.check()).isTrue()

    // Immediate check returns false
    assertThat(throttled.check()).isFalse()

    // Advancing time by 101 ms (> 100 ms interval) returns true
    currentTimeNanos += 101.milliseconds.inWholeNanoseconds
    assertThat(throttled.check()).isTrue()
  }
}
