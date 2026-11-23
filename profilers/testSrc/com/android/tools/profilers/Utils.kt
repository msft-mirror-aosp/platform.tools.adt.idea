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
package com.android.tools.profilers

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.ProfilerTaskMetadataProto.ProfilerTaskMetadata
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.android.tools.profilers.tasks.taskhandlers.singleartifact.LiveTaskHandler
import com.android.tools.profilers.utils.ProfilerHashUtils
import com.intellij.openapi.application.ApplicationManager
import java.io.File

interface WithFakeTimer {
  val timer: FakeTimer

  fun tickOneSec() = tick(FakeTimer.ONE_SECOND_IN_NS)

  fun tick(ns: Long) = timer.tick(ns)

  fun <T> tickOneSecThen(get: () -> T) = tickThen(FakeTimer.ONE_SECOND_IN_NS, get)

  fun <T> tickThen(ns: Long, get: () -> T): T {
    tick(ns)
    return get()
  }
}

object Utils {
  fun newDevice(initialState: Common.Device.State, setUp: Common.Device.Builder.() -> Unit) =
    Common.Device.newBuilder()
      .apply {
        state = initialState
        setUp()
      }
      .build()

  fun onlineDevice(setUp: Common.Device.Builder.() -> Unit) = newDevice(Common.Device.State.ONLINE, setUp)

  fun newProcess(
    initialExposureLevel: Common.Process.ExposureLevel = Common.Process.ExposureLevel.UNKNOWN,
    setUp: Common.Process.Builder.() -> Unit,
  ) =
    Common.Process.newBuilder()
      .apply {
        exposureLevel = initialExposureLevel
        state = Common.Process.State.ALIVE
        setUp()
      }
      .build()

  fun debuggableProcess(setUp: Common.Process.Builder.() -> Unit) = newProcess(Common.Process.ExposureLevel.DEBUGGABLE, setUp)

  @JvmStatic fun runOnUi(work: Runnable): Unit = ApplicationManager.getApplication().invokeAndWait(work)

  /**
   * Registers a [LiveTaskHandler] for the [ProfilerTaskType.LIVE_VIEW] task type if [profilers] has no handler for it yet. Selecting a
   * session launches the task of the session's task type, which requires a handler for that task type.
   */
  @JvmStatic
  fun ensureLiveTaskHandler(profilers: StudioProfilers) {
    if (!profilers.taskHandlers.containsKey(ProfilerTaskType.LIVE_VIEW)) {
      profilers.addTaskHandler(ProfilerTaskType.LIVE_VIEW, LiveTaskHandler(profilers.sessionsManager))
    }
  }

  /**
   * Selects [device] and [process], and starts and selects a new session on them, the same way the Task-Based UX does when the user starts
   * a task. Note that a new session is only auto-selected if it is started with a task type, so a Live View task is used (see
   * [ensureLiveTaskHandler]). If [process] is null, the preferred process of [device] is used.
   *
   * The device and process must already be known by [profilers] (e.g. by ticking the timer after adding them to the transport service).
   */
  @JvmStatic
  fun startSession(profilers: StudioProfilers, timer: FakeTimer, device: Common.Device, process: Common.Process?) {
    ensureLiveTaskHandler(profilers)
    profilers.setProcess(device, process, Common.ProfilerTaskType.LIVE_VIEW, false)
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
  }

  /**
   * Starts and selects a new session on [FakeTransportService.FAKE_PROCESS] running on the fake device of [FakeTransportService]. See
   * [startSession].
   */
  @JvmStatic
  fun startSessionOnFakeProcess(profilers: StudioProfilers, timer: FakeTimer) {
    // Make sure the device and process are picked up.
    timer.tick(FakeTimer.ONE_SECOND_IN_NS)
    val device = profilers.deviceProcessMap.keys.first { it.deviceId == FakeTransportService.FAKE_DEVICE_ID }
    val process = profilers.deviceProcessMap[device]!!.first { it.pid == FakeTransportService.FAKE_PROCESS.pid }
    startSession(profilers, timer, device, process)
  }

  /** Reads the metadata file saved next to [recording], a recording that opened in the editor. */
  @JvmStatic
  fun readRecordingMetadata(recording: File): ProfilerTaskMetadata {
    val metadataFile =
      File(recording.absoluteFile.parentFile, "${recording.nameWithoutExtension}-${ProfilerHashUtils.hashTracePath(recording)}.metadata")
    return ProfilerTaskMetadata.parseFrom(metadataFile.readBytes())
  }
}
