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
package com.android.tools.idea.gradle.project.sync.listeners

import com.android.tools.idea.gradle.project.sync.GRADLE_SYNC_TOPIC
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotifications
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock

class StateChangeNotificationTest : LightPlatformTestCase() {

  private lateinit var mockEditorNotifications: EditorNotifications
  private lateinit var notification: StateChangeNotification

  override fun setUp() {
    super.setUp()
    mockEditorNotifications = mock()
    project.replaceService(EditorNotifications::class.java, mockEditorNotifications, testRootDisposable)
    notification = StateChangeNotification(project)
  }

  fun testSyncStartedUpdatesNotifications() {
    notification.syncStarted(project, project.basePath.orEmpty())
    verify(mockEditorNotifications).updateAllNotifications()
  }

  fun testSyncSucceededUpdatesNotifications() {
    notification.syncSucceeded(project, project.basePath.orEmpty())
    verify(mockEditorNotifications).updateAllNotifications()
  }

  fun testSyncFailedUpdatesNotifications() {
    notification.syncFailed(project, "Sync error", project.basePath.orEmpty())
    verify(mockEditorNotifications).updateAllNotifications()
  }

  fun testSyncCancelledUpdatesNotifications() {
    project.messageBus.syncPublisher(GRADLE_SYNC_TOPIC).syncCancelled(project, project.basePath.orEmpty())
    verify(mockEditorNotifications).updateAllNotifications()
  }

  fun testSyncSkippedDoesNotUpdateNotifications() {
    notification.syncSkipped(project)
    verify(mockEditorNotifications, never()).updateAllNotifications()
  }
}
