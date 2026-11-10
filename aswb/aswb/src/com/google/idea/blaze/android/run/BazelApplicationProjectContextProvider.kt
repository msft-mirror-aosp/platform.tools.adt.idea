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
package com.google.idea.blaze.android.run

import com.android.tools.idea.projectsystem.ApplicationProjectContext
import com.android.tools.idea.projectsystem.ApplicationProjectContextProvider
import com.android.tools.idea.projectsystem.ApplicationProjectContextProvider.RunningApplicationIdentity
import com.google.idea.blaze.android.projectsystem.BazelProjectSystem
import com.google.idea.blaze.android.projectsystem.BazelToken
import com.google.idea.blaze.base.run.DeployedApplicationTargetStore
import com.google.idea.blaze.base.run.RuntimeArtifactCache
import com.google.idea.blaze.base.run.RuntimeArtifactKind
import com.intellij.openapi.project.Project
import java.io.File

/** An implementation of [ApplicationProjectContextProvider] for the Blaze project system. */
class BazelApplicationProjectContextProvider : ApplicationProjectContextProvider<BazelProjectSystem>, BazelToken {

  override fun computeApplicationProjectContext(
    projectSystem: BazelProjectSystem,
    identity: RunningApplicationIdentity,
  ): ApplicationProjectContext? {
    val applicationId = identity.heuristicApplicationId ?: return null
    val project = projectSystem.project
    return BazelApplicationProjectContext.forRunningApplication(project, applicationId) {
      getSymbolDirs(project, applicationId)
    }
  }

  private fun getSymbolDirs(project: Project, applicationId: String): List<File> {
    val targets =
      DeployedApplicationTargetStore.getInstance(project).getTargetsForApplication(applicationId)?.allTargets ?: return emptyList()
    val artifactCache = RuntimeArtifactCache.getInstance(project)
    return targets
      .flatMap { target -> artifactCache.getCachedArtifacts(target, RuntimeArtifactKind.SYMBOL_FILE) }
      .mapNotNull { it.parent }
      .distinct()
      .map { it.toFile() }
  }
}
