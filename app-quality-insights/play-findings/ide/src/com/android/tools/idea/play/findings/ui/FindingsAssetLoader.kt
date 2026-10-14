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

import com.google.common.cache.CacheBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_MAX_CACHE_BYTES = 32L * 1024 * 1024 // 32 MB

internal class LoadedAsset(val bytes: ByteArray, val mimeType: String? = null)

/**
 * Loads remote HTTPS assets for Play Console findings on [Dispatchers.IO] and caches results in memory (up to [maxCacheBytes]) so repeated
 * requests for the same URL avoid redundant network downloads.
 */
@Service(Service.Level.PROJECT)
internal class FindingsAssetLoader(
  maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
  private val fetcher: (String) -> LoadedAsset = ::fetchHttpsAsset,
) {
  private val cache =
    CacheBuilder.newBuilder()
      .concurrencyLevel(1)
      .maximumWeight(maxCacheBytes)
      .weigher<String, LoadedAsset> { _, asset -> asset.bytes.size }
      .expireAfterAccess(30, TimeUnit.MINUTES)
      .build<String, LoadedAsset>()

  suspend fun loadAsset(urlStr: String): LoadedAsset {
    require(URI(urlStr).scheme.equals("https", ignoreCase = true)) {
      "Finding asset URL must use HTTPS: $urlStr"
    }
    return withContext(Dispatchers.IO) { cache.get(urlStr) { fetcher(urlStr) } }
  }

  suspend fun load(urlStr: String): ByteArray = loadAsset(urlStr).bytes

  companion object {
    fun getInstance(project: Project): FindingsAssetLoader = project.service()
  }
}

private fun fetchHttpsAsset(urlStr: String): LoadedAsset =
  HttpRequests.request(urlStr).connect { request ->
    val mimeType = request.connection.contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    LoadedAsset(bytes = request.readBytes(null), mimeType = mimeType)
  }
