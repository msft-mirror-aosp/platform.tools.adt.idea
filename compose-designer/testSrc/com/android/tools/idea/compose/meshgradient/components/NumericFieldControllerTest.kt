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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NumericFieldControllerTest {
  private val updates = mutableListOf<Float?>()

  private fun floatController(
    initialValue: Float?,
    min: Float? = null,
    max: Float? = null,
    allowAuto: Boolean = false,
    commitWhileEditing: Boolean = true,
  ) =
    NumericFieldController(
      initialValue = initialValue,
      format = floatFormat(min, max),
      allowAuto = allowAuto,
      commitWhileEditing = commitWhileEditing,
      onUpdate = { updates.add(it) },
    )

  private fun NumericFieldController<*>.type(text: String) {
    textState.edit { replace(0, length, text) }
    onTextChanged()
  }

  private val NumericFieldController<*>.text: String
    get() = textState.text.toString()

  @Test
  fun commitsValidValuesWhileEditing() {
    val controller = floatController(10f)
    controller.onFocusChanged(true)

    controller.type("12")
    controller.onValueChanged(12f)
    controller.type("12.")
    controller.onValueChanged(12f)
    controller.type("12.5")

    assertEquals(listOf(12f, 12.5f), updates)
    assertEquals("Committed values do not reformat the text being typed", "12.5", controller.text)
  }

  @Test
  fun doesNotCommitWhileEditingWhenDisabled() {
    val controller = floatController(10f, commitWhileEditing = false)
    controller.onFocusChanged(true)

    controller.type("12")
    assertTrue(updates.isEmpty())

    controller.onFocusChanged(false)
    assertEquals(listOf(12f), updates)
  }

  @Test
  fun outOfRangeValuesAreOnlyCommittedWhenEditingEnds() {
    val controller = floatController(100f, max = 400f)
    controller.onFocusChanged(true)

    controller.type("500")
    assertTrue(updates.isEmpty())

    controller.onFocusChanged(false)
    assertEquals(listOf(400f), updates)
    assertEquals("400", controller.text)
  }

  @Test
  fun focusLossNormalizesTextOfCommittedValue() {
    val controller = floatController(10f)
    controller.onFocusChanged(true)
    controller.type("12.")
    controller.onValueChanged(12f)

    controller.onFocusChanged(false)

    assertEquals(listOf(12f), updates)
    assertEquals("12", controller.text)
  }

  @Test
  fun followsExternalChangesUnlessTheUserIsEditing() {
    val controller = floatController(10f)
    controller.onValueChanged(20f)
    assertEquals("20", controller.text)

    controller.onFocusChanged(true)
    controller.onValueChanged(30f)
    assertEquals("Focused field still showing the value follows it", "30", controller.text)

    controller.type("4")
    controller.onValueChanged(4f)
    controller.onValueChanged(50f)
    assertEquals("Text committed while editing keeps following the value", "50", controller.text)

    controller.type("-")
    controller.onValueChanged(60f)
    assertEquals("Text being typed is kept", "-", controller.text)
  }

  @Test
  fun invalidTextIsKeptAndReportedWhenEditingEnds() {
    val controller = floatController(10f)
    controller.onFocusChanged(true)
    controller.type("-")
    assertFalse("Invalid text is not reported while typing", controller.isInvalid)

    controller.onFocusChanged(false)

    assertTrue(updates.isEmpty())
    assertEquals("-", controller.text)
    assertTrue(controller.isInvalid)
  }

  @Test
  fun revertRestoresValueAtFocus() {
    val controller = floatController(10f)
    controller.onFocusChanged(true)
    controller.type("12")
    controller.onValueChanged(12f)

    controller.revert()
    controller.onFocusChanged(false)

    assertEquals(listOf(12f, 10f), updates)
    assertEquals("10", controller.text)
  }

  @Test
  fun clearingCommitsAutomaticValueWhenEditingEnds() {
    val controller = floatController(10f, allowAuto = true)
    controller.onFocusChanged(true)

    controller.type("")
    assertTrue("Blank text is not committed while typing", updates.isEmpty())

    controller.onFocusChanged(false)
    assertEquals(listOf<Float?>(null), updates)
  }

  @Test
  fun programmaticTextChangesAreNotCommitted() {
    val controller = floatController(10f)
    controller.onValueChanged(20f)
    controller.onTextChanged()
    assertTrue(updates.isEmpty())
  }

  @Test
  fun commitsOnlyWhenEditingEndsByDefault() {
    val updates = mutableListOf<Int?>()
    val controller = NumericFieldController(initialValue = 3, format = intFormat(min = 2, max = 10), onUpdate = { updates.add(it) })
    controller.onFocusChanged(true)

    controller.textState.edit { replace(0, length, "5") }
    controller.onTextChanged()
    assertTrue(updates.isEmpty())

    controller.onFocusChanged(false)
    assertEquals(listOf<Int?>(5), updates)
  }

  @Test
  fun valueNormalizedBackToThePreviousOneIsRedisplayedAfterFocusLoss() {
    val controller = floatController(3f, commitWhileEditing = false)
    controller.onFocusChanged(true)
    controller.type("5")

    controller.onFocusChanged(false)
    assertEquals(listOf<Float?>(5f), updates)
    assertEquals(1, controller.focusLossCommits)

    // The caller keeps 3, and the field reports it again because focusLossCommits changed.
    controller.onValueChanged(3f)
    assertEquals("3", controller.text)
  }
}
