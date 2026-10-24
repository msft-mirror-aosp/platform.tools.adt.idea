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
import com.android.utils.CpuArchitecture
import com.android.utils.OperatingSystem

/** Whether Studio requires Emulator (Preview), the qemu-next emulator in the emulators;latest SDK package. */
object QemuNext {
  /**
   * True if [StudioFlags.EMULATOR_PREVIEW_REQUIRED] is on and Emulator (Preview) is published for this host. Callers still decide which
   * AVDs need it.
   */
  @JvmStatic fun isRequired(): Boolean = StudioFlags.EMULATOR_PREVIEW_REQUIRED.get() && isAvailableOnHost()

  /**
   * The SDK repository has no emulators;latest archive for Intel Macs (b/577484463), so they keep using the emulator package. Every other
   * host counts as available, Windows and Linux on ARM included.
   *
   * An x86_64 Studio running on Apple Silicon is translated to ARM and reports [CpuArchitecture.X86_ON_ARM], so it counts as ARM here, as
   * it does when the SDK manager picks archives to download. Intel Macs never report X86_ON_ARM.
   */
  private fun isAvailableOnHost(): Boolean = !(OperatingSystem.isMac() && CpuArchitecture.osArchitecture == CpuArchitecture.X86_64)
}
