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

import com.google.idea.blaze.base.settings.BazelImportSettingsManager
import com.google.idea.blaze.base.settings.BuildSystemName
import com.google.idea.blaze.common.Label
import com.google.idea.blaze.qsync.project.BuildGraphData
import com.intellij.openapi.project.Project

/**
 * Default Bazel implementation of [BazelTestToolingProjectLabelProvider] that discovers the external JUnit target from target dependencies
 * in the project's [BuildGraphData].
 */
class DefaultBazelTestToolingProjectLabelProvider : BazelTestToolingProjectLabelProvider {
  override fun isApplicable(project: Project): Boolean {
    return BazelImportSettingsManager.getInstance(project).buildSystem == BuildSystemName.Bazel
  }

  override fun getTestToolingLabel(graph: BuildGraphData): Label? {
    return junitDeps(graph).firstOrNull()
  }

  override fun hasTests(graph: BuildGraphData): Boolean {
    return junitDeps(graph).any()
  }

  private fun isJUnitLabel(label: Label): Boolean {
    return label.name == "junit_junit" || label.name == "junit.junit"
  }

  private fun junitDeps(graph: BuildGraphData): Sequence<Label> {
    return graph.allLoadedTargets().flatMap { it.deps().asSequence() + it.runtimeDeps().asSequence() }.filter(::isJUnitLabel)
  }
}
