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

import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Checks the source written back after resizing a mesh with the editor controls. */
@RunWith(JUnit4::class)
@RunsInEdt
class GradientMeshResizeWriteBackTest {
  private val projectRule = AndroidProjectRule.inMemory().withKotlin()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule())

  @Before
  fun setUp() {
    projectRule.fixture.addComposeGraphicsStubs()
  }

  private fun addMeshFile(body: String = SIMPLE_MESH): KtFile =
    projectRule.fixture.addFileToProject(
      "src/test/Mesh.kt",
      "package test\n\n$IMPORTS\n\nfun MyMesh() {\n${body.trimIndent().prependIndent("    ")}\n}\n",
    ) as KtFile

  /** Opens the editor on the mesh of [file], applies [edit] and writes the changes back. */
  private fun edit(file: KtFile, edit: GradientEditorState.() -> Unit) {
    val call = runReadAction { file.findMeshPainterCall() }!!
    val result =
      withGradientEditorDialog(projectRule.project, call) { dialog ->
        dialog.state.edit()
        dialog.applyChanges()
      }
    assertEquals(GradientWriteResult.Written, result)
    projectRule.fixture.assertNoErrors(file)
  }

  private fun KtFile.meshSource(): String = runReadAction { text }.substringAfter("fun MyMesh() {\n").substringBeforeLast("\n}")

  @Test
  fun addingARowRenumbersTheLastRowAndAppendsTheInsertedVertices() {
    val file = addMeshFile()

    edit(file) { updateRows(3) }

    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 2, columns = 1) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(1f, 0f), Color.Blue)
          setVertex(2, 0, Offset(0f, 1f), Color.Green)
          setVertex(2, 1, Offset(1f, 1f), Color.Yellow)
          setVertex(1, 0, Offset(0f, 0.5f), Color(0xFFFF0000))
          setVertex(1, 1, Offset(1f, 0.5f), Color(0xFF0000FF))
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )
  }

  @Test
  fun addingAndRemovingAColumnOnlyRenumbersTheLastColumn() {
    val file = addMeshFile()

    edit(file) { updateCols(3) }

    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 1, columns = 2) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 2, Offset(1f, 0f), Color.Blue)
          setVertex(1, 0, Offset(0f, 1f), Color.Green)
          setVertex(1, 2, Offset(1f, 1f), Color.Yellow)
          setVertex(0, 1, Offset(0.5f, 0f), Color(0xFFFF0000))
          setVertex(1, 1, Offset(0.5f, 1f), Color(0xFF00FF00))
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )

    edit(file) { updateCols(2) }

    assertEquals(SIMPLE_MESH.trimIndent().prependIndent("    "), file.meshSource())
  }

  @Test
  fun everyCallSettingAMovedVertexIsRenumbered() {
    val file =
      addMeshFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 1, Offset(1f, 0f), Color.Cyan)
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(row = 0, column = 1, position = Offset(1f, 0f), color = Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )

    edit(file) { updateCols(3) }

    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 1, columns = 2) {
          setVertex(0, 2, Offset(1f, 0f), Color.Cyan)
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(row = 0, column = 2, position = Offset(1f, 0f), color = Color.Blue)
          setVertex(1, 0, Offset(0f, 1f), Color.Green)
          setVertex(1, 2, Offset(1f, 1f), Color.Yellow)
          setVertex(0, 1, Offset(0.5f, 0f), Color(0xFFFF0000))
          setVertex(1, 1, Offset(0.5f, 1f), Color(0xFF00FF00))
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )
  }

  @Test
  fun removingARowDeletesItsCallsAndRenumbersTheLastRow() {
    val file =
      addMeshFile(
        """
        val painter = MeshGradientPainter(rows = 2, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 0.4f), Color.Cyan)
            setVertex(1, 1, Offset(1f, 0.6f), Color(0xFF888888))
            setVertex(2, 0, Offset(0f, 1f), Color.Green)
            setVertex(2, 1, Offset(1f, 1f), Color.Yellow)
        }
        """
      )

    edit(file) { updateRows(2) }

    assertEquals(SIMPLE_MESH.trimIndent().prependIndent("    "), file.meshSource())
  }

  @Test
  fun resizingBothAxesRenumbersTheRowAndColumnOfTheLastVertex() {
    val file = addMeshFile()

    edit(file) {
      updateRows(3)
      updateCols(3)
    }

    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 2, columns = 2) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 2, Offset(1f, 0f), Color.Blue)
          setVertex(2, 0, Offset(0f, 1f), Color.Green)
          setVertex(2, 2, Offset(1f, 1f), Color.Yellow)
          setVertex(0, 1, Offset(0.5f, 0f), Color(0xFFFF0000))
          setVertex(1, 0, Offset(0f, 0.5f), Color(0xFFFF0000))
          setVertex(1, 1, Offset(0.5f, 0.5f), Color(0xFFFF0000))
          setVertex(1, 2, Offset(1f, 0.5f), Color(0xFF0000FF))
          setVertex(2, 1, Offset(0.5f, 1f), Color(0xFF00FF00))
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )
  }

  @Test
  fun callsOutsideTheGridAreUpdatedInPlaceInsteadOfRenumbering() {
    val file =
      addMeshFile(
        """
        val painter = MeshGradientPainter(rows = 1, columns = 1) {
            setVertex(0, 0, Offset(0f, 0f), Color.Red)
            setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            setVertex(1, 0, Offset(0f, 1f), Color.Green)
            setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
            setVertex(2, 0, Offset(0f, 2f), Color.Cyan)
        }
        """
      )

    edit(file) { updateRows(3) }

    // Renumbering the last row to 2 would leave it overridden by the call that was outside the grid.
    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 2, columns = 1) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(1f, 0f), Color.Blue)
          setVertex(1, 0, Offset(0f, 0.5f), Color(0xFFFF0000))
          setVertex(1, 1, Offset(1f, 0.5f), Color(0xFF0000FF))
          setVertex(2, 0, Offset(0f, 1f), Color.Green)
          setVertex(2, 1, Offset(1f, 1f), Color.Yellow)
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )
  }

  private companion object {
    const val IMPORTS =
      "import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.MeshGradientPainter"

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
