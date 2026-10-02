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

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.tools.adtui.compose.utils.StudioComposeTestRule
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class GeneratedCodeSectionTest {
  private val projectRule = AndroidProjectRule.inMemory()
  private val composeTestRule = StudioComposeTestRule.createStudioComposeTestRule()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(projectRule).around(composeTestRule)

  @Test
  fun debouncedDocumentUpdatesAndCopyAndDisposal() {
    var code by mutableStateOf("initialCode()")
    var showSection by mutableStateOf(true)
    var viewer: GeneratedCodeViewer? = null

    composeTestRule.setContent {
      Column {
        if (showSection) {
          GeneratedCodeSection(
            project = projectRule.project,
            onViewerCreated = { viewer = it },
            renderEditor = false,
            generatedCode = { code },
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    val activeViewer = checkNotNull(viewer)
    assertEquals("initialCode()", activeViewer.editor.document.text)

    // Debounced update
    code = "updatedCode()"
    composeTestRule.waitUntil { activeViewer.editor.document.text == "updatedCode()" }
    assertEquals("updatedCode()", activeViewer.editor.document.text)

    // Copy to clipboard
    composeTestRule.onNodeWithText(message("gradient.editor.code.copy")).performClick()
    val copied = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    assertEquals("updatedCode()", copied)

    // Disposal on unmount
    showSection = false
    composeTestRule.waitForIdle()
    assertTrue(activeViewer.editor.isDisposed)
  }
}
