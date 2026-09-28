/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.editor

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_TEXT
import com.android.SdkConstants.BUTTON
import com.android.SdkConstants.LINEAR_LAYOUT
import com.android.SdkConstants.TAG_NAVIGATION
import com.android.tools.idea.common.SyncNlModel
import com.android.tools.idea.common.api.InsertType
import com.android.tools.idea.common.command.NlWriteCommandActionUtil
import com.android.tools.idea.common.editor.DesignToolsSplitEditor
import com.android.tools.idea.common.fixtures.ComponentDescriptor
import com.android.tools.idea.common.type.DesignerTypeRegistrar
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.waitForResourceRepositoryUpdates
import com.android.tools.idea.uibuilder.model.NlComponentRegistrar
import com.android.tools.idea.uibuilder.model.createChild
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.jetbrains.android.facet.AndroidFacet
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NlEditorProviderTest {

  @get:Rule val projectRule = AndroidProjectRule.withSdk()

  private val provider = NlEditorProvider()

  @After
  fun tearDown() {
    DesignerTypeRegistrar.clearRegisteredTypes()
  }

  @Test
  fun testAcceptLayoutFile() {
    val file = projectRule.fixture.addFileToProject("res/layout/my_layout.xml", layoutContent())
    assertTrue(provider.accept(projectRule.project, file.virtualFile))
  }

  @Test
  fun testDoNotAcceptNonLayoutFile() {
    val file = projectRule.fixture.addFileToProject("src/SomeFile.kt", "")
    assertFalse(provider.accept(projectRule.project, file.virtualFile))
  }

  @Test
  fun testDoNotAcceptNavigationFile() {
    val file = projectRule.fixture.addFileToProject("res/navigation/my_nav.xml", navigationContent())
    assertFalse(provider.accept(projectRule.project, file.virtualFile))
  }

  @Test
  fun testSplitViewComponentSelectionAndNavigation() = runBlocking {
    @Language("XML")
    val fileContents =
      """
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="match_parent"
          android:orientation="vertical">
          <TextView
              android:id="@+id/textView"
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              android:text="Hello World" />
          <Button
              android:id="@+id/button"
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              android:text="Click Me" />
      </LinearLayout>
      """
        .trimIndent()
    val file = projectRule.fixture.addFileToProject("res/layout/my_layout.xml", fileContents) as XmlFile

    projectRule.fixture.configureFromExistingVirtualFile(file.virtualFile)
    waitForResourceRepositoryUpdates(projectRule.module)

    val editor = provider.createFileEditor(projectRule.project, file.virtualFile, null, this) as DesignToolsSplitEditor
    Disposer.register(projectRule.testRootDisposable, editor)

    withContext(Dispatchers.EDT) {
      editor.selectSplitMode(userExplicitlyTriggered = false)
      assertTrue(editor.isSplitMode())

      val surface = editor.designerEditor.component.surface
      val model = attachSyncedModelToSurface(editor, file)

      val textViewComponent = model.treeReader.find("textView")!!
      val buttonComponent = model.treeReader.find("button")!!

      val text = editor.textEditor.editor.document.text
      val textViewOffset = text.indexOf("<TextView") + 1
      val buttonOffset = text.indexOf("<Button") + 1

      // XML -> Design Surface
      editor.textEditor.editor.caretModel.moveToOffset(textViewOffset)
      assertThat(surface.selectionModel.selection).containsExactly(textViewComponent)

      editor.textEditor.editor.caretModel.moveToOffset(buttonOffset)
      assertThat(surface.selectionModel.selection).containsExactly(buttonComponent)

      // Design Surface -> XML
      val descriptor = PsiNavigationSupport.getInstance().getDescriptor(textViewComponent.tagDeprecated)!!
      editor.navigateTo(descriptor)
      assertThat(editor.textEditor.editor.caretModel.offset).isEqualTo(textViewComponent.tagDeprecated.textOffset)
    }
  }

  @Test
  fun testBasicLayoutEditingAndModeSwitching() = runBlocking {
    projectRule.fixture.addFileToProject(
      "res/values/strings.xml",
      """
      <resources>
          <string name="app_name">MyTestApp</string>
      </resources>
      """
        .trimIndent(),
    )

    @Language("XML")
    val fileContents =
      """
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="match_parent"
          android:orientation="vertical">
      </LinearLayout>
      """
        .trimIndent()
    val file = projectRule.fixture.addFileToProject("res/layout/basic_layout.xml", fileContents) as XmlFile

    projectRule.fixture.configureFromExistingVirtualFile(file.virtualFile)
    waitForResourceRepositoryUpdates(projectRule.module)

    val editor = provider.createFileEditor(projectRule.project, file.virtualFile, null, this) as DesignToolsSplitEditor
    Disposer.register(projectRule.testRootDisposable, editor)

    withContext(Dispatchers.EDT) {
      // Verify mode switching
      editor.selectDesignMode(userExplicitlyTriggered = false)
      assertTrue(editor.isDesignMode())
      assertFalse(editor.isSplitMode())
      assertFalse(editor.isTextMode())

      editor.selectSplitMode(userExplicitlyTriggered = false)
      assertTrue(editor.isSplitMode())
      assertFalse(editor.isDesignMode())
      assertFalse(editor.isTextMode())

      editor.selectTextMode(userExplicitlyTriggered = false)
      assertTrue(editor.isTextMode())
      assertFalse(editor.isDesignMode())
      assertFalse(editor.isSplitMode())

      val model = attachSyncedModelToSurface(editor, file)

      val rootComponent = model.treeReader.components.first()
      NlWriteCommandActionUtil.run(rootComponent, "Add Button") {
        val button = rootComponent.createChild(BUTTON, null, InsertType.CREATE)
        button?.setAttribute(ANDROID_URI, ATTR_TEXT, "@string/app_name")
      }
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()

      val documentText = editor.textEditor.editor.document.text
      assertThat(documentText).contains("<Button")
      assertThat(documentText).contains("android:text=\"@string/app_name\"")
    }
  }

  private suspend fun attachSyncedModelToSurface(editor: DesignToolsSplitEditor, file: XmlFile): SyncNlModel {
    val model =
      SyncNlModel.create(
        projectRule.testRootDisposable,
        NlComponentRegistrar,
        AndroidBuildTargetReference.gradleOnly(AndroidFacet.getInstance(projectRule.module)!!),
        file.virtualFile,
      )
    model.syncWithPsi(file.rootTag!!, emptyList())
    editor.designerEditor.component.surface.addModelsWithoutRender(listOf(model))
    return model
  }

  @Language("XML")
  private fun layoutContent(): String {
    val layout = ComponentDescriptor(LINEAR_LAYOUT).withBounds(0, 0, 1000, 1000).matchParentWidth().matchParentHeight()
    val sb = StringBuilder(1000)
    layout.appendXml(sb, 0)
    return sb.toString()
  }

  @Language("XML")
  private fun navigationContent(): String {
    val nav = ComponentDescriptor(TAG_NAVIGATION).id("mynav")
    val sb = StringBuilder()
    nav.appendXml(sb, 0)
    return sb.toString()
  }
}
