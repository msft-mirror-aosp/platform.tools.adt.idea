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

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.google.common.truth.Truth.assertThat
import com.intellij.debugger.mockJDI.MockVirtualMachine
import com.intellij.debugger.mockJDI.types.MockArrayType
import com.intellij.debugger.mockJDI.types.MockType
import com.intellij.debugger.ui.tree.render.ClassRenderer
import com.intellij.debugger.ui.tree.render.NodeRenderer
import com.intellij.debugger.ui.tree.render.ValueIconRenderer
import com.intellij.testFramework.RuleChain
import com.sun.jdi.Type
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BaseRendererProviderTest {
  private val flagRule = FlagRule(StudioFlags.SORT_OBJECT_PROPERTIES)
  @get:Rule val rules = RuleChain(flagRule)

  private val baseRendererProvider = TestBaseRendererProvider(ClassRenderer())

  @Test
  fun isEnabled_disabledByDefault() {
    assertThat(baseRendererProvider.isEnabled).isFalse()
  }

  @Test
  fun isEnabled_enabledByFlag() {
    StudioFlags.SORT_OBJECT_PROPERTIES.override(true)
    assertThat(baseRendererProvider.isEnabled).isTrue()
  }

  @Test
  fun isApplicable_array() {
    val type = MockArrayType(MockType.createType(virtualMachine(), Any::class.java))

    assertThat(baseRendererProvider.isApplicable(type)).isFalse()
  }

  @Test
  fun isApplicable_collection() {
    val type = MockType.createType(virtualMachine(), List::class.java)

    assertThat(baseRendererProvider.isApplicable(type)).isFalse()
  }

  @Test
  fun isApplicable_map() {
    val type = MockType.createType(virtualMachine(), Map::class.java)

    assertThat(baseRendererProvider.isApplicable(type)).isFalse()
  }

  @Test
  fun isApplicable_object() {
    val type = MockType.createType(virtualMachine(), Any::class.java)

    assertThat(baseRendererProvider.isApplicable(type)).isTrue()
  }

  @Test
  fun isApplicable_notDex() {
    val type = MockType.createType(virtualMachine(isDex = false), Any::class.java)

    assertThat(baseRendererProvider.isApplicable(type)).isFalse()
  }

  @Test
  fun getRenderers() {
    assertThat(baseRendererProvider.childrenRenderer).isNotNull()
    assertThat(baseRendererProvider.valueLabelRenderer).isNotNull()
    assertThat(baseRendererProvider.valueLabelRenderer).isSameAs(baseRendererProvider.childrenRenderer)
    assertThat(baseRendererProvider.iconRenderer).isNull()
  }

  private class TestBaseRendererProvider(renderer: NodeRenderer) : BaseRendererProvider(renderer) {
    override fun getName() = "Test Renderer"

    public override fun isEnabled() = super.isEnabled

    fun isApplicable(type: Type): Boolean = getIsApplicableChecker().apply(type).get()

    public override fun getChildrenRenderer() = super.childrenRenderer

    public override fun getIconRenderer(): ValueIconRenderer? = super.iconRenderer

    public override fun getValueLabelRenderer() = super.valueLabelRenderer
  }
}

private fun virtualMachine(isDex: Boolean = true) =
  object : MockVirtualMachine() {
    override fun name() = if (isDex) "Dalvik" else "JDK"
  }
