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
package com.android.tools.idea.compose.preview.actions

import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder
import com.android.tools.idea.compose.preview.PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE
import com.android.tools.idea.compose.preview.runconfiguration.ComposePreviewRunConfiguration
import com.android.tools.idea.compose.preview.runconfiguration.ComposePreviewRunConfigurationType
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class DeployToDeviceActionTest {

  @get:Rule val projectRule = ComposeProjectRule()
  @get:Rule val edtRule = EdtRule()

  // Regression test for b/389580538
  @Test
  fun `running previews with the same display name creates distinct run configurations`() = runTest {
    val file1 =
      projectRule.fixture.addFileToProject(
        "src/File1.kt",
        // language=kotlin
        """
        package com.example.one

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun Preview() {
        }
        """
          .trimIndent(),
      )
    val file2 =
      projectRule.fixture.addFileToProject(
        "src/File2.kt",
        // language=kotlin
        """
        package com.example.two

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun Preview() {
        }
        """
          .trimIndent(),
      )

    val preview1 =
      AnnotationFilePreviewElementFinder.findPreviewElements(projectRule.project, file1.virtualFile)
        .single() as PsiComposePreviewElementInstance
    val preview2 =
      AnnotationFilePreviewElementFinder.findPreviewElements(projectRule.project, file2.virtualFile)
        .single() as PsiComposePreviewElementInstance

    val executedSettings = mutableListOf<RunnerAndConfigurationSettings>()
    val action = DeployToDeviceAction(executeConfiguration = { executedSettings.add(it) })

    // 1. Run 1st preview
    val event1 =
      TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder().add(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE, preview1).build(),
      )
    action.actionPerformed(event1)

    assertThat(executedSettings).hasSize(1)
    val config1 = executedSettings[0].configuration as ComposePreviewRunConfiguration
    assertThat(config1.name).isEqualTo("Preview")
    assertThat(config1.composableMethodFqn).isEqualTo("com.example.one.File1Kt.Preview")
    assertThat(config1.triggerSource).isEqualTo(ComposePreviewRunConfiguration.TriggerSource.TOOLBAR)

    // 2. Run 2nd preview with the same function/display name ("Preview")
    val event2 =
      TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder().add(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE, preview2).build(),
      )
    action.actionPerformed(event2)

    assertThat(executedSettings).hasSize(2)
    val config2 = executedSettings[1].configuration as ComposePreviewRunConfiguration
    assertThat(config2.composableMethodFqn).isEqualTo("com.example.two.File2Kt.Preview")
    assertThat(config2.name).isNotEqualTo(config1.name)

    val runManager = RunManager.getInstance(projectRule.project)
    val allConfigs =
      runManager
        .getConfigurationSettingsList(ComposePreviewRunConfigurationType::class.java)
        .map { (it.configuration as ComposePreviewRunConfiguration).composableMethodFqn }
    assertThat(allConfigs)
      .containsExactly("com.example.one.File1Kt.Preview", "com.example.two.File2Kt.Preview")

    // 3. Running 1st and 2nd previews again should reuse their existing configurations
    action.actionPerformed(event1)
    action.actionPerformed(event2)

    assertThat(executedSettings).hasSize(4)
    assertThat(executedSettings[2]).isSameAs(executedSettings[0])
    assertThat(executedSettings[3]).isSameAs(executedSettings[1])
    assertThat(
        runManager.getConfigurationSettingsList(ComposePreviewRunConfigurationType::class.java)
      )
      .hasSize(2)
  }
}
