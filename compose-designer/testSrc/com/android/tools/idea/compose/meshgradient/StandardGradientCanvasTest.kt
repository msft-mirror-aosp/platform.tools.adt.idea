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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.meshgradient.components.GRADIENT_HANDLE_SIZE
import com.android.tools.idea.compose.preview.message
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StandardGradientCanvasTest {
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(ApplicationRule()).around(composeTestRule)

  /** Padding around the preview in [StandardGradientCanvas]. */
  private val previewPadding = 16.dp

  private val initialStart = Offset(100f, 50f)

  private val state =
    GradientEditorState().apply {
      currentType = GradientType.LINEAR
      previewSize = Size(400f, 200f)
      start = initialStart
      end = Offset(300f, 150f)
    }

  /** Width of the canvas; the preview fills it, minus the padding, keeping the aspect ratio of [GradientEditorState.previewSize]. */
  private var canvasWidth by mutableStateOf(232.dp)

  /** Canvas pixels per brush pixel for the current [canvasWidth], when the preview is wider than tall. */
  private fun scale(state: GradientEditorState = this.state): Float =
    with(composeTestRule.density) { (canvasWidth - previewPadding * 2).toPx() } / state.previewSize.width

  private fun setCanvas(state: GradientEditorState = this.state) {
    composeTestRule.setContent { Box(Modifier.size(canvasWidth, canvasWidth)) { StandardGradientCanvas(state) } }
  }

  private fun startHandle() = composeTestRule.onNodeWithContentDescription(message("gradient.editor.canvas.start"))

  private fun moveStartHandle(steps: Int, stepPx: Float, down: Boolean = false, up: Boolean = false) {
    startHandle().performTouchInput {
      if (down) down(center)
      repeat(steps) { moveBy(Offset(stepPx, 0f)) }
      if (up) up()
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun draggingTheStartHandleMovesTheStartInBrushPixels() {
    setCanvas()
    val scale = scale()

    moveStartHandle(steps = 6, stepPx = 10f, down = true, up = true)

    assertEquals(draggedPoint(initialStart, initialStart, Offset(60f, 0f), scale), state.start)
    assertEquals(initialStart.x + 60f / scale, state.start.x, 1f)
    assertEquals("The vertical axis did not move and keeps its value", initialStart.y, state.start.y)
  }

  @Test
  fun draggingNextToAHandleMovesNothing() {
    setCanvas()
    val handleSize = with(composeTestRule.density) { GRADIENT_HANDLE_SIZE.toPx() }

    startHandle().performTouchInput {
      down(center + Offset(handleSize, 0f))
      repeat(6) { moveBy(Offset(10f, 0f)) }
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(initialStart, state.start)
    assertEquals(Offset(300f, 150f), state.end)
  }

  @Test
  fun partOfACornerHandleOutsideThePreviewCanBeDraggedWithTheMouse() {
    // The default linear gradient goes from the top left corner to the bottom right corner, so most of each handle is outside the preview.
    val defaultLinear = GradientEditorState().apply { currentType = GradientType.LINEAR }
    setCanvas(defaultLinear)
    val scale = scale(defaultLinear)
    val handleRadius = with(composeTestRule.density) { GRADIENT_HANDLE_SIZE.toPx() / 2 }

    startHandle().performMouseInput {
      moveTo(center - Offset(handleRadius / 2, handleRadius / 2))
      press()
      repeat(6) { moveBy(Offset(10f, 10f)) }
      release()
    }
    composeTestRule.waitForIdle()

    assertEquals(draggedPoint(Offset.Zero, Offset.Zero, Offset(60f, 60f), scale), defaultLinear.start)
  }

  @Test
  fun dragContinuesWithTheNewScaleWhenTheCanvasIsResized() {
    setCanvas()

    moveStartHandle(steps = 3, stepPx = 10f, down = true)
    canvasWidth = 332.dp
    composeTestRule.waitForIdle()
    val newScale = scale()
    moveStartHandle(steps = 3, stepPx = 10f, up = true)

    // The whole movement is applied with the latest scale, which a restarted gesture would not do.
    assertEquals(draggedPoint(initialStart, initialStart, Offset(60f, 0f), newScale), state.start)
  }
}
