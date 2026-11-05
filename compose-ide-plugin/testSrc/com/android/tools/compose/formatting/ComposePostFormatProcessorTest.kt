/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.compose.formatting

import com.android.tools.compose.COMPOSE_UI_PACKAGE
import com.android.tools.compose.isModifierChainLongerThanTwo
import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.loadNewFile
import com.google.common.truth.Truth.assertThat
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.android.compose.stubComposableAnnotation
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Test for [ComposePostFormatProcessor]. */
class ComposePostFormatProcessorTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val fixture: CodeInsightTestFixture by lazy { projectRule.fixture }

  private val project: Project by lazy { fixture.project }

  @Before
  fun setUp() {
    (fixture.module.getModuleSystem() as DefaultModuleSystem).usesCompose = true
    fixture.stubComposableAnnotation()
    fixture.addFileToProject(
      "src/${COMPOSE_UI_PACKAGE.replace(".", "/")}/Modifier.kt",
      // language=kotlin
      """
    package $COMPOSE_UI_PACKAGE

    interface Modifier {
      fun adjust(): Modifier
      companion object : Modifier {
        fun adjust(): Modifier {}
      }
    }
    """
        .trimIndent(),
    )

    val settings = CodeStyle.getSettings(project).getCustomSettings(KotlinCodeStyleSettings::class.java)
    settings.CONTINUATION_INDENT_FOR_CHAINED_CALLS = false
  }

  @Test
  fun modifierChainIsWrapped() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier.adjust().adjust()
      }
      """
        .trimIndent(),
    )

    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(fixture.file.textRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier
              .adjust()
              .adjust()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun nestedChainInModifierIsNotWrapped() {
    // Regression test for b/364549431
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun BugRepro(modifier: Modifier) {
          SomeComposable(modifier = Modifier.onClick { "foo".someFunction().someFunction() }.secondCall())

          SomeComposable(modifier = Modifier.onClick {
            "foo".someFunction().someFunction()
          }.secondCall())
      }

      @Composable
      fun SomeComposable(modifier: Modifier) {
          TODO()
      }

      fun Modifier.onClick(onClick: @Composable () -> Unit): Modifier = this

      fun Modifier.secondCall(): Modifier = this

      fun Any.someFunction() = this
      """
        .trimIndent(),
    )

    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(fixture.file.textRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun BugRepro(modifier: Modifier) {
          SomeComposable(modifier = Modifier
              .onClick { "foo".someFunction().someFunction() }
              .secondCall())

          SomeComposable(modifier = Modifier
              .onClick {
                  "foo".someFunction().someFunction()
              }
              .secondCall())
      }

      @Composable
      fun SomeComposable(modifier: Modifier) {
          TODO()
      }

      fun Modifier.onClick(onClick: @Composable () -> Unit): Modifier = this

      fun Modifier.secondCall(): Modifier = this

      fun Any.someFunction() = this
      """
        .trimIndent()
    )
  }

  @Test
  fun shortModifierChainIsNotWrapped() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier.adjust()
      }
      """
        .trimIndent(),
    )

    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(fixture.file.textRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier.adjust()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun modifierChainInLambdaOfModifierChainIsWrapped() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier.onClick {
              val inner = Modifier.adjust().adjust()
          }.adjust()
      }

      fun Modifier.onClick(onClick: @Composable () -> Unit): Modifier = this
      """
        .trimIndent(),
    )

    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(fixture.file.textRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier
              .onClick {
                  val inner = Modifier
                      .adjust()
                      .adjust()
              }
              .adjust()
      }

      fun Modifier.onClick(onClick: @Composable () -> Unit): Modifier = this
      """
        .trimIndent()
    )
  }

  @Test
  fun partiallyWrappedModifierChainIsFullyWrapped() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier
              .adjust().adjust()
      }
      """
        .trimIndent(),
    )

    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(fixture.file.textRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun HomeScreen() {
          val m = Modifier
              .adjust()
              .adjust()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun onlyModifierChainsInReformattedRangeAreWrapped() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun InRange() {
          val m = Modifier.adjust().adjust()
      }

      @Composable
      fun OutOfRange() {
          val m = Modifier.adjust().adjust()
      }
      """
        .trimIndent(),
    )

    val inRange = runReadAction { (fixture.file as KtFile).declarations.single { it.name == "InRange" }.textRange }
    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
      CodeStyleManager.getInstance(project).reformatText(fixture.file, listOf(inRange))
    }

    fixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      @Composable
      fun InRange() {
          val m = Modifier
              .adjust()
              .adjust()
      }

      @Composable
      fun OutOfRange() {
          val m = Modifier.adjust().adjust()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun candidatesAreOnlyUnwrappedModifierCallChains() {
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      class Colors { val primary: Int = 0 }
      object Theme { val colors: Colors = Colors() }

      @Composable fun Box(modifier: Modifier, content: @Composable () -> Unit) {}

      fun Any.someFunction() = this

      @Composable
      fun HomeScreen(flag: Boolean) {
          val unwrapped = Modifier.adjust().adjust()
          val alreadyWrapped = Modifier
              .adjust()
              .adjust()
          val property = Theme.colors.primary
          val propertyThenCall = Theme.colors.primary.plus(1)
          val notModifier = "foo".someFunction().someFunction()
          Box(modifier = if (flag) Modifier.adjust().adjust() else Modifier) {
              val inLambda = Modifier.adjust().adjust()
          }
      }
      """
        .trimIndent(),
    )

    val candidates = runReadAction {
      ComposeModifierProcessor(CodeStyle.getSettings(fixture.file)).collectCandidates(fixture.file).map { pointer ->
        val element = pointer.element!!
        "${element.text} @ line ${fixture.editor.document.getLineNumber(element.textOffset) + 1}"
      }
    }

    assertThat(candidates)
      .containsExactly(
        "Modifier.adjust().adjust() @ line 15",
        "Modifier.adjust().adjust() @ line 22",
        "Modifier.adjust().adjust() @ line 23",
      )
      .inOrder()
  }

  @Test
  fun callChainPreconditionKeepsModifierChains() {
    // Every Modifier chain that the analysis would accept must also pass the cheap syntactic precondition that runs before it.
    fixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier

      fun Modifier.withLambda(block: () -> Unit): Modifier = this
      fun Modifier.then(other: Modifier): Modifier = this
      val Modifier.self: Modifier get() = this

      @Composable
      fun HomeScreen(modifier: Modifier, other: Modifier) {
          val a = Modifier.adjust().adjust()
          val b = modifier.adjust().adjust()
          val c = Modifier.withLambda { }.adjust()
          val d = Modifier.adjust().then(other)
          val e = Modifier.adjust().adjust().adjust().adjust()
          val f = Modifier.adjust().adjust().self
          val g = Modifier.self.adjust()
      }
      """
        .trimIndent(),
    )

    val (modifierChains, passingPrecondition) =
      runReadAction {
        val topLevelChains = fixture.file.collectDescendantsOfType<KtDotQualifiedExpression> { it.parent !is KtDotQualifiedExpression }
        val modifierChains = topLevelChains.filter { isModifierChainLongerThanTwo(it) }
        modifierChains.map { it.text } to modifierChains.filter { it.looksLikeCallChain() }.map { it.text }
      }

    assertThat(modifierChains)
      .containsExactly(
        "Modifier.adjust().adjust()",
        "modifier.adjust().adjust()",
        "Modifier.withLambda { }.adjust()",
        "Modifier.adjust().then(other)",
        "Modifier.adjust().adjust().adjust().adjust()",
        "Modifier.adjust().adjust().self",
        "Modifier.self.adjust()",
      )
      .inOrder()
    assertThat(passingPrecondition).isEqualTo(modifierChains)
  }
}
