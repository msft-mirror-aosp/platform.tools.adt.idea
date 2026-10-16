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
package com.android.tools.idea.fonts

import com.android.ide.common.fonts.FontDetail
import com.android.ide.common.fonts.FontFamily
import com.android.ide.common.fonts.FontLoader
import com.android.ide.common.fonts.FontProvider
import com.android.tools.fonts.DownloadableFontCacheService
import com.android.tools.fonts.DownloadableFontCacheServiceImpl
import com.android.tools.fonts.FontDirectoryDownloader
import com.android.tools.fonts.FontDownloader
import com.android.tools.idea.sdk.AndroidSdks
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.awt.Font
import java.io.File
import java.io.IOException

/** Studio specific version of [DownloadableFontCacheService]. */
class StudioDownloadableFontCacheService :
  DownloadableFontCacheServiceImpl(
    object : FontDownloader {
      override fun download(fontsToDownload: List<FontFamily>, menuFontsOnly: Boolean, success: Runnable?, failure: Runnable?) =
        FontDownloadService.download(fontsToDownload, menuFontsOnly, success, failure)

      override fun createFontDirectoryDownloader(
        fontLoader: FontLoader,
        provider: FontProvider,
        fontCachePath: File,
      ): FontDirectoryDownloader = FontDirectoryDownloadService(fontLoader, provider, fontCachePath)
    },
    { AndroidSdks.getInstance().tryToChooseSdkHandler().location?.toFile() },
  ) {

  // These overrides make sure that the base implementation, which loads fonts without sanitizing them, is never used in Studio.
  // MoreFontsDialog and FontIconProvider use FontSanitizer directly, because they need to know why a font cannot be loaded.
  override fun loadMenuFont(fontFamily: FontFamily): Font? = loadFont(getCachedMenuFile(fontFamily))

  override fun loadDetailFont(fontDetail: FontDetail): Font? = loadFont(getCachedFontFile(fontDetail))

  private fun loadFont(file: File?): Font? {
    if (file == null || !file.exists()) return null
    return try {
      FontSanitizer.createFont(file.toPath())
    } catch (e: IOException) {
      LOG.debug("Could not load font $file", e)
      null
    }
  }

  companion object {
    private val LOG = Logger.getInstance(StudioDownloadableFontCacheService::class.java)

    @JvmStatic
    fun getInstance(): DownloadableFontCacheServiceImpl =
      ApplicationManager.getApplication().getService(DownloadableFontCacheService::class.java) as DownloadableFontCacheServiceImpl
  }
}
