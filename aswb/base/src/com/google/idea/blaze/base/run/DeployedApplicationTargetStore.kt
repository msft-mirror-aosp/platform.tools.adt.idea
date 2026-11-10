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
package com.google.idea.blaze.base.run

import com.google.idea.blaze.common.Label
import com.intellij.openapi.project.Project

/** Target label and co-deployed companion targets recorded for an application ID. */
data class DeployedApplicationTargets(val target: Label, val deployedWith: Set<Label>) {
  /** All deployed targets with [target] ordered ahead of [deployedWith]. */
  val allTargets: Set<Label>
    get() = buildSet {
      add(target)
      addAll(deployedWith)
    }
}

/** Service for mapping deployed application IDs to their Bazel target labels. */
interface DeployedApplicationTargetStore {
  /**
   * Associates the given [applicationId] with the deployed [target] and records any companion targets [deployedWith] it into the same
   * process (for example, the test APK and app under test in a separate-target `android_instrumentation_test`, or an empty set when
   * deployed alone).
   */
  fun trackTargetForApplication(applicationId: String, target: Label, deployedWith: Set<Label>)

  /** Returns the target label associated with the given [applicationId], or null if none is tracked. */
  fun getTargetForApplication(applicationId: String): Label?

  /**
   * Returns the primary target label and any co-deployed companion targets for [applicationId], or null if [applicationId] is not tracked.
   */
  fun getTargetsForApplication(applicationId: String): DeployedApplicationTargets? =
    getTargetForApplication(applicationId)?.let { primary -> DeployedApplicationTargets(primary, emptySet()) }

  /** Returns all tracked application IDs for the project. */
  fun getAllApplicationIds(): Set<String>

  companion object {
    @JvmStatic
    fun getInstance(project: Project): DeployedApplicationTargetStore = project.getService(DeployedApplicationTargetStore::class.java)
  }
}
