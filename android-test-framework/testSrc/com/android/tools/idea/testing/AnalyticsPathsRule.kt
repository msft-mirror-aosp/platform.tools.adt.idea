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
package com.android.tools.idea.testing

import com.android.tools.analytics.AnalyticsPaths
import com.intellij.testFramework.TemporaryDirectory
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Overrides the Android settings home directory used by [AnalyticsPaths] with a temporary directory for the duration of the test. */
class AnalyticsPathsRule : ExternalResource() {

  private val tempDirectory = TemporaryDirectory()

  override fun apply(base: Statement, description: Description): Statement {
    return tempDirectory.apply(super.apply(base, description), description)
  }

  override fun before() {
    AnalyticsPaths.overrideAndroidSettingsHomeDirectory(tempDirectory.newPath().toString())
  }

  override fun after() {
    AnalyticsPaths.restoreAndroidSettingsHomeDirectory()
  }
}
