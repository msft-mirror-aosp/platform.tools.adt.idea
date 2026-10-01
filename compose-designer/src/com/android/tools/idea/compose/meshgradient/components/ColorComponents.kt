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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

@Composable
fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.clip(RoundedCornerShape(4.dp)).size(16.dp)) {
    if (color == Color.Transparent) {
      Spacer(
        Modifier.drawBehind {
            drawIntoCanvas {
              drawPath(
                path =
                  Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height)
                    close()
                  },
                color = Color.Red,
                style = Stroke(width = 2f),
              )
            }
          }
          .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
          .fillMaxSize()
      )
    } else Spacer(Modifier.fillMaxSize().background(color))
  }
}
