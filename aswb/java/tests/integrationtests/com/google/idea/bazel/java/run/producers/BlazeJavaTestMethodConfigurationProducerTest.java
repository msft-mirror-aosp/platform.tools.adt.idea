/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.bazel.java.run.producers;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.idea.bazel.java.run.BlazeJavaTestEventsHandler;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.dependencies.TargetInfo;
import com.google.idea.blaze.base.lang.buildfile.psi.util.PsiUtils;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.BlazeCommandRunConfigurationType;
import com.google.idea.blaze.base.run.producers.BlazeFilterExistingRunConfigurationProducer;
import com.google.idea.blaze.base.run.producers.BlazeRunConfigurationProducerTestCase;
import com.google.idea.blaze.base.run.producers.TestContextRunConfigurationProducer;
import com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState;
import com.google.idea.blaze.base.run.targetfinder.TargetFinder;
import com.intellij.execution.Location;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.junit2.PsiMemberParameterizedLocation;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.List;
import javax.swing.tree.TreePath;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for configuring run configurations from java test methods. */
@RunWith(JUnit4.class)
public class BlazeJavaTestMethodConfigurationProducerTest
    extends BlazeRunConfigurationProducerTestCase {

  @Before
  public final void setup() {
    // required for IntelliJ to recognize annotations, JUnit version, etc.
    workspace.createPsiFile(
        new WorkspacePath("org/junit/runner/RunWith.java"),
        "package org.junit.runner;"
            + "public @interface RunWith {"
            + "    Class<? extends Runner> value();"
            + "}");
    workspace.createPsiFile(
        new WorkspacePath("org/junit/Test.java"),
        "package org.junit;",
        "public @interface Test {}");
    workspace.createPsiFile(
        new WorkspacePath("org/junit/runners/JUnit4.java"),
        "package org.junit.runners;",
        "public class JUnit4 {}");
  }

  @Test
  public void testProducedFromPsiMethod() throws Throwable {
    // Arrange
    PsiFile javaFile =
        createAndIndexFile(
            new WorkspacePath("java/com/google/test/TestClass.java"),
            "package com.google.test;",
            "@org.junit.runner.RunWith(org.junit.runners.JUnit4.class)",
            "public class TestClass {",
            "  @org.junit.Test",
            "  public void testMethod1() {}",
            "}");

    registerTargets(new TargetInfo(Label.create("//java/com/google/test:TestClass"), "java_test"));
    PsiMethod method =
        runReadAction(() -> PsiUtils.findFirstChildOfClassRecursive(javaFile, PsiMethod.class));

    // Act
    ConfigurationContext context = createContextFromPsi(method);
    List<ConfigurationFromContext> configurations = getConfigurationsFromContext(context);
    ConfigurationFromContext fromContext = configurations.get(0);

    // Assert
    assertThat(configurations).hasSize(1);
    assertThat(fromContext.isProducedBy(TestContextRunConfigurationProducer.class)).isTrue();
    assertThat(fromContext.getConfiguration()).isInstanceOf(BlazeCommandRunConfiguration.class);

    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) fromContext.getConfiguration();
    assertThat(config.getName()).isEqualTo("Bazel test TestClass.testMethod1");
    performFirstRun(config, context);
    assertThat(config.getTargetPatterns()).containsExactly("//java/com/google/test:TestClass");
    assertThat(getTestFilterContents(config))
        .isEqualTo(
            "--test_filter=com.google.test.TestClass(\\[.*\\])?(\\(.*\\))?#testMethod1(\\[.*\\])?(\\(.*\\))?$");
    assertThat(getCommandType(config)).isEqualTo(BlazeCommandName.TEST);

    BlazeCommandRunConfigurationCommonState state =
        config.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);
    assertThat(state.getBlazeFlagsState().getRawFlags()).contains(BlazeFlags.DISABLE_TEST_SHARDING);
  }

  @Test
  public void testConfigFromContextRecognizesItsOwnConfig() throws Throwable {
    PsiMethod method = setupGenericJunitTestClassAndBlazeTarget();
    ConfigurationContext context = createContextFromPsi(method);
    RunnerAndConfigurationSettings settings = runReadAction(context::getConfiguration);
    assertThat(settings).isNotNull();
    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) settings.getConfiguration();
    performFirstRun(config, context);

    boolean isConfigFromContext =
        runReadAction(
            () ->
                new TestContextRunConfigurationProducer()
                    .isConfigurationFromContext(config, context));

    assertThat(isConfigFromContext).isTrue();
  }

  @Test
  public void testConfigWithDifferentLabelIsIgnored() throws Throwable {
    // Arrange
    PsiMethod method = setupGenericJunitTestClassAndBlazeTarget();
    ConfigurationContext context = createContextFromPsi(method);
    RunnerAndConfigurationSettings settings = runReadAction(context::getConfiguration);
    assertThat(settings).isNotNull();
    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) settings.getConfiguration();
    performFirstRun(config, context);
    // modify the label, and check that is enough for the producer to class it as different.
    config.setTargetPattern("//java/com/google/test:DifferentTestTarget");

    // Act
    boolean isConfigFromContext =
        runReadAction(
            () ->
                new TestContextRunConfigurationProducer()
                    .isConfigurationFromContext(config, context));

    // Assert
    assertThat(isConfigFromContext).isTrue();
  }

  @Test
  public void testConfigWithDifferentFilterIgnored() throws Throwable {
    // Arrange
    PsiMethod method = setupGenericJunitTestClassAndBlazeTarget();
    ConfigurationContext context = createContextFromPsi(method);
    RunnerAndConfigurationSettings settings = runReadAction(context::getConfiguration);
    assertThat(settings).isNotNull();
    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) settings.getConfiguration();
    performFirstRun(config, context);
    BlazeCommandRunConfigurationCommonState handlerState =
        config.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);

    // modify the test filter, and check that is enough for the producer to class it as different.
    List<String> flags = new ArrayList<>(handlerState.getBlazeFlagsState().getRawFlags());
    flags.removeIf((flag) -> flag.startsWith(BlazeFlags.TEST_FILTER));
    flags.add(BlazeFlags.TEST_FILTER + "=com.google.test.DifferentTestClass#");
    handlerState.getBlazeFlagsState().setRawFlags(flags);

    // Act
    boolean isConfigFromContext =
        runReadAction(
            () ->
                new TestContextRunConfigurationProducer()
                    .isConfigurationFromContext(config, context));

    // Assert
    assertThat(isConfigFromContext).isFalse();
  }

  @Test
  public void testFilteredConfigurationFromParameterizedTestTreeElement() throws Throwable {
    // Arrange
    PsiMethod method = setupGenericJunitTestClassAndBlazeTarget();
    TargetInfo targetInfo =
        new TargetInfo(Label.create("//java/com/google/test:TestClass"), "java_test");
    registerExtension(
        TargetFinder.EP_NAME, (project, label) -> Futures.immediateFuture(targetInfo));

    RunManager runManager = RunManager.getInstance(getProject());
    RunnerAndConfigurationSettings baseSettings =
        runManager.createConfiguration(
            "Bazel test TestClass", BlazeCommandRunConfigurationType.getInstance().getFactory());
    BlazeCommandRunConfiguration baseConfig =
        (BlazeCommandRunConfiguration) baseSettings.getConfiguration();
    baseConfig.setTargetInfo(targetInfo);
    baseConfig.setName("Bazel test TestClass");
    runManager.addConfiguration(baseSettings);
    runManager.setSelectedConfiguration(baseSettings);

    Location<?> parameterizedLocation =
        runReadAction(
            () ->
                new PsiMemberParameterizedLocation(
                    getProject(), method, method.getContainingClass(), "[param]"));
    SMTestProxy testProxy =
        new SMTestProxy(
            "testMethod1[param]",
            false,
            "java:test://com.google.test.TestClass/testMethod1[param]") {
          @Override
          public Location<?> getLocation(Project project, GlobalSearchScope searchScope) {
            return parameterizedLocation;
          }
        };
    TreePath selectionPath = new TreePath(new Object[] {"root", testProxy});
    SMTRunnerTestTreeView treeView =
        new SMTRunnerTestTreeView() {
          @Override
          public TreePath[] getSelectionPaths() {
            return new TreePath[] {selectionPath};
          }

          @Override
          public boolean isPathSelected(TreePath path) {
            return selectionPath.equals(path);
          }

          @Override
          public SMTestProxy getSelectedTest(TreePath path) {
            return selectionPath.equals(path) ? testProxy : null;
          }
        };

    DataContext dataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(Location.DATA_KEY, parameterizedLocation)
            .add(RunConfiguration.DATA_KEY, baseConfig)
            .add(SMTRunnerTestTreeView.SM_TEST_RUNNER_VIEW, treeView)
            .build();
    ConfigurationContext context =
        runReadAction(() -> ConfigurationContext.getFromContext(dataContext));

    // Act
    BlazeFilterExistingRunConfigurationProducer producer =
        new BlazeFilterExistingRunConfigurationProducer();
    ConfigurationFromContext fromContext =
        runReadAction(() -> producer.createConfigurationFromContext(context));
    assertThat(fromContext).isNotNull();
    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) fromContext.getConfiguration();
    assertThat(config.getName()).isEqualTo("Bazel test TestClass (filtered)");

    performFirstRun(producer, config, context);

    // Assert
    assertThat(config.getName()).isEqualTo("Bazel test TestClass (filtered)");
    assertThat(config.getTargetPatterns()).containsExactly("//java/com/google/test:TestClass");
    assertThat(getTestFilterContents(config))
        .isEqualTo("--test_filter=com.google.test.TestClass#testMethod1\\[param\\]$");
    assertThat(getCommandType(config)).isEqualTo(BlazeCommandName.TEST);

    BlazeCommandRunConfigurationCommonState state =
        config.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);
    assertThat(state.getBlazeFlagsState().getRawFlags()).contains(BlazeFlags.DISABLE_TEST_SHARDING);
  }

  @Test
  public void testFilteredConfigurationFromInheritedAbstractTestTreeElement() throws Throwable {
    // Arrange
    createAndIndexFile(
        new WorkspacePath("java/com/google/test/BaseTest.java"),
        "package com.google.test;",
        "public abstract class BaseTest {",
        "  @org.junit.Test",
        "  public void testInherited() {}",
        "}");
    createAndIndexFile(
        new WorkspacePath("java/com/google/test/ConcreteTest.java"),
        "package com.google.test;",
        "@org.junit.runner.RunWith(org.junit.runners.JUnit4.class)",
        "public class ConcreteTest extends com.google.test.BaseTest {}");

    TargetInfo targetInfo =
        new TargetInfo(Label.create("//java/com/google/test:ConcreteTest"), "java_test");
    registerTargets(targetInfo);
    registerExtension(
        TargetFinder.EP_NAME, (project, label) -> Futures.immediateFuture(targetInfo));

    RunManager runManager = RunManager.getInstance(getProject());
    RunnerAndConfigurationSettings baseSettings =
        runManager.createConfiguration(
            "Bazel test ConcreteTest", BlazeCommandRunConfigurationType.getInstance().getFactory());
    BlazeCommandRunConfiguration baseConfig =
        (BlazeCommandRunConfiguration) baseSettings.getConfiguration();
    baseConfig.setTargetInfo(targetInfo);
    baseConfig.setName("Bazel test ConcreteTest");
    runManager.addConfiguration(baseSettings);
    runManager.setSelectedConfiguration(baseSettings);

    BlazeJavaTestEventsHandler eventsHandler = new BlazeJavaTestEventsHandler();
    Location<?> inheritedParameterizedLocation =
        runReadAction(
            () ->
                eventsHandler
                    .getTestLocator()
                    .getLocation(
                        "java:test",
                        "com.google.test.ConcreteTest/testInherited(param)",
                        getProject(),
                        GlobalSearchScope.allScope(getProject()))
                    .get(0));
    SMTestProxy testProxy =
        new SMTestProxy(
            "testInherited(param)",
            false,
            "java:test://com.google.test.ConcreteTest/testInherited(param)") {
          @Override
          public Location<?> getLocation(Project project, GlobalSearchScope searchScope) {
            return inheritedParameterizedLocation;
          }
        };
    TreePath selectionPath = new TreePath(new Object[] {"root", testProxy});
    SMTRunnerTestTreeView treeView =
        new SMTRunnerTestTreeView() {
          @Override
          public TreePath[] getSelectionPaths() {
            return new TreePath[] {selectionPath};
          }

          @Override
          public boolean isPathSelected(TreePath path) {
            return selectionPath.equals(path);
          }

          @Override
          public SMTestProxy getSelectedTest(TreePath path) {
            return selectionPath.equals(path) ? testProxy : null;
          }
        };

    DataContext dataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(Location.DATA_KEY, inheritedParameterizedLocation)
            .add(RunConfiguration.DATA_KEY, baseConfig)
            .add(SMTRunnerTestTreeView.SM_TEST_RUNNER_VIEW, treeView)
            .build();
    ConfigurationContext context =
        runReadAction(() -> ConfigurationContext.getFromContext(dataContext));

    // Act
    BlazeFilterExistingRunConfigurationProducer producer =
        new BlazeFilterExistingRunConfigurationProducer();
    ConfigurationFromContext fromContext =
        runReadAction(() -> producer.createConfigurationFromContext(context));
    assertThat(fromContext).isNotNull();
    BlazeCommandRunConfiguration config =
        (BlazeCommandRunConfiguration) fromContext.getConfiguration();
    assertThat(config.getName()).isEqualTo("Bazel test ConcreteTest (filtered)");

    performFirstRun(producer, config, context);

    // Assert
    assertThat(config.getName()).isEqualTo("Bazel test ConcreteTest (filtered)");
    assertThat(config.getTargetPatterns()).containsExactly("//java/com/google/test:ConcreteTest");
    assertThat(getTestFilterContents(config))
        .isEqualTo("--test_filter=com.google.test.ConcreteTest#testInherited\\(param\\)$");
    assertThat(getCommandType(config)).isEqualTo(BlazeCommandName.TEST);

    BlazeCommandRunConfigurationCommonState state =
        config.getHandlerStateIfType(BlazeCommandRunConfigurationCommonState.class);
    assertThat(state.getBlazeFlagsState().getRawFlags()).contains(BlazeFlags.DISABLE_TEST_SHARDING);
  }

  /**
   * Creates a JUnit test class and associated blaze target, and returns a PsiMethod from that
   * class. Used when the implementation details (class name, target string, etc.) aren't relevant
   * to the test.
   */
  private PsiMethod setupGenericJunitTestClassAndBlazeTarget() throws Throwable {
    PsiFile javaFile =
        createAndIndexFile(
            new WorkspacePath("java/com/google/test/TestClass.java"),
            "package com.google.test;",
            "@org.junit.runner.RunWith(org.junit.runners.JUnit4.class)",
            "public class TestClass {",
            "  @org.junit.Test",
            "  public void testMethod1() {}",
            "}");

    registerTargets(new TargetInfo(Label.create("//java/com/google/test:TestClass"), "java_test"));

    return runReadAction(() -> PsiUtils.findFirstChildOfClassRecursive(javaFile, PsiMethod.class));
  }
}
