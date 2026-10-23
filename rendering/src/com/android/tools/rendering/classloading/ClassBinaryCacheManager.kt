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

import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.GuardedBy
import com.google.common.base.Ticker
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalCause
import com.intellij.openapi.util.LowMemoryWatcher
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.Duration
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.concurrent.withLock
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting

private const val MAX_COMPRESSED_WEIGHT_BYTES = 100_000_000L // We will store no more than 100Mb of compressed cached classes in L2
private const val MAX_UNCOMPRESSED_WEIGHT_BYTES = 10_000_000L // We will store no more than 10Mb of uncompressed cached classes in L1
private const val EXPIRE_MINUTES = 30L // We will store cached classes for no longer than 30 minutes

/** Number of bytes used to store the uncompressed size at the beginning of a compressed entry. */
private const val UNCOMPRESSED_SIZE_HEADER_BYTES = 4

/**
 * Compresses the given class [data] for storage in the cache. The returned array is the uncompressed size, encoded as a big-endian int,
 * followed by the deflated contents. [Deflater.BEST_SPEED] is used since this runs as part of class loading, and class files still compress
 * to roughly half their size at that level.
 */
@VisibleForTesting
internal fun compress(data: ByteArray): ByteArray {
  val deflater = Deflater(Deflater.BEST_SPEED)
  try {
    val baos = ByteArrayOutputStream(UNCOMPRESSED_SIZE_HEADER_BYTES + data.size / 2)
    DataOutputStream(baos).use { dos ->
      dos.writeInt(data.size)
      DeflaterOutputStream(dos, deflater).use { it.write(data) }
    }
    return baos.toByteArray()
  } finally {
    deflater.end()
  }
}

/** Reverses [compress]. */
@VisibleForTesting
internal fun decompress(stored: ByteArray): ByteArray {
  if (stored.size < UNCOMPRESSED_SIZE_HEADER_BYTES) {
    throw IOException("Malformed compressed cache entry: size ${stored.size} < $UNCOMPRESSED_SIZE_HEADER_BYTES")
  }
  val uncompressedSize =
    ((stored[0].toInt() and 0xFF) shl 24) or
      ((stored[1].toInt() and 0xFF) shl 16) or
      ((stored[2].toInt() and 0xFF) shl 8) or
      (stored[3].toInt() and 0xFF)
  if (uncompressedSize < 0) {
    throw IOException("Malformed compressed cache entry: negative uncompressed size $uncompressedSize")
  }
  val result = ByteArray(uncompressedSize)
  val inflater = Inflater()
  try {
    inflater.setInput(stored, UNCOMPRESSED_SIZE_HEADER_BYTES, stored.size - UNCOMPRESSED_SIZE_HEADER_BYTES)
    var offset = 0
    while (offset < uncompressedSize) {
      val read = inflater.inflate(result, offset, uncompressedSize - offset)
      if (read == 0) {
        throw IOException("Truncated compressed cache entry: expected $uncompressedSize bytes, got $offset")
      }
      offset += read
    }
    return result
  } finally {
    inflater.end()
  }
}

/** Cached library class entry storing its originating [libraryPath] alongside its bytecode [data]. */
private class CacheEntry(val libraryPath: String, val data: ByteArray)

/**
 * A two-tier class binary representation cache:
 * - **L1 ([l1Cache])**: Bounded by [maxUncompressedWeight] (weighed by raw uncompressed `ByteArray.size` so heap snapshots track
 *   its exact memory footprint) for zero-decompression warm reads. Size-evicted L1 entries are compressed and demoted into
 *   [l2Cache], and [lowMemoryWatcher] demotes all L1 entries to L2 under IDE memory pressure.
 * - **L2 ([l2Cache])**: Bounded by [maxCompressedWeight] (weighed by compressed `ByteArray.size`), storing `Deflater.BEST_SPEED`-compressed
 *   bytecode.
 *
 * A cached key resides in either [l1Cache] or [l2Cache], never both simultaneously.
 */
class ClassBinaryCacheManager
private constructor(
  private val ticker: Ticker,
  maxCompressedWeight: Long,
  private val expireMinutes: Long,
  private val maxUncompressedWeight: Long = MAX_UNCOMPRESSED_WEIGHT_BYTES,
) {
  @GuardedBy("this") private val scopeCaches = WeakHashMap<Any, ModuleClassCache>()
  private val lock = ReentrantLock()
  /** A mapping from a library path to the caching keys of all the classes cached from this library. */
  @GuardedBy("lock") private val libraryPath2CachingKeys = mutableMapOf<String, MutableSet<String>>()
  /** Current total weight (in bytes) of entries residing in [l1Cache]. */
  @Volatile private var currentUncompressedWeight = 0L

  @Suppress("unused")
  private val lowMemoryWatcher = LowMemoryWatcher.register(::evictUncompressedCache, LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC)

  private fun createCacheBuilder(maxWeight: Long): CacheBuilder<String, CacheEntry> =
    CacheBuilder.newBuilder()
      .ticker(ticker)
      .concurrencyLevel(1)
      .maximumWeight(maxWeight)
      .weigher { _: String, value: CacheEntry -> value.data.size }
      .expireAfterAccess(Duration.ofMinutes(expireMinutes))

  /**
   * L1 uncompressed class cache (`$transformationId:$fqcn` -> uncompressed [CacheEntry]).
   *
   * When an entry is evicted due to [RemovalCause.SIZE], it is compressed and demoted to [l2Cache].
   */
  private val l1Cache: Cache<String, CacheEntry> =
    createCacheBuilder(maxUncompressedWeight)
      .removalListener<String, CacheEntry> { notification ->
        lock.withLock {
          val key = notification.key ?: return@removalListener
          val entry = notification.value ?: return@removalListener
          currentUncompressedWeight = (currentUncompressedWeight - entry.data.size).coerceAtLeast(0L)
          when (notification.cause) {
            RemovalCause.REPLACED -> return@removalListener
            RemovalCause.SIZE -> {
              l2Cache.put(key, CacheEntry(entry.libraryPath, compress(entry.data)))
            }
            RemovalCause.EXPIRED,
            RemovalCause.COLLECTED,
            RemovalCause.EXPLICIT -> {
              if (l2Cache.getIfPresent(key) == null) {
                removeKeyFromLibrary(entry.libraryPath, key)
              }
            }
          }
        }
      }
      .build()

  /** L2 compressed class cache (`$transformationId:$fqcn` -> compressed [CacheEntry]). */
  private val l2Cache: Cache<String, CacheEntry> =
    createCacheBuilder(maxCompressedWeight)
      .removalListener<String, CacheEntry> { notification ->
        lock.withLock {
          val key = notification.key ?: return@removalListener
          val entry = notification.value ?: return@removalListener
          if (notification.cause == RemovalCause.REPLACED) return@removalListener
          if (l1Cache.getIfPresent(key) == null) {
            removeKeyFromLibrary(entry.libraryPath, key)
          }
        }
      }
      .build()

  @GuardedBy("lock")
  private fun removeKeyFromLibrary(libraryPath: String, key: String) {
    libraryPath2CachingKeys[libraryPath]?.let { keys ->
      keys.remove(key)
      if (keys.isEmpty()) libraryPath2CachingKeys.remove(libraryPath)
    }
  }

  private fun invalidateLibrary(libraryPath: String) {
    lock.withLock {
      libraryPath2CachingKeys.remove(libraryPath)?.forEach { key ->
        l1Cache.invalidate(key)
        l2Cache.invalidate(key)
      }
    }
  }

  /**
   * Compresses and demotes all entries currently in [l1Cache] into [l2Cache], freeing the uncompressed L1
   * footprint under memory pressure while retaining the compressed bytecode in L2.
   */
  @VisibleForTesting
  fun evictUncompressedCache() {
    val entriesToCompress = lock.withLock {
      l1Cache.asMap().entries.map { (key, entry) -> Triple(key, entry.libraryPath, entry.data) }
    }
    val compressedEntries = entriesToCompress.map { (key, libraryPath, data) ->
      Triple(key, libraryPath, compress(data))
    }
    lock.withLock {
      for ((key, libraryPath, compressedData) in compressedEntries) {
        if (l1Cache.getIfPresent(key) != null) {
          l2Cache.put(key, CacheEntry(libraryPath, compressedData))
          l1Cache.invalidate(key)
        }
      }
    }
  }

  /**
   * Returns a scope specific cache that will only return classes if they belong to the scope, the cache will also invalidate cache for
   * dated classes.
   */
  @Synchronized
  @AnyThread
  fun getCache(scope: Any): ClassBinaryCache {
    return scopeCaches.computeIfAbsent(scope) { ModuleClassCache() }
  }

  @TestOnly
  fun getCachedKeysForLibrary(libraryPath: String): Set<String> = lock.withLock {
    libraryPath2CachingKeys[libraryPath]?.toSet() ?: emptySet()
  }

  @TestOnly
  fun isInUncompressedCacheForTest(fqcn: String, transformationId: String = ""): Boolean =
    l1Cache.getIfPresent("$transformationId:$fqcn") != null

  @TestOnly
  fun isInCompressedCacheForTest(fqcn: String, transformationId: String = ""): Boolean =
    l2Cache.getIfPresent("$transformationId:$fqcn") != null

  @TestOnly fun getUncompressedWeightForTest(): Long = lock.withLock { currentUncompressedWeight }

  private inner class ModuleClassCache : ClassBinaryCache {
    @Volatile private var libraryPaths = setOf<String>()

    // @LayoutlibRenderThread
    override fun get(fqcn: String, transformationId: String): ByteArray? {
      val key = getCachingKey(fqcn, transformationId)
      l1Cache.getIfPresent(key)?.let { entry ->
        // If the library the class was cached from is not among the current dependencies of this
        // module, the whole library is stale, so all the classes cached from it are dropped.
        if (entry.libraryPath !in libraryPaths) {
          invalidateLibrary(entry.libraryPath)
          return null
        }
        return entry.data
      }

      val compressedEntry = l2Cache.getIfPresent(key) ?: return null
      if (compressedEntry.libraryPath !in libraryPaths) {
        invalidateLibrary(compressedEntry.libraryPath)
        return null
      }

      val decompressed = decompress(compressedEntry.data)
      // Scan-resistant promotion: only promote to L1 if L1 has spare weight capacity.
      // Evicting an LRU entry from L1 on get() when L1 is full would cause cyclic compression
      // churn whenever the working set of a render pass exceeds L1 capacity.
      if (maxUncompressedWeight > 0 && currentUncompressedWeight + decompressed.size <= maxUncompressedWeight) {
        lock.withLock {
          if (
            currentUncompressedWeight + decompressed.size <= maxUncompressedWeight &&
              l1Cache.getIfPresent(key) == null &&
              l2Cache.getIfPresent(key) != null
          ) {
            currentUncompressedWeight += decompressed.size
            l1Cache.put(key, CacheEntry(compressedEntry.libraryPath, decompressed))
            l2Cache.invalidate(key)
          }
        }
      }
      return decompressed
    }

    private fun getCachingKey(fqcn: String, transformationId: String) = "$transformationId:$fqcn"

    // @LayoutlibRenderThread
    override fun put(fqcn: String, transformationId: String, libraryPath: String, data: ByteArray) {
      val key = getCachingKey(fqcn, transformationId)
      val fitsInL1 = maxUncompressedWeight > 0 && data.size <= maxUncompressedWeight
      // If the entry does not fit in L1, compress it outside the lock to avoid blocking other threads.
      val compressedData = if (!fitsInL1) compress(data) else null

      lock.withLock {
        // Track the previous library origin if this class was already cached in either tier,
        // so we can clean up stale reverse mappings if the origin library changed.
        val oldLibrary = l1Cache.getIfPresent(key)?.libraryPath ?: l2Cache.getIfPresent(key)?.libraryPath

        // When L1 uncompressed caching is enabled and this entry fits within the total L1 capacity,
        // place it in L1 (uncompressed). Otherwise, store it compressed in L2.
        if (fitsInL1) {
          // Enforce exclusive residency: remove from L2 if previously present.
          l2Cache.invalidate(key)
          // Optimistically account for uncompressed weight. If inserting this entry triggers
          // size eviction in l1Cache, the removalListener automatically decrements
          // currentUncompressedWeight, compresses the evicted entries, and demotes them to L2.
          currentUncompressedWeight += data.size
          l1Cache.put(key, CacheEntry(libraryPath, data))
        } else {
          // Entry exceeds L1 capacity or L1 is disabled: invalidate any stale L1 entry and store
          // compressed in L2.
          l1Cache.invalidate(key)
          l2Cache.put(key, CacheEntry(libraryPath, compressedData!!))
        }

        // If the class is now associated with a different library path, remove the key from the old
        // library's key set.
        if ((oldLibrary != null) && (oldLibrary != libraryPath)) {
          removeKeyFromLibrary(oldLibrary, key)
        }

        // Maintain reverse index from libraryPath -> keys for dependency invalidation, provided
        // the entry was actually retained in either tier.
        if (l1Cache.getIfPresent(key) != null || l2Cache.getIfPresent(key) != null) {
          libraryPath2CachingKeys.computeIfAbsent(libraryPath) { mutableSetOf() }.add(key)
        }
      }
    }

    @AnyThread
    @Synchronized
    override fun setDependencies(paths: Collection<String>) {
      libraryPaths = paths.toSet()
    }
  }

  companion object {
    private val globalManager =
      ClassBinaryCacheManager(
        Ticker.systemTicker(),
        MAX_COMPRESSED_WEIGHT_BYTES,
        EXPIRE_MINUTES,
        MAX_UNCOMPRESSED_WEIGHT_BYTES,
      )

    @JvmStatic fun getInstance() = globalManager

    @TestOnly
    @JvmOverloads
    fun getTestInstance(
      ticker: Ticker,
      maxCompressedWeight: Long,
      expireMinutes: Long,
      maxUncompressedWeight: Long = 0L,
    ) = ClassBinaryCacheManager(ticker, maxCompressedWeight, expireMinutes, maxUncompressedWeight)
  }
}
