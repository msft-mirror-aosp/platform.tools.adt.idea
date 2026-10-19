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
package com.android.tools.idea.preview

import com.android.annotations.concurrency.GuardedBy
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.PreviewElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.SmartPsiElementPointer
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service that keeps track of runtime backgrounds used for Compose previews when using
 * [com.android.tools.preview.PreviewDisplaySettings.Background.Image]. This only maintains the information in memory and it's only used
 * when an image background has been explicitly set.
 *
 * Backgrounds are scoped to a single preview. They are stored per [PreviewElement.previewElementDefinition] (or
 * [PreviewElement.previewBody] if there is no definition) and per display name, since all the leaves of a MultiPreview share the same
 * definition. This means that the background is not kept if the name of the preview changes.
 */
@Service(Service.Level.PROJECT)
class BackgroundManager(project: Project) {
  private val lock = Any()

  /** Backgrounds indexed by the element they apply to and then by the display name of the preview. */
  @GuardedBy("lock")
  private val backgrounds: WeakHashMap<SmartPsiElementPointer<*>, MutableMap<String, PreviewDisplaySettings.Background.Image>> =
    WeakHashMap()
  private val _modificationTracker = SimpleModificationTracker()

  /** [com.intellij.openapi.util.ModificationTracker] that changes every time backgrounds are updated. */
  val modificationTracker: ModificationTracker = _modificationTracker
  private val _modificationFlow = MutableStateFlow(_modificationTracker.modificationCount)

  /** [kotlinx.coroutines.flow.StateFlow] that gets a new value every time that backgrounds are updated. */
  val modificationFlow: StateFlow<Long> = _modificationFlow

  private fun fireModification() {
    _modificationTracker.incModificationCount()
    _modificationFlow.value = _modificationTracker.modificationCount
  }

  /** Sets a [background] for the given [previewElement]. Setting a null [background] removes it. */
  fun setBackground(previewElement: PsiPreviewElement, background: PreviewDisplaySettings.Background.Image?) {
    val key = previewElement.backgroundKey ?: return
    val name = previewElement.displaySettings.name
    val modified =
      synchronized(lock) {
        if (background == null) {
          val elementBackgrounds = backgrounds[key]
          val removed = elementBackgrounds?.remove(name) != null
          if (elementBackgrounds?.isEmpty() == true) backgrounds.remove(key)
          removed
        } else {
          backgrounds.getOrPut(key) { mutableMapOf() }.put(name, background) != background
        }
      }
    if (modified) fireModification()
  }

  /** Gets the background for the given [previewElement]. */
  fun getBackground(previewElement: PsiPreviewElement): PreviewDisplaySettings.Background.Image? {
    val key = previewElement.backgroundKey ?: return null
    // WeakHashMap.get is not thread safe either since it expunges stale entries.
    return synchronized(lock) { backgrounds[key]?.get(previewElement.displaySettings.name) }
  }

  companion object {
    fun getInstance(project: Project): BackgroundManager {
      return project.getService(BackgroundManager::class.java)
    }
  }
}

/** Element used to store the background of a [PsiPreviewElement]. See [BackgroundManager]. */
private val PsiPreviewElement.backgroundKey: SmartPsiElementPointer<*>?
  get() = previewElementDefinition ?: previewBody
