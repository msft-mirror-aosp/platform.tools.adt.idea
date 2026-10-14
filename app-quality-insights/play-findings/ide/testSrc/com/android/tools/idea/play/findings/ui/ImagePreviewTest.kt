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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.testutils.retryUntilPassing
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class ImagePreviewTest {

  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule()).around(disposableRule).around(composeTestRule)

  private val mockImage = createTestPngBytes()

  private fun replaceAssetLoader(fetcher: (String) -> ByteArray) {
    projectRule.project.replaceService(
      FindingsAssetLoader::class.java,
      FindingsAssetLoader { url -> LoadedAsset(fetcher(url)) },
      disposableRule.disposable,
    )
  }

  @Test
  fun testImageUrl_whileLoading_showsLoadingMessageThenImage() {
    val future = CompletableFuture<ByteArray>()
    replaceAssetLoader { future.get(5, TimeUnit.SECONDS) }
    composeTestRule.setContent {
      ImagePreview(
        imageUrl = "https://example.com/screenshot.png",
        fileName = "finding_1_preview_1.png",
        project = projectRule.project,
      )
    }

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertDoesNotExist()
      composeTestRule.onNodeWithTag("image_status", useUnmergedTree = true).assertTextEquals("Loading preview...")
    }

    future.complete(mockImage)

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_status", useUnmergedTree = true).assertDoesNotExist()
    }
  }

  @Test
  fun testImageUrl_successfulLoad_displaysImage() {
    replaceAssetLoader { mockImage }
    composeTestRule.setContent {
      ImagePreview(
        imageUrl = "https://example.com/screenshot.png",
        fileName = "finding_1_preview_1.png",
        project = projectRule.project,
      )
    }

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }
  }

  @Test
  fun testImageUrl_failure_showsErrorMessage() {
    replaceAssetLoader { throw RuntimeException("Network error") }
    composeTestRule.setContent {
      ImagePreview(
        imageUrl = "https://example.com/broken.png",
        fileName = "finding_1_preview_1.png",
        project = projectRule.project,
      )
    }

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertDoesNotExist()
      composeTestRule.onNodeWithTag("image_status", useUnmergedTree = true).assertTextEquals("Unable to load preview.")
    }
  }

  @Test
  fun testImageUrl_corruptBytes_showsErrorMessage() {
    replaceAssetLoader { byteArrayOf(1, 2, 3) }
    composeTestRule.setContent {
      ImagePreview(
        imageUrl = "https://example.com/corrupt.png",
        fileName = "finding_1_preview_1.png",
        project = projectRule.project,
      )
    }

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_panel", useUnmergedTree = true).assertIsDisplayed()
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertDoesNotExist()
      composeTestRule.onNodeWithTag("image_status", useUnmergedTree = true).assertTextEquals("Unable to load preview.")
    }
  }

  @Test
  fun testClickScreenshotWithProject_opensInEditorAndReusesExistingTab() {
    val openedFiles = mutableListOf<VirtualFile>()
    val fileEditorManager: FileEditorManager = mock()
    whenever(fileEditorManager.openFiles).thenAnswer { openedFiles.toTypedArray() }
    whenever(fileEditorManager.openFile(any(), eq(true))).thenAnswer { invocation ->
      val file = invocation.getArgument<VirtualFile>(0)
      if (file !in openedFiles) {
        openedFiles.add(file)
      }
      emptyArray<FileEditor>()
    }
    projectRule.project.replaceService(
      FileEditorManager::class.java,
      fileEditorManager,
      disposableRule.disposable,
    )
    replaceAssetLoader { mockImage }

    composeTestRule.setContent {
      ImagePreview(
        imageUrl = "https://example.com/screen_preview.png",
        fileName = "finding_10_screenshot_1.png",
        project = projectRule.project,
      )
    }

    retryUntilPassing(5.seconds) {
      composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).assertIsDisplayed()
    }

    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).performClick()
    composeTestRule.onNodeWithTag("image_preview", useUnmergedTree = true).performClick()

    retryUntilPassing(5.seconds) {
      val fileCaptor = argumentCaptor<VirtualFile>()
      verify(fileEditorManager, times(2)).openFile(fileCaptor.capture(), eq(true))
      assertThat(openedFiles).hasSize(1)
      assertThat(fileCaptor.firstValue).isEqualTo(fileCaptor.secondValue)
      assertThat(fileCaptor.firstValue.name).isEqualTo("finding_10_screenshot_1.png")
      assertThat(fileCaptor.firstValue.contentsToByteArray()).isNotEmpty()
    }
  }

  @Test
  fun testFindingsAssetLoader_cachesSuccessfulFetchAndNotFailures(): Unit = runBlocking {
    var fetchCount = 0
    val loader = FindingsAssetLoader { url ->
      fetchCount++
      if (url == "https://example.com/broken.png") throw RuntimeException("Network error")
      LoadedAsset(mockImage, "image/webp")
    }

    val firstAsset = loader.loadAsset("https://example.com/1.png")
    val secondResult = loader.load("https://example.com/1.png")
    assertThat(firstAsset.bytes).isEqualTo(mockImage)
    assertThat(firstAsset.mimeType).isEqualTo("image/webp")
    assertThat(secondResult).isSameAs(firstAsset.bytes)
    assertThat(fetchCount).isEqualTo(1)

    assertThat(runCatching { loader.load("https://example.com/broken.png") }.isFailure).isTrue()
    assertThat(runCatching { loader.load("https://example.com/broken.png") }.isFailure).isTrue()
    assertThat(fetchCount).isEqualTo(3)
  }

  @Test
  fun testFindingsAssetLoader_evictsLeastRecentlyUsedWhenExceedingMaxWeight(): Unit = runBlocking {
    var fetchCount = 0
    val loader =
      FindingsAssetLoader(maxCacheBytes = 15) {
        fetchCount++
        LoadedAsset(ByteArray(10), "image/png")
      }

    loader.load("https://example.com/1.png")
    loader.load("https://example.com/1.png")
    assertThat(fetchCount).isEqualTo(1)

    // Loading a second 10-byte asset exceeds the 15-byte limit and evicts 1.png.
    loader.load("https://example.com/2.png")
    assertThat(fetchCount).isEqualTo(2)

    // 2.png is still cached, whereas 1.png must be re-fetched.
    loader.load("https://example.com/2.png")
    assertThat(fetchCount).isEqualTo(2)

    loader.load("https://example.com/1.png")
    assertThat(fetchCount).isEqualTo(3)
  }
}

internal fun createTestPngBytes(): ByteArray =
  ByteArrayOutputStream().use { out ->
    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
    out.toByteArray()
  }
