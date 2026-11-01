/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.idea

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.model.IdeArtifactName
import com.android.tools.idea.gradle.model.IdeBaseArtifactCore
import com.android.tools.idea.gradle.model.IdeVariantCore
import com.android.tools.idea.gradle.model.findTestSuiteArtifact
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.project.sync.idea.data.service.AndroidProjectKeys
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData

/** Sets the compiler output paths on the module [DataNode]. */
// TODO(b/213887150) : once this bug is fixed and we have code coverage exclusively with Jacoco, then this can be deleted.
@JvmOverloads
fun DataNode<ModuleData>.setupCompilerOutputPaths(variant: IdeVariantCore? = null, isDelegatedBuildUsed: Boolean) {
  val androidModel = ExternalSystemApiUtil.find(this, AndroidProjectKeys.ANDROID_MODEL)?.data ?: return
  val selectedVariant = variant ?: androidModel.selectedVariantCore
  val testSuiteSourceSetNames =
    if (StudioFlags.AGP_TEST_SUITES_ENABLED.get()) androidModel.getTestSuiteSourceSetNames(selectedVariant) else emptySet()

  data.useExternalCompilerOutput(isDelegatedBuildUsed)
  data.isInheritProjectCompileOutputPath = false
  // MPSS: Set compilation data for Gradle sourceSets too.
  for (sourceSet in ExternalSystemApiUtil.findAll(this, GradleSourceSetData.KEY)) {
    val sourceSetData = sourceSet.data
    val (artifact, isTestScope) = getArtifactAndScope(sourceSetData, selectedVariant, testSuiteSourceSetNames) ?: continue

    // TODO(b/232780259): Look for the compilation output folder. We can have both java and kotlin compilation outputs in
    // classesFolder(IDEA-235250).

    val sourceCompilerOutput = if (!isTestScope) artifact?.classesFolder?.firstOrNull()?.absolutePath else null
    val testCompilerOutput = if (isTestScope) artifact?.classesFolder?.firstOrNull()?.absolutePath else null

    // The compiler output paths are not inherited here as every moduleData can have its own path.
    // In order for CompilerModuleExtension to use the compiler paths of each module, we need to make sure that
    // isInheritProjectCompileOutputPath is set to false.
    sourceSetData.isInheritProjectCompileOutputPath = false
    sourceSetData.useExternalCompilerOutput(isDelegatedBuildUsed)
    sourceSetData.setCompileOutputPath(ExternalSystemSourceType.SOURCE, null)
    sourceSetData.setCompileOutputPath(ExternalSystemSourceType.TEST, null)
    sourceSetData.setExternalCompilerOutputPath(ExternalSystemSourceType.SOURCE, sourceCompilerOutput)
    sourceSetData.setExternalCompilerOutputPath(ExternalSystemSourceType.TEST, testCompilerOutput)
  }
}

/**
 * Returns the artifact of [selectedVariant] that [sourceSetData] was created for, which is null for an assets test suite source, and
 * whether its compiler output is a test output. Returns null for source sets that are not Android source sets, e.g. in a KMP project.
 */
private fun getArtifactAndScope(
  sourceSetData: GradleSourceSetData,
  selectedVariant: IdeVariantCore,
  testSuiteSourceSetNames: Set<String>,
): Pair<IdeBaseArtifactCore?, Boolean>? {
  // Test suite source sets are checked first, so that a test suite source set named like a well-known one (e.g. `screenshotTest`) gets
  // the output of its own artifact. Assets sources have no artifact and therefore no compiler output.
  if (sourceSetData.moduleName in testSuiteSourceSetNames) {
    return selectedVariant.findTestSuiteArtifact(sourceSetData.moduleName) to true
  }
  val knownSourceSet = IdeModuleWellKnownSourceSet.fromName(sourceSetData.moduleName) ?: return null
  val artifact =
    when (knownSourceSet) {
      IdeModuleWellKnownSourceSet.MAIN -> selectedVariant.mainArtifact
      IdeModuleWellKnownSourceSet.TEST_FIXTURES -> selectedVariant.testFixturesArtifact
      IdeModuleWellKnownSourceSet.UNIT_TEST -> selectedVariant.hostTestArtifacts.find { it.name == IdeArtifactName.UNIT_TEST }
      IdeModuleWellKnownSourceSet.ANDROID_TEST -> selectedVariant.deviceTestArtifacts.find { it.name == IdeArtifactName.ANDROID_TEST }
      IdeModuleWellKnownSourceSet.SCREENSHOT_TEST -> selectedVariant.hostTestArtifacts.find { it.name == IdeArtifactName.SCREENSHOT_TEST }
    }
  val isTestScope =
    when (knownSourceSet) {
      IdeModuleWellKnownSourceSet.MAIN -> false
      IdeModuleWellKnownSourceSet.TEST_FIXTURES -> true
      IdeModuleWellKnownSourceSet.UNIT_TEST -> true
      IdeModuleWellKnownSourceSet.ANDROID_TEST -> true
      IdeModuleWellKnownSourceSet.SCREENSHOT_TEST -> true
    }
  return artifact to isTestScope
}
