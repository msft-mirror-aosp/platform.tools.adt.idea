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
package com.google.idea.blaze.android.run

import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.AndroidDebuggerState
import com.android.tools.idea.execution.common.debug.impl.java.AndroidJavaDebugger
import com.android.tools.idea.run.editor.AndroidDebuggerInfoProvider
import com.google.common.truth.Truth.assertThat
import com.google.idea.bazel.java.AndroidBlazeRules.RuleTypes.ANDROID_BINARY
import com.google.idea.bazel.java.AndroidBlazeRules.RuleTypes.ANDROID_INSTRUMENTATION_TEST
import com.google.idea.blaze.android.cppimpl.debug.BlazeNativeAndroidDebugger
import com.google.idea.blaze.android.run.binary.BlazeAndroidBinaryRunConfigurationState
import com.google.idea.blaze.android.run.runner.BlazeAndroidDebuggerProvider
import com.google.idea.blaze.android.run.runner.BlazeAndroidDebuggerService
import com.google.idea.blaze.android.run.test.BlazeAndroidTestRunConfigurationState
import com.google.idea.blaze.base.BlazeIntegrationTestCase
import com.google.idea.blaze.base.dependencies.TargetInfo
import com.google.idea.blaze.base.model.MockBlazeProjectDataBuilder
import com.google.idea.blaze.base.model.MockBlazeProjectDataManager
import com.google.idea.blaze.base.model.primitives.GenericBlazeRules
import com.google.idea.blaze.base.model.primitives.Label
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration
import com.google.idea.blaze.base.run.BlazeCommandRunConfigurationType
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager
import com.intellij.openapi.project.Project
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Integration tests for [BlazeCommandAndroidDebuggerInfoProvider]. */
@RunWith(JUnit4::class)
class BlazeCommandAndroidDebuggerInfoProviderTest : BlazeIntegrationTestCase() {
  @Test
  fun `extension point resolves provider for synced Blaze project`() {
    val directProvider = BlazeCommandAndroidDebuggerInfoProvider()
    assertThat(directProvider.supportsProject(project)).isFalse()

    registerProjectService(
      BlazeProjectDataManager::class.java,
      MockBlazeProjectDataManager(MockBlazeProjectDataBuilder.builder(workspaceRoot).build()),
    )

    val supportedProviders = AndroidDebuggerInfoProvider.EP_NAME.extensionList.filter { it.supportsProject(project) }
    assertThat(supportedProviders).hasSize(1)
    val registeredProvider = supportedProviders.single()
    assertThat(registeredProvider).isInstanceOf(BlazeCommandAndroidDebuggerInfoProvider::class.java)
    assertThat(
        registeredProvider.getSelectedAndroidDebugger(createRunConfiguration("//java/com/foo/app:app", ANDROID_BINARY.kind.kindString))
      )
      .isInstanceOf(AndroidJavaDebugger::class.java)
  }

  @Test
  fun `getAndroidDebuggers returns Blaze native and Java for android_binary run configuration`() {
    val binaryConfig = createRunConfiguration("//java/com/foo/app:app", ANDROID_BINARY.kind.kindString)
    assertThat(binaryConfig.getHandlerStateIfType(BlazeAndroidBinaryRunConfigurationState::class.java)).isNotNull()

    val debuggers = BlazeCommandAndroidDebuggerInfoProvider().getAndroidDebuggers(binaryConfig)

    assertThat(debuggers).hasSize(2)
    assertThat(debuggers[0]).isInstanceOf(BlazeNativeAndroidDebugger::class.java)
    assertThat(debuggers[1]).isInstanceOf(AndroidJavaDebugger::class.java)
  }

  @Test
  fun `getAndroidDebuggers returns Blaze native and Java for android_instrumentation_test run configuration`() {
    val testConfig = createRunConfiguration("//javatests/com/foo/app:test", ANDROID_INSTRUMENTATION_TEST.kind.kindString)
    assertThat(testConfig.getHandlerStateIfType(BlazeAndroidTestRunConfigurationState::class.java)).isNotNull()

    val debuggers = BlazeCommandAndroidDebuggerInfoProvider().getAndroidDebuggers(testConfig)

    assertThat(debuggers).hasSize(2)
    assertThat(debuggers[0]).isInstanceOf(BlazeNativeAndroidDebugger::class.java)
    assertThat(debuggers[1]).isInstanceOf(AndroidJavaDebugger::class.java)
  }

  @Test
  fun `getAndroidDebuggers returns empty for non-Android run configuration`() {
    val shBinaryConfig = createRunConfiguration("//tools:script", GenericBlazeRules.RuleTypes.SH_BINARY.kind.kindString)

    val debuggers = BlazeCommandAndroidDebuggerInfoProvider().getAndroidDebuggers(shBinaryConfig)

    assertThat(debuggers).isEmpty()
  }

  @Test
  fun `debugger service and getAndroidDebuggers delegate to registered AndroidDebuggerProvider extension`() {
    val customDebugger = AndroidJavaDebugger()
    val customState = customDebugger.createState()
    val customProvider =
      object : BlazeAndroidDebuggerProvider {
        override fun getDebugger(isNativeDebuggingEnabled: Boolean): AndroidDebugger<*> = customDebugger

        override fun getRunConfigurationDebuggers(): List<AndroidDebugger<*>> = listOf(customDebugger)

        override fun getDebuggerState(project: Project, debugger: AndroidDebugger<*>): AndroidDebuggerState = customState
      }
    registerExtension(BlazeAndroidDebuggerProvider.EP_NAME, customProvider)

    val debuggerService = BlazeAndroidDebuggerService.getInstance(project)
    assertThat(debuggerService.getDebugger(true)).isSameInstanceAs(customDebugger)
    assertThat(debuggerService.getDebuggerState(customDebugger)).isSameInstanceAs(customState)

    val provider = BlazeCommandAndroidDebuggerInfoProvider()
    val binaryConfig = createRunConfiguration("//java/com/foo/app:app", ANDROID_BINARY.kind.kindString)
    val testConfig = createRunConfiguration("//javatests/com/foo/app:test", ANDROID_INSTRUMENTATION_TEST.kind.kindString)

    assertThat(provider.getAndroidDebuggers(binaryConfig)).containsExactly(customDebugger)
    assertThat(provider.getAndroidDebuggers(testConfig)).containsExactly(customDebugger)
  }

  private fun createRunConfiguration(label: String, kindString: String): BlazeCommandRunConfiguration =
    BlazeCommandRunConfiguration(project, BlazeCommandRunConfigurationType.getInstance().factory, "test").apply {
      setTargetInfo(TargetInfo(Label.create(label), kindString))
    }
}
