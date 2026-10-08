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
package com.android.tools.idea.sdk.wizard

import com.android.repository.api.DelegatingProgressIndicator
import com.android.repository.api.ProgressIndicator

/**
 * A [ProgressIndicator] that retains the log output it passes on to [wrapped], so that it can be shown after the fact.
 *
 * This is useful for operations that outlive the UI that was displaying their progress: once that UI is gone, the user has no other way to
 * see the output after the operation finishes.
 */
class LogCollectingProgressIndicator(wrapped: ProgressIndicator) : DelegatingProgressIndicator(wrapped) {
  private val builder = StringBuilder()

  /** The log output so far. If it grew past [MAX_LENGTH], the beginning has been dropped. */
  val log: String
    get() = synchronized(builder) { builder.toString() }

  override fun logWarning(s: String) {
    append(s)
    super.logWarning(s)
  }

  override fun logWarning(s: String, e: Throwable?) {
    append(s, e)
    super.logWarning(s, e)
  }

  override fun logError(s: String) {
    append(s)
    super.logError(s)
  }

  override fun logError(s: String, e: Throwable?) {
    append(s, e)
    super.logError(s, e)
  }

  override fun logInfo(s: String) {
    append(s)
    super.logInfo(s)
  }

  private fun append(s: String, e: Throwable? = null) {
    synchronized(builder) {
      // Messages are inconsistent about trailing newlines; normalize to exactly one so the output doesn't have arbitrary blank lines.
      builder.append(s.trimEnd('\n')).append('\n')
      if (e != null) {
        builder.append(e.stackTraceToString())
      }
      if (builder.length > MAX_LENGTH) {
        builder.delete(0, builder.length - MAX_LENGTH)
      }
    }
  }

  private companion object {
    /** An install logs a line per downloaded chunk and per extracted file, so this can get long; only the end is likely to be useful. */
    private const val MAX_LENGTH = 100_000
  }
}
