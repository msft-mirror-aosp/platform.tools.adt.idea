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
import com.android.tools.idea.insights.inspection.AppInsightsFilterSelector
import com.android.tools.idea.insights.inspection.ConnectionFilter
import com.android.tools.idea.insights.inspection.ConnectionPreference
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

  private val testDispatcher = UnconfinedTestDispatcher()
  private lateinit var controllerScope: CoroutineScope
  private lateinit var packageNameFlow: MutableSharedFlow<String?>

  private lateinit var fakeClient: FakeFindingsClient
  private lateinit var controller: FindingsProjectLevelControllerImpl

  @Before
  fun setUp() {
    controllerScope = CoroutineScope(testDispatcher + Job())
    packageNameFlow = MutableSharedFlow(replay = 1)
    fakeClient = FakeFindingsClient()
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
  fun testSetPackageName_fetchesFindingsAutomatically() {
    runBlocking {
      packageNameFlow.tryEmit(FakeFindingsClient.TEST_PACKAGE_NAME)

      val state = controller.state.value
      assertThat(state.packageName).isEqualTo(FakeFindingsClient.TEST_PACKAGE_NAME)
      @Suppress("UNCHECKED_CAST") val findings = (state.currentFindings as LoadingState.Ready<List<AppFinding>>).value
      assertThat(findings).hasSize(3)
    }
  }

  @Test
  fun testSetPackageName_callsClientFetch() {
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
      assertThat(findingsAfter).hasSize(2)
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
  fun testSetPackageName_cancelsInFlightRefresh() = runTest {
    val delayedClient =
      object : FindingsClient {
        override suspend fun fetchFindings(request: FetchFindingsRequest): LoadingState.Done<List<AppFinding>> {
          delay(1000L)
          return FakeFindingsClient().fetchFindings(request)
        }
      }
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
    assertThat(findingsAfter).hasSize(2)
    assertThat(findingsAfter.all { it.name.contains(FakeFindingsClient.OTHER_PACKAGE_NAME) }).isTrue()
  }

  @Test
  fun testRefresh_cancelsPreviousInFlightRefresh() = runTest {
    val delayedClient =
      object : FindingsClient {
        override suspend fun fetchFindings(request: FetchFindingsRequest): LoadingState.Done<List<AppFinding>> {
          delay(1000L)
          return FakeFindingsClient().fetchFindings(request)
        }
      }
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
    assertThat(findings).hasSize(2)
  }

  @Test
  fun testRefresh_reconcilesSelectedFinding() = runTest {
    val mockClient = mock<FindingsClient>()
    val allFindings = (fakeClient.fetchFindings(FetchFindingsRequest(FakeFindingsClient.TEST_PACKAGE_NAME)) as LoadingState.Ready).value
    val firstFinding = allFindings.first()

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

    // Now refresh when firstFinding is no longer in the returned list
    whenever(mockClient.fetchFindings(any())).thenReturn(LoadingState.Ready(allFindings.drop(1)))
    testController.refresh()
    testScheduler.advanceUntilIdle()

    assertThat(testController.state.value.selectedFinding).isNull()
  }

  @Test
  fun testRefresh_preservesAndUpdatesSelectedFindingWhenStillPresent() = runTest {
    val mockClient = mock<FindingsClient>()
    val allFindings = (fakeClient.fetchFindings(FetchFindingsRequest(FakeFindingsClient.TEST_PACKAGE_NAME)) as LoadingState.Ready).value
    val firstFinding = allFindings.first() as AppFinding.Supported
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
