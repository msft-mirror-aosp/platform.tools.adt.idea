/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.bazel.java.run

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.junit2.PsiMemberParameterizedLocation
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement

/**
 * A [PsiMemberParameterizedLocation] that preserves the concrete test subclass during ancestor traversal for inherited test methods and
 * records optional class-level parameter suffixes from Test Results URLs.
 */
class BlazePsiMemberParameterizedLocation(
  project: Project,
  psiElement: PsiElement,
  containingClass: PsiClass?,
  paramSetName: String,
  val classParamSetName: String?,
) : PsiMemberParameterizedLocation(project, psiElement, containingClass, paramSetName) {

  override fun <T : PsiElement> getAncestors(
    ancestorClass: Class<T>,
    strict: Boolean,
  ): Iterator<Location<T>> {
    val targetClass = containingClass
    if (targetClass == null || psiElement === targetClass) {
      return super.getAncestors(ancestorClass, strict)
    }
    val classLocation = PsiLocation.fromPsiElement(project, targetClass)
    val fromClass = classLocation.getAncestors(ancestorClass, false)
    if (strict) return fromClass
    return object : Iterator<Location<T>> {
      private var firstStep = ancestorClass.isInstance(psiElement)

      override fun hasNext(): Boolean = firstStep || fromClass.hasNext()

      @Suppress("UNCHECKED_CAST")
      override fun next(): Location<T> {
        val location = if (firstStep) this@BlazePsiMemberParameterizedLocation as Location<T> else fromClass.next()
        firstStep = false
        return location
      }
    }
  }
}
