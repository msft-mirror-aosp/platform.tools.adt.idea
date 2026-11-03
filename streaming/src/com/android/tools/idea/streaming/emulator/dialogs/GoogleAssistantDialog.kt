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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsLines
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Key
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.KeyEvent.VK_ENTER
import java.time.Duration
import javax.swing.JButton
import javax.swing.JComponent.WHEN_FOCUSED
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls

/**
 * Dialog for sending text queries to Google Assistant on an Android TV emulator, providing functionality similar to the Google Assistant
 * section of the Directional Pad page in extended controls.
 */
internal class GoogleAssistantDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("google.assistant.dialog.title", emulator.emulatorId.avdName)) {

  private var query: String = emulator.getUserData(QUERY_KEY) ?: DEFAULT_QUERY
    set(value) {
      field = value
      emulator.putUserData(QUERY_KEY, value)
    }

  private lateinit var sendButton: Cell<JButton>

  private val commandMutex = Mutex()
  @Volatile private var isDisposed = false

  private val adbDeviceServices: AdbDeviceServices
    get() = (project?.let(AdbLibService::getSession) ?: AdbLibApplicationService.instance.session).deviceServices

  private val deviceSelector: DeviceSelector = DeviceSelector.fromSerialNumber(emulator.emulatorId.serialNumber)

  init {
    onDispose {
      isDisposed = true
    }
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    return panel {
      row {
        val label = message("google.assistant.dialog.label.query")
        textArea()
          .label(label, LabelPosition.TOP)
          .accessibleName(label.removeSuffix(":"))
          .rows(5)
          .columns(COLUMNS_MEDIUM)
          .align(Align.FILL)
          .focused()
          .applyToComponent {
            name = "queryTextArea"
            text = query
            lineWrap = true
            wrapStyleWord = true
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
            registerKeyboardAction({ sendQuery() }, KeyStroke.getKeyStroke(VK_ENTER, CTRL_DOWN_MASK), WHEN_FOCUSED)
            document.addDocumentListener(
              object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                  query = text
                  updateSendButton()
                }
              }
            )
          }
      }
        .resizableRow()

      row {
        sendButton =
          button(message("google.assistant.dialog.button.send")) { sendQuery() }
            .align(AlignX.RIGHT)
            .applyToComponent {
              name = "sendButton"
            }
      }
    }
      .apply { updateSendButton() }
  }

  private fun sendQuery() {
    val normalizedQuery = normalizeQuery(query)
    if (normalizedQuery.isNotEmpty()) {
      val command = createAssistCommand(normalizedQuery)
      val adb = adbDeviceServices
      coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!commandMutex.tryLock()) {
          commandMutex.lock()
          if (isDisposed) {
            commandMutex.unlock()
            return@launch
          }
        }
        try {
          withContext(Dispatchers.IO) {
            executeShellCommand(adb, command)
          }
        } finally {
          commandMutex.unlock()
        }
      }
    }
  }

  private fun updateSendButton() {
    if (::sendButton.isInitialized) {
      sendButton.component.isEnabled = query.isNotBlank()
    }
  }

  private suspend fun executeShellCommand(adb: AdbDeviceServices, command: String) {
    try {
      adb.shellAsLines(deviceSelector, command, commandTimeout = COMMAND_TIMEOUT).collect()
    } catch (t: Throwable) {
      t.throwIfCancellation()
      thisLogger().warn("Error executing shell command: $command", t)
    }
  }

  companion object {
    @NonNls private const val DEFAULT_QUERY = "Hey Google"
    private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val QUERY_KEY = Key.create<String>("com.android.tools.idea.streaming.emulator.dialogs.GoogleAssistantQuery")
    private val LINE_BREAK_REGEX = Regex("""\s*\R\s*""")
    private val SPECIAL_CHARACTERS_REGEX = Regex("""[\\"$`]""")

    private fun normalizeQuery(query: String): String = query.trim().replace(LINE_BREAK_REGEX, " ")

    private fun createAssistCommand(query: String): String =
      "am start -a android.intent.action.ASSIST --ei search_type 1 --es query ${sanitizeUserInput(query)}"

    /** Quotes and escapes [rawInput] for safe passage inside double quotes to the Android shell. */
    private fun sanitizeUserInput(rawInput: String): String = "\"${rawInput.replace(SPECIAL_CHARACTERS_REGEX) { "\\${it.value}" }}\""
  }
}
