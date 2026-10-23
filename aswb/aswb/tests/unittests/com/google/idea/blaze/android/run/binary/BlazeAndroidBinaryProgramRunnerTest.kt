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
package com.google.idea.blaze.android.run.binary

import com.android.tools.idea.execution.common.AndroidConfigurationExecutor
import com.android.tools.idea.execution.common.AndroidConfigurationExecutorRunProfileState
import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.mock.MockFileDocumentManagerImpl
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.CoreProgressManager
import java.util.concurrent.ExecutionException as ConcurrentExecutionException
import org.jetbrains.concurrency.Promise
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Unit tests for [BlazeAndroidBinaryProgramRunner]. */
@RunWith(JUnit4::class)
class BlazeAndroidBinaryProgramRunnerTest : BlazeTestCase() {

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
    applicationServices.register(FileDocumentManager::class.java, MockFileDocumentManagerImpl(null) { null })
    applicationServices.register(
      ProgressManager::class.java,
      object : CoreProgressManager() {
        override fun run(task: Task) {
          try {
            task.run(EmptyProgressIndicator())
            task.onSuccess()
          } catch (e: ProcessCanceledException) {
            task.onCancel()
          } catch (t: Throwable) {
            task.onThrowable(t)
          } finally {
            task.onFinished()
          }
        }
      },
    )
  }

  @After
  fun restoreLoggerFactory() {
    Logger.setFactory(::DefaultLogger)
  }

  @Test
  fun execute_whenExecutorThrowsNotImplementedError_rejectsPromiseWithExecutionException() {
    val runner = BlazeAndroidBinaryProgramRunner()
    val env = createMockEnvironment()
    val state = createExecutorState {
      TODO("LiveEdit step not implemented yet")
    }

    val promise = runner.execute(env, state)

    assertThat(promise.state).isEqualTo(Promise.State.REJECTED)
    val thrown = assertThrows(ConcurrentExecutionException::class.java) { promise.blockingGet(0) }
    assertThat(thrown.cause).isInstanceOf(ExecutionException::class.java)
    assertThat(thrown.cause?.cause).isInstanceOf(NotImplementedError::class.java)
  }

  @Test
  fun execute_whenExecutorThrowsRuntimeException_rejectsPromiseWithExecutionException() {
    val runner = BlazeAndroidBinaryProgramRunner()
    val env = createMockEnvironment()
    val state = createExecutorState { throw IllegalStateException("unexpected failure") }

    val promise = runner.execute(env, state)

    assertThat(promise.state).isEqualTo(Promise.State.REJECTED)
    val thrown = assertThrows(ConcurrentExecutionException::class.java) { promise.blockingGet(0) }
    assertThat(thrown.cause).isInstanceOf(ExecutionException::class.java)
    assertThat(thrown.cause?.cause).isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun execute_whenExecutorThrowsExecutionException_rejectsPromiseWithSameExecutionException() {
    val runner = BlazeAndroidBinaryProgramRunner()
    val env = createMockEnvironment()
    val expected = ExecutionException("APK build failed")
    val state = createExecutorState { throw expected }

    val promise = runner.execute(env, state)

    assertThat(promise.state).isEqualTo(Promise.State.REJECTED)
    val thrown = assertThrows(ConcurrentExecutionException::class.java) { promise.blockingGet(0) }
    assertThat(thrown.cause).isSameInstanceAs(expected)
  }

  @Test
  fun execute_whenExecutorThrowsProcessCanceledException_resolvesPromiseWithNull() {
    val runner = BlazeAndroidBinaryProgramRunner()
    val env = createMockEnvironment()
    val state = createExecutorState { throw ProcessCanceledException() }

    val promise = runner.execute(env, state)

    assertThat(promise.state).isEqualTo(Promise.State.SUCCEEDED)
    assertThat(promise.blockingGet(0)).isNull()
  }

  private fun createMockEnvironment(): ExecutionEnvironment {
    val env = mock(ExecutionEnvironment::class.java)
    val runProfile = mock(RunProfile::class.java)
    val executor = mock(Executor::class.java)
    `when`(runProfile.name).thenReturn("app")
    `when`(executor.id).thenReturn(DefaultRunExecutor.EXECUTOR_ID)
    `when`(env.project).thenReturn(project)
    `when`(env.runProfile).thenReturn(runProfile)
    `when`(env.executor).thenReturn(executor)
    return env
  }

  private fun createExecutorState(onRun: (ProgressIndicator) -> RunContentDescriptor): AndroidConfigurationExecutorRunProfileState {
    val config = mock(RunConfiguration::class.java)
    val executor =
      object : AndroidConfigurationExecutor {
        override val configuration: RunConfiguration = config

        override fun run(indicator: ProgressIndicator): RunContentDescriptor = onRun(indicator)

        override fun debug(indicator: ProgressIndicator): RunContentDescriptor = onRun(indicator)
      }
    return AndroidConfigurationExecutorRunProfileState(executor)
  }
}
