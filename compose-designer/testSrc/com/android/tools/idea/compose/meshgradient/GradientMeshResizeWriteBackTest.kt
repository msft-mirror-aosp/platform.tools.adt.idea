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

  private fun addMeshFile(): KtFile =
    projectRule.fixture.addFileToProject(
      "src/test/Mesh.kt",
      "package test\n\n$IMPORTS\n\nfun MyMesh() {\n${SIMPLE_MESH.trimIndent().prependIndent("    ")}\n}\n",
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
  fun addingARowWritesTheInsertedVertices() {
    val file = addMeshFile()

    edit(file) { updateRows(3) }

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

  @Test
  fun addingAndRemovingAColumnRestoresTheSource() {
    val file = addMeshFile()

    edit(file) { updateCols(3) }

    assertEquals(
      """
      val painter = MeshGradientPainter(rows = 1, columns = 2) {
          setVertex(0, 0, Offset(0f, 0f), Color.Red)
          setVertex(0, 1, Offset(0.5f, 0f), Color(0xFFFF0000))
          setVertex(1, 0, Offset(0f, 1f), Color.Green)
          setVertex(1, 1, Offset(0.5f, 1f), Color(0xFF00FF00))
          setVertex(0, 2, Offset(1f, 0f), Color.Blue)
          setVertex(1, 2, Offset(1f, 1f), Color.Yellow)
      }
      """
        .trimIndent()
        .prependIndent("    "),
      file.meshSource(),
    )

    edit(file) { updateCols(2) }

    assertEquals(SIMPLE_MESH.trimIndent().prependIndent("    "), file.meshSource())
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
