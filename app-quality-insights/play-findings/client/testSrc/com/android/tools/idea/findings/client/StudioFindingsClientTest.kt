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
package com.android.tools.idea.findings.client

import com.android.tools.findings.client.PlayFindingsClient
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.DeviceInfo
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingContext
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.StackTrace
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.insights.LoadingState
import com.google.common.truth.Truth.assertThat
import com.google.play.androidpublisher.v3.ComputeFindingsResponse
import com.google.play.androidpublisher.v3.DrmAppCompatFindingData
import com.google.play.androidpublisher.v3.EdgeToEdgeCompatFindingData
import com.google.play.androidpublisher.v3.Finding as ProtoFinding
import com.google.play.androidpublisher.v3.FindingData as ProtoFindingData
import com.google.play.androidpublisher.v3.InAppLocation as ProtoInAppLocation
import com.google.play.androidpublisher.v3.LargeScreensCrashFindingData
import com.google.play.androidpublisher.v3.LargeScreensDeviceInfo as ProtoLargeScreensDeviceInfo
import com.google.play.androidpublisher.v3.LargeScreensLayoutFindingData
import com.google.play.androidpublisher.v3.LargeScreensVisualFindingData
import com.google.play.androidpublisher.v3.VitalsAnomalyFindingData
import com.google.play.androidpublisher.v3.VitalsBadBehaviorFindingData
import com.google.play.androidpublisher.v3.VitalsDeviceBadBehaviorFindingData
import com.google.protobuf.Timestamp
import com.google.protobuf.util.JsonFormat
import com.google.type.Date
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * Unit tests for [StudioFindingsClient].
 * 1. **Verify network contract**: Validates the actual JSON serialization/deserialization logic against mock HTTP payloads.
 * 2. **Network error robustness**: Tests how the client translates HTTP status codes (like 500, non-200) and transport errors (like
 *    [IOException]) into clean domain [LoadingState] objects.
 */
class StudioFindingsClientTest {

  private lateinit var mockPlayClient: PlayFindingsClient
  private lateinit var parentDisposable: com.intellij.openapi.Disposable
  private lateinit var client: StudioFindingsClient

  @Before
  fun setUp() {
    mockPlayClient = mock(PlayFindingsClient::class.java)
    parentDisposable = com.intellij.openapi.util.Disposer.newDisposable()
    client = StudioFindingsClient(mockPlayClient, parentDisposable)
  }

  @Test
  fun testFetchFindings_Success() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          createDrmFinding(
            name = "applications/com.example.app/findings/1",
            severity = ProtoFinding.Severity.WARNING,
            recommendedVersion = "1.1",
            learnMoreUrl = "http://example.com/drm",
            inAppLocations = listOf(createArtifactLocation("101")),
          )
        )
        .addFindings(
          createDrmFinding(
            name = "applications/com.example.app/findings/2",
            severity = ProtoFinding.Severity.INFO,
            inAppLocations = listOf(createReleaseLocation("release-v2.0")),
          )
        )
        .build()

    val jsonResponse = JsonFormat.printer().print(responseProto)

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(jsonResponse)

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))

    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(2)

    val finding1 = findings[0]
    assertThat(finding1.name).isEqualTo("applications/com.example.app/findings/1")
    assertThat(finding1.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(finding1.severity).isEqualTo(FindingSeverity.WARNING)
    assertThat(finding1.displayName).isEqualTo("Bad version of your app protection service")
    assertThat(finding1.description).contains("Version 1.0 of the com.bad.sdk app protection service")
    assertThat(finding1.description).contains("version 1.1 or later")
    assertThat(finding1.jsonPayload).contains("\"drmPackageName\":\"com.bad.sdk\"")
    assertThat(finding1.jsonPayload).contains("\"recommendedDrmVersion\":\"1.1\"")
    assertThat(finding1.affectedScopes).hasSize(1)
    val location1 = finding1.affectedScopes[0]
    assertThat(location1).isInstanceOf(AffectedScope.Artifact::class.java)
    assertThat((location1 as AffectedScope.Artifact).versionCode).isEqualTo("101")

    val finding2 = findings[1]
    assertThat(finding2.name).isEqualTo("applications/com.example.app/findings/2")
    assertThat(finding2.category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(finding2.severity).isEqualTo(FindingSeverity.INFO)
    assertThat(finding2.affectedScopes).hasSize(1)
    val location2 = finding2.affectedScopes[0]
    assertThat(location2).isInstanceOf(AffectedScope.Release::class.java)
    assertThat((location2 as AffectedScope.Release).releaseName).isEqualTo("release-v2.0")
  }

  @Test
  fun testFetchFindings_EdgeToEdgeCompat() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/e2e")
            .setFindingType(ProtoFinding.Type.EDGE_TO_EDGE_COMPAT)
            .setFindingSeverity(ProtoFinding.Severity.WARNING)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setEdgeToEdgeCompat(EdgeToEdgeCompatFindingData.newBuilder().setReviewMessage("Draw edge to edge"))
            )
            .addInAppLocations(createArtifactLocation("105"))
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    assertThat(findings[0].category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(findings[0].severity).isEqualTo(FindingSeverity.WARNING)
    assertThat(findings[0].description).isEqualTo("Draw edge to edge")
    assertThat(findings[0].jsonPayload).contains("\"reviewMessage\":\"Draw edge to edge\"")
  }

  @Test
  fun testFetchFindings_VitalsAnomaly() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/anomaly-hourly")
            .setFindingType(ProtoFinding.Type.VITALS_ANOMALY)
            .setFindingSeverity(ProtoFinding.Severity.INFO)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setVitalsAnomaly(
                  VitalsAnomalyFindingData.newBuilder()
                    .setMetricSet("vitals.crashrate")
                    .setMetric("userPerceivedCrashRate")
                    .setHourlyTime(Timestamp.newBuilder().setSeconds(1700000000L))
                    .setMetricValue(0.045)
                )
            )
        )
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/anomaly-daily")
            .setFindingType(ProtoFinding.Type.VITALS_ANOMALY)
            .setFindingSeverity(ProtoFinding.Severity.INFO)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setVitalsAnomaly(
                  VitalsAnomalyFindingData.newBuilder()
                    .setMetricSet("vitals.anrrate")
                    .setMetric("userPerceivedAnrRate")
                    .setDailyDate(Date.newBuilder().setYear(2026).setMonth(10).setDay(1))
                    .setMetricValue(0.012)
                )
            )
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(2)

    assertThat(findings[0].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[0].displayName).isEqualTo("Vitals anomaly detected: User-perceived crash rate")
    assertThat(findings[0].description).contains("4.5%")

    assertThat(findings[1].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[1].displayName).isEqualTo("Vitals anomaly detected: User-perceived ANR rate")
    assertThat(findings[1].description).contains("1.2%")
  }

  @Test
  fun testFetchFindings_VitalsBadBehavior() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/bad-behavior")
            .setFindingType(ProtoFinding.Type.VITALS_BAD_BEHAVIOR)
            .setFindingSeverity(ProtoFinding.Severity.SEVERE)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setVitalsBadBehavior(
                  VitalsBadBehaviorFindingData.newBuilder()
                    .setMetricSet("vitals.crashrate")
                    .setMetric("userPerceivedCrashRate")
                    .setThreshold(0.0109)
                    .setMetricValue(0.032)
                )
            )
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    assertThat(findings[0].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[0].severity).isEqualTo(FindingSeverity.SEVERE)
    assertThat(findings[0].displayName).isEqualTo("Vitals bad behavior threshold exceeded: User-perceived crash rate")
    assertThat(findings[0].description).contains("threshold of 1.09% (actual: 3.20%)")
  }

  @Test
  fun testFetchFindings_VitalsDeviceBadBehavior() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/device-bad-behavior")
            .setFindingType(ProtoFinding.Type.VITALS_DEVICE_BAD_BEHAVIOR)
            .setFindingSeverity(ProtoFinding.Severity.SEVERE)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setVitalsDeviceBadBehavior(
                  VitalsDeviceBadBehaviorFindingData.newBuilder()
                    .setMetricSet("vitals.crashrate")
                    .setMetric("userPerceivedCrashRate")
                    .setThreshold(0.02)
                    .setAffectedDeviceModelCount(4)
                    .setBadBehaviorType(VitalsDeviceBadBehaviorFindingData.Type.EMERGING_BAD_BEHAVIOR)
                    .setAffectedInstallsRatio(0.07)
                )
            )
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    assertThat(findings[0].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[0].severity).isEqualTo(FindingSeverity.SEVERE)
    assertThat(findings[0].displayName).isEqualTo("Per-device vitals bad behavior: User-perceived crash rate")
    assertThat(findings[0].description).contains("4 device model(s) exceed the emerging bad behavior threshold")
  }

  @Test
  fun testFetchFindings_SdkHasCrashAnnotation() = runBlocking {
    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/sdk-crash-annotation")
            .setFindingType(ProtoFinding.Type.SDK_HAS_CRASH_ANNOTATION)
            .setFindingSeverity(ProtoFinding.Severity.INFO)
            .addInAppLocations(createReleaseLocation("release-v1.0"))
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    assertThat(findings[0].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[0].severity).isEqualTo(FindingSeverity.INFO)
    assertThat(findings[0].context).isEqualTo(FindingContext.EMPTY)
  }

  @Test
  fun testFetchFindings_LargeScreens() = runBlocking {
    val protoDeviceInfo =
      ProtoLargeScreensDeviceInfo.newBuilder()
        .setDeviceBrandName("samsung")
        .setDeviceModel("sm-f946b")
        .setAndroidVersion("14")
        .setAndroidSdkVersion(34)
        .build()
    val expectedDeviceInfo =
      DeviceInfo(
        deviceBrandName = "samsung",
        deviceModel = "sm-f946b",
        androidVersion = "14",
        androidSdkVersion = 34,
      )

    val responseProto =
      ComputeFindingsResponse.newBuilder()
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/ls-layout")
            .setFindingType(ProtoFinding.Type.LARGE_SCREENS_LAYOUT)
            .setFindingSeverity(ProtoFinding.Severity.WARNING)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setLargeScreensLayout(
                  LargeScreensLayoutFindingData.newBuilder()
                    .setLayoutIssueType(LargeScreensLayoutFindingData.LayoutIssueType.DOES_NOT_USE_ENTIRE_SCREEN)
                    .setScreenshotFifeUrl("https://lh3.googleusercontent.com/layout-screenshot")
                    .setDeviceInfo(protoDeviceInfo)
                )
            )
        )
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/ls-camera")
            .setFindingType(ProtoFinding.Type.LARGE_SCREENS_CAMERA_STRETCHED)
            .setFindingSeverity(ProtoFinding.Severity.WARNING)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setLargeScreensVisual(
                  LargeScreensVisualFindingData.newBuilder()
                    .setScreenshotFifeUrl("https://lh3.googleusercontent.com/camera-screenshot")
                    .setDeviceInfo(protoDeviceInfo)
                )
            )
        )
        .addFindings(
          ProtoFinding.newBuilder()
            .setName("applications/com.example.app/findings/ls-crash")
            .setFindingType(ProtoFinding.Type.LARGE_SCREENS_CRASH)
            .setFindingSeverity(ProtoFinding.Severity.SEVERE)
            .setFindingData(
              ProtoFindingData.newBuilder()
                .setLargeScreensCrash(
                  LargeScreensCrashFindingData.newBuilder()
                    .setStackTrace("java.lang.IllegalStateException: Crash on fold")
                    .setDeviceInfo(protoDeviceInfo)
                )
            )
        )
        .build()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(JsonFormat.printer().print(responseProto))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))
    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(3)

    assertThat(findings[0].category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(findings[0].displayName).isEqualTo("App does not use entire screen")
    assertThat(findings[0].context)
      .isEqualTo(
        FindingContext(
          visualEvidences = listOf(VisualEvidence(url = "https://lh3.googleusercontent.com/layout-screenshot")),
          deviceInfo = expectedDeviceInfo,
        )
      )

    assertThat(findings[1].category).isEqualTo(FindingCategory.USER_EXPERIENCE)
    assertThat(findings[1].context)
      .isEqualTo(
        FindingContext(
          visualEvidences = listOf(VisualEvidence(url = "https://lh3.googleusercontent.com/camera-screenshot")),
          deviceInfo = expectedDeviceInfo,
        )
      )

    assertThat(findings[2].category).isEqualTo(FindingCategory.TECHNICAL_QUALITY)
    assertThat(findings[2].context)
      .isEqualTo(
        FindingContext(
          stackTrace = StackTrace(raw = "java.lang.IllegalStateException: Crash on fold"),
          deviceInfo = expectedDeviceInfo,
        )
      )
  }

  @Test
  fun testFetchFindings_Non200() = runBlocking {
    val mockResponse = mock(HttpResponse::class.java)
    whenever(mockResponse.status).thenReturn(HttpStatusCode.InternalServerError)
    val mockException = object : ResponseException(mockResponse, "Internal Server Error") {}
    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenThrow(mockException)

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))

    assertThat(result).isInstanceOf(LoadingState.NetworkFailure::class.java)
    val failure = result as LoadingState.NetworkFailure
    assertThat(failure.message).contains("500")
  }

  @Test
  fun testFetchFindings_NetworkError() = runBlocking {
    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenThrow(IOException("Network down"))

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))

    assertThat(result).isInstanceOf(LoadingState.NetworkFailure::class.java)
    val failure = result as LoadingState.NetworkFailure
    assertThat(failure.cause).isInstanceOf(IOException::class.java)
  }

  @Test
  fun testFetchFindings_UnknownTypePreserved() = runBlocking {
    val jsonResponse =
      """
      {
        "findings": [
          {
            "name": "applications/com.example.app/findings/1",
            "findingType": "NEW_UNKNOWN_TYPE",
            "findingSeverity": "INFO",
            "findingData": {
              "someNewData": {
                "someKey": "someValue"
              }
            }
          }
        ]
      }
      """
        .trimIndent()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(jsonResponse)

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))

    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    val finding = findings[0]
    assertThat(finding.name).isEqualTo("applications/com.example.app/findings/1")
    assertThat(finding.category).isEqualTo(FindingCategory.OTHER)
    assertThat(finding.displayName).isEqualTo("Unknown Finding")
    assertThat(finding.context).isEqualTo(FindingContext.EMPTY)
  }

  @Test
  fun testFetchFindings_MalformedResponseRobustness() = runBlocking {
    val jsonResponse =
      """
      {
        "findings": [
          {
            "findingType": "TYPE_UNSPECIFIED",
            "findingSeverity": "SEVERITY_UNSPECIFIED"
          }
        ]
      }
      """
        .trimIndent()

    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn(jsonResponse)

    val result = client.fetchFindings(FetchFindingsRequest("com.example.app"))

    assertThat(result).isInstanceOf(LoadingState.Ready::class.java)
    val findings = (result as LoadingState.Ready).value
    assertThat(findings).hasSize(1)
    val finding = findings[0]
    assertThat(finding.name).isEmpty()
    assertThat(finding.category).isEqualTo(FindingCategory.OTHER)
    assertThat(finding.severity).isEqualTo(FindingSeverity.INFO)
    assertThat(finding.context).isEqualTo(FindingContext.EMPTY)
    assertThat(finding.affectedScopes).isEmpty()
  }

  @Test
  fun testFetchFindings_WithFilters() = runBlocking {
    whenever(mockPlayClient.fetchFindings(any(), anyOrNull())).thenReturn("""{ "findings": [] }""")

    val filters = FindingsFilters(severities = setOf(FindingSeverity.WARNING))
    client.fetchFindings(FetchFindingsRequest("com.example.app", filters))

    val filterCaptor = ArgumentCaptor.forClass(String::class.java)
    verify(mockPlayClient).fetchFindings(eq("com.example.app"), filterCaptor.capture())
    assertThat(filterCaptor.value).isEqualTo("(finding_severity = \"WARNING\")")
  }
}

private fun createDrmFinding(
  name: String,
  severity: ProtoFinding.Severity,
  packageName: String = "com.bad.sdk",
  displayName: String = "Bad SDK",
  version: String = "1.0",
  recommendedVersion: String? = null,
  learnMoreUrl: String? = null,
  inAppLocations: List<ProtoInAppLocation> = emptyList(),
): ProtoFinding {
  val drmDataBuilder =
    DrmAppCompatFindingData.newBuilder().apply {
      drmPackageName = packageName
      drmDisplayName = displayName
      drmVersion = version
      recommendedVersion?.let { recommendedDrmVersion = it }
      learnMoreUrl?.let { this.learnMoreUrl = it }
    }

  return ProtoFinding.newBuilder()
    .apply {
      setName(name)
      setFindingType(ProtoFinding.Type.DRM_APP_COMPAT)
      setFindingSeverity(severity)
      setFindingData(ProtoFindingData.newBuilder().setDrmAppCompat(drmDataBuilder))
      addAllInAppLocations(inAppLocations)
    }
    .build()
}

private fun createArtifactLocation(versionCode: String): ProtoInAppLocation {
  return ProtoInAppLocation.newBuilder().setArtifact(ProtoInAppLocation.Artifact.newBuilder().setVersionCode(versionCode)).build()
}

private fun createReleaseLocation(releaseName: String): ProtoInAppLocation {
  return ProtoInAppLocation.newBuilder().setRelease(ProtoInAppLocation.Release.newBuilder().setReleaseName(releaseName)).build()
}
