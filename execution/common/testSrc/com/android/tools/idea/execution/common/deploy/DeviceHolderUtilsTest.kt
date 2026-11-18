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

import com.android.adblib.connectedDevicesTracker
import com.android.adblib.device
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.tools.adblib.testutils.FakeAdbServerAdbLibRule
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.adblib.AdbLibService
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class DeviceHolderUtilsTest {
  private val projectRule = ProjectRule()
  private val fakeAdbRule = FakeAdbServerAdbLibRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(fakeAdbRule)

  @Test
  fun toDeviceHolder_returnsDeviceHolderBackedByConnectedDevice() = runBlocking {
    // Prepare
    val iDevice = connectDevice()

    // Act
    val deviceHolder = iDevice.toDeviceHolder(projectRule.project)

    // Assert
    assertThat(deviceHolder.serialNumber).isEqualTo(SERIAL_NUMBER)
    // DeviceHolder doesn't expose its ConnectedDevice, so check it indirectly: the version is read from the ConnectedDevice's properties.
    assertThat(deviceHolder.version.apiLevel).isEqualTo(36)
  }

  @Test
  fun toDeviceHolder_returnsDeviceHolderWithoutConnectedDevice_ifDeviceNotFound() = runBlocking {
    // Prepare
    val iDevice = connectDevice()
    val project = projectRule.project

    // Disconnect device from server so it's removed from AdbSessions
    fakeAdbRule.adbServer.disconnectDevice(SERIAL_NUMBER).get()
    yieldUntil { AdbLibApplicationService.instance.session.connectedDevicesTracker.device(SERIAL_NUMBER) == null }
    yieldUntil { AdbLibService.getSession(project).connectedDevicesTracker.device(SERIAL_NUMBER) == null }

    // Act
    val deviceHolder = iDevice.toDeviceHolder(project)

    // Assert
    assertThat(deviceHolder.serialNumber).isEqualTo(SERIAL_NUMBER)
    // DeviceHolder doesn't expose its ConnectedDevice, so check it indirectly: DeviceHolder.version falls back to AndroidVersion.DEFAULT
    // when there is no ConnectedDevice to read the device properties from.
    assertThat(deviceHolder.version).isEqualTo(AndroidVersion.DEFAULT)
  }

  private suspend fun connectDevice(): IDevice {
    fakeAdbRule.connectDevice(SERIAL_NUMBER, "m1", "m2", "10", AndroidApiLevel(36), DeviceState.HostConnectionType.USB)
    val bridge = AndroidDebugBridge.getBridge() ?: error("No bridge")
    yieldUntil { bridge.devices.any { it.serialNumber == SERIAL_NUMBER } }
    return bridge.devices.first { it.serialNumber == SERIAL_NUMBER }
  }

  private companion object {
    const val SERIAL_NUMBER = "device-1"
  }
}
