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
 * Test Suites are defined at the project level and targets selected variants.
 *
 * At the project level, you will find information related to the suites like the configured source folders as well as the JUnit engines
 * configured to run the tests.
 *
 * At the variant level [IdeTestSuiteComponent] will provide information like which devices are targeted and the Gradle task to execute the
 * test suite.
 */
interface IdeTestSuite {
  /** Test suite name. */
  val name: String

  /**
   * Configured sources for the test suite.
   *
   * Each test suite source will be processed by AGP and binaries will be provided to the JUnit test engines.
   */
  val sources: List<IdeTestSuiteSource>

  /** JUnit engines configuration */
  val junitEngineInfo: IdeJUnitEngineInfo

  /** Targeted variants by name. */
  val targetedVariants: Collection<String>
}

/**
 * The JUnit Platform `TestEngine.getId()` of the engine that runs Compose Preview screenshot tests.
 *
 * Must match `PreviewScreenshotTestEngine.getId()` (in `screenshot-validation-junit-engine`) and
 * `ScreenshotAgpTestSuiteImpl.SCREENSHOT_TEST_ENGINE_ID` (in `ScreenshotTestSuiteImpl.kt` in AGP), which adds this ID to
 * [IdeJUnitEngineInfo.includedEngines] for screenshot test suites. Defined here as the single Studio-side constant because neither
 * `sdk-common` nor `builder-model` exposes JUnit engine IDs.
 */
const val SCREENSHOT_TEST_ENGINE_ID = "preview-screenshot-test-engine"

/** Whether this test suite runs Compose Preview screenshot tests, i.e. includes the [SCREENSHOT_TEST_ENGINE_ID] JUnit engine. */
fun IdeTestSuite.isScreenshotTestSuite(): Boolean = junitEngineInfo.includedEngines.contains(SCREENSHOT_TEST_ENGINE_ID)

/**
 * The source set names of this test suite, one per source. Studio creates one IDE module per source set.
 *
 * AGP names the only source of a single-source test suite after the test suite, and the sources of a test suite with several sources
 * `<suite>Test` (host jar), `<suite>AndroidTest` (test APK) and `<suite>Assets` (assets). AGP rejects builds in which these names collide,
 * and adds an assets source to a test suite that declares none, so this list is never empty and has no duplicates.
 */
val IdeTestSuite.sourceSetNames: List<String>
  get() = sources.map { it.name }

/** Returns the test suite of this project that [component] applies to its variant, or null if this project does not define it. */
fun IdeAndroidProject.findTestSuite(component: IdeTestSuiteComponentCore): IdeTestSuite? = testSuites.find {
  it.name == component.suiteName
}
