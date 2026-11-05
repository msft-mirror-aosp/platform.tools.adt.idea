package org.jetbrains.android;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.android.SdkConstants;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link com.android.tools.idea.res.AndroidInnerClassFinder}.
 */
@RunsInEdt
public class AndroidInnerClassFinderTest {
  private static final String BASE_PATH = "/psiElementFinder/";

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
  public void testRClasses() {
    myFixture.copyFileToProject(BASE_PATH + "values.xml", "res/values/values.xml");
    PsiFile javaFile =
      myFixture.addFileToProject("src/com/example/Foo.java", "package com.example; class Foo {}");
    assertNotNull(myFixture.getJavaFacade().findClass("p1.p2.R.drawable", javaFile.getResolveScope()));
    assertEquals(1, myFixture.getJavaFacade().findClasses("p1.p2.R.drawable", javaFile.getResolveScope()).length);
  }

  @Test
  public void testManifestClasses() {
    WriteCommandAction.runWriteCommandAction(projectRule.getProject(),
                                             () -> Manifest.getMainManifest(AndroidFacet.getInstance(projectRule.getModule())).addPermission().getName().setValue("com.example.SEND_MESSAGE"));
    PsiFile javaFile =
      myFixture.addFileToProject("src/com/example/Foo.java", "package com.example; class Foo {}");
    assertNotNull(myFixture.getJavaFacade().findClass("p1.p2.Manifest.permission", javaFile.getResolveScope()));
    assertEquals(1, myFixture.getJavaFacade().findClasses("p1.p2.Manifest.permission", javaFile.getResolveScope()).length);
  }
}
