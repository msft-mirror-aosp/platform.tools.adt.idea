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
package com.android.tools.idea.npw.project

import com.android.tools.idea.npw.model.NewProjectModel
import com.android.tools.idea.npw.model.NewProjectModuleModel
import com.android.tools.idea.observable.BatchInvoker
import com.android.tools.idea.observable.TestInvokeStrategy
import com.android.tools.idea.wizard.model.ModelWizard
import com.android.tools.idea.wizard.template.Template
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@RunsInEdt
class ConfigureAndroidProjectStepTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val edtRule = EdtRule()

  private val invokeStrategy = TestInvokeStrategy()

  @Before
  fun setUp() {
    BatchInvoker.setOverrideStrategy(invokeStrategy)
  }

  @After
  fun tearDown() {
    BatchInvoker.clearOverrideStrategy()
  }

  @Test
  fun testPackageNameAutoSyncsWithApplicationNameWhenDefault() {
    val projectModel = NewProjectModel()
    val moduleModel = NewProjectModuleModel(projectModel)
    moduleModel.newRenderTemplate.setValue(Template.NoActivity)

    val step = ConfigureAndroidProjectStep(moduleModel, projectModel)
    val facade = Mockito.mock(ModelWizard.Facade::class.java)

    step.onWizardStarting(facade)
    step.onEntering()
    invokeStrategy.updateAllSteps()

    val basePackage = NewProjectModel.getSuggestedProjectPackage()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.myapplication")

    projectModel.applicationName.set("Awesome App")
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.awesomeapp")
    Disposer.dispose(step)
  }

  @Test
  fun testPreSetPackageNameIsRespectedAndDoesNotDesyncOnAppChange() {
    val projectModel = NewProjectModel()
    projectModel.packageName.set("com.custom.preset")

    val moduleModel = NewProjectModuleModel(projectModel)
    moduleModel.newRenderTemplate.setValue(Template.NoActivity)

    val step = ConfigureAndroidProjectStep(moduleModel, projectModel)
    val facade = Mockito.mock(ModelWizard.Facade::class.java)

    step.onWizardStarting(facade)
    step.onEntering()
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("com.custom.preset")

    // Modifying applicationName should NOT overwrite custom preset package name
    projectModel.applicationName.set("New App Name")
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("com.custom.preset")
    Disposer.dispose(step)
  }

  @Test
  fun testStepReentryRespectsPackageNameUpdatedByPrecedingStep() {
    val projectModel = NewProjectModel()
    val moduleModel = NewProjectModuleModel(projectModel)
    moduleModel.newRenderTemplate.setValue(Template.NoActivity)

    val step = ConfigureAndroidProjectStep(moduleModel, projectModel)
    val facade = Mockito.mock(ModelWizard.Facade::class.java)

    // Initial wizard starting with default package
    step.onWizardStarting(facade)
    step.onEntering()
    invokeStrategy.updateAllSteps()

    val basePackage = NewProjectModel.getSuggestedProjectPackage()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.myapplication")

    // External controller or preceding step updates packageName reactively
    projectModel.packageName.set("com.imported.app")
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("com.imported.app")

    // Modifying applicationName should now NOT overwrite the newly entered package name
    projectModel.applicationName.set("Updated Name")
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("com.imported.app")
    Disposer.dispose(step)
  }

  @Test
  fun testTypingAppNameKeepsPackageNameSyncedAcrossMultipleEdits() {
    val projectModel = NewProjectModel()
    val moduleModel = NewProjectModuleModel(projectModel)
    moduleModel.newRenderTemplate.setValue(Template.NoActivity)

    val step = ConfigureAndroidProjectStep(moduleModel, projectModel)
    val facade = Mockito.mock(ModelWizard.Facade::class.java)

    step.onWizardStarting(facade)
    step.onEntering()
    invokeStrategy.updateAllSteps()

    val basePackage = NewProjectModel.getSuggestedProjectPackage()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.myapplication")

    projectModel.applicationName.set("Edit One")
    invokeStrategy.updateAllSteps()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.editone")

    projectModel.applicationName.set("Edit Two")
    invokeStrategy.updateAllSteps()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.edittwo")

    projectModel.applicationName.set("Edit Three")
    invokeStrategy.updateAllSteps()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.editthree")

    Disposer.dispose(step)
  }

  @Test
  fun testEmptyModelPackageNameRestoresSync() {
    val projectModel = NewProjectModel()
    projectModel.packageName.set("com.custom.preset")

    val moduleModel = NewProjectModuleModel(projectModel)
    moduleModel.newRenderTemplate.setValue(Template.NoActivity)

    val step = ConfigureAndroidProjectStep(moduleModel, projectModel)
    val facade = Mockito.mock(ModelWizard.Facade::class.java)

    step.onWizardStarting(facade)
    step.onEntering()
    invokeStrategy.updateAllSteps()

    assertThat(projectModel.packageName.get()).isEqualTo("com.custom.preset")

    // Reset/clear package name in model reactively (without needing step reentry)
    projectModel.packageName.set("")
    invokeStrategy.updateAllSteps()

    val basePackage = NewProjectModel.getSuggestedProjectPackage()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.myapplication")

    // Modifying application name should now auto-sync with the package name
    projectModel.applicationName.set("Subsequent App")
    invokeStrategy.updateAllSteps()
    assertThat(projectModel.packageName.get()).isEqualTo("$basePackage.subsequentapp")

    Disposer.dispose(step)
  }
}
