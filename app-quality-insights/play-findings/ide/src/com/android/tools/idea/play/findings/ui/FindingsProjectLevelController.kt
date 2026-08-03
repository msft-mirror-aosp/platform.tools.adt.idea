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
import com.android.tools.idea.findings.model.AppFinding
import com.android.tools.idea.insights.AppInsightsProjectLevelController
import com.android.tools.idea.insights.InsightsProvider
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.inspection.AppInsightsFilterSelector
import com.android.tools.idea.insights.model.connection.Connection
import com.android.tools.idea.play.findings.PlayFindingsInsightsProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Emits the selected app's package name if it is connected to Play Console, or `null` otherwise.
 *
 * Combines [AppInsightsFilterSelector.selectedAppId] with [AppInsightsFilterSelector.filters] (connected apps) so the selection is
 * re-evaluated whenever connections load or change.
 */
internal fun playConnectedPackageNameFlow(filterSelector: AppInsightsFilterSelector): Flow<String?> =
  combine(filterSelector.selectedAppId, filterSelector.filters) { appId, connectedApps ->
    appId?.takeIf { selected ->
      connectedApps.any { it.appId == selected && it.preferences.containsKey(InsightsProvider.Source.PLAY) }
    }
  }

/**
 * Project-level controller for Play Console Findings integration in App Quality Insights.
 *
 * Manages findings state, package name updates, and active finding selection for the project.
 */
interface FindingsProjectLevelController : AppInsightsProjectLevelController {
  /** The observable [FindingsState] representing the currently loaded findings and selection. */
  val state: StateFlow<FindingsState>

  /**
   * Selects an [AppFinding] to be viewed in detail, or null to clear the selection.
   *
   * @param finding The finding to select, or null to deselect.
   */
  fun selectFinding(finding: AppFinding?)
}

/** The terminal empty state, used when there is no app selected. */
private val NO_APP_SELECTED_STATE = FindingsState(currentFindings = LoadingState.Ready(emptyList()), packageName = null)

/**
 * Default implementation of [FindingsProjectLevelController].
 *
 * Coordinates fetching findings from the [client] in the given coroutine [scope].
 *
 * @param project The active [Project].
 * @param client The [FindingsClient] used to retrieve findings.
 * @param scope The [CoroutineScope] used for asynchronous operations.
 * @param packageNameFlow Reactive stream of the selected Play Console package name, or null when no Play app is selected.
 */
class FindingsProjectLevelControllerImpl(
  project: Project,
  client: FindingsClient,
  scope: CoroutineScope,
  packageNameFlow: Flow<String?> = playConnectedPackageNameFlow(project.service<AppInsightsFilterSelector>()),
) : FindingsProjectLevelController {
  override val provider: InsightsProvider = PlayFindingsInsightsProvider
  override val connections: StateFlow<Selection<Connection>> = MutableStateFlow(Selection.emptySelection<Connection>()).asStateFlow()

  private val _state = MutableStateFlow(FindingsState(packageName = null))
  override val state: StateFlow<FindingsState> = _state.asStateFlow()

  private val forceRefreshTrigger =
    MutableSharedFlow<Unit>(
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  init {
    // Monitors the active package name and force refresh triggers. `collectLatest` cancels an in-flight fetch whenever a new emission
    // arrives.
    scope.launch {
      combine(
          packageNameFlow.distinctUntilChanged(),
          forceRefreshTrigger.onStart { emit(Unit) },
        ) { packageName, _ ->
          packageName
        }
        .collectLatest { packageName ->
          if (packageName == null) {
            _state.value = NO_APP_SELECTED_STATE
            return@collectLatest
          }

          // Preserve the current selection when refreshing the same app; clear it when switching apps.
          _state.update { currentState ->
            FindingsState(
              currentFindings = LoadingState.Loading,
              selectedFinding = currentState.selectedFinding.takeIf { currentState.packageName == packageName },
              packageName = packageName,
            )
          }

          // Fetch findings asynchronously. If `packageName` changes while fetching,
          // `collectLatest` cancels this coroutine naturally without leaking state updates.
          val result = client.fetchFindings(FetchFindingsRequest(packageName))

          _state.update { currentState ->
            // Attempt to restore the user's previously selected finding using its name as a stable identifier.
            val updatedSelection =
              when (result) {
                is LoadingState.Ready ->
                  currentState.selectedFinding?.let { current ->
                    result.value.find { it.name == current.name }
                  }
                else -> currentState.selectedFinding
              }
            currentState.copy(currentFindings = result, selectedFinding = updatedSelection)
          }
        }
    }
  }

  override fun selectFinding(finding: AppFinding?) {
    _state.update { it.copy(selectedFinding = finding) }
  }

  override fun refresh() {
    forceRefreshTrigger.tryEmit(Unit)
  }
}
