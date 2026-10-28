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
package com.android.tools.idea.streaming.emulator

import com.android.sdklib.AndroidVersion
import com.android.testutils.GoldenImageRule
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.IconLoaderRule
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.core.ClipboardSynchronizationDisablementRule
import com.android.tools.idea.testing.disposable
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import java.awt.Cursor
import java.awt.Dimension
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Tests for [RotaryInputPanel]. */
@RunsInEdt
class RotaryInputPanelTest {

  companion object {
    @JvmField @ClassRule val iconRule = IconLoaderRule()
  }

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  private val goldenImageRule = GoldenImageRule("tools/adt/idea/streaming/testData/RotaryInputPanelTest/golden")

  @get:Rule
  val ruleChain =
    RuleChain(
      projectRule,
      ClipboardSynchronizationDisablementRule(),
      emulatorRule,
      goldenImageRule,
      EdtRule(),
    )

  private val project
    get() = projectRule.project

  private val testRootDisposable
    get() = projectRule.disposable

  @Test
  fun testRotaryInputPanel() {
    val avdFolder = FakeEmulator.createWatchAvd(emulatorRule.avdRoot, androidVersion = AndroidVersion(33, 0))
    val emulator = emulatorRule.newEmulator(avdFolder)
    emulator.start()
    val emulatorController = getControllerOf(emulator)
    waitForCondition(5.seconds) { emulatorController.connectionState == EmulatorController.ConnectionState.CONNECTED }

    val panel = EmulatorToolWindowPanel(testRootDisposable, project, emulatorController)
    panel.createContent(true)
    panel.size = Dimension(400, 600)
    val ui = FakeUi(panel, parentDisposable = testRootDisposable)
    ui.layoutAndDispatchEvents()

    val rotaryInputPanel = ui.getComponent<RotaryInputPanel>()
    assertThat(rotaryInputPanel.toolTipText).isEqualTo("Use mouse, trackpad or mouse wheel to turn the rotary input")
    goldenImageRule.assertImageSimilar("RotaryInputPanel", ui.render(rotaryInputPanel))

    // Locate the center of the rotary dial in root UI coordinates.
    val panelPos = ui.getPosition(rotaryInputPanel)
    val insets = rotaryInputPanel.insets
    val scale = 0.5
    val originX = panelPos.x + insets.left + (rotaryInputPanel.width - insets.left - insets.right - 184.0 * scale) / 2.0
    val originY = panelPos.y + insets.top + (rotaryInputPanel.height - insets.top - insets.bottom - 212.0 * scale) / 2.0
    val dialCenterX = (originX + 46.0 * scale).roundToInt()
    val dialCenterY = (originY + 106.0 * scale).roundToInt()
    val dragOffset = 35 // Outside the drawn dial radius (23), within the interaction radius (46).

    // Check cursor changes when hovering inside and outside the dial interaction area.
    ui.mouse.moveTo(panelPos.x + 5, panelPos.y + 5)
    assertThat(rotaryInputPanel.cursor).isEqualTo(Cursor.getDefaultCursor())
    ui.mouse.moveTo(dialCenterX, dialCenterY - dragOffset)
    assertThat(rotaryInputPanel.cursor).isEqualTo(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

    // Press at 12 o'clock (+90 degrees clockwise from initial 9 o'clock position).
    ui.mouse.press(dialCenterX, dialCenterY - dragOffset)
    val streamInputCall = emulator.getNextGrpcCall(2.seconds, FakeEmulator.IGNORE_SCREENSHOT_CALL_FILTER)
    assertThat(streamInputCall.methodName).isEqualTo("android.emulation.control.EmulatorController/streamInputEvent")
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag to 3 o'clock (+90 degrees clockwise).
    ui.mouse.dragTo(dialCenterX + dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag to 6 o'clock (+90 degrees clockwise).
    ui.mouse.dragTo(dialCenterX, dialCenterY + dragOffset)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -720 }")

    // Drag counter-clockwise back to 3 o'clock (-90 degrees).
    ui.mouse.dragTo(dialCenterX + dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")

    // Drag counter-clockwise back to 12 o'clock (-90 degrees) and then 9 o'clock (-90 degrees).
    ui.mouse.dragTo(dialCenterX, dialCenterY - dragOffset)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")
    ui.mouse.dragTo(dialCenterX - dragOffset, dialCenterY)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 720 }")
    ui.mouse.release()

    // Check mouse wheel rotation.
    ui.mouse.wheel(dialCenterX, dialCenterY, 1)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: -40 }")
    ui.mouse.wheel(dialCenterX, dialCenterY, -1)
    assertThat(shortDebugString(streamInputCall.getNextRequest(1.seconds))).isEqualTo("wheel_event { dy: 40 }")

    panel.destroyContent()
  }

  private fun getControllerOf(emulator: FakeEmulator): EmulatorController {
    val catalog = RunningEmulatorCatalog.getInstance()
    val emulators = runBlocking { catalog.updateNow().await() }
    return emulators.single { emulator.serialNumber == it.emulatorId.serialNumber }
  }
}
