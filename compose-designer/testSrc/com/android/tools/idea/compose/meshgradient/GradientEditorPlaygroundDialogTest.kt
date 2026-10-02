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
package com.android.tools.idea.compose.meshgradient

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.RunsInEdt
import javax.swing.JLabel
import javax.swing.JList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class GradientEditorPlaygroundDialogTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  @Test
  fun dialogUsesLocalizedTitleAndTypeNames() = withPlaygroundDialog { dialog ->
    assertEquals("Gradient Editor", dialog.title)

    val list = JList<GradientType>()
    val names =
      GradientType.entries.map { type ->
        (dialog.typeSelector.renderer.getListCellRendererComponent(list, type, 0, false, false) as JLabel).text
      }
    assertEquals(listOf("Mesh Gradient", "Linear Gradient", "Radial Gradient", "Sweep Gradient"), names)
  }

  @Test
  fun switchingTypeUpdatesState() = withPlaygroundDialog { dialog ->
    assertEquals(GradientType.MESH, dialog.typeSelector.item)

    dialog.typeSelector.item = GradientType.RADIAL

    assertEquals(GradientType.RADIAL, dialog.state.currentType)
  }

  private fun withPlaygroundDialog(block: (GradientEditorPlaygroundDialog) -> Unit) {
    val dialog = GradientEditorPlaygroundDialog(projectRule.project)
    try {
      block(dialog)
    } finally {
      dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
  }
}
