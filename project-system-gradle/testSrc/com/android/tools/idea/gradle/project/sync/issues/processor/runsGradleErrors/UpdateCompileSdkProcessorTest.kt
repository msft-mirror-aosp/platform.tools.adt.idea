/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.issues.processor.runsGradleErrors

import com.android.sdklib.AndroidApiLevel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.CompileSdkReleaseModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker
import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.android.tools.idea.gradle.project.sync.GradleSyncState
import com.android.tools.idea.gradle.project.sync.issues.processor.UpdateCompileSdkProcessor
import com.android.tools.idea.gradle.project.sync.requestProjectSync
import com.android.tools.idea.gradle.util.GradleProjectSystemUtil
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths.SIMPLE_APPLICATION
import com.android.tools.idea.testing.findAppModule
import com.android.tools.idea.testing.withCompileSdk
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_QF_MIN_COMPILE_SDK_UPDATED
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class UpdateCompileSdkProcessorTest {
  private lateinit var appModule: Module
  private lateinit var buildFile: VirtualFile
  private var currentCompileSdkVersion: Int = 0

  val projectRule = AndroidGradleProjectRule()
  @get:Rule val rule: RuleChain = RuleChain.outerRule(EdtRule()).around(projectRule)
  val project: Project by lazy { projectRule.project }

  @Before
  fun setUp() {
    // TODO(b/580992411): Add test coverage for compileSdk { version = release(...) } block syntax.
    projectRule.loadProject(
      SIMPLE_APPLICATION,
      agpVersion = AgpVersionSoftwareEnvironmentDescriptor.AGP_CURRENT.withCompileSdk(AndroidApiLevel(34)),
    )
    // setup test project module with a lower compileSdk. In the test, we update the compileSdk to latest using
    // UpdateCompileSdkProcessor.
    decrementCompileSdkVersionForTesting()
  }

  private fun decrementCompileSdkVersionForTesting() {
    appModule = project.findAppModule()
    val projectBuildModel: ProjectBuildModel = ProjectBuildModel.get(project)
    buildFile = GradleProjectSystemUtil.getGradleBuildFile(appModule)!!
    val androidBuildModel = projectBuildModel.getModuleBuildModel(buildFile).android()
    val compileSdkModel = androidBuildModel.compileSdkVersion()
    val existingVersion =
      compileSdkModel.getValue(GradlePropertyModel.INTEGER_TYPE)
        ?: (compileSdkModel.toCompileSdkConfig()?.getVersion() as? CompileSdkReleaseModel)
          ?.getVersion()
          ?.getValue(GradlePropertyModel.INTEGER_TYPE)!!
    currentCompileSdkVersion = existingVersion - 1
    WriteCommandAction.runWriteCommandAction(project) {
      compileSdkModel.setValue(currentCompileSdkVersion)
      projectBuildModel.applyChanges()
    }
  }

  private fun isCompileSdkBlockSyntax(): Boolean = runReadAction {
    ProjectBuildModel.get(project).getModuleBuildModel(buildFile).android().compileSdkVersion().toCompileSdkConfig() != null
  }

  private fun setCompileSdkBlockSyntax(blockText: String) {
    WriteCommandAction.runWriteCommandAction(project) {
      val document = FileDocumentManager.getInstance().getDocument(buildFile)!!
      val regex = Regex("""([ \t]*)\bcompileSdk\s*(?:\{[^{}]*(?:\{[^{}]*\}[^{}]*)?\}|=?\s*\d+)""")
      var replacedBlock: String? = null
      val updatedText =
        regex.replace(document.text) { match ->
          val indent = match.groupValues[1]
          blockText.prependIndent(indent).also { replacedBlock = it }
        }
      assertThat(replacedBlock).isNotNull()
      document.setText(updatedText)
      PsiDocumentManager.getInstance(project).commitDocument(document)
      FileDocumentManager.getInstance().saveDocument(document)
    }
  }

  @Test
  fun testFindUsages() {
    val newCompileSdkVersion = currentCompileSdkVersion + 1
    val processor = UpdateCompileSdkProcessor(project, mapOf(buildFile to newCompileSdkVersion))
    val usages = runReadAction { processor.findUsages() }
    assertThat(usages).hasLength(1)
    val expectedElementText =
      if (isCompileSdkBlockSyntax()) {
        "{\n        version = release($currentCompileSdkVersion)\n    }"
      } else {
        """$currentCompileSdkVersion"""
      }
    assertThat(runReadAction { usages[0].element!!.text }).isEqualTo(expectedElementText)

    val noOpProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to currentCompileSdkVersion))
    assertThat(runReadAction { noOpProcessor.findUsages() }).isEmpty()
  }

  @Test
  fun testPerformRefactoring() {
    val newCompileSdkVersion = currentCompileSdkVersion + 1
    val isBlockSyntax = isCompileSdkBlockSyntax()
    val processor = UpdateCompileSdkProcessor(project, mapOf(buildFile to newCompileSdkVersion))
    val usages = runReadAction { processor.findUsages() }
    var synced = false
    GradleSyncState.subscribe(
      project,
      object : GradleSyncListener {
        override fun syncSucceeded(project: Project) {
          synced = true
        }
      },
      projectRule.fixture.testRootDisposable,
    )

    WriteCommandAction.runWriteCommandAction(project) { processor.updateProjectBuildModel(usages) }
    GradleSyncInvoker.getInstance().requestProjectSync(project, TRIGGER_QF_MIN_COMPILE_SDK_UPDATED)

    val fileContents = String(buildFile.contentsToByteArray())
    if (isBlockSyntax) {
      assertThat(fileContents).contains("version = release($newCompileSdkVersion)")
    } else {
      assertThat(fileContents).contains("compileSdk = $newCompileSdkVersion")
    }
    assertThat(
        runReadAction {
          ProjectBuildModel.get(project)
            .getModuleBuildModel(buildFile)
            .android()
            .compileSdkVersion()
            .getValue(GradlePropertyModel.INTEGER_TYPE)
        }
      )
      .isEqualTo(newCompileSdkVersion)
    assertThat(synced).isTrue()
  }

  @Test
  fun testFindUsagesWithCompileSdkBlock() {
    setCompileSdkBlockSyntax(
      """
      compileSdk {
          version = release(33)
      }
      """
        .trimIndent()
    )

    val processor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 34))
    val usages = runReadAction { processor.findUsages() }
    assertThat(usages).hasLength(1)
    assertThat(runReadAction { usages[0].element!!.text }).isEqualTo("{\n        version = release(33)\n    }")

    val noOpProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 33))
    assertThat(runReadAction { noOpProcessor.findUsages() }).isEmpty()
  }

  @Test
  fun testCompileSdkBlockMinorAndExtension() {
    setCompileSdkBlockSyntax(
      """
      compileSdk {
          version = release(33) {
              minorApiLevel = 1
              sdkExtension = 18
          }
      }
      """
        .trimIndent()
    )

    val sameMajorProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 33))
    assertThat(runReadAction { sameMajorProcessor.findUsages() }).isEmpty()

    val higherMajorProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 34))
    val usages = runReadAction { higherMajorProcessor.findUsages() }
    assertThat(usages).hasLength(1)

    WriteCommandAction.runWriteCommandAction(project) { higherMajorProcessor.updateProjectBuildModel(usages) }
    val fileContents = String(buildFile.contentsToByteArray())
    assertThat(fileContents).contains("version = release(34)")
    assertThat(fileContents).doesNotContain("release(33)")
  }

  @Test
  fun testLegacyCompileSdkMinorAndExtension() {
    setCompileSdkBlockSyntax(
      """
      compileSdk = 33
      compileSdkMinor = 1
      compileSdkExtension = 18
      """
        .trimIndent()
    )

    val sameMajorProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 33))
    assertThat(runReadAction { sameMajorProcessor.findUsages() }).isEmpty()

    val prefixCollisionProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 3))
    assertThat(runReadAction { prefixCollisionProcessor.findUsages() }).hasLength(1)

    val higherMajorProcessor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 34))
    val usages = runReadAction { higherMajorProcessor.findUsages() }
    assertThat(usages).hasLength(1)

    WriteCommandAction.runWriteCommandAction(project) { higherMajorProcessor.updateProjectBuildModel(usages) }
    val fileContents = String(buildFile.contentsToByteArray())
    assertThat(fileContents).contains("compileSdk = 34")
    assertThat(fileContents).doesNotContain("compileSdkMinor")
    assertThat(fileContents).doesNotContain("compileSdkExtension")
  }

  @Test
  fun testPerformRefactoringWithCompileSdkBlock() {
    setCompileSdkBlockSyntax(
      """
      compileSdk {
          version = release(33)
      }
      """
        .trimIndent()
    )

    val processor = UpdateCompileSdkProcessor(project, mapOf(buildFile to 34))
    val usages = runReadAction { processor.findUsages() }
    var synced = false
    GradleSyncState.subscribe(
      project,
      object : GradleSyncListener {
        override fun syncSucceeded(project: Project) {
          synced = true
        }
      },
      projectRule.fixture.testRootDisposable,
    )

    WriteCommandAction.runWriteCommandAction(project) { processor.updateProjectBuildModel(usages) }
    GradleSyncInvoker.getInstance().requestProjectSync(project, TRIGGER_QF_MIN_COMPILE_SDK_UPDATED)

    val fileContents = String(buildFile.contentsToByteArray())
    assertThat(fileContents)
      .contains(
        """
        compileSdk {
            version = release(34)
        }
        """
          .trimIndent()
          .prependIndent("    ")
      )
    assertThat(fileContents).doesNotContain("release(33)")
    assertThat(
        runReadAction {
          ProjectBuildModel.get(project)
            .getModuleBuildModel(buildFile)
            .android()
            .compileSdkVersion()
            .getValue(GradlePropertyModel.INTEGER_TYPE)
        }
      )
      .isEqualTo(34)
    assertThat(synced).isTrue()
  }

  @Test
  fun testCompileSdkBlockKotlinScript() {
    lateinit var ktsFile: VirtualFile
    WriteCommandAction.runWriteCommandAction(project) {
      val dir = buildFile.parent.createChildDirectory(this, "ktsLib")
      ktsFile = dir.createChildData(this, "build.gradle.kts")
      val document = FileDocumentManager.getInstance().getDocument(ktsFile)!!
      document.setText(
        """
        android {
            compileSdk {
                version = release(33)
            }
        }
        """
          .trimIndent()
      )
      PsiDocumentManager.getInstance(project).commitDocument(document)
      FileDocumentManager.getInstance().saveDocument(document)
    }

    val noOpProcessor = UpdateCompileSdkProcessor(project, mapOf(ktsFile to 33))
    assertThat(runReadAction { noOpProcessor.findUsages() }).isEmpty()

    val processor = UpdateCompileSdkProcessor(project, mapOf(ktsFile to 34))
    val usages = runReadAction { processor.findUsages() }
    assertThat(usages).hasLength(1)
    assertThat(runReadAction { usages[0].element!!.text }).isEqualTo("version = release(33)")

    WriteCommandAction.runWriteCommandAction(project) { processor.updateProjectBuildModel(usages) }

    val fileContents = String(ktsFile.contentsToByteArray())
    assertThat(fileContents).contains("version = release(34)")
    assertThat(fileContents).doesNotContain("release(33)")
    assertThat(
        runReadAction {
          ProjectBuildModel.get(project)
            .getModuleBuildModel(ktsFile)
            .android()
            .compileSdkVersion()
            .getValue(GradlePropertyModel.INTEGER_TYPE)
        }
      )
      .isEqualTo(34)
  }
}
