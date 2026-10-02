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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.meshgradient.components.ColorSwatch
import com.android.tools.idea.compose.meshgradient.components.FloatInputField
import com.android.tools.idea.ui.resourcechooser.common.ResourcePickerSources
import com.android.tools.idea.ui.resourcechooser.util.createAndShowColorPickerPopup
import com.intellij.openapi.project.Project
import java.awt.Color as AwtColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UseJBColor")
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
        DynamicValuesWarningBanner(
          "Some gradient arguments use dynamic or unresolved expressions. Their initial or default values are shown and they are kept as written; they cannot be changed in the editor."
        )
      }

      // 1. Preview Canvas
      Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        StandardGradientCanvas(state)
      }

      Divider(orientation = Orientation.Horizontal)

      // 2. Colors List
      Text("Colors", style = JewelTheme.typography.h4TextStyle, fontWeight = FontWeight.SemiBold)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        state.colors.forEachIndexed { index, color ->
          ContextMenuArea(
            items = {
              if (state.colors.size > 2) {
                listOf(ContextMenuItem("Delete") { state.removeColor(index) })
              } else {
                emptyList()
              }
            }
          ) {
            ColorSwatch(
              color = color,
              modifier =
                Modifier.clickable {
                  createAndShowColorPickerPopup(
                    initialColor = AwtColor(color.toArgb(), true),
                    initialColorResource = null,
                    facet = null,
                    contextFile = null,
                    resourceResolver = null,
                    resourcePickerSources = emptyList<ResourcePickerSources>(),
                    restoreFocusComponent = null,
                    locationToShow = null,
                    colorPickedCallback = { newAwtColor ->
                      val newColor = Color(newAwtColor.rgb)
                      state.updateColor(index, newColor)
                    },
                    colorResourcePickedCallback = null,
                  )
                },
            )
          }
        }
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
              .size(16.dp)
              .background(JewelTheme.globalColors.panelBackground)
              .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
              .clickable {
                var addedIndex: Int? = null
                createAndShowColorPickerPopup(
                  initialColor = AwtColor.WHITE,
                  initialColorResource = null,
                  facet = null,
                  contextFile = null,
                  resourceResolver = null,
                  resourcePickerSources = emptyList<ResourcePickerSources>(),
                  restoreFocusComponent = null,
                  locationToShow = null,
                  colorPickedCallback = { newAwtColor ->
                    val newColor = Color(newAwtColor.rgb)
                    val idx = addedIndex
                    if (idx == null) {
                      state.addColor(newColor)
                      addedIndex = state.colors.lastIndex
                    } else if (idx in state.colors.indices) {
                      state.updateColor(idx, newColor)
                    }
                  },
                  colorResourcePickedCallback = null,
                )
              },
        ) {
          Icon(
            key = AllIconsKeys.General.InlineAdd,
            iconClass = AllIconsKeys::class.java,
            contentDescription = "Add Color",
            modifier = Modifier.size(10.dp),
          )
        }
      }

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
      paramName = "X",
      onUpdate = { onUpdate(Offset(it, offset.safeY(defaultY))) },
      modifier = Modifier.width(100.dp),
    )
    FloatInputField(
      value = offset.safeY(defaultY),
      paramName = "Y",
      onUpdate = { onUpdate(Offset(offset.safeX(defaultX), it)) },
      modifier = Modifier.width(100.dp),
    )
  }
}

@Composable
fun LinearControls(state: GradientEditorState) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OffsetControl(
      label = "Start Offset",
      offset = state.start,
      defaultX = 0f,
      defaultY = 0f,
      onUpdate = { state.start = it },
    )
    OffsetControl(
      label = "End Offset",
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
      label = "Center Offset",
      offset = state.center,
      defaultX = 0.5f,
      defaultY = 0.5f,
      onUpdate = { state.center = it },
    )
    Text("Radius", style = JewelTheme.typography.labelTextStyle)
    FloatInputField(
      value = if (state.radius.isFinite()) state.radius else 0.5f,
      min = 0.001f,
      paramName = "R",
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
      label = "Center Offset",
      offset = state.center,
      defaultX = 0.5f,
      defaultY = 0.5f,
      onUpdate = { state.center = it },
    )
  }
}

@Composable
fun TileModeControl(value: TileMode, onUpdate: (TileMode) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Tile Mode", style = JewelTheme.typography.labelTextStyle)
    val modes = listOf(TileMode.Clamp, TileMode.Repeated, TileMode.Mirror, TileMode.Decal)
    Dropdown(
      menuContent = {
        for (mode in modes) {
          selectableItem(selected = value == mode, onClick = { onUpdate(mode) }) { Text(text = mode.toString()) }
        }
      }
    ) {
      Text(text = value.toString())
    }
  }
}
