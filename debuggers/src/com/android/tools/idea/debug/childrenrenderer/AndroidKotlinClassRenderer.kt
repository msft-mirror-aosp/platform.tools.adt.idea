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
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeDescriptorFactory
import com.intellij.debugger.ui.tree.NodeManager
import com.intellij.debugger.ui.tree.render.ClassRenderer
import com.sun.jdi.Field
import com.sun.jdi.ObjectReference
import java.util.concurrent.CompletableFuture
import org.jetbrains.kotlin.idea.debugger.KotlinClassRenderer

/**
 * A [ClassRenderer] that sorts fields based on declaration order
 *
 * TODO: Support sorting getters (blocked on https://github.com/JetBrains/intellij-community/pull/3679)
 */
internal class AndroidKotlinClassRenderer : KotlinClassRenderer() {

  override fun createNodesToShow(
    fields: List<Field>,
    evaluationContext: EvaluationContext,
    parentDescriptor: ValueDescriptorImpl,
    nodeManager: NodeManager,
    nodeDescriptorFactory: NodeDescriptorFactory,
    objRef: ObjectReference,
  ): CompletableFuture<List<DebuggerTreeNode>> {
    StudioFlags.SORT_OBJECT_PROPERTIES.override(true)
    val fields = AndroidPropertySorter(evaluationContext.project).sortFields(fields)
    return super.createNodesToShow(fields, evaluationContext, parentDescriptor, nodeManager, nodeDescriptorFactory, objRef)
  }
}
