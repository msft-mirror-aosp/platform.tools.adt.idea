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
package com.android.tools.idea.compose.pickers.preview

import com.android.sdklib.devices.Device
import com.android.tools.adtui.model.stdui.EditingErrorCategory
import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.PsiComposePreviewElement
import com.android.tools.idea.compose.pickers.base.model.PsiPropertiesModel
import com.android.tools.idea.compose.pickers.base.property.PsiCallParameterPropertyItem
import com.android.tools.idea.compose.pickers.base.property.PsiPropertyItem
import com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker
import com.android.tools.idea.compose.pickers.common.tracking.NoOpTracker
import com.android.tools.idea.compose.pickers.preview.enumsupport.FontScale
import com.android.tools.idea.compose.pickers.preview.enumsupport.UiModeWithNightMaskEnumValue
import com.android.tools.idea.compose.pickers.preview.enumsupport.Wallpaper
import com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel
import com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder
import com.android.tools.idea.compose.preview.COMPOSABLE_ANNOTATION_FQN
import com.android.tools.idea.compose.preview.PREVIEW_TOOLING_PACKAGE
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.testing.Sdks
import com.android.tools.preview.config.ReferencePhoneConfig
import com.android.tools.property.panel.api.PropertiesModel
import com.android.tools.property.panel.api.PropertiesModelListener
import com.google.wireless.android.sdk.stats.EditorPickerEvent.EditorPickerAction.PreviewPickerModification.PreviewPickerValue
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.runInEdtAndWait
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.jetbrains.android.compose.stubConfigurationAsLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

private fun PsiComposePreviewElement.annotationText(): String =
  ReadAction.compute<String, Throwable> { previewElementDefinition?.element?.text ?: "" }

class PreviewPickerTests {

  @get:Rule val projectRule = ComposeProjectRule()

  @get:Rule val edtRule = EdtRule()
  private val fixture
    get() = projectRule.fixture

  private val project
    get() = projectRule.project

  private val module
    get() = projectRule.fixture.module

  @RunsInEdt
  @Test
  fun `the psi model reads the preview annotation correctly`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview
      fun PreviewNoParameters() {
      }

      @Composable
      @Preview("named")
      fun PreviewWithName() {
      }

      @Composable
      @Preview
      fun PreviewParameters() {
      }

      private const val nameFromConst = "Name from Const"

      @Composable
      @Preview(nameFromConst)
      fun PreviewWithNameFromConst() {
      }
    """
        .trimIndent()

    val file = fixture.configureByText("Test.kt", fileContent)
    val previews = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, file.virtualFile).toList()
    ReadAction.run<Throwable> {
      previews[0].also { noParametersPreview ->
        val parsed =
          PreviewPickerPropertiesModel.fromPreviewElement(project, module, noParametersPreview.previewElementDefinition, NoOpTracker)
        assertNotNull(parsed.properties["", "name"])
        assertNull(parsed.properties.getOrNull("", "name2"))
      }
      previews[1].also { namedPreview ->
        val parsed = PreviewPickerPropertiesModel.fromPreviewElement(project, module, namedPreview.previewElementDefinition, NoOpTracker)
        assertEquals("named", parsed.properties["", "name"].value)
      }
      previews[3].also { namedPreviewFromConst ->
        val parsed =
          PreviewPickerPropertiesModel.fromPreviewElement(project, module, namedPreviewFromConst.previewElementDefinition, NoOpTracker)
        assertEquals("Name from Const", parsed.properties["", "name"].value)
      }
    }
  }

  @Test
  fun `updating model updates the psi correctly`() = runBlocking {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)

    @Language("kotlin")
    val annotationWithParameters =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(name = "Test")
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    assertUpdatingModelUpdatesPsiCorrectly(annotationWithParameters)

    @Language("kotlin")
    val emptyAnnotation =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    assertUpdatingModelUpdatesPsiCorrectly(emptyAnnotation)

    // Avoid duplicate `()` if parentheses already exist but are empty, e.g. `@Preview()`.
    val emptyAnnotationWithParentheses =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview()
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    assertUpdatingModelUpdatesPsiCorrectly(emptyAnnotationWithParentheses)

    // Case when we have spaces within the parentheses
    @Language("kotlin")
    val emptyAnnotationWithWhitespaceInParentheses =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(   )
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    assertUpdatingModelUpdatesPsiCorrectly(emptyAnnotationWithWhitespaceInParentheses)
  }

  @Test
  fun `updating empty parentheses annotation does not add duplicate parentheses`() = runBlocking {
    // Start with an annotation that already has empty parentheses @Preview() (b/414544459)
    @Language("kotlin")
    val emptyParenthesesPreviewContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview()
      fun PreviewWithEmptyParentheses() {
      }
      """
        .trimIndent()

    val propertiesModel = getFirstModel(emptyParenthesesPreviewContent)
    val previewElement =
      AnnotationFilePreviewElementFinder.findPreviewElements(
          fixture.project,
          fixture.findFileInTempDir("Test.kt"),
        )
        .first()

    // Setting "name" triggers PsiCallParameterPropertyItem.writeNewValue it must reuse the existing "()" rather than appending a second
    // "()".
    runModificationInEdtWaitAndDispatch {
      propertiesModel.properties["", "name"].value = "MyPreview"
    }
    assertEquals("@Preview(name = \"MyPreview\")", previewElement.annotationText())

    // Clear the parameters by setting "name" to null it should delete the now-empty KtValueArgumentList "()" from the PSI tree.
    runModificationInEdtWaitAndDispatch {
      propertiesModel.properties["", "name"].value = null
    }
    assertEquals("@Preview", previewElement.annotationText())

    // Re-add the parameter now that @Preview has no parentheses by setting "name" again triggers KtCallElement.addNewValueArgument when
    // valueArgumentList == null.
    runModificationInEdtWaitAndDispatch {
      propertiesModel.properties["", "name"].value = "MyPreview"
    }
    assertEquals("@Preview(name = \"MyPreview\")", previewElement.annotationText())
  }

  @Test
  fun `supported parameters displayed correctly`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(name = "Test", fontScale = 1.2f, backgroundColor = 4294901760)
      fun PreviewWithParemeters() {
      }
    """
        .trimIndent()

    val model = getFirstModel(fileContent)
    assertNotNull(model.properties["", "backgroundColor"].colorButton)
    assertEquals("1.2", runReadAction { model.properties["", "fontScale"].value })
    assertEquals("0xFFFF0000", runReadAction { model.properties["", "backgroundColor"].value })

    runModificationInEdtWaitAndDispatch {
      model.properties["", "fontScale"].value = "0.5"
      model.properties["", "backgroundColor"].value = "0x00FF00"
    }
    assertEquals("0.5", runReadAction { model.properties["", "fontScale"].value })
    assertEquals("0x0000FF00", runReadAction { model.properties["", "backgroundColor"].value })
  }

  @Test
  fun `preview default values`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(name = "Test")
      fun PreviewNoParameters() {
      }
    """
        .trimIndent()

    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val model = getFirstModel(fileContent)
    assertEquals(1.0f, model.properties["", "fontScale"].defaultValue!!.toFloat(), 0f)
    assertEquals("false", model.properties["", "showBackground"].defaultValue)
    assertEquals("false", model.properties["", "showSystemUi"].defaultValue)
    assertEquals("Default (en-US)", model.properties["", "locale"].defaultValue)
    assertTrue(model.properties["", "apiLevel"].defaultValue!!.toInt() > 0)

    // Note that uiMode and device, are displayed through a ComboBox option and don't actually
    // display these values
    assertEquals("Undefined", model.properties["", "uiMode"].defaultValue)
    assertEquals("Default", model.properties["", "Device"].defaultValue)

    // Hardware properties
    assertEquals("1080", model.properties["", "Width"].defaultValue)
    assertEquals("2340", model.properties["", "Height"].defaultValue)
    assertEquals("px", model.properties["", "DimensionUnit"].defaultValue)
    assertEquals("portrait", model.properties["", "Orientation"].defaultValue)
    assertEquals("440", model.properties["", "Density"].defaultValue)
    assertEquals("false", model.properties["", "IsRound"].defaultValue)
    assertEquals("0", model.properties["", "ChinSize"].defaultValue)
    assertEquals("None", model.properties["", "wallpaper"].defaultValue)

    // We hide the default value of some values when the value's behavior is undefined
    assertEquals(null, model.properties["", "widthDp"].defaultValue)
    assertEquals(null, model.properties["", "heightDp"].defaultValue)
    // We don't take the library's default value for color
    assertEquals(null, model.properties["", "backgroundColor"].defaultValue)
  }

  @Test
  fun `switching dimension unit from px to dp and back to px preserves pixel dimensions`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(name = "Test")
      fun PreviewNoParameters() {
      }
    """
        .trimIndent()

    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val model = getFirstModel(fileContent)

    // Verify initial default pixel dimensions (pixel_5: 1080 x 2340 px at 440 dpi).
    runInEdtAndWait {
      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("2340", model.properties["", "Height"].value)
    }

    // Switch DimensionUnit from px to dp.
    runModificationInEdtWaitAndDispatch { model.properties["", "DimensionUnit"].value = "dp" }
    runInEdtAndWait {
      assertEquals("392.7", model.properties["", "Width"].value)
      assertEquals("850.9", model.properties["", "Height"].value)
    }

    // Switch DimensionUnit back from dp to px.
    runModificationInEdtWaitAndDispatch { model.properties["", "DimensionUnit"].value = "px" }
    runInEdtAndWait {
      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("2340", model.properties["", "Height"].value)
    }
  }

  @Test
  fun `saved custom preview dimensions are reflected in hardware properties and cleared when device is changed`() = runTest {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(
          name = "50dp x 81dp",
          showBackground = true,
          widthDp = 50,
          heightDp = 81
      )
      fun CustomSizePreview() {
      }
      """
        .trimIndent()

    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val model = getFirstModel(fileContent)
    val preview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, fixture.findFileInTempDir("Test.kt")).first()

    withContext(Dispatchers.EDT) {
      assertEquals("50", model.properties["", "Width"].value)
      assertEquals("81", model.properties["", "Height"].value)
      assertEquals("dp", model.properties["", "DimensionUnit"].value)
      assertEquals("portrait", model.properties["", "Orientation"].value)

      model.properties["", "Device"].value = "id:pixel_5"
      dispatchAllEventsInIdeEventQueue()

      assertNull(model.properties["", "widthDp"].value)
      assertNull(model.properties["", "heightDp"].value)
      assertEquals("id:pixel_5", model.properties["", "Device"].value)
      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("2340", model.properties["", "Height"].value)
      assertEquals("px", model.properties["", "DimensionUnit"].value)
    }
    assertEquals(
      """
      @Preview(
          name = "50dp x 81dp",
          showBackground = true, device = "id:pixel_5"
      )
      """
        .trimIndent(),
      preview.annotationText(),
    )
  }

  @Test
  fun `editing the dimensions of a saved custom preview updates widthDp and heightDp`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 50, heightDp = 81)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Width", "60")

      assertEquals("60", model.properties["", "widthDp"].value)
      assertEquals("81", model.properties["", "heightDp"].value)
      assertEquals("60", model.properties["", "Width"].value)
      assertEquals("81", model.properties["", "Height"].value)
    }
    // The composable keeps its fixed size: widthDp and heightDp also size it, which the device parameter doesn't.
    assertEquals("@Preview(widthDp = 60, heightDp = 81)", preview.annotationText())

    // Values are rounded to whole dp.
    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Height", "100.6") }
    assertEquals("@Preview(widthDp = 60, heightDp = 101)", preview.annotationText())
  }

  @Test
  fun `rotating a saved custom preview swaps widthDp and heightDp`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 50, heightDp = 81)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Orientation", "landscape")

      assertEquals("landscape", model.properties["", "Orientation"].value)
      assertEquals("81", model.properties["", "Width"].value)
      assertEquals("50", model.properties["", "Height"].value)
    }
    assertEquals("@Preview(widthDp = 81, heightDp = 50)", preview.annotationText())
  }

  @Test
  fun `changing dimension unit of saved custom preview converts widthDp and heightDp to device spec`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 50, heightDp = 81)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("DimensionUnit", "px")

      // 50dp x 81dp at 440 dpi
      assertEquals("px", model.properties["", "DimensionUnit"].value)
      assertEquals("138", model.properties["", "Width"].value)
      assertEquals("223", model.properties["", "Height"].value)
    }
    assertEquals("@Preview(device = \"spec:width=138px,height=223px,dpi=440\")", preview.annotationText())
  }

  @Test
  fun `hardware edits keep custom preview dimensions`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 50, heightDp = 81)")

    // The edits apply to the device (the default one, pixel_5), widthDp and heightDp keep replacing its screen size.
    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Density", "320") }
    assertEquals("@Preview(widthDp = 50, heightDp = 81, device = \"spec:width=1080px,height=2340px,dpi=320\")", preview.annotationText())

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Navigation", "buttons")

      assertEquals("50", model.properties["", "Width"].value)
      assertEquals("81", model.properties["", "Height"].value)
      assertEquals("dp", model.properties["", "DimensionUnit"].value)
      assertEquals("320", model.properties["", "Density"].value)
      assertEquals("buttons", model.properties["", "Navigation"].value)
    }
    assertEquals(
      """
      @Preview(widthDp = 50, heightDp = 81,
          device = "spec:width=1080px,height=2340px,dpi=320,navigation=buttons"
      )
      """
        .trimIndent(),
      preview.annotationText(),
    )
  }

  @Test
  fun `hardware edits with custom preview dimensions keep the parent device`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(device = \"id:pixel_5\", widthDp = 200, heightDp = 300)")

    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Navigation", "buttons") }
    assertEquals(
      "@Preview(device = \"spec:parent=pixel_5,navigation=buttons\", widthDp = 200, heightDp = 300)",
      preview.annotationText(),
    )
  }

  @Test
  fun `hardware edits that do not change the device do not write it`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(device = \"id:pixel_5\", widthDp = 50, heightDp = 81)")

    withContext(Dispatchers.EDT) {
      // Selecting the value that is already selected or typing an equal one.
      model.setValueAndDispatch("Orientation", "portrait")
      model.setValueAndDispatch("DimensionUnit", "dp")
      model.setValueAndDispatch("Width", "50.0")
      model.setValueAndDispatch("Width", "50.2")
      model.setValueAndDispatch("Density", "440")
      // Values that can't be applied.
      model.setValueAndDispatch("Orientation", "bad input")
      model.setValueAndDispatch("Height", "bad input")
    }
    assertEquals("@Preview(device = \"id:pixel_5\", widthDp = 50, heightDp = 81)", preview.annotationText())
  }

  @Test
  fun `editing the dimension that is not set adds it`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 200)")

    withContext(Dispatchers.EDT) {
      // Crossing into landscape keeps widthDp and the orientation follows the new size.
      model.setValueAndDispatch("Height", "100")

      assertEquals("200", model.properties["", "Width"].value)
      assertEquals("100", model.properties["", "Height"].value)
      assertEquals("landscape", model.properties["", "Orientation"].value)
    }
    assertEquals("@Preview(widthDp = 200, heightDp = 100)", preview.annotationText())
  }

  @Test
  fun `rotating a preview with one custom dimension sets both`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 200)")

    // The height is the one of the default device, pixel_5 (850.9dp).
    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Orientation", "landscape") }
    assertEquals("@Preview(widthDp = 851, heightDp = 200)", preview.annotationText())
  }

  @Test
  fun `custom preview dimensions replace the screen size of the device parameter`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(device = \"id:pixel_5\", widthDp = 200, heightDp = 300)")

    withContext(Dispatchers.EDT) {
      assertEquals("200", model.properties["", "Width"].value)
      assertEquals("300", model.properties["", "Height"].value)
      assertEquals("dp", model.properties["", "DimensionUnit"].value)
      assertEquals("440", model.properties["", "Density"].value)

      model.setValueAndDispatch("Width", "250")
    }
    assertEquals("@Preview(device = \"id:pixel_5\", widthDp = 250, heightDp = 300)", preview.annotationText())
  }

  @Test
  fun `picking a device removes custom preview dimensions even with the size of the device`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(widthDp = 411, heightDp = 891)")

    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Device", ReferencePhoneConfig.deviceSpec()) }
    assertEquals("@Preview(device = \"${ReferencePhoneConfig.deviceSpec()}\")", preview.annotationText())
  }

  @Test
  fun `picking the device that is already set removes custom preview dimensions`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(device = \"id:pixel_5\", widthDp = 200, heightDp = 300)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Device", "id:pixel_5")

      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("2340", model.properties["", "Height"].value)
    }
    assertEquals("@Preview(device = \"id:pixel_5\")", preview.annotationText())
  }

  @Test
  fun `clearing the device when it is not set removes custom preview dimensions`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(name = \"50dp x 81dp\", widthDp = 50, heightDp = 81)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Device", null)

      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("px", model.properties["", "DimensionUnit"].value)
    }
    assertEquals("@Preview(name = \"50dp x 81dp\")", preview.annotationText())
  }

  @Test
  fun `custom preview dimensions are not used as device dimensions when the system UI is shown`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(showSystemUi = true, widthDp = 200, heightDp = 300)")

    withContext(Dispatchers.EDT) {
      // With the system UI, widthDp and heightDp only size the composable inside the frame of the default device (pixel_5).
      assertEquals("1080", model.properties["", "Width"].value)
      assertEquals("2340", model.properties["", "Height"].value)
      assertEquals("px", model.properties["", "DimensionUnit"].value)

      // Hardware edits apply to the device and keep them.
      model.setValueAndDispatch("Width", "1000")
    }
    assertEquals(
      """
      @Preview(showSystemUi = true, widthDp = 200, heightDp = 300,
          device = "spec:width=1000px,height=2340px,dpi=440"
      )
      """
        .trimIndent(),
      preview.annotationText(),
    )

    // Picking a device removes them, as the picker has no field for them.
    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Device", "id:pixel_5") }
    assertEquals(
      """
      @Preview(showSystemUi = true,
          device = "id:pixel_5"
      )
      """
        .trimIndent(),
      preview.annotationText(),
    )
  }

  @Test
  fun `custom preview dimensions are read like in rendering`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)

    // A missing dimension is the one of the device: the height of the default device, pixel_5 (2340px at 440 dpi).
    val (singleDimensionModel, _) = createCustomSizePreviewModel("@Preview(widthDp = 200)")
    withContext(Dispatchers.EDT) {
      assertEquals("200", singleDimensionModel.properties["", "Width"].value)
      assertEquals("850.9", singleDimensionModel.properties["", "Height"].value)
      assertEquals("portrait", singleDimensionModel.properties["", "Orientation"].value)
    }

    // ... taking the orientation of the device into account.
    val (rotatedDeviceModel, _) =
      createCustomSizePreviewModel("@Preview(device = \"spec:parent=pixel_5,orientation=landscape\", heightDp = 200)")
    withContext(Dispatchers.EDT) {
      assertEquals("850.9", rotatedDeviceModel.properties["", "Width"].value)
      assertEquals("200", rotatedDeviceModel.properties["", "Height"].value)
      assertEquals("landscape", rotatedDeviceModel.properties["", "Orientation"].value)
    }

    // Dimensions are clamped to [MIN_DIMENSION_DP, MAX_DIMENSION_DP] and the orientation follows them.
    val (clampedModel, _) = createCustomSizePreviewModel("@Preview(widthDp = 5000, heightDp = 0)")
    withContext(Dispatchers.EDT) {
      assertEquals("3000", clampedModel.properties["", "Width"].value)
      assertEquals("1", clampedModel.properties["", "Height"].value)
      assertEquals("landscape", clampedModel.properties["", "Orientation"].value)
    }
  }

  @Test
  fun `clearing the device removes custom preview dimensions`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) =
      createCustomSizePreviewModel("@Preview(name = \"Custom\", device = \"id:pixel_5\", widthDp = 200, heightDp = 300)")

    withContext(Dispatchers.EDT) { model.setValueAndDispatch("Device", null) }
    assertEquals("@Preview(name = \"Custom\")", preview.annotationText())
  }

  @Test
  fun `clearing a positional argument keeps the arguments after it in their parameters`() = runTest {
    // Regression test for b/576666275: the group used to shift into the name.
    val (model, preview) = createCustomSizePreviewModel("@Preview(\"My preview\", \"My group\")")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("name", null)

      assertNull(model.properties["", "name"].value)
      assertEquals("My group", model.properties["", "group"].value)
    }
    assertEquals("@Preview(group = \"My group\")", preview.annotationText())

    // The group property is still bound to its argument after it was named.
    withContext(Dispatchers.EDT) { model.setValueAndDispatch("group", "Other group") }
    assertEquals("@Preview(group = \"Other group\")", preview.annotationText())
  }

  @Test
  fun `clearing a named argument keeps the positional arguments after it in their parameters`() = runTest {
    // A named argument in its own position can be followed by positional arguments.
    val (model, preview) = createCustomSizePreviewModel("@Preview(\"My preview\", group = \"My group\", 33)")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("group", null)

      assertNull(model.properties["", "group"].value)
      assertEquals("33", model.properties["", "apiLevel"].value)
    }
    assertEquals("@Preview(\"My preview\", apiLevel = 33)", preview.annotationText())

    // That includes the first argument.
    val (nameModel, namePreview) = createCustomSizePreviewModel("@Preview(name = \"My preview\", \"My group\")")
    withContext(Dispatchers.EDT) {
      nameModel.setValueAndDispatch("name", null)

      assertNull(nameModel.properties["", "name"].value)
      assertEquals("My group", nameModel.properties["", "group"].value)
    }
    assertEquals("@Preview(group = \"My group\")", namePreview.annotationText())
  }

  @Test
  fun `clearing an argument keeps the call unchanged if an argument after it can't be named`() = runTest {
    // The last argument doesn't match any parameter, so it can't be named.
    val annotation = "@Preview(\"My preview\", \"My group\", 33, 200, 300, \"fr\", 1f, false, false, 0, 0, \"id:pixel_5\", 0, 42)"
    val (model, preview) = createCustomSizePreviewModel(annotation)

    withContext(Dispatchers.EDT) {
      assertEquals("My group", model.properties["", "group"].value)

      model.setValueAndDispatch("name", null)

      assertEquals("My preview", model.properties["", "name"].value)
      assertEquals("My group", model.properties["", "group"].value)
    }
    assertEquals(annotation, preview.annotationText())
  }

  @Test
  fun `removing custom preview dimensions keeps the arguments after them in their parameters`() = runTest {
    Sdks.addLatestAndroidSdk(fixture.projectDisposable, module)
    val (model, preview) = createCustomSizePreviewModel("@Preview(\"Name\", \"Group\", 33, 200, 300, \"fr\")")

    withContext(Dispatchers.EDT) {
      model.setValueAndDispatch("Device", "id:pixel_5")

      assertNull(model.properties["", "widthDp"].value)
      assertNull(model.properties["", "heightDp"].value)
      assertEquals("fr", model.properties["", "locale"].value)
    }
    assertEquals("@Preview(\"Name\", \"Group\", 33, locale = \"fr\", device = \"id:pixel_5\")", preview.annotationText())

    // Named custom preview dimensions in their own positions can be followed by positional arguments too.
    val (namedModel, namedPreview) =
      createCustomSizePreviewModel("@Preview(\"Name\", \"Group\", 33, widthDp = 200, heightDp = 300, \"fr\")")
    withContext(Dispatchers.EDT) { namedModel.setValueAndDispatch("Device", "id:pixel_5") }
    assertEquals("@Preview(\"Name\", \"Group\", 33, locale = \"fr\", device = \"id:pixel_5\")", namedPreview.annotationText())
  }

  @Test
  fun fontScaleEditing() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    val model = getFirstModel(fileContent)
    val preview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, fixture.findFileInTempDir("Test.kt")).first()

    fun checkFontScaleChange(newValue: String, expectedPropertyValue: String) {
      val expectedTextValue = expectedPropertyValue + 'f'

      // When writing into model.properties is an operation that triggers [PsiCallParameterPropertyItem.runModification], this operation
      // runs a WriteAction that runs in EDT
      runModificationInEdtWaitAndDispatch { model.properties["", "fontScale"].value = newValue }
      assertEquals(expectedPropertyValue, model.properties["", "fontScale"].value)
      assertEquals("@Preview(fontScale = $expectedTextValue)", preview.annotationText())
    }

    checkFontScaleChange("1", "1.0")
    checkFontScaleChange("2.", "2.0")
    checkFontScaleChange("3.01", "3.01")
    checkFontScaleChange("4.0f", "4.0")
    checkFontScaleChange("5.0d", "5.0")
    checkFontScaleChange("6f", "6.0")
    checkFontScaleChange("7d", "7.0")
    checkFontScaleChange("8.f", "8.0")
  }

  @Test
  fun fontScaleValidation() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    val model = getFirstModel(fileContent)
    val fontScaleProperty = model.properties["", "fontScale"]

    fun assertValidationError(value: String) {
      val result = fontScaleProperty.editingSupport.validation(value)
      assertEquals(EditingErrorCategory.ERROR, result.first)
    }

    assertValidationError("123456789012345678901234567890")
    assertValidationError("11")
    assertValidationError("Infinity")
    assertValidationError("NaN")
  }

  @Test
  fun fontScaleEnumValues() {
    val fontScaleValues = FontScale.entries
    assertEquals(7, fontScaleValues.size)
    assertEquals("100% (1.0f)", fontScaleValues[0].display)
    assertEquals("1.0f", fontScaleValues[0].value)
    assertEquals("85% (0.85f)", fontScaleValues[1].display)
    assertEquals("0.85f", fontScaleValues[1].value)
    assertEquals("115% (1.15f)", fontScaleValues[2].display)
    assertEquals("1.15f", fontScaleValues[2].value)
    assertEquals("130% (1.3f)", fontScaleValues[3].display)
    assertEquals("1.3f", fontScaleValues[3].value)
    assertEquals("150% (1.5f)", fontScaleValues[4].display)
    assertEquals("1.5f", fontScaleValues[4].value)
    assertEquals("180% (1.8f)", fontScaleValues[5].display)
    assertEquals("1.8f", fontScaleValues[5].value)
    assertEquals("200% (2.0f)", fontScaleValues[6].display)
    assertEquals("2.0f", fontScaleValues[6].value)
  }

  @Test
  fun testUiModeImports() {
    runBlocking<Unit> {
      @Language("kotlin")
      val fileContent =
        """
        import $COMPOSABLE_ANNOTATION_FQN
        import $PREVIEW_TOOLING_PACKAGE.Preview

        @Composable
        @Preview
        fun Preview() {
        }
        """
          .trimIndent()

      fixture.stubConfigurationAsLibrary()
      val model = getFirstModel(fileContent)

      val uiModeProperty = model.properties["", "uiMode"]
      val nightModeOption = UiModeWithNightMaskEnumValue.NormalNightEnumValue
      val notNightOption = UiModeWithNightMaskEnumValue.NormalNotNightEnumValue

      runModificationInEdtWaitAndDispatch { notNightOption.select(uiModeProperty, {}) }
      assertEquals(
        """
        import android.content.res.Configuration
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        @Preview(uiMode = Configuration.UI_MODE_TYPE_NORMAL)
        fun Preview() {
        }
        """
          .trimIndent(),
        fixture.file.text,
      )

      runModificationInEdtWaitAndDispatch { nightModeOption.select(uiModeProperty, {}) }
      assertEquals(
        """
        import android.content.res.Configuration
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        @Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
        fun Preview() {
        }
        """
          .trimIndent(),
        fixture.file.text,
      )
    }
  }

  @Test
  fun testWallpaperImports() {
    runBlocking {
      @Language("kotlin")
      val fileContent =
        """
        import $COMPOSABLE_ANNOTATION_FQN
        import $PREVIEW_TOOLING_PACKAGE.Preview

        @Composable
        @Preview
        fun Preview() {
        }
        """
          .trimIndent()

      val model = getFirstModel(fileContent)
      runModificationInEdtWaitAndDispatch { Wallpaper.BLUE.select(model.properties["", "wallpaper"], {}) }
      assertEquals(
        """
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.tooling.preview.Wallpapers

        @Composable
        @Preview(wallpaper = Wallpapers.BLUE_DOMINATED_EXAMPLE)
        fun Preview() {
        }
        """
          .trimIndent(),
        fixture.file.text,
      )
    }
  }

  @Test
  fun showBackgroundEditing() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview
      fun PreviewNoParameters() {
      }
      """
        .trimIndent()

    val model = getFirstModel(fileContent)
    val preview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, fixture.findFileInTempDir("Test.kt")).first()

    // When the parameter is not present, BooleanPsiCallParameter will assign "false" instead of "null"
    assertEquals("false", model.properties["", "showBackground"].value)
    assertEquals("false", model.properties["", "showSystemUi"].value)

    fun checkShowBackgroundChange(newValue: String?, expectedPropertyValue: String?) {
      runModificationInEdtWaitAndDispatch { model.properties["", "showBackground"].value = newValue }
      assertEquals(expectedPropertyValue, model.properties["", "showBackground"].value)
      assertEquals("@Preview(showBackground = $expectedPropertyValue)", preview.annotationText())
    }

    fun checkShowBackgroundEmptyChange(newValue: String?, expectedPropertyValue: String?) {
      runModificationInEdtWaitAndDispatch { model.properties["", "showBackground"].value = newValue }
      assertEquals(expectedPropertyValue, model.properties["", "showBackground"].value)
      assertEquals("@Preview", preview.annotationText())
    }

    checkShowBackgroundChange("true", "true")
    checkShowBackgroundEmptyChange(null, null)
    checkShowBackgroundChange("false", "false")
    checkShowBackgroundEmptyChange("", null)
  }

  @Test
  fun `original order is preserved`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(fontScale = 1.0f, name = "MyPreview", apiLevel = 1)
      fun PreviewWithParameters() {
      }
      """
        .trimIndent()

    val model = getFirstModel(fileContent)

    val properties = model.properties.values.iterator()
    assertEquals("name", properties.next().name)
    assertEquals("group", properties.next().name)
    assertEquals("apiLevel", properties.next().name)
    assertEquals("widthDp", properties.next().name)
    assertEquals("heightDp", properties.next().name)
    assertEquals("locale", properties.next().name)
    assertEquals("fontScale", properties.next().name)
    assertEquals("showSystemUi", properties.next().name)
  }

  @Test
  fun testDevicePropertiesTracked() {
    val (testTracker, model) = simpleTrackingTestSetup()

    runModificationInEdtWaitAndDispatch {
      model.properties["", "Device"].value = "hello world"

      model.properties["", "Orientation"].value = "portrait"
      model.properties["", "Orientation"].value = "landscape"
      model.properties["", "Orientation"].value = "bad input"

      model.properties["", "Density"].value = "480" // XXHIGH
      model.properties["", "Density"].value = "470" // Close to XXHIGH
      model.properties["", "Density"].value = "320" // XHIGH
      model.properties["", "Density"].value = "10000" // Extremely high (XXXHIGH is closest)
      model.properties["", "Density"].value = "bad input"

      model.properties["", "DimensionUnit"].value = "dp"
      model.properties["", "DimensionUnit"].value = "px"
      model.properties["", "DimensionUnit"].value = "bad input"

      model.properties["", "Width"].value = "100"
      model.properties["", "Height"].value = "200"
    }

    assertEquals(14, testTracker.valuesRegistered.size)
    var index = 0
    // Device
    assertEquals(PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED, testTracker.valuesRegistered[index++])

    // Orientation
    assertEquals(PreviewPickerValue.ORIENTATION_PORTRAIT, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.ORIENTATION_LANDSCAPE, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE, testTracker.valuesRegistered[index++])

    // Density
    assertEquals(PreviewPickerValue.DENSITY_XX_HIGH, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.DENSITY_XX_HIGH, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.DENSITY_X_HIGH, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.DENSITY_XXX_HIGH, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE, testTracker.valuesRegistered[index++])

    // DimensionUnit
    assertEquals(PreviewPickerValue.UNIT_DP, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.UNIT_PIXELS, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.UNKNOWN_PREVIEW_PICKER_VALUE, testTracker.valuesRegistered[index++])

    // Width/Height
    assertEquals(PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED, testTracker.valuesRegistered[index++])
    assertEquals(PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED, testTracker.valuesRegistered[index])
  }

  @Test
  fun testTrackedValuesOfUiModeOptions() {
    val (testTracker, model) = simpleTrackingTestSetup()

    val uiModeProperty = model.properties["", "uiMode"]

    val nightModeOption = UiModeWithNightMaskEnumValue.NormalNightEnumValue
    val notNightOption = UiModeWithNightMaskEnumValue.NormalNotNightEnumValue

    // The Night/NotNight is not explicitly set
    val nightModeUndefined = UiModeWithNightMaskEnumValue.UndefinedEnumValue
    runModificationInEdtWaitAndDispatch {
      nightModeOption.select(uiModeProperty, {})
      notNightOption.select(uiModeProperty, {})
      nightModeUndefined.select(uiModeProperty, {})
    }

    // Only 2 registered as undefined will not be set, instead property will be removed
    assertEquals(2, testTracker.valuesRegistered.size)
    assertEquals(PreviewPickerValue.UI_MODE_NIGHT, testTracker.valuesRegistered[0])
    assertEquals(PreviewPickerValue.UI_MODE_NOT_NIGHT, testTracker.valuesRegistered[1])
  }

  @Test
  fun testDeviceTrackedPerModification() {
    // We need the sdk to be able to figure out devices set by ID, including the initial/default
    // device
    Sdks.addLatestAndroidSdk(projectRule.fixture.projectDisposable, module)
    val (testTracker, model) = simpleTrackingTestSetup()

    // Modifications under default device
    runModificationInEdtWaitAndDispatch {
      model.properties["", "name"].value = "my name 1"
      model.properties["", "Device"].value = "id:pixel"
    }
    // Modifications under pixel device
    runModificationInEdtWaitAndDispatch {
      model.properties["", "name"].value = "my name 2"
      model.properties["", "Device"].value = ReferencePhoneConfig.deviceSpec()
    }
    // Modifications under Reference Phone device
    runModificationInEdtWaitAndDispatch { model.properties["", "name"].value = "my name 3" }
    assertEquals(5, testTracker.devicesRegistered.size)
    assertEquals("pixel_5", testTracker.devicesRegistered[0]!!.id) // Default device
    assertEquals("pixel_5", testTracker.devicesRegistered[1]!!.id)
    assertEquals("pixel", testTracker.devicesRegistered[2]!!.id) // Pixel
    assertEquals("pixel", testTracker.devicesRegistered[3]!!.id)
    assertEquals("Custom", testTracker.devicesRegistered[4]!!.displayName) // Reference Phone Device
  }

  @RunsInEdt
  @Test
  fun `check that writeNewValue does not throw a ProhibitedAnalysisException`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
        import $COMPOSABLE_ANNOTATION_FQN
        import $PREVIEW_TOOLING_PACKAGE.Preview

        @Composable
        @Preview
        fun PreviewWithoutParameters() {
        }
        """
        .trimIndent()
    val model = getFirstModel(fileContent)

    val property = model.properties.values.find { it.name == "showSystemUi" } as PsiCallParameterPropertyItem

    model.addListener(FakePropertiesRefreshListener())

    // Test fails if this call throws a ProhibitedAnalysisException.
    property.writeNewValue("true", true, PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED)
  }

  @RunsInEdt
  @Test
  fun `test K2 crash if analyze is called into a write action`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
          import $COMPOSABLE_ANNOTATION_FQN
          import $PREVIEW_TOOLING_PACKAGE.Preview

          @Composable
          @Preview
          fun PreviewWithoutParameters() {
          }
          """
        .trimIndent()
    val model = getFirstModel(fileContent)

    val property = model.properties.values.find { it.name == "showSystemUi" } as PsiCallParameterPropertyItem

    model.addListener(FakePropertiesRefreshListener())

    runWriteAction {
      // Should not throw ProhibitedAnalysisException
      property.writeNewValue("true", true, PreviewPickerValue.UNSUPPORTED_OR_OPEN_ENDED)
    }
  }

  @RunsInEdt
  @Test
  fun `check that deleteParameter does not throw a ProhibitedAnalysisException`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
        import $COMPOSABLE_ANNOTATION_FQN
        import $PREVIEW_TOOLING_PACKAGE.Preview

        @Composable
        @Preview(
            name = "old-preview-name",
            showSystemUi = true,
            showBackground = true,
            device = "id:medium_phone"
        )
        fun PreviewWithParameters() {
        }
        """
        .trimIndent()
    val model = getFirstModel(fileContent)

    val property = model.properties.values.find { it.name == "name" } as PsiCallParameterPropertyItem

    model.addListener(FakePropertiesRefreshListener())

    // Test fails if this call throws a ProhibitedAnalysisException.
    assertEquals("old-preview-name", property.value)
    property.deleteParameter()
  }

  @RunsInEdt
  @Test
  fun `test K2 crash if analyze is called into a delete parameter`() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
          import $COMPOSABLE_ANNOTATION_FQN
          import $PREVIEW_TOOLING_PACKAGE.Preview

          @Composable
          @Preview(
              name = "old-preview-name",
              showSystemUi = true,
              showBackground = true,
              device = "id:medium_phone"
          )
          fun PreviewWithParameters() {
          }
          """
        .trimIndent()
    val model = getFirstModel(fileContent)

    val property = model.properties.values.find { it.name == "showSystemUi" } as PsiCallParameterPropertyItem

    model.addListener(FakePropertiesRefreshListener())

    runWriteAction {
      // Should not throw ProhibitedAnalysisException
      property.deleteParameter()
    }
  }

  private suspend fun assertUpdatingModelUpdatesPsiCorrectly(fileContent: String) {
    val file = fixture.configureByText("Test.kt", fileContent)
    val noParametersPreview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, file.virtualFile).first()
    val model =
      ReadAction.compute<PsiPropertiesModel, Throwable> {
        PreviewPickerPropertiesModel.fromPreviewElement(project, module, noParametersPreview.previewElementDefinition, NoOpTracker)
      }
    var expectedModificationsCountdown = 3
    model.addListener(
      object : PropertiesModelListener<PsiPropertyItem> {
        override fun propertyValuesChanged(model: PropertiesModel<PsiPropertyItem>, childElementChanges: Boolean) {
          expectedModificationsCountdown--
        }
      }
    )

    runModificationInEdtWaitAndDispatch {
      model.properties["", "name"].value = "NoHello"
      // Try to override our previous write. Only the last one should persist
      model.properties["", "name"].value = "Hello"

      // Clear values
      model.properties["", "group"].value = null
      model.properties["", "widthDp"].value = "    " // Blank value is the same as null value
      model.properties["", "Device"].value = null
    }
    assertEquals("@Preview(name = \"Hello\")", noParametersPreview.annotationText())

    runModificationInEdtWaitAndDispatch {
      model.properties["", "name"].value = null
      try {
        model.properties["", "notexists"].value = "3"
        fail("Nonexistent property should throw NoSuchElementException")
      } catch (expected: NoSuchElementException) {}
    }

    // Verify final values on model
    assertNull(model.properties["", "name"].value)
    assertNull(model.properties["", "group"].value)
    assertNull(model.properties["", "widthDp"].value)
    // Verify final state of file
    assertEquals("@Preview", noParametersPreview.annotationText())
    // Verify that every modification (setting, overwriting and deleting values) triggered the
    // listener
    assertEquals(0, expectedModificationsCountdown)
  }

  @RunsInEdt
  @Test
  fun testAsyncValueEvaluation() = runBlocking {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      private const val myName = "Async Name"

      @Composable
      @Preview(name = myName)
      fun Preview() {
      }
    """
        .trimIndent()
    val model = getFirstModel(fileContent) as PreviewPickerPropertiesModel
    val property = model.properties["", "name"] as PsiCallParameterPropertyItem

    // Reset cache to force async evaluation.
    // We use reflection because these fields are private/protected and intended for internal use,
    // but we need to manipulate them to test the async fallback path specifically.
    val fieldCachedValue = PsiCallParameterPropertyItem::class.java.getDeclaredField("cachedValue").apply { isAccessible = true }
    val fieldIsCachedValueValid =
      PsiCallParameterPropertyItem::class.java.getDeclaredField("isCachedValueValid").apply { isAccessible = true }

    fieldCachedValue.set(property, null)
    fieldIsCachedValueValid.set(property, false)

    val future = CompletableFuture<Unit>()
    model.addListener(
      object : PropertiesModelListener<PsiPropertyItem> {
        override fun propertyValuesChanged(model: PropertiesModel<PsiPropertyItem>, childElementChanges: Boolean) {
          future.complete(Unit)
        }
      }
    )

    // On EDT, first access should return null and trigger async update
    assertNull(property.value)

    PlatformTestUtil.waitForFuture(future, 5000)

    assertEquals("Async Name", property.value)
  }

  private fun simpleTrackingTestSetup(): Pair<TestTracker, PsiPropertiesModel> {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      @Preview(name = "Test")
      fun PreviewNoParameters() {
      }
    """
        .trimIndent()
    val testTracker = TestTracker()
    val model = runBlocking { getFirstModel(fileContent, testTracker) }
    return Pair(testTracker, model)
  }

  private suspend fun getFirstModel(fileContent: String, tracker: ComposePickerTracker = NoOpTracker): PsiPropertiesModel {
    val file = fixture.configureByText("Test.kt", fileContent)
    val preview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, file.virtualFile).first()
    ConfigurationManager.getOrCreateInstance(module)
    return ReadAction.compute<PsiPropertiesModel, Throwable> {
      PreviewPickerPropertiesModel.fromPreviewElement(project, module, preview.previewElementDefinition, tracker)
    }
  }

  /** Returns the model of a preview annotated with [previewAnnotation], and the preview element to read the annotation back. */
  private suspend fun createCustomSizePreviewModel(previewAnnotation: String): Pair<PsiPropertiesModel, PsiComposePreviewElement> {
    @Language("kotlin")
    val fileContent =
      """
      import $COMPOSABLE_ANNOTATION_FQN
      import $PREVIEW_TOOLING_PACKAGE.Preview

      @Composable
      $previewAnnotation
      fun CustomSizePreview() {
      }
      """
        .trimIndent()
    val model = getFirstModel(fileContent)
    val preview = AnnotationFilePreviewElementFinder.findPreviewElements(fixture.project, fixture.findFileInTempDir("Test.kt")).first()
    return model to preview
  }

  /** Sets the property [name] to [value] and dispatches the events, as hardware properties write the device parameter later on the EDT. */
  private fun PsiPropertiesModel.setValueAndDispatch(name: String, value: String?) {
    properties["", name].value = value
    dispatchAllEventsInIdeEventQueue()
  }

  /**
   * Executes the given [modification] on the EDT and waits for all events to be dispatched.
   *
   * This is necessary because `PsiCallParameterPropertyItem.runModification` uses `invokeLater` to schedule a `WriteAction`. By dispatching
   * all events, we ensure that the modification is applied before the test continues.
   *
   * When doing a writing operation in this test you should wrap such change into this function before an assertion or the test will fail.
   */
  private fun runModificationInEdtWaitAndDispatch(modification: () -> Unit) {
    runInEdtAndWait {
      modification()
      dispatchAllEventsInIdeEventQueue()
    }
  }
}

private class TestTracker : ComposePickerTracker {
  val valuesRegistered = mutableListOf<PreviewPickerValue>()
  val devicesRegistered = mutableListOf<Device?>()

  override fun registerModification(name: String, value: PreviewPickerValue, device: Device?) {
    valuesRegistered.add(value)
    devicesRegistered.add(device)
  }

  override fun pickerShown() {} // Not tested

  override fun pickerClosed() {} // Not tested

  override fun logUsageData() {} // Not tested
}

/**
 * This listener is going through all the properties and reads their values. However, whenever to read a property value we call
 * PsiCallParameterPropertyItem.value. Whenever we get [PsiCallParameterPropertyItem.value] the [analyze] function is called. Because it is
 * forbidden to call a write action within an [analyze] a ProhibitedAnalysisException is thrown.
 */
private class FakePropertiesRefreshListener : PropertiesModelListener<PsiPropertyItem> {

  override fun propertyValuesChanged(model: PropertiesModel<PsiPropertyItem>, childElementChanges: Boolean) {
    // We simulate a refresh of all the properties, getting their values to trigger analyze()
    model.properties.values.forEach { it.value }
  }
}
