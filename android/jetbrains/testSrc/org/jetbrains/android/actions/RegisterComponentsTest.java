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
package org.jetbrains.android.actions;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.android.SdkConstants;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.AndroidTestUtils;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import org.jetbrains.android.AndroidTestBase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@RunsInEdt
public class RegisterComponentsTest {
  /**
   * "Add activity" intention name, isn't shared with non-test code, used to avoid typos in the test suite
   */
  private static final String ADD_ACTIVITY_TO_MANIFEST = "Add activity to manifest";

  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  private CodeInsightTestFixture myFixture;

  @Before
  public void setUp() throws Exception {
    myFixture = projectRule.getFixture();
    myFixture.setTestDataPath(AndroidTestBase.getTestDataPath());

    // b/402201770: Ensure any idempotence checks fail consistently.
    Registry.get("platform.random.idempotence.check.rate").setValue(1, projectRule.getTestRootDisposable());
    myFixture.copyFileToProject(SdkConstants.FN_ANDROID_MANIFEST_XML, SdkConstants.FN_ANDROID_MANIFEST_XML);
  }

  /**
   * Test that "add activity" quick intention does show up and works
   */
  @Test
  public void testAddActivity() throws Exception {
    final VirtualFile file = myFixture.copyFileToProject("intentions/DummyActivity.java", "src/com/example/DummyActivity.java");
    myFixture.configureFromExistingVirtualFile(file);

    final IntentionAction action = AndroidTestUtils.getIntentionAction(myFixture, ADD_ACTIVITY_TO_MANIFEST);
    assertNotNull(action);

    action.invoke(projectRule.getProject(), myFixture.getEditor(), myFixture.getFile());

    myFixture.checkResultByFile("AndroidManifest.xml", "intentions/DummyActivity_manifest_after.xml", true);
  }

  /**
   * Test that "add activity" doesn't show up when activity is already registered
   */
  @Test
  public void testAddActivityNoShow() throws Exception {
    myFixture.copyFileToProject("intentions/DummyActivity_manifest_after.xml", "AndroidManifest.xml");
    final VirtualFile file = myFixture.copyFileToProject("intentions/DummyActivity.java", "src/com/example/DummyActivity.java");
    myFixture.configureFromExistingVirtualFile(file);

    final IntentionAction action = AndroidTestUtils.getIntentionAction(myFixture, ADD_ACTIVITY_TO_MANIFEST);
    assertNull(action);
  }

  /**
   * Test that "add activity" doesn't show up when class doesn't extend "android.app.Activity"
   */
  @Test
  public void testAddActivityOnNonActivity() throws Exception {
    final VirtualFile file = myFixture.copyFileToProject("intentions/DummyActivity2.java", "src/com/example/DummyActivity.java");
    myFixture.configureFromExistingVirtualFile(file);

    final IntentionAction action = AndroidTestUtils.getIntentionAction(myFixture, ADD_ACTIVITY_TO_MANIFEST);
    assertNull(action);
  }

  /**
   * Test that "add service" quick intention does show up and works
   */
  @Test
  public void testAddService() throws Exception {
    final VirtualFile file = myFixture.copyFileToProject("intentions/DummyService.java", "src/com/example/DummyService.java");
    myFixture.configureFromExistingVirtualFile(file);

    final IntentionAction action = AndroidTestUtils.getIntentionAction(myFixture, "Add service to manifest");
    assertNotNull(action);

    action.invoke(projectRule.getProject(), myFixture.getEditor(), myFixture.getFile());
    myFixture.checkResultByFile("AndroidManifest.xml", "intentions/DummyService_manifest_after.xml", true);
  }

  /**
   * Test that "add broadcast receiver" quick intention shows up and works
   */
  @Test
  public void testAddBroadcastReceiver() throws Exception {
    final VirtualFile file = myFixture.copyFileToProject("intentions/DummyReceiver.java", "src/com/example/DummyReceiver.java");
    myFixture.configureFromExistingVirtualFile(file);

    final IntentionAction action = AndroidTestUtils.getIntentionAction(myFixture, "Add broadcast receiver to manifest");
    assertNotNull(action);

    action.invoke(projectRule.getProject(), myFixture.getEditor(), myFixture.getFile());
    myFixture.checkResultByFile("AndroidManifest.xml", "intentions/DummyReceiver_manifest_after.xml", true);
  }
}
