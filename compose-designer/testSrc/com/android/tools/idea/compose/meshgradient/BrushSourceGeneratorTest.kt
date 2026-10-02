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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BrushSourceGeneratorTest {

  @Test
  fun linearGradientWithColors() {
    val source =
      BrushSourceGenerator.generate(
        Gradient.LinearGradient(listOf(Color.Red, Color.Blue), null, Offset(10f, 20f), Offset(300f, 150f), TileMode.Mirror)
      )
    assertEquals(
      """
      Brush.linearGradient(
          colors = listOf(Color(0xFFFF0000), Color(0xFF0000FF)),
          start = Offset(10f, 20f),
          end = Offset(300f, 150f),
          tileMode = TileMode.Mirror,
      )
      """
        .trimIndent(),
      source,
    )
  }

  @Test
  fun colorStopsAreEmittedAsVarargPairs() {
    val source =
      BrushSourceGenerator.generate(
        Gradient.LinearGradient(
          listOf(Color.Red, Color.Blue),
          listOf(0f to Color.Red, 0.25f to Color.Blue),
          Offset.Zero,
          Offset.Infinite,
          TileMode.Clamp,
        )
      )
    assertEquals(
      """
      Brush.linearGradient(
          0f to Color(0xFFFF0000),
          0.25f to Color(0xFF0000FF),
          start = Offset(0f, 0f),
          end = Offset.Infinite,
          tileMode = TileMode.Clamp,
      )
      """
        .trimIndent(),
      source,
    )
  }

  @Test
  fun radialGradientKeepsAutomaticValues() {
    val source =
      BrushSourceGenerator.generate(
        Gradient.RadialGradient(listOf(Color.Red, Color.Blue), null, Offset.Unspecified, Float.POSITIVE_INFINITY, TileMode.Decal)
      )
    assertEquals(
      """
      Brush.radialGradient(
          colors = listOf(Color(0xFFFF0000), Color(0xFF0000FF)),
          center = Offset.Unspecified,
          radius = Float.POSITIVE_INFINITY,
          tileMode = TileMode.Decal,
      )
      """
        .trimIndent(),
      source,
    )
  }

  @Test
  fun sweepGradient() {
    val source = BrushSourceGenerator.generate(Gradient.SweepGradient(listOf(Color.Red, Color.Blue), null, Offset(200f, 100f)))
    assertEquals(
      """
      Brush.sweepGradient(
          colors = listOf(Color(0xFFFF0000), Color(0xFF0000FF)),
          center = Offset(200f, 100f),
      )
      """
        .trimIndent(),
      source,
    )
  }

  @Test
  fun tileModeNameDoesNotDependOnToString() {
    assertEquals("Clamp", tileModeName(TileMode.Clamp))
    assertEquals("Repeated", tileModeName(TileMode.Repeated))
    assertEquals("Mirror", tileModeName(TileMode.Mirror))
    assertEquals("Decal", tileModeName(TileMode.Decal))
  }
}
