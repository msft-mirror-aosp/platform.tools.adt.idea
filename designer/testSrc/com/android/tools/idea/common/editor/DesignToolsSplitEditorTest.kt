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
package com.android.tools.idea.common.editor

import com.android.annotations.concurrency.UiThread
import com.android.tools.adtui.workbench.WorkBench
import com.android.tools.idea.common.analytics.CommonUsageTracker
import com.android.tools.idea.common.fixtures.ComponentDescriptor
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.navigateToComponent
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.uibuilder.NlModelBuilderUtil
import com.android.tools.idea.uibuilder.surface.NlSurfaceBuilder
import com.android.tools.idea.uibuilder.type.LayoutFileType
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.LayoutEditorEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunsInEdt
class DesignToolsSplitEditorTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val runsInEdt = EdtRule()

  private lateinit var nlModel: NlModel
  private lateinit var splitEditor: DesignToolsSplitEditor
  private lateinit var textEditor: TextEditor
  private lateinit var designerEditor: DesignerEditor
  private var showDefaultGutterPopupValue = false

  @Before
  fun setUp() {
    nlModel =
      NlModelBuilderUtil.model(projectRule, "layout", "layout.xml", ComponentDescriptor("LinearLayout")).build().apply {
        Disposer.register(projectRule.testRootDisposable, this)
      }

    val project = projectRule.project
    val surface = NlSurfaceBuilder.build(projectRule.project, projectRule.testRootDisposable)
    designerEditor =
      object : DesignerEditor(nlModel.file.virtualFile, project, LayoutFileType) {
        override fun getName(): String = "Test Design Editor"

        override fun getEditorId(): String = "TestDesignEditor"

        @UiThread
        override fun createEditorPanel(): DesignerEditorPanel =
          object :
            DesignerEditorPanel(
              this,
              project,
              file!!,
              WorkBench(project, "TestWorkBench", null, projectRule.testRootDisposable),
              { _ -> surface },
              {},
              { _, _, _, _, _ -> nlModel },
              { _ -> listOf() },
              { _, _ -> JLabel() },
              DesignerEditorPanel.State.FULL,
            ) {
            override fun initNeleModel() {
              // No-op in tests: prevent asynchronous model initialization on background thread
            }
          }
            .apply { Disposer.register(projectRule.testRootDisposable, this) }
      }

    val textEditorComponent = object : JComponent() {}
    textEditor = mock()
    whenever(textEditor.component).thenReturn(textEditorComponent)
    whenever(textEditor.file).thenReturn(mock(VirtualFile::class.java))
    val editor = mock(EditorEx::class.java)
    whenever(editor.contentComponent).thenReturn(mock(JComponent::class.java))
    whenever(textEditor.editor).thenReturn(editor)

    val gutterComponentEx = mock(EditorGutterComponentEx::class.java)
    whenever(editor.gutterComponentEx).thenReturn(gutterComponentEx)

    whenever(gutterComponentEx.setShowDefaultGutterPopup(anyBoolean())).then {
      showDefaultGutterPopupValue = (it.arguments[0] as? Boolean) ?: false
      Unit
    }
    val component = object : JComponent() {}
    splitEditor =
      object : DesignToolsSplitEditor(textEditor, designerEditor, project) {
        // The fact that we have to call registerModeNavigationShortcuts here repeating the behavior
        // in SplitEditor is incorrect
        // and should be fixed. However, we can not use the original getComponent method since it
        // calls getComponent of
        // TextEditorWithPreview which fails with a NullPointerException in testing environment.
        // This test, however, has a value
        // because we test that registerModeNavigationShortcuts does the right thing.
        // TODO(b/146150328)
        private var registeredShortcuts = false

        override fun getComponent(): JComponent {
          if (!registeredShortcuts) {
            registeredShortcuts = true
            registerModeNavigationShortcuts(component)
          }
          return component
        }
      }
    Disposer.register(projectRule.testRootDisposable, splitEditor)
    CommonUsageTracker.NOP_TRACKER.resetLastTrackedEvent()
  }

  @After
  fun tearDown() {
    KeyboardFocusManager.setCurrentKeyboardFocusManager(null)
  }

  @Test
  fun testTrackingModeChange() {
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isNull()
    var triggerExplicitly = false
    splitEditor.selectTextMode(triggerExplicitly)
    // We don't track change mode events when users don't trigger them explicitly
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isNull()

    triggerExplicitly = true
    splitEditor.selectTextMode(triggerExplicitly)
    // We don't track mode selection when it's redundant, i.e trying to select the mode that is
    // already selected
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isNull()

    splitEditor.selectDesignMode(triggerExplicitly)
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isEqualTo(LayoutEditorEvent.LayoutEditorEventType.SELECT_VISUAL_MODE)

    splitEditor.selectSplitMode(triggerExplicitly)
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isEqualTo(LayoutEditorEvent.LayoutEditorEventType.SELECT_SPLIT_MODE)

    splitEditor.selectTextMode(triggerExplicitly)
    assertThat(CommonUsageTracker.NOP_TRACKER.lastTrackedEvent).isEqualTo(LayoutEditorEvent.LayoutEditorEventType.SELECT_TEXT_MODE)
  }

  @Test
  fun testModeChange() {
    var triggerExplicitly = true
    splitEditor.selectTextMode(triggerExplicitly)
    assertThat(splitEditor.isTextMode()).isTrue()
    assertTrue(showDefaultGutterPopupValue)

    triggerExplicitly = false
    // We change mode even when users don't trigger it explicitly, e.g. when jumping to XML
    // definition
    splitEditor.selectDesignMode(triggerExplicitly)
    assertThat(splitEditor.isDesignMode()).isTrue()
    assertFalse(showDefaultGutterPopupValue)

    splitEditor.selectSplitMode(triggerExplicitly)
    assertThat(splitEditor.isSplitMode()).isTrue()
    assertTrue(showDefaultGutterPopupValue)
  }

  @Test
  fun testFileIsDelegateToTextEditor() {
    val splitEditorFile = splitEditor.file!!
    assertThat(splitEditorFile).isEqualTo(textEditor.file)
  }

  @Test
  fun testKeyboardShortcuts() {
    val modifiers = (if (SystemInfo.isMac) InputEvent.CTRL_DOWN_MASK else InputEvent.ALT_DOWN_MASK) or InputEvent.SHIFT_DOWN_MASK
    val focusManager = mock(KeyboardFocusManager::class.java)
    val component = splitEditor.component
    whenever(focusManager.focusOwner).thenReturn(component)
    KeyboardFocusManager.setCurrentKeyboardFocusManager(focusManager)
    val dispatcher = IdeKeyEventDispatcher(null)

    splitEditor.selectSplitMode(userExplicitlyTriggered = true)
    // The circular sequence is ... Code <-> Split <-> Design <-> Code <-> Split <-> Design <-> Code
    // ...
    dispatcher.pressAndRelease(KeyEvent.VK_LEFT, modifiers)
    assertThat(splitEditor.isTextMode()).isTrue()

    dispatcher.pressAndRelease(KeyEvent.VK_LEFT, modifiers)
    assertThat(splitEditor.isDesignMode()).isTrue()

    dispatcher.pressAndRelease(KeyEvent.VK_LEFT, modifiers)
    assertThat(splitEditor.isSplitMode()).isTrue()

    dispatcher.pressAndRelease(KeyEvent.VK_RIGHT, modifiers)
    assertThat(splitEditor.isDesignMode()).isTrue()

    dispatcher.pressAndRelease(KeyEvent.VK_RIGHT, modifiers)
    assertThat(splitEditor.isTextMode()).isTrue()

    dispatcher.pressAndRelease(KeyEvent.VK_RIGHT, modifiers)
    assertThat(splitEditor.isSplitMode()).isTrue()
  }

  // Regression test for b/348688204
  @Test
  fun testFocusRequestedOnVisibleEditorWhenSwitchingBackFromDesignMode() {
    var focusRequestedWhileRecursivelyVisible = false
    val editorContentComponent =
      object : JPanel() {
        override fun requestFocusInWindow(): Boolean {
          focusRequestedWhileRecursivelyVisible =
            generateSequence<Component>(this) { it.parent }.all { it.isVisible }
          return focusRequestedWhileRecursivelyVisible
        }
      }
    val editorComponent = JPanel().apply { add(editorContentComponent) }
    textEditor.component.apply {
      add(editorComponent)
      // Simulate PsiAwareTextEditorComponent updating editor.component visibility via ComponentEvents.
      addComponentListener(
        object : ComponentAdapter() {
          override fun componentShown(e: ComponentEvent?) {
            editorComponent.isVisible = true
          }

          override fun componentHidden(e: ComponentEvent?) {
            editorComponent.isVisible = false
          }
        }
      )
    }
    whenever(textEditor.preferredFocusedComponent).thenReturn(editorContentComponent)
    whenever(textEditor.editor.component).thenReturn(editorComponent)
    whenever(textEditor.editor.contentComponent).thenReturn(editorContentComponent)

    splitEditor.selectDesignMode(true)
    UIUtil.dispatchAllInvocationEvents()
    assertThat(editorComponent.isVisible).isFalse()

    focusRequestedWhileRecursivelyVisible = false
    splitEditor.selectTextMode(true)
    assertThat(focusRequestedWhileRecursivelyVisible).isTrue()
  }

  @Test
  fun testNavigateToSwitchesDesignModeToSplitMode() {
    splitEditor.selectDesignMode(userExplicitlyTriggered = false)
    assertThat(splitEditor.isDesignMode()).isTrue()

    val navigatable = OpenFileDescriptor(projectRule.project, nlModel.virtualFile, 0)
    splitEditor.navigateTo(navigatable)

    assertThat(splitEditor.isSplitMode()).isTrue()
    verify(textEditor).navigateTo(navigatable)
  }

  @Test
  fun testNavigateToComponentWithoutFocusInDesignModeNavigatesTextAndKeepsDesignMode() {
    val fileEditorManager = spy(FileEditorManagerEx.getInstanceEx(projectRule.project))
    doReturn(splitEditor).whenever(fileEditorManager).selectedEditor
    projectRule.replaceProjectService(FileEditorManager::class.java, fileEditorManager)
    whenever(textEditor.canNavigateTo(any())).thenReturn(true)

    splitEditor.selectDesignMode(userExplicitlyTriggered = false)
    assertThat(splitEditor.isDesignMode()).isTrue()

    val rootComponent = nlModel.treeReader.components.first()
    navigateToComponent(rootComponent, false)

    assertThat(splitEditor.isDesignMode()).isTrue()
    val descriptorCaptor = argumentCaptor<OpenFileDescriptor>()
    verify(textEditor).navigateTo(descriptorCaptor.capture())
    assertThat(descriptorCaptor.firstValue.file).isEqualTo(nlModel.virtualFile)
    assertThat(descriptorCaptor.firstValue.offset).isEqualTo(rootComponent.tagDeprecated!!.textOffset)
  }

  private fun IdeKeyEventDispatcher.pressAndRelease(keyCode: Int, modifiers: Int) {
    val component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    dispatchKeyEvent(KeyEvent(component, KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
    dispatchKeyEvent(KeyEvent(component, KeyEvent.KEY_RELEASED, 0, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
  }
}
