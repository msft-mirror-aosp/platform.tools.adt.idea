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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MeshGradientPainterTest {

  @Test
  fun bicubicColorOvershootIsClamped() {
    // Rows of vertices with alpha 0, 1, 1, 0 make the Catmull-Rom spline of the middle patch overshoot alpha = 1 along the V axis.
    val painter =
      MeshGradientPainter(rows = 3, columns = 1, hasBicubicColor = true) {
        for (row in 0..3) {
          val alpha = if (row == 0 || row == 3) 0f else 1f
          for (column in 0..1) {
            setVertex(row, column, Offset(column.toFloat(), row / 3f), Color.Red.copy(alpha = alpha))
          }
        }
      }

    val pixels = painter.drawToBitmap(width = 60, height = 60).toPixelMap()

    val middle = pixels[30, 30]
    assertEquals(1f, middle.alpha, 0.01f)
    assertEquals(1f, middle.red, 0.01f)
  }

  @Test
  fun drawsVertexColorsAtTheCorners() {
    val painter =
      MeshGradientPainter(rows = 1, columns = 1) {
        setVertex(0, 0, Offset(0f, 0f), Color.Red)
        setVertex(0, 1, Offset(1f, 0f), Color.Green)
        setVertex(1, 0, Offset(0f, 1f), Color.Blue)
        setVertex(1, 1, Offset(1f, 1f), Color.White)
      }

    val pixels = painter.drawToBitmap(width = 100, height = 100).toPixelMap()

    assertColorEquals(Color.Red, pixels[0, 0])
    assertColorEquals(Color.Green, pixels[99, 0])
    assertColorEquals(Color.Blue, pixels[0, 99])
    assertColorEquals(Color.White, pixels[99, 99])
  }

  private fun assertColorEquals(expected: Color, actual: Color) {
    val tolerance = 0.05f
    assertEquals("alpha of $actual", expected.alpha, actual.alpha, tolerance)
    assertEquals("red of $actual", expected.red, actual.red, tolerance)
    assertEquals("green of $actual", expected.green, actual.green, tolerance)
    assertEquals("blue of $actual", expected.blue, actual.blue, tolerance)
  }

  private fun Painter.drawToBitmap(width: Int, height: Int): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val size = Size(width.toFloat(), height.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) { draw(size) }
    return bitmap
  }
}
