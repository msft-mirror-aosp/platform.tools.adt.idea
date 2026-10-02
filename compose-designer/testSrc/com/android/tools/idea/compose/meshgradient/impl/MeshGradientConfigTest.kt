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
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MeshGradientConfigTest {

  @Test
  fun invalidDimensionsAreRejectedBeforeAllocating() {
    assertThrows(IllegalArgumentException::class.java) { MeshGradientConfig(rows = -2, columns = 1) }
    assertThrows(IllegalArgumentException::class.java) { MeshGradientConfig(rows = 1, columns = -2) }
    assertThrows(IllegalArgumentException::class.java) { MeshGradientConfig(rows = 0, columns = 1) }
  }

  @Test
  fun unspecifiedControlPointsAreInferredAsAThirdOfTheDistanceToTheNeighbors() {
    val config = regularGridConfig(rows = 2, columns = 2)

    // Interior vertex at (0.5, 0.5): neighbors are 0.5 away in every direction.
    assertOffsetEquals(Offset(-1f / 6f, 0f), config.leftBezierOffsets.offsetAt(row = 1, column = 1, columns = 2))
    assertOffsetEquals(Offset(1f / 6f, 0f), config.rightBezierOffsets.offsetAt(row = 1, column = 1, columns = 2))
    assertOffsetEquals(Offset(0f, -1f / 6f), config.topBezierOffsets.offsetAt(row = 1, column = 1, columns = 2))
    assertOffsetEquals(Offset(0f, 1f / 6f), config.bottomBezierOffsets.offsetAt(row = 1, column = 1, columns = 2))

    // Top-left corner: there are no neighbors to the left or above, so those control points collapse onto the vertex.
    assertOffsetEquals(Offset.Zero, config.leftBezierOffsets.offsetAt(row = 0, column = 0, columns = 2))
    assertOffsetEquals(Offset.Zero, config.topBezierOffsets.offsetAt(row = 0, column = 0, columns = 2))
    assertOffsetEquals(Offset(1f / 6f, 0f), config.rightBezierOffsets.offsetAt(row = 0, column = 0, columns = 2))
    assertOffsetEquals(Offset(0f, 1f / 6f), config.bottomBezierOffsets.offsetAt(row = 0, column = 0, columns = 2))
  }

  @Test
  fun inferredTangentsAreCollinearAcrossTheVertex() {
    // Uneven spacing: the vertex at (0.25, 0) is 0.25 away from its left neighbor and 0.75 away from its right one.
    val config =
      MeshGradientConfig(rows = 1, columns = 2).apply {
        configure {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(0.25f, 0f), Color.Red)
          setVertex(0, 2, Offset(1f, 0f), Color.Red)
          setVertex(1, 0, Offset(0f, 1f), Color.Red)
          setVertex(1, 1, Offset(0.25f, 1f), Color.Red)
          setVertex(1, 2, Offset(1f, 1f), Color.Red)
        }
      }

    assertOffsetEquals(Offset(-0.25f / 3f, 0f), config.leftBezierOffsets.offsetAt(row = 0, column = 1, columns = 2))
    assertOffsetEquals(Offset(0.75f / 3f, 0f), config.rightBezierOffsets.offsetAt(row = 0, column = 1, columns = 2))
  }

  @Test
  fun explicitControlPointsAreKept() {
    val config =
      MeshGradientConfig(rows = 1, columns = 1).apply {
        configure {
          setVertex(0, 0, Offset(0f, 0f), Color.Red, rightControlPoint = Offset(0.1f, 0.05f), bottomControlPoint = Offset(-0.02f, 0.2f))
          setVertex(0, 1, Offset(1f, 0f), Color.Red)
          setVertex(1, 0, Offset(0f, 1f), Color.Red)
          setVertex(1, 1, Offset(1f, 1f), Color.Red)
        }
      }

    assertOffsetEquals(Offset(0.1f, 0.05f), config.rightBezierOffsets.offsetAt(row = 0, column = 0, columns = 1))
    assertOffsetEquals(Offset(-0.02f, 0.2f), config.bottomBezierOffsets.offsetAt(row = 0, column = 0, columns = 1))
  }

  private fun regularGridConfig(rows: Int, columns: Int): MeshGradientConfig =
    MeshGradientConfig(rows, columns).apply {
      configure {
        for (row in 0..rows) {
          for (column in 0..columns) {
            setVertex(row, column, Offset(column / columns.toFloat(), row / rows.toFloat()), Color.Red)
          }
        }
      }
    }

  private fun FloatArray.offsetAt(row: Int, column: Int, columns: Int): Offset {
    val index = meshGradientPointIndex(row, column, columns) * 2
    return Offset(this[index], this[index + 1])
  }

  private fun assertOffsetEquals(expected: Offset, actual: Offset) {
    assertEquals("x of $actual", expected.x, actual.x, 1e-6f)
    assertEquals("y of $actual", expected.y, actual.y, 1e-6f)
  }
}
