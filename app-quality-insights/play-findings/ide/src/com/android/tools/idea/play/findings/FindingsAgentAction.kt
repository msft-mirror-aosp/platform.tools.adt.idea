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
package com.android.tools.idea.play.findings

import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.gemini.GeminiPluginApiV2
import com.android.tools.idea.gemini.LlmBlob
import com.android.tools.idea.gemini.LlmChatInToolWindowResult
import com.android.tools.idea.gemini.LlmFailureReason
import com.android.tools.idea.gemini.LlmUserQueryPayload
import com.android.tools.idea.insights.ai.sanitizeForPrompt
import com.android.tools.idea.play.findings.ui.FindingsAssetLoader
import com.android.tools.idea.project.AndroidNotification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

private val LOG = Logger.getInstance("com.android.tools.idea.play.findings.FindingsAgentAction")

/** Maximum number of finding assets downloaded concurrently for a single finding. */
private const val MAX_CONCURRENT_ASSET_LOADS = 4

/** Submits [query] to the Agent tool window with [finding]'s JSON payload and screenshots attached. */
internal suspend fun submitFindingQueryToAgent(
  project: Project,
  finding: AppFinding,
  query: String,
  assetLoader: FindingsAssetLoader = FindingsAssetLoader.getInstance(project),
) {
  try {
    val api = GeminiPluginApiV2.getInstance()
    // TODO(b/517599039): Revisit whether "Ask AI" needs an availability gate (e.g. to avoid downloading assets when no agent
    //  can run, or to guide agent setup/sign-in) or whether relying on the Agent tool window and RequestNotSubmitted is sufficient.
    // TODO(b/585184654): Decide what to show users while assets are being resolved, before the Agent chat opens.
    val resolved = finding.resolvePayload(assetLoader)
    // Attach the finding JSON as hidden IDE context so the user's chat bubble only displays the short `query`.
    // An empty [0, 0) text range covers no part of `query`, so no inline reference chip is rendered in the message bubble.
    val references =
      listOf(
        LlmUserQueryPayload.Reference(
          promptString = resolved.json,
          textRangeStart = 0,
          textRangeEnd = 0,
        )
      )
    // TODO(b/584984398): Support attaching or sending finding context to the currently active Agent chat session instead of always
    // starting a new conversation.
    when (
      val result =
        api.submitQueryWithPayloadsInToolWindow(
          project = project,
          query = query,
          blobs = resolved.images,
          payloads = references,
        )
    ) {
      is LlmChatInToolWindowResult.Success -> {
        LOG.info("Successfully submitted query to Agent tool window.")
      }
      is LlmChatInToolWindowResult.RequestNotSubmitted -> {
        LOG.warn("Failed to submit query to Agent tool window: ${result.reason}")
        val message =
          when (result.reason) {
            LlmFailureReason.NO_MODELS_AVAILABLE -> "Please ensure an AI model is configured and available."
          }
        AndroidNotification.getInstance(project).showBalloon("Play Findings", message, NotificationType.WARNING)
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LOG.warn("Exception while submitting query to Agent tool window", e)
  }
}

internal data class ResolvedPayload(val json: String, val images: List<LlmBlob>)

/**
 * Downloads screenshots from [AppFinding.context] and replaces their URLs in [AppFinding.jsonPayload] with `[Attachment 1..N]`
 * placeholders. If a screenshot fails to load, its original URL is kept in the JSON.
 *
 * Returned text is sanitized for prompts. Runs on [Dispatchers.Default], so it is safe to call from the EDT.
 */
// TODO(b/581970782): Cap the number of attached screenshots for large multi-capture findings
//  (concurrent downloads are already capped by MAX_CONCURRENT_ASSET_LOADS).
@VisibleForTesting
internal suspend fun AppFinding.resolvePayload(assetLoader: FindingsAssetLoader): ResolvedPayload =
  withContext(Dispatchers.Default) {
    val screenshotUrls = context.visualEvidences.map { it.url }.filter { it.isNotBlank() }.distinct()
    if (screenshotUrls.isEmpty()) {
      return@withContext ResolvedPayload(json = jsonPayload.sanitizeForPrompt(), images = emptyList())
    }

    val permits = Semaphore(MAX_CONCURRENT_ASSET_LOADS)
    val loadedScreenshots =
      screenshotUrls.map { url -> async { url to permits.withPermit { loadScreenshot(url, assetLoader) } } }.awaitAll()

    var resolvedJson = jsonPayload.sanitizeForPrompt()
    val images = mutableListOf<LlmBlob>()
    // Match each URL with its surrounding quotes so only JSON values that are exactly this URL get replaced, not URLs inside text or
    // longer URLs that start with it. StudioFindingsClient keeps characters like '=' as-is in jsonPayload (instead of writing them as
    // \u003d), so these values are the same text as VisualEvidence.url.
    for ((url, blob) in loadedScreenshots) {
      if (blob == null) continue
      images += blob
      resolvedJson = resolvedJson.replace("\"$url\"", "\"[Attachment ${images.size}]\"")
    }
    ResolvedPayload(json = resolvedJson, images = images)
  }

private suspend fun loadScreenshot(url: String, assetLoader: FindingsAssetLoader): LlmBlob? {
  return try {
    val asset = assetLoader.loadAsset(url)
    val mimeType = asset.mimeType
    if (mimeType == null || !mimeType.startsWith("image/") || asset.bytes.isEmpty()) {
      LOG.warn("Expected a non-empty image for finding screenshot but got Content-Type=$mimeType, size=${asset.bytes.size}")
      return null
    }
    // TODO(b/517599039): Clicking an image blob chip in the Agent tool window currently creates a new BinaryLightVirtualFile and
    // opens a duplicate editor tab on every click; update the Agent tool window image preview handler to reuse an already-open tab.
    LlmBlob(data = asset.bytes, mimeType = mimeType)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LOG.warn("Failed to load finding asset: ${e.javaClass.simpleName}")
    null
  }
}
