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
import com.android.tools.idea.layoutinspector.model.VIEW3
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterGroupItem
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterItem
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.android.tools.idea.layoutinspector.properties.InspectorPropertyItem
import com.android.tools.idea.layoutinspector.properties.PropertySection
import com.android.tools.idea.layoutinspector.properties.PropertyType
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.collect.HashBasedTable
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [BackStackPanelModel], verifying that navigation back stack properties are correctly identified, parsed, and dynamically
 * refreshed without touching Swing components.
 */
class BackStackPanelModelTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()

  @Test
  fun testBackStackPanelModelWithBackStack() {
    val model = model(disposableRule.disposable) { view(ROOT) { view(VIEW1) } }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(model)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val listChildren =
      mutableListOf(
        ParameterItem(
          name = "0",
          type = PropertyType.STRING,
          value = "Home",
          section = PropertySection.PARAMETERS,
          viewId = VIEW1,
          lookup = model,
          rootId = VIEW1,
          index = 0,
        ),
        ParameterItem(
          name = "1",
          type = PropertyType.STRING,
          value = "Detail",
          section = PropertySection.PARAMETERS,
          viewId = VIEW1,
          lookup = model,
          rootId = VIEW1,
          index = 1,
        ),
      )

    val backStackGroup =
      ParameterGroupItem(
        name = "backStack",
        type = PropertyType.ITERABLE,
        value = "List[2]",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 0,
        reference = null,
        children = listChildren,
      )

    val propertyTable = HashBasedTable.create<String, String, InspectorPropertyItem>()
    propertyTable.put("parameter", "backStack", backStackGroup)
    val properties = PropertiesTable.create(propertyTable)

    model.setSelection(model[VIEW1], SelectionOrigin.INTERNAL)
    propertiesModel.properties = properties

    val extractedBackstackItems = BackStackPanelModel.extractBackStackItems(backStackGroup)

    val panelModel = BackStackPanelModel(propertiesModel, extractedBackstackItems)
    assertThat(panelModel.backStackList).containsExactly("Home", "Detail").inOrder()
  }

  @Test
  fun testBackStackPanelModelDynamicRefreshOnPropertyValueUpdate() {
    val model = model(disposableRule.disposable) { view(ROOT) { view(VIEW1) } }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(model)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val initialChildren =
      mutableListOf(
        ParameterItem(
          name = "0",
          type = PropertyType.STRING,
          value = "Home",
          section = PropertySection.PARAMETERS,
          viewId = VIEW1,
          lookup = model,
          rootId = VIEW1,
          index = 0,
        )
      )

    val initialBackStackGroup =
      ParameterGroupItem(
        name = "backStack",
        type = PropertyType.ITERABLE,
        value = "List[1]",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 0,
        reference = null,
        children = initialChildren,
      )

    val propertyTable = HashBasedTable.create<String, String, InspectorPropertyItem>()
    propertyTable.put("parameter", "backStack", initialBackStackGroup)
    propertiesModel.properties = PropertiesTable.create(propertyTable)

    model.setSelection(model[VIEW1], SelectionOrigin.INTERNAL)

    val extractedBackstackItems = BackStackPanelModel.extractBackStackItems(initialBackStackGroup)

    val panelModel = BackStackPanelModel(propertiesModel, extractedBackstackItems)
    assertThat(panelModel.backStackList).containsExactly("Home")

    var modelChangedCalled = false
    panelModel.addListener { modelChangedCalled = true }

    val updatedChildren =
      mutableListOf(
        ParameterItem("0", PropertyType.STRING, "Home", PropertySection.PARAMETERS, VIEW1, model, VIEW1, 0),
        ParameterItem("1", PropertyType.STRING, "Detail", PropertySection.PARAMETERS, VIEW1, model, VIEW1, 1),
      )
    val updatedBackStackGroup =
      ParameterGroupItem(
        name = "backStack",
        type = PropertyType.ITERABLE,
        value = "List[2]",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 0,
        reference = null,
        children = updatedChildren,
      )

    val updatedTable = HashBasedTable.create<String, String, InspectorPropertyItem>()
    updatedTable.put("parameter", "backStack", updatedBackStackGroup)
    propertiesModel.properties = PropertiesTable.create(updatedTable)

    panelModel.updateBackStackForTesting()

    assertThat(panelModel.backStackList).containsExactly("Home", "Detail").inOrder()
    assertThat(modelChangedCalled).isTrue()
  }

  @Test
  fun testBackStackPanelModelWithEmptyBackStack() {
    val model = model(disposableRule.disposable) { view(ROOT) { view(VIEW1) } }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(model)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val backStackGroup =
      ParameterGroupItem(
        name = "backStack",
        type = PropertyType.ITERABLE,
        value = "List[0]",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 0,
        reference = null,
        children = mutableListOf(),
      )

    val propertyTable = HashBasedTable.create<String, String, InspectorPropertyItem>()
    propertyTable.put("parameter", "backStack", backStackGroup)
    propertiesModel.properties = PropertiesTable.create(propertyTable)

    model.setSelection(model[VIEW1], SelectionOrigin.INTERNAL)

    val extractedBackstackItems = BackStackPanelModel.extractBackStackItems(backStackGroup)

    val panelModel = BackStackPanelModel(propertiesModel, extractedBackstackItems)
    assertThat(panelModel.backStackList).isEmpty()
  }

  @Test
  fun testBackStackPanelModelFormattedEntries() {
    val model = model(disposableRule.disposable) { view(ROOT) { view(VIEW1) } }

    val idParam =
      ParameterItem(
        name = "id",
        type = PropertyType.INT32,
        value = "1",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 0,
      )

    val productEntry =
      ParameterGroupItem(
        name = "1",
        type = PropertyType.ITERABLE,
        value = "Product",
        section = PropertySection.PARAMETERS,
        viewId = VIEW1,
        lookup = model,
        rootId = VIEW1,
        index = 1,
        reference = null,
        children = mutableListOf(idParam),
      )

    val formatted = BackStackPanelModel.formatBackStackEntry(productEntry)
    assertThat(formatted).isEqualTo("Product(id=1)")
  }

  @Test
  fun testBackStackPanelModelVisibilityOnSelection() {
    val model =
      model(disposableRule.disposable) {
        view(ROOT) {
          view(VIEW1, qualifiedName = "androidx.compose.material3.Button")
          view(VIEW2, qualifiedName = "androidx.navigation3.NavDisplay")
          view(VIEW3, qualifiedName = "com.example.NavDisplayabilissimo")
        }
      }
    val layoutInspector: LayoutInspector = mock()
    whenever(layoutInspector.inspectorModel).thenReturn(model)

    val propertiesModel =
      InspectorPropertiesModel(disposableRule.disposable).apply {
        this.layoutInspector = layoutInspector
      }

    val panelModel = BackStackPanelModel(propertiesModel)
    panelModel.setToolContext(layoutInspector)
    assertThat(panelModel.isVisible).isFalse()

    model.setSelection(model[VIEW1], SelectionOrigin.COMPONENT_TREE)
    assertThat(panelModel.isVisible).isFalse()

    // Composable named NavDisplayabilissimo must NOT match
    model.setSelection(model[VIEW3], SelectionOrigin.COMPONENT_TREE)
    assertThat(panelModel.isVisible).isFalse()

    // NavDisplay matches
    model.setSelection(model[VIEW2], SelectionOrigin.COMPONENT_TREE)
    assertThat(panelModel.isVisible).isTrue()

    model.setSelection(null, SelectionOrigin.INTERNAL)
    assertThat(panelModel.isVisible).isFalse()
  }

  @Test
  fun testBackStackPanelModelFiltering() {
    val panelModel = BackStackPanelModel(initialBackStackList = listOf("Home", "Detail"))

    panelModel.setFilter("Det")
    assertThat(panelModel.filter).isEqualTo("Det")

    panelModel.setFilter("")
    assertThat(panelModel.filter).isEmpty()
  }
}
