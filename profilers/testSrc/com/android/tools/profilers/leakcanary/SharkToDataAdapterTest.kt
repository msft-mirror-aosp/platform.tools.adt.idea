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
import com.android.tools.leakcanarylib.data.AnalysisSuccess
import com.android.tools.leakcanarylib.data.GcRootType
import com.android.tools.leakcanarylib.data.LeakType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import shark.ApplicationLeak
import shark.HeapAnalysisSuccess
import shark.LeakTrace as SharkLeakTrace
import shark.LeakTrace.GcRootType as SharkGcRootType
import shark.LeakTraceObject
import shark.LeakTraceObject.LeakingStatus as SharkLeakingStatus
import shark.LeakTraceReference
import shark.LibraryLeak

class SharkToDataAdapterTest {

  /** Verifies that general metadata from the analysis is mapped correctly to AnalysisSuccess. */
  @Test
  fun testMapAnalysisExtractsMetadata() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    val dummyFile = File("/tmp/dummy.hprof")
    val dummyMetadata = mapOf("AppVersion" to "1.0", "Device" to "Pixel")

    `when`(mockSuccess.heapDumpFile).thenReturn(dummyFile)
    `when`(mockSuccess.createdAtTimeMillis).thenReturn(1000L)
    `when`(mockSuccess.dumpDurationMillis).thenReturn(2000L)
    `when`(mockSuccess.analysisDurationMillis).thenReturn(3000L)
    `when`(mockSuccess.metadata).thenReturn(dummyMetadata)
    `when`(mockSuccess.applicationLeaks).thenReturn(emptyList())
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)

    assertEquals(dummyFile, analysis.heapDumpFile)
    assertEquals(1000L, analysis.createdAtTimeMillis)
    assertEquals(2000L, analysis.dumpDurationMillis)
    assertEquals(3000L, analysis.analysisDurationMillis)
    assertEquals(dummyMetadata, analysis.metadata)
  }

  /** Verifies that both application and library leaks are mapped with the correct leak type enums. */
  @Test
  fun testLeaksAreCategorizedCorrectly() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.leakTraces).thenReturn(emptyList())
    `when`(appLeak.signature).thenReturn("app_sig")
    `when`(appLeak.totalRetainedHeapByteSize).thenReturn(100)

    val libLeak = mock(LibraryLeak::class.java)
    `when`(libLeak.leakTraces).thenReturn(emptyList())
    `when`(libLeak.signature).thenReturn("lib_sig")
    `when`(libLeak.totalRetainedHeapByteSize).thenReturn(200)

    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(listOf(libLeak))

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    assertEquals(2, analysis.leaks.size)

    val mappedAppLeak = analysis.leaks.find { it.signature == "app_sig" }!!
    assertEquals(LeakType.APPLICATION_LEAKS, mappedAppLeak.type)
    assertEquals(100, mappedAppLeak.retainedByteSize)

    val mappedLibLeak = analysis.leaks.find { it.signature == "lib_sig" }!!
    assertEquals(LeakType.LIBRARY_LEAKS, mappedLibLeak.type)
    assertEquals(200, mappedLibLeak.retainedByteSize)
  }

  /** Verifies that when a single signature has multiple traces, all traces are preserved in the list. */
  @Test
  fun testMapLeakRetainsMultipleOccurrences() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.signature).thenReturn("multi_trace_sig")
    `when`(appLeak.totalRetainedHeapByteSize).thenReturn(300)

    val mockTrace1 = createMockTrace()
    val mockTrace2 = createMockTrace()
    val mockTrace3 = createMockTrace()

    `when`(appLeak.leakTraces).thenReturn(listOf(mockTrace1, mockTrace2, mockTrace3))
    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    val leak = analysis.leaks.first()

    assertEquals(3, leak.leakTraceCount)
    assertEquals(3, leak.displayedLeakTrace.size)
  }

  /** Verifies that the outgoing referencing field is attached to the correct node in the path. */
  @Test
  fun testMapTraceAttachesOutgoingReferencesCorrectly() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.signature).thenReturn("ref_sig")

    val mockTrace = createMockTrace()
    `when`(appLeak.leakTraces).thenReturn(listOf(mockTrace))
    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    val trace = analysis.leaks.first().displayedLeakTrace.first()

    // 1 origin object + 1 leaking object = 2 nodes total
    assertEquals(2, trace.nodes.size)

    // The first node should have an outgoing reference
    val rootNode = trace.nodes[0]
    assertNotNull(rootNode.referencingField)
    assertEquals("mContext", rootNode.referencingField?.referenceName)

    // The final leaking object should NOT have an outgoing reference
    val leakingNode = trace.nodes[1]
    assertNull(leakingNode.referencingField)
  }

  /** Verifies that if Shark marks a reference as the suspect, it is mapped to isLikelyCause correctly. */
  @Test
  fun testMapNodeIdentifiesSuspectField() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.signature).thenReturn("suspect_sig")

    val mockTrace = createMockTrace(suspectIndex = 0)
    `when`(appLeak.leakTraces).thenReturn(listOf(mockTrace))
    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    val trace = analysis.leaks.first().displayedLeakTrace.first()

    val rootNode = trace.nodes[0]
    assertTrue(rootNode.referencingField!!.isLikelyCause)
  }

  /** Verifies that a generic or slightly altered GC Root description doesn't crash the mapper. */
  @Test
  fun testGcRootTypeFallbackLogic() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.signature).thenReturn("fallback_sig")

    val mockTrace = createMockTrace()
    val weirdGcRoot = mock(SharkGcRootType::class.java)
    `when`(weirdGcRoot.ordinal).thenReturn(-1)
    `when`(weirdGcRoot.name).thenReturn("WEIRD_UNKNOWN_ENUM")
    `when`(weirdGcRoot.description).thenReturn("Local variable in native code")
    `when`(mockTrace.gcRootType).thenReturn(weirdGcRoot)

    `when`(appLeak.leakTraces).thenReturn(listOf(mockTrace))
    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    val trace = analysis.leaks.first().displayedLeakTrace.first()

    assertEquals(GcRootType.JNI_LOCAL, trace.gcRootType)
  }

  /** Verifies that after converting the Shark object, it can be serialized and deserialized flawlessly. */
  @Test
  fun testSerializationRoundTripForPastSessions() {
    val mockSuccess = mock(HeapAnalysisSuccess::class.java)
    `when`(mockSuccess.heapDumpFile).thenReturn(File("/tmp/dummy.hprof"))
    `when`(mockSuccess.metadata).thenReturn(emptyMap())

    val appLeak = mock(ApplicationLeak::class.java)
    `when`(appLeak.signature).thenReturn("roundtrip_sig")
    `when`(appLeak.totalRetainedHeapByteSize).thenReturn(500)

    val mockTrace = createMockTrace()
    `when`(appLeak.leakTraces).thenReturn(listOf(mockTrace))
    `when`(mockSuccess.applicationLeaks).thenReturn(listOf(appLeak))
    `when`(mockSuccess.libraryLeaks).thenReturn(emptyList())

    val analysis = SharkToDataAdapter.mapAnalysis(mockSuccess)
    val serializedStr = analysis.toString()

    val deserializedAnalysis = Analysis.fromString(serializedStr) as AnalysisSuccess

    assertEquals(analysis.heapDumpFile, deserializedAnalysis.heapDumpFile)
    assertEquals(analysis.leaks.size, deserializedAnalysis.leaks.size)

    val originalLeak = analysis.leaks.first()
    val restoredLeak = deserializedAnalysis.leaks.first()

    assertEquals(originalLeak.signature, restoredLeak.signature)
    assertEquals(originalLeak.retainedByteSize, restoredLeak.retainedByteSize)
    assertEquals(originalLeak.displayedLeakTrace.first().nodes.size, restoredLeak.displayedLeakTrace.first().nodes.size)
  }

  /**
   * Builds a real [HeapAnalysisSuccess] using unmocked Shark objects and verifies that [SharkToDataAdapter.mapAnalysis] produces the exact
   * same first trace and leak metadata as parsing `realSuccess.toString()` via [Analysis.fromString].
   */
  @Test
  fun testParityWithLegacyStringParserUsingRealSharkObjects() {
    val rootObj =
      LeakTraceObject(
        type = LeakTraceObject.ObjectType.INSTANCE,
        className = "com.example.RootSingleton",
        labels = linkedSetOf("Singleton holder"),
        leakingStatus = SharkLeakingStatus.NOT_LEAKING,
        leakingStatusReason = "Singleton is a global root",
        retainedHeapByteSize = null,
        retainedObjectCount = null,
      )
    val leakingObj =
      LeakTraceObject(
        type = LeakTraceObject.ObjectType.INSTANCE,
        className = "com.example.LeakedActivity",
        labels = linkedSetOf("Activity.mDestroyed = true"),
        leakingStatus = SharkLeakingStatus.LEAKING,
        leakingStatusReason = "Activity.mDestroyed is true",
        retainedHeapByteSize = 2200,
        retainedObjectCount = 43,
      )
    val reference =
      LeakTraceReference(
        originObject = rootObj,
        referenceType = LeakTraceReference.ReferenceType.INSTANCE_FIELD,
        owningClassName = "com.example.RootSingleton",
        referenceName = "leakedActivityRef",
      )
    val realTrace =
      SharkLeakTrace(
        gcRootType = SharkGcRootType.STICKY_CLASS,
        referencePath = listOf(reference),
        leakingObject = leakingObj,
      )
    val realAppLeak = ApplicationLeak(leakTraces = listOf(realTrace))
    val realSuccess =
      HeapAnalysisSuccess(
        heapDumpFile = File("/tmp/real_parity_test.hprof"),
        createdAtTimeMillis = 1700000000000L,
        dumpDurationMillis = 120L,
        analysisDurationMillis = 450L,
        metadata = mapOf("App" to "com.example"),
        applicationLeaks = listOf(realAppLeak),
        libraryLeaks = emptyList(),
        unreachableObjects = emptyList(),
      )

    val mappedFromShark = SharkToDataAdapter.mapAnalysis(realSuccess)
    val parsedFromText = Analysis.fromString(realSuccess.toString()) as AnalysisSuccess

    assertEquals(parsedFromText.leaks.size, mappedFromShark.leaks.size)
    val expectedFirstTrace = parsedFromText.leaks.first().displayedLeakTrace.first()
    val actualFirstTrace = mappedFromShark.leaks.first().displayedLeakTrace.first()
    assertEquals(expectedFirstTrace, actualFirstTrace)
    assertEquals("RootSingleton", actualFirstTrace.nodes.first().referencingField?.className)
    assertEquals("2.2 kB", actualFirstTrace.nodes.last().retainedHeapSize)
  }

  private fun createMockTrace(suspectIndex: Int = -1): SharkLeakTrace {
    val mockTrace = mock(SharkLeakTrace::class.java)
    val mockGcRoot = mock(SharkGcRootType::class.java)
    `when`(mockGcRoot.name).thenReturn("JNI_LOCAL")
    `when`(mockGcRoot.description).thenReturn("Local variable in native code")
    `when`(mockTrace.gcRootType).thenReturn(mockGcRoot)

    val rootObj = mock(LeakTraceObject::class.java)
    `when`(rootObj.className).thenReturn("com.example.RootActivity")
    `when`(rootObj.type).thenReturn(LeakTraceObject.ObjectType.INSTANCE)
    `when`(rootObj.leakingStatus).thenReturn(SharkLeakingStatus.NOT_LEAKING)
    `when`(rootObj.leakingStatusReason).thenReturn("")
    `when`(rootObj.labels).thenReturn(emptySet())
    `when`(rootObj.retainedHeapByteSize).thenReturn(null)
    `when`(rootObj.retainedObjectCount).thenReturn(null)

    val leakingObj = mock(LeakTraceObject::class.java)
    `when`(leakingObj.className).thenReturn("com.example.LeakedActivity")
    `when`(leakingObj.type).thenReturn(LeakTraceObject.ObjectType.INSTANCE)
    `when`(leakingObj.leakingStatus).thenReturn(SharkLeakingStatus.LEAKING)
    `when`(leakingObj.leakingStatusReason).thenReturn("")
    `when`(leakingObj.labels).thenReturn(emptySet())
    `when`(leakingObj.retainedHeapByteSize).thenReturn(500)
    `when`(leakingObj.retainedObjectCount).thenReturn(1)

    val reference = mock(LeakTraceReference::class.java)
    `when`(reference.originObject).thenReturn(rootObj)
    `when`(reference.referenceName).thenReturn("mContext")
    `when`(reference.owningClassName).thenReturn("com.example.RootActivity")
    `when`(reference.owningClassSimpleName).thenReturn("RootActivity")

    val refType = mock(LeakTraceReference.ReferenceType::class.java)
    `when`(refType.name).thenReturn("INSTANCE_FIELD")
    `when`(reference.referenceType).thenReturn(refType)

    `when`(mockTrace.referencePath).thenReturn(listOf(reference))
    `when`(mockTrace.leakingObject).thenReturn(leakingObj)

    `when`(mockTrace.referencePathElementIsSuspect(0)).thenReturn(suspectIndex == 0)

    return mockTrace
  }
}
