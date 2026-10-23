/*
 * Copyright (C) 2026 The Android Open Source Project
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

import java.io.File

/**
 * Artifact built from a host jar source of a test suite. Its [name] is the test suite source set name.
 *
 * [variantSourceProvider] and [multiFlavorSourceProvider] are always `null` because test suite source directories are defined at the
 * project level in [IdeAndroidProject.testSuites].
 */
interface IdeTestSuiteHostArtifactCore : IdeBaseArtifactCore {
  override val name: IdeTestSuiteArtifactName

  /**
   * The unique identifier AGP gives to the test suite source set in the variant: the key of the artifact in the `Variant` model, which is
   * also the name of its dependencies in `VariantDependencies` and of its Kotlin compilation.
   */
  val sourceSetIdentifier: String
}

/** Resolved [IdeTestSuiteHostArtifactCore] with library dependencies. */
interface IdeTestSuiteHostArtifact : IdeTestSuiteHostArtifactCore, IdeBaseArtifact

/**
 * Artifact built from a test APK source of a test suite. Its [name] is the test suite source set name.
 *
 * [variantSourceProvider] and [multiFlavorSourceProvider] are always `null` because test suite source directories are defined at the
 * project level in [IdeAndroidProject.testSuites].
 *
 * Unlike [IdeAndroidArtifactCore], this extends [IdeBaseArtifactCore] without `generatedResourceFolders` or `generatedAssetFolders`: AGP's
 * `TestSuiteSourceSet.TestApk` only supports manifest, Java/Kotlin, and Java resources (not Android `res` or `assets`), so AGP's
 * `TestSuiteDeviceArtifact` extends `AbstractArtifact` rather than `AndroidArtifact`.
 */
interface IdeTestSuiteDeviceArtifactCore : IdeBaseArtifactCore {
  override val name: IdeTestSuiteArtifactName

  /**
   * The unique identifier AGP gives to the test suite source set in the variant: the key of the artifact in the `Variant` model, which is
   * also the name of its dependencies in `VariantDependencies` and of its Kotlin compilation.
   */
  val sourceSetIdentifier: String

  /** The application ID of the test APK, or null if it cannot be computed. */
  val applicationId: String?

  /** The minimum SDK version of the test APK. */
  val minSdkVersion: IdeApiVersion

  /** Whether the test APK is signed. */
  val isSigned: Boolean

  /** The name of the signing config used to sign the test APK, or null if none. */
  val signingConfigName: String?

  /** The file containing the location of the output(s) of the assemble task, if applicable. */
  val assembleTaskOutputListingFile: File?
}

/** Resolved [IdeTestSuiteDeviceArtifactCore] with library dependencies. */
interface IdeTestSuiteDeviceArtifact : IdeTestSuiteDeviceArtifactCore, IdeBaseArtifact
