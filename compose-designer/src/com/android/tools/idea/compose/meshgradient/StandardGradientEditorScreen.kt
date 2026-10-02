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

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.meshgradient.components.ColorSwatch
import com.android.tools.idea.compose.meshgradient.components.FloatInputField
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.ui.resourcechooser.util.createAndShowColorPickerPopup
import com.intellij.openapi.project.Project
import java.awt.Color as AwtColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val FRACTION_FIELD_WIDTH = 72.dp
private val SWATCH_SIZE = 16.dp
private val SWATCH_SHAPE = RoundedCornerShape(4.dp)
private val PALETTE_MAX_WIDTH = 220.dp

@Composable
fun StandardGradientEditorScreen(project: Project, state: GradientEditorState, isEditingExisting: Boolean = false) {
  val scrollState = rememberScrollState()

  Column(
    modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(
      modifier =
        if (isEditingExisting) {
          Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
        } else {
          Modifier.fillMaxWidth().verticalScroll(scrollState)
        },
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isEditingExisting && state.hasDynamicOrUnresolvedValues) {
        DynamicValuesWarningBanner(message("gradient.editor.brush.dynamic.values.warning"))
      }

      // 1. Preview Canvas
      Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        StandardGradientCanvas(state)
      }

      Divider(orientation = Orientation.Horizontal)

      // 2. Colors List
      ColorStopsSection(state)

      Divider(orientation = Orientation.Horizontal)

      // 3. Specific Parameters
      when (state.currentType) {
        GradientType.LINEAR -> LinearControls(state)
        GradientType.RADIAL -> RadialControls(state)
        GradientType.SWEEP -> SweepControls(state)
        else -> {}
      }
    }

    if (!isEditingExisting) {
      GeneratedCodeSection(project = project, generatedCode = state.generatedCode)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorStopsSection(state: GradientEditorState) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(message("gradient.editor.brush.colors"), style = JewelTheme.typography.h4TextStyle, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.weight(1f))
    CheckboxRow(
      text = message("gradient.editor.brush.explicit.stops"),
      checked = ColorStops.hasExplicitFractions(state.stops),
      onCheckedChange = { state.setExplicitFractions(it) },
    )
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    state.stops.forEachIndexed { index, stop -> key(stop.id) { ColorStopItem(state, stop, index) } }
    AddColorStopButton(state)
  }
}

@Composable
private fun ColorStopItem(state: GradientEditorState, stop: ColorStop, index: Int) {
  val stopId = stop.id
  val fraction = stop.fraction
  val colorText = "#${stop.color.toHexStringNoHash(includeAlpha = true)}"
  val description =
    if (fraction == null) {
      message("gradient.editor.brush.color.stop.description", index + 1, colorText)
    } else {
      message("gradient.editor.brush.color.stop.fraction.description", index + 1, colorText, formatFloat(fraction))
    }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    ContextMenuArea(
      items = {
        if (state.stops.size > ColorStops.MIN_STOPS) {
          listOf(ContextMenuItem(message("gradient.editor.brush.delete.color")) { state.removeColorStop(stopId) })
        } else {
          emptyList()
        }
      }
    ) {
      ColorChooser(
        initialColor = stop.color,
        palette = state.availableColors,
        newPickSession = { { color -> state.updateStopColor(stopId, color) } },
      ) { open ->
        ColorSwatch(
          color = stop.color,
          modifier = Modifier.semantics { contentDescription = description }.clickable(role = Role.Button, onClick = open),
        )
      }
    }
    if (fraction != null) {
      // Changing a fraction re-sorts the stops, so it is only committed when editing ends to avoid moving the field while typing.
      FloatInputField(
        value = fraction,
        min = 0f,
        max = 1f,
        commitWhileEditing = false,
        onUpdate = { state.updateStopFraction(stopId, it) },
        modifier = Modifier.width(FRACTION_FIELD_WIDTH),
      )
    }
  }
}

@Composable
private fun AddColorStopButton(state: GradientEditorState) {
  ColorChooser(
    initialColor = Color.White,
    palette = state.availableColors,
    newPickSession = {
      // The first picked color adds a stop; colors picked later in the same session update it.
      var addedStopId: Long? = null
      val session: (Color) -> Unit = { color ->
        val id = addedStopId
        if (id == null) addedStopId = state.addColorStop(color) else state.updateStopColor(id, color)
      }
      session
    },
  ) { open ->
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.clip(SWATCH_SHAPE)
          .size(SWATCH_SIZE)
          .background(JewelTheme.globalColors.panelBackground)
          .border(1.dp, JewelTheme.globalColors.borders.normal, SWATCH_SHAPE)
          .clickable(role = Role.Button, onClick = open),
    ) {
      Icon(
        key = AllIconsKeys.General.InlineAdd,
        iconClass = AllIconsKeys::class.java,
        contentDescription = message("gradient.editor.brush.add.color"),
        modifier = Modifier.size(10.dp),
      )
    }
  }
}

/**
 * Lets the user pick a color from [palette], shown in a popup below the [content], or from the IDE color picker. The palette holds the
 * default colors, the colors of the gradient and the colors declared in scope of the edited call (see
 * [GradientPsiManager.collectAvailableColors]). When [palette] is empty, the IDE color picker opens directly.
 *
 * @param newPickSession creates the callback that receives the colors picked after one opening of the chooser. The IDE color picker reports
 *   every color the user selects while it is open, so a session can receive several colors.
 * @param content the element that opens the chooser by calling the given function.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChooser(
  initialColor: Color,
  palette: List<Color>,
  newPickSession: () -> (Color) -> Unit,
  content: @Composable (open: () -> Unit) -> Unit,
) {
  var paletteSession by remember { mutableStateOf<((Color) -> Unit)?>(null) }
  Box {
    content {
      val session = newPickSession()
      if (palette.isEmpty()) showColorPicker(initialColor, session) else paletteSession = session
    }
    val session = paletteSession
    if (session != null) {
      PopupContainer(onDismissRequest = { paletteSession = null }, horizontalAlignment = Alignment.Start) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(max = PALETTE_MAX_WIDTH),
          ) {
            palette.forEach { color ->
              val colorText = "#${color.toHexStringNoHash(includeAlpha = true)}"
              ColorSwatch(
                color = color,
                modifier =
                  Modifier.semantics { contentDescription = colorText }
                    .clickable(role = Role.Button) {
                      session(color)
                      paletteSession = null
                    },
              )
            }
          }
          Link(
            text = message("gradient.editor.brush.color.custom"),
            onClick = {
              paletteSession = null
              showColorPicker(initialColor, session)
            },
          )
        }
      }
    }
  }
}

private fun showColorPicker(initialColor: Color, onColorPicked: (Color) -> Unit) {
  @Suppress("UseJBColor") val initialAwtColor = AwtColor(initialColor.toArgb(), true)
  createAndShowColorPickerPopup(
    initialColor = initialAwtColor,
    initialColorResource = null,
    facet = null,
    contextFile = null,
    resourceResolver = null,
    resourcePickerSources = emptyList(),
    restoreFocusComponent = null,
    locationToShow = null,
    colorPickedCallback = { onColorPicked(Color(it.rgb)) },
    colorResourcePickedCallback = null,
  )
}

internal fun Offset.safeX(default: Float): Float = if (this != Offset.Unspecified && x.isFinite()) x else default

internal fun Offset.safeY(default: Float): Float = if (this != Offset.Unspecified && y.isFinite()) y else default

@Composable
private fun OffsetControl(
  label: String,
  offset: Offset,
  defaultX: Float,
  defaultY: Float,
  onUpdate: (Offset) -> Unit,
) {
  Text(label, style = JewelTheme.typography.labelTextStyle)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FloatInputField(
      value = offset.safeX(defaultX),
      paramName = message("gradient.editor.brush.param.x"),
      onUpdate = { onUpdate(Offset(it, offset.safeY(defaultY))) },
      modifier = Modifier.width(100.dp),
    )
    FloatInputField(
      value = offset.safeY(defaultY),
      paramName = message("gradient.editor.brush.param.y"),
      onUpdate = { onUpdate(Offset(offset.safeX(defaultX), it)) },
      modifier = Modifier.width(100.dp),
    )
  }
}

@Composable
fun LinearControls(state: GradientEditorState) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OffsetControl(
      label = message("gradient.editor.brush.start"),
      offset = state.start,
      defaultX = 0f,
      defaultY = 0f,
      onUpdate = { state.start = it },
    )
    OffsetControl(
      label = message("gradient.editor.brush.end"),
      offset = state.end,
      defaultX = 1f,
      defaultY = 1f,
      onUpdate = { state.end = it },
    )
    TileModeControl(value = state.tileMode, onUpdate = { state.tileMode = it })
  }
}

@Composable
fun RadialControls(state: GradientEditorState) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OffsetControl(
      label = message("gradient.editor.brush.center"),
      offset = state.center,
      defaultX = 0.5f,
      defaultY = 0.5f,
      onUpdate = { state.center = it },
    )
    Text(message("gradient.editor.brush.radius"), style = JewelTheme.typography.labelTextStyle)
    FloatInputField(
      value = if (state.radius.isFinite()) state.radius else 0.5f,
      min = 0.001f,
      paramName = message("gradient.editor.brush.param.radius"),
      onUpdate = { state.radius = it },
      modifier = Modifier.width(100.dp),
    )
    TileModeControl(value = state.tileMode, onUpdate = { state.tileMode = it })
  }
}

@Composable
fun SweepControls(state: GradientEditorState) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OffsetControl(
      label = message("gradient.editor.brush.center"),
      offset = state.center,
      defaultX = 0.5f,
      defaultY = 0.5f,
      onUpdate = { state.center = it },
    )
  }
}

/** Tile modes offered by the editor, in display order. */
private val TILE_MODES = listOf(TileMode.Clamp, TileMode.Repeated, TileMode.Mirror, TileMode.Decal)

private fun tileModeDisplayName(tileMode: TileMode): String =
  when (tileMode) {
    TileMode.Repeated -> message("gradient.editor.brush.tile.mode.repeated")
    TileMode.Mirror -> message("gradient.editor.brush.tile.mode.mirror")
    TileMode.Decal -> message("gradient.editor.brush.tile.mode.decal")
    else -> message("gradient.editor.brush.tile.mode.clamp")
  }

@Composable
private fun TileModeControl(value: TileMode, onUpdate: (TileMode) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(message("gradient.editor.brush.tile.mode"), style = JewelTheme.typography.labelTextStyle)
    ListComboBox(
      items = remember { TILE_MODES.map(::tileModeDisplayName) },
      selectedIndex = TILE_MODES.indexOf(value).coerceAtLeast(0),
      onSelectedItemChange = { onUpdate(TILE_MODES[it]) },
      modifier = Modifier.width(120.dp),
    )
  }
}
