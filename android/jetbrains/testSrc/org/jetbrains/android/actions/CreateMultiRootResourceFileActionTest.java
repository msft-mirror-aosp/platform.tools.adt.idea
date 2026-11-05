/*
 * Copyright (C) 2017 The Android Open Source Project
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
package org.jetbrains.android.actions;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertEquals;

import com.android.AndroidXConstants;
import com.android.SdkConstants;
import com.android.ide.common.repository.GoogleMavenArtifactId;
import com.android.resources.ResourceFolderType;
import com.android.tools.idea.projectsystem.TestProjectSystem;
import com.android.tools.idea.projectsystem.TestVersion;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.testFramework.RunsInEdt;
import java.util.Arrays;
import java.util.Collections;
import org.jetbrains.android.facet.AndroidFacet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

@RunsInEdt
public final class CreateMultiRootResourceFileActionTest {
  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  private CreateMultiRootResourceFileAction myAction;
  private TestProjectSystem myTestProjectSystem;
  private AndroidFacet myFacet;

  @Before
  public void setUp() throws Exception {
    myFacet = AndroidFacet.getInstance(projectRule.getModule());
    myAction = Mockito.spy(new CreateMultiRootResourceFileAction("Layout", ResourceFolderType.LAYOUT));
    myTestProjectSystem = new TestProjectSystem(projectRule.getProject(), Collections.emptyList());
    myTestProjectSystem.useInTests();
  }

  @After
  public void tearDown() throws Exception {
    myAction = null;
  }

  @Test
  public void testGetDefaultRootTag_ModuleDoesntDependOnConstraintLayout() {
    assertEquals(SdkConstants.LINEAR_LAYOUT, myAction.getDefaultRootTag(myFacet.getModule()));
  }

  @Test
  public void testGetDefaultRootTag_ModuleDependsOnConstraintLayout() {
    myTestProjectSystem.addDependency(GoogleMavenArtifactId.CONSTRAINT_LAYOUT, myFacet.getModule(), TestVersion.create(1, 1));
    Mockito.when(myAction.getPossibleRoots(myFacet))
      .thenReturn(Arrays.asList(SdkConstants.LINEAR_LAYOUT, AndroidXConstants.CONSTRAINT_LAYOUT.oldName()));

    assertEquals(AndroidXConstants.CONSTRAINT_LAYOUT.oldName(), myAction.getDefaultRootTag(myFacet.getModule()));
  }

  @Test
  public void testGetDefaultRootTag_ModuleDependsOnAndroidXConstraintLayout() {
    myTestProjectSystem.addDependency(GoogleMavenArtifactId.ANDROIDX_CONSTRAINTLAYOUT, myFacet.getModule(), TestVersion.create(1, 1));
    Mockito.when(myAction.getPossibleRoots(myFacet))
      .thenReturn(Arrays.asList(SdkConstants.LINEAR_LAYOUT, AndroidXConstants.CONSTRAINT_LAYOUT.defaultName()));

    assertEquals(AndroidXConstants.CONSTRAINT_LAYOUT.newName(), myAction.getDefaultRootTag(myFacet.getModule()));
  }
}
