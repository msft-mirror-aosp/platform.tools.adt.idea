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
import com.android.tools.idea.gradle.util.GradleProjectSystemUtil
import com.android.tools.idea.npw.NewProjectWizardTestUtils.getAgpVersion
import com.android.tools.idea.npw.baselineprofiles.ConfigureBaselineProfilesModuleStep
import com.android.tools.idea.npw.baselineprofiles.NewBaselineProfilesModuleModel
import com.android.tools.idea.npw.model.AgpVersionSelector
import com.android.tools.idea.npw.model.ProjectSyncInvoker
import com.android.tools.idea.npw.platform.AndroidVersionsInfo
import com.android.tools.idea.observable.BatchInvoker
import com.android.tools.idea.testing.AndroidGradleProjectRule
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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import java.awt.EventQueue
import java.io.File
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

  @get:Rule val projectRule = AndroidGradleProjectRule(agpVersionSoftwareEnvironment = getAgpVersion())

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
  fun stepEntryAndTargetModuleSelectionUpdatePropertiesFromSyncedModels() {
    projectRule.loadProject(
      TestProjectPaths.APP_WITH_WEAR_AND_TV_AND_AUTOMOTIVE,
      agpVersion = getAgpVersion(),
    )

    val project = projectRule.project
    val appModule = project.findAppModule()
    val tvModule = project.findModule("tvApp")
    val wearModule = project.findModule("wearApp")
    val automotiveModule = project.findModule("automotiveApp")

    val appBuildGradle = VfsUtil.findFileByIoFile(File(project.basePath!!, "app/build.gradle.kts"), false)!!
    val appDocument = runInEdtAndGet {
      projectRule.fixture.openFileInEditor(appBuildGradle)
      val doc = FileDocumentManager.getInstance().getDocument(appBuildGradle)!!
      val originalText = doc.text
      val updatedText = Regex("minSdk\\s*=\\s*\\d+").replace(originalText, "minSdk = 30")
      assertThat(updatedText).isNotEqualTo(originalText)
      WriteCommandAction.runWriteCommandAction(project) {
        doc.setText(updatedText)
        PsiDocumentManager.getInstance(project).commitDocument(doc)
      }
      assertThat(PsiDocumentManager.getInstance(project).isCommitted(doc)).isTrue()
      assertThat(FileDocumentManager.getInstance().isDocumentUnsaved(doc)).isTrue()
      doc
    }

    val (step, model) =
      runInEdtAndGet {
        val newModel =
          NewBaselineProfilesModuleModel(
            project = project,
            moduleParent = ":",
            projectSyncInvoker = AddBaselineProfilesModuleTest.emptyProjectSyncInvoker,
          )
        Disposer.register(disposable, newModel)
        ConfigureBaselineProfilesModuleStep(model = newModel, disposable = disposable).also {
          Disposer.register(disposable, it)
        } to newModel
      }

    // Ensure the test thread is not the EDT so SwingUtilities.invokeAndWait dispatches on lock-free Swing EDT.
    assertThat(EventQueue.isDispatchThread()).isFalse()

    SwingUtilities.invokeAndWait {
      val app = ApplicationManager.getApplication()
      assertThat(EventQueue.isDispatchThread()).isTrue()
      assertThat(app.isReadAccessAllowed).isFalse()
      assertThat(app.isWriteIntentLockAcquired).isFalse()

      // Entering the wizard selects the mobile app module (synced minSdk = 24, ignoring unsynced minSdk = 30 edit).
      val wizard = ModelWizard.Builder().addStep(step).build()
      Disposer.register(disposable, wizard)
      assertThat(model.targetModule.value).isEqualTo(appModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION)
      assertThat(step.useGmdCheck.isEnabled).isTrue()
      assertThat(model.useGmd.get()).isFalse()

      step.useGmdCheck.isSelected = true
      assertThat(model.useGmd.get()).isTrue()

      // Switching to tvApp (synced minSdk = 21 < 24) clamps minApiLevel to 24 and disables GMD.
      step.targetModuleCombo.selectedItem = tvModule
      assertThat(app.isReadAccessAllowed).isFalse()
      assertThat(app.isWriteIntentLockAcquired).isFalse()
      assertThat(model.targetModule.value).isEqualTo(tvModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION)
      assertThat(step.useGmdCheck.isEnabled).isFalse()
      assertThat(step.useGmdCheck.isSelected).isFalse()
      assertThat(model.useGmd.get()).isFalse()

      // Switching to wearApp (synced minSdk = 25 > 24) updates minApiLevel to 25 and keeps GMD disabled.
      step.targetModuleCombo.selectedItem = wearModule
      assertThat(model.targetModule.value).isEqualTo(wearModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(25)
      assertThat(step.useGmdCheck.isEnabled).isFalse()
      assertThat(model.useGmd.get()).isFalse()

      // Switching to automotiveApp (synced minSdk = 28 > 24) updates minApiLevel to 28 and keeps GMD disabled.
      step.targetModuleCombo.selectedItem = automotiveModule
      assertThat(model.targetModule.value).isEqualTo(automotiveModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(28)
      assertThat(step.useGmdCheck.isEnabled).isFalse()
      assertThat(model.useGmd.get()).isFalse()

      // Switching back to app re-enables GMD and still uses synced minSdk = 24 rather than the unsynced edit.
      step.targetModuleCombo.selectedItem = appModule
      assertThat(model.targetModule.value).isEqualTo(appModule)
      assertThat(model.androidSdkInfo.value.minApiLevel).isEqualTo(SdkVersionInfo.LOWEST_PROFILE_GUIDED_OPTIMIZATIONS_SDK_VERSION)
      assertThat(step.useGmdCheck.isEnabled).isTrue()
      assertThat(model.useGmd.get()).isFalse()
    }

    runInEdtAndWait {
      assertThat(PsiDocumentManager.getInstance(project).isCommitted(appDocument)).isTrue()
      assertThat(FileDocumentManager.getInstance().isDocumentUnsaved(appDocument)).isTrue()
      assertThat(PsiDocumentManager.getInstance(project).getPsiFile(appDocument)!!.text).contains("minSdk = 30")
    }
  }
}
