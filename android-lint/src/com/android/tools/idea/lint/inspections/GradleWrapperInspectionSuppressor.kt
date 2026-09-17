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
package com.android.tools.idea.lint.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.facet.ProjectFacetManager
import com.intellij.psi.PsiElement
import org.jetbrains.android.facet.AndroidFacet

/**
 * Suppresses IntelliJ's GradleLatestMinorVersionInspection ("LatestMinorVersion") in Android projects, where Gradle wrapper version
 * checking is handled by Android Lint's AGP_DEPENDENCY check. Lint ensures the suggested Gradle version is compatible with the project's
 * Android Gradle Plugin (AGP) version.
 */
class GradleWrapperInspectionSuppressor : InspectionSuppressor {

  override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
    if (toolId != GRADLE_LATEST_MINOR_VERSION_INSPECTION_ID) return false
    val project = element.project
    if (project.isDisposed) return false
    val file = element.containingFile ?: return false
    return file.name.equals(GRADLE_WRAPPER_PROPERTIES_FILE_NAME, ignoreCase = true) &&
      ProjectFacetManager.getInstance(project).hasFacets(AndroidFacet.ID)
  }

  override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
    return SuppressQuickFix.EMPTY_ARRAY
  }

  companion object {
    const val GRADLE_LATEST_MINOR_VERSION_INSPECTION_ID = "LatestMinorVersion"
    private const val GRADLE_WRAPPER_PROPERTIES_FILE_NAME = "gradle-wrapper.properties"
  }
}
