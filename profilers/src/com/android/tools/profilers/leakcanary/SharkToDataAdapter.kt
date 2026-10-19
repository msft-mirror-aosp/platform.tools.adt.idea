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
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import shark.HeapAnalysisSuccess
import shark.Leak as SharkLeak
import shark.LeakTrace as SharkLeakTrace
import shark.LeakTraceObject
import shark.LeakTraceReference

/** Adapter that maps the raw object graph produced by Shark (HeapAnalysisSuccess) directly into our internal leakcanarylib data models */
object SharkToDataAdapter {

  /** Entry point that converts the top-level Shark success result and metadata into our AnalysisSuccess object. */
  fun mapAnalysis(sharkSuccess: HeapAnalysisSuccess): AnalysisSuccess {
    val leaks = mutableListOf<Leak>()

    sharkSuccess.applicationLeaks.forEach { leak ->
      leaks.add(mapLeak(leak, LeakType.APPLICATION_LEAKS))
    }
    sharkSuccess.libraryLeaks.forEach { leak ->
      leaks.add(mapLeak(leak, LeakType.LIBRARY_LEAKS))
    }

    return AnalysisSuccess(
      heapDumpFile = sharkSuccess.heapDumpFile,
      createdAtTimeMillis = sharkSuccess.createdAtTimeMillis,
      dumpDurationMillis = sharkSuccess.dumpDurationMillis,
      analysisDurationMillis = sharkSuccess.analysisDurationMillis,
      metadata = sharkSuccess.metadata,
      leaks = leaks,
    )
  }

  /** Converts a specific leak signature group, aggregating all its individual trace occurrences and total retained size. */
  private fun mapLeak(sharkLeak: SharkLeak, leakType: LeakType): Leak {
    val mappedTraces = sharkLeak.leakTraces.map { mapTrace(it) }

    val totalRetainedBytes = sharkLeak.totalRetainedHeapByteSize ?: -1

    return Leak(
      type = leakType,
      retainedByteSize = totalRetainedBytes,
      signature = sharkLeak.signature,
      leakTraceCount = sharkLeak.leakTraces.size,
      displayedLeakTrace = mappedTraces,
    )
  }

  /** Reconstructs the exact chain of references from the GC Root down to the leaking object. */
  private fun mapTrace(sharkTrace: SharkLeakTrace): LeakTrace {
    val gcRootType = runCatching {
      when (sharkTrace.gcRootType) {
        SharkLeakTrace.GcRootType.JNI_GLOBAL -> GcRootType.JNI_GLOBAL
        SharkLeakTrace.GcRootType.JNI_LOCAL -> GcRootType.JNI_LOCAL
        SharkLeakTrace.GcRootType.JAVA_FRAME -> GcRootType.JAVA_FRAME
        SharkLeakTrace.GcRootType.NATIVE_STACK -> GcRootType.NATIVE_STACK
        SharkLeakTrace.GcRootType.STICKY_CLASS -> GcRootType.STICKY_CLASS
        SharkLeakTrace.GcRootType.THREAD_BLOCK -> GcRootType.THREAD_BLOCK
        SharkLeakTrace.GcRootType.MONITOR_USED -> GcRootType.MONITOR_USED
        SharkLeakTrace.GcRootType.THREAD_OBJECT -> GcRootType.THREAD_OBJECT
        SharkLeakTrace.GcRootType.JNI_MONITOR -> GcRootType.JNI_MONITOR
      }
    }
      .getOrElse { GcRootType.fromDescription(sharkTrace.gcRootType.description) }
    val nodes = mutableListOf<Node>()

    val refPath = sharkTrace.referencePath
    val leakingObj = sharkTrace.leakingObject

    // 1. Root & intermediate nodes: each node gets its outgoing reference (refPath[i])
    for (i in refPath.indices) {
      val obj = refPath[i].originObject
      val outgoingRef = refPath[i]
      nodes.add(mapNode(obj, outgoingRef, sharkTrace.referencePathElementIsSuspect(i)))
    }

    // 2. The leaking object itself has no outgoing reference
    nodes.add(mapNode(leakingObj, null, false))

    return LeakTrace(gcRootType = gcRootType, nodes = nodes)
  }

  /** Maps an individual Java/Kotlin object and attaches its outgoing reference pointing to the next node in the path. */
  private fun mapNode(
    sharkObj: LeakTraceObject,
    outgoingRef: LeakTraceReference?,
    isSuspect: Boolean,
  ): Node {

    val referencingField = outgoingRef?.let {
      ReferencingField(
        className = it.owningClassSimpleName,
        type =
          when (it.referenceType) {
            LeakTraceReference.ReferenceType.INSTANCE_FIELD -> ReferencingFieldType.INSTANCE_FIELD
            LeakTraceReference.ReferenceType.STATIC_FIELD -> ReferencingFieldType.STATIC_FIELD
            LeakTraceReference.ReferenceType.LOCAL -> ReferencingFieldType.LOCAL
            LeakTraceReference.ReferenceType.ARRAY_ENTRY -> ReferencingFieldType.ARRAY_ENTRY
          },
        isLikelyCause = isSuspect,
        referenceName = it.referenceName,
      )
    }

    val retainedStr = sharkObj.retainedHeapByteSize?.let { humanReadableByteCount(it.toLong()) }

    return Node(
      nodeType =
        when (sharkObj.type) {
          LeakTraceObject.ObjectType.INSTANCE -> LeakTraceNodeType.INSTANCE
          LeakTraceObject.ObjectType.CLASS -> LeakTraceNodeType.CLASS
          LeakTraceObject.ObjectType.ARRAY -> LeakTraceNodeType.ARRAY
        },
      className = sharkObj.className,
      leakingStatus =
        when (sharkObj.leakingStatus) {
          LeakTraceObject.LeakingStatus.LEAKING -> LeakingStatus.YES
          LeakTraceObject.LeakingStatus.NOT_LEAKING -> LeakingStatus.NO
          LeakTraceObject.LeakingStatus.UNKNOWN -> LeakingStatus.UNKNOWN
        },
      leakingStatusReason = sharkObj.leakingStatusReason,
      retainedHeapSize = retainedStr,
      retainedObjectCount = sharkObj.retainedObjectCount,
      notes = sharkObj.labels.toList(),
      referencingField = referencingField,
    )
  }

  /** Formats a byte count using SI units (e.g., "400 B", "1.2 kB"), matching Shark's LeakTraceObject formatting. */
  private fun humanReadableByteCount(bytes: Long): String {
    val unit = 1000
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val pre = "kMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
  }
}
