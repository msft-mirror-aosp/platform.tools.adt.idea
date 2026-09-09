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

import com.android.tools.leakcanarylib.data.Analysis
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
import com.android.tools.profiler.proto.LeakCanary.Leak as LeakProto
import com.android.tools.profiler.proto.LeakCanary.Leak.LeakType as LeakTypeProto
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisFailure as LeakCanaryAnalysisFailureProto
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisSuccess as LeakCanaryAnalysisSuccessProto
import com.android.tools.profiler.proto.LeakCanary.LeakTrace as LeakTraceProto
import com.android.tools.profiler.proto.LeakCanary.LeakTrace.GcRootType as GcRootTypeProto
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode as LeakTraceNodeProto
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode.LeakingStatus as LeakingStatusProto
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode.NodeType as NodeTypeProto
import com.android.tools.profiler.proto.LeakCanary.ReferencingField as ReferencingFieldProto
import com.android.tools.profiler.proto.LeakCanary.ReferencingField.ReferencingFieldType as ReferencingFieldTypeProto
import com.intellij.openapi.diagnostic.Logger

/**
 * Bidirectional adapter between [Analysis] domain data models (`leakcanarylib/data`) and structured [LeakCanaryAnalysisData] Protobuf
 * messages for Transport Database (`.asdb`) persistence.
 *
 * This adapter completely eliminates string serialization (`toString()`) and string regex parsing (`fromString()`) across the Transport DB,
 * preserving all leak trace occurrences as a native Protobuf array (`repeated LeakTrace`).
 */
object LeakCanaryProtoAdapter {
  private val logger: Logger = Logger.getInstance(LeakCanaryProtoAdapter::class.java)

  /** Converts an in-memory [Analysis] object into a structured [LeakCanaryAnalysisData] Protobuf message. */
  fun toProto(analysis: Analysis): LeakCanaryAnalysisData {
    val builder = LeakCanaryAnalysisData.newBuilder()
    when (analysis) {
      is AnalysisSuccess -> {
        logger.debug("Serializing AnalysisSuccess to LeakCanaryAnalysisData proto (${analysis.leaks.size} leak groups).")
        builder
          .setCreatedAtTimeMillis(analysis.createdAtTimeMillis)
          .setDumpDurationMillis(analysis.dumpDurationMillis)
          .setAnalysisDurationMillis(analysis.analysisDurationMillis)
          .setSuccess(
            LeakCanaryAnalysisSuccessProto.newBuilder()
              .putAllMetadata(analysis.metadata)
              .addAllLeaks(analysis.leaks.map { mapLeakToProto(it) })
              .build()
          )
      }
      is AnalysisFailure -> {
        logger.debug("Serializing AnalysisFailure to LeakCanaryAnalysisData proto.")
        builder
          .setCreatedAtTimeMillis(analysis.createdAtTimeMillis)
          .setDumpDurationMillis(analysis.dumpDurationMillis)
          .setAnalysisDurationMillis(analysis.analysisDurationMillis)
          .setFailure(
            LeakCanaryAnalysisFailureProto.newBuilder()
              .setExceptionMessage(analysis.exception.message ?: analysis.exception.toString())
              .setExceptionStackTrace(analysis.exception.stackTraceToString())
              .build()
          )
      }
      else -> {
        logger.warn("Unsupported Analysis subtype for structured Protobuf serialization: ${analysis::class.simpleName}")
      }
    }
    return builder.build()
  }

  /** Reconstructs an in-memory [Analysis] domain object from a structured [LeakCanaryAnalysisData] Protobuf message. */
  fun fromProto(proto: LeakCanaryAnalysisData): Analysis? {
    return when {
      proto.hasSuccess() -> {
        val successProto = proto.success
        logger.debug("Deserializing AnalysisSuccess from LeakCanaryAnalysisData proto (${successProto.leaksCount} leak groups).")
        AnalysisSuccess(
          createdAtTimeMillis = proto.createdAtTimeMillis,
          dumpDurationMillis = proto.dumpDurationMillis,
          analysisDurationMillis = proto.analysisDurationMillis,
          metadata = successProto.metadataMap,
          leaks = successProto.leaksList.map { mapProtoToLeak(it) },
        )
      }
      proto.hasFailure() -> {
        val failureProto = proto.failure
        logger.debug("Deserializing AnalysisFailure from LeakCanaryAnalysisData proto.")
        val cause = failureProto.exceptionStackTrace.takeIf { it.isNotEmpty() }?.let { Throwable(it) }
        AnalysisFailure(
          createdAtTimeMillis = proto.createdAtTimeMillis,
          dumpDurationMillis = proto.dumpDurationMillis,
          analysisDurationMillis = proto.analysisDurationMillis,
          exception = RuntimeException(failureProto.exceptionMessage, cause),
        )
      }
      else -> {
        logger.warn("LeakCanaryAnalysisData proto does not contain a structured success or failure payload.")
        null
      }
    }
  }

  private fun mapLeakToProto(leak: Leak): LeakProto {
    val typeProto =
      when (leak.type) {
        LeakType.APPLICATION_LEAKS -> LeakTypeProto.LEAK_TYPE_APPLICATION_LEAK
        LeakType.LIBRARY_LEAKS -> LeakTypeProto.LEAK_TYPE_LIBRARY_LEAK
      }
    val builder =
      LeakProto.newBuilder()
        .setType(typeProto)
        .setSignature(leak.signature)
        .setLeakTraceCount(leak.leakTraceCount)
        .addAllLeakTraces(leak.displayedLeakTrace.map { mapLeakTraceToProto(it) })
    if (leak.retainedByteSize >= 0) {
      builder.retainedByteSize = leak.retainedByteSize.toLong()
    }
    return builder.build()
  }

  private fun mapProtoToLeak(proto: LeakProto): Leak {
    val leakType =
      when (proto.type) {
        LeakTypeProto.LEAK_TYPE_LIBRARY_LEAK -> LeakType.LIBRARY_LEAKS
        else -> LeakType.APPLICATION_LEAKS
      }
    return Leak(
      type = leakType,
      retainedByteSize = if (proto.hasRetainedByteSize()) proto.retainedByteSize.toInt() else -1,
      signature = proto.signature,
      leakTraceCount = proto.leakTraceCount,
      displayedLeakTrace = proto.leakTracesList.map { mapProtoToLeakTrace(it) },
    )
  }

  private fun mapLeakTraceToProto(trace: LeakTrace): LeakTraceProto {
    val gcRootProto = mapGcRootTypeToProto(trace.gcRootType)
    return LeakTraceProto.newBuilder().setGcRootType(gcRootProto).addAllNodes(trace.nodes.map { mapNodeToProto(it) }).build()
  }

  private fun mapProtoToLeakTrace(proto: LeakTraceProto): LeakTrace {
    val gcRootType = mapProtoToGcRootType(proto.gcRootType)
    return LeakTrace(
      gcRootType = gcRootType,
      nodes = proto.nodesList.map { mapProtoToNode(it) },
    )
  }

  private fun mapNodeToProto(node: Node): LeakTraceNodeProto {
    val nodeTypeProto = mapNodeTypeToProto(node.nodeType)
    val leakingStatusProto = mapLeakingStatusToProto(node.leakingStatus)

    val builder =
      LeakTraceNodeProto.newBuilder()
        .setNodeType(nodeTypeProto)
        .setClassName(node.className)
        .setLeakingStatus(leakingStatusProto)
        .setLeakingStatusReason(node.leakingStatusReason)
        .addAllNotes(node.notes)

    node.retainedHeapSize?.let { builder.retainedHeapSize = it }
    node.retainedObjectCount?.let { builder.retainedObjectCount = it }
    node.referencingField?.let { builder.referencingField = mapReferencingFieldToProto(it) }

    return builder.build()
  }

  private fun mapProtoToNode(proto: LeakTraceNodeProto): Node {
    val nodeType = mapProtoToNodeType(proto.nodeType)
    val leakingStatus = mapProtoToLeakingStatus(proto.leakingStatus)
    val retainedHeapSize = if (proto.hasRetainedHeapSize()) proto.retainedHeapSize else null
    val retainedObjectCount = if (proto.hasRetainedObjectCount()) proto.retainedObjectCount else null
    val referencingField = if (proto.hasReferencingField()) mapProtoToReferencingField(proto.referencingField) else null

    return Node(
      nodeType = nodeType,
      className = proto.className,
      leakingStatus = leakingStatus,
      leakingStatusReason = proto.leakingStatusReason,
      retainedHeapSize = retainedHeapSize,
      retainedObjectCount = retainedObjectCount,
      notes = proto.notesList,
      referencingField = referencingField,
    )
  }

  private fun mapReferencingFieldToProto(field: ReferencingField): ReferencingFieldProto {
    val fieldTypeProto = mapReferencingFieldTypeToProto(field.type)
    return ReferencingFieldProto.newBuilder()
      .setClassName(field.className)
      .setReferenceName(field.referenceName)
      .setType(fieldTypeProto)
      .setIsLikelyCause(field.isLikelyCause)
      .build()
  }

  private fun mapProtoToReferencingField(proto: ReferencingFieldProto): ReferencingField {
    val fieldType = mapProtoToReferencingFieldType(proto.type)
    return ReferencingField(
      className = proto.className,
      type = fieldType,
      isLikelyCause = proto.isLikelyCause,
      referenceName = proto.referenceName,
    )
  }

  private fun mapGcRootTypeToProto(gcRootType: GcRootType): GcRootTypeProto =
    when (gcRootType) {
      GcRootType.JNI_GLOBAL -> GcRootTypeProto.GC_ROOT_TYPE_JNI_GLOBAL
      GcRootType.JNI_LOCAL -> GcRootTypeProto.GC_ROOT_TYPE_JNI_LOCAL
      GcRootType.JAVA_FRAME -> GcRootTypeProto.GC_ROOT_TYPE_JAVA_FRAME
      GcRootType.NATIVE_STACK -> GcRootTypeProto.GC_ROOT_TYPE_NATIVE_STACK
      GcRootType.STICKY_CLASS -> GcRootTypeProto.GC_ROOT_TYPE_STICKY_CLASS
      GcRootType.THREAD_BLOCK -> GcRootTypeProto.GC_ROOT_TYPE_THREAD_BLOCK
      GcRootType.MONITOR_USED -> GcRootTypeProto.GC_ROOT_TYPE_MONITOR_USED
      GcRootType.THREAD_OBJECT -> GcRootTypeProto.GC_ROOT_TYPE_THREAD_OBJECT
      GcRootType.JNI_MONITOR -> GcRootTypeProto.GC_ROOT_TYPE_JNI_MONITOR
      GcRootType.INTERNED_STRING -> GcRootTypeProto.GC_ROOT_TYPE_INTERNED_STRING
      GcRootType.FINALIZING -> GcRootTypeProto.GC_ROOT_TYPE_FINALIZING
      GcRootType.DEBUGGER -> GcRootTypeProto.GC_ROOT_TYPE_DEBUGGER
      GcRootType.REFERENCE_CLEANUP -> GcRootTypeProto.GC_ROOT_TYPE_REFERENCE_CLEANUP
      GcRootType.VM_INTERNAL -> GcRootTypeProto.GC_ROOT_TYPE_VM_INTERNAL
      GcRootType.UNREACHABLE -> GcRootTypeProto.GC_ROOT_TYPE_UNREACHABLE
      GcRootType.UNKNOWN -> GcRootTypeProto.GC_ROOT_TYPE_UNKNOWN
    }

  private fun mapProtoToGcRootType(proto: GcRootTypeProto): GcRootType =
    when (proto) {
      GcRootTypeProto.GC_ROOT_TYPE_JNI_GLOBAL -> GcRootType.JNI_GLOBAL
      GcRootTypeProto.GC_ROOT_TYPE_JNI_LOCAL -> GcRootType.JNI_LOCAL
      GcRootTypeProto.GC_ROOT_TYPE_JAVA_FRAME -> GcRootType.JAVA_FRAME
      GcRootTypeProto.GC_ROOT_TYPE_NATIVE_STACK -> GcRootType.NATIVE_STACK
      GcRootTypeProto.GC_ROOT_TYPE_STICKY_CLASS -> GcRootType.STICKY_CLASS
      GcRootTypeProto.GC_ROOT_TYPE_THREAD_BLOCK -> GcRootType.THREAD_BLOCK
      GcRootTypeProto.GC_ROOT_TYPE_MONITOR_USED -> GcRootType.MONITOR_USED
      GcRootTypeProto.GC_ROOT_TYPE_THREAD_OBJECT -> GcRootType.THREAD_OBJECT
      GcRootTypeProto.GC_ROOT_TYPE_JNI_MONITOR -> GcRootType.JNI_MONITOR
      GcRootTypeProto.GC_ROOT_TYPE_INTERNED_STRING -> GcRootType.INTERNED_STRING
      GcRootTypeProto.GC_ROOT_TYPE_FINALIZING -> GcRootType.FINALIZING
      GcRootTypeProto.GC_ROOT_TYPE_DEBUGGER -> GcRootType.DEBUGGER
      GcRootTypeProto.GC_ROOT_TYPE_REFERENCE_CLEANUP -> GcRootType.REFERENCE_CLEANUP
      GcRootTypeProto.GC_ROOT_TYPE_VM_INTERNAL -> GcRootType.VM_INTERNAL
      GcRootTypeProto.GC_ROOT_TYPE_UNREACHABLE -> GcRootType.UNREACHABLE
      GcRootTypeProto.GC_ROOT_TYPE_UNKNOWN,
      GcRootTypeProto.GC_ROOT_TYPE_UNSPECIFIED,
      GcRootTypeProto.UNRECOGNIZED -> GcRootType.UNKNOWN
    }

  private fun mapNodeTypeToProto(nodeType: LeakTraceNodeType): NodeTypeProto =
    when (nodeType) {
      LeakTraceNodeType.INSTANCE -> NodeTypeProto.NODE_TYPE_INSTANCE
      LeakTraceNodeType.CLASS -> NodeTypeProto.NODE_TYPE_CLASS
      LeakTraceNodeType.ARRAY -> NodeTypeProto.NODE_TYPE_ARRAY
    }

  private fun mapProtoToNodeType(proto: NodeTypeProto): LeakTraceNodeType =
    when (proto) {
      NodeTypeProto.NODE_TYPE_CLASS -> LeakTraceNodeType.CLASS
      NodeTypeProto.NODE_TYPE_ARRAY -> LeakTraceNodeType.ARRAY
      NodeTypeProto.NODE_TYPE_INSTANCE,
      NodeTypeProto.NODE_TYPE_UNSPECIFIED,
      NodeTypeProto.UNRECOGNIZED -> LeakTraceNodeType.INSTANCE
    }

  private fun mapLeakingStatusToProto(status: LeakingStatus): LeakingStatusProto =
    when (status) {
      LeakingStatus.YES -> LeakingStatusProto.LEAKING_STATUS_YES
      LeakingStatus.NO -> LeakingStatusProto.LEAKING_STATUS_NO
      LeakingStatus.UNKNOWN -> LeakingStatusProto.LEAKING_STATUS_UNKNOWN
    }

  private fun mapProtoToLeakingStatus(proto: LeakingStatusProto): LeakingStatus =
    when (proto) {
      LeakingStatusProto.LEAKING_STATUS_YES -> LeakingStatus.YES
      LeakingStatusProto.LEAKING_STATUS_NO -> LeakingStatus.NO
      LeakingStatusProto.LEAKING_STATUS_UNKNOWN,
      LeakingStatusProto.LEAKING_STATUS_UNSPECIFIED,
      LeakingStatusProto.UNRECOGNIZED -> LeakingStatus.UNKNOWN
    }

  private fun mapReferencingFieldTypeToProto(type: ReferencingFieldType): ReferencingFieldTypeProto =
    when (type) {
      ReferencingFieldType.INSTANCE_FIELD -> ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_INSTANCE_FIELD
      ReferencingFieldType.STATIC_FIELD -> ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_STATIC_FIELD
      ReferencingFieldType.LOCAL -> ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_LOCAL
      ReferencingFieldType.ARRAY_ENTRY -> ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_ARRAY_ENTRY
    }

  private fun mapProtoToReferencingFieldType(proto: ReferencingFieldTypeProto): ReferencingFieldType =
    when (proto) {
      ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_STATIC_FIELD -> ReferencingFieldType.STATIC_FIELD
      ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_LOCAL -> ReferencingFieldType.LOCAL
      ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_ARRAY_ENTRY -> ReferencingFieldType.ARRAY_ENTRY
      ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_INSTANCE_FIELD,
      ReferencingFieldTypeProto.REFERENCING_FIELD_TYPE_UNSPECIFIED,
      ReferencingFieldTypeProto.UNRECOGNIZED -> ReferencingFieldType.INSTANCE_FIELD
    }
}
