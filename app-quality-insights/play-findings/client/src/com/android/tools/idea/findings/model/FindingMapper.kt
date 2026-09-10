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

import java.util.Locale

// Multiple FindingTypes can share a FindingData subtype (or use FindingData.Empty), while each
// FindingType expects at most one FindingData subtype. Branching on FindingType keeps the text and
// fallback for each finding in one place.

/** Returns a human-readable display name for the given [type] and optional [data]. */
fun getFindingDisplayName(type: FindingType, data: FindingData? = null): String {
  return when (type) {
    FindingType.DRM_APP_COMPAT -> "Bad version of your app protection service"
    FindingType.EDGE_TO_EDGE_COMPAT -> "Edge-to-edge display not supported"
    FindingType.VITALS_ANOMALY -> {
      val metric = (data as? FindingData.VitalsAnomaly)?.metric?.displayName?.takeIf { it.isNotBlank() }
      if (metric != null) "Vitals anomaly detected: $metric" else "Vitals anomaly detected"
    }
    FindingType.VITALS_BAD_BEHAVIOR -> {
      val metric = (data as? FindingData.VitalsBadBehavior)?.metric?.displayName?.takeIf { it.isNotBlank() }
      if (metric != null) "Vitals bad behavior threshold exceeded: $metric" else "Vitals bad behavior threshold exceeded"
    }
    FindingType.VITALS_DEVICE_BAD_BEHAVIOR -> {
      val metric = (data as? FindingData.VitalsDeviceBadBehavior)?.metric?.displayName?.takeIf { it.isNotBlank() }
      if (metric != null) "Per-device vitals bad behavior: $metric" else "Per-device vitals bad behavior"
    }
    FindingType.SDK_HAS_CRASH_ANNOTATION -> "Some crashes or ANRs have a note from an SDK provider"
    FindingType.UNKNOWN -> "Unknown Finding"
  }
}

/** Generates user-facing details and recommendations for a finding based on its [type] and [data]. */
fun getFindingDetails(type: FindingType, data: FindingData): String? {
  return when (type) {
    FindingType.DRM_APP_COMPAT -> {
      (data as? FindingData.DrmAppCompat)?.run { // TODO(b/517599039): update wording
        "Version $drmVersion of the $drmPackageName app protection service has" +
          " compatibility issues that are known to cause your app to crash or become non-responsive" +
          " (ANR). Protect your app with version ${recommendedDrmVersion ?: "N/A"} or later, or" +
          " get in touch with your app protection service provider, $drmDisplayName."
      }
    }

    FindingType.EDGE_TO_EDGE_COMPAT -> {
      val customMsg = (data as? FindingData.EdgeToEdge)?.manualReviewMessage?.takeIf { it.isNotEmpty() }
      customMsg
        ?: "Your app does not support edge-to-edge display properly. Update your app to target Android 15 (API level 35) or higher and configure window insets to draw content edge-to-edge."
    }

    FindingType.VITALS_ANOMALY -> {
      (data as? FindingData.VitalsAnomaly)
        ?.takeIf { it.metric.displayName.isNotBlank() }
        ?.run {
          "An anomalous spike in ${metric.displayName} was detected (${String.format(Locale.US, "%.1f", metricValue * 100)}%). Investigate recent releases or configuration changes affecting user experience."
        } ?: "An anomalous vitals spike was detected for this release."
    }

    FindingType.VITALS_BAD_BEHAVIOR -> {
      (data as? FindingData.VitalsBadBehavior)
        ?.takeIf { it.metric.displayName.isNotBlank() }
        ?.run {
          "The metric ${metric.displayName} exceeded the bad behavior threshold of ${String.format(Locale.US, "%.2f", threshold * 100)}% (actual: ${String.format(Locale.US, "%.2f", metricValue * 100)}%). This negatively impacts discovery and user ratings on Google Play."
        } ?: "App vitals exceed the overall bad behavior threshold on Google Play."
    }

    FindingType.VITALS_DEVICE_BAD_BEHAVIOR -> {
      (data as? FindingData.VitalsDeviceBadBehavior)
        ?.takeIf { it.metric.displayName.isNotBlank() }
        ?.run {
          "$affectedDeviceModelsCount device model(s) exceed the ${if (isEmerging) "emerging " else ""}bad behavior threshold for ${metric.displayName}. Consider testing on affected device configurations."
        } ?: "App vitals exceed the bad behavior threshold on specific device models."
    }

    FindingType.SDK_HAS_CRASH_ANNOTATION ->
      "User-perceived crashes or ANRs are occurring in a production version of your app. One of your SDK providers has additional information about this issue to help you fix it."

    FindingType.UNKNOWN -> null
  }
}
