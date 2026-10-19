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
package com.android.tools.idea.naveditor.actions

import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.actions.DESIGN_SURFACE
import com.android.tools.idea.naveditor.NavEditorRule
import com.android.tools.idea.naveditor.NavModelBuilderUtil.navigation
import com.android.tools.idea.naveditor.dialogs.AddActionDialog
import com.android.tools.idea.naveditor.surface.NavDesignSurface
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

@RunsInEdt
class AddActionDialogActionTest {

  private val edtRule = EdtRule()
  private val navRule = NavEditorRule()
  private val headlessDialogRule = HeadlessDialogRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(edtRule).around(navRule).around(headlessDialogRule)

  @Test
  fun testUpdate() {
    val model =
      navRule.model("nav.xml") {
        navigation {
          fragment("f1") { action("a1", "f2") }
          fragment("f2")
        }
      }
    val surface = model.surface as NavDesignSurface
    val f1 = model.treeReader.find("f1")!!
    val a1 = model.treeReader.find("a1")!!

    val toDestinationAction = ToDestinationAction(f1)
    val editExistingAction = EditExistingAction(f1, a1)

    val validEvent = TestActionEvent.createTestEvent { if (DESIGN_SURFACE.`is`(it)) surface else null }
    val invalidEvent = TestActionEvent.createTestEvent { null }

    toDestinationAction.update(validEvent)
    assertThat(validEvent.presentation.isEnabled).isTrue()

    toDestinationAction.update(invalidEvent)
    assertThat(invalidEvent.presentation.isEnabled).isFalse()

    editExistingAction.update(validEvent)
    assertThat(validEvent.presentation.isEnabled).isTrue()

    editExistingAction.update(invalidEvent)
    assertThat(invalidEvent.presentation.isEnabled).isFalse()
  }

  @Test
  fun testActionPerformed() {
    val model =
      navRule.model("nav.xml") {
        navigation {
          fragment("f1")
        }
      }
    val surface = model.surface as NavDesignSurface
    val f1 = model.treeReader.find("f1")!!

    val action = ToDestinationAction(f1)
    val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val event =
      TestActionEvent.createTestEvent(action) { if (DESIGN_SURFACE.`is`(it)) surface else null }
        .apply {
          installCoroutineScope(testScope)
        }

    try {
      createModalDialogAndInteractWithIt({ action.actionPerformed(event) }) { dialog ->
        assertThat(dialog).isInstanceOf(AddActionDialog::class.java)
        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
      }
    } finally {
      testScope.cancel()
    }
  }

  @Test
  fun testActionPerformedEditExisting() {
    val model =
      navRule.model("nav.xml") {
        navigation {
          fragment("f1") { action("a1", "f2") }
          fragment("f2")
        }
      }
    val surface = model.surface as NavDesignSurface
    val f1 = model.treeReader.find("f1")!!
    val a1 = model.treeReader.find("a1")!!

    val action = EditExistingAction(f1, a1)
    val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val event =
      TestActionEvent.createTestEvent(action) { if (DESIGN_SURFACE.`is`(it)) surface else null }
        .apply {
          installCoroutineScope(testScope)
        }

    try {
      createModalDialogAndInteractWithIt({ action.actionPerformed(event) }) { dialog ->
        assertThat(dialog).isInstanceOf(AddActionDialog::class.java)
        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
      }
    } finally {
      testScope.cancel()
    }
  }
}
