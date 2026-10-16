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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.emulation.control.incubating.AnimationState
import com.android.emulation.control.incubating.Poster
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.protobuf.BytesValue
import com.android.tools.idea.protobuf.StringValue
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.file.registerFakeFileChooserFactory
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExtendableTextField
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JSlider
import javax.swing.JTextField
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [CameraDialog]. */
@RunsInEdt
class CameraDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      EdtRule(),
      HeadlessDialogRule(),
    )
  @get:Rule val tempDirRule = TemporaryDirectory()

  private lateinit var fakeEmulator: FakeEmulator
  private lateinit var emulatorController: EmulatorController

  @Before
  fun setUp() {
    val testAvdFolder = FakeEmulator.createPhoneAvd(emulatorRule.avdRoot)
    fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
    fakeEmulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    assertThat(emulators).hasSize(1)
    emulatorController = emulators.first()
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  }

  @Test
  fun testInitialStateFromDefaults() {
    val dialog = CameraDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val wallSizeSlider = ui.getComponent<JSlider> { it.name == "wallSizeSlider" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val tableSizeSlider = ui.getComponent<JSlider> { it.name == "tableSizeSlider" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(wallPreview.icon).isNull()
    assertThat(wallFileNameField.text).isEmpty()
    assertThat(wallFileNameField.extensions).hasSize(1)
    assertThat(wallSizeSlider.isShowing).isFalse()
    assertThat(tablePreview.text).isEqualTo("No image applied")
    assertThat(tablePreview.icon).isNull()
    assertThat(tableFileNameField.text).isEmpty()
    assertThat(tableFileNameField.extensions).hasSize(1)
    assertThat(tableSizeSlider.isShowing).isFalse()
    assertThat(enableAnimationsCheckBox.isSelected).isTrue()

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInitialStateFromEmulator() {
    val wallImagePath = createTestImageFile("wall_poster.png", Color.BLUE)
    val wallImageBytes = Files.readAllBytes(wallImagePath)
    fakeEmulator.posters =
      mapOf(
        "wall" to
          Poster.newBuilder()
            .setName("wall")
            .setMinWidth(0.2f)
            .setMaxWidth(2.0f)
            .setScale(0.6f)
            .setFileName(StringValue.of(wallImagePath.toString()))
            .setImage(BytesValue.of(ByteString.copyFrom(wallImageBytes)))
            .build(),
        "table" to Poster.newBuilder().setName("table").setMinWidth(0.2f).setMaxWidth(1.0f).setScale(1.0f).build(),
      )
    fakeEmulator.animationState = AnimationState.newBuilder().setTvOn(false).build()

    val dialog = CameraDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<JTextField> { it.name == "wallFileNameField" }
    val wallSizeSlider = ui.getComponent<JSlider> { it.name == "wallSizeSlider" }
    val wallSizeField = ui.getComponent<JTextField> { it.name == "wallSizeField" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    waitForCondition(5.seconds) {
      wallPreview.icon != null &&
        wallFileNameField.text == wallImagePath.fileName.toString() &&
        wallSizeSlider.value == 120 &&
        wallSizeField.text == "1.20 m" &&
        tablePreview.icon == null &&
        !enableAnimationsCheckBox.isSelected
    }

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testDialogUiInteraction() {
    val wallImagePath = createTestImageFile("wall_poster.png", Color.RED)
    val virtualFile = mock<VirtualFile>()
    whenever(virtualFile.path).thenReturn(wallImagePath.toString())
    projectRule.disposable.registerFakeFileChooserFactory(virtualFile)

    val dialog = CameraDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val wallSizeSlider = ui.getComponent<JSlider> { it.name == "wallSizeSlider" }
    val wallSizeField = ui.getComponent<JTextField> { it.name == "wallSizeField" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    // Add wall poster image via browse extension.
    wallFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()

    assertThat(wallPreview.icon).isNotNull()
    assertThat(wallFileNameField.text).isEqualTo(wallImagePath.fileName.toString())
    assertThat(wallFileNameField.extensions).hasSize(2)
    assertThat(wallSizeSlider.isShowing).isTrue()
    assertThat(wallSizeField.text).isEqualTo("2.00 m")

    waitForCondition(5.seconds) {
      fakeEmulator.posters["wall"]?.fileName?.value == wallImagePath.toString()
    }

    // Adjust wall poster size slider.
    wallSizeSlider.value = 150
    assertThat(wallSizeField.text).isEqualTo("1.50 m")

    waitForCondition(5.seconds) {
      fakeEmulator.posters["wall"]?.scale == 0.75f
    }

    // Remove wall poster image.
    wallFileNameField.extensions.first { it.tooltip == "Remove" }.actionOnClick.run()
    assertThat(wallPreview.icon).isNull()
    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(wallFileNameField.text).isEmpty()
    assertThat(wallFileNameField.extensions).hasSize(1)
    assertThat(wallSizeSlider.isShowing).isFalse()

    waitForCondition(5.seconds) {
      fakeEmulator.posters["wall"]?.hasFileName() == false
    }

    // Toggle animations checkbox.
    ui.clickOn(enableAnimationsCheckBox)
    assertThat(enableAnimationsCheckBox.isSelected).isFalse()

    waitForCondition(5.seconds) {
      !fakeEmulator.animationState.tvOn
    }

    ui.clickOn(enableAnimationsCheckBox)
    assertThat(enableAnimationsCheckBox.isSelected).isTrue()

    waitForCondition(5.seconds) {
      fakeEmulator.animationState.tvOn
    }

    val closeButton = rootPane.defaultButton
    assertThat(closeButton.text).isEqualTo("Close")
    ui.clickOn(closeButton)
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testInvalidImageFileAndDropParsing() {
    val invalidPath = tempDirRule.newPath("invalid.png")
    Files.writeString(invalidPath, "not a valid image")
    val virtualFile = mock<VirtualFile>()
    whenever(virtualFile.path).thenReturn(invalidPath.toString())
    projectRule.disposable.registerFakeFileChooserFactory(virtualFile)

    val dialog = CameraDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }

    tableFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()
    assertThat(tablePreview.icon).isNull()
    assertThat(tablePreview.text).isEqualTo("No image applied")

    val validPng = createTestImageFile("table.png", Color.GREEN)
    val validEvent = createDragEvent(listOf(validPng.toFile()))
    assertThat(CameraDialog.getDroppedImageFile(validEvent)).isEqualTo(validPng)

    val unsupportedTxt = tempDirRule.newPath("notes.txt").also { Files.writeString(it, "hello") }
    val unsupportedEvent = createDragEvent(listOf(unsupportedTxt.toFile()))
    assertThat(CameraDialog.getDroppedImageFile(unsupportedEvent)).isNull()

    ui.clickOn(rootPane.defaultButton)
    assertThat(dialog.isShowing).isFalse()
  }

  private fun createTestImageFile(fileName: String, color: Color): Path {
    val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = color
    g.fillRect(0, 0, 40, 30)
    g.dispose()
    val path = tempDirRule.newPath(fileName)
    ImageIO.write(image, "png", path.toFile())
    return path
  }

  private fun createDragEvent(files: List<java.io.File>): com.intellij.ide.dnd.DnDEvent {
    val transferableWrapper = mock<com.intellij.ide.dnd.TransferableWrapper>()
    whenever(transferableWrapper.asFileList()).thenReturn(files)
    val event = mock<com.intellij.ide.dnd.DnDEvent>()
    whenever(event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(true)
    whenever(event.attachedObject).thenReturn(transferableWrapper)
    return event
  }
}
