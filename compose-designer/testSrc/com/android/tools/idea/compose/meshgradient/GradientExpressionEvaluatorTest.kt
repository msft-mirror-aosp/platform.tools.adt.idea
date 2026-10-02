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
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the static expression evaluator used by [GradientPsiManager] to read gradient arguments. */
@RunWith(JUnit4::class)
class GradientExpressionEvaluatorTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val project
    get() = projectRule.project

  private fun createFile(code: String): KtFile = KtPsiFactory(project).createFile("Test.kt", code.trimIndent())

  private fun parseMesh(code: String): GradientPsiManager.ParsedMesh? = runReadActionBlocking {
    val psiManager = GradientPsiManager(project)
    val call = checkNotNull(createFile(code).findMeshPainterCall()) { "Should find MeshGradientPainter call" }
    psiManager.parseMesh(call)
  }

  private fun findLinearGradientCall(file: KtFile): KtCallExpression =
    PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).first { it.calleeExpression?.text == FUN_LINEAR_GRADIENT }

  private fun parseLinearGradient(code: String): Gradient.LinearGradient = runReadActionBlocking {
    checkNotNull(GradientPsiManager(project).parseLinearGradient(findLinearGradientCall(createFile(code)))) {
      "Should parse linearGradient call"
    }
  }

  private fun GradientPsiManager.ParsedMesh.vertexAt(row: Int, col: Int) = vertices.firstOrNull { it.row == row && it.col == col }

  @Test
  fun topLevelSelfReferenceIsUnresolved() {
    val mesh =
      parseMesh(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter

        val x = x + 1f

        fun MyMesh() {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, Offset(x, 0f), Color.Red)
                setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            }
        }
        """
      )

    checkNotNull(mesh)
    assertTrue(mesh.hasDynamicOrUnresolvedValues)
    assertNull("Self-referencing offset should not be resolved", mesh.vertexAt(0, 0))
    assertEquals(Offset(1f, 0f), mesh.vertexAt(0, 1)?.offset)
  }

  @Test
  fun mutuallyRecursiveTopLevelPropertiesAreUnresolved() {
    val mesh =
      parseMesh(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter

        val a = b * 2f
        val b = a / 2f

        fun MyMesh() {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, Offset(a, b), Color.Red)
                setVertex(0, 1, Offset(1f, 0f), Color.Blue)
            }
        }
        """
      )

    checkNotNull(mesh)
    assertTrue(mesh.hasDynamicOrUnresolvedValues)
    assertNull("Mutually recursive offset should not be resolved", mesh.vertexAt(0, 0))
    assertEquals(Offset(1f, 0f), mesh.vertexAt(0, 1)?.offset)
  }

  @Test
  fun classBodySelfReferenceIsUnresolved() {
    val mesh =
      parseMesh(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter

        class Holder {
            val x = x + 1f

            fun MyMesh() {
                MeshGradientPainter(rows = 1, columns = 1) {
                    setVertex(0, 0, Offset(x, 0f), Color.Red)
                    setVertex(0, 1, Offset(1f, 0f), Color.Blue)
                }
            }
        }
        """
      )

    checkNotNull(mesh)
    assertTrue(mesh.hasDynamicOrUnresolvedValues)
    assertNull("Self-referencing offset should not be resolved", mesh.vertexAt(0, 0))
    assertEquals(Offset(1f, 0f), mesh.vertexAt(0, 1)?.offset)
  }

  @Test
  fun localPropertyCanShadowOuterPropertyWithSameName() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val x = 0.25f

        fun MyBrush() {
            val x = x + 0.25f
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(x, 0f))
        }
        """
      )

    assertEquals(Offset(0.5f, 0f), gradient.start)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun repeatedReferencesToTheSamePropertyAreNotCycles() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val a = 0.25f
            val p = Offset(a, a)
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = p + p, end = Offset(a * 4f, a))
        }
        """
      )

    assertEquals(Offset(0.5f, 0.5f), gradient.start)
    assertEquals(Offset(1f, 0.25f), gradient.end)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun functionParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        fun MyBrush(tint: Color) {
            val brush = Brush.linearGradient(colors = listOf(tint, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun constructorParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        class Themed(tint: Color) {
            val brush = Brush.linearGradient(colors = listOf(tint, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun lambdaParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        fun MyBrush() {
            listOf(Color.Green).forEach { tint -> Brush.linearGradient(colors = listOf(tint, Color.Blue)) }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun destructuredLambdaParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        fun MyBrush() {
            listOf(Color.Green to Color.Cyan).forEach { (tint, _) -> Brush.linearGradient(colors = listOf(tint, Color.Blue)) }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun implicitLambdaParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val it = Color.Red

        fun MyBrush() {
            listOf(Color.Green).forEach { Brush.linearGradient(colors = listOf(it, Color.Blue)) }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun forLoopVariableShadowsTopLevelProperty() {
    val mesh =
      parseMesh(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter

        val i = 0

        fun MyMesh() {
            MeshGradientPainter(rows = 1, columns = 1) {
                for (i in 0..1) {
                    setVertex(i, 0, Offset(0f, 0f), Color.Red)
                }
                setVertex(1, 1, Offset(1f, 1f), Color.Blue)
            }
        }
        """
      )

    checkNotNull(mesh)
    assertTrue(mesh.hasDynamicOrUnresolvedValues)
    assertEquals(listOf(1 to 1), mesh.vertices.map { it.row to it.col })
  }

  @Test
  fun destructuringDeclarationShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val first = Color.Red

        fun MyBrush() {
            val (first, second) = Color.Green to Color.Cyan
            val brush = Brush.linearGradient(colors = listOf(first, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun whenSubjectVariableShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        fun MyBrush() {
            val brush = when (val tint = Color.Green) {
                else -> Brush.linearGradient(colors = listOf(tint, Color.Blue))
            }
        }
        """
      )

    assertEquals(listOf(Color.Green, Color.Blue), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun varPropertyIsDynamic() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            var tint = Color.Red
            tint = Color.Green
            val brush = Brush.linearGradient(colors = listOf(tint, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun memberFunctionDoesNotSeePlainConstructorParameter() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        class Themed(tint: Color) {
            fun brush() = Brush.linearGradient(colors = listOf(tint, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun propertyConstructorParameterIsDynamicInMemberFunction() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        class Themed(val tint: Color) {
            fun brush() = Brush.linearGradient(colors = listOf(tint, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun companionObjectPropertyShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        class Themed {
            fun brush() = Brush.linearGradient(colors = listOf(tint, Color.Blue))

            companion object {
                val tint = Color.Green
            }
        }
        """
      )

    assertEquals(listOf(Color.Green, Color.Blue), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun constructorParameterShadowsMemberInInitializers() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        class Themed(s: Float) {
            val s = 0.5f
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(s, 0f))
        }
        """
      )

    assertEquals(Offset.Zero, gradient.start)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun initBlockSeesConstructorParameter() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        class Themed(tint: Color) {
            init {
                listOf(1).forEach { Brush.linearGradient(colors = listOf(tint, Color.Blue)) }
            }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun catchParameterShadowsTopLevelProperty() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red

        fun MyBrush() {
            try {
            } catch (tint: Exception) {
                val brush = Brush.linearGradient(colors = listOf(tint, Color.Blue))
            }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun nestedLambdaParametersShadowTopLevelProperties() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val tint = Color.Red
        val it = Color.Green

        fun MyBrush() {
            listOf(Color.Cyan).forEach { tint ->
                listOf(Color.Yellow).forEach { Brush.linearGradient(colors = listOf(tint, it, Color.Blue)) }
            }
        }
        """
      )

    assertEquals(listOf(Color.White, Color.White, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  private fun chainedPropertiesCode(length: Int, step: (Int) -> String): String = buildString {
    appendLine("package test")
    appendLine("import androidx.compose.ui.geometry.Offset")
    appendLine("import androidx.compose.ui.graphics.Brush")
    appendLine("import androidx.compose.ui.graphics.Color")
    appendLine("val v0 = 0.25f")
    for (i in 1..length) appendLine("val v$i = ${step(i)}")
    appendLine("fun MyBrush() {")
    appendLine("    val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(v$length, 0f))")
    appendLine("}")
  }

  @Test
  fun shallowReferenceChainIsResolved() {
    val gradient = parseLinearGradient(chainedPropertiesCode(20) { "v${it - 1} + 1f" })

    assertEquals(Offset(20.25f, 0f), gradient.start)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun referenceChainDeeperThanTheDepthLimitIsUnresolved() {
    val gradient = parseLinearGradient(chainedPropertiesCode(70) { "v${it - 1} + 1f" })

    assertEquals(Offset.Zero, gradient.start)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun exponentialReferenceGraphIsBounded() {
    val gradient = parseLinearGradient(chainedPropertiesCode(30) { "v${it - 1} + v${it - 1}" })

    assertEquals(Offset.Zero, gradient.start)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun mutuallyRecursivePropertiesAcrossFilesAreUnresolved() {
    val fixture = projectRule.fixture
    fixture.addFileToProject("src/test/A.kt", "package test\n\nval a = b * 2f\n")
    fixture.addFileToProject("src/test/B.kt", "package test\n\nval b = a / 2f\n")
    val mainFile =
      fixture.addFileToProject(
        "src/test/Main.kt",
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(a, 0f))
        }
        """
          .trimIndent(),
      ) as KtFile
    IndexingTestUtil.waitUntilIndexesAreReady(project)

    val gradient = runReadActionBlocking {
      assertNotNull(resolveImportedOrSamePackageProperty(project, mainFile, "a"))
      GradientPsiManager(project).parseLinearGradient(findLinearGradientCall(mainFile))
    }

    checkNotNull(gradient)
    assertEquals(Offset.Zero, gradient.start)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun meshDimensionsAndIndicesSupportIntegerArithmetic() {
    val mesh =
      parseMesh(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter

        val N = 2

        fun MyMesh() {
            val points = listOf(Offset(0f, 0f), Offset(0.5f, 0.5f))
            MeshGradientPainter(rows = N - 1, columns = N * 2 - 2) {
                setVertex(N - 2, (N + 1) % N, points[N - 1], Color.Red)
                setVertex(-(-1), 0x2, Offset(1f, 1f), Color.Blue)
            }
        }
        """
      )

    checkNotNull(mesh)
    assertEquals(2, mesh.rows)
    assertEquals(3, mesh.cols)
    assertEquals(Offset(0.5f, 0.5f), mesh.vertexAt(0, 1)?.offset)
    assertEquals(Offset(1f, 1f), mesh.vertexAt(1, 2)?.offset)
  }

  @Test
  fun intColorComponentsUseIntegerSemantics() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        val HALF = 255 / 2

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color(HALF, 0x80, 0), Color(red = 1f / 2, green = 0f, blue = 0f)))
        }
        """
      )

    assertEquals(listOf(Color(127, 128, 0), Color(0.5f, 0f, 0f)), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun intColorComponentsKeepTheirLowest8Bits() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color(256, -1, 0), Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Green, Color.Blue), gradient.colors)
  }

  @Test
  fun floatExpressionsUseIntegerSemanticsForIntegerOperands() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), start = Offset(1f * (3 / 2), 7f % 4f))
        }
        """
      )

    assertEquals(Offset(1f, 3f), gradient.start)
  }

  @Test
  fun unresolvedTileModeNameIsDynamic() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), tileMode = myMirror)
        }
        """
      )

    assertEquals(TileMode.Clamp, gradient.tileMode)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun tileModeFromImportedEntry() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.TileMode.Companion.Mirror

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), tileMode = Mirror)
        }
        """
      )

    assertEquals(TileMode.Mirror, gradient.tileMode)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun tileModeFromAliasedClass() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.TileMode as Tiling

        fun MyBrush() {
            val mode = Tiling.Decal
            val brush = Brush.linearGradient(colors = listOf(Color.Red, Color.Blue), tileMode = mode)
        }
        """
      )

    assertEquals(TileMode.Decal, gradient.tileMode)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun unspecifiedColorIsResolvedButNotOfferedInPalette() {
    val code =
      """
      package test

      import androidx.compose.ui.graphics.Brush
      import androidx.compose.ui.graphics.Color

      fun MyBrush() {
          val none = Color.Unspecified
          val brush = Brush.linearGradient(colors = listOf(none, Color.Blue))
      }
      """
    val gradient = parseLinearGradient(code)
    assertEquals(listOf(Color.Unspecified, Color.Blue), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)

    val palette = runReadActionBlocking { GradientPsiManager(project).collectAvailableColors(findLinearGradientCall(createFile(code))) }
    assertFalse(Color.Unspecified in palette)
  }

  @Test
  fun nonSrgbColorSpaceIsDynamic() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.colorspace.ColorSpaces

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color(1f, 0f, 0f, 1f, ColorSpaces.DisplayP3), Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun srgbColorSpaceIsNotDynamic() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.colorspace.ColorSpaces

        fun MyBrush() {
            val brush = Brush.linearGradient(colors = listOf(Color(1f, 0f, 0f, colorSpace = ColorSpaces.Srgb), Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun unresolvedAlphaIsDynamic() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush(fade: Float) {
            val brush = Brush.linearGradient(colors = listOf(Color(1f, 0f, 0f, alpha = fade), Color.Blue))
        }
        """
      )

    assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
    assertTrue(gradient.hasDynamicOrUnresolvedValues)
  }

  @Test
  fun namedSpreadColorStops() {
    val gradient =
      parseLinearGradient(
        """
        package test

        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color

        fun MyBrush() {
            val brush = Brush.linearGradient(colorStops = *arrayOf(0f to Color.Red, 1f to Color.Blue))
        }
        """
      )

    assertEquals(listOf(0f to Color.Red, 1f to Color.Blue), gradient.colorStops)
    assertFalse(gradient.hasDynamicOrUnresolvedValues)
  }
}
