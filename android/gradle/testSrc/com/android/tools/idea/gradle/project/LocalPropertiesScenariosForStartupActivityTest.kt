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
package com.android.tools.idea.gradle.project

import com.android.SdkConstants.FN_LOCAL_PROPERTIES
import com.android.tools.idea.gradle.project.entities.GradleAndroidModelEntity
import com.android.tools.idea.gradle.project.entities.GradleModuleModelEntity
import com.android.tools.idea.gradle.project.entities.GradleProjectPathEntity
import com.android.tools.idea.gradle.project.facet.gradle.GradleFacet
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.gradle.project.model.gradleModuleModel
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker
import com.android.tools.idea.gradle.project.sync.GradleSyncListener
import com.android.tools.idea.projectsystem.ProjectSyncModificationTracker
import com.android.tools.idea.projectsystem.gradle.LINKED_ANDROID_GRADLE_MODULE_GROUP
import com.android.tools.idea.projectsystem.gradle.getAndroidTestModule
import com.android.tools.idea.projectsystem.gradle.getGradleProjectPath
import com.android.tools.idea.projectsystem.gradle.getMainModule
import com.android.tools.idea.projectsystem.gradle.getUnitTestModule
import com.android.tools.idea.projectsystem.gradle.isLinkedAndroidModule
import com.android.tools.idea.projectsystem.gradle.resolveIn
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.onEdt
import com.android.tools.idea.testing.runDispatching
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger
import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.replaceService
import java.io.File
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.core.util.toVirtualFile
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for {@link AndroidGradleProjectStartupActivity} where local.properties isn't there or definition in the file isn't as expected. */
@RunsInEdt
class LocalPropertiesScenariosForStartupActivityTest {

  private var myStartupActivity: AndroidGradleProjectStartupActivity? = null
  private var myRequest: GradleSyncInvoker.Request? = null
  private var myInfo: GradleProjectInfo? = null

  @get:Rule val projectRule = AndroidGradleProjectRule().onEdt()

  @Before
  fun setUp() {
    projectRule.loadProject(TestProjectPaths.SIMPLE_APPLICATION)
    val syncInvoker =
      object : GradleSyncInvoker.FakeInvoker() {
        override fun requestProjectSync(project: Project, request: GradleSyncInvoker.Request, listener: GradleSyncListener?) {
          assertThat(myRequest).isNull()
          myRequest = request
          super.requestProjectSync(project, request, listener)
        }
      }
    ApplicationManager.getApplication().replaceService(GradleSyncInvoker::class.java, syncInvoker, projectRule.project)
    myStartupActivity = AndroidGradleProjectStartupActivity()
  }

  @After
  fun after() {
    myRequest = null
    myInfo = null
    myStartupActivity = null
  }

  @Test
  fun `test no sync when local properties file present`() {
    executeMyStartupActivity()
    assertThat(myRequest).isNull()
  }

  @Test
  fun `test sync for missing local properties file`() {
    deleteLocalProperties()
    executeMyStartupActivity()
    assertThat(myRequest).isNotNull()
    assertThat(myRequest!!.trigger).isEqualTo(Trigger.TRIGGER_PROJECT_MODIFIED)
  }

  @Test
  fun `test sync for missing sdk dir in local properties file`() {
    deleteSdkDirFromLocalProperties()
    executeMyStartupActivity()
    assertThat(myRequest).isNotNull()
    assertThat(myRequest!!.trigger).isEqualTo(Trigger.TRIGGER_PROJECT_MODIFIED)
  }

  @Test
  fun `test cached models and linked module groups are attached when sync is requested`() {
    val project = projectRule.project
    val appHolder = project.gradleModule(":app")!!

    // Simulate a freshly reopened project before AndroidGradleProjectStartupActivity attaches facet external sources and cached
    // Android/Gradle workspace model entities.
    ModuleManager.getInstance(project).modules.forEach { module ->
      module.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, null)
      FacetManager.getInstance(module).let { facetManager ->
        (facetManager.getFacetsByType(GradleFacet.getFacetTypeId()) + facetManager.getFacetsByType(AndroidFacet.ID)).forEach { facet ->
          facet.externalSource = null
        }
      }
    }
    runWriteAction {
      project.workspaceModel.updateProjectModel("Clear cached entities for reopen test") { storage ->
        storage.entities(GradleProjectPathEntity::class.java).toList().forEach(storage::removeEntity)
        storage.entities(GradleAndroidModelEntity::class.java).toList().forEach(storage::removeEntity)
        storage.entities(GradleModuleModelEntity::class.java).toList().forEach(storage::removeEntity)
      }
    }
    ProjectStructure.getInstance(project).analyzeProjectStructure()

    assertThat(appHolder.isLinkedAndroidModule()).isFalse()
    assertThat(GradleAndroidModel.get(appHolder)).isNull()
    assertThat(appHolder.gradleModuleModel).isNull()
    assertThat(ProjectStructure.getInstance(project).appHolderModules).isEmpty()

    deleteLocalProperties()
    executeMyStartupActivity()

    assertThat(myRequest).isNotNull()
    assertThat(myRequest!!.trigger).isEqualTo(Trigger.TRIGGER_PROJECT_MODIFIED)
    assertThat(appHolder.isLinkedAndroidModule()).isTrue()
    assertThat(appHolder.getMainModule()).isNotEqualTo(appHolder)
    assertThat(appHolder.getUnitTestModule()).isNotNull()
    assertThat(appHolder.getAndroidTestModule()).isNotNull()
    assertThat(GradleAndroidModel.get(appHolder)).isNotNull()
    assertThat(appHolder.gradleModuleModel).isNotNull()
    assertThat(ProjectStructure.getInstance(project).appHolderModules).containsExactly(appHolder)
    assertThat(AndroidFacet.getInstance(appHolder)!!.externalSource).isNotNull()
    assertThat(GradleFacet.getInstance(appHolder)!!.externalSource).isNotNull()
  }

  @Test
  fun `test cached GradleProjectPath is invalidated when ExternalProjectsDataStorage loads`() {
    val project = projectRule.project
    val appHolder = project.gradleModule(":app")!!
    val expectedAppPath = appHolder.getGradleProjectPath()!!
    val externalProjectInfo =
      ProjectDataManager.getInstance().getExternalProjectData(project, GradleConstants.SYSTEM_ID, project.basePath!!)!!

    // Simulate a freshly reopened project where workspace model entities from the previous session are not attached and
    // ExternalProjectsDataStorage has not yet finished loading external project data.
    ModuleManager.getInstance(project).modules.forEach { module ->
      module.putUserData(LINKED_ANDROID_GRADLE_MODULE_GROUP, null)
      FacetManager.getInstance(module).let { facetManager ->
        (facetManager.getFacetsByType(GradleFacet.getFacetTypeId()) + facetManager.getFacetsByType(AndroidFacet.ID)).forEach { facet ->
          facet.externalSource = null
        }
      }
    }
    runWriteAction {
      project.workspaceModel.updateProjectModel("Clear cached entities for reopen test") { storage ->
        storage.entities(GradleProjectPathEntity::class.java).toList().forEach(storage::removeEntity)
        storage.entities(GradleAndroidModelEntity::class.java).toList().forEach(storage::removeEntity)
        storage.entities(GradleModuleModelEntity::class.java).toList().forEach(storage::removeEntity)
      }
    }
    val dataStorage = ExternalProjectsDataStorage.getInstance(project)
    ExternalProjectsManagerImpl.getInstance(project).forgetExternalProjectData(GradleConstants.SYSTEM_ID, project.basePath!!)
    dataStorage.incModificationCount()

    // Query getGradleProjectPath() and resolveIn() before ExternalProjectsDataStorage finishes loading (b/582423009).
    assertThat(appHolder.getGradleProjectPath()).isNull()
    assertThat(expectedAppPath.resolveIn(project)).isNull()

    val rootModCountBefore = ProjectRootModificationTracker.getInstance(project).modificationCount
    val syncModCountBefore = ProjectSyncModificationTracker.getInstance(project).modificationCount

    // Simulate ExternalProjectsDataStorage finishing load(), which updates dataStorage and only increments its modification count.
    dataStorage.update(externalProjectInfo)

    assertThat(ProjectRootModificationTracker.getInstance(project).modificationCount).isEqualTo(rootModCountBefore)
    assertThat(ProjectSyncModificationTracker.getInstance(project).modificationCount).isEqualTo(syncModCountBefore)
    assertThat(appHolder.getGradleProjectPath()).isEqualTo(expectedAppPath)
    assertThat(expectedAppPath.resolveIn(project)).isEqualTo(appHolder)

    executeMyStartupActivity()

    assertThat(myRequest).isNull()
    assertThat(appHolder.isLinkedAndroidModule()).isTrue()
    assertThat(GradleAndroidModel.get(appHolder)).isNotNull()
    assertThat(appHolder.gradleModuleModel).isNotNull()
  }

  private fun executeMyStartupActivity() {
    runDispatching { myStartupActivity!!.execute(projectRule.project) }
  }

  private fun deleteLocalProperties() {
    val localPropertiesFile = File(projectRule.project.basePath, FN_LOCAL_PROPERTIES)
    assertThat(localPropertiesFile.exists()).isTrue()
    runWriteAction { localPropertiesFile.toVirtualFile()!!.delete(null) }
  }

  private fun deleteSdkDirFromLocalProperties() {
    val localPropertiesFile = File(projectRule.project.basePath, FN_LOCAL_PROPERTIES)
    runWriteAction { VfsUtil.saveText(localPropertiesFile.toVirtualFile()!!, "") }
  }
}
