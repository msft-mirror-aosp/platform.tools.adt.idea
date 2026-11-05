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
import com.android.emulation.control.incubating.VirtualSceneServiceGrpc
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.idea.io.grpc.Status
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
import com.intellij.testFramework.PlatformTestUtil
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
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.JButton
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
    val wallImagePath = createTestImageFile("wall_poster.png", Color.BLUE)
    val virtualFile = mock<VirtualFile>()
    whenever(virtualFile.path).thenReturn(wallImagePath.toString())
    projectRule.disposable.registerFakeFileChooserFactory(virtualFile)

    fakeEmulator.pauseGrpc()
    val dialog = CameraDialog(emulatorController).show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val wallSizeSlider = ui.getComponent<JSlider> { it.name == "wallSizeSlider" }
    val wallSizeField = ui.getComponent<JTextField> { it.name == "wallSizeField" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val tableSizeSlider = ui.getComponent<JSlider> { it.name == "tableSizeSlider" }
    val tableSizeField = ui.getComponent<JTextField> { it.name == "tableSizeField" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    // Poster editing controls are disabled before receiving initial poster state from the emulator.
    assertThat(wallPreview.isEnabled).isFalse()
    assertThat(wallPreview.text).isNull()
    assertThat(wallPreview.icon).isNull()
    assertThat(wallFileNameField.isEnabled).isFalse()
    assertThat(wallFileNameField.text).isEmpty()
    assertThat(wallSizeSlider.isEnabled).isFalse()
    assertThat(wallSizeField.isEnabled).isFalse()
    assertThat(wallSizeSlider.isShowing).isFalse()
    assertThat(tablePreview.isEnabled).isFalse()
    assertThat(tablePreview.text).isNull()
    assertThat(tablePreview.icon).isNull()
    assertThat(tableFileNameField.isEnabled).isFalse()
    assertThat(tableFileNameField.text).isEmpty()
    assertThat(tableSizeSlider.isEnabled).isFalse()
    assertThat(tableSizeField.isEnabled).isFalse()
    assertThat(tableSizeSlider.isShowing).isFalse()
    assertThat(enableAnimationsCheckBox.isEnabled).isFalse()
    assertThat(enableAnimationsCheckBox.isSelected).isTrue()

    // Editing actions are ignored while poster state is still unknown.
    wallFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()
    assertThat(wallFileNameField.text).isEmpty()
    assertThat(wallPreview.icon).isNull()

    fakeEmulator.resumeGrpc()
    waitForCondition(5.seconds) {
      wallFileNameField.isEnabled && tableFileNameField.isEnabled && enableAnimationsCheckBox.isEnabled
    }

    assertThat(wallPreview.isEnabled).isTrue()
    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(wallPreview.icon).isNull()
    assertThat(wallFileNameField.text).isEmpty()
    assertThat(wallFileNameField.extensions).hasSize(1)
    assertThat(wallSizeSlider.isEnabled).isTrue()
    assertThat(wallSizeField.isEnabled).isTrue()
    assertThat(wallSizeSlider.isShowing).isFalse()
    assertThat(tablePreview.isEnabled).isTrue()
    assertThat(tablePreview.text).isEqualTo("No image applied")
    assertThat(tablePreview.icon).isNull()
    assertThat(tableFileNameField.text).isEmpty()
    assertThat(tableFileNameField.extensions).hasSize(1)
    assertThat(tableSizeSlider.isEnabled).isTrue()
    assertThat(tableSizeField.isEnabled).isTrue()
    assertThat(tableSizeSlider.isShowing).isFalse()
    assertThat(enableAnimationsCheckBox.isEnabled).isTrue()
    assertThat(enableAnimationsCheckBox.isSelected).isTrue()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
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

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testEmbeddedImageWithoutFileName() {
    val tableImageBytes = Files.readAllBytes(createTestImageFile("table_poster.png", Color.GREEN))
    fakeEmulator.posters =
      mapOf(
        "wall" to Poster.newBuilder().setName("wall").setMinWidth(0.2f).setMaxWidth(2.0f).setScale(1.0f).build(),
        "table" to
          Poster.newBuilder()
            .setName("table")
            .setMinWidth(0.2f)
            .setMaxWidth(1.0f)
            .setScale(0.557f)
            .setImage(BytesValue.of(ByteString.copyFrom(tableImageBytes)))
            .build(),
      )
    fakeEmulator.clearGrpcCallLog()

    val cameraDialog = CameraDialog(emulatorController)
    fakeEmulator.getNextGrpcCall(5.seconds) { it.methodName.endsWith("/listPosters") }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val dialog = cameraDialog.show()
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val tableSizeSlider = ui.getComponent<JSlider> { it.name == "tableSizeSlider" }
    val tableSizeField = ui.getComponent<JTextField> { it.name == "tableSizeField" }

    waitForCondition(5.seconds) {
      tablePreview.icon != null &&
        tableFileNameField.text.isEmpty() &&
        tableFileNameField.extensions.size == 2 &&
        tableSizeSlider.isShowing &&
        tableSizeSlider.value == 56 &&
        tableSizeField.text == "0.56 m"
    }
    assertThat(fakeEmulator.posters["table"]?.scale).isEqualTo(0.557f)
    assertThat(fakeEmulator.grpcCallLog.none { it.methodName.endsWith("/setPoster") }).isTrue()

    tableFileNameField.extensions.first { it.tooltip == "Remove" }.actionOnClick.run()
    assertThat(tablePreview.icon).isNull()
    assertThat(tablePreview.text).isEqualTo("No image applied")
    assertThat(tableFileNameField.extensions).hasSize(1)
    assertThat(tableSizeSlider.isShowing).isFalse()

    waitForCondition(5.seconds) {
      fakeEmulator.posters["table"]?.hasImage() == false
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
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

    waitForCondition(5.seconds) { wallFileNameField.isEnabled && enableAnimationsCheckBox.isEnabled }

    // Add wall poster image via browse extension.
    wallFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()

    waitForCondition(5.seconds) { wallPreview.icon != null }
    val initialIcon = wallPreview.icon
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
    assertThat(wallPreview.icon).isSameAs(initialIcon)

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

    val closeButton = ui.getComponent<JButton> { it.text == "Close" }
    assertThat(rootPane.defaultButton).isNull()
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

    waitForCondition(5.seconds) { tableFileNameField.isEnabled }

    tableFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()
    waitForCondition(5.seconds) { hasValidationError(ui, invalidPath.fileName.toString()) }
    assertThat(tablePreview.icon).isNull()
    assertThat(tablePreview.text).isEqualTo("No image applied")

    val validPng = createTestImageFile("table.png", Color.GREEN)
    val validEvent = createDragEvent(listOf(validPng.toFile()))
    assertThat(CameraDialog.getDroppedImageFile(validEvent)).isEqualTo(validPng)

    val unsupportedTxt = tempDirRule.newPath("notes.txt").also { Files.writeString(it, "hello") }
    val unsupportedEvent = createDragEvent(listOf(unsupportedTxt.toFile()))
    assertThat(CameraDialog.getDroppedImageFile(unsupportedEvent)).isNull()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testListPostersFailureRecovery() {
    val wallImagePath = createTestImageFile("wall_poster.png", Color.BLUE)
    val virtualFile = mock<VirtualFile>()
    whenever(virtualFile.path).thenReturn(wallImagePath.toString())
    projectRule.disposable.registerFakeFileChooserFactory(virtualFile)

    fakeEmulator.failGrpcCall(VirtualSceneServiceGrpc.getListPostersMethod(), Status.INTERNAL)
    fakeEmulator.pauseGrpc()
    val dialog = CameraDialog(emulatorController).show()
    val ui = FakeUi(dialog.rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    assertThat(wallPreview.isEnabled).isFalse()
    assertThat(wallPreview.text).isNull()
    assertThat(enableAnimationsCheckBox.isEnabled).isFalse()

    fakeEmulator.resumeGrpc()
    waitForCondition(5.seconds) {
      wallFileNameField.isEnabled &&
        tableFileNameField.isEnabled &&
        enableAnimationsCheckBox.isEnabled &&
        hasValidationError(ui, "Unable to load current poster settings")
    }

    assertThat(wallPreview.isEnabled).isTrue()
    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(tablePreview.isEnabled).isTrue()
    assertThat(tablePreview.text).isEqualTo("No image applied")
    assertThat(enableAnimationsCheckBox.isEnabled).isTrue()

    // Selecting a valid image clears the error and applies the new poster.
    wallFileNameField.extensions.first { it.tooltip == "Select an image file" }.actionOnClick.run()
    waitForCondition(5.seconds) {
      wallPreview.icon != null &&
        fakeEmulator.posters["wall"]?.fileName?.value == wallImagePath.toString() &&
        !hasValidationError(ui, "Unable to load current poster settings")
    }

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testListPostersFailureBeforeShow() {
    fakeEmulator.failGrpcCall(VirtualSceneServiceGrpc.getListPostersMethod(), Status.INTERNAL)
    fakeEmulator.clearGrpcCallLog()

    val cameraDialog = CameraDialog(emulatorController)
    fakeEmulator.getNextGrpcCall(5.seconds) { it.methodName == VirtualSceneServiceGrpc.getListPostersMethod().fullMethodName }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val dialog = cameraDialog.show()
    val ui = FakeUi(dialog.rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    waitForCondition(5.seconds) {
      wallFileNameField.isEnabled &&
        tableFileNameField.isEnabled &&
        enableAnimationsCheckBox.isEnabled &&
        hasValidationError(ui, "Unable to load current poster settings")
    }

    assertThat(wallPreview.isEnabled).isTrue()
    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(tablePreview.isEnabled).isTrue()
    assertThat(tablePreview.text).isEqualTo("No image applied")
    assertThat(enableAnimationsCheckBox.isEnabled).isTrue()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testLargePosterImageExceedingScreenshotChannelLimit() {
    val initialMaxInboundMessageSize = emulatorController.maxInboundMessageSize
    fakeEmulator.clearGrpcCallLog()
    val smallPngBytes = Files.readAllBytes(createTestImageFile("large_wall.png", Color.MAGENTA))
    // Exceeds twice the default phone channel maxInboundMessageSize (1440 * 2960 * 3 + 100 = 12,787,300 bytes),
    // requiring two maxInboundMessageSize doublings before listPosters succeeds.
    val largeImageBytes = ByteArray(30 * 1024 * 1024)
    smallPngBytes.copyInto(largeImageBytes)
    fakeEmulator.posters =
      mapOf(
        "wall" to
          Poster.newBuilder()
            .setName("wall")
            .setMinWidth(0.2f)
            .setMaxWidth(2.0f)
            .setScale(0.8f)
            .setImage(BytesValue.of(ByteString.copyFrom(largeImageBytes)))
            .build(),
        "table" to Poster.newBuilder().setName("table").setMinWidth(0.2f).setMaxWidth(1.0f).setScale(1.0f).build(),
      )

    val dialog = CameraDialog(emulatorController).show()
    val ui = FakeUi(dialog.rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val tablePreview = ui.getComponent<JBLabel> { it.name == "tableImagePreview" }

    waitForCondition(5.seconds) {
      wallPreview.icon != null && tablePreview.text == "No image applied"
    }
    assertThat(fakeEmulator.grpcCallLog.count { it.methodName == VirtualSceneServiceGrpc.getListPostersMethod().fullMethodName })
      .isEqualTo(3)
    assertThat(emulatorController.maxInboundMessageSize).isEqualTo(initialMaxInboundMessageSize * 4)
    assertThat(hasValidationError(ui, "Unable to load current poster settings")).isFalse()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testListPostersPersistentResourceExhaustedTerminates() {
    fakeEmulator.failGrpcCall(VirtualSceneServiceGrpc.getListPostersMethod(), Status.RESOURCE_EXHAUSTED)

    val dialog = CameraDialog(emulatorController).show()
    val ui = FakeUi(dialog.rootPane)

    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }
    val enableAnimationsCheckBox = ui.getComponent<JBCheckBox> { it.name == "enableAnimationsCheckBox" }

    waitForCondition(5.seconds) {
      wallFileNameField.isEnabled &&
        tableFileNameField.isEnabled &&
        enableAnimationsCheckBox.isEnabled &&
        hasValidationError(ui, "Unable to load current poster settings")
    }
    assertThat(emulatorController.maxInboundMessageSize).isEqualTo(Int.MAX_VALUE)

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
    assertThat(dialog.isShowing).isFalse()
  }

  @Test
  fun testListPostersRetryOnChannelRecreation() {
    val calls = AtomicInteger()
    fakeEmulator.setGrpcCallHandler(VirtualSceneServiceGrpc.getListPostersMethod()) {
      if (calls.incrementAndGet() == 1) {
        emulatorController.connectGrpcOrIncreaseMaxInboundMessageSize(20 * 1024 * 1024)
        Status.CANCELLED
      } else {
        Status.OK
      }
    }

    val dialog = CameraDialog(emulatorController).show()
    val ui = FakeUi(dialog.rootPane)

    val wallPreview = ui.getComponent<JBLabel> { it.name == "wallImagePreview" }
    val wallFileNameField = ui.getComponent<ExtendableTextField> { it.name == "wallFileNameField" }
    val tableFileNameField = ui.getComponent<ExtendableTextField> { it.name == "tableFileNameField" }

    waitForCondition(5.seconds) { wallFileNameField.isEnabled && tableFileNameField.isEnabled }

    assertThat(calls.get()).isEqualTo(2)
    assertThat(wallPreview.text).isEqualTo("No image applied")
    assertThat(hasValidationError(ui, "Unable to load current poster settings")).isFalse()

    ui.clickOn(ui.getComponent<JButton> { it.text == "Close" })
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
