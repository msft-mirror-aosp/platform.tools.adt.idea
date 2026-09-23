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

import com.android.repository.api.ProgressIndicator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests for [com.android.tools.idea.sdk.wizard.LogCollectingProgressIndicator] */
class LogCollectingProgressIndicatorTest {
  @Test
  fun testLogIsCollectedAndPassedOn() {
    val wrapped = RecordingProgressIndicator()
    val indicator = LogCollectingProgressIndicator(wrapped)

    indicator.logInfo("Downloading")
    indicator.logWarning("Retrying")
    indicator.logError("Gave up")

    assertThat(indicator.log).isEqualTo("Downloading\nRetrying\nGave up\n")
    assertThat(wrapped.messages).containsExactly("Downloading", "Retrying", "Gave up").inOrder()
  }

  @Test
  fun testMessagesAreSeparatedByExactlyOneNewline() {
    val indicator = LogCollectingProgressIndicator(RecordingProgressIndicator())

    indicator.logInfo("With a newline\n")
    indicator.logInfo("Without one")

    assertThat(indicator.log).isEqualTo("With a newline\nWithout one\n")
  }

  @Test
  fun testExceptionsAreIncluded() {
    val indicator = LogCollectingProgressIndicator(RecordingProgressIndicator())

    indicator.logWarning("Failed", RuntimeException("the cause"))

    assertThat(indicator.log).contains("Failed")
    assertThat(indicator.log).contains("the cause")
  }

  @Test
  fun testOldOutputIsDropped() {
    val indicator = LogCollectingProgressIndicator(RecordingProgressIndicator())

    indicator.logInfo("the first message")
    repeat(2000) { indicator.logInfo("a".repeat(100)) }

    assertThat(indicator.log).doesNotContain("the first message")
    assertThat(indicator.log.length).isAtMost(100_000)
  }

  @Test
  fun testProgressIsDelegated() {
    val wrapped = RecordingProgressIndicator()
    val indicator = LogCollectingProgressIndicator(wrapped)

    indicator.fraction = 0.5

    assertThat(wrapped.fraction).isEqualTo(0.5)
    assertThat(indicator.fraction).isEqualTo(0.5)
  }
}

private class RecordingProgressIndicator : ProgressIndicator {
  val messages = mutableListOf<String>()

  private var fraction = 0.0
  private var canceled = false
  private var cancellable = true
  private var indeterminate = false

  override fun setText(s: String?) {}

  override fun isCanceled(): Boolean = canceled

  override fun cancel() {
    canceled = true
  }

  override fun setCancellable(cancellable: Boolean) {
    this.cancellable = cancellable
  }

  override fun isCancellable(): Boolean = cancellable

  override fun setIndeterminate(indeterminate: Boolean) {
    this.indeterminate = indeterminate
  }

  override fun isIndeterminate(): Boolean = indeterminate

  override fun setFraction(v: Double) {
    fraction = v
  }

  override fun getFraction(): Double = fraction

  override fun setSecondaryText(s: String?) {}

  override fun logWarning(s: String) {
    messages.add(s)
  }

  override fun logWarning(s: String, e: Throwable?) {
    messages.add(s)
  }

  override fun logError(s: String) {
    messages.add(s)
  }

  override fun logError(s: String, e: Throwable?) {
    messages.add(s)
  }

  override fun logInfo(s: String) {
    messages.add(s)
  }
}
