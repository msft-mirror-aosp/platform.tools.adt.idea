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
package com.android.tools.idea.gradle.project.sync

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SynchronizedViewsIntegrationTest {
  interface Node {
    val name: String
  }

  @Test
  fun `concurrent reads of a view with a cache that is not thread-safe can return null`() {
    val view = UnsafeCachingView()
    val firstRead = FutureTask { view.node.name }
    Thread(firstRead).start()
    assertTrue(view.firstComputationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    // Reads the cache entry that the first read has not filled in yet, as can happen with the Tooling API's view cache (b/371925845).
    val secondReadResult: String? = view.node.name
    view.finishComputation.countDown()

    assertNull(secondReadResult)
    assertEquals("node", firstRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun `concurrent reads of a synchronized view with a cache that is not thread-safe return the value`() {
    val view = UnsafeCachingView()
    val node = view.node.synchronizedViews()
    val firstRead = FutureTask { node.name }
    val firstThread = Thread(firstRead).apply { start() }
    assertTrue(view.firstComputationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    val secondRead = FutureTask<String?> { node.name }
    val secondThread = Thread(secondRead).apply { start() }
    waitFor("the second read to wait for the first one") {
      secondThread.state == Thread.State.BLOCKED && secondThread.lockOwnerId() == firstThread.id
    }
    view.finishComputation.countDown()

    assertEquals("node", firstRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertEquals("node", secondRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  /**
   * A [Node] view whose handler caches property values in a [HashMap] without synchronization, like the views of the Tooling API do.
   *
   * The first read of a property puts a placeholder in the cache and waits for [finishComputation] before storing the value, so a read that
   * runs at the same time deterministically sees the placeholder.
   */
  private class UnsafeCachingView : InvocationHandler {
    val firstComputationStarted = CountDownLatch(1)
    val finishComputation = CountDownLatch(1)
    private val cache = HashMap<String, Any?>()

    val node: Node = Proxy.newProxyInstance(Node::class.java.classLoader, arrayOf(Node::class.java), this) as Node

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
      when (method.name) {
        "equals" -> return proxy === args!![0]
        "hashCode" -> return System.identityHashCode(proxy)
        "toString" -> return "UnsafeCachingView"
      }
      if (cache.containsKey(method.name)) return cache[method.name]
      cache[method.name] = null
      firstComputationStarted.countDown()
      finishComputation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      return "node".also { cache[method.name] = it }
    }
  }
}
