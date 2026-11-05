/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.setup.post;

import com.android.tools.analytics.HostData;
import com.android.tools.analytics.stubs.StubOperatingSystemMXBean;
import com.android.tools.idea.gradle.project.sync.setup.post.MemorySettingsPostSyncChecker.MemorySettingsNotification;
import com.android.tools.idea.memorysettings.MemorySettingsRecommendation;
import com.intellij.diagnostic.VMOptions;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.EdtRule;
import com.intellij.testFramework.ProjectRule;
import com.intellij.testFramework.RunsInEdt;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.mockito.Mock;

import static com.intellij.testFramework.UsefulTestCase.assertSize;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

@RunsInEdt
public class MemorySettingsPostSyncCheckerTest {
  private final ProjectRule projectRule = new ProjectRule();
  @Rule public final RuleChain ruleChain = RuleChain.outerRule(projectRule).around(new EdtRule());

  private Project myProject;

  @Mock
  private TimeBasedReminder myReminder;

  @Before
  public void setUp() throws Exception {
    myProject = projectRule.getProject();
    initMocks(this);
    cleanNotification();
  }

  @After
  public void tearDown() throws Exception {
    cleanNotification();
  }

  @Test
  public void testNoNotificationIfSmallRam() {
    stubHostData(4);
    MemorySettingsPostSyncChecker.checkSettings(myProject, myReminder);
    Notification[] notifications = getNotifications();
    assertSize(0, notifications);
  }

  @Test
  public void testNoNotificationIfShouldNotCheck() {
    when(myReminder.shouldAsk()).thenReturn(false);
    MemorySettingsPostSyncChecker.checkSettings(myProject, myReminder);
    Notification[] notifications = getNotifications();
    assertSize(0, notifications);
  }

  @Test
  public void testNotificationIfRecommended() throws IOException {
    when(myReminder.shouldAsk()).thenReturn(true);
    // set the heap size to be small so there is a recommendation made
    stubHostData(16);
    MemorySettingsPostSyncChecker.checkSettings(myProject, myReminder);
    MemorySettingsPostSyncChecker.checkSettings(myProject, myReminder);
    Notification[] notifications = getNotifications();
    // Check twice but there should be only one notification
    assertSize(1, notifications);
  }

  private void cleanNotification() {
    Notification[] notifications = getNotifications();
    for (Notification notification : notifications) {
      notification.expire();
    }
  }

  private Notification[] getNotifications() {
    return NotificationsManager.getNotificationsManager().getNotificationsOfType(
      MemorySettingsNotification.class, myProject);
  }

  private void stubHostData(int machineMemInGB) {
    HostData.setOsBean(new StubOperatingSystemMXBean() {
      @Override
      public long getTotalPhysicalMemorySize() {
        return machineMemInGB * 1024 * 1024L * 1024L;
      }
    });
  }
}