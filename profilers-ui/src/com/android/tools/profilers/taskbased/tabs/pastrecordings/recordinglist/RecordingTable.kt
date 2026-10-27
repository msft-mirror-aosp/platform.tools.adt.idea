/*
 * Copyright (C) 2023 The Android Open Source Project
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

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.model.formatter.TimeFormatter
import com.android.tools.profilers.sessions.SessionItem
import com.android.tools.profilers.taskbased.common.constants.colors.TaskBasedUxColors.TABLE_HEADER_BACKGROUND_COLOR
import com.android.tools.profilers.taskbased.common.constants.colors.TaskBasedUxColors.TABLE_ROW_SELECTION_BACKGROUND_COLOR
import com.android.tools.profilers.taskbased.common.constants.colors.TaskBasedUxColors.TABLE_SEPARATOR_COLOR
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions.RECORDING_TASKS_COL_WIDTH_DP
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions.RECORDING_TIME_COL_WIDTH_DP
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions.TABLE_ROW_HEIGHT_DP
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions.TABLE_ROW_HORIZONTAL_PADDING_DP
import com.android.tools.profilers.taskbased.common.table.LeftAlignedColumnText
import com.android.tools.profilers.taskbased.common.table.RightAlignedColumnText
import com.intellij.util.ui.UIUtil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.jetbrains.jewel.foundation.lazy.SingleSelectionLazyColumn
import org.jetbrains.jewel.foundation.lazy.items
import org.jetbrains.jewel.foundation.lazy.rememberSingleSelectionLazyListState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/**
 * A row of the recording table. Clicking it twice within the platform's multi-click interval invokes [onDoubleClick].
 *
 * Single clicks are not delayed, and row selection is still handled on press by the enclosing [SingleSelectionLazyColumn]. The last click
 * is cleared once [onDoubleClick] fires, so that a triple-click does not trigger it twice.
 *
 * [onDoubleClick] runs on the second click's release rather than its press, because a modal dialog opened on press can swallow the release.
 */
@Composable
fun RecordingListRow(selectedRecording: SessionItem?, recording: SessionItem, onDoubleClick: () -> Unit) {
  var lastClickTimeMark by remember { mutableStateOf<TimeMark?>(null) }
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .height(TABLE_ROW_HEIGHT_DP)
        .background(if (recording == selectedRecording) TABLE_ROW_SELECTION_BACKGROUND_COLOR else Color.Transparent)
        // Keep keyboard focus on the enclosing list so that arrow-key navigation continues to work.
        .focusProperties { canFocus = false }
        .clickable(interactionSource = null, indication = null) {
          val lastClick = lastClickTimeMark
          if (lastClick != null && lastClick.elapsedNow() < UIUtil.getMultiClickInterval().milliseconds) {
            lastClickTimeMark = null
            onDoubleClick()
          } else {
            lastClickTimeMark = TimeSource.Monotonic.markNow()
          }
        }
        .padding(horizontal = TABLE_ROW_HORIZONTAL_PADDING_DP)
        .testTag("RecordingListRow")
  ) {
    LeftAlignedColumnText(recording.name, rowScope = this)
    RightAlignedColumnText(
      text = TimeFormatter.getLocalizedDateTime(recording.sessionMetaData.startTimestampEpochMs),
      colWidth = RECORDING_TIME_COL_WIDTH_DP,
    )
    RightAlignedColumnText(text = recording.getTaskType().description, colWidth = RECORDING_TASKS_COL_WIDTH_DP)
  }
}

@Composable
fun RecordingListHeader() {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .height(TABLE_ROW_HEIGHT_DP)
        .background(TABLE_HEADER_BACKGROUND_COLOR)
        .padding(horizontal = TABLE_ROW_HORIZONTAL_PADDING_DP)
  ) {
    LeftAlignedColumnText(text = "Recording name", rowScope = this)
    Divider(thickness = 1.dp, orientation = Orientation.Vertical, modifier = Modifier.fillMaxHeight())
    RightAlignedColumnText(text = "Recording time", colWidth = RECORDING_TIME_COL_WIDTH_DP)
    Divider(thickness = 1.dp, orientation = Orientation.Vertical, modifier = Modifier.fillMaxHeight())
    RightAlignedColumnText(text = "Recorded tasks", colWidth = RECORDING_TASKS_COL_WIDTH_DP)
  }
}

@Composable
fun RecordingTable(
  recordingList: List<SessionItem>,
  selectedRecording: SessionItem?,
  onRecordingSelection: (SessionItem?) -> Unit,
  onRecordingDoubleClick: (SessionItem) -> Unit,
) {
  val listState = rememberSingleSelectionLazyListState()

  Box {
    SingleSelectionLazyColumn(
      state = listState,
      onSelectedIndexesChange = {
        // The - 1 is to account for the sticky header.
        if (it.isNotEmpty() && it.first() > 0) {
          val newSelectedDeviceProcess = recordingList[it.first() - 1]
          onRecordingSelection(newSelectedDeviceProcess)
        }
      },
    ) {
      stickyHeader(key = Integer.MAX_VALUE) {
        RecordingListHeader()
        Divider(color = TABLE_SEPARATOR_COLOR, modifier = Modifier.fillMaxWidth(), thickness = 1.dp, orientation = Orientation.Horizontal)
      }
      items(items = recordingList) { recording ->
        RecordingListRow(selectedRecording, recording, onDoubleClick = { onRecordingDoubleClick(recording) })
      }
    }

    VerticalScrollbar(
      adapter = rememberScrollbarAdapter(listState.lazyListState),
      modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd),
    )
  }
}
