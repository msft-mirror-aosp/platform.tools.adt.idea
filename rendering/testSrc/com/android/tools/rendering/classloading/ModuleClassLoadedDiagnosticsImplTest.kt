/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.rendering.classloading

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class ModuleClassLoadedDiagnosticsImplTest {
  @Test
  fun testCounters() {
    val diagnostics = ModuleClassLoadedDiagnosticsImpl()
    diagnostics.classFindStart("A")
    diagnostics.classFindEnd("A", true, TimeUnit.MICROSECONDS.toNanos(1000_500))
    diagnostics.classRewritten("A", 50, TimeUnit.MILLISECONDS.toNanos(1000))
    diagnostics.classFindStart("B")
    diagnostics.classFindEnd("B", true, TimeUnit.MICROSECONDS.toNanos(300_200))
    diagnostics.classRewritten("B", 50, TimeUnit.MILLISECONDS.toNanos(200))
    diagnostics.classFindStart("C")
    diagnostics.classFindEnd("C", true, TimeUnit.MICROSECONDS.toNanos(500_100))

    assertEquals(3, diagnostics.classesFound)
    assertEquals(1800, diagnostics.accumulatedFindTimeMs)
    assertEquals(1800_800, diagnostics.accumulatedFindTimeUs)
    assertEquals(1200, diagnostics.accumulatedRewriteTimeMs)
  }

  @Test
  fun testUnfoundClassesIgnored() {
    val diagnostics = ModuleClassLoadedDiagnosticsImpl()
    diagnostics.classFindStart("A")
    diagnostics.classFindStart("MissingChild")
    diagnostics.classFindEnd("MissingChild", false, TimeUnit.MICROSECONDS.toNanos(200))
    diagnostics.classFindEnd("A", true, TimeUnit.MICROSECONDS.toNanos(500))

    diagnostics.classFindStart("TopLevelMissing")
    diagnostics.classFindEnd("TopLevelMissing", false, TimeUnit.MICROSECONDS.toNanos(400))

    assertEquals(1, diagnostics.classesFound)
    assertEquals(300, diagnostics.accumulatedFindTimeUs)
  }

  @Test
  fun testHierarchicalTimeCounter() {
    /** Simple extension to allow adding counters without any children. */
    fun HierarchicalTimeCounter.add(value: Long) {
      start("")
      end("", value)
    }

    val counter = HierarchicalTimeCounter()
    counter.start("A")
    counter.start("B")
    counter.add(100L)
    counter.add(100L)
    assertEquals("Self time is expected to be 100ms", 100L, counter.end("B", 300L))
    counter.add(100L)
    counter.add(100L)
    assertEquals(400, counter.end("A", 900L))
  }

  @Test
  fun testHierarchicalTimeCounterUnmatchedEntry() {
    val counter = HierarchicalTimeCounter()
    counter.start("A")
    counter.start("B")
    assertThrows(IllegalStateException::class.java) { counter.end("A", 100L) }
  }
}
