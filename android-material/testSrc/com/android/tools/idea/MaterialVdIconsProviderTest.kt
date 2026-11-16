/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea

import com.android.prefs.AndroidLocationsSingleton
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.tools.idea.material.icons.MaterialVdIcons
import com.android.tools.idea.material.icons.MaterialVdIconsLoader
import com.android.tools.idea.material.icons.common.BundledIconsUrlProvider
import com.android.tools.idea.material.icons.common.BundledMetadataUrlProvider
import com.android.tools.idea.material.icons.common.MaterialIconsMetadataUrlProvider
import com.android.tools.idea.material.icons.common.MaterialIconsUrlProvider
import com.android.tools.idea.material.icons.metadata.MaterialIconsMetadata
import com.android.tools.idea.material.icons.metadata.MaterialIconsMetadataDownloadCacheService
import com.android.tools.idea.material.icons.utils.MaterialIconsUtils.getIconFileNameWithoutExtension
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.download.DownloadableFileService
import com.intellij.util.download.FileDownloader
import com.intellij.util.download.impl.DownloadableFileDescriptionImpl
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.intellij.lang.annotations.Language
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever

private const val TEST_PATH = "images/material/icons/"
private const val METADATA_FILE_NAME = "icons_metadata_test.txt"
private const val WAIT_TIMEOUT_SECONDS = 10L
private val TIMEOUT_UNIT = TimeUnit.SECONDS

class MaterialVdIconsProviderTest {

  companion object {
    @JvmField @ClassRule val appRule = ApplicationRule()
  }

  private lateinit var disposable: Disposable

  @Before
  fun setup() {
    disposable = Disposer.newDisposable()
  }

  @After
  fun teardown() {
    Disposer.dispose(disposable)
  }

  @Test
  fun testGetMaterialIcons() {
    val latch = CountDownLatch(2) // A call for each style: "Style 1", "Style 2"
    var materialIcons = MaterialVdIcons.EMPTY
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { icons, _ ->
      materialIcons = icons
      latch.countDown()
    }
    MaterialVdIconsProvider.loadMaterialVdIcons(
      uiCallback,
      disposable,
      MaterialIconsMetadataTestUrlProvider(),
      MaterialIconsTestUrlProvider(),
    )
    assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT))
    Truth.assertThat(materialIcons.styles).containsExactly("Style 1", "Style 2")
    Truth.assertThat(materialIcons.getCategories("Style 1")).containsAllIn(materialIcons.getCategories("Style 2"))
    val icons = materialIcons.getAllIcons("Style 1")
    Truth.assertThat(icons.map { it.name }).containsExactly("style1_my_icon_1_24.xml", "style1_my_icon_2_24.xml")
  }

  @Test
  fun testBadMetadataProviderReturnsEmptyStyles() {
    val latch = CountDownLatch(1) // 1 call from having a provider that doesn't return a metadata
    var materialIcons: MaterialVdIcons? = null
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { icons, status ->
      materialIcons = icons
      assertEquals(MaterialVdIconsProvider.Status.FINISHED, status)
      latch.countDown()
    }
    MaterialVdIconsProvider.loadMaterialVdIcons(
      uiCallback,
      disposable,
      object : MaterialIconsMetadataUrlProvider {
        override fun getMetadataUrl(): URL? = null
      },
      null,
    )
    assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT))
    Truth.assertThat(materialIcons!!.styles).isEmpty()
  }

  @Test
  fun testCorruptMetadataCallsFinishedCallback() {
    val latch = CountDownLatch(1)
    var finished = false
    var materialIcons: MaterialVdIcons? = null
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { icons, status ->
      materialIcons = icons
      if (status == MaterialVdIconsProvider.Status.FINISHED) {
        finished = true
        latch.countDown()
      }
    }
    val tempFile = File.createTempFile("corrupt_metadata", ".txt")
    tempFile.writeText("corrupt content")
    try {
      MaterialVdIconsProvider.loadMaterialVdIcons(
        uiCallback,
        disposable,
        object : MaterialIconsMetadataUrlProvider {
          override fun getMetadataUrl(): URL = tempFile.toURI().toURL()
        },
        null,
      )
      assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT))
      assertTrue(finished)
      Truth.assertThat(materialIcons!!.styles).isEmpty()
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun testBadLoaderProviderReturnsEmptyIcons() {
    val latch = CountDownLatch(2) // A call for each style: "Style 1", "Style 2"
    var icons: MaterialVdIcons? = null
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { materialIcons, _ ->
      icons = materialIcons
      latch.countDown()
    }
    MaterialVdIconsProvider.loadMaterialVdIcons(
      uiCallback,
      disposable,
      MaterialIconsMetadataTestUrlProvider(),
      object : MaterialIconsUrlProvider {
        override fun getStyleUrl(style: String): URL? = null

        override fun getIconUrl(style: String, iconName: String, iconFileName: String): URL? = null
      },
    )
    assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT))
    val materialIcons = icons!!
    Truth.assertThat(materialIcons.styles).containsExactly("Style 1", "Style 2")

    Truth.assertThat(materialIcons.getAllIcons("Style 1")).isEmpty()
    Truth.assertThat(materialIcons.getAllIcons("Style 2")).isEmpty()
  }

  @Test
  fun testBundledProvidersResolveMetadataAndIcon() {
    val bundledMetadataUrl = BundledMetadataUrlProvider().getMetadataUrl()
    Truth.assertThat(bundledMetadataUrl).isNotNull()
    val metadata = MaterialIconsMetadata.parse(checkNotNull(bundledMetadataUrl)).getOrThrow()
    Truth.assertThat(metadata.families).isNotEmpty()
    Truth.assertThat(metadata.icons).isNotEmpty()

    val style = metadata.families.first()
    val iconMetadata = metadata.icons.first { style !in it.unsupportedFamilies }
    val expectedFileName = "${getIconFileNameWithoutExtension(iconName = iconMetadata.name, styleName = style)}.xml"
    val bundledIconsProvider = BundledIconsUrlProvider()
    val iconUrl =
      bundledIconsProvider.getIconUrl(
        style = style,
        iconName = iconMetadata.name,
        iconFileName = expectedFileName,
      )
    Truth.assertThat(iconUrl).isNotNull()
    val vdIcon =
      MaterialVdIconsLoader(metadata, bundledIconsProvider)
        .loadVdIcon(styleName = style, iconName = iconMetadata.name, fileName = expectedFileName)
    Truth.assertThat(vdIcon).isNotNull()
    Truth.assertThat(checkNotNull(vdIcon).name).isEqualTo(expectedFileName)
  }
}

private class MaterialIconsMetadataTestUrlProvider : MaterialIconsMetadataUrlProvider {
  override fun getMetadataUrl(): URL? = MaterialVdIconsProviderTest::class.java.classLoader.getResource("$TEST_PATH$METADATA_FILE_NAME")
}

private class MaterialIconsTestUrlProvider : MaterialIconsUrlProvider {
  override fun getStyleUrl(style: String): URL? {
    return MaterialVdIconsProviderTest::class.java.classLoader.getResource(getStylePath(style))
  }

  override fun getIconUrl(style: String, iconName: String, iconFileName: String): URL? {
    return MaterialVdIconsProviderTest::class.java.classLoader.getResource("${getStylePath(style)}$iconName/$iconFileName")
  }

  private fun getStylePath(style: String): String {
    return "$TEST_PATH${style.lowercase(Locale.US).replace(" ", "")}/"
  }
}

private const val SIMPLE_METADATA =
  ")]}'\n" +
    "{\n" +
    "  \"host\": \"\",\n" +
    "  \"asset_url_pattern\": \"\",\n" +
    "  \"families\": [\n" +
    "    \"Style 1\"\n" +
    "  ],\n" +
    "  \"icons\": [\n" +
    "    {\n" +
    "      \"name\": \"my_sdk_icon\",\n" +
    "      \"version\": 1,\n" +
    "      \"unsupported_families\": [],\n" +
    "      \"categories\": [\n" +
    "        \"category1\",\n" +
    "        \"category2\"\n" +
    "      ],\n" +
    "      \"tags\": [],\n" +
    "      \"codepoint\": 59530\n" +
    "    }\n" +
    "  ]\n" +
    "}"

@Language("XML")
private const val SIMPLE_VD =
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
    "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
    "    android:height=\"100dp\"\n" +
    "    android:width=\"100dp\"\n" +
    "    android:viewportHeight=\"100\"\n" +
    "    android:viewportWidth=\"100\">\n" +
    "  <path\n" +
    "      android:fillColor=\"#FF000000\"\n" +
    "      android:pathData=\"M 0,0 L 100,0 0,100 z\" />\n" +
    "\n" +
    "</vector>"

class MaterialVdIconsProviderTestWithSdk {

  @get:Rule val rule = AndroidProjectRule.inMemory()

  private lateinit var disposable: Disposable
  private lateinit var testSdkDirectory: File
  private lateinit var mockAndroidSdks: AndroidSdks
  private var activeJob: Job? = null

  @Before
  fun setup() {
    disposable = Disposer.newDisposable(rule.testRootDisposable)
    val testDirectory = FileUtil.createTempDirectory(javaClass.simpleName, null)
    testSdkDirectory = testDirectory.resolve("FakeSdk").apply { mkdir() }
    val testMaterialIconsSdkDirectory = testSdkDirectory.resolve("icons").resolve("material").apply { mkdirs() }
    testMaterialIconsSdkDirectory.resolve("icons_metadata.txt").writeText(SIMPLE_METADATA)
    testMaterialIconsSdkDirectory
      .resolve("style1")
      .resolve("my_sdk_icon")
      .apply { mkdirs() }
      .resolve("style1_my_sdk_icon_24.xml")
      .writeText(SIMPLE_VD)

    val sdkHandler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, testSdkDirectory.toPath())
    mockAndroidSdks = rule.mockService(AndroidSdks::class.java)
    whenever(mockAndroidSdks.tryToChooseSdkHandler()).thenReturn(sdkHandler)

    val mockDownloadableFileService = rule.mockService(DownloadableFileService::class.java)
    val mockDownloader = Mockito.mock(FileDownloader::class.java)
    whenever(mockDownloader.download(Mockito.any())).thenThrow(IOException("Offline in test"))
    whenever(mockDownloadableFileService.createDownloader(Mockito.any(), Mockito.any())).thenReturn(mockDownloader)
    whenever(mockDownloadableFileService.createFileDescription(Mockito.any(), Mockito.any()))
      .thenReturn(DownloadableFileDescriptionImpl("https://example.com/icons", "icons_metadata_temp", "txt"))

    rule.replaceService(
      MaterialIconsMetadataDownloadCacheService::class.java,
      MaterialIconsMetadataDownloadCacheService(),
    )
  }

  @After
  fun teardown() {
    try {
      runBlocking { withTimeout(TIMEOUT_UNIT.toMillis(WAIT_TIMEOUT_SECONDS)) { activeJob?.cancelAndJoin() } }
    } finally {
      if (::disposable.isInitialized) {
        Disposer.dispose(disposable)
      }
    }
  }

  @Test
  fun getMaterialIconsFromSdk() {
    val latch = CountDownLatch(1) // A call to load all icons of "Style 1"
    var materialIcons = MaterialVdIcons.EMPTY
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { icons, _ ->
      materialIcons = icons
      latch.countDown()
    }
    activeJob =
      MaterialVdIconsProvider.loadMaterialVdIconsForTestOnly(
        uiCallback,
        disposable,
        null, // Use the 'real' URL providers
        null,
      )
    assertTrue(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT))
    runBlocking { withTimeout(TIMEOUT_UNIT.toMillis(WAIT_TIMEOUT_SECONDS)) { checkNotNull(activeJob).join() } }
    Truth.assertThat(materialIcons.styles).containsExactly("Style 1")
    val icons = materialIcons.getAllIcons("Style 1")
    Truth.assertThat(icons.map { it.name }).containsExactly("style1_my_sdk_icon_24.xml")
  }

  @Test
  fun testCorruptSdkMetadataFallsBackToBundled() {
    val testMaterialIconsSdkDirectory = testSdkDirectory.resolve("icons").resolve("material")
    testMaterialIconsSdkDirectory.resolve("icons_metadata.txt").writeText("corrupt content")

    val latch = CountDownLatch(1)
    val materialIcons = AtomicReference(MaterialVdIcons.EMPTY)
    val finished = AtomicBoolean(false)
    val uiCallback: (MaterialVdIcons, MaterialVdIconsProvider.Status) -> Unit = { icons, status ->
      materialIcons.set(icons)
      if (status == MaterialVdIconsProvider.Status.FINISHED) {
        finished.set(true)
        latch.countDown()
      }
    }
    activeJob =
      MaterialVdIconsProvider.loadMaterialVdIconsForTestOnly(
        refreshUiCallback = uiCallback,
        parentDisposable = disposable,
        metadataUrlProvider = null,
        iconsUrlProvider = null,
        bundledMetadataUrlProvider = ::MaterialIconsMetadataTestUrlProvider,
        bundledIconsUrlProvider = ::MaterialIconsTestUrlProvider,
      )
    Truth.assertThat(latch.await(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT)).isTrue()
    runBlocking { withTimeout(TIMEOUT_UNIT.toMillis(WAIT_TIMEOUT_SECONDS)) { checkNotNull(activeJob).join() } }
    Truth.assertThat(finished.get()).isTrue()
    Truth.assertThat(materialIcons.get().styles).containsExactly("Style 1", "Style 2")
    Truth.assertThat(materialIcons.get().getAllIcons("Style 1").map { it.name })
      .containsExactly("style1_my_icon_1_24.xml", "style1_my_icon_2_24.xml")
  }

  @Test
  fun testDownloadCacheServiceFallsBackToBundledOnCorruptSdkMetadata() {
    val testMaterialIconsSdkDirectory = testSdkDirectory.resolve("icons").resolve("material")
    testMaterialIconsSdkDirectory.resolve("icons_metadata.txt").writeText("corrupt content")

    val cacheService = ApplicationManager.getApplication().getService(MaterialIconsMetadataDownloadCacheService::class.java)
    val metadata = cacheService.getMetadata().get(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT)
    Truth.assertThat(metadata.families).isNotEmpty()
    Truth.assertThat(metadata.icons).isNotEmpty()
  }

  @Test
  fun testDownloadCacheServiceFallsBackWhenCachedUrlDeletedAndSdkEmpty() {
    val cacheService = ApplicationManager.getApplication().getService(MaterialIconsMetadataDownloadCacheService::class.java)
    val initialMetadata = cacheService.getMetadata(::MaterialIconsMetadataTestUrlProvider).get(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT)
    Truth.assertThat(initialMetadata.families).asList().containsExactly("Style 1")

    Truth.assertThat(testSdkDirectory.resolve("icons").resolve("material").resolve("icons_metadata.txt").delete()).isTrue()
    val emptySdkDirectory = FileUtil.createTempDirectory("${javaClass.simpleName}EmptySdk", null).resolve("FakeSdk")
    emptySdkDirectory.resolve("icons").resolve("material").apply { mkdirs() }.resolve("icons_metadata.txt").writeText("")
    val emptySdkHandler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, emptySdkDirectory.toPath())
    whenever(mockAndroidSdks.tryToChooseSdkHandler()).thenReturn(emptySdkHandler)

    val fallbackMetadata = cacheService.getMetadata(::MaterialIconsMetadataTestUrlProvider).get(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT)
    Truth.assertThat(fallbackMetadata.families).asList().containsExactly("Style 1", "Style 2")
  }

  @Test
  fun testDownloadCacheServicePreservesCauseWhenBundledMetadataUrlMissing() {
    val testMaterialIconsSdkDirectory = testSdkDirectory.resolve("icons").resolve("material")
    testMaterialIconsSdkDirectory.resolve("icons_metadata.txt").writeText("corrupt content")

    val cacheService = ApplicationManager.getApplication().getService(MaterialIconsMetadataDownloadCacheService::class.java)
    val thrown =
      runCatching {
        cacheService
          .getMetadata {
            object : MaterialIconsMetadataUrlProvider {
              override fun getMetadataUrl(): URL? = null
            }
          }
          .get(WAIT_TIMEOUT_SECONDS, TIMEOUT_UNIT)
      }
        .exceptionOrNull()
    Truth.assertThat(thrown).isInstanceOf(ExecutionException::class.java)
    val cause = checkNotNull(thrown?.cause)
    Truth.assertThat(cause).isInstanceOf(IllegalStateException::class.java)
    Truth.assertThat(cause).hasMessageThat().isEqualTo("Bundled metadata URL not found")
    Truth.assertThat(cause.cause).isNotNull()
  }
}
