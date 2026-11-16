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
package com.android.tools.idea.project

import com.android.SdkConstants.FN_ANDROID_MANIFEST_XML
import com.android.tools.idea.model.queryIsMainManifestIndexReady
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.util.androidFacet
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.RunManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.runInEdtAndWait
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.intellij.lang.annotations.Language
import org.junit.Rule
import org.junit.Test

@Language("XML")
private val APP_MANIFEST_WITH_LAUNCHER_ACTIVITY =
  """
  <?xml version="1.0" encoding="utf-8"?>
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.example.app">
      <application>
          <activity android:name=".MainActivity" android:exported="true">
              <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
          </activity>
      </application>
  </manifest>
  """
    .trimIndent()

class AndroidRunConfigurationsManifestIndexNotReadyTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun `create run configurations creates a single android run configuration when the manifest index is not ready`() =
    runBlocking<Unit> {
      val isManifestIndexReady = projectRule.module.androidFacet?.queryIsMainManifestIndexReady() ?: false
      assertFalse(isManifestIndexReady)

      // The module has no default launcher activity, so whether an Android run configuration should be
      // created depends on whether the watch feature is required, which is only checked in smart mode.
      AndroidRunConfigurations.instance.createNonSmartModeRunConfigurations(projectRule.project)
      assertThat(RunManager.getInstance(projectRule.project).allConfigurationsList).isEmpty()

      AndroidRunConfigurations.instance.createSmartModeRunConfigurations(projectRule.project)

      val runConfigurations = RunManager.getInstance(projectRule.project).allConfigurationsList
      assertThat(runConfigurations).hasSize(1)
      assertThat(runConfigurations.single().type).isInstanceOf(AndroidRunConfigurationType::class.java)

      runInEdtAndWait {
        val selected = RunManager.getInstance(projectRule.project).selectedConfiguration
        assertThat(selected?.configuration).isEqualTo(runConfigurations.single())
      }

      // Declarative WatchFace and Wear configurations are not created when the manifest index is not ready
      // as LaunchUtils.isWatchFeatureRequired queries the manifest index.
    }

  @Test
  fun `createProjectRunConfigurations creates non-smart-mode configurations in dumb mode and waits for smart mode for the rest`() =
    runBlocking<Unit> {
      val project = projectRule.project
      // A default launcher activity lets the non-smart-mode pass create the Android run configuration.
      projectRule.fixture.addFileToProject(FN_ANDROID_MANIFEST_XML, APP_MANIFEST_WITH_LAUNCHER_ACTIVITY)
      val runManager = RunManager.getInstance(project)
      runManager.allConfigurationsList.forEach { runManager.removeConfiguration(runManager.findSettings(it)) }

      val manager = AndroidRunConfigurationsManager.getInstance(project)
      val dumbTask = DumbModeTestUtils.startEternalDumbModeTask(project)
      val job1: Job
      val job2: Job
      try {
        job1 = manager.createProjectRunConfigurations()
        job2 = manager.createProjectRunConfigurations()
        // The non-smart-mode passes run in dumb mode, while both calls wait for smart mode to run the smart-mode pass.
        withTimeout(10_000) {
          while (runManager.allConfigurationsList.isEmpty()) {
            delay(10)
          }
        }
        val runConfigurations = runManager.allConfigurationsList
        assertThat(runConfigurations).hasSize(1)
        assertThat(runConfigurations.single().type).isInstanceOf(AndroidRunConfigurationType::class.java)
        assertThat(job1.isCompleted).isFalse()
        assertThat(job2.isCompleted).isFalse()
      } finally {
        DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, dumbTask)
      }

      withTimeout(10_000) { joinAll(job1, job2) }
      assertThat(job1.isCancelled).isFalse()
      assertThat(job2.isCancelled).isFalse()

      val trackedJobs = mutableListOf<Job>()
      runInEdtAndWait { manager.consumeBulkOperationsState { trackedJobs.add(it) } }
      withTimeout(10_000) { trackedJobs.forEach { it.join() } }

      val runConfigurations = runManager.allConfigurationsList
      assertThat(runConfigurations).hasSize(1)
      assertThat(runConfigurations.single().type).isInstanceOf(AndroidRunConfigurationType::class.java)
    }
}
