/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.naveditor.actions

import com.android.tools.idea.actions.DESIGN_SURFACE
import com.android.tools.idea.actions.DesignerActions
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.naveditor.dialogs.ActionDialogData
import com.android.tools.idea.naveditor.dialogs.AddActionDialog
import com.android.tools.idea.naveditor.dialogs.showAndUpdateFromDialog
import com.android.tools.idea.naveditor.surface.NavDesignSurface
import com.google.wireless.android.sdk.stats.NavEditorEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.android.dom.AndroidDomElement
import org.jetbrains.android.dom.navigation.NavActionElement
import org.jetbrains.android.dom.navigation.NavigationSchema

class AddActionToolbarAction private constructor() : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val surface = e.getData(DESIGN_SURFACE) as? NavDesignSurface
    val selection = surface?.selectionModel?.selection

    e.presentation.isEnabled = selection?.singleOrNull()?.let { supportsSubtag(selection[0], NavActionElement::class.java) } ?: false
  }

  private fun supportsSubtag(component: NlComponent, subtag: Class<out AndroidDomElement>): Boolean {
    val model = component.model
    val schema = NavigationSchema.get(model.module)
    return schema.getDestinationSubtags(component.tagName).containsKey(subtag)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val surface = e.getData(DESIGN_SURFACE) as? NavDesignSurface ?: return
    val component = surface.selectionModel.selection.firstOrNull() ?: return
    val module = component.model.module
    val modality = ModalityState.current().asContextElement()
    e.coroutineScope.launch {
      val data =
        readAction {
          if (module.isDisposed) return@readAction null
          ActionDialogData.load(component)
        } ?: return@launch
      withContext(Dispatchers.EDT + modality) {
        if (module.isDisposed || surface.isDisposed() || surface.models.isEmpty()) return@withContext
        val dialog = AddActionDialog(AddActionDialog.Defaults.NORMAL, null, component, NavEditorEvent.Source.TOOLBAR, data)
        showAndUpdateFromDialog(dialog, surface, hadExisting = false)
      }
    }
  }

  companion object {
    @JvmStatic
    val instance: AddActionToolbarAction
      get() = ActionManager.getInstance().getAction(DesignerActions.ACTION_ADD_ACTION) as AddActionToolbarAction
  }
}
