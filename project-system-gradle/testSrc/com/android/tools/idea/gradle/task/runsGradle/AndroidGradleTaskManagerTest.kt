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
package com.android.tools.idea.gradle.task.runsGradle

import com.android.tools.idea.gradle.project.sync.snapshots.AndroidCoreTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.gradle.task.AndroidGradleTaskManager
import com.android.tools.idea.testartifacts.testsuite.GradleRunConfigurationExtension.BooleanOptions.SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.hookExecuteTasks
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.LocationAwareExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.model.task.event.ExternalSystemTaskExecutionEvent
import com.intellij.openapi.externalSystem.model.task.event.TestOperationDescriptor
import com.intellij.openapi.externalSystem.service.ExternalSystemFacadeManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.writeText
import kotlinx.coroutines.runBlocking
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.task.GradleTaskManager
import org.jetbrains.plugins.gradle.service.task.PredefinedVersionSpecificInitScript
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class AndroidGradleTaskManagerTest {
  @get:Rule val expect: Expect = Expect.createAndEnableStackTrace()

  @get:Rule val projectRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @Test
  fun `app assembleDebug from root and app`() = runBlocking {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
    preparedProject.open { project ->
      val path = preparedProject.root
      val capturedRequests = project.hookExecuteTasks()
      val facadeManager = ExternalSystemFacadeManager.getInstance()
      val facade = facadeManager.getFacade(project, path.absolutePath, GradleConstants.SYSTEM_ID)
      val taskManager = facade.taskManager

      val externalSystemTaskId = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
      run {
        // 1) This is a common form used by Android Studio etc.
        val projectPath = path.absolutePath
        val settings = GradleExecutionSettings().apply { tasks = listOf(":app:assembleDebug") }
        taskManager.executeTasks(projectPath, externalSystemTaskId, settings)
      }
      run {
        // 2) This is a way in which tasks are invoked from the Gradle tool window and from Gradle run configurations, if configured this
        // way.
        val projectPath = path.resolve("app").absolutePath
        val settings = GradleExecutionSettings().apply { tasks = listOf("assembleDebug") }
        taskManager.executeTasks(projectPath, externalSystemTaskId, settings)
      }

      expect.that(capturedRequests).hasSize(2)

      expect.that(capturedRequests.getOrNull(0)?.taskId).isSameAs(externalSystemTaskId)
      expect.that(capturedRequests.getOrNull(0)?.project).isSameAs(project)
      expect.that(capturedRequests.getOrNull(0)?.rootProjectPath).isEqualTo(path)
      expect.that(capturedRequests.getOrNull(0)?.gradleTasks).isEqualTo(listOf(":app:assembleDebug"))

      expect.that(capturedRequests.getOrNull(1)?.taskId).isSameAs(externalSystemTaskId)
      expect.that(capturedRequests.getOrNull(1)?.project).isSameAs(project)
      expect.that(capturedRequests.getOrNull(1)?.rootProjectPath).isEqualTo(path.resolve("app"))
      expect.that(capturedRequests.getOrNull(1)?.gradleTasks).isEqualTo(listOf("assembleDebug"))
    }
  }

  @Test
  fun `Given invalid java home settings When executing any task Then no exception was thrown since invalid path is not specified to TAPI`() =
    runBlocking {
      val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
      preparedProject.open {
        val executionSettings =
          ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, project.basePath!!, GradleConstants.SYSTEM_ID)
            .apply {
              tasks = listOf(":help")
              javaHome = "invalid"
            }

        val exception =
          assertThrows(ExternalSystemException::class.java) {
            AndroidGradleTaskManager()
              .executeTasks(
                project.basePath!!,
                ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project),
                executionSettings,
                ExternalSystemTaskNotificationListener.NULL_OBJECT,
              )
          }

        assertThat(exception.cause).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(exception.message).contains("Supplied javaHome is not a valid folder")
      }
    }

  @Test
  fun `supports version-specific scripts`() = runBlocking {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
    preparedProject.open { project ->
      var capturedException: Exception? = null
      val executionSettings =
        ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, project.basePath!!, GradleConstants.SYSTEM_ID).apply {
          tasks = listOf("foo")
        }
      val initScript =
        PredefinedVersionSpecificInitScript(
          script =
            """
            afterProject {
              it.tasks.register("foo") {
                println 'foo'
              }
            }
            """
              .trimIndent(),
          filePrefix = "fooTask",
          isApplicable = { v -> GradleVersion.version("7.0") < v },
        )
      executionSettings.putUserData(GradleTaskManager.VERSION_SPECIFIC_SCRIPTS_KEY, listOf(initScript))

      AndroidGradleTaskManager()
        .executeTasks(
          project.basePath!!,
          ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project),
          executionSettings,
          object : ExternalSystemTaskNotificationListener {
            override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: java.lang.Exception) {
              capturedException = exception
            }
          },
        )

      assertNull(capturedException)
    }
  }

  @Test
  fun `throws exception if gradle task execution fails`() = runBlocking {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
    preparedProject.open { project ->
      runWriteActionAndWait {
        val buildFile = VfsUtil.findFileByIoFile(projectRoot.resolve("app/build.gradle"), true)!!
        buildFile.writeText("**")
      }
      val executionSettings =
        ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, project.basePath!!, GradleConstants.SYSTEM_ID).apply {
          tasks = listOf(":assembleDebug")
        }
      val exception =
        assertThrows(LocationAwareExternalSystemException::class.java) {
          AndroidGradleTaskManager()
            .executeTasks(
              project.basePath!!,
              ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project),
              executionSettings,
              ExternalSystemTaskNotificationListener.NULL_OBJECT,
            )
        }

      assertThat(exception.message).contains("fails/project/app/build.gradle': 1: Unexpected input: '**' @ line 1, column 1")
    }
  }

  /**
   * Regression test for b/567713204. GradleTasksExecutorImpl must enable built-in (Tooling API) test events for Gradle 7.6+, mirroring
   * GradleTaskManager.setupBuiltInTestEvents in the IntelliJ Gradle plugin, which Studio bypasses. Without it, test events are only
   * reported via the legacy init-script test logger, which does not see test tasks in included builds ("Test events were not received").
   * This verifies that Tooling API test events (with a [TestOperationDescriptor]) are delivered to the notification listener.
   */
  @Test
  fun `enables built-in test events for test executions with Gradle 7_6 and above`() = runBlocking {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
    preparedProject.open { project ->
      val executionSettings =
        ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, project.basePath!!, GradleConstants.SYSTEM_ID).apply {
          tasks = listOf(":app:testDebugUnitTest", "--tests", "google.simpleapplication.UnitTest.passingTest")
          isRunAsTest = true
        }

      val statusEvents = mutableListOf<ExternalSystemTaskNotificationEvent>()
      AndroidGradleTaskManager()
        .executeTasks(
          project.basePath!!,
          ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project),
          executionSettings,
          object : ExternalSystemTaskNotificationListener {
            override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) {
              statusEvents.add(event)
            }
          },
        )

      val testEvents =
        statusEvents.filterIsInstance<ExternalSystemTaskExecutionEvent>().filter { it.progressEvent.descriptor is TestOperationDescriptor }
      assertThat(testEvents).isNotEmpty()
    }
  }

  /**
   * Runs whose results are shown in AndroidTestSuiteView (e.g. AGP test suites such as Journeys, or screenshot tests) are rendered by
   * GradleAndroidTestsExecutionConsoleManager, which only parses the legacy `<ijLog>` XML events printed to stdout by the ijTestLogger init
   * script. Built-in (Tooling API) test events must therefore not be enabled for them, otherwise ijTestLogger is not injected and the view
   * receives no test events.
   */
  @Test
  fun `does not enable built-in test events when results are shown in Android Test Suite view`() = runBlocking {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.SIMPLE_APPLICATION)
    preparedProject.open { project ->
      val executionSettings =
        ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(project, project.basePath!!, GradleConstants.SYSTEM_ID).apply {
          tasks = listOf(":app:testDebugUnitTest", "--tests", "google.simpleapplication.UnitTest.passingTest")
          isRunAsTest = true
          putUserData(SHOW_TEST_RESULT_IN_ANDROID_TEST_SUITE_VIEW.userDataKey, true)
        }

      val statusEvents = mutableListOf<ExternalSystemTaskNotificationEvent>()
      val output = StringBuilder()
      AndroidGradleTaskManager()
        .executeTasks(
          project.basePath!!,
          ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project),
          executionSettings,
          object : ExternalSystemTaskNotificationListener {
            override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) {
              statusEvents.add(event)
            }

            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
              output.append(text)
            }
          },
        )

      val testEvents =
        statusEvents.filterIsInstance<ExternalSystemTaskExecutionEvent>().filter { it.progressEvent.descriptor is TestOperationDescriptor }
      assertThat(testEvents).isEmpty()
      assertThat(output.toString()).contains("<ijLog>")
    }
  }
}
