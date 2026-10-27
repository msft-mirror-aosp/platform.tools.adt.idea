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
package com.android.tools.profilers.taskbased.common.table

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions.TABLE_ROW_HEIGHT_DP
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings
import com.android.tools.profilers.taskbased.common.text.EllipsisText
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

/**
 * Gives a table row a height of at least [minHeight] that grows to fit its text, e.g. at a large IDE font size.
 *
 * The row still gets a definite height, so cells that fill it, like [LeftAlignedColumnText], [RightAlignedColumnText] and the header's
 * column dividers, span the whole row. The minimum is applied first, so that it raises the content's intrinsic height rather than capping
 * it.
 */
fun Modifier.tableRowHeight(minHeight: Dp = TABLE_ROW_HEIGHT_DP): Modifier = heightIn(min = minHeight).height(IntrinsicSize.Min)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeftAlignedColumnText(text: String, iconKey: IntelliJIconKey? = null, rowScope: RowScope) {
  with(rowScope) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 5.dp), contentAlignment = Alignment.CenterStart) {
      Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        iconKey?.let {
          Tooltip({ Text(TaskBasedUxStrings.PREFERRED_PROCESS_TOOLTIP) }) {
            Icon(it, contentDescription = TaskBasedUxStrings.PREFERRED_PROCESS_DESC)
          }
        }
        EllipsisText(text = text)
      }
    }
  }
}

@Composable
fun RightAlignedColumnText(text: String, colWidth: Dp) {
  Box(modifier = Modifier.width(colWidth).fillMaxHeight().padding(horizontal = 5.dp), contentAlignment = Alignment.CenterEnd) {
    EllipsisText(text = text)
  }
}
