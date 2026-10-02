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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.tools.idea.compose.meshgradient.formatFloat
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
internal fun ParameterSwatch(text: String, modifier: Modifier = Modifier) {
  Box(modifier = modifier.clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
    Text(text, color = JewelTheme.globalColors.text.info)
  }
}

/** Result of committing the text of a [NumericInputField]. */
internal sealed interface NumericCommit<out T : Any> {
  /** The text is not a valid value. The field keeps the text and shows an error outline. */
  data object Invalid : NumericCommit<Nothing>

  /** The text represents the current value. No update is emitted. */
  data object Unchanged : NumericCommit<Nothing>

  /** The text represents a new [value]; `null` is the automatic value. */
  data class Changed<T : Any>(val value: T?) : NumericCommit<T>
}

/**
 * Parsing, formatting and coercion rules of a [NumericInputField].
 *
 * @param parseValue parses trimmed, non-empty text that only contains accepted characters; returns `null` if it is not a valid value.
 * @param formatValue formats a value for display. Two values with the same formatted text are considered equal.
 * @param coerce brings a parsed value into the accepted range.
 * @param isAcceptedChar whether a character can be typed into the field. Other characters are rejected as they are typed.
 */
internal class NumericFormat<T : Any>(
  private val parseValue: (String) -> T?,
  private val formatValue: (T) -> String,
  private val coerce: (T) -> T,
  private val isAcceptedChar: (Char) -> Boolean,
) {
  /** Whether [text] only contains characters that can be typed into the field. */
  fun accepts(text: CharSequence): Boolean = text.all(isAcceptedChar)

  /** Parses and coerces [text], or returns `null` if it is not a valid value. */
  fun parse(text: String): T? = parseUncoerced(text)?.let(coerce)

  private fun parseUncoerced(text: String): T? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !accepts(trimmed)) return null
    return parseValue(trimmed)
  }

  /** Formats [value] for display; the automatic value (`null`) is displayed as empty text. */
  fun format(value: T?): String = value?.let(formatValue) ?: ""

  /**
   * Interprets [text] as a replacement for [current]. Blank text stands for the automatic value (`null`) when [allowAuto] is set. Values
   * are compared by their formatted text, so committing text that displays the current value never emits an update.
   */
  fun commit(text: String, current: T?, allowAuto: Boolean): NumericCommit<T> {
    val trimmed = text.trim()
    if (trimmed == format(current)) return NumericCommit.Unchanged
    val next =
      if (trimmed.isEmpty()) {
        if (!allowAuto) return NumericCommit.Invalid
        null
      } else {
        parse(trimmed) ?: return NumericCommit.Invalid
      }
    return if (format(next) == format(current)) NumericCommit.Unchanged else NumericCommit.Changed(next)
  }

  /**
   * Returns the value to commit while [text] is still being edited, or `null` if nothing should be committed yet. Unlike [commit], blank
   * text and values outside of the accepted range are not committed while editing: they are often intermediate states (e.g. clearing the
   * field before typing a new number), and are resolved when editing ends.
   */
  fun editCommit(text: String, current: T?): T? {
    val parsed = parseUncoerced(text) ?: return null
    if (coerce(parsed) != parsed || format(parsed) == format(current)) return null
    return parsed
  }
}

private fun isAsciiDigit(c: Char) = c in '0'..'9'

/** Integer format accepting values in `min..max`. A minus sign can only be typed if negative values are accepted. */
internal fun intFormat(min: Int? = null, max: Int? = null): NumericFormat<Int> =
  NumericFormat(
    parseValue = String::toIntOrNull,
    formatValue = Int::toString,
    coerce = { it.coerceIn(min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE) },
    isAcceptedChar = { isAsciiDigit(it) || (it == '-' && (min == null || min < 0)) },
  )

/**
 * Float format accepting finite values in `min..max`. Both `.` and `,` are accepted as decimal separator. A minus sign can only be typed if
 * negative values are accepted.
 */
internal fun floatFormat(min: Float? = null, max: Float? = null): NumericFormat<Float> =
  NumericFormat(
    parseValue = { text -> text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() } },
    formatValue = ::formatFloat,
    coerce = { it.coerceIn(min ?: -Float.MAX_VALUE, max ?: Float.MAX_VALUE) },
    isAcceptedChar = { isAsciiDigit(it) || it == '.' || it == ',' || (it == '-' && (min == null || min < 0f)) },
  )

/** Rejects edits that would introduce characters not accepted by [format]. */
private class NumericInputTransformation(private val format: () -> NumericFormat<*>) : InputTransformation {
  override fun TextFieldBuffer.transformInput() {
    if (!format().accepts(asCharSequence())) revertAllChanges()
  }
}

/**
 * Keeps the text of a [NumericInputField] and its value in sync.
 *
 * The text follows [value] unless the user is editing it: while the field is focused, the text is only replaced if it still shows the last
 * value displayed or committed by the field. Text is committed when the field loses focus and, if [commitWhileEditing] is set, on every
 * edit that produces a valid value in range (see [NumericFormat.editCommit]).
 *
 * @param initialValue the initial value, or `null` for the automatic value.
 * @param format parses, formats and validates the text.
 * @param allowAuto whether blank text commits the automatic value (`null`).
 * @param commitWhileEditing whether valid values are also committed as they are typed, rather than only when the field loses focus.
 * @param onUpdate called with every committed value. The caller may store a different (e.g. normalized) value and report it back through
 *   [onValueChanged].
 */
internal class NumericFieldController<T : Any>(
  initialValue: T?,
  var format: NumericFormat<T>,
  var allowAuto: Boolean = false,
  var commitWhileEditing: Boolean = false,
  var onUpdate: (T?) -> Unit = {},
) {
  /** State of the text field. */
  val textState = TextFieldState(format.format(initialValue))

  /** The current value, as last reported through [onValueChanged]. */
  var value: T? by mutableStateOf(initialValue)
    private set

  /** Whether the field has focus. */
  var isFocused: Boolean by mutableStateOf(false)
    private set

  /**
   * Number of values committed when editing ended. The field reports [value] again after each one, so the text is replaced by the stored
   * value even when the caller normalizes the committed value back to the previous one, and [value] does not change.
   */
  var focusLossCommits: Int by mutableIntStateOf(0)
    private set

  /** Last text that was known to represent [value], as opposed to text being typed by the user. */
  private var syncedText: String by mutableStateOf(textState.text.toString())

  /** Value when the field gained focus, restored by [revert]. */
  private var valueAtFocus: T? = initialValue

  private val text: String
    get() = textState.text.toString()

  /** Whether the text is not a valid value. Only reported when the field is not focused, so typing is not interrupted. */
  val isInvalid: Boolean
    get() = !isFocused && format.commit(text, value, allowAuto) == NumericCommit.Invalid

  /** Reports a new current value, updating the text unless the user is editing it. */
  fun onValueChanged(newValue: T?) {
    value = newValue
    when {
      format.commit(text, newValue, allowAuto) == NumericCommit.Unchanged -> syncedText = text
      !isFocused || text == syncedText -> display(newValue)
    }
  }

  /** Called after every change of the text. */
  fun onTextChanged() {
    if (!commitWhileEditing || !isFocused) return
    val next = format.editCommit(text, value) ?: return
    syncedText = text
    onUpdate(next)
  }

  /** Called when the focus of the field changes. Losing focus commits the text. */
  fun onFocusChanged(focused: Boolean) {
    if (focused == isFocused) return
    isFocused = focused
    if (focused) {
      valueAtFocus = value
    } else {
      commit()
    }
  }

  /** Discards the edits made since the field gained focus. */
  fun revert() {
    val original = if (isFocused) valueAtFocus else value
    display(original)
    if (format.format(original) != format.format(value)) {
      value = original
      onUpdate(original)
    }
  }

  private fun commit() {
    when (val result = format.commit(text, value, allowAuto)) {
      NumericCommit.Invalid -> Unit
      NumericCommit.Unchanged -> display(value)
      is NumericCommit.Changed -> {
        display(result.value)
        onUpdate(result.value)
        focusLossCommits++
      }
    }
  }

  private fun display(newValue: T?) {
    val newText = format.format(newValue)
    if (text != newText) textState.setTextAndPlaceCursorAtEnd(newText)
    syncedText = newText
  }
}

/**
 * A text field editing a number.
 *
 * The text is committed when the field loses focus (including through Tab or Enter); Escape discards the edits. The field displays [value]
 * and follows its changes unless the user is editing the text. Text that cannot be parsed is kept and highlighted with an error outline
 * instead of being silently reverted.
 *
 * @param value the current value, or `null` for the automatic value, which is displayed as the [autoPlaceholder].
 * @param autoPlaceholder text shown when the value is automatic. When set, clearing the field commits the automatic value.
 * @param paramName optional short label shown at the start of the field.
 * @param commitWhileEditing whether valid values are also committed as they are typed, so their effect can be seen immediately.
 */
@Composable
internal fun <T : Any> NumericInputField(
  value: T?,
  format: NumericFormat<T>,
  onUpdate: (T?) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  paramName: String? = null,
  autoPlaceholder: String? = null,
  commitWhileEditing: Boolean = false,
) {
  val focusManager = LocalFocusManager.current
  val controller = remember { NumericFieldController(value, format) }
  SideEffect {
    controller.format = format
    controller.allowAuto = autoPlaceholder != null
    controller.commitWhileEditing = commitWhileEditing
    controller.onUpdate = onUpdate
  }
  LaunchedEffect(value, format, controller.focusLossCommits) { controller.onValueChanged(value) }
  LaunchedEffect(controller) { snapshotFlow { controller.textState.text.toString() }.collect { controller.onTextChanged() } }
  val inputTransformation = remember(controller) { NumericInputTransformation { controller.format } }

  TextField(
    state = controller.textState,
    enabled = enabled,
    inputTransformation = inputTransformation,
    outline = if (controller.isInvalid) Outline.Error else Outline.None,
    placeholder = autoPlaceholder?.let { placeholder -> { Text(placeholder) } },
    leadingIcon = paramName?.let { name -> { ParameterSwatch(text = name, modifier = Modifier.height(16.dp).padding(end = 6.dp)) } },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    onKeyboardAction = { focusManager.clearFocus() },
    modifier =
      modifier
        .onFocusChanged { controller.onFocusChanged(it.isFocused) }
        .onKeyEvent {
          if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            controller.revert()
            focusManager.clearFocus()
            true
          } else {
            false
          }
        },
  )
}

/** A [NumericInputField] for an integer in `min..max`, committed when editing ends. */
@Composable
internal fun DimensionInputField(
  value: Int,
  modifier: Modifier = Modifier,
  min: Int? = null,
  max: Int? = null,
  enabled: Boolean = true,
  paramName: String,
  onUpdate: (Int) -> Unit,
) {
  val format = remember(min, max) { intFormat(min, max) }
  NumericInputField(
    value = value,
    format = format,
    onUpdate = { it?.let(onUpdate) },
    modifier = modifier,
    enabled = enabled,
    paramName = paramName,
  )
}

/**
 * A [NumericInputField] for a finite float in `min..max`.
 *
 * @param commitWhileEditing whether valid values are committed as they are typed rather than only when editing ends.
 */
@Composable
internal fun FloatInputField(
  value: Float,
  modifier: Modifier = Modifier,
  min: Float? = null,
  max: Float? = null,
  enabled: Boolean = true,
  paramName: String? = null,
  commitWhileEditing: Boolean = true,
  onUpdate: (Float) -> Unit,
) {
  val format = remember(min, max) { floatFormat(min, max) }
  NumericInputField(
    value = value,
    format = format,
    onUpdate = { it?.let(onUpdate) },
    modifier = modifier,
    enabled = enabled,
    paramName = paramName,
    commitWhileEditing = commitWhileEditing,
  )
}
