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
import com.google.play.androidpublisher.v3.DrmAppCompatFindingData
import com.google.play.androidpublisher.v3.EdgeToEdgeCompatFindingData
import com.google.play.androidpublisher.v3.Finding as ProtoFinding
import com.google.play.androidpublisher.v3.FindingData as ProtoFindingData
import com.google.play.androidpublisher.v3.LabCrashNoTelemetryFindingData
import com.google.play.androidpublisher.v3.LabCrashSixteenKbFindingData
import com.google.play.androidpublisher.v3.LargeScreensCrashFindingData
import com.google.play.androidpublisher.v3.LargeScreensLayoutFindingData
import com.google.play.androidpublisher.v3.VitalsAnomalyFindingData
import com.google.play.androidpublisher.v3.VitalsBadBehaviorFindingData
import com.google.play.androidpublisher.v3.VitalsDeviceBadBehaviorFindingData
import org.junit.Test

class FindingMapperTest {

  @Test
  fun testDrmAppCompatDisplayNameAndDescription() {
    val protoData =
      ProtoFindingData.newBuilder()
        .setDrmAppCompat(
          DrmAppCompatFindingData.newBuilder()
            .setDrmPackageName("com.bad.sdk")
            .setDrmDisplayName("Bad SDK Provider")
            .setDrmVersion("1.0.0")
            .setRecommendedDrmVersion("1.1.0")
            .setLearnMoreUrl("https://example.com/learn-more")
        )
        .build()
    val finding =
      AppFinding(
        name = "applications/com.example.app/findings/1",
        category = ProtoFinding.Type.DRM_APP_COMPAT.toFindingCategory(),
        severity = FindingSeverity.WARNING,
        displayName = getFindingDisplayName(ProtoFinding.Type.DRM_APP_COMPAT, protoData),
        description = getFindingDescription(ProtoFinding.Type.DRM_APP_COMPAT, protoData) ?: "",
      )

    assertThat(finding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(finding.displayName).isEqualTo("Bad version of your app protection service")
    assertThat(finding.description)
      .isEqualTo(
        "Version 1.0.0 of the com.bad.sdk app protection service has compatibility issues that are known to cause your app to crash or become non-responsive (ANR). Protect your app with version 1.1.0 or later, or get in touch with your app protection service provider, Bad SDK Provider."
      )
  }

  @Test
  fun testEdgeToEdgeDisplayNameAndDescription() {
    val customData =
      ProtoFindingData.newBuilder()
        .setEdgeToEdgeCompat(EdgeToEdgeCompatFindingData.newBuilder().setReviewMessage("Custom review note"))
        .build()
    val customFinding =
      AppFinding(
        name = "applications/com.example.app/findings/2",
        category = ProtoFinding.Type.EDGE_TO_EDGE_COMPAT.toFindingCategory(),
        severity = FindingSeverity.WARNING,
        displayName = getFindingDisplayName(ProtoFinding.Type.EDGE_TO_EDGE_COMPAT, customData),
        description = getFindingDescription(ProtoFinding.Type.EDGE_TO_EDGE_COMPAT, customData) ?: "",
      )
    assertThat(customFinding.category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(customFinding.displayName).isEqualTo("Edge-to-edge display not supported")
    assertThat(customFinding.description).isEqualTo("Custom review note")

    assertThat(getFindingDescription(ProtoFinding.Type.EDGE_TO_EDGE_COMPAT))
      .isEqualTo(
        "Your app does not support edge-to-edge display properly. Update your app to target Android 15 (API level 35) or higher and configure window insets to draw content edge-to-edge."
      )
  }

  @Test
  fun testVitalsAndSdkCrashAnnotationDescription() {
    val vitalsBadBehaviorData =
      ProtoFindingData.newBuilder()
        .setVitalsBadBehavior(
          VitalsBadBehaviorFindingData.newBuilder().setMetric("userPerceivedCrashRate").setMetricValue(0.025).setThreshold(0.0109)
        )
        .build()
    val vitalsFinding =
      AppFinding(
        name = "applications/com.example.app/findings/4",
        category = ProtoFinding.Type.VITALS_BAD_BEHAVIOR.toFindingCategory(),
        severity = FindingSeverity.SEVERE,
        displayName = getFindingDisplayName(ProtoFinding.Type.VITALS_BAD_BEHAVIOR, vitalsBadBehaviorData),
        description = getFindingDescription(ProtoFinding.Type.VITALS_BAD_BEHAVIOR, vitalsBadBehaviorData) ?: "",
      )
    assertThat(vitalsFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(vitalsFinding.displayName).isEqualTo("Vitals bad behavior threshold exceeded: User-perceived crash rate")
    assertThat(vitalsFinding.description)
      .isEqualTo(
        "The metric User-perceived crash rate exceeded the bad behavior threshold of 1.09% (actual: 2.50%). This negatively impacts discovery and user ratings on Google Play."
      )

    val vitalsAnomalyData =
      ProtoFindingData.newBuilder()
        .setVitalsAnomaly(VitalsAnomalyFindingData.newBuilder().setMetric("userPerceivedAnrRate").setMetricValue(0.042))
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.VITALS_ANOMALY, vitalsAnomalyData))
      .isEqualTo("Vitals anomaly detected: User-perceived ANR rate")

    val vitalsDeviceData =
      ProtoFindingData.newBuilder()
        .setVitalsDeviceBadBehavior(
          VitalsDeviceBadBehaviorFindingData.newBuilder().setMetric("otherVitalsMetric").setThreshold(0.08).setAffectedDeviceModelCount(3)
        )
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR, vitalsDeviceData))
      .isEqualTo("Per-device vitals bad behavior: otherVitalsMetric")

    val sdkAnnotationFinding =
      AppFinding(
        name = "applications/com.example.app/findings/5",
        category = ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION.toFindingCategory(),
        severity = FindingSeverity.INFO,
        displayName = getFindingDisplayName(ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION),
        description = getFindingDescription(ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION) ?: "",
      )
    assertThat(sdkAnnotationFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(sdkAnnotationFinding.displayName).isEqualTo("Some crashes or ANRs have a note from an SDK provider")
    assertThat(sdkAnnotationFinding.description)
      .isEqualTo(
        "User-perceived crashes or ANRs are occurring in a production version of your app. One of your SDK providers has additional information about this issue to help you fix it."
      )
  }

  @Test
  fun testLargeScreensDisplayNameAndDescription() {
    val layoutData =
      ProtoFindingData.newBuilder()
        .setLargeScreensLayout(
          LargeScreensLayoutFindingData.newBuilder()
            .setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.DOES_NOT_USE_ENTIRE_SCREEN)
        )
        .build()
    val layoutFinding =
      AppFinding(
        name = "applications/com.example.app/findings/6",
        category = ProtoFinding.Type.LARGE_SCREENS_LAYOUT.toFindingCategory(),
        severity = FindingSeverity.WARNING,
        displayName = getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, layoutData),
        description = getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, layoutData) ?: "",
      )
    assertThat(layoutFinding.category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(layoutFinding.displayName).isEqualTo("App does not use entire screen")
    assertThat(layoutFinding.description)
      .isEqualTo("App content does not fill the available screen area. Optimize your layouts for tablets, foldables, and desktop devices.")

    val cutOffData =
      ProtoFindingData.newBuilder()
        .setLargeScreensLayout(
          LargeScreensLayoutFindingData.newBuilder()
            .setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.CONTENT_CUT_OFF_ON_SCREEN)
        )
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, cutOffData)).isEqualTo("Content is cut off on screen")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, cutOffData))
      .isEqualTo(
        "Text or UI elements are cut off or clipped at screen edges. Optimize your layouts for tablets, foldables, and desktop devices."
      )

    val overlappingData =
      ProtoFindingData.newBuilder()
        .setLargeScreensLayout(
          LargeScreensLayoutFindingData.newBuilder().setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.OVERLAPPING)
        )
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, overlappingData)).isEqualTo("UI components overlap each other")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, overlappingData))
      .isEqualTo("UI elements or text overlap with each other. Optimize your layouts for tablets, foldables, and desktop devices.")

    val excessiveWhiteSpaceData =
      ProtoFindingData.newBuilder()
        .setLargeScreensLayout(
          LargeScreensLayoutFindingData.newBuilder().setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.EXCESSIVE_WHITE_SPACE)
        )
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, excessiveWhiteSpaceData))
      .isEqualTo("Excessive empty or unused space")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, excessiveWhiteSpaceData))
      .isEqualTo("Layout contains excessive empty or unused space. Optimize your layouts for tablets, foldables, and desktop devices.")

    val edgeToEdgeData =
      ProtoFindingData.newBuilder()
        .setLargeScreensLayout(
          LargeScreensLayoutFindingData.newBuilder().setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.EDGE_TO_EDGE)
        )
        .build()
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, edgeToEdgeData))
      .isEqualTo("Content or controls overlap system bars or display cutouts")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT, edgeToEdgeData))
      .isEqualTo(
        "Content or controls overlap system bars, display cutouts, or navigation bars. Optimize your layouts for tablets, foldables, and desktop devices."
      )

    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_LAYOUT)).isEqualTo("Large screens layout issue")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_LAYOUT))
      .isEqualTo("Layout issues detected on large screen devices. Optimize your layouts for tablets, foldables, and desktop devices.")

    assertThat(ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED.toFindingCategory()).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED))
      .isEqualTo("Camera preview stretched on large screens")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED))
      .isEqualTo(
        "Camera preview appears stretched or distorted on large screens or foldables. Use CameraX or configure proper aspect ratio handling for multi-window and large screen environments."
      )

    assertThat(ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS.toFindingCategory()).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS)).isEqualTo("Camera preview sideways on large screens")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS))
      .isEqualTo(
        "Camera preview is oriented sideways or rotated incorrectly on large screen devices. Ensure your camera display rotation accounts for device and display orientation."
      )

    assertThat(ProtoFinding.Type.LARGE_SCREENS_CONTINUITY.toFindingCategory()).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_CONTINUITY)).isEqualTo("Large screen continuity issue")
    assertThat(getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_CONTINUITY))
      .isEqualTo(
        "State loss or continuity issue detected on fold/unfold or screen resizing. Preserve UI and app state across configuration changes and display resizing."
      )

    val crashData =
      ProtoFindingData.newBuilder()
        .setLargeScreensCrash(
          LargeScreensCrashFindingData.newBuilder()
            .addEvents(
              LargeScreensCrashFindingData.CrashEvent.newBuilder()
                .setStackTrace("java.lang.NullPointerException: Missing view\n\tat Foo.bar(Foo.kt:10)")
            )
        )
        .build()
    val crashFinding =
      AppFinding(
        name = "applications/com.example.app/findings/7",
        category = ProtoFinding.Type.LARGE_SCREENS_CRASH.toFindingCategory(),
        severity = FindingSeverity.SEVERE,
        displayName = getFindingDisplayName(ProtoFinding.Type.LARGE_SCREENS_CRASH, crashData),
        description = getFindingDescription(ProtoFinding.Type.LARGE_SCREENS_CRASH, crashData) ?: "",
      )
    assertThat(crashFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(crashFinding.displayName).isEqualTo("Large screen crash on configuration change")
    assertThat(crashFinding.description)
      .isEqualTo(
        "App crashed during a configuration change: java.lang.NullPointerException: Missing view. Handle configuration changes or avoid lifecycle destruction issues."
      )
  }

  @Test
  fun testLabCrashDisplayNameAndDescription() {
    val noTelemetryData =
      ProtoFindingData.newBuilder()
        .setLabCrashNoTelemetry(LabCrashNoTelemetryFindingData.newBuilder().setErrorMessage("java.lang.NullPointerException: crash in lab"))
        .build()
    val noTelemetryFinding =
      AppFinding(
        name = "applications/com.example.app/findings/8",
        category = ProtoFinding.Type.LAB_CRASH_NO_TELEMETRY.toFindingCategory(),
        severity = FindingSeverity.SEVERE,
        displayName = getFindingDisplayName(ProtoFinding.Type.LAB_CRASH_NO_TELEMETRY, noTelemetryData),
        description = getFindingDescription(ProtoFinding.Type.LAB_CRASH_NO_TELEMETRY, noTelemetryData) ?: "",
      )
    assertThat(noTelemetryFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(noTelemetryFinding.displayName).isEqualTo("Lab crash detected")
    assertThat(noTelemetryFinding.description)
      .isEqualTo("Lab tests identified a crash without telemetry: java.lang.NullPointerException: crash in lab.")

    val sixteenKbData =
      ProtoFindingData.newBuilder()
        .setLabCrashSixteenKb(LabCrashSixteenKbFindingData.newBuilder().setErrorMessage("dlopen failed: alignment error"))
        .build()
    val sixteenKbFinding =
      AppFinding(
        name = "applications/com.example.app/findings/9",
        category = ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB.toFindingCategory(),
        severity = FindingSeverity.BLOCKING,
        displayName = getFindingDisplayName(ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB, sixteenKbData),
        description = getFindingDescription(ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB, sixteenKbData) ?: "",
      )
    assertThat(sixteenKbFinding.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(sixteenKbFinding.displayName).isEqualTo("16 KB page-size compatibility crash")
    assertThat(sixteenKbFinding.description)
      .isEqualTo(
        "Lab test failed on 16 KB page size device: dlopen failed: alignment error. Recompile native libraries with 16 KB alignment support."
      )
  }
}
