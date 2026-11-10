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
package com.android.tools.idea.gradle.variant.view

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildNdkModelStub
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import java.awt.event.KeyEvent
import javax.swing.DefaultCellEditor
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

/** Tests for the cell editors of the table in [BuildVariantView]. */
@RunWith(JUnit4::class)
@RunsInEdt
class BuildVariantViewTest {
  @get:Rule val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @Test
  fun variantCellEditorListsModuleVariantsWithCurrentOneSelected() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(":app", "release", AndroidProjectBuilder()),
    )
    val table = variantsTable()

    val comboBox = editorComboBox(table, projectRule.project.gradleModule(":app")!!, VARIANT_COLUMN_INDEX)

    assertThat(comboBox.items().map { (it as BuildVariantItem).buildVariantName }).containsExactly("debug", "release").inOrder()
    assertThat((comboBox.selectedItem as BuildVariantItem).buildVariantName).isEqualTo("release")
  }

  @Test
  fun escapeCancelsEditingInVariantCellEditor() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(":app", "debug", AndroidProjectBuilder()),
    )
    val table = variantsTable()
    val row = rowOf(table, projectRule.project.gradleModule(":app")!!)
    val editor = table.getCellEditor(row, VARIANT_COLUMN_INDEX) as DefaultCellEditor
    var canceled = 0
    editor.addCellEditorListener(
      object : CellEditorListener {
        override fun editingStopped(e: ChangeEvent) = Unit

        override fun editingCanceled(e: ChangeEvent) {
          canceled++
        }
      }
    )

    val comboBox = editor.component
    val escape = KeyEvent(comboBox, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
    comboBox.keyListeners.forEach { it.keyPressed(escape) }

    assertThat(canceled).isEqualTo(1)
  }

  @Test
  fun abiCellEditorListsModuleAbisWithCurrentOneSelected() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        ":lib",
        "debug",
        AndroidProjectBuilder(projectType = { IdeAndroidProjectType.PROJECT_TYPE_LIBRARY }, ndkModel = { buildNdkModelStub() }),
      ),
    )
    val table = variantsTable()

    val comboBox = editorComboBox(table, projectRule.project.gradleModule(":lib")!!, ABI_COLUMN_INDEX)

    assertThat(comboBox.items().map { (it as AbiItem).abiName }).containsExactly("arm64-v8a", "x86_64").inOrder()
    assertThat((comboBox.selectedItem as AbiItem).abiName).isEqualTo("x86_64")
  }

  /** Builds the tool window content through the public API and returns the populated variants table. */
  private fun variantsTable(): JBTable {
    val view = BuildVariantView.getInstance(projectRule.project)
    val contentManager = mock(ContentManager::class.java)
    val toolWindow = mock(ToolWindow::class.java)
    whenever(toolWindow.contentManager).thenReturn(contentManager)
    view.createToolWindowContent(toolWindow)
    val content = ArgumentCaptor.forClass(Content::class.java)
    verify(contentManager).addContent(content.capture())

    view.updateContents(emptyList())
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    return UIUtil.findComponentOfType(content.value.component, JBTable::class.java)!!
  }

  private fun rowOf(table: JBTable, module: Module): Int =
    (0 until table.rowCount).single { table.getValueAt(it, MODULE_COLUMN_INDEX) == module }

  private fun editorComboBox(table: JBTable, module: Module, column: Int): ComboBox<*> {
    val editor = table.getCellEditor(rowOf(table, module), column)
    assertThat(editor).isInstanceOf(DefaultCellEditor::class.java)
    return (editor as DefaultCellEditor).component as ComboBox<*>
  }

  private fun ComboBox<*>.items(): List<Any?> = (0 until itemCount).map { getItemAt(it) }

  private companion object {
    // Mirror the private column indices in BuildVariantView.
    const val MODULE_COLUMN_INDEX = 0
    const val VARIANT_COLUMN_INDEX = 1
    const val ABI_COLUMN_INDEX = 2
  }
}
