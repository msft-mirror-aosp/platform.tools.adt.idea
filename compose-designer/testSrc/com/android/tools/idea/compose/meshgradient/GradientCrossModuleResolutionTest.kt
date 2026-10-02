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

import androidx.compose.ui.graphics.Color
import com.android.tools.idea.gradle.model.IdeAndroidProjectType.PROJECT_TYPE_APP
import com.android.tools.idea.gradle.model.IdeAndroidProjectType.PROJECT_TYPE_LIBRARY
import com.android.tools.idea.testing.AndroidModuleDependency
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder.Companion.rootModuleBuilder
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private val BRAND_BLUE = Color(0xFF1A73E8)
private val BRAND_ACCENT = Color(0xFFFBBC04)
private val UNRELATED_RED = Color(0xFFD93025)

/** Tests that top-level properties are resolved across modules, following the module dependencies of the file being parsed. */
@RunWith(JUnit4::class)
class GradientCrossModuleResolutionTest {
  @get:Rule
  val projectRule =
    AndroidProjectRule.withAndroidModels(
      rootModuleBuilder,
      AndroidModuleModelBuilder(":designsystem", "debug", AndroidProjectBuilder(projectType = { PROJECT_TYPE_LIBRARY })),
      AndroidModuleModelBuilder(":unrelated", "debug", AndroidProjectBuilder(projectType = { PROJECT_TYPE_LIBRARY })),
      AndroidModuleModelBuilder(
        ":app",
        "debug",
        AndroidProjectBuilder(
          projectType = { PROJECT_TYPE_APP },
          androidModuleDependencyList = { listOf(AndroidModuleDependency(moduleGradlePath = ":designsystem", variant = "debug")) },
        ),
      ),
    )

  private val project
    get() = projectRule.project

  private val fixture
    get() = projectRule.fixture

  private lateinit var appFile: KtFile

  @Before
  fun setUp() {
    fixture.addFileToProject(
      "designsystem/src/main/java/com/example/designsystem/Colors.kt",
      // language=kotlin
      """
      package com.example.designsystem

      import androidx.compose.ui.graphics.Color

      val BrandBlue = Color(0xFF1A73E8)
      val BrandAccent = Color(0xFFFBBC04)
      """
        .trimIndent(),
    )
    fixture.addFileToProject(
      "unrelated/src/main/java/com/example/unrelated/Colors.kt",
      // language=kotlin
      """
      package com.example.unrelated

      import androidx.compose.ui.graphics.Color

      val UnrelatedRed = Color(0xFFD93025)
      """
        .trimIndent(),
    )
    appFile =
      fixture.addFileToProject(
        "app/src/main/java/com/example/app/Screen.kt",
        // language=kotlin
        """
        package com.example.app

        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.MeshGradientPainter
        import com.example.designsystem.BrandBlue
        import com.example.designsystem.*
        import com.example.unrelated.UnrelatedRed

        fun MyMesh() {
            MeshGradientPainter(rows = 1, columns = 1) {
                setVertex(0, 0, Offset(0f, 0f), BrandBlue)
                setVertex(0, 1, Offset(1f, 0f), UnrelatedRed)
            }
        }
        """
          .trimIndent(),
      ) as KtFile
    IndexingTestUtil.waitUntilIndexesAreReady(project)
  }

  @Test
  fun resolvesPropertiesFromDependencyModules() {
    runReadActionBlocking {
      val psiManager = GradientPsiManager(project)
      val call = checkNotNull(psiManager.findMeshPainterCall(appFile))

      val mesh = checkNotNull(psiManager.parseMesh(call))
      assertEquals(BRAND_BLUE, mesh.vertices.firstOrNull { it.row == 0 && it.col == 0 }?.color)
      assertNull("Modules that are not dependencies are not visible", mesh.vertices.firstOrNull { it.row == 0 && it.col == 1 })
      assertTrue(mesh.hasDynamicOrUnresolvedValues)

      assertNotNull(resolveImportedOrSamePackageProperty(project, appFile, "BrandAccent"))
      assertNull(resolveImportedOrSamePackageProperty(project, appFile, "UnrelatedRed"))
    }
  }

  @Test
  fun collectsColorsFromDependencyModules() {
    runReadActionBlocking {
      val psiManager = GradientPsiManager(project)
      val call = checkNotNull(psiManager.findMeshPainterCall(appFile))

      val colors = psiManager.collectAvailableColors(call)
      assertTrue("Should include explicitly imported BrandBlue", BRAND_BLUE in colors)
      assertTrue("Should include star-imported BrandAccent", BRAND_ACCENT in colors)
      assertFalse("Should not include UnrelatedRed from a module that is not a dependency", UNRELATED_RED in colors)
    }
  }

  @Test
  fun explicitImportTakesPriorityOverPackageAndStarImports() {
    fixture.addFileToProject(
      "designsystem/src/main/java/com/example/designsystem/Computed.kt",
      // language=kotlin
      """
      package com.example.designsystem

      import androidx.compose.ui.graphics.Color

      val Accent: Color
          get() = Color(0xFF34A853)
      """
        .trimIndent(),
    )
    fixture.addFileToProject(
      "designsystem/src/main/java/com/example/designsystem/other/Colors.kt",
      // language=kotlin
      """
      package com.example.designsystem.other

      import androidx.compose.ui.graphics.Color

      val Accent = Color(0xFF4285F4)
      """
        .trimIndent(),
    )
    fixture.addFileToProject(
      "app/src/main/java/com/example/app/AppColors.kt",
      // language=kotlin
      """
      package com.example.app

      import androidx.compose.ui.graphics.Color

      val Accent = Color(0xFFEA4335)
      """
        .trimIndent(),
    )
    val importingFile =
      fixture.addFileToProject(
        "app/src/main/java/com/example/app/Importing.kt",
        // language=kotlin
        """
        package com.example.app

        import com.example.designsystem.Accent
        import com.example.designsystem.other.*

        val Copy = Accent
        """
          .trimIndent(),
      ) as KtFile
    IndexingTestUtil.waitUntilIndexesAreReady(project)

    runReadActionBlocking {
      assertNotNull(
        "Without an explicit import, the same-package property is found",
        resolveImportedOrSamePackageProperty(project, appFile, "Accent"),
      )
      assertNull(
        "The explicitly imported property has no initializer and must not fall back to other candidates",
        resolveImportedOrSamePackageProperty(project, importingFile, "Accent"),
      )
    }
  }
}
