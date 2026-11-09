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
package com.android.tools.profilers.taskbased.tabs.task.leakcanary.banner

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.profilers.leakcanary.MIN_STUDIO_LEAKCANARY_VERSION
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings.LEAKCANARY_OPEN_BUILD_FILE
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings.getLeakCanaryOutdatedLibraryMessage
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Warning banner shown when the app's studio-leakcanary is older than [MIN_STUDIO_LEAKCANARY_VERSION]. It has an "Open build file" link
 * when [showOpenBuildFile] is true, and a close button. It has no "Don't show again", because the update is needed.
 */
@Composable
fun OutdatedLibraryBanner(
  foundVersion: String,
  showOpenBuildFile: Boolean,
  onOpenBuildFileClick: () -> Unit,
  onBannerClose: () -> Unit,
) {
  InlineWarningBanner(
    iconActions = { iconAction(icon = AllIconsKeys.General.Close, contentDescription = "Close", onClick = onBannerClose) },
    modifier = Modifier.fillMaxWidth(),
  ) {
    // The banner puts its own link actions on a new line, so the link is placed here to keep it on the same line as the text.
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(getLeakCanaryOutdatedLibraryMessage(foundVersion, MIN_STUDIO_LEAKCANARY_VERSION), modifier = Modifier.weight(1f, fill = false))
      if (showOpenBuildFile) {
        Spacer(modifier = Modifier.width(8.dp))
        Link(LEAKCANARY_OPEN_BUILD_FILE, onClick = onOpenBuildFileClick)
      }
    }
  }
}
