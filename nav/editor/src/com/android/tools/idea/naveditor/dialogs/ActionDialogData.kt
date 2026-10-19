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
package com.android.tools.idea.naveditor.dialogs

import com.android.annotations.concurrency.Slow
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.resources.ResourceVisibility
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.naveditor.model.visibleDestinations
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.res.getResourceItems
import org.jetbrains.android.dom.navigation.NavigationSchema

data class ActionDialogData(
  val animResources: List<ValueWithDisplayString>,
  val animatorResources: List<ValueWithDisplayString>,
  val visibleDestinations: Map<NlComponent, List<NlComponent>> = emptyMap(),
) {
  val allAnimators: List<ValueWithDisplayString> = (animResources + animatorResources).sortedBy { it.display }

  fun getAnimators(includeAnimators: Boolean): List<ValueWithDisplayString> = if (includeAnimators) allAnimators else animResources

  companion object {
    @Slow
    @JvmStatic
    fun load(parent: NlComponent): ActionDialogData {
      val module = parent.model.module
      try {
        NavigationSchema.createIfNecessary(module)
      } catch (_: ClassNotFoundException) {}

      val visibleDestinations = parent.visibleDestinations
      val repoManager =
        StudioResourceRepositoryManager.getInstance(module) ?: return ActionDialogData(emptyList(), emptyList(), visibleDestinations)
      val appResources = repoManager.appResources
      val anim =
        appResources
          .getResourceItems(ResourceNamespace.TODO(), ResourceType.ANIM, ResourceVisibility.PUBLIC)
          .map { ValueWithDisplayString(it, "@${ResourceType.ANIM.getName()}/$it") }
          .sortedBy { it.display }
      val animators =
        appResources
          .getResourceItems(ResourceNamespace.TODO(), ResourceType.ANIMATOR, ResourceVisibility.PUBLIC)
          .map { ValueWithDisplayString(it, "@${ResourceType.ANIMATOR.getName()}/$it") }
          .sortedBy { it.display }
      return ActionDialogData(anim, animators, visibleDestinations)
    }
  }
}
