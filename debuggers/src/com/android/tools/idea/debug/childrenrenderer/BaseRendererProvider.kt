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

import com.android.tools.idea.flags.StudioFlags
import com.intellij.debugger.impl.DexDebugFacility
import com.intellij.debugger.impl.instanceOf
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.NodeRenderer
import com.intellij.psi.CommonClassNames
import com.intellij.psi.CommonClassNames.JAVA_UTIL_COLLECTION
import com.sun.jdi.ArrayType
import com.sun.jdi.Type
import java.util.concurrent.CompletableFuture
import java.util.function.Function

abstract class BaseRendererProvider(private val renderer: NodeRenderer) : CompoundRendererProvider() {
  override fun getChildrenRenderer() = renderer

  override fun getValueLabelRenderer() = renderer

  override fun isEnabled(): Boolean = StudioFlags.SORT_OBJECT_PROPERTIES.get()

  override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> = Function { type: Type? ->
    if (!type.isApplicable()) {
      val completableFuture = CompletableFuture<Boolean>()
      completableFuture.complete(false)
      return@Function completableFuture
    }
    renderer.isApplicableAsync(type)
  }
}

private fun Type.isArray() = this is ArrayType

private fun Type.isCollection() = instanceOf(JAVA_UTIL_COLLECTION)

private fun Type.isMap() = instanceOf(CommonClassNames.JAVA_UTIL_MAP)

private fun Type.isDex() = DexDebugFacility.isDex(virtualMachine())

private fun Type?.isApplicable() = this != null && isDex() && !(isArray() || isCollection() || isMap())
