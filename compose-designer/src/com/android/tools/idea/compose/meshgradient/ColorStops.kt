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
import java.util.concurrent.atomic.AtomicLong

private val colorStopIds = AtomicLong()

/**
 * A color of a standard `Brush` gradient.
 *
 * @property color the color of the stop.
 * @property fraction the position of the stop in `0..1`, or `null` when the gradient is declared with a plain `colors` list, in which case
 *   Compose distributes the colors evenly. Within one gradient either every stop has a fraction or none has.
 * @property id stable identity of the stop, used to address it from asynchronous callbacks (e.g. a color picker) regardless of its current
 *   index in the list.
 */
internal data class ColorStop(val color: Color, val fraction: Float? = null, val id: Long = colorStopIds.incrementAndGet())

/** Pure operations on the color stops of a standard `Brush` gradient. */
internal object ColorStops {
  /** Compose requires at least two colors for a gradient. */
  const val MIN_STOPS = 2

  /** Returns the stops of a new gradient. Each call creates stops with new ids. */
  fun defaultStops(): List<ColorStop> = listOf(ColorStop(Color.Red), ColorStop(Color.Blue))

  /** Returns whether the stops are declared with explicit fractions (`colorStops`) rather than a plain `colors` list. */
  fun hasExplicitFractions(stops: List<ColorStop>): Boolean = stops.isNotEmpty() && stops.all { it.fraction != null }

  /**
   * Builds the stops of a parsed gradient. Explicit [colorStops] take precedence over [colors]. The result is padded with white to
   * [MIN_STOPS] stops, and falls back to [defaultStops] when nothing was parsed.
   */
  fun fromParsed(colors: List<Color>, colorStops: List<Pair<Float, Color>>?): List<ColorStop> {
    if (!colorStops.isNullOrEmpty()) {
      val stops = colorStops.map { (fraction, color) -> ColorStop(color, fraction) }
      return if (stops.size >= MIN_STOPS) stops else stops + ColorStop(Color.White, 1f)
    }
    return when {
      colors.size >= MIN_STOPS -> colors.map { ColorStop(it) }
      colors.size == 1 -> listOf(ColorStop(colors[0]), ColorStop(Color.White))
      else -> defaultStops()
    }
  }

  /**
   * Returns the index at which a new stop is inserted and its fraction.
   *
   * Without explicit fractions the new stop is appended and has no fraction. With explicit fractions, the new stop takes the midpoint of
   * the widest gap between adjacent fractions (including the gaps to 0 and 1, preferring the last one on ties) and is inserted next to the
   * stops bounding that gap, so no existing fraction changes.
   */
  fun insertionPoint(stops: List<ColorStop>): Pair<Int, Float?> {
    if (!hasExplicitFractions(stops)) return stops.size to null
    val sorted = stops.withIndex().sortedBy { it.value.fraction }
    var bestGap = -1f
    var bestIndex = stops.size
    var bestFraction = 1f
    var previousFraction = 0f
    var previousIndex = -1
    for ((index, stop) in sorted) {
      val fraction = stop.fraction ?: continue
      val gap = fraction - previousFraction
      if (gap >= bestGap) {
        bestGap = gap
        bestFraction = (previousFraction + fraction) / 2
        bestIndex = if (previousIndex < 0) index else previousIndex + 1
      }
      previousFraction = fraction
      previousIndex = index
    }
    if (1f - previousFraction >= bestGap) {
      bestFraction = (previousFraction + 1f) / 2
      bestIndex = previousIndex + 1
    }
    return bestIndex to bestFraction
  }

  /** Returns [stops] with the stop identified by [id] removed, unless that would leave fewer than [MIN_STOPS] stops. */
  fun remove(stops: List<ColorStop>, id: Long): List<ColorStop> = if (stops.size <= MIN_STOPS) stops else stops.filterNot { it.id == id }

  /** Returns [stops] with the color of the stop identified by [id] replaced by [color]. */
  fun updateColor(stops: List<ColorStop>, id: Long, color: Color): List<ColorStop> = stops.map {
    if (it.id == id) it.copy(color = color) else it
  }

  /**
   * Returns [stops] with the fraction of the stop identified by [id] set to [fraction] (coerced to `0..1`), sorted by fraction so the list
   * order matches the rendered order. The sort is stable: stops with equal fractions keep their relative order. Stops without explicit
   * fractions first get the evenly distributed fractions Compose uses for them, so the other stops keep their rendered position.
   */
  fun updateFraction(stops: List<ColorStop>, id: Long, fraction: Float): List<ColorStop> {
    if (stops.none { it.id == id }) return stops
    return withExplicitFractions(stops, true)
      .map { if (it.id == id) it.copy(fraction = fraction.coerceIn(0f, 1f)) else it }
      .sortedBy { it.fraction }
  }

  /**
   * Switches between explicit fractions and a plain color list. Enabling explicit fractions assigns the evenly distributed fractions
   * Compose uses for a plain color list (so the rendering does not change) to the stops without one. Disabling them drops all fractions.
   */
  fun withExplicitFractions(stops: List<ColorStop>, explicit: Boolean): List<ColorStop> {
    if (!explicit) return stops.map { it.copy(fraction = null) }
    if (hasExplicitFractions(stops)) return stops
    val last = (stops.size - 1).coerceAtLeast(1)
    return stops.mapIndexed { index, stop -> stop.copy(fraction = stop.fraction ?: (index.toFloat() / last)) }
  }
}
