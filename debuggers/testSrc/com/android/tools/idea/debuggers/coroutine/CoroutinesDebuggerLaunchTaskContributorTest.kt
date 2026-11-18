/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.debuggers.coroutine

import com.android.ddmlib.IDevice
import com.android.flags.junit.FlagRule
import com.android.sdklib.AndroidVersion
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.run.AndroidRunConfigurationBase
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.registerServiceInstance
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val EXPECTED_AM_START_OPTIONS = "--attach-agent /data/data/com.test.application/code_cache/coroutine_debugger_agent.so"

@RunWith(JUnit4::class)
class CoroutinesDebuggerLaunchTaskContributorTest {
  private val projectRule = ProjectRule()
  private val project
    get() = projectRule.project

  @get:Rule val rule = RuleChain(projectRule, FlagRule(StudioFlags.COROUTINE_DEBUGGER_ENABLE, false))

  private val configuration = mock<AndroidRunConfigurationBase>()
  private val contributor = CoroutineDebuggerLaunchTaskContributor()
  private val device = mock<IDevice>()

  @Before
  fun setUp() {
    whenever(configuration.project).thenReturn(project)
    whenever(device.version).thenReturn(AndroidVersion(AndroidVersion.VersionCodes.Q, 0))
  }

  @After
  fun tearDown() {
    CoroutineDebuggerSettings.reset()
  }

  @Test
  fun testNoAmOptionsIfFlagIsDisabled() {
    assertThat(getAmStartOptions()).isEmpty()
  }

  @Test
  fun testNoAmOptionsIfNotDebuggable() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    assertThat(getAmStartOptions(DefaultRunExecutor.getRunExecutorInstance())).isEmpty()
  }

  @Test
  fun testNoAmOptionsIfSettingsNotEnabled() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(false)

    assertThat(getAmStartOptions()).isEmpty()
  }

  @Test
  fun testNoAmOptionsOnAPI28AndLower() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    for (api in
      listOf(AndroidVersion.VersionCodes.P, AndroidVersion.VersionCodes.O, AndroidVersion.VersionCodes.N, AndroidVersion.VersionCodes.M)) {
      whenever(device.version).thenReturn(AndroidVersion(api, 0))

      assertThat(getAmStartOptions()).isEmpty()
    }
  }

  @Test
  fun testAmOptionsIsCorrect() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS)
  }

  @Test
  fun testLaunchEventIsTracked() {
    val fakeTracker = FakeCoroutineDebuggerAnalyticsTracker()
    project.registerServiceInstance(CoroutineDebuggerAnalyticsTracker::class.java, fakeTracker)
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    getAmStartOptions()

    assertThat(fakeTracker.trackLaunchEventCalled).isTrue()
    assertThat(fakeTracker.launchEventIsDisabledInSettings).isFalse()

    fakeTracker.reset()
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(false)

    getAmStartOptions()

    assertThat(fakeTracker.trackLaunchEventCalled).isTrue()
    assertThat(fakeTracker.launchEventIsDisabledInSettings).isTrue()
  }

  private fun getAmStartOptions(executor: Executor = DefaultDebugExecutor.getDebugExecutorInstance()) =
    contributor.getAmStartOptions("com.test.application", configuration, device, executor)
}
