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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import java.io.IOException
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class GradientBrushWriteBackTest {
  private val projectRule = AndroidProjectRule.inMemory().withKotlin()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule())

  private val project
    get() = projectRule.project

  @Before
  fun setUp() {
    projectRule.fixture.addComposeGraphicsStubs()
    projectRule.fixture.addGradientTestHelpers()
  }

  /** Adds a file declaring `val brush = <[brush]>`, and returns it. */
  private fun addFile(brush: String, imports: String = DEFAULT_IMPORTS): KtFile =
    projectRule.fixture.addFileToProject("src/test/Brushes.kt", "package test\n\n$imports\n\nval brush = $brush\n") as KtFile

  private fun KtFile.currentText(): String = runReadAction { text }

  /** The `Brush` expression assigned to `brush` in this file. */
  private fun KtFile.brushText(): String = currentText().substringAfter("val brush = ").trimEnd()

  /**
   * Opens the editor on the [function] call in [file], applies [edit] and writes the changes back. When the source is written, checks that
   * it still compiles.
   */
  private fun edit(file: KtFile, function: String, edit: GradientEditorState.() -> Unit): GradientWriteResult {
    val result =
      withGradientEditorDialog(project, file.findCall(function)) { dialog ->
        dialog.state.edit()
        dialog.applyChanges()
      }
    if (result == GradientWriteResult.Written) projectRule.fixture.assertNoErrors(file)
    return result
  }

  /** Checks that applying [edit] to the [function] call in [file] fails with [message], leaving the source untouched. */
  private fun assertEditFails(file: KtFile, function: String, message: String, edit: GradientEditorState.() -> Unit) {
    val originalText = file.currentText()

    val result = edit(file, function, edit)

    assertEquals(GradientWriteResult.Failed(message), result)
    assertEquals(originalText, file.currentText())
  }

  @Test
  fun paletteOffersTheColorsDeclaredInScope() {
    val file =
      projectRule.fixture.addFileToProject(
        "src/test/Brushes.kt",
        "package test\n\n$DEFAULT_IMPORTS\n\nval Accent = Color(0xFFFF4081)\n\nval brush = Brush.linearGradient(listOf(Color.Red, Color.Blue))\n",
      ) as KtFile

    withGradientEditorDialog(project, file.findCall("linearGradient")) { dialog ->
      assertTrue("Should include Accent in the palette", Color(0xFFFF4081) in dialog.state.availableColors)
    }
  }

  @Test
  fun okWithoutEditsLeavesTheSourceUntouched() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red,Color.Blue),  start = Offset.Zero)")
    val originalText = file.currentText()

    withGradientEditorDialog(project, file.findCall("linearGradient")) { dialog ->
      assertEquals(GradientWriteResult.Unchanged, dialog.applyChanges())
      dialog.performOkAction()
      assertTrue("The dialog should close", dialog.isDisposed)
    }

    assertEquals(originalText, file.currentText())
  }

  @Test
  fun unparsedArgumentsAreKeptWhenOtherValuesChange() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, themeColor()), start = startOffset(), end = Offset(1f, 1f))")

    val result = edit(file, "linearGradient") { end = Offset(2f, 3f) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.linearGradient(colors = listOf(Color.Red, themeColor()), start = startOffset(), end = Offset(2f, 3f))",
      file.brushText(),
    )
  }

  @Test
  fun changingUnparsedValuesFailsWithoutWriting() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, themeColor()), start = startOffset(), end = Offset(1f, 1f))")
    val originalText = file.currentText()

    withGradientEditorDialog(project, file.findCall("linearGradient")) { dialog ->
      val initialStart = dialog.state.start
      dialog.state.end = Offset(2f, 3f)
      dialog.state.start = Offset(0.5f, 0.5f)
      dialog.performOkAction()
      assertFalse("The dialog should stay open to show the error", dialog.isDisposed)

      dialog.state.start = initialStart
      dialog.state.updateStopColor(dialog.state.stops[1].id, Color.Green)
      assertEquals(GradientWriteResult.Failed(message("gradient.editor.error.argument.unresolved", ARG_COLORS)), dialog.applyChanges())
    }

    assertEquals(originalText, file.currentText())
  }

  @Test
  fun brushWithoutAnyEvaluableColorIsNotEditable() {
    val file = addFile("Brush.radialGradient(colors = themeColors(), radius = 10f)")

    val input = runReadAction { GradientPsiManager(project).analyze(file.findCall("radialGradient")) }

    assertEquals(GradientEditorInput.Unsupported(message("gradient.editor.error.brush.colors")), input)
  }

  @Test
  fun animatedArgumentIsNotOverwritten() {
    val file =
      projectRule.fixture.addFileToProject(
        "src/test/AnimatedBrush.kt",
        // language=kotlin
        """
        package test

        import androidx.compose.animation.core.animateFloatAsState
        import androidx.compose.animation.core.getValue
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.TileMode

        fun animatedBrush(): Brush {
            val animatedRadius by animateFloatAsState(100f)
            return Brush.radialGradient(listOf(Color.Red, Color.Blue), radius = animatedRadius)
        }
        """
          .trimIndent(),
      ) as KtFile

    assertEditFails(file, "radialGradient", message("gradient.editor.error.argument.unresolved", ARG_RADIUS)) { radius = 50f }

    assertEquals(GradientWriteResult.Written, edit(file, "radialGradient") { tileMode = TileMode.Mirror })
    assertTrue(
      file.currentText(),
      file
        .currentText()
        .contains("Brush.radialGradient(listOf(Color.Red, Color.Blue), radius = animatedRadius, tileMode = TileMode.Mirror)"),
    )
  }

  @Test
  fun readOnlyFileIsReportedAndTheDialogStaysOpen() {
    val file = addFile("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue))")
    val originalText = file.currentText()
    val virtualFile = file.virtualFile
    WriteAction.runAndWait<IOException> { virtualFile.isWritable = false }
    try {
      withGradientEditorDialog(project, file.findCall("radialGradient")) { dialog ->
        dialog.state.tileMode = TileMode.Decal
        assertEquals(GradientWriteResult.Failed(message("gradient.editor.error.readonly")), dialog.applyChanges())
        dialog.performOkAction()
        assertFalse("The dialog should stay open to show the error", dialog.isDisposed)
      }
    } finally {
      WriteAction.runAndWait<IOException> { virtualFile.isWritable = true }
    }

    assertEquals(originalText, file.currentText())
  }

  @Test
  fun onlyTheChangedColorIsReplaced() {
    val file = addFile("Brush.radialGradient(listOf(Color.Red, red, Color.Blue), radius = 10f)")

    val result = edit(file, "radialGradient") { updateStopColor(stops[2].id, Color(0xFF123456)) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(listOf(Color.Red, red, Color(0xFF123456)), radius = 10f)", file.brushText())
  }

  @Test
  fun editingThePlaceholderOfASingleColorListAddsTheColor() {
    val file = addFile("Brush.radialGradient(listOf(Color.Red), radius = 10f)")

    val result = edit(file, "radialGradient") { updateStopColor(stops[1].id, Color(0xFF123456)) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(listOf(Color.Red, Color(0xFF123456)), radius = 10f)", file.brushText())
  }

  @Test
  fun untouchedDefaultsAreNotWritten() {
    val file = addFile("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue))")

    val result = edit(file, "radialGradient") { tileMode = TileMode.Decal }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue), tileMode = TileMode.Decal)", file.brushText())
  }

  @Test
  fun argumentIsAddedAfterATrailingComma() {
    val file = addFile("Brush.radialGradient(\n    colors = listOf(Color.Red, Color.Blue),\n)")

    val result = edit(file, "radialGradient") { tileMode = TileMode.Decal }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(\n    colors = listOf(Color.Red, Color.Blue),\n    tileMode = TileMode.Decal,\n)", file.brushText())
  }

  @Test
  fun typeSwitchKeepsTheColorsAndSharedArguments() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(1f, 2f), tileMode = TileMode.Mirror)")

    val result = edit(file, "linearGradient") { currentType = GradientType.RADIAL }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue), tileMode = TileMode.Mirror)", file.brushText())
  }

  @Test
  fun typeSwitchDropsPositionalArgumentsOfTheOldFunction() {
    val file = addFile("Brush.linearGradient(listOf(Color.Red, Color.Blue), Offset(1f, 2f), Offset(3f, 4f))")

    val result = edit(file, "linearGradient") { currentType = GradientType.SWEEP }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.sweepGradient(listOf(Color.Red, Color.Blue))", file.brushText())
  }

  @Test
  fun userWrittenQualifiedNamesAreKept() {
    val file =
      addFile(
        "Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), tileMode = androidx.compose.ui.graphics.TileMode.Mirror)",
        imports = "import androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color",
      )

    val result =
      edit(file, "linearGradient") {
        currentType = GradientType.RADIAL
        center = Offset(1f, 2f)
      }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.radialGradient(colors = listOf(Color.Red, Color.Blue), center = Offset(1f, 2f), " +
        "tileMode = androidx.compose.ui.graphics.TileMode.Mirror)",
      file.brushText(),
    )
    assertTrue(file.currentText(), file.currentText().contains("import androidx.compose.ui.geometry.Offset\n"))
    assertFalse(file.currentText(), file.currentText().contains("import androidx.compose.ui.graphics.TileMode"))
  }

  @Test
  fun commentsThatWouldBeLostPreventTheChange() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), /* mirrored */ tileMode = TileMode.Mirror)")

    assertEditFails(file, "linearGradient", message("gradient.editor.error.comments")) { currentType = GradientType.RADIAL }
  }

  @Test
  fun commentsInKeptArgumentsDoNotPreventTheChange() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, /* accent */ Color.Blue), tileMode = TileMode.Mirror)")

    val result = edit(file, "linearGradient") { currentType = GradientType.RADIAL }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(colors = listOf(Color.Red, /* accent */ Color.Blue), tileMode = TileMode.Mirror)", file.brushText())
  }

  @Test
  fun horizontalGradientIsPreserved() {
    val file = addFile("Brush.horizontalGradient(listOf(Color.Red, Color.Blue), startX = 10f, endX = 100f)")

    val result = edit(file, "horizontalGradient") { end = Offset(200f, 0f) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.horizontalGradient(listOf(Color.Red, Color.Blue), startX = 10f, endX = 200f)", file.brushText())
  }

  @Test
  fun horizontalGradientMovedVerticallyBecomesLinear() {
    val file = addFile("Brush.horizontalGradient(listOf(Color.Red, Color.Blue), startX = 10.0f, endX = 100.00f)")

    val result = edit(file, "horizontalGradient") { end = Offset(100f, 50f) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset(10.0f, 0f), end = Offset(100.00f, 50f))",
      file.brushText(),
    )
  }

  @Test
  fun verticalGradientMovedHorizontallyBecomesLinear() {
    val file = addFile("Brush.verticalGradient(listOf(Color.Red, Color.Blue), startY = 5.0f)")

    val result = edit(file, "verticalGradient") { start = Offset(20f, 5f) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset(20f, 5.0f), end = Offset(0f, Float.POSITIVE_INFINITY))",
      file.brushText(),
    )
  }

  @Test
  fun horizontalGradientWithUnparsedAxisIsNotConverted() {
    val file = addFile("Brush.horizontalGradient(listOf(Color.Red, Color.Blue), startX = axisStart(), endX = 100f)")

    assertEditFails(file, "horizontalGradient", message("gradient.editor.error.argument.unresolved", ARG_START_X)) {
      end = Offset(100f, 50f)
    }
  }

  @Test
  fun addingAVarargColorStopKeepsTheCallCompilable() {
    val file = addFile("Brush.linearGradient(0f to Color.Red, 1f to Color.Blue, start = Offset.Zero)")

    val result = edit(file, "linearGradient") { addColorStop(Color.Blue) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.linearGradient(0f to Color.Red, 0.5f to Color(0xFF0000FF), 1f to Color.Blue, start = Offset.Zero)",
      file.brushText(),
    )
  }

  @Test
  fun removingAVarargColorStopKeepsTheOtherStops() {
    val file = addFile("Brush.linearGradient(0f to Color.Red, 0.5f to Color.Green, 1f to Color.Blue, start = Offset.Zero)")

    val result = edit(file, "linearGradient") { removeColorStop(stops[1].id) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.linearGradient(0f to Color.Red, 1f to Color.Blue, start = Offset.Zero)", file.brushText())
  }

  @Test
  fun addingANamedColorStopKeepsTheSpreadOperator() {
    val file = addFile("Brush.sweepGradient(colorStops = *arrayOf(0f to Color.Red, 1f to Color.Blue))")

    val result = edit(file, "sweepGradient") { addColorStop(Color.Blue) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.sweepGradient(colorStops = *arrayOf(0f to Color.Red, 0.5f to Color(0xFF0000FF), 1f to Color.Blue))",
      file.brushText(),
    )
  }

  @Test
  fun rebuiltArgumentListKeepsItsLayout() {
    val file = addFile("Brush.linearGradient(\n    0f to Color.Red,\n    1f to Color.Blue,\n)")

    val result = edit(file, "linearGradient") { addColorStop(Color.Blue) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.linearGradient(\n    0f to Color.Red,\n    0.5f to Color(0xFF0000FF),\n    1f to Color.Blue,\n)", file.brushText())
  }

  @Test
  fun enablingExplicitStopsWritesTheColorsAsStops() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(1f, 2f))")

    val result = edit(file, "linearGradient") { setExplicitFractions(true) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.linearGradient(0f to Color(0xFFFF0000), 1f to Color(0xFF0000FF), start = Offset(1f, 2f))", file.brushText())
  }

  @Test
  fun disablingExplicitStopsWritesAColorList() {
    val file = addFile("Brush.linearGradient(0f to Color.Red, 1f to Color.Blue, start = Offset.Zero)")

    val result = edit(file, "linearGradient") { setExplicitFractions(false) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.linearGradient(colors = listOf(Color(0xFFFF0000), Color(0xFF0000FF)), start = Offset.Zero)", file.brushText())
  }

  @Test
  fun stopMovedPastItsNeighbourIsWrittenInRenderedOrder() {
    val file = addFile("Brush.linearGradient(0f to Color.Red, 0.5f to Color.Green, 1f to Color.Blue)")

    val result = edit(file, "linearGradient") { updateStopFraction(stops[0].id, 0.75f) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.linearGradient(0.5f to Color(0xFF00FF00), 0.75f to Color(0xFFFF0000), 1f to Color.Blue)", file.brushText())
  }

  @Test
  fun editedPixelCoordinateAndStopAreTheOnlyChanges() {
    val file = addFile("Brush.linearGradient(0f to Color.Red, 1f to Color.Blue, start = Offset(10f, 20f), end = Offset(300f, 150f))")

    withGradientEditorDialog(project, file.findCall("linearGradient")) { dialog ->
      assertEquals(Size(300f, 150f), dialog.state.previewSize)
      dialog.state.start = Offset(25.5f, 20f)
      dialog.state.updateStopColor(dialog.state.stops[1].id, Color.Green)
      dialog.performOkAction()
      assertTrue("The dialog should close", dialog.isDisposed)
    }

    assertEquals(
      "Brush.linearGradient(0f to Color.Red, 1f to Color(0xFF00FF00), start = Offset(25.5f, 20f), end = Offset(300f, 150f))",
      file.brushText(),
    )
    projectRule.fixture.assertNoErrors(file)
  }

  @Test
  fun defaultGeometryIsNotWrittenWhenOnlyAStopChanges() {
    val file = addFile("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue))")

    val result = edit(file, "radialGradient") { updateStopColor(stops[0].id, Color.Green) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals("Brush.radialGradient(colors = listOf(Color(0xFF00FF00), Color.Blue))", file.brushText())
  }

  @Test
  fun switchingTypeAndBackKeepsThePixelGeometry() {
    val file = addFile("Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(10f, 20f), end = Offset(300f, 150f))")
    val originalText = file.currentText()

    val result =
      edit(file, "linearGradient") {
        val loadedPreviewSize = previewSize
        currentType = GradientType.RADIAL
        assertEquals("Switching type does not refit the preview", loadedPreviewSize, previewSize)
        currentType = GradientType.LINEAR
      }

    assertEquals(GradientWriteResult.Unchanged, result)
    assertEquals(originalText, file.currentText())
  }

  @Test
  fun linearGradientValuesAreWritten() {
    val file =
      addFile(
        "Brush.linearGradient(\n    colors = listOf(Color.Red, Color.Blue),\n    start = Offset(0.0f, 0.0f),\n    end = Offset(1.0f, 1.0f),\n" +
          "    tileMode = TileMode.Mirror\n)"
      )

    val result =
      edit(file, "linearGradient") {
        updateStopColor(stops[0].id, Color.Green)
        updateStopColor(stops[1].id, Color.Yellow)
        start = Offset(0.1f, 0.2f)
        end = Offset(0.8f, 0.9f)
        tileMode = TileMode.Repeated
      }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.linearGradient(\n    colors = listOf(Color(0xFF00FF00), Color(0xFFFFFF00)),\n    start = Offset(0.1f, 0.2f),\n" +
        "    end = Offset(0.8f, 0.9f),\n    tileMode = TileMode.Repeated\n)",
      file.brushText(),
    )
  }

  @Test
  fun specialRadialValuesAreWritten() {
    val file = addFile("Brush.radialGradient(colors = listOf(Color.Red, Color.Blue), center = Offset(0.5f, 0.5f), radius = 100.0f)")

    val result =
      edit(file, "radialGradient") {
        center = Offset.Unspecified
        radius = Float.POSITIVE_INFINITY
      }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "Brush.radialGradient(colors = listOf(Color.Red, Color.Blue), center = Offset.Unspecified, radius = Float.POSITIVE_INFINITY)",
      file.brushText(),
    )
  }

  @Test
  fun aliasedImportsAreUsed() {
    val file =
      addFile(
        "GBrush.linearGradient(colors = listOf(GColor.Red, GColor.Blue))",
        imports =
          """
          import androidx.compose.ui.geometry.Offset as GOffset
          import androidx.compose.ui.graphics.Brush as GBrush
          import androidx.compose.ui.graphics.Color as GColor
          """
            .trimIndent(),
      )
    val originalImports = file.currentText().substringBefore("val brush")

    val result =
      edit(file, "linearGradient") {
        updateStopColor(stops[0].id, Color(0xFF123456))
        start = Offset(1f, 2f)
      }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      "GBrush.linearGradient(colors = listOf(GColor(0xFF123456), GColor.Blue), start = GOffset(1f, 2f))",
      file.brushText(),
    )
    assertEquals(originalImports, file.currentText().substringBefore("val brush"))
  }

  @Test
  fun missingImportsAreAddedForNewExpressions() {
    val file =
      addFile(
        "Brush.linearGradient(colors = listOf(Color.Red, Color.Blue))",
        imports = "import androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color",
      )

    val result =
      edit(file, "linearGradient") {
        start = Offset(1f, 2f)
        tileMode = TileMode.Repeated
      }

    assertEquals(GradientWriteResult.Written, result)
    val text = file.currentText()
    assertTrue(
      text,
      text.contains("Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(1f, 2f), tileMode = TileMode.Repeated)"),
    )
    assertTrue(text, text.contains("import androidx.compose.ui.geometry.Offset\n"))
    assertTrue(text, text.contains("import androidx.compose.ui.graphics.TileMode\n"))
  }

  private companion object {
    val DEFAULT_IMPORTS =
      """
      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Brush
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.graphics.TileMode
      import test.colors.red
      """
        .trimIndent()
  }
}
