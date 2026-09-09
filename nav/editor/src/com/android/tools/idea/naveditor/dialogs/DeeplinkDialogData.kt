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
import com.android.tools.idea.common.model.NlComponent
import org.jetbrains.android.dom.navigation.NavigationSchema

data class DeeplinkDialogData(
  val actions: List<String>,
  val isExtended: Boolean,
) {
  companion object {
    @Slow
    @JvmStatic
    fun load(parent: NlComponent): DeeplinkDialogData {
      val module = parent.model.module
      try {
        NavigationSchema.createIfNecessary(module)
      } catch (_: ClassNotFoundException) {}
      val actions = ActionTextFieldModel.loadActions(module)
      val isExtended = AddDeeplinkDialog.isExtended(parent)
      return DeeplinkDialogData(actions, isExtended)
    }
  }
}
