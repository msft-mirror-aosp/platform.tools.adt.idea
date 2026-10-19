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

import com.android.sdklib.AndroidVersion
import com.android.sdklib.ISystemImage
import com.android.sdklib.RemoteSystemImage
import com.android.sdklib.SystemImageTags
import com.android.sdklib.devices.Abi
import com.android.sdklib.repository.IdDisplay
import com.android.sdklib.repository.targets.SystemImage
import com.android.utils.Environment
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ISystemImagesTest {
  @get:Rule val applicationRule = ApplicationRule()

  @After
  fun tearDown() {
    Environment.initialize(Environment.SYSTEM)
  }

  @Test
  fun systemImageFlow_empty() {
    with(SdkFixture()) {
      val imageFlow = ISystemImages.systemImageFlow(sdkHandler)
      runBlocking {
        withTimeout(10.seconds) {
          val state = imageFlow.first { it.hasLocal && it.hasRemote }
          assertThat(state.images).isEmpty()
        }
      }
    }
  }

  @Test
  fun systemImageFlow_local() {
    with(SdkFixture()) {
      setLocalPackages(listOf(createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))))

      val imageFlow = ISystemImages.systemImageFlow(sdkHandler)
      runBlocking {
        withTimeout(10.seconds) {
          val state = imageFlow.first { it.hasLocal && it.hasRemote }
          assertThat(state.images).hasSize(1)
        }
      }
    }
  }

  @Test
  fun systemImageFlow_download() {
    with(SdkFixture()) {
      repoPackages.setRemotePkgInfos(listOf(createRemoteSystemImage("google_apis", listOf(), AndroidVersion(34))))

      val flowScope = CoroutineScope(EmptyCoroutineContext)
      val imageFlow = ISystemImages.systemImageFlow(sdkHandler).stateIn(flowScope, SharingStarted.Eagerly, SystemImageState.INITIAL)

      runBlocking {
        withTimeout(5.seconds) {
          // Initially we should see a remote system image
          val state = imageFlow.first { it.hasLocal && it.hasRemote }
          assertThat(state.images).hasSize(1)
          assertThat(state.images[0]).isInstanceOf(RemoteSystemImage::class.java)
        }

        // Simulate download of the system image
        repoManager.setLocalPackages(listOf(createLocalSystemImage("google_apis", listOf(), AndroidVersion(34))))

        withTimeout(5.seconds) {
          // Now the flow should update and it should be represented as a local system image
          val state = imageFlow.first { it.images.any { it is SystemImage } }
          assertThat(state.images).hasSize(1)
          assertThat(state.hasLocal).isTrue()
          assertThat(state.hasRemote).isTrue()
        }
      }
      flowScope.cancel()
    }
  }

  @Test
  fun imageWarnings_x86_64Host() {
    withEnvironment(linuxX86_64) {
      assertThat(createImage(Abi.X86_64.toString()).imageWarnings()).isEmpty()
      assertThat(createImage(Abi.X86.toString()).imageWarnings()).isEmpty()
      assertThat(createImage(Abi.ARM64_V8A.toString()).imageWarnings()).containsExactly("ARM images will run very slowly on x86 hosts.")
      assertThat(createImage(Abi.ARMEABI_V7A.toString()).imageWarnings()).containsExactly("ARM images will run very slowly on x86 hosts.")
      assertThat(createImage(Abi.ARMEABI.toString()).imageWarnings()).containsExactly("ARM images will run very slowly on x86 hosts.")
      assertThat(createImage("riscv64").imageWarnings()).containsExactly("Compatibility with riscv64 images is unknown.")
    }
  }

  @Test
  fun imageWarnings_macArmHost() {
    for (environment in listOf(macArm, macArmRosetta)) {
      withEnvironment(environment) {
        assertThat(createImage(Abi.ARM64_V8A.toString()).imageWarnings()).isEmpty()
        assertThat(createImage(Abi.ARM64_V8A.toString(), listOf(SystemImageTags.PAGE_SIZE_16KB)).imageWarnings()).isEmpty()
        assertThat(createImage(Abi.ARMEABI_V7A.toString()).imageWarnings())
          .containsExactly("32-bit ARM images are not supported on Apple Silicon.")
        assertThat(createImage(Abi.ARMEABI.toString()).imageWarnings())
          .containsExactly("32-bit ARM images are not supported on Apple Silicon.")
        assertThat(createImage(Abi.X86_64.toString()).imageWarnings()).containsExactly("x86_64 images are not supported on ARM hosts.")
        assertThat(createImage(Abi.X86.toString()).imageWarnings()).containsExactly("x86 images are not supported on ARM hosts.")
        assertThat(createImage("riscv64").imageWarnings()).containsExactly("Compatibility with riscv64 images is unknown.")
      }
    }
  }

  @Test
  fun imageWarnings_windowsArmHost() {
    for (environment in listOf(windowsArm, windowsX86OnArm)) {
      withEnvironment(environment) {
        assertThat(createImage(Abi.ARM64_V8A.toString()).imageWarnings()).isEmpty()
        assertThat(createImage(Abi.ARM64_V8A.toString(), listOf(SystemImageTags.PAGE_SIZE_16KB)).imageWarnings())
          .containsExactly("Windows on ARM does not support images with 16 KB page size.")
        assertThat(createImage(Abi.ARMEABI_V7A.toString()).imageWarnings())
          .containsExactly("Compatibility with armeabi-v7a images is unknown.")
        assertThat(createImage(Abi.X86_64.toString()).imageWarnings()).containsExactly("x86_64 images are not supported on ARM hosts.")
        assertThat(createImage(Abi.X86.toString()).imageWarnings()).containsExactly("x86 images are not supported on ARM hosts.")
      }
    }
  }

  @Test
  fun imageWarnings_32BitHost() {
    withEnvironment(windowsX86) {
      assertThat(createImage(Abi.X86.toString()).imageWarnings()).containsExactly("The Android Emulator requires a 64-bit host.")
    }
  }

  @Test
  fun imageWarnings_atd() {
    withEnvironment(linuxX86_64) {
      assertThat(createImage(Abi.X86_64.toString(), listOf(SystemImageTags.AOSP_ATD_TAG)).imageWarnings())
        .containsExactly("Automated Test Device (ATD) images are intended for headless testing only.")
      assertThat(createImage(Abi.ARM64_V8A.toString(), listOf(SystemImageTags.GOOGLE_ATD_TAG)).imageWarnings())
        .containsExactly(
          "ARM images will run very slowly on x86 hosts.",
          "Automated Test Device (ATD) images are intended for headless testing only.",
        )
    }
  }

  private fun createImage(abi: String, tags: List<IdDisplay> = emptyList()): ISystemImage =
    RemoteSystemImage(SdkFixture().createRemoteSystemImage("google_apis", tags, AndroidVersion(36), abi = abi))

  private fun environment(
    osName: String,
    osArch: String,
    isRosetta: Boolean = false,
    isWindowsArm64: Boolean = false,
  ) =
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

  private val linuxX86_64
    get() = environment(osName = "Linux", osArch = "x86_64")

  private val macArm
    get() = environment(osName = "Mac OS X", osArch = "aarch64")

  private val macArmRosetta
    get() = environment(osName = "Mac OS X", osArch = "x86_64", isRosetta = true)

  private val windowsArm
    get() = environment(osName = "Windows 11", osArch = "aarch64")

  private val windowsX86OnArm
    get() = environment(osName = "Windows 11", osArch = "x86_64", isWindowsArm64 = true)

  private val windowsX86
    get() = environment(osName = "Windows 10", osArch = "x86")

  private fun withEnvironment(
    environment: Environment,
    block: () -> Unit,
  ) {
    Environment.initialize(environment)
    block()
  }
}
