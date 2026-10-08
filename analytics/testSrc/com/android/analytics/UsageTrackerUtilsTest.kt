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
package com.android.analytics

import com.android.tools.analytics.isMdnsAutoConnectTls
import com.android.tools.analytics.isMdnsAutoConnectUnencrypted
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTrackerUtilsTest {

  @Test
  fun testMdnsConnectionIsFalseForPhysicalDeviceSerialNumber() {
    val serialNumber = "435DT06WH"
    assertFalse(isMdnsAutoConnectUnencrypted(serialNumber))
    assertFalse(isMdnsAutoConnectTls(serialNumber))
  }

  @Test
  fun testMdnsConnectionIsTrueForTlsSerialNumber() {
    val serialNumber = "adb-435DT06WH-vWgJpq._adb-tls-connect._tcp."
    assertFalse(isMdnsAutoConnectUnencrypted(serialNumber))
    assertTrue(isMdnsAutoConnectTls(serialNumber))
  }

  @Test
  fun testMdnsConnectionIsTrueForClearSerialNumber() {
    val serialNumber = "adb-435DT06WH-vWgJpq._adb._tcp"
    assertTrue(isMdnsAutoConnectUnencrypted(serialNumber))
    assertFalse(isMdnsAutoConnectTls(serialNumber))
  }

  @Test
  fun testIsMdnsAutoConnectTlsTrueForSerialNumberThatDoesntEndWithDot() {
    val serialNumber = "adb-86UX00F4R-cYuns7._adb-tls-connect._tcp"
    assertTrue(isMdnsAutoConnectTls(serialNumber))
  }
}
