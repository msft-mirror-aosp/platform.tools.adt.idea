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
package com.android.tools.adtui.stdui

import com.android.tools.adtui.instructions.HyperlinkInstruction
import com.android.tools.adtui.instructions.InstructionsPanel
import com.android.tools.adtui.instructions.TextInstruction
import com.android.tools.adtui.swing.FakeUi
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Point
import javax.swing.SwingUtilities
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class EmptyStatePanelTest {
  companion object {
    @JvmField @ClassRule val appRule = ApplicationRule()
  }

  @get:Rule val edtRule = EdtRule()
  @get:Rule val disposableRule = DisposableRule()

  @Test
  fun uses8pxSpacingWithinRelatedGroupsAnd20pxBetweenUnrelatedGroups() {
    val panel =
      EmptyStatePanel(
        reason = LabelData(TextChunk("Line 1"), NewLineChunk, TextChunk("Line 2")),
        helpUrlData = UrlData("Help link", "https://d.android.com"),
        ActionData("First action") {},
        ActionData("Second action") {},
      )

    val instructionsPanel = panel.getComponent(0) as InstructionsPanel
    val renderer = instructionsPanel.renderer
    val cursor = Point(renderer.getStartX(0), 0)
    val rowTopsByText = mutableMapOf<String, Int>()
    for (instruction in instructionsPanel.getRenderInstructionsForComponent(0)) {
      val bounds = instruction.getBounds(renderer, cursor)
      when (instruction) {
        is TextInstruction -> rowTopsByText[instruction.text] = bounds.y
        is HyperlinkInstruction -> rowTopsByText[instruction.displayTextForTests] = bounds.y
      }
      instruction.moveCursor(renderer, cursor)
    }

    val rowHeight = renderer.rowHeight
    assertThat(rowTopsByText.getValue("Line 2") - (rowTopsByText.getValue("Line 1") + rowHeight)).isEqualTo(JBUI.scale(8))
    assertThat(rowTopsByText.getValue("Help link") - (rowTopsByText.getValue("Line 2") + rowHeight)).isEqualTo(JBUI.scale(20))
    assertThat(rowTopsByText.getValue("First action") - (rowTopsByText.getValue("Help link") + rowHeight)).isEqualTo(JBUI.scale(20))
    assertThat(rowTopsByText.getValue("Second action") - (rowTopsByText.getValue("First action") + rowHeight)).isEqualTo(JBUI.scale(8))
  }

  @Test
  fun separatesExtraActionsToolbarFromInstructionsBy20px() {
    val extraAction =
      object : AnAction("Extra Action") {
        override fun actionPerformed(e: AnActionEvent) {}
      }
    val panel =
      EmptyStatePanel(
        reason = LabelData(TextChunk("Line 1"), NewLineChunk, TextChunk("Line 2")),
        helpUrlData = UrlData("Help link", "https://d.android.com"),
        extraActions = listOf(extraAction),
      )
    panel.size = Dimension(600, 400)
    val fakeUi = FakeUi(panel, parentDisposable = disposableRule.disposable)
    ActionToolbarImpl.updateAllToolbarsImmediately()
    fakeUi.layoutAndDispatchEvents()

    val instructionsPanel = panel.getComponent(0) as InstructionsPanel
    val instructionsComponent = instructionsPanel.getComponent(0)
    val extraActionsPanel = instructionsPanel.getComponent(1) as DialogPanel
    val toolbar = extraActionsPanel.components.filterIsInstance<ActionToolbar>().single().component
    val firstActionButton = toolbar.getComponent(0)

    val lastInstructionRowBottomInPanel =
      instructionsComponent.y + InstructionsPanel.Builder.DEFAULT_PADDING_PX + instructionsPanel.renderer.renderSize.height
    val firstButtonTopInPanel = SwingUtilities.convertPoint(firstActionButton.parent, firstActionButton.location, instructionsPanel).y
    assertThat(firstButtonTopInPanel - lastInstructionRowBottomInPanel).isEqualTo(JBUI.scale(20))
  }
}
