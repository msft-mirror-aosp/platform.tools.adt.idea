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
package com.google.idea.blaze.android.run.test

import com.android.tools.idea.console.getDeviceConsoleFilters
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration
import com.google.idea.blaze.base.run.filter.getSafeBlazeTestConsoleFilters
import com.google.idea.blaze.base.run.smrunner.BlazeTestConsoleProperties
import com.google.idea.blaze.base.run.testlogs.BlazeTestResultsProvider
import com.intellij.execution.Executor
import com.intellij.execution.testframework.ui.TestsConsoleBuilderImpl
import com.intellij.execution.ui.ConsoleView

/**
 * [BlazeTestConsoleProperties] for `android_instrumentation_test` targets, restricting console filters to the device-safe allowlist plus
 * audited Blaze filters since output mixes `blaze test` output with Android device output.
 */
class BlazeAndroidTestConsoleProperties(
  runConfiguration: BlazeCommandRunConfiguration,
  executor: Executor,
  testResultFinderStrategy: BlazeTestResultsProvider,
) : BlazeTestConsoleProperties(runConfiguration, executor, testResultFinderStrategy) {

  override fun createConsole(): ConsoleView {
    val builder = TestsConsoleBuilderImpl(project, scope, !isEditable, /* usePredefinedMessageFilter= */ false)
    builder.filters(getDeviceConsoleFilters(project, scope) + getSafeBlazeTestConsoleFilters(project))
    return builder.console
  }
}
