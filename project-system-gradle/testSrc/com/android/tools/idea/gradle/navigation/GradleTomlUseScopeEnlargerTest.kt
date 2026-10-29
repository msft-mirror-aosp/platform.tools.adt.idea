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

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.plugins.gradle.config.GradleBuildscriptSearchScope
import org.jetbrains.plugins.gradle.service.resolve.GradleVersionCatalogHandler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.toml.lang.psi.TomlElement
import org.toml.lang.psi.TomlKeySegment

@RunWith(JUnit4::class)
@RunsInEdt
class GradleTomlUseScopeEnlargerTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  private val enlarger = GradleTomlUseScopeEnlarger()
  private val optimizer = GradleTomlUseScopeOptimizer()

  @Test
  fun testNonTomlElementReturnsNull() {
    val javaFile = projectRule.fixture.addFileToProject("src/MyClass.java", "class MyClass {}")
    val nonTomlElement = javaFile.findElementAt(0)!!
    assertThat(enlarger.getAdditionalUseScope(nonTomlElement)).isNull()
    assertThat(optimizer.getRestrictedUseScope(nonTomlElement)).isNull()
  }

  @Test
  fun testTomlElementWithNullContainingFileReturnsNull() {
    val tomlElement = mock(TomlElement::class.java)
    `when`(tomlElement.containingFile).thenReturn(null)

    assertThat(enlarger.getAdditionalUseScope(tomlElement)).isNull()
    assertThat(optimizer.getRestrictedUseScope(tomlElement)).isNull()
  }

  @Test
  fun testTomlElementWithNullVirtualFileReturnsNull() {
    val tomlElement = mock(TomlElement::class.java)
    val psiFile = mock(PsiFile::class.java)
    `when`(tomlElement.containingFile).thenReturn(psiFile)
    `when`(psiFile.virtualFile).thenReturn(null)

    assertThat(enlarger.getAdditionalUseScope(tomlElement)).isNull()
    assertThat(optimizer.getRestrictedUseScope(tomlElement)).isNull()
  }

  @Test
  fun testTomlElementNotInVersionCatalogReturnsNull() {
    val nonCatalogToml =
      projectRule.fixture.addFileToProject("other.toml", "[section]\nkey = 'value'")
    val tomlElement = PsiTreeUtil.findChildOfType(nonCatalogToml, TomlElement::class.java)!!

    assertThat(enlarger.getAdditionalUseScope(tomlElement)).isNull()
    assertThat(optimizer.getRestrictedUseScope(tomlElement)).isNull()
  }

  @Test
  fun testVersionCatalogElementReturnsGradleBuildscriptSearchScope() {
    val catalogFile =
      projectRule.fixture.addFileToProject(
        "gradle/libs.versions.toml",
        "[libraries]\nguava = 'com.google.guava:guava:30.0'",
      )
    registerVersionCatalog(catalogFile.virtualFile)

    val tomlElement = PsiTreeUtil.findChildOfType(catalogFile, TomlElement::class.java)!!
    val scope = enlarger.getAdditionalUseScope(tomlElement)

    assertThat(scope).isInstanceOf(GradleBuildscriptSearchScope::class.java)
  }

  @Test
  fun testUseScopeOfVersionCatalogElementRestrictedToBuildscriptsAndCatalogFile() {
    val catalogFile =
      projectRule.fixture.addFileToProject(
        "gradle/libs.versions.toml",
        "[libraries]\nandroidx-compose-ui = 'androidx.compose.ui:ui:1.0.0'",
      )
    val otherTomlFile =
      projectRule.fixture.addFileToProject("other.toml", "[section]\nui = 'value'")
    val groovyBuildFile = projectRule.fixture.addFileToProject("build.gradle", "")
    val ktsBuildFile = projectRule.fixture.addFileToProject("build.gradle.kts", "")
    val appGroovyBuildFile = projectRule.fixture.addFileToProject("app/build.gradle", "")
    val appKtsBuildFile = projectRule.fixture.addFileToProject("app/build.gradle.kts", "")
    val javaFile = projectRule.fixture.addFileToProject("src/MyClass.java", "class MyClass {}")
    val kotlinFile = projectRule.fixture.addFileToProject("src/MyFile.kt", "val ui = 1")
    val jsonFile = projectRule.fixture.addFileToProject("assets/data.json", "{\"ui\": true}")

    registerVersionCatalog(catalogFile.virtualFile)

    val keySegment = PsiTreeUtil.findChildOfType(catalogFile, TomlKeySegment::class.java)!!
    val useScope =
      PsiSearchHelper.getInstance(projectRule.project).getUseScope(keySegment) as GlobalSearchScope

    assertThat(useScope.contains(catalogFile.virtualFile)).isTrue()
    assertThat(useScope.contains(groovyBuildFile.virtualFile)).isTrue()
    assertThat(useScope.contains(ktsBuildFile.virtualFile)).isTrue()
    assertThat(useScope.contains(appGroovyBuildFile.virtualFile)).isTrue()
    assertThat(useScope.contains(appKtsBuildFile.virtualFile)).isTrue()

    assertThat(useScope.contains(otherTomlFile.virtualFile)).isFalse()
    assertThat(useScope.contains(javaFile.virtualFile)).isFalse()
    assertThat(useScope.contains(kotlinFile.virtualFile)).isFalse()
    assertThat(useScope.contains(jsonFile.virtualFile)).isFalse()
  }

  private fun registerVersionCatalog(catalogVirtualFile: VirtualFile) {
    val testExtension =
      object : GradleVersionCatalogHandler {
        override fun getVersionCatalogFiles(project: Project): Map<String, VirtualFile> =
          mapOf("libs" to catalogVirtualFile)

        override fun getVersionCatalogFiles(module: Module): Map<String, VirtualFile> =
          mapOf("libs" to catalogVirtualFile)

        override fun getExternallyHandledExtension(project: Project): Set<String> = setOf("libs")
      }
    val ep =
      ExtensionPointName.create<GradleVersionCatalogHandler>(
        "org.jetbrains.plugins.gradle.externallyHandledExtensions"
      )
    ExtensionTestUtil.maskExtensions(ep, listOf(testExtension), projectRule.testRootDisposable)
  }
}
