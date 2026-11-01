/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.tools.idea.gradle.variant.conflict

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdePreResolvedModuleLibraryImpl
import com.android.tools.idea.gradle.model.IdeTestSuiteArtifactName
import com.android.tools.idea.gradle.model.impl.IdeDependencyCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeJUnitEngineInfoImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteComponentCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteHostArtifactCoreImpl
import com.android.tools.idea.gradle.model.impl.IdeTestSuiteImpl
import com.android.tools.idea.gradle.project.sync.LibraryIdentity
import com.android.tools.idea.testartifacts.testsuite.TestSuiteTestUtils.createHostJarTestSuiteSource
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.JavaModuleModelBuilder.Companion.rootModuleBuilder
import com.android.tools.idea.testing.buildDependenciesStub
import com.android.tools.idea.testing.findAppModule
import com.android.tools.idea.testing.findModule
import com.android.tools.idea.testing.flags.overrideForTest
import com.android.tools.idea.testing.setupTestProjectFromAndroidModel
import com.google.common.collect.Iterables
import com.google.common.truth.Truth.assertThat
import java.io.File

/** Tests for [ConflictSet]. */
class ConflictSetTest : ConflictsTestCase() {

  fun testFindSelectionConflictsWithoutConflict() {
    setupTestProjectFromAndroidModel(project, File(myFixture.tempDirPath), rootModuleBuilder, appModuleBuilder(), libModuleBuilder())

    val conflicts = ConflictSet.findConflicts(project).selectionConflicts
    assertThat(conflicts).isEmpty()
  }

  fun testFindSelectionConflictsWithoutEmptyVariantDependency() {
    setupTestProjectFromAndroidModel(
      project,
      File(myFixture.tempDirPath),
      rootModuleBuilder,
      appModuleBuilder(dependOnVariant = ""),
      libModuleBuilder(),
    )

    val conflicts = ConflictSet.findConflicts(project).selectionConflicts
    assertThat(conflicts).isEmpty()
  }

  fun testFindSelectionConflictsWithoutNullVariantDependency() {
    setupTestProjectFromAndroidModel(
      project,
      File(myFixture.tempDirPath),
      rootModuleBuilder,
      appModuleBuilder(dependOnVariant = null),
      libModuleBuilder(),
    )

    val conflicts = ConflictSet.findConflicts(project).selectionConflicts
    assertThat(conflicts).isEmpty()
  }

  fun testFindSelectionConflictsWithConflict() {
    setupTestProjectFromAndroidModel(
      project,
      File(myFixture.tempDirPath),
      rootModuleBuilder,
      appModuleBuilder(dependOnVariant = "release"),
      libModuleBuilder(),
    )

    val conflicts = ConflictSet.findConflicts(project).selectionConflicts
    assertThat(conflicts).hasSize(1)

    val conflict = conflicts[0]

    assertThat(conflict.source).isSameAs(project.findModule("lib"))
    assertThat(conflict.selectedVariant).isEqualTo("debug")
    val affectedModules = conflict.affectedModules
    assertThat(affectedModules).hasSize(1)
    val affectedModule = Iterables.getOnlyElement(affectedModules)
    assertThat(affectedModule.target).isSameAs(project.findAppModule())
    assertThat(affectedModule.expectedVariant).isEqualTo("release")
  }

  fun testFindSelectionConflictsFromTestSuiteArtifact() {
    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(true, testRootDisposable)
    setupTestProjectFromAndroidModel(project, File(myFixture.tempDirPath), rootModuleBuilder, appWithTestSuiteBuilder(), libModuleBuilder())

    val conflicts = ConflictSet.findConflicts(project).selectionConflicts
    assertThat(conflicts).hasSize(1)

    val conflict = conflicts[0]
    assertThat(conflict.source).isSameAs(project.findModule("lib"))
    assertThat(conflict.selectedVariant).isEqualTo("debug")
    val affectedModule = Iterables.getOnlyElement(conflict.affectedModules)
    assertThat(affectedModule.target).isSameAs(project.findAppModule())
    assertThat(affectedModule.expectedVariant).isEqualTo("release")
  }

  fun testTestSuiteArtifactsIgnoredWhenTestSuitesAreDisabled() {
    StudioFlags.AGP_TEST_SUITES_ENABLED.overrideForTest(false, testRootDisposable)
    setupTestProjectFromAndroidModel(project, File(myFixture.tempDirPath), rootModuleBuilder, appWithTestSuiteBuilder(), libModuleBuilder())

    assertThat(ConflictSet.findConflicts(project).selectionConflicts).isEmpty()
  }

  /** An app whose single-source test suite `suite1` depends on the `release` variant of `:lib`, while the app depends on `debug`. */
  private fun appWithTestSuiteBuilder() =
    AndroidModuleModelBuilder(
      ":app",
      "debug",
      AndroidProjectBuilder(
        testSuites = {
          listOf(
            IdeTestSuiteImpl(
              name = "suite1",
              sources = listOf(createHostJarTestSuiteSource(name = "suite1", testSuitePath = moduleBasePath.resolve("src/suite1"))),
              junitEngineInfo = IdeJUnitEngineInfoImpl(emptySet()),
              targetedVariants = listOf("debug"),
            )
          )
        },
        testSuiteComponentsStub = { variant ->
          val libRef =
            IdePreResolvedModuleLibraryImpl(
                projectPath = ":lib",
                buildId = buildId,
                variant = "release",
                lintJar = null,
                sourceSet = IdeModuleWellKnownSourceSet.MAIN,
              )
              .let { internedModels.internModuleLibrary(LibraryIdentity.fromIdeModel(it)) { it } }
          val deps = buildDependenciesStub(listOf(IdeDependencyCoreImpl(libRef, dependencies = emptyList())))
          listOf(
            IdeTestSuiteComponentCoreImpl(
              suiteName = "suite1",
              targets = emptyList(),
              hostTestSuiteArtifacts =
                listOf(
                  IdeTestSuiteHostArtifactCoreImpl(
                    name = IdeTestSuiteArtifactName("suite1"),
                    sourceSetIdentifier = "${variant}Suite1",
                    compileTaskName = "compile${variant.replaceFirstChar { it.uppercase() }}Suite1Sources",
                    assembleTaskName = "assemble${variant.replaceFirstChar { it.uppercase() }}Suite1",
                    classesFolder = emptyList(),
                    variantSourceProvider = null,
                    multiFlavorSourceProvider = null,
                    ideSetupTaskNames = emptyList(),
                    generatedSourceFolders = emptyList(),
                    isTestArtifact = true,
                    compileClasspathCore = deps,
                    runtimeClasspathCore = deps,
                    unresolvedDependencies = emptyList(),
                    generatedClassPaths = emptyMap(),
                    bytecodeTransforms = null,
                  )
                ),
              deviceTestSuiteArtifacts = emptyList(),
            )
          )
        },
      ),
    )
}
