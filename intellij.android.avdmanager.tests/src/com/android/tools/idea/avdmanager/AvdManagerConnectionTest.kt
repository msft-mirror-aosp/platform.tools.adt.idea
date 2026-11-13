/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.avdmanager

import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.deviceprovisioner.DeviceActionCanceledException
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.EmptyIcon
import com.android.sdklib.deviceprovisioner.LocalEmulatorProperties
import com.android.sdklib.deviceprovisioner.RunningAvd
import com.android.sdklib.deviceprovisioner.testing.DeviceProvisionerRule
import com.android.sdklib.deviceprovisioner.testing.FakeAdbDeviceProvisionerPlugin
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.internal.avd.BootMode
import com.android.sdklib.internal.avd.ColdBoot
import com.android.sdklib.internal.avd.OnDiskSkin
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.targets.SystemImage
import com.android.sdklib.util.ProcessHandleProvider
import com.android.testutils.ProcessHandleProviderRule
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.android.testutils.file.recordExistingFile
import com.android.testutils.truth.PathSubject
import com.android.tools.idea.avdmanager.AvdManagerConnection.Companion.resetConnectionFactory
import com.android.tools.idea.deviceprovisioner.ActivatableDeviceHandle
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.utils.NullLogger
import com.google.common.truth.Truth.assertThat
import com.intellij.credentialStore.Credentials
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.intellij.util.net.ProxyConfiguration
import com.intellij.util.net.ProxyConfiguration.ProxyProtocol
import com.intellij.util.net.ProxyCredentialStore
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [AvdManagerConnection]. */
class AvdManagerConnectionTest {
  @get:Rule val projectRule = ProjectRule()
  @get:Rule val disposableRule = DisposableRule()
  @get:Rule val deviceProvisionerRule = DeviceProvisionerRule()
  @get:Rule val processHandleProviderRule = ProcessHandleProviderRule()
  @get:Rule val testName = TestName()

  private val project: Project
    get() = projectRule.project

  private val name: String
    get() = testName.methodName

  private val sdkRoot = createInMemoryFileSystemAndFolder("sdk")
  private val prefsRoot: Path = sdkRoot.root.resolve("android-home")

  private lateinit var avdManager: AvdManager
  private lateinit var avdManagerConnection: AvdManagerConnection
  private lateinit var avdFolder: Path
  private lateinit var systemImage: SystemImage

  @Before
  fun setUp() {
    sdkRoot.resolve("tools/lib/emulator/snapshots.img").recordExistingFile()
    recordGoogleApisSysImg23(sdkRoot)
    recordEmulatorVersion_23_4_5(sdkRoot)

    val androidSdkHandler = AndroidSdkHandler(sdkRoot, prefsRoot)

    avdManager =
      AvdManager.createInstance(
        androidSdkHandler,
        prefsRoot.resolve("avd"),
        DeviceManager.createInstance(androidSdkHandler, NullLogger.getLogger()),
        NullLogger.getLogger(),
      )

    avdFolder = AvdInfo.getDefaultAvdFolder(avdManager, name, false)
    systemImage = androidSdkHandler.getSystemImageManager(FakeProgressIndicator()).images.first()

    // We use Dispatchers.Unconfined to show dialogs: this causes MessageDialog to be invoked on the
    // calling thread. We don't simulate
    // user input in these tests; the dialogs just immediately throw an exception.
    avdManagerConnection = AvdManagerConnection(androidSdkHandler, avdManager, Dispatchers.Unconfined)

    val mockService = mock<DeviceProvisionerService>()
    whenever(mockService.deviceProvisioner).thenReturn(deviceProvisionerRule.deviceProvisioner)
    project.replaceService(DeviceProvisionerService::class.java, mockService, disposableRule.disposable)
  }

  @After
  fun tearDown() {
    resetConnectionFactory()
  }

  @Test
  fun testWipeAvd() {
    // Create an AVD
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    // Create files that are present on some but not all AVDs.
    createFile(avdFolder, "sdcard.img")
    createFile(avdFolder, "user-settings.ini")

    // Create few additional files and directories.
    createFile(avdFolder, "cache.img")
    createFile(avdFolder, AvdManager.USERDATA_QEMU_IMG)
    createFile(avdFolder, "snapshots/default_boot/snapshot.pb")
    createFile(avdFolder, "data/misc/pstore/pstore.bin")

    // Do the "wipe-data"
    assertTrue("Could not wipe data from AVD", avdManagerConnection.wipeUserData(avd))

    val files: MutableList<Path?> = ArrayList()
    Files.list(avdFolder).use { stream -> stream.forEach { e: Path? -> files.add(e) } }
    assertThat(files)
      .containsExactly(
        avdFolder.resolve("config.ini"),
        avdFolder.resolve("sdcard.img"),
        avdFolder.resolve("user-settings.ini"),
        avdFolder.resolve("userdata.img"),
      )
  }

  @Test
  fun testStopAvd() {
    val processHandle = checkNotNull(ProcessHandleProvider.getProcessHandle(412503))
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val lockFile = avdManager.resolveLockFile(avd, "hardware-qemu.ini.lock")
    Files.createDirectories(lockFile.parent)
    Files.write(lockFile, "412503".toByteArray())
    val tracker = RunningAvdTracker.getInstance()
    tracker.started(avd.dataFolderPath, processHandle, RunningAvd.RunType.STANDALONE)
    var wasShuttingDownWhenExited = false
    processHandle.onExit().thenRun { wasShuttingDownWhenExited = tracker.runningAvds[avd.dataFolderPath]?.isShuttingDown == true }

    avdManagerConnection.stopAvd(avd)

    assertThat(processHandle.isAlive).isFalse()
    assertThat(wasShuttingDownWhenExited).isTrue()
    assertThat(tracker.runningAvds).doesNotContainKey(avd.dataFolderPath)
  }

  // Note: This only tests a small part of startAvd(). We are not set up
  //       here to actually launch an Emulator instance.
  @Test
  fun testStartAvdSkinned() {
    // Create an AVD with a skin
    val skinnyAvdName = "skinnyAvd"
    val skinnyAvdFolder = AvdInfo.getDefaultAvdFolder(avdManager, skinnyAvdName, false)
    val skinFolder = prefsRoot.resolve("skinFolder")
    Files.createDirectories(skinFolder)

    val skinnyAvd =
      avdManager.createAvd(
        skinnyAvdFolder,
        skinnyAvdName,
        systemImage,
        OnDiskSkin(skinFolder),
        null,
        null,
        null,
        null,
        null,
        false,
        true,
        false,
      )

    try {
      checkNotNull(skinnyAvd)
      runBlocking { avdManagerConnection.startAvd(null, skinnyAvd) }
      fail()
    } catch (expected: RuntimeException) {
      assertThat(expected.message).startsWith("No emulator installed")
    }
  }

  // Note: This only tests a small part of startAvd(). We are not set up here to actually launch an
  // Emulator instance.
  @Test
  fun testStartAvdSkinless() {
    // Create an AVD without a skin
    val skinlessAvdName = "skinlessAvd"
    val skinlessAvdFolder = AvdInfo.getDefaultAvdFolder(avdManager, skinlessAvdName, false)

    val skinlessAvd =
      avdManager.createAvd(skinlessAvdFolder, skinlessAvdName, systemImage, null, null, null, null, null, null, false, true, false)

    try {
      runBlocking { avdManagerConnection.startAvd(null, skinlessAvd) }
      fail()
    } catch (expected: RuntimeException) {
      assertThat(expected.message).startsWith("No emulator installed")
    }
  }

  @Test
  fun testStudioParams() {
    val config = ProxyConfiguration.proxy(ProxyProtocol.HTTP, "proxy.com", 80, "")
    ProxyCredentialStore.getInstance().setCredentials("proxy.com", 80, Credentials("myuser", "hunter2"), false)

    val params = config.toStudioParams(ProxyCredentialStore.getInstance())
    assertThat(params)
      .containsExactly(
        "http.proxyHost=proxy.com",
        "http.proxyPort=80",
        "proxy.authentication.username=myuser",
        "proxy.authentication.password=hunter2",
      )
  }

  @Test
  fun testStartAvdWaitsForBoot() = runBlocking {
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val processHandler = FakeEmulatorProcessHandler()
    deviceProvisionerRule.deviceProvisionerPlugin.explicitBoot = true
    val connection = createTestConnectionWithProcessHandler(processHandler)
    val handle = addFakeDevice(avd, connection)

    val startDeferred = async { connection.startAvd(project, avd) }
    handle.stateFlow.first { it.isTransitioning }
    assertThat(startDeferred.isCompleted).isFalse()

    // Connect but not yet booted (isReady = false)
    handle.connect()
    yield()
    assertThat(startDeferred.isCompleted).isFalse()

    // Boot complete (isReady = true); should complete without waiting for the 8s progress indicator
    handle.finishBoot()
    withTimeout(4.seconds) { startDeferred.await() }
  }

  @Test
  fun testStartAvdCrash() = runBlocking {
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val processHandler = FakeEmulatorProcessHandler()
    val connection = createTestConnectionWithProcessHandler(processHandler)
    val handle = addFakeDevice(avd, connection)

    launch {
      handle.stateFlow.first { it.isTransitioning }
      processHandler.terminate(1)
    }

    try {
      withTimeout(4.seconds) { connection.startAvd(project, avd) }
      fail("Expected DeviceActionException")
    } catch (expected: DeviceActionException) {
      assertThat(expected.message).isEqualTo("The emulator process for AVD ${avd.displayName} has terminated.")
    }
  }

  @Test
  fun testStartAvdClosedByUser() = runBlocking {
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val processHandler = FakeEmulatorProcessHandler()
    val connection = createTestConnectionWithProcessHandler(processHandler)
    val handle = addFakeDevice(avd, connection)

    launch {
      handle.stateFlow.first { it.isTransitioning }
      processHandler.terminate(0)
    }

    try {
      withTimeout(4.seconds) { connection.startAvd(project, avd) }
      fail("Expected DeviceActionCanceledException")
    } catch (expected: DeviceActionCanceledException) {
      assertThat(expected.message).isEqualTo("The emulator process for AVD ${avd.displayName} was closed.")
    }
    assertThat(handle.state.isTransitioning).isFalse()
  }

  @Test
  fun testStartAvdDelegatesToActivatableDeviceHandle() = runBlocking {
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val processHandler = FakeEmulatorProcessHandler()
    deviceProvisionerRule.deviceProvisionerPlugin.explicitBoot = true
    val connection = createTestConnectionWithProcessHandler(processHandler)
    val handle = addFakeDevice(avd, connection)

    val startDeferred = async { connection.startAvd(project, avd, forceLaunchInToolWindow = true, bootMode = ColdBoot) }
    handle.stateFlow.first { it.isTransitioning }
    handle.connect()
    yield()
    assertThat(handle.activatedWithToolWindow).isTrue()
    assertThat(handle.activatedBootMode).isEqualTo(ColdBoot)
    assertThat(startDeferred.isCompleted).isFalse()

    handle.finishBoot()
    withTimeout(4.seconds) { startDeferred.await() }
  }

  @Test
  fun testStartAvdDisconnectedWhileBooting() = runBlocking {
    val avd = avdManager.createAvd(avdFolder, name, systemImage, null, null, null, null, null, null, false, false, false)
    val processHandler = FakeEmulatorProcessHandler()
    deviceProvisionerRule.deviceProvisionerPlugin.explicitBoot = true
    val connection = createTestConnectionWithProcessHandler(processHandler)
    val handle = addFakeDevice(avd, connection)

    launch {
      handle.stateFlow.first { it.isTransitioning }
      handle.connect()
      yield()
      handle.disconnect()
    }

    try {
      withTimeout(4.seconds) { connection.startAvd(project, avd, forceLaunchInToolWindow = true) }
      fail("Expected DeviceActionException")
    } catch (expected: DeviceActionException) {
      assertThat(expected.message).isEqualTo("Device did not come online")
    }
  }

  private suspend fun addFakeDevice(avd: AvdInfo, connection: AvdManagerConnection): FakeActivatableDeviceHandle {
    val properties =
      LocalEmulatorProperties.build(avd) {
        populateDeviceInfoProto("LocalEmulator", null, emptyMap(), "")
        icon = EmptyIcon.DEFAULT
      }
    val baseHandle =
      deviceProvisionerRule.deviceProvisionerPlugin.addNewDevice(properties = properties).also { handle ->
        deviceProvisionerRule.deviceProvisioner.devices.first { it.contains(handle) }
      }
    val activatableHandle = FakeActivatableDeviceHandle(avd, baseHandle, connection)
    val mockProvisioner = mock<DeviceProvisioner>()
    whenever(mockProvisioner.devices).thenReturn(MutableStateFlow(listOf(activatableHandle)))
    val mockService = mock<DeviceProvisionerService>()
    whenever(mockService.deviceProvisioner).thenReturn(mockProvisioner)
    project.replaceService(DeviceProvisionerService::class.java, mockService, disposableRule.disposable)
    return activatableHandle
  }

  private inner class FakeActivatableDeviceHandle(
    private val avd: AvdInfo,
    private val baseHandle: FakeAdbDeviceProvisionerPlugin.FakeDeviceHandle,
    private val connection: AvdManagerConnection,
  ) : ActivatableDeviceHandle {
    override val id = DeviceId("LocalEmulator", false, "path=${avd.dataFolderPath}")
    override val scope = baseHandle.scope
    override val stateFlow = baseHandle.stateFlow

    var activatedWithToolWindow = false
    var activatedBootMode: BootMode? = null

    override suspend fun activate(project: Project?, forceLaunchInToolWindow: Boolean, bootMode: BootMode?) {
      val self = this
      activatedWithToolWindow = forceLaunchInToolWindow
      activatedBootMode = bootMode
      baseHandle.stateFlow.update { (it as? DeviceState.Disconnected)?.copy(isTransitioning = true, status = "Starting up") ?: it }

      val activationResult = CompletableDeferred<Unit>()
      val startJob = scope.launch {
        try {
          connection.startAvd(project, self, avd, forceLaunchInToolWindow, bootMode ?: BootMode.fromProperties(avd.properties))
        } catch (e: Throwable) {
          baseHandle.stateFlow.update { (it as? DeviceState.Disconnected)?.copy(isTransitioning = false, status = "Offline") ?: it }
          activationResult.completeExceptionally(e)
        }
      }
      val connectJob = scope.launch {
        stateFlow.first { it is DeviceState.Connected }
        activationResult.complete(Unit)
      }
      try {
        activationResult.await()
      } finally {
        connectJob.cancel()
        if (stateFlow.value !is DeviceState.Connected) {
          startJob.cancel()
        }
      }
    }

    suspend fun connect() {
      baseHandle.activationAction.activate()
      stateFlow.first { it is DeviceState.Connected }
    }

    suspend fun disconnect() {
      baseHandle.deactivationAction.deactivate()
      stateFlow.first { it is DeviceState.Disconnected }
    }

    fun finishBoot() {
      baseHandle.finishBoot()
    }
  }

  private fun createTestConnectionWithProcessHandler(processHandler: FakeEmulatorProcessHandler): AvdManagerConnection =
    object : AvdManagerConnection(AndroidSdkHandler(sdkRoot, prefsRoot), avdManager, Dispatchers.Unconfined) {
      override suspend fun startAvdProcess(
        project: Project?,
        avd: AvdInfo,
        forceLaunchInToolWindow: Boolean,
        bootMode: BootMode,
      ): ProcessHandler {
        processHandler.startNotify()
        return processHandler
      }
    }

  private class FakeEmulatorProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() {
      notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
      notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    fun terminate(exitCode: Int) {
      notifyProcessTerminated(exitCode)
    }
  }

  private fun createFile(dir: Path, relativePath: String) {
    val file = dir.resolve(relativePath)
    PathSubject.assertThat(file.recordExistingFile("Contents of $relativePath")).exists()
  }

  companion object {
    private fun recordGoogleApisSysImg23(sdkRoot: Path) {
      sdkRoot.resolve("system-images/android-23/google_apis/x86_64/system.img").recordExistingFile()
      sdkRoot.resolve("system-images/android-23/google_apis/x86_64/" + AvdManager.USERDATA_IMG).recordExistingFile("Some dummy info")
      sdkRoot
        .resolve("system-images/android-23/google_apis/x86_64/package.xml")
        .recordExistingFile(
          ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<ns3:sdk-sys-img " +
            "xmlns:ns3=\"http://schemas.android.com/sdk/android/repo/sys-img2/01\">" +
            "<localPackage path=\"system-images;android-23;google_apis;x86_64\">" +
            "<type-details xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xsi:type=\"ns3:sysImgDetailsType\"><api-level>23</api-level>" +
            "<tag><id>google_apis</id><display>Google APIs</display></tag>" +
            "<vendor><id>google</id><display>Google Inc.</display></vendor>" +
            "<abi>x86_64</abi></type-details><revision><major>9</major></revision>" +
            "<display-name>Google APIs Intel x86 Atom_64 System Image</display-name>" +
            "</localPackage></ns3:sdk-sys-img>\n")
        )
    }

    private fun recordEmulatorVersion_23_4_5(sdkRoot: Path) {
      // This creates two 'package' directories.
      // We do not create a valid Emulator executable, so tests expect
      // a failure when they try to launch the Emulator.
      sdkRoot
        .resolve("emulator/package.xml")
        .recordExistingFile(
          ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<ns2:repository xmlns:ns2=\"http://schemas.android.com/repository/android/common/01\"" +
            "                xmlns:ns3=\"http://schemas.android.com/repository/android/generic/01\">" +
            "  <localPackage path=\"emulator\">" +
            "    <type-details xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
            "        xsi:type=\"ns3:genericDetailsType\"/>" +
            "    <revision><major>23</major><minor>4</minor><micro>5</micro></revision>" +
            "    <display-name>Google APIs Intel x86 Atom_64 System Image</display-name>" +
            "  </localPackage>" +
            "</ns2:repository>")
        )
      sdkRoot
        .resolve("tools/package.xml")
        .recordExistingFile(
          ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<ns2:repository xmlns:ns2=\"http://schemas.android.com/repository/android/common/01\"" +
            "                xmlns:ns3=\"http://schemas.android.com/repository/android/generic/01\">" +
            "  <localPackage path=\"tools\">" +
            "    <type-details xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
            "        xsi:type=\"ns3:genericDetailsType\"/>" +
            "    <revision><major>23</major><minor>4</minor><micro>5</micro></revision>" +
            "    <display-name>Google APIs Intel x86 Atom_64 System Image</display-name>" +
            "  </localPackage>" +
            "</ns2:repository>")
        )
    }
  }
}
