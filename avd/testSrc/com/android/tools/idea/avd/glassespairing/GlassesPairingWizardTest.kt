/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.avd.glassespairing

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState as AdbDeviceState
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.tools.aiglasses.AiGlassesPairing
import com.android.adblib.waitForDevice
import com.android.sdklib.AndroidTargetHash
import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.AbstractAvdScanner
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.deviceprovisioner.EmptyIcon
import com.android.sdklib.deviceprovisioner.LocalEmulatorProperties
import com.android.sdklib.deviceprovisioner.testing.FakeDeviceProvisionerPlugin
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.ConfigKey
import com.android.tools.adtui.compose.TestComposeWizard
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.analytics.UsageTracker
import com.android.tools.analytics.UsageTrackerWriter
import com.android.tools.idea.avd.glassespairing.LaunchState.Booting
import com.android.tools.idea.avd.glassespairing.LaunchState.Launching
import com.android.tools.idea.avd.glassespairing.LaunchState.Ready
import com.android.tools.idea.deviceprovisioner.GlassesInteractivePairableDeviceHandle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.protobuf.Message.Builder
import com.google.wireless.android.play.playlog.proto.ClientAnalytics
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.GlassesPairingEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.testFramework.runInEdtAndWait
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

class GlassesPairingWizardTest {
  @get:Rule val temporaryDirectoryRule = TemporaryDirectory()
  @get:Rule val composeTestRule = createStudioComposeTestRule()
  @get:Rule val applicationRule = ApplicationRule()

  /** [GlassesPairingWizard.showCore] is @UiThread and production calls it on the EDT, so run the test body there. */
  private fun runTestOnEdt(testBody: suspend TestScope.() -> Unit) = runInEdtAndWait { runTest(testBody = testBody) }

  @Before
  fun setUp() {
    AiGlassesPairing.setDelaysForTest(
      foregroundDelay = Duration.ZERO,
      pairingCommandDelay = Duration.ZERO,
      pollingInterval = Duration.ZERO,
      pollingTimeout = 5.seconds,
    )
  }

  @After
  fun tearDown() {
    AiGlassesPairing.setDelaysForTest()
    GlassesPairingWizard.resetForTesting()
  }

  @Test
  fun testGlassesPairingWizard() {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val mediumPhone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p0",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Medium Phone"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = true,
            isReady = false,
            status = "Online",
          ),
        )
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 9"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = true,
            isReady = false,
            status = "Online",
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = true,
            isReady = false,
            status = "Online",
          ),
        )
      val devicesFlow = MutableStateFlow(listOf(mediumPhone, phone, glasses))

      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            assertThat(g).isSameAs(glasses)
            assertThat(p).isSameAs(phone)
            return pairingFlow
          }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      composeTestRule.onNodeWithText("Select a device", substring = true).assertIsDisplayed()
      assertThat(glassesWizard.phoneHandle).isEqualTo(mediumPhone)
      composeTestRule.onNodeWithText("Next").assertIsEnabled()
      assertThat(tracker.events).doesNotContain(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()

      // Verify selection event
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)
      assertThat(glassesWizard.phoneHandle).isEqualTo(phone)
      assertThat(glassesWizard.glassesHandle).isEqualTo(glasses)

      composeTestRule.onNodeWithText("Next").performClick()

      // Verify initiated event
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_INITIATED)

      pairingFlow.value = PairingState.Launching("Pixel 9", Booting, "Audio glasses", Booting)

      composeTestRule.onNodeWithText("Starting Pixel 9 and Audio glasses...").assertIsDisplayed()
      composeTestRule.onNodeWithText("Booting Audio glasses system image...").assertIsDisplayed()

      pairingFlow.value = PairingState.Pairing("Initiating pairing with Pixel 9 and Audio glasses...")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Establishing pairing...").assertIsDisplayed()
      composeTestRule.onNodeWithText("Initiating pairing with Pixel 9 and Audio glasses...").assertIsDisplayed()

      pairingFlow.value = PairingState.AwaitingAuthorization("Pixel 9")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Accept Companion app Permissions on Pixel 9").assertIsDisplayed()
      composeTestRule.onNodeWithText("Grant Companion app permissions on Google Pixel 9 to continue...").assertIsDisplayed()

      pairingFlow.value = PairingState.GlassesCoreConnecting("Pixel 9")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Accept XR Services Permissions on Pixel 9").assertIsDisplayed()
      composeTestRule.onNodeWithText("Allow XR Services permissions on Google Pixel 9 to complete pairing").assertIsDisplayed()

      pairingFlow.value = PairingState.GlassesCoreConnected("Pixel 9")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Finishing pairing with Pixel 9...").assertIsDisplayed()

      pairingFlow.value = PairingState.Complete("Pixel 9", "Audio glasses")

      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Successfully paired Pixel 9 with Audio glasses").assertIsDisplayed()

      // Verify success event
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.SHOW_SUCCESSFUL_PAIRING)

      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()

      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testLaunchAvds(): Unit = runTest {
    val glasses = TestDeviceHandle(this@runTest, "G", activationDelay = 8.seconds, bootDelay = 5.seconds)
    val phone = TestDeviceHandle(this@runTest, "P", activationDelay = 8.seconds, bootDelay = 5.seconds)

    val states = launchGlassesAndPhone(glasses, phone).toList()

    assertThat(states)
      .containsExactly(
        createLaunchingState(phone = Launching, glasses = Launching),
        createLaunchingState(phone = Booting, glasses = Booting),
        createLaunchingState(phone = Ready, glasses = Ready),
      )
      .inOrder()
  }

  @OptIn(ExperimentalTime::class)
  @Test
  fun testLaunchAvds_glassesAlreadyRunning(): Unit = runTest {
    val glasses = TestDeviceHandle(this@runTest, "G", activationDelay = 0.seconds)
    val phone = TestDeviceHandle(this@runTest, "P", activationDelay = 4.seconds, bootDelay = 4.seconds)

    glasses.activationAction!!.activate()

    val duration = testTimeSource.measureTime {
      val states = launchGlassesAndPhone(glasses, phone).toList()

      assertThat(states)
        .containsExactly(
          createLaunchingState(phone = Launching, glasses = Ready),
          createLaunchingState(phone = Booting, glasses = Ready),
          createLaunchingState(phone = Ready, glasses = Ready),
        )
        .inOrder()
    }

    assertThat(duration).isLessThan(9.seconds)
  }

  @OptIn(ExperimentalTime::class)
  @Test
  fun testPairingTimeout() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Audio glasses"
              deviceType = DeviceType.AI_GLASSES
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val devicesFlow = MutableStateFlow(listOf(phone, glasses))

      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> =
            flow {
              delay(Long.MAX_VALUE)
            }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      // Disable auto advance so we can control it explicitly
      composeTestRule.mainClock.autoAdvance = false

      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()

      // Give it time to start the flow
      composeTestRule.mainClock.advanceTimeBy(1000)
      testScheduler.advanceTimeBy(1000)

      // Now advance by 15 minutes to trigger the timeout
      testScheduler.advanceTimeBy(15.minutes.inWholeMilliseconds + 1000)
      composeTestRule.mainClock.advanceTimeBy(15.minutes.inWholeMilliseconds + 1000)
      composeTestRule.mainClock.advanceTimeByFrame()

      composeTestRule.onNodeWithText("Pairing timed out").assertIsDisplayed()
      composeTestRule.onNodeWithText("The pairing process timed out.").assertIsDisplayed()

      composeTestRule.onNodeWithText("Cancel").performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testPairingErrors() {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Audio glasses"
              deviceType = DeviceType.AI_GLASSES
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val devicesFlow = MutableStateFlow(listOf(phone, glasses))

      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            return pairingFlow
          }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      // Select device and start
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()

      // 1. Test UI_CDM_ASSOCIATION_FAILED
      pairingFlow.value =
        PairingState.Error(
          heading = "Error pairing Audio glasses",
          detailText = "Failed to create companion device association between Pixel 9 and Audio glasses.",
        )
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Error pairing Audio glasses").assertIsDisplayed()
      composeTestRule.onNodeWithText("Failed to create companion device association between Pixel 9 and Audio glasses.").assertIsDisplayed()
      tracker.events.clear()

      // 2. Test WORKER_GLASSES_CORE_CONNECTION_FAILED
      pairingFlow.value =
        PairingState.Error(
          heading = "Error pairing Audio glasses",
          detailText = "Failed to connect Audio glasses to XR Services on Pixel 9. Please make sure to accept all permissions on Pixel 9.",
        )
      composeTestRule.waitForIdle()
      composeTestRule
        .onNodeWithText("Failed to connect Audio glasses to XR Services on Pixel 9. Please make sure to accept all permissions on Pixel 9.")
        .assertIsDisplayed()
      tracker.events.clear()

      // 3. Test WORKER_BOND_FAILED
      pairingFlow.value =
        PairingState.Error(
          heading = "Error pairing Audio glasses",
          detailText = "Failed to create a Bluetooth bond between Pixel 9 and Audio glasses.",
        )
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Failed to create a Bluetooth bond between Pixel 9 and Audio glasses.").assertIsDisplayed()

      composeTestRule.onNodeWithText("Cancel").performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testShowEnforcesSingleInstance() = runTestOnEdt {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val devicesFlow = MutableStateFlow(emptyList<DeviceHandle>())
    val glasses =
      FakeDeviceProvisionerPlugin.FakeDeviceHandle(
        "g1",
        coroutineScope,
        DeviceState.Disconnected(
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Audio glasses"
            deviceType = DeviceType.AI_GLASSES
            androidVersion = AndroidVersion(36, 1)
          }
        ),
      )

    val controller1 = TestWizardController()
    val controller2 = TestWizardController()
    val controllers = mutableListOf(controller1, controller2)

    val job1 = launch { GlassesPairingWizard.showCore(null, null, devicesFlow, glasses) { _, _, _, _, _, _ -> controllers.removeAt(0) } }

    // Wait for first wizard to be active
    yieldUntil { controller1.showCalled }
    assertThat(controller1.showCalled).isTrue()

    // Try to show again for same glasses
    val result2 = GlassesPairingWizard.showCore(null, null, devicesFlow, glasses) { _, _, _, _, _, _ -> controllers.removeAt(0) }

    assertThat(result2).isNull()
    assertThat(controller2.showCalled).isFalse()

    // Close first wizard
    controller1.close(false)
    job1.join()
  }

  @Test
  fun testShowRejectsMultipleDevices() = runTestOnEdt {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val devicesFlow = MutableStateFlow(emptyList<DeviceHandle>())
    val glasses1 =
      FakeDeviceProvisionerPlugin.FakeDeviceHandle(
        "g1",
        coroutineScope,
        DeviceState.Disconnected(
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Audio glasses 1"
            deviceType = DeviceType.AI_GLASSES
            androidVersion = AndroidVersion(36, 1)
          }
        ),
      )
    val glasses2 =
      FakeDeviceProvisionerPlugin.FakeDeviceHandle(
        "g2",
        coroutineScope,
        DeviceState.Disconnected(
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Audio glasses 2"
            deviceType = DeviceType.AI_GLASSES
            androidVersion = AndroidVersion(36, 1)
          }
        ),
      )

    val controller1 = TestWizardController()
    val controller2 = TestWizardController()
    val controllers = mutableListOf(controller1, controller2)

    val job1 = launch { GlassesPairingWizard.showCore(null, null, devicesFlow, glasses1) { _, _, _, _, _, _ -> controllers.removeAt(0) } }

    yieldUntil { controller1.showCalled }
    assertThat(controller1.showCalled).isTrue()

    // Second launch should return null immediately
    val result2 = GlassesPairingWizard.showCore(null, null, devicesFlow, glasses2) { _, _, _, _, _, _ -> controllers.removeAt(0) }

    assertThat(result2).isNull()
    assertThat(controller2.showCalled).isFalse()

    controller1.close(false)
    job1.join()
  }

  @Test
  fun testIsAnyWizardOpen() = runTestOnEdt {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val devicesFlow = MutableStateFlow(emptyList<DeviceHandle>())
    val glasses =
      FakeDeviceProvisionerPlugin.FakeDeviceHandle(
        "g1",
        coroutineScope,
        DeviceState.Disconnected(
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Audio glasses"
            deviceType = DeviceType.AI_GLASSES
            androidVersion = AndroidVersion(36, 1)
          }
        ),
      )

    val controller = TestWizardController()
    val controllers = mutableListOf(controller)
    val lockService = ApplicationManager.getApplication().getService(GlassesPairingLockService::class.java)

    assertThat(lockService.isWizardOpen.value).isFalse()

    val job = launch { GlassesPairingWizard.showCore(null, null, devicesFlow, glasses) { _, _, _, _, _, _ -> controllers.removeAt(0) } }

    yieldUntil { lockService.isWizardOpen.value }
    assertThat(lockService.isWizardOpen.value).isTrue()

    controller.close(false)
    job.join()

    assertThat(lockService.isWizardOpen.value).isFalse()
  }

  @Test
  fun testCreateDeviceButton() = runTest {
    val wizardScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    val avdRoot = temporaryDirectoryRule.newPath()
    try {
      val phoneAvd =
        AvdInfo(
          iniFile = avdRoot.resolve("Pixel_9.ini"),
          dataFolderPath = avdRoot.resolve("Pixel_9.avd"),
          systemImage = null,
          properties =
            mapOf(ConfigKey.DISPLAY_NAME to "Pixel 9", ConfigKey.TARGET to AndroidTargetHash.getPlatformHashString(AndroidVersion(36))),
        )

      val phoneProps =
        LocalEmulatorProperties.build(phoneAvd) {
          icon = EmptyIcon.DEFAULT
          isAiGlassesCompatible = true
        }

      val phone = FakeDeviceProvisionerPlugin.FakeDeviceHandle("p1", this, DeviceState.Disconnected(phoneProps))
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          wizardScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Audio glasses"
              deviceType = DeviceType.AI_GLASSES
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val devicesFlow = MutableStateFlow(listOf(phone, glasses))

      val newAvd =
        AvdInfo(
          iniFile = avdRoot.resolve("New_Phone.ini"),
          dataFolderPath = avdRoot.resolve("New_Phone.avd"),
          systemImage = null,
          properties =
            mapOf(ConfigKey.DISPLAY_NAME to "New Phone", ConfigKey.TARGET to AndroidTargetHash.getPlatformHashString(AndroidVersion(36))),
        )

      val newPhoneProps =
        LocalEmulatorProperties.build(newAvd) {
          icon = EmptyIcon.DEFAULT
          isAiGlassesCompatible = true
        }

      val newPhone = FakeDeviceProvisionerPlugin.FakeDeviceHandle("New_Phone", this, DeviceState.Disconnected(newPhoneProps))

      val addDeviceDialog = AddDeviceDialog { _, _, _, _ ->
        // Update devicesFlow when dialog "finishes"
        devicesFlow.value = listOf(phone, glasses, newPhone)
        newAvd
      }

      val glassesWizard =
        GlassesPairingWizard(
          null,
          wizardScope,
          devicesFlow,
          glasses,
          addDeviceDialog = addDeviceDialog,
          avdScanner = { mock<AbstractAvdScanner>() },
        )
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      composeTestRule.onNodeWithText("Create a new compatible device").performClick()

      // The onClick handler is running in 'coroutineScope' (which is 'this').
      // Since it's StandardTestDispatcher, it needs help to progress.
      composeTestRule.waitUntil(10000) {
        testScheduler.runCurrent()
        try {
          composeTestRule.onNodeWithText("New Phone").assertIsDisplayed()
          composeTestRule.onNodeWithText("Next").assertIsEnabled()
          assertThat(glassesWizard.phoneHandle).isEqualTo(newPhone)
          true
        } catch (e: AssertionError) {
          false
        }
      }
      assertThat(tracker.events).doesNotContain(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)
    } finally {
      wizardScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testGlassesPairingWizard_withPreselectedPhone() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 9"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val devicesFlow = MutableStateFlow(listOf(phone, glasses))

      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      var pairCalled = false
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            assertThat(g).isSameAs(glasses)
            assertThat(p).isSameAs(phone)
            pairCalled = true
            return pairingFlow
          }
        }

      val glassesWizard =
        GlassesPairingWizard(
          project = null,
          coroutineScope = coroutineScope,
          devicesFlow = devicesFlow,
          initialGlassesHandle = glasses,
          initialPhoneHandle = phone,
          pairer = pairer,
          isCompatible = { true },
        )
      val wizard = TestComposeWizard { with(glassesWizard) { PreselectedPairingPage(glassesHandle = glasses, phoneHandle = phone) } }

      composeTestRule.setContent { wizard.Content() }

      composeTestRule.onNodeWithText("Select a device", substring = true).assertDoesNotExist()
      composeTestRule.onNodeWithText("Preparing", substring = true).assertIsDisplayed()

      yieldUntil { pairCalled }
      assertThat(pairCalled).isTrue()

      pairingFlow.value = PairingState.Pairing("Initiating pairing...")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Establishing pairing...", substring = true).assertIsDisplayed()

      pairingFlow.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Successfully paired Pixel 9 with Audio glasses").assertIsDisplayed()

      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testSelectGlassesPage_greysOutAlreadyPairedGlasses() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 9"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )

      val pairedGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Connected(
              properties =
                DeviceProperties.buildForTest {
                  icon = EmptyIcon.DEFAULT
                  manufacturer = "Google"
                  model = "Paired Glasses"
                  deviceType = DeviceType.AI_GLASSES
                  androidVersion = AndroidVersion(36, 1)
                  pairedPhoneId = phone.id // Paired to the initiating phone
                },
              connectedDevice = mock<ConnectedDevice>(),
              isTransitioning = false,
              isReady = true,
              status = "Online",
            ),
          )
        )

      val otherPairedGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g2",
            coroutineScope,
            DeviceState.Connected(
              properties =
                DeviceProperties.buildForTest {
                  icon = EmptyIcon.DEFAULT
                  manufacturer = "Google"
                  model = "Other Paired Glasses"
                  deviceType = DeviceType.AI_GLASSES
                  androidVersion = AndroidVersion(36, 1)
                  pairedPhoneId = DeviceId("Test", false, "other_phone") // Paired to a different phone
                },
              connectedDevice = mock<ConnectedDevice>(),
              isTransitioning = false,
              isReady = true,
              status = "Online",
            ),
          )
        )

      val unpairedGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g3",
            coroutineScope,
            DeviceState.Connected(
              properties =
                DeviceProperties.buildForTest {
                  icon = EmptyIcon.DEFAULT
                  manufacturer = "Google"
                  model = "Unpaired Glasses"
                  deviceType = DeviceType.AI_GLASSES
                  androidVersion = AndroidVersion(36, 1)
                  pairedPhoneId = null // Unpaired
                },
              connectedDevice = mock<ConnectedDevice>(),
              isTransitioning = false,
              isReady = true,
              status = "Online",
            ),
          )
        )

      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, pairedGlasses))

      val glassesWizard =
        GlassesPairingWizard(
          project = null,
          coroutineScope = coroutineScope,
          devicesFlow = devicesFlow,
          initialGlassesHandle = null, // Phone-First flow
          initialPhoneHandle = phone, // Initiating phone is 'phone'
          pairer = mock<GlassesPairer>(),
          isCompatible = { true },
        )
      val wizard = TestComposeWizard { with(glassesWizard) { SelectGlassesPage() } }

      composeTestRule.setContent { wizard.Content() }
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithText("Already paired to this phone").assertIsDisplayed()
      composeTestRule.onNodeWithText("Google Paired Glasses").performClick()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
      assertThat(glassesWizard.glassesHandle).isNull()

      val onlineUnpairedState = unpairedGlasses.state
      (unpairedGlasses.delegate as FakeDeviceProvisionerPlugin.FakeDeviceHandle).stateFlow.value =
        DeviceState.Disconnected(onlineUnpairedState.properties)
      devicesFlow.value = listOf(phone, pairedGlasses, otherPairedGlasses, unpairedGlasses)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.glassesHandle).isEqualTo(otherPairedGlasses)

      devicesFlow.value = listOf(phone, pairedGlasses)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.glassesHandle).isNull()

      (unpairedGlasses.delegate as FakeDeviceProvisionerPlugin.FakeDeviceHandle).stateFlow.value = onlineUnpairedState
      devicesFlow.value = listOf(phone, pairedGlasses, otherPairedGlasses, unpairedGlasses)
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Already paired").assertIsDisplayed()
      composeTestRule.onNodeWithText("Next").assertIsEnabled()
      assertThat(glassesWizard.glassesHandle).isEqualTo(unpairedGlasses)
      assertThat(tracker.events).doesNotContain(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)

      composeTestRule.onNodeWithText("Google Paired Glasses").performClick()
      assertThat(glassesWizard.glassesHandle).isEqualTo(unpairedGlasses)

      composeTestRule.onNodeWithText("Google Other Paired Glasses").performClick()
      composeTestRule.onNodeWithText("Next").assertIsEnabled()
      assertThat(glassesWizard.glassesHandle).isEqualTo(otherPairedGlasses)
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)

      composeTestRule.onNodeWithText("Google Unpaired Glasses").performClick()
      composeTestRule.onNodeWithText("Next").assertIsEnabled()
      assertThat(glassesWizard.phoneHandle).isEqualTo(phone)
      assertThat(glassesWizard.glassesHandle).isEqualTo(unpairedGlasses)

      wizard.close()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testSelectDevicePage_pairedGlasses_unpairsAfterPhoneIsChosen() {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val events = CopyOnWriteArrayList<String>()
      val unpairGate = CompletableDeferred<Unit>()
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "old_phone")
              }
            ),
          )
        ) {
          events.add("unpair started")
          unpairGate.await()
          events.add("unpaired")
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            events.add("pair")
            return pairingFlow
          }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      // Nothing is wiped until a phone is chosen, so cancelling here leaves the glasses as they were.
      composeTestRule.onNodeWithText("Select a device to pair").assertIsDisplayed()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      assertThat(events).isEmpty()

      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      // Pairing states reach the page asynchronously, so wait for the UI, not for the fake's events.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Unpairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()

      unpairGate.complete(Unit)
      composeTestRule.waitUntilDoesNotExist(hasText("Unpairing..."), 10_000)
      assertThat(events).containsExactly("unpair started", "unpaired", "pair").inOrder()
      assertThat(tracker.events.count { it == GlassesPairingEvent.EventKind.PAIRING_INITIATED }).isEqualTo(1)
      composeTestRule.onNodeWithText("Preparing").assertIsDisplayed()
      composeTestRule.onNodeWithText("Previous").assertIsEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()

      pairingFlow.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testSelectGlassesPage_glassesPairedToAnotherPhone_unpairsAfterConfirmation() {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)
    val events = CopyOnWriteArrayList<String>()
    val dialogAnswers = ArrayDeque(listOf(Messages.NO, Messages.YES))
    TestDialogManager.setTestDialog(
      TestDialog {
        events.add("asked")
        dialogAnswers.removeFirst()
      }
    )

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val unpairGate = CompletableDeferred<Unit>()
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "other_phone")
              }
            ),
          )
        ) {
          events.add("unpair started")
          unpairGate.await()
          events.add("unpaired")
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            assertThat(g).isSameAs(glasses)
            assertThat(p).isSameAs(phone)
            events.add("pair")
            return pairingFlow
          }
        }

      val glassesWizard =
        GlassesPairingWizard(
          project = null,
          coroutineScope = coroutineScope,
          devicesFlow = devicesFlow,
          initialGlassesHandle = null,
          pairer = pairer,
          isCompatible = { true },
          initialPhoneHandle = phone,
        )
      val wizard = TestComposeWizard { with(glassesWizard) { SelectGlassesPage() } }

      composeTestRule.setContent { wizard.Content() }

      // Declining the warning leaves the glasses alone and keeps the user on the selection page.
      composeTestRule.onNodeWithText("Google Audio glasses").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntil(10_000) { "asked" in events }
      composeTestRule.waitForIdle()
      assertThat(events).containsExactly("asked")
      composeTestRule.onNodeWithText("Select a Glasses device to pair").assertIsDisplayed()

      // Accepting it replaces the list with the pair page, which unpairs first with Previous and Next disabled; Cancel stays available.
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntilAtLeastOneExists(hasText("Unpairing..."), 10_000)
      composeTestRule.onNodeWithText("Select a Glasses device to pair").assertDoesNotExist()
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
      assertThat(events).doesNotContain("pair")

      unpairGate.complete(Unit)
      composeTestRule.waitUntilDoesNotExist(hasText("Unpairing..."), 10_000)
      assertThat(events).containsExactly("asked", "asked", "unpair started", "unpaired", "pair").inOrder()
      assertThat(tracker.events.count { it == GlassesPairingEvent.EventKind.PAIRING_INITIATED }).isEqualTo(1)
      composeTestRule.onNodeWithText("Preparing").assertIsDisplayed()
      composeTestRule.onNodeWithText("Previous").assertIsEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()

      pairingFlow.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()
      wizard.awaitClose()
    } finally {
      TestDialogManager.setTestDialog(null)
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testShowCore_preselectedPairedGlasses_unpairsAfterConfirmation() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)
    val events = CopyOnWriteArrayList<String>()
    val dialogAnswers = ArrayDeque(listOf(Messages.NO, Messages.YES))
    TestDialogManager.setTestDialog(
      TestDialog {
        events.add("asked")
        dialogAnswers.removeFirst()
      }
    )

    try {
      val phone = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val unpairGate = CompletableDeferred<Unit>()
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "old_phone")
              }
            ),
          )
        ) {
          events.add("unpair started")
          unpairGate.await()
          events.add("unpaired")
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            assertThat(g).isSameAs(glasses)
            assertThat(p).isSameAs(phone)
            events.add("pair")
            onMacRetrieved("00:11:22:33:44:55")
            return pairingFlow
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      // With both devices given, showCore asks before re-pairing the glasses, then opens straight on the pair page.
      fun showCoreAsync() =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = null,
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            phoneHandle = phone,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }

      // Declining the warning returns before the wizard opens, so the glasses are neither unpaired nor paired.
      assertThat(showCoreAsync().await()).isNull()
      assertThat(testWizard.isCompleted).isFalse()
      assertThat(events).containsExactly("asked")

      // Accepting it unpairs the glasses before pairing, with Previous and Next disabled and Cancel still available.
      val resultDeferred = showCoreAsync()
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }
      composeTestRule.waitUntil(10_000) { "unpair started" in events }
      composeTestRule.waitUntilAtLeastOneExists(hasText("Unpairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
      assertThat(events).doesNotContain("pair")

      // A phone state change gives the page a new phone row and restarts LaunchedEffect(phoneRow). That must not start a second
      // attempt, which would cancel this unpair and wipe the glasses again. Nothing on screen shows the new row, so wait up to
      // 1 second for another unpair to start while this one is still held.
      phone.stateFlow.value = (phone.state as DeviceState.Connected).copy(status = "Online, updated")
      assertFailsWith<ComposeTimeoutException>("A second unpair started") {
        composeTestRule.waitUntil(1_000) { events.count { it == "unpair started" } > 1 }
      }
      assertThat(events.count { it == "unpair started" }).isEqualTo(1)

      unpairGate.complete(Unit)
      composeTestRule.waitUntilDoesNotExist(hasText("Unpairing..."), 10_000)
      assertThat(events).containsExactly("asked", "asked", "unpair started", "unpaired", "pair").inOrder()
      composeTestRule.onNodeWithText("Preparing").assertIsDisplayed()
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()

      pairingFlow.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitUntilAtLeastOneExists(hasText("Successfully paired Pixel 9 with Audio glasses"), 10_000)
      controller.close(true)
      val result = resultDeferred.await()
      assertThat(result).isEqualTo(GlassesPairingResult(glasses = glasses, phone = phone, glassesMacAddress = "00:11:22:33:44:55"))
      // Only the first attempt was started, even after the phone state changed.
      assertThat(tracker.events.count { it == GlassesPairingEvent.EventKind.PAIRING_INITIATED }).isEqualTo(1)
    } finally {
      TestDialogManager.setTestDialog(null)
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testPairPage_unpairTimesOut_showsErrorAndEnablesPrevious() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val events = CopyOnWriteArrayList<String>()
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "old_phone")
              }
            ),
          )
        ) {
          events.add("unpair started")
          // Never returns, like an adb command sent to an emulator that stopped responding.
          CompletableDeferred<Unit>().await()
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            events.add("pair")
            return MutableStateFlow<PairingState>(PairingState.NotStarted)
          }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      // The fake runs inside the timeout, so once it has started the timeout is scheduled and virtual time can move.
      composeTestRule.waitUntil(10_000) { "unpair started" in events }
      composeTestRule.waitUntilAtLeastOneExists(hasText("Unpairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()

      // Previous is disabled while unpairing, so a stuck unpair must end on its own, but not before 2 minutes.
      testScheduler.advanceTimeBy(2.minutes.inWholeMilliseconds - 1)
      testScheduler.runCurrent()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Unpairing...").assertIsDisplayed()
      composeTestRule.onNodeWithText("Failed to unpair Google Audio glasses").assertDoesNotExist()
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()

      testScheduler.advanceTimeBy(2)
      composeTestRule.waitUntilAtLeastOneExists(hasText("Failed to unpair Google Audio glasses"), 10_000)
      composeTestRule.onNodeWithText("Google Audio glasses may already be wiped. See idea.log for details.").assertIsDisplayed()
      assertThat(events).containsExactly("unpair started")
      composeTestRule.onNodeWithText("Previous").assertIsEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
  @Test
  fun testShowCore_cancelWhileUnpairing_stopsUnpairAndReturnsNull() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)
    val events = CopyOnWriteArrayList<String>()
    // showCore asks before re-pairing glasses that are still paired to a phone.
    TestDialogManager.setTestDialog(TestDialog { Messages.YES })

    try {
      val phone = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "old_phone")
              }
            ),
          )
        ) {
          events.add("unpair started")
          try {
            // Suspends like an adb command or the wait for the glasses to stop, until the wizard's scope is cancelled.
            CompletableDeferred<Unit>().await()
          } finally {
            events.add("unpair stopped")
          }
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            events.add("pair")
            return MutableStateFlow<PairingState>(PairingState.NotStarted)
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = null,
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntil(10_000) { "unpair started" in events }
      composeTestRule.waitUntilAtLeastOneExists(hasText("Unpairing..."), 10_000)

      // Cancel stays available during the unpair, so the wizard can always be closed. The dialog then returns false to showCore, as
      // ComposeWizard does after Cancel.
      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
      wizard.awaitClose()
      controller.close(false)
      assertThat(resultDeferred.await()).isNull()

      // Closing cancels the wizard's scope, which stops the unpair where it is suspended without logging failure telemetry.
      composeTestRule.waitUntil(10_000) { "unpair stopped" in events }
      composeTestRule.waitUntil(10_000) { !service<GlassesPairingLockService>().isWizardOpen.value }
      assertThat(events).containsExactly("unpair started", "unpair stopped").inOrder()
      assertThat(service<GlassesPairingLockService>().isWizardOpen.value).isFalse()
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_ASSISTANT_LAUNCHED)
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_INITIATED)
      assertThat(tracker.events)
        .containsNoneOf(
          GlassesPairingEvent.EventKind.SHOW_FAILED_PAIRING,
          GlassesPairingEvent.EventKind.UNSPECIFIED,
        )
    } finally {
      TestDialogManager.setTestDialog(null)
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testPairPage_previousAfterFailedUnpair_doesNotUnpairAgain() {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val events = CopyOnWriteArrayList<String>()
      // Like the provisioner after a failed stop or wipe: it shows a dialog, returns normally and keeps pairedPhoneId set.
      val glasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g1",
            coroutineScope,
            DeviceState.Disconnected(
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
                pairedPhoneId = DeviceId("Test", false, "old_phone")
              }
            ),
          )
        ) {
          events.add("unpair")
        }
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val pairingFlow = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val pairer =
        object : GlassesPairer {
          override fun pair(g: DeviceHandle, p: DeviceHandle, project: Project?, onMacRetrieved: (String) -> Unit): Flow<PairingState> {
            events.add("pair")
            return pairingFlow
          }
        }

      val glassesWizard = GlassesPairingWizard(null, coroutineScope, devicesFlow, glasses, pairer, { true })
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }

      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntil(10_000) { "pair" in events }

      // The bond survived the failed wipe, so pairing stops at the "already paired" check, where Previous is enabled.
      pairingFlow.value =
        PairingState.Error(
          heading = "Audio glasses is already paired",
          detailText = "Wipe data on Audio glasses to pair a new phone device.",
        )
      composeTestRule.waitUntilAtLeastOneExists(hasText("Audio glasses is already paired"), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()

      // Going back shows the phone list again without unpairing or wiping the glasses a second time.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a device to pair"), 10_000)
      assertThat(events).containsExactly("unpair", "pair").inOrder()
      assertThat(tracker.events.count { it == GlassesPairingEvent.EventKind.PAIRING_INITIATED }).isEqualTo(1)
      composeTestRule.onNodeWithText("Unpairing...").assertDoesNotExist()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testPairPage_attemptCompletesAfterPrevious_nextAttemptTurnsFinishOff() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phoneA =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val phoneB =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p2",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 8"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Audio glasses"
              deviceType = DeviceType.AI_GLASSES
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phoneA, phoneB, glasses))
      val firstAttempt = MutableStateFlow<PairingState>(PairingState.NotStarted)
      // The second attempt reports nothing until the test opens these gates, like an attempt that has only just started.
      val secondAttemptPairing = CompletableDeferred<Unit>()
      val secondAttemptComplete = CompletableDeferred<Unit>()
      val attempts = CopyOnWriteArrayList<DeviceHandle>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved("00:11:22:33:44:55")
            attempts.add(phone)
            if (attempts.size == 1) return firstAttempt
            return flow {
              secondAttemptPairing.await()
              emit(PairingState.Pairing("Pairing in progress..."))
              secondAttemptComplete.await()
              emit(PairingState.Complete("Pixel 8", "Audio glasses"))
            }
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = null,
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      firstAttempt.value = PairingState.Pairing("Pairing in progress...")
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()

      // Previous does not stop the first attempt, which completes while the phone list shows.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a device to pair"), 10_000)
      firstAttempt.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitUntil(10_000) { GlassesPairingEvent.EventKind.SHOW_SUCCESSFUL_PAIRING in tracker.events }
      composeTestRule.onNodeWithText("Google Pixel 8").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntil(10_000) { attempts.size == 2 }

      // Until the second attempt reports a state, its page shows the first attempt's Complete, which turns Next into Finish.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Successfully paired Pixel 9 with Audio glasses"), 10_000)
      secondAttemptPairing.complete(Unit)
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      composeTestRule.onNodeWithText("Finish").assertDoesNotExist()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Previous").assertIsEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsEnabled()

      secondAttemptComplete.complete(Unit)
      composeTestRule.waitUntilAtLeastOneExists(hasText("Successfully paired Pixel 8 with Audio glasses"), 10_000)
      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()
      wizard.awaitClose()
      controller.close(true)
      val result = resultDeferred.await()
      assertThat(result).isEqualTo(GlassesPairingResult(glasses = glasses, phone = phoneB, glassesMacAddress = "00:11:22:33:44:55"))
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testShowCore_attemptCompletesAfterPrevious_returnsThatAttempt() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val audioGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          connectedTestDevice("g1", "emulator-5556", "Audio glasses", DeviceType.AI_GLASSES, coroutineScope)
        )
      val displayGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          connectedTestDevice("g2", "emulator-5558", "Display glasses", DeviceType.AI_GLASSES, coroutineScope)
        )
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, audioGlasses, displayGlasses))
      val firstAttempt = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val attempts = CopyOnWriteArrayList<DeviceHandle>()
      val splitViewPairs = CopyOnWriteArrayList<Pair<String, String>>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved(if (glasses === audioGlasses) "00:11:22:33:44:01" else "00:11:22:33:44:02")
            attempts.add(glasses)
            // The second attempt never reports a state, so the first attempt's Complete stays the current state.
            return if (glasses === audioGlasses) firstAttempt else flow { CompletableDeferred<Unit>().await() }
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = projectRecordingSplitView(splitViewPairs),
            devicesFlow = devicesFlow,
            glassesHandle = null,
            phoneHandle = phone,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Audio glasses"), 10_000)
      composeTestRule.onNodeWithText("Google Audio glasses").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      firstAttempt.value = PairingState.Pairing("Pairing in progress...")
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()

      // After Previous the user picks other glasses; the first attempt completes, then Next starts pairing them with their own MAC address.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a Glasses device to pair"), 10_000)
      composeTestRule.onNodeWithText("Google Display glasses").performClick()
      composeTestRule.waitForIdle()
      firstAttempt.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitUntil(10_000) {
        GlassesPairingEvent.EventKind.SHOW_SUCCESSFUL_PAIRING in tracker.events && splitViewPairs.isNotEmpty()
      }
      composeTestRule.onNodeWithText("Next").performClick()
      composeTestRule.waitUntil(10_000) { attempts.size == 2 }

      // Closing the window while that Complete is current returns what the first attempt paired, not the current selection.
      controller.close(false)
      val result = resultDeferred.await()
      assertThat(result).isEqualTo(GlassesPairingResult(glasses = audioGlasses, phone = phone, glassesMacAddress = "00:11:22:33:44:01"))
      assertThat(splitViewPairs).containsExactly("emulator-5556" to "emulator-5554")
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testShowCore_windowClosedOnPhoneListAfterAttemptCompletes_returnsThatAttempt() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phoneA = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val phoneB = connectedTestDevice("p2", "emulator-5558", "Pixel 8", DeviceType.HANDHELD, coroutineScope)
      val glasses = connectedTestDevice("g1", "emulator-5556", "Audio glasses", DeviceType.AI_GLASSES, coroutineScope)
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phoneA, phoneB, glasses))
      val attempt = MutableStateFlow<PairingState>(PairingState.NotStarted)
      val attempts = CopyOnWriteArrayList<DeviceHandle>()
      val splitViewPairs = CopyOnWriteArrayList<Pair<String, String>>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved("00:11:22:33:44:55")
            attempts.add(phone)
            return attempt
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = projectRecordingSplitView(splitViewPairs),
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      attempt.value = PairingState.Pairing("Pairing in progress...")
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()

      // After Previous the user selects phone B while phone A's attempt still runs; it completes, and the window is closed without Next.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a device to pair"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 8").performClick()
      composeTestRule.waitForIdle()
      attempt.value = PairingState.Complete("Pixel 9", "Audio glasses")
      composeTestRule.waitUntil(10_000) {
        GlassesPairingEvent.EventKind.SHOW_SUCCESSFUL_PAIRING in tracker.events && splitViewPairs.isNotEmpty()
      }
      composeTestRule.waitForIdle()
      controller.close(false)

      // The result and split-view notification name phone A, which paired, not the selected phone B.
      val result = resultDeferred.await()
      assertThat(attempts).containsExactly(phoneA)
      assertThat(result).isEqualTo(GlassesPairingResult(glasses = glasses, phone = phoneA, glassesMacAddress = "00:11:22:33:44:55"))
      assertThat(splitViewPairs).containsExactly("emulator-5556" to "emulator-5554")
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun testShowCore_attemptAwaitsAuthorizationAfterPrevious_bringsUpItsOwnPhone() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phoneA = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val phoneB = connectedTestDevice("p2", "emulator-5558", "Pixel 8", DeviceType.HANDHELD, coroutineScope)
      val glasses = connectedTestDevice("g1", "emulator-5556", "Audio glasses", DeviceType.AI_GLASSES, coroutineScope)
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phoneA, phoneB, glasses))
      val attempt = MutableSharedFlow<PairingState>(replay = 1, extraBufferCapacity = 2)
      val attempts = CopyOnWriteArrayList<DeviceHandle>()
      val headsUps = CopyOnWriteArrayList<String>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved("00:11:22:33:44:55")
            attempts.add(phone)
            return attempt
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = projectRecordingSplitView(CopyOnWriteArrayList(), headsUps),
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      attempt.emit(PairingState.Pairing("Pairing in progress..."))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()

      // After Previous the user selects phone B while phone A's attempt still runs; that attempt then waits for authorization on phone A.
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a device to pair"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 8").performClick()
      composeTestRule.waitForIdle()
      repeat(2) { attempt.emit(PairingState.AwaitingAuthorization("Pixel 9")) }
      composeTestRule.waitUntil(10_000) { headsUps.isNotEmpty() }
      composeTestRule.waitForIdle()

      // The heads-up brings up phone A once, where the Companion app asks for authorization, not the selected phone B.
      assertThat(headsUps).containsExactly("emulator-5554")
      assertThat(attempts).containsExactly(phoneA)
      controller.close(false)
      assertThat(resultDeferred.await()).isNull()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
  @Test
  fun testShowCore_attemptWaitsForXrServicesPermissions_bringsUpItsPhone() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phoneA = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val phoneB = connectedTestDevice("p2", "emulator-5558", "Pixel 8", DeviceType.HANDHELD, coroutineScope)
      val glasses = connectedTestDevice("g1", "emulator-5556", "Audio glasses", DeviceType.AI_GLASSES, coroutineScope)
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phoneA, phoneB, glasses))
      val attempt = MutableSharedFlow<PairingState>(replay = 1, extraBufferCapacity = 4)
      val attempts = CopyOnWriteArrayList<DeviceHandle>()
      val headsUps = CopyOnWriteArrayList<String>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved("00:11:22:33:44:55")
            attempts.add(phone)
            return attempt
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = projectRecordingSplitView(CopyOnWriteArrayList(), headsUps),
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      // Pre-creating the Companion CDM association lets pairing skip AwaitingAuthorization, so neither AwaitingForeground nor Pairing
      // raises Running Devices before the XR Services prompt arrives.
      attempt.emit(PairingState.AwaitingForeground("Pixel 9"))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Waiting for Companion app to move to the foreground on Pixel 9"), 10_000)
      attempt.emit(PairingState.Pairing("Pairing in progress..."))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      assertThat(headsUps).isEmpty()

      // Even if the user navigates back and selects phone B on the list, repeated GlassesCoreConnecting polls bring up phone A once.
      composeTestRule.onNodeWithText("Previous").assertIsEnabled().performClick()
      composeTestRule.waitUntilAtLeastOneExists(hasText("Select a device to pair"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 8").performClick()
      composeTestRule.waitForIdle()
      repeat(2) { attempt.emit(PairingState.GlassesCoreConnecting("Pixel 9")) }
      attempt.emit(
        PairingState.Error(
          heading = "Error pairing Audio glasses",
          detailText = "Failed to connect Audio glasses to XR Services on Pixel 9.",
          logDetail = "WORKER_CONNECTION_FAILED",
        )
      )
      // Waiting for the trailing error guarantees both GlassesCoreConnecting polls traversed the collector on Dispatchers.Default.
      composeTestRule.waitUntil(10_000) { GlassesPairingEvent.EventKind.SHOW_FAILED_PAIRING in tracker.events }

      assertThat(headsUps).containsExactly("emulator-5554")
      assertThat(attempts).containsExactly(phoneA)
      controller.close(false)
      assertThat(resultDeferred.await()).isNull()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
  @Test
  fun testShowCore_bothPermissionPrompts_bringsUpPhoneAtEachPrompt() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val phone = connectedTestDevice("p1", "emulator-5554", "Pixel 9", DeviceType.HANDHELD, coroutineScope)
      val glasses = connectedTestDevice("g1", "emulator-5556", "Audio glasses", DeviceType.AI_GLASSES, coroutineScope)
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, glasses))
      val attempt = MutableSharedFlow<PairingState>(replay = 1, extraBufferCapacity = 4)
      val headsUps = CopyOnWriteArrayList<String>()
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> {
            onMacRetrieved("00:11:22:33:44:55")
            return attempt
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = projectRecordingSplitView(CopyOnWriteArrayList(), headsUps),
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }

      composeTestRule.waitUntilAtLeastOneExists(hasText("Google Pixel 9"), 10_000)
      composeTestRule.onNodeWithText("Google Pixel 9").performClick()
      composeTestRule.onNodeWithText("Next").performClick()
      attempt.emit(PairingState.AwaitingForeground("Pixel 9"))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Waiting for Companion app to move to the foreground on Pixel 9"), 10_000)
      assertThat(headsUps).isEmpty()

      // When Stage 1 CDM pre-association does not short-circuit, both permission steps raise Running Devices once each.
      repeat(2) { attempt.emit(PairingState.AwaitingAuthorization("Pixel 9")) }
      attempt.emit(PairingState.Pairing("Pairing in progress..."))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Establishing pairing..."), 10_000)
      assertThat(headsUps).containsExactly("emulator-5554")

      repeat(2) { attempt.emit(PairingState.GlassesCoreConnecting("Pixel 9")) }
      attempt.emit(PairingState.Complete("Pixel 9", "Audio glasses"))
      composeTestRule.waitUntilAtLeastOneExists(hasText("Successfully paired Pixel 9 with Audio glasses"), 10_000)
      assertThat(headsUps).containsExactly("emulator-5554", "emulator-5554")

      composeTestRule.onNodeWithText("Previous").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Finish").assertIsEnabled().performClick()
      wizard.awaitClose()
      controller.close(true)
      assertThat(resultDeferred.await())
        .isEqualTo(GlassesPairingResult(glasses = glasses, phone = phone, glassesMacAddress = "00:11:22:33:44:55"))
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  private fun connectedTestDevice(
    id: String,
    serial: String,
    deviceModel: String,
    type: DeviceType,
    scope: CoroutineScope,
  ): FakeDeviceProvisionerPlugin.FakeDeviceHandle =
    FakeDeviceProvisionerPlugin.FakeDeviceHandle(
      id,
      scope,
      DeviceState.Connected(
        properties =
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = deviceModel
            deviceType = type
            androidVersion = AndroidVersion(36, 1)
          },
        connectedDevice =
          mock<ConnectedDevice>().also { device ->
            org.mockito.kotlin
              .whenever(device.deviceInfoFlow)
              .thenReturn(MutableStateFlow(com.android.adblib.DeviceInfo(serial, com.android.adblib.DeviceState.ONLINE)))
          },
        isTransitioning = false,
        isReady = true,
        status = "Online",
      ),
    )

  /** A project that records each split view in [events] and each single device brought up in [headsUps], by serial number. */
  private fun projectRecordingSplitView(
    events: MutableList<Pair<String, String>>,
    headsUps: MutableList<String> = mutableListOf(),
  ): Project {
    val bus = mock<com.intellij.util.messages.MessageBus>()
    val mockProject = mock<Project>()
    org.mockito.kotlin.whenever(mockProject.messageBus).thenReturn(bus)
    val listener =
      object : com.android.tools.idea.run.DeviceHeadsUpListener {
        override fun userInvolvementRequired(deviceSerialNumber: String, project: Project) {
          assertThat(project === mockProject).isTrue()
          headsUps.add(deviceSerialNumber)
        }

        override fun userInvolvementRequired(device1SerialNumber: String, device2SerialNumber: String, project: Project) {
          assertThat(project === mockProject).isTrue()
          events.add(device1SerialNumber to device2SerialNumber)
        }

        override fun launchingApp(deviceSerialNumber: String, project: Project) {}

        override fun launchingTest(deviceSerialNumber: String, project: Project) {}
      }
    org.mockito.kotlin.whenever(bus.syncPublisher(com.android.tools.idea.run.DeviceHeadsUpListener.TOPIC)).thenReturn(listener)
    return mockProject
  }

  @Test
  fun testSelectGlassesPage_pairedGlasses_repairWarningMentionsDataWipe() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val shownMessages = CopyOnWriteArrayList<String>()
    val previousDialog =
      TestDialogManager.setTestDialog(
        TestDialog { msg ->
          shownMessages.add(msg)
          Messages.NO
        }
      )
    var wizard: TestComposeWizard? = null
    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 9"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val pairedGlasses =
        FakeGlassesInteractivePairableDeviceHandle(
          FakeDeviceProvisionerPlugin.FakeDeviceHandle(
            "g2",
            coroutineScope,
            DeviceState.Connected(
              properties =
                DeviceProperties.buildForTest {
                  icon = EmptyIcon.DEFAULT
                  manufacturer = "Google"
                  model = "Other Paired Glasses"
                  deviceType = DeviceType.AI_GLASSES
                  androidVersion = AndroidVersion(36, 1)
                  pairedPhoneId = DeviceId("Test", false, "other_phone")
                },
              connectedDevice = mock<ConnectedDevice>(),
              isTransitioning = false,
              isReady = true,
              status = "Online",
            ),
          )
        )
      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(phone, pairedGlasses))
      val glassesWizard =
        GlassesPairingWizard(
          project = null,
          coroutineScope = coroutineScope,
          devicesFlow = devicesFlow,
          initialGlassesHandle = null,
          initialPhoneHandle = phone,
          pairer = mock<GlassesPairer>(),
          isCompatible = { true },
        )
      wizard = TestComposeWizard { with(glassesWizard) { SelectGlassesPage() } }
      composeTestRule.setContent { wizard.Content() }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Next").assertIsEnabled().performClick()
      composeTestRule.waitUntil(10_000) { shownMessages.isNotEmpty() }
      assertThat(shownMessages)
        .containsExactly(
          "Pairing these glasses with a different phone will unpair them from the original phone and wipe their data. Do you want to proceed?"
        )
    } finally {
      wizard?.close()
      TestDialogManager.setTestDialog(previousDialog)
      coroutineScope.cancel()
    }
  }

  @Test
  fun testPairingIllustrationsHaveDarkVariants() {
    for (name in listOf("glasses_pairing_bridge", "glasses_pairing_success", "glasses_pairing_error")) {
      for (path in listOf("/screens/$name.svg", "/screens/${name}_dark.svg")) {
        assertWithMessage(path).that(GlassesPairingWizard::class.java.getResource(path)).isNotNull()
      }
    }
  }

  @Test
  fun testShowCoreReturnsResultWhenWindowClosedInCompleteState() = runTest {
    val coroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    try {
      val phone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "phone_1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel Phone"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(37, 0)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "glasses_1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Display Glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val devicesFlow = MutableStateFlow(listOf<DeviceHandle>(phone, glasses))
      val pairer =
        object : GlassesPairer {
          override fun pair(
            glasses: DeviceHandle,
            phone: DeviceHandle,
            project: Project?,
            onMacRetrieved: (String) -> Unit,
          ): Flow<PairingState> = flow {
            onMacRetrieved("00:11:22:33:44:55")
            emit(PairingState.Complete("Pixel Phone", "Display Glasses"))
          }
        }
      val controller = TestWizardController()
      val testWizard = CompletableDeferred<TestComposeWizard>()
      // showCore is @UiThread (production calls it on the EDT); the compose test rule is still driven from the test thread.
      val resultDeferred =
        coroutineScope.async(Dispatchers.EDT) {
          GlassesPairingWizard.showCore(
            parent = null,
            project = null,
            devicesFlow = devicesFlow,
            glassesHandle = glasses,
            phoneHandle = phone,
            pairer = pairer,
            isCompatible = { true },
          ) { _, _, _, _, _, content ->
            testWizard.complete(TestComposeWizard { content() })
            controller
          }
        }
      val wizard = testWizard.await()
      composeTestRule.setContent { wizard.Content() }
      composeTestRule.waitForIdle()
      // Simulate closing the wizard window via the titlebar X button (controller.show() returns false)
      controller.close(false)
      val result = resultDeferred.await()
      assertThat(result).isEqualTo(GlassesPairingResult(glasses = glasses, phone = phone, glassesMacAddress = "00:11:22:33:44:55"))
    } finally {
      coroutineScope.cancel()
    }
  }

  @Test
  fun testSelectDevicePage_defaultSelectionPrioritizesOnlineAndIgnoresIncompatible() = runTest {
    val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)

    try {
      val glasses =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "g1",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Audio glasses"
                deviceType = DeviceType.AI_GLASSES
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val incompatiblePhone =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p_incompat",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 8"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )
      val offlinePhoneA =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p_offline_a",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Medium Phone"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val offlinePhoneB =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p_offline_b",
          coroutineScope,
          DeviceState.Disconnected(
            DeviceProperties.buildForTest {
              icon = EmptyIcon.DEFAULT
              manufacturer = "Google"
              model = "Pixel 9 Pro"
              deviceType = DeviceType.HANDHELD
              androidVersion = AndroidVersion(36, 1)
            }
          ),
        )
      val onlinePhoneB =
        FakeDeviceProvisionerPlugin.FakeDeviceHandle(
          "p_online_b",
          coroutineScope,
          DeviceState.Connected(
            properties =
              DeviceProperties.buildForTest {
                icon = EmptyIcon.DEFAULT
                manufacturer = "Google"
                model = "Pixel 9 Pro"
                deviceType = DeviceType.HANDHELD
                androidVersion = AndroidVersion(36, 1)
              },
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = false,
            isReady = true,
            status = "Online",
          ),
        )

      val devicesFlow = MutableStateFlow<List<DeviceHandle>>(listOf(glasses, incompatiblePhone))
      val glassesWizard =
        GlassesPairingWizard(
          project = null,
          coroutineScope = coroutineScope,
          devicesFlow = devicesFlow,
          initialGlassesHandle = glasses,
          pairer = mock<GlassesPairer>(),
          isCompatible = { it != incompatiblePhone },
        )
      val wizard = TestComposeWizard { with(glassesWizard) { SelectDevicePage() } }

      composeTestRule.setContent { wizard.Content() }
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithText("No compatible AVDs found.").assertIsDisplayed()
      assertThat(glassesWizard.phoneHandle).isNull()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()

      devicesFlow.value = listOf(glasses, incompatiblePhone, offlinePhoneB, offlinePhoneA)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.phoneHandle).isEqualTo(offlinePhoneA)
      composeTestRule.onNodeWithText("Next").assertIsEnabled()

      devicesFlow.value = listOf(glasses, incompatiblePhone, offlinePhoneA, onlinePhoneB)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.phoneHandle).isEqualTo(offlinePhoneA)

      devicesFlow.value = listOf(glasses, incompatiblePhone, onlinePhoneB)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.phoneHandle).isEqualTo(onlinePhoneB)

      devicesFlow.value = listOf(glasses, incompatiblePhone)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.phoneHandle).isNull()
      composeTestRule.onNodeWithText("Next").assertIsNotEnabled()

      devicesFlow.value = listOf(glasses, incompatiblePhone, offlinePhoneA, onlinePhoneB)
      composeTestRule.waitForIdle()
      assertThat(glassesWizard.phoneHandle).isEqualTo(onlinePhoneB)
      composeTestRule.onNodeWithText("Next").assertIsEnabled()

      assertThat(tracker.events).doesNotContain(GlassesPairingEvent.EventKind.PAIRING_DEVICE_SELECTED)

      wizard.close()
      wizard.awaitClose()
    } finally {
      coroutineScope.cancel()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testPairGlassesToPhone_enablesGlassesBluetoothAndWaitsForAddress() = runBlockingWithTimeout {
    AiGlassesPairing.setPollingIntervalForTest(10.milliseconds)
    UsageTracker.setWriterForTest(TestTracker())
    val adbSession = FakeAdbSession()
    try {
      val (glasses, phone) = connectPairingDevices(adbSession, glassesBluetoothAddress = "null\n")
      val glassesSelector = DeviceSelector.fromSerialNumber(GLASSES_SERIAL)

      var retrievedMac: String? = null
      val statesDeferred = async {
        pairGlassesToPhone(glasses, phone, launchFlow = { emptyFlow() }, onMacRetrieved = { retrievedMac = it }).toList()
      }

      yieldUntil { adbSession.shellCommandsSentTo(GLASSES_SERIAL).contains("settings get secure bluetooth_address") }
      adbSession.deviceServices.configureShellCommand(glassesSelector, "settings get secure bluetooth_address", "$GLASSES_MAC\n")

      val states = statesDeferred.await()

      assertThat(retrievedMac).isEqualTo(GLASSES_MAC)
      assertThat(states.last()).isEqualTo(PairingState.Complete("Google Pixel 9", "Google Audio glasses"))
      val glassesCommands = adbSession.shellCommandsSentTo(GLASSES_SERIAL)
      assertThat(glassesCommands.take(4))
        .containsExactly(
          "cmd location set-location-enabled true",
          "cmd bluetooth_manager enable",
          "cmd bluetooth_manager wait-for-state:STATE_ON",
          "dumpsys bluetooth_manager | grep 'Bonded devices:'",
        )
        .inOrder()
      assertThat(glassesCommands.drop(4).toSet()).containsExactly("settings get secure bluetooth_address")
      assertThat(glassesCommands.count { it == "settings get secure bluetooth_address" }).isAtLeast(2)
    } finally {
      adbSession.closeAndJoin()
      UsageTracker.cleanAfterTesting()
    }
  }

  @Test
  fun testPairGlassesToPhone_reportsErrorWhenGlassesBluetoothAddressTimesOut() = runBlockingWithTimeout {
    AiGlassesPairing.setPollingTimeoutForTest(Duration.ZERO)
    val tracker = TestTracker()
    UsageTracker.setWriterForTest(tracker)
    val adbSession = FakeAdbSession()
    try {
      val (glasses, phone) = connectPairingDevices(adbSession, glassesBluetoothAddress = "null\n")

      val states = pairGlassesToPhone(glasses, phone, launchFlow = { emptyFlow() }, onMacRetrieved = {}).toList()

      assertThat(states.last()).isEqualTo(PairingState.Error("Failed to retrieve Bluetooth address of Google Audio glasses."))
      assertThat(tracker.events).contains(GlassesPairingEvent.EventKind.PAIRING_ERROR_BLUETOOTH_ADDRESS)
      assertThat(adbSession.shellCommandsSentTo(GLASSES_SERIAL))
        .containsExactly(
          "cmd location set-location-enabled true",
          "cmd bluetooth_manager enable",
          "cmd bluetooth_manager wait-for-state:STATE_ON",
          "dumpsys bluetooth_manager | grep 'Bonded devices:'",
          "settings get secure bluetooth_address",
        )
        .inOrder()
    } finally {
      adbSession.closeAndJoin()
      UsageTracker.cleanAfterTesting()
    }
  }
}

private class TestWizardController : WizardController {
  val completion = CompletableDeferred<Boolean>()
  var showCalled = false

  override suspend fun show(): Boolean {
    showCalled = true
    return completion.await()
  }

  fun close(result: Boolean) {
    completion.complete(result)
  }
}

private class TestTracker : UsageTrackerWriter() {
  val events = CopyOnWriteArrayList<GlassesPairingEvent.EventKind>()

  override fun logDetails(logEvent: ClientAnalytics.LogEvent.Builder) {}

  override fun processEvent(studioEvent: AndroidStudioEvent.Builder): Builder {
    if (studioEvent.kind == AndroidStudioEvent.EventKind.GLASSES_PAIRING_EVENT) {
      events.add(studioEvent.glassesPairingEvent.kind)
    }

    return studioEvent
  }

  override fun close() {}

  override fun flush() {}
}

private class TestDeviceHandle(
  scope: CoroutineScope,
  val name: String,
  val activationDelay: Duration,
  val bootDelay: Duration = 0.seconds,
) :
  FakeDeviceProvisionerPlugin.FakeDeviceHandle(
    name,
    scope,
    initialState =
      DeviceState.Disconnected(
        DeviceProperties.buildForTest {
          model = name
          icon = EmptyIcon.DEFAULT
        }
      ),
  ) {
  override var activationAction: ActivationAction? =
    object : FakeDeviceProvisionerPlugin.FakeActivationAction() {
      override suspend fun activate() {
        delay(activationDelay)
        stateFlow.value =
          DeviceState.Connected(
            properties = state.properties,
            connectedDevice = mock<ConnectedDevice>(),
            isTransitioning = true,
            isReady = false,
            status = "Online",
          )
        scope.launch {
          delay(bootDelay)
          stateFlow.value =
            DeviceState.Connected(
              properties = state.properties,
              connectedDevice = mock<ConnectedDevice>(),
              isTransitioning = false,
              isReady = true,
              status = "Online",
            )
        }
      }
    }
}

private fun createLaunchingState(phone: LaunchState, glasses: LaunchState) =
  PairingState.Launching(phoneName = "P", phoneLaunchState = phone, glassesName = "G", glassesLaunchState = glasses)

private class FakeGlassesInteractivePairableDeviceHandle(
  val delegate: DeviceHandle,
  var pairEnabled: Boolean = true,
  var unpairEnabled: Boolean = true,
  private val onUnpair: suspend () -> Unit = {},
) : GlassesInteractivePairableDeviceHandle, DeviceHandle by delegate {
  override fun isPairGlassesEnabled() = pairEnabled

  override suspend fun pairGlasses(parent: java.awt.Component?, project: com.intellij.openapi.project.Project?) = true

  override fun isUnpairGlassesEnabled() = unpairEnabled

  override suspend fun unpairGlasses(parent: java.awt.Component?) {
    onUnpair()
  }
}

private const val PHONE_SERIAL = "emulator-5554"
private const val GLASSES_SERIAL = "emulator-5556"
private const val GLASSES_MAC = "AA:BB:CC:DD:EE:FF"
private const val COMPANION_PKG = "com.google.android.glasses.companion"
private const val UNPAIR_COMMAND = "am broadcast -a $COMPANION_PKG.UNPAIR --es address \"$GLASSES_MAC\" -p $COMPANION_PKG"
private const val ASSISTED_PAIR_COMMAND =
  "am broadcast -a $COMPANION_PKG.ASSISTED_PAIR --es address \"$GLASSES_MAC\" --ez auto_cdm true --ez force true -p $COMPANION_PKG"

private fun FakeAdbSession.shellCommandsSentTo(serialNumber: String): List<String> {
  val selector = DeviceSelector.fromSerialNumber(serialNumber).toString()
  return (deviceServices.shellRequests + deviceServices.shellV2Requests).filter { it.deviceSelector == selector }.map { it.command }
}

private data class PairingDevices(val glasses: DeviceHandle, val phone: DeviceHandle)

private suspend fun connectPairingDevices(adbSession: FakeAdbSession, glassesBluetoothAddress: String): PairingDevices {
  adbSession.hostServices.devices =
    DeviceList(listOf(DeviceInfo(PHONE_SERIAL, AdbDeviceState.ONLINE), DeviceInfo(GLASSES_SERIAL, AdbDeviceState.ONLINE)), emptyList())
  val glassesSelector = DeviceSelector.fromSerialNumber(GLASSES_SERIAL)
  val phoneSelector = DeviceSelector.fromSerialNumber(PHONE_SERIAL)

  adbSession.deviceServices.configureShellCommand(glassesSelector, "cmd location set-location-enabled true", "")
  adbSession.deviceServices.configureShellCommand(glassesSelector, "cmd bluetooth_manager enable", "")
  adbSession.deviceServices.configureShellCommand(glassesSelector, "cmd bluetooth_manager wait-for-state:STATE_ON", "")
  adbSession.deviceServices.configureShellCommand(
    glassesSelector,
    "dumpsys bluetooth_manager | grep 'Bonded devices:'",
    "Bonded devices: 0\n",
  )
  adbSession.deviceServices.configureShellCommand(glassesSelector, "settings get secure bluetooth_address", glassesBluetoothAddress)

  adbSession.deviceServices.configureShellCommand(
    phoneSelector,
    "pm list packages com.google.android.glasses",
    "package:$COMPANION_PKG\npackage:com.google.android.glasses.core\n",
  )
  adbSession.deviceServices.configureShellCommand(phoneSelector, "settings get secure bluetooth_address", "11:22:33:44:55:66\n")
  listOf(
      "pm grant $COMPANION_PKG android.permission.NEARBY_WIFI_DEVICES",
      "pm grant $COMPANION_PKG android.permission.BLUETOOTH_CONNECT",
      "pm grant com.google.android.glasses.core android.permission.ACCESS_FINE_LOCATION",
      "cmd location set-location-enabled true",
      "cmd bluetooth_manager enable",
      "cmd bluetooth_manager wait-for-state:STATE_ON",
      "monkey -p $COMPANION_PKG -c android.intent.category.LAUNCHER 1",
      UNPAIR_COMMAND,
      ASSISTED_PAIR_COMMAND,
    )
    .forEach { adbSession.deviceServices.configureShellCommand(phoneSelector, it, "") }
  adbSession.deviceServices.configureShellCommand(
    phoneSelector,
    "dumpsys window displays",
    "  mCurrentFocus=Window{1 u0 $COMPANION_PKG/$COMPANION_PKG.MainActivity}\n",
  )
  adbSession.deviceServices.configureShellCommand(
    phoneSelector,
    "am broadcast -a $COMPANION_PKG.GET_PAIRING_STATE -p $COMPANION_PKG",
    "Broadcast completed: result=-1, data=\"state=PAIRED\"\n",
  )

  val phone =
    FakeDeviceProvisionerPlugin.FakeDeviceHandle(
      "p1",
      adbSession.scope,
      DeviceState.Connected(
        properties =
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Pixel 9"
            deviceType = DeviceType.HANDHELD
            androidVersion = AndroidVersion(36, 1)
          },
        isTransitioning = false,
        isReady = true,
        status = "Connected",
        connectedDevice = adbSession.connectedDevicesTracker.waitForDevice(PHONE_SERIAL),
      ),
    )
  val glasses =
    FakeDeviceProvisionerPlugin.FakeDeviceHandle(
      "g1",
      adbSession.scope,
      DeviceState.Connected(
        properties =
          DeviceProperties.buildForTest {
            icon = EmptyIcon.DEFAULT
            manufacturer = "Google"
            model = "Audio glasses"
            deviceType = DeviceType.AI_GLASSES
            androidVersion = AndroidVersion(36, 1)
          },
        isTransitioning = false,
        isReady = true,
        status = "Connected",
        connectedDevice = adbSession.connectedDevicesTracker.waitForDevice(GLASSES_SERIAL),
      ),
    )
  return PairingDevices(glasses = glasses, phone = phone)
}
