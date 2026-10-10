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
import com.intellij.openapi.diagnostic.Logger

private val LOG = Logger.getInstance(FindingCategory::class.java)

/** Categories of Play Console Findings to group technical quality issues and user experience guidelines. */
enum class FindingCategory(val displayName: String) {
  TECHNICAL_QUALITY("Technical quality"),
  USER_EXPERIENCE("User experience"),
  OTHER("Other"),
}

/** Maps a Protobuf [ProtoFinding.Type] to its corresponding high-level [FindingCategory]. */
internal fun ProtoFinding.Type.toFindingCategory(): FindingCategory {
  return when (this) {
    ProtoFinding.Type.DRM_APP_COMPAT,
    ProtoFinding.Type.VITALS_ANOMALY,
    ProtoFinding.Type.VITALS_BAD_BEHAVIOR,
    ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR,
    ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION,
    ProtoFinding.Type.LAB_CRASH_NO_TELEMETRY,
    ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB,
    ProtoFinding.Type.LARGE_SCREENS_CRASH -> FindingCategory.TECHNICAL_QUALITY

    ProtoFinding.Type.EDGE_TO_EDGE_COMPAT,
    ProtoFinding.Type.LARGE_SCREENS_LAYOUT,
    ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED,
    ProtoFinding.Type.LARGE_SCREENS_CAMERA_SIDEWAYS,
    ProtoFinding.Type.LARGE_SCREENS_CONTINUITY -> FindingCategory.USER_EXPERIENCE

    ProtoFinding.Type.TYPE_UNSPECIFIED,
    ProtoFinding.Type.UNRECOGNIZED -> {
      LOG.warn("Encountered unknown finding type: $this")
      FindingCategory.OTHER
    }
  }
}
