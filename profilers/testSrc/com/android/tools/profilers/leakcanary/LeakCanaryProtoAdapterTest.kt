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
package com.android.tools.profilers.leakcanary

import com.android.tools.leakcanarylib.data.AnalysisFailure
import com.android.tools.leakcanarylib.data.AnalysisSuccess
import com.android.tools.leakcanarylib.data.GcRootType
import com.android.tools.leakcanarylib.data.Leak
import com.android.tools.leakcanarylib.data.LeakTrace
import com.android.tools.leakcanarylib.data.LeakTraceNodeType
import com.android.tools.leakcanarylib.data.LeakType
import com.android.tools.leakcanarylib.data.LeakingStatus
import com.android.tools.leakcanarylib.data.Node
import com.android.tools.leakcanarylib.data.ReferencingField
import com.android.tools.leakcanarylib.data.ReferencingField.ReferencingFieldType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LeakCanaryProtoAdapterTest {

  @Test
  fun `round-trip AnalysisSuccess preserves multiple occurrences and all node fields`() {
    val trace1 =
      LeakTrace(
        gcRootType = GcRootType.JAVA_FRAME,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.CLASS,
              className = "com.example.SingletonHolder",
              leakingStatus = LeakingStatus.NO,
              leakingStatusReason = "a class is never leaking",
              retainedHeapSize = null,
              retainedObjectCount = null,
              notes = listOf("Thread name: 'main'"),
              referencingField =
                ReferencingField(
                  className = "com.example.SingletonHolder",
                  type = ReferencingFieldType.STATIC_FIELD,
                  isLikelyCause = true,
                  referenceName = "leakedInstance",
                ),
            ),
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.FirstActivity",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Activity#mDestroyed is true",
              retainedHeapSize = "4.2 kB",
              retainedObjectCount = 55,
              notes = listOf("mDestroyed = true"),
              referencingField = null,
            ),
          ),
      )

    val trace2 =
      LeakTrace(
        gcRootType = GcRootType.JNI_GLOBAL,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.ARRAY,
              className = "java.lang.Object[]",
              leakingStatus = LeakingStatus.UNKNOWN,
              leakingStatusReason = "",
              retainedHeapSize = "0 B",
              retainedObjectCount = 0,
              notes = emptyList(),
              referencingField =
                ReferencingField(
                  className = "java.lang.Object[]",
                  type = ReferencingFieldType.ARRAY_ENTRY,
                  isLikelyCause = true,
                  referenceName = "0",
                ),
            ),
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.SecondActivity",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Activity#mDestroyed is true",
              retainedHeapSize = "1.8 kB",
              retainedObjectCount = 22,
              notes = emptyList(),
              referencingField = null,
            ),
          ),
      )

    val leak =
      Leak(
        type = LeakType.APPLICATION_LEAKS,
        retainedByteSize = 6000,
        signature = "abcdef1234567890",
        leakTraceCount = 2,
        displayedLeakTrace = listOf(trace1, trace2),
      )

    val originalAnalysis =
      AnalysisSuccess(
        createdAtTimeMillis = 1700000000000L,
        dumpDurationMillis = 120L,
        analysisDurationMillis = 850L,
        metadata = mapOf("Build.VERSION.SDK_INT" to "34", "LeakCanary version" to "2.14"),
        leaks = listOf(leak),
      )

    val proto = LeakCanaryProtoAdapter.toProto(originalAnalysis)
    assertThat(proto.hasSuccess()).isTrue()
    assertThat(proto.hasFailure()).isFalse()
    assertThat(proto.data).isEmpty()
    assertThat(proto.success.leaksCount).isEqualTo(1)
    assertThat(proto.success.getLeaks(0).leakTracesCount).isEqualTo(2)

    val deserialized = LeakCanaryProtoAdapter.fromProto(proto)
    assertThat(deserialized).isInstanceOf(AnalysisSuccess::class.java)
    assertThat(deserialized).isEqualTo(originalAnalysis)
  }

  @Test
  fun `round-trip zero-leak AnalysisSuccess preserves hasSuccess`() {
    val emptyAnalysis =
      AnalysisSuccess(
        createdAtTimeMillis = 1700000005000L,
        dumpDurationMillis = 80L,
        analysisDurationMillis = 300L,
        metadata = mapOf("App" to "com.example"),
        leaks = emptyList(),
      )

    val proto = LeakCanaryProtoAdapter.toProto(emptyAnalysis)
    assertThat(proto.hasSuccess()).isTrue()
    assertThat(proto.success.leaksCount).isEqualTo(0)

    val deserialized = LeakCanaryProtoAdapter.fromProto(proto)
    assertThat(deserialized).isEqualTo(emptyAnalysis)
  }

  @Test
  fun `round-trip AnalysisFailure preserves failure fields`() {
    val failureAnalysis =
      AnalysisFailure(
        createdAtTimeMillis = 1700000010000L,
        dumpDurationMillis = 50L,
        analysisDurationMillis = 15L,
        exception = IllegalStateException("Corrupted HPROF header"),
      )

    val proto = LeakCanaryProtoAdapter.toProto(failureAnalysis)
    assertThat(proto.hasFailure()).isTrue()
    assertThat(proto.hasSuccess()).isFalse()

    val deserialized = LeakCanaryProtoAdapter.fromProto(proto) as AnalysisFailure
    assertThat(deserialized.heapDumpFile).isEqualTo(failureAnalysis.heapDumpFile)
    assertThat(deserialized.createdAtTimeMillis).isEqualTo(failureAnalysis.createdAtTimeMillis)
    assertThat(deserialized.dumpDurationMillis).isEqualTo(failureAnalysis.dumpDurationMillis)
    assertThat(deserialized.analysisDurationMillis).isEqualTo(failureAnalysis.analysisDurationMillis)
    assertThat(deserialized.exception.message).isEqualTo("Corrupted HPROF header")
    assertThat(deserialized.exception.cause?.message).isEqualTo(failureAnalysis.exception.stackTraceToString())
  }

  @Test
  fun `toSingleOccurrenceString formats requested occurrence index`() {
    val trace1 =
      LeakTrace(
        gcRootType = GcRootType.JAVA_FRAME,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.OccurrenceOne",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Destroyed",
              retainedHeapSize = null,
              retainedObjectCount = null,
              notes = emptyList(),
              referencingField = null,
            )
          ),
      )
    val trace2 =
      LeakTrace(
        gcRootType = GcRootType.JNI_GLOBAL,
        nodes =
          listOf(
            Node(
              nodeType = LeakTraceNodeType.INSTANCE,
              className = "com.example.OccurrenceTwo",
              leakingStatus = LeakingStatus.YES,
              leakingStatusReason = "Destroyed",
              retainedHeapSize = null,
              retainedObjectCount = null,
              notes = emptyList(),
              referencingField = null,
            )
          ),
      )
    val leak =
      Leak(
        type = LeakType.APPLICATION_LEAKS,
        retainedByteSize = 1024,
        signature = "sig123",
        leakTraceCount = 2,
        displayedLeakTrace = listOf(trace1, trace2),
      )

    val firstFormatted = leak.toSingleOccurrenceString(0)
    assertThat(firstFormatted).contains("com.example.OccurrenceOne")
    assertThat(firstFormatted).doesNotContain("com.example.OccurrenceTwo")
    // Verify leak.toString() (used by AI Insights) matches the 1st occurrence (index 0)
    assertThat(leak.toString()).isEqualTo(firstFormatted)

    val secondFormatted = leak.toSingleOccurrenceString(1)
    assertThat(secondFormatted).contains("com.example.OccurrenceTwo")
    assertThat(secondFormatted).doesNotContain("com.example.OccurrenceOne")
  }
}
