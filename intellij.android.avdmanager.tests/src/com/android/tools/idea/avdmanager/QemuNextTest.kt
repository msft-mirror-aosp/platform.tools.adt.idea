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
package com.android.tools.idea.avdmanager

import com.android.tools.idea.flags.StudioFlags
import com.android.utils.Environment
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class QemuNextTest {
  @Before
  fun setUp() {
    StudioFlags.EMULATOR_PREVIEW_REQUIRED.override(true)
  }

  @After
  fun tearDown() {
    StudioFlags.EMULATOR_PREVIEW_REQUIRED.clearOverride()
    Environment.initialize(Environment.SYSTEM)
  }

  @Test
  fun requiredOnLinuxX86_64() {
    Environment.initialize(fakeEnvironment(osName = "Linux", osArch = "amd64"))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnWindowsX86_64() {
    Environment.initialize(fakeEnvironment(osName = "Windows 11", osArch = "amd64"))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnAppleSilicon() {
    Environment.initialize(fakeEnvironment(osName = "Mac OS X", osArch = "aarch64"))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnAppleSiliconWithX64Studio() {
    Environment.initialize(fakeEnvironment(osName = "Mac OS X", osArch = "x86_64", isRosetta = true))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnWindowsArm64() {
    Environment.initialize(fakeEnvironment(osName = "Windows 11", osArch = "aarch64"))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnWindowsArmWithX64Studio() {
    Environment.initialize(fakeEnvironment(osName = "Windows 11", osArch = "amd64", isWindowsArm64 = true))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun requiredOnLinuxArm64() {
    Environment.initialize(fakeEnvironment(osName = "Linux", osArch = "aarch64"))
    assertThat(QemuNext.isRequired()).isTrue()
  }

  @Test
  fun notRequiredOnIntelMac() {
    Environment.initialize(fakeEnvironment(osName = "Mac OS X", osArch = "x86_64"))
    assertThat(QemuNext.isRequired()).isFalse()
  }

  @Test
  fun notRequiredWhenFlagIsOff() {
    StudioFlags.EMULATOR_PREVIEW_REQUIRED.override(false)
    Environment.initialize(fakeEnvironment(osName = "Linux", osArch = "amd64"))
    assertThat(QemuNext.isRequired()).isFalse()
  }

  private fun fakeEnvironment(osName: String, osArch: String, isRosetta: Boolean = false, isWindowsArm64: Boolean = false) =
    object : Environment() {
      override fun getVariable(name: EnvironmentVariable): String? = null

      override fun getSystemProperty(name: SystemProperty): String =
        when (name) {
          SystemProperty.OS_NAME -> osName
          SystemProperty.OS_ARCH -> osArch
        }

      override val isRosetta: Boolean = isRosetta
      override val isWindowsArm64: Boolean = isWindowsArm64
    }
}
