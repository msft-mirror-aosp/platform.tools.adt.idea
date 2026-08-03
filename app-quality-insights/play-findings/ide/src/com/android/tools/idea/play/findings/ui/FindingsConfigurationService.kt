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

import com.android.tools.idea.findings.client.StudioFindingsClient
import com.android.tools.idea.insights.AppInsightsConfigurationManager
import com.android.tools.idea.insights.AppInsightsModel
import com.android.tools.idea.insights.OfflineStatusManagerImpl
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.fstLoginFeature
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Project-level service that manages the lifecycle of the [FindingsConfigurationManager] and the [FindingsProjectLevelController]. */
@Service(Service.Level.PROJECT)
class FindingsConfigurationService(project: Project, scope: CoroutineScope) : Disposable {

  private val controller: FindingsProjectLevelController =
    FindingsProjectLevelControllerImpl(
      project = project,
      client = StudioFindingsClient.create(this),
      scope = scope,
    )

  /** The [AppInsightsConfigurationManager] instance managing authentication and configuration state. */
  val manager: AppInsightsConfigurationManager =
    FindingsConfigurationManager(
      project = project,
      controller = controller,
      scope = scope,
    )

  override fun dispose() = Unit

  companion object {
    fun getInstance(project: Project): FindingsConfigurationService = project.service()
  }
}

/** Emits `true` whenever there is an active user signed in with Play Console access ([fstLoginFeature]), and `false` otherwise. */
internal fun isPlayConsoleLoggedInFlow(loginService: GoogleLoginService): Flow<Boolean> =
  loginService.activeUserFlow.map { user ->
    user != null && loginService.isLoggedIn(user.email, fstLoginFeature)
  }

/**
 * Manages configuration and authentication state for Play Console Findings.
 *
 * Exposes a [configuration] [StateFlow] that transitions between [AppInsightsModel.Authenticated] and [AppInsightsModel.Unauthenticated]
 * based on Google login state.
 *
 * @property project The active project.
 * @param controller The [FindingsProjectLevelController] provided when authenticated.
 * @param isLoggedInFlow Flow emitting whether the active account is logged in with Play Console access.
 * @param scope The [CoroutineScope] used to manage internal coroutines and StateFlows.
 */
class FindingsConfigurationManager(
  override val project: Project,
  controller: FindingsProjectLevelController,
  isLoggedInFlow: Flow<Boolean> = isPlayConsoleLoggedInFlow(GoogleLoginService.instance),
  scope: CoroutineScope,
) : AppInsightsConfigurationManager {

  override val offlineStatusManager = OfflineStatusManagerImpl()

  override val configuration: StateFlow<AppInsightsModel> =
    isLoggedInFlow
      .map { isLoggedIn ->
        if (isLoggedIn) {
          AppInsightsModel.Authenticated(controller)
        } else {
          AppInsightsModel.Unauthenticated
        }
      }
      .stateIn(scope, SharingStarted.Eagerly, AppInsightsModel.Uninitialized)
}
