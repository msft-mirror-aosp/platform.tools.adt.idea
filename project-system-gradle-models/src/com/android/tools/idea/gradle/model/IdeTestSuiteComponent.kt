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
package com.android.tools.idea.gradle.model

/**
 * A test suite applied to a variant.
 *
 * Test suite definition is obtained at the project level using [IdeAndroidProject.testSuites]. Here you will find the list of targets
 * defined for this variant, each with an associated test task, and the artifacts built from the test suite sources for this variant.
 */
interface IdeTestSuiteComponentCore {
  /** Name of the test suite. */
  val suiteName: String

  /** List of defined targets for this test suite in the current variant context. */
  val targets: Collection<IdeTestSuiteTarget>

  /**
   * Artifacts built from the [IdeTestSuiteSource.SourceType.HOST_JAR] source sets of this test suite in the current variant context, one
   * per host jar source set.
   *
   * Empty when syncing with AGP versions below model producer version 25.0.
   */
  val hostTestSuiteArtifacts: Collection<IdeTestSuiteHostArtifactCore>

  /**
   * Artifacts built from the [IdeTestSuiteSource.SourceType.TEST_APK] source sets of this test suite in the current variant context, one
   * per test APK source set.
   *
   * Empty when syncing with AGP versions below model producer version 25.0.
   */
  val deviceTestSuiteArtifacts: Collection<IdeTestSuiteDeviceArtifactCore>
}

/** Resolved [IdeTestSuiteComponentCore] whose artifacts include resolved library dependencies. */
interface IdeTestSuiteComponent : IdeTestSuiteComponentCore {
  override val hostTestSuiteArtifacts: Collection<IdeTestSuiteHostArtifact>
  override val deviceTestSuiteArtifacts: Collection<IdeTestSuiteDeviceArtifact>
}

/** All the host and device artifacts of this test suite component. */
val IdeTestSuiteComponentCore.allArtifacts: List<IdeBaseArtifactCore>
  get() = hostTestSuiteArtifacts + deviceTestSuiteArtifacts

/** All the host and device artifacts, with their dependencies, of this test suite component. */
val IdeTestSuiteComponent.allArtifacts: List<IdeBaseArtifact>
  get() = hostTestSuiteArtifacts + deviceTestSuiteArtifacts

/** Returns the source set identifier AGP reports for the artifact of this test suite component built from [sourceSetName], if any. */
fun IdeTestSuiteComponentCore.sourceSetIdentifierOf(sourceSetName: String): String? =
  hostTestSuiteArtifacts.find { it.name.name == sourceSetName }?.sourceSetIdentifier
    ?: deviceTestSuiteArtifacts.find { it.name.name == sourceSetName }?.sourceSetIdentifier

/**
 * Returns the artifact of this variant built from the test suite source set [sourceSetName], or null if no artifact is built from it.
 * Assets sources have no artifact because AGP does not compile them.
 */
fun IdeVariantCore.findTestSuiteArtifact(sourceSetName: String): IdeBaseArtifactCore? =
  testSuiteComponents.firstNotNullOfOrNull { component ->
    component.allArtifacts.find { it.name.name == sourceSetName }
  }

/**
 * Returns the artifact, with its dependencies, of this variant built from the test suite source set [sourceSetName], or null if no artifact
 * is built from it. Assets sources have no artifact because AGP does not compile them.
 */
fun IdeVariant.findTestSuiteArtifact(sourceSetName: String): IdeBaseArtifact? = testSuiteComponents.firstNotNullOfOrNull { component ->
  component.allArtifacts.find { it.name.name == sourceSetName }
}
