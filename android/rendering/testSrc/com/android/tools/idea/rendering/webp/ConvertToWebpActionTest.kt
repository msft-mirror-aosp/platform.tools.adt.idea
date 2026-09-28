/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.rendering.webp

import com.android.testutils.waitForCondition
import com.google.common.truth.Truth.assertThat
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent.createEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.update.MergingUpdateQueue
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JSlider
import org.jetbrains.android.AndroidTestCase
import org.junit.Assume.assumeTrue

class ConvertToWebpActionTest : AndroidTestCase() {
  val notifications = mutableListOf<Notification>()

  override fun setUp() {
    super.setUp()
    project.messageBus
      .connect(testRootDisposable)
      .subscribe(
        Notifications.TOPIC,
        object : Notifications {
          override fun notify(notification: Notification) {
            notifications.add(notification)
          }
        },
      )
  }

  fun testConvert() {
    // Regression test for issue 226893
    // Ensure that images that are too large to encode are encoded anyway if the user asked for it
    val settings = WebpConversionSettings()
    settings.previewConversion = false
    settings.skipTransparentImages = false
    settings.skipLargerImages = true
    settings.quality = 90
    val mdpi = myFixture.copyFileToProject("webp/ic_action_name-mdpi.png", "res/drawable-mdpi/ic_action_name.png")
    val xhdpi = myFixture.copyFileToProject("webp/ic_action_name-xhdpi.png", "res/drawable-xhdpi/ic_action_name.png")
    val mdpiFolder = mdpi.parent
    val xhdpiFolder = xhdpi.parent
    val action = ConvertToWebpAction()
    action.convert(project, settings, true, listOf(mdpi, xhdpi))

    waitForCondition(2, TimeUnit.SECONDS) { notifications.isNotEmpty() }
    assertThat(notifications).hasSize(1)
    assertThat(notifications[0].content)
      .isEqualTo("1 file was converted<br/>55 bytes saved<br>1 file was skipped because there was no net space saving")
    // Check that we only converted the xhdpi image (the mdpi image encodes to a larger image)
    assertThat(xhdpiFolder.findChild("ic_action_name.png")).isNull()
    assertThat(xhdpiFolder.findChild("ic_action_name.webp")).isNotNull()
    assertThat(mdpiFolder.findChild("ic_action_name.png")).isNotNull()
    assertThat(mdpiFolder.findChild("ic_action_name.webp")).isNull()
  }

  fun testIncludeLargerImages() {
    // Regression test for issue 226893
    // Ensure that images that are too large to encode are encoded anyway if the user asked for it
    val settings = WebpConversionSettings()
    settings.previewConversion = false
    settings.skipTransparentImages = false
    settings.skipLargerImages = false
    settings.quality = 100
    val mdpi = myFixture.copyFileToProject("webp/ic_action_name-mdpi.png", "res/drawable-mdpi/ic_action_name.png")
    // test conversion of a transparent gray issue
    val gray = myFixture.copyFileToProject("webp/ic_arrow_back.png", "res/drawable-mdpi/ic_arrow_back.png")
    val xhdpi = myFixture.copyFileToProject("webp/ic_action_name-xhdpi.png", "res/drawable-xhdpi/ic_action_name.png")
    val mdpiFolder = mdpi.parent
    val xhdpiFolder = xhdpi.parent
    val action = ConvertToWebpAction()
    action.convert(project, settings, true, listOf(mdpi, xhdpi, gray))

    waitForCondition(2, TimeUnit.SECONDS) { notifications.isNotEmpty() }
    assertThat(notifications).hasSize(1)
    assertThat(notifications[0].content).isEqualTo("3 files were converted<br/>size increased by 139 bytes")
    // Check that we converted both images
    assertThat(xhdpiFolder.findChild("ic_action_name.png")).isNull()
    assertThat(xhdpiFolder.findChild("ic_action_name.webp")).isNotNull()
    assertThat(mdpiFolder.findChild("ic_action_name.png")).isNull()
    assertThat(mdpiFolder.findChild("ic_action_name.webp")).isNotNull()
    assertThat(mdpiFolder.findChild("ic_arrow_back.png")).isNull()
    assertThat(mdpiFolder.findChild("ic_arrow_back.webp")).isNotNull()
  }

  fun testVisibility() {
    val action = ConvertToWebpAction()
    val resFolder = myFixture.findFileInTempDir("res")
    val pngFile = myFixture.addFileToProject("folder/image.png", "").virtualFile
    val assetFolder = myFixture.copyDirectoryToProject("webp", "assets")
    val nonResFolder = myFixture.copyDirectoryToProject("webp", "folder")
    val presentation = Presentation()
    fun testDataContext(files: Array<VirtualFile>): DataContext =
      SimpleDataContext.builder().add(CommonDataKeys.PROJECT, myFixture.project).add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files).build()
    fun createTestEvent(files: Array<VirtualFile>) = createEvent(testDataContext(files), presentation, "", ActionUiKind.NONE, null)

    action.update(createTestEvent(VirtualFile.EMPTY_ARRAY))
    assertFalse(presentation.isVisible)

    action.update(createTestEvent(arrayOf(resFolder)))
    assertTrue(presentation.isVisible)

    action.update(createTestEvent(arrayOf(pngFile)))
    assertTrue(presentation.isVisible)

    action.update(createTestEvent(arrayOf(assetFolder)))
    assertTrue(presentation.isVisible)

    action.update(createTestEvent(arrayOf(nonResFolder)))
    assertFalse(presentation.isVisible)
  }

  fun testWebpConversionDialogSettings() {
    runInEdtAndWait {
      // Test minSdkVersion < 18 behavior (skipTransparentImages is forced to true when minSdkVersion < 18)
      val defaultSettings15 = WebpConversionSettings().apply { skipTransparentImages = false }
      val dialog15 = WebpConversionDialog(project, /* minSdkVersion= */ 15, defaultSettings15, /* singleFile= */ false)
      try {
        val settingsResult15 = WebpConversionSettings().apply { skipTransparentImages = false }
        dialog15.toSettings(settingsResult15)
        assertTrue(settingsResult15.skipTransparentImages)
      } finally {
        Disposer.dispose(dialog15.disposable)
      }

      // Test minSdkVersion >= 18 behavior (skipTransparentImages can be false)
      val defaultSettings18 = WebpConversionSettings()
      defaultSettings18.skipTransparentImages = false
      val dialog18 = WebpConversionDialog(project, /* minSdkVersion= */ 18, defaultSettings18, /* singleFile= */ false)
      try {
        val settingsResult18 = WebpConversionSettings()
        dialog18.toSettings(settingsResult18)
        assertFalse(settingsResult18.skipTransparentImages)
      } finally {
        Disposer.dispose(dialog18.disposable)
      }

      // Test modifying WebpConversionSettings and verifying round-trip binding
      val customSettings = WebpConversionSettings()
      customSettings.lossless = true
      customSettings.quality = 90
      customSettings.previewConversion = false
      customSettings.skipNinePatches = true
      customSettings.skipLargerImages = true
      customSettings.skipTransparentImages = true

      val customDialog = WebpConversionDialog(project, /* minSdkVersion= */ 18, customSettings, /* singleFile= */ false)
      try {
        val roundTripSettings = WebpConversionSettings()
        customDialog.toSettings(roundTripSettings)
        assertTrue(roundTripSettings.lossless)
        assertEquals(90, roundTripSettings.quality)
        assertFalse(roundTripSettings.previewConversion)
        assertTrue(roundTripSettings.skipNinePatches)
        assertTrue(roundTripSettings.skipLargerImages)
        assertTrue(roundTripSettings.skipTransparentImages)

        // Test UI component interaction via reflection
        val losslessButton = customDialog.getPrivateField<JBRadioButton>("myLosslessButton")
        val lossyButton = customDialog.getPrivateField<JBRadioButton>("myLossyButton")
        val qualitySlider = customDialog.getPrivateField<JSlider>("myQualitySlider")
        val qualityField = customDialog.getPrivateField<JBTextField>("myQualityField")

        // When lossless is selected, quality slider/field should be disabled
        assertTrue(losslessButton.isSelected)
        assertFalse(lossyButton.isSelected)
        assertFalse(qualitySlider.isEnabled)
        assertFalse(qualityField.isEnabled)

        // Switch to lossy encoding
        lossyButton.isSelected = true
        customDialog.actionPerformed(null)
        assertTrue(qualitySlider.isEnabled)
        assertTrue(qualityField.isEnabled)

        // Change quality field text and verify slider and resulting settings update
        qualityField.text = "65"
        assertEquals(65, qualitySlider.value)
        val modifiedSettings = WebpConversionSettings()
        customDialog.toSettings(modifiedSettings)
        assertFalse(modifiedSettings.lossless)
        assertEquals(65, modifiedSettings.quality)

        // Change quality slider and verify text and resulting settings update
        qualitySlider.value = 45
        assertEquals("45", qualityField.text)
        customDialog.toSettings(modifiedSettings)
        assertEquals(45, modifiedSettings.quality)
      } finally {
        Disposer.dispose(customDialog.disposable)
      }
    }
  }

  fun testWebpPreviewDialog() {
    assumeTrue(ImageIO.getImageWritersByFormatName("webp").hasNext())

    val settings = WebpConversionSettings()
    settings.quality = 80
    settings.lossless = false
    settings.previewConversion = true
    settings.skipTransparentImages = false
    settings.skipLargerImages = false

    val xhdpi = myFixture.copyFileToProject("webp/ic_action_name-xhdpi.png", "res/drawable-xhdpi/ic_action_name.png")
    val convertedFile = WebpConvertedFile.create(xhdpi, settings)
    assertNotNull(convertedFile)
    val converted = convertedFile!!.convert(settings)
    assertTrue(converted)
    assertNotNull(convertedFile.encoded)
    assertTrue(convertedFile.encoded.isNotEmpty())

    runInEdtAndWait {
      val dialog = WebpPreviewDialog(project, settings, listOf(convertedFile))
      try {
        val pngSizeLabel = dialog.getPrivateField<JBLabel>("myPngSizeLabel")
        val webpSizeLabel = dialog.getPrivateField<JBLabel>("myWebpSizeLabel")
        val fileIndexLabel = dialog.getPrivateField<JBLabel>("myFileIndexLabel")
        val qualitySlider = dialog.getPrivateField<JSlider>("myQualitySlider")

        val renderingQueue = dialog.getPrivateField<MergingUpdateQueue>("myRenderingQueue")
        renderingQueue.isPassThrough = true
        renderingQueue.flush()
        waitForCondition(2, TimeUnit.SECONDS) {
          PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
          pngSizeLabel.text.isNotEmpty()
        }

        val sourceImage = convertedFile.sourceImage
        assertNotNull(sourceImage)
        val encodedImage = convertedFile.encodedImage
        assertNotNull(encodedImage)
        assertEquals(sourceImage!!.width, encodedImage!!.width)
        assertEquals(sourceImage.height, encodedImage.height)

        assertThat(pngSizeLabel.text).contains("bytes")
        assertThat(webpSizeLabel.text).contains("bytes")
        assertThat(webpSizeLabel.text).contains("% of original size")
        assertThat(fileIndexLabel.text).contains("1/1")
        assertEquals(80, qualitySlider.value)

        // Adjust quality in dialog and verify re-encoding
        qualitySlider.value = 50
        renderingQueue.flush()
        assertEquals(50, settings.quality)
        assertFalse(settings.lossless)

        // Paint preview component to verify image rendering
        val previewComponent = dialog.getPrivateField<JComponent>("myPreviewImage")
        previewComponent.setSize(600, 400)
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
          previewComponent.paint(g)
        } finally {
          g.dispose()
        }
      } finally {
        Disposer.dispose(dialog.disposable)
      }
    }
  }

  private inline fun <reified T> Any.getPrivateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
