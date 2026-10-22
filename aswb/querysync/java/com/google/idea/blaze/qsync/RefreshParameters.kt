/*
 * Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.qsync

import com.google.common.collect.Maps
import com.google.idea.blaze.common.Context
import com.google.idea.blaze.common.PrintOutput
import com.google.idea.blaze.qsync.project.PostQuerySyncData
import com.google.idea.blaze.qsync.project.ProjectDefinition
import com.google.idea.blaze.qsync.project.ProjectStructureData
import java.nio.file.Path

/**
 * Input parameters to a project refresh, and logic to determine what sort of refresh is required.
 *
 * [mtimeReader] is stateful and not thread-safe: it caches the mtimes that it reads for the whole sync.
 */
class RefreshParameters(
  @JvmField val lastQuery: PostQuerySyncData,
  @JvmField val projectDefinition: ProjectDefinition,
  @JvmField val projectStructureData: ProjectStructureData,
  @JvmField val requestedPackages: Set<Path>,
  @JvmField val requireFullSync: (Context<*>) -> Boolean,
  /** Reads the mtimes of subincluded files for this refresh. */
  @JvmField val mtimeReader: CachingFileMtimeReader,
) {

  /**
   * Returns the packages to query and to drop. A requested package is queried if its BUILD file changed, or if the files that it loads have
   * changed since it was last queried, which this checks by reading their mtimes with [mtimeReader].
   */
  fun calculateAffectedPackages(context: Context<*>): AffectedPackages {
    val projectStamps =
      projectStructureData.roots.asSequence().flatMap { it.buildPackages.values }.associate { pkg -> pkg.path to pkg.stamp }

    val lastQueryPackages = lastQuery.querySummary().buildPackages.associateBy { it.packageLabel.getBuildPackagePath() }
    var checked = 0
    var changed = 0
    val startNanos = System.nanoTime()
    val affected =
      calculatePackageStampAffectedPackages(
        latestProjectDataPackageStamp = projectStamps,
        // A view of lastQueryPackages, not a copy.
        latestBuildGraphDataPackageStamp = Maps.transformValues(lastQueryPackages) { it.stamp },
        packagesToUpdate = requestedPackages,
        projectScope = { projectDefinition.isIncluded(it) },
        haveSubincludesChanged = { path ->
          // Only called for a package whose stamp is unchanged, so it's in lastQueryPackages.
          val pkg = lastQueryPackages.getValue(path)
          checked++
          (mtimeReader.subincludeStampOf(pkg) != pkg.subincludeStamp).also { if (it) changed++ }
        },
      )
    if (checked > 0) {
      context.output(
        PrintOutput.log(
          "Checked the loaded files of %d packages in %d ms (%d files read): %d changed",
          checked,
          (System.nanoTime() - startNanos) / 1_000_000,
          mtimeReader.filesRead,
          changed,
        )
      )
    }
    return affected
  }
}
