/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android.run.runner

import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.AndroidConfigurationExecutor
import com.android.tools.idea.run.AndroidDevice
import com.android.tools.idea.run.ApkProvisionException
import com.android.tools.idea.run.DeviceFutures
import com.android.tools.idea.run.editor.DeployTarget
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.idea.blaze.android.run.BazelAndroidRunContext
import com.google.idea.blaze.android.run.deployinfo.BlazeAndroidDeployInfo
import com.google.idea.blaze.android.run.runner.BlazeAndroidDeviceSelector.DeviceSession
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.MockProjectViewManager
import com.google.idea.blaze.base.async.executor.BlazeExecutor
import com.google.idea.blaze.base.async.executor.MockBlazeExecutor
import com.google.idea.blaze.base.issueparser.BlazeIssueParserProvider
import com.google.idea.blaze.base.projectview.ProjectViewManager
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration
import com.google.idea.blaze.base.settings.BazelImportSettingsManager
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager
import com.google.idea.blaze.base.settings.BlazeUserSettings
import com.google.idea.blaze.base.settings.BuildSystemName
import com.google.idea.blaze.base.sync.aspects.BlazeBuildOutputs
import com.google.idea.blaze.base.toolwindow.Task
import com.google.idea.blaze.base.toolwindow.TasksToolWindowService
import com.google.idea.blaze.base.ui.problems.BuildTasksProblemsView
import com.google.idea.blaze.exception.BuildException
import com.google.idea.common.experiments.ExperimentService
import com.google.idea.common.experiments.MockExperimentService
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.RunCanceledByUserException
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.WindowManagerEx
import com.intellij.ui.ExperimentalUI
import com.intellij.util.ui.UIUtil
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Unit tests for error handling in [BlazeAndroidRunConfigurationRunner]. */
@RunWith(JUnit4::class)
class BlazeAndroidRunConfigurationRunnerTest : BlazeTestCase() {

  private lateinit var mockTasksToolWindowService: TasksToolWindowService
  private val loggedErrors = mutableListOf<Throwable>()

  override fun initTest(applicationServices: Container, projectServices: Container) {
    super.initTest(applicationServices, projectServices)
    Logger.setFactory { category ->
      object : DefaultLogger(category) {
        override fun error(message: String?, t: Throwable?, vararg details: String?) {
          if (t != null) {
            loggedErrors.add(t)
          }
        }
      }
    }
    applicationServices.register(ExperimentService::class.java, MockExperimentService())
    applicationServices.register(BlazeExecutor::class.java, MockBlazeExecutor())
    applicationServices.register(BlazeUserSettings::class.java, BlazeUserSettings())
    applicationServices.register(ProgressManager::class.java, CoreProgressManager())
    applicationServices.register(WindowManager::class.java, mock(WindowManagerEx::class.java))
    applicationServices.register(ExperimentalUI::class.java, mock(ExperimentalUI::class.java))

    projectServices.register(BazelImportSettingsManager::class.java, BlazeImportSettingsManager(project))
    BlazeImportSettingsManager.getInstanceForTestingOnly(project)
      .setImportSettingsForTests(Path.of("/path/to/workspace"), BuildSystemName.Bazel)
    projectServices.register(ProjectViewManager::class.java, MockProjectViewManager())
    projectServices.register(BuildTasksProblemsView::class.java, mock(BuildTasksProblemsView::class.java))
    mockTasksToolWindowService = mock(TasksToolWindowService::class.java)
    projectServices.register(TasksToolWindowService::class.java, mockTasksToolWindowService)

    registerExtensionPoint(BlazeIssueParserProvider.EP_NAME, BlazeIssueParserProvider::class.java)
  }

  @After
  fun restoreLoggerFactory() {
    UIUtil.invokeAndWaitIfNeeded { Disposer.dispose(testDisposable) }
    Logger.setFactory(::DefaultLogger)
  }

  @Test
  fun run_whenBuildThrowsNotImplementedError_marksToolWindowErrorAndThrowsExecutionException() {
    val runner = createRunner(apkBuildStep = ApkBuildStep { TODO("Not implemented build step") })

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    val thrown = assertThrows(ExecutionException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(thrown.cause).isInstanceOf(NotImplementedError::class.java)
    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.ERROR)
  }

  @Test
  fun run_whenBuildThrowsRuntimeException_marksToolWindowErrorAndThrowsExecutionException() {
    val runner = createRunner(apkBuildStep = ApkBuildStep { throw IllegalStateException("unexpected runtime crash") })

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    val thrown = assertThrows(ExecutionException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(thrown.cause).isInstanceOf(IllegalStateException::class.java)
    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.ERROR)
  }

  @Test
  fun run_whenBuildThrowsBuildException_marksToolWindowErrorAndThrowsApkBuildFailedWithCause() {
    val buildException =
      object : BuildException("Compile failed") {
        override fun isIdeError(): Boolean = false
      }
    val runner =
      createRunner(
        apkBuildStep =
          ApkBuildStep { context ->
            context.handleException("Failed to build APK", buildException)
            throw buildException
          }
      )

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    val thrown = assertThrows(ExecutionException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(thrown).isNotInstanceOf(RunCanceledByUserException::class.java)
    assertThat(thrown.message).isEqualTo("APK build failed")
    assertThat(thrown.cause).isSameInstanceAs(buildException)
    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.ERROR)
  }

  @Test
  fun run_whenBuildCancelledAndThrowsBuildException_marksToolWindowCancelledAndThrowsRunCanceledByUserException() {
    val buildException = object : BuildException("Build interrupted") {}
    val runner =
      createRunner(
        apkBuildStep =
          ApkBuildStep { context ->
            context.setCancelled()
            context.handleException("Failed to build APK", buildException)
            throw buildException
          }
      )

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    assertThrows(RunCanceledByUserException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.CANCELLED)
  }

  @Test
  fun run_whenBuildThrowsProcessCanceledException_marksToolWindowCancelledAndThrowsRunCanceledByUserException() {
    val runner = createRunner(apkBuildStep = ApkBuildStep { throw ProcessCanceledException() })

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    assertThrows(RunCanceledByUserException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.CANCELLED)
  }

  @Test
  fun run_whenDeployInfoExtractorThrowsApkProvisionException_preservesSpecificExecutionExceptionMessageAndCause() {
    val provisionException = ApkProvisionException("missing deploy info artifact")
    val runner =
      createRunner(
        apkBuildStep = ApkBuildStep { mock(BlazeBuildOutputs::class.java) },
        deployInfoExtractor = DeployInfoExtractor { _, _, _ -> throw provisionException },
      )

    val state = runner.getRunProfileState(createMockExecutor(), createMockEnvironment()) as AndroidConfigurationExecutor
    val thrown = assertThrows(ExecutionException::class.java) { state.run(EmptyProgressIndicator()) }

    assertThat(thrown.message).contains("Error retrieving deployment info from build results: missing deploy info artifact")
    assertThat(thrown.cause).isSameInstanceAs(provisionException)
    assertThat(captureFinishedTaskStatus()).isEqualTo(Task.Status.ERROR)
  }

  private fun captureFinishedTaskStatus(): Task.Status {
    val statusCaptor = ArgumentCaptor.forClass(Task.Status::class.java)
    verify(mockTasksToolWindowService).finishTask(any(), statusCaptor.capture())
    return statusCaptor.value
  }

  private fun createRunner(
    apkBuildStep: ApkBuildStep,
    deployInfoExtractor: DeployInfoExtractor = DeployInfoExtractor { _, _, _ -> mock(BlazeAndroidDeployInfo::class.java) },
    liveEditDataExtractor: LiveEditDataExtractor? = null,
  ): BlazeAndroidRunConfigurationRunner {
    val deployTarget = mock(DeployTarget::class.java)
    `when`(deployTarget.hasCustomRunProfileState(any())).thenReturn(false)
    val androidDevice = mock(AndroidDevice::class.java)
    `when`(androidDevice.launchedDevice).thenReturn(Futures.immediateFuture(mock(IDevice::class.java)))
    val deviceFutures = DeviceFutures(listOf(androidDevice))
    val deviceSelector =
      object : BlazeAndroidDeviceSelector {
        override fun getDevice(
          project: Project,
          executor: Executor,
          env: ExecutionEnvironment,
          debug: Boolean,
          runConfigId: Int,
        ): DeviceSession = DeviceSession(deployTarget, deviceFutures)
      }
    val launchStrategy = mock(BlazeAndroidDeployAndLaunchStrategy::class.java)
    `when`(launchStrategy.deviceSelector).thenReturn(deviceSelector)
    `when`(launchStrategy.createBlazeAndroidRunContext(any(), any(), any(), any())).thenReturn(mock(BazelAndroidRunContext::class.java))

    val runConfig = mock(BlazeCommandRunConfiguration::class.java)
    return BlazeAndroidRunConfigurationRunner(
      launchStrategy = launchStrategy,
      runConfig = runConfig,
      apkBuildStep = apkBuildStep,
      deployInfoExtractor = deployInfoExtractor,
      liveEditDataExtractor = liveEditDataExtractor,
    )
  }

  private fun createMockExecutor(): Executor {
    val executor = mock(Executor::class.java)
    `when`(executor.id).thenReturn(DefaultRunExecutor.EXECUTOR_ID)
    return executor
  }

  private fun createMockEnvironment(): ExecutionEnvironment {
    val env = mock(ExecutionEnvironment::class.java)
    `when`(env.project).thenReturn(project)
    return env
  }
}
