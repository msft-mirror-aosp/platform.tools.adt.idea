package org.jetbrains.android;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

@RunsInEdt
public class  AndroidResourcesLineMarkerTest {
  private static final String BASE_PATH = "/resNavigation/";

  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  @Rule
  public TestName testName = new TestName();

  private CodeInsightTestFixture myFixture;

  @Before
  public void setUp() throws Exception {
    myFixture = projectRule.getFixture();
    myFixture.setTestDataPath(AndroidTestBase.getTestDataPath());
    myFixture.copyFileToProject(BASE_PATH + "AndroidManifest.xml", "AndroidManifest.xml");
    myFixture.copyDirectoryToProject(BASE_PATH + "res", "res");
  }

  @Test
  public void testJavaFileNavigation1() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation2() throws Exception {
    doJavaFileNavigationTest(3, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation3() throws Exception {
    doJavaFileNavigationTest(2, true, PsiFile.class);
  }

  @Test
  public void testJavaFileNavigation4() throws Exception {
    doJavaFileNavigationTest(0, false, null);
  }

  @Test
  public void testJavaFileNavigation5() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation6() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation7() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation12() throws Exception {
    myFixture.copyFileToProject(BASE_PATH + "dist_delims.xml", "res/values/strings12.xml");
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation13() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation14() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigation15() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigationManifest1() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigationManifest2() throws Exception {
    doJavaFileNavigationTest(2, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigationToSystemResource1() throws Exception {
    doJavaFileNavigationTest(1, true, XmlAttributeValue.class);
  }

  @Test
  public void testJavaFileNavigationToSystemResource2() throws Exception {
    doJavaFileNavigationTest(2, true, XmlAttributeValue.class);
  }

  private void doJavaFileNavigationTest(int expectedTargets,
                                        boolean expectedEnabled,
                                        @Nullable Class<? extends PsiElement> targetElementClass) throws IOException {
    String path = "src/p1/p2/" + UsefulTestCase.getTestName(testName.getMethodName(), false) + ".java";
    doJavaFileNavigationTest(path, path, expectedTargets, expectedEnabled, true, targetElementClass);
  }

  private void doJavaFileNavigationTest(String srcPath, String destPath, int expectedTargets, boolean expectedEnabled,
                                        boolean testGotoDeclaration, Class<? extends PsiElement> targetElementClass) throws IOException {
    VirtualFile file = myFixture.copyFileToProject(BASE_PATH + srcPath, destPath);
    myFixture.configureFromExistingVirtualFile(file);

    // test Ctrl+B
    if (testGotoDeclaration) {
      PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(projectRule.getProject(), myFixture.getEditor(), myFixture.getCaretOffset());
      assertNotNull(targets);
      assertEquals(expectedTargets, targets.length);

      for (PsiElement target : targets) {
        assertThat(target).isInstanceOf(targetElementClass);
      }
    }
  }
}
