/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.visual

import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.ComponentUtil
import com.intellij.ui.components.JBTextField
import java.awt.event.KeyEvent
import javax.swing.JButton
import kotlin.test.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class CustomConfigurationSetCreatePaletteTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  @Test
  fun testSetNameCannotBeBlank() {
    val palette = CustomConfigurationSetCreatePalette {}

    val textField = ComponentUtil.findComponentsOfType(palette, JBTextField::class.java).single()
    val addButton = ComponentUtil.findComponentsOfType(palette, JButton::class.java).single()

    textField.text = ""
    assertFalse(addButton.isEnabled)

    textField.text = "Something"
    assertTrue(addButton.isEnabled)

    // Also test the blank text
    textField.text = "    "
    assertFalse(addButton.isEnabled)
  }

  @Test
  fun testSetNameCannotBeDuplicated() {
    val existingCustomSetName = "My Existing Custom Config"
    // Simulate the creation of a config named [existingCustomSetName]
    val createdConfigSet = CustomConfigurationSet(existingCustomSetName, emptyList())
    VisualizationUtil.setCustomConfigurationSet("id", createdConfigSet)

    val palette = CustomConfigurationSetCreatePalette {}

    val textField = ComponentUtil.findComponentsOfType(palette, JBTextField::class.java).single()
    val addButton = ComponentUtil.findComponentsOfType(palette, JButton::class.java).single()
    val customSetName = "My Custom Config"

    textField.text = customSetName
    assertTrue(addButton.isEnabled)

    textField.text = existingCustomSetName
    assertFalse(addButton.isEnabled)
  }

  @Test
  fun testEnterSubmitsDefaultAddButtonAndClearsOnRemoval() {
    var createdName: String? = null
    val palette = CustomConfigurationSetCreatePalette { createdName = it }
    val fakeUi = FakeUi(palette, projectRule.testRootDisposable)

    val textField = ComponentUtil.findComponentsOfType(palette, JBTextField::class.java).single()
    val addButton = ComponentUtil.findComponentsOfType(palette, JButton::class.java).single()
    val rootPane = assertNotNull(palette.rootPane)
    assertTrue(addButton.isDefaultButton)

    textField.text = "Custom123"
    fakeUi.keyboard.setFocus(textField)
    fakeUi.keyboard.pressAndRelease(KeyEvent.VK_ENTER)
    assertEquals("Custom123", createdName)

    palette.parent.remove(palette)
    assertNull(rootPane.defaultButton)
  }
}
