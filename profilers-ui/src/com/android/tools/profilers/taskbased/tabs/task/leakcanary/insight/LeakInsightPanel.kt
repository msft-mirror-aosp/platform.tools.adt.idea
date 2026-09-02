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
package com.android.tools.profilers.taskbased.tabs.task.leakcanary.insight

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.android.tools.profilers.leakcanary.AiInsight
import com.android.tools.profilers.leakcanary.InsightFeedback
import com.android.tools.profilers.leakcanary.LoadingState
import com.android.tools.profilers.taskbased.common.constants.dimensions.TaskBasedUxDimensions
import com.android.tools.profilers.taskbased.common.constants.strings.TaskBasedUxStrings
import com.android.tools.profilers.taskbased.common.dividers.ToolWindowHorizontalDivider
import icons.StudioIconsCompose
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalJewelApi::class)
@Composable
fun LeakInsightPanel(
  insightState: LoadingState<AiInsight?>,
  isLeakSelected: Boolean,
  autoGenerateEnabled: Boolean,
  onAutoGenerateChange: (Boolean) -> Unit,
  onClose: () -> Unit,
  onFeedback: (InsightFeedback?) -> Unit,
  onGenerateFix: (String) -> Unit,
  onCopy: () -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  onEnableInsights: () -> Unit = {},
  checkAiAvailable: () -> Boolean = { false },
  onAiAvailable: () -> Unit = onRefresh,
  markdownDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  val clipboardManager = LocalClipboardManager.current
  val currentInsight = (insightState as? LoadingState.Ready)?.value
  var lastInsight by remember { mutableStateOf<AiInsight?>(null) }
  if (currentInsight != null) {
    lastInsight = currentInsight
  }

  val failureMessage = (insightState as? LoadingState.Failure)?.message
  var lastFailureMessage by remember { mutableStateOf<String?>(null) }
  if (failureMessage != null) {
    lastFailureMessage = failureMessage
  }

  // When unauthorized, check for AI availability upon window focus (e.g. after user completes
  // the onboarding/sign-in flow in the browser) and periodically check for up to 1 minute to
  // seamlessly detect completion of in-IDE onboarding steps (e.g. Terms of Service, project selection).
  if (insightState is LoadingState.Unauthorized) {
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
      if (windowInfo.isWindowFocused) {
        repeat(6) {
          if (checkAiAvailable()) {
            onAiAvailable()
            return@LaunchedEffect
          }
          delay(500)
        }
      }
    }

    LaunchedEffect(insightState) {
      repeat(60) {
        delay(1.seconds)
        if (checkAiAvailable()) {
          onAiAvailable()
          return@LaunchedEffect
        }
      }
    }
  }

  Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
    InsightHeader(onClose = onClose, modifier = Modifier.fillMaxWidth())
    ToolWindowHorizontalDivider()

    val screenState =
      when (insightState) {
        is LoadingState.Loading -> InsightScreenState.LOADING
        is LoadingState.Unauthorized -> InsightScreenState.ONBOARDING_REQUIRED
        is LoadingState.Failure -> InsightScreenState.FAILURE
        is LoadingState.Ready -> if (insightState.value == null) InsightScreenState.EMPTY else InsightScreenState.CONTENT
      }

    Crossfade(targetState = screenState, modifier = Modifier.weight(1f)) { state ->
      Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
          InsightScreenState.LOADING -> InsightLoadingState(modifier = Modifier.fillMaxSize())
          InsightScreenState.ONBOARDING_REQUIRED -> {
            InsightOnboardingState(
              onEnableInsights = onEnableInsights,
              modifier = Modifier.fillMaxSize(),
            )
          }
          InsightScreenState.CONTENT -> {
            val insight = currentInsight ?: lastInsight
            if (insight != null) {
              InsightContent(
                insight = insight,
                onFeedback = onFeedback,
                onCopyClick = {
                  clipboardManager.setText(AnnotatedString(insight.rawInsight))
                  onCopy()
                },
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                markdownDispatcher = markdownDispatcher,
              )
            }
          }
          InsightScreenState.EMPTY -> {
            InsightEmptyState(
              isLeakSelected = isLeakSelected,
              autoGenerateEnabled = autoGenerateEnabled,
              onAutoGenerateChange = onAutoGenerateChange,
              onRefresh = onRefresh,
              modifier = Modifier.fillMaxSize(),
            )
          }
          InsightScreenState.FAILURE -> {
            val message = failureMessage ?: lastFailureMessage ?: "Unknown error"
            InsightFailureState(errorMessage = message, onRefresh = onRefresh, modifier = Modifier.fillMaxSize())
          }
        }
      }
    }

    ToolWindowHorizontalDivider()

    InsightFooter(
      isFixEnabled = currentInsight != null,
      onGenerateFix = { currentInsight?.rawInsight?.let { onGenerateFix(it) } },
      autoGenerateEnabled = autoGenerateEnabled,
      onAutoGenerateChange = onAutoGenerateChange,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun InsightHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.padding(horizontal = 8.dp).height(TaskBasedUxDimensions.LEAKCANARY_PANE_HEADER_HEIGHT_DP),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = TaskBasedUxStrings.LEAKCANARY_INSIGHTS_TITLE, modifier = Modifier.weight(1f))
    IconButton(onClick = onClose) { Icon(key = AllIconsKeys.General.HideToolWindow, contentDescription = TaskBasedUxStrings.LEAKCANARY_MINIMIZE_INSIGHTS_DESC) }
  }
}

@Composable
private fun InsightLoadingState(modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(TaskBasedUxStrings.LEAKCANARY_GENERATING_INSIGHT) }
}

@Composable
private fun InsightEmptyState(
  isLeakSelected: Boolean,
  autoGenerateEnabled: Boolean,
  onAutoGenerateChange: (Boolean) -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (isLeakSelected) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(text = TaskBasedUxStrings.LEAKCANARY_FROM_AI_ASSISTANT, color = JewelTheme.globalColors.text.info, fontWeight = FontWeight.Medium)
      Spacer(modifier = Modifier.height(8.dp))
      ToolWindowHorizontalDivider()
      Spacer(modifier = Modifier.height(8.dp))

      if (!autoGenerateEnabled) {
        Text(
          text = TaskBasedUxStrings.LEAKCANARY_INSIGHT_AUTO_GEN_DISABLED,
          fontStyle = FontStyle.Italic,
          color = JewelTheme.globalColors.text.normal.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Link(text = TaskBasedUxStrings.LEAKCANARY_GENERATE_INSIGHT_LINK, onClick = onRefresh)
          Link(text = TaskBasedUxStrings.LEAKCANARY_ENABLE_AUTO_GEN_LINK, onClick = { onAutoGenerateChange(true) })
        }
      } else {
        Text(
          text = TaskBasedUxStrings.LEAKCANARY_NO_INSIGHT_GENERATED_YET,
          fontStyle = FontStyle.Italic,
          color = JewelTheme.globalColors.text.normal.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Link(text = TaskBasedUxStrings.LEAKCANARY_GENERATE_INSIGHT_LINK, onClick = onRefresh)
      }
    }
  } else {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(TaskBasedUxStrings.LEAKCANARY_SELECT_LEAK_FOR_INSIGHT) }
  }
}

@Composable
private fun InsightFailureState(errorMessage: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
      Text(
        text = TaskBasedUxStrings.LEAKCANARY_FAILED_TO_GENERATE_INSIGHT_TITLE,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = JewelTheme.globalColors.text.normal,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = errorMessage,
        textAlign = TextAlign.Center,
        color = JewelTheme.globalColors.text.normal.copy(alpha = 0.7f),
      )
      Spacer(modifier = Modifier.height(16.dp))
      IconButton(onClick = onRefresh) { Icon(key = AllIconsKeys.Actions.Refresh, contentDescription = TaskBasedUxStrings.LEAKCANARY_RETRY_ANALYSIS_DESC) }
    }
  }
}

@Composable
private fun InsightContent(
  insight: AiInsight,
  onFeedback: (InsightFeedback?) -> Unit,
  onCopyClick: () -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  markdownDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  val scrollState = rememberScrollState()
  LaunchedEffect(insight.rawInsight) { scrollState.scrollTo(0) }
  Box(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(text = TaskBasedUxStrings.LEAKCANARY_FROM_MODEL_NAME_FORMAT.format(insight.modelName), color = JewelTheme.globalColors.text.info, fontWeight = FontWeight.Medium)
      Spacer(modifier = Modifier.height(8.dp))
      ToolWindowHorizontalDivider()
      Spacer(modifier = Modifier.height(8.dp))
      Markdown(markdown = insight.rawInsight, selectable = true, modifier = Modifier.fillMaxWidth(), processingDispatcher = markdownDispatcher)
      Spacer(modifier = Modifier.height(16.dp))
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        InsightFeedbackToolbar(feedback = insight.feedback, onFeedback = onFeedback, onCopyClick = onCopyClick, onRefresh = onRefresh)
      }
    }
    VerticalScrollbar(scrollState = scrollState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InsightActionButton(
  tooltipText: String,
  iconKey: IntelliJIconKey,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isSelected: Boolean = false,
) {
  Tooltip(tooltip = { Text(tooltipText) }) {
    IconButton(
      onClick = onClick,
      modifier =
        modifier.then(
          if (isSelected) {
            Modifier.background(color = JewelTheme.globalColors.text.info.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
              .border(width = 1.dp, color = JewelTheme.globalColors.text.info.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp))
          } else {
            Modifier
          }
        ),
    ) {
      Icon(key = iconKey, contentDescription = tooltipText)
    }
  }
}

@Composable
private fun InsightFeedbackToolbar(
  feedback: InsightFeedback?,
  onFeedback: (InsightFeedback?) -> Unit,
  onCopyClick: () -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    InsightActionButton(
      tooltipText = TaskBasedUxStrings.LEAKCANARY_UPVOTE_INSIGHT,
      iconKey = StudioIconsCompose.Common.Like,
      onClick = { onFeedback(if (feedback == InsightFeedback.THUMBS_UP) null else InsightFeedback.THUMBS_UP) },
      isSelected = feedback == InsightFeedback.THUMBS_UP,
    )
    InsightActionButton(
      tooltipText = TaskBasedUxStrings.LEAKCANARY_DOWNVOTE_INSIGHT,
      iconKey = StudioIconsCompose.Common.Dislike,
      onClick = { onFeedback(if (feedback == InsightFeedback.THUMBS_DOWN) null else InsightFeedback.THUMBS_DOWN) },
      isSelected = feedback == InsightFeedback.THUMBS_DOWN,
    )
    InsightActionButton(
      tooltipText = TaskBasedUxStrings.LEAKCANARY_COPY_INSIGHT,
      iconKey = AllIconsKeys.Actions.Copy,
      onClick = onCopyClick,
    )
    InsightActionButton(
      tooltipText = TaskBasedUxStrings.LEAKCANARY_REGENERATE_INSIGHT,
      iconKey = AllIconsKeys.Actions.Refresh,
      onClick = onRefresh,
    )
  }
}

@Composable
private fun InsightFooter(
  isFixEnabled: Boolean,
  onGenerateFix: () -> Unit,
  autoGenerateEnabled: Boolean,
  onAutoGenerateChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Link(text = TaskBasedUxStrings.LEAKCANARY_FIX_WITH_AI, onClick = onGenerateFix, enabled = isFixEnabled)

    var isSettingsPopupVisible by remember { mutableStateOf(false) }
    Box {
      IconButton(onClick = { isSettingsPopupVisible = !isSettingsPopupVisible }) {
        Icon(key = StudioIconsCompose.Common.Settings, contentDescription = TaskBasedUxStrings.LEAKCANARY_INSIGHTS_SETTINGS)
      }

      if (isSettingsPopupVisible) {
        val popupPositionProvider = remember {
          object : PopupPositionProvider {
            override fun calculatePosition(
              anchorBounds: IntRect,
              windowSize: IntSize,
              layoutDirection: LayoutDirection,
              popupContentSize: IntSize,
            ): IntOffset {
              // Position the popup above the anchor, aligned to the right
              val x = anchorBounds.right - popupContentSize.width
              val y = anchorBounds.top - popupContentSize.height
              return IntOffset(x, y)
            }
          }
        }
        Popup(popupPositionProvider = popupPositionProvider, onDismissRequest = { isSettingsPopupVisible = false }) {
          Column(
            modifier =
              Modifier.border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .background(JewelTheme.globalColors.panelBackground, RoundedCornerShape(4.dp))
                .padding(12.dp)
                .widthIn(min = 250.dp)
          ) {
            Text(
              text = TaskBasedUxStrings.LEAKCANARY_INSIGHTS_SETTINGS,
              color = JewelTheme.globalColors.text.info,
              fontWeight = FontWeight.SemiBold,
              fontSize = 12.sp,
              modifier = Modifier.padding(bottom = 8.dp),
            )
            CheckboxRow(text = TaskBasedUxStrings.LEAKCANARY_AUTO_GENERATE_INSIGHT_SUMMARIES, checked = autoGenerateEnabled, onCheckedChange = onAutoGenerateChange)
          }
        }
      }
    }
  }
}

@Composable
private fun InsightOnboardingState(onEnableInsights: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
      Text(
        text = TaskBasedUxStrings.LEAKCANARY_INSIGHTS_REQUIRE_GEMINI,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = JewelTheme.globalColors.text.normal,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = TaskBasedUxStrings.LEAKCANARY_ENABLE_INSIGHTS_DESCRIPTION,
        textAlign = TextAlign.Center,
        color = JewelTheme.globalColors.text.normal.copy(alpha = 0.7f),
      )
      Spacer(modifier = Modifier.height(16.dp))
      DefaultButton(onClick = onEnableInsights) { Text(TaskBasedUxStrings.LEAKCANARY_ENABLE_INSIGHTS_BUTTON) }
    }
  }
}

private enum class InsightScreenState {
  LOADING,
  FAILURE,
  EMPTY,
  CONTENT,
  ONBOARDING_REQUIRED,
}
