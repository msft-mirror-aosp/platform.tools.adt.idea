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
package com.android.tools.idea.avd

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.android.flags.junit.FlagRule
import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.AndroidVersion
import com.android.sdklib.PathFileWrapper
import com.android.sdklib.SystemImageTags
import com.android.sdklib.devices.Device
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.internal.avd.ConfigKey
import com.android.sdklib.internal.avd.EnvironmentKey
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.tools.adtui.compose.TestComposeWizard
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.adddevicedialog.FormFactors
import com.android.tools.idea.avd.EnvironmentFileAnalyzerTest.Companion.createMockJpegWithXmp
import com.android.tools.idea.avdmanager.AccelerationErrorCode
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.AndroidSdksImpl
import com.android.utils.PathUtils
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.replaceService
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunsInEdt
class AddDeviceWizardTest {
  @get:Rule val edtRule = EdtRule()
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()
  @get:Rule val composeTestRule = createStudioComposeTestRule()
  @get:Rule val aiGlassesFlagRule = FlagRule(StudioFlags.AI_GLASSES_DEVICE_SUPPORT_ENABLED, true)
  @get:Rule val xrGlassesFlagRule = FlagRule(StudioFlags.XR_GLASSES_DEVICE_SUPPORT_ENABLED, true)

  /**
   * Pick a device, advance, and then finish (using default system image and settings). Verify that AVD files are created. Then do it again.
   */
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun addDeviceDefaultPath() {
    with(SdkFixture()) {
      val api34 = createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))
      setLocalPackages(api34)

      val source = createAddDeviceWizard()

      fun addPixel8() {
        val wizard = createTestAddDeviceWizard(source)

        composeTestRule.setContentWithSdkLocals { wizard.Content() }

        composeTestRule.waitUntilAtLeastOneExists(hasText("Pixel 8"))
        composeTestRule.onNodeWithText("Pixel 8").performClick()
        composeTestRule.waitForIdle()

        wizard.performAction(wizard.nextAction)
        composeTestRule.waitForIdle()

        composeTestRule.waitUntilDoesNotExist(hasText("Loading system images", substring = true))
        wizard.performAction(wizard.nextAction)
        composeTestRule.waitForIdle()
        wizard.awaitClose()
      }

      addPixel8()

      assertThat(Files.list(avdRoot).map { it.fileName.toString() }.toList()).containsExactly("Pixel_8.avd", "Pixel_8.ini")

      addPixel8()

      assertThat(Files.list(avdRoot).map { it.fileName.toString() }.toList())
        .containsExactly("Pixel_8.avd", "Pixel_8.ini", "Pixel_8_2.avd", "Pixel_8_2.ini")
    }
  }

  @Test
  fun addAutomotiveDevice() {
    with(SdkFixture()) {
      val api34Ext9Auto =
        createLocalSystemImage("android-automotive", listOf(SystemImageTags.AUTOMOTIVE_TAG), AndroidVersion(34, null, 9, false))
      setLocalPackages(api34Ext9Auto)

      val source = createAddDeviceWizard()
      val wizard = createTestAddDeviceWizard(source)

      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("Automotive").performClick()
      composeTestRule.onNodeWithText("Automotive Portrait").performClick()
      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)

      composeTestRule.onNodeWithText(api34Ext9Auto.displayName).assertIsSelected()
      composeTestRule.onNodeWithText("Additional settings").performClick()
      composeTestRule.onNodeWithText("Camera").assertDoesNotExist()
      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)
      wizard.awaitClose()

      assertThat(Files.list(avdRoot).map { it.fileName.toString() }.toList())
        .containsExactly("Automotive_Portrait.avd", "Automotive_Portrait.ini")
      val properties =
        checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdRoot.resolve("Automotive_Portrait.avd").resolve("config.ini")), null))
      assertThat(properties[ConfigKey.CAMERA_FRONT]).isEqualTo("none")
    }
  }

  @Test
  fun addAudioGlassesDevice() {
    // The AVD needs to be on a real filesystem for the copy of the default environment to work.
    val fixture = SdkFixture(avdRoot = createTempDirectory("AddAudioGlassesDeviceTest"))
    with(fixture) {
      // We need to inject the SDK for EnvironmentsUpdater.
      val androidSdks =
        object : AndroidSdksImpl() {
          override fun tryToChooseSdkHandler(): AndroidSdkHandler {
            return sdkHandler
          }
        }
      ApplicationManager.getApplication()?.registerOrReplaceServiceInstance(AndroidSdks::class.java, androidSdks, disposableRule.disposable)

      val api36Glasses = createLocalSystemImage("ai-glasses", listOf(SystemImageTags.AI_GLASSES_TAG), AndroidVersion(36, null, 9, false))
      setLocalPackages(api36Glasses)

      val source = createAddDeviceWizard()
      val wizard = createTestAddDeviceWizard(source)

      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("XR").performClick()
      composeTestRule.onNodeWithText("Audio Glasses").performClick()
      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)

      composeTestRule.onNodeWithText(api36Glasses.displayName).assertIsSelected()
      composeTestRule.onNodeWithText("Additional settings").performClick()
      // Glasses have an environment, not skin, which is configured at runtime.
      composeTestRule.onNodeWithText("skin", substring = true, ignoreCase = true).assertDoesNotExist()
      // We need to disable the external storage, since we can't run mksdcard.
      composeTestRule.onNode(hasText("None") and hasParent(hasTestTag("StorageGroup"))).performClick()

      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)
      wizard.awaitClose()

      val avdFolder = avdRoot.listDirectoryEntries("*.avd").single()
      val properties = checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve("config.ini")), null))
      assertThat(properties[ConfigKey.LCD_TRANSPARENT]).isEqualTo("yes")
      assertThat(properties[ConfigKey.FORCE_COLD_BOOT_MODE]).isEqualTo("yes")
      val defaultEnv = runBlocking { EnvironmentsUpdater.getInstance().getEnvironments() }.first { it.isDefault }
      val environmentProperties =
        checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve(AvdManager.ENVIRONMENT_INI)), null))
      assertThat(environmentProperties[EnvironmentKey.SCENE_MODE])
        .isEqualTo("image360:${PathUtils.toSystemIndependentPath(defaultEnv.path)}")
    }
  }

  @Test
  fun addAiGlassesDevice_sets360And2dEnvironmentModes() = runBlocking {
    with(SdkFixture()) {
      val api36Glasses = createLocalSystemImage("ai-glasses", listOf(SystemImageTags.AI_GLASSES_TAG), AndroidVersion(36, null, 9, false))
      setLocalPackages(api36Glasses)
      val glassesProfile = deviceManager.getDevice("ai_glasses_device", "Google")!!
      val systemImage = sdkHandler.getSystemImageManager(FakeProgressIndicator()).images.single()

      val envDir = sdkRoot.resolve("environments")
      Files.createDirectories(envDir)
      val image360Path = envDir.resolve("custom-panorama.jpg")
      Files.write(image360Path, createMockJpegWithXmp("Panorama", isDefault = true, is360 = true))
      val image2dPath = envDir.resolve("custom-flat.jpg")
      Files.write(image2dPath, createMockJpegWithXmp("Flat", isDefault = true, is360 = false))

      val mockUpdater = mock<EnvironmentsUpdater>()
      ApplicationManager.getApplication().replaceService(EnvironmentsUpdater::class.java, mockUpdater, disposableRule.disposable)

      // 1. 360 default environment image -> image360:<path>
      whenever(mockUpdater.getEnvironments()).thenReturn(listOf(EnvironmentImage(image360Path, "Panorama", isDefault = true)))
      val device360 =
        VirtualDevice(glassesProfile).apply {
          initializeFromProfile()
          name = "Glasses 360"
          image = systemImage
          expandedStorage = None
        }
      val avdInfo360 = VirtualDevices(avdManager).add(device360)
      val envProps360 =
        checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdInfo360.dataFolderPath.resolve(AvdManager.ENVIRONMENT_INI)), null))
      assertThat(envProps360[EnvironmentKey.SCENE_MODE]).isEqualTo("image360:${PathUtils.toSystemIndependentPath(image360Path)}")

      // 2. 2D default environment image -> imagefile:<path>
      whenever(mockUpdater.getEnvironments()).thenReturn(listOf(EnvironmentImage(image2dPath, "Flat", isDefault = true)))
      val device2d =
        VirtualDevice(glassesProfile).apply {
          initializeFromProfile()
          name = "Glasses 2D"
          image = systemImage
          expandedStorage = None
        }
      val avdInfo2d = VirtualDevices(avdManager).add(device2d)
      val envProps2d =
        checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdInfo2d.dataFolderPath.resolve(AvdManager.ENVIRONMENT_INI)), null))
      assertThat(envProps2d[EnvironmentKey.SCENE_MODE]).isEqualTo("imagefile:${PathUtils.toSystemIndependentPath(image2dPath)}")
    }
  }

  @Test
  fun addXrGlassesDevice() {
    // The AVD needs to be on a real filesystem for the copy of the default environment to work.
    val fixture = SdkFixture(avdRoot = createTempDirectory("AddXrGlassesDeviceTest"))
    with(fixture) {
      val api34XrOst =
        createLocalSystemImage(
          "xr-glasses",
          listOf(SystemImageTags.XR_GLASSES_TAG, SystemImageTags.XR_HEADSET_TAG),
          AndroidVersion(34, null, 9, false),
        )
      setLocalPackages(api34XrOst)

      val source = createAddDeviceWizard()
      val wizard = createTestAddDeviceWizard(source)

      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("XR").performClick()
      composeTestRule.onAllNodesWithText("XR Glasses", substring = true).onFirst().performClick()
      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)

      composeTestRule.onNodeWithText(api34XrOst.displayName).assertIsSelected()
      composeTestRule.onNodeWithText("Additional settings").performClick()
      composeTestRule.onNodeWithText("Device skin").assertDoesNotExist()
      // We need to disable the external storage, since we can't run mksdcard.
      composeTestRule.onNode(hasText("None") and hasParent(hasTestTag("StorageGroup"))).performClick()

      composeTestRule.waitForIdle()

      wizard.performAction(wizard.nextAction)
      wizard.awaitClose()

      val avdFolder = avdRoot.listDirectoryEntries("*.avd").single()
      val properties = checkNotNull(AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve("config.ini")), null))
      assertThat(properties[ConfigKey.LCD_TRANSPARENT]).isNotEqualTo("yes")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun accelerationErrorCode() {
    with(SdkFixture()) {
      val api34 = createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))
      setLocalPackages(api34)

      val wizard = TestComposeWizard {
        with(createAddDeviceWizard(accelerationCheck = { AccelerationErrorCode.NO_EMULATOR_INSTALLED })) { DeviceGridPage() }
      }
      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.waitUntilDoesNotExist(hasText("Loading system images", substring = true))
      // The fetching of the acceleration status is asynchronous
      composeTestRule.waitUntilAtLeastOneExists(hasText("No emulator installed"))
      composeTestRule.onNodeWithText("No emulator installed").assertIsDisplayed()
      composeTestRule.onNodeWithText("Install Emulator").assertIsEnabled()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun statePreservation() {
    with(SdkFixture()) {
      val api34 = createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))
      setLocalPackages(api34)

      val phone = deviceManager.getDevice("medium_phone", "Generic")!!
      for (letter in 'A'..'Z') {
        deviceManager
          .getUserDevices()
          ?.addUserDevice(
            Device.Builder(phone)
              .apply {
                setName("${letter}Phone")
                setId("${letter}phone")
              }
              .build()
          )
      }

      val deviceCount = deviceManager.getDevices().size
      val source = createAddDeviceWizard()
      val wizard = createTestAddDeviceWizard(source)

      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      // Sort by name then arrow down to bring ZPhone into view
      composeTestRule.onNodeWithText("Name").performClick()
      repeat(deviceCount) { composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
      composeTestRule.onNodeWithText("ZPhone").performClick()

      // Show the details, now we see ZPhone twice
      composeTestRule.onNodeWithContentDescription("Details").performClick()
      composeTestRule.onAllNodesWithText("ZPhone").assertCountEquals(2)

      // Go forward and back
      wizard.performAction(wizard.nextAction)
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Previous").performClick()

      // Sort order is preserved; ZPhone is still selected; details still visible
      composeTestRule.onNodeWithText("APhone").assertDoesNotExist()
      composeTestRule.onNodeWithClickableText("ZPhone").assertIsSelected()
      composeTestRule.onAllNodesWithText("ZPhone").assertCountEquals(2)
      composeTestRule.onNode(hasText("Name") and hasContentDescription("Sorted ascending"))
    }
  }

  @Test
  fun noSystemImage() {
    with(SdkFixture()) {
      val api34 = createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))
      setLocalPackages(api34)

      val wizard = TestComposeWizard {
        with(createAddDeviceWizard(accelerationCheck = { AccelerationErrorCode.NO_EMULATOR_INSTALLED })) { DeviceGridPage() }
      }
      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("XR").performClick()
      composeTestRule.waitForIdle()
      assertThat(wizard.nextAction.enabled).isFalse()

      composeTestRule.onNodeWithText("XR Headset").performClick()
      composeTestRule.waitForIdle()
      wizard.performAction(wizard.nextAction)

      composeTestRule.waitForIdle()
      assertThat(wizard.nextAction.enabled).isFalse()

      composeTestRule.onNodeWithText("No system images available.").assertIsDisplayed()
    }
  }

  @Test
  fun noSupportedSystemImage() {
    with(SdkFixture()) {
      val api34 = createLocalSystemImage("google_atd", listOf(SystemImageTags.GOOGLE_ATD_TAG), AndroidVersion(34))
      setLocalPackages(api34)

      val source = createAddDeviceWizard()

      val wizard = TestComposeWizard {
        with(createAddDeviceWizard(accelerationCheck = { AccelerationErrorCode.NO_EMULATOR_INSTALLED })) { DeviceGridPage() }
      }
      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("Medium Phone").performClick()
      composeTestRule.waitForIdle()
      wizard.performAction(wizard.nextAction)

      composeTestRule.waitForIdle()
      assertThat(wizard.nextAction.enabled).isFalse()

      composeTestRule.onNodeWithText("No system images available matching the current set of filters.").assertIsDisplayed()
    }
  }

  @Test
  fun virtualDeviceFilter() {
    with(SdkFixture()) {
      val wizard = TestComposeWizard { with(createAddDeviceWizard(virtualDeviceFilter = { it.name == "Pixel 8" })) { DeviceGridPage() } }
      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("Pixel 8").assertIsDisplayed()
      composeTestRule.onNodeWithText("Pixel 7").assertDoesNotExist()
    }
  }

  @Test
  fun virtualDeviceFilter_nonDefaultFormFactor() {
    with(SdkFixture()) {
      val wizard = TestComposeWizard {
        with(createAddDeviceWizard(virtualDeviceFilter = { it.formFactor == FormFactors.AI_GLASSES })) { DeviceGridPage() }
      }
      composeTestRule.setContentWithSdkLocals { wizard.Content() }

      composeTestRule.onNodeWithText("XR").assertIsSelected()
      composeTestRule.onNodeWithText("Audio Glasses").assertIsDisplayed()
      composeTestRule.onNodeWithText("Display Glasses").assertIsDisplayed()
    }
  }
}

private fun createTestAddDeviceWizard(wizard: AddDeviceWizard) = TestComposeWizard { with(wizard) { DeviceGridPage() } }
