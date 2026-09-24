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

  @Test
  fun testPutExistingKeyRetainsMetadataAndCanBeRetrieved() {
    val cacheKey = Any()
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100, 1)

    val moduleCache = manager.getCache(cacheKey)
    moduleCache.setDependencies(listOf("A"))

    val fqcn = "com.example.MyClass"
    val transformationId = "trans1"
    val libraryPath = "A"

    // First put: stores class data and registers key in metadata maps
    moduleCache.put(fqcn, transformationId, libraryPath, "v1".toByteArray())
    assertEquals("v1", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary(libraryPath))

    // Second put for the exact same key (e.g. reload or across multiple class loaders / threads)
    moduleCache.put(fqcn, transformationId, libraryPath, "v2".toByteArray())

    // Replacing an existing key in the cache must not cause the removalListener to purge
    // the entry's metadata from cachingKey2LibraryPath or libraryPath2CachingKeys.
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary(libraryPath))
    assertEquals("v2", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))

    // Replacement when libraryPath changes (e.g. from "A" to "B"), verifying that "A"'s
    // cached key set is emptied, "B" now owns the key, and the new value can be retrieved
    moduleCache.setDependencies(listOf("B"))
    moduleCache.put(fqcn, transformationId, "B", "v3".toByteArray())
    assertEquals(emptySet(), manager.getCachedKeysForLibrary("A"))
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary("B"))
    assertEquals("v3", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))
  }

  @Test
  fun testPutChangingLibraryPathCleansUpOldMetadata() {
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100, 1)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("A", "B"))

    val fqcn = "com.example.MyClass"
    val transformationId = "trans1"

    moduleCache.put(fqcn, transformationId, "A", "v1".toByteArray())
    moduleCache.put(fqcn, transformationId, "B", "v2".toByteArray())

    assertEquals(emptySet(), manager.getCachedKeysForLibrary("A"))
    assertEquals(setOf("trans1:com.example.MyClass"), manager.getCachedKeysForLibrary("B"))
    assertEquals("v2", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))
  }

  @Test
  fun testPutAfterEntryExpiresPreservesMetadata() {
    val manualTicker = ManualTicker()
    val manager = ClassBinaryCacheManager.getTestInstance(manualTicker, 100, 1)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("A", "B"))

    val fqcn = "com.example.MyClass"
    val transformationId = "trans1"

    // First put: stores class data and registers key in metadata maps
    moduleCache.put(fqcn, transformationId, "A", "v1".toByteArray())
    assertEquals("v1", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary("A"))

    // Advance ticker beyond expiration (2 minutes > 1 minute expiration)
    manualTicker.timeNanos += 2L * 60L * 1000_000_000L

    // Second put for the same key after expiration: Guava's put triggers preWriteCleanup,
    // evicting the expired entry with RemovalCause.EXPIRED before inserting the new entry.
    moduleCache.put(fqcn, transformationId, "A", "v2".toByteArray())

    // Metadata maps must reflect the newly inserted entry and the value must be retrievable
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary("A"))
    assertEquals("v2", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))

    // Advance ticker again and put with a different library
    manualTicker.timeNanos += 2L * 60L * 1000_000_000L
    moduleCache.put(fqcn, transformationId, "B", "v3".toByteArray())

    assertEquals(emptySet(), manager.getCachedKeysForLibrary("A"))
    assertEquals(setOf("$transformationId:$fqcn"), manager.getCachedKeysForLibrary("B"))
    assertEquals("v3", moduleCache.get(fqcn, transformationId)?.toString(Charsets.UTF_8))
  }
}
