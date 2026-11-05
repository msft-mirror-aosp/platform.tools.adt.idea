/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.tools.adtui.actions.SEPARATOR_TEXT
import com.android.tools.adtui.actions.createTestActionEvent
import com.android.tools.adtui.actions.prettyPrintActions
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.RunsInEdt
import java.lang.StringBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class ConfigurationSetMenuActionTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val project
    get() = projectRule.project

  private lateinit var form: VisualizationForm

  @Before
  fun setUp() {
    form = VisualizationForm(project, projectRule.testRootDisposable, TestVisualizationFormInitializer)
  }

  @After
  fun tearDown() {
    Disposer.dispose(form)
  }

  @Test
  fun testActions() {
    val menuGroups = ConfigurationSetProvider.getGroupedConfigurationSets()
    val firstOption = menuGroups.firstOrNull()?.firstOrNull() ?: return
    val menuAction = ConfigurationSetMenuAction(firstOption)
    // Call update(AnActionEvent) for updating text of menuAction.
    menuAction.update(createTestActionEvent(menuAction, dataContext = DataContext.EMPTY_CONTEXT))

    val actual = prettyPrintActions(menuAction)
    // The displayed text of dropdown action is the current selected option, which is Pixel Devices
    // in this case.
    val builder = StringBuilder("${firstOption.name}\n") // The current selection of dropdown action

    // Note: The options in dropdown menu have 4 spaces as indent.
    var isPreviousGroupEmpty = true
    for (group in menuGroups) {
      if (!isPreviousGroupEmpty) {
        builder.append("    $SEPARATOR_TEXT\n")
      }
      val groupItems = group.filter { it.visible }
      groupItems.forEach {
        if (it == firstOption) {
          builder.append("    ✔ ${it.name}\n")
        } else {
          builder.append("    ${it.name}\n")
        }
      }
      isPreviousGroupEmpty = groupItems.isEmpty()
    }

    val expected = builder.toString()
    assertEquals(expected, actual)
  }
}
