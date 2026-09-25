package com.android.tools.profilers;

import com.android.testutils.JarTestSuiteRunner;
import com.android.tools.tests.IdeaTestSuiteBase;
import org.junit.runner.RunWith;

@RunWith(JarTestSuiteRunner.class)
@JarTestSuiteRunner.ExcludeClasses({
  com.android.tools.profilers.ProfilersTestSuite.class,  // a suite mustn't contain itself
  com.android.tools.profilers.performance.DataSeriesPerformanceTest.class,  // b/115665506
  com.android.tools.profilers.performance.LeakCanarySharkAnalysisTest.class,  // Benchmark, runs in intellij.android.profilers.performance
})
public class ProfilersTestSuite extends IdeaTestSuiteBase {
}
