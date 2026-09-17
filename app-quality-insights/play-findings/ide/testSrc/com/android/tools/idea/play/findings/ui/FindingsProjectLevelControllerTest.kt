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
package com.android.tools.idea.play.findings.ui

import com.android.tools.idea.findings.client.FetchFindingsRequest
import com.android.tools.idea.findings.client.FindingsClient
import com.android.tools.idea.findings.client.utils.FakeFindingsClient
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.insights.InsightsProvider.Source
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.ai.AiInsight
import com.android.tools.idea.insights.experiments.InsightFeedback
import com.android.tools.idea.insights.ui.AppInsightsFilterSelector
import com.android.tools.idea.insights.ui.ConnectionFilter
import com.android.tools.idea.insights.ui.ConnectionPreference
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ProjectRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val PLAY_APP = "com.example.play"
private const val OTHER_PLAY_APP = "com.example.other"
private const val FIREBASE_APP = "com.example.firebase"

@OptIn(ExperimentalCoroutinesApi::class)
class FindingsProjectLevelControllerTest {

  @get:Rule val projectRule = ProjectRule()

  private lateinit var fakeClient: FindingsClient
  private lateinit var controllerScope: CoroutineScope
  private lateinit var packageNameFlow: MutableSharedFlow<String?>
  private lateinit var controller: FindingsProjectLevelController

  @Before
  fun setUp() {
    fakeClient = FakeFindingsClient(delayMs = 0L)
    controllerScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
    packageNameFlow = MutableSharedFlow(replay = 1)
    controller =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = fakeClient,
        scope = controllerScope,
        packageNameFlow = packageNameFlow,
      )
  }

  @After
  fun tearDown() {
    controllerScope.cancel()
  }

  @Test
  fun testInitialState() {
    assertThat(controller.provider).isEqualTo(PlayFindingsInsightsProvider)
    assertThat(controller.connections.value.selected).isNull()
    val state = controller.state.value
    assertThat(state.packageName).isNull()
    assertThat(state.selectedFinding).isNull()
  }

  @Test
  fun testSetPackageName_updatesStateAndFetchesFindings() {
    runBlocking {
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)

      val state = controller.state.value
      assertThat(state.packageName).isEqualTo(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST") val findings = (state.currentFindings as LoadingState.Ready<List<AppFinding>>).value
      assertThat(findings).hasSize(12)
    }
  }

  @Test
  fun testSetPackageName_twiceWithSamePackage_doesNotFetchAgain() {
    runBlocking {
      val mockClient = mock<FindingsClient>()
      whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(emptyList()))
      val controllerWithMock =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = mockClient,
          scope = controllerScope,
          packageNameFlow = packageNameFlow,
        )

      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)

      verify(mockClient).fetchFindings(FetchFindingsRequest(FakeFindingsClient.TEST_PACKAGE_NAME))
      assertThat(controllerWithMock.state.value.packageName).isEqualTo(FakeFindingsClient.TEST_PACKAGE_NAME)
    }
  }

  @Test
  fun testSelectFinding() {
    runBlocking {
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      val state = controller.state.value
      @Suppress("UNCHECKED_CAST") val finding = (state.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()

      controller.selectFinding(finding)
      assertThat(controller.state.value.selectedFinding).isEqualTo(finding)

      controller.selectFinding(null)
      assertThat(controller.state.value.selectedFinding).isNull()
    }
  }

  @Test
  fun testRefresh_fetchesFindingsForCurrentPackage() {
    runBlocking {
      packageNameFlow.tryEmit(FakeFindingsClient.OTHER_PACKAGE_NAME)

      val stateAfter = controller.state.value
      assertThat(stateAfter.packageName).isEqualTo(FakeFindingsClient.OTHER_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST") val findingsAfter = (stateAfter.currentFindings as LoadingState.Ready<List<AppFinding>>).value
      assertThat(findingsAfter).hasSize(4)
    }
  }

  @Test
  fun testRefresh_whenPackageNameIsEmpty_doesNotFetch() {
    runBlocking {
      val mockClient = mock<FindingsClient>()
      val controllerWithMock =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = mockClient,
          scope = controllerScope,
          packageNameFlow = packageNameFlow,
        )

      controllerWithMock.refresh()

      verify(mockClient, never()).fetchFindings(any())
    }
  }

  @Test
  fun testClientError_emitsFailureState() {
    runBlocking {
      val mockClient = mock<FindingsClient>()
      val expectedFailure = LoadingState.UnknownFailure("Failed to fetch findings", Throwable("Failed to fetch findings"))
      whenever(mockClient.fetchFindings(any())).thenReturn(expectedFailure)

      val controllerWithMock =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = mockClient,
          scope = controllerScope,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit("com.example.app")
      controllerWithMock.refresh()

      val state = controllerWithMock.state.value
      assertThat(state.packageName).isEqualTo("com.example.app")
      assertThat(state.currentFindings).isEqualTo(expectedFailure)
    }
  }

  @Test
  fun testRefreshInsight_fetchesInsightForSelectedFinding() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      val fakeInsight = AiInsight("Test insight")
      whenever(mockToolkit.fetchInsight(any(), any())).thenReturn(LoadingState.Ready(fakeInsight))

      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      controllerWithToolkit.refresh()
      @Suppress("UNCHECKED_CAST")
      val finding = (controllerWithToolkit.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
      controllerWithToolkit.selectFinding(finding)

      controllerWithToolkit.refreshInsight(false)

      val currentInsight = controllerWithToolkit.state.value.currentInsight
      assertThat(currentInsight).isInstanceOf(LoadingState.Ready::class.java)
      assertThat((currentInsight as LoadingState.Ready<*>).value).isEqualTo(fakeInsight)
    }
  }

  @Test
  fun testRefreshInsight_whenNoFindingSelected_doesNotFetchInsight() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      controllerWithToolkit.refreshInsight(false)

      verify(mockToolkit, never()).fetchInsight(any(), any())
      assertThat(controllerWithToolkit.state.value.currentInsight).isNull()
    }
  }

  @Test
  fun testSelectFinding_setsInsightLoadingAndClearingSelectionClearsInsight() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      whenever(mockToolkit.fetchInsight(any(), any())).thenReturn(LoadingState.Ready(AiInsight("Test insight")))
      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      assertThat(controllerWithToolkit.state.value.currentInsight).isNull()

      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST")
      val finding = (controllerWithToolkit.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
      controllerWithToolkit.selectFinding(finding)
      assertThat(controllerWithToolkit.state.value.currentInsight).isEqualTo(LoadingState.Ready(AiInsight("Test insight")))

      controllerWithToolkit.selectFinding(null)
      assertThat(controllerWithToolkit.state.value.currentInsight).isNull()
    }
  }

  @Test
  fun testSwitchingApps_clearsSelectionAndInsight() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      whenever(mockToolkit.fetchInsight(any(), any())).thenReturn(LoadingState.Ready(AiInsight("Test insight")))
      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST")
      val finding = (controllerWithToolkit.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
      controllerWithToolkit.selectFinding(finding)

      packageNameFlow.tryEmit(FakeFindingsClient.OTHER_PACKAGE_NAME)

      val state = controllerWithToolkit.state.value
      assertThat(state.packageName).isEqualTo(FakeFindingsClient.OTHER_PACKAGE_NAME)
      assertThat(state.selectedFinding).isNull()
      assertThat(state.currentInsight).isNull()
    }
  }

  @Test
  fun testSelectDifferentFinding_resetsCurrentInsightAtomically() = runTest {
    val mockToolkit = mock<FindingsInsightToolkit>()
    val firstInsight = AiInsight("First insight")
    val secondInsight = AiInsight("Second insight")

    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = FakeFindingsClient(delayMs = 0L),
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        aiInsightToolkit = mockToolkit,
        packageNameFlow = packageNameFlow,
      )
    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
    testScheduler.advanceUntilIdle()

    @Suppress("UNCHECKED_CAST") val findings = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value
    whenever(mockToolkit.fetchInsight(findings[0], false)).thenReturn(LoadingState.Ready(firstInsight))
    whenever(mockToolkit.fetchInsight(findings[1], false)).thenReturn(LoadingState.Ready(secondInsight))

    testController.selectFinding(findings[0])
    testScheduler.advanceUntilIdle()
    assertThat(testController.state.value.currentInsight).isEqualTo(LoadingState.Ready(firstInsight))

    // Selecting findings[1] immediately resets currentInsight to Loading before the coroutine scheduler dispatches Pipeline 2
    testController.selectFinding(findings[1])
    assertThat(testController.state.value.selectedFinding).isEqualTo(findings[1])
    assertThat(testController.state.value.currentInsight).isEqualTo(LoadingState.Loading("Fetching insights..."))

    testScheduler.advanceUntilIdle()
    assertThat(testController.state.value.currentInsight).isEqualTo(LoadingState.Ready(secondInsight))
  }

  @Test
  fun testForceRefreshInsight_doesNotCarryOverToNextSelectedFinding() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      val fakeInsight = AiInsight("Regenerated insight")
      whenever(mockToolkit.fetchInsight(any(), org.mockito.kotlin.eq(false))).thenReturn(LoadingState.InsightAutogenerateDisabled)
      whenever(mockToolkit.fetchInsight(any(), org.mockito.kotlin.eq(true))).thenReturn(LoadingState.Ready(fakeInsight))

      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      controllerWithToolkit.refresh()
      @Suppress("UNCHECKED_CAST")
      val findings = (controllerWithToolkit.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value

      // 1. Select first finding while auto-generate is disabled -> InsightAutogenerateDisabled
      controllerWithToolkit.selectFinding(findings[0])
      assertThat(controllerWithToolkit.state.value.currentInsight).isEqualTo(LoadingState.InsightAutogenerateDisabled)

      // 2. User manually clicks "Generate insight for this finding" (forceGenerateNewInsight = true)
      controllerWithToolkit.refreshInsight(forceGenerateNewInsight = true)
      assertThat(controllerWithToolkit.state.value.currentInsight).isEqualTo(LoadingState.Ready(fakeInsight))

      // 3. Selecting a different finding should NOT inherit forceGenerateNewInsight = true
      controllerWithToolkit.selectFinding(findings[1])
      assertThat(controllerWithToolkit.state.value.currentInsight).isEqualTo(LoadingState.InsightAutogenerateDisabled)
    }
  }

  @Test
  fun testSubmitInsightFeedback_updatesCurrentInsightFeedback() {
    runBlocking {
      val mockToolkit = mock<FindingsInsightToolkit>()
      val fakeInsight = AiInsight("Test insight")
      whenever(mockToolkit.fetchInsight(any(), any())).thenReturn(LoadingState.Ready(fakeInsight))

      val controllerWithToolkit =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          aiInsightToolkit = mockToolkit,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST")
      val finding = (controllerWithToolkit.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
      controllerWithToolkit.selectFinding(finding)

      controllerWithToolkit.submitInsightFeedback(InsightFeedback.THUMBS_UP)

      val updatedInsight = (controllerWithToolkit.state.value.currentInsight as LoadingState.Ready<AiInsight>).value
      assertThat(updatedInsight.feedback).isEqualTo(InsightFeedback.THUMBS_UP)
    }
  }

  @Test
  fun testSetPackageName_cancelsInFlightRefresh() = runTest {
    val delayedClient = FakeFindingsClient(delayMs = 1000L)
    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = delayedClient,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        packageNameFlow = packageNameFlow,
      )

    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)

    testScheduler.advanceTimeBy(100)
    assertThat(testController.state.value.currentFindings).isEqualTo(LoadingState.Loading)

    // Change package at t=100ms while TEST_PACKAGE_NAME refresh is in-flight
    packageNameFlow.tryEmit(FakeFindingsClient.OTHER_PACKAGE_NAME)
    testScheduler.runCurrent()
    assertThat(testController.state.value.packageName).isEqualTo(FakeFindingsClient.OTHER_PACKAGE_NAME)
    assertThat(testController.state.value.currentFindings).isEqualTo(LoadingState.Loading)

    // At t=1050ms (past the t=1000ms mark of the first request), it should still be loading OTHER_PACKAGE_NAME (which finishes at t=1100ms)
    testScheduler.advanceTimeBy(950)
    assertThat(testController.state.value.currentFindings).isEqualTo(LoadingState.Loading)

    // Advance past t=1100ms so OTHER_PACKAGE_NAME fetch completes
    testScheduler.advanceTimeBy(200)
    assertThat(testController.state.value.packageName).isEqualTo(FakeFindingsClient.OTHER_PACKAGE_NAME)
    @Suppress("UNCHECKED_CAST")
    val findingsAfter = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value
    assertThat(findingsAfter).hasSize(4)
    assertThat(findingsAfter.all { it.name.contains(FakeFindingsClient.OTHER_PACKAGE_NAME) }).isTrue()
  }

  @Test
  fun testRefresh_cancelsPreviousInFlightRefresh() = runTest {
    val delayedClient = FakeFindingsClient(delayMs = 1000L)
    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = delayedClient,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        packageNameFlow = packageNameFlow,
      )

    // Request 1 starts at t=0, would naturally finish at t=1000
    packageNameFlow.tryEmit(FakeFindingsClient.OTHER_PACKAGE_NAME)

    testScheduler.advanceTimeBy(100)
    assertThat(testController.state.value.currentFindings).isEqualTo(LoadingState.Loading)

    // Trigger another refresh at t=100. Request 2 would naturally finish at t=1100
    testController.refresh()

    // Advance to t=1050 (past the time Request 1 would have finished).
    // If Request 1 was NOT cancelled, it would have fired and changed the state to Ready.
    testScheduler.advanceTimeBy(950)
    assertThat(testController.state.value.currentFindings).isEqualTo(LoadingState.Loading)

    // Advance to t=1150 (past the time Request 2 finishes at t=1100)
    testScheduler.advanceTimeBy(100)
    assertThat(testController.state.value.currentFindings).isInstanceOf(LoadingState.Ready::class.java)
    @Suppress("UNCHECKED_CAST") val findings = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value
    assertThat(findings).hasSize(4)
  }

  @Test
  fun testRefreshInsight_forceGenerate_emitsRegeneratingLoadingState() = runTest {
    val fakeInsight = AiInsight("Regenerated insight")

    // An interface object allows us to insert a native coroutine delay to test the loading state
    val delayedToolkit =
      object : FindingsInsightToolkit(projectRule.project) {
        override suspend fun fetchInsight(
          finding: AppFinding,
          forceGenerateNewInsight: Boolean,
        ): LoadingState.Done<AiInsight> {
          delay(100)
          return LoadingState.Ready(fakeInsight)
        }
      }

    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = FakeFindingsClient(delayMs = 0L),
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        aiInsightToolkit = delayedToolkit,
        packageNameFlow = packageNameFlow,
      )

    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
    testController.refresh()
    testScheduler.advanceUntilIdle()

    @Suppress("UNCHECKED_CAST")
    val finding = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
    testController.selectFinding(finding)
    testScheduler.advanceUntilIdle()

    testController.refreshInsight(forceGenerateNewInsight = true)
    testScheduler.runCurrent() // Runs up until the 100ms suspension in the mock toolkit

    val loadingState = testController.state.value.currentInsight
    assertThat(loadingState).isEqualTo(LoadingState.Loading("Regenerating insight..."))

    testScheduler.advanceUntilIdle() // Finish the 100ms suspension

    val finalState = testController.state.value.currentInsight
    assertThat(finalState).isEqualTo(LoadingState.Ready(fakeInsight))
  }

  @Test
  fun testRefresh_reconcilesSelectedFinding() = runTest {
    val mockClient = mock<FindingsClient>()
    val allFindings = (fakeClient.fetchFindings(FetchFindingsRequest(FakeFindingsClient.TEST_PACKAGE_NAME)) as LoadingState.Ready).value
    val finding = allFindings.last()

    whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(allFindings))
    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = mockClient,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        packageNameFlow = packageNameFlow,
      )

    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
    testScheduler.advanceUntilIdle()

    testController.selectFinding(finding)
    assertThat(testController.state.value.selectedFinding).isEqualTo(finding)

    // Now refresh when finding is no longer in the returned list
    whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(allFindings.dropLast(1)))
    testController.refresh()
    testScheduler.advanceUntilIdle()

    assertThat(testController.state.value.selectedFinding).isNull()
  }

  @Test
  fun testRefresh_preservesAndUpdatesSelectedFindingWhenStillPresent() = runTest {
    val mockClient = mock<FindingsClient>()
    val allFindings = (fakeClient.fetchFindings(FetchFindingsRequest(FakeFindingsClient.TEST_PACKAGE_NAME)) as LoadingState.Ready).value
    val firstFinding = allFindings.first()
    val updatedFirstFinding = firstFinding.copy(affectedScopes = emptyList())

    whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(allFindings))
    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = mockClient,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        packageNameFlow = packageNameFlow,
      )

    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
    testScheduler.advanceUntilIdle()

    testController.selectFinding(firstFinding)
    assertThat(testController.state.value.selectedFinding).isEqualTo(firstFinding)

    // Refresh when firstFinding still exists by name in the returned list with updated fields
    whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(listOf(updatedFirstFinding) + allFindings.drop(1)))
    testController.refresh()
    testScheduler.advanceUntilIdle()

    assertThat(testController.state.value.selectedFinding).isEqualTo(updatedFirstFinding)
  }

  @Test
  fun testSwitchApp_clearsSelectedFinding() = runTest {
    val testController =
      FindingsProjectLevelControllerImpl(
        projectRule.project,
        client = fakeClient,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        packageNameFlow = packageNameFlow,
      )

    packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
    testScheduler.advanceUntilIdle()

    @Suppress("UNCHECKED_CAST")
    val finding = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
    testController.selectFinding(finding)
    assertThat(testController.state.value.selectedFinding).isEqualTo(finding)

    packageNameFlow.tryEmit(FakeFindingsClient.OTHER_PACKAGE_NAME)
    testScheduler.advanceUntilIdle()

    assertThat(testController.state.value.packageName).isEqualTo(FakeFindingsClient.OTHER_PACKAGE_NAME)
    assertThat(testController.state.value.selectedFinding).isNull()
  }

  @Test
  fun testSetPackageName_null_showsEmptyStateAndDoesNotFetch() {
    runBlocking {
      val mockClient = mock<FindingsClient>()
      val testController =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = mockClient,
          scope = controllerScope,
          packageNameFlow = packageNameFlow,
        )

      packageNameFlow.tryEmit(null)

      verify(mockClient, never()).fetchFindings(any())
      val state = testController.state.value
      assertThat(state.packageName).isNull()
      assertThat(state.selectedFinding).isNull()
      assertThat(state.currentFindings).isEqualTo(LoadingState.Ready(emptyList<AppFinding>()))
    }
  }

  @Test
  fun testSetPackageName_null_clearsPreviouslyLoadedFindings() {
    runBlocking {
      val testController =
        FindingsProjectLevelControllerImpl(
          projectRule.project,
          client = fakeClient,
          scope = controllerScope,
          packageNameFlow = packageNameFlow,
        )
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST")
      val finding = (testController.state.value.currentFindings as LoadingState.Ready<List<AppFinding>>).value.first()
      testController.selectFinding(finding)

      packageNameFlow.tryEmit(null)

      val state = testController.state.value
      assertThat(state.packageName).isNull()
      assertThat(state.selectedFinding).isNull()
      assertThat(state.currentFindings).isEqualTo(LoadingState.Ready(emptyList<AppFinding>()))
    }
  }

  @Test
  fun `playConnectedPackageNameFlow emits selected Play app`() = runTest {
    val (filterSelector, connectedApps, selectedAppId) = createFilterSelectorFixture()
    connectedApps.value = listOf(playApp(PLAY_APP))
    selectedAppId.value = PLAY_APP

    assertThat(playConnectedPackageNameFlow(filterSelector).first()).isEqualTo(PLAY_APP)
  }

  @Test
  fun `playConnectedPackageNameFlow clears selection that is no longer connected`() = runTest {
    val (filterSelector, connectedApps, selectedAppId) = createFilterSelectorFixture()
    connectedApps.value = listOf(playApp(PLAY_APP))
    selectedAppId.value = PLAY_APP

    val emissions = mutableListOf<String?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      playConnectedPackageNameFlow(filterSelector).collect { emissions += it }
    }
    assertThat(emissions).containsExactly(PLAY_APP)

    // Signing into a different account leaves the stale selection pointing at an app that is no longer connected.
    connectedApps.value = listOf(playApp(OTHER_PLAY_APP))
    assertThat(emissions).containsExactly(PLAY_APP, null).inOrder()
  }

  @Test
  fun `playConnectedPackageNameFlow resolves to null when selection is not connected to Play`() = runTest {
    val (filterSelector, connectedApps, selectedAppId) = createFilterSelectorFixture()
    connectedApps.value = listOf(firebaseApp(FIREBASE_APP))
    selectedAppId.value = FIREBASE_APP

    assertThat(playConnectedPackageNameFlow(filterSelector).first()).isNull()
  }

  @Test
  fun `playConnectedPackageNameFlow switching to non-Play app emits null`() = runTest {
    val (filterSelector, connectedApps, selectedAppId) = createFilterSelectorFixture()
    connectedApps.value = listOf(playApp(PLAY_APP), firebaseApp(FIREBASE_APP))
    selectedAppId.value = PLAY_APP

    val emissions = mutableListOf<String?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      playConnectedPackageNameFlow(filterSelector).collect { emissions += it }
    }
    assertThat(emissions).containsExactly(PLAY_APP)

    selectedAppId.value = FIREBASE_APP
    assertThat(emissions).containsExactly(PLAY_APP, null).inOrder()
  }

  @Test
  fun `playConnectedPackageNameFlow resolves to null when nothing is selected`() = runTest {
    val (filterSelector, _, _) = createFilterSelectorFixture()

    assertThat(playConnectedPackageNameFlow(filterSelector).first()).isNull()
  }

  private fun createFilterSelectorFixture():
    Triple<AppInsightsFilterSelector, MutableStateFlow<List<ConnectionFilter>>, MutableStateFlow<String?>> {
    val connectedApps = MutableStateFlow<List<ConnectionFilter>>(emptyList())
    val selectedAppId = MutableStateFlow<String?>(null)
    val filterSelector = mock<AppInsightsFilterSelector>()
    whenever(filterSelector.filters).thenReturn(connectedApps)
    whenever(filterSelector.selectedAppId).thenReturn(selectedAppId)
    return Triple(filterSelector, connectedApps, selectedAppId)
  }

  private fun playApp(appId: String) = ConnectionFilter(appId, appId, mapOf(Source.PLAY to ConnectionPreference.PREFERRED))

  private fun firebaseApp(appId: String) = ConnectionFilter(appId, appId, mapOf(Source.FIREBASE to ConnectionPreference.PREFERRED))
}
