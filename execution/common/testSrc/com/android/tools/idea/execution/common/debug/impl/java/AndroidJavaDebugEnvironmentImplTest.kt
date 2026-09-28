/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.idea.execution.common.debug.impl.java

import com.android.ddmlib.Client
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
@RunsInEdt
class AndroidJavaDebugEnvironmentImplTest {
  private val projectRule = ProjectRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(projectRule).around(EdtRule())

  private val project
    get() = projectRule.project

  private val client =
    mock<Client>(defaultAnswer = RETURNS_DEEP_STUBS).also {
      whenever(it.debuggerListenPort).thenReturn(8700)
      whenever(it.clientData.packageName).thenReturn("com.example.app")
      whenever(it.device.serialNumber).thenReturn("emulator-5554")
    }

  @Test
  fun disposingNewConsoleDisposesEnvironment() {
    val env = AndroidJavaDebugEnvironmentImpl(project, client, "session", consoleViewToReuse = null, detachIsDefault = false)

    val result = env.createExecutionResult()
    assertThat(Disposer.isDisposed(env)).isFalse()

    Disposer.dispose(result.executionConsole)

    assertThat(Disposer.isDisposed(env)).isTrue()
  }

  @Test
  fun disposingReusedConsoleReleasesIt() {
    val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    val env = AndroidJavaDebugEnvironmentImpl(project, client, "session", consoleViewToReuse = console, detachIsDefault = false)

    val result = env.createExecutionResult()
    assertThat(result.executionConsole === console).isTrue()

    Disposer.dispose(console)

    assertThat(Disposer.isDisposed(env)).isTrue()
    assertThat(env.consoleViewToReuse).isNull()
  }
}
