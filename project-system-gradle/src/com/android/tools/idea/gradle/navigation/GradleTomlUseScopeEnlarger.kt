// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.android.tools.idea.gradle.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ScopeOptimizer
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import org.jetbrains.plugins.gradle.config.GradleBuildscriptSearchScope
import org.jetbrains.plugins.gradle.service.resolve.getVersionCatalogFiles
import org.jetbrains.plugins.gradle.service.resolve.isInVersionCatalog
import org.toml.lang.psi.TomlElement

/**
 * This is a copy from JetBrains TOML navigation fix
 * https://github.com/JetBrains/intellij-community/commit/ae5d725ec6e24c00414efdf79b774306442720c2.
 */
class GradleTomlUseScopeEnlarger : UseScopeEnlarger() {
  override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
    if (!isInVersionCatalogFile(element)) return null
    return GradleBuildscriptSearchScope(element.project)
  }
}

/**
 * Restricts the use scope of version catalog [TomlElement]s to Gradle buildscripts and the
 * containing version catalog file itself, preventing reference searches (such as
 * [com.intellij.gradle.java.toml.codeInspection.UnusedVersionCatalogEntryInspection]) from
 * scanning unrelated project files.
 */
class GradleTomlUseScopeOptimizer : ScopeOptimizer {
  override fun getRestrictedUseScope(element: PsiElement): SearchScope? {
    if (!isInVersionCatalogFile(element)) return null
    val containingFile = element.containingFile?.virtualFile ?: return null
    val project = element.project
    return GradleBuildscriptSearchScope(project)
      .uniteWith(GlobalSearchScope.fileScope(project, containingFile))
  }
}

private fun isInVersionCatalogFile(element: PsiElement): Boolean {
  if (element !is TomlElement) return false
  val containingFile = element.containingFile?.virtualFile ?: return false
  return isInVersionCatalog(element) ||
    containingFile in getVersionCatalogFiles(element.project).values
}
