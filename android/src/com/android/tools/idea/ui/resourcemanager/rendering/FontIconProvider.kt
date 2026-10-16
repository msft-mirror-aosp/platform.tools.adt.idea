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
package com.android.tools.idea.ui.resourcemanager.rendering

import com.android.ide.common.fonts.FontFamily
import com.android.tools.fonts.ProjectFonts
import com.android.tools.idea.fonts.FontSanitizer
import com.android.tools.idea.fonts.FontSanitizerUnavailableException
import com.android.tools.idea.fonts.InvalidFontException
import com.android.tools.idea.fonts.StudioDownloadableFontCacheService
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.ui.resourcemanager.model.Asset
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.ImageIcon
import org.jetbrains.android.facet.AndroidFacet

/**
 * Create a reusable [Icon] that is updated each time [getIcon] is called. It paints a sample of all font styles defined in the resource's
 * [FontFamily], It will try to fit the samples in the given dimensions. Usually, for most Android Projects, a [FontFamily] will have up to
 * two styles.
 */
class FontIconProvider(facet: AndroidFacet) : AssetIconProvider {

  private val fontIcon = FontFamilyIcon()

  private val projectFonts =
    ProjectFonts(StudioDownloadableFontCacheService.getInstance(), StudioResourceRepositoryManager.getInstance(facet))

  override val supportsTransparency: Boolean = false

  /** [getIcon] is called on every paint, so the icons for fonts that cannot be previewed are reused. */
  private val failedIcons = ConcurrentHashMap<FailedIconKey, Icon>()

  override fun getIcon(
    assetToRender: Asset,
    width: Int,
    height: Int,
    component: Component,
    refreshCallback: () -> Unit,
    shouldBeRendered: () -> Boolean,
  ): Icon {
    val resource = assetToRender.resourceItem
    val fontFamily = projectFonts.getFont(resource.referenceToSelf.resourceUrl.toString())

    fontIcon.width = width
    fontIcon.height = height
    val failure = fontIcon.setFontFamily(fontFamily)
    if (failure != null) {
      val message = failure.toPreviewMessage()
      return failedIcons.computeIfAbsent(FailedIconKey(width, height, message)) {
        ImageIcon(createFailedIcon(Dimension(width, height), message))
      }
    }
    return fontIcon
  }
}

private data class FailedIconKey(val width: Int, val height: Int, val message: String)

/** A short message for a font that cannot be previewed. */
private fun IOException.toPreviewMessage(): String =
  when (this) {
    is InvalidFontException -> "Invalid font"
    is FontSanitizerUnavailableException -> "Cannot verify font"
    else -> "Failed preview"
  }

/** Creates an Icon that draws samples of each style within a [FontFamily]. */
private class FontFamilyIcon : Icon {
  var width: Int = 0
  var height: Int = 0

  private var fonts: List<FontIconData> = emptyList()
  private val fontService = StudioDownloadableFontCacheService.getInstance()

  /**
   * The sanitized fonts, or the reason they could not be loaded, by font file. [setFontFamily] is called on every paint, so this avoids
   * reading and sanitizing the font files each time. A changed file is loaded again.
   */
  private val loadedFonts = ConcurrentHashMap<File, LoadedFont>()

  /**
   * Loads the sanitized fonts of [fontFamily]. Fonts that are not downloaded yet are skipped.
   *
   * @return the first failure if none of the fonts could be loaded, or `null` otherwise
   */
  fun setFontFamily(fontFamily: FontFamily): IOException? {
    var failure: IOException? = null
    fonts =
      fontFamily.fonts.mapNotNull { fontDetail ->
        val file = fontService.getCachedFontFile(fontDetail)?.takeIf { it.exists() } ?: return@mapNotNull null
        loadFont(file)
          .fold(
            onSuccess = { FontIconData(fontDetail.styleName, it) },
            onFailure = {
              failure = failure ?: it as IOException
              null
            },
          )
      }
    return if (fonts.isEmpty()) failure else null
  }

  private fun loadFont(file: File): Result<Font> {
    val lastModified = file.lastModified()
    val length = file.length()
    val cached = loadedFonts[file]
    if (cached != null && cached.lastModified == lastModified && cached.length == length) {
      return cached.font
    }
    val font =
      try {
        Result.success(FontSanitizer.createFont(file.toPath()))
      } catch (e: IOException) {
        Result.failure(e)
      }
    loadedFonts[file] = LoadedFont(lastModified, length, font)
    return font
  }

  override fun getIconWidth(): Int = width

  override fun getIconHeight(): Int = height

  override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
    g as Graphics2D
    var maxWidth = 0
    var maxHeight = 0
    var fontSize = 10f // Need an initial size value

    // Save current configuration.
    val originalFont = g.font
    val antiAliasValue = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
    val renderValue = g.getRenderingHint(RenderingHints.KEY_RENDERING)

    // MEASURE - horizontal
    fonts.forEach { fontToRender ->
      val fontBounds = fontToRender.getFontBounds(g, fontSize)
      maxWidth = maxOf(maxWidth, fontBounds.width.toInt())
    }
    fontSize *= width.toFloat() / (maxWidth + JBUI.scale(4)).toFloat()

    // MEASURE - vertical
    fonts.forEach { fontToRender ->
      val fontBounds = fontToRender.getFontBounds(g, fontSize)
      maxHeight += fontBounds.height.toInt()
    }
    fontSize *= minOf((height.toFloat() / (maxHeight + JBUI.scale(4)).toFloat()), 1f)

    // PAINT
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    var yOffset = 0
    fonts.forEach { fontToRender ->
      val textBounds = fontToRender.getFontBounds(g, fontSize)
      yOffset += textBounds.height.toInt()
      g.font = fontToRender.font.deriveFont(fontSize)
      g.drawString(fontToRender.name, x + ((width - textBounds.width) / 2).toInt(), yOffset)
    }

    // Restore configuration.
    g.font = originalFont
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAliasValue)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, renderValue)
  }
}

private data class FontIconData(val name: String, val font: Font)

/** A font loaded from a file with the given [lastModified] time and [length], or the [IOException] thrown while loading it. */
private class LoadedFont(val lastModified: Long, val length: Long, val font: Result<Font>)

private fun FontIconData.getFontBounds(g: Graphics, fontSize: Float): Rectangle2D {
  return g.getFontMetrics(font.deriveFont(fontSize)).getStringBounds(name, g)
}
