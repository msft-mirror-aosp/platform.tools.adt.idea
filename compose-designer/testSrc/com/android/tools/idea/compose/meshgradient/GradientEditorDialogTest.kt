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
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientEditorDialogTest : LightPlatformTestCase() {

  private val codeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val gradientPainter = remember {
            MeshGradientPainter(rows = 2, columns = 3, hasBicubicColor = true) {
                setVertex(0, 0, Offset(0.0000f, 0.0000f), Color(0xFFF44336))
                setVertex(0, 1, Offset(0.3333f, 0.0000f), Color(0xFFE91E63))
                setVertex(0, 2, Offset(0.6666f, 0.0000f), Color(0xFF9C27B0))
                setVertex(0, 3, Offset(1.0000f, 0.0000f), Color(0xFF673AB7))

                setVertex(1, 0, Offset(0.0000f, 0.5000f), Color(0xFF3F51B5))
                setVertex(1, 1, Offset(0.3333f, 0.5000f), Color(0xFF2196F3))
                setVertex(1, 2, Offset(0.6666f, 0.5000f), Color(0xFF03A9F4))
                setVertex(1, 3, Offset(1.0000f, 0.5000f), Color(0xFF00BCD4))

                setVertex(2, 0, Offset(0.0000f, 1.0000f), Color(0xFF009688))
                setVertex(2, 1, Offset(0.3333f, 1.0000f), Color(0xFF4CAF50))
                setVertex(2, 2, Offset(0.6666f, 1.0000f), Color(0xFF8BC34A))
                setVertex(2, 3, Offset(1.0000f, 1.0000f), Color(0xFFCDDC39))
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testDialogPreservesUnmodifiedVariableAndAnimatedExpressions() {
    val animatedCode =
      """
      package test

      import androidx.compose.animation.core.animateFloat
      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      fun MyAnimatedMesh() {
          val infiniteTransition = rememberInfiniteTransition()
          val animatedOffset by infiniteTransition.animateFloat(initialValue = -0.1f, targetValue = 0.1f)
          val indigo = Color(0xFF5856D6)
          val coral = Color(255, 90, 90)
          val points = remember { listOf(Offset(0.0f, 0.0f), Offset(1.0f, 0.0f)) }

          val gradientPainter = remember {
              MeshGradientPainter(rows = 1, columns = 1) {
                  setVertex(0, 0, points[0], indigo)
                  setVertex(0, 1, points[1], indigo)
                  setVertex(1, 0, Offset(0.0000f, 1.0000f), coral)
                  setVertex(1, 1, Offset(0.2f, 0.4f) + Offset(animatedOffset, animatedOffset), coral)
              }
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("TestAnimatedDialog.kt", animatedCode)
    val psiManager = GradientPsiManager(project)

    val call = runReadActionBlocking { psiManager.findMeshPainterCall(file) }
    assertNotNull(call)

    val dialog = GradientEditorDialog(project, file, call!!)
    assertTrue("Should detect dynamic values", dialog.state.hasDynamicOrUnresolvedValues)

    // Modify only (0, 0) position and (0, 1) color; leave (1, 1) untouched
    dialog.state.constrainEdgePoints = false
    dialog.state.updateMeshPoint(0, 0, Offset(0.05f, 0.05f))
    dialog.state.updateVertexColor(0, 1, Color(0xFF00BCD4))

    dialog.doOKAction()

    val updatedText = runReadActionBlocking { file.text }
    assertTrue(
      "Updated vertex (0,0) should have new offset literal and preserve indigo variable: $updatedText",
      updatedText.contains("setVertex(0, 0, Offset(0.0500f, 0.0500f), indigo)"),
    )
    assertTrue(
      "Updated vertex (0,1) should preserve points[1] and have new color literal: $updatedText",
      updatedText.contains("setVertex(0, 1, points[1], Color(0xFF00BCD4))"),
    )
    assertTrue(
      "Untouched vertex (1,1) should preserve animated offset expression and coral variable: $updatedText",
      updatedText.contains("setVertex(1, 1, Offset(0.2f, 0.4f) + Offset(animatedOffset, animatedOffset), coral)"),
    )
  }

  @Test
  fun testDialogInitialization() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", codeTemplate)
    val psiManager = GradientPsiManager(project)

    val call = runReadActionBlocking { psiManager.findMeshPainterCall(file) }
    assertNotNull(call)

    val dialog = GradientEditorDialog(project, file, call!!)

    assertEquals(3, dialog.state.rows)
    assertEquals(4, dialog.state.cols)
    assertTrue(dialog.state.hasBicubicColor)

    val p01 = dialog.state.meshPoints[0][1]
    assertEquals(Offset(0.3333f, 0f), p01.position)
    assertEquals(Color(0xFFE91E63), p01.color)

    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
  }

  @Test
  fun testDialogOkActionCommitsChanges() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", codeTemplate)
    val psiManager = GradientPsiManager(project)

    val call = runReadActionBlocking { psiManager.findMeshPainterCall(file) }
    assertNotNull(call)

    val dialog = GradientEditorDialog(project, file, call!!)

    dialog.state.updateMeshPoint(1, 1, Offset(0.4f, 0.6f))

    dialog.doOKAction()

    val updatedText = runReadActionBlocking { file.text }
    assertTrue(
      "Should contain updated offset in code",
      updatedText.contains("setVertex(1, 1, Offset(0.4000f, 0.6000f), Color(0xFF2196F3))"),
    )
    assertTrue("Should preserve hasBicubicColor = true", updatedText.contains("hasBicubicColor = true"))
  }

  @Test
  fun testDialogPreservesBezierControlPointsAcrossEdits() {
    val codeWithBezier =
      """
      package test

      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      fun MyMesh() {
          val gradientPainter = remember {
              MeshGradientPainter(rows = 1, columns = 1, hasBicubicColor = true) {
                  setVertex(0, 0, Offset(0.0000f, 0.0000f), Color(0xFFF44336), rightControlPoint = Offset(0.2500f, 0.1000f))
                  setVertex(0, 1, Offset(1.0000f, 0.0000f), Color(0xFFE91E63))
                  setVertex(1, 0, Offset(0.0000f, 1.0000f), Color(0xFF3F51B5))
                  setVertex(1, 1, Offset(1.0000f, 1.0000f), Color(0xFF2196F3))
              }
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("TestBezier.kt", codeWithBezier)
    val psiManager = GradientPsiManager(project)

    val call = runReadActionBlocking { psiManager.findMeshPainterCall(file) }
    assertNotNull(call)

    val dialog = GradientEditorDialog(project, file, call!!)
    assertEquals(Offset(0.25f, 0.1f), dialog.state.meshPoints[0][0].rightBezierOffset)

    dialog.state.updatePaletteAndMeshColor(Color(0xFFF44336), Color(0xFF00BCD4))
    assertEquals(Offset(0.25f, 0.1f), dialog.state.meshPoints[0][0].rightBezierOffset)

    dialog.doOKAction()

    val updatedText = runReadActionBlocking { file.text }
    assertTrue("Should contain updated color: $updatedText", updatedText.contains("Color(0xFF00BCD4)"))
    assertTrue(
      "Should preserve rightControlPoint: $updatedText",
      updatedText.contains("rightControlPoint = Offset(0.2500f, 0.1000f)"),
    )

    val reparsed = runReadActionBlocking { psiManager.parseMesh(call) }
    assertNotNull(reparsed)
    val v00 = reparsed!!.vertices.first { it.row == 0 && it.col == 0 }
    assertEquals(Color(0xFF00BCD4), v00.color)
    assertEquals(Offset(0.25f, 0.1f), v00.rightBezierOffset)
  }

  @Test
  fun testDialogLoadsLocalAndImportedColorsIntoPalette() {
    VfsTestUtil.createFile(
      getSourceRoot(),
      "ui/theme/Color.kt",
      """
      package test.ui.theme

      import androidx.compose.ui.graphics.Color

      val ThemePrimary = Color(0xFF6200EE)
      val ThemeSecondary = Color(0xFF03DAC5)
      """
        .trimIndent(),
    )

    val mainVFile =
      VfsTestUtil.createFile(
        getSourceRoot(),
        "MainScreen.kt",
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import test.ui.theme.*

        val FileAccent = Color(0xFFFF4081)

        fun MyMesh() {
            val localCustom = Color(0xFFAA00FF)
            val gradientPainter = remember {
                MeshGradientPainter(rows = 1, columns = 1) {
                    setVertex(0, 0, Offset(0f, 0f), Color.Red)
                    setVertex(0, 1, Offset(1f, 0f), Color.Blue)
                    setVertex(1, 0, Offset(0f, 1f), Color.Green)
                    setVertex(1, 1, Offset(1f, 1f), Color.Yellow)
                }
            }
        }
        """
          .trimIndent(),
      )

    val psiManager = GradientPsiManager(project)
    val mainFile = runReadActionBlocking { this.psiManager.findFile(mainVFile) as KtFile }
    val call = runReadActionBlocking { psiManager.findMeshPainterCall(mainFile) }
    assertNotNull(call)

    val dialog = GradientEditorDialog(project, mainFile, call!!)
    assertTrue("Should include localCustom in palette", Color(0xFFAA00FF) in dialog.state.availableColors)
    assertTrue("Should include FileAccent in palette", Color(0xFFFF4081) in dialog.state.availableColors)
    assertTrue("Should include star-imported ThemePrimary in palette", Color(0xFF6200EE) in dialog.state.availableColors)
    assertTrue("Should include star-imported ThemeSecondary in palette", Color(0xFF03DAC5) in dialog.state.availableColors)

    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
  }
}
