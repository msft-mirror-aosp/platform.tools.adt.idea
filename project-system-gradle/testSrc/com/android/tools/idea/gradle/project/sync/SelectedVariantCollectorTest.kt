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
package com.android.tools.idea.gradle.project.sync

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.testing.AndroidModuleDependency
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildNdkModelStub
import com.android.tools.idea.testing.gradleModule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class SelectedVariantCollectorTest {
  @get:Rule val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @Test
  fun emptyProjectReturnsEmptySelection() {
    projectRule.setupProjectFrom(JavaModuleModelBuilder.rootModuleBuilder)
    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected).isEqualTo(SelectedVariants(emptyMap()))
  }

  @Test
  fun allModulesOnDefaultVariantsReturnsEmptySelection() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(
        selectedVariant = "debug",
        defaultVariantName = "debug",
        dependencyList = listOf(AndroidModuleDependency(":lib", "debug")),
      ),
      libModuleBuilder(selectedVariant = "debug", defaultVariantName = "debug"),
    )
    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected).isEqualTo(SelectedVariants(emptyMap()))
  }

  @Test
  fun nonDefaultVariantSelectedReturnsFullSelection() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(
        selectedVariant = "release",
        defaultVariantName = "debug",
        dependencyList = listOf(AndroidModuleDependency(":lib", "debug")),
      ),
      libModuleBuilder(selectedVariant = "debug", defaultVariantName = "debug"),
    )
    val appId = projectRule.project.gradleModule(":app")!!.getModuleIdForSyncRequest()!!
    val libId = projectRule.project.gradleModule(":lib")!!.getModuleIdForSyncRequest()!!

    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected.selectedVariants.keys).containsExactly(appId, libId)
    assertThat(collected.getSelectedVariant(appId)).isEqualTo("release")
    assertThat(collected.getSelectedVariant(libId)).isEqualTo("debug")
  }

  @Test
  fun dependencyVariantPropagatedFromConsumerReturnsFullSelection() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(
        selectedVariant = "release",
        defaultVariantName = "release",
        dependencyList = listOf(AndroidModuleDependency(":lib", "release")),
      ),
      libModuleBuilder(selectedVariant = "release", defaultVariantName = "debug"),
    )
    val appId = projectRule.project.gradleModule(":app")!!.getModuleIdForSyncRequest()!!
    val libId = projectRule.project.gradleModule(":lib")!!.getModuleIdForSyncRequest()!!

    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected.selectedVariants.keys).containsExactly(appId, libId)
    assertThat(collected.getSelectedVariant(appId)).isEqualTo("release")
    assertThat(collected.getSelectedVariant(libId)).isEqualTo("release")
  }

  @Test
  fun selectedAbiReturnsFullSelection() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(
        selectedVariant = "debug",
        defaultVariantName = "debug",
        dependencyList = listOf(AndroidModuleDependency(":lib", "debug")),
      ),
      ndkLibModuleBuilder(selectedVariant = "debug", defaultVariantName = "debug"),
    )
    val appId = projectRule.project.gradleModule(":app")!!.getModuleIdForSyncRequest()!!
    val libId = projectRule.project.gradleModule(":lib")!!.getModuleIdForSyncRequest()!!

    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected.selectedVariants.keys).containsExactly(appId, libId)
    assertThat(collected.getSelectedVariant(libId)).isEqualTo("debug")
    assertThat(collected.getSelectedAbi(libId)).isEqualTo("x86_64")
  }

  @Test
  fun missingDefaultVariantNameReturnsFullSelection() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(selectedVariant = "debug", defaultVariantName = null),
    )
    val appModule = projectRule.project.gradleModule(":app")!!
    val appId = appModule.getModuleIdForSyncRequest()!!

    val collected = SelectedVariantCollector(projectRule.project).collectSelectedVariants()
    assertThat(collected.selectedVariants.keys).containsExactly(appId)
    assertThat(collected.getSelectedVariant(appId)).isEqualTo("debug")
  }
}

private fun appModuleBuilder(
  appPath: String = ":app",
  selectedVariant: String = "debug",
  dependencyList: List<AndroidModuleDependency> = emptyList(),
  defaultVariantName: String? = null,
) =
  AndroidModuleModelBuilder(
    appPath,
    selectedVariant,
    AndroidProjectBuilder(androidModuleDependencyList = { dependencyList }, defaultVariantName = { defaultVariantName }),
  )

private fun libModuleBuilder(libPath: String = ":lib", selectedVariant: String = "debug", defaultVariantName: String? = null) =
  AndroidModuleModelBuilder(
    libPath,
    selectedVariant,
    AndroidProjectBuilder(projectType = { IdeAndroidProjectType.PROJECT_TYPE_LIBRARY }, defaultVariantName = { defaultVariantName }),
  )

private fun ndkLibModuleBuilder(selectedVariant: String = "debug", defaultVariantName: String? = null) =
  AndroidModuleModelBuilder(
    ":lib",
    selectedVariant,
    AndroidProjectBuilder(
      projectType = { IdeAndroidProjectType.PROJECT_TYPE_LIBRARY },
      defaultVariantName = { defaultVariantName },
      ndkModel = { buildNdkModelStub() },
    ),
  )
