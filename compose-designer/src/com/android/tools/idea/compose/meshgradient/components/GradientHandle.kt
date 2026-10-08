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
package com.android.tools.idea.compose.meshgradient.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text

/** Diameter of a [GradientHandle]. */
internal val GRADIENT_HANDLE_SIZE = 24.dp

/**
 * A circular handle drawn over a gradient preview, filled with [color] and showing a short [glyph].
 *
 * @param glyph short text drawn inside the handle (e.g. a letter or an index). It is purely visual: assistive technologies read
 *   [contentDescription] instead.
 * @param contentDescription accessible description of the point the handle controls.
 */
@Composable
internal fun GradientHandle(glyph: String, contentDescription: String, color: Color, modifier: Modifier = Modifier) {
  val textColor = if (color.luminance() > 0.5f) Color.Black else Color.White
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .size(GRADIENT_HANDLE_SIZE)
        .clearAndSetSemantics { this.contentDescription = contentDescription }
        .drawWithContent {
          drawCircle(color = color)
          drawCircle(color = Color.White, style = Stroke(width = 4.dp.toPx()))
          drawContent()
        },
  ) {
    Text(glyph, color = textColor)
  }
}

/**
 * Lets the user drag the handles drawn over this element. A gesture starting on the handle returned by [findHandle] for the pointer
 * position (local to this element) reports the total pointer movement since the pointer went down, rather than per-event deltas. Applying
 * the total movement to the state captured in [onDragStart] keeps the dragged handle under the pointer without accumulating drift.
 *
 * The gesture is detected by this element rather than by the handles: a handle following the pointer would see the same local position on
 * consecutive events, and Compose does not deliver move events whose position did not change, so the last movement could be lost. The
 * movement is measured in root coordinates, so it stays correct if this element is laid out again during the drag. Mouse gestures only
 * start with the primary button.
 *
 * The gesture is not restarted when the callbacks change, so an ongoing drag survives recompositions (e.g. a resize of the canvas); the
 * latest callbacks are always used.
 */
internal fun <T : Any> Modifier.handleDrag(
  findHandle: (position: Offset) -> T?,
  onDragStart: (handle: T) -> Unit,
  onDrag: (handle: T, totalDelta: Offset) -> Unit,
): Modifier = this then HandleDragElement(findHandle, onDragStart, onDrag)

private data class HandleDragElement<T : Any>(
  val findHandle: (Offset) -> T?,
  val onDragStart: (T) -> Unit,
  val onDrag: (T, Offset) -> Unit,
) : ModifierNodeElement<HandleDragNode<T>>() {
  override fun create() = HandleDragNode(findHandle, onDragStart, onDrag)

  override fun update(node: HandleDragNode<T>) {
    node.findHandle = findHandle
    node.onDragStart = onDragStart
    node.onDrag = onDrag
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "handleDrag"
  }
}

private class HandleDragNode<T : Any>(
  var findHandle: (Offset) -> T?,
  var onDragStart: (T) -> Unit,
  var onDrag: (T, Offset) -> Unit,
) : DelegatingNode(), PointerInputModifierNode {
  private val pointerInputNode = delegate(SuspendingPointerInputModifierNode { detectHandleDrag() })

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
    pointerInputNode.onPointerEvent(pointerEvent, pass, bounds)
  }

  override fun onCancelPointerInput() {
    pointerInputNode.onCancelPointerInput()
  }

  override fun onDensityChange() {
    pointerInputNode.onDensityChange()
  }

  override fun onViewConfigurationChange() {
    pointerInputNode.onViewConfigurationChange()
  }

  private suspend fun PointerInputScope.detectHandleDrag() {
    awaitEachGesture {
      val down = awaitFirstDown()
      if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
      val handle = findHandle(down.position) ?: return@awaitEachGesture
      val downInRoot = toRoot(down.position)
      val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
      // The slop detector follows another pointer if the first one is lifted; dragging with it would make the handle jump.
      if (dragStart.id != down.id) return@awaitEachGesture
      onDragStart(handle)
      onDrag(handle, toRoot(dragStart.position) - downInRoot)
      drag(dragStart.id) { change ->
        change.consume()
        onDrag(handle, toRoot(change.position) - downInRoot)
      }
    }
  }

  private fun toRoot(localPosition: Offset): Offset = requireLayoutCoordinates().localToRoot(localPosition)
}
