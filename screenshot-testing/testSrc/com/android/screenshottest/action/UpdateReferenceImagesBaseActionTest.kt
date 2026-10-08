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
package com.android.screenshottest.action

import com.android.screenshottest.util.UpdateReferenceImagesDialogManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.runInEdtAndWait
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateReferenceImagesBaseActionTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testDialogNotCreatedWhenNoScreenshotTestConfiguration() = runInEdtAndWait {
    val manager = UpdateReferenceImagesDialogManager.getInstance(projectRule.project)
    var dialogsCreated = 0
    manager.dialogFactory = {
      dialogsCreated++
      error("Dialog should not be created without a Screenshot Tests configuration")
    }

    val file = projectRule.fixture.configureByText("Foo.kt", "class Foo")
    val action = UpdateReferenceImagesAction()
    val dataContext =
      SimpleDataContext.builder().add(CommonDataKeys.PROJECT, projectRule.project).add(Location.DATA_KEY, PsiLocation(file)).build()
    action.actionPerformed(TestActionEvent.createTestEvent(action, dataContext))

    assertEquals(0, dialogsCreated)
  }
}
