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

import com.intellij.openapi.diagnostic.Logger
import java.util.Stack
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import org.jetbrains.annotations.VisibleForTesting

/** Interface for reading [ModuleClassLoader] stats. */
interface ModuleClassLoaderDiagnosticsRead {
  /** The total number of classes loaded so far by the [ModuleClassLoader]. */
  val classesFound: Long

  /** The total time used finding classes by the [ModuleClassLoader] in milliseconds. */
  val accumulatedFindTimeMs: Long

  /** The total time used finding classes by the [ModuleClassLoader] in microseconds. */
  val accumulatedFindTimeUs: Long

  /** The total time used rewriting classes by the [ModuleClassLoader] in milliseconds. */
  val accumulatedRewriteTimeMs: Long
}

/** Interface for writing [ModuleClassLoader] stats. */
interface ModuleClassLoaderDiagnosticsWrite : ModuleClassLoaderDiagnosticsRead {
  /** Called when [ClassLoader.findClass] starts. */
  fun classFindStart(fqn: String)

  /**
   * Called when [ClassLoader.findClass] ends.
   *
   * @param fqn the Fully Qualified Name of the class.
   * @param wasFound true if the class was found or false otherwise.
   * @param timeNs time in nanoseconds that the lookup took.
   */
  fun classFindEnd(fqn: String, wasFound: Boolean, timeNs: Long)

  /**
   * Called when a class has been rewritten.
   *
   * @param fqn the Fully Qualified Name of the class.
   * @param length size of the original class.
   * @param timeNs time in nanoseconds that the rewrite took.
   */
  fun classRewritten(fqn: String, length: Int, timeNs: Long)
}

/** Nop implementation of the stats, to use in production. */
object NopModuleClassLoadedDiagnostics : ModuleClassLoaderDiagnosticsWrite {
  override fun classFindStart(fqn: String) {}

  override fun classFindEnd(fqn: String, wasFound: Boolean, timeNs: Long) {}

  override fun classRewritten(fqn: String, length: Int, timeNs: Long) {}

  override val classesFound: Long = 0
  override val accumulatedFindTimeMs: Long = 0
  override val accumulatedFindTimeUs: Long = 0
  override val accumulatedRewriteTimeMs: Long = 0
}

/**
 * A counter that allows accounting for self time and total time. Self time is the time spent running the operation excluding the time used
 * by the children. Total time is self time + total time of all children. The counters do not have units so it's up to the client to make
 * sure the units are consistent.
 */
class HierarchicalTimeCounter {
  private var childrenTime = 0L

  /** Stack of the current running counts with pairs of the key and the current childrenTime. */
  private val counterStack: Stack<Pair<String, Long>> = Stack()

  @Synchronized
  fun start(key: String) {
    counterStack.push(key to childrenTime)
    childrenTime = 0
  }

  @Throws(java.lang.IllegalStateException::class)
  @Synchronized
  fun end(key: String, totalTime: Long): Long {
    val (poppedKey, siblingsTotalTime) = counterStack.pop()
    return if (key == poppedKey) {
      val selfTime = totalTime - childrenTime
      childrenTime = totalTime + siblingsTotalTime
      selfTime
    } else {
      Logger.getInstance(HierarchicalTimeCounter::class.java).warn("Unbalanced start/end calls. Received $key (expected $poppedKey)")
      throw IllegalStateException()
    }
  }
}

/** Implementation that records and saves the loading times and counts for classes. */
@VisibleForTesting
class ModuleClassLoadedDiagnosticsImpl : ModuleClassLoaderDiagnosticsWrite {
  /** [HierarchicalTimeCounter] for the find time. */
  private val totalFindTimeCounterNs = HierarchicalTimeCounter()

  /** Captures the total time of the find calls. */
  private val totalFindTimeNs = LongAdder()
  /** Counts the total number of classes found. */
  private val totalClassesFound = LongAdder()
  /** Counts the total time spent rewriting classes in this class loader. */
  private val totalRewriteTimeNs = LongAdder()

  override fun classFindStart(fqn: String) {
    totalFindTimeCounterNs.start(fqn)
  }

  override fun classFindEnd(fqn: String, wasFound: Boolean, timeNs: Long) {
    val selfTimeNs =
      try {
        totalFindTimeCounterNs.end(fqn, timeNs)
      } catch (_: IllegalStateException) {
        return
      }
    if (wasFound) {
      totalFindTimeNs.add(selfTimeNs)
      totalClassesFound.increment()
    }
  }

  override fun classRewritten(fqn: String, length: Int, timeNs: Long) {
    totalRewriteTimeNs.add(timeNs)
  }

  override val classesFound: Long
    get() = totalClassesFound.sum()

  override val accumulatedFindTimeMs: Long
    get() = TimeUnit.NANOSECONDS.toMillis(totalFindTimeNs.sum())

  override val accumulatedFindTimeUs: Long
    get() = TimeUnit.NANOSECONDS.toMicros(totalFindTimeNs.sum())

  override val accumulatedRewriteTimeMs: Long
    get() = TimeUnit.NANOSECONDS.toMillis(totalRewriteTimeNs.sum())
}
