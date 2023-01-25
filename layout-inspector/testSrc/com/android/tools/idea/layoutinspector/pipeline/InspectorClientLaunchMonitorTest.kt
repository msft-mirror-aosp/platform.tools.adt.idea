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
package com.android.tools.idea.layoutinspector.pipeline

import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.idea.layoutinspector.LayoutInspectorBundle
import com.android.tools.idea.layoutinspector.metrics.statistics.SessionStatisticsImpl
import com.android.tools.idea.layoutinspector.ui.InspectorBannerService
import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.project.DefaultProjectSystem
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.ListenerCollection
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorAttachToProcess.ClientType
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorErrorInfo.AttachErrorState
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.concurrent.TimeUnit

class InspectorClientLaunchMonitorTest {
  @get:Rule
  val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun monitorOffersUserToStopsStuckConnection() {
    val project = projectRule.project
    val scheduler = VirtualTimeScheduler()
    val banner = InspectorBannerService.getInstance(project) ?: error("no banner")
    val stats = SessionStatisticsImpl(ClientType.APP_INSPECTION_CLIENT) { false }
    run {
      val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
      val client = mock<InspectorClient>()
      monitor.start(client)
      scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
      assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
      assertThat(stats.currentProgress).isEqualTo(AttachErrorState.NOT_STARTED)
      banner.notification = null
    }
    run {
      val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
      val client = mock<InspectorClient>()
      monitor.start(client)
      scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS - 1, TimeUnit.SECONDS)
      monitor.updateProgress(AttachErrorState.START_REQUEST_SENT)
      scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS - 1, TimeUnit.SECONDS)
      assertThat(banner.notification).isNull()
      assertThat(stats.currentProgress).isEqualTo(AttachErrorState.START_REQUEST_SENT)
      scheduler.advanceBy(2, TimeUnit.SECONDS)
      assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
      banner.notification = null
    }
    run {
      val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
      monitor.updateProgress(CONNECTED_STATE)
      scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
      assertThat(banner.notification).isNull()
    }
  }

  @Test
  fun attachErrorStateListenersAreCalled() {
    val listeners = ListenerCollection.createWithDirectExecutor<(AttachErrorState) -> Unit>()
    val mockListener = mock<(AttachErrorState) -> Unit>()
    listeners.add(mockListener)

    val stats = SessionStatisticsImpl(ClientType.APP_INSPECTION_CLIENT) { false }
    val monitor = InspectorClientLaunchMonitor(projectRule.project, listeners, stats)
    monitor.updateProgress(AttachErrorState.ADB_PING)

    verify(mockListener).invoke(AttachErrorState.ADB_PING)
    assertThat(stats.currentProgress).isEqualTo(AttachErrorState.ADB_PING)
  }

  @Test
  fun slowAttachMessageWithLegacyClient() {
    val legacyClient = mock<InspectorClient>()
    whenever(legacyClient.clientType).thenReturn(ClientType.LEGACY_CLIENT)
    slowAttachMessage(legacyClient, "Disconnect")
  }

  @Test
  fun slowAttachMessageWithAppInspectionClient() {
    val appInspectionClient = mock<InspectorClient>()
    whenever(appInspectionClient.clientType).thenReturn(ClientType.APP_INSPECTION_CLIENT)
    slowAttachMessage(appInspectionClient, "Dump Views")
  }

  private fun slowAttachMessage(client: InspectorClient, expectedDisconnectMessage: String) {
    val project = projectRule.project
    val projectSystem = projectRule.project.getProjectSystem() as DefaultProjectSystem
    val moduleSystem = DefaultModuleSystem(projectRule.module)
    projectSystem.setModuleSystem(moduleSystem.module, moduleSystem)
    moduleSystem.usesCompose = true

    val banner = InspectorBannerService.getInstance(project) ?: error("no banner")
    val scheduler = VirtualTimeScheduler()
    val stats = SessionStatisticsImpl(ClientType.APP_INSPECTION_CLIENT) { false }
    val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
    monitor.start(client)
    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    verify(client, never()).disconnect()
    assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
    assertThat(banner.notification?.actions?.first()?.templateText).isEqualTo("Continue Waiting")
    assertThat(banner.notification?.actions?.last()?.templateText).isEqualTo(expectedDisconnectMessage)

    // Continue waiting:
    banner.notification?.actions?.first()?.actionPerformed(mock())
    assertThat(banner.notification).isNull()

    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    verify(client, never()).disconnect()
    assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
    assertThat(banner.notification?.actions?.first()?.templateText).isEqualTo("Continue Waiting")
    assertThat(banner.notification?.actions?.last()?.templateText).isEqualTo(expectedDisconnectMessage)

    // Continue waiting:
    banner.notification?.actions?.first()?.actionPerformed(mock())
    assertThat(banner.notification).isNull()

    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    verify(client, never()).disconnect()
    assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
    assertThat(banner.notification?.actions?.first()?.templateText).isEqualTo("Continue Waiting")
    assertThat(banner.notification?.actions?.last()?.templateText).isEqualTo(expectedDisconnectMessage)

    // Disconnect:
    banner.notification?.actions?.last()?.actionPerformed(mock())
    assertThat(banner.notification).isNull()
    verify(client).disconnect()
  }

  @Test
  fun slowAttachMessageRemovedWhenConnected() {
    val project = projectRule.project
    val projectSystem = projectRule.project.getProjectSystem() as DefaultProjectSystem
    val moduleSystem = DefaultModuleSystem(projectRule.module)
    projectSystem.setModuleSystem(moduleSystem.module, moduleSystem)
    moduleSystem.usesCompose = true

    val banner = InspectorBannerService.getInstance(project) ?: error("no banner")
    val scheduler = VirtualTimeScheduler()
    val stats = SessionStatisticsImpl(ClientType.APP_INSPECTION_CLIENT) { false }
    val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
    val client = mock<InspectorClient>()
    monitor.start(client)
    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    verify(client, never()).disconnect()
    assertThat(banner.notification?.message).isEqualTo(LayoutInspectorBundle.message(CONNECT_TIMEOUT_MESSAGE_KEY))
    assertThat(banner.notification?.actions?.first()?.templateText).isEqualTo("Continue Waiting")
    assertThat(banner.notification?.actions?.last()?.templateText).isEqualTo("Disconnect")

    monitor.updateProgress(CONNECTED_STATE)
    assertThat(banner.notification).isNull()
    assertThat(stats.currentProgress).isEqualTo(AttachErrorState.MODEL_UPDATED)
  }

  @Test
  fun slowAttachedMessageNotScheduledWhenClientIsClosed() {
    val project = projectRule.project
    val projectSystem = projectRule.project.getProjectSystem() as DefaultProjectSystem
    val moduleSystem = DefaultModuleSystem(projectRule.module)
    projectSystem.setModuleSystem(moduleSystem.module, moduleSystem)
    moduleSystem.usesCompose = true

    val banner = InspectorBannerService.getInstance(project) ?: error("no banner")
    val scheduler = VirtualTimeScheduler()
    val stats = SessionStatisticsImpl(ClientType.APP_INSPECTION_CLIENT) { false }
    val monitor = InspectorClientLaunchMonitor(project, ListenerCollection.createWithDirectExecutor(), stats, scheduler)
    val client = mock<InspectorClient>()
    monitor.start(client)
    monitor.stop()
    monitor.updateProgress(AttachErrorState.ADB_PING)
    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    assertThat(banner.notification).isNull()
    assertThat(stats.currentProgress).isEqualTo(AttachErrorState.ADB_PING)
  }
}