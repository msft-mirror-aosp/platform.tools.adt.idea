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

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.LightCalloutPopup
import com.android.tools.adtui.MaterialColorPaletteProvider
import com.android.tools.adtui.stdui.KeyStrokes
import com.android.tools.idea.compose.meshgradient.toHexStringNoHash
import com.android.tools.idea.compose.preview.message
import com.intellij.ui.colorpicker.ColorPickerBuilder
import com.intellij.ui.colorpicker.MaterialGraphicalColorPipetteProvider
import java.awt.Color as AwtColor
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Shape of a [ColorSwatch], shared by the controls laid out next to swatches. */
internal val SWATCH_SHAPE = RoundedCornerShape(4.dp)

/** Size of a [ColorSwatch], shared by the controls laid out next to swatches. */
internal val SWATCH_SIZE = 16.dp
private val TRANSPARENT_MARK_WIDTH = 1.5.dp

/** Returns the `#AARRGGBB` representation of this color. */
internal fun Color.toHexString(): String = "#${toHexStringNoHash(includeAlpha = true)}"

/** Returns the accessibility description of a [ColorSwatch] showing [color]. */
internal fun colorSwatchDescription(color: Color): String = message("gradient.editor.swatch.description", color.toHexString())

/**
 * A small square preview of [color], outlined so that colors matching the panel background stay visible. Fully transparent colors are drawn
 * as an empty swatch crossed by a diagonal line. Callers provide the content description, see [colorSwatchDescription].
 */
@Composable
internal fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
  var isFocused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val borderColor = JewelTheme.globalColors.borders.normal
  val transparentMarkColor = JewelTheme.globalColors.text.error
  Box(
    modifier =
      Modifier.focusOutline(showOutline = isFocused, outlineShape = SWATCH_SHAPE)
        .onFocusChanged { isFocused = it.isFocused }
        .colorNavigationKeys(focusManager)
        .size(SWATCH_SIZE)
        .clip(SWATCH_SHAPE)
        .then(modifier)
        .then(
          if (color.alpha == 0f) {
            Modifier.drawBehind {
              drawLine(
                color = transparentMarkColor,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
                strokeWidth = TRANSPARENT_MARK_WIDTH.toPx(),
              )
            }
          } else {
            Modifier.background(color)
          }
        )
        .border(1.dp, borderColor, SWATCH_SHAPE)
  )
}

/**
 * A [ColorSwatch] button that opens a color editor on click and, when [onDelete] is provided, can be deleted from its context menu, with
 * the Delete/Backspace keys, or through an accessibility action.
 */
@Composable
internal fun EditableColorSwatch(
  color: Color,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onDelete: (() -> Unit)? = null,
) {
  val deleteLabel = message("gradient.editor.brush.delete.color")
  ContextMenuArea(items = { if (onDelete != null) listOf(ContextMenuItem(deleteLabel, onDelete)) else emptyList() }) {
    ColorSwatch(
      color = color,
      modifier =
        modifier
          .semantics {
            this.contentDescription = contentDescription
            if (onDelete != null) {
              customActions =
                listOf(
                  CustomAccessibilityAction(deleteLabel) {
                    onDelete()
                    true
                  }
                )
            }
          }
          .onKeyEvent { event ->
            if (onDelete != null && event.type == KeyEventType.KeyDown && (event.key == Key.Delete || event.key == Key.Backspace)) {
              onDelete()
              true
            } else {
              false
            }
          }
          .clickable(role = Role.Button, onClick = onClick),
    )
  }
}

/** A swatch-sized button that adds a new color stop or palette color. */
@Composable
internal fun AddColorButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  var isFocused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.focusOutline(showOutline = isFocused, outlineShape = SWATCH_SHAPE)
        .onFocusChanged { isFocused = it.isFocused }
        .colorNavigationKeys(focusManager)
        .then(modifier)
        .clip(SWATCH_SHAPE)
        .size(SWATCH_SIZE)
        .background(JewelTheme.globalColors.panelBackground)
        .border(1.dp, JewelTheme.globalColors.borders.normal, SWATCH_SHAPE)
        .clickable(role = Role.Button, onClick = onClick),
  ) {
    Icon(
      key = AllIconsKeys.General.InlineAdd,
      iconClass = AllIconsKeys::class.java,
      contentDescription = contentDescription,
      modifier = Modifier.size(10.dp),
    )
  }
}

/**
 * Grid of [palette] color swatches shown inside a color chooser popup. Requests initial focus on the swatch matching [selectedColor] (or
 * the first swatch if none matches) so keyboard and screen reader users can navigate the palette immediately when the popup opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteSwatchGrid(
  palette: List<Color>,
  selectedColor: Color?,
  onColorSelected: (Color) -> Unit,
  modifier: Modifier = Modifier,
  onClickLabel: String? = null,
) {
  val initialFocusRequester = remember { FocusRequester() }
  val initialIndex = remember(palette, selectedColor) { palette.indexOf(selectedColor).coerceAtLeast(0) }
  LaunchedEffect(palette.isNotEmpty()) {
    if (palette.isNotEmpty()) {
      initialFocusRequester.tryRequestFocus()
    }
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = modifier,
  ) {
    palette.forEachIndexed { index, color ->
      val description = colorSwatchDescription(color)
      val isSelected = color == selectedColor
      ColorSwatch(
        color = color,
        modifier =
          (if (index == initialIndex) Modifier.focusRequester(initialFocusRequester) else Modifier)
            .semantics {
              contentDescription = description
              selected = isSelected
            }
            .clickable(onClickLabel = onClickLabel, role = Role.Button) { onColorSelected(color) },
      )
    }
  }
}

/** Requests focus on this [FocusRequester], ignoring [IllegalStateException] if the target node is not currently attached. */
internal fun FocusRequester.tryRequestFocus() {
  try {
    requestFocus()
  } catch (_: IllegalStateException) {}
}

/** Moves focus between adjacent controls when arrow keys are pressed on a color swatch or add-color button. */
private fun Modifier.colorNavigationKeys(focusManager: FocusManager): Modifier = onKeyEvent { event ->
  if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
  when (event.key) {
    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Previous)
    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Next)
    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
    else -> false
  }
}

/**
 * Shows a color picker popup at the mouse location, starting at [initialColor]. [onColorPicked] is called for every color picked while the
 * popup is open, and [onClosed] once the popup closes, however it is closed. In a headless environment, or if the mouse location is
 * unknown, e.g. in some remote sessions, no popup is shown and [onClosed] is called right away.
 */
internal fun showColorPicker(initialColor: Color, onColorPicked: (Color) -> Unit, onClosed: () -> Unit = {}) {
  // MouseInfo.getPointerInfo throws a HeadlessException in a headless environment.
  if (GraphicsEnvironment.isHeadless()) return onClosed()
  val location = MouseInfo.getPointerInfo()?.location ?: return onClosed()
  val popup = LightCalloutPopup(closedCallback = onClosed, cancelCallBack = onClosed)
  @Suppress("UseJBColor") val initialAwtColor = AwtColor(initialColor.toArgb(), true)
  val picker =
    ColorPickerBuilder(showAlpha = true, showAlphaAsPercent = false)
      .setOriginalColor(initialAwtColor)
      .addSaturationBrightnessComponent()
      .addColorAdjustPanel(MaterialGraphicalColorPipetteProvider())
      .addColorValuePanel()
      .withFocus()
      .addSeparator()
      .addCustomComponent(MaterialColorPaletteProvider)
      .addColorListener { color, _ -> onColorPicked(Color(color.rgb)) }
      .focusWhenDisplay(true)
      .setFocusCycleRoot(true)
      .addKeyAction(
        KeyStrokes.ESCAPE,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) {
            popup.close()
          }
        },
      )
      .build()
  popup.show(picker.content, null, location)
}
