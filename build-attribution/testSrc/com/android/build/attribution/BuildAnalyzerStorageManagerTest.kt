/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.build.attribution

import com.android.build.attribution.analyzers.BuildEventsAnalyzersProxy
import com.android.build.attribution.data.BuildRequestHolder
import com.android.build.attribution.data.PluginContainer
import com.android.build.attribution.data.TaskContainer
import com.android.tools.idea.Projects
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test

class BuildAnalyzerStorageManagerTest {
  @get:Rule val projectRule = AndroidProjectRule.onDisk()

  @After
  fun cleanup() {
    StudioFlags.BUILD_ANALYZER_HISTORY.clearOverride()
  }

  @Test(expected = IllegalStateException::class)
  fun testNullBuildResultsResponse() {
    BuildAnalyzerStorageManager.getInstance(projectRule.project).getLatestBuildAnalysisResults()
  }

  @Test
  fun testBuildResultsAreStored() {
    val buildAnalyzerResultData = constructBuildAnalyzerResultData(buildID = "some buildID")
    BuildAnalyzerStorageManager.getInstance(projectRule.project)
      .storeNewBuildResults(
        buildAnalyzerResultData.analyzersProxy,
        buildAnalyzerResultData.buildID,
        buildAnalyzerResultData.buildRequestHolder,
      )
      .get()
    Truth.assertThat(BuildAnalyzerStorageManager.getInstance(projectRule.project).getSuccessfulResult().getBuildSessionID())
      .isEqualTo("some buildID")
  }

  @Test
  fun testDoesNotStoreResultsWithFalseFlag() {
    StudioFlags.BUILD_ANALYZER_HISTORY.override(false)
    val buildAnalyzerResultData = constructBuildAnalyzerResultData(buildID = "some buildID")
    BuildAnalyzerStorageManager.getInstance(projectRule.project)
      .storeNewBuildResults(
        buildAnalyzerResultData.analyzersProxy,
        buildAnalyzerResultData.buildID,
        buildAnalyzerResultData.buildRequestHolder,
      )
      .get()
    Truth.assertThat(BuildAnalyzerStorageManager.getInstance(projectRule.project).getListOfHistoricBuildDescriptors()).isEmpty()
  }

  @Test
  fun testListenerIsActive() {
    var listenerInvocationCounter = 0
    projectRule.project.messageBus
      .connect()
      .subscribe(
        BuildAnalyzerStorageManager.DATA_IS_READY_TOPIC,
        object : BuildAnalyzerStorageManager.Listener {
          override fun newDataAvailable() {
            listenerInvocationCounter += 1
          }
        },
      )
    Truth.assertThat(listenerInvocationCounter).isEqualTo(0)
    val buildAnalyzerResultData = constructBuildAnalyzerResultData(buildID = "some buildID")
    BuildAnalyzerStorageManager.getInstance(projectRule.project)
      .storeNewBuildResults(
        buildAnalyzerResultData.analyzersProxy,
        buildAnalyzerResultData.buildID,
        buildAnalyzerResultData.buildRequestHolder,
      )
      .get()
    Truth.assertThat(listenerInvocationCounter).isEqualTo(1)
  }

  data class BuildAnalyzerResultData(
    val analyzersProxy: BuildEventsAnalyzersProxy,
    val buildID: String,
    val buildRequestHolder: BuildRequestHolder,
  )

  private fun constructBuildAnalyzerResultData(
    buildStartedTimestamp: Long = 12345,
    buildFinishedTimestamp: Long = 12345,
    buildID: String = UUID.randomUUID().toString(),
  ): BuildAnalyzerResultData {
    val taskContainer = TaskContainer()
    val pluginContainer = PluginContainer()
    val analyzersProxy =
      BuildEventsAnalyzersProxy(taskContainer, pluginContainer, BuildAnalyzerStorageManager.getInstance(projectRule.project))
    val setPrivateField: (Any, String, Any) -> Unit = { classInstance: Any, fieldName: String, newValue: Any ->
      val field = classInstance.javaClass.getDeclaredField(fieldName)
      field.isAccessible = true
      field.set(classInstance, newValue)
    }
    val criticalPathAnalyzer = analyzersProxy.criticalPathAnalyzer
    setPrivateField(criticalPathAnalyzer, "buildStartedTimestamp", buildStartedTimestamp)
    setPrivateField(criticalPathAnalyzer, "buildFinishedTimestamp", buildFinishedTimestamp)
    val request =
      GradleBuildInvoker.Request.builder(projectRule.project, Projects.getBaseDirPath(projectRule.project), "assembleDebug").build()
    return BuildAnalyzerResultData(analyzersProxy, buildID, BuildRequestHolder(request))
  }
}
