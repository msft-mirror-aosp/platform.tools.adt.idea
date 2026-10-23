/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.gradle.model.impl

import com.android.tools.idea.gradle.model.IdeTestSuiteComponent
import com.android.tools.idea.gradle.model.IdeTestSuiteComponentCore
import java.io.Serializable

data class IdeTestSuiteComponentCoreImpl(
  override val suiteName: String,
  override val targets: List<IdeTestSuiteTargetImpl>,
  override val hostTestSuiteArtifacts: List<IdeTestSuiteHostArtifactCoreImpl> = emptyList(),
  override val deviceTestSuiteArtifacts: List<IdeTestSuiteDeviceArtifactCoreImpl> = emptyList(),
) : IdeTestSuiteComponentCore, Serializable

data class IdeTestSuiteComponentImpl(
  override val suiteName: String,
  override val targets: List<IdeTestSuiteTargetImpl>,
  override val hostTestSuiteArtifacts: List<IdeTestSuiteHostArtifactImpl> = emptyList(),
  override val deviceTestSuiteArtifacts: List<IdeTestSuiteDeviceArtifactImpl> = emptyList(),
) : IdeTestSuiteComponent {
  constructor(
    core: IdeTestSuiteComponentCoreImpl,
    resolver: IdeLibraryModelResolverImpl,
  ) : this(
    suiteName = core.suiteName,
    targets = core.targets,
    hostTestSuiteArtifacts = core.hostTestSuiteArtifacts.map { IdeTestSuiteHostArtifactImpl(it, resolver) },
    deviceTestSuiteArtifacts = core.deviceTestSuiteArtifacts.map { IdeTestSuiteDeviceArtifactImpl(it, resolver) },
  )

  internal fun hasSameResolvedDependencies(other: IdeTestSuiteComponentImpl): Boolean =
    hostTestSuiteArtifacts.allPairs(other.hostTestSuiteArtifacts) { a, b -> a.hasSameResolvedDependencies(b) } &&
      deviceTestSuiteArtifacts.allPairs(other.deviceTestSuiteArtifacts) { a, b -> a.hasSameResolvedDependencies(b) }
}
