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
package com.android.tools.idea.profilers

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.impl.toImpl
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.profilers.ProfilerR8MappingToken.R8Mapping
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.projectsystem.gradle.GradleProjectSystem
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildMainArtifactStub
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.RunsInEdt
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class ProfilerR8MappingGradleTokenTest {

  @get:Rule val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  private val token = ProfilerR8MappingGradleToken()

  private val releaseMappingFile = File("/project/app/build/outputs/mapping/release/mapping.txt")

  @Test
  fun getR8MappingsReturnsMappingWhenSelectedVariantIsObfuscated() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModule(selectedVariant = "release", mappingFor = { if (it == "release") releaseMappingFile else null }),
    )

    val expected = R8Mapping(text = releaseMappingFile.toPath(), applicationId = "com.example.app")
    assertThat(getMappings()).containsExactly(expected)
    assertThat(ProfilerR8MappingToken.getR8Mappings(projectRule.project)).containsExactly(expected)
  }

  @Test
  fun getR8MappingsReturnsEmptyWhenSelectedVariantIsNotObfuscatedEvenIfOtherVariantIs() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModule(selectedVariant = "debug", mappingFor = { if (it == "release") releaseMappingFile else null }),
    )

    assertThat(getMappings()).isEmpty()
  }

  @Test
  fun getR8MappingsReturnsEmptyWhenMappingFileIsNull() {
    projectRule.setupProjectFrom(JavaModuleModelBuilder.rootModuleBuilder, appModule(mappingFor = { null }))

    assertThat(getMappings()).isEmpty()
  }

  // The token reads mainArtifact.applicationId directly (not AndroidModel.applicationId), so null is not coerced to
  // UNINITIALIZED_APPLICATION_ID here. applicationIdFor can't return null, hence copy(applicationId = null) on the artifact.
  @Test
  fun getR8MappingsReturnsEmptyWhenApplicationIdIsNull() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        gradlePath = ":app",
        selectedBuildVariant = "release",
        projectBuilder =
          AndroidProjectBuilder(
            mainArtifactStub = { variant ->
              buildMainArtifactStub(variant).copy(applicationId = null, mappingR8TextFile = releaseMappingFile.toImpl())
            }
          ),
      ),
    )

    assertThat(getMappings()).isEmpty()
  }

  @Test
  fun getR8MappingsReturnsEmptyWhenApplicationIdIsEmpty() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModule(applicationId = "", mappingFor = { releaseMappingFile }),
    )

    assertThat(getMappings()).isEmpty()
  }

  @Test
  fun getR8MappingsReturnsEmptyWhenApplicationIdIsUninitialized() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModule(applicationId = AndroidModel.UNINITIALIZED_APPLICATION_ID, mappingFor = { releaseMappingFile }),
    )

    assertThat(getMappings()).isEmpty()
  }

  @Test
  fun getR8MappingsIgnoresNonAppModules() {
    val libMappingFile = File("/project/lib/build/outputs/mapping/release/mapping.txt")
    val featureMappingFile = File("/project/feature/build/outputs/mapping/release/mapping.txt")
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        gradlePath = ":app",
        selectedBuildVariant = "debug",
        projectBuilder = AndroidProjectBuilder(dynamicFeatures = { listOf(":feature") }),
      ),
      // Valid application IDs and mapping files so that only the projectType check filters these modules out.
      appModule(
        gradlePath = ":lib",
        applicationId = "com.example.lib",
        projectType = IdeAndroidProjectType.PROJECT_TYPE_LIBRARY,
        mappingFor = { libMappingFile },
      ),
      appModule(
        gradlePath = ":feature",
        applicationId = "com.example.feature",
        projectType = IdeAndroidProjectType.PROJECT_TYPE_DYNAMIC_FEATURE,
        mappingFor = { featureMappingFile },
      ),
    )

    assertThat(getMappings()).isEmpty()
  }

  // AGP < 4.1.0 (e.g. 3.6.0) has isBuildOutputFileSupported == false.
  @Test
  fun getR8MappingsIgnoresModulesWhenBuildOutputFileNotSupported() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      AndroidModuleModelBuilder(
        agpVersion = "3.6.0",
        gradlePath = ":app",
        selectedBuildVariant = "release",
        projectBuilder =
          AndroidProjectBuilder(
            applicationIdFor = { "com.example.app" },
            mainArtifactStub = { variant ->
              buildMainArtifactStub(variant).copy(mappingR8TextFile = releaseMappingFile.toImpl())
            },
          ),
      ),
    )

    assertThat(getMappings()).isEmpty()
  }

  // distinct() deduplicates multiple source-set modules sharing a model as well as separate modules with identical mappings.
  @Test
  fun getR8MappingsReturnsMappingsForMultipleAppModulesAndDeduplicates() {
    val app1Mapping = File("/project/app1/build/outputs/mapping/release/mapping.txt")
    val app2Mapping = File("/project/app2/build/outputs/mapping/release/mapping.txt")
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModule(gradlePath = ":app1", applicationId = "com.example.app1", mappingFor = { app1Mapping }),
      appModule(gradlePath = ":app1Duplicate", applicationId = "com.example.app1", mappingFor = { app1Mapping }),
      appModule(gradlePath = ":app2", applicationId = "com.example.app2", mappingFor = { app2Mapping }),
    )

    assertThat(getMappings())
      .containsExactly(
        R8Mapping(text = app1Mapping.toPath(), applicationId = "com.example.app1"),
        R8Mapping(text = app2Mapping.toPath(), applicationId = "com.example.app2"),
      )
  }

  private fun appModule(
    gradlePath: String = ":app",
    selectedVariant: String = "release",
    applicationId: String = "com.example.app",
    projectType: IdeAndroidProjectType = IdeAndroidProjectType.PROJECT_TYPE_APP,
    mappingFor: (variant: String) -> File? = { null },
  ) =
    AndroidModuleModelBuilder(
      gradlePath = gradlePath,
      selectedBuildVariant = selectedVariant,
      projectBuilder =
        AndroidProjectBuilder(
          projectType = { projectType },
          applicationIdFor = { applicationId },
          mainArtifactStub = { variant ->
            buildMainArtifactStub(variant).copy(mappingR8TextFile = mappingFor(variant)?.toImpl())
          },
        ),
    )

  private fun getMappings() = token.getR8Mappings(projectRule.project.getProjectSystem() as GradleProjectSystem)
}
