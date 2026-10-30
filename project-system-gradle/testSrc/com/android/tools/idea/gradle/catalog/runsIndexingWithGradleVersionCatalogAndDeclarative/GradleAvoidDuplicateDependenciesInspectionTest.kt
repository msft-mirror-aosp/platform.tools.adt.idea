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
package com.android.tools.idea.gradle.catalog.runsIndexingWithGradleVersionCatalogAndDeclarative

import com.android.tools.idea.gradle.project.sync.snapshots.replaceContent
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths.SIMPLE_APPLICATION_VERSION_CATALOG_KTS
import com.android.tools.idea.testing.findAppModule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.virtualFile
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.descendantsOfType
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.RunsInEdt
import java.io.File
import org.jetbrains.kotlin.idea.core.script.k2.modules.KotlinScriptEntity
import org.jetbrains.kotlin.idea.core.script.k2.modules.KotlinScriptLibraryEntity
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.plugins.gradle.codeInspection.GradleAvoidDuplicateDependenciesInspection
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class GradleAvoidDuplicateDependenciesInspectionTest {

  @get:Rule val projectRule = AndroidGradleProjectRule().onEdt()

  private val fixture
    get() = projectRule.fixture

  private val project
    get() = projectRule.project

  @Before
  fun setUp() {
    fixture.enableInspections(GradleAvoidDuplicateDependenciesInspection::class.java)
  }

  // Regression test for b/526305384
  @Test
  fun testDistinctBundlesDoNotReportDuplicate() {
    val appBuildFile = loadProjectWithBundles()

    updateBuildFile(
      appBuildFile,
      """
      plugins {
        alias(libs.plugins.android.application)
      }

      android {
        namespace = "google.simpleapplication"
        compileSdkVersion(23)

        defaultConfig {
          targetSdkVersion(23)
          minSdkVersion(19)
          applicationId = "google.simpleapplication"
          versionCode = 1
          versionName = "1.0"
        }
      }

      dependencies {
        api(libs.constraint.layout)
        api(libs.guava)
        api(libs.bundles.both)
        api(libs.bundles.other)
        testImplementation(libsTest.junit)
      }
      """
        .trimIndent(),
    )

    fixture.configureFromExistingVirtualFile(appBuildFile)
    val ktFile = fixture.file as KtFile
    assertThat(ProblemHighlightFilter.shouldHighlightFile(ktFile)).isTrue()
    val bundleExpressions =
      ktFile
        .descendantsOfType<KtDotQualifiedExpression>()
        .filter { it.text == "libs.bundles.both" || it.text == "libs.bundles.other" }
        .toList()
    assertThat(bundleExpressions).hasSize(2)
    for (bundleExpr in bundleExpressions) {
      assertThat(bundleExpr.selectorExpression?.mainReference?.resolve()).isInstanceOf(PsiMethod::class.java)
    }

    val duplicateWarnings = duplicateDependencyWarnings()
    assertThat(duplicateWarnings).isEmpty()
  }

  @Test
  fun testDuplicateBundlesAndLibrariesReported() {
    val appBuildFile = loadProjectWithBundles()

    updateBuildFile(
      appBuildFile,
      """
      plugins {
        alias(libs.plugins.android.application)
      }

      android {
        namespace = "google.simpleapplication"
        compileSdkVersion(23)

        defaultConfig {
          targetSdkVersion(23)
          minSdkVersion(19)
          applicationId = "google.simpleapplication"
          versionCode = 1
          versionName = "1.0"
        }
      }

      dependencies {
        api(libs.guava)
        implementation(libs.guava.common)
        api(libs.bundles.both)
        implementation(libs.bundles.both)
        api(libs.bundles.other)
      }
      """
        .trimIndent(),
    )

    fixture.configureFromExistingVirtualFile(appBuildFile)
    val duplicateWarnings = duplicateDependencyWarnings()
    // The inspection reports every member of a duplicate group (on-the-fly mode), keyed by the resolved coordinates:
    // - libs.guava and libs.guava.common are distinct catalog aliases for the same module (com.google.guava:guava:19.0 in
    //   libs.versions.toml), so `api(libs.guava)` and `implementation(libs.guava.common)` each get a warning.
    // - Bundles don't resolve to a single coordinate, so they are keyed by their accessor text and only the two
    //   `libs.bundles.both` declarations are duplicates; `libs.bundles.other` is not, even though it also contains guava.
    assertThat(duplicateWarnings)
      .containsExactly(
        "Dependency 'com.google.guava:guava:19.0' is declared multiple times",
        "Dependency 'com.google.guava:guava:19.0' is declared multiple times",
        "Dependency 'libs.bundles.both' is declared multiple times",
        "Dependency 'libs.bundles.both' is declared multiple times",
      )
  }

  /**
   * Returns the descriptions of all highlights produced by [GradleAvoidDuplicateDependenciesInspection], identified by its short name
   * rather than by severity or message text, so that a change in the inspection's default level cannot make these tests silently pass.
   */
  private fun duplicateDependencyWarnings(): List<String> {
    // The short name comes from the plugin.xml registration, which differs from the class-derived default.
    val shortName =
      LocalInspectionEP.LOCAL_INSPECTION.extensionList
        .single { it.implementationClass == GradleAvoidDuplicateDependenciesInspection::class.java.name }
        .getShortName()
    val highlights = fixture.doHighlighting()
    return highlights.filter { it.inspectionToolId == shortName }.mapNotNull { it.description }
  }

  private fun loadProjectWithBundles(): VirtualFile {
    projectRule.loadProject(
      SIMPLE_APPLICATION_VERSION_CATALOG_KTS,
      AgpVersionSoftwareEnvironmentDescriptor.AGP_CURRENT,
      null,
    ) { projectRoot ->
      projectRoot.resolve("gradle/libs.versions.toml").replaceContent { content ->
        content.replace(
          "[bundles]\nboth = [\"constraint-layout\", \"guava\"]",
          "[bundles]\nboth = [\"constraint-layout\", \"guava\"]\nother = [\"guava\"]",
        )
      }
      VfsUtil.markDirtyAndRefresh(false, true, true, projectRoot)
    }

    val snapshot = project.workspaceModel.currentSnapshot
    val scriptEntities = snapshot.entities(KotlinScriptEntity::class.java).toList()
    assertThat(scriptEntities).isNotEmpty()
    snapshot
      .entities(KotlinScriptLibraryEntity::class.java)
      .flatMap { it.classes }
      .forEach { url ->
        if (url.virtualFile == null) {
          VfsUtil.findFileByIoFile(File(VfsUtilCore.urlToPath(url.url).removeSuffix("!/")), true)
        }
      }
    IndexingTestUtil.waitUntilIndexesAreReady(project)

    return project.findAppModule().guessModuleDir()!!.findChild("build.gradle.kts")!!
  }

  private fun updateBuildFile(file: VirtualFile, content: String) {
    runWriteAction {
      VfsUtil.saveText(file, content)
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
  }
}
