package org.jetbrains.android;

import static com.android.tools.idea.testing.AndroidProjectRuleKt.onEdt;

import com.android.tools.idea.testing.AndroidProjectRule;
import com.android.tools.idea.testing.EdtAndroidProjectRule;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspection;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.RunsInEdt;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@RunsInEdt
public class AndroidJavaHighlightingTest {
  private static final String BASE_PATH = "/javaHighlighting/";

  @Rule
  public EdtAndroidProjectRule projectRule = onEdt(AndroidProjectRule.withSdk());

  private CodeInsightTestFixture myFixture;

  @Before
  public void setUp() {
    myFixture = projectRule.getFixture();
    myFixture.setTestDataPath(AndroidTestBase.getTestDataPath());
  }

  @Test
  public void testParcelable() throws Exception {
    myFixture.enableInspections(new UnusedDeclarationInspectionBase(true));
    final VirtualFile f = myFixture.copyFileToProject(BASE_PATH + "Parcelable.java", "src/p1/p2/MyParcelable.java");
    myFixture.configureFromExistingVirtualFile(f);
    myFixture.checkHighlighting(true, false, true);
  }

  @Test
  public void testUnusedConstructors() throws Exception {
    // Regression test for https://code.google.com/p/android/issues/detail?id=77054
    // Checks that various constructors are not marked as unused
    final UnusedDeclarationInspection inspection = new UnusedDeclarationInspection(true);
    myFixture.enableInspections(inspection);
    final VirtualFile f = myFixture.copyFileToProject(BASE_PATH + "UnusedConstructors.java", "src/p1/p2/UnusedConstructors.java");
    myFixture.configureFromExistingVirtualFile(f);
    myFixture.checkHighlighting(true, false, true);
  }
}
