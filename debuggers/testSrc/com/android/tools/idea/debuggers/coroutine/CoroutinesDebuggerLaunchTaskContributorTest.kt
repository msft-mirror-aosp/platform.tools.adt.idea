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
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.replaceService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val EXPECTED_AM_START_OPTIONS = "--attach-agent /data/data/com.test.application/code_cache/coroutine_debugger_agent.so"
private const val EXPECTED_AM_START_OPTIONS_WITH_CREATION_STACK_TRACES = "$EXPECTED_AM_START_OPTIONS=--enable-creation-stack-traces"

@RunWith(JUnit4::class)
class CoroutinesDebuggerLaunchTaskContributorTest {
  private val projectRule = ProjectRule()
  private val disposableRule = DisposableRule()
  private val project
    get() = projectRule.project

  @get:Rule
  val rule =
    RuleChain(
      projectRule,
      disposableRule,
      FlagRule(StudioFlags.COROUTINE_DEBUGGER_ENABLE, false),
      FlagRule(StudioFlags.CREATION_STACK_TRACES_ENABLE, false),
    )

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
  fun testNoAmOptionsIfFlagsDisabled() {
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEmpty()
  }

  @Test
  fun testNoAmOptionsWithDefaultSettings() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)

    assertThat(getAmStartOptions()).isEmpty()
  }

  @Test
  fun testNoAmOptionsIfNotDebuggable() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)

    assertThat(getAmStartOptions(DefaultRunExecutor.getRunExecutorInstance())).isEmpty()
  }

  @Test
  fun testNoAmOptionsIfNotDebuggable_creationStackTraces() {
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions(DefaultRunExecutor.getRunExecutorInstance())).isEmpty()
  }

  @Test
  fun testNoAmOptionsOnAPI28AndLower() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    for (api in
      listOf(AndroidVersion.VersionCodes.P, AndroidVersion.VersionCodes.O, AndroidVersion.VersionCodes.N, AndroidVersion.VersionCodes.M)) {
      whenever(device.version).thenReturn(AndroidVersion(api, 0))

      assertThat(getAmStartOptions()).isEmpty()
    }
  }

  @Test
  fun testAmOptions_coroutineDebuggerEnabled() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS)
  }

  @Test
  fun testAmOptions_coroutineDebuggerEnabled_creationStackTracesFlagDisabled() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS)
  }

  @Test
  fun testAmOptions_coroutineDebuggerEnabled_creationStackTracesDisabledInSettings() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS)
  }

  @Test
  fun testAmOptions_creationStackTracesEnabled() {
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS_WITH_CREATION_STACK_TRACES)
  }

  @Test
  fun testAmOptions_creationStackTracesEnabled_coroutineDebuggerDisabledInSettings() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS_WITH_CREATION_STACK_TRACES)
  }

  @Test
  fun testAmOptions_creationStackTracesEnabled_coroutineDebuggerFlagDisabled() {
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS_WITH_CREATION_STACK_TRACES)
  }

  @Test
  fun testAmOptions_bothFeaturesEnabled() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isEqualTo(EXPECTED_AM_START_OPTIONS_WITH_CREATION_STACK_TRACES)
  }

  @Test
  fun testLaunchEventIsTracked_defaultSettings() {
    val fakeTracker = FakeCoroutineDebuggerAnalyticsTracker()
    project.replaceService(CoroutineDebuggerAnalyticsTracker::class.java, fakeTracker, disposableRule.disposable)
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    assertThat(getAmStartOptions()).isEmpty()

    assertThat(fakeTracker.trackLaunchEventCalled).isTrue()
    assertThat(fakeTracker.launchEventIsDisabledInSettings).isTrue()
  }

  @Test
  fun testLaunchEventIsTracked_enabledInSettings() {
    val fakeTracker = FakeCoroutineDebuggerAnalyticsTracker()
    project.replaceService(CoroutineDebuggerAnalyticsTracker::class.java, fakeTracker, disposableRule.disposable)
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    CoroutineDebuggerSettings.setCoroutineDebuggerEnabled(true)

    assertThat(getAmStartOptions()).isNotEmpty()

    assertThat(fakeTracker.trackLaunchEventCalled).isTrue()
    assertThat(fakeTracker.launchEventIsDisabledInSettings).isFalse()
  }

  @Test
  fun testLaunchEventIsNotTracked_creationStackTracesOnly() {
    val fakeTracker = FakeCoroutineDebuggerAnalyticsTracker()
    project.replaceService(CoroutineDebuggerAnalyticsTracker::class.java, fakeTracker, disposableRule.disposable)
    StudioFlags.CREATION_STACK_TRACES_ENABLE.override(true)
    CoroutineDebuggerSettings.setCreationStackTracesEnabled(true)

    assertThat(getAmStartOptions()).isNotEmpty()
    assertThat(fakeTracker.trackLaunchEventCalled).isFalse()
  }

  private fun getAmStartOptions(executor: Executor = DefaultDebugExecutor.getDebugExecutorInstance()) =
    contributor.getAmStartOptions("com.test.application", configuration, device, executor)
}
