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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.common.primaryContentBackground
import com.android.tools.adtui.compose.StudioComposePanel
import com.android.tools.idea.findings.model.AffectedScope
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.findings.model.DeviceInfo
import com.android.tools.idea.findings.model.FindingOccurrence
import com.android.tools.idea.findings.model.VisualEvidence
import com.android.tools.idea.insights.ui.DETAIL_PANEL_HORIZONTAL_SPACING
import com.android.tools.idea.insights.ui.StackTraceConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import icons.StudioIconsCompose
import javax.swing.JComponent
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@VisibleForTesting internal const val EMPTY_DETAILS_TEXT = "Select a finding to view details."

private val CONTENT_PADDING = 12.dp
private val SECTION_SPACING = 10.dp
private val SUB_TAB_SECTION_SPACING = 8.dp
private val MIN_STACK_TRACE_HEIGHT = 220.dp

internal sealed interface FindingDetailSubTab {
  enum class Type(val title: String) {
    STACK_TRACE("Stack trace"),
    VISUAL_EVIDENCES("Screenshots"),
  }

  val type: Type

  data class StackTrace(val rawStackTrace: String) : FindingDetailSubTab {
    override val type: Type = Type.STACK_TRACE
  }

  data class VisualEvidences(val evidences: List<VisualEvidence>) : FindingDetailSubTab {
    override val type: Type = Type.VISUAL_EVIDENCES
  }
}

/**
 * Displays the details of a selected [AppFinding] using two vertical sections:
 * 1. [FindingSummarySection]: Always-shown finding-level summary (severity, title, "Ask AI" action, and description) shared by all
 *    findings.
 * 2. [FindingOccurrencesSection]: Optional event/occurrence details panel shown when [AppFinding.context] has one or more
 *    [FindingOccurrence]s, rendering the occurrence switcher (`1 of N`), device and API level metadata, and per-occurrence evidence
 *    sub-tabs (`Stack trace`, `Screenshots`).
 */
class FindingsDetailsPanel(
  parentDisposable: Disposable,
  private val project: Project,
  private val onAskAiClick: (AppFinding) -> Unit = {},
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

  /** Renders the empty state when no finding is selected, or the scrollable summary and occurrence sections for [currentFinding]. */
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
    var summarySectionHeightPx by remember { mutableIntStateOf(0) }
    var dividerHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val viewportHeight = maxHeight
      Row(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(CONTENT_PADDING),
          verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
          // Summary section (top): Finding-level overview (severity, title, "Ask AI" action, and
          // description) always shown for every finding.
          FindingSummarySection(
            finding = finding,
            onAskAiClick = onAskAiClick,
            modifier = Modifier.fillMaxWidth().testTag("finding_summary_section").onSizeChanged { summarySectionHeightPx = it.height },
          )

          // Event / occurrence details panel (bottom): Per-occurrence metadata (device, API level)
          // and evidence sub-tabs (e.g., stack traces, screenshots) shown when the finding has
          // individual occurrences.
          if (finding.context.occurrences.isNotEmpty()) {
            Divider(
              Orientation.Horizontal,
              modifier = Modifier.fillMaxWidth().onSizeChanged { dividerHeightPx = it.height },
            )
            val summaryHeightDp = with(density) { summarySectionHeightPx.toDp() }
            val dividerHeightDp = with(density) { dividerHeightPx.toDp() }
            val remainingViewportHeight = viewportHeight - summaryHeightDp - dividerHeightDp - CONTENT_PADDING * 2 - SECTION_SPACING * 2
            FindingOccurrencesSection(
              finding = finding,
              project = project,
              stackTraceConsoleView = stackTraceConsoleView,
              remainingViewportHeight = remainingViewportHeight,
              modifier = Modifier.fillMaxWidth().testTag("finding_occurrences_section"),
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
}

/** Renders the finding-level summary (severity icon, title, affected scopes, description, and right-aligned "Ask AI" action). */
@Composable
private fun FindingSummarySection(
  finding: AppFinding,
  onAskAiClick: (AppFinding) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().testTag("finding_title_row"),
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
        modifier = Modifier.weight(1f).testTag("finding_title"),
      )
    }

    FindingAffectedScopesRow(affectedScopes = finding.affectedScopes)

    Row(
      modifier = Modifier.fillMaxWidth().testTag("finding_description_row"),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SelectionContainer(modifier = Modifier.weight(1f)) {
        Text(
          text = finding.description,
          modifier = Modifier.testTag("finding_description"),
        )
      }
      OutlinedButton(onClick = { onAskAiClick(finding) }, modifier = Modifier.testTag("ask_ai_button")) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            key = StudioIconsCompose.StudioBot.GenericAiAction,
            contentDescription = null,
          )
          Text(text = "Ask AI", maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

@Composable
private fun FindingAffectedScopesRow(affectedScopes: List<AffectedScope>) {
  val text = formatAffectedScopes(affectedScopes) ?: return

  SelectionContainer {
    Text(
      text = text,
      modifier = Modifier.fillMaxWidth().testTag("finding_affected_scopes"),
    )
  }
}

// TODO(b/517599039): Revisit this wording/formatting to group each release with its associated version code(s) once
// InAppLocation.Release nests artifacts in the synced API proto.
@VisibleForTesting
internal fun formatAffectedScopes(affectedScopes: List<AffectedScope>): String? {
  val releases =
    affectedScopes.filterIsInstance<AffectedScope.Release>().map { it.releaseName.trim() }.filter { it.isNotEmpty() }.distinct()
  val versionCodes =
    affectedScopes.filterIsInstance<AffectedScope.Artifact>().map { it.versionCode.trim() }.filter { it.isNotEmpty() }.distinct()

  return buildList {
      if (releases.isNotEmpty()) {
        val label = if (releases.size == 1) "Affected release" else "Affected releases"
        add("$label: ${releases.joinToString(", ")}")
      }
      if (versionCodes.isNotEmpty()) {
        val label = if (versionCodes.size == 1) "Affected version code" else "Affected version codes"
        add("$label: ${versionCodes.joinToString(", ")}")
      }
    }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" • ")
}

/** Renders the occurrence selector, device/API metadata, and evidence sub-tabs (`Stack trace`, `Screenshots`) for [finding]. */
@Composable
private fun FindingOccurrencesSection(
  finding: AppFinding,
  project: Project,
  stackTraceConsoleView: StackTraceConsoleView,
  remainingViewportHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val occurrences = finding.context.occurrences
  var selectedOccurrenceIndex by remember(finding) { mutableIntStateOf(0) }
  var selectedTabType by remember(finding) { mutableStateOf(occurrences.firstOrNull()?.availableSubTabs()?.firstOrNull()?.type) }

  val activeOccurrence = occurrences.getOrNull(selectedOccurrenceIndex) ?: return
  val availableTabs = activeOccurrence.availableSubTabs()
  val activeTab = availableTabs.firstOrNull { it.type == selectedTabType } ?: availableTabs.firstOrNull()

  var headerHeightPx by remember { mutableIntStateOf(0) }
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(SUB_TAB_SECTION_SPACING),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height },
      verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
      FindingOccurrenceSelectorBar(
        occurrenceCount = occurrences.size,
        selectedIndex = selectedOccurrenceIndex,
        deviceInfo = activeOccurrence.deviceInfo,
        onSelectIndex = { selectedOccurrenceIndex = it },
      )
      if (activeTab != null) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            TabStrip(
              tabs =
                availableTabs.map { tab ->
                  TabData.Default(
                    selected = activeTab.type == tab.type,
                    content = { Text(tab.type.title) },
                    onClick = { selectedTabType = tab.type },
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
      }
    }

    when (activeTab) {
      is FindingDetailSubTab.StackTrace -> {
        val headerHeightDp = with(LocalDensity.current) { headerHeightPx.toDp() }
        val stackTraceHeight = (remainingViewportHeight - headerHeightDp - SUB_TAB_SECTION_SPACING).coerceAtLeast(MIN_STACK_TRACE_HEIGHT)
        FindingStackTracePanel(
          stackTrace = activeTab.rawStackTrace,
          consoleView = stackTraceConsoleView,
          modifier = Modifier.fillMaxWidth().height(stackTraceHeight),
        )
      }
      is FindingDetailSubTab.VisualEvidences -> {
        FindingVisualEvidencesPanel(
          finding = finding,
          evidences = activeTab.evidences,
          selectedOccurrenceIndex = selectedOccurrenceIndex,
          totalOccurrences = occurrences.size,
          project = project,
        )
      }
      null -> Unit
    }
  }
}

/** Renders the "Observed occurrence" header, optional `1 of N` switcher, and the active occurrence's metadata row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FindingOccurrenceSelectorBar(
  occurrenceCount: Int,
  selectedIndex: Int,
  deviceInfo: DeviceInfo?,
  onSelectIndex: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().testTag("finding_occurrence_bar"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Observed occurrence",
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f).testTag("occurrences_header"),
      )
      if (occurrenceCount > 1) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = "${selectedIndex + 1} of $occurrenceCount",
            color = JewelTheme.globalColors.text.info,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 4.dp).testTag("occurrence_counter"),
          )
          Tooltip(tooltip = { Text("Previous occurrence") }) {
            IconButton(
              onClick = { onSelectIndex(selectedIndex - 1) },
              enabled = selectedIndex > 0,
              modifier = Modifier.testTag("previous_occurrence_button"),
            ) {
              Icon(
                key = AllIconsKeys.General.ChevronLeft,
                contentDescription = "Previous occurrence",
              )
            }
          }
          Tooltip(tooltip = { Text("Next occurrence") }) {
            IconButton(
              onClick = { onSelectIndex(selectedIndex + 1) },
              enabled = selectedIndex < occurrenceCount - 1,
              modifier = Modifier.testTag("next_occurrence_button"),
            ) {
              Icon(
                key = AllIconsKeys.General.ChevronRight,
                contentDescription = "Next occurrence",
              )
            }
          }
        }
      }
    }

    FindingOccurrenceMetadataRow(deviceInfo)
  }
}

/** Renders the affected device name and Android/API level for an occurrence, falling back to `"Unknown"` when absent. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FindingOccurrenceMetadataRow(device: DeviceInfo?) {
  val deviceName = device?.displayName?.ifBlank { null } ?: "Unknown"
  val androidVersion = device?.androidVersionDisplayText?.ifBlank { null } ?: "Unknown"

  FlowRow(
    modifier = Modifier.fillMaxWidth().testTag("occurrence_metadata_row"),
    horizontalArrangement = Arrangement.spacedBy(DETAIL_PANEL_HORIZONTAL_SPACING.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Tooltip(tooltip = { Text("Affected Device") }) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          key = StudioIconsCompose.LayoutEditor.Toolbar.DeviceScreen,
          contentDescription = "Affected Device",
        )
        Text(
          text = deviceName,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag("occurrence_device_label"),
        )
      }
    }
    Tooltip(tooltip = { Text("Affected API Level") }) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          key = StudioIconsCompose.LayoutEditor.Toolbar.AndroidApi,
          contentDescription = "Affected API Level",
        )
        Text(
          text = androidVersion,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag("occurrence_api_level_label"),
        )
      }
    }
  }
}

/** Renders an occurrence's visual evidences in a horizontally scrollable row, with optional step captions and issue highlight. */
@Composable
private fun FindingVisualEvidencesPanel(
  finding: AppFinding,
  evidences: List<VisualEvidence>,
  selectedOccurrenceIndex: Int,
  totalOccurrences: Int,
  project: Project,
) {
  val horizontalScrollState = remember(finding, selectedOccurrenceIndex) { ScrollState(0) }
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // TODO(b/517599039): Consider LazyRow with fixed-width frames if findings have many screenshots to avoid eager downloads.
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(horizontalScrollState),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.Top,
    ) {
      val findingId = finding.name.substringAfterLast('/').takeIf { it.isNotBlank() }
      evidences.forEachIndexed { index, evidence ->
        val fileName =
          if (findingId != null) {
            if (totalOccurrences > 1) {
              "finding_${findingId}_occurrence_${selectedOccurrenceIndex + 1}_screenshot_${index + 1}.png"
            } else {
              "finding_${findingId}_screenshot_${index + 1}.png"
            }
          } else {
            "screenshot_${index + 1}.png"
          }
        Column(
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = if (evidence.highlighted) Modifier.testTag("highlighted_visual_evidence") else Modifier,
        ) {
          evidence.label
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
              Text(
                text = label,
                color = if (evidence.highlighted) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
                fontWeight = if (evidence.highlighted) FontWeight.Medium else null,
                modifier = Modifier.testTag("visual_evidence_label"),
              )
            }
          ImagePreview(
            imageUrl = evidence.url,
            fileName = fileName,
            project = project,
          )
        }
      }
    }
    HorizontalScrollbar(
      adapter = rememberScrollbarAdapter(horizontalScrollState),
      modifier = Modifier.fillMaxWidth().testTag("screenshots_horizontal_scrollbar"),
    )
  }
}

private fun FindingOccurrence.availableSubTabs(): List<FindingDetailSubTab> = buildList {
  stackTrace?.let { add(FindingDetailSubTab.StackTrace(it.raw)) }
  val validEvidences = visualEvidences.filter { it.url.isNotBlank() }
  if (validEvidences.isNotEmpty()) {
    add(FindingDetailSubTab.VisualEvidences(validEvidences))
  }
}
