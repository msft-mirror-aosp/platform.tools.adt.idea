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
package com.android.tools.asdriver.tests

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUsageReportProcessorTest {
  @Test
  fun skipsCollectionWhenNotEnabled() {
    assertFalse(shouldCollectMemoryUsageStatistics(systemProperty = null, environmentVariable = null))
    assertFalse(shouldCollectMemoryUsageStatistics(systemProperty = "false", environmentVariable = "false"))
  }

  @Test
  fun collectsWhenEnabledViaSystemProperty() {
    assertTrue(shouldCollectMemoryUsageStatistics(systemProperty = "true", environmentVariable = null))
  }

  @Test
  fun collectsWhenEnabledViaEnvironmentVariable() {
    assertTrue(shouldCollectMemoryUsageStatistics(systemProperty = null, environmentVariable = "true"))
  }
}
