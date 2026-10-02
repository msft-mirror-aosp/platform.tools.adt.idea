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
package com.android.tools.idea.compose.meshgradient.impl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MeshGradientRendererTest {

  @Test
  fun largeMeshIsDrawnInBalancedBatchesAddressableWith16BitIndices() {
    // Each patch is large enough to be tessellated into 64x64 vertices, so at most 8 patches fit in a 16-bit index buffer. The 9 patches
    // are therefore drawn in 2 batches, of 5 and 4 patches.
    val config = gridConfig(rows = 3, columns = 3)
    val size = Size(2048f, 2048f)
    val (subdivisionsU, subdivisionsV) = calculateMeshGradientSubdivisions(3, 3, config.positions, size)
    assertEquals(64, subdivisionsU)
    assertEquals(64, subdivisionsV)
    val verticesPerPatch = subdivisionsU * subdivisionsV
    val indicesPerPatch = (subdivisionsU - 1) * (subdivisionsV - 1) * 6

    val batches = tessellate(config, size)

    assertEquals(2, batches.size)
    assertEquals(5 * indicesPerPatch, batches[0].indices.size)
    assertEquals(4 * indicesPerPatch, batches[1].indices.size)
    for (batch in batches) {
      assertEquals(5 * verticesPerPatch, batch.colors.size)
      assertEquals(batch.positions.size / 2, batch.colors.size)
      assertTrue("Indices out of the vertex range", batch.indices.all { it >= 0 && it < batch.colors.size })
    }
    // The last batch ends with the bottom-right patch, whose last vertex is the bottom-right corner of the mesh.
    val lastBatch = batches[1]
    val lastVertex = 4 * verticesPerPatch - 1
    assertEquals(lastVertex, lastBatch.indices.maxOf { it.toInt() })
    assertEquals(2048f, lastBatch.positions[lastVertex * 2], 0.01f)
    assertEquals(2048f, lastBatch.positions[lastVertex * 2 + 1], 0.01f)
  }

  @Test
  fun smallMeshIsDrawnInASingleBatch() {
    val config = gridConfig(rows = 3, columns = 4)

    val batch = tessellate(config, Size(400f, 300f)).single()

    assertEquals(batch.positions.size / 2, batch.colors.size)
    assertEquals(0, batch.indices.size % 3)
    assertEquals(batch.colors.size - 1, batch.indices.maxOf { it.toInt() })
    assertEquals(0, batch.indices.minOf { it.toInt() })
  }

  @Test
  fun bilinearColorsAreInterpolatedInOklabWithoutIntermediateQuantization() {
    val topLeft = Color(0xFFE91E63)
    val topRight = Color(0xFF2196F3)
    val bottomLeft = Color(0xFFFFEB3B)
    val bottomRight = Color(0xFF4CAF50)
    val config =
      MeshGradientConfig(rows = 1, columns = 1).apply {
        configure {
          setVertex(0, 0, Offset(0f, 0f), topLeft)
          setVertex(0, 1, Offset(1f, 0f), topRight)
          setVertex(1, 0, Offset(0f, 1f), bottomLeft)
          setVertex(1, 1, Offset(1f, 1f), bottomRight)
        }
      }
    val size = Size(512f, 512f)
    val (subdivisionsU, subdivisionsV) = calculateMeshGradientSubdivisions(1, 1, config.positions, size)

    val colors = tessellate(config, size).single().colors

    for (uIndex in 0 until subdivisionsU) {
      for (vIndex in 0 until subdivisionsV) {
        val u = uIndex / (subdivisionsU - 1f)
        val v = vIndex / (subdivisionsV - 1f)
        val expected = oklabBilinear(topLeft, topRight, bottomLeft, bottomRight, u, v)
        assertEquals("Color at u=$uIndex, v=$vIndex", expected.toArgb(), colors[uIndex * subdivisionsV + vIndex])
      }
    }
  }

  @Test
  fun patchCornersMatchTheMeshVertices() {
    val topLeft = Offset(0.1f, 0.05f)
    val bottomRight = Offset(0.95f, 0.9f)
    for (hasBicubicColor in listOf(false, true)) {
      val config =
        MeshGradientConfig(rows = 1, columns = 1, hasBicubicColor = hasBicubicColor).apply {
          configure {
            setVertex(0, 0, topLeft, Color.Red)
            setVertex(0, 1, Offset(0.9f, 0.1f), Color.Green)
            setVertex(1, 0, Offset(0.05f, 0.95f), Color.Blue)
            setVertex(1, 1, bottomRight, Color.White)
          }
        }

      val batch = tessellate(config, Size(300f, 200f)).single()

      val last = batch.colors.size - 1
      assertEquals(topLeft.x * 300f, batch.positions[0], 1e-3f)
      assertEquals(topLeft.y * 200f, batch.positions[1], 1e-3f)
      assertEquals(bottomRight.x * 300f, batch.positions[last * 2], 1e-3f)
      assertEquals(bottomRight.y * 200f, batch.positions[last * 2 + 1], 1e-3f)
      assertArgbEquals(Color.Red.toArgb(), batch.colors[0])
      assertArgbEquals(Color.White.toArgb(), batch.colors[last])
    }
  }

  @Test
  fun adjacentPatchesShareTheirCommonEdge() {
    for (hasBicubicColor in listOf(false, true)) {
      // Two patches side by side, whose shared edge is curved.
      val config =
        MeshGradientConfig(rows = 1, columns = 2, hasBicubicColor = hasBicubicColor).apply {
          configure {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(0.5f, 0f), Color.Green, bottomControlPoint = Offset(0.2f, 0.3f))
            setVertex(0, 2, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Yellow)
            setVertex(1, 1, Offset(0.4f, 1f), Color.Magenta, topControlPoint = Offset(-0.2f, -0.3f))
            setVertex(1, 2, Offset(1f, 1f), Color.Cyan)
          }
        }
      val size = Size(400f, 300f)
      val (subdivisionsU, subdivisionsV) = calculateMeshGradientSubdivisions(1, 2, config.positions, size)

      val batch = tessellate(config, size).single()

      val verticesPerPatch = subdivisionsU * subdivisionsV
      for (vIndex in 0 until subdivisionsV) {
        val leftPatchVertex = (subdivisionsU - 1) * subdivisionsV + vIndex
        val rightPatchVertex = verticesPerPatch + vIndex
        assertEquals(batch.positions[leftPatchVertex * 2], batch.positions[rightPatchVertex * 2], 0.01f)
        assertEquals(batch.positions[leftPatchVertex * 2 + 1], batch.positions[rightPatchVertex * 2 + 1], 0.01f)
        assertArgbEquals(batch.colors[leftPatchVertex], batch.colors[rightPatchVertex])
      }
    }
  }

  @Test
  fun bicubicAlphaOvershootIsClamped() {
    // Rows of vertices with alpha 0, 1, 1, 0 make the Catmull-Rom spline of the middle patch overshoot alpha = 1 along the V axis.
    val transparent = Color.Red.copy(alpha = 0f)
    val colors = middlePatchColorsOfBicubicColumn(listOf(transparent, Color.Red, Color.Red, transparent))

    for (color in colors) {
      assertEquals(0xFF, color ushr 24)
    }
  }

  @Test
  fun bicubicAlphaUndershootIsClamped() {
    // Rows of vertices with alpha 1, 0, 0, 1 make the Catmull-Rom spline of the middle patch undershoot alpha = 0 along the V axis.
    val transparent = Color.Red.copy(alpha = 0f)
    val colors = middlePatchColorsOfBicubicColumn(listOf(Color.Red, transparent, transparent, Color.Red))

    for (color in colors) {
      assertEquals(0, color ushr 24)
    }
  }

  @Test
  fun bicubicLightnessOvershootIsClamped() {
    // Rows of black, white, white and black vertices make the Catmull-Rom spline of the middle patch overshoot L = 1 along the V axis.
    val colors = middlePatchColorsOfBicubicColumn(listOf(Color.Black, Color.White, Color.White, Color.Black))

    for (color in colors) {
      assertArgbEquals(Color.White.toArgb(), color)
    }
  }

  /**
   * Tessellates a bicubic 3x1 mesh whose vertex rows have the given colors, and returns the ARGB colors of the vertices of the middle
   * patch.
   */
  private fun middlePatchColorsOfBicubicColumn(rowColors: List<Color>): List<Int> {
    val config =
      MeshGradientConfig(rows = 3, columns = 1, hasBicubicColor = true).apply {
        configure {
          for (row in 0..3) {
            for (column in 0..1) {
              setVertex(row, column, Offset(column.toFloat(), row / 3f), rowColors[row])
            }
          }
        }
      }
    val size = Size(60f, 60f)
    val (subdivisionsU, subdivisionsV) = calculateMeshGradientSubdivisions(3, 1, config.positions, size)
    val verticesPerPatch = subdivisionsU * subdivisionsV

    val colors = tessellate(config, size).single().colors

    return colors.slice(verticesPerPatch until 2 * verticesPerPatch)
  }

  private class Batch(val positions: FloatArray, val colors: IntArray, val indices: ShortArray)

  private fun tessellate(config: MeshGradientConfig, size: Size): List<Batch> {
    val batches = mutableListOf<Batch>()
    MeshGradientRenderer().tessellate(config, size) { positions, colors, indices ->
      batches.add(Batch(positions.copyOf(), colors.copyOf(), indices.copyOf()))
    }
    return batches
  }

  /** Asserts that every 8-bit channel of the [actual] ARGB color is at most 1 away from the [expected] one. */
  private fun assertArgbEquals(expected: Int, actual: Int) {
    val matches = (0 until 32 step 8).all { shift -> abs((expected ushr shift and 0xFF) - (actual ushr shift and 0xFF)) <= 1 }
    assertTrue("Expected ${expected.toUInt().toString(16)} but was ${actual.toUInt().toString(16)}", matches)
  }

  /** Creates a configuration with a regular grid of opaque vertices. */
  private fun gridConfig(rows: Int, columns: Int, hasBicubicColor: Boolean = false): MeshGradientConfig =
    MeshGradientConfig(rows, columns, hasBicubicColor).apply {
      configure {
        for (row in 0..rows) {
          for (column in 0..columns) {
            val color = Color(red = row / rows.toFloat(), green = column / columns.toFloat(), blue = 0.5f)
            setVertex(row, column, Offset(column / columns.toFloat(), row / rows.toFloat()), color)
          }
        }
      }
    }

  /**
   * Bilinearly interpolates the given corner colors in Oklab, keeping full float precision until the final conversion.
   *
   * This intentionally mirrors the renderer's formula, including the order of the operations, so that results can be compared exactly: any
   * intermediate quantization (like the one of nested `lerp(Color, Color)` calls) makes some vertices differ.
   */
  private fun oklabBilinear(topLeft: Color, topRight: Color, bottomLeft: Color, bottomRight: Color, u: Float, v: Float): Color {
    fun lerp(start: Float, stop: Float, fraction: Float) = (1f - fraction) * start + fraction * stop
    val corners = listOf(topLeft, topRight, bottomLeft, bottomRight).map { it.convert(ColorSpaces.Oklab) }
    val components =
      (0 until 4).map { channel ->
        val (tl, tr, bl, br) = corners.map { it.component(channel) }
        lerp(lerp(tl, tr, u), lerp(bl, br, u), v)
      }
    return Color(components[0], components[1], components[2], components[3], ColorSpaces.Oklab)
  }

  private fun Color.component(channel: Int): Float =
    when (channel) {
      0 -> red
      1 -> green
      2 -> blue
      else -> alpha
    }
}
