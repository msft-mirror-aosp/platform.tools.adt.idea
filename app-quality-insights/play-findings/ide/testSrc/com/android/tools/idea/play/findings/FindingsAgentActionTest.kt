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
import com.android.tools.idea.findings.model.FindingCategory
import com.android.tools.idea.findings.model.FindingContext
import com.android.tools.idea.findings.model.FindingSeverity
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.gemini.GeminiPluginApiV2
import com.android.tools.idea.gemini.LlmBlob
import com.android.tools.idea.gemini.LlmChatInEditorResult
import com.android.tools.idea.gemini.LlmChatInToolWindowResult
import com.android.tools.idea.gemini.LlmConversationTarget
import com.android.tools.idea.gemini.LlmEditorTarget
import com.android.tools.idea.gemini.LlmFailureReason
import com.android.tools.idea.gemini.LlmUserQueryPayload
import com.android.tools.idea.play.findings.ui.FindingsAssetLoader
import com.android.tools.idea.play.findings.ui.LoadedAsset
import com.android.tools.idea.project.AndroidNotification
import com.google.common.truth.Truth.assertThat
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class FindingsAgentActionTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(disposableRule)

  private fun fakeGeminiPluginApi(
    available: Boolean = true,
    onSubmitWithPayloads: suspend (query: String, blobs: List<LlmBlob>, payloads: List<LlmUserQueryPayload>) -> LlmChatInToolWindowResult =
      { _, _, _ ->
        LlmChatInToolWindowResult.Success("conv-id")
      },
  ): GeminiPluginApiV2 =
    object : GeminiPluginApiV2 {
      override val availability = MutableStateFlow(available)

      override fun isAvailable(): Boolean = availability.value

      override suspend fun submitQueryInEditor(
        project: Project,
        query: String,
        blobs: List<LlmBlob>,
        payloads: List<LlmUserQueryPayload>,
        editorTarget: LlmEditorTarget,
        conversationTarget: LlmConversationTarget,
      ): LlmChatInEditorResult = LlmChatInEditorResult.Success("conv-id", "tab-id")

      override suspend fun submitQueryWithPayloadsInToolWindow(
        project: Project,
        query: String,
        blobs: List<LlmBlob>,
        payloads: List<LlmUserQueryPayload>,
        conversationTarget: LlmConversationTarget,
      ): LlmChatInToolWindowResult = onSubmitWithPayloads(query, blobs, payloads)

      override suspend fun submitQueryInToolWindow(
        project: Project,
        query: String,
        blobs: List<LlmBlob>,
        fileReferences: List<Path>,
        conversationTarget: LlmConversationTarget,
      ): LlmChatInToolWindowResult = LlmChatInToolWindowResult.Success("conv-id")
    }

  @Test
  fun testResolvePayload_ignoresNonAssetUrlsAndReusesCache(): Unit = runBlocking {
    val image1Bytes = byteArrayOf(1, 2, 3, 4)
    val image2Bytes = byteArrayOf(5, 6, 7, 8)
    val networkFetchUrls = Collections.synchronizedList(mutableListOf<String>())
    val assetLoader = FindingsAssetLoader { url ->
      networkFetchUrls.add(url)
      when (url) {
        "https://play.google.com/artifacts/1" -> LoadedAsset(image1Bytes, "image/png")
        "https://play.google.com/artifacts/broken.png" -> throw RuntimeException("Expired URL")
        "https://play.google.com/artifacts/3.png" -> LoadedAsset(image2Bytes, "image/webp")
        else -> error("Unexpected URL fetched: $url")
      }
    }

    // Simulate FindingsDetailsPanel (ImagePreview) having already loaded artifacts/1 into the shared cache.
    assetLoader.load("https://play.google.com/artifacts/1")
    networkFetchUrls.clear()

    val jsonPayload =
      """{"learnMoreUrl":"https://developer.android.com/large-screens","reviewMessage":"See https://play.google.com/docs","steps":[{"screenshot":"https://play.google.com/artifacts/1"},{"screenshot":"https://play.google.com/artifacts/broken.png"},{"screenshot":"https://play.google.com/artifacts/3.png"}]}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            visualEvidences =
              listOf(
                VisualEvidence(url = "https://play.google.com/artifacts/1"),
                VisualEvidence(url = "https://play.google.com/artifacts/broken.png"),
                VisualEvidence(url = "https://play.google.com/artifacts/3.png"),
              )
          ),
      )

    val multimodal = finding.resolvePayload(assetLoader)
    assertThat(multimodal.json)
      .isEqualTo(
        """{"learnMoreUrl":"https://developer.android.com/large-screens","reviewMessage":"See https://play.google.com/docs","steps":[{"screenshot":"[Attachment 1]"},{"screenshot":"https://play.google.com/artifacts/broken.png"},{"screenshot":"[Attachment 2]"}]}"""
      )
    assertThat(multimodal.images).hasSize(2)
    assertThat(multimodal.images[0].data).isEqualTo(image1Bytes)
    assertThat(multimodal.images[0].mimeType).isEqualTo("image/png")
    assertThat(multimodal.images[1].data).isEqualTo(image2Bytes)
    assertThat(multimodal.images[1].mimeType).isEqualTo("image/webp")

    // Non-asset URLs (learnMoreUrl, reviewMessage) were never fetched, and artifacts/1 was served from cache.
    assertThat(networkFetchUrls).containsExactly("https://play.google.com/artifacts/broken.png", "https://play.google.com/artifacts/3.png")
  }

  @Test
  fun testResolvePayload_handlesDuplicateUrlsPrefixOverlapProseAndNonImageAssets(): Unit = runBlocking {
    val fetchedUrls = Collections.synchronizedList(mutableListOf<String>())
    val assetLoader = FindingsAssetLoader { url ->
      fetchedUrls += url
      when (url) {
        "https://example.com/1" -> LoadedAsset(byteArrayOf(1, 2), "image/png")
        "https://example.com/10" -> LoadedAsset(byteArrayOf(3, 4), "image/png")
        "https://example.com/recording.mp4" -> LoadedAsset(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte()), "video/mp4")
        else -> error("Unexpected URL: $url")
      }
    }

    val jsonPayload =
      """{"note":"See https://example.com/1 in docs","first":"https://example.com/1","prefixOverlap":"https://example.com/10","duplicate":"https://example.com/1","video":"https://example.com/recording.mp4"}"""
    val finding =
      AppFinding(
        name = "findings/overlap_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            visualEvidences =
              listOf(
                VisualEvidence(url = "https://example.com/1"),
                VisualEvidence(url = "https://example.com/10"),
                VisualEvidence(url = "https://example.com/1"),
                VisualEvidence(url = "https://example.com/recording.mp4"),
              )
          ),
      )
    val multimodal = finding.resolvePayload(assetLoader)

    assertThat(fetchedUrls).containsExactly("https://example.com/1", "https://example.com/10", "https://example.com/recording.mp4")

    assertThat(multimodal.json)
      .isEqualTo(
        """{"note":"See https://example.com/1 in docs","first":"[Attachment 1]","prefixOverlap":"[Attachment 2]","duplicate":"[Attachment 1]","video":"https://example.com/recording.mp4"}"""
      )
    assertThat(multimodal.images).hasSize(2)
  }

  @Test
  fun testResolvePayload_fetchesAssetsInParallelWithDeterministicAttachmentOrder(): Unit = runBlocking {
    val allStartedLatch = CountDownLatch(3)
    val url1 = "https://example.com/1.png"
    val url2 = "https://example.com/2.png"
    val url3 = "https://example.com/3.png"
    val assetLoader = FindingsAssetLoader { url ->
      allStartedLatch.countDown()
      check(allStartedLatch.await(5, TimeUnit.SECONDS)) { "Expected concurrent asset fetches to overlap on IO threads" }
      LoadedAsset(url.toByteArray(), "image/png")
    }

    val jsonPayload = """{"captures":[{"png":"$url1"},{"png":"$url2"},{"png":"$url3"}]}"""
    val finding =
      AppFinding(
        name = "findings/parallel_test",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = jsonPayload,
        context =
          FindingContext(visualEvidences = listOf(VisualEvidence(url = url1), VisualEvidence(url = url2), VisualEvidence(url = url3))),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.json).isEqualTo("""{"captures":[{"png":"[Attachment 1]"},{"png":"[Attachment 2]"},{"png":"[Attachment 3]"}]}""")
    assertThat(formatted.images.map { it.data.toString(Charsets.UTF_8) }).containsExactly(url1, url2, url3).inOrder()
  }

  @Test
  fun testResolvePayload_limitsConcurrentAssetLoads(): Unit = runBlocking {
    val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)
    val assetLoader = FindingsAssetLoader { url ->
      val current = inFlight.incrementAndGet()
      maxInFlight.accumulateAndGet(current, ::maxOf)
      Thread.sleep(50)
      inFlight.decrementAndGet()
      LoadedAsset(url.toByteArray(), "image/png")
    }
    val captures = (1..8).map { VisualEvidence(url = "https://example.com/$it.png") }
    val finding =
      AppFinding(
        name = "findings/many_captures",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = """{"captures":[${captures.joinToString(",") { "\"${it.url}\"" }}]}""",
        context = FindingContext(visualEvidences = captures),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.images).hasSize(8)
    assertThat(maxInFlight.get()).isAtMost(4)
  }

  @Test
  fun testResolvePayload_requiresNonEmptyImageForScreenshots(): Unit = runBlocking {
    val assetLoader = FindingsAssetLoader { url ->
      when (url) {
        "https://example.com/error-page.png" -> LoadedAsset("<html>Access denied</html>".toByteArray(), "text/html")
        "https://example.com/untyped.png" -> LoadedAsset(byteArrayOf(1, 2), null)
        "https://example.com/empty.png" -> LoadedAsset(byteArrayOf(), "image/png")
        "https://example.com/ok.png" -> LoadedAsset(byteArrayOf(3, 4), "image/png")
        else -> error("Unexpected URL: $url")
      }
    }
    val finding =
      AppFinding(
        name = "findings/content_types",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload =
          """{"a":"https://example.com/error-page.png","b":"https://example.com/untyped.png","c":"https://example.com/empty.png","d":"https://example.com/ok.png"}""",
        context =
          FindingContext(
            visualEvidences =
              listOf(
                VisualEvidence(url = "https://example.com/error-page.png"),
                VisualEvidence(url = "https://example.com/untyped.png"),
                VisualEvidence(url = "https://example.com/empty.png"),
                VisualEvidence(url = "https://example.com/ok.png"),
              )
          ),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.images).hasSize(1)
    assertThat(formatted.images[0].data).isEqualTo(byteArrayOf(3, 4))
    assertThat(formatted.json)
      .isEqualTo(
        """{"a":"https://example.com/error-page.png","b":"https://example.com/untyped.png","c":"https://example.com/empty.png","d":"[Attachment 1]"}"""
      )
  }

  @Test
  fun testSubmitFindingQueryToAgent_passesFindingContextPayloadAndImageBlobs(): Unit = runBlocking {
    var submittedQuery: String? = null
    var submittedBlobs: List<LlmBlob>? = null
    var submittedPayloads: List<LlmUserQueryPayload>? = null

    val fakeGemini = fakeGeminiPluginApi { query, blobs, payloads ->
      submittedQuery = query
      submittedBlobs = blobs
      submittedPayloads = payloads
      LlmChatInToolWindowResult.Success("conv-id")
    }
    ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(fakeGemini), disposableRule.disposable)

    val screenshot1Bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    val screenshot2Bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    val screenshot1Url = "https://example.com/1.png"
    val brokenScreenshotUrl = "https://example.com/broken.png"
    val screenshot2Url = "https://example.com/2.webp"
    val requestedUrls = Collections.synchronizedList(mutableListOf<String>())
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url ->
        requestedUrls.add(url)
        when (url) {
          screenshot1Url -> LoadedAsset(screenshot1Bytes, "image/png")
          brokenScreenshotUrl -> throw RuntimeException("Expired URL")
          screenshot2Url -> LoadedAsset(screenshot2Bytes, "image/webp")
          else -> error("Unexpected URL: $url")
        }
      },
      disposableRule.disposable,
    )

    val jsonPayload =
      """{"note":"```fenced```","steps":[{"screenshot":"$screenshot1Url"},{"screenshot":"$brokenScreenshotUrl"},{"screenshot":"$screenshot2Url"}]}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            visualEvidences =
              listOf(
                VisualEvidence(url = screenshot1Url),
                VisualEvidence(url = brokenScreenshotUrl),
                VisualEvidence(url = screenshot2Url),
              )
          ),
      )

    submitFindingQueryToAgent(
      project = projectRule.project,
      finding = finding,
      query = "Suggest a fix for the Play Console finding \"App does not use entire screen\".",
    )

    assertThat(submittedQuery).isEqualTo("Suggest a fix for the Play Console finding \"App does not use entire screen\".")
    assertThat(requestedUrls).containsExactly(screenshot1Url, brokenScreenshotUrl, screenshot2Url)
    assertThat(submittedBlobs).isNotNull()
    assertThat(submittedBlobs).hasSize(2)
    assertThat(submittedBlobs!![0].data).isEqualTo(screenshot1Bytes)
    assertThat(submittedBlobs!![0].mimeType).isEqualTo("image/png")
    assertThat(submittedBlobs!![1].data).isEqualTo(screenshot2Bytes)
    assertThat(submittedBlobs!![1].mimeType).isEqualTo("image/webp")
    assertThat(submittedPayloads)
      .containsExactly(
        LlmUserQueryPayload.Reference(
          promptString =
            """{"note":"fenced","steps":[{"screenshot":"[Attachment 1]"},{"screenshot":"$brokenScreenshotUrl"},{"screenshot":"[Attachment 2]"}]}""",
          textRangeStart = 0,
          textRangeEnd = 0,
        )
      )
  }

  @Test
  fun testSubmitFindingQueryToAgent_whenGeminiUnavailable_stillSubmitsQuery(): Unit = runBlocking {
    var submittedQuery: String? = null
    ExtensionTestUtil.maskExtensions(
      GeminiPluginApiV2.EP_NAME,
      listOf(
        fakeGeminiPluginApi(available = false) { query, _, _ ->
          submittedQuery = query
          LlmChatInToolWindowResult.Success("conv-id")
        }
      ),
      disposableRule.disposable,
    )
    val finding =
      AppFinding(
        name = "findings/drm_1",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Bad version of your app protection service",
      )

    submitFindingQueryToAgent(projectRule.project, finding, "Suggest a fix")

    assertThat(submittedQuery).isEqualTo("Suggest a fix")
  }

  @Test
  fun testSubmitFindingQueryToAgent_whenNoModelsAvailable_showsWarningBalloon(): Unit = runBlocking {
    val mockNotification = mock<AndroidNotification>()
    projectRule.project.replaceService(AndroidNotification::class.java, mockNotification, disposableRule.disposable)
    val fakeGemini = fakeGeminiPluginApi { _, _, _ ->
      LlmChatInToolWindowResult.RequestNotSubmitted(LlmFailureReason.NO_MODELS_AVAILABLE)
    }
    ExtensionTestUtil.maskExtensions(GeminiPluginApiV2.EP_NAME, listOf(fakeGemini), disposableRule.disposable)

    val finding =
      AppFinding(
        name = "findings/drm_1",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Bad version of your app protection service",
      )

    submitFindingQueryToAgent(projectRule.project, finding, "Explain this finding")

    verify(mockNotification)
      .showBalloon("Play Findings", "Please ensure an AI model is configured and available.", NotificationType.WARNING)
  }

  @Test
  fun testSubmitFindingQueryToAgent_rethrowsCancellationAndSwallowsUnexpectedExceptions() {
    val finding =
      AppFinding(
        name = "findings/drm_1",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Bad version of your app protection service",
      )
    var toThrow: Throwable = CancellationException("Cancelled")
    ExtensionTestUtil.maskExtensions(
      GeminiPluginApiV2.EP_NAME,
      listOf(fakeGeminiPluginApi { _, _, _ -> throw toThrow }),
      disposableRule.disposable,
    )

    assertThrows(CancellationException::class.java) {
      runBlocking { submitFindingQueryToAgent(projectRule.project, finding, "Explain") }
    }

    toThrow = ProcessCanceledException()
    assertThrows(ProcessCanceledException::class.java) {
      runBlocking { submitFindingQueryToAgent(projectRule.project, finding, "Explain") }
    }

    toThrow = IllegalStateException("Unexpected failure")
    runBlocking { submitFindingQueryToAgent(projectRule.project, finding, "Explain") }
  }
}
