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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.common.primaryContentBackground
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.FindingData
import com.android.tools.idea.findings.model.VisualEvidence
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@VisibleForTesting internal const val EMPTY_DETAILS_TEXT = "Select a finding to view details."

internal sealed interface FindingDetailSubTab {
  val title: String

  data class StackTrace(val rawStackTrace: String) : FindingDetailSubTab {
    override val title: String = "Stack trace"
  }

  data class VisualEvidences(val evidences: List<VisualEvidence>) : FindingDetailSubTab {
    override val title: String = "Visual evidence"
  }
}

/**
 * Displays the details of a selected [AppFinding], including its title, severity icon, category badge, description, and optional sub-tabs
 * (such as crash stack trace and visual evidence).
 */
class FindingsDetailsPanel(
  parentDisposable: Disposable,
  private val project: Project,
) {

  @get:VisibleForTesting
  internal var currentFinding by mutableStateOf<AppFinding?>(null)
    private set

  @get:VisibleForTesting
  internal val stackTraceConsoleView = createFindingStackTraceConsoleView(project = project, parentDisposable = parentDisposable)

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
    val density = LocalDensity.current
    var headerSectionHeightPx by remember { mutableIntStateOf(0) }
    val availableTabs = remember(finding) { getAvailableSubTabs(finding.findingData) }
    var selectedTab by remember(finding) { mutableStateOf(availableTabs.firstOrNull()) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val viewportHeight = maxHeight
      Row(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { headerSectionHeightPx = it.height },
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

          selectedTab?.let { activeTab ->
            Column(
              modifier = Modifier.fillMaxWidth().testTag("finding_sub_tabs_section"),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  TabStrip(
                    tabs =
                      availableTabs.map { tab ->
                        TabData.Default(
                          selected = activeTab == tab,
                          content = { Text(tab.title) },
                          onClick = { selectedTab = tab },
                          closable = false,
                        )
                      },
                    style = JewelTheme.defaultTabStyle,
                    modifier = Modifier.weight(1f).testTag("finding_sub_tab_strip"),
                  )
                  if (activeTab is FindingDetailSubTab.StackTrace) {
                    FindingStackTraceSoftWrapButton(consoleView = stackTraceConsoleView)
                  }
                }
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
              }

              when (activeTab) {
                is FindingDetailSubTab.StackTrace -> {
                  val headerHeightDp = with(density) { headerSectionHeightPx.toDp() }
                  val stackTraceHeight = (viewportHeight - headerHeightDp - 64.dp).coerceAtLeast(220.dp)
                  FindingStackTracePanel(
                    stackTrace = activeTab.rawStackTrace,
                    consoleView = stackTraceConsoleView,
                    modifier = Modifier.fillMaxWidth().height(stackTraceHeight),
                  )
                }
                is FindingDetailSubTab.VisualEvidences -> {
                  // TODO(b/517599039): Add a label above each screenshot once the API provides per-screenshot context (e.g. device/fold
                  //  state), and consider LazyRow with fixed-width frames if findings have many screenshots to avoid eager downloads.
                  Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(remember(finding) { ScrollState(0) }),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    val findingId = finding.name.substringAfterLast('/').takeIf { it.isNotBlank() }
                    activeTab.evidences.forEachIndexed { index, evidence ->
                      val fileName =
                        if (findingId != null) {
                          "finding_${findingId}_screenshot_${index + 1}.png"
                        } else {
                          "screenshot_${index + 1}.png"
                        }
                      ImagePreview(
                        imageUrl = evidence.url,
                        fileName = fileName,
                        project = project,
                      )
                    }
                  }
                }
              }
            }
          }
        }
        VerticalScrollbar(
          adapter = rememberScrollbarAdapter(scrollState),
          modifier = Modifier.fillMaxHeight(),
        )
      }
    }
  }
}

private fun getAvailableSubTabs(findingData: FindingData): List<FindingDetailSubTab> =
  when (findingData) {
    is FindingData.LargeScreens ->
      buildList {
        findingData.stackTrace?.let { add(FindingDetailSubTab.StackTrace(it.raw)) }
        val validEvidences = findingData.visualEvidences.filter { it.url.isNotBlank() }
        if (validEvidences.isNotEmpty()) {
          add(FindingDetailSubTab.VisualEvidences(validEvidences))
        }
      }
    else -> emptyList()
  }
