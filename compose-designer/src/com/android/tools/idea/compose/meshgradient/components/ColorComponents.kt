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
package com.android.tools.idea.compose.meshgradient.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.meshgradient.toHexStringNoHash
import com.android.tools.idea.compose.preview.message
import org.jetbrains.jewel.foundation.theme.JewelTheme

/** Shape of a [ColorSwatch], shared by the controls laid out next to swatches. */
internal val SWATCH_SHAPE = RoundedCornerShape(4.dp)

/** Size of a [ColorSwatch], shared by the controls laid out next to swatches. */
internal val SWATCH_SIZE = 16.dp
private val TRANSPARENT_MARK_WIDTH = 1.5.dp

/** Returns the `#AARRGGBB` representation of this color. */
internal fun Color.toHexString(): String = "#${toHexStringNoHash(includeAlpha = true)}"

/** Returns the accessibility description of a [ColorSwatch] showing [color]. */
internal fun colorSwatchDescription(color: Color): String = message("gradient.editor.swatch.description", color.toHexString())

/**
 * A small square preview of [color], outlined so that colors matching the panel background stay visible. Fully transparent colors are drawn
 * as an empty swatch crossed by a diagonal line. Callers provide the content description, see [colorSwatchDescription].
 */
@Composable
internal fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
  val borderColor = JewelTheme.globalColors.borders.normal
  val transparentMarkColor = JewelTheme.globalColors.text.error
  Box(
    modifier
      .size(SWATCH_SIZE)
      .clip(SWATCH_SHAPE)
      .then(
        if (color.alpha == 0f) {
          Modifier.drawBehind {
            drawLine(
              color = transparentMarkColor,
              start = Offset.Zero,
              end = Offset(size.width, size.height),
              strokeWidth = TRANSPARENT_MARK_WIDTH.toPx(),
            )
          }
        } else {
          Modifier.background(color)
        }
      )
      .border(1.dp, borderColor, SWATCH_SHAPE)
  )
}
