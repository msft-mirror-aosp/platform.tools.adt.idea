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
package com.android.tools.idea.debug.childrenrenderer

import com.google.common.truth.Truth.assertThat
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AndroidKotlinRendererProviderTest {
  private val applicationRule = ApplicationRule()
  @get:Rule val rules = RuleChain(applicationRule)

  @Test
  fun name() {
    assertThat(AndroidKotlinRendererProvider().name).isEqualTo("Android Kotlin Class")
  }

  @Test
  fun className() {
    assertThat(AndroidKotlinRendererProvider().className).isEqualTo("kotlin.Any")
  }

  @Test
  fun pluginOrder() {
    val extensions = CompoundRendererProvider.EP_NAME.extensions
    val pos = extensions.indexOfFirst { it is AndroidKotlinRendererProvider }
    val posOfKotlinProvider = extensions.indexOfFirst { it.javaClass.name == "KotlinClassRendererProvider" }

    assertThat(pos).isGreaterThan(posOfKotlinProvider)
  }
}
