/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSet
import com.android.tools.idea.gradle.model.impl.IdeModuleSourceSetImpl
import com.android.tools.idea.gradle.model.impl.IdeModuleWellKnownSourceSet
import java.io.Serializable

sealed interface IdeArtifactName : Serializable {
  val name: String

  companion object {
    @JvmField val MAIN = WellKnownIdeArtifactName.MAIN
    @JvmField val ANDROID_TEST = WellKnownIdeArtifactName.ANDROID_TEST
    @JvmField val UNIT_TEST = WellKnownIdeArtifactName.UNIT_TEST
    @JvmField val TEST_FIXTURES = WellKnownIdeArtifactName.TEST_FIXTURES
    @JvmField val SCREENSHOT_TEST = WellKnownIdeArtifactName.SCREENSHOT_TEST

    /**
     * Returns the IDE module source set of this artifact.
     *
     * A test suite source set may have the same name as a well-known source set, e.g. a screenshot test suite named `screenshotTest`.
     * [IdeModuleSourceSetImpl] does not allow such names, so the well-known source set of the same name is returned instead, which is
     * consistent with how the IDE resolves source set names to [IdeModuleSourceSet]s (see [IdeModuleSourceSetImpl.wellKnownOrCreate]).
     */
    @JvmStatic
    fun IdeArtifactName.toSourceSet(): IdeModuleSourceSet {
      return when (this) {
        is WellKnownIdeArtifactName -> toWellKnownSourceSet()
        is IdeTestSuiteArtifactName -> IdeModuleWellKnownSourceSet.fromName(name) ?: IdeModuleSourceSetImpl(name, canBeConsumed = false)
      }
    }

    @JvmStatic
    fun IdeArtifactName.toPrintableName(): String {
      return when (this) {
        is WellKnownIdeArtifactName -> toPrintableName()
        is IdeTestSuiteArtifactName -> name
      }
    }
  }
}

enum class WellKnownIdeArtifactName : IdeArtifactName {
  MAIN,
  ANDROID_TEST,
  UNIT_TEST,
  TEST_FIXTURES,
  SCREENSHOT_TEST;

  fun toWellKnownSourceSet(): IdeModuleWellKnownSourceSet {
    return when (this) {
      MAIN -> IdeModuleWellKnownSourceSet.MAIN
      UNIT_TEST -> IdeModuleWellKnownSourceSet.UNIT_TEST
      ANDROID_TEST -> IdeModuleWellKnownSourceSet.ANDROID_TEST
      TEST_FIXTURES -> IdeModuleWellKnownSourceSet.TEST_FIXTURES
      SCREENSHOT_TEST -> IdeModuleWellKnownSourceSet.SCREENSHOT_TEST
    }
  }

  fun toPrintableName(): String {
    return when (this) {
      MAIN -> "Main"
      UNIT_TEST -> "UnitTest"
      ANDROID_TEST -> "AndroidTest"
      TEST_FIXTURES -> "TestFixtures"
      SCREENSHOT_TEST -> "ScreenshotTest"
    }
  }
}

/**
 * The name of an artifact built from a test suite source.
 *
 * @property name the source set name of the test suite source, which is also its IDE module name (`:app.<name>`), e.g. `first` for a
 *   single-source suite, or `mixedTest` / `mixedAndroidTest` for a suite `mixed` with several sources. It is neither the suite name
 *   (`mixed`) nor prefixed with the variant name (`debugMixedTest`).
 */
data class IdeTestSuiteArtifactName(override val name: String) : IdeArtifactName {
  init {
    require(name.isNotEmpty()) { "Test suite artifact name cannot be empty" }
  }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}
