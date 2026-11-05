/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.logging

import com.intellij.openapi.project.Project

/** Data class for AI event stats. */
data class AiEventStats(
  val project: Project?,
  val completionEvent: CompletionEventMetadata? = null,
  val transformEvent: TransformEventMetadata? = null,
  val chatBotEvent: ChatBotEventMetadata? = null,
  val devAiEventContext: DevAiContext,
  val feature: Feature,
  val modelId: String,
  val modelProviderId: String? = null,
  val agentInfo: AgentInfo? = null,
) : LoggedEvent {
  enum class Feature {
    FEATURE_UNSPECIFIED,
    CHAT,
    AGENT,
    QUICK_EDIT,
    CODE_COMPLETION,
    TRANSFORM_CODE,
    CHANGE_REVIEW,
    ONBOARDING,
    NEXT_EDIT_PREDICTION,
  }
}

data class AgentInfo(
  val agentType: AgentType = AgentType.AGENT_TYPE_UNSPECIFIED,
  val agentVersion: String? = null,
  val acpProtocolVersion: Int? = null,
) {
  enum class AgentType {
    AGENT_TYPE_UNSPECIFIED,
    BUILT_IN,
    CLAUDE_CODE,
    CODEX,
    ANTIGRAVITY,
    OPENCODE,
    COPILOT,
    OTHER,
    JETSKI,
  }
}

data class CompletionEventMetadata(val trigger: Trigger, val completionRequestError: CompletionRequestError? = null) {
  enum class Trigger {
    UNKNOWN_TRIGGER,
    USER,
    AUTOMATIC,
  }

  data class CompletionRequestError(val modelId: String, val status: String)
}

data class TransformEventMetadata(val kind: Kind, val phase: Phase, val sessionId: String, val transformEvent: TransformEvent) {
  enum class Kind {
    UNKNOWN_KIND,
    DOCUMENT,
    CUSTOM,
    INTEGRATE_CODE_BLOCK,
    MULTIMODAL_COMPOSE_PREVIEW,
    GENERATE_COMPOSE_PREVIEW,
    GENERATE_SUGGESTED_FIX,
  }

  enum class Phase {
    UNKNOWN_PHASE,
    INITIAL_TRANSFORM,
    REFINE,
    REGENERATE,
  }

  enum class ErrorCause {
    UNKNOWN_ERROR_CAUSE,
    EMPTY_RESPONSE,
    INVALID_FORMAT,
    BACKEND_ERROR,
    RUNTIME_EXCEPTION,
  }

  data class Request(val file: String, val selectedLength: Int, val contextLength: Int)

  data class ErrorResponse(val cause: ErrorCause, val exceptionClass: String, val statusCode: Int)

  data class SuccessResponse(val latencyMillis: Long)

  data class TransformEvent(
    val transformEventType: TransformEventType? = null,
    val request: Request? = null,
    val successResponse: SuccessResponse? = null,
    val errorResponse: ErrorResponse? = null,
  ) {
    enum class TransformEventType {
      UNKNOWN,
      SHOWN,
      ACCEPTED,
      REJECTED,
    }
  }
}

data class ChatBotEventMetadata(
  val entryPoint: EntryPoint,
  val mode: ChatMode,
  val botResponses: List<BotResponse>,
  val userFeedbacks: List<UserFeedback>,
  val toolCallResponses: List<ToolCallResponse>,
) {
  enum class EntryPoint {
    UNKNOWN_ENTRY_POINT,
    DOCUMENT,
    COMMENT,
    SIMPLIFY,
    EXPLAIN,
    IDE_ERROR,
    SYNC,
    BUILD,
    OTHER,
    SLASH_COMMAND,
    CRASHLYTICS,
    PLAY_VITALS,
    OTHER_EDITOR,
    LOGCAT,
    QUICK_EDIT_ENTRY_POINT,
  }

  enum class ChatMode {
    UNKNOWN_CHAT_MODE,
    CHAT,
    AGENT_MODE,
    QUICK_EDIT,
    PLANNING,
    VERSION_UPGRADE_AGENT,
  }

  enum class ToolCallResponseStatus {
    UNKNOWN_RESPONSE_STATUS,
    OK,
    ERROR_OCCURRED,
    ERR_FILE_NOT_FOUND,
    ERR_FILE_INACCESSIBLE,
    ERR_FILE_NOT_IN_PROJECT,
    ERR_FILE_BLOCKED_BY_AI_EXCLUDE,
    ERR_IO_EXCEPTION,
    ERR_REPLACE_TEXT_NO_OCCURRENCES_FOUND_TO_REPLACE,
    ERR_WRITE_FILE_NEW_TEXT_IDENTICAL_TO_CURRENT,
    ERR_USER_REJECTED_PROPOSAL,
    BUILD_FINISHED_SUCCESSFULLY,
    BUILD_FINISHED_WITH_ERRORS,
    BUILD_FAILED,
    BUILD_INTERRUPTED,
    MALFORMATTED_TOOL_CALL,
    GRADLE_ERR_NO_MODULES,
    GRADLE_ERR_NOT_ANDROID,
    GRADLE_ERR_MODULE_NOT_FOUND,
    GRADLE_ERR_ARTIFACT_NOT_FOUND,
    GRADLE_ERR_VARIANT_NOT_FOUND,
    GRADLE_ERR_WRONG_ARTIFACT,
    GRADLE_ERR_MISSING_DATA,
    BUILD_TASK_NOT_FOUND,
    SUB_AGENT_RESULT,
    SUB_AGENT_ENDED_WITHOUT_RESPONSE,
    ERR_INVALID_ARGUMENTS,
    ERR_INVALID_URL,
    ERR_FILE_BLOCKED_BY_GIT_IGNORE,
    ERR_FILE_CRITIC_TIMED_OUT,
    ERR_FILE_IS_DIRECTORY,
    ERR_INVALID_LINE_RANGE,
    ERR_FILE_MODIFIED_BY_SOMEONE_ELSE,
    TOOL_EXECUTION_INTERRUPTED,
    GRADLE_ERR_DAEMON_CONNECTION_FAILED,
    GRADLE_ERR_BUILD_CANCELLED,
    GRADLE_ERR_UNSUPPORTED_VERSION,
    GRADLE_ERR_PROJECT_DIR_NOT_FOUND,
    GRADLE_ERR_INVALID_ARGUMENTS,
    GRADLE_ERR_COMPILATION_FAILED,
    GRADLE_ERR_TEST_FAILED,
  }

  enum class BotResponseErrorStatus {
    UNKNOWN_ERROR_STATUS,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    CLIENT_SIDE_RATE_LIMIT,
    UNAVAILABLE,
    DATA_LOSS,
    DEADLINE_EXCEEDED,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    UNAUTHENTICATED,
    FAILED_PRECONDITION,
    INTERNAL,
    MALFORMED_FUNCTION_CALL,
    INVALID_ARGUMENT,
    ALREADY_EXISTS,
    NOT_FOUND,
    CANCELLED,
    STATUS_OK,
    UNKNOWN_ERROR,
  }

  data class BotResponse(
    val chunkLatenciesMillis: List<Long>,
    val timeToFirstTokenMillis: Long,
    val errorStatus: BotResponseErrorStatus,
    val latencyMilliseconds: Long? = null,
    val inputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val cacheReadTokenCount: Int? = null,
    val thinkingOutputTokenCount: Int? = null,
    val finishReason: FinishReason? = null,
    val historyMessageCount: Int? = null,
    val systemInstructionLengthChars: Int? = null,
    val requestSizeBytes: Long? = null,
    val responseSizeBytes: Long? = null,
    val responseChunks: List<ResponseChunk> = emptyList(),
    val requestId: String? = null,
    val serverTraceId: String? = null,
    val httpStatusCode: Int? = null,
  ) {
    enum class FinishReason {
      FINISH_REASON_UNSPECIFIED,
      STOP,
      MAX_TOKENS,
      SAFETY,
      RECITATION,
      MISSING_THOUGHT_SIGNATURE,
      LANGUAGE,
      OTHER,
      BLOCKLIST,
      PROHIBITED_CONTENT,
      SPII,
      MALFORMED_FUNCTION_CALL,
      IMAGE_SAFETY,
      IMAGE_PROHIBITED_CONTENT,
      IMAGE_OTHER,
      NO_IMAGE,
      IMAGE_RECITATION,
      UNEXPECTED_TOOL_CALL,
      TOO_MANY_TOOL_CALLS,
      MALFORMED_RESPONSE,
    }

    data class ResponseChunk(
      val timestampMillis: Long = 0L,
      val textLengthChars: Int = 0,
      val thoughtLengthChars: Int = 0,
      val hasSignature: Boolean = false,
    )
  }

  data class UserFeedback(val sentiment: Sentiment, val userInput: UserInput? = null) {
    enum class Sentiment {
      SENTIMENT_UNSPECIFIED,
      POSITIVE,
      NEGATIVE,
    }

    data class UserInput(val issueType: IssueType) {
      enum class IssueType {
        ISSUE_TYPE_UNSPECIFIED,
        TOXIC,
        IRRELEVANT,
        TOO_VERBOSE,
        WRONG_ANSWER,
      }
    }
  }

  data class ToolCallResponse(
    val toolCallId: String,
    val toolName: String,
    val status: ToolCallResponseStatus,
    val executionDurationMillis: Long? = null,
    val activeExecutionDurationMillis: Long? = null,
    val startTimestampMillis: Long? = null,
    val executionStatus: ExecutionStatus? = null,
  ) {
    enum class ExecutionStatus {
      UNKNOWN_STATUS,
      SUCCESS,
      INTERRUPTED,
      INTERNAL_ERROR,
    }
  }
}

data class DevAiContext(val conversation: Conversation? = null, val edit: Edit? = null) {

  data class Conversation(
    val conversationId: String,
    val turn: Turn,
    val trajectoryId: String? = null,
    val parentTrajectoryId: String? = null,
    val isChatThread: Boolean? = null,
  ) {
    data class Turn(
      val turnIndex: Int,
      val edit: List<Edit>,
      val lastFeedbackProvided: FeedbackProvided,
      val turnId: String,
      val latencyMilliseconds: Long? = null,
      val fromSlashCommand: Boolean? = null,
      val userQueryType: UserQueryType? = null,
      val exceptionClassName: String? = null,
      val commentCount: Int? = null,
    ) {
      enum class UserQueryType {
        USER_QUERY_TYPE_UNSPECIFIED,
        DEFAULT,
        STEER,
        QUEUED,
        SUB_AGENT,
        AUTO_RETRY,
      }
    }
  }

  data class Edit(
    val suggestedEditApplied: Boolean,
    val programmingLanguage: String,
    val charactersSuggested: CharactersSuggested,
    val charactersAccepted: CharactersAccepted,
    val reviewTimeMilliseconds: Long,
    val depotPath: String,
  ) {
    data class CharactersSuggested(val added: Long, val removed: Long)

    data class CharactersAccepted(val added: Long, val removed: Long)
  }

  enum class FeedbackProvided {
    UNKNOWN_FEEDBACK,
    NO_FEEDBACK,
    POSITIVE_FEEDBACK,
    NEGATIVE_FEEDBACK,
  }
}
