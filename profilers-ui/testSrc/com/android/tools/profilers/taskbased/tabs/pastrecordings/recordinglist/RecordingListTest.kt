/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.profilers.taskbased.tabs.pastrecordings.recordinglist

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.android.testutils.ignore.IgnoreTestRule
import com.android.tools.adtui.compose.StudioTestTheme
import com.android.tools.adtui.compose.standaloneSingleWindowApplication
import com.android.tools.adtui.compose.utils.StudioComposeTestRule.Companion.createStudioComposeTestRule
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.SessionArtifactUtils.createSessionItemWithSystemTraceArtifact
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.taskbased.home.selections.recordings.RecordingListModel
import com.android.tools.profilers.tasks.taskhandlers.ProfilerTaskHandlerFactory
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class RecordingListTest {
  private val myTimer = FakeTimer()
  private val myTransportService = FakeTransportService(myTimer, false)

  @get:Rule val composeTestRule = createStudioComposeTestRule()

  @get:Rule val ignoreTestRule = IgnoreTestRule()

  @get:Rule var myGrpcChannel = FakeGrpcChannel("TaskGridViewTestChannel", myTransportService)

  @get:Rule val applicationRule = ApplicationRule()

  private lateinit var myProfilers: StudioProfilers
  private lateinit var myManager: SessionsManager
  private lateinit var ideProfilerServices: FakeIdeProfilerServices
  private lateinit var recordingListModel: RecordingListModel
  private var openProfilerTaskCount = 0

  @Before
  fun setup() {
    ideProfilerServices = FakeIdeProfilerServices()
    myProfilers = StudioProfilers(ProfilerClient(myGrpcChannel.channel), ideProfilerServices, myTimer)
    myManager = myProfilers.sessionsManager
    val taskHandlers = ProfilerTaskHandlerFactory.createTaskHandlers(myManager)
    taskHandlers.forEach { myProfilers.addTaskHandler(it.key, it.value) }
    recordingListModel = RecordingListModel(myProfilers, taskHandlers, {}) { openProfilerTaskCount++ }
    ideProfilerServices.enableTaskBasedUx(true)
  }

  @Ignore("b/309566948")
  @Test
  fun `visual test, dark theme`() {
    recordingListModel.setRecordingList(listOf(createSessionItemWithSystemTraceArtifact("Recording 1", 1L, 1L, myProfilers)))
    standaloneSingleWindowApplication(title = "Testing TaskGridView") {
      StudioTestTheme(darkMode = true) { RecordingList(recordingListModel = recordingListModel) }
    }
  }

  @Test
  fun `test import file renders in UI and is reflected in data model`() {
    composeTestRule.setContent(darkMode = true) { RecordingList(recordingListModel) }

    recordingListModel.setRecordingList(listOf(createSessionItemWithSystemTraceArtifact("Recording 1", 1L, 1L, myProfilers)))

    // Assert both the data model and the UI reflect the past recording entry.
    assertThat(recordingListModel.recordingList.value).hasSize(1)
    composeTestRule.onAllNodesWithTag("RecordingListRow", useUnmergedTree = true).assertCountEquals(1)
  }

  @Test
  fun `test double-clicking a recording selects and opens it`() {
    composeTestRule.setContent(darkMode = true) { RecordingList(recordingListModel) }

    val recording = createSessionItemWithSystemTraceArtifact("Recording 1", 1L, 1L, myProfilers)
    recordingListModel.setRecordingList(listOf(recording))

    composeTestRule.onAllNodesWithTag("RecordingListRow", useUnmergedTree = true)[0].performTouchInput { doubleClick() }

    // Double-clicking should behave like selecting the recording and clicking the "Open profiler task" button.
    assertThat(recordingListModel.selectedRecording.value).isEqualTo(recording)
    assertThat(openProfilerTaskCount).isEqualTo(1)
  }

  @Test
  fun `test single click selects a recording without opening it`() {
    composeTestRule.setContent(darkMode = true) { RecordingList(recordingListModel) }

    val recording = createSessionItemWithSystemTraceArtifact("Recording 1", 1L, 1L, myProfilers)
    recordingListModel.setRecordingList(listOf(recording))

    composeTestRule.onAllNodesWithTag("RecordingListRow", useUnmergedTree = true)[0].performClick()

    assertThat(recordingListModel.selectedRecording.value).isEqualTo(recording)
    assertThat(openProfilerTaskCount).isEqualTo(0)
  }

  @Test
  fun `test triple-clicking a recording opens it only once`() {
    composeTestRule.setContent(darkMode = true) { RecordingList(recordingListModel) }

    recordingListModel.setRecordingList(listOf(createSessionItemWithSystemTraceArtifact("Recording 1", 1L, 1L, myProfilers)))

    composeTestRule.onAllNodesWithTag("RecordingListRow", useUnmergedTree = true)[0].performTouchInput {
      click()
      advanceEventTime(100)
      click()
      advanceEventTime(100)
      click()
    }

    // The third click starts a new click sequence rather than opening the recording a second time.
    assertThat(openProfilerTaskCount).isEqualTo(1)
  }
}
