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

/**
 * Optional diagnostic and UI context associated with an [AppFinding].
 *
 * Holds the observed [occurrences] of the finding. Each occurrence is one time the issue was observed on a specific device, with that
 * device's info and the evidence captured there: a crash stack trace, or screenshots (including multi-step continuity captures). Findings
 * without such evidence have no occurrences.
 */
// TODO(b/517599039): Add a generic learnMoreUrl field once the details panel UI supports rendering a "Learn more" link.
data class FindingContext(val occurrences: List<FindingOccurrence> = emptyList()) {
  companion object {
    val EMPTY = FindingContext()
  }
}

/**
 * A single observed occurrence of a finding (for example, on a specific test device), carrying its device metadata and any captured
 * diagnostic evidence.
 */
data class FindingOccurrence(
  val deviceInfo: DeviceInfo? = null,
  val visualEvidences: List<VisualEvidence> = emptyList(),
  val stackTrace: StackTrace? = null,
)

/**
 * Visual evidence (such as a screenshot and optional UI hierarchy dump) associated with a finding occurrence.
 *
 * @property url Signed URL of the screenshot image.
 * @property label Optional human-readable caption describing the capture (e.g., continuity step).
 * @property uiHierarchyUrl Optional download URL of the UI hierarchy XML dump captured with the screenshot.
 * @property highlighted Whether this capture should be highlighted (e.g., the continuity step where the issue was detected).
 */
data class VisualEvidence(
  val url: String,
  val label: String? = null,
  val uiHierarchyUrl: String? = null,
  val highlighted: Boolean = false,
)

/** Crash stack trace associated with a finding occurrence. */
data class StackTrace(val raw: String)

/** Device information associated with a finding occurrence. */
data class DeviceInfo(
  val deviceBrandName: String,
  val deviceModel: String,
  val androidVersion: String,
  val androidSdkVersion: Int? = null,
) {
  /**
   * Human-readable device name combining [deviceBrandName] and [deviceModel], omitting [deviceBrandName] if [deviceModel] already starts
   * with it, or empty string if neither is present.
   */
  val displayName: String
    get() {
      val brand = deviceBrandName.trim()
      val model = deviceModel.trim()
      return when {
        brand.isEmpty() -> model
        model.isEmpty() -> brand
        model.equals(brand, ignoreCase = true) || model.startsWith("$brand ", ignoreCase = true) -> model
        else -> "$brand $model"
      }
    }

  /** Human-readable Android version and API level (e.g. `"Android 14 (API 34)"`), or empty string if neither is present. */
  val androidVersionDisplayText: String
    get() {
      val version = androidVersion.trim()
      val sdk = androidSdkVersion?.takeIf { it > 0 }
      return when {
        version.isNotEmpty() && sdk != null -> "Android $version (API $sdk)"
        version.isNotEmpty() -> "Android $version"
        sdk != null -> "API $sdk"
        else -> ""
      }
    }
}
