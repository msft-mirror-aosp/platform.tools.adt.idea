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
package com.android.tools.idea.gradle.dependencies

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

/** Covers [DeclarativePluginsInserter] behavior when the project has no declarative settings file. */
class DeclarativePluginsInserterUnitTest {

  private val projectModel = mock(ProjectBuildModel::class.java) // declarativeSettingsModel is null
  private val buildModel = mock(GradleBuildModel::class.java)
  private val inserter = DeclarativePluginsInserter(projectModel)

  private var matcherFactoryCalls = 0
  private val matcherFactory: (String, String) -> PluginMatcher = { id, _ ->
    matcherFactoryCalls++
    IdPluginMatcher(id)
  }

  @Test
  fun addPluginOrClasspathWithoutSettingsFileChangesNothing() {
    val changed =
      inserter.addPluginOrClasspath(
        "com.android.application",
        "fakeClasspathModule",
        "1.0",
        listOf(buildModel),
        matcherFactory,
        GroupNameDependencyMatcher("", ""),
        PluginInsertionConfig.defaultInsertionConfig(),
      )

    assertNoChanges(changed)
  }

  @Test
  fun findPlaceAndAddPluginWithoutSettingsFileChangesNothing() {
    val changed = inserter.findPlaceAndAddPlugin("com.android.application", "1.0", listOf(buildModel), matcherFactory)

    assertNoChanges(changed)
  }

  private fun assertNoChanges(changed: Set<*>) {
    assertThat(changed).isEmpty()
    assertThat(matcherFactoryCalls).isEqualTo(0)
    verifyNoInteractions(buildModel)
    verify(projectModel).declarativeSettingsModel
    verifyNoMoreInteractions(projectModel)
  }
}
