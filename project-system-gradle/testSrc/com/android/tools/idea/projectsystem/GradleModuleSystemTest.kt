/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.projectsystem

import com.android.SdkConstants
import com.android.ide.common.gradle.Dependency
import com.android.ide.common.repository.GoogleMavenArtifactId
import com.android.tools.idea.gradle.dependencies.GradleDependencyManager
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntity
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntityId
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntitySource
import com.android.tools.idea.gradle.project.entities.gradleAndroidModel
import com.android.tools.idea.gradle.project.entities.gradleProjectPath
import com.android.tools.idea.gradle.project.entities.modifyGradleAndroidModelEntity
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.projectsystem.gradle.GradleDependencyCompatibilityAnalyzer
import com.android.tools.idea.projectsystem.gradle.GradleHolderProjectPath
import com.android.tools.idea.projectsystem.gradle.GradleModuleSystem
import com.android.tools.idea.projectsystem.gradle.GradleProjectPath
import com.android.tools.idea.projectsystem.gradle.ProjectBuildModelHandler
import com.android.tools.idea.projectsystem.gradle.toSourceSetPath
import com.android.tools.idea.res.MainContentRootSampleDataDirectoryProvider
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IdeComponents
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.android.AndroidTestBase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * These unit tests use a local test maven repo "project-system-gradle/testData/repoIndex". To see what dependencies are available to test
 * with, go to that folder and look at the group-indices.
 */
@RunsInEdt
class GradleModuleSystemTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val project
    get() = projectRule.project

  private var _gradleDependencyManager: GradleDependencyManager? = null
  private var _gradleModuleSystem: GradleModuleSystem? = null
  private var androidProject: IdeAndroidProject? = null
  private val gradleDependencyManager
    get() = _gradleDependencyManager!!

  private val gradleModuleSystem
    get() = _gradleModuleSystem!!

  private val moduleHierarchyProviderStub = object : ModuleHierarchyProvider {}

  @Before
  fun setUp() {
    val fixture = projectRule.fixture
    fixture.testDataPath = AndroidTestBase.getTestDataPath()
    fixture.copyFileToProject(SdkConstants.FN_ANDROID_MANIFEST_XML, SdkConstants.FN_ANDROID_MANIFEST_XML)
    _gradleDependencyManager = IdeComponents(project).mockProjectService(GradleDependencyManager::class.java)
    _gradleModuleSystem = GradleModuleSystem(projectRule.module, ProjectBuildModelHandler(project), moduleHierarchyProviderStub)
    assertThat(gradleModuleSystem.getAndroidLibraryDependencies(DependencyScopeType.MAIN)).isEmpty()
  }

  @After
  fun tearDown() {
    _gradleDependencyManager = null
    _gradleModuleSystem = null
    androidProject = null
  }

  @Test
  fun testRegisterDependencyDependency() {
    val dependency = GoogleMavenArtifactId.CONSTRAINT_LAYOUT.getDependency("+")
    gradleModuleSystem.registerDependency(dependency, DependencyType.IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "implementation")
  }

  @Test
  fun testRegisterDebugDependencyDependency() {
    val dependency = GoogleMavenArtifactId.COMPOSE_TOOLING.getDependency("+")
    gradleModuleSystem.registerDependency(dependency, DependencyType.DEBUG_IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "debugImplementation")
  }

  @Test
  fun testRegisterAnnotationProcessorDependencyDependency() {
    val dependency = GoogleMavenArtifactId.ROOM_COMPILER.getDependency("+")
    gradleModuleSystem.registerDependency(dependency, DependencyType.ANNOTATION_PROCESSOR)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "annotationProcessor")
  }

  @Test
  fun testRegisterDependencyId() {
    val id = gradleModuleSystem.getRegisteredDependencyId(GoogleMavenArtifactId.CONSTRAINT_LAYOUT)
    val dependency = GoogleMavenArtifactId.CONSTRAINT_LAYOUT.getDependency("+")
    gradleModuleSystem.registerDependency(id, DependencyType.IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "implementation")
  }

  @Test
  fun testRegisterDebugDependencyId() {
    val id = gradleModuleSystem.getRegisteredDependencyId(GoogleMavenArtifactId.COMPOSE_TOOLING)
    val dependency = GoogleMavenArtifactId.COMPOSE_TOOLING.getDependency("+")
    gradleModuleSystem.registerDependency(id, DependencyType.DEBUG_IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "debugImplementation")
  }

  @Test
  fun testRegisterAnnotationProcessorDependencyId() {
    val id = gradleModuleSystem.getRegisteredDependencyId(GoogleMavenArtifactId.ROOM_COMPILER)
    val dependency = GoogleMavenArtifactId.ROOM_COMPILER.getDependency("+")
    gradleModuleSystem.registerDependency(id, DependencyType.ANNOTATION_PROCESSOR)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "annotationProcessor")
  }

  @Test
  fun testRegisterDependencyVersionedId() {
    val dependency = GoogleMavenArtifactId.CONSTRAINT_LAYOUT.getDependency("1.2.3")
    val id = gradleModuleSystem.getRegisteredDependencyId(dependency)
    gradleModuleSystem.registerDependency(id, DependencyType.IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "implementation")
  }

  @Test
  fun testRegisterDebugDependencyVersionedId() {
    val dependency = GoogleMavenArtifactId.COMPOSE_TOOLING.getDependency("4.5.6")
    val id = gradleModuleSystem.getRegisteredDependencyId(dependency)
    gradleModuleSystem.registerDependency(id, DependencyType.DEBUG_IMPLEMENTATION)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "debugImplementation")
  }

  @Test
  fun testRegisterAnnotationProcessorDependencyVersionedId() {
    val dependency = GoogleMavenArtifactId.ROOM_COMPILER.getDependency("7.8.9")
    val id = gradleModuleSystem.getRegisteredDependencyId(dependency)
    gradleModuleSystem.registerDependency(id, DependencyType.ANNOTATION_PROCESSOR)
    Mockito.verify<GradleDependencyManager>(gradleDependencyManager, times(1))
      .addDependencies(projectRule.module, listOf(dependency), "annotationProcessor")
  }

  @Test
  fun testNoGradleAndroidModel() {
    // The GradleAndroidModel shouldn't be created when running from an IdeaTestCase.
    assertThat(GradleAndroidModel.get(projectRule.module)).isNull()
    assertThat(gradleModuleSystem.hasResolvedDependency(GoogleMavenArtifactId.SUPPORT_APPCOMPAT_V7)).isFalse()
  }

  @Test
  fun testGetPackageName_noOverrides() {
    val packageName = (projectRule.module.getModuleSystem() as DefaultModuleSystem).getPackageName()
    assertThat(packageName).isEqualTo("p1.p2")
  }

  @Test
  fun testInitDoesNotCreateDependencyCompatibilityAnalyzer() {
    Mockito.mockConstruction(GradleDependencyCompatibilityAnalyzer::class.java) { mock, _ ->
        whenever(mock.analyzeDependencyCompatibility(any())).thenReturn(Futures.immediateFuture(Triple(emptyMap(), emptyList(), "")))
      }
      .use { constructed ->
        val moduleSystem = GradleModuleSystem(projectRule.module, ProjectBuildModelHandler(project), moduleHierarchyProviderStub)
        assertThat(constructed.constructed()).isEmpty()

        moduleSystem.analyzeDependencyCompatibility(emptyList<Dependency>()).get()
        assertThat(constructed.constructed()).hasSize(1)

        moduleSystem.analyzeDependencyCompatibility(emptyList<Dependency>()).get()
        assertThat(constructed.constructed()).hasSize(2)
      }
  }

  @Test
  fun testAndroidModelReflectsWorkspaceModelUpdatesAndDisposal() {
    val holderModule = projectRule.module
    val mainModule = runWriteAction {
      ModuleManager.getInstance(project).newModule(project.basePath + "/app.main.iml", JavaModuleType.getModuleType().id)
    }
    val moduleSystem = GradleModuleSystem(mainModule, ProjectBuildModelHandler(project), moduleHierarchyProviderStub)
    assertThat(moduleSystem.androidModel).isNull()

    val holderPath = GradleHolderProjectPath("/project", ":app")
    attachGradleProjectPath(holderModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))

    val model1 = createMockAndroidModel(holderModule)
    setAndroidModelEntity(holderModule, model1)
    assertThat(moduleSystem.androidModel).isSameAs(model1)

    val model2 = createMockAndroidModel(holderModule)
    setAndroidModelEntity(holderModule, model2)
    assertThat(moduleSystem.androidModel).isSameAs(model2)

    setAndroidModelEntity(holderModule, null)
    assertThat(moduleSystem.androidModel).isNull()

    setAndroidModelEntity(holderModule, model2)
    assertThat(moduleSystem.androidModel).isSameAs(model2)

    runWriteAction { ModuleManager.getInstance(project).disposeModule(mainModule) }
    assertThat(moduleSystem.androidModel).isNull()
  }

  @Test
  fun testInitDoesNotInteractWithModuleOrDependencies() {
    val mockModule = Mockito.mock(Module::class.java)
    val mockBuildModelHandler = Mockito.mock(ProjectBuildModelHandler::class.java)
    val mockHierarchyProvider = Mockito.mock(ModuleHierarchyProvider::class.java)

    GradleModuleSystem(mockModule, mockBuildModelHandler, mockHierarchyProvider)

    verifyNoInteractions(mockModule, mockBuildModelHandler, mockHierarchyProvider)
  }

  @Test
  fun testGetSampleDataDirectoryResolvesHolderModuleDynamically() {
    val holderModule = projectRule.module
    val expectedSampleDataDir = MainContentRootSampleDataDirectoryProvider(holderModule).getSampleDataDirectory()
    assertThat(expectedSampleDataDir).isNotNull()

    val mainModule = runWriteAction {
      ModuleManager.getInstance(project).newModule(project.basePath + "/app.sample.main.iml", JavaModuleType.getModuleType().id)
    }
    val moduleSystem = GradleModuleSystem(mainModule, ProjectBuildModelHandler(project), moduleHierarchyProviderStub)
    assertThat(moduleSystem.getSampleDataDirectory()).isNull()

    val holderPath = GradleHolderProjectPath("/project", ":app")
    attachGradleProjectPath(holderModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    setAndroidModelEntity(holderModule, createMockAndroidModel(holderModule))

    assertThat(moduleSystem.getSampleDataDirectory()).isEqualTo(expectedSampleDataDir)
  }

  private fun attachGradleProjectPath(module: Module, path: GradleProjectPath) {
    runWriteAction {
      WorkspaceModel.getInstance(project).updateProjectModel("Attach GradleProjectPath") { storage ->
        val moduleEntity = storage.resolve(ModuleId(module.name))!!
        storage.modifyModuleEntity(moduleEntity) {
          this.gradleProjectPath = GradleProjectPathEntity(path, GradleProjectPathEntitySource)
        }
      }
    }
  }

  private fun createMockAndroidModel(holderModule: Module): GradleAndroidModelImpl {
    val mockModel = Mockito.mock(GradleAndroidModelImpl::class.java)
    val mockVariant = Mockito.mock(IdeVariantCoreImpl::class.java)
    whenever(mockVariant.testSuiteComponents).thenReturn(emptyList())
    whenever(mockModel.moduleName).thenReturn(holderModule.name)
    whenever(mockModel.selectedVariant).thenReturn(mockVariant)
    return mockModel
  }

  private fun setAndroidModelEntity(holderModule: Module, model: GradleAndroidModelImpl?) {
    runWriteAction {
      WorkspaceModel.getInstance(project).updateProjectModel("Update GradleAndroidModel") { storage ->
        val moduleEntity = storage.resolve(ModuleId(holderModule.name))!!
        val existing = storage.resolve(GradleAndroidModelEntityId(moduleEntity.symbolicId))
        when {
          model == null -> existing?.let { storage.removeEntity(it) }
          existing != null -> storage.modifyGradleAndroidModelEntity(existing) { gradleAndroidModel = model }
          else ->
            storage.modifyModuleEntity(moduleEntity) {
              this.gradleAndroidModel = GradleAndroidModelEntity(gradleAndroidModel = model, entitySource = moduleEntity.entitySource)
            }
        }
      }
    }
  }
}
