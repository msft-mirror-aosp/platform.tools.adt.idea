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

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.RunsInEdt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Tests the gutter icons added by [GradientLineMarkerProvider] through the regular daemon highlighting passes. */
@RunsInEdt
class GradientLineMarkerGutterTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  @get:Rule val flagRule = FlagRule(StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR, true)

  private val fixture
    get() = projectRule.fixture

  /** A gutter icon, described by the text of the element it is anchored on, the line of that element and its tooltip. */
  private data class Marker(val anchorText: String, val line: Int, val tooltip: String)

  @Test
  fun starImportOfComposeGraphicsIsRecognized() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.*

        fun gradients() {
            val mesh = MeshGradientPainter(rows = 1, columns = 1) {}
            val brush = Brush.verticalGradient(listOf(Color.Red, Color.Blue))
        }
        """
      )

    assertEquals(
      listOf(Marker("MeshGradientPainter", 5, "Edit Mesh Gradient"), Marker("verticalGradient", 6, "Edit Vertical Gradient")),
      markers,
    )
  }

  @Test
  fun aliasedImportsAreRecognized() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.Brush as ComposeBrush
        import androidx.compose.ui.graphics.Brush.Companion.radialGradient as radial
        import androidx.compose.ui.graphics.MeshGradientPainter as Mesh

        fun gradients() {
            val mesh = Mesh(rows = 1, columns = 1) {}
            val linear = ComposeBrush.linearGradient(listOf(Color.Red, Color.Blue))
            val sweep = ComposeBrush.Companion.sweepGradient(listOf(Color.Red, Color.Blue))
            val radial = radial(listOf(Color.Red, Color.Blue))
            val hiddenByAlias = MeshGradientPainter(rows = 1, columns = 1) {}
        }
        """
      )

    assertEquals(
      listOf(
        Marker("Mesh", 7, "Edit Mesh Gradient"),
        Marker("linearGradient", 8, "Edit Linear Gradient"),
        Marker("sweepGradient", 9, "Edit Sweep Gradient"),
        Marker("radial", 10, "Edit Radial Gradient"),
      ),
      markers,
    )
  }

  @Test
  fun unqualifiedBrushFactoryImportedFromCompanionIsRecognized() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.Brush.Companion.horizontalGradient
        import androidx.compose.ui.graphics.Brush.Companion.linearGradient

        fun gradients() {
            val linear = linearGradient(listOf(Color.Red, Color.Blue))
            val horizontal = horizontalGradient(listOf(Color.Red, Color.Blue))
            val notImported = sweepGradient(listOf(Color.Red, Color.Blue))
        }
        """
      )

    assertEquals(
      listOf(Marker("linearGradient", 6, "Edit Linear Gradient"), Marker("horizontalGradient", 7, "Edit Horizontal Gradient")),
      markers,
    )
  }

  @Test
  fun callsWithGradientNamesFromOtherDeclarationsHaveNoMarker() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.*
        import com.example.Brush
        import com.example.MeshGradientPainter

        object Gradients {
            fun linearGradient(): Int = 0
        }

        fun notGradients() {
            Gradients.linearGradient()
            Brush.linearGradient()
            MeshGradientPainter(rows = 1, columns = 1)
            com.example.MeshGradientPainter(rows = 1, columns = 1)
            androidx.compose.ui.graphics.Brush?.radialGradient()
            linearGradient()
        }
        """
      )

    assertEquals(emptyList<Marker>(), markers)
  }

  @Test
  fun unresolvableCallsWithoutImportsHaveNoMarker() {
    val markers =
      gradientMarkers(
        """
        package test

        fun notImported() {
            val mesh = MeshGradientPainter(rows = 1, columns = 1) {}
            val linear = Brush.linearGradient(listOf(Color.Red, Color.Blue))
        }
        """
      )

    assertEquals(emptyList<Marker>(), markers)
  }

  @Test
  fun severalGradientsOnOneLineGetOneMarkerEachOnTheirCallee() {
    val code =
      """
      package test

      import androidx.compose.ui.graphics.Brush

      val brushes = listOf(Brush.linearGradient(colors), Brush.radialGradient(colors), Brush.sweepGradient(colors))
      """
        .trimIndent()
    fixture.configureByText("Test.kt", code)
    fixture.doHighlighting()

    val infos = DaemonCodeAnalyzerImpl.getLineMarkers(fixture.editor.document, projectRule.project).filter { it.isGradientMarker() }

    assertEquals(
      listOf("linearGradient", "radialGradient", "sweepGradient").map { code.indexOf(it) },
      infos.map { it.startOffset }.sorted(),
    )
    assertEquals(
      listOf(
        Marker("linearGradient", 4, "Edit Linear Gradient"),
        Marker("radialGradient", 4, "Edit Radial Gradient"),
        Marker("sweepGradient", 4, "Edit Sweep Gradient"),
      ),
      infos.toMarkers(),
    )
  }

  @Test
  fun aliasedImportHidesSimpleNameFromStarImport() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.*
        import androidx.compose.ui.graphics.Brush as ComposeBrush
        import androidx.compose.ui.graphics.MeshGradientPainter as Mesh

        fun gradients() {
            val hiddenMesh = MeshGradientPainter(rows = 1, columns = 1) {}
            val hiddenLinear = Brush.linearGradient(listOf(Color.Red, Color.Blue))
            val mesh = Mesh(rows = 1, columns = 1) {}
            val linear = ComposeBrush.linearGradient(listOf(Color.Red, Color.Blue))
        }
        """
      )

    assertEquals(listOf(Marker("Mesh", 9, "Edit Mesh Gradient"), Marker("linearGradient", 10, "Edit Linear Gradient")), markers)
  }

  @Test
  fun topLevelDeclarationsOfTheFileShadowStarImport() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.*

        class MeshGradientPainter(rows: Int, columns: Int)

        object Brush {
            fun linearGradient(): Int = 0
        }

        fun notGradients() {
            MeshGradientPainter(rows = 1, columns = 1)
            Brush.linearGradient()
        }
        """
      )

    assertEquals(emptyList<Marker>(), markers)
  }

  @Test
  fun fullyQualifiedCallsAreRecognized() {
    val markers =
      gradientMarkers(
        """
        package test

        fun gradients() {
            val linear = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Red, Color.Blue))
            val sweep = androidx.compose.ui.graphics.Brush.Companion.sweepGradient(listOf(Color.Red, Color.Blue))
            val mesh = androidx.compose.ui.graphics.MeshGradientPainter(rows = 1, columns = 1) {}
        }
        """
      )

    assertEquals(
      listOf(
        Marker("linearGradient", 3, "Edit Linear Gradient"),
        Marker("sweepGradient", 4, "Edit Sweep Gradient"),
        Marker("MeshGradientPainter", 5, "Edit Mesh Gradient"),
      ),
      markers,
    )
  }

  @Test
  fun callsInComposeGraphicsPackageNeedNoImports() {
    val markers =
      gradientMarkers(
        """
        package androidx.compose.ui.graphics

        fun gradients() {
            val mesh = MeshGradientPainter(rows = 1, columns = 1) {}
            val radial = Brush.radialGradient(listOf(Color.Red, Color.Blue))
        }
        """
      )

    assertEquals(
      listOf(Marker("MeshGradientPainter", 3, "Edit Mesh Gradient"), Marker("radialGradient", 4, "Edit Radial Gradient")),
      markers,
    )
  }

  @Test
  fun brushCompanionReceiverIsRecognized() {
    val markers =
      gradientMarkers(
        """
        package test

        import androidx.compose.ui.graphics.Brush

        val linear = Brush.Companion.linearGradient(listOf(Color.Red, Color.Blue))
        """
      )

    assertEquals(listOf(Marker("linearGradient", 4, "Edit Linear Gradient")), markers)
  }

  @Test
  fun gutterRendererIsStableAcrossPasses() {
    fixture.configureByText(
      "Test.kt",
      """
      package test

      import androidx.compose.ui.graphics.Brush

      val linear = Brush.linearGradient(listOf(Color.Red, Color.Blue))
      """
        .trimIndent(),
    )
    val provider = GradientLineMarkerProvider()
    val callee = fixture.file.findElementAt(fixture.file.text.indexOf("linearGradient"))!!

    val firstPass = provider.getLineMarkerInfo(callee)!!.createGutterRenderer()
    val secondPass = provider.getLineMarkerInfo(callee)!!.createGutterRenderer()

    // The daemon only replaces gutter icons whose renderers are not equal to the previous ones.
    assertEquals(firstPass, secondPass)
  }

  @Test
  fun markersAreComputedWithoutResolvingReferences() {
    fixture.configureByText(
      "Test.kt",
      """
      package test

      import androidx.compose.ui.graphics.*

      fun gradients() {
          val mesh = MeshGradientPainter(rows = 1, columns = 1) {}
      }
      """
        .trimIndent(),
    )
    val provider = GradientLineMarkerProvider()

    // Resolving references needs indexes, which are unavailable in dumb mode.
    val anchors =
      DumbModeTestUtils.computeInDumbModeSynchronously(projectRule.project) {
        SyntaxTraverser.psiTraverser(fixture.file)
          .filter(LeafPsiElement::class.java)
          .mapNotNull { provider.getLineMarkerInfo(it)?.element?.text }
          .toList()
      }

    assertEquals(listOf("MeshGradientPainter"), anchors)
  }

  private fun gradientMarkers(code: String): List<Marker> {
    fixture.configureByText("Test.kt", code.trimIndent())
    fixture.doHighlighting()
    return DaemonCodeAnalyzerImpl.getLineMarkers(fixture.editor.document, projectRule.project).filter { it.isGradientMarker() }.toMarkers()
  }

  private fun LineMarkerInfo<*>.isGradientMarker(): Boolean = this is GradientLineMarkerInfo

  private fun List<LineMarkerInfo<*>>.toMarkers(): List<Marker> {
    val document = fixture.editor.document
    return sortedBy { it.startOffset }.map { Marker(it.element!!.text, document.getLineNumber(it.startOffset), it.lineMarkerTooltip!!) }
  }
}
