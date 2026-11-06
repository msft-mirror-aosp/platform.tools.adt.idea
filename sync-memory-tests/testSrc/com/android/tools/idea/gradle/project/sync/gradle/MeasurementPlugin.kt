/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.gradle

import com.sun.management.GarbageCollectionNotificationInfo
import com.sun.management.HotSpotDiagnosticMXBean
import java.io.Closeable
import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.Instant
import javax.inject.Inject
import javax.management.MBeanServer
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.ObjectName
import javax.management.openmbean.CompositeData
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceSpec
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener

enum class CaptureType {
  HEAP_HISTOGRAM,
  HEAP_DUMP,
  HEAP_PEAK,
  TIMESTAMP,
}

enum class MeasurementCheckpoint {
  CONFIGURATION_FINISHED,
  SYNC_FINISHED,
}

// Functions and variables can't be top level and has to be static to keep Gradle happy.
object MeasurementPluginConfig {
  var outputPath: String = ""
  var captureTypes: Set<CaptureType> = emptySet()
  var captureJfr: Boolean = false

  /** Call this from a benchmark test to configure and apply the plugin globally via init script in Gradle home. */
  @JvmStatic
  fun configureAndApply(outputPath: String, captureTypes: Set<CaptureType>, captureJfr: Boolean = false) {

    val src = File("tools/adt/idea/sync-memory-tests/testSrc/com/android/tools/idea/gradle/project/sync/gradle/MeasurementPlugin.kt")
    val initScript = File(System.getProperty("gradle.user.home")).resolve("init.gradle.kts")
    src.copyTo(initScript, overwrite = true)
    // Apply the plugin to Settings via beforeSettings instead of applying it to the Gradle instance at the top level, as applying a
    // plugin to the Gradle instance makes Gradle report a Project Isolation violation and discard the configuration cache entry.
    initScript.appendText(
      """
      ${this::class.simpleName}.${this::outputPath.name} = "$outputPath"
      ${this::class.simpleName}.${this::captureJfr.name} = $captureJfr
      ${this::class.simpleName}.${this::captureTypes.name} = setOf(${
      captureTypes.joinToString(",") { "${CaptureType::class.simpleName}.$it" }
    })
      beforeSettings {
        apply<${MeasurementPlugin::class.simpleName}>()
      }
    """
        .trimIndent()
    )
  }
}

// Uses lifecycle callbacks instead of build listeners so that it is compatible with the configuration cache. Note that when the
// configuration cache is reused, configuration callbacks do not run, so CONFIGURATION_FINISHED is not recorded for such builds.
class MeasurementPlugin @Inject constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Settings> {
  override fun apply(settings: Settings) {
    if (settings.gradle.parent == null && CaptureType.HEAP_PEAK in MeasurementPluginConfig.captureTypes) {
      HeapPeakTracker.startBuild()
    }
    if (MeasurementPluginConfig.captureJfr) {
      EventRecorder.startJavaFlightRecording()
    }
    settings.gradle.projectsEvaluated {
      if (it.parent == null && CaptureType.HEAP_PEAK in MeasurementPluginConfig.captureTypes) {
        // Capture peak heap before CONFIGURATION_FINISHED triggers a Full GC via gcClassHistogram / dumpHeap.
        EventRecorder.captureHeapPeak(EventRecorder.CONFIGURATION_PEAK_HEAP_SUFFIX)
      }
      EventRecorder.recordEvent(MeasurementCheckpoint.CONFIGURATION_FINISHED)
    }

    val serviceProvider =
      settings.gradle.sharedServices.registerIfAbsent(
        "measurement-service",
        MeasurementService::class.java,
        object : Action<BuildServiceSpec<MeasurementServiceParams>> {
          override fun execute(spec: BuildServiceSpec<MeasurementServiceParams>) {
            spec.parameters.outputPath.set(MeasurementPluginConfig.outputPath)
            spec.parameters.captureTypes.set(MeasurementPluginConfig.captureTypes)
            spec.parameters.captureJfr.set(MeasurementPluginConfig.captureJfr)
          }
        },
      )
    registry.onTaskCompletion(serviceProvider)
    serviceProvider.get()
  }
}

interface MeasurementServiceParams : BuildServiceParameters {
  val outputPath: Property<String>
  val captureTypes: SetProperty<CaptureType>
  val captureJfr: Property<Boolean>
}

abstract class MeasurementService : OperationCompletionListener, Closeable, BuildService<MeasurementServiceParams> {
  init {
    if (CaptureType.HEAP_PEAK in MeasurementPluginConfig.captureTypes) {
      // Ensure peak heap tracking starts even on warm Configuration Cache hits where beforeSettings does not run.
      HeapPeakTracker.ensureStarted()
    }
  }

  // Ignored event, we only care about the one right before closing
  override fun onFinish(event: FinishEvent?) {}

  override fun close() {
    // When the configuration cache is reused, the init script does not run and MeasurementService may be deserialized in a fresh
    // ClassLoader, so restore MeasurementPluginConfig from the serialized BuildServiceParameters before recording events.
    MeasurementPluginConfig.outputPath = parameters.outputPath.get()
    MeasurementPluginConfig.captureTypes = parameters.captureTypes.get()
    MeasurementPluginConfig.captureJfr = parameters.captureJfr.get()
    if (MeasurementPluginConfig.captureJfr) {
      EventRecorder.stopJavaFlightRecording()
    }
    if (CaptureType.HEAP_PEAK in MeasurementPluginConfig.captureTypes) {
      // Capture peak heap before SYNC_FINISHED triggers a Full GC via gcClassHistogram / dumpHeap.
      EventRecorder.captureHeapPeak(EventRecorder.SYNC_PEAK_HEAP_SUFFIX)
    }
    HeapPeakTracker.stopBuild()
    EventRecorder.recordEvent(MeasurementCheckpoint.SYNC_FINISHED)
    EventRecorder.captureGcCollectionTime()
  }
}

/**
 * Tracks the Gradle daemon's post-GC live + promoted heap high-water mark (`Old + Survivor` occupancy) across a build.
 *
 * Summing `MemoryPoolMXBean.peakUsage.used` across G1 heap pools over-reports peak heap (exceeding `-Xmx`) because G1 dynamically reassigns
 * physical regions between Eden, Survivor, and Old Gen at different times. Pre-GC total heap (`memoryUsageBeforeGc`) includes uncollected
 * G1 Eden TLAB garbage whose size fluctuates adaptively with G1 pause targets. Listening to evacuating GC notifications and tracking the
 * post-pause non-Eden heap (`memoryUsageAfterGc`) captures the true simultaneous `Old + Survivor` high-water mark with low run-to-run
 * variance, floored by current non-Eden heap usage to capture humongous allocations even if no GC pause occurs afterward.
 */
object HeapPeakTracker {
  private val registeredListeners = mutableListOf<Pair<NotificationEmitter, NotificationListener>>()
  private var peakHeapBytes: Long = 0L
  private var buildActive: Boolean = false

  @JvmStatic
  @Synchronized
  fun startBuild() {
    unregisterListeners()
    buildActive = true
    val heapPoolNames = heapPools().map { it.name }.toSet()
    peakHeapBytes = currentNonEdenHeapBytes()
    for (gcBean in ManagementFactory.getGarbageCollectorMXBeans()) {
      val emitter = gcBean as? NotificationEmitter ?: continue
      val listener = NotificationListener { notification, _ ->
        if (notification.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
          val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
          val afterUsage = info.gcInfo.memoryUsageAfterGc.mapValues { (_, usage) -> usage.used }
          onGcAfterUsage(info.gcName, heapPoolNames, afterUsage)
        }
      }
      emitter.addNotificationListener(listener, null, null)
      registeredListeners.add(emitter to listener)
    }
  }

  /**
   * Starts tracking peak heap for the current build if not already started by [startBuild] (for example, on a warm Configuration Cache hit
   * where configuration callbacks do not run and [MeasurementService] is restored directly by Gradle).
   */
  @JvmStatic
  @Synchronized
  fun ensureStarted() {
    if (!buildActive) {
      startBuild()
    }
  }

  @JvmStatic
  @Synchronized
  fun stopBuild() {
    buildActive = false
    unregisterListeners()
  }

  @JvmStatic @Synchronized fun isBuildActive(): Boolean = buildActive

  @JvmStatic
  @JvmOverloads
  @Synchronized
  fun currentPeakHeapBytes(currentNonEdenBytes: Long = currentNonEdenHeapBytes()): Long =
    max(peakHeapBytes, currentNonEdenBytes).also { peakHeapBytes = it }

  @JvmStatic
  @Synchronized
  fun resetForTest(initialBytes: Long = 0L) {
    buildActive = false
    unregisterListeners()
    peakHeapBytes = initialBytes
  }

  @JvmStatic
  @Synchronized
  fun onGcAfterUsage(gcName: String, heapPoolNames: Set<String>, afterUsageByPool: Map<String, Long>) {
    // Concurrent collector notifications (e.g. "G1 Concurrent GC") fire at remark/cleanup pause boundaries without evacuating Eden.
    if (gcName.contains("Concurrent", ignoreCase = true)) return
    val nonEdenAfterBytes =
      afterUsageByPool.entries
        .filter { (poolName, _) -> poolName in heapPoolNames && !poolName.contains("Eden", ignoreCase = true) }
        .sumOf { (_, used) -> used.coerceAtLeast(0L) }
    peakHeapBytes = max(peakHeapBytes, nonEdenAfterBytes)
  }

  private fun unregisterListeners() {
    for ((emitter, listener) in registeredListeners) {
      runCatching { emitter.removeNotificationListener(listener) }
    }
    registeredListeners.clear()
  }

  private fun heapPools() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

  private fun currentNonEdenHeapBytes(): Long =
    heapPools().filter { !it.name.contains("Eden", ignoreCase = true) }.sumOf { it.usage.used.coerceAtLeast(0L) }
}

// Functions can't be top level and has to be static to keep Gradle happy.
object EventRecorder {
  const val GC_COLLECTION_TIME_FILE_NAME_SUFFIX = "gcCollectionTime"
  const val CONFIGURATION_PEAK_HEAP_SUFFIX = "CONFIGURATION_PEAK_HEAP"
  const val SYNC_PEAK_HEAP_SUFFIX = "SYNC_PEAK_HEAP"

  @JvmStatic
  fun recordEvent(checkpoint: MeasurementCheckpoint) {
    println("Recording event ${checkpoint.name}")
    if (CaptureType.HEAP_DUMP in MeasurementPluginConfig.captureTypes) {
      captureHeapOfCurrentProcess(checkpoint)
    }
    if (CaptureType.HEAP_HISTOGRAM in MeasurementPluginConfig.captureTypes) {
      captureHeapHistogramOfCurrentProcess(checkpoint)
    }
    if (CaptureType.TIMESTAMP in MeasurementPluginConfig.captureTypes) {
      captureEventTimestamp(checkpoint)
    }
  }

  @JvmStatic
  fun captureHeapPeak(metricSuffix: String) {
    val peakBytes = HeapPeakTracker.currentPeakHeapBytes()
    val filePeak = File(MeasurementPluginConfig.outputPath).resolve("${Instant.now().toEpochMilli()}_$metricSuffix.peak")
    println("Capturing heap peak ($metricSuffix = $peakBytes bytes) at ${filePeak.path}")
    filePeak.writeText(peakBytes.toString())
  }

  @JvmStatic
  fun startJavaFlightRecording() {
    val server = ManagementFactory.getPlatformMBeanServer()
    println("Started capturing jfr")
    println(server.execute("jfrStart", arrayOf(arrayOf("name=jfr settings=profile method-profiling=max"))))
  }

  @JvmStatic
  fun stopJavaFlightRecording() {
    val fileJfr = File(MeasurementPluginConfig.outputPath).resolve("${Instant.now().toEpochMilli()}_gradle.jfr")
    val server = ManagementFactory.getPlatformMBeanServer()

    println(server.execute("jfrStop", arrayOf(arrayOf("name=jfr filename=${fileJfr.path}"))))
  }

  @JvmStatic
  fun captureGcCollectionTime() {
    val collectionTime = ManagementFactory.getGarbageCollectorMXBeans().sumOf { (it as GarbageCollectorMXBean).collectionTime }
    val file = File(MeasurementPluginConfig.outputPath).resolve("${Instant.now().toEpochMilli()}_$GC_COLLECTION_TIME_FILE_NAME_SUFFIX")
    file.writeText(collectionTime.toString())
    println("Total accumulated GC Collection time: ${collectionTime.milliseconds}")
  }

  @JvmStatic
  private fun captureEventTimestamp(checkpoint: MeasurementCheckpoint) {
    val name = checkpoint.name
    val now = Instant.now()
    val fileTimestamp = File(MeasurementPluginConfig.outputPath).resolve("${now.toEpochMilli()}_$name.timestamp")
    println("Capturing timestamp at ${fileTimestamp.path}")

    fileTimestamp.writeText(now.toString())
  }

  @JvmStatic
  private fun captureHeapHistogramOfCurrentProcess(checkpoint: MeasurementCheckpoint) {
    val name = checkpoint.name
    val server = ManagementFactory.getPlatformMBeanServer()
    val histogram = server.execute("gcClassHistogram")
    val fileHistogram = File(MeasurementPluginConfig.outputPath).resolve("${Instant.now().toEpochMilli()}_${name}.histogram")
    println("Capturing histogram at ${fileHistogram.path}")
    fileHistogram.writeText(histogram)
  }

  @JvmStatic
  private fun captureHeapOfCurrentProcess(checkpoint: MeasurementCheckpoint) {
    val name = checkpoint.name
    val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()
    val mxBean: HotSpotDiagnosticMXBean =
      ManagementFactory.newPlatformMXBeanProxy(server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean::class.java)
    val heapDumpPath = File(MeasurementPluginConfig.outputPath).resolve("${Instant.now().toEpochMilli()}_${name}.hprof").path
    println("Capturing heap dump at $heapDumpPath")
    mxBean.dumpHeap(heapDumpPath, true)
  }

  @JvmStatic
  private fun MBeanServer.execute(name: String, args: Array<Array<String>?> = arrayOf(null)) =
    invoke(ObjectName("com.sun.management:type=DiagnosticCommand"), name, args, arrayOf(Array<String>::class.java.name)).toString()
}
