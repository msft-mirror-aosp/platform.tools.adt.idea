/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.google.common.base.Ticker
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class ClassBinaryCacheManagerTest {
  private class ManualTicker : Ticker() {
    var timeNanos = 0L

    override fun read(): Long = timeNanos
  }

  @Test
  fun testPutAndGet() {
    val cacheKey = Any()
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A", "B"))

    moduleCache.put("a.b.c", "A", "hello".toByteArray())
    moduleCache.put("d.e.f", "B", "bye".toByteArray())

    assertEquals("hello", moduleCache.get("a.b.c")?.toString(Charsets.UTF_8))
    assertEquals("bye", moduleCache.get("d.e.f")?.toString(Charsets.UTF_8))

    val sameModuleCache = manager.getCache(cacheKey)

    assertEquals("hello", sameModuleCache.get("a.b.c")?.toString(Charsets.UTF_8))
    assertEquals("bye", sameModuleCache.get("d.e.f")?.toString(Charsets.UTF_8))
  }

  @Test
  fun testInvalidateWhenDependenciesChanged() {
    val cacheKey = Any()
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A"))

    moduleCache.put("a.b.c", "A", "hello".toByteArray())

    val sameModuleCache = manager.getCache(cacheKey)
    sameModuleCache.setDependencies(listOf("B"))

    assertNull(sameModuleCache.get("a.b.c"))
  }

  @Test
  fun testInvalidateWholeLibraryWhenOneClassIsStale() {
    val cacheKey = Any()
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 1000, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A"))

    moduleCache.put("a.b.c", "trans1", "A", "hello".toByteArray())
    moduleCache.put("a.b.d", "trans1", "A", "world".toByteArray())

    // Dependencies change to B (library A is no longer a dependency)
    moduleCache.setDependencies(listOf("B"))

    // Reading a.b.c detects that library A is stale and must invalidate all classes from library A in the cache
    assertNull(moduleCache.get("a.b.c", "trans1"))

    // Dependencies change back to A
    moduleCache.setDependencies(listOf("A"))

    // a.b.d was also from library A, so it must have been evicted when library A was invalidated
    assertNull(moduleCache.get("a.b.d", "trans1"))
  }

  @Test
  fun testInvalidateWhenOverweight() {
    val cacheKey = Any()
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A"))

    moduleCache.put("a.b.c", "A", ByteArray(80))

    assertNotNull(moduleCache.get("a.b.c"))
    assertEquals(setOf(":a.b.c"), manager.getCachedKeysForLibrary("A"))

    moduleCache.put("a.b.d", "A", ByteArray(80))

    assertNull(moduleCache.get("a.b.c"))
    // Ensure the removal listener cleaned up the evicted entry from the library mapping.
    assertEquals(setOf(":a.b.d"), manager.getCachedKeysForLibrary("A"))
  }

  @Test
  fun testInvalidateWhenTimeout() {
    val cacheKey = Any()
    val manualTicker = ManualTicker()
    val manager = ClassBinaryCacheManager.getTestInstance(manualTicker, 100, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A"))

    moduleCache.put("a.b.c", "A", ByteArray(80))

    assertNotNull(moduleCache.get("a.b.c"))

    manualTicker.timeNanos += 2L * 60L * 1000_000_000L // In 2 minutes

    assertNull(moduleCache.get("a.b.c"))
  }
}
