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

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.common.Label
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests [BlazeSubincludeFileResolver] and [BazelSubincludeFileResolver] on the layout that Bazel creates, as seen with Bazel 8.2 and 9.2,
 * with a custom output base whose parent also holds the user's local repositories:
 * ```
 * workspace/                          the main repository
 *   defs/defs.bzl
 *   localmod/defs.bzl                 a module with a local_path_override
 * local/
 *   localrepo/defs.bzl                a local_repository
 *   newlocal/defs.bzl                 a new_local_repository
 *   output_base/external/
 *     localmod+                       -> workspace/localmod
 *     +local_repository+localrepo     -> local/localrepo
 *     +new_local_repository+newlocal/
 *       defs.bzl                      -> local/newlocal/defs.bzl
 *       BUILD.bazel                   created by Bazel
 *     fetched+/defs.bzl               a fetched repository, as Bazel 8.2 makes them
 *     cached+                         -> repository_cache/contents/0123/4567, a fetched repository, as Bazel 9.2 makes them
 *     bazel_tools                     -> install_base/embedded_tools
 * install_base/embedded_tools/tools/defs.bzl
 * repository_cache/contents/0123/4567/defs.bzl
 * ```
 */
@RunWith(JUnit4::class)
class SubincludeFileResolverTest {
  @get:Rule val tempDir = TemporaryFolder()

  private lateinit var layout: Path
  private lateinit var workspaceRoot: Path

  @Before
  fun createLayout() {
    layout = tempDir.newFolder("layout").toPath()
    workspaceRoot = layout.resolve("workspace")
    createFile("workspace/defs/defs.bzl")
    createFile("workspace/localmod/defs.bzl")
    createFile("local/localrepo/defs.bzl")
    createFile("local/newlocal/defs.bzl")
    createFile("local/output_base/external/+new_local_repository+newlocal/BUILD.bazel")
    createFile("local/output_base/external/fetched+/defs.bzl")
    createFile("install_base/embedded_tools/tools/defs.bzl")
    createFile("repository_cache/contents/0123/4567/defs.bzl")
    createSymlink("local/output_base/external/localmod+", "workspace/localmod")
    createSymlink("local/output_base/external/+local_repository+localrepo", "local/localrepo")
    createSymlink("local/output_base/external/+new_local_repository+newlocal/defs.bzl", "local/newlocal/defs.bzl")
    createSymlink("local/output_base/external/cached+", "repository_cache/contents/0123/4567")
    createSymlink("local/output_base/external/bazel_tools", "install_base/embedded_tools")
  }

  private fun createFile(path: String) {
    val file = layout.resolve(path)
    Files.createDirectories(file.parent)
    Files.writeString(file, "")
  }

  private fun createSymlink(link: String, target: String) {
    Files.createSymbolicLink(layout.resolve(link), layout.resolve(target))
  }

  private fun realPath(path: String): Path = layout.resolve(path).toRealPath()

  /** Returns a [BazelSubincludeFileResolver] for the layout, reached through [root]. */
  private fun bazelResolver(root: Path = layout) =
    BazelSubincludeFileResolver(
      root.resolve("workspace"),
      outputBase = root.resolve("local/output_base"),
      installBase = root.resolve("install_base"),
      repositoryCache = root.resolve("repository_cache"),
    )

  @Test
  fun blaze_mainRepository_isUnderWorkspaceRoot() {
    assertThat(BlazeSubincludeFileResolver(workspaceRoot).resolve(Label.of("//defs:defs.bzl")))
      .isEqualTo(workspaceRoot.resolve("defs/defs.bzl"))
  }

  @Test
  fun blaze_otherRepository_isNotTracked() {
    assertThat(BlazeSubincludeFileResolver(workspaceRoot).resolve(Label.of("@@localmod+//:defs.bzl"))).isNull()
  }

  @Test
  fun bazel_mainRepository_isUnderWorkspaceRoot() {
    val resolver = bazelResolver()

    assertThat(resolver.resolve(Label.of("@@//defs:defs.bzl"))).isEqualTo(workspaceRoot.resolve("defs/defs.bzl"))
  }

  @Test
  fun bazel_localRepositories_areTracked() {
    val resolver = bazelResolver()

    assertThat(resolver.resolve(Label.of("@@localmod+//:defs.bzl"))).isEqualTo(realPath("workspace/localmod/defs.bzl"))
    assertThat(resolver.resolve(Label.of("@@+local_repository+localrepo//:defs.bzl"))).isEqualTo(realPath("local/localrepo/defs.bzl"))
    assertThat(resolver.resolve(Label.of("@@+new_local_repository+newlocal//:defs.bzl"))).isEqualTo(realPath("local/newlocal/defs.bzl"))
  }

  @Test
  fun bazel_filesThatBazelCreatedOrFetched_areNotTracked() {
    val resolver = bazelResolver()

    assertThat(resolver.resolve(Label.of("@@+new_local_repository+newlocal//:BUILD.bazel"))).isNull()
    assertThat(resolver.resolve(Label.of("@@fetched+//:defs.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@cached+//:defs.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@bazel_tools//tools:defs.bzl"))).isNull()
  }

  @Test
  fun bazel_missingFileOrRepository_isNotTracked() {
    val resolver = bazelResolver()

    assertThat(resolver.resolve(Label.of("@@localmod+//:missing.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@unknown+//:defs.bzl"))).isNull()
  }

  @Test
  fun bazel_withoutBazelInfo_onlyMainRepositoryIsTracked() {
    val resolver = BazelSubincludeFileResolver(workspaceRoot, outputBase = null, installBase = null, repositoryCache = null)

    assertThat(resolver.resolve(Label.of("//defs:defs.bzl"))).isEqualTo(workspaceRoot.resolve("defs/defs.bzl"))
    assertThat(resolver.resolve(Label.of("@@localmod+//:defs.bzl"))).isNull()
  }

  @Test
  fun bazel_directoriesBehindSymlink_areComparedByRealPath() {
    // As on macOS, where /var and /tmp are symlinks to /private/var and /private/tmp.
    val link = Files.createSymbolicLink(tempDir.root.toPath().resolve("link"), layout)
    val resolver = bazelResolver(link)

    assertThat(resolver.resolve(Label.of("@@fetched+//:defs.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@cached+//:defs.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@bazel_tools//tools:defs.bzl"))).isNull()
    assertThat(resolver.resolve(Label.of("@@+local_repository+localrepo//:defs.bzl"))).isEqualTo(realPath("local/localrepo/defs.bzl"))
  }
}
