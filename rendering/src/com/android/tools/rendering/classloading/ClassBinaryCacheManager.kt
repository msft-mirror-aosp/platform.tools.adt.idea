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
import com.google.common.cache.CacheBuilder
import java.time.Duration
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.annotations.TestOnly

private const val MAX_WEIGHT_BYTES = 100_000_000L // We will store no more than 100Mb of cached classes
private const val EXPIRE_MINUTES = 30L // We will store cached classes for no longer than 30 minutes

/** A class binary representation cache. */
class ClassBinaryCacheManager private constructor(ticker: Ticker, maxWeight: Long, expireMinutes: Long) {
  @GuardedBy("this") private val scopeCaches = WeakHashMap<Any, ModuleClassCache>()
  private val lock = ReentrantLock()
  /** A mapping from a library path to the caching keys of all the classes cached from this library. */
  @GuardedBy("lock") private val libraryPath2CachingKeys = mutableMapOf<String, MutableSet<String>>()
  /** A mapping from a caching key to a library (path) that contains the class. */
  @GuardedBy("lock") private val cachingKey2LibraryPath = mutableMapOf<String, String>()

  /** A binary representation class cache ($transformationId:$fqcn -> bytes). */
  private val globalCache =
    CacheBuilder.newBuilder()
      .ticker(ticker)
      .concurrencyLevel(1)
      .maximumWeight(maxWeight)
      .weigher { _: String, value: ByteArray -> value.size }
      .expireAfterAccess(Duration.ofMinutes(expireMinutes))
      .removalListener<String, ByteArray> {
        lock.withLock { cachingKey2LibraryPath.remove(it.key)?.let { url -> libraryPath2CachingKeys[url]?.remove(it.key) } }
      }
      .build<String, ByteArray>()

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

  private inner class ModuleClassCache : ClassBinaryCache {
    @GuardedBy("this") private var libraryPaths = setOf<String>()

    /** Synchronously checks if there a library with [path] among the current module dependencies. */
    @Synchronized private fun notCurrentDependency(path: String?) = path !in libraryPaths

    // @LayoutlibRenderThread
    override fun get(fqcn: String, transformationId: String): ByteArray? {
      val key = getCachingKey(fqcn, transformationId)
      // If the library the class was cached from is not among the current dependencies of this module, the whole library is stale, so all
      // the classes cached from it are dropped.
      val libraryPath = lock.withLock { cachingKey2LibraryPath[key] }
      if (notCurrentDependency(libraryPath)) {
        libraryPath?.let { lock.withLock { libraryPath2CachingKeys.remove(libraryPath) }?.forEach { globalCache.invalidate(it) } }
        return null
      }

      return globalCache.getIfPresent(key)
    }

    private fun getCachingKey(fqcn: String, transformationId: String) = "$transformationId:$fqcn"

    // @LayoutlibRenderThread
    override fun put(fqcn: String, transformationId: String, libraryPath: String, data: ByteArray) {
      val key = getCachingKey(fqcn, transformationId)
      lock.withLock {
        cachingKey2LibraryPath[key] = libraryPath
        libraryPath2CachingKeys.computeIfAbsent(libraryPath) { mutableSetOf() }.add(key)
      }
      globalCache.put(key, data)
    }

    @AnyThread
    @Synchronized
    override fun setDependencies(paths: Collection<String>) {
      libraryPaths = paths.toSet()
    }
  }

  companion object {
    private val globalManager = ClassBinaryCacheManager(Ticker.systemTicker(), MAX_WEIGHT_BYTES, EXPIRE_MINUTES)

    @JvmStatic fun getInstance() = globalManager

    @TestOnly
    fun getTestInstance(ticker: Ticker, maxWeight: Long, expireMinutes: Long) = ClassBinaryCacheManager(ticker, maxWeight, expireMinutes)
  }
}
