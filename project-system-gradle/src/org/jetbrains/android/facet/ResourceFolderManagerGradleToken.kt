/*
 * Copyright (C) 2024 The Android Open Source Project
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
package org.jetbrains.android.facet

import com.android.tools.idea.projectsystem.CommonTestType
import com.android.tools.idea.projectsystem.GradleToken
import com.android.tools.idea.projectsystem.IdeaSourceProvider
import com.android.tools.idea.projectsystem.SourceProviders
import com.android.tools.idea.projectsystem.gradle.GradleProjectSystem
import com.android.tools.idea.projectsystem.gradle.isAndroidTestModule
import com.android.tools.idea.projectsystem.gradle.isScreenshotTestModule
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile

class ResourceFolderManagerGradleToken : GradleToken, ResourceFolderManagerToken<GradleProjectSystem> {
  private fun getSourceProviders(sourceProviders: SourceProviders, module: Module): List<IdeaSourceProvider>? =
    when {
      module.isAndroidTestModule() ->
        sourceProviders.run {
          val sources = currentDeviceTestSourceProviders[CommonTestType.ANDROID_TEST].orEmpty()
          val generated = listOfNotNull(generatedDeviceTestSources[CommonTestType.ANDROID_TEST])
          sources + generated
        }
      module.isScreenshotTestModule() ->
        sourceProviders.run {
          val sources = currentHostTestSourceProviders[CommonTestType.SCREENSHOT_TEST].orEmpty()
          val generated = listOfNotNull(generatedHostTestSources[CommonTestType.SCREENSHOT_TEST])
          sources + generated
        }
      else -> null
    }

  override fun computeFoldersFromSourceProviders(
    projectSystem: GradleProjectSystem,
    sourceProviders: SourceProviders,
    module: Module,
  ): List<VirtualFile>? = getSourceProviders(sourceProviders, module)?.flatMap { it.resDirectories }

  override fun computeFolderUrlsFromSourceProviders(
    projectSystem: GradleProjectSystem,
    sourceProviders: SourceProviders,
    module: Module,
  ): Iterable<String>? = getSourceProviders(sourceProviders, module)?.asSequence()?.flatMap { it.resDirectoryUrls }?.asIterable()
}
