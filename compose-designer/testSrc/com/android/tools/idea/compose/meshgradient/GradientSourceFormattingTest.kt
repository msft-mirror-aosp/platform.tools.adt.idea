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

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GradientSourceFormattingTest {
  @Test
  fun floatSourceIsAValidKotlinLiteral() {
    assertEquals("0.5f", generateFloatSource(0.5f))
    assertEquals("1f", generateFloatSource(1f))
    assertEquals("-0.25f", generateFloatSource(-0.25f))
    assertEquals("1234.5679f", generateFloatSource(1234.56789f))
    assertEquals("10000000000f", generateFloatSource(1e10f))
    assertEquals("0f", generateFloatSource(-0f))
    assertEquals("0f", generateFloatSource(0.00001f))
  }

  @Test
  fun nonFiniteFloatSourceUsesTheFloatConstants() {
    assertEquals("Float.NaN", generateFloatSource(Float.NaN))
    assertEquals("Float.POSITIVE_INFINITY", generateFloatSource(Float.POSITIVE_INFINITY))
    assertEquals("Float.NEGATIVE_INFINITY", generateFloatSource(Float.NEGATIVE_INFINITY))
  }
}
