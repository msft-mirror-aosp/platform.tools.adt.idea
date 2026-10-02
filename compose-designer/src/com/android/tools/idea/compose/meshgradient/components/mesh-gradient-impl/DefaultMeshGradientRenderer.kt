/*
 * Copyright 2026 The Android Open Source Project
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

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

// Taken from compose framework

/**
 * [BaseMeshGradientRenderer] that draws the tessellated triangle mesh straight from the reused vertex buffers through the Skia canvas
 * backing the Compose [Canvas]. Unlike the common [Canvas.drawVertices] API, this does not box every vertex nor allocate new `Vertices` for
 * every draw.
 */
internal class DefaultMeshGradientRenderer : BaseMeshGradientRenderer() {
  // Created lazily since Skia objects load the Skiko native library, which tessellation alone does not need.
  private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint() }

  override fun drawTriangles(canvas: Canvas, surfacePositions: FloatArray, surfaceColors: IntArray, indices: ShortArray) {
    canvas.skiaCanvas.drawVertices(
      vertexMode = VertexMode.TRIANGLES,
      positions = surfacePositions,
      colors = surfaceColors,
      texCoords = null,
      indices = indices,
      // Use the vertex colors as they are, ignoring the paint color.
      blendMode = BlendMode.DST,
      paint = paint,
    )
  }
}
