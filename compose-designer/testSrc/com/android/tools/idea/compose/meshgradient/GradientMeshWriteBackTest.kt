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
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.kotlin.psi.KtCallExpression
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
class GradientMeshWriteBackTest {
  private val projectRule = AndroidProjectRule.inMemory().withKotlin()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule())

  private val project
    get() = projectRule.project

  @Before
  fun setUp() {
    projectRule.fixture.addComposeGraphicsStubs()
    projectRule.fixture.addGradientTestHelpers()
  }

  private fun addFile(body: String, imports: String = DEFAULT_IMPORTS, fileName: String = "Mesh.kt"): KtFile =
    projectRule.fixture.addFileToProject(
      "src/test/$fileName",
      "package test\n\n$imports\n\nfun MyMesh() {\n${body.trimIndent().prependIndent("    ")}\n}\n",
    ) as KtFile

  private fun KtFile.meshCall(): KtCallExpression = runReadAction { findMeshPainterCall() }!!

  private fun KtFile.currentText(): String = runReadAction { text }

  private fun analyzeMesh(call: KtCallExpression): GradientEditorInput.Mesh =
    runReadAction { GradientPsiManager(project).analyze(call) } as GradientEditorInput.Mesh

  /** Checks that [file] still compiles if [result] reports that it was written. */
  private fun checkWritten(file: KtFile, result: GradientWriteResult): GradientWriteResult {
    if (result == GradientWriteResult.Written) projectRule.fixture.assertNoErrors(file)
    return result
  }

  /** Writes [target] over the mesh of [file], with the values parsed from the source as the initial ones. */
  private fun writeMesh(file: KtFile, target: (List<List<MeshGradientPoint>>) -> MeshValues): GradientWriteResult {
    val input = analyzeMesh(file.meshCall())
    val result =
      try {
        val initial = MeshValues(input.mesh.toGrid(), input.mesh.hasBicubicColor)
        WriteCommandAction.writeCommandAction(project)
          .compute(
            ThrowableComputable<GradientWriteResult, RuntimeException> {
              GradientSourceWriter(project).updateMesh(input.mesh, initial, target(initial.points))
            }
          )
      } finally {
        input.releasePointers(project)
      }
    return checkWritten(file, result)
  }

  /** Opens the editor on the mesh of [file], applies [edit] and writes the changes back. */
  private fun edit(file: KtFile, edit: GradientEditorState.() -> Unit): GradientWriteResult {
    val result =
      withGradientEditorDialog(project, file.meshCall()) { dialog ->
        dialog.state.edit()
        dialog.applyChanges()
      }
    return checkWritten(file, result)
  }

  @Test
  fun okWithoutEditsLeavesTheSourceUntouched() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            // Corners
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1,Offset( 1f, 0f ),   Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color(0xFF00FF00))
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )
    val originalText = file.currentText()

    withGradientEditorDialog(project, file.meshCall()) { dialog ->
      assertEquals(GradientWriteResult.Unchanged, dialog.applyChanges())
      dialog.performOkAction()
      assertTrue("The dialog should close", dialog.isDisposed)
    }

    assertEquals(originalText, file.currentText())
  }

  @Test
  fun meshThatCannotBeParsedIsNotEditable() {
    val file =
      addFile(
        """
        fun painter(size: Int) = MeshGradientPainter(rows = size, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
        }
        """
      )

    val input = runReadAction { GradientPsiManager(project).analyze(file.meshCall()) }

    assertTrue("Expected Unsupported but was $input", input is GradientEditorInput.Unsupported)
  }

  @Test
  fun rowsOutOfRangeAreRejected() {
    for (rows in listOf(0, 10)) {
      val file =
        addFile(
          """
          val painter = MeshGradientPainter(rows = $rows, columns = 1) {
              setVertex(0, 0, Offset(0f, 0f), Color.Red)
          }
          """,
          fileName = "Mesh$rows.kt",
        )

      val input = runReadAction { GradientPsiManager(project).analyze(file.meshCall()) }

      assertTrue("Expected Unsupported for rows = $rows but was $input", input is GradientEditorInput.Unsupported)
    }
  }

  @Test
  fun gridSizeOutOfRangeIsNotWritten() {
    val file = addFile(SIMPLE_MESH)
    val originalText = file.currentText()
    val expected = GradientWriteResult.Failed(message("gradient.editor.error.mesh.size", MIN_MESH_VERTICES, MAX_MESH_VERTICES))

    for (rows in listOf(MIN_MESH_VERTICES - 1, MAX_MESH_VERTICES + 1)) {
      val result =
        writeMesh(file) { points ->
          val columns = points[0].size
          MeshValues(List(rows) { r -> List(columns) { c -> MeshGradientPoint(Offset(c.toFloat(), r.toFloat()), Color.Red) } }, false)
        }

      assertEquals("Unexpected result for $rows rows", expected, result)
      assertEquals(originalText, file.currentText())
    }
  }

  @Test
  fun editPatchesOnlyTheChangedArgument() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1,Offset( 1f, 0f ),   Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow, leftControlPoint = Offset(0.5f, 0.75f))
        }
        """
      )
    val originalText = file.currentText()

    assertEquals(GradientWriteResult.Written, edit(file) { updateVertexColor(1, 1, Color(0xFF123456)) })

    val expected =
      originalText.replace(
        "setVertex(1, 1, Offset(1f, 1f), Color.Yellow,",
        "setVertex(1, 1, Offset(1f, 1f), Color(0xFF123456),",
      )
    assertEquals(expected, file.currentText())
  }

  @Test
  fun verticesSetInLoopsAreNotOverwritten() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            for (r in 0..1) {
                for (c in 0..1) {
                    setVertex(r, c, Offset(c.toFloat(), r.toFloat()), Color.Red)
                }
            }
            setVertex(0, 0, Offset(0f, 0f), Color.Blue)
        }
        """
      )
    val input = analyzeMesh(file.meshCall())
    assertTrue(input.mesh.hasUnparsedVertices)
    input.releasePointers(project)
    val originalText = file.currentText()

    // (1, 1) is only set by the loop, so it cannot be updated.
    val lockedResult =
      writeMesh(file) { points -> MeshValues(points.updated(1, 1) { it.copy(color = Color.Green, colorExpression = null) }, false) }
    assertTrue("Expected Failed but was $lockedResult", lockedResult is GradientWriteResult.Failed)
    assertEquals(originalText, file.currentText())

    // Adding a row would require rewriting the block.
    val resizeResult = writeMesh(file) { points -> MeshValues(points + listOf(points.last()), false) }
    assertEquals(GradientWriteResult.Failed(message("gradient.editor.error.mesh.structure")), resizeResult)
    assertEquals(originalText, file.currentText())

    // (0, 0) is set by a parsed call after the loop, which wins at runtime, so it can be updated in place.
    val editResult =
      writeMesh(file) { points ->
        MeshValues(points.updated(0, 0) { it.copy(color = Color(0xFF123456), colorExpression = null) }, false)
      }
    assertEquals(GradientWriteResult.Written, editResult)
    assertEquals(
      originalText.replace("setVertex(0, 0, Offset(0f, 0f), Color.Blue)", "setVertex(0, 0, Offset(0f, 0f), Color(0xFF123456))"),
      file.currentText(),
    )
  }

  @Test
  fun conditionalVertexIsNotUpdated() {
    val file =
      addFile(
        """
        val highlighted = true
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            if (highlighted) {
                setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
            }
        }
        """
      )
    val originalText = file.currentText()

    val result = edit(file) { updateVertexColor(1, 1, Color(0xFF123456)) }

    assertEquals(GradientWriteResult.Failed(message("gradient.editor.error.mesh.vertex.unresolved", 1, 1)), result)
    assertEquals(originalText, file.currentText())
  }

  @Test
  fun animatedVertexValueIsNotOverwritten() {
    val file =
      addFile(
        """
        val animatedX by animateFloatAsState(0f)
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(animatedX, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """,
        imports =
          "$DEFAULT_IMPORTS\nimport androidx.compose.animation.core.animateFloatAsState\nimport androidx.compose.animation.core.getValue",
      )
    val originalText = file.currentText()

    val moveResult =
      writeMesh(file) { points ->
        MeshValues(points.updated(0, 0) { it.copy(position = Offset(0.1f, 0.1f), positionExpression = null) }, false)
      }

    assertEquals(GradientWriteResult.Failed(message("gradient.editor.error.argument.unresolved", ARG_POSITION)), moveResult)
    assertEquals(originalText, file.currentText())

    val colorResult =
      writeMesh(file) { points ->
        MeshValues(points.updated(0, 0) { it.copy(color = Color(0xFF123456), colorExpression = null) }, false)
      }

    assertEquals(GradientWriteResult.Written, colorResult)
    assertTrue(file.currentText(), file.currentText().contains("setVertex(0, 0, Offset(animatedX, 0f), Color(0xFF123456))"))
  }

  @Test
  fun failedChangeIsNotPartiallyWritten() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow, leftControlPoint = controlPoint())
        }
        """
      )
    val originalText = file.currentText()

    withGradientEditorDialog(project, file.meshCall()) { dialog ->
      dialog.state.updateVertexColor(0, 0, Color(0xFF123456))
      dialog.state.updateAllPoints { point ->
        if (point.color == Color.Yellow) point.copy(leftBezierOffset = Offset(0.1f, 0.2f)) else point
      }
      dialog.performOkAction()

      assertFalse("The dialog should stay open to show the error", dialog.isDisposed)
    }

    assertEquals(originalText, file.currentText())
  }

  @Test
  fun unparsedControlPointIsKeptWhenOtherValuesChange() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red, rightControlPoint = controlPoint())
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )

    assertEquals(GradientWriteResult.Written, edit(file) { updateVertexColor(0, 0, Color(0xFF123456)) })

    assertTrue(file.currentText().contains("setVertex(0, 0, Offset(0f, 0f), Color(0xFF123456), rightControlPoint = controlPoint())"))
  }

  @Test
  fun controlPointIsAddedAfterATrailingComma() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(
                0,
                0,
                Offset(0f, 0f),
                Color.Red,
            )
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )

    val result = writeMesh(file) { points -> MeshValues(points.updated(0, 0) { it.copy(rightBezierOffset = Offset(0.25f, 0f)) }, false) }

    assertEquals(GradientWriteResult.Written, result)
    assertTrue(
      file.currentText(),
      file
        .currentText()
        .contains(
          "setVertex(\n            0,\n            0,\n            Offset(0f, 0f),\n            Color.Red,\n" +
            "            rightControlPoint = Offset(0.25f, 0f),\n        )"
        ),
    )
  }

  @Test
  fun namedBlockLambdaIsEditable() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1, block = {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        })
        """
      )

    val result =
      edit(file) {
        assertEquals(Color.Yellow, meshPoints[1][1].color)
        updateVertexColor(1, 1, Color(0xFF123456))
      }

    assertEquals(GradientWriteResult.Written, result)
    assertTrue(file.currentText().contains("setVertex(1, 1, Offset(1f, 1f), Color(0xFF123456))"))
  }

  @Test
  fun duplicateSetVertexUpdatesTheLastCall() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
            setVertex(0, 0, Offset(0f, 0f), Color.Magenta)
        }
        """
      )

    val result =
      edit(file) {
        assertEquals(Color.Magenta, meshPoints[0][0].color)
        updateVertexColor(0, 0, Color(0xFF123456))
      }

    assertEquals(GradientWriteResult.Written, result)
    val text = file.currentText()
    assertTrue(text.contains("setVertex(0, 0, Offset(0f, 0f), Color.Red)"))
    assertTrue(text.contains("setVertex(0, 0, Offset(0f, 0f), Color(0xFF123456))"))
    assertFalse(text.contains("Color.Magenta"))
  }

  @Test
  fun aliasedColorImportIsUsedAndPreserved() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), ComposeColor.Red)
            setVertex(0, 1, Offset(1f, 0f), ComposeColor.Blue)
            setVertex(1, 0, Offset(0f, 1f), ComposeColor.Green)
            setVertex(1, 1, Offset(1f, 1f), ComposeColor.Yellow)
        }
        """,
        imports =
          "import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Color as ComposeColor\n$MESH_PAINTER_IMPORT",
      )

    assertEquals(GradientWriteResult.Written, edit(file) { updateVertexColor(1, 1, Color(0xFF123456)) })

    val text = file.currentText()
    assertTrue(text, text.contains("setVertex(1, 1, Offset(1f, 1f), ComposeColor(0xFF123456))"))
    assertTrue(text, text.contains("import androidx.compose.ui.graphics.Color as ComposeColor"))
    assertFalse(text, text.contains("import androidx.compose.ui.graphics.Color\n"))
  }

  @Test
  fun resizeUpdatesTheSizeAndOnlyAddsOrRemovesTheAffectedVertices() {
    val file = addFile(SIMPLE_MESH)

    val growResult =
      writeMesh(file) { points ->
        MeshValues(points.map { row -> row + MeshGradientPoint(Offset(2f, row[0].position.y), Color(0xFF123456)) }, false)
      }

    assertEquals(GradientWriteResult.Written, growResult)
    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 1, columns = 2) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(1f, 0f), Color.Blue)
          setVertex(1, 0, Offset(0f, 1f), Color.Green)
          setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
          setVertex(0, 2, Offset(2f, 0f), Color(0xFF123456))
          setVertex(1, 2, Offset(2f, 1f), Color(0xFF123456))
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.currentText().substringAfter("fun MyMesh() {\n").substringBeforeLast("\n}"),
    )

    val shrinkResult = writeMesh(file) { points -> MeshValues(points.map { row -> row.take(2) }, false) }

    assertEquals(GradientWriteResult.Written, shrinkResult)
    assertEquals(
      SIMPLE_MESH.trimIndent().prependIndent("    "),
      file.currentText().substringAfter("fun MyMesh() {\n").substringBeforeLast("\n}"),
    )
  }

  @Test
  fun removedVerticesTakeTheirTrailingComments() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 2) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(0, 2, Offset(2f, 0f), Color.Cyan) // top right
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
            setVertex(1, 2, Offset(2f, 1f), Color.Cyan) /* bottom right */
        }
        """
      )

    val result = writeMesh(file) { points -> MeshValues(points.map { row -> row.take(2) }, false) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      SIMPLE_MESH.trimIndent().prependIndent("    "),
      file.currentText().substringAfter("fun MyMesh() {\n").substringBeforeLast("\n}"),
    )
  }

  @Test
  fun removingTheFirstStatementLeavesNoBlankLine() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 2) {
            setVertex(0, 2, Offset(2f, 0f), Color.Cyan) // top right
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
            setVertex(1, 2, Offset(2f, 1f), Color.Cyan)
        }
        """
      )

    val result = writeMesh(file) { points -> MeshValues(points.map { row -> row.take(2) }, false) }

    assertEquals(GradientWriteResult.Written, result)
    assertEquals(
      SIMPLE_MESH.trimIndent().prependIndent("    "),
      file.currentText().substringAfter("fun MyMesh() {\n").substringBeforeLast("\n}"),
    )
  }

  @Test
  fun unchangedDimensionKeepsItsExpression() {
    val file =
      addFile(
        """
        val meshRows = 1
        val painter = MeshGradientPainter(rows = meshRows, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )

    val result =
      writeMesh(file) { points ->
        MeshValues(points.map { row -> row + MeshGradientPoint(Offset(2f, row[0].position.y), Color(0xFF123456)) }, false)
      }

    assertEquals(GradientWriteResult.Written, result)
    assertTrue(file.currentText(), file.currentText().contains("MeshGradientPainter(rows = meshRows, columns = 2)"))
  }

  @Test
  fun bicubicColorArgumentIsAddedWhenMissing() {
    val file = addFile(SIMPLE_MESH)

    assertEquals(GradientWriteResult.Written, edit(file) { hasBicubicColor = true })

    assertTrue(file.currentText().contains("MeshGradientPainter(rows = 1, columns = 1, hasBicubicColor = true)"))
  }

  @Test
  fun missingImportIsAddedForNewExpressions() {
    val file =
      addFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), red)
            setVertex(0, 1, Offset(1f, 0f), red)
            setVertex(1, 0, Offset(0f, 1f), red)
            setVertex(1, 1, Offset(1f, 1f), red)
        }
        """,
        imports = "import androidx.compose.ui.geometry.Offset\n$MESH_PAINTER_IMPORT\nimport test.colors.red",
      )

    assertEquals(GradientWriteResult.Written, edit(file) { updateVertexColor(1, 1, Color(0xFF123456)) })

    val text = file.currentText()
    assertTrue(text, text.contains("setVertex(1, 1, Offset(1f, 1f), Color(0xFF123456))"))
    assertTrue(text, text.contains("import androidx.compose.ui.graphics.Color\n"))
  }

  private fun List<List<MeshGradientPoint>>.updated(row: Int, col: Int, transform: (MeshGradientPoint) -> MeshGradientPoint) =
    mapIndexed { r, points ->
      points.mapIndexed { c, point -> if (r == row && c == col) transform(point) else point }
    }

  private companion object {
    const val MESH_PAINTER_IMPORT = "import androidx.compose.ui.graphics.MeshGradientPainter"

    const val DEFAULT_IMPORTS =
      "import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Color\n$MESH_PAINTER_IMPORT"

    const val SIMPLE_MESH =
      """
      val painter = MeshGradientPainter(rows = 1, columns = 1) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(1f, 0f), Color.Blue)
          setVertex(1, 0, Offset(0f, 1f), Color.Green)
          setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
      }
      """
  }
}
