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

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MeshGradientTessellationTest {

  /** Positions of a 1x2 mesh whose left patch spans a quarter of the width and the right patch the remaining three quarters. */
  private val unevenPositions = floatArrayOf(0f, 0f, 0.25f, 0f, 1f, 0f, 0f, 1f, 0.25f, 1f, 1f, 1f)

  @Test
  fun subdivisionsFollowTheLargestPatch() {
    // The right patch is 225px wide and both patches are 100px tall, which gives ceil(225 / 8) = 29 vertices per row and
    // ceil(100 / 8) = 13 vertices per column of a patch.
    assertEquals(IntSize(29, 13), calculateMeshGradientSubdivisions(rows = 1, columns = 2, unevenPositions, Size(300f, 100f)))
  }

  @Test
  fun subdivisionsAreClampedToTheMinimum() {
    assertEquals(IntSize(4, 4), calculateMeshGradientSubdivisions(rows = 1, columns = 2, unevenPositions, Size(10f, 10f)))
    assertEquals(IntSize(4, 4), calculateMeshGradientSubdivisions(rows = 1, columns = 2, unevenPositions, Size.Zero))
    assertEquals(IntSize(4, 4), calculateMeshGradientSubdivisions(rows = 1, columns = 2, unevenPositions, Size(Float.NaN, Float.NaN)))
  }

  @Test
  fun subdivisionsAreClampedToTheMaximum() {
    assertEquals(IntSize(64, 64), calculateMeshGradientSubdivisions(rows = 1, columns = 2, unevenPositions, Size(100_000f, 100_000f)))
  }

  @Test
  fun pointIndexUsesOneMoreVertexThanColumnsPerRow() {
    assertEquals(0, meshGradientPointIndex(row = 0, col = 0, columns = 2))
    assertEquals(2, meshGradientPointIndex(row = 0, col = 2, columns = 2))
    assertEquals(3, meshGradientPointIndex(row = 1, col = 0, columns = 2))
    assertEquals(11, meshGradientPointIndex(row = 3, col = 2, columns = 2))
  }
}
