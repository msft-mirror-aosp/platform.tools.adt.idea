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
import com.android.tools.findings.client.PlayFindingsEndpoints
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.DeviceInfo
import com.android.tools.idea.findings.model.FindingContext
import com.android.tools.idea.findings.model.FindingOccurrence
import com.android.tools.idea.findings.model.StackTrace
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.findings.model.getFindingDescription
import com.android.tools.idea.findings.model.getFindingDisplayName
import com.android.tools.idea.findings.model.toDomain
import com.android.tools.idea.findings.model.toFindingCategory
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.insights.LoadingState
import com.google.gct.login2.fstLoginFeature
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.play.androidpublisher.v3.ComputeFindingsResponse as ProtoComputeFindingsResponse
import com.google.play.androidpublisher.v3.Finding as ProtoFinding
import com.google.play.androidpublisher.v3.FindingData as ProtoFindingData
import com.google.play.androidpublisher.v3.InAppLocation as ProtoInAppLocation
import com.google.play.androidpublisher.v3.LargeScreensDeviceInfo as ProtoLargeScreensDeviceInfo
import com.google.play.androidpublisher.v3.LargeScreensEvidenceStep as ProtoLargeScreensEvidenceStep
import com.google.play.androidpublisher.v3.LargeScreensScreenCapture as ProtoLargeScreensScreenCapture
import com.google.play.androidpublisher.v3.LargeScreensVisualEvent as ProtoLargeScreensVisualEvent
import com.google.protobuf.util.JsonFormat
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import io.ktor.client.plugins.ResponseException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = Logger.getInstance(StudioFindingsClient::class.java)

/** Serializes JSON without HTML escaping, so characters such as '=' in URLs are not written as `\u003d`. */
private val UNESCAPED_GSON = GsonBuilder().disableHtmlEscaping().create()

val StudioFlags.PlayFindingsEndpoint.url: String
  get() =
    when (this) {
      StudioFlags.PlayFindingsEndpoint.PRODUCTION -> PlayFindingsEndpoints.PRODUCTION
      StudioFlags.PlayFindingsEndpoint.STAGING -> PlayFindingsEndpoints.STAGING
    }

/**
 * Production implementation of [FindingsClient] that communicates with the Play Developer API over HTTP.
 *
 * Delegates the actual HTTP call to [PlayFindingsClient] and handles parsing and mapping to domain models.
 */
class StudioFindingsClient(private val playClient: PlayFindingsClient, parentDisposable: Disposable) : FindingsClient {

  init {
    Disposer.register(parentDisposable) { playClient.close() }
  }

  companion object {
    /** Creates a production [StudioFindingsClient] by instantiating the underlying [PlayFindingsClient]. */
    fun create(parentDisposable: Disposable): StudioFindingsClient =
      StudioFindingsClient(
        playClient =
          PlayFindingsClient(baseUrl = StudioFlags.PLAY_FINDINGS_ENDPOINT.get().url, tokenProvider = { fstLoginFeature.oAuthToken() }),
        parentDisposable = parentDisposable,
      )
  }

  /** Fetches the findings for the specified application package from the Play Console. Logs any unknown finding types encountered. */
  override suspend fun fetchFindings(request: FetchFindingsRequest): LoadingState.Done<List<AppFinding>> {
    val filterStr = request.filters.toFilterString()

    return withContext(Dispatchers.IO) {
      try {
        val responseBody = playClient.fetchFindings(request.packageName, filterStr.takeIf { it.isNotEmpty() })
        val builder = ProtoComputeFindingsResponse.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(responseBody, builder)
        val responseProto = builder.build()

        val findings = responseProto.findingsList.map { protoFinding -> protoFinding.toDomain() }

        LoadingState.Ready(findings)
      } catch (e: ResponseException) {
        // TODO(b/517599039): Narrow down error categorization and map to more specific LoadingState.Failure subclasses (e.g. Unauthorized,
        // PermissionDenied, ServerFailure) based on the HTTP status code for better error handling and display.
        LoadingState.NetworkFailure("Failed to fetch findings: ${e.response.status.value} ${e.message}", e)
      } catch (e: IOException) {
        // TODO(b/517599039): Handle TokenResponseException to return LoadingState.Unauthorized if token fetching/refresh fails.
        LoadingState.NetworkFailure("Network error fetching findings", e)
      } catch (e: Exception) {
        LoadingState.UnknownFailure("Unknown error fetching findings", e)
      }
    }
  }
}

/** Converts a Protobuf [ProtoFinding] to its domain [AppFinding] counterpart. */
internal fun ProtoFinding.toDomain(): AppFinding {
  val protoData = if (hasFindingData()) findingData else null
  // TODO(b/517599039): Because the HTTP response is parsed into ProtoComputeFindingsResponse with
  //  ignoringUnknownFields(), re-serializing ProtoFinding here drops any new fields not yet in
  //  Studio's compiled proto. Revisit how to better support forward-compatibility for new API
  //  finding types/details.
  val jsonPayload = runCatching {
    val json = JsonFormat.printer().omittingInsignificantWhitespace().print(this)
    // Protobuf's JSON printer HTML-escapes characters such as '=' (as \u003d); re-serialize without that escaping so asset URLs
    // (e.g. screenshot URLs ending in "=s0") stay intact and readable in prompts.
    UNESCAPED_GSON.toJson(JsonParser.parseString(json))
  }
    .getOrDefault("")

  return AppFinding(
    name = name,
    category = findingType.toFindingCategory(),
    severity = findingSeverity.toDomain(),
    displayName = getFindingDisplayName(findingType, protoData),
    description = getFindingDescription(findingType, protoData) ?: "",
    affectedScopes = inAppLocationsList.mapNotNull { it.toDomain() },
    context = protoData?.toFindingContext() ?: FindingContext.EMPTY,
    jsonPayload = jsonPayload,
  )
}

private fun ProtoFindingData.toFindingContext(): FindingContext =
  when (dataTypeCase) {
    ProtoFindingData.DataTypeCase.LARGE_SCREENS_LAYOUT -> largeScreensLayout.eventsList.toVisualFindingContext()
    ProtoFindingData.DataTypeCase.LARGE_SCREENS_VISUAL -> largeScreensVisual.eventsList.toVisualFindingContext()
    ProtoFindingData.DataTypeCase.LARGE_SCREENS_CONTINUITY ->
      FindingContext(
        occurrences =
          largeScreensContinuity.eventsList.mapNotNull { event ->
            val visualEvidences =
              event.evidence.stepsList.mapIndexedNotNull { stepIndex, step ->
                step.capture.toVisualEvidence(
                  label = formatContinuityStepLabel(stepIndex + 1, step),
                  highlighted = step.hasShowsIssue() && step.showsIssue,
                )
              }
            val device = if (event.hasDeviceInfo()) event.deviceInfo.toDomain() else null
            if (visualEvidences.isEmpty() && device == null) {
              null
            } else {
              FindingOccurrence(deviceInfo = device, visualEvidences = visualEvidences)
            }
          }
      )
    ProtoFindingData.DataTypeCase.LARGE_SCREENS_CRASH ->
      largeScreensCrash.run {
        FindingContext(
          occurrences =
            eventsList.mapNotNull { event ->
              val stackTrace = event.stackTrace.takeIf { it.isNotBlank() }?.let { StackTrace(raw = it) }
              val device = if (event.hasDeviceInfo()) event.deviceInfo.toDomain() else null
              if (stackTrace == null && device == null) {
                null
              } else {
                FindingOccurrence(deviceInfo = device, stackTrace = stackTrace)
              }
            }
        )
      }
    // TODO(b/579509763): Populate FindingContext for Vitals findings (VITALS_ANOMALY, VITALS_BAD_BEHAVIOR,
    //  VITALS_DEVICE_BAD_BEHAVIOR) to either fetch device breakdowns and top crash/ANR stack traces via the
    //  Play Developer Reporting API, or navigate the user to the Android Vitals tab with matching filters.
    else -> FindingContext.EMPTY
  }

private fun List<ProtoLargeScreensVisualEvent>.toVisualFindingContext(): FindingContext =
  FindingContext(
    occurrences =
      mapNotNull { event ->
        val visualEvidences = event.capturesList.mapNotNull { capture -> capture.toVisualEvidence() }
        val device = if (event.hasDeviceInfo()) event.deviceInfo.toDomain() else null
        if (visualEvidences.isEmpty() && device == null) {
          null
        } else {
          FindingOccurrence(deviceInfo = device, visualEvidences = visualEvidences)
        }
      }
  )

private fun ProtoLargeScreensScreenCapture.toVisualEvidence(label: String? = null, highlighted: Boolean = false): VisualEvidence? =
  screenshotUrl
    .takeIf { it.isNotBlank() }
    ?.let { url ->
      VisualEvidence(
        url = url,
        label = label,
        uiHierarchyUrl = uiHierarchyDownloadUrl.takeIf { it.isNotBlank() },
        highlighted = highlighted,
      )
    }

private fun formatContinuityStepLabel(stepNumber: Int, step: ProtoLargeScreensEvidenceStep): String {
  val changeText =
    when (step.configurationChange) {
      ProtoLargeScreensEvidenceStep.ConfigurationChange.NONE -> "Initial state"
      ProtoLargeScreensEvidenceStep.ConfigurationChange.FOLD -> "Fold"
      ProtoLargeScreensEvidenceStep.ConfigurationChange.UNFOLD -> "Unfold"
      ProtoLargeScreensEvidenceStep.ConfigurationChange.ROTATE -> "Rotate"
      ProtoLargeScreensEvidenceStep.ConfigurationChange.RESIZE -> "Resize"
      ProtoLargeScreensEvidenceStep.ConfigurationChange.CONFIGURATION_CHANGE_UNSPECIFIED,
      ProtoLargeScreensEvidenceStep.ConfigurationChange.UNRECOGNIZED -> null
    }
  val base = if (changeText != null) "Step $stepNumber: $changeText" else "Step $stepNumber"
  return if (step.hasShowsIssue() && step.showsIssue) "$base (Issue detected)" else base
}

private fun ProtoLargeScreensDeviceInfo.toDomain(): DeviceInfo? =
  DeviceInfo(
      deviceBrandName = deviceBrand,
      deviceModel = deviceModel,
      androidVersion = androidVersion,
      androidSdkVersion = if (hasAndroidSdkVersion()) androidSdkVersion else null,
    )
    .takeIf { it.displayName.isNotBlank() || it.androidVersionDisplayText.isNotBlank() }

private fun ProtoInAppLocation.toDomain(): AffectedScope? =
  when (locationTypeCase) {
    ProtoInAppLocation.LocationTypeCase.ARTIFACT -> AffectedScope.Artifact(artifact.versionCode)
    ProtoInAppLocation.LocationTypeCase.RELEASE -> AffectedScope.Release(release.releaseName)
    else -> {
      LOG.warn("Encountered unknown location type: $locationTypeCase")
      null
    }
  }
