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
package com.android.tools.idea.preview.actions

import com.android.tools.idea.preview.TestPreviewElement
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.preview.modes.UiCheckInstance
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.ActionGroupWrapper
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.TestActionEvent.createTestEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ActionUtilsTest {
  @get:Rule val applicationRule = ApplicationRule()

  private val previewElement = TestPreviewElement()
  private val allModes =
    listOf(
      PreviewMode.Default(),
      PreviewMode.Focus(previewElement),
      PreviewMode.Interactive(previewElement),
      PreviewMode.UiCheck(UiCheckInstance(previewElement, isWearPreview = false)),
      PreviewMode.AnimationInspection(previewElement),
    )

  @Test
  fun visibleOnlyInFiltersByPreviewModeType() {
    val modeChecks =
      listOf(
        { action: AnAction -> action.visibleOnlyInDefaultPreview() } to PreviewMode.Default::class,
        { action: AnAction -> action.visibleOnlyInFocus() } to PreviewMode.Focus::class,
        { action: AnAction -> action.visibleOnlyInInteractive() } to PreviewMode.Interactive::class,
        { action: AnAction -> action.visibleOnlyInUiCheck() } to PreviewMode.UiCheck::class,
        { action: AnAction -> action.visibleOnlyIn(PreviewMode.AnimationInspection::class) } to PreviewMode.AnimationInspection::class,
      )

    for ((wrap, expectedModeClass) in modeChecks) {
      val wrapped = wrap(EmptyTestAction(visible = true))
      assertThat(wrapped).isInstanceOf(AnActionWrapper::class.java)
      assertThat(wrapped.actionUpdateThread).isEqualTo(ActionUpdateThread.BGT)

      for (mode in allModes) {
        val event = eventForMode(mode)
        wrapped.update(event)
        assertThat(event.presentation.isVisible).isEqualTo(expectedModeClass.isInstance(mode))
      }
    }
  }

  @Test
  fun visibleOnlyInPreservesDelegateInvisibility() {
    val hiddenDelegate = EmptyTestAction(visible = false).visibleOnlyInDefaultPreview()
    val event = eventForMode(PreviewMode.Default())
    hiddenDelegate.update(event)
    assertThat(event.presentation.isVisible).isFalse()
  }

  @Test
  fun visibleOnlyInHidesWhenPreviewModeManagerMissing() {
    val wrapped = EmptyTestAction(visible = true).visibleOnlyInDefaultPreview()
    val event = createTestEvent(SimpleDataContext.EMPTY_CONTEXT)
    wrapped.update(event)
    assertThat(event.presentation.isVisible).isFalse()
  }

  @Test
  fun visibleOnlyInWrapsActionGroupAndSeparatorAsActionGroup() {
    val childAction = EmptyTestAction(visible = true)
    val group = DefaultActionGroup(childAction).visibleOnlyInDefaultPreview()
    assertThat(group).isInstanceOf(ActionGroupWrapper::class.java)
    assertThat(group.actionUpdateThread).isEqualTo(ActionUpdateThread.BGT)

    val defaultEvent = eventForMode(PreviewMode.Default())
    group.update(defaultEvent)
    assertThat(defaultEvent.presentation.isVisible).isTrue()
    assertThat((group as ActionGroupWrapper).getChildren(defaultEvent)).asList().containsExactly(childAction)

    val focusEvent = eventForMode(PreviewMode.Focus(previewElement))
    group.update(focusEvent)
    assertThat(focusEvent.presentation.isVisible).isFalse()

    val wrappedSeparator = Separator.getInstance().visibleOnlyInUiCheck()
    assertThat(wrappedSeparator).isInstanceOf(ActionGroupWrapper::class.java)
    assertThat(wrappedSeparator.actionUpdateThread).isEqualTo(ActionUpdateThread.BGT)
    val uiCheckEvent = eventForMode(PreviewMode.UiCheck(UiCheckInstance(previewElement, isWearPreview = false)))
    wrappedSeparator.update(uiCheckEvent)
    assertThat(uiCheckEvent.presentation.isVisible).isTrue()
    assertThat((wrappedSeparator as ActionGroupWrapper).getChildren(uiCheckEvent)).asList().containsExactly(Separator.getInstance())
  }

  private fun eventForMode(mode: PreviewMode): AnActionEvent {
    val modeManager = mock<PreviewModeManager>()
    whenever(modeManager.mode).thenReturn(MutableStateFlow(mode))
    val dataContext = SimpleDataContext.builder().add(PreviewModeManager.KEY, modeManager).build()
    return createTestEvent(dataContext)
  }

  private class EmptyTestAction(private val visible: Boolean) : AnAction() {
    override fun update(e: AnActionEvent) {
      e.presentation.isVisible = visible
    }

    override fun actionPerformed(e: AnActionEvent) {}
  }
}
