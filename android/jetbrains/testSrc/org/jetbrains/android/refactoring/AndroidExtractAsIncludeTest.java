package org.jetbrains.android.refactoring;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.SdkConstants;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import org.jetbrains.android.AndroidTestBase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

@RunsInEdt
public class AndroidExtractAsIncludeTest {
  private static final String BASE_PATH = "refactoring/extractAsInclude/";

  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  @Rule
  public TestName testName = new TestName();

  private CodeInsightTestFixture myFixture;

  @Before
  public void setUp() {
    myFixture = projectRule.getFixture();
    myFixture.setTestDataPath(AndroidTestBase.getTestDataPath());
    myFixture.copyFileToProject(SdkConstants.FN_ANDROID_MANIFEST_XML, SdkConstants.FN_ANDROID_MANIFEST_XML);
  }

  @Test
  public void test1() throws Exception {
    doTest();
  }

  @Test
  public void test2() throws Exception {
    doTest();
  }

  @Test
  public void test3() throws Exception {
    doTestDisabled();
  }

  @Test
  public void test4() throws Exception {
    doTestDisabled();
  }

  @Test
  public void test5() throws Exception {
    doTestDisabled();
  }

  @Test
  public void test6() throws Exception {
    doTest();
  }

  @Test
  public void test7() throws Exception {
    doTest();
  }

  @Test
  public void test8() throws Exception {
    doTestDisabled();
  }

  @Test
  public void test9() throws Exception {
    doTest();
  }

  @Test
  public void test10() throws Exception {
    doTest();
  }

  private void doTest() {
    final String testName = getTestName(true);
    final VirtualFile f = myFixture.copyFileToProject(BASE_PATH + testName + ".xml", "res/layout/test.xml");
    myFixture.configureFromExistingVirtualFile(f);
    final String extractedFileName = "extracted.xml";
    myFixture.testAction(new AndroidExtractAsIncludeAction(new AndroidExtractAsIncludeAction.TestConfig(extractedFileName)));
    myFixture.checkResultByFile(BASE_PATH + testName + "_after.xml", true);
    myFixture.checkResultByFile("res/layout/" + extractedFileName, BASE_PATH + testName + "_extracted.xml", true);
  }

  private void doTestDisabled() {
    final String testName = getTestName(true);
    final VirtualFile f = myFixture.copyFileToProject(BASE_PATH + testName + ".xml", "res/layout/test.xml");
    myFixture.configureFromExistingVirtualFile(f);
    final Presentation p =
      myFixture.testAction(new AndroidExtractAsIncludeAction(new AndroidExtractAsIncludeAction.TestConfig("extracted.xml")));
    assertTrue(p.isVisible());
    assertFalse(p.isEnabled());
  }

  private String getTestName(boolean lowercaseFirstLetter) {
    return UsefulTestCase.getTestName(testName.getMethodName(), lowercaseFirstLetter);
  }
}
