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
package com.android.tools.idea.gradle.util

import com.android.ide.common.repository.AgpVersion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class KotlinGradleProjectSystemUtilTest {

  @Test
  fun testAgpVersionGetKotlinVersion() {
    assertNull(AgpVersion.parse("8.0.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.0.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.1.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.2.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.3.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.4.0").getKotlinVersion())
    assertEquals("2.2.10", AgpVersion.parse("9.5.0").getKotlinVersion())
    assertEquals(AGP_BUILT_IN_KOTLIN_VERSION, AgpVersion.parse("9.6.0-alpha01").getKotlinVersion())
    assertEquals(AGP_BUILT_IN_KOTLIN_VERSION, AgpVersion.parse("100.0.0").getKotlinVersion())
    assertEquals("2.2.10", AGP_MINIMUM_BUILT_IN_KOTLIN_VERSION)
    assertEquals("2.4.20", AGP_BUILT_IN_KOTLIN_VERSION)
  }

  @Test
  fun testShouldUseKotlinCompilerOptionsDsl_below220() {
    assertFalse(shouldUseKotlinCompilerOptionsDsl("1.7.20"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("1.8.10"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("1.9.0"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("2.0.21"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("2.1.0"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("2.1.20"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("2.1.20-RC"))
  }

  @Test
  fun testShouldUseKotlinCompilerOptionsDsl_220AndAbove() {
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.2.0-dev-1234"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.2.0-Beta1"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.2.0-RC"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.2.0"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.2.10"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.3.0"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.3.10"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("2.5.0-Beta2"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl("3.0.0"))
    assertTrue(shouldUseKotlinCompilerOptionsDsl(" 2.2.0 "))
  }

  @Test
  fun testShouldUseKotlinCompilerOptionsDsl_nullOrInvalid() {
    assertFalse(shouldUseKotlinCompilerOptionsDsl(null))
    assertFalse(shouldUseKotlinCompilerOptionsDsl(""))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("   "))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("+"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("invalid"))
    assertFalse(shouldUseKotlinCompilerOptionsDsl("2"))
  }
}
