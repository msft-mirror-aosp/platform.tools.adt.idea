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

package com.android.tools.idea.compose.meshgradient.impl

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.util.lerp
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

// Forked from androidx-main (commit 080d2b3e532):
// compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/BaseMeshGradientRenderer.kt,
// DefaultMeshGradientRenderer.kt and MeshGradientRenderer.kt.
// Divergences from upstream:
// - A single renderer class replaces the MeshGradientRenderer interface, the BaseMeshGradientRenderer backend base class (and its
//   Android pre-Q colors buffer hook) and DefaultMeshGradientRenderer.
// - Triangles are drawn straight from the reused primitive buffers through the Skia canvas, instead of boxing every vertex into a new
//   Compose Vertices object, and all patches share one buffer and draw call as far as 16-bit indices allow, instead of one per patch.
// - A check guards that every vertex of a patch is addressable by the 16-bit indices.
// - The vertical pass of the bicubic color interpolation clamps to the Oklab ranges, where upstream can throw from Color().
// - Bilinear colors are interpolated in Oklab floats, instead of with nested lerp(Color, Color) that quantizes to 8-bit sRGB.

/** Number of vertices that a single draw call can address with its 16-bit (signed) index buffer. */
private const val MaxVerticesPerDraw = Short.MAX_VALUE + 1

private val OklabMinL = ColorSpaces.Oklab.getMinValue(0)
private val OklabMaxL = ColorSpaces.Oklab.getMaxValue(0)
private val OklabMinA = ColorSpaces.Oklab.getMinValue(1)
private val OklabMaxA = ColorSpaces.Oklab.getMaxValue(1)
private val OklabMinB = ColorSpaces.Oklab.getMinValue(2)
private val OklabMaxB = ColorSpaces.Oklab.getMaxValue(2)

/** Valid range of the L, a, b and alpha channels of an Oklab color, indexed by channel. */
private val OklabChannelMinValues = floatArrayOf(OklabMinL, OklabMinA, OklabMinB, 0f)
private val OklabChannelMaxValues = floatArrayOf(OklabMaxL, OklabMaxA, OklabMaxB, 1f)

/**
 * A renderer responsible for tessellating and drawing a 2D mesh gradient.
 *
 * A mesh gradient is defined by a grid of vertices, where each vertex has a position, color, and four optional Bezier control points
 * (tangents) that define the curvature of the edges connecting neighboring vertices. Colors can be interpolated using either bilinear or
 * bicubic interpolation.
 *
 * Each patch is tessellated into a triangle mesh (Bezier surface evaluation, Catmull-Rom / bilinear color interpolation and adaptive
 * subdivision), which is drawn through the Skia canvas backing the Compose canvas. The renderer is stateful: it keeps its vertex, color and
 * index buffers across frames, only reallocating them when the tessellation level or the number of patches per draw call changes, and draws
 * all patches in as few calls as the 16-bit index buffer allows (a single one unless the mesh is both large and finely subdivided).
 *
 * @see MeshGradientConfig
 */
internal class MeshGradientRenderer {

  // Created lazily since Skia objects load the Skiko native library, which tessellation alone does not need.
  private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint() }

  private var lastSubdivisionU: Int = -1
  private var lastSubdivisionV: Int = -1
  private var lastPatchesPerBatch: Int = -1

  private var indexBuffer = ShortArray(0)
  private var partialIndexBuffer = ShortArray(0)

  private var vBernsteinBasis = FloatArray(0)
  private var vCatmullRomBasis = FloatArray(0)
  private var forwardDifferenceRowResultsX = FloatArray(0)
  private var forwardDifferenceRowResultsY = FloatArray(0)
  private var colorForwardDifferenceRowResults = FloatArray(0)

  private var positionsBuffer = FloatArray(0)
  private var colorsBuffer = IntArray(0)

  private val patchPositions = FloatArray(8)
  private val patchLeftBezierOffsets = FloatArray(8)
  private val patchRightBezierOffsets = FloatArray(8)
  private val patchTopBezierOffsets = FloatArray(8)
  private val patchBottomBezierOffsets = FloatArray(8)
  private val patchColors = IntArray(16)
  private val okLabPatchColors = FloatArray(64)
  private val controlPoints = FloatArray(32)

  /** Renders the mesh gradient defined by [config] onto this [DrawScope]. */
  fun DrawScope.draw(config: MeshGradientConfig) {
    if (size.isEmpty()) return
    val canvas = drawContext.canvas.skiaCanvas
    tessellate(config, size) { positions, colors, indices ->
      canvas.drawVertices(
        vertexMode = VertexMode.TRIANGLES,
        positions = positions,
        colors = colors,
        texCoords = null,
        indices = indices,
        // Use the vertex colors as they are, ignoring the paint color.
        blendMode = BlendMode.DST,
        paint = paint,
      )
    }
  }

  /**
   * Tessellates the mesh described by [config] for a drawing area of [size] and hands the resulting triangle batches to [drawBatch].
   *
   * Each batch consists of the flattened (x, y) vertex positions, the per-vertex ARGB colors (one per position pair) and the triangle
   * indices into them. The arrays are owned by this renderer and are overwritten by the next batch, so [drawBatch] must not retain them.
   * Vertices of a batch that are not referenced by its indices are leftovers from a previous batch.
   *
   * The patches are spread evenly over the fewest batches that 16-bit indices allow, so that a trailing batch with fewer patches does not
   * push a mostly stale vertex buffer to the native draw call.
   */
  @VisibleForTesting
  fun tessellate(
    config: MeshGradientConfig,
    size: Size,
    drawBatch: (positions: FloatArray, colors: IntArray, indices: ShortArray) -> Unit,
  ) {
    val (subdivisionsU, subdivisionsV) = calculateMeshGradientSubdivisions(config.rows, config.columns, config.positions, size)
    val patchCount = config.rows * config.columns
    val verticesPerPatch = subdivisionsU * subdivisionsV
    check(verticesPerPatch <= MaxVerticesPerDraw) { "A patch of $verticesPerPatch vertices cannot be drawn with 16-bit indices" }
    val maxPatchesPerBatch = MaxVerticesPerDraw / verticesPerPatch
    val batchCount = (patchCount + maxPatchesPerBatch - 1) / maxPatchesPerBatch
    val patchesPerBatch = (patchCount + batchCount - 1) / batchCount
    ensureBuffers(subdivisionsU, subdivisionsV, patchesPerBatch)

    var patchesInBatch = 0
    for (patchIdx in 0 until patchCount) {
      tessellatePatch(config, patchIdx, size, subdivisionsU, subdivisionsV, vertexOffset = patchesInBatch * verticesPerPatch)
      patchesInBatch++
      if (patchesInBatch == patchesPerBatch || patchIdx == patchCount - 1) {
        drawBatch(positionsBuffer, colorsBuffer, indicesForBatch(patchesInBatch, patchesPerBatch))
        patchesInBatch = 0
      }
    }
  }

  /**
   * (Re)allocates the vertex, index and scratch buffers when the tessellation level of a patch or the number of patches per batch changes,
   * and keeps them otherwise.
   */
  private fun ensureBuffers(subdivisionsU: Int, subdivisionsV: Int, patchesPerBatch: Int) {
    if (lastSubdivisionU == subdivisionsU && lastSubdivisionV == subdivisionsV && lastPatchesPerBatch == patchesPerBatch) return
    val vertexCount = subdivisionsU * subdivisionsV * patchesPerBatch
    forwardDifferenceRowResultsX = FloatArray(4 * subdivisionsU)
    forwardDifferenceRowResultsY = FloatArray(4 * subdivisionsU)
    colorForwardDifferenceRowResults = FloatArray(4 * subdivisionsU * 4)
    positionsBuffer = FloatArray(vertexCount * 2)
    colorsBuffer = IntArray(vertexCount)
    indexBuffer = buildIndexBuffer(subdivisionsU, subdivisionsV, patchesPerBatch)
    partialIndexBuffer = ShortArray(0)
    precomputeBasisArrays(subdivisionsV)
    lastSubdivisionU = subdivisionsU
    lastSubdivisionV = subdivisionsV
    lastPatchesPerBatch = patchesPerBatch
  }

  /**
   * Returns the index buffer for a batch of [patchesInBatch] patches. Only the last batch of a frame can hold fewer than [patchesPerBatch]
   * patches; since the native draw call uses the whole array, that batch gets an exactly sized prefix of [indexBuffer].
   */
  private fun indicesForBatch(patchesInBatch: Int, patchesPerBatch: Int): ShortArray {
    if (patchesInBatch == patchesPerBatch) return indexBuffer
    val indexCount = indexBuffer.size / patchesPerBatch * patchesInBatch
    if (partialIndexBuffer.size != indexCount) {
      partialIndexBuffer = indexBuffer.copyOf(indexCount)
    }
    return partialIndexBuffer
  }

  private fun tessellatePatch(
    config: MeshGradientConfig,
    patchIdx: Int,
    size: Size,
    subdivisionsU: Int,
    subdivisionsV: Int,
    vertexOffset: Int,
  ) {
    val columns = config.columns
    readPatchPositions(patchIdx, columns, config.positions, size, patchPositions)
    readPatchPositions(patchIdx, columns, config.leftBezierOffsets, size, patchLeftBezierOffsets)
    readPatchPositions(patchIdx, columns, config.rightBezierOffsets, size, patchRightBezierOffsets)
    readPatchPositions(patchIdx, columns, config.topBezierOffsets, size, patchTopBezierOffsets)
    readPatchPositions(patchIdx, columns, config.bottomBezierOffsets, size, patchBottomBezierOffsets)
    readPatchColors(patchIdx, config.rows, columns, config.colors, patchColors)

    buildControlPointMatrix(
      patchPositions,
      patchLeftBezierOffsets,
      patchRightBezierOffsets,
      patchTopBezierOffsets,
      patchBottomBezierOffsets,
      controlPoints,
    )
    computeBezierSurfacePoints(controlPoints, subdivisionsU, subdivisionsV, positionsBuffer, vertexOffset)

    if (config.hasBicubicColor) {
      computeCatmullRomSurfaceColors(patchColors, subdivisionsU, subdivisionsV, colorsBuffer, vertexOffset)
    } else {
      computeBilinearSurfaceColors(patchColors, subdivisionsU, subdivisionsV, colorsBuffer, vertexOffset)
    }
  }

  private fun buildControlPointMatrix(
    patchPositions: FloatArray,
    leftBezierOffsets: FloatArray,
    rightBezierOffsets: FloatArray,
    topBezierOffsets: FloatArray,
    bottomBezierOffsets: FloatArray,
    out: FloatArray,
  ) {
    // Helper to map 2D (row, col) to 1D index in the 4x4x2 controlPoints array
    fun idx(row: Int, col: Int, component: Int): Int = (row * 4 + col) * 2 + component

    // Corners
    out[idx(0, 0, 0)] = patchPositions[0]
    out[idx(0, 0, 1)] = patchPositions[1]
    out[idx(0, 3, 0)] = patchPositions[2]
    out[idx(0, 3, 1)] = patchPositions[3]
    out[idx(3, 0, 0)] = patchPositions[4]
    out[idx(3, 0, 1)] = patchPositions[5]
    out[idx(3, 3, 0)] = patchPositions[6]
    out[idx(3, 3, 1)] = patchPositions[7]

    // Horizontal Bezier Offsets
    out[idx(0, 1, 0)] = out[idx(0, 0, 0)] + rightBezierOffsets[0]
    out[idx(0, 1, 1)] = out[idx(0, 0, 1)] + rightBezierOffsets[1]
    out[idx(0, 2, 0)] = out[idx(0, 3, 0)] + leftBezierOffsets[2]
    out[idx(0, 2, 1)] = out[idx(0, 3, 1)] + leftBezierOffsets[3]
    out[idx(3, 1, 0)] = out[idx(3, 0, 0)] + rightBezierOffsets[4]
    out[idx(3, 1, 1)] = out[idx(3, 0, 1)] + rightBezierOffsets[5]
    out[idx(3, 2, 0)] = out[idx(3, 3, 0)] + leftBezierOffsets[6]
    out[idx(3, 2, 1)] = out[idx(3, 3, 1)] + leftBezierOffsets[7]

    // Vertical Bezier Offsets
    out[idx(1, 0, 0)] = out[idx(0, 0, 0)] + bottomBezierOffsets[0]
    out[idx(1, 0, 1)] = out[idx(0, 0, 1)] + bottomBezierOffsets[1]
    out[idx(2, 0, 0)] = out[idx(3, 0, 0)] + topBezierOffsets[4]
    out[idx(2, 0, 1)] = out[idx(3, 0, 1)] + topBezierOffsets[5]
    out[idx(1, 3, 0)] = out[idx(0, 3, 0)] + bottomBezierOffsets[2]
    out[idx(1, 3, 1)] = out[idx(0, 3, 1)] + bottomBezierOffsets[3]
    out[idx(2, 3, 0)] = out[idx(3, 3, 0)] + topBezierOffsets[6]
    out[idx(2, 3, 1)] = out[idx(3, 3, 1)] + topBezierOffsets[7]

    // Interior points with zero twist vectors
    out[idx(1, 1, 0)] = out[idx(0, 1, 0)] + out[idx(1, 0, 0)] - out[idx(0, 0, 0)]
    out[idx(1, 1, 1)] = out[idx(0, 1, 1)] + out[idx(1, 0, 1)] - out[idx(0, 0, 1)]
    out[idx(1, 2, 0)] = out[idx(0, 2, 0)] + out[idx(1, 3, 0)] - out[idx(0, 3, 0)]
    out[idx(1, 2, 1)] = out[idx(0, 2, 1)] + out[idx(1, 3, 1)] - out[idx(0, 3, 1)]
    out[idx(2, 1, 0)] = out[idx(2, 0, 0)] + out[idx(3, 1, 0)] - out[idx(3, 0, 0)]
    out[idx(2, 1, 1)] = out[idx(2, 0, 1)] + out[idx(3, 1, 1)] - out[idx(3, 0, 1)]
    out[idx(2, 2, 0)] = out[idx(2, 3, 0)] + out[idx(3, 2, 0)] - out[idx(3, 3, 0)]
    out[idx(2, 2, 1)] = out[idx(2, 3, 1)] + out[idx(3, 2, 1)] - out[idx(3, 3, 1)]
  }

  /**
   * Computes the vertex positions for a bicubic Bezier surface patch.
   *
   * This implementation uses the forward differencing technique to efficiently evaluate the cubic polynomials.
   *
   * @param controlPoints The 4x4 grid of control points (32 floats: x, y for each).
   * @param subdivisionsU The number of horizontal subdivisions.
   * @param subdivisionsV The number of vertical subdivisions.
   * @param outPositions The output array that receives the flattened (x, y) position of each vertex.
   * @param vertexOffset The index of the first vertex of this patch in [outPositions].
   */
  private fun computeBezierSurfacePoints(
    controlPoints: FloatArray,
    subdivisionsU: Int,
    subdivisionsV: Int,
    outPositions: FloatArray,
    vertexOffset: Int,
  ) {
    val forwardDiffX = forwardDifferenceRowResultsX
    val forwardDiffY = forwardDifferenceRowResultsY
    val stepSize = 1f / (subdivisionsU - 1).toFloat()
    val stepSize2 = stepSize * stepSize
    val stepSize3 = stepSize2 * stepSize

    for (row in 0 until 4) {
      val base = row * 8

      val cubicTermX =
        (-controlPoints[base] + 3f * controlPoints[base + 2] - 3f * controlPoints[base + 4] + controlPoints[base + 6]) * stepSize3
      val quadraticTermX = (3f * controlPoints[base] - 6f * controlPoints[base + 2] + 3f * controlPoints[base + 4]) * stepSize2

      var forwardDiff1x = cubicTermX + quadraticTermX + (-3f * controlPoints[base] + 3f * controlPoints[base + 2]) * stepSize
      var forwardDiff2x = 6f * cubicTermX + 2f * quadraticTermX
      val forwardDiff3x = 6f * cubicTermX

      val cubicTermY =
        (-controlPoints[base + 1] + 3f * controlPoints[base + 3] - 3f * controlPoints[base + 5] + controlPoints[base + 7]) * stepSize3
      val quadraticTermY = (3f * controlPoints[base + 1] - 6f * controlPoints[base + 3] + 3f * controlPoints[base + 5]) * stepSize2

      var forwardDiff1y = cubicTermY + quadraticTermY + (-3f * controlPoints[base + 1] + 3f * controlPoints[base + 3]) * stepSize
      var forwardDiff2y = 6f * cubicTermY + 2f * quadraticTermY
      val forwardDiff3y = 6f * cubicTermY

      var currentX = controlPoints[base]
      var currentY = controlPoints[base + 1]
      val rowOffset = row * subdivisionsU
      forwardDiffX[rowOffset] = currentX
      forwardDiffY[rowOffset] = currentY

      for (uIndex in 1 until subdivisionsU) {
        currentX += forwardDiff1x
        forwardDiff1x += forwardDiff2x
        forwardDiff2x += forwardDiff3x
        currentY += forwardDiff1y
        forwardDiff1y += forwardDiff2y
        forwardDiff2y += forwardDiff3y
        forwardDiffX[rowOffset + uIndex] = currentX
        forwardDiffY[rowOffset + uIndex] = currentY
      }
    }

    val bernsteinBasis = vBernsteinBasis
    for (vIndex in 0 until subdivisionsV) {
      val vBase = vIndex * 4

      for (uIndex in 0 until subdivisionsU) {
        val outIdx = (vertexOffset + uIndex * subdivisionsV + vIndex) * 2
        outPositions[outIdx] =
          bernsteinBasis[vBase] * forwardDiffX[uIndex] +
            bernsteinBasis[vBase + 1] * forwardDiffX[subdivisionsU + uIndex] +
            bernsteinBasis[vBase + 2] * forwardDiffX[2 * subdivisionsU + uIndex] +
            bernsteinBasis[vBase + 3] * forwardDiffX[3 * subdivisionsU + uIndex]
        outPositions[outIdx + 1] =
          bernsteinBasis[vBase] * forwardDiffY[uIndex] +
            bernsteinBasis[vBase + 1] * forwardDiffY[subdivisionsU + uIndex] +
            bernsteinBasis[vBase + 2] * forwardDiffY[2 * subdivisionsU + uIndex] +
            bernsteinBasis[vBase + 3] * forwardDiffY[3 * subdivisionsU + uIndex]
      }
    }
  }

  /**
   * Computes the colors for a patch using bicubic Catmull-Rom interpolation. This is used when hasBicubicColor is true to provide smoother
   * color transitions.
   *
   * This implementation uses the forward differencing algorithm to efficiently evaluate the Catmull-Rom spline across the surface
   * subdivisions.
   *
   * @param patchColors The 4x4 grid of ARGB colors surrounding and including the patch.
   * @param subdivisionsU The number of horizontal subdivisions.
   * @param subdivisionsV The number of vertical subdivisions.
   * @param outColors The output array that receives the interpolated ARGB color of each vertex.
   * @param vertexOffset The index of the first vertex of this patch in [outColors].
   */
  private fun computeCatmullRomSurfaceColors(
    patchColors: IntArray,
    subdivisionsU: Int,
    subdivisionsV: Int,
    outColors: IntArray,
    vertexOffset: Int,
  ) {
    for (i in 0 until 16) {
      writeOklab(patchColors[i], okLabPatchColors, i * 4)
    }

    val forwardDiffColor = colorForwardDifferenceRowResults
    val stepSize = 1f / (subdivisionsU - 1).toFloat()
    val stepSize2 = stepSize * stepSize
    val stepSize3 = stepSize2 * stepSize

    for (row in 0 until 4) {
      val rowBase = row * 16
      for (channel in 0 until 4) {
        val cubicTerm =
          0.5f *
            (-okLabPatchColors[rowBase + channel] + 3f * okLabPatchColors[rowBase + 4 + channel] -
              3f * okLabPatchColors[rowBase + 8 + channel] + okLabPatchColors[rowBase + 12 + channel]) *
            stepSize3
        val quadraticTerm =
          0.5f *
            (2f * okLabPatchColors[rowBase + channel] - 5f * okLabPatchColors[rowBase + 4 + channel] +
              4f * okLabPatchColors[rowBase + 8 + channel] - okLabPatchColors[rowBase + 12 + channel]) *
            stepSize2

        var forwardDiff1Color =
          cubicTerm + quadraticTerm + 0.5f * (-okLabPatchColors[rowBase + channel] + okLabPatchColors[rowBase + 8 + channel]) * stepSize
        var forwardDiff2Color = 6f * cubicTerm + 2f * quadraticTerm
        val forwardDiff3Color = 6f * cubicTerm

        var currentColorValue = okLabPatchColors[rowBase + 4 + channel]
        val rowOffset = row * subdivisionsU * 4

        val minValue = OklabChannelMinValues[channel]
        val maxValue = OklabChannelMaxValues[channel]

        forwardDiffColor[rowOffset + channel] = currentColorValue.coerceIn(minValue, maxValue)

        for (uIndex in 1 until subdivisionsU) {
          currentColorValue += forwardDiff1Color
          forwardDiff1Color += forwardDiff2Color
          forwardDiff2Color += forwardDiff3Color
          forwardDiffColor[rowOffset + uIndex * 4 + channel] = currentColorValue.coerceIn(minValue, maxValue)
        }
      }
    }

    val catmullRomBasis = vCatmullRomBasis
    for (uIndex in 0 until subdivisionsU) {
      val uBase0 = uIndex * 4
      val uBase1 = subdivisionsU * 4 + uIndex * 4
      val uBase2 = 2 * subdivisionsU * 4 + uIndex * 4
      val uBase3 = 3 * subdivisionsU * 4 + uIndex * 4

      for (vIndex in 0 until subdivisionsV) {
        val vBasisOffset = vIndex * 4

        val l =
          (catmullRomBasis[vBasisOffset] * forwardDiffColor[uBase0] +
            catmullRomBasis[vBasisOffset + 1] * forwardDiffColor[uBase1] +
            catmullRomBasis[vBasisOffset + 2] * forwardDiffColor[uBase2] +
            catmullRomBasis[vBasisOffset + 3] * forwardDiffColor[uBase3])
        val a =
          (catmullRomBasis[vBasisOffset] * forwardDiffColor[uBase0 + 1] +
            catmullRomBasis[vBasisOffset + 1] * forwardDiffColor[uBase1 + 1] +
            catmullRomBasis[vBasisOffset + 2] * forwardDiffColor[uBase2 + 1] +
            catmullRomBasis[vBasisOffset + 3] * forwardDiffColor[uBase3 + 1])
        val b =
          (catmullRomBasis[vBasisOffset] * forwardDiffColor[uBase0 + 2] +
            catmullRomBasis[vBasisOffset + 1] * forwardDiffColor[uBase1 + 2] +
            catmullRomBasis[vBasisOffset + 2] * forwardDiffColor[uBase2 + 2] +
            catmullRomBasis[vBasisOffset + 3] * forwardDiffColor[uBase3 + 2])
        val alpha =
          (catmullRomBasis[vBasisOffset] * forwardDiffColor[uBase0 + 3] +
            catmullRomBasis[vBasisOffset + 1] * forwardDiffColor[uBase1 + 3] +
            catmullRomBasis[vBasisOffset + 2] * forwardDiffColor[uBase2 + 3] +
            catmullRomBasis[vBasisOffset + 3] * forwardDiffColor[uBase3 + 3])

        outColors[vertexOffset + uIndex * subdivisionsV + vIndex] = oklabToArgb(l, a, b, alpha)
      }
    }
  }

  /**
   * Computes the colors for a patch using bilinear interpolation in Oklab. This is used when hasBicubicColor is false.
   *
   * The four corner colors are converted to Oklab once, and every vertex is interpolated in floating point and converted back to sRGB
   * exactly once, so intermediate results are not quantized to 8 bits.
   *
   * @param patchColors The 4x4 grid of ARGB colors surrounding and including the patch.
   * @param subdivisionsU The number of horizontal subdivisions.
   * @param subdivisionsV The number of vertical subdivisions.
   * @param outColors The output array that receives the interpolated ARGB color of each vertex.
   * @param vertexOffset The index of the first vertex of this patch in [outColors].
   */
  private fun computeBilinearSurfaceColors(
    patchColors: IntArray,
    subdivisionsU: Int,
    subdivisionsV: Int,
    outColors: IntArray,
    vertexOffset: Int,
  ) {
    val subdivisionsUMinus1 = (subdivisionsU - 1).toFloat()
    val subdivisionsVMinus1 = (subdivisionsV - 1).toFloat()

    fun colorIdx(row: Int, col: Int): Int = (row * 4 + col)

    // The corners of the current patch are at the center of the 4x4 color grid. Store them as top-left, top-right, bottom-left and
    // bottom-right Oklab colors in the first 16 entries of okLabPatchColors.
    val corners = okLabPatchColors
    writeOklab(patchColors[colorIdx(1, 1)], corners, 0)
    writeOklab(patchColors[colorIdx(1, 2)], corners, 4)
    writeOklab(patchColors[colorIdx(2, 1)], corners, 8)
    writeOklab(patchColors[colorIdx(2, 2)], corners, 12)

    for (uIndex in 0 until subdivisionsU) {
      val u = uIndex / subdivisionsUMinus1
      val topL = lerp(corners[0], corners[4], u)
      val topA = lerp(corners[1], corners[5], u)
      val topB = lerp(corners[2], corners[6], u)
      val topAlpha = lerp(corners[3], corners[7], u)
      val bottomL = lerp(corners[8], corners[12], u)
      val bottomA = lerp(corners[9], corners[13], u)
      val bottomB = lerp(corners[10], corners[14], u)
      val bottomAlpha = lerp(corners[11], corners[15], u)
      for (vIndex in 0 until subdivisionsV) {
        val v = vIndex / subdivisionsVMinus1
        outColors[vertexOffset + uIndex * subdivisionsV + vIndex] =
          oklabToArgb(lerp(topL, bottomL, v), lerp(topA, bottomA, v), lerp(topB, bottomB, v), lerp(topAlpha, bottomAlpha, v))
      }
    }
  }

  /**
   * Extracts the four corner positions of a specific patch from the global [inArray] and scales them by the provided [size].
   *
   * @param patchIdx The index of the patch to read.
   * @param columns The number of columns in the mesh.
   * @param inArray The source array containing normalized (0-1) vertex positions.
   * @param size The dimensions to scale the normalized positions by.
   * @param out The output FloatArray to store the 8 coordinates (4 * 2).
   */
  private fun readPatchPositions(patchIdx: Int, columns: Int, inArray: FloatArray, size: Size, out: FloatArray) {
    val patchRow = patchIdx / columns
    val patchColumn = patchIdx % columns
    val topLeft = meshGradientPointIndex(patchRow, patchColumn, columns) * 2
    val topRight = meshGradientPointIndex(patchRow, patchColumn + 1, columns) * 2
    val bottomLeft = meshGradientPointIndex(patchRow + 1, patchColumn, columns) * 2
    val bottomRight = meshGradientPointIndex(patchRow + 1, patchColumn + 1, columns) * 2
    out[0] = inArray[topLeft] * size.width
    out[1] = inArray[topLeft + 1] * size.height
    out[2] = inArray[topRight] * size.width
    out[3] = inArray[topRight + 1] * size.height
    out[4] = inArray[bottomLeft] * size.width
    out[5] = inArray[bottomLeft + 1] * size.height
    out[6] = inArray[bottomRight] * size.width
    out[7] = inArray[bottomRight + 1] * size.height
  }

  /**
   * Extracts a 4x4 grid of colors centered around a specific patch for bicubic interpolation.
   *
   * @param patchIdx The index of the patch to read.
   * @param rows The number of rows in the mesh.
   * @param columns The number of columns in the mesh.
   * @param colors The source array containing the ARGB color of each vertex.
   * @param out The output array to store the 16 ARGB colors.
   */
  private fun readPatchColors(patchIdx: Int, rows: Int, columns: Int, colors: IntArray, out: IntArray) {
    val patchRow = patchIdx / columns
    val patchColumn = patchIdx % columns
    for (r in 0 until 4) {
      for (c in 0 until 4) {
        val row = (patchRow - 1 + r).coerceIn(0, rows)
        val col = (patchColumn - 1 + c).coerceIn(0, columns)
        val writeIdx = (r * 4 + c)
        val readIdx = meshGradientPointIndex(row, col, columns)
        out[writeIdx] = colors[readIdx]
      }
    }
  }

  /**
   * Builds the index buffer for [patchCount] consecutive patches, each a grid of triangles based on the number of subdivisions. The
   * vertices of patch `p` start at `p * subdivisionsU * subdivisionsV`.
   */
  private fun buildIndexBuffer(subdivisionsU: Int, subdivisionsV: Int, patchCount: Int): ShortArray {
    val indices = ShortArray((subdivisionsU - 1) * (subdivisionsV - 1) * 6 * patchCount)
    var idx = 0
    for (patch in 0 until patchCount) {
      val base = patch * subdivisionsU * subdivisionsV
      for (u in 0 until subdivisionsU - 1) {
        for (v in 0 until subdivisionsV - 1) {
          val topLeft = (base + u * subdivisionsV + v).toShort()
          val bottomLeft = (base + u * subdivisionsV + v + 1).toShort()
          val topRight = (base + (u + 1) * subdivisionsV + v).toShort()
          val bottomRight = (base + (u + 1) * subdivisionsV + v + 1).toShort()
          indices[idx++] = topLeft
          indices[idx++] = topRight
          indices[idx++] = bottomRight
          indices[idx++] = topLeft
          indices[idx++] = bottomRight
          indices[idx++] = bottomLeft
        }
      }
    }
    return indices
  }

  /**
   * Precomputes the Bernstein and Catmull-Rom basis matrices for the given number of [subdivisionsV]. These arrays are used during surface
   * interpolation to avoid redundant power and multiplication operations for every vertex in every patch.
   */
  private fun precomputeBasisArrays(subdivisionsV: Int) {
    if (vBernsteinBasis.size != subdivisionsV * 4) {
      vBernsteinBasis = FloatArray(subdivisionsV * 4)
    }
    val bernsteinBasis = vBernsteinBasis
    val subdivisionsVMinus1 = (subdivisionsV - 1).toFloat()
    for (vIndex in 0 until subdivisionsV) {
      val v = vIndex / subdivisionsVMinus1
      val v2 = v * v
      val v3 = v2 * v
      val base = vIndex * 4
      bernsteinBasis[base] = -v3 + 3f * v2 - 3f * v + 1f
      bernsteinBasis[base + 1] = 3f * v3 - 6f * v2 + 3f * v
      bernsteinBasis[base + 2] = -3f * v3 + 3f * v2
      bernsteinBasis[base + 3] = v3
    }

    if (vCatmullRomBasis.size != subdivisionsV * 4) {
      vCatmullRomBasis = FloatArray(subdivisionsV * 4)
    }
    val vCatmullRom = vCatmullRomBasis
    for (vIndex in 0 until subdivisionsV) {
      val v = vIndex / subdivisionsVMinus1
      val v2 = v * v
      val v3 = v2 * v
      val base = vIndex * 4
      vCatmullRom[base] = 0.5f * (-v3 + 2f * v2 - v)
      vCatmullRom[base + 1] = 0.5f * (3f * v3 - 5f * v2 + 2f)
      vCatmullRom[base + 2] = 0.5f * (-3f * v3 + 4f * v2 + v)
      vCatmullRom[base + 3] = 0.5f * (v3 - v2)
    }
  }
}

/** Writes the Oklab (L, a, b, alpha) components of the [argb] sRGB color into [out], starting at [offset]. */
private fun writeOklab(argb: Int, out: FloatArray, offset: Int) {
  val color = Color(argb).convert(ColorSpaces.Oklab)
  out[offset] = color.red
  out[offset + 1] = color.green
  out[offset + 2] = color.blue
  out[offset + 3] = color.alpha
}

/**
 * Converts an Oklab color to an sRGB ARGB int. Components are clamped to their valid ranges first: interpolated values can fall outside of
 * them (Catmull-Rom splines overshoot their control values), and [Color] rejects out-of-range components.
 */
private fun oklabToArgb(l: Float, a: Float, b: Float, alpha: Float): Int =
  Color(
      red = l.coerceIn(OklabMinL, OklabMaxL),
      green = a.coerceIn(OklabMinA, OklabMaxA),
      blue = b.coerceIn(OklabMinB, OklabMaxB),
      alpha = alpha.coerceIn(0f, 1f),
      colorSpace = ColorSpaces.Oklab,
    )
    .convert(ColorSpaces.Srgb)
    .toArgb()
