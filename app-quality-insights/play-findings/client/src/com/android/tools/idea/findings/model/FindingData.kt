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
 * Base sealed interface for structured data associated with a finding.
 *
 * Each [FindingType] maps to at most one [FindingData] subtype: some finding types have no extra payload ([Empty]), and multiple
 * [FindingType]s may share the same [FindingData] subtype. If the server omits the expected payload, it falls back to [Empty].
 */
sealed interface FindingData {
  /**
   * Payload for an Edge-to-Edge compatibility issue.
   *
   * @property manualReviewMessage Optional message from manual review.
   * @property versionCode The affected version code, if known.
   * @property xmlFiles Layout XML files related to this finding.
   * @property invocations Code invocations related to this finding.
   */
  data class EdgeToEdge(
    val manualReviewMessage: String,
    val versionCode: String? = null,
    val xmlFiles: List<String> = emptyList(),
    val invocations: List<String> = emptyList(),
  ) : FindingData

  /**
   * Payload for a DRM (Digital Rights Management) SDK compatibility issue.
   *
   * Indicates that the app is using a version of an SDK that has known issues on Google Play.
   *
   * @property drmPackageName The package name of the affected SDK.
   * @property drmDisplayName The user-facing display name of the SDK.
   * @property drmVersion The version of the SDK currently used in the app.
   * @property recommendedDrmVersion The SDK version recommended by the provider to upgrade to, if available.
   * @property learnMoreUrl An optional URL containing more details about the SDK issue.
   */
  data class DrmAppCompat(
    val drmPackageName: String,
    val drmDisplayName: String,
    val drmVersion: String,
    val recommendedDrmVersion: String?,
    val learnMoreUrl: String?,
  ) : FindingData

  /** Payload for Vitals Anomaly findings. */
  data class VitalsAnomaly(
    val metric: VitalsMetric,
    val metricValue: Double,
    val isHourly: Boolean = false,
  ) : FindingData

  /** Payload for Vitals Bad Behavior findings. */
  data class VitalsBadBehavior(
    val metric: VitalsMetric,
    val threshold: Double,
    val metricValue: Double,
  ) : FindingData

  /** Payload for Vitals Device Bad Behavior findings. */
  data class VitalsDeviceBadBehavior(
    val metric: VitalsMetric,
    val threshold: Double,
    val affectedDeviceModelsCount: Int,
    val isEmerging: Boolean = false,
    val affectedInstallsRatio: Double? = null,
  ) : FindingData

  /** Payload for Large Screens findings (UI layout, camera preview issues, crashes on fold/resize, etc.). */
  data class LargeScreens(
    val layoutIssueType: String? = null,
    val visualEvidences: List<VisualEvidence> = emptyList(),
    val stackTrace: StackTrace? = null,
    val deviceInfo: DeviceInfo? = null,
  ) : FindingData

  /** Represents a finding type that has no extra structured data payload. */
  object Empty : FindingData

  /**
   * Fallback payload used when the finding type is unknown to this version of the client.
   *
   * Preserves the raw payload as a string (typically JSON) for debugging or forward-compatibility.
   *
   * @property rawData The raw string representation of the unparsed finding data.
   */
  data class Unknown(val rawData: String) : FindingData
}

/** Visual evidence (such as a screenshot and its optional UI view hierarchy) associated with a finding. */
data class VisualEvidence(
  val url: String,
  val viewHierarchy: ViewHierarchy? = null,
)

/** UI view hierarchy associated with a finding. */
data class ViewHierarchy(val url: String)

/** Crash stack trace associated with a finding. */
data class StackTrace(val raw: String)

/** Device information associated with a finding. */
data class DeviceInfo(
  val deviceBrandName: String,
  val deviceModel: String,
  val androidVersion: String,
  val androidSdkVersion: Int,
)

/** Vitals metric associated with a Vitals finding. */
sealed interface VitalsMetric {
  val raw: String
  val displayName: String

  data object UserPerceivedCrashRate : VitalsMetric {
    override val raw: String = "userPerceivedCrashRate"
    override val displayName: String = "User-perceived crash rate"
  }

  data object UserPerceivedAnrRate : VitalsMetric {
    override val raw: String = "userPerceivedAnrRate"
    override val displayName: String = "User-perceived ANR rate"
  }

  data class Unknown(override val raw: String) : VitalsMetric {
    override val displayName: String
      get() = raw
  }

  companion object {
    fun fromProto(metric: String): VitalsMetric =
      when (metric) {
        UserPerceivedCrashRate.raw -> UserPerceivedCrashRate
        UserPerceivedAnrRate.raw -> UserPerceivedAnrRate
        else -> Unknown(metric)
      }
  }
}
