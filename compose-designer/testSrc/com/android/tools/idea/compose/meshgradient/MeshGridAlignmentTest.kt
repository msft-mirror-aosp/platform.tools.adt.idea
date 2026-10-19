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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MeshGridAlignmentTest {
  /** A [rows] x [cols] mesh whose vertices all differ. */
  private fun mesh(rows: Int, cols: Int): List<List<MeshGradientPoint>> =
    List(rows) { row -> List(cols) { col -> MeshGradientPoint(evenMeshPosition(row, col, rows, cols), Color(row * 16 + col, 0, 0)) } }

  @Test
  fun sameSizeIsAlignedByIndex() {
    val initial = mesh(3, 3)
    val target = initial.map { row -> row.map { it.withColor(Color.Black) } }

    assertEquals(listOf(0, 1, 2) to listOf(0, 1, 2), alignMeshGrids(initial, target))
  }

  @Test
  fun resizedMeshKeepsItsLastRowAndColumnAtTheEnd() {
    val initial = mesh(3, 3)

    assertEquals(listOf(0, 1, null, 2) to listOf(0, 1, 2), alignMeshGrids(initial, resizeMeshGrid(initial, 4, 3)))
    assertEquals(listOf(0, 2) to listOf(0, 1, null, null, 2), alignMeshGrids(initial, resizeMeshGrid(initial, 2, 5)))
  }

  @Test
  fun alignmentByIndexCanBeRequired() {
    val initial = mesh(3, 3)

    assertEquals(listOf(0, 1, 2, null) to listOf(0, 1, 2), alignMeshGrids(initial, resizeMeshGrid(initial, 4, 3), byIndexOnly = true))
  }

  @Test
  fun verticesAddedOrRemovedAtTheEndAreAlignedByIndex() {
    val initial = mesh(3, 3)
    val grown = initial.mapIndexed { row, points -> points + MeshGradientPoint(Offset(2f, row / 2f), Color.Black) }

    assertEquals(listOf(0, 1, 2) to listOf(0, 1, 2, null), alignMeshGrids(initial, grown))
    assertEquals(listOf(0, 1) to listOf(0, 1, 2), alignMeshGrids(initial, initial.take(2)))
  }
}
