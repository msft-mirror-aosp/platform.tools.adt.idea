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

import com.android.emulation.control.incubating.Call
import com.android.emulation.control.incubating.Call.CallState
import com.android.emulation.control.incubating.SmsMessage
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.streaming.StreamingBundle.message
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.utils.throwIfCancellation
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls

/** Dialog for simulating incoming phone calls and SMS messages on the emulator, similar to the Phone page in extended controls. */
internal class PhoneDialog(emulator: EmulatorController) :
  AbstractExtendedControlsDialog(emulator, message("phone.dialog.title", emulator.emulatorId.avdName)) {

  private var activeCall: Call = Call.getDefaultInstance()
  private val numberComboBoxModel = DefaultComboBoxModel(getInitialPhoneNumbers())

  private lateinit var numberComboBox: Cell<ComboBox<String>>
  private lateinit var holdCallButton: Cell<JButton>
  private lateinit var callButton: Cell<JButton>
  private lateinit var smsMessageArea: Cell<JBTextArea>
  private lateinit var sendSmsButton: Cell<JButton>

  private val currentPhoneNumberText: String
    get() {
      if (!::numberComboBox.isInitialized) return numberComboBoxModel.selectedItem as? String ?: DEFAULT_PHONE_NUMBERS[0]
      return getComboBoxText(numberComboBox.component)
    }

  init {
    val phoneEventsJob = coroutineScope.launch {
      try {
        val activeCalls = emulator.listCalls()
        withContext(uiContext) {
          if (activeCall.state == CallState.CALL_STATE_UNSPECIFIED && activeCalls.callsCount > 0) {
            activeCall = activeCalls.getCalls(0)
            updateUiFromState()
          }
        }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
      }

      try {
        emulator.receivePhoneEvents().collect { event ->
          if (event.hasActive() && event.active.callsCount == 0) {
            withContext(uiContext) {
              if (activeCall.state != CallState.CALL_STATE_UNSPECIFIED) {
                endCall()
              }
            }
          }
        }
      } catch (t: Throwable) {
        t.throwIfCancellation()
        // Error is already logged by EmulatorController.
      }
    }
    onDispose { phoneEventsJob.cancel() }
  }

  /** Creates contents of the dialog. */
  override fun createPanel(): DialogPanel {
    val isAutomotive = emulator.emulatorConfig.deviceType == DeviceType.AUTOMOTIVE
    val inputChangeListener =
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          clearError()
          updateUiFromState()
        }
      }

    return panel {
      row {
          val label = message("phone.dialog.label.from")
          numberComboBox =
            comboBox(numberComboBoxModel)
              .label(label, LabelPosition.TOP)
              .accessibleName(label.removeSuffix(":"))
              .align(AlignX.FILL)
              .validationOnInput { validatePhoneNumber(it) }
              .applyToComponent {
                name = "numberComboBox"
                isEditable = true
                selectedItem = numberComboBoxModel.getElementAt(0)
                setMinimumAndPreferredWidth(JBUIScale.scale(FIELD_WIDTH))
                addActionListener {
                  clearError()
                  updateUiFromState()
                }
                (editor.editorComponent as? JTextField)?.document?.addDocumentListener(inputChangeListener)
              }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          holdCallButton =
            cell(EqualWidthButton(message("phone.dialog.button.hold.call"))).align(AlignX.FILL).resizableColumn().applyToComponent {
              name = "holdCallButton"
              isEnabled = false
              addActionListener { toggleHoldCall() }
            }

          callButton =
            cell(EqualWidthButton(message("phone.dialog.button.call.device"))).align(AlignX.FILL).resizableColumn().applyToComponent {
              name = "callButton"
              addActionListener { startOrEndCall() }
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          val label = message("phone.dialog.label.sms.message")
          smsMessageArea =
            textArea()
              .label(label, LabelPosition.TOP)
              .accessibleName(label.removeSuffix(":"))
              .rows(5)
              .align(Align.FILL)
              .validationOnInput { validateSmsMessage(it) }
              .applyToComponent {
                name = "smsMessageArea"
                text = DEFAULT_SMS_MESSAGE
                lineWrap = true
                wrapStyleWord = true
                isEditable = !isAutomotive
                isEnabled = !isAutomotive
                background = JBTextField().background
                border = TextAreaBorder()
                document.addDocumentListener(inputChangeListener)
              }
        }
        .layout(RowLayout.PARENT_GRID)
        .resizableRow()

      row {
          @Suppress("UnstableApiUsage") placeholder().resizableColumn()

          sendSmsButton =
            cell(EqualWidthButton(message("phone.dialog.button.send.message"))).align(AlignX.FILL).resizableColumn().applyToComponent {
              name = "sendSmsButton"
              isEnabled = !isAutomotive
              addActionListener { sendSms() }
            }
        }
        .layout(RowLayout.PARENT_GRID)
    }
      .apply { updateUiFromState() }
  }

  private fun ValidationInfoBuilder.validatePhoneNumber(comboBox: ComboBox<String>): ValidationInfo? {
    val rawNumber = getComboBoxText(comboBox).trim()
    return if (!isValidPhoneNumber(rawNumber)) error(message("phone.dialog.error.invalid.number")) else null
  }

  private fun ValidationInfoBuilder.validateSmsMessage(textArea: JBTextArea): ValidationInfo? {
    if (!textArea.isEditable) return null
    val text = textArea.text
    return when {
      text.isEmpty() -> error(message("phone.dialog.error.empty.message"))
      text.toByteArray(Charsets.UTF_8).size > MAX_SMS_MSG_SIZE -> error(message("phone.dialog.error.message.too.long", MAX_SMS_MSG_SIZE))
      else -> null
    }
  }

  private fun startOrEndCall() {
    clearError()
    if (activeCall.state == CallState.CALL_STATE_UNSPECIFIED) {
      val rawNumber = currentPhoneNumberText.trim()
      if (!isValidPhoneNumber(rawNumber)) {
        showError(message("phone.dialog.error.invalid.number"))
        return
      }
      recordRecentNumber(rawNumber)
      val cleanNumber = cleanPhoneNumber(rawNumber)
      val call = Call.newBuilder().setNumber(cleanNumber).build()
      launchGrpcCall({ it.createCall(call) }) { createdCall ->
        activeCall = createdCall.toBuilder().setNumber(cleanNumber).setState(CallState.CALL_STATE_ACTIVE).build()
        updateUiFromState()
      }
    } else {
      endCall()
    }
  }

  private fun endCall() {
    val callToEnd = activeCall
    launchGrpcCall({ it.deleteCall(callToEnd) }) {
      activeCall = Call.getDefaultInstance()
      updateUiFromState()
    }
  }

  private fun toggleHoldCall() {
    clearError()
    val newState =
      when (activeCall.state) {
        CallState.CALL_STATE_ACTIVE -> CallState.CALL_STATE_HELD
        CallState.CALL_STATE_HELD -> CallState.CALL_STATE_ACTIVE
        else -> return
      }
    val updatedRequest = activeCall.toBuilder().setState(newState).build()
    launchGrpcCall({ it.updateCall(updatedRequest) }) {
      activeCall = updatedRequest
      updateUiFromState()
    }
  }

  private fun sendSms() {
    clearError()
    val rawNumber = currentPhoneNumberText.trim()
    if (!isValidPhoneNumber(rawNumber)) {
      showError(message("phone.dialog.error.invalid.number"))
      return
    }
    val messageText = if (::smsMessageArea.isInitialized) smsMessageArea.component.text else ""
    if (messageText.isEmpty()) {
      showError(message("phone.dialog.error.empty.message"))
      return
    }
    if (messageText.toByteArray(Charsets.UTF_8).size > MAX_SMS_MSG_SIZE) {
      showError(message("phone.dialog.error.message.too.long", MAX_SMS_MSG_SIZE))
      return
    }

    recordRecentNumber(rawNumber)
    val cleanNumber = cleanPhoneNumber(rawNumber)
    val sms = SmsMessage.newBuilder().setNumber(cleanNumber).setText(messageText).build()
    launchGrpcCall({ it.receiveSms(sms) })
  }

  private fun recordRecentNumber(number: String) {
    val existingIndex = numberComboBoxModel.getIndexOf(number)
    if (existingIndex != 0) {
      if (existingIndex > 0) {
        numberComboBoxModel.removeElementAt(existingIndex)
      }
      numberComboBoxModel.insertElementAt(number, 0)
      while (numberComboBoxModel.size > MAX_RECENT_NUMBERS) {
        numberComboBoxModel.removeElementAt(numberComboBoxModel.size - 1)
      }
    }
    numberComboBoxModel.selectedItem = number
    if (::numberComboBox.isInitialized) {
      numberComboBox.component.editor.item = number
    }
    val numbers = List(numberComboBoxModel.size) { numberComboBoxModel.getElementAt(it) }
    PropertiesComponent.getInstance().setList(RECENT_NUMBERS_PROPERTY, numbers)
  }

  private fun updateUiFromState() {
    val state = activeCall.state
    val isNumberValid = isValidPhoneNumber(currentPhoneNumberText.trim())
    if (::numberComboBox.isInitialized) {
      val numberComponent = numberComboBox.component
      numberComponent.isEnabled = state == CallState.CALL_STATE_UNSPECIFIED
      if (state != CallState.CALL_STATE_UNSPECIFIED && activeCall.number.isNotEmpty() && currentPhoneNumberText.isEmpty()) {
        numberComponent.selectedItem = activeCall.number
      }
    }
    if (::holdCallButton.isInitialized) {
      holdCallButton.component.isEnabled = state == CallState.CALL_STATE_ACTIVE || state == CallState.CALL_STATE_HELD
      holdCallButton.component.text =
        if (state == CallState.CALL_STATE_HELD) {
          message("phone.dialog.button.resume.call")
        } else {
          message("phone.dialog.button.hold.call")
        }
    }
    if (::callButton.isInitialized) {
      callButton.component.isEnabled = state != CallState.CALL_STATE_UNSPECIFIED || isNumberValid
      callButton.component.text =
        if (state == CallState.CALL_STATE_UNSPECIFIED) {
          message("phone.dialog.button.call.device")
        } else {
          message("phone.dialog.button.end.call")
        }
    }
    if (::sendSmsButton.isInitialized && ::smsMessageArea.isInitialized) {
      val isAutomotive = emulator.emulatorConfig.deviceType == DeviceType.AUTOMOTIVE
      val messageText = smsMessageArea.component.text
      val isMessageValid = messageText.isNotEmpty() && messageText.toByteArray(Charsets.UTF_8).size <= MAX_SMS_MSG_SIZE
      sendSmsButton.component.isEnabled = !isAutomotive && isNumberValid && isMessageValid
    }
  }

  private class TextAreaBorder : LineBorder(JBColor.border(), 1) {
    override fun getBorderInsets(c: Component, insets: Insets): Insets {
      val left = JBUI.scale(6)
      val top = JBUI.scale(3)
      insets.set(top, left, top, left)
      return insets
    }
  }

  private class EqualWidthButton(text: String) : JButton(text) {
    override fun getPreferredSize(): Dimension {
      val size = super.getPreferredSize()
      return Dimension(JBUIScale.scale(BUTTON_WIDTH).coerceAtLeast(size.width), size.height)
    }

    override fun getMinimumSize(): Dimension = preferredSize
  }

  companion object {
    @NonNls internal const val RECENT_NUMBERS_PROPERTY = "com.android.tools.idea.streaming.phone.dialog.recent.numbers"
    private const val MAX_RECENT_NUMBERS = 8
    private const val FIELD_WIDTH = 280
    private const val BUTTON_WIDTH = 132
    internal const val MAX_SMS_MSG_SIZE = 1024
    private const val MAX_DIGITS = 16
    private const val MAX_DIGITAL_LENGTH = 31
    private const val MAX_ALPHANUMERIC_LENGTH = 11
    private const val ACCEPTABLE_NON_DIGITS = "-.()/ +"
    private const val GSM_DEFAULT_ALPHABET_SPECIAL_CHARS = "@_ !\"#%&'()*+,-./:;<=>?"
    private val NON_DIGIT_OR_PLUS_REGEX = Regex("[^+0-9]")

    @NonNls private val DEFAULT_PHONE_NUMBERS = arrayOf("(650) 555-1212", "+1 650 555-6789")
    @NonNls private const val DEFAULT_SMS_MESSAGE = "Android is always a sweet treat!"

    private fun getInitialPhoneNumbers(): Array<String> {
      val savedNumbers = PropertiesComponent.getInstance().getList(RECENT_NUMBERS_PROPERTY)
      return if (savedNumbers.isNullOrEmpty()) DEFAULT_PHONE_NUMBERS.clone() else savedNumbers.take(MAX_RECENT_NUMBERS).toTypedArray()
    }

    private fun getComboBoxText(comboBox: ComboBox<String>): String {
      return (comboBox.editor.editorComponent as? JTextField)?.text
        ?: (comboBox.editor.item as? String)
        ?: (comboBox.selectedItem as? String)
        ?: ""
    }

    /** Validates a telephone number or alphanumeric sender address according to emulator telephony rules. */
    internal fun isValidPhoneNumber(input: String): Boolean = isValidDigitalNumber(input) || isValidAlphanumericNumber(input)

    private fun isValidDigitalNumber(input: String): Boolean {
      if (input.isEmpty() || input.length > MAX_DIGITAL_LENGTH) {
        return false
      }
      var numDigits = 0
      for (i in input.indices) {
        when (input[i]) {
          in '0'..'9' -> {
            numDigits++
            if (numDigits > MAX_DIGITS) {
              return false
            }
          }

          '+' if i != 0 -> return false
          !in ACCEPTABLE_NON_DIGITS -> return false
        }
      }
      return numDigits > 0
    }

    private fun isValidAlphanumericNumber(input: String): Boolean =
      !(input.isEmpty() || input.length > MAX_ALPHANUMERIC_LENGTH) && input.all { isInGsmDefaultAlphabet(it) }

    private fun isInGsmDefaultAlphabet(c: Char): Boolean =
      c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in GSM_DEFAULT_ALPHABET_SPECIAL_CHARS

    /** Strips formatting punctuation from digital phone numbers while preserving alphanumeric sender IDs. */
    internal fun cleanPhoneNumber(input: String): String =
      if (isValidDigitalNumber(input)) input.replace(NON_DIGIT_OR_PLUS_REGEX, "") else input
  }
}
