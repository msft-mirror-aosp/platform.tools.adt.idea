/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.idea.run.tasks

import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.waitForDevice
import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.android.tools.deployer.Deployer
import com.android.tools.deployer.common.ApkVerifierTracker
import com.android.tools.deployer.common.Canceller
import com.android.tools.deployer.common.InstallOptions
import com.android.tools.deployer.model.App
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import org.mockito.kotlin.whenever

class DeployTaskTest {
  @get:Rule val appRule = ApplicationRule()
  @Mock private lateinit var project: Project
  @Mock private lateinit var deployer: Deployer
  @Spy private lateinit var canceller: Canceller

  private val adbSession = FakeAdbSession()

  @Before
  fun setup() {
    MockitoAnnotations.initMocks(this)
    ApkVerifierTracker.clear()
    whenever(deployer.install(any(), any(), any())).thenReturn(Deployer.Result(false, false, false, App.fromApks("id", emptyList())))
    whenever(canceller.cancelled()).thenReturn(false)
  }

  @After
  fun tearDown() {
    adbSession.close()
  }

  @Test
  fun testDeploy() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.BASE)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().build()

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployWithUserPmOptions() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.BASE)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setUserInstallOptions("-v").build()

    val deployTask = makeDeployTaskForTesting(userInstallOptions = "-v")
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployEmbedded() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.BASE, embedded = true)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setGrantAllPermissions().build()

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployEmbeddedWithUserPmOptions() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.BASE, embedded = true)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setGrantAllPermissions().setUserInstallOptions("-v").build()

    val deployTask = makeDeployTaskForTesting(userInstallOptions = "-v")
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployApi28() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.P)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setInstallFullApk().setDontKill().build()

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  @Ignore("b/389067070")
  fun testDeployApi33() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.TIRAMISU)
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setInstallFullApk().build()

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
    verify(device, never()).forceStop(any())
  }

  @Test
  fun testDeployApi28WithUserPmOptions() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.P)
    val expectedOptions =
      InstallOptions.builder().setAllowDebuggable().setInstallFullApk().setDontKill().setUserInstallOptions("-v").build()

    val deployTask = makeDeployTaskForTesting(userInstallOptions = "-v")
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployToCurrentUserOnly() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.P)
    val expectedOptions =
      InstallOptions.builder().setAllowDebuggable().setInstallOnUser(InstallOptions.CURRENT_USER).setInstallFullApk().setDontKill().build()

    val deployTask = makeDeployTaskForTesting(installOnAllUsers = false)
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(deployer, atLeast(1)).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployPostInstallForceStopPreN() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.BASE)

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(device, never()).forceStop(any())
  }

  @Test
  fun testDeployPostInstallForceStopPostN() {
    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.N)

    val deployTask = makeDeployTaskForTesting()
    deployTask.perform(device, connectedDevice, deployer, mock(ApkInfo::class.java), canceller)
    verify(device, times(1)).forceStop(any())
  }

  @Test
  fun testDeployApkWithRequiredInstallOptionsOnApi30() {
    val expectedOptions =
      InstallOptions.builder().setAllowDebuggable().setGrantAllPermissions().setForceQueryable().setInstallFullApk().setDontKill().build()

    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.R)
    deployApkWithRequiredInstallOptions(device, connectedDevice)

    verify(deployer).install(any(), eq(expectedOptions), any())
    verify(device, times(1)).forceStop(any())
  }

  @Test
  fun testDeployApkWithRequiredInstallOptionsOnApi29() {
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().setGrantAllPermissions().setInstallFullApk().setDontKill().build()

    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.Q)
    deployApkWithRequiredInstallOptions(device, connectedDevice)

    verify(deployer).install(any(), eq(expectedOptions), any())
  }

  @Test
  fun testDeployApkWithRequiredInstallOptionsOnApi22() {
    val expectedOptions = InstallOptions.builder().setAllowDebuggable().build()

    val (device, connectedDevice) = setupDevice(AndroidVersion.VersionCodes.LOLLIPOP_MR1)
    deployApkWithRequiredInstallOptions(device, connectedDevice)

    verify(deployer).install(any(), eq(expectedOptions), any())
    verify(device, never()).forceStop(any())
  }

  private fun deployApkWithRequiredInstallOptions(device: IDevice, connectedDevice: ConnectedDevice) {
    val mockApkInfo = mock(ApkInfo::class.java)
    whenever(mockApkInfo.requiredInstallOptions)
      .thenReturn(setOf(ApkInfo.AppInstallOption.FORCE_QUERYABLE, ApkInfo.AppInstallOption.GRANT_ALL_PERMISSIONS))

    val deployTask = makeDeployTaskForTesting(listOf(mockApkInfo))
    deployTask.perform(device, connectedDevice, deployer, mockApkInfo, canceller)
  }

  private fun setupDevice(
    apiLevel: Int,
    embedded: Boolean = false,
  ): Pair<IDevice, ConnectedDevice> {
    val serialNumber = "1234"
    val device = mock(IDevice::class.java)
    whenever(device.serialNumber).thenReturn(serialNumber)
    whenever(device.supportsFeature(IDevice.HardwareFeature.EMBEDDED)).thenReturn(embedded)
    whenever(device.version).thenReturn(AndroidVersion(apiLevel))

    adbSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serialNumber, DeviceState.ONLINE)), emptyList())
    val connectedDevice = runBlocking { adbSession.connectedDevicesTracker.waitForDevice(serialNumber) }
    val properties = buildMap {
      put(DevicePropertyNames.RO_BUILD_VERSION_SDK, apiLevel.toString())
      if (embedded) {
        put(DevicePropertyNames.RO_BUILD_CHARACTERISTICS, "embedded")
      }
    }
    adbSession.deviceServices.configureDeviceProperties(
      DeviceSelector.fromSerialNumber(serialNumber),
      properties,
    )
    return Pair(device, connectedDevice)
  }

  private fun makeDeployTaskForTesting(
    packages: Collection<ApkInfo> = listOf(),
    userInstallOptions: String? = null,
    installOnAllUsers: Boolean = true,
    alwaysInstallWithPm: Boolean = false,
    allowAssumeVerified: Boolean = false,
    makeBeforeRun: Boolean = true,
  ) = DeployTask(project, packages, null, userInstallOptions, installOnAllUsers, alwaysInstallWithPm, allowAssumeVerified, makeBeforeRun)
}
