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
package com.android.tools.idea.npw.templateengine.viewmodel

import androidx.compose.runtime.snapshots.Snapshot
import com.android.tools.adtui.device.FormFactor
import com.android.tools.idea.npw.model.TemplateRendererStrategy
import com.android.tools.idea.npw.templateengine.ui.TemplateEngineTestUtils
import com.android.tools.idea.wizard.template.BuildConfigurationLanguageForNewProject
import com.android.tools.idea.wizard.template.KOTLIN_DSL_LINK
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.ExtensionTestUtil
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.android.util.AndroidBundle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureProjectViewModelTest {
  @get:Rule val tempFolder = TemporaryFolder()
  @get:Rule val applicationRule = ApplicationRule()

  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var disposable: Disposable
  private lateinit var defaultParentDir: Path
  private lateinit var viewModel: ConfigureProjectViewModel
  private val defaultTemplate = TemplateEngineTestUtils.createTestTemplateDefinition(name = "Empty Activity", shortName = "empty-activity")

  @Before
  fun setUp() {
    disposable = Disposer.newDisposable()
    clearProperties()
    Dispatchers.setMain(testDispatcher)
    defaultParentDir = tempFolder.newFolder("AndroidStudioProjects").toPath()
    viewModel = ConfigureProjectViewModel(defaultParentDir, listOf(defaultTemplate))
  }

  @After
  fun tearDown() {
    Disposer.dispose(disposable)
    clearProperties()
    Dispatchers.resetMain()
  }

  private fun clearProperties() {
    for (formFactor in FormFactor.values()) {
      PropertiesComponent.getInstance().unsetValue(formFactor.name + "minApi")
    }
  }

  @Test
  fun testInitialState() {
    assertThat(viewModel.name).isEqualTo("My Application")
    assertThat(viewModel.packageName).isEqualTo("com.example.myapplication")
    assertThat(viewModel.location).isEqualTo(defaultParentDir.resolve("MyApplication").toAbsolutePath().toString())
    assertThat(viewModel.minSdk).isEqualTo("24")
    assertThat(viewModel.validationError).isNull()
    assertThat(viewModel.isFinishEnabled).isTrue()
  }

  @Test
  fun testNameChangeUpdatesPackageAndLocation() {
    Snapshot.withMutableSnapshot { viewModel.updateName("Test Project") }
    testDispatcher.scheduler.advanceUntilIdle()
    assertThat(viewModel.name).isEqualTo("Test Project")
    assertThat(viewModel.packageName).isEqualTo("com.example.testproject")
    assertThat(viewModel.location).isEqualTo(defaultParentDir.resolve("TestProject").toAbsolutePath().toString())
    assertThat(viewModel.validationError).isNull()
  }

  @Test
  fun testManualPackageNameChangeDisablesAutoUpdate() {
    Snapshot.withMutableSnapshot { viewModel.updatePackage("com.custom.pack") }
    Snapshot.withMutableSnapshot { viewModel.updateName("Another Name") }
    testDispatcher.scheduler.advanceUntilIdle()
    assertThat(viewModel.packageName).isEqualTo("com.custom.pack")
    assertThat(viewModel.location).isEqualTo(defaultParentDir.resolve("AnotherName").toAbsolutePath().toString())
  }

  @Test
  fun testManualLocationChangeDisablesAutoUpdate() {
    val customLoc = tempFolder.root.toPath().resolve("CustomLoc").toAbsolutePath().toString()
    Snapshot.withMutableSnapshot { viewModel.updateLocation(customLoc) }
    Snapshot.withMutableSnapshot { viewModel.updateName("Another Name") }
    testDispatcher.scheduler.advanceUntilIdle()
    assertThat(viewModel.location).isEqualTo(customLoc)
    assertThat(viewModel.packageName).isEqualTo("com.example.anothername")
  }

  @Test
  fun testValidationEmptyName() {
    Snapshot.withMutableSnapshot { viewModel.updateName("") }
    testDispatcher.scheduler.advanceUntilIdle()
    assertThat(viewModel.validationError).isEqualTo(AndroidBundle.message("android.wizard.validate.empty.application.name"))
    assertThat(viewModel.isFinishEnabled).isFalse()
  }

  @Test
  fun testValidationInvalidPackage() {
    Snapshot.withMutableSnapshot { viewModel.updatePackage("invalid-package") }
    testDispatcher.scheduler.advanceUntilIdle()
    assertThat(viewModel.validationError).isEqualTo("The character '-' is not allowed in Android application package names")
    assertThat(viewModel.isFinishEnabled).isFalse()
  }

  @Test
  fun testUpdateTemplate() {
    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(name = "Empty Activity", shortName = "empty-activity"),
      FormFactor.MOBILE,
    )
    assertThat(viewModel.templateName).isEqualTo("Empty Activity")
    assertThat(viewModel.templateDescription)
      .isEqualTo(AndroidBundle.message("android.wizard.project.new.choose.template.description.empty.activity"))

    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(name = "Custom Template", shortName = "some-other-template"),
      FormFactor.MOBILE,
    )
    assertThat(viewModel.templateName).isEqualTo("Custom Template")
    assertThat(viewModel.templateDescription).isEqualTo(AndroidBundle.message("android.wizard.action.new.component", "some-other-template"))
  }

  @Test
  fun testUpdateTemplateChangesFormFactorAndSdks() {
    assertThat(viewModel.formFactor).isEqualTo(FormFactor.MOBILE)
    val defaultMinSdk = viewModel.selectedMinSdk
    assertThat(viewModel.availableMinSdks.any { it.minApiLevel < 23 }).isFalse()

    // Switch to WEAR OS
    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(name = "Wear Template", shortName = "wear-template"),
      FormFactor.WEAR,
    )
    assertThat(viewModel.formFactor).isEqualTo(FormFactor.WEAR)
    assertThat(viewModel.selectedMinSdk).isNotEqualTo(defaultMinSdk)
    assertThat(viewModel.minSdk).isEqualTo(viewModel.selectedMinSdk.minApiLevel.toString())

    // Wear OS APIs are 25+ (R = 30 is default, LOWEST_ACTIVE_API_WEAR = 25)
    assertThat(viewModel.availableMinSdks.any { it.minApiLevel < 25 }).isFalse()

    // Switch back to MOBILE
    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(name = "Mobile Template", shortName = "mobile-template"),
      FormFactor.MOBILE,
    )
    assertThat(viewModel.formFactor).isEqualTo(FormFactor.MOBILE)
    assertThat(viewModel.availableMinSdks.any { it.minApiLevel < 23 }).isFalse()
  }

  @Test
  fun testMinSdkSelectionUpdatesAndPersists() {
    val defaultMinSdk = viewModel.selectedMinSdk
    assertThat(viewModel.minSdk).isEqualTo(defaultMinSdk.minApiLevel.toString())

    val targetItem = viewModel.availableMinSdks.find { it.minApiLevel == 30 }
    assertThat(targetItem).isNotNull()

    viewModel.updateSelectedMinSdk(targetItem!!)
    assertThat(viewModel.selectedMinSdk).isEqualTo(targetItem)
    assertThat(viewModel.minSdk).isEqualTo("30")

    val persistedValue = PropertiesComponent.getInstance().getValue("MOBILEminApi")
    assertThat(persistedValue).isEqualTo(targetItem.minSdk.apiStringWithExtension)
  }

  @Test
  fun testUpdateTemplateWithCustomMinSdkDefault() {
    // Default is Mobile
    assertThat(viewModel.formFactor).isEqualTo(FormFactor.MOBILE)

    // Switch to Mobile template with minSdk = 30
    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(
        name = "Custom Mobile Template",
        shortName = "custom-mobile",
        minSdkDefault = "30",
      ),
      FormFactor.MOBILE,
    )
    assertThat(viewModel.minSdk).isEqualTo("30")
    assertThat(viewModel.selectedMinSdk.minApiLevel).isEqualTo(30)

    // Switch to Wear template with minSdk = 30
    viewModel.updateTemplate(
      TemplateEngineTestUtils.createTestTemplateDefinition(
        name = "Custom Wear Template",
        shortName = "custom-wear",
        minSdkDefault = "30",
      ),
      FormFactor.WEAR,
    )
    assertThat(viewModel.minSdk).isEqualTo("30")
    assertThat(viewModel.selectedMinSdk.minApiLevel).isEqualTo(30)
  }

  @Test
  fun testBuildConfigurationHelpTooltipDefault() {
    assertThat(viewModel.buildConfigurationHelpDescription)
      .isEqualTo(AndroidBundle.message("android.wizard.project.help.buildconfigurationlanguage.description"))
    assertThat(viewModel.buildConfigurationHelpUrl).isEqualTo(KOTLIN_DSL_LINK)
  }

  @Test
  fun testUpdateTemplateWithMultipleVariants() {
    val mockStrategy =
      mock<TemplateRendererStrategy<*>>().apply {
        whenever(id).thenReturn("lightbuild")
        whenever(displayName).thenReturn("Lightbuild (Experimental)")
        whenever(helpDescription).thenReturn("Custom strategy description")
        whenever(helpUrl).thenReturn("https://d.android.com/lightbuild")
      }
    ExtensionTestUtil.maskExtensions(TemplateRendererStrategy.EP_NAME, listOf(mockStrategy), disposable)

    val gradleTemplate =
      TemplateEngineTestUtils.createTestTemplateDefinition(
        name = "Empty Activity",
        shortName = "empty-activity",
        tags = listOf("agp-9"),
      )
    val lightbuildTemplate =
      TemplateEngineTestUtils.createTestTemplateDefinition(
        name = "Empty Activity",
        shortName = "empty-activity-lightbuild",
        tags = listOf("lightbuild"),
      )

    viewModel.updateTemplate(listOf(gradleTemplate, lightbuildTemplate), FormFactor.MOBILE)

    assertThat(viewModel.isBuildConfigurationVisible).isTrue()
    assertThat(viewModel.availableTemplateVariants).hasSize(2)
    assertThat(viewModel.availableTemplateVariants[0].template).isEqualTo(gradleTemplate)
    assertThat(viewModel.availableTemplateVariants[0].buildSystem.displayName)
      .isEqualTo(BuildConfigurationLanguageForNewProject.KTS.description)
    assertThat(viewModel.availableTemplateVariants[1].template).isEqualTo(lightbuildTemplate)
    assertThat(viewModel.availableTemplateVariants[1].buildSystem.displayName).isEqualTo("Lightbuild (Experimental)")
    assertThat(viewModel.availableTemplateVariants[1].buildSystem.helpDescription).isEqualTo("Custom strategy description")
    assertThat(viewModel.availableTemplateVariants[1].buildSystem.helpUrl).isEqualTo("https://d.android.com/lightbuild")

    // Default selection is Gradle
    assertThat(viewModel.selectedTemplateVariant).isEqualTo(viewModel.availableTemplateVariants[0])
    assertThat(viewModel.effectiveTemplate).isEqualTo(gradleTemplate)
    assertThat(viewModel.buildConfigurationHelpDescription).isEqualTo("Custom strategy description")
    assertThat(viewModel.buildConfigurationHelpUrl).isEqualTo("https://d.android.com/lightbuild")

    // Switch variant to Lightbuild
    viewModel.updateTemplateVariant(viewModel.availableTemplateVariants[1])
    assertThat(viewModel.selectedTemplateVariant).isEqualTo(viewModel.availableTemplateVariants[1])
    assertThat(viewModel.effectiveTemplate).isEqualTo(lightbuildTemplate)
  }

  @Test
  fun testUpdateTemplateSingleVariantHidesBuildConfiguration() {
    val gradleTemplate =
      TemplateEngineTestUtils.createTestTemplateDefinition(
        name = "Empty Activity",
        shortName = "empty-activity",
        tags = listOf("agp-9"),
      )

    viewModel.updateTemplate(listOf(gradleTemplate), FormFactor.MOBILE)

    assertThat(viewModel.isBuildConfigurationVisible).isFalse()
    assertThat(viewModel.availableTemplateVariants).hasSize(1)
    assertThat(viewModel.effectiveTemplate).isEqualTo(gradleTemplate)
  }
}
