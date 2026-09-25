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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshGradientPsiManagerTest : LightPlatformTestCase() {

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
                setVertex(1, 0, Offset(0.0000f, 0.5000f), Color(0xFF3F51B5))
                setVertex(1, 1, Offset(0.3333f, 0.5000f), Color(0xFF2196F3))
            }
        }
    }
    """
      .trimIndent()

  private val robustCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val gradientPainter = remember {
            MeshGradientPainter(2, 3) {
                setVertex(0, 0, Offset(0.1f, 0.2f), Color(0xFF123456.toInt()))
                setVertex(0, 1, Offset(x = 0.3f, y = 0.4f), Color(1f, 0.5f, 0.2f))
                setVertex(1, 0, Offset(0.5f, 0.6f), Color(255, 128, 64, 255))
                setVertex(1, 1, Offset(0.7f, 0.8f), Color.Green)
            }
        }
    }
    """
      .trimIndent()

  private val aliasCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.MeshGradientPainter as CustomMeshPainter

    fun MyMesh() {
        val gradientPainter = remember {
            CustomMeshPainter(rows = 2, columns = 3, hasBicubicColor = true) {
                setVertex(0, 0, Offset(0.0f, 0.0f), Color.Red)
            }
        }
    }
    """
      .trimIndent()

  private val variablesCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val purple = Color(0xFFAF52DE)
        val yellow = Color(0xFFFFCC00)
        val points = remember {
            listOf(
                Offset(0.0f, 0.1f),
                Offset(0.2f, 0.3f)
            )
        }
        val gradientPainter = remember {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, points[0], yellow)
                setVertex(0, 1, points[1], purple)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testParseAnimatedMeshAndBinaryExpressions() {
    val animatedSnippet =
      """
      package test

      import androidx.compose.animation.animateColorAsState
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.runtime.mutableStateOf
      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      fun AnimatedMesh() {
          val infiniteTransition = rememberInfiniteTransition(label = "meshMovement")
          val animatedOffset by infiniteTransition.animateFloat(
              initialValue = -0.1f,
              targetValue = 0.1f,
          )
          val animatedColor by animateColorAsState(targetValue = Color(0xFF112233))
          val mutableX by remember { mutableStateOf(0.25f) }

          val coral = Color(255, 90, 90)
          val peach = Color(255, 139, 90)
          val indigo = Color(0xFF5856D6)
          val pink = Color(0xFFFF2D55)

          val gradientPainter = remember {
              MeshGradientPainter(rows = 1, columns = 1) {
                  setVertex(0, 0, Offset.Zero, indigo, leftControlPoint = Offset.Zero, rightControlPoint = Offset.Unspecified)
                  setVertex(0, 1, Offset(mutableX * 2f, 0.0f), animatedColor)
                  setVertex(1, 0, Offset(0.3f, 0.8f) - Offset(animatedOffset, 0f), pink)
                  setVertex(1, 1, Offset(0.2f, 0.4f) + Offset(animatedOffset, animatedOffset), coral)
              }
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("AnimatedTest.kt", animatedSnippet)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse animated mesh successfully", parsedMesh)
      assertTrue("Should flag dynamic values", parsedMesh!!.hasDynamicOrUnresolvedValues)
      assertEquals(4, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset.Zero, v00.offset)
      assertEquals(Color(0xFF5856D6), v00.color)
      assertEquals(Offset.Zero, v00.leftBezierOffset)
      assertEquals(Offset.Unspecified, v00.rightBezierOffset)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.5f, 0.0f), v01.offset)
      assertEquals(Color(0xFF112233), v01.color)

      val v10 = parsedMesh.vertices.first { it.row == 1 && it.col == 0 }
      assertEquals(0.4f, v10.offset.x, 0.0001f)
      assertEquals(0.8f, v10.offset.y, 0.0001f)
      assertEquals(Color(0xFFFF2D55), v10.color)

      val v11 = parsedMesh.vertices.first { it.row == 1 && it.col == 1 }
      assertEquals(0.1f, v11.offset.x, 0.0001f)
      assertEquals(0.3f, v11.offset.y, 0.0001f)
      assertEquals(Color(255, 90, 90), v11.color)
      assertEquals("Offset(0.2f, 0.4f) + Offset(animatedOffset, animatedOffset)", v11.positionExpression)
      assertEquals("coral", v11.colorExpression)
    }
  }

  @Test
  fun testDelegatedShadowingDynamicArrayAccessAndUnspecifiedOffsetGuard() {
    val snippet =
      """
      package test

      import androidx.compose.runtime.mutableFloatStateOf
      import androidx.compose.runtime.mutableStateOf
      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      val shadowedX = 0.9f

      fun DynamicShadowMesh() {
          val shadowedX = 0.25f
          val shadowedX by remember { mutableFloatStateOf(shadowedX) }
          val dynamicPoints by remember { mutableStateOf(listOf(Offset(0.1f, 0.2f), Offset(0.3f, 0.4f))) }

          val gradientPainter = remember {
              MeshGradientPainter(rows = 1, columns = 1) {
                  setVertex(0, 0, dynamicPoints[0], Color.Red)
                  setVertex(0, 1, Offset(shadowedX, 0.5f), Color.Blue)
                  setVertex(1, 0, Offset.Unspecified, Color.Green)
                  setVertex(1, 1, Offset.Unspecified + Offset(0.1f, 0.1f), Color.Yellow)
              }
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("DynamicShadowTest.kt", snippet)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull(parsedMesh)
      assertTrue(parsedMesh!!.hasDynamicOrUnresolvedValues)
      // Vertices (1, 0) and (1, 1) with Offset.Unspecified primary positions are rejected
      assertEquals(2, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.1f, 0.2f), v00.offset)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.25f, 0.5f), v01.offset)
    }
  }

  @Test
  fun testParseMesh() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", codeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(3, parsedMesh!!.rows)
      assertEquals(4, parsedMesh.cols)
      assertEquals(4, parsedMesh.vertices.size)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.3333f, 0f), v01.offset)
      assertEquals(Color(0xFFE91E63), v01.color)
    }
  }

  @Test
  fun testFindMeshPainterCallWithImportAlias() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", aliasCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    val call = runReadActionBlocking { psiManager.findMeshPainterCall(file) }
    assertNotNull("Should find call even when using import alias", call)

    val resolvedText = runReadActionBlocking { call!!.calleeExpression?.text }
    assertEquals("CustomMeshPainter", resolvedText)
  }

  @Test
  fun testParseMeshWithVariables() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", variablesCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(2, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.0f, 0.1f), v00.offset)
      assertEquals(Color(0xFFFFCC00), v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.2f, 0.3f), v01.offset)
      assertEquals(Color(0xFFAF52DE), v01.color)
    }
  }

  @Test
  fun testRobustParsing() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", robustCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(3, parsedMesh!!.rows)
      assertEquals(4, parsedMesh.cols)
      assertEquals(4, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.1f, 0.2f), v00.offset)
      assertEquals(Color(0xFF123456), v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.3f, 0.4f), v01.offset)
      assertEquals(Color(1f, 0.5f, 0.2f, 1f), v01.color)

      val v10 = parsedMesh.vertices.first { it.row == 1 && it.col == 0 }
      assertEquals(Offset(0.5f, 0.6f), v10.offset)
      assertEquals(Color(255, 128, 64, 255), v10.color)

      val v11 = parsedMesh.vertices.first { it.row == 1 && it.col == 1 }
      assertEquals(Offset(0.7f, 0.8f), v11.offset)
      assertEquals(Color.Green, v11.color)
    }
  }

  @Test
  fun testUpdateVertexColor() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", codeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    WriteCommandAction.runWriteCommandAction(project) {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)
      val success = psiManager.updateVertexColor(call!!, 0, 1, Color(0xFF2196F3))
      assertTrue("Should update color successfully", success)
    }

    val updatedText = runReadActionBlocking { file.text }
    assertTrue("Should contain updated color in code", updatedText.contains("setVertex(0, 1, Offset(0.3333f, 0.0000f), Color(0xFF2196F3))"))
  }

  @Test
  fun testUpdateVertexOffset() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", codeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    WriteCommandAction.runWriteCommandAction(project) {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)
      val success = psiManager.updateVertexOffset(call!!, 1, 0, Offset(0.1f, 0.6f))
      assertTrue("Should update offset successfully", success)
    }

    val updatedText = runReadActionBlocking { file.text }
    assertTrue(
      "Should contain updated offset in code",
      updatedText.contains("setVertex(1, 0, Offset(0.1000f, 0.6000f), Color(0xFF3F51B5))"),
    )
  }

  private val userSnippet =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MeshGradientComplex() {
        val purple = Color(0xFFAF52DE)
        val indigo = Color(0xFF5856D6)
        val yellow = Color(0xFFFFCC00)
        val pink = Color(0xFFFF2D55)
        val orange = Color(0xFFFF9500)
        val points = remember {
            listOf(
                Offset(0.0f, 0.0f), Offset(0.3f, 0.0f), Offset(0.7f, 0.0f), Offset(1.0f, 0.0f),
                Offset(0.0f, 0.3f), Offset(0.2f, 0.4f), Offset(0.7f, 0.2f), Offset(1.0f, 0.3f),
                Offset(0.0f, 0.7f), Offset(0.3f, 0.8f), Offset(0.7f, 0.6f), Offset(1.0f, 0.7f),
                Offset(0.0f, 1.0f), Offset(0.3f, 1.0f), Offset(0.7f, 1.0f), Offset(1.0f, 1.0f)
            )
        }

        val gradientPainter = remember {
            MeshGradientPainter(rows = 3, columns = 3) {
                // Row 0
                setVertex(0, 0, points[0], yellow)
                setVertex(0, 1, points[1], orange)
                setVertex(0, 2, points[2], yellow)
                setVertex(0, 3, points[3], purple)

                // Row 1
                setVertex(1, 0, points[4], pink)
                setVertex(1, 1, points[5], yellow)
                setVertex(1, 2, points[6], pink)
                setVertex(1, 3, points[7], purple)

                // Row 2
                setVertex(2, 0, points[8], indigo)
                setVertex(2, 1, points[9], pink)
                setVertex(2, 2, points[10], purple)
                setVertex(2, 3, points[11], indigo)

                // Row 3
                setVertex(3, 0, points[12], purple)
                setVertex(3, 1, points[13], indigo)
                setVertex(3, 2, points[14], pink)
                setVertex(3, 3, points[15], yellow)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testParseUserSnippet() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", userSnippet)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(4, parsedMesh!!.rows)
      assertEquals(4, parsedMesh.cols)
      assertEquals(16, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.0f, 0.0f), v00.offset)
      assertEquals(Color(0xFFFFCC00), v00.color)

      val v11 = parsedMesh.vertices.first { it.row == 1 && it.col == 1 }
      assertEquals(Offset(0.2f, 0.4f), v11.offset)
      assertEquals(Color(0xFFFFCC00), v11.color)

      val v22 = parsedMesh.vertices.first { it.row == 2 && it.col == 2 }
      assertEquals(Offset(0.7f, 0.6f), v22.offset)
      assertEquals(Color(0xFFAF52DE), v22.color)

      val v33 = parsedMesh.vertices.first { it.row == 3 && it.col == 3 }
      assertEquals(Offset(1.0f, 1.0f), v33.offset)
      assertEquals(Color(0xFFFFCC00), v33.color)
    }
  }

  private val fqnAndAliasCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset as ComposeOffset
    import androidx.compose.ui.graphics.Color as ComposeColor
    import androidx.compose.runtime.remember as rememberCompose

    fun MyMesh() {
        val purple = androidx.compose.ui.graphics.Color(0xFFAF52DE)
        val yellow = ComposeColor(0xFFFFCC00)
        val points = rememberCompose {
            kotlin.collections.listOf(
                androidx.compose.ui.geometry.Offset(0.0f, 0.1f),
                ComposeOffset(0.2f, 0.3f)
            )
        }
        val gradientPainter = rememberCompose {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, points[0], yellow)
                setVertex(0, 1, points[1], purple)
                setVertex(1, 0, points[0], ComposeColor.Red)
                setVertex(1, 1, points[1], androidx.compose.ui.graphics.Color.Green)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testParseWithFullyQualifiedNamesAndAliases() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", fqnAndAliasCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(4, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.0f, 0.1f), v00.offset)
      assertEquals(Color(0xFFFFCC00), v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.2f, 0.3f), v01.offset)
      assertEquals(Color(0xFFAF52DE), v01.color)

      val v10 = parsedMesh.vertices.first { it.row == 1 && it.col == 0 }
      assertEquals(Offset(0.0f, 0.1f), v10.offset)
      assertEquals(Color.Red, v10.color)

      val v11 = parsedMesh.vertices.first { it.row == 1 && it.col == 1 }
      assertEquals(Offset(0.2f, 0.3f), v11.offset)
      assertEquals(Color.Green, v11.color)
    }
  }

  private val circularReferenceCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val purple = remember { purple }
        val yellow = Color(0xFFFFCC00)
        val gradientPainter = remember {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, Offset(0f, 0f), yellow)
                setVertex(0, 1, Offset(1f, 1f), purple)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testCircularReferenceSafety() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", circularReferenceCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh without hanging", parsedMesh)

      // The vertex with the circular reference should not be successfully resolved,
      // but other valid vertices must be parsed successfully.
      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(1, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0f, 0f), v00.offset)
      assertEquals(Color(0xFFFFCC00), v00.color)
    }
  }

  private val variableIndexCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val yellow = Color(0xFFFFCC00)
        val points = listOf(Offset(0.0f, 0.1f), Offset(0.2f, 0.3f))
        val idx0 = 0
        val idx1 = 1
        val gradientPainter = remember {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, points[idx0], yellow)
                setVertex(0, 1, points[idx1], yellow)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testVariableIndexResolution() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", variableIndexCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(2, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.0f, 0.1f), v00.offset)
      assertEquals(Color(0xFFFFCC00), v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.2f, 0.3f), v01.offset)
      assertEquals(Color(0xFFFFCC00), v01.color)
    }
  }

  private val coordinateVariablesCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color

    fun MyMesh() {
        val defaultX = 0.1f
        val yCoord = 0.2f
        val gradientPainter = remember {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, Offset(defaultX, yCoord), Color.Red)
                setVertex(0, 1, Offset(0.3f, 0.4f), Color.Blue)
            }
        }
    }
    """
      .trimIndent()

  private val collectionAliasCodeTemplate =
    """
    package test

    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color
    import kotlin.collections.listOf as myListOf

    fun MyMesh() {
        val points = remember {
            myListOf(
                Offset(0.1f, 0.2f),
                Offset(0.3f, 0.4f)
            )
        }
        val gradientPainter = remember {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, points[0], Color.Red)
                setVertex(0, 1, points[1], Color.Blue)
            }
        }
    }
    """
      .trimIndent()

  @Test
  fun testParseMeshWithCoordinateVariables() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", coordinateVariablesCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(2, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.1f, 0.2f), v00.offset)
    }
  }

  @Test
  fun testParseMeshWithCollectionAlias() {
    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", collectionAliasCodeTemplate)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh successfully", parsedMesh)

      assertEquals(2, parsedMesh!!.rows)
      assertEquals(2, parsedMesh.cols)
      assertEquals(2, parsedMesh.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Offset(0.1f, 0.2f), v00.offset)
    }
  }

  @Test
  fun testParseNamedArgumentsBezierControlPointsAndUnderscoreHex() {
    val code =
      """
      package test

      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      fun MyMesh() {
          val rowCount = 1
          val colCount = 2
          val useBicubic = true
          val r0 = 0
          val c1 = 1
          val hexColor = 0xFF_12_34_56L
          val rComp = 0.5f
          val gComp = 0.25f
          val bComp = 0.75f
          val gradientPainter = remember {
              MeshGradientPainter(columns = colCount, rows = rowCount, hasBicubicColor = useBicubic) {
                  setVertex(
                      color = Color(hexColor),
                      position = Offset(y = 0.2f, x = 0.1f),
                      column = c1,
                      row = r0,
                      leftControlPoint = Offset(0.05f, 0.15f),
                      bottomControlPoint = Offset(0.12f, 0.25f),
                  )
                  setVertex(
                      row = 0,
                      column = 0,
                      position = Offset(0f, 0f),
                      color = Color(red = rComp, green = gComp, blue = bComp),
                  )
              }
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", code)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull(parsedMesh)
      assertEquals(2, parsedMesh!!.rows)
      assertEquals(3, parsedMesh.cols)
      assertTrue(parsedMesh.hasBicubicColor)
      assertEquals(2, parsedMesh.vertices.size)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Offset(0.1f, 0.2f), v01.offset)
      assertEquals(Color(0xFF123456), v01.color)
      assertEquals(Offset(0.05f, 0.15f), v01.leftBezierOffset)
      assertEquals(Offset.Unspecified, v01.topBezierOffset)
      assertEquals(Offset.Unspecified, v01.rightBezierOffset)
      assertEquals(Offset(0.12f, 0.25f), v01.bottomBezierOffset)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Color(0.5f, 0.25f, 0.75f, 1f), v00.color)
    }
  }

  @Test
  fun testLocalVariableShadowingAndNoForwardReference() {
    val code =
      """
      package test

      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      val color = Color.Red

      fun MyMesh() {
          val color = Color.Green
          val color = remember { color }
          val firstColor = color
          val color = Color.Blue
          val gradientPainter = remember {
              MeshGradientPainter(rows = 1, columns = 1) {
                  setVertex(0, 0, Offset(0f, 0f), firstColor)
                  setVertex(0, 1, Offset(1f, 0f), color)
              }
          }
          val color = Color.Yellow
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", code)
    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)
      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull(parsedMesh)

      val v00 = parsedMesh!!.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Color.Green, v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Color.Blue, v01.color)
    }
  }

  @Test
  fun testUpdateConstructorArgumentsAppendsMissingHasBicubicColor() {
    val code =
      """
      package test

      import androidx.compose.ui.geometry.Offset
      import androidx.compose.ui.graphics.Color

      fun MyMesh() {
          val painter = MeshGradientPainter(2, 3) {
              setVertex(0, 0, Offset(0f, 0f), Color.Red)
          }
      }
      """
        .trimIndent()

    val psiFactory = KtPsiFactory(project)
    val file = psiFactory.createFile("Test.kt", code)
    val psiManager = MeshGradientPsiManager(project)

    WriteCommandAction.runWriteCommandAction(project) {
      val call = psiManager.findMeshPainterCall(file)
      assertNotNull(call)
      val updated = psiManager.updateConstructorArguments(call!!, newRows = 3, newCols = 4, hasBicubicColor = true)
      assertTrue(updated)
    }

    val updatedText = runReadActionBlocking { file.text }
    assertTrue("Should contain updated rows", updatedText.contains("2"))
    assertTrue("Should contain updated columns", updatedText.contains("3"))
    assertTrue("Should append hasBicubicColor = true", updatedText.contains("hasBicubicColor = true"))
  }

  @Test
  fun testCollectAndResolveLocalAndImportedModuleColors() {
    VfsTestUtil.createFile(
      getSourceRoot(),
      "ui/theme/ThemeColors.kt",
      """
      package test.ui.theme

      import androidx.compose.ui.graphics.Color

      val Purple80 = Color(0xFFD0BCFF)
      val PurpleGrey80 = Color(0xFFCCC2DC)
      val Pink80 = Color(0xFFEFB8C8)
      private val ThemePrivateRed = Color(0xFFFF0000)
      """
        .trimIndent(),
    )

    VfsTestUtil.createFile(
      getSourceRoot(),
      "ui/palette/StarColors.kt",
      """
      package test.ui.palette

      import androidx.compose.ui.graphics.Color

      val StarAmber = Color(0xFFFFC107)
      val UnusedStarCyan = Color(0xFF00E5FF)
      private val StarPrivateBlue = Color(0xFF0000FF)
      """
        .trimIndent(),
    )

    VfsTestUtil.createFile(
      getSourceRoot(),
      "SamePkgColors.kt",
      """
      package test

      import androidx.compose.ui.graphics.Color

      val SamePkgCoral = Color(0xFFFF5A5A)
      private val SamePkgPrivateGreen = Color(0xFF00FF00)
      """
        .trimIndent(),
    )

    val mainVFile =
      VfsTestUtil.createFile(
        getSourceRoot(),
        "MyScreen.kt",
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import test.ui.theme.Purple80
        import test.ui.theme.Pink80 as BrandPink
        import test.ui.palette.*

        val FileLevelMint = Color(0xFF00E676)
        private val FileLevelPrivateTeal = Color(0xFF008080)

        fun MyMesh() {
            val localUnusedGold = Color(0xFFFFD700)
            val gradientPainter = remember {
                val blockLocalPrior = Color(0xFFABCDEF)
                MeshGradientPainter(rows = 1, columns = 1) {
                    setVertex(0, 0, Offset(0f, 0f), Purple80)
                    setVertex(0, 1, Offset(1f, 0f), BrandPink)
                    setVertex(1, 0, Offset(0f, 1f), StarAmber)
                    setVertex(1, 1, Offset(1f, 1f), SamePkgCoral)
                }
            }
            val forwardLocalIgnored = Color(0xFF123456)
        }
        """
          .trimIndent(),
      )

    val psiManager = MeshGradientPsiManager(project)

    runReadActionBlocking {
      val mainFile = this.psiManager.findFile(mainVFile) as KtFile
      val call = psiManager.findMeshPainterCall(mainFile)
      assertNotNull("Should find MeshGradientPainter call", call)

      val parsedMesh = psiManager.parseMesh(call!!)
      assertNotNull("Should parse mesh with imported colors", parsedMesh)
      assertEquals(4, parsedMesh!!.vertices.size)

      val v00 = parsedMesh.vertices.first { it.row == 0 && it.col == 0 }
      assertEquals(Color(0xFFD0BCFF), v00.color)

      val v01 = parsedMesh.vertices.first { it.row == 0 && it.col == 1 }
      assertEquals(Color(0xFFEFB8C8), v01.color)

      val v10 = parsedMesh.vertices.first { it.row == 1 && it.col == 0 }
      assertEquals(Color(0xFFFFC107), v10.color)

      val v11 = parsedMesh.vertices.first { it.row == 1 && it.col == 1 }
      assertEquals(Color(0xFFFF5A5A), v11.color)

      val availableColors = psiManager.collectAvailableColors(call)
      assertTrue("Should include localUnusedGold", Color(0xFFFFD700) in availableColors)
      assertTrue("Should include blockLocalPrior", Color(0xFFABCDEF) in availableColors)
      assertTrue("Should include FileLevelMint", Color(0xFF00E676) in availableColors)
      assertTrue("Should include FileLevelPrivateTeal from current file", Color(0xFF008080) in availableColors)
      assertTrue("Should include explicitly imported Purple80", Color(0xFFD0BCFF) in availableColors)
      assertTrue("Should include aliased import BrandPink", Color(0xFFEFB8C8) in availableColors)
      assertTrue("Should include star-imported StarAmber", Color(0xFFFFC107) in availableColors)
      assertTrue("Should include star-imported UnusedStarCyan", Color(0xFF00E5FF) in availableColors)
      assertTrue("Should include same-package SamePkgCoral", Color(0xFFFF5A5A) in availableColors)
      assertTrue("Should not include unimported PurpleGrey80", Color(0xFFCCC2DC) !in availableColors)
      assertTrue("Should not include forward declaration forwardLocalIgnored", Color(0xFF123456) !in availableColors)
      assertTrue("Should not include cross-file private SamePkgPrivateGreen", Color(0xFF00FF00) !in availableColors)
      assertTrue("Should not include cross-file private StarPrivateBlue", Color(0xFF0000FF) !in availableColors)
      assertTrue("Should not include cross-file private ThemePrivateRed", Color(0xFFFF0000) !in availableColors)

      val resolvePrivateCrossFile = resolveImportedOrSamePackageProperty(project, mainFile, "SamePkgPrivateGreen")
      assertNull("Cross-file same-package private property should not be resolved", resolvePrivateCrossFile)

      val resolveStarPrivateCrossFile = resolveImportedOrSamePackageProperty(project, mainFile, "StarPrivateBlue")
      assertNull("Cross-file star-imported private property should not be resolved", resolveStarPrivateCrossFile)
    }
  }
}
