/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.bazel.java.run;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.idea.bazel.java.AndroidBlazeRules;
import com.google.idea.bazel.java.JavaBlazeRules;
import com.google.idea.bazel.java.sync.source.JavaLikeLanguage;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.bazel.BuildSystem;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker.Capability;
import com.google.idea.blaze.base.bazel.BuildSystemProvider;
import com.google.idea.blaze.base.bazel.FakeBuildInvoker;
import com.google.idea.blaze.base.bazel.FakeBuildSystem;
import com.google.idea.blaze.base.bazel.FakeBuildSystemProvider;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeCommandRunnerExperiments;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.command.BuildFlagsProvider;
import com.google.idea.blaze.base.dependencies.TargetInfo;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.io.TempDirectoryProvider;
import com.google.idea.blaze.base.io.TempDirectoryProviderImpl;
import com.google.idea.blaze.base.issueparser.BlazeIssueParserProvider;
import com.google.idea.blaze.base.model.primitives.GenericBlazeRules;
import com.google.idea.blaze.base.model.primitives.Kind;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.BlazeCommandRunConfigurationType;
import com.google.idea.blaze.base.run.ExecutorType;
import com.google.idea.blaze.base.run.confighandler.BlazeCommandGenericRunConfigurationHandlerProvider;
import com.google.idea.blaze.base.run.confighandler.BlazeCommandRunConfigurationHandlerProvider;
import com.google.idea.blaze.base.run.confighandler.PendingTargetRunConfigurationHandlerProvider;
import com.google.idea.blaze.base.run.smrunner.BlazeTestEventsHandler;
import com.google.idea.blaze.base.run.state.BlazeCommandRunConfigurationCommonState;
import com.google.idea.blaze.base.run.targetfinder.TargetFinder;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.settings.BazelImportSettingsManager;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.settings.BlazeUserSettings;
import com.google.idea.blaze.base.settings.BuildSystemName;
import com.google.idea.common.experiments.ExperimentService;
import com.google.idea.common.experiments.MockExperimentService;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.filters.TextConsoleBuilderFactoryImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BlazeJavaDebugRunProfileState}. */
@RunWith(JUnit4.class)
public class BlazeJavaDebugRunProfileStateTest extends BlazeTestCase {

  private static final Supplier<BlazeJavaDebugRunProfileState.IpVersion> DEFAULT_DETECTOR =
      BlazeJavaDebugRunProfileState.ipVersionDetector;

  private BlazeCommandRunConfiguration configuration;
  private ImmutableSet<Capability> invokerCapabilities =
      ImmutableSet.of(Capability.ATTACH_JAVA_DEBUGGER, Capability.RETURN_PROCESS_HANDLER);
  private final AtomicReference<Set<? extends Capability>> lastRequestedCapabilities =
      new AtomicReference<>();
  private final AtomicReference<BlazeCommand> lastInvokedCommand = new AtomicReference<>();

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    BlazeJavaDebugRunProfileState.ipVersionDetector = () -> BlazeJavaDebugRunProfileState.IpVersion.V4;
    projectServices.register(
        BazelImportSettingsManager.class, new BlazeImportSettingsManager(project));
    BlazeImportSettingsManager.getInstanceForTestingOnly(getProject())
        .setImportSettingsForTests(java.nio.file.Path.of(""), BuildSystemName.Blaze);

    ExperimentService experimentService = new MockExperimentService();
    applicationServices.register(ExperimentService.class, experimentService);
    applicationServices.register(BlazeUserSettings.class, new BlazeUserSettings());
    applicationServices.register(TempDirectoryProvider.class, new TempDirectoryProviderImpl());
    applicationServices.register(FileOperationProvider.class, new FakeFileOperationProvider());
    applicationServices.register(
        TextConsoleBuilderFactory.class, new TextConsoleBuilderFactoryImpl());

    ExtensionPointImpl<Kind.Provider> kindProviderEp =
        registerExtensionPoint(Kind.Provider.EP_NAME, Kind.Provider.class);
    kindProviderEp.registerExtension(new GenericBlazeRules(), testDisposable);
    kindProviderEp.registerExtension(new JavaBlazeRules(), testDisposable);
    kindProviderEp.registerExtension(new AndroidBlazeRules(), testDisposable);
    applicationServices.register(Kind.ApplicationState.class, new Kind.ApplicationState());

    projectServices.register(ProjectViewManager.class, new MockProjectViewManager());

    ExtensionPointImpl<TargetFinder> targetFinderEp =
        registerExtensionPoint(TargetFinder.EP_NAME, TargetFinder.class);
    targetFinderEp.registerExtension(new MockTargetFinder(), testDisposable);

    ExtensionPointImpl<JavaLikeLanguage> javaLikeEp =
        registerExtensionPoint(JavaLikeLanguage.EP_NAME, JavaLikeLanguage.class);
    javaLikeEp.registerExtension(new JavaLikeLanguage.Java(), testDisposable);

    registerExtensionPoint(BuildFlagsProvider.EP_NAME, BuildFlagsProvider.class);
    registerExtensionPoint(BlazeTestEventsHandler.EP_NAME, BlazeTestEventsHandler.class);
    registerExtensionPoint(BlazeIssueParserProvider.EP_NAME, BlazeIssueParserProvider.class);

    ExtensionPointImpl<BlazeCommandRunConfigurationHandlerProvider> handlerProviderEp =
        registerExtensionPoint(
            BlazeCommandRunConfigurationHandlerProvider.EP_NAME,
            BlazeCommandRunConfigurationHandlerProvider.class);
    handlerProviderEp.registerExtension(
        new PendingTargetRunConfigurationHandlerProvider(), testDisposable);
    handlerProviderEp.registerExtension(
        new BlazeJavaRunConfigurationHandlerProvider(), testDisposable);
    handlerProviderEp.registerExtension(
        new BlazeCommandGenericRunConfigurationHandlerProvider(), testDisposable);

    configuration =
        new BlazeCommandRunConfigurationType().getFactory().createTemplateConfiguration(project);
  }

  @Override
  protected BuildSystemProvider createBuildSystemProvider() {
    FakeBuildSystem delegate =
        FakeBuildSystem.builder(BuildSystemName.Bazel)
            .setBuildInvoker(
                FakeBuildInvoker.builder()
                    .invokeCommand(ImmutableList.of("/usr/bin/blaze"))
                    .build())
            .build();
    return FakeBuildSystemProvider.builder()
        .setBuildSystem(
            (BuildSystem)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {BuildSystem.class},
                    (proxy, method, args) -> {
                      if ("getBuildInvoker".equals(method.getName())
                          && args != null
                          && args.length == 2) {
                        @SuppressWarnings("unchecked")
                        Set<? extends Capability> reqs = (Set<? extends Capability>) args[1];
                        lastRequestedCapabilities.set(ImmutableSet.copyOf(reqs));
                        if (!invokerCapabilities.containsAll(reqs)) {
                          return Optional.empty();
                        }
                        FakeBuildInvoker delegateInvoker =
                            FakeBuildInvoker.builder()
                                .invokeCommand(ImmutableList.of("/usr/bin/blaze"))
                                .capabilities(invokerCapabilities)
                                .build();
                        BuildInvoker invokerProxy =
                            (BuildInvoker)
                                Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {BuildInvoker.class},
                                    (invProxy, invMethod, invArgs) -> {
                                      if ("invokeAsProcessHandler".equals(invMethod.getName())) {
                                        lastInvokedCommand.set(
                                            ((BlazeCommand.Builder) invArgs[0]).build());
                                      }
                                      return invMethod.invoke(delegateInvoker, invArgs);
                                    });
                        return Optional.of(invokerProxy);
                      }
                      return method.invoke(delegate, args);
                    }))
        .build();
  }

  @Test
  public void flagsShouldBeAppendedIfPresent() {
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    handlerState.getBlazeFlagsState().setRawFlags(ImmutableList.of("--flag1", "--flag2"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.RUN,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .isEqualTo(
            ImmutableList.of(
                "command",
                BlazeFlags.getToolTagFlag(),
                "--flag1",
                "--flag2",
                "--",
                "//label:rule"));
  }

  @Test
  public void debugFlagShouldBeIncludedForJavaTest() {
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .isEqualTo(
            ImmutableList.of(
                "command",
                BlazeFlags.getToolTagFlag(),
                "--java_debug",
                "--test_arg=--wrapper_script_flag=--debug=127.0.0.1:5005",
                "--",
                "//label:rule"));
  }

  @Test
  public void debugFlagShouldBeIncludedForJavaBinary() {
    configuration.setTargetInfo(
        new TargetInfo(Label.create("//label:java_binary_rule"), "java_binary"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .isEqualTo(
            ImmutableList.of(
                "command",
                BlazeFlags.getToolTagFlag(),
                "--",
                "//label:java_binary_rule",
                "--wrapper_script_flag=--debug=127.0.0.1:5005"));
  }

  @Test
  public void kotlinxCoroutinesJavaAgentShouldBeAddedAsJavaAgent() {
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:main"), "java_binary"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));

    assertThat(
        BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                project,
                configuration,
                ImmutableList.of(),
                ExecutorType.DEBUG,
                "/path/to/kotlinx-coroutines-lib.jar")
            .build()
            .toArgumentList()).isNotEmpty();
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    "/path/to/kotlinx-coroutines-lib.jar")
                .build()
                .toArgumentList())
        .contains("--jvmopt=-javaagent:/path/to/kotlinx-coroutines-lib.jar");
  }

  @Test
  public void kotlinxCoroutinesJavaAgentShouldBeAddedAsWrapperScriptFlagWhenExperimentEnabled() {
    ((MockExperimentService) ExperimentService.getInstance())
        .setExperiment(
            BlazeCommandRunnerExperiments.BAZEL_DEBUG_USE_WRAPPER_SCRIPT_FLAG_FOR_JAVA_AGENT, true);
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:main"), "java_binary"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));

    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    "/path/to/kotlinx-coroutines-lib.jar")
                .build()
                .toArgumentList())
        .contains(
            "--wrapper_script_flag=--jvm_flag=-javaagent:/path/to/kotlinx-coroutines-lib.jar");

    configuration.setTargetInfo(new TargetInfo(Label.create("//label:test"), "java_test"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    "/path/to/kotlinx-coroutines-lib.jar")
                .build()
                .toArgumentList())
        .contains(
            "--test_arg=--wrapper_script_flag=--jvm_flag=-javaagent:/path/to/kotlinx-coroutines-lib.jar");
  }

  @After
  public void restoreDetector() throws Exception {
    BlazeJavaDebugRunProfileState.ipVersionDetector = DEFAULT_DETECTOR;
  }

  @Test
  public void debugFlagShouldBeIPv4IfDetectorSaysV4() {
    BlazeJavaDebugRunProfileState.ipVersionDetector = () -> BlazeJavaDebugRunProfileState.IpVersion.V4;
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .contains("--test_arg=--wrapper_script_flag=--debug=127.0.0.1:5005");
  }

  @Test
  public void debugFlagShouldBeIPv6IfDetectorSaysV6() {
    BlazeJavaDebugRunProfileState.ipVersionDetector = () -> BlazeJavaDebugRunProfileState.IpVersion.V6;
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .contains("--test_arg=--wrapper_script_flag=--debug=[::1]:5005");
  }

  @Test
  public void debugFlagShouldBeSystemIfDetectorSaysSystem() {
    BlazeJavaDebugRunProfileState.ipVersionDetector =
        () -> BlazeJavaDebugRunProfileState.IpVersion.SYSTEM;
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.fromString("command"));
    assertThat(
            BlazeJavaDebugRunProfileState.getBlazeCommandBuilder(
                    project,
                    configuration,
                    ImmutableList.of(),
                    ExecutorType.DEBUG,
                    /* kotlinxCoroutinesJavaAgent= */ null)
                .build()
                .toArgumentList())
        .contains("--test_arg=--wrapper_script_flag=--debug=5005");
  }

  @Test
  public void startProcess_withDebuggerCapableInvoker_invokesBlazeCli() throws Exception {
    configuration.setTargetInfo(new TargetInfo(Label.create("//label:rule"), "java_test"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.TEST);

    ExecutionEnvironment env =
        new ExecutionEnvironmentBuilder(project, new DefaultDebugExecutor())
            .runProfile(configuration)
            .runner(new BlazeJavaDebuggerRunner())
            .build();

    ProcessHandler processHandler = new BlazeJavaDebugRunProfileState(env).startProcess();

    assertThat(processHandler).isNotNull();
    assertThat(lastRequestedCapabilities.get())
        .containsExactly(Capability.ATTACH_JAVA_DEBUGGER, Capability.RETURN_PROCESS_HANDLER);
    assertThat(lastInvokedCommand.get().toArgumentList())
        .containsAtLeast(
            "test",
            "--java_debug",
            "--test_arg=--wrapper_script_flag=--debug=127.0.0.1:5005",
            "//label:rule");
  }

  @Test
  public void startProcess_withoutDebuggerCapableInvoker_binaryTarget_throws() {
    invokerCapabilities = ImmutableSet.of(Capability.RETURN_PROCESS_HANDLER);
    configuration.setTargetInfo(
        new TargetInfo(Label.create("//label:java_binary_rule"), "java_binary"));
    BlazeCommandRunConfigurationCommonState handlerState =
        (BlazeCommandRunConfigurationCommonState) configuration.getHandler().getState();
    handlerState.getCommandState().setCommand(BlazeCommandName.RUN);

    ExecutionEnvironment env =
        new ExecutionEnvironmentBuilder(project, new DefaultDebugExecutor())
            .runProfile(configuration)
            .runner(new BlazeJavaDebuggerRunner())
            .build();

    assertThrows(
        NoSuchElementException.class, () -> new BlazeJavaDebugRunProfileState(env).startProcess());
    assertThat(lastRequestedCapabilities.get())
        .containsExactly(Capability.ATTACH_JAVA_DEBUGGER, Capability.RETURN_PROCESS_HANDLER);
  }

  private static class MockTargetFinder implements TargetFinder {
    @Override
    public Future<TargetInfo> findTarget(
        Project project, com.google.idea.blaze.common.Label label) {
      String kind;
      if (label.getName().equals("java_binary_rule")) {
        kind = "java_binary";
      } else {
        kind = "java_test";
      }
      return Futures.immediateFuture(new TargetInfo(Label.create(label), kind));
    }
  }

  private static class MockProjectViewManager extends ProjectViewManager {
    @Override
    public ProjectViewSet getProjectViewSet() {
      return ProjectViewSet.builder().build();
    }

    @Override
    public ProjectViewSet reloadProjectView(BlazeContext context) {
      return ProjectViewSet.builder().build();
    }

    @Override
    public ProjectViewSet doLoadProjectView(
        BlazeContext context, Path projectViewRootFile, Path workspaceRoot) {
      return ProjectViewSet.EMPTY;
    }
  }

  private static class FakeFileOperationProvider extends FileOperationProvider {
    @Override
    public Path createTempFile(
        Path tempDirectory, String prefix, String suffix, FileAttribute<?>... attributes) {
      return tempDirectory.resolve(prefix + "1337" + suffix);
    }
  }
}
