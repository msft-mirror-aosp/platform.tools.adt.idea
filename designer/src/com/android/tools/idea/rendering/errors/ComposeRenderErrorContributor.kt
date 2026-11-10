/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.rendering.errors

import com.android.tools.idea.rendering.StudioHtmlLinkManager
import com.android.tools.idea.rendering.errors.ui.MessageTip
import com.android.tools.idea.rendering.errors.ui.RenderErrorModel.Issue
import com.android.tools.rendering.HtmlLinkManager
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.compose.ComposeRenderErrorType
import com.android.tools.rendering.compose.ComposeRenderErrors
import com.android.utils.HtmlBuilder
import com.intellij.lang.annotation.HighlightSeverity
import javax.swing.event.HyperlinkListener

/**
 * Contributor for generating Compose-specific render errors.
 *
 * Detection and classification of the [Throwable]s is delegated to the shared, UI-independent [ComposeRenderErrors]; this object only maps
 * each [ComposeRenderErrorType] to its Android Studio presentation ([Issue] severity, HTML content and message tips).
 */
object ComposeRenderErrorContributor {

  // region Public API - These methods must remain stable for external contract.

  // TODO(b/552871624): The constants and throwable helpers below are thin delegates kept for callers outside this repository (e.g. the
  //  aiplugin Compose preview agent tools). Once those callers use ComposeRenderErrors directly, everything in this region except
  //  reportComposeErrors can be removed.
  const val VIEW_MODEL_HINT = ComposeRenderErrors.VIEW_MODEL_HINT
  const val CLASS_CAST_EXCEPTION_HINT = ComposeRenderErrors.CLASS_CAST_EXCEPTION_HINT
  const val COMPOSITION_LOCAL_NOT_FOUND_HINT = ComposeRenderErrors.COMPOSITION_LOCAL_NOT_FOUND_HINT

  /** Returns a hint for the given [throwable] if it is a known Compose issue. See [ComposeRenderErrors.getHint]. */
  @JvmStatic fun getHint(throwable: Throwable?): String? = ComposeRenderErrors.getHint(throwable)

  /** Checks if the given [Throwable] is one of the types that this contributor reports via [reportComposeErrors]. */
  @JvmStatic fun isHandledByComposeContributor(throwable: Throwable?): Boolean = ComposeRenderErrors.isHandled(throwable)

  /** See [ComposeRenderErrors.isCompositionLocalThrowable]. */
  @JvmStatic fun isCompositionLocalThrowable(throwable: Throwable?): Boolean = ComposeRenderErrors.isCompositionLocalThrowable(throwable)

  /** See [ComposeRenderErrors.isViewModelThrowable]. */
  @JvmStatic fun isViewModelThrowable(throwable: Throwable?): Boolean = ComposeRenderErrors.isViewModelThrowable(throwable)

  /** See [ComposeRenderErrors.isClassCastException]. */
  @JvmStatic fun isClassCastException(throwable: Throwable?): Boolean = ComposeRenderErrors.isClassCastException(throwable)

  /** See [ComposeRenderErrors.unwrapIfInvocationTargetException]. */
  @JvmStatic
  fun unwrapIfInvocationTargetException(throwable: Throwable?): Throwable? =
    ComposeRenderErrors.unwrapIfInvocationTargetException(throwable)

  /** Analyzes the logged errors and returns a list of [Issue] for Compose-specific problems. */
  @JvmStatic
  fun reportComposeErrors(
    logger: RenderLogger,
    linkManager: HtmlLinkManager = StudioHtmlLinkManager(),
    linkHandler: HyperlinkListener = HyperlinkListener {},
  ): List<Issue> =
    logger.messages.mapNotNull { message ->
      val match = ComposeRenderErrors.match(message.throwable) ?: return@mapNotNull null
      val presentation = match.type.presentation()
      Issue.builder()
        .setSeverity(presentation.severity)
        .setSummary(match.summary)
        .setLinkHandler(linkHandler)
        .setThrowable(match.rootCause)
        .apply {
          presentation.htmlContent?.let { setHtmlContent(it(linkManager, match.rootCause)) }
          presentation.messageTip?.let { addMessageTip(it(linkManager, match.rootCause)) }
        }
        .build()
    }

  // endregion

  // region Android Studio presentation of each shared error type

  /** Android Studio presentation of a [ComposeRenderErrorType]: the [Issue] severity plus optional HTML body and bottom-panel tip. */
  private class IssuePresentation(
    val severity: HighlightSeverity,
    val htmlContent: ((HtmlLinkManager, Throwable) -> HtmlBuilder)? = null,
    val messageTip: ((HtmlLinkManager, Throwable) -> MessageTip)? = null,
  )

  /**
   * Returns the [IssuePresentation] for this error type. The `when` is intentionally exhaustive so that adding a new
   * [ComposeRenderErrorType] in `tools.rendering` fails compilation here until a presentation is defined for it.
   */
  private fun ComposeRenderErrorType.presentation(): IssuePresentation =
    when (this) {
      ComposeRenderErrorType.PREVIEW_PARAMETER_PROVIDER_MISMATCH ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          messageTip = { linkManager, _ ->
            createBuildTheProjectMessage(
              linkManager,
              "The type of the PreviewParameterProvider must match the @Preview input parameter type annotated with it.",
            )
          },
        )
      ComposeRenderErrorType.FAIL_TO_LOAD_PREVIEW_PARAMETER_PROVIDER ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          htmlContent = { _, _ ->
            HtmlBuilder()
              .addLink(
                "There was problem to load the ",
                "PreviewParameterProvider",
                " defined. Please double-check its constructor and the values property implementation. The IDE logs should contain" +
                  " the full exception stack trace.",
                "https://developer.android.com/develop/ui/compose/tooling/previews#preview-data",
              )
          },
        )
      ComposeRenderErrorType.COMPOSE_PREVIEW_NOT_FOUND ->
        IssuePresentation(
          severity = HighlightSeverity.WARNING,
          messageTip = { linkManager, _ ->
            createBuildTheProjectMessage(linkManager, "The preview will display after rebuilding the project.")
          },
        )
      ComposeRenderErrorType.TIMEOUT_TO_LOAD_PREVIEW ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          htmlContent = { _, _ ->
            HtmlBuilder()
              .add("The preview took too long to load. The issue can be caused by long operations or infinite loops on the Preview code.")
              .newline()
              .add("If you think this issue is not caused by your code, you can report a bug in our issue tracker.")
          },
          messageTip = { linkManager, _ -> createAddReportBugMessage(linkManager, null) },
        )
      ComposeRenderErrorType.CLASS_CAST_TO_ACTIVITY ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          htmlContent = { linkManager, throwable ->
            HtmlBuilder()
              .add(
                "The java.lang.ClassCastException you are currently seeing in Jetpack Compose " +
                  "Previews occurs because the @Preview environment provides a non-activity Context " +
                  "(BridgeContext) that cannot be cast to an Activity. "
              )
              .newline()
              .newline()
              .addExceptionMessage(linkManager, throwable)
          },
        )
      ComposeRenderErrorType.COMPOSITION_LOCAL_NOT_FOUND ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          htmlContent = { linkManager, throwable ->
            HtmlBuilder()
              .addLink(
                "This preview was unable to find a ",
                "CompositionLocal",
                ". ",
                "https://developer.android.com/jetpack/compose/compositionlocal",
              )
              .add("You might need to define it so it can render correctly.")
              .newlineIfNecessary()
              .addExceptionMessage(linkManager, throwable)
          },
        )
      ComposeRenderErrorType.VIEW_MODEL_INSTANTIATION ->
        IssuePresentation(
          severity = HighlightSeverity.ERROR,
          htmlContent = { linkManager, throwable ->
            HtmlBuilder()
              .addLink("This preview uses a ", "ViewModel", ". ", "https://developer.android.com/topic/libraries/architecture/viewmodel")
              .add(
                "ViewModels often trigger operations not supported by Compose Preview, " +
                  "such as database access, I/O operations, or network requests. "
              )
              .addLink(
                "You can ",
                "read more",
                " about preview limitations in our external documentation.",
                "https://developer.android.com/jetpack/compose/tooling/" + "previews#preview-viewmodel",
              )
              .newlineIfNecessary()
              .addExceptionMessage(linkManager, throwable)
          },
        )
    }

  // endregion
}
