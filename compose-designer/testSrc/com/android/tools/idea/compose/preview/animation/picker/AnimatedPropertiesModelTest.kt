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
package com.android.tools.idea.compose.preview.animation.picker

import com.android.tools.idea.compose.preview.animation.ComposeUnit
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.property.panel.impl.model.util.FakeInspectorPanel
import javax.swing.JLabel
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AnimatedPropertiesModelTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testPropertiesSeparatedByNamespace() {
    val initial = ComposeUnit.IntOffset(1, 2)
    val target = ComposeUnit.IntOffset(3, 4)
    val model = AnimatedPropertiesModel(initial, target) { _, _ -> }

    val initialProps = model.properties.getByNamespace(INITIAL_PROPERTY)
    val targetProps = model.properties.getByNamespace(TARGET_PROPERTY)

    assertEquals(2, initialProps.size)
    assertEquals(2, targetProps.size)
    assertEquals("1", initialProps["0"]?.defaultValue)
    assertEquals("2", initialProps["1"]?.defaultValue)
    assertEquals("3", targetProps["0"]?.defaultValue)
    assertEquals("4", targetProps["1"]?.defaultValue)
  }

  @Test
  fun testAttachToInspectorOrdersInitialAndTarget() {
    val initial = ComposeUnit.IntOffset(1, 2)
    val target = ComposeUnit.IntOffset(3, 4)
    val model = AnimatedPropertiesModel(initial, target) { _, _ -> }
    val inspector = FakeInspectorPanel()

    model.inspectorBuilder.attachToInspector(inspector, model.properties)

    assertEquals(6, inspector.lines.size)

    // Initial section label and properties
    val initialLabelPanel = inspector.lines[0].component as JPanel
    val initialTitleLabel = initialLabelPanel.components.first() as JLabel
    assertEquals("initial", initialTitleLabel.text)

    assertEquals(model.properties[INITIAL_PROPERTY, "0"], inspector.lines[1].editorModel?.property)
    assertEquals("x", inspector.lines[1].editorModel?.property?.name)
    assertEquals("1", inspector.lines[1].editorModel?.property?.defaultValue)

    assertEquals(model.properties[INITIAL_PROPERTY, "1"], inspector.lines[2].editorModel?.property)
    assertEquals("y", inspector.lines[2].editorModel?.property?.name)
    assertEquals("2", inspector.lines[2].editorModel?.property?.defaultValue)

    // Target section label and properties
    val targetLabelPanel = inspector.lines[3].component as JPanel
    val targetTitleLabel = targetLabelPanel.components.first() as JLabel
    assertEquals("target", targetTitleLabel.text)

    assertEquals(model.properties[TARGET_PROPERTY, "0"], inspector.lines[4].editorModel?.property)
    assertEquals("x", inspector.lines[4].editorModel?.property?.name)
    assertEquals("3", inspector.lines[4].editorModel?.property?.defaultValue)

    assertEquals(model.properties[TARGET_PROPERTY, "1"], inspector.lines[5].editorModel?.property)
    assertEquals("y", inspector.lines[5].editorModel?.property?.name)
    assertEquals("4", inspector.lines[5].editorModel?.property?.defaultValue)
  }
}
