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
import com.android.tools.idea.findings.client.toDomain
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.model.connection.Connection
import com.google.play.androidpublisher.v3.DrmAppCompatFindingData
import com.google.play.androidpublisher.v3.EdgeToEdgeCompatFindingData
import com.google.play.androidpublisher.v3.Finding as ProtoFinding
import com.google.play.androidpublisher.v3.FindingData as ProtoFindingData
import com.google.play.androidpublisher.v3.InAppLocation as ProtoInAppLocation
import com.google.play.androidpublisher.v3.LabCrashNoTelemetryFindingData
import com.google.play.androidpublisher.v3.LabCrashSixteenKbFindingData
import com.google.play.androidpublisher.v3.LargeScreensContinuityEvent
import com.google.play.androidpublisher.v3.LargeScreensContinuityFindingData
import com.google.play.androidpublisher.v3.LargeScreensCrashFindingData
import com.google.play.androidpublisher.v3.LargeScreensDeviceInfo
import com.google.play.androidpublisher.v3.VitalsAnomalyFindingData
import com.google.play.androidpublisher.v3.VitalsBadBehaviorFindingData
import com.google.play.androidpublisher.v3.VitalsDeviceBadBehaviorFindingData
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
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/1",
        type = ProtoFinding.Type.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        protoData =
          ProtoFindingData.newBuilder()
            .setDrmAppCompat(
              DrmAppCompatFindingData.newBuilder()
                .setDrmPackageName("com.bad.sdk")
                .setDrmDisplayName("Bad SDK")
                .setDrmVersion("1.0")
                .setRecommendedDrmVersion("1.1")
                .setLearnMoreUrl("http://example.com/drm")
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/2",
        type = ProtoFinding.Type.DRM_APP_COMPAT,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setDrmAppCompat(
              DrmAppCompatFindingData.newBuilder().setDrmPackageName("com.worst.sdk").setDrmDisplayName("Worst SDK").setDrmVersion("2.0")
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("102")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/3",
        type = ProtoFinding.Type.DRM_APP_COMPAT,
        severity = FindingSeverity.INFO,
        protoData =
          ProtoFindingData.newBuilder()
            .setDrmAppCompat(
              DrmAppCompatFindingData.newBuilder()
                .setDrmPackageName("com.info.sdk")
                .setDrmDisplayName("Info SDK")
                .setDrmVersion("0.9")
                .setRecommendedDrmVersion("1.0")
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/4",
        type = ProtoFinding.Type.EDGE_TO_EDGE_COMPAT,
        severity = FindingSeverity.WARNING,
        protoData =
          ProtoFindingData.newBuilder()
            .setEdgeToEdgeCompat(EdgeToEdgeCompatFindingData.newBuilder().setReviewMessage("Your app does not draw content edge-to-edge."))
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/5",
        type = ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB,
        severity = FindingSeverity.BLOCKING,
        protoData =
          ProtoFindingData.newBuilder()
            .setLabCrashSixteenKb(
              LabCrashSixteenKbFindingData.newBuilder().setErrorMessage("dlopen failed: empty/missing DT_HASH/DT_GNU_HASH in libnative.so")
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/6",
        type = ProtoFinding.Type.LAB_CRASH_NO_TELEMETRY,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setLabCrashNoTelemetry(
              LabCrashNoTelemetryFindingData.newBuilder()
                .setErrorMessage("java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference")
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/7",
        type = ProtoFinding.Type.VITALS_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setVitalsBadBehavior(
              VitalsBadBehaviorFindingData.newBuilder().setMetric("userPerceivedCrashRate").setThreshold(0.0109).setMetricValue(0.035)
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/8",
        type = ProtoFinding.Type.VITALS_ANOMALY,
        severity = FindingSeverity.INFO,
        protoData =
          ProtoFindingData.newBuilder()
            .setVitalsAnomaly(VitalsAnomalyFindingData.newBuilder().setMetric("userPerceivedAnrRate").setMetricValue(0.042))
            .build(),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/9",
        type = ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setVitalsDeviceBadBehavior(
              VitalsDeviceBadBehaviorFindingData.newBuilder()
                .setMetric("Excessive background wakelocks")
                .setThreshold(0.02)
                .setAffectedDeviceModelCount(3)
                .setAffectedInstallsRatio(0.08)
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      // Large screen continuity (state loss on fold/unfold/resize)
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/15",
        type = ProtoFinding.Type.LARGE_SCREENS_CONTINUITY,
        severity = FindingSeverity.WARNING,
        protoData =
          ProtoFindingData.newBuilder()
            .setLargeScreensContinuity(
              LargeScreensContinuityFindingData.newBuilder()
                .addEvents(
                  LargeScreensContinuityEvent.newBuilder()
                    .setDeviceInfo(
                      LargeScreensDeviceInfo.newBuilder()
                        .setDeviceBrand("Google")
                        .setDeviceModel("Pixel Fold")
                        .setAndroidVersion("14")
                        .setAndroidSdkVersion(34)
                    )
                )
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      // Large screen crash on configuration change
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/16",
        type = ProtoFinding.Type.LARGE_SCREENS_CRASH,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setLargeScreensCrash(
              LargeScreensCrashFindingData.newBuilder()
                .addEvents(
                  LargeScreensCrashFindingData.CrashEvent.newBuilder()
                    .setDeviceInfo(
                      LargeScreensDeviceInfo.newBuilder()
                        .setDeviceBrand("Google")
                        .setDeviceModel("Pixel Fold")
                        .setAndroidVersion("14")
                        .setAndroidSdkVersion(34)
                    )
                    .setStackTrace(
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
                )
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("101")),
      ),
      createFinding(
        name = "applications/$TEST_PACKAGE_NAME/findings/18",
        type = ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION,
        severity = FindingSeverity.INFO,
        affectedScopes = listOf(AffectedScope.Release("release-v1.0")),
      ),
      // Findings for com.other.app
      createFinding(
        name = "applications/$OTHER_PACKAGE_NAME/findings/4",
        type = ProtoFinding.Type.DRM_APP_COMPAT,
        severity = FindingSeverity.WARNING,
        protoData =
          ProtoFindingData.newBuilder()
            .setDrmAppCompat(
              DrmAppCompatFindingData.newBuilder()
                .setDrmPackageName("com.another.bad.sdk")
                .setDrmDisplayName("Another Bad SDK")
                .setDrmVersion("1.5")
                .setRecommendedDrmVersion("1.6")
            )
            .build(),
        affectedScopes = emptyList(),
      ),
      createFinding(
        name = "applications/$OTHER_PACKAGE_NAME/findings/6",
        type = ProtoFinding.Type.LAB_CRASH_SIXTEEN_KB,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setLabCrashSixteenKb(LabCrashSixteenKbFindingData.newBuilder().setErrorMessage("dlopen failed: alignment error in libfoo.so"))
            .build(),
        affectedScopes = listOf(AffectedScope.Artifact("201")),
      ),
      createFinding(
        name = "applications/$OTHER_PACKAGE_NAME/findings/7",
        type = ProtoFinding.Type.VITALS_BAD_BEHAVIOR,
        severity = FindingSeverity.SEVERE,
        protoData =
          ProtoFindingData.newBuilder()
            .setVitalsBadBehavior(
              VitalsBadBehaviorFindingData.newBuilder().setMetric("userPerceivedCrashRate").setThreshold(0.0109).setMetricValue(0.025)
            )
            .build(),
        affectedScopes = listOf(AffectedScope.Release("release-v2.0")),
      ),
      createFinding(
        name = "applications/$OTHER_PACKAGE_NAME/findings/5",
        type = ProtoFinding.Type.TYPE_UNSPECIFIED,
        severity = FindingSeverity.INFO,
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
      val matchesSeverity = request.filters.severities.isEmpty() || request.filters.severities.contains(finding.severity)
      matchesPackage && matchesSeverity
    }
    return LoadingState.Ready(filtered)
  }
}

private fun createFinding(
  name: String,
  type: ProtoFinding.Type,
  severity: FindingSeverity,
  protoData: ProtoFindingData? = null,
  affectedScopes: List<AffectedScope> = emptyList(),
): AppFinding =
  ProtoFinding.newBuilder()
    .setName(name)
    .setFindingType(type)
    .setFindingSeverity(severity.toProto())
    .apply { if (protoData != null) setFindingData(protoData) }
    .addAllInAppLocations(affectedScopes.map { it.toProto() })
    .build()
    .toDomain()

private fun AffectedScope.toProto(): ProtoInAppLocation =
  when (this) {
    is AffectedScope.Artifact ->
      ProtoInAppLocation.newBuilder().setArtifact(ProtoInAppLocation.Artifact.newBuilder().setVersionCode(versionCode)).build()
    is AffectedScope.Release ->
      ProtoInAppLocation.newBuilder().setRelease(ProtoInAppLocation.Release.newBuilder().setReleaseName(releaseName)).build()
  }
