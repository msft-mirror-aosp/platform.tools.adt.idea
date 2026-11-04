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

import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnActionButton
import com.intellij.ui.CommonActionsPanel
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JLayeredPane
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** Checks whether the play/pause button is currently in the playing state (displaying the pause icon). */
internal val JButton.isPlayingState: Boolean
  get() = icon == AllIcons.Actions.Pause

/** Selects the playback speed item in this combo box whose string representation matches [displayName]. */
internal fun JComboBox<*>.selectSpeed(displayName: String) {
  selectedIndex = (0 until itemCount).first { getItemAt(it).toString() == displayName }
}

/** Creates an Automotive AVD, starts a [FakeEmulator], and waits until its [EmulatorController] is connected. */
internal fun createAndStartAutomotiveEmulator(emulatorRule: FakeEmulatorRule): Pair<FakeEmulator, EmulatorController> {
  val testAvdFolder = FakeEmulator.createAutomotiveAvd(emulatorRule.avdRoot)
  val fakeEmulator = emulatorRule.newEmulator(testAvdFolder)
  fakeEmulator.start()
  val catalog = RunningEmulatorCatalog.getInstance()
  val emulators = runBlocking { catalog.updateNow().await() }
  val emulatorController = emulators.first { it.emulatorId.grpcPort == fakeEmulator.grpcPort }
  waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }
  return fakeEmulator to emulatorController
}

/** Returns whether [ui] contains a visible [JLabel] whose text includes [expectedSubstring]. */
internal fun hasValidationError(ui: FakeUi, expectedSubstring: String): Boolean =
  ui.findAllComponents<JLabel>().any { it.isVisible && it.text?.contains(expectedSubstring) == true }

/** Imports [file] into [dialogController] by stubbing its file chooser and invoking the toolbar Add action in [ui]. */
internal fun importSensorFile(ui: FakeUi, dialogController: SensorReplayDialog, file: Path) {
  val virtualFile =
    checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.toAbsolutePath().normalize())) {
      "Missing VirtualFile for $file"
    }
  val previousCreator = dialogController.fileChooserCreator
  dialogController.fileChooserCreator = { _, _, _ -> FileChooserDialog { _, _ -> arrayOf(virtualFile) } }
  try {
    performAddAction(ui)
  } finally {
    dialogController.fileChooserCreator = previousCreator
  }
}

/** Triggers the Add action on the [CommonActionsPanel] toolbar in [ui]. */
internal fun performAddAction(ui: FakeUi) {
  val actionsPanel = ui.getComponent<CommonActionsPanel>()
  val addAction = checkNotNull(actionsPanel.getAnAction(CommonActionsPanel.Buttons.ADD))
  performAction(addAction)
}

private fun performAction(action: AnAction) {
  if (action is AnActionButton) {
    val contextComponent =
      object : JLayeredPane() {
        override fun isShowing(): Boolean = true
      }
    val savedContextComponent = action.contextComponent
    try {
      action.contextComponent = contextComponent
      executeAction(action)
    } finally {
      action.contextComponent = savedContextComponent
    }
  } else {
    executeAction(action)
  }
}
