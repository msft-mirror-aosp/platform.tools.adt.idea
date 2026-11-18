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
package com.android.tools.idea.execution.common.deploy

import com.android.adblib.ConnectedDevice
import com.android.ddmlib.IDevice
import com.android.tools.deployer.common.DeviceHolder
import com.android.tools.idea.adblib.toConnectedDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

private val LOG = Logger.getInstance("com.android.tools.idea.execution.common.deploy.DeviceHolderUtils")

/**
 * Creates a [DeviceHolder] for this [IDevice], looking up the matching [ConnectedDevice] in the [project]'s AdbSession.
 *
 * If no matching [ConnectedDevice] is found, e.g. because the device got disconnected, a warning is logged and the returned [DeviceHolder]
 * is created without a [ConnectedDevice].
 */
suspend fun IDevice.toDeviceHolder(project: Project): DeviceHolder {
  val connectedDevice = toConnectedDevice(project)
  if (connectedDevice == null) {
    LOG.warn("ConnectedDevice lookup for serial $serialNumber failed to find a device")
  }
  return DeviceHolder(serialNumber, connectedDevice)
}
