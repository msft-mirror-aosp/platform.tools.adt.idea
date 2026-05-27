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
package com.android.tools.idea.compose.gradle.preview

import com.android.tools.idea.compose.ANDROID_KOTLIN_MULTIPLATFORM_REMEMBER_IN_COMPOSITION
import com.android.tools.idea.lint.common.AndroidLintInspectionBase
import com.android.tools.idea.testartifacts.TestConfigurationTestingUtil.Companion.getPsiElement
import com.android.tools.idea.testartifacts.TestConfigurationTestingUtil.Method
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.EdtAndroidGradleProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.RunsInEdt
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class KotlinMultiplatformRememberInCompositionTest {

  @get:Rule
  val projectRule: EdtAndroidGradleProjectRule =
    AndroidGradleProjectRule(agpVersionSoftwareEnvironment = AgpVersionSoftwareEnvironmentDescriptor.AGP_8_13).onEdt()

  @Before
  fun setUp() {
    AndroidLintInspectionBase.setRegisterDynamicToolsFromTests(true)
    projectRule.loadProject(ANDROID_KOTLIN_MULTIPLATFORM_REMEMBER_IN_COMPOSITION)
  }

  @After
  fun tearDown() {
    AndroidLintInspectionBase.setRegisterDynamicToolsFromTests(false)
  }

  @Test
  fun `FocusRequester triggers RememberInComposition lint in commonMain`() = runBlocking {
    assertRememberInCompositionWarning(FOCUS_REQUESTER_METHOD)
  }

  @Test
  fun `movableContentOf triggers RememberInComposition lint in commonMain`() = runBlocking {
    assertRememberInCompositionWarning(MOVABLE_CONTENT_METHOD)
  }

  @Test
  fun `Animatable triggers RememberInComposition lint in commonMain`() = runBlocking {
    assertRememberInCompositionWarning(ANIMATABLE_METHOD)
  }

  @Test
  fun `remember does not trigger RememberInComposition lint in commonMain`() = runBlocking {
    assertRememberInCompositionWarning(CORRECT_METHOD, expectWarning = false)
  }

  private fun assertRememberInCompositionWarning(
    methodLocator: Method,
    expectWarning: Boolean = true,
  ) {
    val testMethod = projectRule.project.getPsiElement(methodLocator)
    assertNotNull(testMethod, "Could not find PSI element for method: ${methodLocator.methodName}")

    val virtualFile = testMethod.containingFile.virtualFile
    projectRule.fixture.openFileInEditor(virtualFile)

    val warnings = projectRule.fixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description }

    val hasWarning = warnings.any { it.contains("without using `remember`") }

    if (expectWarning) {
      assertTrue(hasWarning, "Expected RememberInComposition warning, but found: $warnings")
    } else {
      assertFalse(hasWarning, "Expected no warnings, but found: $warnings")
    }
  }
}

private val FOCUS_REQUESTER_METHOD = Method("org.example.project.FocusRequesterTestKt", "FocusRequesterTest")
private val MOVABLE_CONTENT_METHOD = Method("org.example.project.MovableContentTestKt", "MovableContentTest")
private val ANIMATABLE_METHOD = Method("org.example.project.AnimatableTestKt", "AnimatableTest")
private val CORRECT_METHOD = Method("org.example.project.ValidRememberUsageTestKt", "ValidRememberUsageTest")
