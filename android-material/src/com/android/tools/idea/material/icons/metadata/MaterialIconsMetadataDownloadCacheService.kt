/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.material.icons.metadata

import com.android.annotations.concurrency.GuardedBy
import com.android.tools.idea.material.icons.common.BundledMetadataUrlProvider
import com.android.tools.idea.material.icons.common.MaterialIconsMetadataUrlProvider
import com.android.tools.idea.material.icons.common.SdkMetadataUrlProvider
import com.android.tools.idea.material.icons.utils.MaterialIconsUtils.getMetadata
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.net.URL
import java.util.concurrent.CompletableFuture
import kotlin.io.path.div

private val LOG = Logger.getInstance(MaterialIconsMetadataDownloadCacheService::class.java)

/**
 * Application Service that caches a [MaterialIconsMetadataDownloadService].
 *
 * The [MaterialIconsMetadata] file is a file that gets updated infrequently, [MaterialIconsMetadataDownloadService] takes care of properly
 * managing when to actually download the file, but the instance to it must be kept around.
 */
@Service
class MaterialIconsMetadataDownloadCacheService {
  @GuardedBy("this") private var cachedMetadataDownloadService: MaterialIconsMetadataDownloadService? = null

  /**
   * Returns a [CompletableFuture] with a [MaterialIconsMetadata] that executes on a background thread.
   *
   * If the download fails or there is no directory to download the file, it will contain an object from a pre-existing metadata file,
   * either bundled with studio or present in the Android/Sdk directory.
   *
   * @see MaterialIconsMetadataDownloadService
   */
  fun getMetadata(): CompletableFuture<MaterialIconsMetadata> = getMetadata(::BundledMetadataUrlProvider)

  internal fun getMetadata(bundledMetadataUrlProvider: () -> MaterialIconsMetadataUrlProvider): CompletableFuture<MaterialIconsMetadata> {
    val fallbackMetadataURL: URL =
      // The bundled URL for the metadata is expected to exist.
      checkNotNull(SdkMetadataUrlProvider().getMetadataUrl() ?: bundledMetadataUrlProvider().getMetadataUrl())

    // Return the fallback URL for the metadata if there's no Sdk directory.
    val downloadDir = (PathManager.getSystemDir() / "caches" / "material_icons").toFile()
    if (!downloadDir.exists()) {
      downloadDir.mkdirs()
    }
    if (!downloadDir.isDirectory) {
      return runCatching { resolveFallbackMetadata(fallbackMetadataURL, bundledMetadataUrlProvider) }
        .fold({ CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })
    }
    return getDownloadService(downloadDir, fallbackMetadataURL).refreshAndGetMetadata(fallbackMetadataURL, bundledMetadataUrlProvider)
  }

  @Synchronized
  private fun getDownloadService(
    downloadDir: File,
    fallbackMetadataURL: URL,
  ): MaterialIconsMetadataDownloadService {
    val downloadService = cachedMetadataDownloadService ?: MaterialIconsMetadataDownloadService(downloadDir, fallbackMetadataURL)
    cachedMetadataDownloadService = downloadService
    return downloadService
  }
}

/**
 * Returns a [CompletableFuture] that waits for [MaterialIconsMetadataDownloadService] to be refreshed on a background thread and returns
 * the **refreshed** metadata object. Note that it doesn't guarantee that the metadata was properly downloaded.
 */
private fun MaterialIconsMetadataDownloadService.refreshAndGetMetadata(
  fallbackMetadataURL: URL,
  bundledMetadataUrlProvider: () -> MaterialIconsMetadataUrlProvider,
): CompletableFuture<MaterialIconsMetadata> {
  val completableFuture = CompletableFuture<Void>()
  val onComplete = Runnable { completableFuture.complete(null) }
  refresh(onComplete, onComplete, false)
  return completableFuture.thenApplyAsync(
    {
      val latestUrl = this.getLatestMetadataUrl()
      val metadataResult = parseValidMetadata(latestUrl)
      // Wait for async refresh to complete, then ask the service for the metadata.
      metadataResult.getOrNull()?.let {
        return@thenApplyAsync it
      }
      metadataResult.exceptionOrNull()?.let { LOG.warn("Error downloading icons metadata", it) }
      val preParsedFallback =
        metadataResult.takeIf { latestUrl.toExternalForm() == fallbackMetadataURL.toExternalForm() }
      resolveFallbackMetadata(fallbackMetadataURL, bundledMetadataUrlProvider, preParsedFallback)
    },
    AppExecutorUtil.getAppExecutorService(),
  )
}

private fun resolveFallbackMetadata(
  fallbackMetadataURL: URL,
  bundledMetadataUrlProvider: () -> MaterialIconsMetadataUrlProvider,
  preParsedFallbackResult: Result<MaterialIconsMetadata>? = null,
): MaterialIconsMetadata {
  val fallbackResult = preParsedFallbackResult ?: parseValidMetadata(fallbackMetadataURL)
  fallbackResult.getOrNull()?.let {
    return it
  }
  val bundledUrl =
    bundledMetadataUrlProvider().getMetadataUrl()
      ?: throw IllegalStateException("Bundled metadata URL not found", fallbackResult.exceptionOrNull())
  if (bundledUrl.toExternalForm() == fallbackMetadataURL.toExternalForm()) {
    throw checkNotNull(fallbackResult.exceptionOrNull())
  }
  LOG.warn(
    "Failed to load metadata from fallback URL, falling back to bundled metadata",
    fallbackResult.exceptionOrNull(),
  )
  return parseValidMetadata(bundledUrl).getOrThrow()
}

private fun parseValidMetadata(url: URL): Result<MaterialIconsMetadata> {
  val result = getMetadata(url)
  if (result.isFailure) return result
  val metadata = result.getOrNull() ?: return Result.failure(IllegalStateException("Empty metadata at $url"))
  if (metadata.families.isEmpty()) {
    return Result.failure(IllegalStateException("No icon families in metadata at $url"))
  }
  return Result.success(metadata)
}
