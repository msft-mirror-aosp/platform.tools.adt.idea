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
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

object SdkInstallNotifications {
  /** Errors are worth interrupting the user for, so they stay up until dismissed. */
  private const val FAILURE_NOTIFICATION_GROUP = "SDK Install"

  /** Successes are not, so they use a group that fades away on its own. */
  private const val SUCCESS_NOTIFICATION_GROUP = "SDK Install (Info)"

  /**
   * Tells the user that an SDK install running in the background failed, with an action to show them [log].
   *
   * An install that is being shown in a dialog reports its failures there; this is for installs that have no UI of their own, either
   * because they were never shown in one or because the user sent them to the background.
   */
  fun notifySdkInstallFailed(failures: Collection<RepoPackage>, log: String) {
    val names = failures.joinToString(", ") { it.displayName }
    notifySdkInstall(
      groupId = FAILURE_NOTIFICATION_GROUP,
      type = NotificationType.ERROR,
      title = "Download failed",
      message = "Failed to download $names.",
      log = log,
    )
  }

  /**
   * Tells the user that an SDK install running in the background finished, with an action to show them [log].
   *
   * Like [notifySdkInstallFailed], this is only for installs with no UI of their own: one that is still being shown in a dialog reports its
   * own completion. Does nothing if [installed] is empty, as there is then nothing to report.
   */
  fun notifySdkInstallSucceeded(installed: Collection<RepoPackage>, log: String) {
    if (installed.isEmpty()) return
    val names = installed.joinToString(", ") { it.displayName }
    notifySdkInstall(
      groupId = SUCCESS_NOTIFICATION_GROUP,
      type = NotificationType.INFORMATION,
      title = "Download complete",
      message = "Downloaded $names.",
      log = log,
    )
  }

  private fun notifySdkInstall(
    groupId: String,
    type: NotificationType,
    title: String,
    message: String,
    log: String,
  ) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(groupId)
      .createNotification(title, message, type)
      .addAction(
        NotificationAction.create("Show Details") { event, _ ->
          SdkInstallLogDialog(event.project, "SDK Component Installation Log", log).show()
        }
      )
      .notify(null)
  }

  /** Shows the output of an install, which is otherwise lost once the dialog that was displaying it closes. */
  private class SdkInstallLogDialog(project: Project?, dialogTitle: String, private val log: String) : DialogWrapper(project) {
    init {
      title = dialogTitle
      setOKButtonText("Close")
      init()
    }

    override fun createCenterPanel(): JComponent {
      val output =
        JBTextArea(log).apply {
          isEditable = false
          font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }
      return JPanel(BorderLayout()).apply {
        add(JBScrollPane(output).apply { preferredSize = JBUI.size(700, 400) }, BorderLayout.CENTER)
      }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
  }
}
