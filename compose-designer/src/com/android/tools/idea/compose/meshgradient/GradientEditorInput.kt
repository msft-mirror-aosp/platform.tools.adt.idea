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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Result of analyzing a gradient call for the [GradientEditorDialog]. It is computed in a background read action and only holds immutable
 * values and [SmartPsiElementPointer]s, so it can be safely handed over to the EDT.
 */
internal sealed interface GradientEditorInput {

  /** The call cannot be edited. [reason] explains why and is shown to the user. */
  data class Unsupported(@NlsContexts.DialogMessage val reason: String) : GradientEditorInput

  /** The call can be opened in the editor. */
  sealed interface Editable : GradientEditorInput {
    /** Releases the [SmartPsiElementPointer]s held by this input. */
    fun releasePointers(project: Project)
  }

  /** A `MeshGradientPainter` call, plus the colors declared in scope that are offered in the editor palette. */
  class Mesh(val mesh: GradientPsiManager.ParsedMesh, val availableColors: List<Color>) : Editable {
    override fun releasePointers(project: Project) = mesh.release(project)
  }

  /** A `Brush` gradient call, plus the colors declared in scope that are offered in the editor palette. */
  class Brush(val brush: ParsedBrush, val availableColors: List<Color>) : Editable {
    override fun releasePointers(project: Project) = brush.release(project)
  }
}

/**
 * A `Brush` gradient factory call as found in the source.
 *
 * @param function the canonical name of the called factory function (e.g. `horizontalGradient`), independently of import aliases.
 * @param gradient the values to load in the editor. Values whose argument could not be evaluated hold the Compose default.
 * @param colors where the colors (or color stops) are defined in the source.
 * @param arguments the explicitly passed arguments other than the colors, keyed by parameter name.
 */
internal class ParsedBrush(
  val callPointer: SmartPsiElementPointer<KtCallExpression>,
  val function: String,
  val gradient: Gradient,
  val colors: ParsedColors,
  val arguments: Map<String, BrushArgument>,
) {
  /** Releases the [SmartPsiElementPointer]s held by this instance. */
  fun release(project: Project) {
    val pointerManager = SmartPointerManager.getInstance(project)
    pointerManager.removePointer(callPointer)
    arguments.values.forEach { pointerManager.removePointer(it.pointer) }
    colors.argument?.let { pointerManager.removePointer(it) }
    colors.varargStops.forEach { pointerManager.removePointer(it) }
  }
}

/**
 * An explicitly passed `Brush` gradient argument. [isParsed] is false when its value could not be statically evaluated, or depends on
 * runtime state such as an animation.
 */
internal class BrushArgument(val pointer: SmartPsiElementPointer<KtValueArgument>, val isParsed: Boolean)

/**
 * Where the colors of a `Brush` gradient are defined.
 *
 * @param isStops whether the colors are given as `colorStops` (pairs of fraction and color) rather than a `colors` list.
 * @param argument the `colors`/`colorStops` argument, or null when the stops are passed as positional vararg arguments.
 * @param varargStops the positional vararg color stop arguments, if any.
 * @param isDirect whether the elements are written at the call site (a `listOf`/`arrayOf` literal or vararg arguments), as opposed to a
 *   reference to a declaration elsewhere.
 * @param elementParsed for each element of the evaluated list, whether its value could be statically evaluated without depending on runtime
 *   state. Empty when the list itself could not be evaluated.
 */
internal class ParsedColors(
  val isStops: Boolean,
  val argument: SmartPsiElementPointer<KtValueArgument>?,
  val varargStops: List<SmartPsiElementPointer<KtValueArgument>>,
  val isDirect: Boolean,
  val elementParsed: List<Boolean>,
) {
  /** Whether every element of the colors could be evaluated. */
  val isFullyParsed: Boolean
    get() = elementParsed.isNotEmpty() && elementParsed.all { it }
}

/** Snapshot of the `Brush` gradient values of a [GradientEditorState], used to compute the edits to write back. */
internal data class BrushValues(
  val type: GradientType,
  val colors: List<Color>,
  val colorStops: List<Pair<Float, Color>>,
  val start: Offset,
  val end: Offset,
  val center: Offset,
  val radius: Float,
  val tileMode: TileMode,
)

/** Snapshot of the mesh values of a [GradientEditorState]. The number of rows and columns is given by the size of [points]. */
internal data class MeshValues(val points: List<List<MeshGradientPoint>>, val hasBicubicColor: Boolean)

/** Outcome of writing the editor values back to the source. */
internal sealed interface GradientWriteResult {
  /** Nothing had to be written. */
  data object Unchanged : GradientWriteResult

  /** The source was updated. */
  data object Written : GradientWriteResult

  /** Nothing was written because the change cannot be applied safely. [message] explains why. */
  data class Failed(@NlsContexts.DialogMessage val message: String) : GradientWriteResult
}
