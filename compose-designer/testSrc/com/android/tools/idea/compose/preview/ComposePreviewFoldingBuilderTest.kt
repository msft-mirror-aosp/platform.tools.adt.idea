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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.loadNewFile
import com.google.common.truth.Truth.assertThat
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.application.readAction
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import kotlinx.coroutines.test.runTest
import org.jetbrains.android.compose.stubComposableAnnotation
import org.jetbrains.android.compose.stubPreviewAnnotation
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Test for [ComposePreviewFoldingBuilder]. */
class ComposePreviewFoldingBuilderTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val myFixture: CodeInsightTestFixtureImpl by lazy { projectRule.fixture as CodeInsightTestFixtureImpl }

  @Before
  fun setUp() {
    (myFixture.module.getModuleSystem() as DefaultModuleSystem).usesCompose = true
    myFixture.stubComposableAnnotation()
  }

  @Test
  fun `validate preview function body folding generated and collapsed state follows setting`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/PreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun HomeScreenPreview() {
          HomeScreen()
        }

        @Composable
        fun NonPreviewComposable() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptorsQuick = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ true)
    }
    assertThat(descriptorsQuick).isEmpty()

    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).hasLength(1)

    val previewDescriptor = descriptors[0]
    readAction {
      assertThat(previewDescriptor.element.text).isEqualTo("{\n  HomeScreen()\n}")
      val placeholderText = foldingBuilder.getPlaceholderText(previewDescriptor.element, previewDescriptor.range)
      assertThat(placeholderText).isEqualTo("{...}")
    }

    val previewSettings = ComposePreviewSettings.getInstance()
    val previousSetting = previewSettings.state.isFoldComposePreviewFunctions
    try {
      previewSettings.state.isFoldComposePreviewFunctions = false
      assertThat(foldingBuilder.isCollapsedByDefault(previewDescriptor.element)).isFalse()

      previewSettings.state.isFoldComposePreviewFunctions = true
      assertThat(foldingBuilder.isCollapsedByDefault(previewDescriptor.element)).isTrue()
    } finally {
      previewSettings.state.isFoldComposePreviewFunctions = previousSetting
    }
  }

  @Test
  fun `validate code folding options provider configuration`() {
    val provider = ComposePreviewCodeFoldingOptionsProvider()
    assertThat(provider.title).isEqualTo("Compose")

    val previewSettings = ComposePreviewSettings.getInstance()
    val previousSetting = previewSettings.state.isFoldComposePreviewFunctions
    try {
      previewSettings.state.isFoldComposePreviewFunctions = false
      provider.reset()
      assertThat(provider.isModified).isFalse()

      previewSettings.state.isFoldComposePreviewFunctions = true
      assertThat(provider.isModified).isTrue()

      provider.apply()
      assertThat(previewSettings.state.isFoldComposePreviewFunctions).isFalse()
    } finally {
      previewSettings.state.isFoldComposePreviewFunctions = previousSetting
    }
  }

  @Test
  fun `validate multipreview function body folding`() = runTest {
    myFixture.stubPreviewAnnotation()
    myFixture.addFileToProject(
      "src/com/example/CustomPreview.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      annotation class CustomTheme
      """
        .trimIndent(),
    )

    val multiPreviewTestFile =
      myFixture.loadNewFile(
        "src/com/example/MultiPreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable

        @CustomTheme
        @Composable
        fun MultiPreviewComponent() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(multiPreviewTestFile, multiPreviewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).hasLength(1)

    val previewDescriptor = descriptors[0]
    readAction {
      assertThat(previewDescriptor.element.text).isEqualTo("{\n  HomeScreen()\n}")
      val placeholderText = foldingBuilder.getPlaceholderText(previewDescriptor.element, previewDescriptor.range)
      assertThat(placeholderText).isEqualTo("{...}")
    }

    val previewSettings = ComposePreviewSettings.getInstance()
    val previousSetting = previewSettings.state.isFoldComposePreviewFunctions
    try {
      previewSettings.state.isFoldComposePreviewFunctions = true
      assertThat(foldingBuilder.isCollapsedByDefault(previewDescriptor.element)).isTrue()
    } finally {
      previewSettings.state.isFoldComposePreviewFunctions = previousSetting
    }
  }

  @Test
  fun `validate preview function inside class or object is folded`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/ClassPreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        class PreviewContainer {
          @Preview
          @Composable
          fun NestedPreview() {
            HomeScreen()
          }
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).hasLength(1)
  }

  @Test
  fun `validate compose preview folding builder is registered before kotlin folding builder`() = runTest {
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/PreviewCheck.kt",
        // language=kotlin
        """
        package com.example
        """
          .trimIndent(),
      )
    val builders = LanguageFolding.INSTANCE.allForLanguage(previewTestFile.language)
    val composeBuilderIndex = builders.indexOfFirst { it is ComposePreviewFoldingBuilder }
    val kotlinBuilderIndex = builders.indexOfFirst { it.javaClass.simpleName == "KotlinFoldingBuilder" }
    assertThat(composeBuilderIndex).isAtLeast(0)
    assertThat(kotlinBuilderIndex).isAtLeast(0)
    assertThat(composeBuilderIndex).isLessThan(kotlinBuilderIndex)
  }

  @Test
  fun `validate composite folding builder collapses preview function by default when setting is enabled`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/PreviewTestComposite.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun HomeScreenPreview() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val compositeBuilder = LanguageFolding.INSTANCE.forLanguage(previewTestFile.language) as FoldingBuilderEx
    val descriptors = readAction {
      compositeBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }

    val previewDescriptor = descriptors.firstOrNull { it.element.text == "{\n  HomeScreen()\n}" }
    assertThat(previewDescriptor).isNotNull()

    val previewSettings = ComposePreviewSettings.getInstance()
    val previousSetting = previewSettings.state.isFoldComposePreviewFunctions
    try {
      previewSettings.state.isFoldComposePreviewFunctions = true
      assertThat(compositeBuilder.isCollapsedByDefault(previewDescriptor!!)).isTrue()

      previewSettings.state.isFoldComposePreviewFunctions = false
      assertThat(compositeBuilder.isCollapsedByDefault(previewDescriptor)).isFalse()
    } finally {
      previewSettings.state.isFoldComposePreviewFunctions = previousSetting
    }
  }

  @Test
  fun `validate single line preview function produces no folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/SingleLinePreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun SingleLinePreview() { HomeScreen() }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).isEmpty()
  }

  @Test
  fun `validate expression bodied preview function produces no folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/ExpressionBodyPreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun ExpressionBodyPreview() = HomeScreen()
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).isEmpty()
  }

  @Test
  fun `validate non composable preview function produces no folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val testFile =
      myFixture.loadNewFile(
        "src/com/example/NonComposablePreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        fun NonComposableFunction() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(testFile, testFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).isEmpty()
  }

  @Test
  fun `validate module not using compose produces no folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/NoComposeModulePreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun HomeScreenPreview() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val defaultModuleSystem = myFixture.module.getModuleSystem() as DefaultModuleSystem
    defaultModuleSystem.usesCompose = false
    try {
      val foldingBuilder = ComposePreviewFoldingBuilder()
      val descriptors = readAction {
        foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
      }
      assertThat(descriptors).isEmpty()
    } finally {
      defaultModuleSystem.usesCompose = true
    }
  }

  @Test
  fun `validate multiple preview functions produce multiple folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/MultiplePreviewsTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun PreviewOne() {
          HomeScreen()
        }

        @Composable
        fun NonPreviewComposable() {
          HomeScreen()
        }

        @Preview
        @Composable
        fun PreviewTwo() {
          DetailScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).hasLength(2)
  }

  @Test
  fun `validate quick mode produces no folding regions`() = runTest {
    myFixture.stubPreviewAnnotation()
    val previewTestFile =
      myFixture.loadNewFile(
        "src/com/example/QuickModePreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun HomeScreenPreview() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(previewTestFile, previewTestFile.fileDocument, /* quick= */ true)
    }
    assertThat(descriptors).isEmpty()
  }

  @Test
  fun `validate kmp preview function body folding`() = runTest {
    myFixture.addFileToProject(
      "src/org/jetbrains/compose/ui/tooling/preview/Preview.kt",
      // language=kotlin
      """
      package org.jetbrains.compose.ui.tooling.preview

      annotation class Preview
      """
        .trimIndent(),
    )

    val kmpPreviewTestFile =
      myFixture.loadNewFile(
        "src/com/example/KmpPreviewTest.kt",
        // language=kotlin
        """
        package com.example

        import androidx.compose.runtime.Composable
        import org.jetbrains.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun KmpPreviewComponent() {
          HomeScreen()
        }
        """
          .trimIndent(),
      )

    val foldingBuilder = ComposePreviewFoldingBuilder()
    val descriptors = readAction {
      foldingBuilder.buildFoldRegions(kmpPreviewTestFile, kmpPreviewTestFile.fileDocument, /* quick= */ false)
    }
    assertThat(descriptors).hasLength(1)

    val previewDescriptor = descriptors[0]
    readAction {
      assertThat(previewDescriptor.element.text).isEqualTo("{\n  HomeScreen()\n}")
      val placeholderText = foldingBuilder.getPlaceholderText(previewDescriptor.element, previewDescriptor.range)
      assertThat(placeholderText).isEqualTo("{...}")
    }

    val previewSettings = ComposePreviewSettings.getInstance()
    val previousSetting = previewSettings.state.isFoldComposePreviewFunctions
    try {
      previewSettings.state.isFoldComposePreviewFunctions = true
      assertThat(foldingBuilder.isCollapsedByDefault(previewDescriptor.element)).isTrue()
    } finally {
      previewSettings.state.isFoldComposePreviewFunctions = previousSetting
    }
  }
}
