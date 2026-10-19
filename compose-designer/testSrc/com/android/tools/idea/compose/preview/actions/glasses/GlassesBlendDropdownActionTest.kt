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
package com.android.tools.idea.compose.preview.actions.glasses

import com.android.flags.junit.FlagRule
import com.android.sdklib.SystemImageTags.AI_GLASSES_TAG
import com.android.sdklib.SystemImageTags.XR_HEADSET_TAG
import com.android.sdklib.devices.Device
import com.android.tools.configurations.Configuration
import com.android.tools.idea.actions.SCENE_VIEW
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.compose.PsiComposePreviewElementInstance
import com.android.tools.idea.compose.preview.PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.preview.BackgroundManager
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.testFramework.TestActionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GlassesBlendDropdownActionTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val flagRule = FlagRule(StudioFlags.COMPOSE_PREVIEW_AI_GLASSES_PREVIEW, true)

  @Test
  fun `action is not visible when device is not glasses`() {
    val event = setUpDeviceAndActionVisibility(XR_HEADSET_TAG.id, PreviewMode.Default())
    assertThat(event.presentation.isEnabledAndVisible).isFalse()
  }

  @Test
  fun `action is visible when device is glasses and mode is default`() {
    val event = setUpDeviceAndActionVisibility(AI_GLASSES_TAG.id, PreviewMode.Default())
    assertThat(event.presentation.isEnabledAndVisible).isTrue()
  }

  @Test
  fun `action is visible when device is glasses and mode is focus`() {
    val event = setUpDeviceAndActionVisibility(AI_GLASSES_TAG.id, PreviewMode.Focus(mock()))
    assertThat(event.presentation.isEnabledAndVisible).isTrue()
  }

  @Test
  fun `action is not visible when device is glasses and mode is not default or focus`() {
    val event = setUpDeviceAndActionVisibility(AI_GLASSES_TAG.id, PreviewMode.UiCheck(mock()))
    assertThat(event.presentation.isEnabledAndVisible).isFalse()

    val event2 = setUpDeviceAndActionVisibility(AI_GLASSES_TAG.id, PreviewMode.Interactive(mock()))
    assertThat(event2.presentation.isEnabledAndVisible).isFalse()

    val event3 = setUpDeviceAndActionVisibility(AI_GLASSES_TAG.id, PreviewMode.AnimationInspection(mock()))
    assertThat(event3.presentation.isEnabledAndVisible).isFalse()
  }

  @Test
  fun `selecting No Background clears background in BackgroundManager`() {
    val previewElement = glassesPreview("Preview", definition = null, body = pointer())
    val dataContext = contextFor(previewElement)

    val noneAction = SetGlassesBackgroundAction(GlassesBackground.NONE)
    val lightAction = SetGlassesBackgroundAction(GlassesBackground.LIGHT_BACKGROUND)
    val backgroundManager = BackgroundManager.getInstance(projectRule.project)

    // Initially no glasses background is set; NONE is selected.
    assertThat(noneAction.isSelected(TestActionEvent.createTestEvent(noneAction, dataContext))).isTrue()
    assertThat(lightAction.isSelected(TestActionEvent.createTestEvent(lightAction, dataContext))).isFalse()

    // Selecting LIGHT_BACKGROUND stores Background.Image in BackgroundManager.
    lightAction.setSelected(TestActionEvent.createTestEvent(lightAction, dataContext), true)
    assertThat(backgroundManager.getBackground(previewElement)?.image).isEqualTo(GlassesBackground.LIGHT_BACKGROUND.imageTransform)
    assertThat(lightAction.isSelected(TestActionEvent.createTestEvent(lightAction, dataContext))).isTrue()
    assertThat(noneAction.isSelected(TestActionEvent.createTestEvent(noneAction, dataContext))).isFalse()

    // Selecting NONE removes the background entry from BackgroundManager so default showBackground is restored.
    noneAction.setSelected(TestActionEvent.createTestEvent(noneAction, dataContext), true)
    assertThat(backgroundManager.getBackground(previewElement)).isNull()
    assertThat(noneAction.isSelected(TestActionEvent.createTestEvent(noneAction, dataContext))).isTrue()
    assertThat(lightAction.isSelected(TestActionEvent.createTestEvent(lightAction, dataContext))).isFalse()
  }

  @Test
  fun `selecting background does not affect other previews in the same function`() {
    val body = pointer()
    val multiPreview = pointer()
    val previews =
      listOf(
        // Two separate @Preview annotations in the same function with the same name, e.g. if no name parameter is used.
        glassesPreview("Glasses Preview", definition = pointer(), body = body),
        glassesPreview("Glasses Preview", definition = pointer(), body = body),
        // Two leaves of the same MultiPreview, which share the definition.
        glassesPreview("Glasses Preview - Light", definition = multiPreview, body = body),
        glassesPreview("Glasses Preview - Dark", definition = multiPreview, body = body),
      )
    val lightAction = SetGlassesBackgroundAction(GlassesBackground.LIGHT_BACKGROUND)
    fun isLightSelected(preview: PsiComposePreviewElementInstance) =
      lightAction.isSelected(TestActionEvent.createTestEvent(lightAction, contextFor(preview)))

    // Select LIGHT_BACKGROUND for the first preview and for the last MultiPreview leaf.
    lightAction.setSelected(TestActionEvent.createTestEvent(lightAction, contextFor(previews[0])), true)
    lightAction.setSelected(TestActionEvent.createTestEvent(lightAction, contextFor(previews[3])), true)

    assertThat(previews.map { isLightSelected(it) }).containsExactly(true, false, false, true).inOrder()
  }

  private fun pointer(): SmartPsiElementPointer<PsiElement> = mock { on { project } doReturn projectRule.project }

  private fun glassesPreview(
    name: String,
    definition: SmartPsiElementPointer<PsiElement>?,
    body: SmartPsiElementPointer<PsiElement>,
  ): PsiComposePreviewElementInstance =
    SingleComposePreviewElementInstance(
      methodFqn = "TestKt.Preview",
      displaySettings =
        PreviewDisplaySettings(
          name = name,
          baseName = "Preview",
          parameterName = null,
          group = null,
          showDecoration = false,
          background = PreviewDisplaySettings.Background.Default,
          organizationGroup = "",
          organizationName = "",
        ),
      previewElementDefinition = definition,
      previewBody = body,
      configuration = PreviewConfiguration.cleanAndGet(),
    )

  private fun contextFor(previewElement: PsiComposePreviewElementInstance) =
    SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, projectRule.project)
      .add(PSI_COMPOSE_PREVIEW_ELEMENT_INSTANCE, previewElement)
      .build()

  /**
   * Sets up a data context containing a screen view that has a device passed as an argument. Then, create an [AnActionEvent] and calls
   * `GlassesBlendDropdownAction#update()` on it. The event's data context is populated with a [PreviewModeManager] that returns the given
   * [previewMode] as the current mode.
   *
   * Returns the event so it can be used to check the action visibility.
   */
  private fun setUpDeviceAndActionVisibility(deviceId: String, previewMode: PreviewMode): AnActionEvent {
    val sceneView = mock<SceneView>()
    val configuration = mock<Configuration>()
    whenever(sceneView.configuration).thenReturn(configuration)

    val device = mock<Device>()
    whenever(device.tagId).thenReturn(deviceId)
    whenever(configuration.device).thenReturn(device)

    val previewModeManager = mock<PreviewModeManager>()
    whenever(previewModeManager.mode).thenReturn(MutableStateFlow(previewMode))

    val dataContext = SimpleDataContext.builder().add(SCENE_VIEW, sceneView).add(PreviewModeManager.KEY, previewModeManager).build()

    val action = GlassesBlendDropdownAction()
    return TestActionEvent.createTestEvent(action, dataContext).also { action.update(it) }
  }
}
