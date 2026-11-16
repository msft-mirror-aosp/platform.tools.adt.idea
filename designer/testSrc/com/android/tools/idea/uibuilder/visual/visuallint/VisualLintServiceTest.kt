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
package com.android.tools.idea.uibuilder.visual.visuallint

import com.android.testutils.TestUtils
import com.android.testutils.waitForCondition
import com.android.tools.idea.common.SyncNlModel
import com.android.tools.idea.common.model.ChangeType
import com.android.tools.idea.common.type.DesignerTypeRegistrar
import com.android.tools.idea.configurations.ReferenceDevice
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.RenderTestUtil
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.idea.rendering.createNoSecurityRenderService
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.uibuilder.model.NlComponentRegistrar
import com.android.tools.idea.uibuilder.scene.NlModelHierarchyUpdater
import com.android.tools.idea.uibuilder.type.LayoutFileType
import com.android.tools.rendering.RenderAsyncActionExecutor.RenderingTopic
import com.android.tools.rendering.RenderExecutor
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.android.tools.rendering.tracking.notDisposedRenderTasks
import com.android.tools.visuallint.VisualLintErrorType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.android.facet.AndroidFacet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VisualLintServiceTest {

  @get:Rule val projectRule = AndroidGradleProjectRule()

  @Before
  fun setup() {
    projectRule.fixture.testDataPath = TestUtils.resolveWorkspacePath("tools/adt/idea/designer/testData").toString()
    RenderTestUtil.beforeRenderTestCase()
    StudioRenderService.setForTesting(projectRule.project, createNoSecurityRenderService())
    DesignerTypeRegistrar.register(LayoutFileType)
    val visualLintInspections =
      arrayOf(
        BoundsAnalyzerInspection(),
        BottomNavAnalyzerInspection(),
        BottomAppBarAnalyzerInspection(),
        TextFieldSizeAnalyzerInspection(),
        OverlapAnalyzerInspection(),
        LongTextAnalyzerInspection(),
        ButtonSizeAnalyzerInspection(),
        WearMarginAnalyzerInspection(),
        AtfAnalyzerInspection(),
      )
    projectRule.fixture.enableInspections(*visualLintInspections)
  }

  @After
  fun tearDown() {
    ApplicationManager.getApplication().invokeAndWait { RenderTestUtil.afterRenderTestCase() }
    StudioRenderService.setForTesting(projectRule.project, null)
  }

  @Test
  fun runBackgroundVisualLintAnalysis() {
    projectRule.load("projects/visualLintApplication")
    runBlocking {
      val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val visualLintService =
          VisualLintService.getInstanceForTest(
            project = projectRule.project,
            parentDisposable = projectRule.fixture.testRootDisposable,
            coroutineScope = serviceScope,
            visualLintDispatcher = Dispatchers.Default,
            visualLintAnalyzerDispatcher = Dispatchers.Default,
          )
        val visualLintIssueModel = visualLintService.issueModel

        val module = projectRule.getModule("app")
        val facet = AndroidFacet.getInstance(module)!!
        val dashboardLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_dashboard.xml")!!
        val nlModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            dashboardLayout,
          )
        val dashboardJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            listOf(nlModel),
            emptyMap(),
          )
        dashboardJob.join()
        assertFalse(dashboardJob.isCancelled)

        val issues = visualLintIssueModel.issues
        assertEquals(2, issues.size)
        issues.forEach { assertEquals("Visual Lint Issue", it.category) }

        val atfLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/atf_layout.xml")!!
        val atfModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            atfLayout,
          )
        val atfJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            listOf(atfModel),
            emptyMap(),
          )
        atfJob.join()
        assertFalse(atfJob.isCancelled)

        val atfIssues = visualLintIssueModel.issues
        assertEquals(1, atfIssues.size)
        atfIssues.forEach {
          assertEquals("Visual Lint Issue", it.category)
          assertFalse((it as VisualLintRenderIssue).type.isAtfErrorType())
        }

        val wearLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/wear_layout.xml")!!
        val wearConfiguration = RenderTestUtil.getConfiguration(module, wearLayout, "wearos_small_round")
        val wearModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            wearLayout,
            wearConfiguration,
          )
        val wearJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            listOf(wearModel),
            emptyMap(),
          )
        wearJob.join()
        assertFalse(wearJob.isCancelled)

        val wearIssues = visualLintIssueModel.issues
        assertEquals(13, wearIssues.size)
        wearIssues.forEach { assertEquals("Visual Lint Issue", it.category) }
        val wearMarginIssues = wearIssues.filterIsInstance<VisualLintRenderIssue>().filter { it.type == VisualLintErrorType.WEAR_MARGIN }
        assertEquals(5, wearMarginIssues.size)
      } finally {
        serviceScope.cancel()
      }
    }
  }

  @Test
  fun runOnPreviewVisualLintAnalysis() {
    projectRule.load("projects/visualLintApplication")
    runTest {
      val visualLintService =
        VisualLintService.getInstanceForTest(
          project = projectRule.project,
          parentDisposable = projectRule.fixture.testRootDisposable,
          coroutineScope = this,
          visualLintDispatcher = StandardTestDispatcher(testScheduler),
          visualLintAnalyzerDispatcher = StandardTestDispatcher(testScheduler),
        )
      val visualLintIssueModel = visualLintService.issueModel

      val module = projectRule.getModule("app")
      val facet = AndroidFacet.getInstance(module)!!
      val notificationsLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_notifications.xml")!!

      val phoneConfig =
        RenderTestUtil.getConfiguration(
          module,
          notificationsLayout,
          "_device_class_phone",
          "Theme.MaterialComponents.DayNight.DarkActionBar",
        )
      val phoneModel =
        SyncNlModel.create(
          projectRule.fixture.testRootDisposable,
          NlComponentRegistrar,
          AndroidBuildTargetReference.gradleOnly(facet),
          notificationsLayout,
          phoneConfig,
        )
      RenderTestUtil.withRenderTask(facet, notificationsLayout, phoneConfig) { task: RenderTask ->
        task.setDecorations(false)
        try {
          val result = task.render().get()
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            emptyList(),
            mapOf(result to phoneModel),
          )
          testScheduler.advanceUntilIdle()
          val issues = visualLintIssueModel.issues
          assertEquals(0, issues.size)
        } catch (ex: Exception) {
          throw RuntimeException(ex)
        }
      }

      val tabletConfig =
        RenderTestUtil.getConfiguration(
          module,
          notificationsLayout,
          "_device_class_tablet",
          "Theme.MaterialComponents.DayNight.DarkActionBar",
        )
      val tabletModel =
        SyncNlModel.create(
          projectRule.fixture.testRootDisposable,
          NlComponentRegistrar,
          AndroidBuildTargetReference.gradleOnly(facet),
          notificationsLayout,
          tabletConfig,
        )
      RenderTestUtil.withRenderTask(facet, notificationsLayout, tabletConfig) { task: RenderTask ->
        task.setDecorations(false)
        try {
          val result = task.render().get()
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            emptyList(),
            mapOf(result to tabletModel),
          )
          testScheduler.advanceUntilIdle()
          val issues = visualLintIssueModel.issues
          assertEquals(1, issues.size)
          assertEquals("Visual Lint Issue", issues[0].category)
        } catch (ex: Exception) {
          throw RuntimeException(ex)
        }
      }

      val atfLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/atf_layout.xml")!!
      val atfModel =
        SyncNlModel.create(
          projectRule.fixture.testRootDisposable,
          NlComponentRegistrar,
          AndroidBuildTargetReference.gradleOnly(facet),
          atfLayout,
          phoneConfig,
        )
      RenderTestUtil.withRenderTask(facet, atfLayout, phoneConfig) { task: RenderTask ->
        task.setDecorations(false)
        task.setEnableLayoutScanner(true)
        try {
          val result = task.render().get()
          NlModelHierarchyUpdater.updateHierarchy(result, atfModel)
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            emptyList(),
            mapOf(result to atfModel),
          )
          testScheduler.advanceUntilIdle()
          val issues = visualLintIssueModel.issues
          assertEquals(3, issues.size)
          val clickIssue = issues.filterIsInstance<VisualLintRenderIssue>().filter { it.type.isAtfErrorType() }
          assertEquals(2, clickIssue.size)
        } catch (ex: Exception) {
          throw RuntimeException(ex)
        }
      }
    }
  }

  @Test
  fun overlappingVisualLintAnalysisCancelsEarlierRun() {
    projectRule.load("projects/visualLintApplication")
    runBlocking {
      val testScheduler = TestCoroutineScheduler()
      val testDispatcher = StandardTestDispatcher(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
      try {
        val visualLintService =
          VisualLintService.getInstanceForTest(
            project = projectRule.project,
            parentDisposable = projectRule.fixture.testRootDisposable,
            coroutineScope = serviceScope,
            visualLintDispatcher = testDispatcher,
            visualLintAnalyzerDispatcher = testDispatcher,
          )
        val visualLintIssueModel = visualLintService.issueModel

        val module = projectRule.getModule("app")
        val facet = AndroidFacet.getInstance(module)!!
        val dashboardLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_dashboard.xml")!!
        val dashboardModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            dashboardLayout,
          )
        val atfLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/atf_layout.xml")!!
        val atfModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            atfLayout,
          )

        val sharedIssueProvider = ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable)
        val firstJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            sharedIssueProvider,
            listOf(dashboardModel),
            emptyMap(),
          )
        // Start the first job so it reaches renderFuture.await(), then supersede it mid-flight.
        testScheduler.runCurrent()

        val secondJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            sharedIssueProvider,
            listOf(atfModel),
            emptyMap(),
          )
        // Pump only ready (t = 0) tasks so runBlocking does not auto-advance virtual time past withTimeout.
        withTimeout(60.seconds) {
          while (!secondJob.isCompleted) {
            testScheduler.runCurrent()
            delay(10)
          }
        }
        secondJob.join()

        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isCompleted)
        assertEquals(1, visualLintIssueModel.issues.size)
        assertEquals(1, sharedIssueProvider.getIssues().size)
      } finally {
        serviceScope.cancel()
      }
    }
  }

  @Test
  fun rapidReTriggeringCancelsIntermediateRuns() {
    projectRule.load("projects/visualLintApplication")
    runBlocking {
      val testScheduler = TestCoroutineScheduler()
      val testDispatcher = StandardTestDispatcher(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
      try {
        val visualLintService =
          VisualLintService.getInstanceForTest(
            project = projectRule.project,
            parentDisposable = projectRule.fixture.testRootDisposable,
            coroutineScope = serviceScope,
            visualLintDispatcher = testDispatcher,
            visualLintAnalyzerDispatcher = testDispatcher,
          )
        val visualLintIssueModel = visualLintService.issueModel

        val module = projectRule.getModule("app")
        val facet = AndroidFacet.getInstance(module)!!
        val dashboardLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_dashboard.xml")!!
        val dashboardModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            dashboardLayout,
          )
        val atfLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/atf_layout.xml")!!
        val atfModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            atfLayout,
          )

        val sharedIssueProvider = ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable)
        val firstJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            sharedIssueProvider,
            listOf(dashboardModel),
            emptyMap(),
          )
        // Start the first job so it acquires analysisMutex and suspends at renderFuture.await().
        testScheduler.runCurrent()

        // Queue secondJob and immediately supersede it with thirdJob before secondJob runs.
        val secondJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            sharedIssueProvider,
            listOf(dashboardModel),
            emptyMap(),
          )
        val thirdJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            sharedIssueProvider,
            listOf(atfModel),
            emptyMap(),
          )

        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isCancelled)

        withTimeout(60.seconds) {
          while (!thirdJob.isCompleted) {
            testScheduler.runCurrent()
            delay(10)
          }
        }
        thirdJob.join()

        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isCancelled)
        assertTrue(thirdJob.isCompleted)
        assertFalse(thirdJob.isCancelled)
        assertEquals(1, visualLintIssueModel.issues.size)
        assertEquals(1, sharedIssueProvider.getIssues().size)
      } finally {
        serviceScope.cancel()
      }
    }
  }

  @Test
  fun previewOnlyDisposedModelReturnsWithoutRunning() {
    projectRule.load("projects/visualLintApplication")
    runTest {
      val visualLintService =
        VisualLintService.getInstanceForTest(
          project = projectRule.project,
          parentDisposable = projectRule.fixture.testRootDisposable,
          coroutineScope = this,
          visualLintDispatcher = StandardTestDispatcher(testScheduler),
          visualLintAnalyzerDispatcher = StandardTestDispatcher(testScheduler),
        )
      val visualLintIssueModel = visualLintService.issueModel

      val module = projectRule.getModule("app")
      val facet = AndroidFacet.getInstance(module)!!
      val notificationsLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_notifications.xml")!!
      val tabletConfig =
        RenderTestUtil.getConfiguration(
          module,
          notificationsLayout,
          "_device_class_tablet",
          "Theme.MaterialComponents.DayNight.DarkActionBar",
        )
      val tabletModel =
        SyncNlModel.create(
          projectRule.fixture.testRootDisposable,
          NlComponentRegistrar,
          AndroidBuildTargetReference.gradleOnly(facet),
          notificationsLayout,
          tabletConfig,
        )
      RenderTestUtil.withRenderTask(facet, notificationsLayout, tabletConfig) { task: RenderTask ->
        task.setDecorations(false)
        val result = task.render().get()
        Disposer.dispose(tabletModel)

        val issueProvider = ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable)
        val job =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            issueProvider,
            emptyList(),
            mapOf(result to tabletModel),
          )
        testScheduler.advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(0, visualLintIssueModel.issues.size)
        assertEquals(0, issueProvider.getIssues().size)
      }
    }
  }

  @Test
  fun createRenderResultCancellationDoesNotLeakRenderTask() {
    projectRule.load("projects/visualLintApplication")
    val facet = AndroidFacet.getInstance(projectRule.getModule("app"))!!
    val dashboardLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_dashboard.xml")!!
    val nlModel =
      SyncNlModel.create(
        projectRule.fixture.testRootDisposable,
        NlComponentRegistrar,
        AndroidBuildTargetReference.gradleOnly(facet),
        dashboardLayout,
      )
    val executor = RenderService.getRenderAsyncActionExecutor() as RenderExecutor
    val tasksBefore = notDisposedRenderTasks().map { it.first }.toSet()

    // Occupy the single render thread so inflate() is deterministically queued, not running or finished, when we cancel.
    val renderThreadBlocked = CountDownLatch(1)
    val releaseRenderThread = CountDownLatch(1)
    val blocker =
      executor.runAsyncActionWithTimeout(30, TimeUnit.SECONDS, 30, TimeUnit.SECONDS, RenderingTopic.NOT_SPECIFIED) {
        renderThreadBlocked.countDown()
        releaseRenderThread.await(30, TimeUnit.SECONDS)
      }
    val createdTask: RenderTask
    try {
      assertTrue(renderThreadBlocked.await(30, TimeUnit.SECONDS))
      val future = createRenderResult(nlModel, false)
      // build() created the RenderTask and inflate() queued its VISUAL_LINT action behind the blocker.
      waitForCondition(30.seconds) { executor.numPendingActions == 1 }
      createdTask = (notDisposedRenderTasks().map { it.first }.toSet() - tasksBefore).single()

      assertTrue(future.cancel(false))
      assertTrue(future.isCancelled)
      // whenComplete -> thenAccept(disposeOnce) runs synchronously inside cancel(); disposeAsync() flips isDisposed first.
      assertTrue(createdTask.isDisposed)
    } finally {
      releaseRenderThread.countDown()
    }
    blocker.get(30, TimeUnit.SECONDS)
    RenderTestUtil.waitForRenderTaskDisposeToFinish()
    assertTrue((notDisposedRenderTasks().map { it.first }.toSet() - tasksBefore).isEmpty())
  }

  @Test
  fun modelChangeDuringBackgroundRenderRetriesInsteadOfPublishingPartialResults() {
    projectRule.load("projects/visualLintApplication")
    runBlocking {
      val testScheduler = TestCoroutineScheduler()
      val testDispatcher = StandardTestDispatcher(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
      try {
        val visualLintService =
          VisualLintService.getInstanceForTest(
            project = projectRule.project,
            parentDisposable = projectRule.fixture.testRootDisposable,
            coroutineScope = serviceScope,
            visualLintDispatcher = testDispatcher,
            visualLintAnalyzerDispatcher = testDispatcher,
          )
        val visualLintIssueModel = visualLintService.issueModel

        val module = projectRule.getModule("app")
        val facet = AndroidFacet.getInstance(module)!!
        val atfLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/atf_layout.xml")!!
        val atfModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            atfLayout,
          )
        atfModel.activate()

        fun issueSignature(): Set<Pair<VisualLintErrorType, Set<String>>> =
          visualLintIssueModel.issues
            .filterIsInstance<VisualLintRenderIssue>()
            .map { it.type to it.models.mapNotNull { m -> m.configuration.device?.displayName }.toSet() }
            .toSet()

        val baselineJob =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable),
            listOf(atfModel),
            emptyMap(),
          )
        withTimeout(60.seconds) {
          while (!baselineJob.isCompleted) {
            testScheduler.runCurrent()
            delay(10)
          }
        }
        baselineJob.join()
        assertFalse(baselineJob.isCancelled)
        val baseline = issueSignature()
        val firstDevice = ReferenceDevice.getWindowSizeDevices().first()
        assertTrue(baseline.any { firstDevice.displayName in it.second })

        val executor = RenderService.getRenderAsyncActionExecutor() as RenderExecutor
        val renderThreadBlocked = CountDownLatch(1)
        val releaseRenderThread = CountDownLatch(1)
        val blocker =
          executor.runAsyncActionWithTimeout(30, TimeUnit.SECONDS, 30, TimeUnit.SECONDS, RenderingTopic.NOT_SPECIFIED) {
            renderThreadBlocked.countDown()
            releaseRenderThread.await(30, TimeUnit.SECONDS)
          }

        val job =
          try {
            assertTrue(renderThreadBlocked.await(30, TimeUnit.SECONDS))
            val issueProvider = ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable)
            val analysisJob =
              visualLintService.runVisualLintAnalysis(
                projectRule.fixture.testRootDisposable,
                issueProvider,
                listOf(atfModel),
                emptyMap(),
              )
            testScheduler.runCurrent()
            waitForCondition(30.seconds) { executor.numPendingActions == 1 }

            // Cancels the queued VISUAL_LINT inflate and bumps modificationCount so the attempt must retry.
            atfModel.notifyModified(ChangeType.EDIT)
            analysisJob
          } finally {
            releaseRenderThread.countDown()
          }
        blocker.get(30, TimeUnit.SECONDS)

        withTimeout(60.seconds) {
          while (!job.isCompleted) {
            testScheduler.runCurrent()
            delay(10)
          }
        }
        job.join()

        assertFalse(job.isCancelled)
        assertEquals(baseline, issueSignature())
      } finally {
        serviceScope.cancel()
      }
    }
  }

  @Test
  fun removeAllIssueProvidersCancelsInFlightAnalysis() {
    projectRule.load("projects/visualLintApplication")
    runBlocking {
      val testScheduler = TestCoroutineScheduler()
      val testDispatcher = StandardTestDispatcher(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + testDispatcher)
      try {
        val visualLintService =
          VisualLintService.getInstanceForTest(
            project = projectRule.project,
            parentDisposable = projectRule.fixture.testRootDisposable,
            coroutineScope = serviceScope,
            visualLintDispatcher = testDispatcher,
            visualLintAnalyzerDispatcher = testDispatcher,
          )
        val visualLintIssueModel = visualLintService.issueModel

        val module = projectRule.getModule("app")
        val facet = AndroidFacet.getInstance(module)!!
        val dashboardLayout = projectRule.project.baseDir.findFileByRelativePath("app/src/main/res/layout/fragment_dashboard.xml")!!
        val dashboardModel =
          SyncNlModel.create(
            projectRule.fixture.testRootDisposable,
            NlComponentRegistrar,
            AndroidBuildTargetReference.gradleOnly(facet),
            dashboardLayout,
          )

        val issueProvider = ViewVisualLintIssueProvider(projectRule.fixture.testRootDisposable)
        val job =
          visualLintService.runVisualLintAnalysis(
            projectRule.fixture.testRootDisposable,
            issueProvider,
            listOf(dashboardModel),
            emptyMap(),
          )
        // Start the job so it suspends at renderFuture.await(), then clear all providers.
        testScheduler.runCurrent()

        visualLintService.removeAllIssueProviders()

        withTimeout(60.seconds) {
          while (!job.isCompleted) {
            testScheduler.runCurrent()
            delay(10)
          }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(visualLintIssueModel.issues.isEmpty())
        visualLintIssueModel.updateErrorsList()
        assertTrue(visualLintIssueModel.issues.isEmpty())
      } finally {
        serviceScope.cancel()
      }
    }
  }
}
