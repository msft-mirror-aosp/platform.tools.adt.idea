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

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.focusOutline

/** Diameter of a [GradientHandle]. */
internal val GRADIENT_HANDLE_SIZE = 24.dp

/** Step in brush pixels moved by a single arrow key press on a focused [GradientHandle]. */
internal const val HANDLE_KEYBOARD_STEP = 10f

/** Corner radius of a pinned handle (`25%` of [GRADIENT_HANDLE_SIZE], matching [SWATCH_SHAPE] proportions). */
private val PINNED_HANDLE_CORNER_RADIUS = 6.dp
private val PINNED_HANDLE_SHAPE = RoundedCornerShape(PINNED_HANDLE_CORNER_RADIUS)

private val OUTER_BORDER_WIDTH = 1.dp
private val INNER_RING_WIDTH = 1.5.dp
private val INNER_HAIRLINE_WIDTH = 0.5.dp
private val CHECKERBOARD_CELL_SIZE = 3.dp

private val SINGLE_GLYPH_FONT_SIZE = 11.dp
private val MULTI_GLYPH_FONT_SIZE = 9.dp
private val MULTI_GLYPH_LETTER_SPACING = (-0.3).dp

private val OUTER_BORDER_COLOR = Color.Black.copy(alpha = 0.45f)
private val PINNED_RING_COLOR = Color(0xFFD0D5DD)
private val INNER_BORDER_COLOR = Color.Black.copy(alpha = 0.18f)
private val CHECKERBOARD_LIGHT_COLOR = Color.White
private val CHECKERBOARD_DARK_COLOR = Color(0xFFDCDFE4)
private val CHECKERBOARD_AVERAGE_COLOR = Color(0xFFEEF0F2)

/**
 * A circular handle drawn over a gradient preview, filled with [color] and showing a short [glyph].
 *
 * @param glyph short text drawn inside the handle (e.g. a letter or an index). It is purely visual: assistive technologies read
 *   [contentDescription] and [stateDescription] instead.
 * @param contentDescription accessible description of the point the handle controls.
 * @param stateDescription optional provider of the accessible description of the current coordinates of the handle.
 * @param onMove optional callback invoked with a delta in brush pixels when the user presses an arrow key while the handle is focused.
 */
@Composable
internal fun GradientHandle(
  glyph: String,
  contentDescription: String,
  color: Color,
  modifier: Modifier = Modifier,
  stateDescription: (() -> String)? = null,
  onMove: ((delta: Offset) -> Unit)? = null,
) {
  GradientHandleBox(
    glyph = glyph,
    color = color,
    pinned = false,
    modifier = modifier,
    onKeyEvent = { event ->
      if (event.type != KeyEventType.KeyDown || onMove == null) return@GradientHandleBox false
      when (event.key) {
        Key.DirectionLeft -> {
          onMove(Offset(-HANDLE_KEYBOARD_STEP, 0f))
          true
        }
        Key.DirectionRight -> {
          onMove(Offset(HANDLE_KEYBOARD_STEP, 0f))
          true
        }
        Key.DirectionUp -> {
          onMove(Offset(0f, -HANDLE_KEYBOARD_STEP))
          true
        }
        Key.DirectionDown -> {
          onMove(Offset(0f, HANDLE_KEYBOARD_STEP))
          true
        }
        else -> false
      }
    },
    semantics = {
      this.contentDescription = contentDescription
      if (stateDescription != null) {
        this.stateDescription = stateDescription()
      }
    },
  )
}

/**
 * Shared visual container for gradient control handles (both standard Brush handles and mesh vertex cursors).
 *
 * Draws a dual-ring border (dark outer ring + opaque white/silver inner ring) strictly inside [GRADIENT_HANDLE_SIZE] so the handle remains
 * legible over both light and dark backgrounds without bleeding fill color into the border. Movable handles are circular; [pinned] handles
 * use a rounded-square silhouette with a muted opaque ring while retaining full-contrast [glyph] text.
 */
@Composable
internal fun GradientHandleBox(
  glyph: String,
  color: Color,
  pinned: Boolean,
  onKeyEvent: (KeyEvent) -> Boolean,
  semantics: SemanticsPropertyReceiver.() -> Unit,
  modifier: Modifier = Modifier,
) {
  var isFocused by remember { mutableStateOf(false) }
  val shape = if (pinned) PINNED_HANDLE_SHAPE else CircleShape
  val textColor = contrastingHandleTextColor(color)
  val density = LocalDensity.current
  val isSingleGlyph = glyph.length <= 1
  // Size the glyph in dp converted to sp so the label always fits within the fixed-dp handle regardless of fontScale.
  val fontSize = with(density) { (if (isSingleGlyph) SINGLE_GLYPH_FONT_SIZE else MULTI_GLYPH_FONT_SIZE).toSp() }
  val letterSpacing = with(density) { (if (isSingleGlyph) 0.dp else MULTI_GLYPH_LETTER_SPACING).toSp() }

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .size(GRADIENT_HANDLE_SIZE)
        .focusOutline(isFocused, shape)
        .onFocusChanged { isFocused = it.isFocused }
        .onKeyEvent(onKeyEvent)
        .focusable()
        .clearAndSetSemantics(semantics)
        .drawGradientHandleBackground(color = color, pinned = pinned),
  ) {
    Text(
      text = glyph,
      color = textColor,
      fontSize = fontSize,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = letterSpacing,
      lineHeight = fontSize,
      maxLines = 1,
      softWrap = false,
    )
  }
}

/**
 * Returns [Color.Black] or [Color.White], whichever provides higher WCAG 2.x contrast against [color].
 *
 * Semi-transparent colors are first composited over [CHECKERBOARD_AVERAGE_COLOR] so the contrast reflects the checkerboard backing drawn
 * behind translucent fills rather than ignoring [Color.alpha]. Comparing WCAG contrast ratios places the relative-luminance crossover at
 * `sqrt(1.05 * 0.05) - 0.05` (~0.179), guaranteeing at least ~4.58:1 contrast for every color.
 */
internal fun contrastingHandleTextColor(color: Color): Color {
  val effectiveColor = if (color.alpha < 1f) color.compositeOver(CHECKERBOARD_AVERAGE_COLOR) else color
  val luminance = effectiveColor.luminance()
  val whiteContrast = 1.05f / (luminance + 0.05f)
  val blackContrast = (luminance + 0.05f) / 0.05f
  return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun Modifier.drawGradientHandleBackground(color: Color, pinned: Boolean): Modifier = drawWithCache {
  val outerBorderPx = OUTER_BORDER_WIDTH.toPx()
  val ringInsetPx = outerBorderPx
  val fillInsetPx = outerBorderPx + INNER_RING_WIDTH.toPx()
  val hairlinePx = INNER_HAIRLINE_WIDTH.toPx().coerceAtLeast(1f)
  val hairlineStroke = Stroke(width = hairlinePx)
  val ringColor = if (pinned) PINNED_RING_COLOR else Color.White
  val pinnedCornerPx = PINNED_HANDLE_CORNER_RADIUS.toPx()
  val cellSizePx = CHECKERBOARD_CELL_SIZE.toPx()

  val clipPath =
    if (color.alpha < 1f) {
      Path().apply {
        val rect = Rect(fillInsetPx, fillInsetPx, size.width - fillInsetPx, size.height - fillInsetPx)
        if (pinned) {
          val radius = (pinnedCornerPx - fillInsetPx).coerceAtLeast(0f)
          addRoundRect(RoundRect(rect, CornerRadius(radius, radius)))
        } else {
          addOval(rect)
        }
      }
    } else {
      null
    }

  onDrawWithContent {
    // Paint concentric filled layers from outside in so adjacent anti-aliased edges never leave translucent seams, and neither the canvas
    // nor the handle fill bleeds into the opaque ring.
    drawHandleLayer(inset = 0f, pinned = pinned, pinnedCornerPx = pinnedCornerPx, color = OUTER_BORDER_COLOR)
    drawHandleLayer(inset = ringInsetPx, pinned = pinned, pinnedCornerPx = pinnedCornerPx, color = ringColor)

    if (clipPath != null) {
      clipPath(clipPath) {
        drawCheckerboard(inset = fillInsetPx, cellSizePx = cellSizePx)
        if (color.alpha > 0f) {
          drawRect(color = color)
        }
      }
    } else {
      drawHandleLayer(inset = fillInsetPx, pinned = pinned, pinnedCornerPx = pinnedCornerPx, color = color)
    }

    // A subtle inner hairline separates white, very light, or transparent fills from the surrounding light ring.
    drawHandleLayer(
      inset = fillInsetPx + hairlinePx / 2f,
      pinned = pinned,
      pinnedCornerPx = pinnedCornerPx,
      color = INNER_BORDER_COLOR,
      style = hairlineStroke,
    )
    drawContent()
  }
}

private fun DrawScope.drawHandleLayer(
  inset: Float,
  pinned: Boolean,
  pinnedCornerPx: Float,
  color: Color,
  style: DrawStyle = Fill,
) {
  val width = (size.width - 2f * inset).coerceAtLeast(0f)
  val height = (size.height - 2f * inset).coerceAtLeast(0f)
  if (width == 0f || height == 0f) return
  if (pinned) {
    val radius = (pinnedCornerPx - inset).coerceAtLeast(0f)
    drawRoundRect(
      color = color,
      topLeft = Offset(inset, inset),
      size = Size(width, height),
      cornerRadius = CornerRadius(radius, radius),
      style = style,
    )
  } else {
    drawCircle(color = color, radius = minOf(width, height) / 2f, style = style)
  }
}

private fun DrawScope.drawCheckerboard(inset: Float, cellSizePx: Float) {
  drawRect(color = CHECKERBOARD_LIGHT_COLOR)
  if (cellSizePx <= 0f) return
  val endX = size.width - inset
  val endY = size.height - inset
  var row = 0
  var y = inset
  while (y < endY) {
    val startCol = if (row % 2 == 0) 1 else 0
    var x = inset + startCol * cellSizePx
    val cellHeight = minOf(cellSizePx, endY - y)
    while (x < endX) {
      val cellWidth = minOf(cellSizePx, endX - x)
      drawRect(color = CHECKERBOARD_DARK_COLOR, topLeft = Offset(x, y), size = Size(cellWidth, cellHeight))
      x += 2f * cellSizePx
    }
    y += cellSizePx
    row++
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
