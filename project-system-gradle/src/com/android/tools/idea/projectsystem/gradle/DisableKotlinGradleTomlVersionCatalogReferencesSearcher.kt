/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.projectsystem.gradle

import com.android.tools.idea.gradle.navigation.GradleKtsVersionCatalogReferencesSearcher
import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * Disable `KotlinGradleTomlVersionCatalogReferencesSearcher` since studio has its own implementation for gradle KTS files using version
 * catalog called [GradleKtsVersionCatalogReferencesSearcher]
 */
class DisableKotlinGradleTomlVersionCatalogReferencesSearcher : ApplicationInitializedListener {
  override suspend fun execute() {
    // The extension is matched by class name: the class is internal, and its module (intellij.kotlin.gradle.codeInsight.toml) has
    // private visibility, so it cannot be depended on from outside the Kotlin plugin.
    val unregistered = ReferencesSearch.EP_NAME.point.unregisterExtensions(
      { className, _ -> className != KOTLIN_SEARCHER_CLASS_NAME },
      /* stopAfterFirstMatch = */ true,
    )
    if (!unregistered) {
      // An error, not a warning, so that tests fail if the class is renamed or moved upstream.
      thisLogger().error("$KOTLIN_SEARCHER_CLASS_NAME is not registered; it may have been renamed")
    }
  }

  private companion object {
    const val KOTLIN_SEARCHER_CLASS_NAME =
      "org.jetbrains.kotlin.idea.gradle.versionCatalog.toml.KotlinGradleTomlVersionCatalogReferencesSearcher"
  }
}
