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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import com.android.tools.idea.compose.meshgradient.components.GRADIENT_HANDLE_SIZE
import com.android.tools.idea.compose.meshgradient.components.GradientHandleBox
import com.android.tools.idea.compose.meshgradient.components.toHexString
import com.android.tools.idea.compose.preview.message

// Adapted from the Mesh project: des/c5inco/mesh/common/PointCursor.kt

/** Diameter of a [PointCursor]. */
internal val POINT_CURSOR_SIZE = GRADIENT_HANDLE_SIZE

/** Normalized step moved by a single arrow key press on a focused movable [PointCursor]. */
internal const val VERTEX_KEYBOARD_STEP = 0.05f

/**
 * Handle for the mesh vertex at [row] and [col], filled with the vertex [color]. A vertex that can't be dragged ([movable] is false) is
 * drawn as a rounded square with a muted opaque ring while keeping full-contrast coordinate text.
 *
 * Pointer input is handled by the canvas that draws the handles, so [onSelect] and [onMove] are exposed through keyboard and accessibility
 * semantics without attaching pointer input listeners to the handle itself.
 */
@Composable
internal fun PointCursor(
  row: Int,
  col: Int,
  color: Color,
  modifier: Modifier = Modifier,
  position: Offset = Offset.Zero,
  movable: Boolean = true,
  onSelect: (() -> Unit)? = null,
  onMove: ((Offset) -> Unit)? = null,
) {
  // The visible label uses the 0-based indices passed to setVertex, while the description read by accessibility services is 1-based.
  val description = message("gradient.editor.mesh.vertex.description", row + 1, col + 1)
  val selectLabel = message("gradient.editor.mesh.vertex.edit.color")
  val stateText =
    remember(color, position, movable) {
      val colorText = color.toHexString()
      val xText = formatFloat(position.x)
      val yText = formatFloat(position.y)
      if (movable) {
        message("gradient.editor.mesh.vertex.state", colorText, xText, yText)
      } else {
        message("gradient.editor.mesh.vertex.pinned.state", colorText, xText, yText)
      }
    }
  GradientHandleBox(
    glyph = "$row,$col",
    color = color,
    pinned = !movable,
    modifier = modifier,
    onKeyEvent = { event ->
      if (event.type != KeyEventType.KeyDown) return@GradientHandleBox false
      when (event.key) {
        Key.Enter,
        Key.NumPadEnter,
        Key.Spacebar -> {
          if (onSelect != null) {
            onSelect()
            true
          } else {
            false
          }
        }
        Key.DirectionLeft -> moveVertex(position, Offset(-VERTEX_KEYBOARD_STEP, 0f), movable, onMove)
        Key.DirectionRight -> moveVertex(position, Offset(VERTEX_KEYBOARD_STEP, 0f), movable, onMove)
        Key.DirectionUp -> moveVertex(position, Offset(0f, -VERTEX_KEYBOARD_STEP), movable, onMove)
        Key.DirectionDown -> moveVertex(position, Offset(0f, VERTEX_KEYBOARD_STEP), movable, onMove)
        else -> false
      }
    },
    semantics = {
      contentDescription = description
      stateDescription = stateText
      if (onSelect != null) {
        role = Role.Button
        onClick(label = selectLabel) {
          onSelect()
          true
        }
      }
    },
  )
}

private fun moveVertex(position: Offset, delta: Offset, movable: Boolean, onMove: ((Offset) -> Unit)?): Boolean {
  if (!movable || onMove == null) return false
  val next = Offset((position.x + delta.x).coerceIn(0f, 1f), (position.y + delta.y).coerceIn(0f, 1f))
  onMove(next)
  return true
}
