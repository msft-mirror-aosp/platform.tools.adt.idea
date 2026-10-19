/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.adblib

import com.android.adblib.ServerStatus
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.adb.AdbOptionsService
import com.android.tools.idea.adb.AdbServerStatusRetriever
import com.android.tools.idea.adb.ServerStatusState
import com.android.tools.idea.isAndroidEnvironment
import com.google.wireless.android.sdk.stats.AdbServerStatus
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicReference

/** Retrieve status of ADB Server and upload stats */
class AdbServerStatusReporter(val statusReporter: (AdbServerStatus) -> Unit) : ProjectActivity {
  @Suppress("unused") constructor() : this(::reportAdbStatus)

  /**
   * The last reported status, shared by all open projects: this class is registered as a `backgroundPostStartupActivity`, which is an
   * application-level extension point, so the platform creates a single instance and calls [execute] on it for every project. This way,
   * opening another project connected to the same adb server doesn't log a duplicate, while a changed server (e.g. restarted with a new
   * version) is logged.
   */
  private val lastReportedStatus = AtomicReference<AdbServerStatus?>()

  override suspend fun execute(project: Project) {
    if (!isAndroidEnvironment(project)) {
      return
    }
    AdbServerStatusRetriever.getInstance(project).serverStatusState.collect { state ->
      val builder = AdbServerStatus.newBuilder().setIsManaged(AdbOptionsService.getInstance().optionsUpdater.useUserManagedAdb())
      when (state) {
        // Nothing to report until the adb server is reachable.
        ServerStatusState.NotConnected -> return@collect
        // adb < 35.0.2 doesn't support `server-status`: log an "unknown" version and leave the other fields unset.
        ServerStatusState.Unsupported -> builder.setVersion(ServerStatus.UNKNOWN)
        is ServerStatusState.Supported ->
          builder
            .setVersion(state.status.version)
            .setIsUsbBackendForced(state.status.usbBackendForced)
            .setUsbBackend(state.status.usbBackend.toProto())
            .setMdnsBackend(state.status.mdnsBackEnd.toProto())
            .setIsMdnsBackendForced(state.status.mdnsBackEndForced)
      }
      val status = builder.build()
      if (lastReportedStatus.getAndSet(status) != status) {
        statusReporter(status)
      }
    }
  }
}

private fun reportAdbStatus(adbServerStatus: AdbServerStatus) {
  UsageTracker.log(
    AndroidStudioEvent.newBuilder().setKind(AndroidStudioEvent.EventKind.ADB_SERVER_STATUS).setAdbServerStatus(adbServerStatus)
  )
}

private fun ServerStatus.UsbBackend.toProto(): AdbServerStatus.USBBackend =
  when (this) {
    ServerStatus.UsbBackend.UNKNOWN -> AdbServerStatus.USBBackend.TYPE_USB_UNKNOWN
    ServerStatus.UsbBackend.LIBUSB -> AdbServerStatus.USBBackend.TYPE_LIBUSB
    ServerStatus.UsbBackend.NATIVE -> AdbServerStatus.USBBackend.TYPE_NATIVE
    ServerStatus.UsbBackend.USB_DISABLED -> AdbServerStatus.USBBackend.TYPE_USB_DISABLED
    ServerStatus.UsbBackend.LIBADBUSB -> AdbServerStatus.USBBackend.TYPE_LIBADBUSB
  }

private fun ServerStatus.MdnsBackend.toProto(): AdbServerStatus.MDNSBackend =
  when (this) {
    ServerStatus.MdnsBackend.UNKNOWN -> AdbServerStatus.MDNSBackend.TYPE_MDNS_UNKNOWN
    ServerStatus.MdnsBackend.BONJOUR -> AdbServerStatus.MDNSBackend.TYPE_BONJOUR
    ServerStatus.MdnsBackend.OPENSCREEN -> AdbServerStatus.MDNSBackend.TYPE_OPENSCREEN
    ServerStatus.MdnsBackend.LIBADBMDNS -> AdbServerStatus.MDNSBackend.TYPE_LIBADBMDNS
    ServerStatus.MdnsBackend.MDNS_DISABLED -> AdbServerStatus.MDNSBackend.TYPE_MDNS_DISABLED
  }
