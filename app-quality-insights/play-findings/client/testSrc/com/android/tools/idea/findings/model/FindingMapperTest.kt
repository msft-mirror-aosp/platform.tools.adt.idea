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

class FindingMapperTest {

  @Test
  fun testDrmAppCompatDisplayNameAndDetails() {
    val finding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/1",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.bad.sdk",
            drmDisplayName = "Bad SDK Provider",
            drmVersion = "1.0.0",
            recommendedDrmVersion = "1.1.0",
            learnMoreUrl = "https://example.com/learn-more",
          ),
      )

    assertThat(finding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(finding.displayName).isEqualTo("Bad version of your app protection service")
    assertThat(finding.details)
      .isEqualTo(
        "Version 1.0.0 of the com.bad.sdk app protection service has compatibility issues that are known to cause your app to crash or become non-responsive (ANR). Protect your app with version 1.1.0 or later, or get in touch with your app protection service provider, Bad SDK Provider."
      )
  }

  @Test
  fun testEdgeToEdgeDisplayNameAndDetails() {
    val customFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/2",
        type = FindingType.EDGE_TO_EDGE_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData = FindingData.EdgeToEdge(manualReviewMessage = "Custom review note", versionCode = "101"),
      )
    assertThat(customFinding.category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(customFinding.displayName).isEqualTo("Edge-to-edge display not supported")
    assertThat(customFinding.details).isEqualTo("Custom review note")

    val defaultFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/3",
        type = FindingType.EDGE_TO_EDGE_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData = FindingData.EdgeToEdge(manualReviewMessage = "", versionCode = "101"),
      )
    assertThat(defaultFinding.details)
      .isEqualTo(
        "Your app does not support edge-to-edge display properly. Update your app to target Android 15 (API level 35) or higher and configure window insets to draw content edge-to-edge."
      )
  }

  @Test
  fun testVitalsAndSdkCrashAnnotationDetails() {
    val vitalsFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/4",
        type = FindingType.VITALS_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.VitalsBadBehavior(
            metric = VitalsMetric.UserPerceivedCrashRate,
            metricValue = 0.025,
            threshold = 0.0109,
          ),
      )
    assertThat(vitalsFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(vitalsFinding.displayName).isEqualTo("Vitals bad behavior threshold exceeded: User-perceived crash rate")
    assertThat(vitalsFinding.details)
      .isEqualTo(
        "The metric User-perceived crash rate exceeded the bad behavior threshold of 1.09% (actual: 2.50%). This negatively impacts discovery and user ratings on Google Play."
      )

    val vitalsAnomalyFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/4a",
        type = FindingType.VITALS_ANOMALY,
        severity = FindingSeverity.INFO,
        findingData =
          FindingData.VitalsAnomaly(
            metric = VitalsMetric.UserPerceivedAnrRate,
            metricValue = 0.042,
            isHourly = true,
          ),
      )
    assertThat(vitalsAnomalyFinding.displayName).isEqualTo("Vitals anomaly detected: User-perceived ANR rate")

    val vitalsDeviceFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/4b",
        type = FindingType.VITALS_DEVICE_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.VitalsDeviceBadBehavior(
            metric = VitalsMetric.Unknown("otherVitalsMetric"),
            threshold = 0.08,
            affectedDeviceModelsCount = 3,
          ),
      )
    assertThat(vitalsDeviceFinding.displayName).isEqualTo("Per-device vitals bad behavior: otherVitalsMetric")
    assertThat(VitalsMetric.UserPerceivedCrashRate.raw).isEqualTo("userPerceivedCrashRate")
    assertThat(VitalsMetric.UserPerceivedAnrRate.raw).isEqualTo("userPerceivedAnrRate")
    assertThat(VitalsMetric.Unknown("otherVitalsMetric").raw).isEqualTo("otherVitalsMetric")

    val sdkAnnotationFinding =
      AppFinding.Supported(
        name = "applications/com.example.app/findings/5",
        type = FindingType.SDK_HAS_CRASH_ANNOTATION,
        severity = FindingSeverity.INFO,
        findingData = FindingData.Empty,
      )
    assertThat(sdkAnnotationFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(sdkAnnotationFinding.displayName).isEqualTo("Some crashes or ANRs have a note from an SDK provider")
    assertThat(sdkAnnotationFinding.details)
      .isEqualTo(
        "User-perceived crashes or ANRs are occurring in a production version of your app. One of your SDK providers has additional information about this issue to help you fix it."
      )
  }
}
