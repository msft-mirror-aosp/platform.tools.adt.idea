package org.jetbrains.android.refactoring;

import com.android.SdkConstants;
import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.util.ArrayUtil;
import org.jetbrains.android.AndroidTestBase;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunsInEdt
public class AndroidExtractStyleTest {
  private static final String BASE_PATH = "refactoring/extractStyle/";

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

    // b/402201770: Ensure any idempotence checks fail consistently.
    Registry.get("platform.random.idempotence.check.rate").setValue(1, projectRule.getTestRootDisposable());
  }

  @Test
  public void test1() {
    doTest("myStyle", new String[]{"android:textColor", "android:background"},
           new String[]{"android:layout_width", "android:layout_height", "android:textColor", "android:background"}, false);
  }

  @Test
  public void test2() {
    doTest("style1.s", new String[]{"android:textColor", "android:background"}, true);
  }

  @Test
  public void test3() {
    doTest("style2", new String[]{"android:textColor", "android:background"}, true);
  }

  @Test
  public void test4() {
    doTest("style1.s", new String[]{"android:textColor", "android:background"}, false);
  }

  @Test
  public void test5() {
    doTestDisabled();
  }

  @Test
  public void test6() {
    doTestDisabled();
  }

  @Test
  public void test7() {
    doTest("myStyle", new String[]{"android:textColor", "android:background"}, false);
  }

  @Test
  public void test8() {
    doTestHidden();
  }

  private void doTestHidden() {
    final String testName = getTestName(true);
    final VirtualFile f =
      myFixture.copyFileToProject(BASE_PATH + testName + ".xml", "res/values/test" + testName + ".xml");
    myFixture.configureFromExistingVirtualFile(f);
    final Presentation presentation = myFixture.testAction(new AndroidExtractStyleAction());
    assertFalse(presentation.isVisible());
  }

  private void doTestDisabled() {
    final String testName = getTestName(true);
    final VirtualFile f =
      myFixture.copyFileToProject(BASE_PATH + testName + ".xml", "res/layout/test" + testName + ".xml");
    myFixture.configureFromExistingVirtualFile(f);
    final Presentation presentation = myFixture.testAction(new AndroidExtractStyleAction());
    assertFalse(presentation.isEnabled());
    assertTrue(presentation.isVisible());
  }

  private void doTest(@NotNull String styleName,
                      @NotNull String[] attributes,
                      boolean copyInitialStylesXml) {
    doTest(styleName, attributes, attributes, copyInitialStylesXml);
  }

  private void doTest(@NotNull String styleName,
                      @NotNull String[] attributesToExtract,
                      @NotNull String[] expectedExtractableAttrs,
                      boolean copyInitialStylesXml) {
    final String testName = getTestName(true);

    if (copyInitialStylesXml) {
      myFixture.copyFileToProject(BASE_PATH + testName + "_styles.xml", "res/values/styles.xml");
    }
    final VirtualFile f =
      myFixture.copyFileToProject(BASE_PATH + testName + ".xml", "res/layout/test" + testName + ".xml");
    VirtualFile resourceDir = f.getParent().getParent();
    myFixture.configureFromExistingVirtualFile(f);
    myFixture.testAction(new AndroidExtractStyleAction(new MyConfig(resourceDir, styleName, attributesToExtract, expectedExtractableAttrs)));
    myFixture.checkResultByFile(BASE_PATH + testName + "_after.xml");
    myFixture.checkResultByFile("res/values/styles.xml", BASE_PATH + testName + "_styles_after.xml", true);
  }

  private String getTestName(boolean lowercaseFirstLetter) {
    return UsefulTestCase.getTestName(testName.getMethodName(), lowercaseFirstLetter);
  }

  private static class MyConfig extends AndroidExtractStyleAction.MyTestConfig {
    private final String[] myExpectedExtractableAttributes;

    MyConfig(@NotNull VirtualFile resourceDir,
             @NotNull String styleName,
             @NotNull String[] attributesToExtract,
             @NotNull String[] expectedExtractableAttributes) {
      super(resourceDir, styleName, attributesToExtract);
      myExpectedExtractableAttributes = expectedExtractableAttributes;
      Arrays.sort(myExpectedExtractableAttributes);
    }

    @Override
    public void validate(@NotNull List<XmlAttribute> extractableAttributes) {
      final List<String> names = new ArrayList<>(extractableAttributes.size());

      for (XmlAttribute attribute : extractableAttributes) {
        names.add(attribute.getName());
      }
      final String[] extractableAttributeNames = ArrayUtil.toStringArray(names);
      Arrays.sort(extractableAttributeNames);

      assertTrue(
        "Expected: " + Arrays.toString(myExpectedExtractableAttributes) + "\nActual: " + Arrays.toString(extractableAttributeNames),
        Arrays.equals(myExpectedExtractableAttributes, extractableAttributeNames)
      );
    }
  }
}
