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

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.internal.ImmutableDomainObjectSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SynchronizedViewsTest {
  /** A model interface. The Tooling API creates views that implement it. */
  interface Node {
    val name: String
    val child: Node?
    val children: List<Node>
    val childSet: Set<Node>
    val childrenByName: Map<String, Node>
    val sortedChildrenByName: SortedMap<String, Node>
    val sortedNames: SortedSet<String>
  }

  /** Object behind a [Node] view. Like the objects built by model builders, it does not implement [Node]. */
  class NodeSource(private val nodeName: String, private val nodes: List<NodeSource> = emptyList(), private val onCall: () -> Unit = {}) {
    val name: String
      get() = nodeName.also { onCall() }

    val child: NodeSource?
      get() = nodes.firstOrNull().also { onCall() }

    val children: List<NodeSource>
      get() = nodes.also { onCall() }

    val childSet: Set<NodeSource>
      get() = nodes.toSet().also { onCall() }

    val childrenByName: Map<String, NodeSource>
      get() = nodes.associateBy { it.nodeName }.also { onCall() }

    val sortedChildrenByName: SortedMap<String, NodeSource>
      get() = nodes.associateByTo(TreeMap()) { it.nodeName }.also { onCall() }

    val sortedNames: SortedSet<String>
      get() = nodes.mapTo(TreeSet()) { it.nodeName }.also { onCall() }
  }

  private val graph = ProtocolToModelAdapter().newGraph()

  private fun view(source: NodeSource): Node = graph.adapt(Node::class.java, source)!!

  @Test
  fun `wraps views and the views and collections that they return`() {
    val node = view(NodeSource("parent", listOf(NodeSource("b"), NodeSource("a")))).synchronizedViews()

    assertSynchronized(node)
    assertEquals("parent", node.name)
    assertSynchronized(node.child!!)
    assertEquals("b", node.child!!.name)
    assertThat(node.children.map { it.name }).containsExactly("b", "a").inOrder()
    node.children.forEach(::assertSynchronized)
    assertThat(node.childSet.map { it.name }).containsExactly("b", "a").inOrder()
    node.childSet.forEach(::assertSynchronized)
    assertThat(node.childrenByName.mapValues { it.value.name }).containsExactly("b", "b", "a", "a").inOrder()
    node.childrenByName.values.forEach(::assertSynchronized)
    assertThat(node.sortedChildrenByName.mapValues { it.value.name }).containsExactly("a", "a", "b", "b").inOrder()
    assertThat(node.sortedChildrenByName).isInstanceOf(SortedMap::class.java)
    node.sortedChildrenByName.values.forEach(::assertSynchronized)
    assertThat(node.sortedNames).containsExactly("a", "b").inOrder()
    assertThat(node.sortedNames).isInstanceOf(SortedSet::class.java)
  }

  @Test
  fun `returns values that are not views as they are`() {
    val file = File("file")

    assertSame(file, file.synchronizedViews())
    assertSame("text", "text".synchronizedViews())
    assertNull((null as Node?).synchronizedViews())
    assertEquals(listOf("a", "b"), listOf("a", "b").synchronizedViews())
    val domainObjectSet: DomainObjectSet<String> = ImmutableDomainObjectSet.of(listOf("a"))
    assertSame(domainObjectSet, domainObjectSet.synchronizedViews())
  }

  @Test
  fun `wrappers of the same view are equal`() {
    val node = view(NodeSource("node"))
    val wrapper1 = node.synchronizedViews()
    val wrapper2 = node.synchronizedViews()

    assertNotSame(wrapper1, wrapper2)
    assertEquals(wrapper1, wrapper2)
    assertEquals(wrapper1.hashCode(), wrapper2.hashCode())
    assertEquals("value", mapOf(wrapper1 to "value")[wrapper2])
  }

  @Test
  fun `calls of the same view are serialized`() {
    val firstCallStarted = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val runningCalls = AtomicInteger()
    val maxRunningCalls = AtomicInteger()
    val source =
      NodeSource("parent", listOf(NodeSource("child"))) {
        maxRunningCalls.accumulateAndGet(runningCalls.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
          if (calls.incrementAndGet() == 1) {
            firstCallStarted.countDown()
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
          }
        } finally {
          runningCalls.decrementAndGet()
        }
      }
    val node = view(source).synchronizedViews()

    val nameTask = FutureTask { node.name }
    val nameThread = Thread(nameTask).apply { start() }
    assertTrue(firstCallStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    val childrenTask = FutureTask { node.children }
    val childrenThread = Thread(childrenTask).apply { start() }
    waitFor("the second call to wait for the first one") {
      childrenThread.state == Thread.State.BLOCKED && childrenThread.lockOwnerId() == nameThread.id
    }
    assertEquals(1, calls.get())
    release.countDown()

    assertEquals("parent", nameTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertThat(childrenTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).map { it.name }).containsExactly("child")
    assertEquals(1, maxRunningCalls.get())
    childrenThread.join()
  }

  @Test
  fun `calls of different views run in parallel`() {
    val bothCallsStarted = CountDownLatch(2)
    val otherCallStarted = mutableListOf<Boolean>()
    fun source(name: String) =
      NodeSource(name) {
        bothCallsStarted.countDown()
        val started = bothCallsStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(otherCallStarted) { otherCallStarted += started }
      }
    val (a, b) = view(NodeSource("parent", listOf(source("a"), source("b")))).synchronizedViews().children

    val tasks = listOf(FutureTask { a.name }, FutureTask { b.name })
    tasks.forEach { Thread(it).start() }

    assertThat(tasks.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }).containsExactly("a", "b").inOrder()
    assertThat(otherCallStarted).containsExactly(true, true)
  }

  private fun assertSynchronized(node: Node) {
    assertTrue(Proxy.isProxyClass(node.javaClass))
    assertTrue(node is SynchronizedView)
    // Only wrappers are returned as they are.
    assertSame(node, node.synchronizedViews())
  }
}

internal const val TIMEOUT_SECONDS = 30L

internal fun waitFor(description: String, condition: () -> Boolean) {
  val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
  while (!condition()) {
    if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $description")
    Thread.sleep(1)
  }
}

internal fun Thread.lockOwnerId(): Long = ManagementFactory.getThreadMXBean().getThreadInfo(id)?.lockOwnerId ?: -1
