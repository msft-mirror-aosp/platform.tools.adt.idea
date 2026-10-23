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
package com.android.tools.idea.gradle.model.impl

import com.android.tools.idea.gradle.model.IdeDependencies
import com.android.tools.idea.gradle.model.IdeSourceProvider
import com.android.tools.idea.gradle.model.IdeTestSuiteArtifactName
import com.android.tools.idea.gradle.model.IdeTestSuiteDeviceArtifact
import com.android.tools.idea.gradle.model.IdeTestSuiteDeviceArtifactCore
import com.android.tools.idea.gradle.model.IdeTestSuiteHostArtifact
import com.android.tools.idea.gradle.model.IdeTestSuiteHostArtifactCore
import java.io.File

data class IdeTestSuiteHostArtifactCoreImpl(
  override val name: IdeTestSuiteArtifactName,
  override val sourceSetIdentifier: String,
  override val compileTaskName: String?,
  override val assembleTaskName: String?,
  override val classesFolder: List<FileImpl>,
  override val variantSourceProvider: IdeSourceProvider?,
  override val multiFlavorSourceProvider: IdeSourceProvider?,
  override val ideSetupTaskNames: List<String>,
  override val generatedSourceFolders: List<FileImpl>,
  override val isTestArtifact: Boolean,
  override val compileClasspathCore: IdeDependenciesCoreImpl,
  override val runtimeClasspathCore: IdeDependenciesCoreImpl,
  override val unresolvedDependencies: List<IdeUnresolvedDependencyImpl>,
  override val generatedClassPaths: Map<String, FileImpl>,
  override val bytecodeTransforms: List<IdeBytecodeTransformationImpl>?,
) : IdeTestSuiteHostArtifactCore

data class IdeTestSuiteDeviceArtifactCoreImpl(
  override val name: IdeTestSuiteArtifactName,
  override val sourceSetIdentifier: String,
  override val compileTaskName: String?,
  override val assembleTaskName: String?,
  override val classesFolder: List<FileImpl>,
  override val variantSourceProvider: IdeSourceProvider?,
  override val multiFlavorSourceProvider: IdeSourceProvider?,
  override val ideSetupTaskNames: List<String>,
  override val generatedSourceFolders: List<FileImpl>,
  override val isTestArtifact: Boolean,
  override val compileClasspathCore: IdeDependenciesCoreImpl,
  override val runtimeClasspathCore: IdeDependenciesCoreImpl,
  override val unresolvedDependencies: List<IdeUnresolvedDependencyImpl>,
  override val generatedClassPaths: Map<String, FileImpl>,
  override val bytecodeTransforms: List<IdeBytecodeTransformationImpl>?,
  override val applicationId: String?,
  override val minSdkVersion: IdeApiVersionImpl,
  override val isSigned: Boolean,
  override val signingConfigName: String?,
  override val assembleTaskOutputListingFile: FileImpl?,
) : IdeTestSuiteDeviceArtifactCore

class IdeTestSuiteHostArtifactImpl(private val core: IdeTestSuiteHostArtifactCoreImpl, resolver: IdeLibraryModelResolverImpl) :
  IdeTestSuiteHostArtifact, IdeTestSuiteHostArtifactCore {
  override val name: IdeTestSuiteArtifactName = core.name
  override val sourceSetIdentifier: String = core.sourceSetIdentifier
  override val compileTaskName: String? = core.compileTaskName
  override val assembleTaskName: String? = core.assembleTaskName
  override val classesFolder: List<FileImpl> = core.classesFolder
  override val variantSourceProvider: IdeSourceProvider? = core.variantSourceProvider
  override val multiFlavorSourceProvider: IdeSourceProvider? = core.multiFlavorSourceProvider
  override val ideSetupTaskNames: List<String> = core.ideSetupTaskNames
  override val generatedSourceFolders: List<FileImpl> = core.generatedSourceFolders
  override val isTestArtifact: Boolean = core.isTestArtifact
  override val compileClasspathCore: IdeDependenciesCoreImpl = core.compileClasspathCore
  override val runtimeClasspathCore: IdeDependenciesCoreImpl = core.runtimeClasspathCore
  override val unresolvedDependencies: List<IdeUnresolvedDependencyImpl> = core.unresolvedDependencies
  override val generatedClassPaths: Map<String, File> = core.generatedClassPaths
  override val bytecodeTransforms: List<IdeBytecodeTransformationImpl>? = core.bytecodeTransforms
  override val compileClasspath: IdeDependencies = IdeDependencies(core.compileClasspathCore, resolver)
  override val runtimeClasspath: IdeDependencies = IdeDependencies(core.runtimeClasspathCore, resolver)

  /**
   * Returns whether the libraries referenced by [core] resolve to the same libraries in [other], assuming the cores are equal.
   *
   * [core] only holds indices into the build-wide library tables, so two artifacts with equal cores can still have different dependencies
   * (e.g. when Phased Sync could not resolve a dependency which a later full sync resolves).
   */
  internal fun hasSameResolvedDependencies(other: IdeTestSuiteHostArtifactImpl): Boolean =
    compileClasspath.hasSameResolvedLibraries(other.compileClasspath) && runtimeClasspath.hasSameResolvedLibraries(other.runtimeClasspath)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as IdeTestSuiteHostArtifactImpl
    return core == other.core && hasSameResolvedDependencies(other)
  }

  // Intentionally only uses [core]: equal artifacts have equal cores, and this avoids resolving and hashing libraries.
  override fun hashCode(): Int = core.hashCode()
}

class IdeTestSuiteDeviceArtifactImpl(private val core: IdeTestSuiteDeviceArtifactCoreImpl, resolver: IdeLibraryModelResolverImpl) :
  IdeTestSuiteDeviceArtifact, IdeTestSuiteDeviceArtifactCore {
  override val name: IdeTestSuiteArtifactName = core.name
  override val sourceSetIdentifier: String = core.sourceSetIdentifier
  override val compileTaskName: String? = core.compileTaskName
  override val assembleTaskName: String? = core.assembleTaskName
  override val classesFolder: List<FileImpl> = core.classesFolder
  override val variantSourceProvider: IdeSourceProvider? = core.variantSourceProvider
  override val multiFlavorSourceProvider: IdeSourceProvider? = core.multiFlavorSourceProvider
  override val ideSetupTaskNames: List<String> = core.ideSetupTaskNames
  override val generatedSourceFolders: List<FileImpl> = core.generatedSourceFolders
  override val isTestArtifact: Boolean = core.isTestArtifact
  override val compileClasspathCore: IdeDependenciesCoreImpl = core.compileClasspathCore
  override val runtimeClasspathCore: IdeDependenciesCoreImpl = core.runtimeClasspathCore
  override val unresolvedDependencies: List<IdeUnresolvedDependencyImpl> = core.unresolvedDependencies
  override val generatedClassPaths: Map<String, File> = core.generatedClassPaths
  override val bytecodeTransforms: List<IdeBytecodeTransformationImpl>? = core.bytecodeTransforms
  override val applicationId: String? = core.applicationId
  override val minSdkVersion: IdeApiVersionImpl = core.minSdkVersion
  override val isSigned: Boolean = core.isSigned
  override val signingConfigName: String? = core.signingConfigName
  override val assembleTaskOutputListingFile: FileImpl? = core.assembleTaskOutputListingFile
  override val compileClasspath: IdeDependencies = IdeDependencies(core.compileClasspathCore, resolver)
  override val runtimeClasspath: IdeDependencies = IdeDependencies(core.runtimeClasspathCore, resolver)

  /**
   * Returns whether the libraries referenced by [core] resolve to the same libraries in [other], assuming the cores are equal.
   *
   * [core] only holds indices into the build-wide library tables, so two artifacts with equal cores can still have different dependencies
   * (e.g. when Phased Sync could not resolve a dependency which a later full sync resolves).
   */
  internal fun hasSameResolvedDependencies(other: IdeTestSuiteDeviceArtifactImpl): Boolean =
    compileClasspath.hasSameResolvedLibraries(other.compileClasspath) && runtimeClasspath.hasSameResolvedLibraries(other.runtimeClasspath)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as IdeTestSuiteDeviceArtifactImpl
    return core == other.core && hasSameResolvedDependencies(other)
  }

  // Intentionally only uses [core]: equal artifacts have equal cores, and this avoids resolving and hashing libraries.
  override fun hashCode(): Int = core.hashCode()
}
