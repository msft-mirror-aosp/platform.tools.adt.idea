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
package com.google.idea.blaze.base.qsync.testing

import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.project.BuildGraphData
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Provides the build target label supplying core test framework annotations (such as JUnit `@Test` and `@RunWith`) for a Query Sync project
 * so that test classes and methods can be recognized by the IDE before per-file code analysis is enabled.
 */
interface BazelTestToolingProjectLabelProvider {
  fun isApplicable(project: Project): Boolean

  fun getTestToolingLabel(graph: BuildGraphData): Label?

  fun hasTests(graph: BuildGraphData): Boolean

  companion object {
    val EP_NAME: ExtensionPointName<BazelTestToolingProjectLabelProvider> =
      ExtensionPointName.create("com.google.idea.blaze.base.qsync.testing.bazelTestToolingProjectLabelProvider")

    fun getTestToolingLabel(project: Project, graph: BuildGraphData): Label? {
      return EP_NAME.extensionList.filter { it.isApplicable(project) }.firstNotNullOfOrNull { it.getTestToolingLabel(graph) }
    }

    fun hasTests(project: Project, graph: BuildGraphData): Boolean {
      return EP_NAME.extensionList.filter { it.isApplicable(project) }.any { it.hasTests(graph) }
    }
  }
}
