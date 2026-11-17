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
package com.android.tools.idea.gradle.catalog

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.api.GradleSettingsModel
import com.android.tools.idea.gradle.dsl.api.GradleVersionCatalogModel
import com.android.tools.idea.gradle.dsl.api.GradleVersionCatalogsModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.model.EP_NAME
import com.android.tools.idea.gradle.dsl.model.GradleFileModelImpl
import com.android.tools.idea.gradle.dsl.model.VersionCatalogFilesModel
import com.android.tools.idea.projectsystem.gradle.GradleModelSource
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.writeText
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.light.LightClass
import com.intellij.psi.util.PropertyUtilBase.getPropertyName
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.registerExtension
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.utils.editor.commitToPsi
import com.intellij.testFramework.utils.editor.reloadFromDisk
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.jetbrains.plugins.groovy.intentions.style.inference.resolve
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Multi-threaded regression tests for b/539554621 ensuring concurrent read-action callers of
 * [GradleDslVersionCatalogHandler.getAccessorClass] and [GradleModelProvider.getCachedVersionCatalogModel] do not mutate shared
 * `GradleVersionCatalogFile` state or race on fallback catalog caches.
 */
@RunWith(JUnit4::class)
class GradleDslVersionCatalogConcurrencyTest {

  @get:Rule val projectRule = AndroidProjectRule.onDisk()
  @get:Rule val disposableRule = DisposableRule()

  private val project: Project
    get() = projectRule.project

  private lateinit var buildGradleFile: VirtualFile

  @Before
  fun setUp() {
    val fixture = projectRule.fixture
    // Provide Gradle Provider<T> on the project scope so SyntheticVersionCatalogAccessor materializes
    // full getter/inner-class hierarchies without requiring a full Gradle sync.
    fixture.addFileToProject(
      "src/main/java/org/gradle/api/provider/Provider.java",
      "package org.gradle.api.provider; public interface Provider<T> {}",
    )
    fixture.addFileToProject(
      "settings.gradle",
      """
      rootProject.name = "test"
      """
        .trimIndent(),
    )
    buildGradleFile =
      fixture
        .addFileToProject(
          "build.gradle",
          """
          dependencies {
            implementation libs.guava
          }
          """
            .trimIndent(),
        )
        .virtualFile

    runInEdtAndWait {
      runWriteAction {
        ExternalSystemModulePropertyManager.getInstance(projectRule.module).setLinkedProjectPath(project.basePath)
      }
    }
  }

  @Test
  fun testConcurrentGetAccessorClassWithMissingSections() {
    val catalogVirtualFile =
      projectRule.fixture
        .addFileToProject(
          "gradle/libs.versions.toml",
          """
          [libraries]
          guava = "com.google.guava:guava:31.1-jre"
          """
            .trimIndent(),
        )
        .virtualFile

    val threadCount = 12
    val iterations = 25
    val pool = Executors.newFixedThreadPool(threadCount)
    try {
      for (iter in 1..iterations) {
        // Update the catalog on each iteration so all reader threads hit a cold CachedValue
        // where [versions], [plugins], and [bundles] are absent in the TOML file.
        runInEdtAndWait {
          runWriteAction {
            catalogVirtualFile.writeTextAndCommit(
              """
              [libraries]
              guava = "com.google.guava:guava:31.1-jre"
              androidx-core = "androidx.core:core:1.9.0"
              iter$iter = "com.example:iter:$iter.0"
              """
                .trimIndent()
            )
          }
        }

        val barrier = CyclicBarrier(threadCount)
        val tasks =
          (1..threadCount).map {
            Callable {
              barrier.await(10, TimeUnit.SECONDS)
              ApplicationManager.getApplication().runReadAction<Pair<List<String>, Boolean>> {
                val buildPsi = PsiManager.getInstance(project).findFile(buildGradleFile)!!
                val accessor = GradleDslVersionCatalogHandler().getAccessorClass(buildPsi, "libs")!!
                val catalogModel =
                  GradleModelSource.getInstance().getCachedVersionCatalogsModel(projectRule.module).getVersionCatalogModel("libs")!!
                assertThat(catalogModel.getVersions()).isNull()
                assertThat(catalogModel.getPlugins()).isNull()
                assertThat(catalogModel.getBundles()).isNull()
                extractDependenciesInGradleFormat(accessor) to catalogModel.isModified
              }
            }
          }

        val results =
          pool.invokeAll(tasks, 30, TimeUnit.SECONDS).map {
            assertThat(it.isCancelled).isFalse()
            it.get()
          }
        val expectedDeps = setOf("guava", "androidx.core", "iter$iter")
        for ((deps, isModified) in results) {
          assertThat(deps).containsExactlyElementsIn(expectedDeps)
          assertThat(isModified).isFalse()
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun testConcurrentSyncFallbackAndCacheInvalidation() {
    val syncCatalogVirtualFile =
      projectRule.fixture
        .addFileToProject(
          "gradle/syncOnlyLibs.versions.toml",
          """
          [libraries]
          retrofit = "com.squareup.retrofit2:retrofit:2.9.0"
          """
            .trimIndent(),
        )
        .virtualFile

    val syncCatalogService =
      object : VersionCatalogFilesModel {
        override fun getCatalogNameToFileMapping(project: Project): Map<String, String> =
          mapOf("syncOnlyLibs" to syncCatalogVirtualFile.path)

        override fun getCatalogNameToFileMapping(module: Module): Map<String, String> = mapOf("syncOnlyLibs" to syncCatalogVirtualFile.path)
      }
    ApplicationManager.getApplication().registerExtension(EP_NAME, syncCatalogService, disposableRule.disposable)

    val threadCount = 12
    val pool = Executors.newFixedThreadPool(threadCount)
    try {
      // Phase 1: race threads on cold sync-fallback catalog missing [versions], [plugins], [bundles].
      val barrier1 = CyclicBarrier(threadCount)
      val results1 =
        pool
          .invokeAll(
            (1..threadCount).map {
              Callable {
                barrier1.await(10, TimeUnit.SECONDS)
                ApplicationManager.getApplication().runReadAction<List<String>> {
                  val buildPsi = PsiManager.getInstance(project).findFile(buildGradleFile)!!
                  val accessor = GradleDslVersionCatalogHandler().getAccessorClass(buildPsi, "syncOnlyLibs")!!
                  extractDependenciesInGradleFormat(accessor)
                }
              }
            },
            30,
            TimeUnit.SECONDS,
          )
          .map {
            assertThat(it.isCancelled).isFalse()
            it.get()
          }
      results1.forEach { deps -> assertThat(deps).containsExactly("retrofit") }

      // Phase 2: modify the sync-fallback TOML file (without touching settings.gradle) and verify
      // edit visibility + concurrent access on the updated fallback file.
      runInEdtAndWait {
        runWriteAction {
          syncCatalogVirtualFile.writeTextAndCommit(
            """
            [libraries]
            retrofit = "com.squareup.retrofit2:retrofit:2.9.0"
            okhttp = "com.squareup.okhttp3:okhttp:4.11.0"

            [bundles]
            network = ["retrofit", "okhttp"]
            """
              .trimIndent()
          )
        }
      }

      val barrier2 = CyclicBarrier(threadCount)
      val results2 =
        pool
          .invokeAll(
            (1..threadCount).map {
              Callable {
                barrier2.await(10, TimeUnit.SECONDS)
                ApplicationManager.getApplication().runReadAction<List<String>> {
                  val buildPsi = PsiManager.getInstance(project).findFile(buildGradleFile)!!
                  val accessor = GradleDslVersionCatalogHandler().getAccessorClass(buildPsi, "syncOnlyLibs")!!
                  extractDependenciesInGradleFormat(accessor)
                }
              }
            },
            30,
            TimeUnit.SECONDS,
          )
          .map {
            assertThat(it.isCancelled).isFalse()
            it.get()
          }
      results2.forEach { deps ->
        assertThat(deps).containsExactly("retrofit", "okhttp", "bundles.network")
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun testSyncFallbackCachesCatalogModelWithoutProjectBuildModel() {
    val syncCatalogVirtualFile =
      projectRule.fixture
        .addFileToProject(
          "gradle/syncOnlyLibs.versions.toml",
          """
          [libraries]
          retrofit = "com.squareup.retrofit2:retrofit:2.9.0"
          """
            .trimIndent(),
        )
        .virtualFile

    val syncCatalogService =
      object : VersionCatalogFilesModel {
        override fun getCatalogNameToFileMapping(project: Project): Map<String, String> =
          mapOf("syncOnlyLibs" to syncCatalogVirtualFile.path)

        override fun getCatalogNameToFileMapping(module: Module): Map<String, String> = mapOf("syncOnlyLibs" to syncCatalogVirtualFile.path)
      }
    ApplicationManager.getApplication().registerExtension(EP_NAME, syncCatalogService, disposableRule.disposable)

    val realProvider = GradleModelProvider.getInstance()
    ApplicationManager.getApplication()
      .replaceService(
        GradleModelProvider::class.java,
        ProjectModelFailingGradleModelProvider(realProvider),
        disposableRule.disposable,
      )

    val firstDslFile =
      ApplicationManager.getApplication().runReadAction<Any> {
        val provider = GradleModelProvider.getInstance()
        val model1 = provider.getCachedVersionCatalogModel(syncCatalogVirtualFile, project, "syncOnlyLibs")!!
        val model2 = provider.getCachedVersionCatalogModel(syncCatalogVirtualFile, project, "syncOnlyLibs")!!
        assertThat((model1 as GradleFileModelImpl).dslFile).isSameAs((model2 as GradleFileModelImpl).dslFile)
        assertThat(model1.catalogName()).isEqualTo("syncOnlyLibs")

        // Distinct catalogName aliases on the same TOML file stay isolated.
        val aliasModel = provider.getCachedVersionCatalogModel(syncCatalogVirtualFile, project, "otherAlias")!!
        assertThat(aliasModel.catalogName()).isEqualTo("otherAlias")
        assertThat((aliasModel as GradleFileModelImpl).dslFile).isNotSameAs(model1.dslFile)

        val buildPsi = PsiManager.getInstance(project).findFile(buildGradleFile)!!
        val accessor = GradleDslVersionCatalogHandler().getAccessorClass(buildPsi, "syncOnlyLibs")!!
        assertThat(extractDependenciesInGradleFormat(accessor)).containsExactly("retrofit")
        model1.dslFile
      }

    // Editing the TOML file invalidates the cached GradleVersionCatalogFile.
    runInEdtAndWait {
      runWriteAction {
        syncCatalogVirtualFile.writeTextAndCommit(
          """
          [libraries]
          retrofit = "com.squareup.retrofit2:retrofit:2.9.0"
          okhttp = "com.squareup.okhttp3:okhttp:4.11.0"
          """
            .trimIndent()
        )
      }
    }

    ApplicationManager.getApplication().runReadAction {
      val updatedModel = GradleModelProvider.getInstance().getCachedVersionCatalogModel(syncCatalogVirtualFile, project, "syncOnlyLibs")!!
      assertThat((updatedModel as GradleFileModelImpl).dslFile).isNotSameAs(firstDslFile)

      val buildPsi = PsiManager.getInstance(project).findFile(buildGradleFile)!!
      val accessor = GradleDslVersionCatalogHandler().getAccessorClass(buildPsi, "syncOnlyLibs")!!
      assertThat(extractDependenciesInGradleFormat(accessor)).containsExactly("retrofit", "okhttp")
    }

    // Deleting the TOML file makes catalogFile invalid and returns null cleanly.
    runInEdtAndWait { runWriteAction { syncCatalogVirtualFile.delete(this) } }
    ApplicationManager.getApplication().runReadAction {
      assertThat(GradleModelProvider.getInstance().getCachedVersionCatalogModel(syncCatalogVirtualFile, project, "syncOnlyLibs")).isNull()
    }
  }

  @Test
  fun testSyntheticAccessorDoesNotMutateMutableCatalogModel() {
    projectRule.fixture.addFileToProject(
      "gradle/libs.versions.toml",
      """
      [libraries]
      guava = "com.google.guava:guava:31.1-jre"
      """
        .trimIndent(),
    )

    ApplicationManager.getApplication().runReadAction {
      val buildPsi: PsiFile = PsiManager.getInstance(project).findFile(buildGradleFile)!!
      val mutableCatalogModel = ProjectBuildModel.get(project).versionCatalogsModel.getVersionCatalogModel("libs")!!
      assertThat(mutableCatalogModel.isModified).isFalse()

      val accessor =
        SyntheticVersionCatalogAccessor.create(
          project,
          buildPsi.resolveScope,
          mutableCatalogModel,
          "libs",
        )
      assertThat(accessor).isNotNull()
      assertThat(extractDependenciesInGradleFormat(accessor!!)).containsExactly("guava")
      assertThat(mutableCatalogModel.getVersions()).isNull()
      assertThat(mutableCatalogModel.getPlugins()).isNull()
      assertThat(mutableCatalogModel.getBundles()).isNull()
      assertThat(mutableCatalogModel.isModified).isFalse()
    }
  }

  private class ProjectModelFailingGradleModelProvider(private val delegate: GradleModelProvider) : GradleModelProvider() {
    override fun getProjectModel(project: Project): ProjectBuildModel {
      throw AssertionError("ProjectBuildModel.get(project) must not be called for sync fallback")
    }

    override fun getProjectModel(hostProject: Project, compositeRoot: String): ProjectBuildModel? {
      throw AssertionError("ProjectBuildModel must not be called for sync fallback")
    }

    override fun getBuildModel(project: Project): GradleBuildModel? = delegate.getBuildModel(project)

    override fun getBuildModel(module: Module): GradleBuildModel? = delegate.getBuildModel(module)

    override fun parseBuildFile(file: VirtualFile, project: Project): GradleBuildModel = delegate.parseBuildFile(file, project)

    override fun parseBuildFile(
      file: VirtualFile,
      project: Project,
      moduleName: String,
    ): GradleBuildModel = delegate.parseBuildFile(file, project, moduleName)

    override fun getSettingsModel(project: Project): GradleSettingsModel? = delegate.getSettingsModel(project)

    override fun getSettingsModel(
      settingsFile: VirtualFile,
      hostProject: Project,
    ): GradleSettingsModel = delegate.getSettingsModel(settingsFile, hostProject)

    override fun getSettingsModel(
      hostProject: Project,
      compositeRoot: String,
    ): GradleSettingsModel? = delegate.getSettingsModel(hostProject, compositeRoot)

    override fun getCachedVersionCatalogsModel(module: Module): GradleVersionCatalogsModel = delegate.getCachedVersionCatalogsModel(module)

    override fun getCachedVersionCatalogModel(
      catalogFile: VirtualFile,
      project: Project,
      catalogName: String,
    ): GradleVersionCatalogModel? = delegate.getCachedVersionCatalogModel(catalogFile, project, catalogName)
  }

  private fun VirtualFile.writeTextAndCommit(text: String) {
    findDocument()?.reloadFromDisk()
    writeText(text)
    findDocument()?.commitToPsi(project)
  }

  private fun extractDependenciesInGradleFormat(accessor: PsiClass): List<String> {
    fun extract(type: PsiClass?, path: List<String>): List<String> {
      return if (type is LightClass) {
        val methods = type.methods.toList()
        val result = mutableListOf<String>()
        for (method in methods) {
          if (method.name == "asProvider") {
            result.add(path.joinToString(separator = "."))
          } else {
            result.addAll(
              extract(
                method.returnType.resolve(),
                path + listOf(getPropertyName(method.name)!!),
              )
            )
          }
        }
        result
      } else {
        listOf(path.joinToString(separator = "."))
      }
    }
    return extract(accessor, listOf())
  }
}
