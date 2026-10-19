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

import com.android.tools.idea.insights.AppInsightsModel
import com.google.common.truth.Truth.assertThat
import com.google.gct.login2.CredentialedUser
import com.google.gct.login2.GoogleLoginService
import com.google.gct.login2.fstLoginFeature
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FindingsConfigurationManagerTest {

  @get:Rule val applicationRule = ApplicationRule()

  private val testDispatcher = UnconfinedTestDispatcher()
  private val testScope = CoroutineScope(testDispatcher + Job())

  private lateinit var project: Project
  private lateinit var controller: FindingsProjectLevelController
  private val isLoggedInFlow = MutableStateFlow(false)
  private val activeUserFlow = MutableStateFlow<CredentialedUser?>(null)

  @Before
  fun setUp() {
    project = mock()
    controller = mock()
  }

  @After
  fun tearDown() {
    testScope.cancel()
  }

  private fun createManager(): FindingsConfigurationManager {
    return FindingsConfigurationManager(
      project = project,
      controller = controller,
      isLoggedInFlow = isLoggedInFlow,
      scope = testScope,
    )
  }

  private fun createLoginService(vararg authorizedEmails: String): GoogleLoginService {
    val service = mock<GoogleLoginService>()
    whenever(service.activeUserFlow).thenReturn(activeUserFlow)
    whenever(service.isLoggedIn(any<String>(), eq(fstLoginFeature))).thenAnswer { invocation ->
      invocation.getArgument<String>(0) in authorizedEmails
    }
    return service
  }

  private fun createUser(email: String): CredentialedUser {
    val user = mock<CredentialedUser>()
    whenever(user.email).thenReturn(email)
    return user
  }

  @Test
  fun `initial emission when logged in emits Authenticated`() = runTest {
    isLoggedInFlow.value = true

    val manager = createManager()

    assertThat(manager.configuration.value).isInstanceOf(AppInsightsModel.Authenticated::class.java)
  }

  @Test
  fun `initial emission when not logged in emits Unauthenticated`() = runTest {
    isLoggedInFlow.value = false

    val manager = createManager()

    assertThat(manager.configuration.value).isEqualTo(AppInsightsModel.Unauthenticated)
  }

  @Test
  fun `logging out transitions to Unauthenticated`() = runTest {
    isLoggedInFlow.value = true

    val manager = createManager()
    assertThat(manager.configuration.value).isInstanceOf(AppInsightsModel.Authenticated::class.java)

    isLoggedInFlow.value = false

    assertThat(manager.configuration.value).isEqualTo(AppInsightsModel.Unauthenticated)
  }

  @Test
  fun `logging in after being logged out transitions to Authenticated`() = runTest {
    isLoggedInFlow.value = false

    val manager = createManager()
    assertThat(manager.configuration.value).isEqualTo(AppInsightsModel.Unauthenticated)

    isLoggedInFlow.value = true

    assertThat(manager.configuration.value).isInstanceOf(AppInsightsModel.Authenticated::class.java)
  }

  @Test
  fun `isPlayConsoleLoggedInFlow emits true when active user has play console access`() = runTest {
    val service = createLoginService("user1@example.com")
    activeUserFlow.value = createUser("user1@example.com")

    assertThat(isPlayConsoleLoggedInFlow(service).first()).isTrue()
  }

  @Test
  fun `isPlayConsoleLoggedInFlow emits false when active user lacks play console access`() = runTest {
    val service = createLoginService()
    activeUserFlow.value = createUser("user1@example.com")

    assertThat(isPlayConsoleLoggedInFlow(service).first()).isFalse()
  }

  @Test
  fun `isPlayConsoleLoggedInFlow emits false when no active user`() = runTest {
    val service = createLoginService("user1@example.com")
    activeUserFlow.value = null

    assertThat(isPlayConsoleLoggedInFlow(service).first()).isFalse()
  }

  @Test
  fun `isPlayConsoleLoggedInFlow evaluates authorization for the emitted user`() = runTest {
    val service = createLoginService("user1@example.com")
    val flow = isPlayConsoleLoggedInFlow(service)

    activeUserFlow.value = createUser("user1@example.com")
    assertThat(flow.first()).isTrue()

    activeUserFlow.value = createUser("user2@example.com")
    assertThat(flow.first()).isFalse()
  }
}
