/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.npw.module

import com.android.sdklib.SdkVersionInfo
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.api.GradleSettingsModel
import com.android.tools.idea.gradle.dsl.api.GradleVersionCatalogModel
import com.android.tools.idea.gradle.dsl.api.GradleVersionCatalogsModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.util.GradleProjectSystemUtil
import com.android.tools.idea.npw.NewProjectWizardTestUtils.getAgpVersion
import com.android.tools.idea.npw.baselineprofiles.ConfigureBaselineProfilesModuleStep
import com.android.tools.idea.npw.baselineprofiles.NewBaselineProfilesModuleModel
import com.android.tools.idea.npw.model.AgpVersionSelector
import com.android.tools.idea.npw.model.ProjectSyncInvoker
import com.android.tools.idea.npw.platform.AndroidVersionsInfo
import com.android.tools.idea.observable.BatchInvoker
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.findAppModule
import com.android.tools.idea.testing.findModule
import com.android.tools.idea.testing.onEdt
import com.android.tools.idea.wizard.model.ModelWizard
import com.android.tools.idea.wizard.template.DslLanguage
import com.android.tools.idea.wizard.template.DslLanguage.GROOVY
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import java.awt.EventQueue
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AddBaselineProfilesModuleTest(private val useGmdParam: Boolean) {

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "useGmdParam={0}") fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))

    fun addNewBaselineProfilesModule(projectRule: AndroidGradleProjectRule, useGmdParam: Boolean, language: DslLanguage) {
      projectRule.load(TestProjectPaths.ANDROIDX_WITH_LIB_MODULE, agpVersion = getAgpVersion())

      val project = projectRule.project
      val model =
        NewBaselineProfilesModuleModel(project = project, moduleParent = ":", projectSyncInvoker = emptyProjectSyncInvoker).apply {
          androidSdkInfo.value = AndroidVersionsInfo.VersionItem.fromStableVersion(SdkVersionInfo.HIGHEST_KNOWN_STABLE_API)
          targetModule.value = project.findAppModule()
          dslLanguage.set(language)
          useGmd.set(useGmdParam)
          agpVersionSelector.set(AgpVersionSelector.FixedVersion(GradleProjectSystemUtil.getAndroidGradleModelVersionInUse(project)!!))
        }

      model.handleFinished() // Generate module files

      projectRule.invokeTasks("assembleDebug").apply {
        buildError?.printStackTrace()
        Assert.assertTrue("Project didn't compile correctly", isBuildSuccessful)
      }
    }

    // Ignore project sync (to speed up test), if later we are going to perform a gradle build
    // anyway.
    val emptyProjectSyncInvoker =
      object : ProjectSyncInvoker {
        override fun syncProject(project: Project) {}
      }
  }

  @get:Rule val projectRule = AndroidGradleProjectRule(agpVersionSoftwareEnvironment = getAgpVersion())

  @Test
  fun addNewBaselineProfilesModuleTest() {
    addNewBaselineProfilesModule(projectRule, useGmdParam, GROOVY)
  }
}

class ConfigureBaselineProfilesModuleStepTest {

  @get:Rule val projectRule = AndroidGradleProjectRule(agpVersionSoftwareEnvironment = getAgpVersion()).onEdt()

  private lateinit var disposable: Disposable

  @Before
  fun setup() {
    disposable = Disposer.newDisposable()
    BatchInvoker.setOverrideStrategy(BatchInvoker.INVOKE_IMMEDIATELY_STRATEGY)
  }

  @After
  fun tearDown() {
    BatchInvoker.clearOverrideStrategy()
    Disposer.dispose(disposable)
  }

  private suspend fun buildStepWithProject(
    targetProjectPath: String
  ): Pair<ConfigureBaselineProfilesModuleStep, NewBaselineProfilesModuleModel> {
    return withContext(Dispatchers.EDT) {
      projectRule.loadProject(targetProjectPath, agpVersion = getAgpVersion())
      val model =
        NewBaselineProfilesModuleModel(
          project = projectRule.project,
          moduleParent = ":",
          projectSyncInvoker = AddBaselineProfilesModuleTest.emptyProjectSyncInvoker,
        )
      ConfigureBaselineProfilesModuleStep(disposable = disposable, model = model) to model
    }
  }

  @Test
  fun withAppProjectWithPhoneAndWearAndTvAndAutomotive() =
    runBlocking(Dispatchers.EDT) {
      val (step, model) = buildStepWithProject(TestProjectPaths.APP_WITH_WEAR_AND_TV_AND_AUTOMOTIVE)

      assertEquals(step.targetModuleCombo.selectedItem, projectRule.project.findAppModule())
      assertEquals(4, step.targetModuleCombo.itemCount)
      assertTrue(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())

      step.targetModuleCombo.selectedItem = projectRule.project.findModule("automotiveApp")
      assertFalse(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())

      step.targetModuleCombo.selectedItem = projectRule.project.findModule("wearApp")
      assertFalse(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())

      step.targetModuleCombo.selectedItem = projectRule.project.findModule("tvApp")
      assertFalse(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())

      step.targetModuleCombo.selectedItem = projectRule.project.findAppModule()
      assertTrue(step.useGmdCheck.isEnabled)
      // Can be true or false depending on GMD selection state.
      assertEquals(step.useGmdCheck.isSelected, model.useGmd.get())
    }

  @Test
  fun withLibProjectOnly() =
    runBlocking(Dispatchers.EDT) {
      val (step, model) = buildStepWithProject(TestProjectPaths.KOTLIN_LIB)

      assertNull(step.targetModuleCombo.selectedItem)
      assertEquals(0, step.targetModuleCombo.itemCount)
      assertFalse(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())
    }

  @Test
  fun withAppWithLibProject() =
    runBlocking(Dispatchers.EDT) {
      val (step, model) = buildStepWithProject(TestProjectPaths.ANDROIDX_WITH_LIB_MODULE)

      assertEquals(step.targetModuleCombo.selectedItem, projectRule.project.findAppModule())
      assertEquals(1, step.targetModuleCombo.itemCount)
      assertTrue(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())
    }

  @Test
  fun withWearAppProjectOnly() =
    runBlocking(Dispatchers.EDT) {
      val (step, model) = buildStepWithProject(TestProjectPaths.WEAR_WATCHFACE)

      assertEquals(step.targetModuleCombo.selectedItem, projectRule.project.findAppModule())
      assertEquals(1, step.targetModuleCombo.itemCount)
      assertFalse(step.useGmdCheck.isEnabled)
      assertFalse(step.useGmdCheck.isSelected)
      assertFalse(model.useGmd.get())
    }
}

class ConfigureBaselineProfilesModuleStepLockFreeEdtTest {

  @get:Rule
  val projectRule =
    AndroidProjectRule.withAndroidModels(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        ":lowSdkApp",
        "debug",
        AndroidProjectBuilder(applicationIdFor = { "com.example.lowsdk" }, minSdk = { LOW_MIN_SDK }),
      ),
      AndroidModuleModelBuilder(
        ":highSdkApp",
        "debug",
        AndroidProjectBuilder(applicationIdFor = { "com.example.highsdk" }, minSdk = { HIGH_MIN_SDK }),
      ),
    )

  private lateinit var disposable: Disposable

  @Before
  fun setUp() {
    disposable = Disposer.newDisposable()
    BatchInvoker.setOverrideStrategy(BatchInvoker.INVOKE_IMMEDIATELY_STRATEGY)
  }

  @After
  fun tearDown() {
    BatchInvoker.clearOverrideStrategy()
    runInEdtAndWait { Disposer.dispose(disposable) }
  }

  @Test
  fun stepEntryAndTargetModuleSelectionWorkOnLockFreeEdtWithoutGradleDslParsing() {
    projectRule.replaceService(
      GradleModelProvider::class.java,
      EdtFailingGradleModelProvider(GradleModelProvider.getInstance()),
    )

    val project = projectRule.project
    val lowSdkModule = project.findModule("lowSdkApp")
    val highSdkModule = project.findModule("highSdkApp")

    val (step, model) =
      runInEdtAndGet {
        val newModel =
          NewBaselineProfilesModuleModel(
            project = project,
            moduleParent = ":",
            projectSyncInvoker = AddBaselineProfilesModuleTest.emptyProjectSyncInvoker,
          )
        Disposer.register(disposable, newModel)
        ConfigureBaselineProfilesModuleStep(model = newModel, disposable = disposable).also { Disposer.register(disposable, it) } to
          newModel
      }

    // Ensure the test thread is not the EDT so SwingUtilities.invokeAndWait dispatches on lock-free Swing EDT.
    assertThat(EventQueue.isDispatchThread()).isFalse()

    SwingUtilities.invokeAndWait {
      val app = ApplicationManager.getApplication()
      assertThat(EventQueue.isDispatchThread()).isTrue()
      assertThat(app.isReadAccessAllowed).isFalse()
      assertThat(app.isWriteIntentLockAcquired).isFalse()

      model.targetModule.value = lowSdkModule
      val wizard = ModelWizard.Builder().addStep(step).build()
      Disposer.register(disposable, wizard)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION)

      step.targetModuleCombo.selectedItem = highSdkModule
      assertThat(app.isReadAccessAllowed).isFalse()
      assertThat(app.isWriteIntentLockAcquired).isFalse()
      assertThat(model.targetModule.value).isEqualTo(highSdkModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(HIGH_MIN_SDK)

      step.targetModuleCombo.selectedItem = lowSdkModule
      assertThat(model.targetModule.value).isEqualTo(lowSdkModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION)
    }
  }

  private class EdtFailingGradleModelProvider(private val delegate: GradleModelProvider) : GradleModelProvider() {
    private fun <T> forbidEdt(method: String, call: () -> T): T {
      if (EventQueue.isDispatchThread()) {
        throw AssertionError("GradleModelProvider.$method must not be called on the EDT")
      }
      return call()
    }

    override fun getProjectModel(project: Project): ProjectBuildModel = forbidEdt("getProjectModel") { delegate.getProjectModel(project) }

    override fun getProjectModel(hostProject: Project, compositeRoot: String): ProjectBuildModel? =
      forbidEdt("getProjectModel") { delegate.getProjectModel(hostProject, compositeRoot) }

    override fun getBuildModel(project: Project): GradleBuildModel? = forbidEdt("getBuildModel") { delegate.getBuildModel(project) }

    override fun getBuildModel(module: Module): GradleBuildModel? = forbidEdt("getBuildModel") { delegate.getBuildModel(module) }

    override fun parseBuildFile(file: VirtualFile, project: Project): GradleBuildModel =
      forbidEdt("parseBuildFile") { delegate.parseBuildFile(file, project) }

    override fun parseBuildFile(file: VirtualFile, project: Project, moduleName: String): GradleBuildModel =
      forbidEdt("parseBuildFile") { delegate.parseBuildFile(file, project, moduleName) }

    override fun getSettingsModel(project: Project): GradleSettingsModel? =
      forbidEdt("getSettingsModel") { delegate.getSettingsModel(project) }

    override fun getSettingsModel(settingsFile: VirtualFile, hostProject: Project): GradleSettingsModel =
      forbidEdt("getSettingsModel") { delegate.getSettingsModel(settingsFile, hostProject) }

    override fun getSettingsModel(hostProject: Project, compositeRoot: String): GradleSettingsModel? =
      forbidEdt("getSettingsModel") { delegate.getSettingsModel(hostProject, compositeRoot) }

    override fun getCachedVersionCatalogsModel(module: Module): GradleVersionCatalogsModel =
      forbidEdt("getCachedVersionCatalogsModel") { delegate.getCachedVersionCatalogsModel(module) }

    override fun getCachedVersionCatalogModel(
      catalogFile: VirtualFile,
      project: Project,
      catalogName: String,
    ): GradleVersionCatalogModel? =
      forbidEdt("getCachedVersionCatalogModel") {
        delegate.getCachedVersionCatalogModel(catalogFile, project, catalogName)
      }
  }

  companion object {
    private const val LOW_MIN_SDK = SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION - 4
    private const val HIGH_MIN_SDK = SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION + 2
  }
}
