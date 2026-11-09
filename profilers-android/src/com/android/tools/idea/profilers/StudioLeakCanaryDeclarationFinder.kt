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
@file:JvmName("StudioLeakCanaryDeclarationFinder")

package com.android.tools.idea.profilers

import com.android.ide.common.repository.GoogleMavenArtifactId
import com.android.tools.idea.projectsystem.getModuleSystem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/** A line in a file. [line] starts at 0. */
data class StudioLeakCanaryDeclaration(val file: VirtualFile, val line: Int)

private val BUILD_FILE_NAMES = listOf("build.gradle.kts", "build.gradle")
private const val VERSION_CATALOG_PATH = "gradle/libs.versions.toml"

private val logger: Logger
  get() = Logger.getInstance("StudioLeakCanaryDeclarationFinder")

/**
 * Finds the line that declares studio-leakcanary, by its Maven group. Looks in [buildFile] first, then in [versionCatalog]. If neither has
 * it, returns the first line of [buildFile]. Uses the loaded document text when there is one, so unsaved edits count. A file that can't be
 * read is skipped.
 */
fun findStudioLeakCanaryDeclaration(buildFile: VirtualFile, versionCatalog: VirtualFile?): StudioLeakCanaryDeclaration {
  for (file in listOfNotNull(buildFile, versionCatalog)) {
    val text = readText(file) ?: continue
    val index = text.indexOf(GoogleMavenArtifactId.LEAKCANARY.mavenGroupId)
    if (index >= 0) {
      return StudioLeakCanaryDeclaration(file, StringUtil.offsetToLineNumber(text, index))
    }
  }
  return StudioLeakCanaryDeclaration(buildFile, 0)
}

/** Returns the text of [file], or null if it can't be read. */
private fun readText(file: VirtualFile): String? {
  FileDocumentManager.getInstance().getCachedDocument(file)?.let {
    return it.text
  }
  return try {
    VfsUtilCore.loadText(file)
  } catch (e: IOException) {
    logger.warn("Can't read ${file.path} to find studio-leakcanary.", e)
    null
  }
}

/** Returns the Gradle build file in the folder of [module], or null if there is none. */
fun findGradleBuildFile(module: Module): VirtualFile? {
  // Gradle projects can have one module per source set. The holder module is the one whose folder has the build file.
  val holderModule = module.getModuleSystem().getHolderModule()
  return ModuleRootManager.getInstance(holderModule).contentRoots.firstNotNullOfOrNull { root ->
    BUILD_FILE_NAMES.firstNotNullOfOrNull { root.findChild(it) }
  }
}

/** Returns the project's Gradle version catalog, or null if there is none. */
fun findVersionCatalog(project: Project): VirtualFile? = project.guessProjectDir()?.findFileByRelativePath(VERSION_CATALOG_PATH)
