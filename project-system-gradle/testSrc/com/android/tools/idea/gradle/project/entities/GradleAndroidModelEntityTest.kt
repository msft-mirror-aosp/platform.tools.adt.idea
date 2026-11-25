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
package com.android.tools.idea.gradle.project.entities

import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeVariantCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeVariantImpl
import com.android.tools.idea.gradle.project.model.GradleAndroidDependencyModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModelImpl
import com.android.tools.idea.projectsystem.gradle.GradleHolderProjectPath
import com.android.tools.idea.projectsystem.gradle.GradleProjectPath
import com.android.tools.idea.projectsystem.gradle.LINKED_ANDROID_GRADLE_MODULE_GROUP
import com.android.tools.idea.projectsystem.gradle.LinkedAndroidGradleModuleGroup
import com.android.tools.idea.projectsystem.gradle.toSourceSetPath
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModulePointer
import com.intellij.openapi.module.ModulePointerManager
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@RunsInEdt
@RunWith(JUnit4::class)
class GradleAndroidModelEntityTest {

  @get:Rule val projectRule = AndroidProjectRule.onDisk().onEdt()

  private val project
    get() = projectRule.project

  private object TestEntitySource : EntitySource

  private fun createExtraModule(name: String): Module = runWriteAction {
    ModuleManager.getInstance(project).newModule(project.basePath + "/$name.iml", JavaModuleType.getModuleType().id)
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

  private fun attachAndroidModelEntity(
    holderModule: Module,
    model: GradleAndroidModelImpl,
    resolvedVariant: IdeVariantImpl? = null,
  ) {
    runWriteAction {
      WorkspaceModel.getInstance(project).updateProjectModel("Attach GradleAndroidModel") { storage ->
        val moduleEntity = storage.resolve(ModuleId(holderModule.name))!!
        storage.modifyModuleEntity(moduleEntity) {
          this.gradleAndroidModel =
            GradleAndroidModelEntity(gradleAndroidModel = model, entitySource = moduleEntity.entitySource) {
              this.resolvedVariant = resolvedVariant
            }
        }
      }
    }
  }

  private fun createMockModel(moduleName: String): GradleAndroidModelImpl {
    val model = mock(GradleAndroidModelImpl::class.java)
    val mockVariant = mock(IdeVariantCoreImpl::class.java)
    `when`(mockVariant.testSuiteComponents).thenReturn(emptyList())
    `when`(model.moduleName).thenReturn(moduleName)
    `when`(model.selectedVariant).thenReturn(mockVariant)
    `when`(model.selectedVariantName).thenReturn("debug")
    return model
  }

  @Test
  fun testCreateAndResolveGradleAndroidModelEntity() {
    val storage = MutableEntityStorage.create()
    val mockModel = createMockModel("app")

    val moduleEntity =
      storage.addEntity(
        ModuleEntity(
          name = "app",
          dependencies = emptyList(),
          entitySource = TestEntitySource,
        )
      )

    storage.modifyModuleEntity(moduleEntity) {
      this.gradleAndroidModel =
        GradleAndroidModelEntity(
          gradleAndroidModel = mockModel,
          entitySource = TestEntitySource,
        )
    }

    val resolvedEntity = storage.resolve(GradleAndroidModelEntityId(ModuleId("app")))
    assertThat(resolvedEntity).isNotNull()
    assertThat(resolvedEntity!!.gradleAndroidModel).isEqualTo(mockModel)
    assertThat(resolvedEntity.module.name).isEqualTo("app")
    assertThat(resolvedEntity.symbolicId.moduleId.name).isEqualTo("app")
    assertThat(resolvedEntity.symbolicId.presentableName).isEqualTo("GradleAndroidModelEntity for app")
  }

  @Test
  fun testBuilderModuleGetterAndSetterWithDiff() {
    val storage = MutableEntityStorage.create()
    val mockModel = createMockModel("app")

    val moduleEntity =
      storage.addEntity(
        ModuleEntity(
          name = "app",
          dependencies = emptyList(),
          entitySource = TestEntitySource,
        )
      )

    storage.modifyModuleEntity(moduleEntity) {
      this.gradleAndroidModel =
        GradleAndroidModelEntity(
          gradleAndroidModel = mockModel,
          entitySource = TestEntitySource,
        )
    }

    val entity = storage.resolve(GradleAndroidModelEntityId(ModuleId("app")))!!
    var retrievedModuleName: String? = null

    storage.modifyGradleAndroidModelEntity(entity) {
      // Exercises Builder.module getter when diff != null
      retrievedModuleName = this.module.name
    }

    assertThat(retrievedModuleName).isEqualTo("app")
  }

  @Test
  fun testBuilderModuleGetterAndSetterWithoutDiff() {
    val mockModel = createMockModel("app")
    val moduleBuilder =
      ModuleEntity(
        name = "app",
        dependencies = emptyList(),
        entitySource = TestEntitySource,
      )

    val entityBuilder =
      GradleAndroidModelEntity(
        gradleAndroidModel = mockModel,
        entitySource = TestEntitySource,
      ) {
        // Exercises Builder.module setter when diff == null
        this.module = moduleBuilder
      }

    // Exercises Builder.module getter when diff == null
    assertThat(entityBuilder.module).isEqualTo(moduleBuilder)
    assertThat(entityBuilder.module.name).isEqualTo("app")
  }

  @Test
  fun testModifyGradleAndroidModelEntityProperties() {
    val storage = MutableEntityStorage.create()
    val initialModel = createMockModel("app")
    val updatedModel = createMockModel("app")
    val mockVariant = mock(IdeVariantImpl::class.java)

    val moduleEntity =
      storage.addEntity(
        ModuleEntity(
          name = "app",
          dependencies = emptyList(),
          entitySource = TestEntitySource,
        )
      )

    storage.modifyModuleEntity(moduleEntity) {
      this.gradleAndroidModel =
        GradleAndroidModelEntity(
          gradleAndroidModel = initialModel,
          entitySource = TestEntitySource,
        )
    }

    val entity = storage.resolve(GradleAndroidModelEntityId(ModuleId("app")))!!

    storage.modifyGradleAndroidModelEntity(entity) {
      this.gradleAndroidModel = updatedModel
      this.resolvedVariant = mockVariant
    }

    val updatedEntity = storage.resolve(GradleAndroidModelEntityId(ModuleId("app")))!!
    assertThat(updatedEntity.gradleAndroidModel).isEqualTo(updatedModel)
    assertThat(updatedEntity.resolvedVariant).isEqualTo(mockVariant)
  }

  @Test
  fun testGradleAndroidModelEntityId() {
    val id1 = GradleAndroidModelEntityId(ModuleId("app"))
    val id2 = GradleAndroidModelEntityId(ModuleId("app"))
    val id3 = GradleAndroidModelEntityId(ModuleId("lib"))

    assertThat(id1).isEqualTo(id2)
    assertThat(id1.hashCode()).isEqualTo(id2.hashCode())
    assertThat(id1).isNotEqualTo(id3)
    assertThat(id1.presentableName).isEqualTo("GradleAndroidModelEntity for app")
  }

  @Test
  fun testGetGradleAndroidModelFromStorage() {
    val holderModule = projectRule.module
    val mainModule = createExtraModule("app.main")
    val unitTestModule = createExtraModule("app.unitTest")
    val androidTestModule = createExtraModule("app.androidTest")
    val commonMainModule = createExtraModule("app.commonMain")
    val unlinkedModule = createExtraModule("java.main")

    val holderPath = GradleHolderProjectPath("/project", ":app")
    attachGradleProjectPath(holderModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))
    attachGradleProjectPath(unitTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.UNIT_TEST))
    attachGradleProjectPath(androidTestModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.ANDROID_TEST))
    attachGradleProjectPath(commonMainModule, holderPath.toSourceSetPath(IdeModuleSourceSetImpl.wellKnownOrCreate("commonMain")))

    val model = createMockModel(holderModule.name)
    attachAndroidModelEntity(holderModule, model)

    val holderPtr = mock(ModulePointer::class.java)
    val mainPtr = mock(ModulePointer::class.java)
    val unitTestPtr = mock(ModulePointer::class.java)
    val androidTestPtr = mock(ModulePointer::class.java)
    val userDataGroup =
      LinkedAndroidGradleModuleGroup(
        holder = holderPtr,
        main = mainPtr,
        unitTest = unitTestPtr,
        androidTest = androidTestPtr,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    listOf(holderModule, mainModule, unitTestModule, androidTestModule, commonMainModule).forEach {
      it.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, userDataGroup)
    }

    val mockPointerManager = mock(ModulePointerManager::class.java)
    projectRule.projectRule.replaceProjectService(ModulePointerManager::class.java, mockPointerManager)

    val storage = WorkspaceModel.getInstance(project).currentSnapshot
    assertThat(storage.getGradleAndroidModel(holderModule)).isSameAs(model)
    assertThat(storage.getGradleAndroidModel(mainModule)).isSameAs(model)
    assertThat(storage.getGradleAndroidModel(unitTestModule)).isSameAs(model)
    assertThat(storage.getGradleAndroidModel(androidTestModule)).isSameAs(model)
    assertThat(storage.getGradleAndroidModel(commonMainModule)).isNull()
    assertThat(storage.getGradleAndroidModel(unlinkedModule)).isNull()

    verifyNoInteractions(mockPointerManager, holderPtr, mainPtr, unitTestPtr, androidTestPtr)
  }

  @Test
  fun testGetGradleAndroidModelWrapsResolvedVariant() {
    val holderModule = projectRule.module
    val mainModule = createExtraModule("app.main")
    val holderPath = GradleHolderProjectPath("/project", ":app")
    attachGradleProjectPath(holderModule, holderPath)
    attachGradleProjectPath(mainModule, holderPath.toSourceSetPath(IdeModuleWellKnownSourceSet.MAIN))

    val model = createMockModel(holderModule.name)
    val resolvedVariant = mock(IdeVariantImpl::class.java)
    `when`(resolvedVariant.name).thenReturn("debug")
    attachAndroidModelEntity(holderModule, model, resolvedVariant = resolvedVariant)

    val storage = WorkspaceModel.getInstance(project).currentSnapshot
    val result = storage.getGradleAndroidModel(mainModule)
    assertThat(result).isInstanceOf(GradleAndroidDependencyModel::class.java)
    assertThat((result as GradleAndroidDependencyModel).selectedVariantWithDependencies).isSameAs(resolvedVariant)
  }

  @Test
  fun testGetGradleAndroidModelFromUserDataFallback() {
    val kmpHolder = createExtraModule("shared")
    val kmpMain = createExtraModule("shared.androidMain")
    val pointerManager = ModulePointerManager.getInstance(project)
    val group =
      LinkedAndroidGradleModuleGroup(
        holder = pointerManager.create(kmpHolder),
        main = pointerManager.create(kmpMain),
        unitTest = null,
        androidTest = null,
        testFixtures = null,
        screenshotTest = null,
        testSuites = emptyList(),
      )
    attachGradleProjectPath(kmpHolder, GradleHolderProjectPath("/project", ":shared"))
    val model = createMockModel(kmpHolder.name)
    attachAndroidModelEntity(kmpHolder, model)
    kmpHolder.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)
    kmpMain.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, group)

    val storage = WorkspaceModel.getInstance(project).currentSnapshot
    assertThat(storage.getGradleAndroidModel(kmpHolder)).isSameAs(model)
    assertThat(storage.getGradleAndroidModel(kmpMain)).isSameAs(model)
  }
}
