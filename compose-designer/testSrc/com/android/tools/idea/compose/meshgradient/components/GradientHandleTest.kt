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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.meshgradient.PointCursor
import com.intellij.testFramework.ApplicationRule
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GradientHandleTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  @Test
  fun contrastingHandleTextColorSelectsHigherWcagContrastAcrossColorsAndAlpha() {
    // Mid-luminance saturated colors (0.179 < luminance <= 0.5) must select Black text for >= 4.5:1 WCAG AA contrast.
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFFFF0000))) // Pure Red
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFFF44336))) // Material Red
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFF00BCD4))) // Material Cyan
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFF4CAF50))) // Material Green
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFF03A9F4))) // Material Light Blue
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0xFFFF9800))) // Orange
    assertEquals(Color.Black, contrastingHandleTextColor(Color.White))

    // Low-luminance colors select White text.
    assertEquals(Color.White, contrastingHandleTextColor(Color.Black))
    assertEquals(Color.White, contrastingHandleTextColor(Color(0xFF0000FF))) // Pure Blue
    assertEquals(Color.White, contrastingHandleTextColor(Color(0xFF5D53C6))) // Mid Purple
    assertEquals(Color.White, contrastingHandleTextColor(Color(0xFF673AB7))) // Material Deep Purple
    assertEquals(Color.White, contrastingHandleTextColor(Color(0xFF3F51B5))) // Material Indigo

    // Transparent and low-alpha colors composite over the light checkerboard backing rather than ignoring alpha.
    assertEquals(Color.Black, contrastingHandleTextColor(Color.Transparent))
    assertEquals(Color.Black, contrastingHandleTextColor(Color(0x00FFFFFF)))
    assertEquals(Color.Black, contrastingHandleTextColor(Color.Black.copy(alpha = 0.2f)))
    assertEquals(Color.White, contrastingHandleTextColor(Color.Black.copy(alpha = 0.9f)))

    val checkerboardBacking = Color(0xFFEEF0F2)
    val channelSteps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    for (r in channelSteps) {
      for (g in channelSteps) {
        for (b in channelSteps) {
          for (a in listOf(0f, 0.4f, 1f)) {
            val color = Color(r, g, b, a)
            val textColor = contrastingHandleTextColor(color)
            val effectiveBg = if (a < 1f) color.compositeOver(checkerboardBacking) else color
            val lighter = maxOf(textColor.luminance(), effectiveBg.luminance())
            val darker = minOf(textColor.luminance(), effectiveBg.luminance())
            val wcagContrast = (lighter + 0.05f) / (darker + 0.05f)
            assertTrue("Expected WCAG AA contrast >= 4.5 for $color, got $wcagContrast", wcagContrast >= 4.5f)
          }
        }
      }
    }
  }

  @Test
  fun coordinateGlyphRemainsInsideInnerFillWithoutBleedingIntoWhiteRing() {
    val fillColor = Color(0xFF3F51B5)
    composeTestRule.setContent {
      // Pass fontScale = 2f to verify dp-to-sp glyph sizing stays invariant across fontScale settings.
      CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 2f)) {
        Box(modifier = Modifier.size(32.dp).background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
          PointCursor(row = 9, col = 9, color = fillColor, movable = true)
        }
      }
    }
    composeTestRule.waitForIdle()
    val pixels = composeTestRule.onRoot().captureToImage().toPixelMap()
    val cx = pixels.width / 2f
    val cy = pixels.height / 2f

    // At 2x density: handle radius = 24px, outer border = 2px (22..24px), white ring = 3px (19..22px), inner fill radius = 19px.
    // 1. Inside the center, white glyph pixels for "9,9" must be rendered.
    var hasWhiteGlyphPixel = false
    for (y in (cy - 10).roundToInt()..(cy + 10).roundToInt()) {
      for (x in (cx - 12).roundToInt()..(cx + 12).roundToInt()) {
        val c = pixels[x, y]
        if (c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f) {
          hasWhiteGlyphPixel = true
        }
      }
    }
    assertTrue("Expected white glyph pixels inside the center of the handle", hasWhiteGlyphPixel)

    // 2. Along a ring inside the fill margin (radius = 16.5px, well inside the 19px fill radius), no glyph pixel may touch or cross into
    // the white border ring.
    for (angleDeg in 0 until 360 step 5) {
      val rad = Math.toRadians(angleDeg.toDouble())
      val x = (cx + 16.5 * cos(rad)).roundToInt()
      val y = (cy + 16.5 * sin(rad)).roundToInt()
      val c = pixels[x, y]
      assertTrue(
        "Expected fill color margin without white glyph bleed at angle $angleDeg, got $c",
        c.blue > c.red && c.luminance() < 0.3f,
      )
    }
  }

  @Test
  fun whiteHandleOnWhiteBackgroundRemainsFramedByOuterContrastBorder() {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f)) {
        Box(modifier = Modifier.size(32.dp).background(Color.White), contentAlignment = Alignment.Center) {
          GradientHandle(glyph = "S", contentDescription = "Start", color = Color.White)
        }
      }
    }
    composeTestRule.waitForIdle()
    val pixels = composeTestRule.onRoot().captureToImage().toPixelMap()
    val cx = pixels.width / 2
    val cy = pixels.height / 2
    // Outer 1.dp (2px at 2x) border sits between radius 22px and 24px from center.
    val outerBorderPixel = pixels[cx + 23, cy]
    assertTrue("Expected visible dark outer border over white background, got $outerBorderPixel", outerBorderPixel.luminance() < 0.7f)
  }

  @Test
  fun pinnedHandleUsesOpaqueRingWithoutFillColorBleedAndRoundedSquareSilhouette() {
    var cursorColor by mutableStateOf(Color.Red)
    var cursorMovable by mutableStateOf(false)
    composeTestRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f)) {
        Box(modifier = Modifier.size(32.dp).background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
          PointCursor(row = 0, col = 0, color = cursorColor, movable = cursorMovable)
        }
      }
    }

    fun captureState(color: Color, movable: Boolean): PixelMap {
      composeTestRule.runOnIdle {
        cursorColor = color
        cursorMovable = movable
      }
      composeTestRule.waitForIdle()
      return composeTestRule.onRoot().captureToImage().toPixelMap()
    }

    val pinnedRed = captureState(Color.Red, movable = false)
    val pinnedBlue = captureState(Color.Blue, movable = false)
    val movableRed = captureState(Color.Red, movable = true)

    val cx = pinnedRed.width / 2
    val cy = pinnedRed.height / 2
    // Midpoint of the 1.5.dp ring along the horizontal axis (20px from center at 2x density).
    assertEquals(
      "Pinned ring color must be opaque and unaffected by the vertex fill color",
      pinnedRed[cx + 20, cy],
      pinnedBlue[cx + 20, cy],
    )

    // Along the 45-degree diagonal at offset (16, 16) from center (distance ~22.6px): inside the 24px
    // rounded square's ring, but near/outside the 24px circle's outer edge.
    assertNotEquals(
      "Pinned handle should have a rounded-square silhouette distinct from a movable circular handle",
      movableRed[cx + 16, cy + 16],
      pinnedRed[cx + 16, cy + 16],
    )
  }

  @Test
  fun transparentHandleDrawsLightCheckerboardInsteadOfCanvasBackground() {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f)) {
        Box(modifier = Modifier.size(32.dp).background(Color.Black), contentAlignment = Alignment.Center) {
          GradientHandle(glyph = "S", contentDescription = "Start", color = Color.Transparent)
        }
      }
    }
    composeTestRule.waitForIdle()
    val pixels = composeTestRule.onRoot().captureToImage().toPixelMap()
    val cx = pixels.width / 2
    val cy = pixels.height / 2

    // Sample interior points along the left fill margin (x = cx - 14..cx - 9, y = cy - 6..cy + 6, radius <= 15.2px, clear of both the
    // center "S" glyph and the 18..19px inner hairline) to verify the light checkerboard cells.
    val interiorSamples = mutableSetOf<Color>()
    for (y in (cy - 6)..(cy + 6)) {
      for (x in (cx - 14)..(cx - 9)) {
        val c = pixels[x, y]
        assertTrue("Transparent handle interior should not leak black canvas background, got $c at ($x,$y)", c.luminance() > 0.65f)
        interiorSamples.add(c)
      }
    }
    assertTrue("Expected distinct light and gray checkerboard cells, got $interiorSamples", interiorSamples.size >= 2)
  }
}
