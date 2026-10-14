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

/** Categories of Play Console Findings to group technical quality issues and user experience guidelines. */
enum class FindingCategory(val displayName: String) {
  TECHNICAL_QUALITY("Technical quality"),
  USER_EXPERIENCE("User experience"),
  OTHER("Other"),
}

/** Maps a [FindingType] to its corresponding high-level [FindingCategory]. */
fun FindingType.toFindingCategory(): FindingCategory {
  return when (this) {
    FindingType.DRM_APP_COMPAT,
    FindingType.VITALS_ANOMALY,
    FindingType.VITALS_BAD_BEHAVIOR,
    FindingType.VITALS_DEVICE_BAD_BEHAVIOR,
    FindingType.SDK_HAS_CRASH_ANNOTATION,
    FindingType.LARGE_SCREENS_CRASH -> FindingCategory.TECHNICAL_QUALITY

    FindingType.EDGE_TO_EDGE_COMPAT,
    FindingType.LARGE_SCREENS_LAYOUT,
    FindingType.LARGE_SCREENS_CAMERA_STRETCHED,
    FindingType.LARGE_SCREENS_CAMERA_SIDEWAYS,
    FindingType.LARGE_SCREENS_CONTINUITY -> FindingCategory.USER_EXPERIENCE

    FindingType.UNKNOWN -> FindingCategory.OTHER
  }
}
