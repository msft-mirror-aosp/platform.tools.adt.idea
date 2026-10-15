/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.profilers

import com.android.ddmlib.IDevice
import com.android.ide.common.repository.GoogleMavenArtifactId
import com.android.tools.idea.execution.common.AndroidSessionInfo
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.project.AndroidNotification
import com.android.tools.idea.project.hyperlink.NotificationHyperlink
import com.android.tools.idea.projectsystem.AndroidModuleSystem
import com.android.tools.idea.projectsystem.AndroidProjectSystem
import com.android.tools.idea.projectsystem.DependencyType
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager
import com.android.tools.idea.projectsystem.RegisteredDependencyCompatibilityResult
import com.android.tools.idea.projectsystem.RegisteredDependencyId
import com.android.tools.idea.projectsystem.RegisteredDependencyQueryId
import com.android.tools.idea.projectsystem.RegisteringModuleSystem
import com.android.tools.idea.run.AndroidRunConfigurationBase
import com.android.tools.idea.run.AndroidRunConfigurationModule
import com.android.tools.idea.run.profiler.CpuProfilerConfig
import com.android.tools.idea.run.profiler.CpuProfilerConfigsState
import com.android.tools.nativeSymbolizer.SymbolFilesLocator
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.actions.RevealFileAction
import com.intellij.mock.MockProjectEx
import com.intellij.mock.MockPsiManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.EmptyModuleManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class IntellijProfilerServicesTest {

  private lateinit var project: Project
  private lateinit var intellijProfilerServices: IntellijProfilerServices

  @get:Rule val applicationRule = ApplicationRule()

  @get:Rule val disposableRule = DisposableRule()

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val spiesToDispose = mutableListOf<IntellijProfilerServices>()

  @After
  fun tearDownAfterTest() {
    Disposer.dispose(intellijProfilerServices)
    for (spy in spiesToDispose) {
      Disposer.dispose(spy)
    }
  }

  @Before
  fun before() {
    StudioFlags.PROFILER_LEAKCANARY.override(false)
    project = Mockito.spy(MockProjectEx(disposableRule.disposable))
    mockProjectAttributes(project)
    intellijProfilerServices = IntellijProfilerServices(project, Mockito.mock(SymbolFilesLocator::class.java))
    Disposer.register(disposableRule.disposable, intellijProfilerServices)
  }

  @After
  fun after() {
    Disposer.dispose(intellijProfilerServices)
  }

  companion object {
    @JvmStatic
    @AfterClass
    fun tearDown() {
      StudioFlags.PROFILER_TRACEBOX.clearOverride()
      StudioFlags.PROFILER_TASK_BASED_UX.clearOverride()
      StudioFlags.PROFILER_LEAKCANARY.clearOverride()
    }
  }

  @Test
  fun featureFlagConfigTraceBoxEnabled() {
    StudioFlags.PROFILER_TRACEBOX.override(true)
    assertTrue(IntellijProfilerServices.FeatureConfigProd().isTraceboxEnabled)
  }

  @Test
  fun featureFlagConfigTraceBoxDisabled() {
    StudioFlags.PROFILER_TRACEBOX.override(false)
    assertFalse(IntellijProfilerServices.FeatureConfigProd().isTraceboxEnabled)
  }

  @Test
  fun featureFlagConfigLeakCanaryEnabled() {
    StudioFlags.PROFILER_LEAKCANARY.override(true)
    assertTrue(IntellijProfilerServices.FeatureConfigProd().isLeakCanaryEnabled)
  }

  @Test
  fun featureFlagConfigLeakCanaryDisabled() {
    StudioFlags.PROFILER_LEAKCANARY.override(false)
    assertFalse(IntellijProfilerServices.FeatureConfigProd().isLeakCanaryEnabled)
  }

  @Test
  fun featureFlagSystemTraceInEditorDisabled() {
    StudioFlags.PROFILER_SYSTEM_TRACE_IN_EDITOR.override(false)
    assertFalse(IntellijProfilerServices.FeatureConfigProd().isSystemTraceInEditorEnabled)
  }

  @Test
  fun featureFlagMethodTraceInEditorDisabled() {
    StudioFlags.PROFILER_METHOD_TRACE_IN_EDITOR.override(false)
    assertFalse(IntellijProfilerServices.FeatureConfigProd().isMethodTraceInEditorEnabled)
  }

  @Test
  fun testGetTaskCpuProfilerConfigs() {
    val result = intellijProfilerServices.getTaskCpuProfilerConfigs(8)
    assertThat(result.size).isEqualTo(4)
    assertThat(result[0].name).isEqualTo("Callstack Sample")
    assertThat(result[1].name).isEqualTo("Java/Kotlin Method Trace")
    assertThat(result[2].name).isEqualTo("Native Allocations")
    assertThat(result[3].name).isEqualTo("System Trace")
  }

  @Test
  fun testGetTaskCpuProfilerConfigsWhenProjectStateChanged() {
    val result = intellijProfilerServices.getTaskCpuProfilerConfigs(9)
    assertThat(result.size).isEqualTo(4)
    assertThat(result[0].name).isEqualTo("Callstack Sample")
    assertThat(result[1].name).isEqualTo("Java/Kotlin Method Trace")
    assertThat(result[2].name).isEqualTo("Native Allocations")
    assertThat(result[3].name).isEqualTo("System Trace")

    val configsToSave: ArrayList<CpuProfilerConfig> = ArrayList()
    configsToSave.add(CpuProfilerConfig("HelloTest1", CpuProfilerConfig.Technology.INSTRUMENTED_JAVA))
    configsToSave.add(CpuProfilerConfig("HelloTest2", CpuProfilerConfig.Technology.SAMPLED_NATIVE))
    // Update configs
    CpuProfilerConfigsState.getInstance(project).taskConfigs = configsToSave

    // Updated config should be reflected
    val resultNew = intellijProfilerServices.getTaskCpuProfilerConfigs(9)
    assertThat(resultNew.size).isEqualTo(2)
    assertThat(resultNew[0].name).isEqualTo("HelloTest1")
    assertThat(resultNew[1].name).isEqualTo("HelloTest2")
  }

  @Test
  fun testGetNativeMemorySamplingRateForCurrentConfigForTaskBased() {
    StudioFlags.PROFILER_TASK_BASED_UX.override(true)
    project = Mockito.spy(MockProjectEx(disposableRule.disposable))
    mockProjectAttributes(project)
    val intellijProfilerServicesNow = IntellijProfilerServices(project, Mockito.mock(SymbolFilesLocator::class.java))
    Disposer.register(disposableRule.disposable, intellijProfilerServicesNow)
    try {
      val result = intellijProfilerServicesNow.getTaskCpuProfilerConfigs(9)
      assertThat(result.size).isEqualTo(4)
      assertThat(result[0].name).isEqualTo("Callstack Sample")
      assertThat(result[1].name).isEqualTo("Java/Kotlin Method Trace")
      assertThat(result[2].name).isEqualTo("Native Allocations")
      assertThat(result[3].name).isEqualTo("System Trace")

      // 2048 is default samplingRateBytes value for native memory
      assertEquals(intellijProfilerServicesNow.nativeAllocationsMemorySamplingRate, 2048)
    } finally {
      Disposer.dispose(intellijProfilerServicesNow)
    }
  }

  @Test
  fun testIsTaskSupportedOnStartup() {
    // The following tasks are supported on startup.
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.NATIVE_ALLOCATIONS)).isTrue()
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.CALLSTACK_SAMPLE)).isTrue()
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.JAVA_KOTLIN_METHOD_RECORDING)).isTrue()
    // The following tasks are NOT supported on startup.
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.HEAP_DUMP)).isFalse()
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.JAVA_KOTLIN_ALLOCATIONS)).isFalse()
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.LIVE_VIEW)).isFalse()
    assertThat(intellijProfilerServices.isTaskSupportedOnStartup(ProfilerTaskType.UNSPECIFIED)).isFalse()
  }

  @Test
  fun testAddDependencyDoesNothingIfDependencyAlreadyExists() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(true)

    mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    verify(mocks.services, never()).showConfirmationDialog(any(), any(), any(), anyOrNull())
    verify(mocks.registeringModuleSystem, never()).registerDependency(any<GoogleMavenArtifactId>(), any())
  }

  @Test
  fun testAddDependencyAbortsForLeakCanaryIfVersionResolutionReturnsNull() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    whenever(mocks.registeringModuleSystem.getRegisteredDependencyId(artifact)).thenReturn(unresolvedId)
    whenever(mocks.registeringModuleSystem.analyzeDependencyCompatibility(any())).thenReturn(null)

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
  }

  @Test
  fun testAddDependencyAddsDependencyAndSyncsIfDialogConfirmed() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(true).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())
    whenever(mocks.syncManager.requestSyncProject(any())).thenReturn(mock())

    // Mock background resolution to a pinned stable version.
    val unresolvedId = FakeDependencyId("$artifact:+")
    val resolvedId = FakeDependencyId("$artifact:1.0.0")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult(unresolvedId to resolvedId)))

    mocks.services.addDependency(artifact, DependencyType.IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    verify(mocks.services).showConfirmationDialog(any(), eq(artifact), eq(DependencyType.IMPLEMENTATION), eq("$artifact:1.0.0"))
    verify(mocks.registeringModuleSystem).registerDependency(eq(resolvedId), eq(DependencyType.IMPLEMENTATION))
    verify(mocks.syncManager).requestSyncProject(ProjectSystemSyncManager.SyncReason.PROJECT_MODIFIED)
  }

  @Test
  fun testAddDependencyAbortsForLeakCanaryIfResolvedToDynamicVersion() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(true).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    val resolvedId = FakeDependencyId("$artifact:+")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult(unresolvedId to resolvedId)))

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
  }

  @Test
  fun testAddDependencyAbortsForLeakCanaryIfResolvedToPreviewVersion() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(true).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    val resolvedId = FakeDependencyId("$artifact:1.1.0-alpha01")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult(unresolvedId to resolvedId)))

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
  }

  @Test
  fun testAddDependencyAbortsForLeakCanaryIfArtifactNotResolved() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(true).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    // The result has no version for the dependency (e.g. not found).
    val unresolvedId = FakeDependencyId("$artifact:+")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult()))

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
  }

  @Test
  fun testAddDependencyAbortsForLeakCanaryIfVersionResolutionFails() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(true).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFailedFuture(RuntimeException("Network failure")))

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
  }

  @Test
  fun testAddDependencyKeepsExistingBehaviorForNonLeakCanaryArtifact() {
    // The stable version check is only for LeakCanary. Other artifacts keep the '+' fallback.
    val artifact = GoogleMavenArtifactId.ANDROIDX_APPCOMPAT
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    whenever(mocks.registeringModuleSystem.getRegisteredDependencyId(artifact)).thenReturn(unresolvedId)
    whenever(mocks.registeringModuleSystem.analyzeDependencyCompatibility(any())).thenReturn(null)

    mocks.services.addDependency(artifact, DependencyType.IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    verify(mocks.services).showConfirmationDialog(any(), eq(artifact), eq(DependencyType.IMPLEMENTATION), eq("$artifact:+"))
    verify(mocks.registeringModuleSystem, never()).registerDependency(any<RegisteredDependencyId>(), any())
  }

  @Test
  fun testAddDependencyDoesNotCancelOrCacheFailedCompatibilityAnalysis() {
    // On failure we stop waiting, never cancel the lookup, and cache nothing.
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)

    val unresolvedId = FakeDependencyId("$artifact:+")
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    lookup.setException(RuntimeException("Network failure"))
    mockLeakCanaryResolution(mocks, unresolvedId, lookup)

    val future = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertLeakCanaryInjectionAborted(mocks, future)
    assertThat(lookup.isCancelled).isFalse()
    assertThat(mocks.services.dependencyVersionResolver.getCached(leakCanaryKey())).isNull()
  }

  @Test
  fun testTimeoutAbortsWithoutCancelingAndRetryUsesLateResult() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks(DependencyVersionResolver(System::nanoTime, 50))
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    val lookup = SettableFuture.create<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>()
    mockLeakCanaryResolution(mocks, unresolvedId, lookup)

    // The lookup doesn't finish in time: the injection is aborted, but the lookup keeps running.
    val first = mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    assertLeakCanaryInjectionAborted(mocks, first)
    assertThat(lookup.isCancelled).isFalse()

    // It finishes later with nobody waiting. The retry uses its result, no new lookup.
    lookup.set(compatibilityResult(unresolvedId to FakeDependencyId("$artifact:1.0.0")))
    mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    verify(mocks.registeringModuleSystem, times(1)).analyzeDependencyCompatibility(any())
    verify(mocks.services).showConfirmationDialog(any(), eq(artifact), eq(DependencyType.DEBUG_IMPLEMENTATION), eq("$artifact:1.0.0"))
  }

  @Test
  fun testAddDependencyReusesRecentlyResolvedVersion() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    val resolvedId = FakeDependencyId("$artifact:1.0.0")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult(unresolvedId to resolvedId)))

    repeat(2) {
      mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
      ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    }

    // The second try uses the cached version, no new lookup.
    verify(mocks.registeringModuleSystem, times(1)).analyzeDependencyCompatibility(any())
    verify(mocks.services, times(2))
      .showConfirmationDialog(any(), eq(artifact), eq(DependencyType.DEBUG_IMPLEMENTATION), eq("$artifact:1.0.0"))
  }

  @Test
  fun testAddDependencyLooksUpVersionAgainAfterCacheExpires() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    var nowNs = 1_000L
    val mocks = setupDependencyMocks(DependencyVersionResolver(LongSupplier { nowNs }, TimeUnit.SECONDS.toMillis(20)))
    whenever(mocks.androidModuleSystem.hasResolvedDependency(any())).thenReturn(false)
    doReturn(false).whenever(mocks.services).showConfirmationDialog(any(), any(), any(), anyOrNull())

    val unresolvedId = FakeDependencyId("$artifact:+")
    val resolvedId = FakeDependencyId("$artifact:1.0.0")
    mockLeakCanaryResolution(mocks, unresolvedId, Futures.immediateFuture(compatibilityResult(unresolvedId to resolvedId)))

    mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    nowNs += DependencyVersionResolver.CACHE_TTL_NS + 1
    mocks.services.addDependency(artifact, DependencyType.DEBUG_IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    verify(mocks.registeringModuleSystem, times(2)).analyzeDependencyCompatibility(any())
  }

  @Test
  fun testDisposeDisposesDependencyVersionResolver() {
    val resolver = DependencyVersionResolver()
    val services = IntellijProfilerServices(project, mock<SymbolFilesLocator>(), resolver)
    assertThat(resolver.cache(leakCanaryKey(), FakeDependencyId("${GoogleMavenArtifactId.LEAKCANARY}:1.0.0"))).isTrue()

    Disposer.dispose(services)

    assertThat(resolver.getCached(leakCanaryKey())).isNull()
  }

  @Test
  fun testAddDependencyReturnsFalseIfModuleIsNull() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.configurationModule.module).thenReturn(null)

    val future = mocks.services.addDependency(artifact, DependencyType.IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertThat(future.get()).isFalse()
    verify(mocks.services, never()).showConfirmationDialog(any(), any(), any(), anyOrNull())
  }

  @Test
  fun testAddDependencyReturnsFalseIfRegisteringModuleSystemIsNull() {
    val artifact = GoogleMavenArtifactId.LEAKCANARY
    val mocks = setupDependencyMocks()
    whenever(mocks.androidModuleSystem.getRegisteringModuleSystem()).thenReturn(null)

    val future = mocks.services.addDependency(artifact, DependencyType.IMPLEMENTATION)
    ApplicationManager.getApplication().invokeAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    assertThat(future.get()).isFalse()
    verify(mocks.services, never()).showConfirmationDialog(any(), any(), any(), anyOrNull())
  }

  private data class DependencyMocks(
    val services: IntellijProfilerServices,
    val androidModuleSystem: AndroidModuleSystem,
    val registeringModuleSystem: RegisteringModuleSystem<RegisteredDependencyQueryId, RegisteredDependencyId>,
    val syncManager: ProjectSystemSyncManager,
    val runManager: RunManager,
    val configurationModule: AndroidRunConfigurationModule,
  )

  private fun setupDependencyMocks(resolver: DependencyVersionResolver = DependencyVersionResolver()): DependencyMocks {
    val projectSystemService = mock<ProjectSystemService>()
    val androidProjectSystem = mock<AndroidProjectSystem>()
    val androidModuleSystem = mock<AndroidModuleSystem>()
    val registeringModuleSystem = mock<RegisteringModuleSystem<RegisteredDependencyQueryId, RegisteredDependencyId>>()
    val syncManager = mock<ProjectSystemSyncManager>()
    val runManager = mock<RunManager>()
    val configurationSettings = mock<RunnerAndConfigurationSettings>()
    val androidConfiguration = mock<AndroidRunConfigurationBase>()
    val configurationModule = mock<AndroidRunConfigurationModule>()
    val module = mock<Module>()
    val featureTracker = mock<com.android.tools.profilers.analytics.FeatureTracker>()

    // Register services on the project so static helpers like ProjectSystemUtil can find them
    (project as MockProjectEx).registerService(ProjectSystemService::class.java, projectSystemService)
    (project as MockProjectEx).registerService(RunManager::class.java, runManager)

    whenever(projectSystemService.projectSystem).thenReturn(androidProjectSystem)
    whenever(androidProjectSystem.getModuleSystem(any())).thenReturn(androidModuleSystem)
    whenever(androidProjectSystem.getSyncManager()).thenReturn(syncManager)
    whenever(androidModuleSystem.getRegisteringModuleSystem()).thenReturn(registeringModuleSystem)

    whenever(runManager.selectedConfiguration).thenReturn(configurationSettings)
    whenever(configurationSettings.configuration).thenReturn(androidConfiguration)
    whenever(androidConfiguration.configurationModule).thenReturn(configurationModule)
    whenever(configurationModule.module).thenReturn(module)
    whenever(module.name).thenReturn("app")
    whenever(module.project).thenReturn(project)

    // Re-create services as a spy for this test
    val actualInstance = IntellijProfilerServices(project, mock<SymbolFilesLocator>(), resolver)
    Disposer.register(disposableRule.disposable, actualInstance)
    val servicesSpy = spy(actualInstance)
    doReturn(featureTracker).whenever(servicesSpy).featureTracker
    spiesToDispose.add(actualInstance)

    return DependencyMocks(servicesSpy, androidModuleSystem, registeringModuleSystem, syncManager, runManager, configurationModule)
  }

  /** A [RegisteredDependencyId] whose [toString] returns a Gradle coordinate, mirroring `GradleRegisteredDependencyId`. */
  private class FakeDependencyId(private val coordinate: String) : RegisteredDependencyId {
    override fun toString(): String = coordinate
  }

  /** The lookup key for LeakCanary in the "app" module used by [setupDependencyMocks]. */
  private fun leakCanaryKey() = DependencyVersionResolver.Key("app", GoogleMavenArtifactId.LEAKCANARY)

  private fun compatibilityResult(
    vararg compatible: Pair<RegisteredDependencyId, RegisteredDependencyId>
  ): RegisteredDependencyCompatibilityResult<RegisteredDependencyId> =
    RegisteredDependencyCompatibilityResult(compatible = mapOf(*compatible), incompatible = listOf(), warning = "")

  private fun mockLeakCanaryResolution(
    mocks: DependencyMocks,
    unresolvedId: RegisteredDependencyId,
    result: ListenableFuture<RegisteredDependencyCompatibilityResult<RegisteredDependencyId>>,
  ) {
    whenever(mocks.registeringModuleSystem.getRegisteredDependencyId(GoogleMavenArtifactId.LEAKCANARY)).thenReturn(unresolvedId)
    whenever(mocks.registeringModuleSystem.analyzeDependencyCompatibility(listOf(unresolvedId))).thenReturn(result)
  }

  private fun assertLeakCanaryInjectionAborted(mocks: DependencyMocks, future: CompletableFuture<Boolean>) {
    assertThat(future.get()).isFalse()
    verify(mocks.services, never()).showConfirmationDialog(any(), any(), any(), anyOrNull())
    verify(mocks.registeringModuleSystem, never()).registerDependency(any<RegisteredDependencyId>(), any())
    verify(project.getService(AndroidNotification::class.java))
      .showBalloon(
        eq("LeakCanary"),
        eq(IntellijProfilerServices.LEAKCANARY_VERSION_NOT_RESOLVED_MESSAGE),
        eq(NotificationType.WARNING),
      )
  }

  private fun mockProjectAttributes(project: Project) {
    val moduleManager = EmptyModuleManager(project)
    val psiManger = MockPsiManager(project)
    val cpuProfilerStateSpy = Mockito.spy(CpuProfilerConfigsState())
    val androidNotification = mock<AndroidNotification>()
    whenever(project.getService(CpuProfilerConfigsState::class.java)).thenReturn(cpuProfilerStateSpy)
    whenever(project.getService(ModuleManager::class.java)).thenReturn(moduleManager)
    whenever(project.getService(PsiManager::class.java)).thenReturn(psiManger)
    whenever(project.getService(AndroidNotification::class.java)).thenReturn(androidNotification)
  }

  @Test
  fun saveFileShowsSuccessNotification() {
    val androidNotification = project.getService(AndroidNotification::class.java)
    val tempFile = File.createTempFile("test_recording", ".trace")
    tempFile.deleteOnExit()

    intellijProfilerServices.saveFile(
      tempFile,
      { fos -> fos.write("test_data".toByteArray()) },
      null,
    )

    assertTrue(tempFile.exists())
    assertEquals("test_data", tempFile.readText())
    if (RevealFileAction.isSupported()) {
      verify(androidNotification)
        .showBalloon(
          eq("Export Successful"),
          eq("Exported recording to ${tempFile.name}"),
          eq(NotificationType.INFORMATION),
          eq(AndroidNotification.BALLOON_GROUP),
          eq(true),
          any<NotificationHyperlink>(),
        )
    } else {
      verify(androidNotification)
        .showBalloon(
          eq("Export Successful"),
          eq("Exported recording to ${tempFile.name}"),
          eq(NotificationType.INFORMATION),
          eq(AndroidNotification.BALLOON_GROUP),
        )
    }
  }

  @Test
  fun saveFileShowsErrorNotificationOnFailure() {
    val androidNotification = project.getService(AndroidNotification::class.java)
    val parentAsFile = File.createTempFile("fake_dir", ".tmp")
    parentAsFile.deleteOnExit()
    val uncreatableDir = File(parentAsFile, "uncreatable_dir")
    val nonExistentDirFile = File(uncreatableDir, "test.trace")

    intellijProfilerServices.saveFile(
      nonExistentDirFile,
      { fos -> fos.write("data".toByteArray()) },
      null,
    )

    verify(androidNotification)
      .showBalloon(
        eq("Export Failed"),
        eq("Could not create directory: ${uncreatableDir.path}"),
        eq(NotificationType.ERROR),
        eq(AndroidNotification.BALLOON_GROUP),
      )
  }

  @Test
  fun revealHyperlinkUsesStableIdentifier() {
    val tempFile = File.createTempFile("test recording & more", ".trace")
    tempFile.deleteOnExit()

    val hyperlink = intellijProfilerServices.createRevealHyperlink(tempFile)

    // The id is embedded into an HTML href and compared back verbatim, so it must not depend on the file path.
    assertEquals(IntellijProfilerServices.REVEAL_FILE_HYPERLINK_ID, hyperlink.url)
    assertThat(hyperlink.toHtml()).contains(RevealFileAction.getActionName())
  }

  @Test
  fun saveFileShowsErrorNotificationOnWriteFailure() {
    val androidNotification = project.getService(AndroidNotification::class.java)
    val tempFile = File.createTempFile("test_recording", ".trace")
    tempFile.deleteOnExit()

    intellijProfilerServices.saveFile(tempFile, { throw IOException("disk full") }, null)

    // Opening the stream truncates the file, so a failed write must not leave an empty one behind.
    assertFalse(tempFile.exists())
    verify(androidNotification)
      .showBalloon(
        eq("Export Failed"),
        eq("Failed to export recording to ${tempFile.name}: disk full"),
        eq(NotificationType.ERROR),
        eq(AndroidNotification.BALLOON_GROUP),
      )
  }

  @Test
  fun saveFileRethrowsCancellationWithoutNotification() {
    val androidNotification = project.getService(AndroidNotification::class.java)
    val tempFile = File.createTempFile("test_recording", ".trace")
    tempFile.deleteOnExit()

    assertFailsWith<ProcessCanceledException> {
      intellijProfilerServices.saveFile(tempFile, { throw ProcessCanceledException() }, null)
    }

    // A cancelled export is not a failure, so no balloon should be shown.
    verifyNoInteractions(androidNotification)
  }

  @Test
  fun testGetProguardMappingForAppReturnsNullWhenNotDeployed() {
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isNull()
  }

  @Test
  fun testGetProguardMappingForAppUsesDeployedVariantMappingRegardlessOfDropdownChanges() {
    val mappingPath = temporaryFolder.newFile("mapping.txt").absolutePath
    val mockLocator = mock<MappingFilesLocator>()
    val sessionInfo = createSessionInfo("device-1")

    // 1. Deploy "debug" (unobfuscated) -> switching dropdown to "release" without redeploying still returns null
    whenever(mockLocator.getMappings()).thenReturn(emptyMap())
    ProfilerExecutionListener.recordDeployment(project, sessionInfo, mockLocator)
    whenever(mockLocator.getMappings()).thenReturn(mapOf("com.example.app" to mappingPath))
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isNull()

    // 2. Deploy "release" (obfuscated) -> switching dropdown to "debug" without redeploying still returns "release" mapping
    ProfilerExecutionListener.recordDeployment(project, sessionInfo, mockLocator)
    whenever(mockLocator.getMappings()).thenReturn(emptyMap())
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isEqualTo(mappingPath)

    // 3. Redeploy "debug" (unobfuscated) -> overwrites record to null
    ProfilerExecutionListener.recordDeployment(project, sessionInfo, mockLocator)
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isNull()
  }

  @Test
  fun testGetProguardMappingForAppReturnsNullWhenMappingModifiedOrDeletedAfterDeploy() {
    val mappingFile = temporaryFolder.newFile("mapping.txt")
    val mappingPath = mappingFile.absolutePath
    val mockLocator = mock<MappingFilesLocator>()
    whenever(mockLocator.getMappings()).thenReturn(mapOf("com.example.app" to mappingPath))
    val sessionInfo = createSessionInfo("device-1")

    // Rebuild without redeploying (mapping.txt modified on disk) -> returns null
    ProfilerExecutionListener.recordDeployment(project, sessionInfo, mockLocator)
    mappingFile.setLastModified(mappingFile.lastModified() + 10_000L)
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isNull()

    // Redeploy with updated mapping, then delete mapping.txt from disk -> returns null
    ProfilerExecutionListener.recordDeployment(project, sessionInfo, mockLocator)
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isEqualTo(mappingPath)
    assertTrue(mappingFile.delete())
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isNull()
  }

  @Test
  fun testGetProguardMappingForAppRecordsMultiDeviceDeployViaProcessStarted() {
    val mappingPath = temporaryFolder.newFile("mapping.txt").absolutePath
    val mockLocator = mock<MappingFilesLocator>()
    whenever(mockLocator.getMappings()).thenReturn(mapOf("com.example.app" to mappingPath))
    val handler = NopProcessHandler()
    createSessionInfo("device-1", "device-2", handler = handler)
    val env = mock<ExecutionEnvironment>()
    whenever(env.project).thenReturn(project)

    ProfilerExecutionListener({ mockLocator }, MoreExecutors.directExecutor()).processStarted("Run", env, handler)

    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.example.app")).isEqualTo(mappingPath)
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-2", "com.example.app")).isEqualTo(mappingPath)
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-3", "com.example.app")).isNull()
    assertThat(intellijProfilerServices.getProguardMappingForApp("device-1", "com.other.app")).isNull()
  }

  private fun createSessionInfo(
    vararg deviceSerials: String,
    handler: NopProcessHandler = NopProcessHandler(),
  ): AndroidSessionInfo {
    val devices = deviceSerials.map { serial ->
      mock<IDevice>().also { whenever(it.serialNumber).thenReturn(serial) }
    }
    return AndroidSessionInfo.create(handler, devices, "com.example.app")
  }
}
