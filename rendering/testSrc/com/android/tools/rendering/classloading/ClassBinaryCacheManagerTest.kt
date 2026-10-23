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

import com.android.tools.rendering.classloading.codeexecution.A
import com.google.common.base.Ticker
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.LowMemoryWatcher
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    // The cache stores the contents compressed and weighs the entries by their compressed size, so the test data needs to be
    // incompressible for the weight to be meaningful.
    val random = Random(0)

    moduleCache.put("a.b.c", "A", random.nextBytes(80))

    assertNotNull(moduleCache.get("a.b.c"))
    assertEquals(setOf(":a.b.c"), manager.getCachedKeysForLibrary("A"))

    moduleCache.put("a.b.d", "A", random.nextBytes(80))

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

  @Test
  fun testCompressionLosslessAndExecutable() {
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 100_000, 1)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("libA"))

    // 1. Test real compiled class bytecode
    val originalBytes = loadClassBytes(A::class.java)
    val fqcn = A::class.java.name
    moduleCache.put(fqcn, "trans1", "libA", originalBytes)

    val decompressedBytes = moduleCache.get(fqcn, "trans1")
    assertNotNull(decompressedBytes)
    assertTrue(originalBytes.contentEquals(decompressedBytes))

    // Verify the decompressed bytecode is valid JVM bytecode and can be loaded/executed
    val testClassLoader = TestClassLoader(mapOf(fqcn to decompressedBytes))
    val loadedClass = testClassLoader.loadClass(fqcn)
    assertNotNull(loadedClass)
    val method = loadedClass.getDeclaredMethod("intA")
    method.isAccessible = true
    assertEquals(0, method.invoke(null))

    // 2. Test roundtrip on various edge cases: empty array, single byte, repetitive data, incompressible data
    val edgeCases =
      listOf(
        "empty" to ByteArray(0),
        "single" to byteArrayOf(0x42),
        "repetitive" to ByteArray(4096) { 0x55 },
        "random" to Random(42).nextBytes(4096),
      )
    for ((name, data) in edgeCases) {
      val key = "test.$name"
      moduleCache.put(key, "trans1", "libA", data)
      val retrieved = moduleCache.get(key, "trans1")
      assertNotNull(retrieved)
      assertTrue(data.contentEquals(retrieved), "Data mismatch for case: $name")
    }
  }

  @Test
  fun testDecompressMalformedHeaderThrows() {
    // Shorter than 4-byte header
    assertFailsWith<IOException> { decompress(ByteArray(0)) }
    assertFailsWith<IOException> { decompress(ByteArray(3)) }

    // Negative uncompressed size (e.g. 0xFFFFFFFF = -1)
    assertFailsWith<IOException> { decompress(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) }
  }

  @Test
  fun testDecompressTruncatedDataThrows() {
    // Header indicates 100 bytes uncompressed, but no compressed payload follows
    val truncatedHeaderOnly = byteArrayOf(0, 0, 0, 100)
    assertFailsWith<IOException> { decompress(truncatedHeaderOnly) }

    // Valid compressed payload truncated halfway
    val original = ByteArray(100) { 0x42 }
    val compressed = compress(original)
    // Truncate compressed array so it cannot finish deflating all 100 bytes
    val truncated = compressed.copyOf(compressed.size / 2)
    assertFailsWith<IOException> { decompress(truncated) }
  }

  @Test
  fun testTwoTierCacheExclusiveResidencyAndDemotionOnOverflow() {
    // L1 capacity = 100 bytes uncompressed, L2 capacity = 1,000 bytes compressed
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 1_000, 1, maxUncompressedWeight = 100)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("libA"))

    val bytes1 = ByteArray(60) { 0x11 }
    val bytes2 = ByteArray(60) { 0x22 }

    // First entry (60 bytes <= 100) resides exclusively in L1 (uncompressed)
    moduleCache.put("com.example.Class1", "trans1", "libA", bytes1)
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isFalse()
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(60L)
    assertThat(moduleCache.get("com.example.Class1", "trans1")).isSameAs(bytes1)

    // Inserting second entry (60 bytes) exceeds L1 capacity (120 > 100), evicting Class1 from L1
    // and compressing/demoting it into L2 while Class2 remains in L1
    moduleCache.put("com.example.Class2", "trans1", "libA", bytes2)
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class1", "trans1")).isFalse()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class2", "trans1")).isTrue()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class2", "trans1")).isFalse()
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(60L)
    assertThat(manager.getCachedKeysForLibrary("libA")).containsExactly("trans1:com.example.Class1", "trans1:com.example.Class2")

    // Reading Class1 from L2 while L1 does not have spare capacity (60 + 60 > 100) decompresses
    // Class1 without evicting Class2 from L1 (preventing sequential-scan LRU thrashing)
    val retrieved1 = moduleCache.get("com.example.Class1", "trans1")
    assertThat(retrieved1).isEqualTo(bytes1)
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class2", "trans1")).isTrue()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
  }

  @Test
  fun testLowMemoryDemotesL1ToL2AndSubsequentGetPromotesWhenCapacityAvailable() {
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 1_000, 1, maxUncompressedWeight = 100)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("libA"))

    val bytes1 = ByteArray(40) { 0x33 }
    val bytes2 = ByteArray(40) { 0x44 }
    moduleCache.put("com.example.Class1", "trans1", "libA", bytes1)
    moduleCache.put("com.example.Class2", "trans1", "libA", bytes2)
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(80L)

    // Triggering low-memory signal demotes all L1 entries into L2 (compressed)
    LowMemoryWatcher.onLowMemorySignalReceived(true)
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(0L)
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class1", "trans1")).isFalse()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class2", "trans1")).isFalse()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class2", "trans1")).isTrue()
    assertThat(manager.getCachedKeysForLibrary("libA")).containsExactly("trans1:com.example.Class1", "trans1:com.example.Class2")

    // Because L1 now has spare capacity (0 + 40 <= 100), reading Class1 promotes it back to L1
    // and removes it from L2 so it is never stored in both simultaneously
    assertThat(moduleCache.get("com.example.Class1", "trans1")).isEqualTo(bytes1)
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isFalse()
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(40L)
  }

  @Test
  fun testInvalidateStaleLibraryPurgesBothL1AndL2() {
    val manager = ClassBinaryCacheManager.getTestInstance(ManualTicker(), 1_000, 1, maxUncompressedWeight = 100)
    val moduleCache = manager.getCache(Any())
    moduleCache.setDependencies(listOf("libA"))

    // Class1 gets demoted to L2, Class2 stays in L1
    moduleCache.put("com.example.Class1", "trans1", "libA", ByteArray(60) { 0x01 })
    moduleCache.put("com.example.Class2", "trans1", "libA", ByteArray(60) { 0x02 })
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isTrue()
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class2", "trans1")).isTrue()

    // Removing libA from dependencies and probing Class2 invalidates both L1 and L2 entries
    moduleCache.setDependencies(listOf("libB"))
    assertThat(moduleCache.get("com.example.Class2", "trans1")).isNull()
    assertThat(manager.isInUncompressedCacheForTest("com.example.Class2", "trans1")).isFalse()
    assertThat(manager.isInCompressedCacheForTest("com.example.Class1", "trans1")).isFalse()
    assertThat(manager.getUncompressedWeightForTest()).isEqualTo(0L)
    assertThat(manager.getCachedKeysForLibrary("libA")).isEmpty()
  }
}
