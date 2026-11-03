/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.gradle.project

import com.android.tools.idea.gradle.project.ProjectImportUtil.findGradleTarget
import com.android.tools.idea.gradle.project.importing.GradleProjectImporter
import com.android.tools.idea.gradle.util.GradleProjects
import com.android.tools.idea.projectsystem.ProjectSystemService.Companion.projectSystemOpenProjectTask
import com.android.tools.idea.projectsystem.gradle.GradleProjectSystemProvider
import com.android.tools.idea.util.toIoFile
import com.android.tools.idea.util.toPathString
import com.android.tools.idea.util.toVirtualFile
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.android.util.AndroidBundle

/**
 * A project open processor to open Gradle projects in Android Studio.
 *
 * It supports opening projects with or without .idea directory.
 */
class AndroidGradleProjectOpenProcessor : ProjectOpenProcessor() {
  override val name: String
    get() = "Android Gradle"

  override fun canOpenProject(file: VirtualFile): Boolean {
    if (GradleProjects.canImportAsGradleProject(file)) return true

    // Try again but refreshing the file system since this might not be updated b/460389443
    file.refresh(false, false)
    return GradleProjects.canImportAsGradleProject(file)
  }

  override suspend fun openProjectAsync(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
    val importTarget = findGradleTarget(virtualFile) ?: return null
    val adjustedOpenTarget = if (importTarget.isDirectory) importTarget else importTarget.parent

    if (!adjustedOpenTarget.toIoFile().canWrite()) {
      withContext(Dispatchers.EDT) {
        Messages.showErrorDialog(
          AndroidBundle.message("android.project.open.permission.readonly.message"),
          AndroidBundle.message("android.project.open.permission.readonly.title"),
        )
      }
      return null
    }

    if (!canOpenAsExistingProject(adjustedOpenTarget)) {
      return GradleProjectImporter.getInstance().importAndOpenProjectCore(projectToClose, forceOpenInNewFrame, adjustedOpenTarget)
    }

    return ProjectManagerEx.getInstanceEx()
      .openProject(
        adjustedOpenTarget.toNioPath(),
        projectSystemOpenProjectTask(GradleProjectSystemProvider.ID, forceOpenInNewFrame, projectToClose),
      )
  }

  private fun canOpenAsExistingProject(file: VirtualFile): Boolean =
    file.toPathString().resolve(Project.DIRECTORY_STORE_FOLDER).toVirtualFile(true) != null
}
