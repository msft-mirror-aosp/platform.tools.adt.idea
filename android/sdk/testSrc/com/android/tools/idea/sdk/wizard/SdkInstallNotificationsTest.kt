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
package com.android.tools.idea.sdk.wizard

import com.android.repository.api.RepoPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.google.common.truth.Truth.assertThat
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.util.application
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests for [SdkInstallNotifications]. */
class SdkInstallNotificationsTest {
  @get:Rule val applicationRule = ApplicationRule()
  @get:Rule val disposableRule = DisposableRule()

  private val notifications = mutableListOf<Notification>()

  @Before
  fun subscribeToNotifications() {
    application.messageBus
      .connect(disposableRule.disposable)
      .subscribe(
        Notifications.TOPIC,
        object : Notifications {
          override fun notify(notification: Notification) {
            notifications.add(notification)
          }
        },
      )
  }

  @Test
  fun succeededNotificationNamesThePackages() {
    SdkInstallNotifications.notifySdkInstallSucceeded(listOf(fakePackage("Package One"), fakePackage("Package Two")), "log output")

    val notification = notifications.single()
    assertThat(notification.type).isEqualTo(NotificationType.INFORMATION)
    assertThat(notification.content).contains("Package One")
    assertThat(notification.content).contains("Package Two")
  }

  @Test
  fun succeededNotificationOffersTheLog() {
    SdkInstallNotifications.notifySdkInstallSucceeded(listOf(fakePackage("Package One")), "log output")

    assertThat(notifications.single().actions.map { it.templateText }).containsExactly("Show Details")
  }

  /** An install with nothing to install, which happens when the request was all uninstalls, has nothing to report. */
  @Test
  fun succeededNotificationIsSkippedWithoutPackages() {
    SdkInstallNotifications.notifySdkInstallSucceeded(emptyList<RepoPackage>(), "log output")

    assertThat(notifications).isEmpty()
  }

  @Test
  fun failedNotificationNamesThePackagesAndOffersTheLog() {
    SdkInstallNotifications.notifySdkInstallFailed(listOf(fakePackage("Package One")), "log output")

    val notification = notifications.single()
    assertThat(notification.type).isEqualTo(NotificationType.ERROR)
    assertThat(notification.content).contains("Package One")
    assertThat(notification.actions.map { it.templateText }).containsExactly("Show Details")
  }

  private fun fakePackage(name: String) = FakeRemotePackage("p1").apply { displayName = name }
}
