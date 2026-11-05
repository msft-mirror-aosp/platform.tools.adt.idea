/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.editors;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.SdkConstants;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import org.jetbrains.android.AndroidTestBase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@RunsInEdt
public class AndroidImportFilterTest {
  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  private JavaCodeInsightTestFixture myFixture;

  @Before
  public void setUp() {
    myFixture = (JavaCodeInsightTestFixture)projectRule.getFixture();
    myFixture.setTestDataPath(AndroidTestBase.getTestDataPath());
    myFixture.copyFileToProject(SdkConstants.FN_ANDROID_MANIFEST_XML, SdkConstants.FN_ANDROID_MANIFEST_XML);
  }

  @Test
  public void testShouldUseFullName() {
    PsiFile javaFile =
      myFixture.addFileToProject("src/com/example/Foo.java", "package com.example; class Foo {}");
    PsiFile file = myFixture.getJavaFacade().findClass("p1.p2.R", javaFile.getResolveScope()).getContainingFile();
    assertNotNull(file);

    AndroidImportFilter filter = new AndroidImportFilter();
    assertTrue(filter.shouldUseFullyQualifiedName(file, "android.R"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "android.R.anim"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "android.R.anything"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "com.android.tools.R"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "com.android.tools.R.anim"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "com.android.tools.R.layout"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "a.R.string"));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "my.weird.clz.R"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "my.weird.clz.R.bogus"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "p1.p2.R")); // Application package: android/testData/AndroidManifest.xml
    assertTrue(filter.shouldUseFullyQualifiedName(file, "p1.p2.R.string"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "p1.p2.R.bogus"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, ""));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "."));
    assertTrue(filter.shouldUseFullyQualifiedName(file, "a.R"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "android"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "android."));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "android.r"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "android.Random"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "my.R.unrelated"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "my.R.unrelated.to"));
    assertFalse(filter.shouldUseFullyQualifiedName(file, "R.string")); // R is never in the default package
    assertTrue(filter.shouldUseFullyQualifiedName(file, "android.databinding.tool.util.GenerationalClassUtil.ExtensionFilter.BR")); // Not the BR you're looking for!
  }
}
