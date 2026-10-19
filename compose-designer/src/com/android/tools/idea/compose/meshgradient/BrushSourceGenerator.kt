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
import androidx.compose.ui.graphics.TileMode

private const val INDENT = "    "

/**
 * Generates the Kotlin source of a standard `Brush` gradient shown in the playground.
 *
 * Values are emitted exactly as stored: pixels, with `Offset.Infinite`, `Offset.Unspecified` and `Float.POSITIVE_INFINITY` kept as such.
 * Plain colors are passed as `colors = listOf(...)`. Explicit color stops are passed as positional `fraction to color` arguments, since
 * `colorStops` is a vararg parameter that cannot take an array without the spread operator.
 *
 * Editing an existing call is done by [GradientSourceWriter] instead, which only rewrites the changed arguments. The output assumes
 * `Brush`, `Color`, `Offset` and `TileMode` are imported.
 */
internal object BrushSourceGenerator {

  /** Returns the `Brush.*Gradient(...)` expression for [gradient]. */
  fun generate(gradient: Gradient): String =
    when (gradient) {
      is Gradient.LinearGradient ->
        generateCall(
          FUN_LINEAR_GRADIENT,
          gradient.colors,
          gradient.colorStops,
          listOf(
            "start = ${generateOffsetSource(gradient.start)}",
            "end = ${generateOffsetSource(gradient.end)}",
            "tileMode = TileMode.${tileModeName(gradient.tileMode)}",
          ),
        )
      is Gradient.RadialGradient ->
        generateCall(
          FUN_RADIAL_GRADIENT,
          gradient.colors,
          gradient.colorStops,
          listOf(
            "center = ${generateOffsetSource(gradient.center)}",
            "radius = ${generateFloatSource(gradient.radius)}",
            "tileMode = TileMode.${tileModeName(gradient.tileMode)}",
          ),
        )
      is Gradient.SweepGradient ->
        generateCall(
          FUN_SWEEP_GRADIENT,
          gradient.colors,
          gradient.colorStops,
          listOf("center = ${generateOffsetSource(gradient.center)}"),
        )
    }

  private fun generateColorSource(color: Color): String = "Color(${color.toComposeHexLiteral()})"

  private fun generateCall(
    functionName: String,
    colors: List<Color>,
    colorStops: List<Pair<Float, Color>>?,
    namedArguments: List<String>,
  ): String {
    val colorArguments =
      if (colorStops != null) {
        colorStops.map { (fraction, color) -> "${generateFloatSource(fraction)} to ${generateColorSource(color)}" }
      } else {
        listOf("colors = listOf(${colors.joinToString { generateColorSource(it) }})")
      }
    return (colorArguments + namedArguments).joinToString(separator = ",\n", prefix = "Brush.$functionName(\n", postfix = ",\n)") {
      "$INDENT$it"
    }
  }
}

/**
 * Returns the name of the `TileMode` constant [tileMode], e.g. `Clamp`.
 *
 * `TileMode` cannot be instantiated outside of Compose, so it can only hold one of the four constants; any other value is a programming
 * error and throws an [IllegalArgumentException] rather than writing a wrong mode.
 */
internal fun tileModeName(tileMode: TileMode): String =
  when (tileMode) {
    TileMode.Clamp -> "Clamp"
    TileMode.Repeated -> "Repeated"
    TileMode.Mirror -> "Mirror"
    TileMode.Decal -> "Decal"
    else -> throw IllegalArgumentException("Unsupported tile mode: $tileMode")
  }
