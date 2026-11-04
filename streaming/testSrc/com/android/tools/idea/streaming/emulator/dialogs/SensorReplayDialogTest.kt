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

import com.android.emulator.control.SensorSession
import com.android.emulator.control.SensorSession.SensorRecord
import com.android.emulator.control.SensorSession.SensorRecord.CarPropertyValue
import com.android.emulator.control.SensorSession.SessionMetadata
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.PortableUiFontRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.protobuf.TextFormat
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.dialogs.VhalPropertyConstants.VehicleProperty
import com.google.common.truth.Truth.assertThat
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl
import com.intellij.openapi.fileChooser.ex.FileSystemTreeImpl
import com.intellij.openapi.fileChooser.impl.FileChooserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [SensorReplayDialog]. */
@RunsInEdt
class SensorReplayDialogTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()

  @get:Rule
  val ruleChain =
    RuleChain(
      emulatorRule,
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      EdtRule(),
      PortableUiFontRule(),
      HeadlessDialogRule(),
    )

  @Before
  fun setUp() {
    PropertiesComponent.getInstance().setList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY, null)
  }

  @After
  fun tearDown() {
    PropertiesComponent.getInstance().setList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY, null)
  }

  @Test
  fun testImportMetadataTimelineSelectionAndRemoval() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val emptyStatePanel = ui.getComponent<JPanel> { it.name == "emptyStatePanel" }
    val detailsCardPanel = ui.getComponent<JPanel> { it.name == "detailsCardPanel" }
    val detailsPanel = ui.getComponent<JPanel> { it.name == "sessionDetailsPanel" }
    val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    val timelineTable = ui.getComponent<TableView<TimelineEntry>> { it.name == "timelineTable" }

    assertThat(ui.getPosition(savedTable).x).isLessThan(ui.getPosition(detailsCardPanel).x)
    assertThat(emptyStatePanel.isVisible).isTrue()
    assertThat(detailsPanel.isVisible).isFalse()
    assertThat(savedTable.rowCount).isEqualTo(0)
    val emptyFragments = savedTable.emptyText.wrappedFragmentsIterable.toList()
    assertThat(emptyFragments.joinToString(" ") { it.toString() }).isEqualTo("Click the plus button below to import a sensor file")
    for (fragment in emptyFragments) {
      assertThat(fragment.preferredSize.width).isAtMost(savedTable.width)
    }

    val tempDir = Files.createTempDirectory("sensor-replay-test")
    val sessionFile1 = tempDir.resolve("highway_drive.pb")
    val session1 =
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder()
            .setVersion(SessionMetadata.Version.newBuilder().setMajor(2).setMinor(1).setPatch(4))
            .putCarPropertyConfigs(VehicleProperty.PERF_VEHICLE_SPEED, SessionMetadata.CarPropertyConfig.getDefaultInstance())
            .putCarPropertyConfigs(VehicleProperty.GEAR_SELECTION, SessionMetadata.CarPropertyConfig.getDefaultInstance())
            .putSubscribedSensors(
              "android.sensor.accelerometer_GoldfishAccel",
              SessionMetadata.Sensor.newBuilder().setName("GoldfishAccel").setType(1).build(),
            )
        )
        .addSensorRecords(SensorRecord.newBuilder().setTimestampNs(200_000_000L))
        .addSensorRecords(SensorRecord.newBuilder().setTimestampNs(750_000_000L))
        .addSensorRecords(SensorRecord.newBuilder().setTimestampNs(2_300_000_000L))
        .build()
    sessionFile1.writeBytes(session1.toByteArray())

    importSensorFile(ui, dialogController, sessionFile1)
    waitForCondition(5.seconds) { (savedTable.rowCount == 1) && detailsPanel.isVisible }

    assertThat(emptyStatePanel.isVisible).isFalse()
    assertThat(ui.getComponent<JBLabel> { it.name == "fileNameValueLabel" }.text).isEqualTo("highway_drive.pb")
    assertThat(ui.getComponent<JBLabel> { it.name == "appVersionValueLabel" }.text).isEqualTo("2.1.4")
    assertThat(ui.getComponent<JBLabel> { it.name == "totalEventsValueLabel" }.text).isEqualTo("3")
    assertThat(ui.getComponent<JBLabel> { it.name == "playbackTimeLabel" }.text).isEqualTo("00:00 / 00:02")

    // Verify 1-second timeline buckets including zero-event gap bucket (00:01 - 00:02).
    assertThat(timelineTable.items)
      .containsExactly(
        TimelineEntry("00:00 - 00:01", 2),
        TimelineEntry("00:01 - 00:02", 0),
        TimelineEntry("00:02 - 00:03", 1),
      )
      .inOrder()

    // Open "More..." sensors detail dialog and verify full sensor list.
    val moreLink = ui.getComponent<ActionLink> { it.name == "sensorsDetailLink" }
    assertThat(moreLink.isVisible).isTrue()
    createModalDialogAndInteractWithIt({ moreLink.doClick() }) { detailDlg ->
      assertThat(detailDlg.title).isEqualTo("Sensors in Session")
      val detailUi = FakeUi(detailDlg.rootPane)
      val list = detailUi.getComponent<JBList<String>> { it.name == "sensorsDetailList" }
      val items = (0 until list.model.size).map { list.model.getElementAt(it) }
      assertThat(items)
        .containsExactly(
          "Selected gear",
          "Speed (m/s)",
          "android.sensor.accelerometer_GoldfishAccel",
        )
        .inOrder()
      detailDlg.close(DialogWrapper.CLOSE_EXIT_CODE)
    }

    // Import a second session without version or sensor metadata.
    val sessionFile2 = tempDir.resolve("minimal_session.pb")
    val session2 = SensorSession.newBuilder().addSensorRecords(SensorRecord.newBuilder().setTimestampNs(500_000_000L)).build()
    sessionFile2.writeBytes(session2.toByteArray())

    importSensorFile(ui, dialogController, sessionFile2)
    waitForCondition(5.seconds) { savedTable.rowCount == 2 }
    assertThat(savedTable.selectedRow).isEqualTo(1)
    assertThat(ui.getComponent<JBLabel> { it.name == "fileNameValueLabel" }.text).isEqualTo("minimal_session.pb")
    assertThat(ui.getComponent<JBLabel> { it.name == "appVersionValueLabel" }.text).isEqualTo("N/A")
    assertThat(ui.getComponent<JBLabel> { it.name == "sensorsPreviewLabel" }.text).isEqualTo("None")
    assertThat(moreLink.isVisible).isFalse()
    assertThat(timelineTable.items).containsExactly(TimelineEntry("00:00 - 00:01", 1))

    // Switch selection back to first session.
    savedTable.selectionModel.setSelectionInterval(0, 0)
    assertThat(ui.getComponent<JBLabel> { it.name == "fileNameValueLabel" }.text).isEqualTo("highway_drive.pb")
    assertThat(moreLink.isVisible).isTrue()

    // Close and reopen dialog on same emulator: saved sessions are preserved.
    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
    val reopenedDialog = SensorReplayDialog(emulatorController).show(projectRule.project)
    val reopenedUi = FakeUi(reopenedDialog.rootPane)
    val reopenedSavedTable = reopenedUi.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    assertThat(reopenedSavedTable.rowCount).isEqualTo(2)

    // Remove both sessions via ToolbarDecorator's Remove action and verify empty state returns.
    val toolbar = reopenedUi.getComponent<ActionToolbar>()
    reopenedSavedTable.selectionModel.setSelectionInterval(0, 1)
    waitForCondition(5.seconds) {
      toolbar.updateActionsAsync()
      toolbar.actions.any { it.templatePresentation.text == "Remove" }
    }
    val removeButton = reopenedUi.getComponent<JComponent> { it.accessibleContext?.accessibleName == "Remove" }
    reopenedUi.clickOn(removeButton)
    waitForCondition(5.seconds) { reopenedSavedTable.rowCount == 0 }
    assertThat(reopenedUi.getComponent<JPanel> { it.name == "emptyStatePanel" }.isVisible).isTrue()

    reopenedDialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testInvalidEmptyAndDuplicateFileErrors() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-errors")

    // Empty file.
    val emptyFile = tempDir.resolve("empty.pb")
    Files.createFile(emptyFile)
    importSensorFile(ui, dialogController, emptyFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Sensor file 'empty.pb' is empty") }

    // Corrupt file.
    val corruptFile = tempDir.resolve("corrupt.pb")
    corruptFile.writeText("This is not a valid binary SensorSession protobuf")
    importSensorFile(ui, dialogController, corruptFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'corrupt.pb'") }

    // Valid file followed by duplicate import.
    val validFile = tempDir.resolve("valid.pb")
    validFile.writeBytes(
      SensorSession.newBuilder().addSensorRecords(SensorRecord.newBuilder().setTimestampNs(100_000_000L)).build().toByteArray()
    )
    importSensorFile(ui, dialogController, validFile)
    val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }

    importSensorFile(ui, dialogController, validFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Sensor file 'valid.pb' has already been imported") }
    assertThat(savedTable.rowCount).isEqualTo(1)

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testTruncatedAndMalformedGroupFilesShowInvalidFileError() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-corrupt")

    // Valid header followed by truncated SensorRecord length-delimited payload.
    val truncatedFile = tempDir.resolve("truncated.pb")
    val validBytes =
      SensorSession.newBuilder()
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(100_000_000L)
            .putCarPropertyValues(VehicleProperty.INFO_VIN, CarPropertyValue.newBuilder().setStringValue("ABCDEFGHIJKLMN").build())
        )
        .build()
        .toByteArray()
    truncatedFile.writeBytes(validBytes.copyOf(validBytes.size - 5))
    importSensorFile(ui, dialogController, truncatedFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'truncated.pb'") }

    // Deeply nested START_GROUP tags (0x0B) must fail cleanly without StackOverflowError.
    val deepGroupFile = tempDir.resolve("deep_groups.pb")
    deepGroupFile.writeBytes(ByteArray(500) { 0x0B })
    importSensorFile(ui, dialogController, deepGroupFile)
    waitForCondition(5.seconds) { hasValidationError(ui, "Unable to parse sensor file 'deep_groups.pb'") }

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testErrorsAndUncheckedExceptionsAreNotSwallowed() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)

    val tempDir = Files.createTempDirectory("sensor-replay-uncaught")
    val dummyFile = tempDir.resolve("valid.pb")
    dummyFile.writeBytes(
      SensorSession.newBuilder()
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(100_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(12f).build())
        )
        .build()
        .toByteArray()
    )

    val loggedThrowables = CopyOnWriteArrayList<Throwable>()
    val processor =
      object : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
          if (t != null) {
            loggedThrowables.add(t)
          }
          return Action.NONE
        }
      }

    LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
      // Unchecked RuntimeException during import must propagate to Logger.error rather than being swallowed as a parse error.
      dialogController.sessionLoader = { _, _ -> throw IllegalStateException("Simulated unchecked import bug") }
      importSensorFile(ui, dialogController, dummyFile)
      waitForCondition(5.seconds) {
        loggedThrowables.any { it is IllegalStateException && it.message == "Simulated unchecked import bug" }
      }
      assertThat(hasValidationError(ui, "Unable to parse sensor file")).isFalse()

      // Error during import must propagate to Logger.error rather than being swallowed as a parse error.
      loggedThrowables.clear()
      dialogController.sessionLoader = { _, _ -> throw AssertionError("Simulated import error") }
      importSensorFile(ui, dialogController, dummyFile)
      waitForCondition(5.seconds) { loggedThrowables.any { it is AssertionError && it.message == "Simulated import error" } }
      assertThat(hasValidationError(ui, "Unable to parse sensor file")).isFalse()

      // Import a real session, then verify expected gRPC StatusRuntimeException vs unexpected RuntimeException/Error during playback.
      loggedThrowables.clear()
      dialogController.sessionLoader = { path, checkCancelled -> loadSensorSession(path, checkCancelled = checkCancelled) }
      importSensorFile(ui, dialogController, dummyFile)
      val savedTable = ui.getComponent<TableView<*>> { it.name == "savedSessionsTable" }
      waitForCondition(5.seconds) { savedTable.rowCount == 1 }

      val playPauseButton = ui.getComponent<JButton> { it.name == "playPauseButton" }
      val speedCombo = ui.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
      speedCombo.selectSpeed("100X")

      // Expected gRPC StatusRuntimeException shows error in dialog and does NOT log Logger.error.
      dialogController.recordSenderOverride = { _, _ ->
        throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("Simulated gRPC disconnect"))
      }
      ui.clickOn(playPauseButton)
      waitForCondition(5.seconds) { !playPauseButton.isPlayingState && hasValidationError(ui, "Simulated gRPC disconnect") }
      assertThat(loggedThrowables).isEmpty()

      // Unchecked RuntimeException during playback resets UI controls and propagates to Logger.error.
      dialogController.recordSenderOverride = { _, _ -> throw IllegalStateException("Simulated playback bug") }
      ui.clickOn(playPauseButton)
      waitForCondition(5.seconds) {
        !playPauseButton.isPlayingState && loggedThrowables.any { it is IllegalStateException && it.message == "Simulated playback bug" }
      }
      assertThat(playPauseButton.icon).isEqualTo(AllIcons.Actions.Execute)
      assertThat(playPauseButton.accessibleContext.accessibleName).isEqualTo("Play")

      // Error during seek propagates to Logger.error rather than showing a validation message.
      loggedThrowables.clear()
      dialogController.recordSenderOverride = { _, _ -> throw AssertionError("Simulated seek error") }
      val slider = ui.getComponent<JSlider> { it.name == "playbackSlider" }
      slider.value = 1000
      waitForCondition(5.seconds) { loggedThrowables.any { it is AssertionError && it.message == "Simulated seek error" } }
    }

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Suppress("DEPRECATION")
  @Test
  fun testFileChooserAllowsOnlySupportedExtensionsAndExtensionlessFiles() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val ui = FakeUi(dialog.rootPane)
    val tempDir = Files.createTempDirectory("sensor-replay-chooser-filter")
    val localFs = LocalFileSystem.getInstance()

    val validSessionBytes =
      SensorSession.newBuilder().addSensorRecords(SensorRecord.newBuilder().setTimestampNs(250_000_000L)).build().toByteArray()

    val allowedNames =
      listOf(
        "recording.bin",
        "recording.BIN",
        "recording.pb",
        "recording.PB",
        "gothenburg_hamburg_dummy_detection.textproto",
        "recording.TextProto",
        "sensor_session",
        "2024-03-14_02.59.23_speed-bump",
        "2024-03-14_02.59.23",
        "trailing_dot.",
      )
    val disallowedNames =
      listOf(
        "notes.txt",
        "data.json",
        "video.mp4",
        "archive.7z",
        "clip.3gp",
        "image.png",
        "library.jar",
        "session.txtpb",
        "session.pbtxt",
        "session.textpb",
        "page.html",
      )

    for (name in allowedNames + disallowedNames + listOf(".hidden_session", ".hidden.txt")) {
      tempDir.resolve(name).writeBytes(validSessionBytes)
    }
    val subDir = Files.createDirectories(tempDir.resolve("subdir"))
    val dottedSubDir = Files.createDirectories(tempDir.resolve("subdir.txt"))

    val pngVirtualFile = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve("image.png")))
    val speedBumpVirtualFile = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve("2024-03-14_02.59.23_speed-bump")))

    val defaultChooserCreator = dialogController.fileChooserCreator
    var capturedDescriptor: FileChooserDescriptor? = null
    dialogController.fileChooserCreator = { descriptor, parentComponent, project ->
      capturedDescriptor = descriptor
      val defaultChooser = defaultChooserCreator(descriptor, parentComponent, project)
      try {
        assertThat(defaultChooser).isInstanceOf(FileChooserDialogImpl::class.java)
      } finally {
        Disposer.dispose((defaultChooser as DialogWrapper).disposable)
      }
      FileChooserDialog { _, _ ->
        FileChooserUtil.getChosenFiles(descriptor, listOf(pngVirtualFile, speedBumpVirtualFile)).toTypedArray()
      }
    }
    performAddAction(ui)

    val savedTable = ui.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }
    waitForCondition(5.seconds) { savedTable.rowCount == 1 }
    assertThat(savedTable.items.single().fileName).isEqualTo("2024-03-14_02.59.23_speed-bump")

    val descriptor = checkNotNull(capturedDescriptor)
    val tempDirVirtualFile = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir))
    val fileSystemTree = FileSystemTreeImpl(projectRule.project, FileChooserDescriptor(descriptor).withRoots(tempDirVirtualFile))
    Disposer.register(dialog.disposable, fileSystemTree)

    var pngSelectDone = false
    fileSystemTree.select(pngVirtualFile) { pngSelectDone = true }
    waitForCondition(5.seconds) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      pngSelectDone
    }
    assertThat(fileSystemTree.selectedFile).isNotEqualTo(pngVirtualFile)

    var speedBumpSelectDone = false
    fileSystemTree.select(speedBumpVirtualFile) { speedBumpSelectDone = true }
    waitForCondition(5.seconds) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      speedBumpSelectDone
    }
    assertThat(fileSystemTree.selectedFile).isEqualTo(speedBumpVirtualFile)

    for (name in allowedNames) {
      val vf = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve(name))) { "Missing VirtualFile for $name" }
      assertThat(descriptor.isFileVisible(vf, false)).named("isFileVisible($name)").isTrue()
      assertThat(descriptor.isFileSelectable(vf)).named("isFileSelectable($name)").isTrue()
      @Suppress("UnstableApiUsage") assertThat(descriptor.isSelectable(vf)).named("isSelectable($name)").isTrue()
    }

    for (name in disallowedNames) {
      val vf = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve(name))) { "Missing VirtualFile for $name" }
      assertThat(descriptor.isFileVisible(vf, false)).named("isFileVisible($name)").isFalse()
      assertThat(descriptor.isFileSelectable(vf)).named("isFileSelectable($name)").isFalse()
      @Suppress("UnstableApiUsage") assertThat(descriptor.isSelectable(vf)).named("isSelectable($name)").isFalse()
    }

    val hiddenExtensionlessVf = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve(".hidden_session")))
    assertThat(descriptor.isFileVisible(hiddenExtensionlessVf, true)).isTrue()
    assertThat(descriptor.isFileSelectable(hiddenExtensionlessVf)).isTrue()

    val hiddenTxtVf = checkNotNull(localFs.refreshAndFindFileByNioFile(tempDir.resolve(".hidden.txt")))
    assertThat(descriptor.isFileVisible(hiddenTxtVf, true)).isFalse()
    assertThat(descriptor.isFileSelectable(hiddenTxtVf)).isFalse()

    for (dirPath in listOf(subDir, dottedSubDir)) {
      val dirVf = checkNotNull(localFs.refreshAndFindFileByNioFile(dirPath))
      assertThat(descriptor.isFileVisible(dirVf, false)).named("isFileVisible(${dirVf.name})").isTrue()
      assertThat(descriptor.isFileSelectable(dirVf)).named("isFileSelectable(${dirVf.name})").isFalse()
      @Suppress("UnstableApiUsage") assertThat(descriptor.isSelectable(dirVf)).named("isSelectable(${dirVf.name})").isFalse()
    }

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testSavedSessionsColumnMinimumAndPreferredWidth() {
    val (_, emulatorController) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController = SensorReplayDialog(emulatorController)
    val dialog = dialogController.show(projectRule.project)
    val rootPane = dialog.rootPane
    val ui = FakeUi(rootPane)

    val savedSessionsTablePanel = ui.getComponent<JPanel> { it.name == "savedSessionsTablePanel" }
    assertThat(savedSessionsTablePanel.preferredSize.width).isEqualTo(JBUIScale.scale(240))
    assertThat(savedSessionsTablePanel.minimumSize.width).isEqualTo(JBUIScale.scale(150))

    rootPane.size = rootPane.preferredSize
    ui.layoutAndDispatchEvents()
    assertThat(savedSessionsTablePanel.width).isEqualTo(JBUIScale.scale(240))

    rootPane.size = rootPane.minimumSize
    ui.layoutAndDispatchEvents()
    assertThat(savedSessionsTablePanel.width).isEqualTo(JBUIScale.scale(150))

    dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
  }

  @Test
  fun testPersistedSensorFilesAcrossStudioRuns() {
    val tempDir = Files.createTempDirectory("sensor-replay-persistence")
    val file1 = tempDir.resolve("first_drive.pb")
    file1.writeBytes(
      SensorSession.newBuilder()
        .setSessionMetadata(
          SessionMetadata.newBuilder().setVersion(SessionMetadata.Version.newBuilder().setMajor(1).setMinor(4).setPatch(0))
        )
        .addSensorRecords(
          SensorRecord.newBuilder()
            .setTimestampNs(100_000_000L)
            .putCarPropertyValues(VehicleProperty.PERF_VEHICLE_SPEED, CarPropertyValue.newBuilder().addFloatValues(33.5f).build())
        )
        .build()
        .toByteArray()
    )

    val file2 = tempDir.resolve("second_commute.textproto")
    file2.writeText(
      TextFormat.printer()
        .printToString(
          SensorSession.newBuilder()
            .setSessionMetadata(
              SessionMetadata.newBuilder().setVersion(SessionMetadata.Version.newBuilder().setMajor(2).setMinor(0).setPatch(1))
            )
            .addSensorRecords(SensorRecord.newBuilder().setTimestampNs(250_000_000L))
            .build()
        )
    )

    val expectedPath1 = FileUtil.toSystemIndependentName(file1.toAbsolutePath().normalize().toString())
    val expectedPath2 = FileUtil.toSystemIndependentName(file2.toAbsolutePath().normalize().toString())

    // Studio run 1: import two sensor files and verify application-level persistence.
    val (_, emulatorController1) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController1 = SensorReplayDialog(emulatorController1)
    val dialog1 = dialogController1.show(projectRule.project)
    val ui1 = FakeUi(dialog1.rootPane)
    val savedTable1 = ui1.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }

    importSensorFile(ui1, dialogController1, tempDir.resolve("./first_drive.pb"))
    waitForCondition(5.seconds) { savedTable1.rowCount == 1 }
    importSensorFile(ui1, dialogController1, file2)
    waitForCondition(5.seconds) { savedTable1.rowCount == 2 }

    val properties = PropertiesComponent.getInstance()
    assertThat(properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY)).containsExactly(expectedPath1, expectedPath2).inOrder()

    dialog1.close(DialogWrapper.CLOSE_EXIT_CODE)
    Disposer.dispose(emulatorController1)

    // Simulate Studio restart by round-tripping PersistentStateComponent state and opening on a brand-new EmulatorController.
    @Suppress("UNCHECKED_CAST") val persistentProperties = properties as PersistentStateComponent<Any>
    val savedState = checkNotNull(persistentProperties.state)
    properties.setList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY, null)
    assertThat(properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY)).isNull()
    persistentProperties.loadState(savedState)

    // Verify closing the dialog while restoration is mid-load cancels cleanly without pruning persisted files.
    val (_, emulatorControllerCancel) = createAndStartAutomotiveEmulator(emulatorRule)
    val startedLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)
    val dialogControllerCancel = SensorReplayDialog(emulatorControllerCancel)
    dialogControllerCancel.sessionLoader = { path, checkCancelled ->
      startedLatch.countDown()
      while (!releaseLatch.await(10, TimeUnit.MILLISECONDS)) {
        checkCancelled()
      }
      loadSensorSession(path, checkCancelled = checkCancelled)
    }
    val dialogCancel = dialogControllerCancel.show(projectRule.project)
    waitForCondition(5.seconds) { startedLatch.count == 0L }
    dialogCancel.close(DialogWrapper.CLOSE_EXIT_CODE)
    releaseLatch.countDown()
    Disposer.dispose(emulatorControllerCancel)
    assertThat(properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY)).containsExactly(expectedPath1, expectedPath2).inOrder()

    // Studio run 2: open dialog on a fresh EmulatorController and verify both files restore in order and can be replayed.
    val (fakeEmulator2, emulatorController2) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController2 = SensorReplayDialog(emulatorController2)
    val dialog2 = dialogController2.show(projectRule.project)
    val ui2 = FakeUi(dialog2.rootPane)
    val savedTable2 = ui2.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }
    val detailsPanel2 = ui2.getComponent<JPanel> { it.name == "sessionDetailsPanel" }

    waitForCondition(5.seconds) { savedTable2.rowCount == 2 && detailsPanel2.isVisible }
    assertThat(savedTable2.items.map { it.fileName }).containsExactly("first_drive.pb", "second_commute.textproto").inOrder()
    assertThat(savedTable2.selectedRow).isEqualTo(0)
    assertThat(ui2.getComponent<JBLabel> { it.name == "fileNameValueLabel" }.text).isEqualTo("first_drive.pb")
    assertThat(ui2.getComponent<JBLabel> { it.name == "appVersionValueLabel" }.text).isEqualTo("1.4.0")

    val speedCombo2 = ui2.getComponent<ComboBox<*>> { it.name == "playbackSpeedComboBox" }
    val playPauseButton2 = ui2.getComponent<JButton> { it.name == "playPauseButton" }
    speedCombo2.selectSpeed("100X")
    ui2.clickOn(playPauseButton2)
    waitForCondition(5.seconds) {
      !playPauseButton2.isPlayingState && fakeEmulator2.getVehicleProperty(VehicleProperty.PERF_VEHICLE_SPEED)?.getFloatValues(0) == 33.5f
    }

    // Remove the first file and verify persistence updates immediately.
    val toolbar2 = ui2.getComponent<ActionToolbar>()
    savedTable2.selectionModel.setSelectionInterval(0, 0)
    waitForCondition(5.seconds) {
      toolbar2.updateActionsAsync()
      toolbar2.actions.any { it.templatePresentation.text == "Remove" }
    }
    val removeButton2 = ui2.getComponent<JComponent> { it.accessibleContext?.accessibleName == "Remove" }
    ui2.clickOn(removeButton2)
    waitForCondition(5.seconds) { savedTable2.rowCount == 1 }
    assertThat(properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY)).containsExactly(expectedPath2)

    dialog2.close(DialogWrapper.CLOSE_EXIT_CODE)
    Disposer.dispose(emulatorController2)

    // Studio run 3: inject missing, empty, unnormalized, and corrupt paths alongside valid files; verify silent pruning without error
    // banner.
    val missingFile = tempDir.resolve("deleted_session.pb")
    val emptyFile = tempDir.resolve("empty_session.pb").also { Files.createFile(it) }
    val corruptFile = tempDir.resolve("corrupt_session.pb").also { it.writeText("not a valid sensor session") }
    properties.setList(
      SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY,
      mutableListOf(
        FileUtil.toSystemIndependentName(missingFile.toString()),
        expectedPath2,
        FileUtil.toSystemIndependentName(emptyFile.toString()),
        FileUtil.toSystemIndependentName(tempDir.resolve("./first_drive.pb").toString()),
        FileUtil.toSystemIndependentName(corruptFile.toString()),
      ),
    )

    val (_, emulatorController3) = createAndStartAutomotiveEmulator(emulatorRule)
    val dialogController3 = SensorReplayDialog(emulatorController3)
    val dialog3 = dialogController3.show(projectRule.project)
    val ui3 = FakeUi(dialog3.rootPane)
    val savedTable3 = ui3.getComponent<TableView<SensorSessionItem>> { it.name == "savedSessionsTable" }

    waitForCondition(5.seconds) {
      savedTable3.rowCount == 2 &&
        properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY) == listOf(expectedPath2, expectedPath1)
    }
    assertThat(savedTable3.items.map { it.fileName }).containsExactly("second_commute.textproto", "first_drive.pb").inOrder()
    assertThat(hasValidationError(ui3, "empty")).isFalse()
    assertThat(hasValidationError(ui3, "Unable to parse")).isFalse()

    // Remove all remaining sessions and verify persisted property is cleared completely.
    val toolbar3 = ui3.getComponent<ActionToolbar>()
    savedTable3.selectionModel.setSelectionInterval(0, 1)
    waitForCondition(5.seconds) {
      toolbar3.updateActionsAsync()
      toolbar3.actions.any { it.templatePresentation.text == "Remove" }
    }
    val removeButton3 = ui3.getComponent<JComponent> { it.accessibleContext?.accessibleName == "Remove" }
    ui3.clickOn(removeButton3)
    waitForCondition(5.seconds) { savedTable3.rowCount == 0 }
    assertThat(properties.getList(SensorReplayDialog.SAVED_SENSOR_FILES_PROPERTY)).isNull()

    dialog3.close(DialogWrapper.CLOSE_EXIT_CODE)
    Disposer.dispose(emulatorController3)
  }
}
