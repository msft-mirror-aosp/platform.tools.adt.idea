/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *aalbert
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.debug.childrenrenderer

import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.ClassRenderer
import com.sun.jdi.Field
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import org.jetbrains.kotlin.idea.debugger.KotlinClassRenderer
import org.jetbrains.kotlin.idea.debugger.core.render.GetterDescriptor

/** A [ClassRenderer] that sorts fields based on declaration order */
internal class AndroidKotlinClassRenderer : KotlinClassRenderer() {

  override fun buildChildren(
    value: ObjectReference,
    builder: ChildrenBuilder,
    evaluationContext: EvaluationContext,
    fields: List<Field>,
    getters: List<Method>,
  ) {
    val project = evaluationContext.project
    val fieldNames = fields.mapTo(HashSet()) { it.name() }
    val sorter = AndroidPropertySorter(project)
    val fields = sorter.sortFields(fields)

    val (withField, withoutField) =
      getters.partition {
        GetterDescriptor(value, it, project).name in fieldNames
      }

    val getters = withField + sorter.sortGetters(withoutField)
    super.buildChildren(value, builder, evaluationContext, fields, getters)
  }
}
