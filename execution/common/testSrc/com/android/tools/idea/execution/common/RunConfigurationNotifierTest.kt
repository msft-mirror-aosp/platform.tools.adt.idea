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
package com.android.tools.idea.execution.common

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.NotificationRule
import com.google.common.truth.Truth.assertThat
import com.intellij.notification.NotificationType
import com.intellij.testFramework.RuleChain
import org.junit.Rule
import org.junit.Test

class RunConfigurationNotifierTest {
  val projectRule = AndroidProjectRule.inMemory()
  val notificationRule = NotificationRule(projectRule)

  @get:Rule val ruleChain = RuleChain(projectRule, notificationRule)

  @Test
  fun testNotifyError_escapesHtmlTags() {
    RunConfigurationNotifier.notifyError(projectRule.project, "TestConfig", "Error <script>alert('xss')</script>")

    val notificationInfo = notificationRule.notifications.single()
    assertThat(notificationInfo.type).isEqualTo(NotificationType.ERROR)
    assertThat(notificationInfo.content).doesNotContain("<script>")
    assertThat(notificationInfo.content).contains("&lt;script&gt;")
  }

  @Test
  fun testNotifyWarning_escapesHtmlTags() {
    RunConfigurationNotifier.notifyWarning(projectRule.project, "TestConfig", "Warning <img src=x onerror=alert(1)>")

    val notificationInfo = notificationRule.notifications.single()
    assertThat(notificationInfo.type).isEqualTo(NotificationType.WARNING)
    assertThat(notificationInfo.content).doesNotContain("<img")
    assertThat(notificationInfo.content).contains("&lt;img")
  }
}
