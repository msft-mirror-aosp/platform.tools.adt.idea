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
package com.android.tools.idea.play.findings.ui

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
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.ai.AiInsight
import com.android.tools.idea.insights.ai.AiInsightContributor
import com.android.tools.idea.insights.ai.AiInsightRequest
import com.android.tools.idea.project.AndroidNotification
import com.google.common.truth.Truth.assertThat
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class FindingsInsightToolkitTest {

  @get:Rule val projectRule = ProjectRule()
  @get:Rule val disposableRule = DisposableRule()

  private val testHierarchyXml =
    """
    <hierarchy rotation="0">
      <node index="0" class="android.widget.FrameLayout" package="com.example.app" bounds="[0,0][1248,1972]">
        <node index="0" text="Sign in" class="android.widget.Button" bounds="[42,1482][1206,1619]" />
      </node>
    </hierarchy>
    """
      .trimIndent()

  private fun replaceAssetLoader(fetcher: (String) -> ByteArray) {
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url -> LoadedAsset(fetcher(url)) },
      disposableRule.disposable,
    )
  }

  private fun fakeGeminiPluginApi(
    onSubmitWithPayloads: suspend (query: String, blobs: List<LlmBlob>, payloads: List<LlmUserQueryPayload>) -> LlmChatInToolWindowResult
  ): GeminiPluginApiV2 =
    object : GeminiPluginApiV2 {
      override val availability = MutableStateFlow(true)

      override fun isAvailable(): Boolean = true

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
        fileReferences: List<java.nio.file.Path>,
        conversationTarget: LlmConversationTarget,
      ): LlmChatInToolWindowResult = LlmChatInToolWindowResult.Success("conv-id")
    }

  private fun findingPrompt(details: String) =
    "Explain this Google Play Console finding for my app and how to fix it:\nFinding:\n```\n$details\n```"

  @Test
  fun testCreateFindingPrompt_withHierarchy(): Unit = runBlocking {
    val hierarchyUrl = "https://example.com/screen-1.xml"
    val screenshotUrl = "https://example.com/1.png"
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url ->
        when (url) {
          screenshotUrl -> LoadedAsset(byteArrayOf(1, 2, 3), "image/png")
          hierarchyUrl -> LoadedAsset(testHierarchyXml.toByteArray(), "application/xml")
          else -> error("Unexpected URL: $url")
        }
      },
      disposableRule.disposable,
    )
    val toolkit = FindingsInsightToolkit(projectRule.project)
    val jsonPayload =
      """{"findingData":{"largeScreensLayout":{"layoutIssueType":"DOES_NOT_USE_ENTIRE_SCREEN","screenshotFifeUrl":"$screenshotUrl","viewHierarchyUrl":"$hierarchyUrl"}}}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
      )

    val prompt = toolkit.createFindingPrompt(finding)

    assertThat(prompt)
      .isEqualTo(
        findingPrompt(jsonPayload.replace(screenshotUrl, "[Screenshot omitted]").replace(hierarchyUrl, "[Attachment 1]")) +
          "\n\n[Attachment 1]\n$testHierarchyXml"
      )
  }

  @Test
  fun testCreateFindingPrompt_withoutHierarchy(): Unit = runBlocking {
    val toolkit = FindingsInsightToolkit(projectRule.project)
    val jsonPayload = """{"findingData":{"drmAppCompat":{"drmPackageName":"com.bad.sdk"}}}"""
    val finding =
      AppFinding(
        name = "findings/drm_1",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Bad version of your app protection service",
        jsonPayload = jsonPayload,
      )

    val prompt = toolkit.createFindingPrompt(finding)

    assertThat(prompt).isEqualTo(findingPrompt(jsonPayload))
  }

  @Test
  fun testCreateFindingPrompt_stripsEmbeddedTripleBackticksInJsonAndAttachments(): Unit = runBlocking {
    val hierarchyUrl = "https://example.com/screen.xml"
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { LoadedAsset("<hierarchy text=\"```escaped```\"/>".toByteArray(), "text/xml") },
      disposableRule.disposable,
    )
    val toolkit = FindingsInsightToolkit(projectRule.project)
    val finding =
      AppFinding(
        name = "findings/drm_2",
        category = FindingCategory.TECHNICAL_QUALITY,
        severity = FindingSeverity.SEVERE,
        displayName = "Bad version of your app protection service",
        jsonPayload = """{"description":"```injected```","hierarchy":"$hierarchyUrl"}""",
      )

    val prompt = toolkit.createFindingPrompt(finding)

    assertThat(prompt)
      .isEqualTo(
        findingPrompt("""{"description":"injected","hierarchy":"[Attachment 1]"}""") + "\n\n[Attachment 1]\n<hierarchy text=\"escaped\"/>"
      )
  }

  @Test
  fun testFetchInsight_whenNoContributor_returnsUnauthorized() {
    ExtensionTestUtil.maskExtensions(AiInsightContributor.EP_NAME, emptyList(), disposableRule.disposable)
    val toolkit = FindingsInsightToolkit(projectRule.project)

    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
      )

    runBlocking {
      val state = toolkit.fetchInsight(finding)
      assertThat(state).isInstanceOf(LoadingState.Unauthorized::class.java)
    }
  }

  @Test
  fun testFetchInsight_whenNoModelAvailable() {
    val contributor =
      object : AiInsightContributor {
        override fun canContribute() = true

        override fun isModelAvailable() = false

        override fun showOnboarding(project: Project) = Unit

        override fun handleModelAssignment(project: Project) = Unit

        override suspend fun fetchInsight(project: Project, request: AiInsightRequest): AiInsight =
          throw UnsupportedOperationException("Should not be called")
      }
    ExtensionTestUtil.maskExtensions(AiInsightContributor.EP_NAME, listOf(contributor), disposableRule.disposable)
    val toolkit = FindingsInsightToolkit(projectRule.project)

    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
      )

    runBlocking {
      val state = toolkit.fetchInsight(finding)
      assertThat(state).isEqualTo(LoadingState.NoModelAvailable)
    }
  }

  @Test
  fun testFetchInsight_whenContributorAvailable_returnsReadyInsight() {
    val contributor =
      object : AiInsightContributor {
        override fun canContribute() = true

        override fun isModelAvailable() = true

        override fun showOnboarding(project: Project) = Unit

        override fun handleModelAssignment(project: Project) = Unit

        override suspend fun fetchInsight(project: Project, request: AiInsightRequest): AiInsight =
          AiInsight(rawInsight = "Live AI insight")
      }
    ExtensionTestUtil.maskExtensions(AiInsightContributor.EP_NAME, listOf(contributor), disposableRule.disposable)

    val toolkit = FindingsInsightToolkit(projectRule.project)
    val finding =
      AppFinding(
        name = "findings/large_screen_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
      )

    runBlocking {
      val state = toolkit.fetchInsight(finding, forceGenerateNewInsight = true)
      assertThat(state).isInstanceOf(LoadingState.Ready::class.java)
      val insight = (state as LoadingState.Ready<AiInsight?>).value
      assertThat(insight).isNotNull()
      assertThat(insight!!.rawInsight).isEqualTo("Live AI insight")
    }
  }

  @Test
  fun testFetchInsight_whenExceptionThrown_returnsUnknownFailure() {
    val contributor =
      object : AiInsightContributor {
        override fun canContribute() = true

        override fun isModelAvailable() = true

        override fun showOnboarding(project: Project) = Unit

        override fun handleModelAssignment(project: Project) = Unit

        override suspend fun fetchInsight(project: Project, request: AiInsightRequest): AiInsight =
          throw IllegalStateException("LLM service unavailable")
      }
    ExtensionTestUtil.maskExtensions(AiInsightContributor.EP_NAME, listOf(contributor), disposableRule.disposable)

    val toolkit = FindingsInsightToolkit(projectRule.project)
    val finding =
      AppFinding(
        name = "findings/error_1",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "Large screens layout issue",
      )

    runBlocking {
      val state = toolkit.fetchInsight(finding, forceGenerateNewInsight = true)
      assertThat(state).isInstanceOf(LoadingState.UnknownFailure::class.java)
      assertThat((state as LoadingState.UnknownFailure).message).contains("LLM service unavailable")
    }
  }

  @Test
  fun testCreateFindingPrompt_whenAssetLoadFails_keepsOriginalUrl(): Unit = runBlocking {
    replaceAssetLoader { throw RuntimeException("Expired URL") }
    val toolkit = FindingsInsightToolkit(projectRule.project)
    val jsonPayload =
      """{"findingData":{"largeScreensLayout":{"layoutIssueType":"DOES_NOT_USE_ENTIRE_SCREEN","viewHierarchyUrl":"https://example.com/broken.xml"}}}"""
    val finding =
      AppFinding(
        name = "findings/large_screen_broken_hierarchy",
        category = FindingCategory.USER_EXPERIENCE,
        severity = FindingSeverity.WARNING,
        displayName = "App does not use entire screen",
        jsonPayload = jsonPayload,
      )

    val prompt = toolkit.createFindingPrompt(finding)

    assertThat(prompt).isEqualTo(findingPrompt(jsonPayload))
  }

  @Test
  fun testResolvePayload_formatsTextOnlyAndMultimodalAndReusesCache(): Unit = runBlocking {
    val image1Bytes = byteArrayOf(1, 2, 3, 4)
    val image2Bytes = byteArrayOf(5, 6, 7, 8)
    val networkFetchUrls = mutableListOf<String>()
    val assetLoader = FindingsAssetLoader { url ->
      networkFetchUrls.add(url)
      when (url) {
        "https://play.google.com/artifacts/1" -> LoadedAsset(image1Bytes, "image/png")
        "https://play.google.com/artifacts/1.xml" -> LoadedAsset("<hierarchy step=\"1\"/>".toByteArray(), "application/xml")
        "https://play.google.com/artifacts/broken.png" -> throw RuntimeException("Expired URL")
        "https://play.google.com/artifacts/2.xml" -> LoadedAsset("<hierarchy step=\"2\"/>".toByteArray(), "text/xml")
        "https://play.google.com/artifacts/3.png" -> LoadedAsset(image2Bytes, "image/webp")
        "https://play.google.com/artifacts/doc" -> LoadedAsset("<html>doc</html>".toByteArray(), "text/html")
        "https://play.google.com/artifacts/unknown" -> LoadedAsset(byteArrayOf(9, 9), null)
        else -> error("Unexpected URL: $url")
      }
    }
    projectRule.project.replaceService(FindingsAssetLoader::class.java, assetLoader, disposableRule.disposable)
    val toolkit = FindingsInsightToolkit(projectRule.project)

    // Simulate FindingsDetailsPanel (ImagePreview) having already loaded artifacts/1 into the shared cache.
    assetLoader.load("https://play.google.com/artifacts/1")
    networkFetchUrls.clear()

    val jsonPayload =
      """{"steps":[{"screenshot":"https://play.google.com/artifacts/1","hierarchy":"https://play.google.com/artifacts/1.xml"},{"screenshot":"https://play.google.com/artifacts/broken.png","hierarchy":"https://play.google.com/artifacts/2.xml"},{"screenshot":"https://play.google.com/artifacts/3.png","doc":"https://play.google.com/artifacts/doc","unknown":"https://play.google.com/artifacts/unknown"}]}"""

    val resolved = toolkit.resolvePayload(jsonPayload)

    // Text-only formatting replaces loaded screenshots with [Screenshot omitted] and numbers text files starting at [Attachment 1].
    val textOnly = resolved.formatTextOnly()
    assertThat(textOnly.json)
      .isEqualTo(
        """{"steps":[{"screenshot":"[Screenshot omitted]","hierarchy":"[Attachment 1]"},{"screenshot":"https://play.google.com/artifacts/broken.png","hierarchy":"[Attachment 2]"},{"screenshot":"[Screenshot omitted]","doc":"https://play.google.com/artifacts/doc","unknown":"https://play.google.com/artifacts/unknown"}]}"""
      )
    assertThat(textOnly.textAttachments)
      .containsExactly(
        ResolvedPayload.TextAttachment("[Attachment 1]", "<hierarchy step=\"1\"/>"),
        ResolvedPayload.TextAttachment("[Attachment 2]", "<hierarchy step=\"2\"/>"),
      )
      .inOrder()
    assertThat(textOnly.images).isEmpty()

    // Multimodal formatting numbers both images ([Attachment 1], [Attachment 4]) and text files ([Attachment 2], [Attachment 3]).
    val multimodal = resolved.formatMultimodal()
    assertThat(multimodal.json)
      .isEqualTo(
        """{"steps":[{"screenshot":"[Attachment 1]","hierarchy":"[Attachment 2]"},{"screenshot":"https://play.google.com/artifacts/broken.png","hierarchy":"[Attachment 3]"},{"screenshot":"[Attachment 4]","doc":"https://play.google.com/artifacts/doc","unknown":"https://play.google.com/artifacts/unknown"}]}"""
      )
    assertThat(multimodal.images).hasSize(2)
    assertThat(multimodal.images[0].data).isEqualTo(image1Bytes)
    assertThat(multimodal.images[0].mimeType).isEqualTo("image/png")
    assertThat(multimodal.images[1].data).isEqualTo(image2Bytes)
    assertThat(multimodal.images[1].mimeType).isEqualTo("image/webp")
    assertThat(multimodal.textAttachments)
      .containsExactly(
        ResolvedPayload.TextAttachment("[Attachment 2]", "<hierarchy step=\"1\"/>"),
        ResolvedPayload.TextAttachment("[Attachment 3]", "<hierarchy step=\"2\"/>"),
      )
      .inOrder()

    // artifacts/1 was served from cache; only uncached URLs hit the fetcher.
    assertThat(networkFetchUrls)
      .containsExactly(
        "https://play.google.com/artifacts/1.xml",
        "https://play.google.com/artifacts/broken.png",
        "https://play.google.com/artifacts/2.xml",
        "https://play.google.com/artifacts/3.png",
        "https://play.google.com/artifacts/doc",
        "https://play.google.com/artifacts/unknown",
      )

    val emptyResolved = toolkit.resolvePayload("""{"findingData":{}}""")
    assertThat(emptyResolved.formatTextOnly().json).isEqualTo("""{"findingData":{}}""")
    assertThat(emptyResolved.formatMultimodal().images).isEmpty()
  }

  @Test
  fun testResolvePayload_handlesDuplicateUrlsPrefixOverlapAndUnsupportedAssets(): Unit = runBlocking {
    val fetchedUrls = mutableListOf<String>()
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url ->
        fetchedUrls += url
        when (url) {
          "https://example.com/1" -> LoadedAsset(byteArrayOf(1, 2), "image/png")
          "https://example.com/10" -> LoadedAsset("<hierarchy id=\"10\"/>".toByteArray(), "text/xml")
          "https://example.com/blank.xml" -> LoadedAsset("   \n  ".toByteArray(), "text/xml")
          "https://example.com/recording.mp4" -> LoadedAsset(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte()), "video/mp4")
          else -> error("Unexpected URL: $url")
        }
      },
      disposableRule.disposable,
    )
    val toolkit = FindingsInsightToolkit(projectRule.project)

    val jsonPayload =
      """{"first":"https://example.com/1","prefixOverlap":"https://example.com/10","duplicate":"https://example.com/1","blank":"https://example.com/blank.xml","video":"https://example.com/recording.mp4"}"""
    val resolved = toolkit.resolvePayload(jsonPayload)

    assertThat(fetchedUrls)
      .containsExactly(
        "https://example.com/1",
        "https://example.com/10",
        "https://example.com/blank.xml",
        "https://example.com/recording.mp4",
      )
      .inOrder()

    val textOnly = resolved.formatTextOnly()
    assertThat(textOnly.json)
      .isEqualTo(
        """{"first":"[Screenshot omitted]","prefixOverlap":"[Attachment 1]","duplicate":"[Screenshot omitted]","blank":"https://example.com/blank.xml","video":"https://example.com/recording.mp4"}"""
      )
    assertThat(textOnly.textAttachments).containsExactly(ResolvedPayload.TextAttachment("[Attachment 1]", "<hierarchy id=\"10\"/>"))
    assertThat(textOnly.images).isEmpty()

    val multimodal = resolved.formatMultimodal()
    assertThat(multimodal.json)
      .isEqualTo(
        """{"first":"[Attachment 1]","prefixOverlap":"[Attachment 2]","duplicate":"[Attachment 1]","blank":"https://example.com/blank.xml","video":"https://example.com/recording.mp4"}"""
      )
    assertThat(multimodal.images).hasSize(1)
    assertThat(multimodal.textAttachments).containsExactly(ResolvedPayload.TextAttachment("[Attachment 2]", "<hierarchy id=\"10\"/>"))
  }

  @Test
  fun testSubmitQueryToAgent_passesFindingContextPayloadAndImageBlobs(): Unit = runBlocking {
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
    val requestedUrls = mutableListOf<String>()
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
        context = FindingContext(visualEvidences = listOf(VisualEvidence(url = screenshot1Url), VisualEvidence(url = screenshot2Url))),
      )

    val toolkit = FindingsInsightToolkit(projectRule.project)
    toolkit.submitQueryToAgent(
      query = "Suggest a fix for the Play Console finding \"Large screens layout issue\"?",
      finding = finding,
    )

    assertThat(submittedQuery).isEqualTo("Suggest a fix for the Play Console finding \"Large screens layout issue\"?")
    assertThat(requestedUrls).containsExactly(screenshot1Url, hierarchy1Url, brokenScreenshotUrl, hierarchy2Url, screenshot2Url).inOrder()
    // Image attachments ([Attachment 1] and [Attachment 4]) are passed positionally as unnamed LlmBlobs.
    assertThat(submittedBlobs).isNotNull()
    assertThat(submittedBlobs).hasSize(2)
    assertThat(submittedBlobs!![0].data).isEqualTo(screenshot1Bytes)
    assertThat(submittedBlobs!![0].mimeType).isEqualTo("image/png")
    assertThat(submittedBlobs!![1].data).isEqualTo(screenshot2Bytes)
    assertThat(submittedBlobs!![1].mimeType).isEqualTo("image/webp")
    // Text attachments ([Attachment 2] and [Attachment 3]) are passed as Reference payloads prefixed with their [Attachment N] label
    // and stripped of embedded triple backticks, while the failed brokenScreenshotUrl remains unchanged in the JSON payload.
    assertThat(submittedPayloads)
      .containsExactly(
        LlmUserQueryPayload.Reference(
          promptString =
            """{"steps":[{"screenshot":"[Attachment 1]","hierarchy":"[Attachment 2]"},{"screenshot":"$brokenScreenshotUrl","hierarchy":"[Attachment 3]"},{"screenshot":"[Attachment 4]"}]}""",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
        LlmUserQueryPayload.Reference(
          promptString = "[Attachment 2]\n$testHierarchyXml",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
        LlmUserQueryPayload.Reference(
          promptString = "[Attachment 3]\n<hierarchy rotation=\"1\" note=\"fenced\"/>",
          textRangeStart = 0,
          textRangeEnd = 0,
        ),
      )
      .inOrder()
  }

  @Test
  fun testSubmitQueryToAgent_whenNoModelsAvailable_showsWarningBalloon(): Unit = runBlocking {
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

    FindingsInsightToolkit(projectRule.project).submitQueryToAgent("Explain this finding", finding)

    verify(mockNotification)
      .showBalloon("Play Findings", "Please ensure an AI model is configured and available.", NotificationType.WARNING)
  }

  @Test
  fun testSubmitQueryToAgent_rethrowsCancellationAndSwallowsUnexpectedExceptions() {
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
    val toolkit = FindingsInsightToolkit(projectRule.project)

    assertThrows(CancellationException::class.java) {
      runBlocking { toolkit.submitQueryToAgent("Explain", finding) }
    }

    toThrow = IllegalStateException("Unexpected failure")
    runBlocking { toolkit.submitQueryToAgent("Explain", finding) }
  }
}
