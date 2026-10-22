/*
 * Copyright (C) 2020 The Android Open Source Project
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
@file:JvmName("NullabilityUtils")

package com.android.tools.idea.psi

import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiType
import com.intellij.psi.TypeAnnotationProvider

private const val PREFIX_ANDROIDX = "androidx.annotation"
private const val PREFIX_SUPPORT = "android.support.annotation"
private const val NONNULL = "NonNull"
private const val NULLABLE = "Nullable"

private fun hasClass(project: Project, context: PsiElement?, classFqn: String): Boolean {
  val module = context?.let { ModuleUtilCore.findModuleForPsiElement(it) }
  return if (module != null) {
    JavaLibraryUtil.hasLibraryClass(module, classFqn)
  } else {
    JavaLibraryUtil.hasLibraryClass(project, classFqn)
  }
}

fun Project.createNullabilityAnnotation(isNonNull: Boolean, context: PsiElement?): PsiAnnotation {
  // Prefer androidx.annotation as much as possible, as it is the preferred annotation moving
  // forward, but fall back to the support annotation in case we are loading a legacy project
  val prefix =
    if (hasClass(this, context, "$PREFIX_ANDROIDX.$NONNULL") || !hasClass(this, context, "$PREFIX_SUPPORT.$NONNULL")) {
      PREFIX_ANDROIDX
    } else {
      PREFIX_SUPPORT
    }
  val suffix = if (isNonNull) NONNULL else NULLABLE

  return PsiElementFactory.getInstance(this).createAnnotationFromText("@$prefix.$suffix", context)
}

fun Project.annotateType(type: PsiType, isNonNull: Boolean, context: PsiElement?): PsiType {
  // Note: all other type annotations are removed.
  val annotations = arrayOf(createNullabilityAnnotation(isNonNull, context))
  // Ideally we would call type.annotate() in all cases. Unfortunately, some PsiType
  // subclasses do not recompute nullability when annotations change (IDEA-377644).
  return when (type) {
    is PsiArrayType -> PsiArrayType(type.componentType, annotations)
    else -> type.annotate(TypeAnnotationProvider.Static.create(annotations))
  }
}
