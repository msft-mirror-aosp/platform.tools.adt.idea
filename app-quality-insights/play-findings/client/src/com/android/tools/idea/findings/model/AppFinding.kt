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
package com.android.tools.idea.findings.model

/**
 * Represents a finding for an application from the Play Console.
 *
 * @property name The unique identifier of the finding.
 * @property category The high-level [FindingCategory] this finding belongs to.
 * @property severity The severity (e.g., [FindingSeverity.WARNING]).
 * @property displayName The user-facing display name describing the finding.
 * @property description Detailed description or explanation for the finding.
 * @property affectedScopes Scopes within the app that this finding refers to.
 * @property context Optional diagnostic and UI attachments associated with the finding.
 * @property jsonPayload Serialized JSON representation of the parsed [com.google.play.androidpublisher.v3.Finding] proto for AI context.
 */
data class AppFinding(
  val name: String,
  val category: FindingCategory,
  val severity: FindingSeverity,
  val displayName: String,
  val description: String = "",
  val affectedScopes: List<AffectedScope> = emptyList(),
  val context: FindingContext = FindingContext.EMPTY,
  val jsonPayload: String = "",
)
