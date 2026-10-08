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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.preview.message
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GradientCanvasTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  private val canvasSize = 232.dp
  private val innerCanvasSize = canvasSize - CANVAS_PADDING * 2
  private val clickedVertices = mutableListOf<VertexIndex>()

  private fun setCanvas(state: GradientEditorState, onPointDrag: (Int, Int, Offset) -> Unit = state::updateMeshPoint) {
    composeTestRule.setContent {
      Box(Modifier.size(canvasSize)) {
        GradientCanvas(
          meshPoints = state.meshPoints,
          showPoints = true,
          constrainEdgePoints = state.constrainEdgePoints,
          onPointDrag = onPointDrag,
          onPointClick = { row, col -> clickedVertices.add(VertexIndex(row, col)) },
        )
      }
    }
  }

  private fun vertexNode(row: Int, col: Int): SemanticsNodeInteraction =
    composeTestRule.onNodeWithContentDescription(message("gradient.editor.mesh.vertex.description", row + 1, col + 1))

  private fun dragVertex(row: Int, col: Int, steps: Int, stepPx: Float) {
    vertexNode(row, col).performTouchInput {
      down(center)
      repeat(steps) { moveBy(Offset(stepPx, 0f)) }
      up()
    }
    composeTestRule.waitForIdle()
  }

  private fun innerCanvasPx(): Float = with(composeTestRule.density) { innerCanvasSize.toPx() }

  @Test
  fun constrainedCornerIsNotDraggable() {
    val state = GradientEditorState().apply { constrainEdgePoints = true }
    var dragCount = 0
    setCanvas(state) { _, _, _ -> dragCount++ }

    dragVertex(row = 0, col = 0, steps = 5, stepPx = 10f)

    assertEquals(0, dragCount)
    assertEquals(emptyList<VertexIndex>(), clickedVertices)
  }

  @Test
  fun draggedVertexFollowsThePointerIncludingTouchSlopWithoutClickingIt() {
    val state = GradientEditorState().apply { constrainEdgePoints = false }
    val start = state.meshPoints[1][1].position
    setCanvas(state)

    dragVertex(row = 1, col = 1, steps = 6, stepPx = 10f)

    assertEquals(start.x + 60f / innerCanvasPx(), state.meshPoints[1][1].position.x, 1e-3f)
    assertEquals(start.y, state.meshPoints[1][1].position.y, 1e-3f)
    assertEquals(emptyList<VertexIndex>(), clickedVertices)
  }

  @Test
  fun tapClicksTheVertexWithoutMovingIt() {
    val state = GradientEditorState().apply { constrainEdgePoints = false }
    val before = state.meshPoints.toList()
    setCanvas(state)

    vertexNode(row = 1, col = 2).performTouchInput {
      down(center)
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(listOf(VertexIndex(1, 2)), clickedVertices)
    assertEquals(before, state.meshPoints)
  }

  @Test
  fun pinnedCornerClicksButDoesNotBlockDraggingAnOverlappingVertex() {
    val red = Color(0xFFF44336)
    val grid =
      List(3) { row ->
        List(3) { col ->
          // The center vertex overlaps the top left corner handle.
          val position = if (row == 1 && col == 1) Offset(0.03f, 0.03f) else Offset(col / 2f, row / 2f)
          MeshGradientPoint(position, red)
        }
      }
    val state = GradientEditorState().apply { loadMesh(grid) }
    state.constrainEdgePoints = true
    setCanvas(state)

    vertexNode(row = 0, col = 0).performTouchInput {
      down(center)
      up()
    }
    composeTestRule.waitForIdle()
    assertEquals(listOf(VertexIndex(0, 0)), clickedVertices)

    dragVertex(row = 0, col = 0, steps = 6, stepPx = 10f)

    assertEquals(Offset.Zero, state.meshPoints[0][0].position)
    assertEquals(0.03f + 60f / innerCanvasPx(), state.meshPoints[1][1].position.x, 1e-3f)
    assertEquals(listOf(VertexIndex(0, 0)), clickedVertices)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun secondaryMouseButtonNeitherClicksNorDragsVertices() {
    val state = GradientEditorState().apply { constrainEdgePoints = false }
    val before = state.meshPoints.toList()
    setCanvas(state)

    vertexNode(row = 1, col = 1).performMouseInput {
      moveTo(center)
      press(MouseButton.Secondary)
      repeat(6) { moveBy(Offset(10f, 0f)) }
      release(MouseButton.Secondary)
    }
    vertexNode(row = 1, col = 2).performMouseInput {
      moveTo(center)
      press(MouseButton.Secondary)
      release(MouseButton.Secondary)
    }
    composeTestRule.waitForIdle()

    assertEquals(before, state.meshPoints)
    assertEquals(emptyList<VertexIndex>(), clickedVertices)
  }

  @Test
  fun accessibilityClickSelectsTheVertex() {
    val state = GradientEditorState()
    setCanvas(state)

    vertexNode(row = 0, col = 0).performSemanticsAction(SemanticsActions.OnClick)

    assertEquals(listOf(VertexIndex(0, 0)), clickedVertices)
  }
}
