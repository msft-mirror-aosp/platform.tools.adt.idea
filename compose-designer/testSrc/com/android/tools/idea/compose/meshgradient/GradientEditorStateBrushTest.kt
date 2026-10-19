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
package com.android.tools.idea.compose.meshgradient

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GradientEditorStateBrushTest {

  @Test
  fun freshStateUsesComposeDefaults() {
    val state = GradientEditorState()
    assertEquals(DEFAULT_PREVIEW_SIZE, state.previewSize)
    assertEquals(Offset.Zero, state.start)
    assertEquals(Offset.Infinite, state.end)
    assertEquals(Offset.Unspecified, state.center)
    assertEquals(Float.POSITIVE_INFINITY, state.radius)
  }

  @Test
  fun fitPreviewSizeKeepsLoadedValues() {
    val state = GradientEditorState()
    state.currentType = GradientType.LINEAR
    state.start = Offset(0f, 0f)
    state.end = Offset(Float.POSITIVE_INFINITY, 120f)
    state.fitPreviewSizeToBrush()
    assertEquals(Offset(Float.POSITIVE_INFINITY, 120f), state.end)
    assertEquals(Size(120f, 120f), state.previewSize)
  }

  @Test
  fun playgroundCodeUsesPixelAndAutomaticValues() {
    val state = GradientEditorState()
    state.currentType = GradientType.RADIAL
    state.loadColorStops(emptyList(), listOf(0f to Color.Red, 0.5f to Color.Green, 1f to Color.Blue))
    state.addColorStop(Color.Yellow)
    assertEquals(
      """
      val gradientBrush = remember {
          Brush.radialGradient(
              0f to Color(0xFFFF0000),
              0.5f to Color(0xFF00FF00),
              0.75f to Color(0xFFFFFF00),
              1f to Color(0xFF0000FF),
              center = Offset.Unspecified,
              radius = Float.POSITIVE_INFINITY,
              tileMode = TileMode.Clamp,
          )
      }
      """
        .trimIndent(),
      state.generatedCode,
    )

    state.center = Offset(120f, 80.5f)
    state.radius = 64f
    assertEquals(
      """
      val gradientBrush = remember {
          Brush.radialGradient(
              0f to Color(0xFFFF0000),
              0.5f to Color(0xFF00FF00),
              0.75f to Color(0xFFFFFF00),
              1f to Color(0xFF0000FF),
              center = Offset(120f, 80.5f),
              radius = 64f,
              tileMode = TileMode.Clamp,
          )
      }
      """
        .trimIndent(),
      state.generatedCode,
    )
  }
}
