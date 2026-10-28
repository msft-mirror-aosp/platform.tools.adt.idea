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

import com.google.play.androidpublisher.v3.Finding as ProtoFinding
import com.google.play.androidpublisher.v3.FindingData as ProtoFindingData
import com.google.play.androidpublisher.v3.LargeScreensLayoutFindingData.LayoutIssueType
import com.google.play.androidpublisher.v3.VitalsDeviceBadBehaviorFindingData
import java.util.Locale

private fun formatVitalsMetric(metric: String): String =
  when (metric) {
    "userPerceivedCrashRate" -> "User-perceived crash rate"
    "userPerceivedAnrRate" -> "User-perceived ANR rate"
    else -> metric
  }

/** Returns a human-readable display name for the given [type] and optional [data]. */
internal fun getFindingDisplayName(type: ProtoFinding.Type, data: ProtoFindingData? = null): String {
  return when (type) {
    ProtoFinding.Type.DRM_APP_COMPAT -> "Bad version of your app protection service"
    ProtoFinding.Type.EDGE_TO_EDGE_COMPAT -> "Edge-to-edge display not supported"
    ProtoFinding.Type.VITALS_ANOMALY -> {
      val metric = data?.takeIf { it.hasVitalsAnomaly() }?.vitalsAnomaly?.metric?.let(::formatVitalsMetric)?.takeIf { it.isNotBlank() }
      if (metric != null) "Vitals anomaly detected: $metric" else "Vitals anomaly detected"
    }
    ProtoFinding.Type.VITALS_BAD_BEHAVIOR -> {
      val metric =
        data?.takeIf { it.hasVitalsBadBehavior() }?.vitalsBadBehavior?.metric?.let(::formatVitalsMetric)?.takeIf { it.isNotBlank() }
      if (metric != null) "Vitals bad behavior threshold exceeded: $metric" else "Vitals bad behavior threshold exceeded"
    }
    ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR -> {
      val metric =
        data
          ?.takeIf { it.hasVitalsDeviceBadBehavior() }
          ?.vitalsDeviceBadBehavior
          ?.metric
          ?.let(::formatVitalsMetric)
          ?.takeIf { it.isNotBlank() }
      if (metric != null) "Per-device vitals bad behavior: $metric" else "Per-device vitals bad behavior"
    }
    ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION -> "Some crashes or ANRs have a note from an SDK provider"
    ProtoFinding.Type.LARGE_SCREENS_LAYOUT -> {
      val issueType = data?.takeIf { it.hasLargeScreensLayout() }?.largeScreensLayout?.layoutIssueType
      when (issueType) {
        LayoutIssueType.DOES_NOT_USE_ENTIRE_SCREEN -> "App does not use entire screen"
        LayoutIssueType.CONTENT_CUT_OFF_ON_SCREEN -> "Content cut off on screen"
        LayoutIssueType.OVERLAPPING -> "Overlapping UI elements"
        null,
        LayoutIssueType.LAYOUT_ISSUE_TYPE_UNSPECIFIED,
        LayoutIssueType.UNRECOGNIZED -> "Large screens layout issue"
        else -> "Large screens layout issue: ${issueType.name}"
      }
    }
    ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED -> "Camera preview stretched on large screens"
    ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS -> "Camera preview sideways on large screens"
    ProtoFinding.Type.LARGE_SCREENS_CONTINUITY -> "Large screen continuity issue"
    ProtoFinding.Type.LARGE_SCREENS_CRASH -> "Large screen crash on configuration change"
    ProtoFinding.Type.TYPE_UNSPECIFIED,
    ProtoFinding.Type.UNRECOGNIZED -> "Unknown Finding"
  }
}

/** Generates user-facing description and recommendations for a finding based on its [type] and optional [data]. */
internal fun getFindingDescription(type: ProtoFinding.Type, data: ProtoFindingData? = null): String? {
  return when (type) {
    ProtoFinding.Type.DRM_APP_COMPAT -> {
      data
        ?.takeIf { it.hasDrmAppCompat() }
        ?.drmAppCompat
        ?.run { // TODO(b/517599039): update wording
          val recommended = recommendedDrmVersion.takeIf { it.isNotEmpty() } ?: "N/A"
          "Version $drmVersion of the $drmPackageName app protection service has" +
            " compatibility issues that are known to cause your app to crash or become non-responsive" +
            " (ANR). Protect your app with version $recommended or later, or" +
            " get in touch with your app protection service provider, $drmDisplayName."
        }
    }

    ProtoFinding.Type.EDGE_TO_EDGE_COMPAT -> {
      val customMsg = data?.takeIf { it.hasEdgeToEdgeCompat() }?.edgeToEdgeCompat?.reviewMessage?.takeIf { it.isNotEmpty() }
      customMsg
        ?: "Your app does not support edge-to-edge display properly. Update your app to target Android 15 (API level 35) or higher and configure window insets to draw content edge-to-edge."
    }

    ProtoFinding.Type.VITALS_ANOMALY -> {
      data
        ?.takeIf { it.hasVitalsAnomaly() }
        ?.vitalsAnomaly
        ?.let { anomaly ->
          val metricName = formatVitalsMetric(anomaly.metric).takeIf { it.isNotBlank() } ?: return@let null
          "An anomalous spike in $metricName was detected (${String.format(Locale.US, "%.1f", anomaly.metricValue * 100)}%). Investigate recent releases or configuration changes affecting user experience."
        } ?: "An anomalous vitals spike was detected for this release."
    }

    ProtoFinding.Type.VITALS_BAD_BEHAVIOR -> {
      data
        ?.takeIf { it.hasVitalsBadBehavior() }
        ?.vitalsBadBehavior
        ?.let { badBehavior ->
          val metricName = formatVitalsMetric(badBehavior.metric).takeIf { it.isNotBlank() } ?: return@let null
          "The metric $metricName exceeded the bad behavior threshold of ${String.format(Locale.US, "%.2f", badBehavior.threshold * 100)}% (actual: ${String.format(Locale.US, "%.2f", badBehavior.metricValue * 100)}%). This negatively impacts discovery and user ratings on Google Play."
        } ?: "App vitals exceed the overall bad behavior threshold on Google Play."
    }

    ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR -> {
      data
        ?.takeIf { it.hasVitalsDeviceBadBehavior() }
        ?.vitalsDeviceBadBehavior
        ?.let { deviceBadBehavior ->
          val metricName = formatVitalsMetric(deviceBadBehavior.metric).takeIf { it.isNotBlank() } ?: return@let null
          val isEmerging = deviceBadBehavior.badBehaviorType == VitalsDeviceBadBehaviorFindingData.Type.EMERGING_BAD_BEHAVIOR
          "${deviceBadBehavior.affectedDeviceModelCount} device model(s) exceed the ${if (isEmerging) "emerging " else ""}bad behavior threshold for $metricName. Consider testing on affected device configurations."
        } ?: "App vitals exceed the bad behavior threshold on specific device models."
    }

    ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION -> {
      "User-perceived crashes or ANRs are occurring in a production version of your app. One of your SDK providers has additional information about this issue to help you fix it."
    }

    ProtoFinding.Type.LARGE_SCREENS_LAYOUT -> {
      val issueType = data?.takeIf { it.hasLargeScreensLayout() }?.largeScreensLayout?.layoutIssueType
      val issueDesc =
        when (issueType) {
          LayoutIssueType.DOES_NOT_USE_ENTIRE_SCREEN -> "App does not use entire screen (letterboxed / pillarboxed)."
          LayoutIssueType.CONTENT_CUT_OFF_ON_SCREEN -> "Content cut off on screen."
          LayoutIssueType.OVERLAPPING -> "Overlapping UI elements detected."
          null,
          LayoutIssueType.LAYOUT_ISSUE_TYPE_UNSPECIFIED,
          LayoutIssueType.UNRECOGNIZED -> "Layout issues detected on large screen devices."
          else -> "UI layout issue detected on large screens: ${issueType.name}."
        }
      "$issueDesc Optimize your layouts for tablets, foldables, and desktop devices."
    }

    ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED -> {
      "Camera preview appears stretched or distorted on large screens or foldables. Use CameraX or configure proper aspect ratio handling for multi-window and large screen environments."
    }

    ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS -> {
      "Camera preview is oriented sideways or rotated incorrectly on large screen devices. Ensure your camera display rotation accounts for device and display orientation."
    }

    ProtoFinding.Type.LARGE_SCREENS_CONTINUITY -> {
      "State loss or continuity issue detected on fold/unfold or screen resizing. Preserve UI and app state across configuration changes and display resizing."
    }

    ProtoFinding.Type.LARGE_SCREENS_CRASH -> {
      val stackTrace = data?.takeIf { it.hasLargeScreensCrash() }?.largeScreensCrash?.stackTrace
      val exceptionLine = stackTrace?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
      if (exceptionLine != null) {
        "App crashed during a configuration change: $exceptionLine. Handle configuration changes or avoid lifecycle destruction issues."
      } else {
        "App crashed during a configuration change (such as folding, unfolding, or resizing on large screen devices). Handle configuration changes or avoid lifecycle destruction issues."
      }
    }

    ProtoFinding.Type.TYPE_UNSPECIFIED,
    ProtoFinding.Type.UNRECOGNIZED -> null
  }
}
