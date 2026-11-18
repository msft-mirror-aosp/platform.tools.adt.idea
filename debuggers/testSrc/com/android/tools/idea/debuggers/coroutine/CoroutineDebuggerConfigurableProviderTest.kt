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

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.ui.components.JBCheckBox
import com.intellij.xdebugger.settings.DebuggerConfigurableProvider
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class CoroutineDebuggerConfigurableProviderTest {
  @get:Rule val rule = RuleChain(ApplicationRule(), EdtRule(), FlagRule(StudioFlags.COROUTINE_DEBUGGER_ENABLE, false))

  @After
  fun tearDown() {
    CoroutineDebuggerSettings.reset()
  }

  @Test
  fun testProviderIsDisabledWithFeatureFlag() {
    assertThat(getProvider().getConfigurables(DebuggerSettingsCategory.GENERAL)).isEmpty()
  }

  @Test
  fun testProviderReturnsCoroutineConfigurable() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)

    val configurable = getConfigurable()

    assertThat(configurable.displayName).isEqualTo("Coroutine Debugger")
    assertThat(getCheckBoxes(configurable).map { it.text }).containsExactly("Enable coroutine debugger")
  }

  @Test
  fun testConfigurableCanResetAndApplySettings() {
    StudioFlags.COROUTINE_DEBUGGER_ENABLE.override(true)
    val configurable = getConfigurable()
    val coroutineDebuggerCheckBox = getCheckBoxes(configurable).single()

    assertThat(CoroutineDebuggerSettings.isCoroutineDebuggerEnabled()).isTrue()
    assertThat(coroutineDebuggerCheckBox.isSelected).isFalse()

    configurable.reset()

    assertThat(coroutineDebuggerCheckBox.isSelected).isTrue()
    assertThat(configurable.isModified).isFalse()

    coroutineDebuggerCheckBox.isSelected = false
    assertThat(configurable.isModified).isTrue()
    assertThat(CoroutineDebuggerSettings.isCoroutineDebuggerEnabled()).isTrue()

    configurable.apply()
    assertThat(configurable.isModified).isFalse()
    assertThat(CoroutineDebuggerSettings.isCoroutineDebuggerEnabled()).isFalse()
  }

  private fun getConfigurable(): Configurable = getProvider().getConfigurables(DebuggerSettingsCategory.GENERAL).single()

  private fun getCheckBoxes(configurable: Configurable): List<JBCheckBox> =
    configurable.createComponent()!!.components.filterIsInstance<JBCheckBox>()

  // first() throws if the provider isn't registered, so every test also verifies registration.
  private fun getProvider(): CoroutineDebuggerConfigurableProvider {
    return DebuggerConfigurableProvider.EXTENSION_POINT.extensionList.filterIsInstance<CoroutineDebuggerConfigurableProvider>().first()
  }
}
