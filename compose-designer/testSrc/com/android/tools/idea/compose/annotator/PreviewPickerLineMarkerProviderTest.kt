/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.compose.annotator

import com.android.tools.compose.COMPOSE_PREVIEW_ANNOTATION_NAME
import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel
import com.android.tools.idea.compose.preview.COMPOSABLE_ANNOTATION_FQN
import com.android.tools.idea.compose.preview.PREVIEW_TOOLING_PACKAGE
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testing.moveCaret
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.runInEdtAndGet
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import org.apache.commons.lang3.StringUtils
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PreviewPickerLineMarkerProviderTest {

  private val filePath = "src/main/Test.kt"

  @get:Rule val rule = ComposeProjectRule()

  private val fixture
    get() = rule.fixture

  @get:Rule val edtRule = EdtRule()

  @Before
  fun setup() {
    (rule.fixture.module.getModuleSystem() as DefaultModuleSystem).usesCompose = true

    fixture.addFileToProject(
      filePath,
      // language=kotlin
      """
        import $COMPOSABLE_ANNOTATION_FQN
        import $PREVIEW_TOOLING_PACKAGE.Preview

        @Preview
        class MyNotAnnotation() {}

        @Preview
        annotation class MyAnnotation() {}

        @Composable
        fun composable1() {}

        @Preview
        fun badPreview1() {}

        @Preview(
          group = "my group"
        )
        @Composable
        fun preview1() {}
      """
        .trimIndent(),
    )
  }

  @RunsInEdt
  @Test
  fun gutterIconOnCorrectAnnotation() {
    val psiFile = fixture.findPsiFile(filePath)
    fixture.configureFromExistingVirtualFile(psiFile.virtualFile)

    fixture.doHighlighting()
    getAndAssertPreviewLineMarkers()

    fixture.moveCaret("\"my group\"\n)|\n@Composable")
    // The Modifier should still hold even after adding whitespace, and the LineMarker should be
    // available
    fixture.type('\n')
    fixture.type('\n')

    fixture.doHighlighting()
    val previewLineMarkerInfos = getAndAssertPreviewLineMarkers()
    listOf(psiFile.findPreviewAnnotation(2), psiFile.findPreviewAnnotation(4)).forEachIndexed { idx, validAnnotation ->
      assertEquals(validAnnotation.startOffset, previewLineMarkerInfos[idx].startOffset)
      assertEquals(validAnnotation.endOffset, previewLineMarkerInfos[idx].endOffset)
      assertEquals(COMPOSE_PREVIEW_ANNOTATION_NAME, previewLineMarkerInfos[idx].element!!.text)
    }
  }

  @RunsInEdt
  @Test
  fun lineMarkerAvailabilityOnVariedDeviceConfiguration() {
    val psiFile = fixture.findPsiFile(filePath)
    fixture.configureFromExistingVirtualFile(psiFile.virtualFile)

    fixture.moveCaret("\"my group\"|\n)\n@Composable")

    // Type incomplete device spec
    fixture.type(",\ndevice = \"spec:width=1080dp\"")
    fixture.doHighlighting()
    assertMissingLineMarker()

    fixture.moveCaret("spec:width=1080dp|\"")
    fixture.type(",height=1920dp") // Type the rest of a correct Device spec.
    fixture.doHighlighting()
    getAndAssertPreviewLineMarkers()
  }

  @Test
  fun clickingGutterIconOpensPicker() {
    var shownTitle: String? = null
    var shownModel: PreviewPickerPropertiesModel? = null
    val provider = PreviewPickerLineMarkerProvider { _, displayTitle, model ->
      shownTitle = displayTitle
      shownModel = model
    }
    val (marker, markerElement) =
      runInEdtAndGet {
        val psiFile = fixture.findPsiFile(filePath)
        fixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val previewLeaf = psiFile.findPreviewAnnotation(4).firstChild
        val info = checkNotNull(provider.getLineMarkerInfo(previewLeaf))
        info to checkNotNull(info.element)
      }

    val mouseEvent = MouseEvent(JLabel(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false)
    @Suppress("UNCHECKED_CAST") val handler = checkNotNull(marker.navigationHandler) as GutterIconNavigationHandler<PsiElement>
    SwingUtilities.invokeAndWait {
      assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
      handler.navigate(mouseEvent, markerElement)
    }

    assertEquals(message("picker.preview.title"), shownTitle)
    assertNotNull(shownModel)
    assertNotNull(shownModel!!.properties.getOrNull("", "group"))
  }

  private fun assertMissingLineMarker() {
    assertEquals(1, getPreviewLineMarkers().size)
  }

  private fun getAndAssertPreviewLineMarkers(): List<LineMarkerInfo<*>> {
    val previewLineMarkerInfos = getPreviewLineMarkers()

    assertEquals(2, previewLineMarkerInfos.size)
    assertTrue(previewLineMarkerInfos.all { "Preview Picker" == it.createGutterRenderer().clickAction!!.templateText })
    return previewLineMarkerInfos
  }

  private fun getPreviewLineMarkers(): List<LineMarkerInfo<*>> =
    LineMarkersPass.getDisplayedLineMarkers(fixture.editor.document, rule.project).filter { lineMarkerInfo ->
      lineMarkerInfo.lineMarkerTooltip == "Preview configuration picker"
    }
}

private fun PsiFile.findPreviewAnnotation(ordinal: Int): PsiElement = runInEdtAndGet {
  // The element should start after the '@' so add 1 to the offset
  val indexOfElement = StringUtils.ordinalIndexOf(text, "@$COMPOSE_PREVIEW_ANNOTATION_NAME", ordinal) + 1
  checkNotNull(PsiTreeUtil.findElementOfClassAtOffset(this, indexOfElement, KtNameReferenceExpression::class.java, true))
}
