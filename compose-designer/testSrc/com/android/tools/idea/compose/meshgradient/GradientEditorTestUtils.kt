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

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertTrue

/** Finds the first `MeshGradientPainter` call in this file. */
internal fun KtFile.findMeshPainterCall(): KtCallExpression? =
  SyntaxTraverser.psiTraverser(this).filter(KtCallExpression::class.java).firstOrNull { it.isValidMeshGradientCall() }

/** Finds the first call in this file whose callee is written as [calleeText]. */
internal fun PsiFile.findCall(calleeText: String): KtCallExpression = runReadAction {
  PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java).first { it.calleeExpression?.text == calleeText }
}

/** Analyzes [call] as [GradientEditorDialog.analyzeAndShow] does, and creates the editor dialog without showing it. */
internal fun createGradientEditorDialog(project: Project, call: KtCallExpression): GradientEditorDialog {
  val input = runReadAction { GradientPsiManager(project).analyze(call) }
  check(input is GradientEditorInput.Editable) { "The call is not editable: $input" }
  return GradientEditorDialog(project, input)
}

/** Asserts that [file] has no errors, e.g. that the code written by the editor compiles. Requires [addComposeGraphicsStubs]. */
internal fun CodeInsightTestFixture.assertNoErrors(file: PsiFile) {
  configureFromExistingVirtualFile(file.virtualFile)
  val errors = doHighlighting(HighlightSeverity.ERROR)
  assertTrue("Unexpected errors in:\n${file.text}\n${errors.joinToString("\n") { it.description }}", errors.isEmpty())
}

/** Runs [block] with an editor dialog for [call], and closes the dialog afterwards. */
internal fun <T> withGradientEditorDialog(project: Project, call: KtCallExpression, block: (GradientEditorDialog) -> T): T {
  val dialog = createGradientEditorDialog(project, call)
  try {
    return block(dialog)
  } finally {
    if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
  }
}

/**
 * Adds minimal stubs of the Compose geometry, graphics and animation APIs used by gradients, so that references to them can be resolved and
 * the code written by the editor can be checked for errors.
 */
internal fun CodeInsightTestFixture.addComposeGraphicsStubs() {
  addFileToProject(
    "src/androidx/compose/ui/geometry/Offset.kt",
    // language=kotlin
    """
    package androidx.compose.ui.geometry

    class Offset(val x: Float, val y: Float) {
      companion object {
        val Zero = Offset(0f, 0f)
        val Infinite = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val Unspecified = Offset(Float.NaN, Float.NaN)
      }
    }
    """
      .trimIndent(),
  )
  addFileToProject(
    "src/androidx/compose/ui/graphics/Graphics.kt",
    // language=kotlin
    """
    package androidx.compose.ui.graphics

    import androidx.compose.ui.geometry.Offset

    class Color(val value: Long) {
      companion object {
        val Red = Color(0xFFFF0000)
        val Green = Color(0xFF00FF00)
        val Blue = Color(0xFF0000FF)
        val Yellow = Color(0xFFFFFF00)
        val Magenta = Color(0xFFFF00FF)
        val Cyan = Color(0xFF00FFFF)
        val White = Color(0xFFFFFFFF)
        val Black = Color(0xFF000000)
      }
    }

    enum class TileMode { Clamp, Repeated, Mirror, Decal }

    class Brush {
      companion object {
        fun linearGradient(colors: List<Color>, start: Offset = Offset.Zero, end: Offset = Offset.Infinite, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun linearGradient(vararg colorStops: Pair<Float, Color>, start: Offset = Offset.Zero, end: Offset = Offset.Infinite, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun horizontalGradient(colors: List<Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun horizontalGradient(vararg colorStops: Pair<Float, Color>, startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun verticalGradient(colors: List<Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun verticalGradient(vararg colorStops: Pair<Float, Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun radialGradient(colors: List<Color>, center: Offset = Offset.Unspecified, radius: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun radialGradient(vararg colorStops: Pair<Float, Color>, center: Offset = Offset.Unspecified, radius: Float = Float.POSITIVE_INFINITY, tileMode: TileMode = TileMode.Clamp) = Brush()
        fun sweepGradient(colors: List<Color>, center: Offset = Offset.Unspecified) = Brush()
        fun sweepGradient(vararg colorStops: Pair<Float, Color>, center: Offset = Offset.Unspecified) = Brush()
      }
    }

    class MeshScope {
      fun setVertex(
        row: Int,
        column: Int,
        position: Offset,
        color: Color,
        leftControlPoint: Offset = Offset.Unspecified,
        topControlPoint: Offset = Offset.Unspecified,
        rightControlPoint: Offset = Offset.Unspecified,
        bottomControlPoint: Offset = Offset.Unspecified,
      ) {}
    }

    class MeshGradientPainter(rows: Int, columns: Int, hasBicubicColor: Boolean = false, block: MeshScope.() -> Unit)
    """
      .trimIndent(),
  )
  addFileToProject(
    "src/androidx/compose/animation/core/Animation.kt",
    // language=kotlin
    """
    package androidx.compose.animation.core

    import kotlin.reflect.KProperty

    class State<T>(val value: T)

    operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

    fun animateFloatAsState(targetValue: Float): State<Float> = State(targetValue)
    """
      .trimIndent(),
  )
}

/**
 * Adds the declarations, in package `test`, of functions whose values the editor cannot evaluate, and a `test.colors.red` property, so that
 * test sources using them compile. Requires [addComposeGraphicsStubs].
 */
internal fun CodeInsightTestFixture.addGradientTestHelpers() {
  addFileToProject(
    "src/test/Helpers.kt",
    // language=kotlin
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun themeColor(): Color = Color.Red
    fun themeColors(): List<Color> = listOf(Color.Red, Color.Blue)
    fun startOffset(): Offset = Offset.Zero
    fun controlPoint(): Offset = Offset.Zero
    fun axisStart(): Float = 0f
    """
      .trimIndent(),
  )
  addFileToProject(
    "src/test/colors/Colors.kt",
    "package test.colors\n\nimport androidx.compose.ui.graphics.Color\n\nval red = Color(0xFFFF0000)\n",
  )
}
