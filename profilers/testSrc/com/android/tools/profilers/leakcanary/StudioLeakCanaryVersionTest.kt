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

import com.android.tools.profilers.FakeProfilerPreferences
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Every version here is built from [MIN_STUDIO_LEAKCANARY_VERSION], so raising the minimum version doesn't need any change in this test.
 */
class StudioLeakCanaryVersionTest {
  /** The three numbers of the minimum version, for example [1, 0, 0]. */
  private val minNumbers = MIN_STUDIO_LEAKCANARY_VERSION.split(".").map { it.toInt() }

  @Test
  fun `minimum version is a stable version with three numbers`() {
    // No "-alpha01" or other preview part, which users must never be sent to.
    assertTrue(MIN_STUDIO_LEAKCANARY_VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
  }

  @Test
  fun `minimum version is not outdated`() {
    assertNull(findOutdatedStudioLeakCanaryVersion(MIN_STUDIO_LEAKCANARY_VERSION))
  }

  @Test
  fun `newer version is not outdated`() {
    val newer = "${minNumbers[0]}.${minNumbers[1]}.${minNumbers[2] + 1}"
    assertNull(findOutdatedStudioLeakCanaryVersion(newer))
  }

  @Test
  fun `older version is outdated`() {
    assertEquals("0.0.1", findOutdatedStudioLeakCanaryVersion("0.0.1"))
  }

  @Test
  fun `alpha of the minimum version is outdated`() {
    val alpha = "$MIN_STUDIO_LEAKCANARY_VERSION-alpha01"
    assertEquals(alpha, findOutdatedStudioLeakCanaryVersion(alpha))
  }

  @Test
  fun `empty version means 1_0_0`() {
    // The first number that differs decides which version is higher.
    val isMinAbove1_0_0 = minNumbers.zip(listOf(1, 0, 0)).firstOrNull { (min, one) -> min != one }?.let { (min, one) -> min > one } ?: false
    assertEquals(if (isMinAbove1_0_0) "1.0.0" else null, findOutdatedStudioLeakCanaryVersion(""))
  }

  @Test
  fun `version that can't be read is not checked`() {
    assertNull(findOutdatedStudioLeakCanaryVersion("abc"))
  }

  @Test
  fun `saved version is kept for each app process`() {
    val preferences = FakeProfilerPreferences()
    assertNull(loadStudioLeakCanaryVersion(preferences, streamId = 1, pid = 10))

    saveStudioLeakCanaryVersion(preferences, streamId = 1, pid = 10, version = "")
    saveStudioLeakCanaryVersion(preferences, streamId = 1, pid = 20, version = "1.1.0")

    assertEquals("", loadStudioLeakCanaryVersion(preferences, streamId = 1, pid = 10))
    assertEquals("1.1.0", loadStudioLeakCanaryVersion(preferences, streamId = 1, pid = 20))
    assertNull(loadStudioLeakCanaryVersion(preferences, streamId = 2, pid = 10))
  }
}
