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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BrushGeometryTest {
  private val size = Size(400f, 200f)
  private val colors = listOf(Color.Red, Color.Blue)

  @Test
  fun resolveLinearPointKeepsPixelValues() {
    assertEquals(Offset(0.5f, 0.5f), resolveLinearPoint(Offset(0.5f, 0.5f), size))
    assertEquals(Offset(1000f, -20f), resolveLinearPoint(Offset(1000f, -20f), size))
  }

  @Test
  fun resolveLinearPointResolvesInfiniteCoordinatesToSize() {
    assertEquals(Offset(400f, 200f), resolveLinearPoint(Offset.Infinite, size))
    assertEquals(Offset(400f, 0f), resolveLinearPoint(Offset(Float.POSITIVE_INFINITY, 0f), size))
    assertEquals(Offset(10f, 200f), resolveLinearPoint(Offset(10f, Float.POSITIVE_INFINITY), size))
  }

  @Test
  fun resolvePlacesInvalidCoordinatesAtZero() {
    assertEquals(Offset.Zero, resolveLinearPoint(Offset.Unspecified, size))
    assertEquals(Offset(0f, 10f), resolveLinearPoint(Offset(Float.NEGATIVE_INFINITY, 10f), size))
    assertEquals(Offset(10f, 0f), resolveCenter(Offset(10f, Float.NaN), size))
  }

  @Test
  fun resolveCenterOfRadialAndSweepGradients() {
    assertEquals(Offset(200f, 100f), resolveCenter(Offset.Unspecified, size))
    assertEquals(Offset(400f, 200f), resolveCenter(Offset.Infinite, size))
    assertEquals(Offset(30f, 40f), resolveCenter(Offset(30f, 40f), size))
  }

  @Test
  fun clampToPreviewOnlyAffectsPlacement() {
    assertEquals(Offset(400f, 0f), clampToPreview(Offset(1000f, -20f), size))
    assertEquals(Offset(10f, 20f), clampToPreview(Offset(10f, 20f), size))
  }

  @Test
  fun draggedPointAppliesTotalDeltaToHandleStart() {
    assertEquals(Offset(130f, 70f), draggedPoint(Offset(100f, 50f), Offset(100f, 50f), Offset(30f, 20f), scale = 1f))
    assertEquals(
      "Canvas deltas are converted to brush pixels",
      Offset(160f, 90f),
      draggedPoint(Offset(100f, 50f), Offset(100f, 50f), Offset(30f, 20f), scale = 0.5f),
    )
  }

  @Test
  fun draggedPointIsNotClampedToUnitRange() {
    assertEquals(Offset(350f, 180f), draggedPoint(Offset(300f, 150f), Offset(300f, 150f), Offset(50f, 30f), scale = 1f))
  }

  @Test
  fun draggedPointKeepsAxesWithinDeadZone() {
    val original = Offset(Float.POSITIVE_INFINITY, 50f)
    assertEquals(Offset(Float.POSITIVE_INFINITY, 80f), draggedPoint(original, Offset(400f, 50f), Offset(1.5f, 30f), scale = 1f))
    assertEquals(Offset(Float.POSITIVE_INFINITY, 50f), draggedPoint(original, Offset(400f, 50f), Offset(-1.9f, 1.9f), scale = 1f))
    assertEquals(original, draggedPoint(original, Offset(400f, 50f), Offset.Zero, scale = 1f))
  }

  @Test
  fun draggedPointRoundsToCanvasPrecision() {
    assertEquals(0, dragDecimals(1f))
    assertEquals(0, dragDecimals(0.25f))
    assertEquals(1, dragDecimals(4f))
    assertEquals(2, dragDecimals(40f))
    assertEquals(
      "A canvas pixel is 4 brush pixels: whole pixels are enough",
      Offset(113f, 50f),
      draggedPoint(Offset(100f, 50f), Offset(100f, 50f), Offset(3.2f, 0f), scale = 0.25f),
    )
    assertEquals(
      "A canvas pixel is 0.25 brush pixels: one decimal is needed",
      Offset(100.8f, 50f),
      draggedPoint(Offset(100f, 50f), Offset(100f, 50f), Offset(3.2f, 0f), scale = 4f),
    )
  }

  @Test
  fun draggedPointNeverRoundsToNegativeZero() {
    // 2.2 - 2.5 = -0.3, which rounds to -0 with no decimals.
    val dragged = draggedPoint(Offset(2.2f, 50f), Offset(2.2f, 50f), Offset(-2.5f, 0f), scale = 1f)

    assertEquals(0f.toBits(), dragged.x.toBits())
    assertEquals(Offset(0f, 50f), dragged)
  }

  @Test
  fun draggedPointOfOutOfBoundsPointStartsFromHandle() {
    // The handle of (1000, 50) is drawn at the right edge of a 400px wide preview.
    assertEquals(Offset(390f, 50f), draggedPoint(Offset(1000f, 50f), Offset(400f, 50f), Offset(-10f, 0f), scale = 1f))
  }

  @Test
  fun draggedPointResolvesBothAxesOfUnspecifiedPoint() {
    assertEquals(Offset(210f, 100f), draggedPoint(Offset.Unspecified, Offset(200f, 100f), Offset(10f, 0f), scale = 1f))
  }

  @Test
  fun withCoordinateSetsPixelValue() {
    assertEquals(Offset(25f, 20f), Offset(10f, 20f).withCoordinate(Axis.X, 25f, Offset.Zero, PointKind.START))
    assertEquals(Offset(10f, 35f), Offset(10f, 20f).withCoordinate(Axis.Y, 35f, Offset.Zero, PointKind.CENTER))
  }

  @Test
  fun withCoordinateClearsAccordingToPointKind() {
    assertEquals(Offset(0f, 20f), Offset(10f, 20f).withCoordinate(Axis.X, null, Offset.Zero, PointKind.START))
    assertEquals(Offset(10f, Float.POSITIVE_INFINITY), Offset(10f, 20f).withCoordinate(Axis.Y, null, Offset.Zero, PointKind.END))
    assertEquals(Offset.Unspecified, Offset(10f, 20f).withCoordinate(Axis.Y, null, Offset.Zero, PointKind.CENTER))
  }

  @Test
  fun withCoordinateOnUnspecifiedPointUsesResolvedValue() {
    assertEquals(Offset(10f, 100f), Offset.Unspecified.withCoordinate(Axis.X, 10f, Offset(200f, 100f), PointKind.CENTER))
  }

  @Test
  fun inferPreviewSizeForLinearGradient() {
    assertEquals(
      Size(300f, 150f),
      inferPreviewSize(GradientType.LINEAR, Offset(10f, 20f), Offset(300f, 150f), Offset.Unspecified, Float.POSITIVE_INFINITY),
    )
    assertEquals(
      Size(251f, 251f),
      inferPreviewSize(GradientType.LINEAR, Offset.Zero, Offset(250.4f, Float.POSITIVE_INFINITY), Offset.Unspecified, 0f),
    )
  }

  @Test
  fun inferPreviewSizeFallsBackToDefault() {
    assertEquals(
      DEFAULT_PREVIEW_SIZE,
      inferPreviewSize(GradientType.LINEAR, Offset.Zero, Offset.Infinite, Offset.Unspecified, Float.POSITIVE_INFINITY),
    )
    assertEquals(
      DEFAULT_PREVIEW_SIZE,
      inferPreviewSize(GradientType.SWEEP, Offset.Zero, Offset.Infinite, Offset.Unspecified, Float.POSITIVE_INFINITY),
    )
    assertEquals(DEFAULT_PREVIEW_SIZE, inferPreviewSize(GradientType.MESH, Offset.Zero, Offset(10f, 10f), Offset(5f, 5f), 5f))
  }

  @Test
  fun inferPreviewSizeForRadialAndSweepGradients() {
    assertEquals(Size(200f, 200f), inferPreviewSize(GradientType.RADIAL, Offset.Zero, Offset.Infinite, Offset.Unspecified, 100f))
    assertEquals(Size(200f, 130f), inferPreviewSize(GradientType.RADIAL, Offset.Zero, Offset.Infinite, Offset(100f, 50f), 80f))
    assertEquals(
      Size(200f, 200f),
      inferPreviewSize(GradientType.RADIAL, Offset.Zero, Offset.Infinite, Offset(100f, 100f), Float.POSITIVE_INFINITY),
    )
    assertEquals(Size(300f, 200f), inferPreviewSize(GradientType.SWEEP, Offset.Zero, Offset.Infinite, Offset(150f, 100f), 1000f))
  }

  @Test
  fun previewBrushKeepsAutomaticValuesForComposeToResolve() {
    assertEquals(
      Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite, tileMode = TileMode.Mirror),
      createPreviewBrush(Gradient.LinearGradient(colors, null, Offset.Zero, Offset.Infinite, TileMode.Mirror)),
    )
    assertEquals(
      Brush.radialGradient(colors, center = Offset.Unspecified, radius = Float.POSITIVE_INFINITY),
      createPreviewBrush(Gradient.RadialGradient(colors, null, Offset.Unspecified, Float.POSITIVE_INFINITY)),
    )
    assertEquals(
      Brush.sweepGradient(0f to Color.Red, 0.3f to Color.Blue, center = Offset.Unspecified),
      createPreviewBrush(Gradient.SweepGradient(colors, listOf(0f to Color.Red, 0.3f to Color.Blue), Offset.Unspecified)),
    )
  }

  @Test
  fun previewBrushUsesPixelValuesAsIs() {
    assertEquals(
      Brush.linearGradient(colors, start = Offset(0.1f, 0.2f), end = Offset(0.8f, 0.9f)),
      createPreviewBrush(Gradient.LinearGradient(colors, null, Offset(0.1f, 0.2f), Offset(0.8f, 0.9f))),
    )
  }

  @Test
  fun previewBrushReplacesValuesThatBreakTheShader() {
    assertEquals(
      Brush.linearGradient(colors, start = Offset.Zero, end = Offset(0f, Float.POSITIVE_INFINITY)),
      createPreviewBrush(
        Gradient.LinearGradient(colors, null, Offset.Unspecified, Offset(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY))
      ),
    )
    assertEquals(
      Brush.radialGradient(colors, center = Offset(0f, 5f), radius = MIN_RADIUS),
      createPreviewBrush(Gradient.RadialGradient(colors, null, Offset(Float.NaN, 5f), 0f)),
    )
    assertEquals(
      Brush.radialGradient(colors, center = Offset(5f, 5f), radius = Float.POSITIVE_INFINITY),
      createPreviewBrush(Gradient.RadialGradient(colors, null, Offset(5f, 5f), Float.NaN)),
    )
    assertEquals(
      Brush.sweepGradient(listOf(Color.Red, Color.Red), center = Offset.Unspecified),
      createPreviewBrush(Gradient.SweepGradient(listOf(Color.Red), null, Offset.Unspecified)),
    )
  }
}
