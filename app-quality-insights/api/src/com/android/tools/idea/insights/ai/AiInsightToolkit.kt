/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.insights.ai

import com.android.tools.idea.gservices.DevServicesDeprecationData
import com.android.tools.idea.gservices.DevServicesDeprecationDataProvider
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.client.AiInsightCache
import com.android.tools.idea.insights.client.runGrpcCatching
import com.android.tools.idea.insights.experiments.InsightFeedback
import com.android.tools.idea.insights.model.connection.Connection
import com.android.tools.idea.insights.model.event.Event
import com.android.tools.idea.insights.model.issue.FailureType
import com.android.tools.idea.insights.model.issue.IssueId
import com.android.tools.idea.insights.persistence.AppInsightsSettings
import com.google.gct.login2.GoogleLoginService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.VisibleForTesting

// TODO(b/517599039): Replace the AiInsightBaseToolkit / AiInsightToolkit / FindingsInsightToolkit inheritance split
//  with a unified composition-based insight pipeline (shared fetch/cache/precondition pipeline, preferences, and assistant).
/** Base class for AI insight toolkits, providing shared features not tied to specific data models. */
open class AiInsightBaseToolkit(protected val project: Project) {
  /** Invokes onboarding action. */
  fun showOnboarding() {
    AiInsightContributor.getFirstAvailableContributor()?.showOnboarding(project)
  }

  /** Returns true if there is a model selected in the contributor. */
  fun isModelAvailable(): Boolean = AiInsightContributor.getFirstAvailableContributor()?.isModelAvailable() ?: false

  /** Delegates handling model assignment to the insight contributor */
  fun handleModelAssignment() = AiInsightContributor.getFirstAvailableContributor()?.handleModelAssignment(project)

  /**
   * Provides deprecation data related to AI insights only. For deprecation data related to AQI see
   * [com.android.tools.idea.insights.AppInsightsConfigurationManager]
   */
  val insightDeprecationData: DevServicesDeprecationData
    get() {
      val geminiData = getDeprecationData("gemini/gemini", "Gemini")
      return if (geminiData.isUnsupported()) {
        geminiData
      } else {
        getDeprecationData("aqi/insights", "Insights")
      }
    }

  fun setAutoGenerate(enabled: Boolean) {
    val user =
      getUser()
        ?: run {
          thisLogger().warn("Could not get user email to enable auto generate")
          return
        }
    if (enabled) {
      project.service<AppInsightsSettings>().enableAutoGenerate(user)
    } else {
      project.service<AppInsightsSettings>().disableAutoGenerate(user)
    }
  }

  fun isAutoGenerateEnabled() = project.service<AppInsightsSettings>().isInsightAutoGenerateEnabled(getUser() ?: "")

  private fun getUser() = GoogleLoginService.instance.getEmail()

  private fun getDeprecationData(service: String, userFriendlyServiceName: String) =
    DevServicesDeprecationDataProvider.getInstance().getCurrentDeprecationData(service, userFriendlyServiceName)
}

/** Exposes Event-based AI related tools to AQI (such as Crashlytics and Vitals). */
abstract class AiInsightToolkit(project: Project, private val insightCache: AiInsightCache = AiInsightCache()) :
  AiInsightBaseToolkit(project) {

  /**
   * Validates whether an AI insight can be fetched for the [fetchInsight] call.
   *
   * This acts as a pre-fetch check. If this method returns a non-null [LoadingState.Done], the fetch process is short-circuited and the
   * returned state is used as the result.
   *
   * @param failureType the type of failure (e.g., CRASH, ANR).
   * @param event the specific event/occurrence of the issue.
   * @return a [LoadingState.Done] result if the fetch should be intercepted, or null to proceed.
   */
  abstract suspend fun validateFetchInsightPrecondition(failureType: FailureType, event: Event): LoadingState.Done<AiInsight>?

  /**
   * Fetches an AI insight for the given parameters.
   *
   * This method handles precondition validation, cache lookups, and auto-generation checks before requesting a new insight from an
   * available contributor.
   */
  suspend fun fetchInsight(
    connection: Connection,
    issueId: IssueId,
    variantId: String?,
    failureType: FailureType,
    event: Event,
    forceGenerateNewInsight: Boolean = false,
    generateInsightStartedCallback: suspend () -> Unit = {},
  ): LoadingState.Done<AiInsight> {
    validateFetchInsightPrecondition(failureType, event)?.let {
      return it
    }
    if (!forceGenerateNewInsight) {
      getCachedInsight(connection, issueId, variantId)?.let {
        return LoadingState.Ready(it)
      }
    }
    if (!isAutoGenerateEnabled() && !forceGenerateNewInsight) {
      return LoadingState.InsightAutogenerateDisabled
    }
    generateInsightStartedCallback()
    val failure = LoadingState.UnknownFailure("Unable to fetch insight for the selected issue.")
    val contributor =
      AiInsightContributor.getFirstAvailableContributor() ?: return LoadingState.Unauthorized("No insight contributor found")
    return runGrpcCatching(failure) {
      val insight = contributor.fetchInsight(project, AiInsightRequest(createCrashPrompt(event))).copy(event = event)
      insightCache.putAiInsight(connection, issueId, variantId, insight)
      LoadingState.Ready(insight)
    }
  }

  fun updateInsightFeedback(connection: Connection, issueId: IssueId, variantId: String?, feedback: InsightFeedback) {
    val cachedInsight = getCachedInsight(connection, issueId, variantId) ?: return

    insightCache.putAiInsight(connection, issueId, variantId, cachedInsight.copy(feedback = feedback))
  }

  // Always prefer the insight generated with context regardless of current context sharing setting.
  private fun getCachedInsight(connection: Connection, issueId: IssueId, variantId: String?): AiInsight? =
    insightCache.getAiInsight(connection, issueId, variantId)

  companion object {
    private val GEMINI_INSIGHT_PROMPT_FORMAT =
      """
      Explain this exception from my app running on %s with Android version %s:
      Exception:
      ```
      %s
      ```
      """
        .trimIndent()

    @VisibleForTesting
    fun createCrashPrompt(event: Event): String {
      val deviceName = event.eventData.device.let { "${it.manufacturer} ${it.model}" }
      val apiLevel = event.eventData.operatingSystemInfo.displayVersion
      val sanitizedArgs = arrayOf(deviceName, apiLevel, event.prettyStackTrace()).map { it.sanitizeForPrompt() }.toTypedArray()
      return String.format(GEMINI_INSIGHT_PROMPT_FORMAT, *sanitizedArgs).trim()
    }
  }
}

/** Strips triple backticks so this content cannot open or close a Markdown code fence when embedded in an [AiInsightRequest.prompt]. */
fun String.sanitizeForPrompt(): String = replace("```", "")
