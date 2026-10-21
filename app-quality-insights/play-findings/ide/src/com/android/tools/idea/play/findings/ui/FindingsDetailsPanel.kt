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
package com.android.tools.idea.play.findings.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.common.primaryContentBackground
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.findings.model.AppFinding
import javax.swing.JComponent
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.badgeStyle

@VisibleForTesting internal const val EMPTY_DETAILS_TEXT = "Select a finding to view details."

/** Displays the details of a selected [AppFinding], including its title, severity icon, category badge, and description. */
class FindingsDetailsPanel {

  @get:VisibleForTesting
  internal var currentFinding by mutableStateOf<AppFinding?>(null)
    private set

  val component: JComponent = StudioComposePanel {
    FindingsDetailsContent()
  }
    .apply {
      background = primaryContentBackground
    }

  /** Updates the panel to display the details of [finding], or resets to the empty state when [finding] is `null`. */
  fun updateFinding(finding: AppFinding?) {
    currentFinding = finding
  }

  @VisibleForTesting
  @Composable
  internal fun FindingsDetailsContent() {
    val finding = currentFinding
    if (finding == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = EMPTY_DETAILS_TEXT,
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.testTag("empty_details_text"),
        )
      }
      return
    }

    val scrollState = remember(finding) { ScrollState(0) }
    Row(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.testTag("finding_title_row"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            key = finding.severity.iconKey,
            contentDescription = finding.severity.name,
            modifier = Modifier.testTag("finding_severity_icon"),
          )
          Text(
            text = finding.displayName,
            fontWeight = FontWeight.Bold,
            fontSize = JewelTheme.defaultTextStyle.fontSize * 1.15f,
            modifier = Modifier.testTag("finding_title"),
          )
        }

        Badge(
          style = JewelTheme.badgeStyle.purpleSecondary,
          textStyle = JewelTheme.defaultTextStyle,
          modifier = Modifier.testTag("finding_category_badge"),
        ) {
          Text(
            text = finding.category.displayName,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
          )
        }

        SelectionContainer {
          Text(
            text = finding.details,
            modifier = Modifier.testTag("finding_description"),
          )
        }
      }
      VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.fillMaxHeight(),
      )
    }
  }
}
