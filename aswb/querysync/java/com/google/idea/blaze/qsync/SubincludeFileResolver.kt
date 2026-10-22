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
package com.google.idea.blaze.qsync

import com.google.idea.blaze.common.Label
import java.nio.file.Path

/**
 * Finds the file of a subinclude, a file that a BUILD file loads, so that [CachingFileMtimeReader] can read its mtime. It also decides
 * which files are tracked: a change to a file that isn't tracked doesn't make the packages that load it be queried again.
 */
fun interface SubincludeFileResolver {
  /** Returns the file of [subinclude], or null if it isn't tracked. */
  fun resolve(subinclude: Label): Path?
}

/**
 * Resolves subincludes for Blaze, which has no external repositories. Only files in the main repository, under [workspaceRoot], are
 * tracked.
 */
class BlazeSubincludeFileResolver(private val workspaceRoot: Path) : SubincludeFileResolver {
  override fun resolve(subinclude: Label): Path? =
    if (subinclude.workspace == Label.ROOT_WORKSPACE) workspaceRoot.resolve(subinclude.toFilePath()) else null
}

/**
 * Resolves subincludes for Bazel. The tracked files are the files in the main repository, under [workspaceRoot], and the user's files in
 * other repositories, such as a `local_repository`, a `new_local_repository`, a module with a `local_path_override`, an
 * `--override_repository` or a vendored repository.
 *
 * Bazel sets up every external repository, local or fetched, at `<output base>/external/<canonical name>`, and query sync's subinclude
 * labels use canonical names. So that path doesn't tell whose a file is, but its real path does:
 * - For the user's repository, the entry is a symlink to the user's directory, or a directory of symlinks to its entries, so the real path
 *   is the user's file.
 * - For a repository that Bazel fetched or created, the real path stays in one of the directories that Bazel manages: [outputBase],
 *   [repositoryCache] (Bazel 9) or [installBase] (`@bazel_tools`). Such a file isn't tracked: it only changes when Bazel fetches it again,
 *   and the files of `@bazel_tools` have mtimes in the future, which would make them look changed in every sync.
 *
 * Real paths also work where some directories are symlinks, such as `/var` and `/tmp` on macOS.
 *
 * A file in another repository that doesn't exist isn't tracked, as its real path isn't known. The stamp of the packages that load it still
 * changes, since the file no longer adds to it.
 *
 * If `--repo_contents_cache` moves the contents of fetched repositories out of the repository cache, those files are tracked. That can make
 * their loaders be queried once more after Bazel fetches them.
 *
 * The three directories come from `bazel info`.
 *
 * @param outputBase Bazel's output base, or null if it isn't known, in which case only the main repository is tracked. Its `external`
 *   directory has an entry for each external repository, named after the repository's canonical name.
 * @param installBase Bazel's install base, which holds `@bazel_tools`, or null if it isn't known.
 * @param repositoryCache Bazel's repository cache, or null if it isn't known or is disabled. Bazel 9 keeps the contents of the repositories
 *   that it fetches in its `contents` directory, unless `--repo_contents_cache` moves them.
 */
class BazelSubincludeFileResolver(private val workspaceRoot: Path, outputBase: Path?, installBase: Path?, repositoryCache: Path?) :
  SubincludeFileResolver {
  /** The directory with an entry for each external repository. */
  private val externalRepositoriesDirectory: Path? = outputBase?.resolve("external")

  /** The real paths of the directories that Bazel manages. */
  private val bazelManagedDirectories: List<Path> =
    listOfNotNull(outputBase, installBase, repositoryCache).mapNotNull { it.realPathOrNull() }

  override fun resolve(subinclude: Label): Path? {
    if (subinclude.workspace == Label.ROOT_WORKSPACE) return workspaceRoot.resolve(subinclude.toFilePath())
    val pathInOutputBase = externalRepositoriesDirectory?.resolve(subinclude.workspace)?.resolve(subinclude.toFilePath()) ?: return null
    // Every external repository is in the output base: only the real path tells whether the file is the user's.
    val realPath = pathInOutputBase.realPathOrNull() ?: return null
    return realPath.takeUnless { isManagedByBazel(it) }
  }

  /** Returns whether [realPath] is in one of the directories that Bazel manages. */
  private fun isManagedByBazel(realPath: Path): Boolean = bazelManagedDirectories.any { realPath.startsWith(it) }
}

/** Returns the real path of this file, following symlinks, or null if it doesn't exist or can't be resolved. */
private fun Path.realPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()
