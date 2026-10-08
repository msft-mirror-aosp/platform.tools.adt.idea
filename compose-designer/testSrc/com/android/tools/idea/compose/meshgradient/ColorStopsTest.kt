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

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ColorStopsTest {
  private fun stops(vararg fractions: Float?): List<ColorStop> = fractions.map { ColorStop(Color.Red, it) }

  private fun List<ColorStop>.fractions() = map { it.fraction }

  @Test
  fun fromParsedPrefersColorStops() {
    val stops = ColorStops.fromParsed(listOf(Color.Green), listOf(0.2f to Color.Red, 0.7f to Color.Blue))
    assertEquals(listOf(Color.Red, Color.Blue), stops.map { it.color })
    assertEquals(listOf(0.2f, 0.7f), stops.fractions())
  }

  @Test
  fun fromParsedPadsToTwoStops() {
    assertEquals(listOf(0.3f, 1f), ColorStops.fromParsed(emptyList(), listOf(0.3f to Color.Red)).fractions())
    assertEquals(listOf(Color.Red, Color.White), ColorStops.fromParsed(listOf(Color.Red), null).map { it.color })
    assertEquals(listOf(Color.Red, Color.Blue), ColorStops.fromParsed(emptyList(), null).map { it.color })
  }

  @Test
  fun insertionPointAppendsWithoutFractions() {
    assertEquals(2 to null, ColorStops.insertionPoint(stops(null, null)))
  }

  @Test
  fun insertionPointUsesMidpointOfWidestGap() {
    assertEquals(1 to 0.5f, ColorStops.insertionPoint(stops(0.2f, 0.8f)))
    assertEquals(1 to 0.5f, ColorStops.insertionPoint(stops(0f, 1f)))
    assertEquals(2 to 0.75f, ColorStops.insertionPoint(stops(0f, 0.5f, 1f)))
    assertEquals(2 to 0.75f, ColorStops.insertionPoint(stops(0.125f, 0.5f)))
    assertEquals(0 to 0.25f, ColorStops.insertionPoint(stops(0.5f, 0.75f, 1f)))
  }

  @Test
  fun insertionPointHandlesUnsortedFractions() {
    assertEquals(2 to 0.5f, ColorStops.insertionPoint(stops(1f, 0f)))
    assertEquals("Inserted after the 0.5 stop bounding the gap", 2 to 0.75f, ColorStops.insertionPoint(stops(1f, 0.5f, 0f)))
  }

  @Test
  fun addColorStopKeepsExistingFractions() {
    val state = GradientEditorState()
    state.loadColorStops(emptyList(), listOf(0f to Color.Red, 0.125f to Color.Green, 1f to Color.Blue))
    state.addColorStop(Color.Yellow)
    assertEquals(listOf(0f, 0.125f, 0.5625f, 1f), state.stops.fractions())
    assertEquals(listOf(Color.Red, Color.Green, Color.Yellow, Color.Blue), state.colors)
  }

  @Test
  fun removeColorStopKeepsOtherFractions() {
    val state = GradientEditorState()
    state.loadColorStops(emptyList(), listOf(0f to Color.Red, 0.1f to Color.Green, 1f to Color.Blue))
    state.removeColorStop(state.stops[1].id)
    assertEquals(listOf(0f to Color.Red, 1f to Color.Blue), state.colorStops)
    state.removeColorStop(state.stops[1].id)
    assertEquals("A gradient keeps at least two stops", 2, state.stops.size)
  }

  @Test
  fun updateStopColorUsesIdentityRatherThanIndex() {
    val state = GradientEditorState()
    state.loadColorStops(listOf(Color.Red, Color.Red, Color.Blue), null)
    val secondId = state.stops[1].id
    state.removeColorStop(state.stops[0].id)
    state.updateStopColor(secondId, Color.Green)
    assertEquals(listOf(Color.Green, Color.Blue), state.colors)
  }

  @Test
  fun updateStopColorOfRemovedStopIsIgnored() {
    val state = GradientEditorState()
    state.loadColorStops(listOf(Color.Red, Color.Green, Color.Blue), null)
    val removedId = state.stops[1].id
    state.removeColorStop(removedId)
    state.updateStopColor(removedId, Color.Yellow)
    assertEquals(listOf(Color.Red, Color.Blue), state.colors)
  }

  @Test
  fun updateFractionMaterializesEvenFractions() {
    val original = stops(null, null, null)
    assertEquals("Unknown stops are ignored", original, ColorStops.updateFraction(original, -1L, 0.2f))
    val result = ColorStops.updateFraction(original, original[1].id, 0.25f)
    assertEquals(listOf(0f, 0.25f, 1f), result.fractions())
    assertEquals(listOf(0f, 1f, 1f), ColorStops.updateFraction(result, result[1].id, 3f).fractions())
  }

  @Test
  fun updateFractionKeepsStopsSortedByFraction() {
    val original = stops(0f, 0.5f, 1f)
    val moved = ColorStops.updateFraction(original, original[0].id, 0.75f)
    assertEquals(listOf(0.5f, 0.75f, 1f), moved.fractions())
    assertEquals("Stops keep their identity", listOf(original[1].id, original[0].id, original[2].id), moved.map { it.id })

    val tied = ColorStops.updateFraction(original, original[2].id, 0.5f)
    assertEquals("Equal fractions keep their order", listOf(original[0].id, original[1].id, original[2].id), tied.map { it.id })
  }

  @Test
  fun loadedStopsKeepTheirOrder() {
    val state = GradientEditorState()
    state.loadColorStops(emptyList(), listOf(1f to Color.Red, 0f to Color.Blue))
    assertEquals(listOf(1f to Color.Red, 0f to Color.Blue), state.colorStops)
  }

  @Test
  fun explicitFractionsToggle() {
    val evenly = ColorStops.withExplicitFractions(stops(null, null, null), true)
    assertEquals(listOf(0f, 0.5f, 1f), evenly.fractions())
    assertTrue(ColorStops.hasExplicitFractions(evenly))
    val custom = stops(0f, 0.1f, 1f)
    assertEquals(custom, ColorStops.withExplicitFractions(custom, true))
    val plain = ColorStops.withExplicitFractions(custom, false)
    assertEquals(listOf(null, null, null), plain.fractions())
    assertFalse(ColorStops.hasExplicitFractions(plain))
  }

  @Test
  fun stateExposesColorsAndColorStopsViews() {
    val state = GradientEditorState()
    state.loadColorStops(listOf(Color.Red, Color.Blue), null)
    assertEquals(listOf(Color.Red, Color.Blue), state.colors)
    assertEquals(emptyList<Pair<Float, Color>>(), state.colorStops)
    state.setExplicitFractions(true)
    assertEquals(listOf(0f to Color.Red, 1f to Color.Blue), state.colorStops)
  }
}
