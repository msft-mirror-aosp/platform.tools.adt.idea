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

import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.sun.jdi.Field

/**
 * Sorts class properties (fields and getters) based on declaration order in source files
 *
 * TODO: Add support for Kotlin function local classes
 * TODO: Add support for getters
 */
internal class AndroidPropertySorter(private val project: Project) {
  private val scope = GlobalSearchScope.allScope(project)

  fun sortFields(fields: List<Field>): List<Field> {
    return runReadActionBlocking {
      try {
        fields
          .groupByTo(LinkedHashMap()) { it.declaringType().name() }
          .flatMap { (typeName, group) ->
            sortFields(typeName, group)
          }
      } catch (e: Throwable) {
        thisLogger().error("Failed to sort fields", e)
        fields
      }
    }
  }

  private fun sortFields(typeName: String, fields: List<Field>): List<Field> {
    val fieldNames = getFields(typeName)
    val fieldOrder = fieldNames.mapIndexed { index, name -> name to index }.toMap()
    return fields.sortedWith(
      compareBy {
        val name = it.name()
        val order = fieldOrder[name]
        when {
          order != null -> order
          name.startsWith("this$") -> Int.MAX_VALUE - 1
          else -> Int.MAX_VALUE
        }
      }
    )
  }

  fun getFields(typeName: String): List<String> {
    val psiClass = DebuggerUtils.findClass(typeName, project, scope) ?: return emptyList()
    return psiClass.fields.map { it.name }
  }
}
