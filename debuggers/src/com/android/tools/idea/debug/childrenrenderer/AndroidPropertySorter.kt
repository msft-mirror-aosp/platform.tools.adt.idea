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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.sun.jdi.Field
import com.sun.jdi.Method
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.debugger.core.render.GetterDescriptor
import org.jetbrains.kotlin.idea.testIntegration.framework.KotlinPsiBasedTestFramework.Companion.asKtNamedFunction
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Sorts class properties (fields and getters) based on declaration order in source files
 *
 * TODO: Add support for Kotlin function local classes
 * TODO: Add support for getters
 * TODO: Add support for interface fields
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

  fun sortGetters(getters: List<Method>): List<Method> {
    return runReadActionBlocking {
      try {
        getters
          .groupByTo(LinkedHashMap()) { it.declaringType().name() }
          .flatMap { (typeName, group) ->
            sortGetters(typeName, group)
          }
      } catch (e: Throwable) {
        thisLogger().error("Failed to sort getters", e)
        getters
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

  private fun sortGetters(typeName: String, getters: List<Method>): List<Method> {
    val names = getGetters(typeName)
    val order = names.mapIndexed { index, name -> name to index }.toMap()
    return getters.sortedWith(compareBy { order[it.name()] ?: Int.MAX_VALUE })
  }

  private fun getFields(typeName: String): List<String> {
    val psiClass = DebuggerUtils.findClass(typeName, project, scope) ?: findMethodLocalClass(typeName)
    return psiClass?.findFields() ?: emptyList()
  }

  fun getGetters(typeName: String): List<String> {
    val psiClass = DebuggerUtils.findClass(typeName, project, scope)
    return when (psiClass != null) {
      true -> psiClass.findGetters()
      false -> findMethodLocalClass(typeName)?.findGetters() ?: emptyList()
    }
  }

  private fun findMethodLocalClass(typeName: String): PsiClass? {
    val (containingClass, remainingSegments) = findContainingClass(typeName) ?: return null
    if (remainingSegments.isEmpty()) {
      return null
    }

    // TODO: Look into Class$fun$Class$Class or Class$fun$Class$fun$Class
    val methodName = remainingSegments.first()
    val segments = remainingSegments.drop(1)
    val methods = containingClass.findMethodsByName(methodName, false)
    return methods.firstNotNullOfOrNull {
      it.asKtNamedFunction()?.findLocalClassOrObject(segments)?.toLightClass()
    }
  }

  private fun findContainingClass(typeName: String): Pair<PsiClass, List<String>>? {
    val segments = typeName.split('$')
    if (segments.size < 2) {
      return null
    }

    for (i in (segments.size - 1) downTo 1) {
      val candidateName = segments.asSequence().take(i).joinToString("$")
      val foundClass = DebuggerUtils.findClass(candidateName, project, scope)
      if (foundClass != null) {
        return foundClass to segments.drop(i)
      }
    }
    return null
  }

  private fun KtNamedFunction.findLocalClassOrObject(segments: List<String>): KtClassOrObject? {
    return when {
      segments.isEmpty() -> null
      segments.isAnonymousObjectWithVarName() -> findAnonymousObject(segments[segments.size - 2], segments.last().toInt())
      segments.isAnonymousObject() -> findAnonymousObject(segments.first().toInt())
      else -> findNamedLocalClass(segments.last().replaceFirst(Regex("^\\d+"), ""))
    }
  }
}

private fun KtNamedFunction.findAnonymousObject(ordinal: Int): KtObjectDeclaration? {
  return PsiTreeUtil.findChildrenOfType(this, KtObjectDeclaration::class.java)
    .asSequence()
    .filter { it.isObjectLiteral() }
    .elementAtOrNull(ordinal - 1)
}

private fun KtNamedFunction.findAnonymousObject(varName: String, ordinal: Int): KtObjectDeclaration? {
  return PsiTreeUtil.findChildrenOfType(this, KtObjectDeclaration::class.java)
    .asSequence()
    .filter { it.isObjectLiteral() }
    .filter { obj ->
      val prop = PsiTreeUtil.getParentOfType(obj, KtProperty::class.java)
      prop?.name == varName
    }
    .elementAtOrNull(ordinal - 1)
}

// Named local class, e.g. ["LocalClass"]
private fun KtNamedFunction.findNamedLocalClass(localClassName: String): KtClassOrObject? {
  return PsiTreeUtil.findChildrenOfType(this, KtClassOrObject::class.java).firstOrNull { it.name == localClassName }
}

// Anonymous object with variable name, e.g. ["o", "1"]
// TODO: Investigate nested declarations
private fun List<String>.isAnonymousObjectWithVarName() =
  (size >= 2) && last().all { it.isDigit() } // Anonymous object with variable name, e.g. ["o", "1"]

// Anonymous object without variable name, e.g. ["1"]
private fun List<String>.isAnonymousObject() = (size == 1) && first().all { it.isDigit() }

private fun PsiClass.findFields() = fields.map { it.name }

private fun PsiClass.findGetters(): List<String> {
  val methods = classAndInterfaces().flatMap { it.methods.asList() }
  return methods.filter { it.isGetter() }.map { it.name }
}

private fun PsiMethod.isGetter() = GetterDescriptor.GETTER_PREFIXES.any { name.startsWith(it) } && !hasParameters()

private fun PsiClass.classAndInterfaces(): List<PsiClass> {
  return listOf(this) + interfaces.flatMapTo(LinkedHashSet()) { it.classAndInterfaces() }
}
