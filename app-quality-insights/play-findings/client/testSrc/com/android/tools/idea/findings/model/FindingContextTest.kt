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
package com.android.tools.idea.findings.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FindingContextTest {

  private fun device(brand: String = "", model: String = "", version: String = "", sdk: Int? = null) =
    DeviceInfo(deviceBrandName = brand, deviceModel = model, androidVersion = version, androidSdkVersion = sdk)

  @Test
  fun testDeviceInfoDisplayName() {
    assertThat(device(brand = "samsung", model = "sm-f946b").displayName).isEqualTo("samsung sm-f946b")
    assertThat(device(brand = " Google ", model = " Pixel Fold ").displayName).isEqualTo("Google Pixel Fold")

    // Only one of brand and model is present.
    assertThat(device(brand = "Google").displayName).isEqualTo("Google")
    assertThat(device(model = "Pixel Fold").displayName).isEqualTo("Pixel Fold")
    assertThat(device().displayName).isEmpty()

    // The brand is not repeated when the model already starts with it (ignoring case) or equals it.
    assertThat(device(brand = "google", model = "Google Pixel Fold").displayName).isEqualTo("Google Pixel Fold")
    assertThat(device(brand = "Nothing", model = "nothing").displayName).isEqualTo("nothing")
    // A model that only shares a prefix with the brand, without a word boundary, keeps the brand.
    assertThat(device(brand = "Google", model = "GooglePixel").displayName).isEqualTo("Google GooglePixel")
  }

  @Test
  fun testDeviceInfoAndroidVersionDisplayText() {
    assertThat(device(version = "14", sdk = 34).androidVersionDisplayText).isEqualTo("Android 14 (API 34)")
    assertThat(device(version = " 14 ").androidVersionDisplayText).isEqualTo("Android 14")
    assertThat(device(sdk = 34).androidVersionDisplayText).isEqualTo("API 34")
    assertThat(device().androidVersionDisplayText).isEmpty()

    // A non-positive SDK level is treated as unknown.
    assertThat(device(version = "14", sdk = 0).androidVersionDisplayText).isEqualTo("Android 14")
    assertThat(device(sdk = 0).androidVersionDisplayText).isEmpty()
  }
}
