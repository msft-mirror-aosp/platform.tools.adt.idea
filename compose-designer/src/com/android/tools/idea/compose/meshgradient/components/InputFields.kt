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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
fun ParameterSwatch(text: String, modifier: Modifier = Modifier) {
  Box(modifier = modifier.clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
    Text(text, color = JewelTheme.globalColors.text.info)
  }
}

@Composable
fun DimensionInputField(
  value: Int,
  modifier: Modifier = Modifier,
  min: Int? = null,
  max: Int? = null,
  enabled: Boolean = true,
  paramName: String,
  onUpdate: (Int) -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val textFieldState = remember(value) { TextFieldState(value.toString()) }

  LaunchedEffect(textFieldState) {
    snapshotFlow { textFieldState.text }
      .collectLatest {
        val filteredValue = it.filter { char -> char.isDigit() }
        if (filteredValue.length != it.length) {
          textFieldState.edit { replace(0, length, filteredValue) }
        }
      }
  }

  fun reset() {
    textFieldState.edit { replace(0, length, value.toString()) }
  }

  fun validate() {
    val parsed = textFieldState.text.toString().toIntOrNull()
    if (parsed == null) {
      reset()
      return
    }
    val nextValue =
      if (min != null && max != null) {
        parsed.coerceIn(min, max)
      } else if (min != null) {
        parsed.coerceAtLeast(min)
      } else if (max != null) {
        parsed.coerceAtMost(max)
      } else {
        parsed
      }
    if (nextValue != value) {
      onUpdate(nextValue)
    }
    if (textFieldState.text.toString() != nextValue.toString()) {
      textFieldState.edit { replace(0, length, nextValue.toString()) }
    }
  }

  TextField(
    state = textFieldState,
    enabled = enabled,
    leadingIcon = { ParameterSwatch(text = paramName, modifier = Modifier.height(16.dp).padding(end = 6.dp)) },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    onKeyboardAction = {
      validate()
      focusManager.clearFocus()
    },
    modifier =
      modifier
        .onFocusChanged {
          if (!it.isFocused) {
            validate()
          }
        }
        .onKeyEvent {
          if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
          when (it.key) {
            Key.Tab -> {
              validate()
              false
            }
            Key.Escape -> {
              reset()
              focusManager.clearFocus()
              true
            }
            else -> false
          }
        },
  )
}
