/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.welcome.install

import com.android.SdkConstants
import com.android.flags.junit.FlagRule
import com.android.prefs.AndroidLocationsSingleton
import com.android.sdklib.internal.avd.EmulatorPackage
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.TestUtils
import com.android.tools.idea.avdmanager.AccelerationErrorCode
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.SystemInfo
import org.junit.Rule
import org.junit.Test

class AndroidSdkTest {
  @get:Rule val emulatorPreviewFlagRule = FlagRule(StudioFlags.EMULATOR_PREVIEW_REQUIRED)

  @Test
  fun `get required SDK packages doesn't return emulator on Chrome OS`() {
    val sdk =
      AndroidSdkComponentTreeNode(true).apply { updateState(AndroidSdkHandler.getInstance(AndroidLocationsSingleton, TestUtils.getSdk())) }
    val packages = sdk.getRequiredSdkPackages(true)
    assertThat(packages).doesNotContain(SdkConstants.FD_EMULATOR)
  }

  @Test
  fun `get required SDK packages includes preview emulator when flag is enabled`() {
    StudioFlags.EMULATOR_PREVIEW_REQUIRED.override(true)
    val sdk = AndroidSdkComponentTreeNode(false)
    val packages = sdk.getRequiredSdkPackages(false)
    assertThat(packages).contains(EmulatorPackage.QEMU_NEXT_PACKAGE_PATH)
  }

  @Test
  fun `get required SDK packages does not include preview emulator when flag is disabled`() {
    StudioFlags.EMULATOR_PREVIEW_REQUIRED.override(false)
    val sdk = AndroidSdkComponentTreeNode(false)
    val packages = sdk.getRequiredSdkPackages(false)
    assertThat(packages).doesNotContain(EmulatorPackage.QEMU_NEXT_PACKAGE_PATH)
  }

  @Test
  fun `AEHD is only compatible with Windows`() {
    if (SystemInfo.isWindows) {
      assertThat(AehdSdkComponentTreeNode.InstallerInfo.checkInstallation()).isNotEqualTo(AccelerationErrorCode.AEHD_REQUIRES_WINDOWS)
    } else {
      assertThat(AehdSdkComponentTreeNode.InstallerInfo.checkInstallation()).isEqualTo(AccelerationErrorCode.AEHD_REQUIRES_WINDOWS)
    }
  }
}
