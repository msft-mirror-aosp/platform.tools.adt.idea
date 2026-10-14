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
package com.android.tools.idea.findings.client.utils

import com.android.tools.idea.findings.client.FetchFindingsRequest
import com.android.tools.idea.findings.client.FindingsClient
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.FindingType
import com.android.tools.idea.findings.model.StackTrace
import com.android.tools.idea.findings.model.VitalsMetric
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.model.connection.Connection
import kotlinx.coroutines.delay

/**
 * Fake [Connection] for testing purposes while the backend is not ready.
 *
 * TODO(b/517599039): Remove [FakeFindingsConnection] and move [FakeFindingsClient] to `testSrc` once the Play Findings API is ready.
 */
private data class FakeFindingsConnection(
  override val appId: String,
  override val displayName: String,
) : Connection {
  override val mobileSdkAppId: String? = null
  override val projectId: String? = null
  override val projectNumber: String? = null
  override val clientId: String = "play-findings:$appId"
  override val isConfigured: Boolean = true

  override fun isPreferredConnection(): Boolean = false

  override fun isMatchingProject(): Boolean = false
}

/**
 * Fake implementation of [com.android.tools.idea.findings.client.FindingsClient] returning hardcoded mock data.
 *
 * Simulates server-side filtering locally, including package name filtering. Contains mock data for two package names: [TEST_PACKAGE_NAME]
 * and [OTHER_PACKAGE_NAME].
 *
 * This is mainly to test UI and integration layers: Allows verifying UI components without requiring a real network connection or a running
 * backend server.
 *
 * @param delayMs Milliseconds to delay [fetchFindings] to mimic network latency.
 *
 * TODO(b/517599039): Move [FakeFindingsClient] to `testSrc` once the Play Findings API is ready.
 */
class FakeFindingsClient(private val delayMs: Long = DEFAULT_DELAY_MS) : FindingsClient {
  companion object {
    /** Standard test package name used in mock data. */
    const val TEST_PACKAGE_NAME = "com.example.app"

    /** Alternative test package name used in mock data. */
    const val OTHER_PACKAGE_NAME = "com.other.app"

    /** Default network delay to simulate real network latency. */
    const val DEFAULT_DELAY_MS = 1000L

    /** Fake Play Console connections exposed to the global app selector when using [FakeFindingsClient]. */
    val FAKE_CONNECTIONS: Selection<Connection> =
      Selection(
        null,
        listOf(
          FakeFindingsConnection(TEST_PACKAGE_NAME, "[Fake] Example App"),
          FakeFindingsConnection(OTHER_PACKAGE_NAME, "[Fake] Other App"),
        ),
      )
  }

  private val database =
    listOf(
      // Findings for com.example.app
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/1",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.bad.sdk",
            drmDisplayName = "Bad SDK",
            drmVersion = "1.0",
            recommendedDrmVersion = "1.1",
            learnMoreUrl = "http://example.com/drm",
          ),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/2",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.worst.sdk",
            drmDisplayName = "Worst SDK",
            drmVersion = "2.0",
            recommendedDrmVersion = null,
            learnMoreUrl = null,
          ),
        affectedScopes = listOf(AffectedScope.Artifact("102")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/3",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.INFO,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.info.sdk",
            drmDisplayName = "Info SDK",
            drmVersion = "0.9",
            recommendedDrmVersion = "1.0",
            learnMoreUrl = null,
          ),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/4",
        type = FindingType.EDGE_TO_EDGE_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.EdgeToEdge(
            manualReviewMessage = "Your app does not draw content edge-to-edge.",
            versionCode = "101",
            xmlFiles = listOf("res/layout/activity_main.xml"),
            invocations = listOf("com.example.app.MainActivity.onCreate"),
          ),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/7",
        type = FindingType.VITALS_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.VitalsBadBehavior(
            metric = VitalsMetric.UserPerceivedCrashRate,
            threshold = 0.0109,
            metricValue = 0.035,
          ),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/8",
        type = FindingType.VITALS_ANOMALY,
        severity = FindingSeverity.INFO,
        findingData =
          FindingData.VitalsAnomaly(
            metric = VitalsMetric.UserPerceivedAnrRate,
            metricValue = 0.042,
            isHourly = true,
          ),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/9",
        type = FindingType.VITALS_DEVICE_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.VitalsDeviceBadBehavior(
            metric = VitalsMetric.Unknown("Excessive background wakelocks"),
            threshold = 0.02,
            affectedDeviceModelsCount = 3,
            isEmerging = false,
            affectedInstallsRatio = 0.08,
          ),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      // Large screen continuity (state loss on fold/unfold/resize)
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/15",
        type = FindingType.LARGE_SCREENS_CONTINUITY,
        severity = FindingSeverity.WARNING,
        findingData = FindingData.LargeScreens(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      // Large screen crash on configuration change
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/16",
        type = FindingType.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.LargeScreens(
            stackTrace =
              StackTrace(
                raw =
                  """
                  java.lang.NullPointerException: Attempt to invoke virtual method 'void android.widget.TextView.setText(java.lang.CharSequence)' on a null object reference
                  	at com.example.app.ui.detail.DetailFragment.onViewCreated(DetailFragment.kt:74)
                  	at androidx.fragment.app.Fragment.performViewCreated(Fragment.java:3146)
                  	at androidx.fragment.app.FragmentStateManager.createView(FragmentStateManager.java:588)
                  	at androidx.fragment.app.FragmentStateManager.moveToExpectedState(FragmentStateManager.java:272)
                  	at androidx.fragment.app.FragmentManager.executeOpsTogether(FragmentManager.java:1943)
                  	at androidx.fragment.app.FragmentManager.removeRedundantOperationsAndExecute(FragmentManager.java:1845)
                  	at androidx.fragment.app.FragmentManager.execPendingActions(FragmentManager.java:1782)
                  	at androidx.fragment.app.FragmentManager${'$'}5.run(FragmentManager.java:562)
                  	at android.os.Handler.handleCallback(Handler.java:958)
                  	at android.os.Handler.dispatchMessage(Handler.java:99)
                  	at android.os.Looper.loopOnce(Looper.java:230)
                  	at android.os.Looper.loop(Looper.java:319)
                  	at android.app.ActivityThread.main(ActivityThread.java:8919)
                  """
                    .trimIndent()
              )
          ),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      AppFinding.Supported(
        name = "applications/$TEST_PACKAGE_NAME/findings/18",
        type = FindingType.SDK_HAS_CRASH_ANNOTATION,
        severity = FindingSeverity.INFO,
        findingData = FindingData.Empty,
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      // Findings for com.other.app
      AppFinding.Supported(
        name = "applications/$OTHER_PACKAGE_NAME/findings/4",
        type = FindingType.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        findingData =
          FindingData.DrmAppCompat(
            drmPackageName = "com.another.bad.sdk",
            drmDisplayName = "Another Bad SDK",
            drmVersion = "1.5",
            recommendedDrmVersion = "1.6",
            learnMoreUrl = null,
          ),
        affectedScopes = emptyList(),
      ),
      AppFinding.Supported(
        name = "applications/$OTHER_PACKAGE_NAME/findings/7",
        type = FindingType.VITALS_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        findingData =
          FindingData.VitalsBadBehavior(
            metric = VitalsMetric.UserPerceivedCrashRate,
            threshold = 0.0109,
            metricValue = 0.025,
          ),
        affectedScopes = listOf(AffectedScope.Release("release-v2.0")),
      ),
      AppFinding.Unknown(
        name = "applications/$OTHER_PACKAGE_NAME/findings/5",
        severity = FindingSeverity.INFO,
        findingData = FindingData.Unknown("{\n  \"rawKey\": \"rawValue\"\n}"),
        affectedScopes = emptyList(),
      ),
    )

  /** Returns findings from the local mock database, filtered by the package name and request criteria. */
  override suspend fun fetchFindings(request: FetchFindingsRequest): LoadingState.Done<List<AppFinding>> {
    if (delayMs > 0) {
      delay(delayMs)
    }
    val filtered = database.filter { finding ->
      val matchesPackage = finding.name.startsWith("applications/${request.packageName}/findings/")
      val matchesType = request.filters.findingTypes.isEmpty() || request.filters.findingTypes.contains(finding.type)
      val matchesSeverity = request.filters.severities.isEmpty() || request.filters.severities.contains(finding.severity)
      matchesPackage && matchesType && matchesSeverity
    }
    return LoadingState.Ready(filtered)
  }
}
