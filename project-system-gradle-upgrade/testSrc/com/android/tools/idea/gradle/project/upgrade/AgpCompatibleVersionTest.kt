package com.android.tools.idea.gradle.project.upgrade

import com.android.SdkConstants
import com.android.Version
import com.android.ide.common.repository.AgpVersion
import com.android.tools.idea.gradle.util.CompatibleGradleVersion.Companion.AGP_MAJOR_MINOR_TO_GRADLE_MAP
import com.android.tools.idea.gradle.util.CompatibleGradleVersion.Companion.VERSION_FOR_DEV
import com.android.tools.idea.gradle.util.CompatibleGradleVersion.Companion.getCompatibleGradleVersion
import com.google.common.truth.Expect
import com.intellij.testFramework.LightPlatformTestCase
import org.gradle.util.GradleVersion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AgpCompatibleVersionTest : LightPlatformTestCase() {
  @get:Rule val expect: Expect = Expect.createAndEnableStackTrace()

  @Test
  fun testAgpToGradleMapConsistency() {
    val currentMajorMinor = AgpVersion.parse(Version.ANDROID_GRADLE_PLUGIN_VERSION).let { AgpVersion(it.major, it.minor) }
    expect.that(AGP_MAJOR_MINOR_TO_GRADLE_MAP).containsKey(currentMajorMinor)
    expect.that(AGP_MAJOR_MINOR_TO_GRADLE_MAP.values.filter { it == VERSION_FOR_DEV }).hasSize(1)

    // Threshold comparisons in GradlePluginsRefactoringProcessor require AGP_MAJOR_MINOR_TO_GRADLE_MAP
    // to be strictly increasing in AGP version and monotonically non-decreasing in CompatibleGradleVersion.
    val entries = AGP_MAJOR_MINOR_TO_GRADLE_MAP.entries.toList()
    for (i in 1 until entries.size) {
      expect.that(entries[i].key).isGreaterThan(entries[i - 1].key)
      expect.that(entries[i].value).isAtLeast(entries[i - 1].value)
    }
  }

  @Test
  fun testCompatibleVersions() {
    // Expected values come from AGP_COMPATIBLE_GRADLE_VERSIONS in tools/base/common/release_version.bzl,
    // so this test needs no update when AGP is bumped.
    val data =
      Version.AGP_COMPATIBLE_GRADLE_VERSIONS
        .filterKeys { AgpVersion.parse("$it.0") >= AgpVersion.parse(SdkConstants.GRADLE_PLUGIN_MINIMUM_FORCED_UPGRADE_VERSION) }
        .mapValues { (agpBase, gradleVersion) ->
          if (agpBase == Version.DEV_AGP_MAJOR_MINOR) {
            GradleVersion.version(SdkConstants.GRADLE_LATEST_VERSION)
          } else {
            GradleVersion.version(gradleVersion)
          }
        }
    fun String.toBetaVersionString() =
      when (this) {
        "3.1" -> "$this.0-beta2"
        else -> "$this.0-beta02"
      }
    fun checkGetCompatibleGradleVersion(from: AgpVersion, to: GradleVersion) {
      expect.that(getCompatibleGradleVersion(from).version).named("getCompatibleGradleVersion(agpVersion=$from)").isEqualTo(to)
    }
    data.forEach { (agpBase, expected) ->
      checkGetCompatibleGradleVersion(AgpVersion.parse("$agpBase.0"), expected)
      checkGetCompatibleGradleVersion(AgpVersion.parse("$agpBase.9"), expected)
      checkGetCompatibleGradleVersion(AgpVersion.parse("$agpBase.0-alpha01"), expected)
      checkGetCompatibleGradleVersion(AgpVersion.parse(agpBase.toBetaVersionString()), expected)
      checkGetCompatibleGradleVersion(AgpVersion.parse("$agpBase.0-rc03"), expected)
    }
  }
}
