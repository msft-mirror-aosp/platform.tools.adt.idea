/*
 * Copyright (C) 2025 The Android Open Source Project
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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.npw.project.ChooseAndroidProjectStep.Companion.getProjectTemplates
import com.android.tools.idea.npw.template.PluginPromotionTemplate
import com.android.tools.idea.npw.template.WizardPluginPromotionTemplateProvider
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.wizard.template.FormFactor
import com.android.tools.idea.wizard.template.Thumb
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.ProjectRule
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ComposeChooseAndroidProjectStepUITest {
  @get:Rule val composeTestRule = createStudioComposeTestRule()
  @get:Rule val projectRule = ProjectRule()

  @Test
  fun showMobileFormFactorAsDefaultFocus() = runTest {
    StudioFlags.GEMINI_NEW_PROJECT_AGENT.override(false)
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.LeftPanel.column)
      .assertExists()
      .onChildren()
      .assertCountEquals(FormFactor.entries.size - 1 /* AiGlasses is not included */)

    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).assertExists().assertIsFocused()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Mobile.getProjectTemplates().size)
  }

  @Test
  fun showSelectedFormFactor() = runTest {
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    val selectedFormFactor = FormFactor.Wear
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule.onNodeWithText(selectedFormFactor.displayName).assertExists().performClick()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(selectedFormFactor.getProjectTemplates().size)
  }

  @Test
  fun showPreviouslySelectedTemplate() = runTest {
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    val mobileTemplates = FormFactor.Mobile.getProjectTemplates()
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).assertIsFocused()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()[mobileTemplates.size - 1]
      .performClick()

    composeTestRule.onNodeWithText(FormFactor.Wear.displayName).performClick()

    composeTestRule.onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid).performClick()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Wear.getProjectTemplates().size)

    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).performClick()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Mobile.getProjectTemplates().size)

    assertEquals(
      mobileTemplates[mobileTemplates.size - 1].name,
      (model.chooseAndroidProjectEntries[1] as FormFactorProjectEntry).selectedGridItem?.name,
    )
  }

  @Test
  fun showSelectedProjectTypeOnBackNavigation() = runTest {
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.getAndroidProjectEntries()
    var showUi by mutableStateOf(true)
    composeTestRule.setContent {
      if (showUi) {
        ChooseAndroidProjectStepUI(model = model)
      }
    }

    // Select Wear OS
    val wearOsDisplayName = FormFactor.Wear.displayName
    composeTestRule.onNodeWithText(wearOsDisplayName).performClick()

    // Simulate navigating next then back by recomposing the UI
    showUi = false
    composeTestRule.awaitIdle()
    showUi = true
    composeTestRule.awaitIdle()

    // Verify Wear OS is still selected and focused in the UI
    composeTestRule.onNodeWithText(wearOsDisplayName).assertIsFocused()

    // And verify the templates are for Wear OS
    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Wear.getProjectTemplates().size)
  }

  @Test
  fun doubleClickTriggersNavigationCallback() = runTest {
    var callbackTriggered = false
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.onGridItemDoubleClick = { callbackTriggered = true }
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule.onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid).onChildren()[0].performTouchInput {
      doubleClick()
    }

    assertTrue(callbackTriggered)
  }

  @Test
  fun canSelectTemplatesAcrossRowsAfterSelectingNoActivity() = runTest {
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    // Switch to Wear OS
    composeTestRule.onNodeWithText(FormFactor.Wear.displayName).performClick()

    val templateGrid = composeTestRule.onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
    val wearTemplates = FormFactor.Wear.getProjectTemplates()
    assertTrue(wearTemplates.size >= 2, "Wear OS must have at least 2 templates")

    // Click 'No Activity' (index 0 in Row 1)
    templateGrid.onChildren()[0].performClick()
    assertEquals("No Activity", (model.selectedAndroidProjectEntry as FormFactorProjectEntry).selectedGridItem?.name)

    // Click subsequent template in the next row / index 1
    templateGrid.onChildren()[1].performClick()
    assertEquals(wearTemplates[1].name, (model.selectedAndroidProjectEntry as FormFactorProjectEntry).selectedGridItem?.name)

    // Also verify in Mobile (Phone and Tablet)
    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).performClick()
    val mobileTemplates = FormFactor.Mobile.getProjectTemplates()
    assertTrue(mobileTemplates.size >= 3, "Mobile must have at least 3 templates")

    // In Phone, auto focus lands on default (e.g. Empty Compose Activity at index 1). Verify we can click index 0 (No Activity) and index 2
    // (Gemini API / next item).
    templateGrid.onChildren()[0].performClick()
    assertEquals("No Activity", (model.selectedAndroidProjectEntry as FormFactorProjectEntry).selectedGridItem?.name)

    templateGrid.onChildren()[2].performClick()
    assertEquals(mobileTemplates[2].name, (model.selectedAndroidProjectEntry as FormFactorProjectEntry).selectedGridItem?.name)
  }

  @Test
  fun showPluginPromotionTemplateInGrid() = runTest {
    StudioFlags.GEMINI_NEW_PROJECT_AGENT.override(false)
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, FormFactor.Mobile))
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).assertExists().performClick()

    // The Mobile template grid now includes the registered promotion template.
    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Mobile.getProjectTemplates().size)

    composeTestRule.onNodeWithText(PROMOTION_NAME, substring = true).assertExists()
  }

  @Test
  fun selectingPluginPromotionTemplateUpdatesSelectedTemplate() = runTest {
    StudioFlags.GEMINI_NEW_PROJECT_AGENT.override(false)
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, FormFactor.Mobile))
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    val mobileTemplateInfos = FormFactor.Mobile.getProjectTemplates()
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).assertExists().performClick()

    // The promotion template is appended after the regular templates, so it is the last cell.
    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()[mobileTemplateInfos.size - 1]
      .performClick()

    val selectedGridItem = (model.chooseAndroidProjectEntries[0] as FormFactorProjectEntry).selectedGridItem
    assertEquals(PROMOTION_PLUGIN_ID, (selectedGridItem as PluginPromotionGridItem).pluginId)
  }

  @Test
  fun pluginPromotionTemplateAppearsOnlyForItsFormFactor() = runTest {
    StudioFlags.GEMINI_NEW_PROJECT_AGENT.override(false)
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, FormFactor.Wear))
    val formFactorSupplier = Supplier<List<FormFactor>> { FormFactor.entries }
    val model = ChooseAndroidProjectStepModel(formFactorSupplier)
    model.getAndroidProjectEntries()

    composeTestRule.setContent { ChooseAndroidProjectStepUI(model = model) }

    // The Mobile grid must not show the Wear-only promotion template.
    composeTestRule.onNodeWithText(FormFactor.Mobile.displayName).assertExists().performClick()
    composeTestRule.onNodeWithText(PROMOTION_NAME, substring = true).assertDoesNotExist()

    // Switching to Wear reveals the promotion template in its grid.
    composeTestRule.onNodeWithText(FormFactor.Wear.displayName).performClick()

    composeTestRule
      .onNodeWithTag(ChooseAndroidProjectStepLayoutTags.RightPanel.templateGrid)
      .onChildren()
      .assertCountEquals(FormFactor.Wear.getProjectTemplates().size)

    composeTestRule.onNodeWithText(PROMOTION_NAME, substring = true).assertExists()
  }

  private fun registerPromotionTemplates(vararg templates: PluginPromotionTemplate) {
    ExtensionTestUtil.maskExtensions(
      PROMOTION_EP_NAME,
      listOf(FakeWizardPluginPromotionTemplateProvider(templates.toList())),
      projectRule.disposable,
    )
  }
}

private const val PROMOTION_NAME = "Test Promotion Plugin"
private const val PROMOTION_PLUGIN_ID = "com.example.testpromotion"

private val PROMOTION_EP_NAME =
  ExtensionPointName<WizardPluginPromotionTemplateProvider>("com.android.tools.idea.npw.template.wizardPluginPromotionTemplateProvider")

private val fakeThumbUrl by lazy {
  val file = java.io.File.createTempFile("fake_thumb", ".png").apply { deleteOnExit() }
  javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", file)
  file.toURI().toURL()
}

private class FakePluginPromotionTemplate(override val name: String, override val pluginId: String, override val formFactor: FormFactor) :
  PluginPromotionTemplate {
  override fun thumb(): Thumb = Thumb { fakeThumbUrl }
}

private class FakeWizardPluginPromotionTemplateProvider(private val templates: List<PluginPromotionTemplate>) :
  WizardPluginPromotionTemplateProvider() {
  override fun getTemplates(): List<PluginPromotionTemplate> = templates
}
