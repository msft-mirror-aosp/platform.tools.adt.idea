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
package com.google.idea.blaze.base.run

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.run.confighandler.BlazeCommandRunConfigurationRunner
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Unit tests for [BlazeBeforeRunTaskProvider]. */
@RunWith(JUnit4::class)
class BlazeBeforeRunTaskProviderTest : BlazeTestCase() {

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
    val contentManager = mock(RunContentManager::class.java)
    `when`(contentManager.getToolWindowIdByEnvironment(any())).thenReturn("Run")
    projectServices.register(RunContentManager::class.java, contentManager)
  }

  @After
  fun restoreLoggerFactory() {
    Logger.setFactory(::DefaultLogger)
  }

  @Test
  fun executeTask_whenRunnerThrowsNotImplementedError_returnsFalseInsteadOfPropagating() {
    val provider = BlazeBeforeRunTaskProvider()
    val config = mock(BlazeCommandRunConfiguration::class.java)
    val task = provider.createTask(config)!!
    val env = createEnvironmentWithRunner {
      TODO("Before-run step not implemented")
    }

    val result = provider.executeTask(DataContext.EMPTY_CONTEXT, config, env, task)

    assertThat(result).isFalse()
  }

  @Test
  fun executeTask_whenRunnerThrowsRuntimeException_returnsFalseInsteadOfPropagating() {
    val provider = BlazeBeforeRunTaskProvider()
    val config = mock(BlazeCommandRunConfiguration::class.java)
    val task = provider.createTask(config)!!
    val env = createEnvironmentWithRunner { throw IllegalStateException("unexpected failure") }

    val result = provider.executeTask(DataContext.EMPTY_CONTEXT, config, env, task)

    assertThat(result).isFalse()
  }

  @Test
  fun executeTask_whenRunnerThrowsProcessCanceledException_rethrowsProcessCanceledException() {
    val provider = BlazeBeforeRunTaskProvider()
    val config = mock(BlazeCommandRunConfiguration::class.java)
    val task = provider.createTask(config)!!
    val env = createEnvironmentWithRunner { throw ProcessCanceledException() }

    assertThrows(ProcessCanceledException::class.java) {
      provider.executeTask(DataContext.EMPTY_CONTEXT, config, env, task)
    }
  }

  private fun createEnvironmentWithRunner(onBeforeRun: () -> Boolean): ExecutionEnvironment {
    val runner =
      object : BlazeCommandRunConfigurationRunner {
        override fun getRunProfileState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null

        override fun executeBeforeRunTask(environment: ExecutionEnvironment): Boolean = onBeforeRun()
      }
    val env = mock(ExecutionEnvironment::class.java)
    val runProfile = mock(RunProfile::class.java)
    `when`(runProfile.name).thenReturn("test-config")
    `when`(env.project).thenReturn(project)
    `when`(env.runProfile).thenReturn(runProfile)
    `when`(env.getCopyableUserData(BlazeCommandRunConfigurationRunner.RUNNER_KEY)).thenReturn(runner)
    return env
  }
}
