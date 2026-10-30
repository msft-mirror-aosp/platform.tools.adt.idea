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
import com.android.tools.idea.findings.model.FindingOccurrence
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

  private val testHierarchyXml =
    """
    <hierarchy rotation="0">
      <node index="0" class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1248,1972]">
        <node index="0" text="Sign in" class="android.widget.Button" bounds="[42,1482][1206,1619]" />
      </node>
    </hierarchy>
    """
      .trimIndent()

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
        "https://play.google.com/artifacts/1.xml" -> LoadedAsset("<hierarchy step=\"1\"/>".toByteArray(), "application/xml")
        "https://play.google.com/artifacts/broken.png" -> throw RuntimeException("Expired URL")
        "https://play.google.com/artifacts/2.xml" -> LoadedAsset("<hierarchy step=\"2\"/>".toByteArray(), "text/xml")
        "https://play.google.com/artifacts/3.png" -> LoadedAsset(image2Bytes, "image/webp")
        else -> error("Unexpected URL fetched: $url")
      }
    }

    // Simulate FindingsDetailsPanel (ImagePreview) having already loaded artifacts/1 into the shared cache.
    assetLoader.load("https://play.google.com/artifacts/1")
    networkFetchUrls.clear()

    val jsonPayload =
      """{"learnMoreUrl":"https://developer.android.com/large-screens","reviewMessage":"See https://play.google.com/docs","steps":[{"screenshot":"https://play.google.com/artifacts/1","hierarchy":"https://play.google.com/artifacts/1.xml"},{"screenshot":"https://play.google.com/artifacts/broken.png","hierarchy":"https://play.google.com/artifacts/2.xml"},{"screenshot":"https://play.google.com/artifacts/3.png"}]}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(
                        url = "https://play.google.com/artifacts/1",
                        uiHierarchyUrl = "https://play.google.com/artifacts/1.xml",
                      ),
                      VisualEvidence(
                        url = "https://play.google.com/artifacts/broken.png",
                        uiHierarchyUrl = "https://play.google.com/artifacts/2.xml",
                      ),
                      VisualEvidence(url = "https://play.google.com/artifacts/3.png"),
                    )
                )
              )
          ),
      )

    val multimodal = finding.resolvePayload(assetLoader)
    assertThat(multimodal.json)
      .isEqualTo(
        """{"learnMoreUrl":"https://developer.android.com/large-screens","reviewMessage":"See https://play.google.com/docs","steps":[{"screenshot":"[Image 1]","hierarchy":"[Text Attachment 1]"},{"screenshot":"https://play.google.com/artifacts/broken.png","hierarchy":"[Text Attachment 2]"},{"screenshot":"[Image 2]"}]}"""
      )
    assertThat(multimodal.images).hasSize(2)
    assertThat(multimodal.images[0].data).isEqualTo(image1Bytes)
    assertThat(multimodal.images[0].mimeType).isEqualTo("image/png")
    assertThat(multimodal.images[1].data).isEqualTo(image2Bytes)
    assertThat(multimodal.images[1].mimeType).isEqualTo("image/webp")
    assertThat(multimodal.textAttachments)
      .containsExactly(
        TextAttachment("[Text Attachment 1]", "<hierarchy step=\"1\"/>"),
        TextAttachment("[Text Attachment 2]", "<hierarchy step=\"2\"/>"),
      )
      .inOrder()

    // Non-asset URLs (learnMoreUrl, reviewMessage) were never fetched, and artifacts/1 was served from cache.
    assertThat(networkFetchUrls)
      .containsExactly(
        "https://play.google.com/artifacts/1.xml",
        "https://play.google.com/artifacts/broken.png",
        "https://play.google.com/artifacts/2.xml",
        "https://play.google.com/artifacts/3.png",
      )
  }

  @Test
  fun testResolvePayload_handlesDuplicateUrlsPrefixOverlapProseAndUnsupportedAssets(): Unit = runBlocking {
    val fetchedUrls = Collections.synchronizedList(mutableListOf<String>())
    val assetLoader = FindingsAssetLoader { url ->
      fetchedUrls += url
      when (url) {
        "https://example.com/1" -> LoadedAsset(byteArrayOf(1, 2), "image/png")
        "https://example.com/10" -> LoadedAsset("<hierarchy id=\"10\"/>".toByteArray(), "text/xml")
        "https://example.com/blank.xml" -> LoadedAsset("   \n  ".toByteArray(), "text/xml")
        "https://example.com/recording.mp4" -> LoadedAsset(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte()), "video/mp4")
        else -> error("Unexpected URL: $url")
      }
    }

    val jsonPayload =
      """{"note":"See https://example.com/1 in docs","first":"https://example.com/1","prefixOverlap":"https://example.com/10","duplicate":"https://example.com/1","blank":"https://example.com/blank.xml","video":"https://example.com/recording.mp4"}"""
    val finding =
      AppFinding(
        name = "findings/overlap_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = "https://example.com/1", uiHierarchyUrl = "https://example.com/10"),
                      VisualEvidence(url = "https://example.com/1", uiHierarchyUrl = "https://example.com/blank.xml"),
                      VisualEvidence(url = "https://example.com/recording.mp4"),
                    )
                )
              )
          ),
      )
    val multimodal = finding.resolvePayload(assetLoader)

    assertThat(fetchedUrls)
      .containsExactly(
        "https://example.com/1",
        "https://example.com/10",
        "https://example.com/blank.xml",
        "https://example.com/recording.mp4",
      )

    assertThat(multimodal.json)
      .isEqualTo(
        """{"note":"See https://example.com/1 in docs","first":"[Image 1]","prefixOverlap":"[Text Attachment 1]","duplicate":"[Image 1]","blank":"https://example.com/blank.xml","video":"https://example.com/recording.mp4"}"""
      )
    assertThat(multimodal.images).hasSize(1)
    assertThat(multimodal.textAttachments).containsExactly(TextAttachment("[Text Attachment 1]", "<hierarchy id=\"10\"/>"))
  }

  @Test
  fun testResolvePayload_fetchesAssetsInParallelWithDeterministicAttachmentOrder(): Unit = runBlocking {
    val allStartedLatch = CountDownLatch(3)
    val url1 = "https://example.com/1.xml"
    val url2 = "https://example.com/2.xml"
    val url3 = "https://example.com/3.xml"
    val assetLoader = FindingsAssetLoader { url ->
      allStartedLatch.countDown()
      check(allStartedLatch.await(5, TimeUnit.SECONDS)) { "Expected concurrent asset fetches to overlap on IO threads" }
      LoadedAsset("<node url=\"$url\"/>".toByteArray(), "text/xml")
    }

    val jsonPayload = """{"captures":[{"xml":"$url1"},{"xml":"$url2"},{"xml":"$url3"}]}"""
    val finding =
      AppFinding(
        name = "findings/parallel_test",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = "", uiHierarchyUrl = url1),
                      VisualEvidence(url = "", uiHierarchyUrl = url2),
                      VisualEvidence(url = "", uiHierarchyUrl = url3),
                    )
                )
              )
          ),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.json)
      .isEqualTo("""{"captures":[{"xml":"[Text Attachment 1]"},{"xml":"[Text Attachment 2]"},{"xml":"[Text Attachment 3]"}]}""")
    assertThat(formatted.textAttachments)
      .containsExactly(
        TextAttachment("[Text Attachment 1]", "<node url=\"$url1\"/>"),
        TextAttachment("[Text Attachment 2]", "<node url=\"$url2\"/>"),
        TextAttachment("[Text Attachment 3]", "<node url=\"$url3\"/>"),
      )
      .inOrder()
  }

  @Test
  fun testResolvePayload_attachesOnlyFirstOccurrenceAndLimitsConcurrentLoads(): Unit = runBlocking {
    val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)
    val fetchedUrls = Collections.synchronizedList(mutableListOf<String>())
    val assetLoader = FindingsAssetLoader { url ->
      fetchedUrls += url
      val current = inFlight.incrementAndGet()
      maxInFlight.accumulateAndGet(current, ::maxOf)
      Thread.sleep(50)
      inFlight.decrementAndGet()
      if (url.endsWith(".png")) LoadedAsset(byteArrayOf(1, 2), "image/png") else LoadedAsset("<node/>".toByteArray(), "text/xml")
    }
    // 3 occurrences: the first has 5 captures (10 assets), the others 1 each. Only the first occurrence is attached.
    val captures = (1..7).map { VisualEvidence(url = "https://example.com/$it.png", uiHierarchyUrl = "https://example.com/$it.xml") }
    val occurrences =
      listOf(FindingOccurrence(visualEvidences = captures.take(5))) +
        captures.drop(5).map { FindingOccurrence(visualEvidences = listOf(it)) }
    val finding =
      AppFinding(
        name = "findings/many_occurrences",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Layout issue",
        jsonPayload = """{"captures":[${captures.joinToString(",") { "{\"png\":\"${it.url}\",\"xml\":\"${it.uiHierarchyUrl}\"}" }}]}""",
        context = FindingContext(occurrences = occurrences),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.images).hasSize(5)
    assertThat(formatted.textAttachments).hasSize(5)
    assertThat(fetchedUrls)
      .containsExactlyElementsIn((1..5).flatMap { listOf("https://example.com/$it.png", "https://example.com/$it.xml") })
    // The other occurrences keep their URLs in the JSON.
    assertThat(formatted.json).contains(""""png":"[Image 5]","xml":"[Text Attachment 5]"""")
    assertThat(formatted.json).contains(""""png":"https://example.com/6.png","xml":"https://example.com/6.xml"""")
    assertThat(formatted.json).contains(""""png":"https://example.com/7.png","xml":"https://example.com/7.xml"""")
    assertThat(maxInFlight.get()).isAtMost(4)
  }

  @Test
  fun testResolvePayload_attachesHierarchyRegardlessOfContentTypeButRequiresImageMimeForScreenshots(): Unit = runBlocking {
    val assetLoader = FindingsAssetLoader { url ->
      when (url) {
        "https://example.com/octet.xml" -> LoadedAsset("<hierarchy id=\"1\"/>".toByteArray(), "application/octet-stream")
        "https://example.com/untyped.xml" -> LoadedAsset("<hierarchy id=\"2\"/>".toByteArray(), null)
        "https://example.com/error-page.png" -> LoadedAsset("<html>Access denied</html>".toByteArray(), "text/html")
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
          """{"a":"https://example.com/octet.xml","b":"https://example.com/untyped.xml","c":"https://example.com/error-page.png"}""",
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = "https://example.com/error-page.png", uiHierarchyUrl = "https://example.com/octet.xml"),
                      VisualEvidence(url = "https://example.com/error-page.png", uiHierarchyUrl = "https://example.com/untyped.xml"),
                    )
                )
              )
          ),
      )

    val formatted = finding.resolvePayload(assetLoader)

    assertThat(formatted.images).isEmpty()
    assertThat(formatted.json)
      .isEqualTo("""{"a":"[Text Attachment 1]","b":"[Text Attachment 2]","c":"https://example.com/error-page.png"}""")
    assertThat(formatted.textAttachments)
      .containsExactly(
        TextAttachment("[Text Attachment 1]", "<hierarchy id=\"1\"/>"),
        TextAttachment("[Text Attachment 2]", "<hierarchy id=\"2\"/>"),
      )
      .inOrder()
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
    val secondHierarchyXml = "<hierarchy rotation=\"1\" note=\"```fenced```\"/>"
    val screenshot1Url = "https://example.com/1.png"
    val hierarchy1Url = "https://example.com/screen-1.xml"
    val brokenScreenshotUrl = "https://example.com/broken.png"
    val hierarchy2Url = "https://example.com/screen-2.xml"
    val screenshot2Url = "https://example.com/2.webp"
    val requestedUrls = Collections.synchronizedList(mutableListOf<String>())
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url ->
        requestedUrls.add(url)
        when (url) {
          screenshot1Url -> LoadedAsset(screenshot1Bytes, "image/png")
          hierarchy1Url -> LoadedAsset(testHierarchyXml.toByteArray(), "application/xml")
          brokenScreenshotUrl -> throw RuntimeException("Expired URL")
          hierarchy2Url -> LoadedAsset(secondHierarchyXml.toByteArray(), "text/xml")
          screenshot2Url -> LoadedAsset(screenshot2Bytes, "image/webp")
          else -> error("Unexpected URL: $url")
        }
      },
      disposableRule.disposable,
    )

    val jsonPayload =
      """{"steps":[{"screenshot":"$screenshot1Url","hierarchy":"$hierarchy1Url"},{"screenshot":"$brokenScreenshotUrl","hierarchy":"$hierarchy2Url"},{"screenshot":"$screenshot2Url"}]}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
        context =
          FindingContext(
            occurrences =
              listOf(
                FindingOccurrence(
                  visualEvidences =
                    listOf(
                      VisualEvidence(url = screenshot1Url, uiHierarchyUrl = hierarchy1Url),
                      VisualEvidence(url = brokenScreenshotUrl, uiHierarchyUrl = hierarchy2Url),
                      VisualEvidence(url = screenshot2Url),
                    )
                )
              )
          ),
      )

    submitFindingQueryToAgent(
      project = projectRule.project,
      finding = finding,
      query = "Suggest a fix for the Play Console finding \"App does not use entire screen\".",
    )

    assertThat(submittedQuery).isEqualTo("Suggest a fix for the Play Console finding \"App does not use entire screen\".")
    assertThat(requestedUrls).containsExactly(screenshot1Url, hierarchy1Url, brokenScreenshotUrl, hierarchy2Url, screenshot2Url)
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
            """{"steps":[{"screenshot":"[Image 1]","hierarchy":"[Text Attachment 1]"},{"screenshot":"$brokenScreenshotUrl","hierarchy":"[Text Attachment 2]"},{"screenshot":"[Image 2]"}]}""",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
        LlmUserQueryPayload.Reference(
          promptString = "[Text Attachment 1]\n$testHierarchyXml",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
        LlmUserQueryPayload.Reference(
          promptString = "[Text Attachment 2]\n<hierarchy rotation=\"1\" note=\"fenced\"/>",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
      )
      .inOrder()
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
