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
package com.android.tools.idea.startup

import com.android.tools.idea.IdeInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryKeyBean
import com.intellij.testFramework.ApplicationRule
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

/** Ensures JetBrains Terminal AI agents are disabled in Android Studio (b/578854528). */
class AndroidStudioTerminalAiAgentsTest {
  @get:Rule val appRule = ApplicationRule()

  @Test
  fun testTerminalAiAgentsAreDisabled() {
    Assume.assumeTrue(IdeInfo.getInstance().isAndroidStudio)
    val agentsKey = "terminal.agent.predefined.actions.enabled"
    assertThat(Registry.`is`(agentsKey)).named("Registry value '$agentsKey'").isFalse()
    var foundOverrideRegistryKey = false
    for (registryKey in ExtensionPointName<RegistryKeyBean>("com.intellij.registryKey").extensionList) {
      if ((registryKey.key == agentsKey) && registryKey.overrides) {
        assertThat(foundOverrideRegistryKey).isFalse()
        assertThat(registryKey.defaultValue.toBoolean()).isFalse()
        foundOverrideRegistryKey = true
      }
    }
    assertWithMessage("Expected to find overriding registry key '$agentsKey'").that(foundOverrideRegistryKey).isTrue()
  }
}
