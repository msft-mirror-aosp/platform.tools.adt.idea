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
package com.android.tools.idea.gradle.navigation

import com.android.tools.idea.projectsystem.gradle.getAndroidTestModule
import com.android.tools.idea.projectsystem.gradle.getMainModule
import com.android.tools.idea.projectsystem.gradle.getUnitTestModule
import com.android.tools.idea.projectsystem.gradle.isHolderModule
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.Processor
import org.jetbrains.plugins.gradle.service.resolve.GradleVersionCatalogHandler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.toml.lang.psi.TomlKeySegment

@RunWith(JUnit4::class)
@RunsInEdt
class GradleKtsVersionCatalogReferencesSearcherTest {

  @get:Rule val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @Test
  fun testNonCatalogTomlDoesNotScheduleSearchRequests() {
    val otherTomlFile = projectRule.fixture.addFileToProject("other.toml", "[libraries]\nfoo-bar = 'a:b:1.0'")
    val nonCatalogSegments = PsiTreeUtil.findChildrenOfType(otherTomlFile, TomlKeySegment::class.java)
    val nonCatalogKeySegment = nonCatalogSegments.first { it.name == "foo-bar" }
    val params =
      ReferencesSearch.SearchParameters(
        nonCatalogKeySegment,
        GlobalSearchScope.allScope(projectRule.project),
        /* ignoreAccessScope = */ true,
      )

    GradleKtsVersionCatalogReferencesSearcher().processQuery(params, Processor { true })

    assertThat(params.optimizer.takeSearchRequests()).isEmpty()
  }

  @Test
  fun testVersionCatalogAndBuildscriptFilesBelongToHolderModulesNotSourceSetModules() {
    val rootCatalogFile =
      projectRule.fixture.addFileToProject(
        "gradle/libs.versions.toml",
        "[libraries]\nandroidx-compose-ui = 'androidx.compose.ui:ui:1.0.0'",
      )
    val moduleCatalogFile =
      projectRule.fixture.addFileToProject(
        "app/libs.versions.toml",
        "[libraries]\njunit = 'junit:junit:4.13.2'",
      )
    val rootKtsBuildFile = projectRule.fixture.addFileToProject("build.gradle.kts", "")
    val appKtsBuildFile = projectRule.fixture.addFileToProject("app/build.gradle.kts", "")
    val mainSourceFile = projectRule.fixture.addFileToProject("app/src/main/java/com/example/MyFile.kt", "val ui = 1")
    val unitTestSourceFile =
      projectRule.fixture.addFileToProject(
        "app/src/test/java/com/example/MyUnitTest.kt",
        "class MyUnitTest",
      )
    val androidTestSourceFile =
      projectRule.fixture.addFileToProject(
        "app/src/androidTest/java/com/example/MyAndroidTest.kt",
        "class MyAndroidTest",
      )

    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(":app", "debug", AndroidProjectBuilder()),
    )

    val rootHolderModule = projectRule.project.gradleModule(":")!!
    val appHolderModule = projectRule.project.gradleModule(":app")!!
    val mainModule = appHolderModule.getMainModule()
    val unitTestModule = appHolderModule.getUnitTestModule()!!
    val androidTestModule = appHolderModule.getAndroidTestModule()!!
    val sourceSetModules = listOf(mainModule, unitTestModule, androidTestModule)

    assertThat(rootHolderModule.isHolderModule()).isTrue()
    assertThat(appHolderModule.isHolderModule()).isTrue()
    for (sourceSetModule in sourceSetModules) {
      assertThat(sourceSetModule.isHolderModule()).isFalse()
    }

    val fileIndex = ProjectFileIndex.getInstance(projectRule.project)
    assertThat(fileIndex.getModuleForFile(rootCatalogFile.virtualFile)).isEqualTo(rootHolderModule)
    assertThat(fileIndex.getModuleForFile(rootKtsBuildFile.virtualFile)).isEqualTo(rootHolderModule)
    assertThat(fileIndex.getModuleForFile(moduleCatalogFile.virtualFile)).isEqualTo(appHolderModule)
    assertThat(fileIndex.getModuleForFile(appKtsBuildFile.virtualFile)).isEqualTo(appHolderModule)

    assertThat(rootHolderModule.moduleContentScope.contains(rootCatalogFile.virtualFile)).isTrue()
    assertThat(rootHolderModule.moduleContentScope.contains(rootKtsBuildFile.virtualFile)).isTrue()
    assertThat(appHolderModule.moduleContentScope.contains(moduleCatalogFile.virtualFile)).isTrue()
    assertThat(appHolderModule.moduleContentScope.contains(appKtsBuildFile.virtualFile)).isTrue()

    for (sourceSetModule in sourceSetModules) {
      assertThat(sourceSetModule.moduleContentScope.contains(rootCatalogFile.virtualFile)).isFalse()
      assertThat(sourceSetModule.moduleContentScope.contains(moduleCatalogFile.virtualFile)).isFalse()
      assertThat(sourceSetModule.moduleContentScope.contains(rootKtsBuildFile.virtualFile)).isFalse()
      assertThat(sourceSetModule.moduleContentScope.contains(appKtsBuildFile.virtualFile)).isFalse()
    }

    assertThat(mainModule.moduleContentScope.contains(mainSourceFile.virtualFile)).isTrue()
    assertThat(unitTestModule.moduleContentScope.contains(unitTestSourceFile.virtualFile)).isTrue()
    assertThat(androidTestModule.moduleContentScope.contains(androidTestSourceFile.virtualFile)).isTrue()
  }

  @Test
  fun testVersionCatalogKeySegmentRestrictsToGradleKtsFiles() {
    val catalogFile =
      projectRule.fixture.addFileToProject(
        "gradle/libs.versions.toml",
        "[libraries]\nandroidx-compose-ui = 'androidx.compose.ui:ui:1.0.0'",
      )
    val groovyBuildFile = projectRule.fixture.addFileToProject("build.gradle", "")
    val ktsBuildFile = projectRule.fixture.addFileToProject("build.gradle.kts", "")
    val appKtsBuildFile = projectRule.fixture.addFileToProject("app/build.gradle.kts", "")
    val kotlinFile = projectRule.fixture.addFileToProject("app/src/main/java/MyFile.kt", "val ui = 1")
    val javaFile = projectRule.fixture.addFileToProject("app/src/main/java/MyClass.java", "class MyClass {}")
    val jsonFile = projectRule.fixture.addFileToProject("app/src/main/assets/data.json", "{\"ui\": true}")

    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(":app", "debug", AndroidProjectBuilder()),
    )

    val rootHolderModule = projectRule.project.gradleModule(":")!!
    val appHolderModule = projectRule.project.gradleModule(":app")!!
    val mainModule = appHolderModule.getMainModule()
    val unitTestModule = appHolderModule.getUnitTestModule()!!
    val androidTestModule = appHolderModule.getAndroidTestModule()!!

    registerVersionCatalog(catalogFile.virtualFile)

    val catalogSegments = PsiTreeUtil.findChildrenOfType(catalogFile, TomlKeySegment::class.java)
    val catalogKeySegment = catalogSegments.first { it.name == "androidx-compose-ui" }
    val params =
      ReferencesSearch.SearchParameters(
        catalogKeySegment,
        GlobalSearchScope.allScope(projectRule.project),
        /* ignoreAccessScope = */ true,
      )

    GradleKtsVersionCatalogReferencesSearcher().processQuery(params, Processor { true })

    val requests = params.optimizer.takeSearchRequests()
    assertThat(requests.map { it.word }).containsExactly("ui", "getUi")
    for (request in requests) {
      val requestScope = request.searchScope as GlobalSearchScope
      assertThat(requestScope.contains(ktsBuildFile.virtualFile)).isTrue()
      assertThat(requestScope.contains(appKtsBuildFile.virtualFile)).isTrue()
      assertThat(requestScope.contains(groovyBuildFile.virtualFile)).isFalse()
      assertThat(requestScope.contains(catalogFile.virtualFile)).isFalse()
      assertThat(requestScope.contains(kotlinFile.virtualFile)).isFalse()
      assertThat(requestScope.contains(javaFile.virtualFile)).isFalse()
      assertThat(requestScope.contains(jsonFile.virtualFile)).isFalse()
      assertThat(requestScope.isSearchInModuleContent(rootHolderModule)).isTrue()
      assertThat(requestScope.isSearchInModuleContent(appHolderModule)).isTrue()
      assertThat(requestScope.isSearchInModuleContent(mainModule)).isFalse()
      assertThat(requestScope.isSearchInModuleContent(unitTestModule)).isFalse()
      assertThat(requestScope.isSearchInModuleContent(androidTestModule)).isFalse()
    }
  }

  private fun registerVersionCatalog(catalogVirtualFile: VirtualFile) {
    val testExtension =
      object : GradleVersionCatalogHandler {
        override fun getVersionCatalogFiles(project: Project): Map<String, VirtualFile> = mapOf("libs" to catalogVirtualFile)

        override fun getVersionCatalogFiles(module: Module): Map<String, VirtualFile> = mapOf("libs" to catalogVirtualFile)

        override fun getExternallyHandledExtension(project: Project): Set<String> = setOf("libs")
      }
    val ep = ExtensionPointName.create<GradleVersionCatalogHandler>("org.jetbrains.plugins.gradle.externallyHandledExtensions")
    ExtensionTestUtil.maskExtensions(ep, listOf(testExtension), projectRule.testRootDisposable)
  }
}
