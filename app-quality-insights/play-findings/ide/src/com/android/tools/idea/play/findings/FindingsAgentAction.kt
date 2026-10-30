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
import com.android.tools.idea.findings.model.FindingOccurrence
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

/** Number of occurrences whose capture assets are downloaded and attached to a single request. */
private const val OCCURRENCE_ASSET_LIMIT = 1

/** Submits [query] to the Agent tool window with [finding]'s JSON payload, UI hierarchy dumps, and screenshots attached. */
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
    // Attach the finding JSON and any text files as hidden IDE context so the user's chat bubble only displays the short `query`.
    // An empty [0, 0) text range covers no part of `query`, so no inline reference chip is rendered in the message bubble.
    // TODO(b/581970782): Improve prompt framing for the hidden Reference context so the model reliably reads and correlates the finding
    //  JSON, `[Image 1..N]` blobs, `[Text Attachment 1..M]` text/XML attachments, and any unfetched URLs.
    val references = buildList {
      add(
        LlmUserQueryPayload.Reference(
          promptString = resolved.json,
          textRangeStart = 0,
          textRangeEnd = 0,
        )
      )
      for (attachment in resolved.textAttachments) {
        add(
          LlmUserQueryPayload.Reference(
            promptString = "${attachment.label}\n${attachment.content}",
            textRangeStart = 0,
            textRangeEnd = 0,
          )
        )
      }
    }
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

private enum class AssetKind {
  IMAGE,
  TEXT,
}

private sealed interface ResolvedAsset {
  data class Image(val blob: LlmBlob) : ResolvedAsset

  data class TextFile(val content: String) : ResolvedAsset
}

internal data class TextAttachment(val label: String, val content: String)

internal data class ResolvedPayload(
  val json: String,
  val textAttachments: List<TextAttachment>,
  val images: List<LlmBlob>,
)

/**
 * Downloads screenshots and UI hierarchy dumps from [AppFinding.context] and replaces their URLs in [AppFinding.jsonPayload] with numbered
 * `[Image N]` and `[Text Attachment N]` placeholders (keeping the original URL if an asset fails to load). Assets are pre-downloaded here
 * rather than left as URLs for the agent because URL fetching across built-in and ACP agents is restricted or agent-dependent (e.g.,
 * per-URL approval, no image fetching, or stripped XML).
 *
 * Returned text is sanitized for prompts. Runs on [Dispatchers.Default], so it is safe to call from the EDT.
 */
@VisibleForTesting
internal suspend fun AppFinding.resolvePayload(assetLoader: FindingsAssetLoader): ResolvedPayload =
  withContext(Dispatchers.Default) {
    // Only resolve assets for the first OCCURRENCE_ASSET_LIMIT occurrences; remaining occurrences keep their URLs in the JSON.
    // TODO(b/581970782): Re-evaluate upfront URL downloading (e.g., on-demand fetching via a tool), attach the occurrence selected in
    //  the details panel instead of the first one, and cap per-file text size.
    val assetUrls = context.occurrences.take(OCCURRENCE_ASSET_LIMIT).collectAssetUrls()
    if (assetUrls.isEmpty()) {
      return@withContext ResolvedPayload(json = jsonPayload.sanitizeForPrompt(), textAttachments = emptyList(), images = emptyList())
    }

    val permits = Semaphore(MAX_CONCURRENT_ASSET_LOADS)
    val loadedAssets =
      assetUrls.map { (url, kind) -> async { url to permits.withPermit { loadFindingAsset(url, kind, assetLoader) } } }.awaitAll()

    var resolvedJson = jsonPayload.sanitizeForPrompt()
    val textAttachments = mutableListOf<TextAttachment>()
    val images = mutableListOf<LlmBlob>()
    // Match each URL with its surrounding quotes so only JSON values that are exactly this URL get replaced, not URLs inside text or
    // longer URLs that start with it. StudioFindingsClient keeps characters like '=' as-is in jsonPayload (instead of writing them as
    // \u003d), so these values are the same text as VisualEvidence.url.
    for ((url, asset) in loadedAssets) {
      when (asset) {
        null -> Unit
        is ResolvedAsset.Image -> {
          images += asset.blob
          resolvedJson = resolvedJson.replace("\"$url\"", "\"[Image ${images.size}]\"")
        }
        is ResolvedAsset.TextFile -> {
          val label = "[Text Attachment ${textAttachments.size + 1}]"
          resolvedJson = resolvedJson.replace("\"$url\"", "\"$label\"")
          textAttachments += TextAttachment(label, asset.content)
        }
      }
    }
    ResolvedPayload(
      json = resolvedJson,
      textAttachments = textAttachments,
      images = images,
    )
  }

private fun List<FindingOccurrence>.collectAssetUrls(): Map<String, AssetKind> {
  val urls = linkedMapOf<String, AssetKind>()
  for (occurrence in this) {
    for (evidence in occurrence.visualEvidences) {
      if (evidence.url.isNotBlank()) {
        urls.putIfAbsent(evidence.url, AssetKind.IMAGE)
      }
      val hierarchyUrl = evidence.uiHierarchyUrl?.takeIf { it.isNotBlank() }
      if (hierarchyUrl != null) {
        urls.putIfAbsent(hierarchyUrl, AssetKind.TEXT)
      }
    }
  }
  return urls
}

private suspend fun loadFindingAsset(url: String, kind: AssetKind, assetLoader: FindingsAssetLoader): ResolvedAsset? {
  return try {
    val asset = assetLoader.loadAsset(url)
    when (kind) {
      AssetKind.IMAGE -> {
        val mimeType = asset.mimeType
        if (mimeType == null || !mimeType.startsWith("image/") || asset.bytes.isEmpty()) {
          LOG.warn("Expected a non-empty image for finding screenshot but got Content-Type=$mimeType, size=${asset.bytes.size}")
          return null
        }
        // TODO(b/517599039): Clicking an image blob chip in the Agent tool window currently creates a new BinaryLightVirtualFile and
        // opens a duplicate editor tab on every click; update the Agent tool window image preview handler to reuse an already-open tab.
        ResolvedAsset.Image(LlmBlob(data = asset.bytes, mimeType = mimeType))
      }
      AssetKind.TEXT -> {
        val content = asset.bytes.toString(Charsets.UTF_8)
        if (content.isNotBlank()) ResolvedAsset.TextFile(content.sanitizeForPrompt()) else null
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LOG.warn("Failed to load finding asset: ${e.javaClass.simpleName}")
    null
  }
}
