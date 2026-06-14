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
package com.android.tools.idea.tracer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.wire.TraceDriver
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.ApplicationRule
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.jewel.ui.component.Text
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidxTracerServiceTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val temporaryFolder = TemporaryFolder()
  @get:Rule val composeTestRule = createStudioComposeTestRule()

  @Test
  fun flushWithResetReturnsOldFileClosesOldDriverAndCreatesNewSession() = runTest {
    val propertiesComponent = PropertiesComponent.getInstance()
    propertiesComponent.setValue(TRACING_ENABLED_KEY, true, false)

    val traceSessionFactory = FakeTraceSessionFactory()
    val service =
      AndroidxTracerService(
        serviceScope = backgroundScope,
        studioTraceLibraryEnabled = { true },
        propertiesComponent = { propertiesComponent },
        tempDir = { temporaryFolder.root.toPath() },
        traceSessionFactory = traceSessionFactory,
      )

    try {
      service.initializeTracing()

      val firstSession = traceSessionFactory.sessions.single()
      val firstTraceFile = firstSession.traceFile

      val flushedTraceFile = service.flush()

      assertThat(flushedTraceFile).isEqualTo(firstTraceFile)
      assertThat(firstSession.sink.flushCount).isAtLeast(1)
      assertThat(firstSession.sink.closeCount).isEqualTo(1)
      assertThat(traceSessionFactory.sessions).hasSize(2)

      val secondSession = traceSessionFactory.sessions[1]
      assertThat(service.flush(reset = false)).isEqualTo(secondSession.traceFile)
      assertThat(secondSession.sink.flushCount).isEqualTo(1)
      assertThat(secondSession.sink.closeCount).isEqualTo(0)
    } finally {
      clearTracingProperties()
    }
  }

  @Test
  fun reinitializeWithTracingStillEnabledClosesPreviousSessionAndCreatesNewSession() = runTest {
    val propertiesComponent = PropertiesComponent.getInstance()
    propertiesComponent.setValue(TRACING_ENABLED_KEY, true, false)

    val traceSessionFactory = FakeTraceSessionFactory()
    val service =
      AndroidxTracerService(
        serviceScope = backgroundScope,
        studioTraceLibraryEnabled = { true },
        propertiesComponent = { propertiesComponent },
        tempDir = { temporaryFolder.root.toPath() },
        traceSessionFactory = traceSessionFactory,
      )

    try {
      service.initializeTracing()
      val firstSession = traceSessionFactory.sessions.single()

      service.initializeTracing()

      assertThat(traceSessionFactory.sessions).hasSize(2)
      assertThat(firstSession.sink.closeCount).isEqualTo(1)
      assertThat(service.flush(reset = false)).isEqualTo(traceSessionFactory.sessions[1].traceFile)
      assertThat(traceSessionFactory.sessions[1].sink.closeCount).isEqualTo(0)
    } finally {
      clearTracingProperties()
    }
  }

  @Test
  fun compositionTracingEnabledRecordsComposeTraceEvents() = runTest {
    val traceSessionFactory = initializeServiceWithCompositionTracingEnabled(enabled = true)

    try {
      composeTestRule.setContent { TraceProbe() }
      composeTestRule.onNodeWithText("Trace probe").assertIsDisplayed()
      composeTestRule.waitForIdle()

      val events = traceSessionFactory.sessions.single().sink.events
      assertThat(events.map { it.primaryCategory }).contains("compose")
      assertThat(events.mapNotNull { it.name }.any { it.contains("AndroidxTracerServiceTest.TraceProbe") }).isTrue()
    } finally {
      clearComposeTracer()
      clearTracingProperties()
    }
  }

  @Test
  fun compositionTracingDisabledDoesNotRecordComposeTraceEvents() = runTest {
    val traceSessionFactory = initializeServiceWithCompositionTracingEnabled(enabled = false)

    try {
      composeTestRule.setContent { TraceProbe() }
      composeTestRule.onNodeWithText("Trace probe").assertIsDisplayed()
      composeTestRule.waitForIdle()

      assertThat(traceSessionFactory.sessions.single().sink.events.map { it.primaryCategory }).doesNotContain("compose")
    } finally {
      clearComposeTracer()
      clearTracingProperties()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun jvmMetricsTracerRecordsCounterEvents() = runTest {
    val traceSessionFactory = initializeServiceWithCompositionTracingEnabled(enabled = false)

    try {
      repeat(15) {
        runCurrent()
        advanceTimeBy(1.seconds)
      }
      runCurrent()

      val trackDescriptorNames = traceSessionFactory.sessions.single().sink.events.mapNotNull { it.trackDescriptorName }
      assertThat(trackDescriptorNames).contains("heap_used_bytes")
    } finally {
      clearComposeTracer()
      clearTracingProperties()
    }
  }

  private fun TestScope.initializeServiceWithCompositionTracingEnabled(enabled: Boolean): FakeTraceSessionFactory {
    val propertiesComponent = PropertiesComponent.getInstance()
    propertiesComponent.setValue(TRACING_ENABLED_KEY, true, false)
    propertiesComponent.setValue(COMPOSITION_TRACING_ENABLED_KEY, enabled, false)

    val traceSessionFactory = FakeTraceSessionFactory()
    val service =
      AndroidxTracerService(
        serviceScope = backgroundScope,
        studioTraceLibraryEnabled = { true },
        propertiesComponent = { propertiesComponent },
        tempDir = { temporaryFolder.root.toPath() },
        traceSessionFactory = traceSessionFactory,
      )
    service.initializeTracing()
    return traceSessionFactory
  }

  @Composable
  private fun TraceProbe() {
    Text("Trace probe")
  }

  @OptIn(InternalComposeTracingApi::class)
  private fun clearComposeTracer() {
    Composer.setTracer(null)
  }

  private fun clearTracingProperties() {
    PropertiesComponent.getInstance().unsetValue(TRACING_ENABLED_KEY)
    PropertiesComponent.getInstance().unsetValue(COMPOSITION_TRACING_ENABLED_KEY)
  }

  private class FakeTraceSessionFactory : TraceSessionFactory {
    val sessions = mutableListOf<FakeTraceSession>()

    override fun createTraceSession(tempDir: Path): TraceSession.Enabled {
      val sink = FakeTraceSink()
      val session = FakeTraceSession(traceFile = tempDir.resolve("trace-${sessions.size}.perfetto"), sink = sink)
      sessions += session
      return TraceSession.Enabled(traceFile = session.traceFile, driver = TraceDriver(sink, true))
    }
  }

  private data class FakeTraceSession(val traceFile: Path, val sink: FakeTraceSink)

  private class FakeTraceSink : AbstractTraceSink() {
    var flushCount = 0
      private set

    var closeCount = 0
      private set

    val events = mutableListOf<TraceEventSnapshot>()

    @OptIn(DelicateTracingApi::class)
    override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
      pooledPacketArray.forEach { event ->
        events +=
          TraceEventSnapshot(primaryCategory = event.primaryCategory, name = event.name, trackDescriptorName = event.trackDescriptor?.name)
      }
      pooledPacketArray.recycle()
    }

    override fun onDroppedTraceEvent() = Unit

    override fun flush() {
      flushCount++
    }

    override fun close() {
      closeCount++
    }
  }

  private data class TraceEventSnapshot(val primaryCategory: String, val name: String?, val trackDescriptorName: String?)
}
