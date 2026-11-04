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
package com.android.tools.idea.adb.wireless

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import com.android.tools.adblib.testutils.FakeAdbServerAdbLibRule
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class AdbServiceWrapperAdbLibImplTest {
  private val projectRule = ProjectRule()

  private val adbRule = FakeAdbServerAdbLibRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(adbRule)

  private val project
    get() = projectRule.project

  @Test
  fun waitForOnlineDeviceShouldMatchByMdnsServiceId() = runBlockingWithTimeout {
    // Prepare
    val adbService = AdbServiceWrapperAdbLibImpl(project)
    val mdnsServiceId = "adb-939AX05XBZ-vWgJpq"
    val connectedSerial = "$mdnsServiceId._adb-tls-connect._tcp"
    val pairingResult = PairingResult("192.168.1.10", 41915, mdnsServiceId)

    // Act: Start waiting in a background coroutine
    val deferred = async { adbService.waitForOnlineDevice(pairingResult) }
    delay(100.milliseconds)

    // In FakeAdbServer, the first argument to connectDevice sets device.serialNumber.
    // When ADB auto-connects over mDNS TLS, device.serialNumber is "<guid>._adb-tls-connect._tcp",
    // which matches pairingResult.mdnsServiceId even if ipOrHost differs.
    adbRule.connectDevice("other-device", "Google", "Other", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)
    adbRule.connectDevice(connectedSerial, "Google", "Pixel 10", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)

    // Assert
    val onlineDevice = deferred.await()
    assertThat(onlineDevice.id).isEqualTo(connectedSerial)
  }

  @Test
  fun waitForOnlineDeviceShouldMatchByIpAddress() = runBlockingWithTimeout {
    // Prepare
    val adbService = AdbServiceWrapperAdbLibImpl(project)
    val ipAddress = "192.168.1.10"
    val pairingResult = PairingResult(ipAddress, 41915, "")

    // Act: Start waiting in a background coroutine
    val deferred = async { adbService.waitForOnlineDevice(pairingResult) }
    delay(100.milliseconds)

    // Note: FakeAdbServer splits "host:transport:<serial>" on ':', so we connect using bare ipAddress without ":port".
    adbRule.connectDevice("192.168.1.100", "Google", "Other", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)
    adbRule.connectDevice(ipAddress, "Google", "Pixel 10", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)

    // Assert
    val onlineDevice = deferred.await()
    assertThat(onlineDevice.id).isEqualTo(ipAddress)
  }

  @Test
  fun waitForOnlineDeviceShouldMatchByHostname() = runBlockingWithTimeout {
    // Prepare
    val adbService = AdbServiceWrapperAdbLibImpl(project)
    val hostname = "android-device.local"
    val pairingResult = PairingResult(hostname, 41915, "")

    // Act: Start waiting in a background coroutine
    val deferred = async { adbService.waitForOnlineDevice(pairingResult) }
    delay(100.milliseconds)

    adbRule.connectDevice("${hostname}2", "Google", "Other", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)
    adbRule.connectDevice(hostname, "Google", "Pixel 10", "testRelease", AndroidApiLevel(36), DeviceState.HostConnectionType.NETWORK)

    // Assert
    val onlineDevice = deferred.await()
    assertThat(onlineDevice.id).isEqualTo(hostname)
  }
}
