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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.preview.message
import org.jetbrains.jewel.ui.component.Text

// Adapted from the Mesh project: des/c5inco/mesh/common/PointCursor.kt

/** Diameter of a [PointCursor]. */
internal val POINT_CURSOR_SIZE = 20.dp
private val CURSOR_BORDER_WIDTH = 4.dp
private const val PINNED_ALPHA = 0.6f

/**
 * Handle for the mesh vertex at [row] and [col], filled with the vertex [color]. A vertex that can't be dragged ([movable] is false) is
 * drawn with a dimmed border and label.
 *
 * Pointer input is handled by the canvas that draws the handles, so [onSelect] is only exposed to accessibility services.
 */
@Composable
internal fun PointCursor(
  row: Int,
  col: Int,
  color: Color,
  modifier: Modifier = Modifier,
  movable: Boolean = true,
  onSelect: (() -> Unit)? = null,
) {
  // The visible label uses the 0-based indices passed to setVertex, while the description read by accessibility services is 1-based.
  val description = message("gradient.editor.mesh.vertex.description", row + 1, col + 1)
  val selectLabel = message("gradient.editor.mesh.vertex.edit.color")
  val contrastColor = if (color.luminance() > 0.5f) Color.Black else Color.White
  val labelColor = if (movable) contrastColor else contrastColor.copy(alpha = PINNED_ALPHA)
  val borderColor = if (movable) Color.White else Color.White.copy(alpha = PINNED_ALPHA)
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .clearAndSetSemantics {
          contentDescription = description
          if (onSelect != null) {
            role = Role.Button
            onClick(label = selectLabel) {
              onSelect()
              true
            }
          }
        }
        .size(POINT_CURSOR_SIZE)
        .drawWithCache {
          val strokeWidth = CURSOR_BORDER_WIDTH.toPx()
          val stroke = Stroke(width = strokeWidth)
          val radius = size.minDimension / 2f
          onDrawWithContent {
            drawCircle(color = color, radius = radius)
            // Inset the border so that it is drawn within the cursor bounds instead of overflowing them by half its width.
            drawCircle(color = borderColor, radius = radius - strokeWidth / 2f, style = stroke)
            drawContent()
          }
        },
  ) {
    Text("$row,$col", color = labelColor)
  }
}
