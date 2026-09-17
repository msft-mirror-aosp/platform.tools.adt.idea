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
package com.android.tools.idea.insights.ai

/**
 * A request to generate an [AiInsight], sent to [AiInsightContributor.fetchInsight].
 *
 * The request is text-only: [prompt] is the only input sent to the model, and no images or other binary attachments are supported. A
 * subject whose source data includes images (such as Play Console finding screenshots) must describe or omit them in [prompt] rather than
 * reference attachments the model will not receive.
 *
 * [prompt] is sent to the model verbatim, so the caller must fully construct and sanitize it (for example, stripping triple backticks from
 * embedded content with [sanitizeForPrompt] so it cannot break the prompt's own code fences).
 */
data class AiInsightRequest(val prompt: String)
