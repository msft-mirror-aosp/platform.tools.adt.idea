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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NumericFormatTest {

  @Test
  fun intFormatParsesAndCoerces() {
    val format = intFormat(min = 2, max = 10)
    assertEquals(5, format.parse("5"))
    assertEquals(5, format.parse(" 5 "))
    assertEquals(10, format.parse("42"))
    assertEquals(2, format.parse("0"))
    assertNull(format.parse(""))
    assertNull(format.parse("1.5"))
    assertNull(format.parse("99999999999"))
  }

  @Test
  fun intFormatOnlyAcceptsMinusWhenNegativeValuesAreAllowed() {
    assertFalse(intFormat(min = 0).accepts("-1"))
    assertTrue(intFormat(min = -5).accepts("-1"))
    assertTrue(intFormat().accepts("-1"))
    assertFalse(intFormat().accepts("1a"))
    assertFalse("Only ASCII digits are accepted", intFormat().accepts("\u0663"))
  }

  @Test
  fun intFormatCommitBehavesLikeDimensionField() {
    val format = intFormat(min = 2, max = 10)
    assertEquals(NumericCommit.Changed(7), format.commit("7", 3, allowAuto = false))
    assertEquals(NumericCommit.Changed(10), format.commit("12", 3, allowAuto = false))
    assertEquals(NumericCommit.Unchanged, format.commit("3", 3, allowAuto = false))
    assertEquals(NumericCommit.Unchanged, format.commit("1", 2, allowAuto = false))
    assertEquals(NumericCommit.Invalid, format.commit("", 3, allowAuto = false))
    assertEquals(NumericCommit.Invalid, format.commit("-", 3, allowAuto = false))
  }

  @Test
  fun floatFormatAcceptsCommaAsDecimalSeparator() {
    val format = floatFormat()
    assertEquals(1.5f, format.parse("1,5"))
    assertEquals(-2.25f, format.parse("-2.25"))
    assertNull(format.parse("1.2.3"))
    assertNull(format.parse("1,000.5"))
    assertTrue(format.accepts("1,5"))
    assertFalse(format.accepts("1e5"))
    assertFalse(format.accepts("NaN"))
  }

  @Test
  fun floatFormatCoercesToRange() {
    val format = floatFormat(min = 0f, max = 1f)
    assertEquals(1f, format.parse("3"))
    assertEquals(0.25f, format.parse("0.25"))
    assertFalse(format.accepts("-1"))
  }

  @Test
  fun floatFormatComparesFormattedValues() {
    val format = floatFormat()
    assertEquals("0.3333", format.format(0.33333334f))
    assertEquals("400", format.format(400f))
    assertEquals(NumericCommit.Unchanged, format.commit("0.3333", 0.33333334f, allowAuto = false))
    assertEquals(NumericCommit.Unchanged, format.commit("400.00", 400f, allowAuto = false))
    assertEquals(NumericCommit.Changed(0.5f), format.commit("0,5", 0.33333334f, allowAuto = false))
  }

  @Test
  fun commitOfUntouchedOutOfRangeValueIsUnchanged() {
    val format = floatFormat(min = 0.01f)
    assertEquals(NumericCommit.Unchanged, format.commit(format.format(0f), 0f, allowAuto = false))
  }

  @Test
  fun blankTextCommitsAutomaticValueOnlyWhenAllowed() {
    val format = floatFormat()
    assertEquals("", format.format(null))
    assertEquals(NumericCommit.Changed<Float>(null), format.commit("", 10f, allowAuto = true))
    assertEquals(NumericCommit.Invalid, format.commit("", 10f, allowAuto = false))
    assertEquals(NumericCommit.Unchanged, format.commit(" ", null, allowAuto = true))
    assertEquals(NumericCommit.Changed(12f), format.commit("12", null, allowAuto = true))
  }

  @Test
  fun editCommitOnlyCommitsNewValuesInRange() {
    val format = floatFormat(min = 0f, max = 1f)
    assertEquals(0.5f, format.editCommit("0.5", 0.25f))
    assertEquals(0.5f, format.editCommit("0,5", null))
    assertNull("Values that would be coerced wait for the end of the edit", format.editCommit("3", 0.25f))
    assertNull("Blank text waits for the end of the edit", format.editCommit("", 0.25f))
    assertNull(format.editCommit("0.", 0f))
    assertNull(format.editCommit("0.25", 0.25f))
    assertNull(format.editCommit(".", 0.25f))
  }
}
