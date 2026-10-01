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
package com.android.screenshottest.ui

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** An executor that queues tasks until [runAll] is called, so tests control when background work happens. */
class ManualExecutorService : AbstractExecutorService() {
  private val tasks = ArrayDeque<Runnable>()

  val queuedCount: Int
    get() = tasks.size

  override fun execute(command: Runnable) {
    tasks.add(command)
  }

  /** Runs queued tasks, including tasks queued while running, until the queue is empty. */
  fun runAll() {
    while (tasks.isNotEmpty()) tasks.removeFirst().run()
  }

  override fun shutdown() {}

  override fun shutdownNow(): List<Runnable> = emptyList()

  override fun isShutdown() = false

  override fun isTerminated() = false

  override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
