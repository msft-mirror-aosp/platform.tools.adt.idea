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

import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.testFramework.LightPlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class GradientLineMarkerProviderTest : LightPlatformTestCase() {

  private val provider = GradientLineMarkerProvider()

  override fun setUp() {
    super.setUp()
    StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.override(true)
  }

  override fun tearDown() {
    try {
      StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.clearOverride()
    } finally {
      super.tearDown()
    }
  }

  fun testLineMarkerShownOnlyOnCalleeIdentifier() {
    val code =
      """
      package test

      import androidx.compose.ui.graphics.MeshGradientPainter

      fun MyMesh() {
          val MeshGradientPainter = 1
          val painter = MeshGradientPainter(rows = MeshGradientPainter, columns = 1) {
              setVertex(0, 0, Offset(0f, 0f), Color.Red)
          }
      }
      """
        .trimIndent()

    val file = createFile("Test.kt", code) as KtFile
    val markers = runReadActionBlocking {
      SyntaxTraverser.psiTraverser(file).filter(LeafPsiElement::class.java).mapNotNull { provider.getLineMarkerInfo(it) }
    }

    assertEquals("Should only attach a single line marker to the constructor callee", 1, markers.size)
    assertEquals("MeshGradientPainter", runReadActionBlocking { markers.single().element?.text })
  }

  fun testLineMarkerSupportsImportAliasAndFqn() {
    val code =
      """
      package test

      import androidx.compose.ui.graphics.MeshGradientPainter as AliasedMeshPainter

      fun MyMesh() {
          val p1 = AliasedMeshPainter(rows = 1, columns = 1) {}
          val p2 = androidx.compose.ui.graphics.MeshGradientPainter(rows = 1, columns = 1) {}
          val p3 = com.other.pkg.MeshGradientPainter(rows = 1, columns = 1) {}
          val p4 = MeshGradientPainter(rows = 1, columns = 1) {}
      }
      """
        .trimIndent()

    val file = createFile("Test2.kt", code) as KtFile
    val markers = runReadActionBlocking {
      SyntaxTraverser.psiTraverser(file).filter(LeafPsiElement::class.java).mapNotNull { provider.getLineMarkerInfo(it) }
    }

    assertEquals(listOf("AliasedMeshPainter", "MeshGradientPainter"), runReadActionBlocking { markers.mapNotNull { it.element?.text } })
  }

  fun testLineMarkerRespectsFeatureFlag() {
    StudioFlags.COMPOSE_MESH_GRADIENT_EDITOR.override(false)
    val code =
      """
      package test

      import androidx.compose.ui.graphics.MeshGradientPainter

      fun MyMesh() {
          val painter = MeshGradientPainter(rows = 1, columns = 1) {}
      }
      """
        .trimIndent()

    val file = createFile("Test3.kt", code) as KtFile
    val markers = runReadActionBlocking {
      SyntaxTraverser.psiTraverser(file).filter(LeafPsiElement::class.java).mapNotNull { provider.getLineMarkerInfo(it) }
    }
    assertTrue(markers.isEmpty())
  }
}
