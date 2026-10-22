/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.profilers.perfetto

import com.android.tools.adtui.model.Range
import com.android.tools.idea.protobuf.CodedInputStream
import com.android.tools.idea.protobuf.ExtensionRegistryLite
import com.android.tools.idea.protobuf.WireFormat
import com.android.tools.profilers.IdeProfilerServices
import com.android.tools.profilers.cpu.CpuCapture
import com.android.tools.profilers.cpu.MainProcessSelector
import com.android.tools.profilers.cpu.TraceParser
import com.android.tools.profilers.cpu.systemtrace.ProcessListSorter
import com.android.tools.profilers.cpu.systemtrace.SystemTraceCpuCaptureBuilder
import com.android.tools.profilers.cpu.systemtrace.SystemTraceSurfaceflingerManager
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import perfetto.protos.PerfettoTrace

class PerfettoParser(private val mainProcessSelector: MainProcessSelector, private val ideProfilerServices: IdeProfilerServices) :
  TraceParser {

  companion object {
    private val LOGGER = Logger.getInstance(PerfettoParser::class.java)
    // This lock controls access to TPD, see comment below in the synchronized block.
    // We use this instead of @Synchronized, because CpuCaptureParser build and return new instances of PerfettoParser,
    // so we need a static object to serve as the lock monitor.
    val TPD_LOCK = Any()

    /** Tag of `Trace.packet`, the only field of the root `Trace` message: field 1, length-delimited. */
    private const val TRACE_PACKET_TAG = (1 shl 3) or WireFormat.WIRETYPE_LENGTH_DELIMITED

    /**
     * @param file handle to a file that represents a perfetto trace. A single trace packet will be read from this file to verify the file
     *   can be opened and parsed to a `TracePacket`.
     * @return true if one `TracePacket` was able to be read from the file.
     */
    @JvmStatic
    fun verifyFileHasPerfettoTraceHeader(file: File): Boolean {
      // 1. Try to read it as a ZIP archive, as Tracing 2.0 captures are zipped.
      try {
        ZipFile(file).use { zipFile ->
          for (entry in zipFile.entries()) {
            zipFile.getInputStream(entry).use { entryStream ->
              if (isPerfettoStream(entryStream)) {
                return true
              }
            }
          }
          return false // Valid zip, but no Perfetto entries found
        }
      } catch (e: Exception) {
        LOGGER.info("File ${file.name} is not a ZIP archive (or threw ${e.javaClass.simpleName}), falling back to raw Perfetto check.")
      }

      // 2. Fall back to reading it as a raw file
      return try {
        FileInputStream(file).use { isPerfettoStream(it) }
      } catch (e: IOException) {
        false
      }
    }

    /** Helper function to verify if an InputStream starts with a valid Perfetto trace packet. */
    private fun isPerfettoStream(input: InputStream): Boolean {
      return try {
        startsWithTracePacket(input)
      } catch (e: IOException) {
        LOGGER.warn("Failed to read the first Perfetto trace packet.", e)
        false
      }
    }

    /**
     * Returns true if [input] starts with a `Trace.packet` field that parses as a `TracePacket`, false if it is empty or starts with any
     * other field.
     *
     * Only the first packet is read, because reading the full `Trace` requires keeping every packet in memory.
     *
     * @throws IOException if the first packet is malformed.
     */
    @VisibleForTesting
    internal fun startsWithTracePacket(input: InputStream): Boolean {
      val stream = CodedInputStream.newInstance(input)
      // A tag of 0 means we have reached the end of the stream. Any tag other than the one of `Trace.packet` means this is not a trace.
      if (stream.readTag() != TRACE_PACKET_TAG) {
        return false
      }
      stream.readMessage(PerfettoTrace.TracePacket.parser(), ExtensionRegistryLite.getEmptyRegistry())
      return true
    }
  }

  override fun parse(file: File, traceId: Long): CpuCapture {
    // We only allow one instance running here, because TPD currently doesn't handle the multiple loaded traces case (since we can only see
    // one in the UI), so any attempt of doing so (like double clicking very fast in the UI to trigger two parses) might lead to a race
    // condition and end up with a failure.
    synchronized(TPD_LOCK) {
      val traceProcessor = ideProfilerServices.traceProcessorService

      val traceLoaded = traceProcessor.loadTrace(traceId, file, ideProfilerServices)
      if (!traceLoaded) {
        error("Unable to load trace with TPD.")
      }

      val processList = traceProcessor.getProcessMetadata(traceId, ideProfilerServices)
      check(processList.isNotEmpty()) { "Invalid trace without any process information." }
      val traceUIMetadata = traceProcessor.getTraceMetadata(traceId, "ui_state", ideProfilerServices)
      var processHint = mainProcessSelector.nameHint
      val initialViewRange = Range()

      if (traceUIMetadata.isNotEmpty()) {
        try {
          val uiState = PerfettoTrace.UiState.parseFrom(Base64.getDecoder().decode(traceUIMetadata.last()))
          if (uiState.highlightProcess.hasPid()) {
            val wantedProcessId = uiState.getHighlightProcess().getPid()
            processHint = processList.find { it.id == wantedProcessId }?.getSafeProcessName() ?: mainProcessSelector.nameHint
          } else if (uiState.highlightProcess.hasCmdline()) {
            processHint = uiState.highlightProcess.cmdline
          }
          if (uiState.timelineStartTs != 0L && uiState.timelineEndTs != 0L) {
            initialViewRange.set(
              TimeUnit.NANOSECONDS.toMicros(uiState.timelineStartTs).toDouble(),
              TimeUnit.NANOSECONDS.toMicros(uiState.timelineEndTs).toDouble(),
            )
          }
        } catch (throwable: Throwable) {
          // Failed to parse / decode ui metadata log and continue.
          LOGGER.warn("Trace contained ui-state, however it failed to parse correctly. Ui state will not be loaded", throwable)
        }
      }
      // If a valid process was not parsed from the UI metadata
      val processListSorter = ProcessListSorter(processHint)
      val userSelectedProcess = mainProcessSelector.apply(processListSorter.sort(processList))
      checkNotNull(userSelectedProcess) { "It was not possible to select a process for this trace." }
      val selectedProcess = processList.first { processModel -> processModel.id == userSelectedProcess }
      val processesToQuery =
        (listOf(selectedProcess) +
            listOfNotNull(
              processList.find { it.getSafeProcessName().endsWith(SystemTraceSurfaceflingerManager.SURFACEFLINGER_PROCESS_NAME) }
            ))
          .distinct()
      val model = traceProcessor.loadCpuData(traceId, processesToQuery, selectedProcess, ideProfilerServices)

      // Track the power rail and battery counter count for power profiler usage metrics.
      // Note: "powerRailCount" is the number of raw power rails. Counting this raw count
      // will help us know the capabilities of phones when ODPM devices get more popular.
      // It will additionally tell us if total number is more than what we anticipate for
      // a certain device.
      val powerRailCount = model.getPowerRails().size
      val batteryCounterCount = model.getBatteryDrain().size
      ideProfilerServices.featureTracker.trackPowerProfilerCapture(powerRailCount, batteryCounterCount)

      val builder = SystemTraceCpuCaptureBuilder(model)

      if (initialViewRange.isEmpty) {
        val traceStartUs = model.getCaptureStartTimestampUs().toDouble()
        val traceEndUs = model.getCaptureEndTimestampUs().toDouble()

        // Default to the full duration of the captured trace
        val fallbackRange = Range(traceStartUs, traceEndUs)
        initialViewRange.set(fallbackRange)

        // Fetch specific start/end markers from metadata, if available to refine the view
        val tracingStartedNs =
          traceProcessor.getTraceMetadata(traceId, "tracing_started_ns", ideProfilerServices).firstOrNull()?.toLongOrNull()
        val tracingDisabledNs =
          traceProcessor.getTraceMetadata(traceId, "tracing_disabled_ns", ideProfilerServices).firstOrNull()?.toLongOrNull()

        if (tracingStartedNs != null && tracingDisabledNs != null) {
          val mdStartUs = TimeUnit.NANOSECONDS.toMicros(tracingStartedNs).toDouble()
          val mdEndUs = TimeUnit.NANOSECONDS.toMicros(tracingDisabledNs).toDouble()
          val metadataRange = Range(mdStartUs, mdEndUs)
          val visibleRange = fallbackRange.getIntersection(metadataRange)
          // Update the view range if the intersection of start/end markers from metadata and trace events is valid
          if (!visibleRange.isEmpty) {
            initialViewRange.set(visibleRange)
          }
        }
      }
      return builder.build(traceId, userSelectedProcess, initialViewRange)
    }
  }
}
