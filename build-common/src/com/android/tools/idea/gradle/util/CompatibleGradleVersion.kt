/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.gradle.util

import com.android.SdkConstants
import com.android.ide.common.repository.AgpVersion
import org.gradle.util.GradleVersion
import org.jetbrains.annotations.VisibleForTesting

data class CompatibleGradleVersion(
  val version: GradleVersion,
  val isDev: Boolean = false,
) : Comparable<CompatibleGradleVersion> {
  // VERSION_FOR_DEV always sorts after pinned versions, even if GRADLE_LATEST_VERSION is a
  // pre-release of a pinned Gradle version.
  override fun compareTo(other: CompatibleGradleVersion): Int =
    compareValuesBy(this, other, { it.isDev }, { it.version })

  companion object {
    // The AGP-to-Gradle compatibility mapping is maintained in
    // tools/base/common/release_version.bzl (AGP_COMPATIBLE_GRADLE_VERSIONS).
    // The VERSION_* constants below are only the thresholds used by
    // GradlePluginsRefactoringProcessor; add one only when a plugin compatibility
    // rule needs a new threshold, not when bumping AGP.
    @JvmField val VERSION_7_2 = CompatibleGradleVersion(GradleVersion.version("7.2"))
    @JvmField val VERSION_7_3_3 = CompatibleGradleVersion(GradleVersion.version("7.3.3"))
    @JvmField val VERSION_7_4 = CompatibleGradleVersion(GradleVersion.version("7.4"))
    @JvmField val VERSION_8_0 = CompatibleGradleVersion(GradleVersion.version("8.0"))
    @JvmField val VERSION_8_4 = CompatibleGradleVersion(GradleVersion.version("8.4"))
    @JvmField val VERSION_9_1_0 = CompatibleGradleVersion(GradleVersion.version("9.1.0"))
    @JvmField
    val VERSION_FOR_DEV =
      CompatibleGradleVersion(
        GradleVersion.version(SdkConstants.GRADLE_LATEST_VERSION),
        isDev = true,
      )

    @VisibleForTesting
    val AGP_MAJOR_MINOR_TO_GRADLE_MAP: Map<AgpVersion, CompatibleGradleVersion> =
      SdkConstants.AGP_COMPATIBLE_GRADLE_VERSIONS.entries.associate { (agpMajorMinor, gradleVersion) ->
        val agpVersion = AgpVersion.parse("$agpMajorMinor.0")
        val compatibleGradleVersion =
          if (agpMajorMinor == SdkConstants.AGP_DEV_MAJOR_MINOR_VERSION) {
            VERSION_FOR_DEV
          } else {
            CompatibleGradleVersion(GradleVersion.version(gradleVersion))
          }
        agpVersion to compatibleGradleVersion
      }

    val entries: List<CompatibleGradleVersion> =
      AGP_MAJOR_MINOR_TO_GRADLE_MAP.values.distinct()

    private fun getAssociatedGradleVersion(agpVersion: AgpVersion): CompatibleGradleVersion {
      val agpVersionMajorMinor = AgpVersion(agpVersion.major, agpVersion.minor)
      return when {
        agpVersionMajorMinor < AgpVersion.parse(SdkConstants.GRADLE_PLUGIN_MINIMUM_FORCED_UPGRADE_VERSION) -> VERSION_MIN
        else -> AGP_MAJOR_MINOR_TO_GRADLE_MAP.getOrDefault(agpVersionMajorMinor, VERSION_FOR_DEV)
      }
    }

    private val VERSION_MIN = getAssociatedGradleVersion(AgpVersion.parse(SdkConstants.GRADLE_PLUGIN_MINIMUM_FORCED_UPGRADE_VERSION))

    fun getCompatibleGradleVersion(agpVersion: AgpVersion): CompatibleGradleVersion {
      val compatibleGradleVersion = getAssociatedGradleVersion(agpVersion)
      return when {
        compatibleGradleVersion.version < VERSION_MIN.version -> VERSION_MIN
        else -> compatibleGradleVersion
      }
    }
  }
}
