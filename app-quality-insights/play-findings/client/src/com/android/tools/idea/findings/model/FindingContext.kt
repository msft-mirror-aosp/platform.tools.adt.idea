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
 * Groups type-specific attachments and metadata (such as crash stack traces, screenshots, and device info) without requiring a separate
 * subclass per finding type.
 */
// TODO(b/517599039): Add a generic learnMoreUrl field once the details panel UI supports rendering a "Learn more" link.
data class FindingContext(
  val visualEvidences: List<VisualEvidence> = emptyList(),
  val stackTrace: StackTrace? = null,
  val deviceInfo: DeviceInfo? = null,
) {
  companion object {
    val EMPTY = FindingContext()
  }
}

/** Visual evidence (such as a screenshot) associated with a finding. */
data class VisualEvidence(val url: String)

/** Crash stack trace associated with a finding. */
data class StackTrace(val raw: String)

/** Device information associated with a finding. */
data class DeviceInfo(
  val deviceBrandName: String,
  val deviceModel: String,
  val androidVersion: String,
  val androidSdkVersion: Int,
)
