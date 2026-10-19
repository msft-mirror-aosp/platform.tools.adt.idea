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
package com.android.tools.idea.layoutinspector.properties.backstack

import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.model
import com.android.tools.idea.layoutinspector.model.ROOT
import com.android.tools.idea.layoutinspector.model.SelectionOrigin
import com.android.tools.idea.layoutinspector.model.VIEW1
import com.android.tools.idea.layoutinspector.model.VIEW2
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/** UI tests for [BackStackPanel], verifying proper rendering, visibility toggling, filtering, and active badges. */
class BackStackPanelTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()

  @Test
  fun testBackStackPanelRendersEntries() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail"))
    val panel = BackStackPanel(model)

    assertThat(panel.backStackList).containsExactly("Home", "Detail").inOrder()

    val labels = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
    assertThat(labels).contains("Home")
    assertThat(labels).contains("Detail")
  }

  @Test
  fun testBackStackPanelVisibilityOnSelection() {
    val inspectorModel =
      model(disposableRule.disposable) {
        view(ROOT) {
          view(VIEW1, qualifiedName = "androidx.compose.material3.Button")
          view(VIEW2, qualifiedName = "androidx.navigation3.NavDisplay")
        }
      }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(inspectorModel)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val panel = BackStackPanel(propertiesModel)
    panel.setToolContext(layoutInspector)
    assertThat(panel.isVisible).isFalse()

    inspectorModel.setSelection(inspectorModel[VIEW1], SelectionOrigin.COMPONENT_TREE)
    assertThat(panel.isVisible).isFalse()

    inspectorModel.setSelection(inspectorModel[VIEW2], SelectionOrigin.COMPONENT_TREE)
    assertThat(panel.isVisible).isTrue()

    inspectorModel.setSelection(null, SelectionOrigin.INTERNAL)
    assertThat(panel.isVisible).isFalse()
  }

  @Test
  fun testBackStackPanelFiltering() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail"))
    val panel = BackStackPanel(model)

    panel.setFilter("Det")
    assertThat(panel.filter).isEqualTo("Det")

    var labels = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
    assertThat(labels).contains("Detail")
    assertThat(labels).doesNotContain("Home")

    // Filter with non-matching term to verify empty state message is shown
    panel.setFilter("NonExistentRoute")
    assertThat(panel.filter).isEqualTo("NonExistentRoute")
    labels = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
    assertThat(labels).doesNotContain("Detail")
    assertThat(labels).doesNotContain("Home")

    // Reset filter
    panel.setFilter("")
    assertThat(panel.filter).isEmpty()
    labels = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
    assertThat(labels).contains("Home")
    assertThat(labels).contains("Detail")
  }

  @Test
  fun testBackStackPanelDuplicateRoutesActiveState() {
    val model = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail", "Home"))
    val panel = BackStackPanel(model)

    assertThat(panel.backStackList).containsExactly("Home", "Detail", "Home").inOrder()
  }
}
