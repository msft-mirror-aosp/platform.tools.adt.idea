/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.idea.data.service;

import com.android.tools.idea.IdeInfo;
import com.android.tools.idea.gradle.project.sync.idea.data.model.ProjectCleanupModel;
import com.android.tools.idea.gradle.project.sync.setup.post.ProjectCleanup;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.EdtRule;
import com.intellij.testFramework.ProjectRule;
import com.intellij.testFramework.RunsInEdt;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static com.android.tools.idea.gradle.project.sync.idea.data.service.AndroidProjectKeys.PROJECT_CLEANUP_MODEL;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests for {@link ProjectCleanupDataService}.
 */
@RunsInEdt
public class ProjectCleanupDataServiceTest {
  private final ProjectRule projectRule = new ProjectRule();
  @Rule public final RuleChain ruleChain = RuleChain.outerRule(projectRule).around(new EdtRule());

  @Mock private IdeInfo myIdeInfo;
  @Mock private ProjectCleanup myProjectCleanup;
  @Mock private IdeModifiableModelsProvider myModelsProvider;

  private ProjectCleanupDataService myDataService;
  private List<DataNode<ProjectCleanupModel>> myDataNodes;

  @Before
  public void setUp() throws Exception {
    initMocks(this);

    myDataNodes = new ArrayList<>();
    myDataNodes.add(new DataNode<>(PROJECT_CLEANUP_MODEL, ProjectCleanupModel.getInstance(), null));

    myDataService = new ProjectCleanupDataService(myProjectCleanup);
  }

  @Test
  public void testGetTargetDataKey() {
    assertSame(PROJECT_CLEANUP_MODEL, myDataService.getTargetDataKey());
  }

  @Test
  public void testImportDataWithAndroidStudio() {
    when(myIdeInfo.isAndroidStudio()).thenReturn(true);

    myDataService.importData(myDataNodes, null, getProject(), myModelsProvider);

    verify(myProjectCleanup, times(1)).cleanUpProject(getProject(), myModelsProvider, null);
  }

  @Test
  public void testImportDataWithIdeNotAndroidStudio() {
    when(myIdeInfo.isAndroidStudio()).thenReturn(false);

    myDataService.importData(myDataNodes, null, getProject(), myModelsProvider);

    verify(myProjectCleanup, times(1)).cleanUpProject(getProject(), myModelsProvider, null);
  }

  private Project getProject() {
    return projectRule.getProject();
  }
}