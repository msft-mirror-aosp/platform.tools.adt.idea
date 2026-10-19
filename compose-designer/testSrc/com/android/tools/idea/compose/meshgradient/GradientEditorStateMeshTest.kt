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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GradientEditorStateMeshTest {
  private val red = Color(0xFFF44336)
  private val blue = Color(0xFF2196F3)
  private val green = Color(0xFF4CAF50)
  private val orange = Color(0xFFFF9800)

  /** A 3x3 mesh whose vertices all carry position and color expressions and a control point. */
  private fun expressionMesh(): List<List<MeshGradientPoint>> =
    List(3) { row ->
      List(3) { col ->
        MeshGradientPoint(
          position = Offset(col / 2f, row / 2f),
          color = if ((row + col) % 2 == 0) red else blue,
          rightBezierOffset = Offset(0.1f, 0f),
          positionExpression = "p$row$col",
          colorExpression = "c$row$col",
        )
      }
    }

  private fun stateWith(points: List<List<MeshGradientPoint>>): GradientEditorState = GradientEditorState().apply { loadMesh(points) }

  @Test
  fun growingRowsKeepsExistingVerticesAndInsertsBeforeTheLastRow() {
    val original = expressionMesh()
    val state = stateWith(original)

    state.updateRows(4)

    assertEquals(4, state.rows)
    assertEquals(4, state.meshPoints.size)
    assertEquals(original[0], state.meshPoints[0])
    assertEquals(original[1], state.meshPoints[1])
    assertEquals(original[2], state.meshPoints[3])
    state.meshPoints[2].forEachIndexed { col, point ->
      assertEquals(Offset(col / 2f, 0.75f), point.position)
      assertEquals(original[1][col].color, point.color)
      assertNull(point.positionExpression)
      assertNull(point.colorExpression)
    }
  }

  @Test
  fun growingColumnsInterpolatesNewVertices() {
    val original = expressionMesh()
    val state = stateWith(original)

    state.updateCols(5)

    assertEquals(5, state.cols)
    state.meshPoints.forEachIndexed { row, rowPoints ->
      assertEquals(5, rowPoints.size)
      assertEquals(original[row][0], rowPoints[0])
      assertEquals(original[row][1], rowPoints[1])
      assertEquals(original[row][2], rowPoints[4])
      assertEquals(2f / 3f, rowPoints[2].position.x, 1e-6f)
      assertEquals(5f / 6f, rowPoints[3].position.x, 1e-6f)
      assertEquals(row / 2f, rowPoints[2].position.y, 1e-6f)
      assertEquals(original[row][1].color, rowPoints[2].color)
      assertEquals(original[row][2].color, rowPoints[3].color)
    }
  }

  @Test
  fun shrinkingAndGrowingBackRestoresTheMesh() {
    val original = expressionMesh()
    val state = stateWith(original)

    state.updateRows(4)
    state.updateCols(4)
    state.updateRows(3)
    state.updateCols(3)

    assertEquals(original, state.meshPoints)
  }

  @Test
  fun shrinkingDropsInteriorRowsAndKeepsTheBorder() {
    val original = expressionMesh()
    val state = stateWith(original)

    state.updateRows(2)
    state.updateCols(2)

    assertEquals(listOf(listOf(original[0][0], original[0][2]), listOf(original[2][0], original[2][2])), state.meshPoints)
  }

  @Test
  fun repeatedDimensionUpdateIsIdempotent() {
    val state = stateWith(expressionMesh())

    state.updateRows(4)
    val afterFirstUpdate = state.meshPoints.toList()
    state.updateRows(4)

    assertEquals(afterFirstUpdate, state.meshPoints)
  }

  @Test
  fun dimensionsAreClamped() {
    val state = GradientEditorState()

    state.updateRows(MAX_MESH_DIMENSION + 5)
    state.updateCols(MIN_MESH_DIMENSION - 1)

    assertEquals(MAX_MESH_DIMENSION, state.meshPoints.size)
    assertTrue(state.meshPoints.all { it.size == MIN_MESH_DIMENSION })
  }

  @Test
  fun constrainedCornerUpdateIsANoOp() {
    val state = stateWith(expressionMesh())
    state.constrainEdgePoints = true

    state.updateMeshPoint(0, 0, Offset(0.01f, 0.02f))

    assertEquals("p00", state.meshPoints[0][0].positionExpression)
    assertEquals(Offset.Zero, state.meshPoints[0][0].position)
  }

  @Test
  fun constrainedEdgeVertexOnlyMovesAlongItsEdge() {
    val state = stateWith(expressionMesh())
    state.constrainEdgePoints = true

    state.updateMeshPoint(0, 1, Offset(0.5f, 0.3f))
    assertEquals("p01", state.meshPoints[0][1].positionExpression)

    state.updateMeshPoint(0, 1, Offset(0.4f, 0.3f))
    assertEquals(Offset(0.4f, 0f), state.meshPoints[0][1].position)
    assertNull(state.meshPoints[0][1].positionExpression)
    assertEquals("c01", state.meshPoints[0][1].colorExpression)
  }

  @Test
  fun unchangedPositionKeepsExpression() {
    val state = stateWith(expressionMesh())
    state.constrainEdgePoints = false

    state.updateMeshPoint(1, 1, Offset(0.5f, 0.5f))

    assertEquals("p11", state.meshPoints[1][1].positionExpression)
  }

  @Test
  fun unchangedVertexColorKeepsExpression() {
    val state = stateWith(expressionMesh())

    state.updateVertexColor(1, 1, state.meshPoints[1][1].color)
    assertEquals("c11", state.meshPoints[1][1].colorExpression)

    state.updateVertexColor(1, 1, green)
    assertEquals(green, state.meshPoints[1][1].color)
    assertNull(state.meshPoints[1][1].colorExpression)
  }

  @Test
  fun distributeEvenlyKeepsExpressionsOfVerticesAlreadyInPlace() {
    val state = stateWith(expressionMesh())

    state.distributeMeshPointsEvenly()

    assertEquals(expressionMesh(), state.meshPoints)
  }

  @Test
  fun removePaletteColorRecolorsAndClearsExpressions() {
    val state = stateWith(expressionMesh())
    val redIndex = state.availableColors.indexOf(red)
    val fallback = state.availableColors.first { it != red }

    state.removePaletteColor(redIndex)

    assertTrue(red !in state.availableColors)
    state.meshPoints.flatten().forEach { point ->
      if (point.colorExpression == null) {
        assertEquals(fallback, point.color)
      } else {
        assertEquals(blue, point.color)
      }
    }
    assertNull(state.meshPoints[0][0].colorExpression)
    assertEquals("c01", state.meshPoints[0][1].colorExpression)
  }

  @Test
  fun lastPaletteColorCannotBeRemoved() {
    val state = GradientEditorState()

    repeat(state.availableColors.size + 2) { state.removePaletteColor(0) }

    assertEquals(1, state.availableColors.size)
    state.updateRows(5)
    assertTrue(state.meshPoints.flatten().isNotEmpty())
  }

  @Test
  fun paletteEditOnlyUpdatesVerticesCapturedWhenItStarted() {
    val state = stateWith(expressionMesh())
    val edit = state.beginPaletteColorEdit(state.availableColors.indexOf(red))
    assertNotNull(edit)

    // Passing through the color of other vertices must not capture them.
    edit!!.update(blue)
    edit.update(orange)

    state.meshPoints.forEachIndexed { row, rowPoints ->
      rowPoints.forEachIndexed { col, point ->
        if ((row + col) % 2 == 0) {
          assertEquals(orange, point.color)
          assertNull(point.colorExpression)
        } else {
          assertEquals(blue, point.color)
          assertEquals("c$row$col", point.colorExpression)
        }
      }
    }
    assertTrue(orange in state.availableColors)
    assertTrue(blue in state.availableColors)
    assertTrue(red !in state.availableColors)
  }

  @Test
  fun paletteEditWithTheSameColorKeepsExpressions() {
    val state = stateWith(expressionMesh())

    state.beginPaletteColorEdit(state.availableColors.indexOf(red))!!.update(red)

    assertEquals("c00", state.meshPoints[0][0].colorExpression)
  }

  @Test
  fun newPaletteColorEditUpdatesTheEntryItAdded() {
    val state = GradientEditorState()
    val initialSize = state.availableColors.size
    val edit = state.beginNewPaletteColor()

    edit.update(orange)
    edit.update(Color.Magenta)

    assertEquals(initialSize + 1, state.availableColors.size)
    assertEquals(Color.Magenta, state.availableColors.last())
    assertTrue(orange !in state.availableColors)
  }

  @Test
  fun paletteEditIsIgnoredAfterEntriesAreRemoved() {
    val state = GradientEditorState()
    val edit = state.beginNewPaletteColor()
    edit.update(orange)

    state.removePaletteColor(0)
    edit.update(Color.Magenta)

    assertTrue(orange in state.availableColors)
    assertTrue(Color.Magenta !in state.availableColors)
  }

  @Test
  fun paletteEditIsIgnoredAfterTheMeshIsResized() {
    val state = stateWith(expressionMesh())
    val edit = state.beginPaletteColorEdit(state.availableColors.indexOf(red))!!

    state.updateCols(4)
    edit.update(orange)

    assertTrue(orange !in state.availableColors)
    assertTrue(state.meshPoints.flatten().none { it.color == orange })
  }

  @Test
  fun paletteEditIsIgnoredAfterAMeshIsLoaded() {
    val state = stateWith(expressionMesh())
    val edit = state.beginPaletteColorEdit(state.availableColors.indexOf(red))!!

    state.loadMesh(expressionMesh().map { row -> row.map { it.copy(color = green, colorExpression = null) } })
    edit.update(orange)

    assertTrue(orange !in state.availableColors)
    assertTrue(state.meshPoints.flatten().all { it.color == green })
  }

  @Test
  fun paletteEditIsIgnoredAfterItIsFinished() {
    val state = stateWith(expressionMesh())
    val edit = state.beginPaletteColorEdit(state.availableColors.indexOf(red))!!

    edit.update(orange)
    edit.finish()
    edit.update(Color.Magenta)

    assertEquals(orange, state.meshPoints[0][0].color)
    assertTrue(Color.Magenta !in state.availableColors)
  }

  @Test
  fun finishingAPaletteEditMergesDuplicateEntries() {
    val state = stateWith(expressionMesh())
    val initialSize = state.availableColors.size
    val edit = state.beginPaletteColorEdit(state.availableColors.indexOf(red))!!

    edit.update(blue)
    assertEquals(initialSize, state.availableColors.size)
    edit.finish()

    assertEquals(initialSize - 1, state.availableColors.size)
    assertEquals(state.availableColors.distinct(), state.availableColors)
    // Every vertex now uses blue, so deleting it must recolor all of them.
    state.removePaletteColor(state.availableColors.indexOf(blue))
    assertTrue(state.meshPoints.flatten().none { it.color == blue })
  }

  @Test
  fun palettesStayDistinct() {
    val state = GradientEditorState()
    val initialSize = state.availableColors.size

    state.addAvailableColors(listOf(red, blue, orange, orange))
    state.replacePaletteColor(state.availableColors.indexOf(red), blue)

    assertEquals(state.availableColors.distinct(), state.availableColors)
    assertEquals(initialSize, state.availableColors.size)
  }

  @Test
  fun generatedCodeFollowsMeshChanges() {
    val state = GradientEditorState()
    state.constrainEdgePoints = false
    val before = state.generatedCode

    state.updateMeshPoint(1, 1, Offset(0.123f, 0.456f))

    assertNotEquals(before, state.generatedCode)
    assertTrue(state.generatedCode, state.generatedCode.contains("0.123"))
  }
}
