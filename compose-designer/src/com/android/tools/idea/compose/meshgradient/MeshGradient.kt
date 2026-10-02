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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.android.tools.idea.compose.meshgradient.impl.MeshGradientPainter

/**
 * Draws a mesh gradient of [rows] x [columns] patches whose vertices are given by [points], indexed by row and then by column, with
 * positions normalized to the composable's size.
 */
@Composable
internal fun MeshGradient(
  rows: Int,
  columns: Int,
  points: List<List<MeshGradientPoint>>,
  modifier: Modifier = Modifier,
  hasBicubicColor: Boolean = false,
  content: @Composable () -> Unit = {},
) {
  val gradientPainter =
    remember(rows, columns, hasBicubicColor, points) {
      MeshGradientPainter(rows, columns, hasBicubicColor) {
        for (r in 0..rows) {
          for (c in 0..columns) {
            if (r < points.size && c < points[r].size) {
              setVertex(
                r,
                c,
                points[r][c].position,
                points[r][c].color,
                points[r][c].leftBezierOffset,
                points[r][c].topBezierOffset,
                points[r][c].rightBezierOffset,
                points[r][c].bottomBezierOffset,
              )
            }
          }
        }
      }
    }

  Box(modifier = modifier.paint(gradientPainter)) { content() }
}

data class MeshGradientPoint(
  val position: Offset,
  val color: Color,
  val leftBezierOffset: Offset = Offset.Unspecified,
  val topBezierOffset: Offset = Offset.Unspecified,
  val rightBezierOffset: Offset = Offset.Unspecified,
  val bottomBezierOffset: Offset = Offset.Unspecified,
  val positionExpression: String? = null,
  val colorExpression: String? = null,
)
